"""Review preserved #25/N03/D2 artifacts. No server or full harness execution."""
from pathlib import Path
import hashlib,json,re,subprocess

ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
REF='b7f90cec7b8067bf5981212acf345f0e8f5adbb2'
BASH='C:/Program Files/Git/bin/bash.exe'
K=ROOT/'docs/evidence/k25-result-ee509cf'
N=ROOT/'docs/evidence/n03-1106-1000'
D=ROOT/'docs/evidence/n20-d2-standin-1899b49'
OLD='7a944723c2959898226c2ad80f6b079ab5678296'
NEW='ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8'
IMAGE='sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6'

def git(*a):return subprocess.check_output(['git',*a],cwd=ROOT)
def read(p):return p.read_text(encoding='utf-8')
def sha(b):return hashlib.sha256(b).hexdigest()
def hashes(p):
    rows={}
    for s in read(p).splitlines():
        m=re.match(r'^([0-9a-f]{64})\s+\*?(.+)$',s)
        if m:
            f=m[2].removeprefix('./');assert f not in rows,(p,f)
            rows[f]=m[1]
    return rows

# Verify each manifest against worktree bytes and committed blob bytes in one batch.
paths=[];counts={}
for name in ['k25-result-ee509cf','n03-1106-1000','n20-d2-standin-1899b49','n20-d1-review-7542a62']:
    folder=ROOT/'docs/evidence'/name;rows=hashes(folder/'SHA256SUMS');assert rows
    for f,h in rows.items():
        p=folder/f;assert p.is_file() and sha(p.read_bytes())==h,(name,f)
        paths.append((p,h))
    counts[name]=len(rows)
requests=''.join(REF+':'+p.relative_to(ROOT).as_posix()+'\n' for p,h in paths).encode()
batch=subprocess.check_output(['git','cat-file','--batch'],input=requests,cwd=ROOT)
pos=0
for p,h in paths:
    end=batch.index(b'\n',pos);head=batch[pos:end].split();assert head[1]==b'blob',(p,head)
    size=int(head[2]);data=batch[end+1:end+1+size];assert sha(data)==h and data==p.read_bytes(),p
    pos=end+1+size+1
assert pos==len(batch)

m=K/'maint-n19'
server=hashes(K/'out/server-list-sha.txt');local=hashes(K/'out/local-copy.sha256')
assert len(local)==73 and len(list(m.iterdir()))==73
for f,h in local.items():assert server[f]==h==sha((m/f).read_bytes()),f
for f in ['image-new.txt','git-deploy.txt']:
    assert read(m/f).splitlines()[0]==(IMAGE if f.startswith('image') else NEW)
assert read(m/'git-before.txt').splitlines()[0]==OLD
assert read(m/'image-before.txt').strip()=='sha256:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6'
jd=read(m/'jar-diff.txt').splitlines()
assert jd==['변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$1.class',
 '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$PriceSnapshot.class',
 '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService.class','(끝) 881 881']
js=read(m/'jar-sha.txt')
assert 'bb67b521da94c6975243f9ff65f449ee6f0abd305ae829fadeb5bcadb0b849ea' in js
assert '89b4ca170921776a7ca2aa659da1bda26b0e958ab82801f8a5c2efafd7847ec9' in js

# Fixed execution source is independent of current main/F pin updates.
source=git('show','342f461359b9e3c9d5941f6df95b905b65dce9a7:docs/LIMITED_LIVE_TEST_PLAN.md').decode().replace('\r\n','\n')
blocks=re.findall(r'^```bash\n(.*?)^```',source,re.M|re.S)
meta=json.loads(read(K/'blocks/blocks.json'))
assert meta['_nblocks']==len(blocks)==141
for name,row in meta.items():
    if name.startswith('_'):continue
    b=(K/'blocks'/(name+'.sh')).read_bytes()
    assert b.decode()==blocks[row['index']] and sha(b)==row['sha256'],name
assert '재기동 진행' in read(K/'out/operator-confirm.txt')
for name in ['U0','U1','U2','U3','U4','U뒤관측']:
    text=read(K/('out-'+name+'.txt'))
    assert re.findall(r'\[로컬 끝\].*rc=(\d+)',text)==['0'],name

