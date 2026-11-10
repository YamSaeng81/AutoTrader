"""Read-only source/artifact review; only local, isolated shell function checks."""
from pathlib import Path
import base64, hashlib, json, os, re, subprocess, sys

ROOT = Path(__file__).resolve().parents[3]
E = Path(__file__).resolve().parent
S = ROOT / 'core-engine/build/o13n21-accept-9aa617d'
S.mkdir(parents=True, exist_ok=True)
REV = '9aa617d1'
BASE = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
NEW = 'b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88'
APP = '1ec91046bf2bf0b7af0e6f05c04267a6c78efc21'
N21 = '6379ee0b2a7527b8136d7fba27e83881980d8555'
D = ROOT / 'docs/evidence/o1o3-n21-d3-b5cd1d0'
V = ROOT / 'docs/evidence/n21-apply-draft-v2-6379ee0b'
B = D / 'd3/blocks-d3'
BASH = r'C:\Program Files\Git\bin\bash.exe'

def sha(b): return hashlib.sha256(b).hexdigest()
def cmd(args, **kw):
    p = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kw)
    if p.returncode: raise AssertionError((args, p.returncode, p.stderr.decode('utf-8', 'replace')[:1000]))
    return p.stdout
def git(*a): return cmd(['git', *a])
def save(name, data): (E / name).write_text(data, encoding='utf-8', newline='\n')
def norm(b): return b.replace(b'\r\n', b'\n')
def posix(p):
    s = str(p.resolve()).replace('\\', '/')
    return '/' + s[0].lower() + s[2:]

assert git('rev-parse', 'HEAD').strip() == git('rev-parse', REV).strip()
assert git('status', '--porcelain', '--untracked-files=no') == b''
report = {'reviewed_commit': git('rev-parse', REV).decode().strip(), 'candidate': NEW}

# Batch cat-file keeps all comparisons byte-exact, including ignored outputs.
items, folders = [], {}
for folder in [V, D, ROOT / 'docs/evidence/review-da7977d4']:
    rows = []
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        m = re.fullmatch(r'([0-9a-f]{64})\s+\*?(.+)', line)
        assert m, line
        h, rel = m.groups()
        path = folder / rel
        assert path.is_file() and sha(path.read_bytes()) == h, path
        items.append((path, h))
        rows.append(rel)
    actual = {p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file()}
    assert actual == set(rows) | {'SHA256SUMS'}, (folder, actual - set(rows))
    folders[folder.name] = {'manifest_entries': len(rows), 'files_including_manifest': len(actual)}
refs = [REV + ':' + p.relative_to(ROOT).as_posix() for p, _ in items]
p = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
                   input=('\n'.join(refs) + '\n').encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
assert p.returncode == 0
at = 0
for (path, h), ref in zip(items, refs):
    end = p.stdout.index(b'\n', at)
    head = p.stdout[at:end].split()
    assert len(head) == 3 and head[1] == b'blob', (ref, head)
    size = int(head[2]); blob = p.stdout[end+1:end+1+size]
    assert sha(blob) == h == sha(path.read_bytes()), path
    at = end + size + 2
report['manifests'] = folders
report['matched_local_and_committed_payloads'] = len(items)
report['committed_manifest_files'] = {}
for folder in [V, D, ROOT / 'docs/evidence/review-da7977d4']:
    f = folder / 'SHA256SUMS'
    blob = git('show', REV + ':' + f.relative_to(ROOT).as_posix())
    assert blob == f.read_bytes()
    report['committed_manifest_files'][folder.name] = sha(blob)

names = git('diff', '--name-only', '-z', BASE, NEW).decode().strip('\0').split('\0')
assert len(names) == 8
identities = []
for path in names:
    source = N21 if path.startswith('scripts/prospective/') else APP
    old = git('rev-parse', source + ':' + path).decode().strip()
    new = git('rev-parse', NEW + ':' + path).decode().strip()
    assert old == new
    identities.append({'path': path, 'source': source, 'blob': new})
assert git('show', '-s', '--format=%P', NEW).decode().strip() == BASE
assert set(git('diff', '--name-only', '-z', APP, NEW).decode().strip('\0').split('\0')) == set(names[:2])
report['eight_blob_identities'] = identities

syntax, hashes = {}, {}
for f in sorted(B.glob('*.sh')):
    cmd([BASH, '--noprofile', '--norc', '-n', str(f)])
    syntax[f.name] = 0
    hashes[f.name] = sha(f.read_bytes())
for line in (D / 'd3/blocks-d3.sha256').read_text(encoding='utf-8').splitlines():
    h, name = line.split(None, 1)
    assert hashes[name.strip()] == h
