"""v1.135 preserved-result review. No server, DB, Gradle or application calls."""
import ast
import collections
import hashlib
import io
import json
from pathlib import Path
import re
import runpy
import subprocess
import sys
import warnings
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
HEAD = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip()
assert HEAD.startswith('a762b8cb'), HEAD
CANDIDATE = '0d142aa213bb7049fa942e73aa4b652a35e2ed90'
BASE = '1899b49f633f8aefdc66b15c42debdaba8b3852c'


def sha(b):
    return hashlib.sha256(b).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def manifest(folder):
    base = ROOT / 'docs/evidence' / folder
    entries = []
    for line in (base / 'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip():
            continue
        m = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        assert m, (folder, line)
        expected, name = m.groups()
        p = base / name
        assert sha(p.read_bytes()) == expected, p
        entries.append((p.relative_to(ROOT).as_posix(), expected))
    data = subprocess.check_output(['git', 'cat-file', '--batch'],
        input=''.join(f'{HEAD}:{p}\n' for p, _ in entries).encode(), cwd=ROOT)
    offset = 0
    missing = []
    for path, expected in entries:
        end = data.index(b'\n', offset)
        header = data[offset:end].split()
        if header[-1] == b'missing':
            missing.append(path)
            offset = end + 1
            continue
        assert len(header) == 3 and header[1] == b'blob', (path, header)
        size = int(header[2])
        assert sha(data[end + 1:end + 1 + size]) == expected, path
        offset = end + size + 2
    assert offset == len(data)
    return {'local': len(entries), 'committed_match': len(entries) - len(missing), 'missing': missing}


folders = ['weekend-review-dbac6053', 'v1134-harness-judge',
           'o1o3-candidate-1899b49', 'w4-n06-prep', 'k26-result-1899b49',
           'n03-1107-1000', 'n03-1107-2200', 'n03-1108-1000', 'n03-1108-2200']
result = {'review_head': HEAD, 'runtime': BASE, 'candidate': CANDIDATE,
          'evidence': {name: manifest(name) for name in folders}}
assert all(not value['missing'] for value in result['evidence'].values())

data = ROOT / 'docs/evidence/o1o3-candidate-1899b49'


def xml_cases(run):
    path = data / 'runs' / run / 'xml'
    cases = {}
    for p in sorted(path.rglob('*.xml')):
        tree = ET.parse(p)
        for case in tree.getroot().iter('testcase'):
            pair = (case.attrib.get('classname'), case.attrib['name'])
            # Parameterized methods can have the same display name in JUnit XML.
            ordinal = sum(k[:2] == pair for k in cases)
            key = (*pair, ordinal)
            state = 'FAIL' if case.find('failure') is not None or case.find('error') is not None else (
                'SKIP' if case.find('skipped') is not None else 'PASS')
            cases[key] = state
    return cases


runs = ['r2-fail-first-1899b49', 'r3-candidate-related', 'r4-o2-link',
        'r5-candidate-full-b9f16a6', 'r6-base-full-1899b49',
        'r10-n15pg-base', 'r11-n15pg-candidate', 'r12-n15pg-noenv-candidate']
cases = {name: xml_cases(name) for name in runs}
result['tests'] = {name: {'total': len(value), **dict(collections.Counter(value.values()))}
                   for name, value in cases.items()}
candidate_full = cases['r5-candidate-full-b9f16a6']
base_full = cases['r6-base-full-1899b49']
common = set(candidate_full) & set(base_full)
assert all(candidate_full[k] == base_full[k] for k in common)
result['full_comparison'] = {'common_tests': len(common), 'changed_results': 0,
    'new_tests': len(set(candidate_full) - set(base_full)),
    'candidate_failures': [f'{k[0]}#{k[1]}' for k, value in candidate_full.items() if value == 'FAIL']}
assert len(result['full_comparison']['candidate_failures']) == 1
assert all(cases['r11-n15pg-candidate'][k] == 'PASS' for k in cases['r11-n15pg-candidate'])

for run, filename in [('r2-fail-first-1899b49', 'O1O3LogContractTest.java'),
                      ('r3-candidate-related', 'O1O3LogContractTest.java'),
                      ('r4-o2-link', 'PoolLossLimitTest.java')]:
    files = list((data / 'runs' / run / 'src').rglob(filename))
    assert len(files) == 1, (run, files)
    committed = git('show', f'{CANDIDATE}:web-api/src/test/java/com/cryptoautotrader/api/service/{filename}')
    assert files[0].read_bytes().replace(b'\r\n', b'\n') == committed.replace(b'\r\n', b'\n')

# r11/r12 preceded the final test-only commit; verify the actual preserved test bytes.
pg_path = 'web-api/src/test/java/com/cryptoautotrader/api/service/N15PgLockWaitExpiryTest.java'
pg_blob = git('show', f'{CANDIDATE}:{pg_path}')
normalized = lambda b: b.replace(b'\r\n', b'\n')
pg_provenance = {}
for run in ('r10-n15pg-base', 'r11-n15pg-candidate', 'r12-n15pg-noenv-candidate'):
    path = data / 'runs' / run
    files = list((path / 'src').rglob('N15PgLockWaitExpiryTest.java'))
    assert len(files) == 1, (run, files)
    assert normalized(files[0].read_bytes()) == normalized(pg_blob)
    pg_provenance[run] = {'actual_head': (path / 'head.txt').read_text().strip(),
        'status': (path / 'status.txt').read_text().strip(),
        'test_source_matches_final_commit_after_crlf_normalization': True}
result['pg_provenance'] = pg_provenance
assert git('diff', '--name-only', 'b9f16a6', CANDIDATE).decode().splitlines() == [pg_path]
assert git('diff', '--name-only', BASE, CANDIDATE, '--', 'web-api/src/main').decode().splitlines() == [
    'web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java',
    'web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java']

# Pure harness test, no application import.
unit = subprocess.run([sys.executable, '-B', '-X', 'utf8', 'scripts/restart-test/test_judge.py'],
                      cwd=ROOT, capture_output=True)
(OUT / 'judge-35.txt').write_bytes(unit.stdout + unit.stderr)
assert unit.returncode == 0 and '결과 35/35' in unit.stdout.decode('utf-8')
judge = runpy.run_path(str(ROOT / 'scripts/restart-test/judge.py'))
original = (ROOT / 'docs/evidence/w3-pg-rehearsal-1899b49/run5/F5-3-w4exec.out').read_text(encoding='utf-8')
f3 = 'bb86fb397da00523f564a8f02a1960c98bb1ac03887629fe74372990929fa2bb'
assert '거래소 BTC 증감 0 ·' in original
bad_btc = original.replace('거래소 BTC 증감 0 ·', '거래소 BTC 증감 0.0016 ·')
assert judge['f53_ok'](0, original, f3)
assert judge['f53_ok'](0, bad_btc, f3)  # actual current false positive
(OUT / 'f53-nonzero-btc.input.txt').write_text(bad_btc, encoding='utf-8')
result['harness'] = {'existing_local_tests': '35/35',
    'additional_f53_zero_control': True, 'nonzero_00016_btc_false_positive_reproduced': True,
    'old_run5_actual_btc_delta_still_zero': True}

# Current approved comparator, read only its declarations, not its argv/main body.
comparator = ROOT / 'docs/evidence/k23-prep-51dcee5/jars/jarcmp.py'
tree = ast.parse(comparator.read_text(encoding='utf-8'))
declarations = [n for n in tree.body if isinstance(n, (ast.Import, ast.ImportFrom, ast.FunctionDef))
                or isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'OWN' for t in n.targets)]
