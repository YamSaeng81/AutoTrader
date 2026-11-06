"""Read preserved candidate evidence; independently exercise original class bodies with local stubs."""
from pathlib import Path
import hashlib, json, re, subprocess, xml.etree.ElementTree as ET, zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
TMP = ROOT / 'core-engine/build/n20-accept-614bd5e'
TMP.mkdir(parents=True, exist_ok=True)
REF = '614bd5e'
OLD = 'ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8'
NEW = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
SVC = 'web-api/src/main/java/com/cryptoautotrader/api/service/'
TEST = 'web-api/src/test/java/com/cryptoautotrader/api/service/N20HeldMarkItemIsolationTest.java'
JAVA = Path('C:/Program Files/Java/jdk-17/bin')
BASH = 'C:/Program Files/Git/bin/bash.exe'
E = ROOT / 'docs/evidence/n20-candidate-1899b49'
def git(*args): return subprocess.check_output(['git', *args], cwd=ROOT)
def read(p): return p.read_text(encoding='utf-8')
def sha(b): return hashlib.sha256(b).hexdigest()
def norm(b): return b.replace(b'\r\n', b'\n')
def manifest(name):
    folder = ROOT / 'docs/evidence' / name
    rows = read(folder / 'SHA256SUMS').splitlines()
    for row in rows:
        h, f = row.split(maxsplit=1)
        p = folder / f.lstrip('*').removeprefix('./')
        assert sha(p.read_bytes()) == h, p
        assert p.read_bytes() == git('show', REF + ':' + p.relative_to(ROOT).as_posix()), p
    return len(rows)
counts = {n: manifest(n) for n in ['n20-candidate-1899b49', 'n20-review-9017d63']}
assert git('rev-parse', NEW + '^').decode().strip() == OLD
paths = git('diff', '--name-only', OLD, NEW).decode().splitlines()
assert paths == [SVC + 'HeldMarkRefresher.java', TEST]
sources = {n: git('show', NEW + ':' + SVC + n + '.java').decode() for n in ['HeldMarkRefresher', 'MarkPriceBook', 'RealtimePriceEvent']}
for n in ['MarkPriceBook', 'RealtimePriceEvent']:
    assert git('show', OLD + ':' + SVC + n + '.java').decode() == sources[n]
assert 'getMessage(' not in sources['HeldMarkRefresher']
assert norm((E / 'code/HeldMarkRefresher-1899b49.java').read_bytes()) == sources['HeldMarkRefresher'].encode()
xml_rows = {}
test_snapshots = []
for run, tests, failures in [('r1-fail-first-ee509cf', 33, 31), ('r2-fix-related', 114, 0)]:
    p = E / 'runs' / run
    assert read(p / 'head.txt').strip() == OLD  # r2 ran uncommitted source before the candidate commit.
    for row in read(p / 'src.sha256').splitlines():
        h, f = row.split(maxsplit=1)
        q = p / 'src' / f.lstrip('*').removeprefix('./')
        assert sha(q.read_bytes()) == h
        assert norm(q.read_bytes()) == git('show', NEW + ':' + q.relative_to(p / 'src').as_posix())
    test_snapshots.append((p / 'src' / TEST).read_bytes())
    suites = [ET.fromstring(q.read_bytes()) for q in sorted((p / 'xml').glob('*.xml'))]
    totals = [sum(int(s.get(k, 0)) for s in suites) for k in ['tests', 'failures', 'errors', 'skipped']]
    assert totals == [tests, failures, 0, 0], totals
    assert read(p / 'exit.txt').strip() == ('1' if failures else '0')
    xml_rows[run] = {'totals': totals, 'classes': {s.find('testcase').get('classname'): int(s.get('tests')) for s in suites}}
    n20 = next(s for s in suites if s.find('testcase').get('classname').endswith('N20HeldMarkItemIsolationTest'))
    rows = [r for r in (n20.findtext('system-out') or '').splitlines() if r.startswith('N20계약행|')]
    # The old lifecycle test fails before its final println; the other 32 emit a row.
    assert len(rows) == (32 if failures else 33), len(rows)
    if not failures:
        assert '\n'.join(rows) + '\n' == read(E / 'runs/r2-rows.txt')
    else:
        passed = [t.get('name') for t in n20.findall('testcase') if t.find('failure') is None]
        assert len(passed) == 2 and all('CONTROL' in x for x in passed)
assert test_snapshots[0] == test_snapshots[1]

# Reuse the accepted full-item jar comparator against actual preserved jars. No build.
cmp = ROOT / 'docs/evidence/k23-prep-51dcee5/jars/jarcmp.py'
jars = [Path('D:/tmp/n19v2/jars/ee509cf.jar'), Path('D:/tmp/n20c/jars/1899b49.jar')]
expected_hashes = ['4f4f42aa058c34a15e438ba9048a9f94c49b2963a8bd72c5eb1ca3d5fbd6f4ec',
                   '74fd7c835ee7edf521496c2117522260b3d303f8aa6b6a18a59cce4901452091']
for p, h in zip(jars, expected_hashes):
    assert sha(p.read_bytes()) == h
    with zipfile.ZipFile(p) as z: assert len(z.namelist()) == len(set(z.namelist()))