svc={f:read(m/f).splitlines() for f in ['svc-before.txt','svc-deploy.txt','svc-after30.txt']}
for f in svc:assert len(svc[f])==5
assert svc['svc-deploy.txt']==svc['svc-after30.txt']
assert svc['svc-before.txt'][1:]==svc['svc-deploy.txt'][1:]
assert svc['svc-deploy.txt'][0]=='backend '+IMAGE+' 2026-11-06T01:32:41.140275884Z'
assert len({(m/f).read_bytes() for f in ['s-before.txt','s-deploy.txt','s-after30.txt']})==1
assert read(m/'s-before.txt').strip()=='66 | 63 | 3 | -'
q=[]
for f in ['q-before.txt','q-deploy.txt','q-after30.txt']:
    text=read(m/f);q.append(text.split('== Q3',1)[1].split('== Q10',1)[0])
assert q[0]==q[1]==q[2]
for f in ['errors-deploy.txt','errors-after30.txt','errors-all-deploy.txt','errors-all-after30.txt']:
    assert (m/f).read_bytes()==b''
# errors-all already excludes the three known-market 404s; the raw full logs remain server-only.
assert read(m/'btc-before.txt').strip()=='true|11-06 09:00|11-06 09:00|20/20'
assert read(m/'btc-deploy.txt')==read(m/'btc-before.txt')
assert read(m/'btc-after30.txt').strip()=='true|11-06 10:00|11-06 10:00|20/20'
assert '구간에 관측이 없다' in read(m/'alerts-after30.txt')
supp=read(K/'out-alerts-supp.txt')
assert all(x in supp for x in ['관측 1회','11-06 02:19','KRW-BLAST','🟢 21 / ⚪ 0 / 🔴 1','종료 1'])
for suffix in ['deploy','after30','20261106-1140']:
    text=read(m/('holdobs-'+suffix+'.txt'))
    assert '연결 획득 시간 초과 줄 0건' in text and '그 밖의 장기 대여 경고 0건' in text
after=read(K/'out-U뒤관측.txt')
assert 'HTTP 403 ×2' in after and '짝 2' in after and '짝 미확인 0' in after
assert len(re.findall(r'^\S+ \S+: 완료 [1-9]\d*·미완료 0',after,re.M))==6
assert all('미관측' in read(m/(tag+'obs-20261106-1140.txt')) for tag in ['entry','n17','n19'])
assert sha((m/'crontab-before.txt').read_bytes())=='f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b'

# Automatic report original bytes agree with the saved server hash listing.
nr=hashes(N/'server-files.txt')
for f in ['20261106-1000.txt','20261106-1000.txt.msg','20261106-1000.txt.raw','last_send']:
    assert sha((N/f).read_bytes())==nr[f]
report=read(N/'20261106-1000.txt');msg=read(N/'20261106-1000.txt.msg')
assert report.splitlines()==msg.splitlines()+read(N/'last_send').splitlines()
assert '⚪' not in report and 'backend 기동' not in report and 'ERROR 로그 범위 미확보' not in report
assert all(x in report for x in ['점검 스크립트 c669ccdc6e24','🔴 미분류 ERROR 1건','B1 true','20/20','SID 203: CREATED','재생성 공백 없음'])
errs=read(N/'error_lines.txt').splitlines();assert len(errs)==7
assert sum('Connection reset' in x for x in errs)==1 and '13:00:29.675' in '\n'.join(errs)
raw=read(N/'20261106-1000.txt.raw')
dbrows=[x for x in raw.splitlines() if re.match(r'^[a-z][a-z0-9_]*\|',x)]
assert len(dbrows)==32 and all(x.count('|')==1 for x in dbrows)
assert len({x.split('|',1)[0] for x in dbrows})==32
ledger=read(N/'ledger-20261106.txt')
assert 'balance_diff' in ledger and '20200.00' in ledger and 'CREATED' in ledger and '== 끝' in ledger

# D2 = previously reviewed move + version guard + four purely textual label fixes.
d1=ROOT/'docs/evidence/n20-candidate-1899b49/blocks-draft'
labels=[('초안 D1 은 실행하지 않는다','초안 D1·D2 는 실행하지 않는다'),
 ('N20 초안 D1 판이 아님','N20 초안 D2 판이 아님'),
 ('초안 D1(미승인','초안 D2(미승인'),('초안 D1 —','초안 D2 —')]
