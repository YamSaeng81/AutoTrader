"""Inspect fixed Git artifacts and reviewer-local probe outputs only."""
from pathlib import Path
import ast
import hashlib
import io
import json
import re
import subprocess
import sys
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHA = '474d103343fb4ccc531136b79e9ece5b4d9cc912'
P = 'docs/evidence/d81d4-20261120/'
checks, obs = [], {}

def git(*a):
    return subprocess.check_output(['git', *a], cwd=ROOT)
def check(name, passed, actual=None):
    checks.append({'check': name, 'pass': bool(passed), 'actual': actual})
def blob(sha, path):
    return git('show', sha + ':' + path)
def tree(sha, path):
    return git('-c', 'core.quotepath=false', 'ls-tree', '-r', '--name-only', sha, '--', path).decode().splitlines()
def batch(paths):
    r = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
        input=''.join(SHA + ':' + p + '\n' for p in paths).encode(), stdout=subprocess.PIPE, check=True)
    stream, out = io.BytesIO(r.stdout), {}
    for p in paths:
        h = stream.readline().decode().split()
        assert len(h) == 3 and h[1] == 'blob'
        out[p] = stream.read(int(h[2])); assert stream.read(1) == b'\n'
    assert not stream.read()
    return out

saved = batch(tree(SHA, P) + tree(SHA, 'docs/evidence/review-7c62a7db/'))
check('all 81 submitted evidence files equal local preserved bytes', len(saved) == 81 and all(
    (ROOT / p).is_file() and (ROOT / p).read_bytes() == b for p, b in saved.items()), len(saved))