namespace = {}
exec(compile(ast.Module(body=declarations, type_ignores=[]), '<comparator-declarations>', 'exec'), namespace)
entries = namespace['entries']
draft_u1 = (data / 'w6/blocks-d1/U1.sh').read_text(encoding='utf-8')
embedded = draft_u1.split("<<'EOF' | tee", 1)[1].split('\n', 1)[1].split('\nEOF\n', 1)[0]
embedded_entries = next(n for n in ast.parse(embedded).body if isinstance(n, ast.FunctionDef) and n.name == 'entries')
source_entries = next(n for n in declarations if isinstance(n, ast.FunctionDef) and n.name == 'entries')
assert ast.dump(embedded_entries) == ast.dump(source_entries)
fixtures = OUT / 'fixtures'
fixtures.mkdir(exist_ok=True)


def fixture(path, duplicate_inner=False, duplicate_outer=False):
    inner = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)  # deliberate duplicate fixture
        with zipfile.ZipFile(inner, 'w') as z:
            if duplicate_inner:
                z.writestr('p/A.class', b'earlier-different-content')
            z.writestr('p/A.class', b'expected-content')
        with zipfile.ZipFile(path, 'w') as z:
            if duplicate_outer:
                z.writestr('load.txt', b'earlier-different-content')
            z.writestr('load.txt', b'expected-content')
            z.writestr('BOOT-INF/lib/core-engine-1.0-SNAPSHOT.jar', inner.getvalue())