assert len(hashes) == 10
report['syntax'] = syntax
report['fixed_block_sha256'] = hashes

cmp = ROOT / 'docs/evidence/v1135-h2-d2-1ec9104/comparator/jarcmp_v2.py'
assert sha(cmp.read_bytes()) == '56f5d3bab560f064ccec049fb92563b3d2a3ddfde06908da4e31a51b33383046'
u1 = (B / 'U1.sh').read_text(encoding='utf-8')
embedded = u1.split("<<'EOF' && JC=0 || JC=$?\n", 1)[1].split('\nEOF\n', 1)[0] + '\n'
assert embedded == cmp.read_text(encoding='utf-8')
jar_dir = Path('D:/tmp/o1o3/jars')
jar_hashes = {}
for c, expected in {
    '1899b49': 'a4fd4e343e6c48d7c5aa4a844f33c431eed10eff0757681d7c3bf262d554bdac',
    '1ec9104': '76a8e403b7527a7c7ec9f32b0d439f008d226a77127e1ce5e394d51fbe976a03',
    'b5cd1d0': 'a7ef759f86c6048b442b1fe37019ed2560fd87453fbea8991a67f49d8d28c79f',
}.items():
    f = jar_dir / (c + '.jar'); actual = sha(f.read_bytes()); assert actual == expected
    jar_hashes[c] = actual
for old in ['1899b49', '1ec9104']:
    out = cmd([sys.executable, '-B', '-I', '-X', 'utf8', str(cmp), str(jar_dir/(old+'.jar')), str(jar_dir/'b5cd1d0.jar')])
    recorded = (D / f'candidate/cmp-{old}-vs-b5cd1d0.txt').read_bytes()
    assert norm(out) == norm(recorded)
    save(f'jar-{old}-to-b5cd1d0.txt', out.decode('utf-8'))
report['jar_sha256'] = jar_hashes
report['jar_comparator_embedded_exact'] = True

# Stored r2 assertions, block results, and checkout call counts. No full rerun.
summary = (D/'standin/scenarios-d3-r2.txt').read_text(encoding='utf-8')
assert len(re.findall(r'^PASS ', summary, re.M)) == 114
assert not re.search(r'^FAIL ', summary, re.M)
assert '대역 단언 114/114 통과' in summary
traces = {}
for case in sorted((D/'standin/runs').iterdir()):
    assert case.name.startswith('r2-')
    calls = (case/'calls.log').read_text(encoding='utf-8').splitlines()
    checkouts = [x for x in calls if '\tgit\tcheckout ' in x]
    assert not (case/'forbidden.log').read_bytes()
    outputs = []
    for f in sorted((case/'out').glob('out-*.txt')):
        s = f.read_text(encoding='utf-8')
        rc = re.findall(r'\[대역 끝\] rc=(\d+)', s)
        assert len(rc) == 1, f
        outputs.append({'name': f.name, 'exit': int(rc[0])})
    traces[case.name] = {'checkout_calls': len(checkouts), 'outputs': outputs}
assert len(traces) == 26
checked_outputs = 0
for m in re.finditer(r"^PASS (d\d+) (U[^ —(]+)(?:\(([^)]+)\))? — 종료 (\d+) · '([^']*)' · checkout (\d+)$", summary, re.M):
    case, block, suffix, rc, reason, nco = m.groups()
    f = D / 'standin/runs' / ('r2-'+case) / 'out' / ('out-'+block+('-'+suffix if suffix else '')+'.txt')
    out = f.read_text(encoding='utf-8')
    assert reason in out and re.search(r'\[대역 끝\] rc='+rc+r'\b',out), f
    assert int(nco) <= traces['r2-'+case]['checkout_calls']
    checked_outputs += 1
assert checked_outputs == 81, checked_outputs
r1 = (D/'standin/scenarios-d3-r1.txt').read_text(encoding='utf-8')
r1fails = [x for x in r1.splitlines() if x.startswith('FAIL ')]
assert len(r1fails) == 81 and all('checkout (기대 ' in x for x in r1fails)
for line in r1fails:
    m = re.search(r'종료 (\d+)\(기대 (\d+)\)', line)
    assert m and m[1] == m[2]
report['stored_standin'] = {'r2_assertions':114, 'r2_cases':26, 'forbidden_lines':0,
                           'individual_block_exit_and_reason_matches': checked_outputs,
                           'r1_failures':81, 'r1_exit_mismatches':0, 'r2_traces':traces,
                           'concurrency_assessment':'Per-case SIM/state/output files; shared stubs/config/blocks/source/jars read only. No blanket rerun.'}

