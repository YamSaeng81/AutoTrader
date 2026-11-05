"""Review preserved evidence and fixed Git objects; no build, tests, DB or server."""
from pathlib import Path
import collections
import hashlib
import io
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
DEV = ROOT / 'docs/evidence/k23-final-1103f48'
PREP = ROOT / 'docs/evidence/k23-prep-51dcee5'
SCRATCH = ROOT / 'core-engine/build/review-k23-86c41e1'
SCRATCH.mkdir(parents=True, exist_ok=True)
REF = '86c41e11f70c6cc44ccabe8e9bed5b2a5275d21b'
BASE = 'fe01207f3f60e97e308925157f84a1bb2da04388'
DRAFT = '51dcee530721c0ab23f3f6f875a15b9416b751a3'
FINAL = '1103f48505a1457029f6555547a87aa986535738'
TEST = 'web-api/src/test/java/com/cryptoautotrader/api/service/DailySma20LiveOrderPathTest.java'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def source(ref, path):
    return git('show', f'{ref}:{path}').decode('utf-8')

def blocks(text):
    return re.findall(r'^```bash\n(.*?)^```', text, re.M | re.S)

def manifest(name, count):
    folder = ROOT / 'docs/evidence' / name
    rows = (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines()
    assert len(rows) == count
    for row in rows:
        want, filename = row.split(' ', 1)
        p = folder / filename.strip().lstrip('*')
        assert sha(p.read_bytes()) == want, p
        assert sha(git('show', f'{REF}:{p.relative_to(ROOT).as_posix()}')) == want, p
    return {'raw_and_committed_blobs_match': count}

def suites(folder):
    rows, names = [], []
    for p in sorted(folder.glob('TEST-*.xml')):
        x = ET.parse(p).getroot()
        cases = x.findall('testcase')
        row = {'class': cases[0].attrib['classname'],
               **{k: int(x.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}}
        assert len(cases) == row['tests']
        row['failed_cases'] = [c.attrib['name'] for c in cases if c.find('failure') is not None]
        row['output'] = x.findtext('system-out', '')
        rows.append(row)
        names.extend((c.attrib['classname'], c.attrib['name']) for c in cases)
    assert rows
    return {'classes': len(rows), **{k: sum(r[k] for r in rows) for k in ('tests', 'failures', 'errors', 'skipped')},
            'suites': rows, 'case_names': names}

manifests = {n: manifest(n, count) for n, count in [('k23-final-1103f48', 92), ('k23-review-6287c59', 6)]}
assert git('rev-parse', FINAL + '^').decode().strip() == DRAFT
assert git('rev-parse', 'refs/remotes/origin/candidate/n15-n16-integration-fe01207').decode().strip() == FINAL
assert git('diff', '--name-only', DRAFT, FINAL).decode().splitlines() == [TEST]
assert git('diff', '--numstat', DRAFT, FINAL).decode().strip() == '8\t0\t' + TEST
assert not git('diff', '--name-only', DRAFT, FINAL, '--', 'web-api/src/main')
old_test = source(DRAFT, TEST).splitlines()
new_test = source(FINAL, TEST).splitlines()
added = [s[1:] for s in git('diff', '--unified=0', DRAFT, FINAL, '--', TEST).decode().splitlines()
         if s.startswith('+') and not s.startswith('+++')]
assert len(added) == 8
assert [s for s in new_test if s not in added] == old_test
assert sum('@org.springframework.test.context.TestPropertySource' in s for s in added) == 1
assert sum('DirtiesContext.ClassMode.AFTER_CLASS' in s for s in added) == 1
paths = git('diff', '--name-only', BASE, FINAL).decode().splitlines()
assert len(paths) == 12 and all(p.endswith('.java') for p in paths)
prod = [p for p in paths if '/src/main/' in p]
assert len(prod) == 8
assert all(git('show', f'{DRAFT}:{p}') == git('show', f'{FINAL}:{p}') for p in prod)
subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, FINAL], cwd=ROOT, check=True)

passed = suites(DEV / 'tests/integ25-1103f48/xml')
prior = suites(PREP / 'tests/integ-run2')
assert (passed['classes'], passed['tests'], passed['failures'], passed['errors'], passed['skipped']) == (25, 173, 0, 0, 0)
assert collections.Counter(passed['case_names']) == collections.Counter(prior['case_names'])
assert (DEV / 'tests/integ25-1103f48/rc.txt').read_text().strip() == 'rc=0'
assert (DEV / 'tests/integ25-1103f48/head.txt').read_text().strip() == FINAL
run_log = (DEV / 'tests/integ25-1103f48/gradle.log').read_text(encoding='utf-8')
assert 'BUILD SUCCESSFUL' in run_log
for name, n in [('DailySma20LiveOrderPathTest', 24), ('N15EntryExpiryTest', 6), ('N15FinalApprovalBoundaryTest', 6), ('N16InvalidPriceReproTest', 13)]:
    s = next(s for s in passed['suites'] if s['class'].endswith('.' + name))
    assert s['tests'] == n
before = suites(DEV / 'isolation/before-51dcee5')
after = suites(DEV / 'isolation/after-1103f48')
assert (before['classes'], before['tests'], before['failures'], before['errors'], before['skipped']) == (2, 25, 9, 0, 0)
assert (after['classes'], after['tests'], after['failures'], after['errors'], after['skipped']) == (3, 26, 0, 0, 0)
a = next(s['output'] for s in after['suites'] if s['class'].endswith('.AaaEntryRoundsPoisonProbeTest'))
z = next(s['output'] for s in after['suites'] if s['class'].endswith('.ZzzEntryRoundsSharedContextProbeTest'))
assert re.search(r'rounds@([0-9a-f]+)', a).group(1) == re.search(r'rounds@([0-9a-f]+)', z).group(1)
assert '871개' in a and '회차 마감 뒤 도착' in z
assert not any('ProbeTest' in p for p in paths)