for p in sorted((D/'blocks-d2').glob('U*.sh')):
    expected=read(d1/p.name).replace('N20D1','N20D2')
    if p.name in ['U3.sh','U4.sh']:
        ls=expected.splitlines(keepends=True)
        i=next(i for i,l in enumerate(ls) if l.startswith('echo "== HOLD 질의 장기 대여'))
        three=''.join(ls[i:i+3]);assert '🔴 P0' in three
        expected=expected.replace(three,'',1);ls=expected.splitlines(keepends=True)
        i=next(i for i,l in enumerate(ls) if l.startswith('$DC logs --no-color --since '))
        ls.insert(i+1,three);expected=''.join(ls)
    for a,b in labels:expected=expected.replace(a,b)
    assert read(p)==expected,p
    r=subprocess.run([BASH,'--noprofile','--norc','-n',str(p)],capture_output=True)
    assert r.returncode==0,(p,r.stderr)
inputs=hashes(D/'harness/blocks-input.sha256')
for f,h in inputs.items():
    p=D/'blocks-d2'/f if f.startswith('U') else K/'blocks'/f
    assert sha(p.read_bytes())==h,f
assert len(inputs)==10

def rc(s):return int(re.findall(r'\[대역 끝\] rc=(\d+)',s)[-1])
cases=[]
for stage in ['u3','u4']:
    for kind in ['normal','errto','warnto','causedto','lease','othererr']:
        folder=D/'runs'/('d2-'+stage+'-'+kind);assert not (folder/'forbidden.log').read_bytes()
        text=read(folder/('out-'+stage.upper()+'.txt'))
        timeout=kind in ['errto','warnto','causedto'];expected=1 if timeout or kind=='othererr' else 0
        assert rc(text)==expected and ('🔴 P0' in text)==timeout,(stage,kind)
        if kind=='lease':assert '⚪ 장기 대여 경고' in text
        if expected:
            assert not (folder/('m-ok-'+stage.upper()+('-deploy' if stage=='u3' else ''))).exists()
        cases.append({'stage':stage,'input':kind,'exit':expected,'P0':timeout})
folder=D/'runs/d2-rollback-p0'
assert not (folder/'forbidden.log').read_bytes()
assert '되돌림 필요: 검증 세션 상태 바뀜' in read(folder/'out-U3.txt')
assert rc(read(folder/'out-U되돌림.txt'))==0
text=read(folder/'out-U3-rollback.txt')
assert rc(text)==1 and '🔴 P0' in text
assert not (folder/'m-ok-U3-rollback').exists()
cases.append({'stage':'u3-rollback','input':'ERROR timeout after explicit rollback','exit':1,'P0':True})
(OUT/'d2-results.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
result={'review_ref':REF,'manifests_worktree_and_git':counts,'server_small_file_copies':73,
 'server_only_manifest_files':sorted(set(server)-set(local)),
 'fixed_execution_source':'342f461359b9e3c9d5941f6df95b905b65dce9a7 §92 UB=92','execution_blocks':10,
 'operating_head':NEW,'operating_image':IMAGE,'image_record':'~/maint-n19/image-new.txt',
 'started_at':'2026-11-06T01:32:41.140275884Z','server_jar_changed':3,'jar_items':[881,881],
 'N19_runtime_accepted':True,'natural_N15_N16_N17_N19':'unobserved; not induced',
 'operator_reply':'developer-preserved confirmation note; root did not independently observe live reply',
 'P0_raw_log_recount':'developer report; 3 full saved logs remain server-only, root checked saved holdobs outputs and manifests',
 'N03_server_original_copies':4,'phone_receipt':'unconfirmed','D2_cases':13,'D2_input_hashes':10,'D2_syntax':8,
 'D2_accepted':'local verification; final #26 source still to be prepared',
 'not_executed':['SSH/server','DB','network','full stand-in rerun','product tests','Gradle','jar build']}
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifests':counts,'server_copies':73,'execution_blocks':10,'D2_cases':13,'D2_syntax':8},ensure_ascii=False))