r = subprocess.run([__import__('sys').executable, '-X', 'utf8', str(cmp), *map(str, jars)], capture_output=True)
assert r.returncode == 0
assert norm(r.stdout) == norm((E / 'jars/diff-ee509cf-1899b49.txt').read_bytes())
assert len(r.stdout.decode().splitlines()) == 3 and r.stdout.decode().splitlines()[-1] == '(끝) 881 881'
(OUT / 'jar-diff.txt').write_bytes(r.stdout)
book_code = []
for i, p in enumerate(jars):
    with zipfile.ZipFile(p) as z:
        b = z.read('BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher$Book.class')
    q = TMP / ('Book-' + str(i) + '.class')
    q.write_bytes(b)
    r = subprocess.run([str(JAVA / 'javap.exe'), '-c', '-p', str(q)], capture_output=True)
    assert r.returncode == 0
    book_code.append(r.stdout)
assert book_code[0] == book_code[1]
syntax = []
for p in sorted((E / 'blocks-draft').glob('U*.sh')):
    r = subprocess.run([BASH, '--noprofile', '--norc', '-n', str(p)], capture_output=True)
    assert r.returncode == 0, (p, r.stderr)
    syntax.append(p.name)
assert len(syntax) == 8
assert 'IMGOLD=미정' in read(E / 'blocks-draft/U변수.sh')
assert 'case "$IMGOLD" in sha256:*)' in read(E / 'blocks-draft/U0.sh')

# Annotation/import/dependency substitution only; production method bodies are not edited.
def inner(name):
    s = sources[name]
    s = re.sub(r'^package .*?;\n|^import .*?;\n', '', s, flags=re.M)
    s = re.sub(r'^\s*@[^\n]+\n', '\n', s, flags=re.M)
    s = s.replace('public class ' + name + ' {', 'static class ' + name + ' {', 1)
    s = s.replace('org.springframework.jdbc.core.JdbcTemplate', 'JdbcTemplate')
    s = s.replace('org.springframework.jdbc.core.RowCallbackHandler', 'RowCallbackHandler')
    if name == 'HeldMarkRefresher':
        s = s.replace('static class HeldMarkRefresher {', 'static class HeldMarkRefresher {\nstatic final Log log = new Log();', 1)
    return s
stub = read(OUT / 'stub-prefix.java.txt')
program = stub + '\n'.join(inner(n) for n in sources) + '\n}\n'
(TMP / 'N20Accept.java').write_text(program, encoding='utf-8')
(OUT / 'method-harness.java.txt').write_text(program, encoding='utf-8')
r = subprocess.run([str(JAVA / 'javac.exe'), '-encoding', 'UTF-8', str(TMP / 'N20Accept.java')], capture_output=True)
assert r.returncode == 0, r.stderr.decode(errors='replace')
r = subprocess.run([str(JAVA / 'java.exe'), '-Dfile.encoding=UTF-8', '-cp', str(TMP), 'N20Accept'], capture_output=True)
assert r.returncode == 0, r.stderr.decode(errors='replace')
assert len(r.stdout.decode().splitlines()) == 14
(OUT / 'method-run.txt').write_bytes(r.stdout)

# Execute observation parser against the preserved successful XML, plus no-argument and book-only controls.
obs = read(E / 'blocks-draft/n20obs.sh')
parser = obs.split("<<'PYEOF'\n", 1)[1].split('\nPYEOF', 1)[0]
(TMP / 'n20obs.py').write_text(parser, encoding='utf-8')
n20 = ET.fromstring((E / 'runs/r2-fix-related/xml/TEST-com.cryptoautotrader.api.service.N20HeldMarkItemIsolationTest.xml').read_bytes())
(TMP / 'stdout.log').write_text(n20.findtext('system-out'), encoding='utf-8')
r = subprocess.run([__import__('sys').executable, '-X', 'utf8', str(TMP / 'n20obs.py'), str(TMP / 'stdout.log')], capture_output=True)
assert r.returncode == 0
line = r.stdout.decode().splitlines()[0]
assert '항목 형식 14' in line and '항목 가격 아님 8' in line and '항목 코인 형식 5' in line, line
assert '시세 처리 실패 1' in line and '회차 오류 1' in line and '경보 전송 실패 1' in line, line
assert 'N20FORGED' not in r.stdout.decode() and 'KRW-N20' not in r.stdout.decode()
(OUT / 'observation-run.txt').write_bytes(r.stdout)
for args, expected in [([], 3), ([str(TMP / 'book-only.log')], 0)]:
    (TMP / 'book-only.log').write_text('08:00:00.001 [scheduler-1] INFO test - [시세 보강] 호가로 보강한 보유 [KRW-N20B] — 보유량을 매수호가에 채운 평균 가격으로 관측\n', encoding='utf-8')
    r = subprocess.run([__import__('sys').executable, '-X', 'utf8', str(TMP / 'n20obs.py'), *args], capture_output=True)
    assert r.returncode == expected
    if args: assert '호가 보강 1' in r.stdout.decode() and 'N20: 미관측' in r.stdout.decode()
result = {'review_ref': git('rev-parse', REF).decode().strip(), 'candidate': NEW, 'parent': OLD,
          'manifests_worktree_and_git': counts, 'preserved_XML': xml_rows,
          'same_test_before_after_and_committed': True, 'production_paths': paths,
          'root_original_class_body_stub_inputs': 14, 'actual_jar_hashes': expected_hashes,
          'actual_jar_changed_items': 2, 'jar_counts': [881, 881], 'Book_javap_code_equal': True, 'draft_bash_n': syntax,
          'draft_approved': False, 'parser_from_preserved_XML': line,
          'not_executed': ['server', 'DB', 'network', 'Gradle/Spring', 'reservation/order/sell', 'jar build', 'deployment']}
(OUT / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifests': counts, 'XML': [33, 31, 114, 0], 'root_stub_inputs': 14, 'jar': [2, 881, 881], 'draft_syntax': 8}))
