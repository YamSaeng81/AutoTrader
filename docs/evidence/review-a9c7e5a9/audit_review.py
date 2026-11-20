"""Fixed-blob and preserved-output audit. No application, SSH, database or build runs."""
from pathlib import Path
import ast
import hashlib
import io
import json
import re
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHA = 'a9c7e5a9499781d838df2db81b248dbfb0c3327c'
SRC = 'cd8bd76fdd4d46bac893a98f4eab36cceb990c99'
OLD = '474d103343fb4ccc531136b79e9ece5b4d9cc912'
P = 'docs/evidence/d81d4r-20261120/'
Q = 'docs/evidence/d81d4-20261120/'
checks, obs = [], {}
H = lambda b: hashlib.sha256(b).hexdigest()

def git(*a):
    return subprocess.check_output(['git', *a], cwd=ROOT)

def blob(c, p):
    return git('show', c + ':' + p)

def check(name, ok, actual=None):
    checks.append({'check': name, 'pass': bool(ok), 'actual': actual})

def tree(c, p):
    return git('-c', 'core.quotepath=false', 'ls-tree', '-r', '--name-only', c, '--', p).decode().splitlines()

def batch(paths):
    r = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT, check=True,
        input=''.join(SHA + ':' + p + '\n' for p in paths).encode(), stdout=subprocess.PIPE)
    out, s = {}, io.BytesIO(r.stdout)
    for p in paths:
        hdr = s.readline().decode().split()
        assert len(hdr) == 3 and hdr[1] == 'blob'
        out[p] = s.read(int(hdr[2])); assert s.read(1) == b'\n'
    assert not s.read()
    return out

def literals(t, name):
    return next(ast.literal_eval(n.value) for n in ast.walk(ast.parse(t))
        if isinstance(n, ast.Assign) and any(isinstance(x, ast.Name) and x.id == name for x in n.targets))

def embedded(t):
    return re.search(r"pf3ok\(\) \{ python3 - \"\$1\" <<'PYEOF'\n(.*?)\nPYEOF", t, re.S)[1]

check('HEAD and saved origin/main', git('rev-parse', 'HEAD', 'refs/remotes/origin/main').decode().splitlines() == [SHA, SHA])
paths = tree(SHA, P) + tree(SHA, 'docs/evidence/review-474d1033/')
bs = batch(paths)
check('submitted evidence bytes equal working copies', all((ROOT / p).read_bytes() == b for p, b in bs.items()), len(bs))
for d in (P, 'docs/evidence/review-474d1033/'):
    lines = bs[d + 'SHA256SUMS'].decode().splitlines()
    bad = []
    for line in lines:
        h, p = line.split(None, 1); p = p.lstrip('*').removeprefix('./')
        if d + p not in bs or H(bs[d + p]) != h:
            bad.append(p)
    check('manifest ' + d, not bad, {'listed': len(lines), 'bad': bad})
check('previous D81 evidence unchanged', git('diff', '--name-only', OLD, SHA, '--', Q) == b'')
delta = git('-c', 'core.quotepath=false', 'diff', '--name-only', OLD, SHA).decode().splitlines()
check('submission changes documentation only', all(p.startswith('docs/') for p in delta), len(delta))
check('settings excluded from submission', '.claude/settings.local.json' not in delta)
check('fixed source and evidence source blocks identical', all(blob(SRC, p) == b for p, b in bs.items() if p.startswith(P + 'd4/') or p in (P + 'runner/run.sh', P + 'runner/extract.py')))

newblocks = {p.rsplit('/', 1)[1]: b for p, b in bs.items() if p.startswith(P + 'd4/blocks-d81/')}
changed = [n for n, b in newblocks.items() if blob(OLD, Q + 'd4/blocks-d81/' + n) != b]
check('exactly U variables and U0 changed', sorted(changed) == sorted(['U변수.sh', 'U0.sh']), changed)
uv = newblocks['U변수.sh'].decode(); olduv = blob(OLD, Q + 'd4/blocks-d81/U변수.sh').decode()
code, oldcode = embedded(uv), embedded(olduv)
check('U variables outside classifier unchanged', uv.replace(code, '<PF3>') == olduv.replace(oldcode, '<PF3>'))
u0 = newblocks['U0.sh'].decode().splitlines(); o0 = blob(OLD, Q + 'd4/blocks-d81/U0.sh').decode().splitlines()
changedlines = [(a, b) for a, b in zip(o0, u0) if a != b]
check('U0 one classifier failure message only', len(u0) == len(o0) and len(changedlines) == 1 and 'pf3ok' in changedlines[0][0] and 'pf3ok' in changedlines[0][1])
obs['u0_changed_line'] = changedlines
exp = literals(bs[P + 'runner/extract.py'].decode(), 'EXP')
runner = bs[P + 'runner/run.sh'].decode()
runtime_exp = literals(re.search(r"<<'PYEOF'.*?\n(.*?)\nPYEOF", runner, re.S)[1], 'EXP')
check('extractor and runner pin identical eleven blocks', exp == runtime_exp and len(exp) == 11)
saved = json.loads(bs[P + 'runner/extract-cd8bd76f/blocks.json'])
check('preserved extraction source', saved['source'] == SRC and len([v for v in saved.values() if isinstance(v, dict)]) == 11)
check('eleven extracted files match pinned hashes', all(H(bs[P + 'runner/extract-cd8bd76f/' + k + '.sh']) == h == saved[k]['sha256'] and saved[k]['expected_match'] for k, h in exp.items()))
check('nine generated blocks match pins', len(newblocks) == 9 and all(H(b) == exp[n[:-3]] for n, b in newblocks.items()))
check('F and G remain previous pins', exp['F'] == '26d392695c47fecb2321e850b07418679c1326c2a7eec13c02002056f96f4c24' and exp['G'] == '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822')
gen = bs[P + 'd4/mkd81r.py'].decode()
check('generator embeds exact classifier body', literals(gen, 'PF3BODY').rstrip('\n') == code)
for k, prefix, count in [('parser', 'tests/pf3-r1.txt', 26), ('runner', 'runner/runner-selftest-r2.txt', 21), ('U0 connection', 'standin/scen-r4.txt', 21)]:
    t = bs[P + prefix].decode()
    check('preserved ' + k + ' result', len(re.findall(r'^PASS ', t, re.M)) == count and not re.search(r'^FAIL ', t, re.M), {'PASS': len(re.findall(r'^PASS ', t, re.M)), 'FAIL': len(re.findall(r'^FAIL ', t, re.M))})
