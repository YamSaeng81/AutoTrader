"""Review fixed #26 source with local extraction, syntax, guards and inert SSH stand-in."""
from pathlib import Path
import difflib,hashlib,json,re,shlex,subprocess,sys
ROOT=Path(__file__).resolve().parents[3];OUT=Path(__file__).resolve().parent
BASE=ROOT/'core-engine/build/k26-accept-de5a7e4';BASE.mkdir(parents=True,exist_ok=True)
TMP=BASE/('run-'+str(len(list(BASE.glob('run-*')))+1));TMP.mkdir()
REF='de5a7e4d0d5d4f6ac6820c57ce5a5dce15e8642a'
E=ROOT/'docs/evidence/n20-k26-final-1899b49'
D2=ROOT/'docs/evidence/n20-d2-standin-1899b49/blocks-d2'
BASH='C:/Program Files/Git/bin/bash.exe'
OLD='ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8'
NEW='1899b49f633f8aefdc66b15c42debdaba8b3852c'
IMAGE='sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6'
def git(*a):return subprocess.check_output(['git',*a],cwd=ROOT)
def read(p):return p.read_text(encoding='utf-8')
def sha(b):return hashlib.sha256(b).hexdigest()
def rows(p):
    out={}
    for line in read(p).splitlines():
        m=re.match(r'^([0-9a-f]{64})\s+\*?(.+)$',line)
        if m:out[m[2].removeprefix('./')]=m[1]
    return out
def save(n,x):(OUT/n).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
paths=[];counts={}
for name in ['n20-k26-final-1899b49','k25-result-review-b7f90ce']:
    folder=ROOT/'docs/evidence'/name;entries=rows(folder/'SHA256SUMS');assert entries
    counts[name]=len(entries)
    for f,h in entries.items():
        p=folder/f;assert sha(p.read_bytes())==h,(name,f);paths.append((p,h))
batch=subprocess.check_output(['git','cat-file','--batch'],input=''.join(REF+':'+p.relative_to(ROOT).as_posix()+'\n' for p,h in paths).encode(),cwd=ROOT)
pos=0
for p,h in paths:
    end=batch.index(b'\n',pos);hdr=batch[pos:end].split();assert hdr[1]==b'blob'
    n=int(hdr[2]);data=batch[end+1:end+1+n];assert sha(data)==h and data==p.read_bytes(),p
    pos=end+1+n+1
assert pos==len(batch)

assert subprocess.run(['git','merge-base','--is-ancestor',OLD,NEW],cwd=ROOT).returncode==0
assert git('rev-parse','refs/remotes/origin/candidate/n20-held-mark-ee509cf').decode().strip()==NEW
assert git('diff','--name-only',OLD,NEW).decode().splitlines()==[
 'web-api/src/main/java/com/cryptoautotrader/api/service/HeldMarkRefresher.java',
 'web-api/src/test/java/com/cryptoautotrader/api/service/N20HeldMarkItemIsolationTest.java']
jarhashes={}
for name,path,expected in [
 ('ee509cf',Path('D:/tmp/n19v2/jars/ee509cf.jar'),'4f4f42aa058c34a15e438ba9048a9f94c49b2963a8bd72c5eb1ca3d5fbd6f4ec'),
 ('1899b49',Path('D:/tmp/n20c/jars/1899b49.jar'),'74fd7c835ee7edf521496c2117522260b3d303f8aa6b6a18a59cce4901452091')]:
    assert sha(path.read_bytes())==expected;jarhashes[name]=expected
jar_diff=read(E/'check/jar-diff-ee509cf-1899b49-reused.txt')
assert jar_diff.splitlines()==[
 '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher$Book.class',
 '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher.class','(끝) 881 881']

# Execute the submitted extractor against the immutable commit, writing only inside this workspace.
extract=TMP/'extracted'
r=subprocess.run([sys.executable,'-B','-X','utf8',str(E/'runner/extract.py'),REF,str(extract)],cwd=ROOT,capture_output=True)
(OUT/'extract-run.txt').write_bytes(r.stdout+r.stderr);assert r.returncode==0,r.stderr
meta=json.loads(read(extract/'blocks.json'));assert meta['source']==REF
assert len([v for v in meta.values() if isinstance(v,dict)])==10
source=git('show',REF+':docs/LIMITED_LIVE_TEST_PLAN.md').decode().replace('\r\n','\n')
blocks=re.findall(r'^```bash\n(.*?)^```',source,re.M|re.S);assert len(blocks)==149
for name,row in meta.items():
    if not isinstance(row,dict):continue
    p=extract/(name+'.sh');assert p.read_bytes()==blocks[row['index']].encode() and sha(p.read_bytes())==row['sha256'] and row['expected_match']
    if name.startswith('U'):
        assert p.read_bytes()==(E/'blocks-k26'/p.name).read_bytes()
        r=subprocess.run([BASH,'--noprofile','--norc','-n',str(p)],capture_output=True);assert r.returncode==0,r.stderr
