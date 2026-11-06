"""Review preserved D1 stand-in results; reproduce P0 ordering with only local fragments."""
from pathlib import Path
import difflib, hashlib, json, re, shlex, subprocess, sys
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
TMP=ROOT/'core-engine/build/n20-d1-review-7542a62'
TMP.mkdir(parents=True,exist_ok=True)
REF='7542a62'
E=ROOT/'docs/evidence/n20-d1-standin-1899b49'
D1=ROOT/'docs/evidence/n20-candidate-1899b49/blocks-draft'
BASH='C:/Program Files/Git/bin/bash.exe'
def git(*a):return subprocess.check_output(['git',*a],cwd=ROOT)
def read(p):return p.read_text(encoding='utf-8')
def sha(b):return hashlib.sha256(b).hexdigest()
def manifest(n):
    folder=ROOT/'docs/evidence'/n;rows=read(folder/'SHA256SUMS').splitlines()
    for row in rows:
        h,f=row.split(maxsplit=1);p=folder/f.lstrip('*').removeprefix('./')
        assert sha(p.read_bytes())==h,p
        assert p.read_bytes()==git('show',REF+':'+p.relative_to(ROOT).as_posix()),p
    return len(rows)
counts={n:manifest(n) for n in ['n20-d1-standin-1899b49','n20-accept-614bd5e']}
cases=sorted(p for p in (E/'runs').iterdir() if p.is_dir())
assert len(cases)==17
for p in cases:assert not (p/'forbidden.log').read_bytes(),p
assert not (E/'runs/s2-imgold-unset/calls.log').read_bytes()
def output(case,block):return read(E/'runs'/case/('out-'+block+'.txt'))
def rc(s):return int(re.findall(r'\[대역 끝\] rc=(\d+)',s)[-1])
normal={b:rc(output('s1b-normal',b)) for b in ['U0','U1','U2','U3','U4','U뒤관측']}
assert set(normal.values())=={0}
assert rc(output('s1b-normal','U3-early'))==1 and rc(output('s1b-normal','U4-early'))==1
assert '⚪ N20 보유 시세 보강' in output('s1b-normal','U4')
for case in ['s2-imgold-unset','s4a-window','s4b-noreport','s5a-btc','s5b-btc20','s5c-session','s5d-sid203','s5e-install','s5f-cron']:
    assert rc(output(case,'U0'))==1,case
assert rc(output('s4c-u1late','U1'))==1
assert rc(output('s3-jar-mismatch','U1'))==1 and rc(output('s3-jar-mismatch','U2'))==1
assert rc(output('s3-jar-mismatch','U1되돌림'))==0
assert '되돌림 필요: 검증 세션 상태 바뀜' in output('s6-rollback','U3')
assert rc(output('s6-rollback','U되돌림'))==0 and rc(output('s6-rollback','U3-rollback'))==0
assert 'ee509cf 복귀 확인' in output('s6-rollback','U3-rollback')
assert '이미 있는 출력' in output('s6-rollback','U3') or '대역 끝' in output('s6-rollback','U3')
assert '🔴 P0' not in output('s7-p0','U3') and rc(output('s7-p0','U3'))==1
assert '🔴 P0' in output('s7b-p0warn','U3') and rc(output('s7b-p0warn','U3'))==1
assert rc(output('s8-n20seen','U4'))==0 and 'N20: 미관측' not in output('s8-n20seen','U4')

# Compare retained extraction copies now; this is not a per-run source hash record.
for p in D1.glob('U*.sh'):
    assert p.read_bytes()==(Path('D:/tmp/n20c/blocks-draft')/p.name).read_bytes()
plan=git('show','342f461:docs/LIMITED_LIVE_TEST_PLAN.md').decode()
blocks=re.findall(r'^```bash\n(.*?)^```',plan,re.M|re.S)
meta=json.loads(read(Path('D:/tmp/k25run/blocks/blocks.json')))
for n in ['F','G']:
    b=(Path('D:/tmp/k25run/blocks')/(n+'.sh')).read_bytes()
    assert sha(b)==meta[n]['sha256'] and b.decode()==blocks[meta[n]['index']]