for prefix, expected in [(P, 74), ('docs/evidence/review-7c62a7db/', 5)]:
    rows = [re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', s) for s in saved[prefix + 'SHA256SUMS'].decode().splitlines()]
    assert all(rows)
    bad = [m[2] for m in rows if hashlib.sha256(saved[prefix + m[2].removeprefix('./')]).hexdigest() != m[1]]
    check(prefix + ' committed manifest', len(rows) == expected and not bad, {'listed': len(rows), 'mismatch': bad})
changed = git('-c', 'core.quotepath=false', 'diff', '--name-only', '7c62a7db', SHA).decode().splitlines()
check('submission changes documents only and excludes settings', all(p.startswith('docs/') for p in changed) and '.claude/settings.local.json' not in changed, len(changed))
check('candidate remains d81c3f2f', git('rev-parse', 'integ/final-abc-60df3437').decode().strip() == 'd81c3f2f91c0239aacb0e1176dde9b898f4d7466')
def data(rel): return saved[P + rel]
def text(rel): return data(rel).decode('utf-8')
NAMES = ['U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'U4', 'U되돌림', 'U뒤관측']
blocks = {n: text('d4/blocks-d81/' + n + '.sh') for n in NAMES}
sums = {m[2]: m[1] for m in [re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', s) for s in text('d4/blocks-d81.sha256').splitlines()]}
check('nine block names and immutable hashes', len(sums) == 9 and all(
    hashlib.sha256(data('d4/blocks-d81/' + n + '.sh')).hexdigest() == sums[n + '.sh'] for n in NAMES))
check('block bytes unchanged across d593/e21/current', all(blob(c, P + 'd4/blocks-d81/' + n + '.sh') ==
    data('d4/blocks-d81/' + n + '.sh') for c in ['d593e1dd', 'e21c777f'] for n in NAMES))
def exp(rel):
    node = ast.parse(text(rel))
    return next(ast.literal_eval(n.value) for n in node.body if isinstance(n, ast.Assign) and
        any(isinstance(t, ast.Name) and t.id == 'EXP' for t in n.targets))
extract_exp = exp('runner/extract.py')
embedded = re.search(r"<<'PYEOF'.*?\n(import hashlib.*?\n)PYEOF", text('runner/run.sh'), re.S)[1]
runner_exp = next(ast.literal_eval(n.value) for n in ast.parse(embedded).body if isinstance(n, ast.Assign) and
    any(isinstance(t, ast.Name) and t.id == 'EXP' for t in n.targets))
check('runner and extractor pin identical eleven hashes', runner_exp == extract_exp and len(runner_exp) == 11)
j = json.loads(text('runner/extract-e21c777f/blocks.json'))
check('saved fixed extraction validates all eleven hashes', j['source'] == 'e21c777fe86b12e22d8890ecca072b2139a37fc4' and
    all(j[k]['expected_match'] and j[k]['sha256'] == v for k,v in extract_exp.items()))
extract_change = git('diff', 'd593e1dd', 'e21c777f', '--', P + 'runner/extract.py').decode()
check('extractor correction only disables quoted non-ASCII filenames', "'core.quotepath=false'" in extract_change and
    git('diff', '--numstat', 'd593e1dd', 'e21c777f', '--', P + 'runner/extract.py').decode().startswith('1\t1\t'))
runner = text('runner/runner-selftest.txt')
check('saved runner selftest 30 passes zero failures', len(re.findall(r'^PASS ', runner, re.M)) == 30 and '== 결과: PASS 30 · FAIL 0' in runner)
for r, passed, failed in [('r2',199,2),('r3',201,0)]:
    s = text('standin/runs/scen-' + r + '.txt')
    check(r + ' saved scenario counts', len(re.findall(r'^PASS ', s, re.M)) == passed and
        len(re.findall(r'^FAIL ', s, re.M)) == failed and f'== 대역 결과 PASS {passed} · FAIL {failed}' in s)
manifest = {}
for l in text('standin/runs/runs-r1r2r3.sha256').splitlines():
    m = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', l); assert m; manifest[m[2]] = m[1]
seen, bad = set(), []
with tarfile.open(fileobj=io.BytesIO(data('standin/runs/standin-runs-r1r2r3.tar.gz')), mode='r|gz') as archive:
    for m in archive:
        if not m.isfile(): continue
        name = m.name.removeprefix('./')
        stream = archive.extractfile(m)
        h = hashlib.sha256()
        while chunk := stream.read(65536): h.update(chunk)
        if name in seen or manifest.get(name) != h.hexdigest(): bad.append(name)
        seen.add(name)
check('15107 archived raw files match committed hashes', len(seen) == len(manifest) == 15107 and seen == set(manifest) and not bad,
    {'files':len(seen),'listed':len(manifest),'mismatch':bad})
check('backend-only blocks contain no frontend mutation or UF2 dependency', all(not re.search(
    r'\$DC (?:build|up|restart|stop)[^\n]*frontend|docker tag[^\n]*autotrader-frontend|ok-UF2|svc-fe\.txt', s)
    for n,s in blocks.items() if n != 'U변수'))
check('U2 V96 validation precedes restart start record', blocks['U2'].index('v96h >') < blocks['U2'].index('t-deploy.txt') < blocks['U2'].index('$DC up'))
check('U2 has no obsolete V94 or boolean t expectation', 'v94' not in blocks['U2'] and '96:t:' not in blocks['U2'])
check('U4 compares all non-backend services to original baseline', 'ok-UF2' not in blocks['U4'] and
    'grep -v \'^backend \' "$M/svc-before.txt"' in blocks['U4'])
rollback = blocks['U되돌림']
check('rollback gate and incompatible refusal precede checkout/tag/up', rollback.index('STOP_INCOMPATIBLE') <
    rollback.index('git checkout') < rollback.index('docker tag') < rollback.index('$DC up'))
check('pre-restart rollback refuses any recorded restart', '[ ! -e "$M/ok-U2" ] && [ ! -e "$M/t-deploy.utc" ]' in blocks['U1되돌림'])
uvars=blocks['U변수']
check('known JARM and JARN pinned', '33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509' in uvars and '(끝) 895 898' in uvars)
check('v96h uses count and NULL-explicit checksum', "count(*) || '|'" in uvars and "coalesce(checksum::text, 'NULL')" in uvars and '1|96:true:$V96SUM' in uvars)
jardiff=text('jarcmp-real/jar-diff-srv0e1d2a02-vs-d81lf.txt')
check('actual jar comparison output matches 35 changes and 3 additions', len(re.findall('^변경 ',jardiff,re.M)) ==35 and
    len(re.findall('^추가 ',jardiff,re.M))==3 and len(re.findall('^삭제 ',jardiff,re.M))==0 and '(끝) 895 898' in jardiff)
check('preflight invoked before U0 success', blocks['U0'].index('pf3ok ') < blocks['U0'].index('touch "$M/ok-U0"'))
gen = HERE / 'work' / 'generated-round1'
if not gen.exists():
    r = subprocess.run([sys.executable, '-I', '-X', 'utf8', str(ROOT / P / 'd4/mkd81.py'), str(ROOT), str(gen / 'blocks-d81')],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (HERE / 'generator-probe.txt').write_bytes(r.stdout + r.stderr)
    assert r.returncode == 0
check('independent generator reproduction equals all nine committed blocks', all(
    (gen / 'blocks-d81' / (n + '.sh')).read_bytes() == data('d4/blocks-d81/' + n + '.sh') for n in NAMES))
check('independent generated block manifest matches saved manifest', (gen / 'blocks-d81.sha256').read_bytes() == data('d4/blocks-d81.sha256'))
runner_probe=json.loads((HERE/'runner-record-probe-round2.json').read_text(encoding='utf-8'))
check('local recording-failure reproduction used exact fixed runner', runner_probe['runner_sha256']==hashlib.sha256(data('runner/run.sh')).hexdigest())
check('four record failures falsely return success after fake SSH', len(runner_probe['cases'])==5 and all(
    r['runner_rc']==0 and r['fake_ssh_calls']==1 for r in runner_probe['cases'][:4]), runner_probe['cases'])
check('remote nonzero remains nonzero despite logging failure', runner_probe['cases'][4]['runner_rc']==7)
pf3=json.loads((HERE/'pf3-probe.json').read_text(encoding='utf-8'))
check('four malformed R3 inputs are accepted by fixed parser', len(pf3['cases'])==6 and all(r['rc']==0 for r in pf3['cases']),
    [{'case':r['case'],'rc':r['rc']} for r in pf3['cases']])
obs.update({'submission':SHA,'listed_evidence':74,'previous_review_checksums':5,'archived_files':len(seen),
    'runner_probe':runner_probe['cases'],'pf3_probe':[{'case':r['case'],'rc':r['rc']} for r in pf3['cases']],
    'findings':['run.sh ignores local record-write failures; first-record failure still calls SSH and success may remain rc0.',
        'pf3ok accepts malformed cost, infinite quantity, invalid no-trade flag and missing sell-net fields.',
        'R1/R3 gate is a conservative cost-source check, not a full product risk-approval verdict. R2 loss sums are only recorded.'],
    'limits':['No product tests, PG, Docker, build, SSH or production operation executed.',
        'Only reviewer-local fake-SSH recording probes and a pure file classifier ran.',
        'First runner probe used a path with spaces in unquoted SSH and returned127 before any fake call; excluded.',
        'First parser probe stopped on Windows output decoding; excluded. Second-round UTF-8 results are used.',
        'Existing 201/0 scenarios and archive checks are preserved evidence, not rerun scenarios.',
        'Synthetic input acceptance is a parser validation gap; it does not establish such corrupt values in production.',
        'Image byte-chain rehearsal uses synthetic originals; actual production image matching remains a future U0 check.']})
(HERE/'checks.json').write_text(json.dumps({'passed':sum(c['pass'] for c in checks),'failed':sum(not c['pass'] for c in checks),
    'checks':checks},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(HERE/'observations.json').write_text(json.dumps(obs,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':sum(c['pass'] for c in checks),'failed':sum(not c['pass'] for c in checks),'archive_files':len(seen)}))
raise SystemExit(0 if all(c['pass'] for c in checks) else 1)