paths = [fixtures / f'{name}.jar.bin' for name in ('base', 'inner-duplicate', 'outer-duplicate')]
fixture(paths[0])
fixture(paths[1], duplicate_inner=True)
fixture(paths[2], duplicate_outer=True)
assert entries(paths[0]) == entries(paths[1])
assert entries(paths[0]) != entries(paths[2])
result['comparator_reproduction'] = {'baseline_and_inner_duplicate_compare_equal': True,
    'outer_duplicate_detected_control': True, 'fixture_does_not_represent_operating_jar': True,
    'draft_U1_uses_same_entries_function': True}

# Verify preserved real jars; do not rebuild.
jars = [Path('D:/tmp/o1o3/jars/1899b49.jar'), Path('D:/tmp/o1o3/jars/0d142aa.jar')]
expected_jars = ['a4fd4e343e6c48d7c5aa4a844f33c431eed10eff0757681d7c3bf262d554bdac',
                 'dc26fe4b2d0ee9772be8143f05fa50c928ed0a4f28e1522e4efeaa0ba99f4dd0']
for p, expected in zip(jars, expected_jars):
    assert sha(p.read_bytes()) == expected
    with zipfile.ZipFile(p) as outer:
        assert len(outer.namelist()) == len(set(outer.namelist()))
        for name in outer.namelist():
            if namespace['OWN'].match(name):
                with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
                    assert len(inner.namelist()) == len(set(inner.namelist()))
a, b = map(entries, jars)
diff = [('추가' if k not in a else '삭제' if k not in b else '변경') + ' ' + k
        for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k)]
assert len(diff) == 3 and len(a) == len(b) == 881
result['actual_jar'] = {'sha256': expected_jars, 'diff': diff, 'counts': [len(a), len(b)],
                       'duplicates_in_outer_or_own_modules': 0}

# Do syntax-only validation of draft blocks; no shell block is executed.
draft = data / 'w6/blocks-d1'
shell = 'C:/Program Files/Git/bin/bash.exe'
syntax = []
for p in sorted(draft.glob('*.sh')):
    r = subprocess.run([shell, '--noprofile', '--norc', '-n', str(p)], capture_output=True)
    assert r.returncode == 0, (p, r.stderr)
    syntax.append(p.name)
assert len(syntax) == 8
result['draft'] = {'syntax_pass': syntax, 'full_standin_not_run_by_reviewer': True, 'production_authorized': False}

reports = {}
for day, hour in [('1107', '1000'), ('1107', '2200'), ('1108', '1000'), ('1108', '2200')]:
    folder = ROOT / f'docs/evidence/n03-{day}-{hour}'
    name = f'2026{day}-{hour}.txt'
    report = (folder / name).read_text(encoding='utf-8')
    install = (folder / 'install_state.txt').read_text(encoding='utf-8')
    assert '🟢 점검 항목 정상' in report
    assert '426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1' in install
    assert BASE in install and 'f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b' in install
    assert 'SID 203: CREATED' in report and 'B1 true' in report and '20/20' in report
    server = (folder / 'server-files.txt').read_text(encoding='utf-8')
    for filename in (name, name + '.msg', name + '.raw', 'last_send'):
        expected = re.search(r'^([0-9a-f]{64})  ' + re.escape(filename) + r'$', server, re.M).group(1)
        assert sha((folder / filename).read_bytes()) == expected, filename
    reports[f'{day}-{hour}'] = {'report_sha256': sha((folder / name).read_bytes()),
        'header_green': True, 'server_log_full_copy_recounted': False,
        'phone_received': 'unconfirmed'}
result['reports'] = reports
result['protected_before'] = {name: sha((ROOT / 'docs' / name).read_bytes())
    for name in ('LIMITED_LIVE_TEST_PLAN.md', 'OPS_DAILY_CHECK.md', 'LIVE_ENTRY_PREREG.md')}
result['operations'] = {'server': False, 'database': False, 'docker': False, 'gradle': False,
                         'jar_build': False, 'product_tests': False, 'commit_push': False}
(OUT / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: v for k, v in result.items() if k not in ('evidence', 'pg_provenance')}, ensure_ascii=False, indent=2))