j = json.loads(bs[P + 'tests/pf3-r1.json'])
check('parser JSON results', j['pass'] == 26 and j['fail'] == 0 and len(j['cases']) == 26 and all(c['pass'] and c['rc'] == c['want'] for c in j['cases']))
r1 = bs[P + 'runner/runner-selftest-r1.txt'].decode()
check('first runner attempt kept separate', len(re.findall(r'^PASS ', r1, re.M)) == 19 and '== 결과:' not in r1)
fixed = bs[P + 'runner/fixedsrc-run-cd8bd76f.txt'].decode()
check('fixed-source smoke uses fake SSH', '가짜 ssh' in fixed and 'rc=0' in fixed)
archives = {}
for ap in ('standin/runs-r4.tar.gz', 'runner/selftest-r1r2.tar.gz', 'tests/pf3-work-r1.tar.gz'):
    with tarfile.open(fileobj=io.BytesIO(bs[P + ap]), mode='r:gz') as tf:
        names = [m.name for m in tf if m.isfile()]
    archives[ap] = {'bytes': len(bs[P + ap]), 'files': len(names), 'duplicates': len(names) - len(set(names)), 'unsafe_names': [n for n in names if n.startswith('/') or '..' in Path(n).parts]}
check('preserved archives readable without extracting', all(a['files'] and not a['duplicates'] and not a['unsafe_names'] for a in archives.values()), archives)
obs['archives'] = archives
with tarfile.open(fileobj=io.BytesIO(bs[P + 'standin/runs-r4.tar.gz']), mode='r:gz') as tf:
    members = {m.name: m for m in tf if m.isfile()}
    cases = []
    for n in range(1, 11):
        base = 'runs/r4-v%02d/' % n
        calls = tf.extractfile(members[base + 'calls.log']).read().decode()
        mutations = []
        for line in calls.splitlines():
            f = line.split('\t')
            if len(f) > 2 and ((f[1] == 'docker' and (f[2].startswith('tag ') or re.search(r'docker-compose\.prod\.yml (?:up|build) ', f[2]))) or (f[1] == 'git' and f[2].startswith('checkout '))):
                mutations.append(line)
        forbidden = tf.extractfile(members[base + 'forbidden.log']).read()
        cases.append({'case': 'v%02d' % n, 'mutations': len(mutations), 'ok_U0': base + 'home/maint-d81/ok-U0' in members, 'forbidden_nonempty': bool(forbidden.strip())})
check('raw U0 ten cases have no checkout/build/tag/restart', all(c['mutations'] == 0 for c in cases), cases)
check('raw U0 completion only normal case', all(c['ok_U0'] == (c['case'] == 'v01') for c in cases))
check('raw U0 forbidden logs empty', not any(c['forbidden_nonempty'] for c in cases))
obs['raw_U0_cases'] = cases
check('classifier validation precedes no-trade exemption', code.index('cost, bq, net, sq =') < code.index("if f[9] == '1':"))
check('recording post-SSH failure keeps remote error code', 'fr=4; [ "$rc" = 0 ] || fr=$rc' in runner)
check('remaining incomplete marker checked before SSH', runner.index('[ ! -e "$I" ]') < runner.index('$SSH -o BatchMode=yes'))
probe = json.loads((HERE / 'boundary-probe.json').read_text(encoding='utf-8'))
check('independent local boundary checks', probe['source'] == SRC and probe['pass'] == 15 and probe['fail'] == 0)
obs['scope'] = 'Git blobs and local preserved files only; no production access, PG, Docker, application tests or build.'
out = {'source': SHA, 'fixed_source': SRC, 'pass': sum(c['pass'] for c in checks), 'fail': sum(not c['pass'] for c in checks), 'checks': checks}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(HERE / 'observations.json').write_text(json.dumps(obs, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'pass': out['pass'], 'fail': out['fail'], 'failed': [c for c in checks if not c['pass']]}))