# Re-execute only the corrected common boundaries using original function bodies.
# No shell HOME changes; no ssh/git/docker/DB/network calls or actual process lookup.
uv = (B/'U변수.sh').read_text(encoding='utf-8')
common = uv[uv.index('fresh_idle()'):uv.index('# ── N21 공통 함수 끝')]
vcommon = (V/'blocks/vars.sh').read_text(encoding='utf-8')
assert common == vcommon[vcommon.index('fresh_idle()'):vcommon.index('# ── N21 공통 함수 끝')]
root_cases = []
for i, spec in enumerate([
    ('idle',1,'','',0), ('busy',0,'123 /usr/bin/python3 start_66.py freshness\n','',1),
    ('lookup-error',2,'','lookup failed\n',2), ('missing-command',127,'','missing\n',2),
    ('contradictory-none',1,'unexpected\n','',2),
]):
    name, rc, stdout, stderr, want = spec
    case = S/('process-'+name); case.mkdir(exist_ok=True)
    script = common + '\nM=' + repr(posix(case)) + "\nFPAT='local-test-only'\n"
    script += f"pgrep() {{ printf '%s' {repr(stdout)}; printf '%s' {repr(stderr)} >&2; return {rc}; }}\n"
    script += 'rc=0; fresh_idle local || rc=$?; printf "RESULT=%s\\n" "$rc"\n'
    f = case/'check.sh'; f.write_text(script, encoding='utf-8', newline='\n')
    out = cmd([BASH,'--noprofile','--norc',str(f)]).decode('utf-8')
    assert f'RESULT={want}' in out
    save('boundary-process-'+name+'.txt',out)
    root_cases.append({'name':'process-'+name, 'result':want})

good = {'at':'2026-11-10T02:19:30+00:00','green':21,'unknown':1,'red':0,
        'redCoins':[],'unknownCoins':['KRW-BTC'],'rechecked':['KRW-BTC'],
        'rows':{'KRW-BTC':{'dbTo':'x','exTo':None,'exErr':'net','count':500,'rechecked':True}}}
for name, cron, row, old_tb, new_tb, stale, want in [
    ('normal',1,1,0,0,0,0), ('no-cron',0,1,0,0,0,1), ('new-traceback',1,1,0,1,0,1),
    ('old-traceback',1,1,1,0,0,0), ('no-row',1,0,0,0,0,1), ('retained-exTo',1,1,0,0,1,1),
]:
    case=S/('after-'+name);case.mkdir(exist_ok=True)
    paths={k:posix(case/(k+'.txt')) for k in ['SYSLOG','RUNLOG','JLOG']}
    script = common + '\nM=' + repr(posix(case)) + '\n'
    script += '\n'.join(k+'='+repr(v) for k,v in paths.items())+'\n'
    script += 'python3() { '+repr(posix(Path(sys.executable)))+' -B -X utf8 "$@"; }\n'
    script += "printf 'old cron\\n' > \"$SYSLOG\"; printf 'old run\\n' > \"$RUNLOG\"; printf 'old row\\n' > \"$JLOG\"\n"
    if old_tb: script += "printf 'Traceback OLD\\n' >> \"$RUNLOG\"\n"
    script += 'n21mark apply || exit 20\n'
    if cron: script += "printf 'Nov 10 11:19:01 host CRON[42]: (user) CMD (/usr/bin/python3 scripts/prospective/start_66.py freshness --save)\\n' >> \"$SYSLOG\"\n"
    if new_tb: script += "printf 'Traceback NEW\\n' >> \"$RUNLOG\"\n"
    if row:
        obj=json.loads(json.dumps(good)); obj['rows']['KRW-BTC']['exTo']='old-success' if stale else None
        script += 'printf "%s\\n" '+repr(json.dumps(obj))+' >> "$JLOG"\n'
    script += 'rc=0; n21after apply || rc=$?; printf "RESULT=%s\\n" "$rc"\n'
    f=case/'check.sh'; f.write_text(script,encoding='utf-8',newline='\n')
    out=cmd([BASH,'--noprofile','--norc',str(f)]).decode('utf-8')
    assert f'RESULT={want}' in out, (name,out)
    save('boundary-after-'+name+'.txt',out)
    root_cases.append({'name':'after-'+name, 'result':want})
report['root_common_boundary_checks'] = root_cases
report['not_executed'] = ['SSH/server/DB/HTTP','actual pgrep/Linux race','product/PG/full regression','Gradle/bootJar','full standin repeat','git checkout/commit/push']
save('review.json',json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'payloads':len(items),'folders':folders,'blocks':len(hashes),'root_boundaries':len(root_cases),'status':'PASS'},ensure_ascii=False))