pins=json.loads(read(ROOT/'docs/evidence/k25-result-review-b7f90ce/n06-blocks.json'))
for row in pins['blocks']:assert sha(blocks[row['index_zero_based']].encode())==row['new_sha256']
assert meta['F']['sha256']==pins['blocks'][0]['new_sha256']

# Whitelist the 12 changed lines; all other block lines and all observation functions stay unchanged.
changes=[]
for p in sorted((E/'blocks-k26').glob('U*.sh')):
    old=read(D2/p.name).splitlines();new=read(p).splitlines();assert len(old)==len(new)
    pairs=[(a,b) for a,b in zip(old,new) if a!=b]
    assert len(pairs)==(4 if p.name=='U변수.sh' else 2 if p.name=='U0.sh' else 1),(p.name,pairs)
    if p.name=='U변수.sh':
        assert pairs[0][0].startswith('M=~/maint-n20;') and pairs[0][0].split('   #',1)[0].replace('UB=N20D2','UB=98')==pairs[0][1].split('   #',1)[0]
        assert pairs[1][0].startswith('IMGOLD=미정 ') and pairs[1][1].startswith('IMGOLD='+IMAGE+' ')
        assert pairs[2][0].split('#',1)[0]==pairs[2][1].split('#',1)[0]
        assert pairs[3][0].startswith('# N20 줄 관측') and pairs[3][1].startswith('# N20 줄 관측')
    elif p.name=='U0.sh':
        assert pairs[0][0].split(' || stop ',1)[0].replace('= N20D2 ]','= 98 ]')==pairs[0][1].split(' || stop ',1)[0]
        assert pairs[1][0].split('stop ',1)[0]==pairs[1][1].split('stop ',1)[0]
    else:
        assert pairs[0][0].split(' || stop ',1)[0].replace('= N20D2 ]','= 98 ]')==pairs[0][1].split(' || stop ',1)[0]
    changes.extend({'block':p.name,'before':a,'after':b} for a,b in pairs)
assert len(changes)==12
for name in ['U3','U4']:
    text=read(extract/(name+'.sh'));lines=text.splitlines()
    at=next(i for i,l in enumerate(lines) if l.startswith('$DC logs --no-color --since'))
    assert 'echo "== HOLD' in lines[at+1] and 'holdobs ' in lines[at+2] and '🔴 P0' in lines[at+3]
    assert sum(l.startswith('holdobs ') for l in lines)==1
save('changed-lines.json',changes)

# Run only the real guard lines, with inert local function definitions for the U0 type checks.
guard_results=[]
for name in ['U0','U1','U2','U3','U4','U뒤관측','U되돌림']:
    line=next(l for l in read(extract/(name+'.sh')).splitlines() if l.startswith('[ ') and '${UB:-}' in l)
    for value in ['98','N20D2','92','']:
        code='set -eu\nstop() { echo "STOP $*"; exit 1; }\n'
        code+='M="$HOME/maint-n20"; NEW='+NEW+'; BTCIN=local_only; UB='+shlex.quote(value)+'\n'
        code+='\n'.join(n+'() { :; }' for n in ['sid203','newsobs','httpobs','holdobs','entryobs','n17obs','n19obs','n20obs'])+'\n'
        p=TMP/('guard-'+name+'-'+(value or 'empty')+'.sh');p.write_text(code+line+'\necho PASSED\n',encoding='utf-8',newline='\n')
        r=subprocess.run([BASH,'--noprofile','--norc',str(p)],capture_output=True)
        assert r.returncode==(0 if value=='98' else 1),(name,value,r.stderr)
        guard_results.append({'block':name,'UB':value,'exit':r.returncode})
