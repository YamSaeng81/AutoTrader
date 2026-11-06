"""Validate v1.129 documentation/F pins and seal this review; no server execution."""
from pathlib import Path
import hashlib,json,re,subprocess
ROOT=Path(__file__).resolve().parents[3];OUT=Path(__file__).resolve().parent
REF='b7f90cec7b8067bf5981212acf345f0e8f5adbb2'
BASH='C:/Program Files/Git/bin/bash.exe'
NAMES=['LIMITED_LIVE_TEST_PLAN.md','LIVE_READINESS.md','NOVEMBER_LIVE_UPGRADE.md','OPS_DAILY_CHECK.md','PROGRESS.md']
def git(*a):return subprocess.check_output(['git',*a],cwd=ROOT)
def old(n):return git('show',REF+':docs/'+n).decode().replace('\r\n','\n')
def cur(n):return (ROOT/'docs'/n).read_text(encoding='utf-8')
def blocks(s):return re.findall(r'^```bash\n(.*?)^```',s,re.M|re.S)
def sha(b):return hashlib.sha256(b).hexdigest()
a=blocks(old(NAMES[0]));b=blocks(cur(NAMES[0]));assert len(a)==len(b)==141
assert [i for i,(x,y) in enumerate(zip(a,b)) if x!=y]==[12,13]
pins=json.loads((OUT/'n06-blocks.json').read_text(encoding='utf-8'))
assert len(pins['blocks'])==15 and sum(r['changed'] for r in pins['blocks'])==2
for r in pins['blocks']:
    i=r['index_zero_based'];assert r['old_sha256']==sha(a[i].encode()) and r['new_sha256']==sha(b[i].encode())
assert pins['final_S']=='unfixed until 2026-11-30' and pins['N18_installation_check_in_F1'] is True
new='ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8'
img='sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6'
assert re.search(r'^NEW='+new+r' ',b[12],re.M)
assert re.search(r'^IMGF1='+img+r' ',b[13],re.M)
assert '$(cat ~/maint-n19/image-new.txt)' in b[13]
assert '{ git rev-parse HEAD;' in b[13] and '"$(head -1 "$M/git-f1.txt")" = "$NEW"' in b[13]
assert 'HEAD 가 ee509cf 가 아니거나' in b[13]
assert 'INSTALLED_SHA' in b[13] and 'ff0a5d7d' in b[13] and '653b22ba' in b[13]
# Only assignment comment/value and the image comparison line changed in the two blocks.
for idx,prefixes in [(12,['NEW=']),(13,['IMGF1=','[ "$(docker inspect --format','[ "$(head -1 "$M/git-f1.txt")'])]:
    x=a[idx].splitlines();y=b[idx].splitlines();assert len(x)==len(y)
    changes=[(u,v) for u,v in zip(x,y) if u!=v]
    assert len(changes)==len(prefixes)
    assert all(any(u.startswith(p) and v.startswith(p) for p in prefixes) for u,v in changes)
    p=ROOT/'core-engine/build/k25-result-review-b7f90ce'/('F-check-'+str(idx)+'.sh')
    p.write_text(b[idx],encoding='utf-8',newline='\n')
    r=subprocess.run([BASH,'--noprofile','--norc','-n',str(p)],capture_output=True)
    assert r.returncode==0,r.stderr
assert blocks(cur('OPS_DAILY_CHECK.md'))==blocks(old('OPS_DAILY_CHECK.md'))
assert len(blocks(cur('OPS_DAILY_CHECK.md')))==7
assert cur('LIVE_ENTRY_PREREG.md')==old('LIVE_ENTRY_PREREG.md')
plan=cur(NAMES[0]);assert plan.splitlines()[2].startswith('> **v1.129 ')
assert plan.count('**97.')==1 and plan.index('**97.')<plan.index('## 6. 시작·중단·종료 조건')
progress=cur('PROGRESS.md')
assert '> **마지막 갱신**: 2026-11-06 (323차,' in progress
assert '> **이전 갱신**: 2026-11-06 (322차,' in progress
nov=cur('NOVEMBER_LIVE_UPGRADE.md')
def row(s,key):return next(l for l in s.splitlines() if l.startswith('| '+key+' |'))
assert new in row(nov,'운영 backend') and img in row(nov,'운영 이미지')
assert '| N19 | 완료 · 운영 반영 확인 |' in nov
assert '| N20 | P2 · 로컬 수용·#26 최종안 준비 |' in nov
for key in ['N06','N19','N20']:assert row(nov,key).count('|')==row(old('NOVEMBER_LIVE_UPGRADE.md'),key).count('|')
for key in ['N15','N16','N17','N18']:assert row(nov,key)==row(old('NOVEMBER_LIVE_UPGRADE.md'),key)
assert sorted(git('diff','--name-only').decode().splitlines())==sorted('docs/'+n for n in NAMES)
r=subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True);assert r.returncode==0,r.stdout+r.stderr
review=json.loads((OUT/'review.json').read_text(encoding='utf-8'))
assert review['D2_cases']==13 and review['N19_runtime_accepted'] is True
result={'version':'1.129','section':97,'progress':323,'plan_blocks':141,'changed_indices':[12,13],
        'F_changed':2,'F_unchanged':13,'other_plan_blocks_unchanged':139,'OPS_blocks_unchanged':7,
        'F_syntax':2,'operating_head':new,'operating_image':img,'image_record':'~/maint-n19/image-new.txt',
        'final_S':'unfixed','N20_D2':'local verification accepted; #26 final fixed source pending','git_diff_check':0}
(OUT/'doc-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
files=sorted((p for p in OUT.iterdir() if p.is_file() and p.name!='SHA256SUMS'),key=lambda p:p.name)
rows=[sha(p.read_bytes())+'  '+p.name for p in files]
(OUT/'SHA256SUMS').write_text('\n'.join(rows)+'\n',encoding='utf-8')
print(json.dumps({'F_changed':2,'F_unchanged':13,'other_plan_unchanged':139,'OPS_unchanged':7,'review_files':len(rows),'diff_check':0}))