doc = source(REF, 'docs/LIMITED_LIVE_TEST_PLAN.md')
docblocks = blocks(doc)
submitted = blocks((DEV / 'blocks/r-blocks.md').read_text(encoding='utf-8'))
assert len(docblocks) == 117 and len(submitted) == 8 and docblocks[-8:] == submitted
assert docblocks[:-8] == blocks(source('6287c59', 'docs/LIMITED_LIVE_TEST_PLAN.md'))
hashes = json.loads((DEV / 'local-check/blocks.json').read_text(encoding='utf-8'))
syntax = []
for i, (b, (name, want)) in enumerate(zip(submitted, hashes)):
    assert sha(b.encode()) == want
    assert b == (DEV / f'local-check/blocks/{name}.sh').read_text(encoding='utf-8')
    p = SCRATCH / f'block-{i}.sh'
    p.write_bytes(b.encode())
    r = subprocess.run(['C:/Program Files/Git/bin/bash.exe', '--noprofile', '--norc', '-n', str(p)], capture_output=True)
    assert r.returncode == 0, r.stderr
    syntax.append({'block': name, 'sha256': want, 'bash_n': 0})
vals = json.loads((DEV / 'blocks/vals.json').read_text(encoding='utf-8'))
assert vals['NEW'] == FINAL and vals['RV'] == vals['SEC'] == '81' and vals['DIFF'] == paths
assert submitted[0].count('NEW=' + FINAL) == 1
assert all('[ "${RV:-}" = 81 ]' in b for b in submitted[1:])
assert (DEV / 'blocks/entryobs.sh').read_bytes() == (PREP / 'blocks/entryobs.sh').read_bytes()
oldblocks = blocks((PREP / 'blocks/r-blocks.md').read_text(encoding='utf-8'))
def original_r(b):
    b = b.replace(FINAL, DRAFT).replace('1103f48', '51dcee5')
    b = b.replace('RV=81', 'RV=79').replace('RV:-}" = 81', 'RV:-}" = 79')
    b = b.replace('§5-9 81', '§5-9 79').replace('v1.113', 'v1.111')
    b = b.replace('12개 파일', '11개 파일').replace('#22 P1', '#22 R1')
    b = b.replace('  ' + TEST + ' \\\n', '')
    lines = b.splitlines(keepends=True)
    # Only the variable header gained an explanatory comment; executable text is fixed.
    for i, line in enumerate(lines):
        if line.startswith('M=~/maint-n15n16;'):
            lines[i] = oldblocks[0].splitlines(keepends=True)[0]
    return ''.join(lines)
assert all(original_r(new) == old for new, old in zip(submitted, oldblocks))

jarhome = Path('D:/tmp/n15final/jars')
jar_hashes = []
for line in (DEV / 'jars/jar-sha.txt').read_text().splitlines():
    want, name = line.split()
    p = jarhome / name
    assert sha(p.read_bytes()) == want, name
    with zipfile.ZipFile(p) as outer:
        assert len(outer.namelist()) == len(set(outer.namelist()))
        for n in outer.namelist():
            if re.match(r'BOOT-INF/lib/(core-engine|strategy-lib|exchange-adapter)[^/]*\.jar$', n):
                with zipfile.ZipFile(io.BytesIO(outer.read(n))) as inner:
                    assert len(inner.namelist()) == len(set(inner.namelist()))
    jar_hashes.append({'file': name, 'sha256': want, 'duplicate_entry_names': 0})
cmp = PREP / 'jars/jarcmp.py'
assert cmp.read_text(encoding='utf-8') in submitted[2]
jar_results = []
for a, b, name in [('fe01207.jar', '1103f48.jar', 'diff-fe01207-1103f48.txt'), ('51dcee5.jar', '1103f48.jar', 'diff-51dcee5-1103f48.txt')]:
    r = subprocess.run([sys.executable, '-B', '-X', 'utf8', str(cmp), str(jarhome/a), str(jarhome/b)], capture_output=True, check=True)
    text = r.stdout.decode().replace('\r\n', '\n')
    assert text == (DEV / 'jars' / name).read_text(encoding='utf-8')
    jar_results.append({'left': a, 'right': b, 'output': text})
assert jar_results[0]['output'] == (PREP / 'jars/jar-diff.txt').read_text(encoding='utf-8')
assert jar_results[1]['output'] == '(끝) 881 881\n'
assert jar_results[0]['output'].splitlines()[:-1] == vals['RARX']

def compact(s):
    return {k: v for k, v in s.items() if k not in ('case_names', 'suites')}

result = {'execution_source': REF, 'candidate': FINAL, 'parent': DRAFT, 'base': BASE,
          'manifests': manifests, 'candidate_delta': {'file': TEST, 'added': 8, 'removed': 0},
          'production_files_exactly_preserved': prod, 'all_changed_paths': paths,
          'XML_25_classes': compact(passed), 'same_173_case_names_as_previous_run': True,
          'isolation_before': compact(before), 'isolation_after': compact(after), 'shared_probe_state_preserved': True,
          'R_blocks': syntax, 'prior_R_equivalent_except_reviewed_values_comments_and_test_path': True,
          'existing_local_jar_hashes': jar_hashes, 'rerun_jar_item_comparison': jar_results,
          'no_server_network_DB_Gradle_bootJar_or_application_test_execution': True}
(OUT / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifests': manifests, 'same_tests': '173/173', 'probe': '9 failures -> 0', 'bash_n': 8, 'existing_jar_hashes': 3, 'jar_comparisons': 2}, ensure_ascii=False))