case=next(l for l in read(extract/'U0.sh').splitlines() if l.startswith('case "$IMGOLD"'))
for value in [IMAGE,'미정']:
    p=TMP/('image-'+str(len(guard_results))+'.sh');p.write_text('set -eu\nstop() { echo STOP; exit 1; }\nIMGOLD='+shlex.quote(value)+'\n'+case+'\n',encoding='utf-8',newline='\n')
    r=subprocess.run([BASH,'--noprofile','--norc',str(p)],capture_output=True)
    assert r.returncode==(0 if value==IMAGE else 1)
    guard_results.append({'block':'U0 IMGOLD','input':value,'exit':r.returncode})
save('guard-run.json',guard_results)

# Inert shell-function SSH replacement never evaluates its arguments; no external SSH runs.
runner=TMP/'runner';runner.mkdir();(runner/'blocks').mkdir()
for p in extract.iterdir():(runner/'blocks'/p.name).write_bytes(p.read_bytes())
calls=TMP/'fake-ssh-calls.txt'
original=read(E/'runner/run.sh')
assert original.count('D=/d/tmp/k26run; B=$D/blocks')==1
local=original.replace('D=/d/tmp/k26run; B=$D/blocks','D='+shlex.quote(runner.as_posix())+'; B=$D/blocks',1)
assert local.count('PYTHONIOENCODING=utf-8 python -I - "$B"')==1
local=local.replace('PYTHONIOENCODING=utf-8 python -I - "$B"','PYTHONIOENCODING=utf-8 '+shlex.quote(Path(sys.executable).as_posix())+' -I - "$B"',1)
assert local.count('ssh -o BatchMode=yes cat-prod ')==1
local=local.replace('ssh -o BatchMode=yes cat-prod ','root_fake_ssh -o BatchMode=yes cat-prod ',1)
assert local.count('set -u\n')==1
local=local.replace('set -u\n','root_fake_ssh() { printf "called\\n" >> '+shlex.quote(calls.as_posix())+'; echo "FAKE SSH: no server command executed"; return 7; }\nset -u\n',1)
copy=TMP/'run-local.sh';copy.write_text(local,encoding='utf-8',newline='\n')
results=[]
def run(args,expected,new_calls):
    before=len(calls.read_text().splitlines()) if calls.exists() else 0
    r=subprocess.run([BASH,'--noprofile','--norc',str(copy),*args],capture_output=True)
    assert r.returncode==expected,(args,r.returncode,r.stdout,r.stderr)
    after=len(calls.read_text().splitlines()) if calls.exists() else 0
    assert after-before==new_calls,(args,before,after)
    (TMP/('runner-case-'+str(len(results))+'.txt')).write_bytes(r.stdout+r.stderr)
    results.append({'args':args,'exit':r.returncode,'fake_ssh_calls':new_calls})
run(['U3','deploy'],7,1)
run(['U3','rollback'],7,1)
run(['U3','deploy'],2,0)
run(['U3','deploi'],2,0)
run(['U9','deploy'],2,0)
p=runner/'blocks/U4.sh';original_block=p.read_bytes();p.write_bytes(original_block+b'# local corruption stand-in\n')
run(['U4','deploy'],2,0);p.write_bytes(original_block)
run(['U3','deploy','retry1'],7,1)
assert {p.name for p in (runner/'out').iterdir()}=={'out-U3-deploy.txt','out-U3-rollback.txt','out-U3-deploy-retry1.txt'}
save('runner-run.json',results)
save('review.json',{'source':REF,'manifests_worktree_and_git':counts,'plan_blocks':149,'fixed_extraction_blocks':10,
 'D2_changed_lines':12,'candidate':NEW,'base':OLD,'rollback_image':IMAGE,'jar_hashes':jarhashes,
 'reused_jar_diff':'HeldMarkRefresher + $Book; changed 2; 881 -> 881','syntax':8,'guard_cases':len(guard_results),'runner_cases':len(results),
 'runner_substitutions':['workspace output root','absolute known Python','inert shell-function fake SSH, return 7, never evaluates command arguments'],
 'N20_server_authorized_after_review':True,'N20_runtime_complete':False,'F_pins_S_unchanged':True,
 'not_executed':['SSH/server','DB','Docker','network','product tests','Gradle','jar build','full deployment stand-in']})
print(json.dumps({'manifests':counts,'extracted':10,'D2_changed_lines':12,'syntax':8,'guard_cases':len(guard_results),'runner_cases':len(results)}))