# D2 proposal: version guards throughout; move the existing three-line P0 inspection once.
texts={p.name:read(p) for p in sorted(D1.glob('U*.sh'))}
assert len(texts)==8
function=texts['U변수.sh'].split('holdobs() {',1)[1].split('\n}\n',1)[0]
function='holdobs() {'+function+'\n}\n'
changes=[];early={};errors={}
for n,src in texts.items():
    dst=src.replace('N20D1','N20D2')
    if n in ['U3.sh','U4.sh']:
        ls=src.splitlines(keepends=True)
        i=next(i for i,l in enumerate(ls) if l.startswith('echo "== HOLD 질의 장기 대여'))
        h=''.join(ls[i:i+3]);assert 'holdobs ' in h and '🔴 P0' in h
        early[n]=h
        # Preserve the general ERROR filter exactly for independent ordering checks.
        i=next(i for i,l in enumerate(ls) if l.startswith('grep " ERROR "'))
        errors[n]=''.join(ls[i:i+4]);assert 'stop ' in errors[n]
        assert dst.count(h)==1
        dst=dst.replace(h,'',1)
        lines=dst.splitlines(keepends=True)
        i=next(i for i,l in enumerate(lines) if l.startswith('$DC logs --no-color --since '))
        lines.insert(i+1,h);dst=''.join(lines)
        assert dst.count('holdobs "$M/')==1
        # Undo the move and marker changes to prove no other safety line was edited.
        undo=dst.replace(h,'',1)
        boundary='echo "== N15·N16 줄' if n=='U3.sh' else 'grep -q "시작 Catchup 완료"'
        # Other lease handling remains at the original location; compare line multiset too.
        assert sorted(src.replace('N20D1','N20D2').splitlines())==sorted(dst.splitlines())
        assert dst.index('holdobs "$M/') < dst.index('grep " ERROR "')
    p=TMP/'D2'/n;p.parent.mkdir(exist_ok=True);p.write_text(dst,encoding='utf-8',newline='\n')
    r=subprocess.run([BASH,'--noprofile','--norc','-n',str(p)],capture_output=True)
    assert r.returncode==0,(p,r.stderr)
    changes.extend(difflib.unified_diff(src.splitlines(keepends=True),dst.splitlines(keepends=True),fromfile='D1/'+n,tofile='D2/'+n))
(OUT/'D1-to-D2.diff').write_text(''.join(changes),encoding='utf-8')

# Only the unchanged holdobs + general ERROR snippets run. No F/G, docker, git, SQL or server command.
logs={
 'ERROR-timeout':'10:33:00.000 [scheduling-1] ERROR test - HikariPool-1 - Connection is not available, request timed out after 30000ms.\n',
 'WARN-timeout':'10:33:00.000 [scheduling-1] WARN test - HikariPool-1 - Connection is not available, request timed out after 30000ms.\n',
 'Caused-by-timeout':'Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.\n',
 'lease-only':'10:33:00.000 [HikariPool-1 housekeeper] WARN ProxyLeakTask - Connection leak detection triggered for conn1 on thread scheduling-1, stack trace follows\n\tat com.cryptoautotrader.api.service.SignalQualityService.evaluateHoldBaselineLoop(SignalQualityService.java:200)\n',
 'other-ERROR':'10:33:00.000 [scheduling-1] ERROR test - unrelated failure\n',
 'normal':'10:33:00.000 [scheduling-1] INFO test - normal\n'}
results=[]
for n in ['U3.sh','U4.sh']:
    for version in ['D1','D2']:
        for kind,log in logs.items():
            p=TMP/'mini'/(n[:-3]+'-'+version+'-'+kind);p.mkdir(parents=True,exist_ok=True)
            for f in ['app-deploy.log','app-after30.log']:(p/f).write_text(log,encoding='utf-8')
            (p/'hold-before.txt').write_text('baseline\n',encoding='utf-8')
            prefix='set -euo pipefail\nstop() { echo "STOP $*"; exit 1; }\n'
            prefix+='M='+shlex.quote(p.as_posix())+'; T=deploy; NOT404="DOES_NOT_MATCH"; NEWSERR="NO_NEWS_MATCH"\n'
            prefix+='python3() { '+shlex.quote(Path(sys.executable).as_posix())+' -B -X utf8 "$@"; }\n'+function
            seq=errors[n]+early[n] if version=='D1' else early[n]+errors[n]
            script=prefix+seq+'\necho "CONTINUE HQ=$HQ"\n'
            scriptpath=p/'fragment.sh';scriptpath.write_text(script,encoding='utf-8',newline='\n')
            r=subprocess.run([BASH,'--noprofile','--norc',str(scriptpath)],capture_output=True)
            out=r.stdout.decode();assert not r.stderr,r.stderr
            timeout='timeout' in kind
            p0=timeout and not(version=='D1' and kind=='ERROR-timeout')
            assert ('🔴 P0' in out)==p0,(n,version,kind,out)
            assert r.returncode==(1 if timeout or kind=='other-ERROR' else 0),(n,version,kind,out)
            if kind=='lease-only':assert 'CONTINUE HQ=3' in out
            if kind=='normal':assert 'CONTINUE HQ=0' in out
            (p/'stdout.txt').write_bytes(r.stdout)
            results.append({'block':n,'version':version,'input':kind,'exit':r.returncode,'P0':p0})
assert len(results)==24
(OUT/'ordering-run.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
result={'review_ref':git('rev-parse',REF).decode().strip(),'manifests_worktree_and_git':counts,
        'preserved_standin_runs':17,'preserved_forbidden_logs_empty':17,'D1_standin_results_accepted':True,
        'retained_D1_and_FG_copies_match_now':True,'per_run_source_hashes_preserved':False,
        'root_ordering_fragment_inputs':24,'D2_syntax':8,'D2_changes':'N20D2 guards; existing P0 inspection moves immediately after saved logs in U3/U4',
        'D2_server_approved':False,'N25_fixed_blocks_unchanged':True,
        'not_executed':['server','DB','network','full stand-in harness rerun','product tests','Gradle','jar build']}
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifests':counts,'standin_runs':17,'ordering_inputs':24,'D2_syntax':8}))
