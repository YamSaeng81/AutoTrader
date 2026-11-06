"""Validate the approval-only doc delta and seal the review; no server or product execution."""
from pathlib import Path
import hashlib,json,re,subprocess
ROOT=Path(__file__).resolve().parents[3];OUT=Path(__file__).resolve().parent
REF='de5a7e4d0d5d4f6ac6820c57ce5a5dce15e8642a'
NAMES=['LIMITED_LIVE_TEST_PLAN.md','LIVE_READINESS.md','NOVEMBER_LIVE_UPGRADE.md','OPS_DAILY_CHECK.md','PROGRESS.md']
def git(*a):return subprocess.check_output(['git',*a],cwd=ROOT)
def old(n):return git('show',REF+':docs/'+n).decode().replace('\r\n','\n')
def cur(n):return (ROOT/'docs'/n).read_text(encoding='utf-8')
def blocks(s):return re.findall(r'^```bash\n(.*?)^```',s,re.M|re.S)
def sha(b):return hashlib.sha256(b).hexdigest()
plan=cur(NAMES[0]);bs=blocks(plan)
assert len(bs)==149 and bs==blocks(old(NAMES[0]))
assert len(blocks(cur('OPS_DAILY_CHECK.md')))==7 and blocks(cur('OPS_DAILY_CHECK.md'))==blocks(old('OPS_DAILY_CHECK.md'))
assert cur('LIVE_ENTRY_PREREG.md')==old('LIVE_ENTRY_PREREG.md')
pins=json.loads((ROOT/'docs/evidence/k25-result-review-b7f90ce/n06-blocks.json').read_text(encoding='utf-8'))
for r in pins['blocks']:assert sha(bs[r['index_zero_based']].encode())==r['new_sha256']
assert pins['operating_head']=='ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8' and pins['final_S']=='unfixed until 2026-11-30'
assert plan.splitlines()[2].startswith('> **v1.131 ')
assert plan.count('**99.')==1 and plan.index('**99.')<plan.index('## 6. 시작·중단·종료 조건')
assert REF in plan.splitlines()[2]
progress=cur('PROGRESS.md')
assert '> **마지막 갱신**: 2026-11-06 (325차,' in progress
assert '> **이전 갱신**: 2026-11-06 (324차,' in progress
nov=cur('NOVEMBER_LIVE_UPGRADE.md')
def row(s,k):return next(l for l in s.splitlines() if l.startswith('| '+k+' |'))
for key in ['운영 backend','운영 이미지','N06','N15','N16','N17','N18','N19']:
    assert row(nov,key)==row(old('NOVEMBER_LIVE_UPGRADE.md'),key),key
assert '| N20 | P2 · 로컬 수용·#26 실행 승인/운영 대기 |' in nov
assert row(nov,'N20').count('|')==row(old('NOVEMBER_LIVE_UPGRADE.md'),'N20').count('|')
# The developer's final-prep commit changed only its docs and the two new evidence folders.
allowed={'docs/'+n for n in NAMES}
for p in filter(None,git('diff','--name-only','-z','b7f90cec7b8067bf5981212acf345f0e8f5adbb2',REF).decode().split('\0')):
    assert p in allowed or p.startswith('docs/evidence/n20-k26-final-1899b49/') or p.startswith('docs/evidence/k25-result-review-b7f90ce/'),p
assert sorted(filter(None,git('diff','--name-only','-z').decode().split('\0')))==sorted(allowed)
r=subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True);assert r.returncode==0,r.stdout+r.stderr
review=json.loads((OUT/'review.json').read_text(encoding='utf-8'))
assert review['fixed_extraction_blocks']==10 and review['guard_cases']==30 and review['runner_cases']==7
result={'version':'1.131','section':99,'progress':325,'approved_fixed_source':REF,'approved_section':98,'UB':98,
 'plan_blocks_unchanged':149,'OPS_blocks_unchanged':7,'F_blocks_unchanged':15,'runtime_pins_S_unchanged':True,
 'N20_state':'execution approved; runtime completion awaits actual results','git_diff_check':0}
(OUT/'doc-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
files=sorted((p for p in OUT.iterdir() if p.is_file() and p.name!='SHA256SUMS'),key=lambda p:p.name)
rows=[sha(p.read_bytes())+'  '+p.name for p in files]
(OUT/'SHA256SUMS').write_text('\n'.join(rows)+'\n',encoding='utf-8')
assert all(sha((OUT/s.split(maxsplit=1)[1]).read_bytes())==s.split(maxsplit=1)[0] for s in rows)
print(json.dumps({'plan_OPS_F_unchanged':[149,7,15],'review_files':len(rows),'diff_check':0}))
