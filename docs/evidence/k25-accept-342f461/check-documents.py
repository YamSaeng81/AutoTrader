"""Verify documentation-only approval and seal this review's files."""
from pathlib import Path
import hashlib,json,re,subprocess

ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
REF='342f461'
def old(n):return subprocess.check_output(['git','show',REF+':docs/'+n],cwd=ROOT).decode()
def cur(n):return (ROOT/'docs'/n).read_text(encoding='utf-8')
def blocks(s):return re.findall(r'^```bash\n(.*?)^```',s,re.M|re.S)
def sha(b):return hashlib.sha256(b).hexdigest()

plan=cur('LIMITED_LIVE_TEST_PLAN.md')
before=blocks(old('LIMITED_LIVE_TEST_PLAN.md'))
after=blocks(plan)
assert before==after and len(after)==141
assert blocks(old('OPS_DAILY_CHECK.md'))==blocks(cur('OPS_DAILY_CHECK.md'))
assert len(blocks(cur('OPS_DAILY_CHECK.md')))==7
assert old('LIVE_ENTRY_PREREG.md')==cur('LIVE_ENTRY_PREREG.md')
pins=json.loads((ROOT/'docs/evidence/night-review-303079b/n06-blocks.json').read_text(encoding='utf-8'))
assert len(pins['blocks'])==15 and pins['final_S']=='unfixed until 2026-11-30'
for row in pins['blocks']:
    assert sha(after[row['index_zero_based']].encode())==row['new_sha256']
assert plan.splitlines()[2].startswith('> **v1.125 ')
assert plan.count('**93.')==1 and plan.index('**93.')<plan.index('## 6. 시작·중단·종료 조건')
assert '342f461359b9e3c9d5941f6df95b905b65dce9a7의§92 UB=92' in plan
assert '> **마지막 갱신**: 2026-11-06 (315차,' in cur('PROGRESS.md')
assert '> **이전 갱신**: 2026-11-06 (314차,' in cur('PROGRESS.md')
nov=cur('NOVEMBER_LIVE_UPGRADE.md')
for key in ['운영 backend','운영 이미지','N15','N16','N17','N18']:
    assert next(x for x in nov.splitlines() if x.startswith('| '+key+' |'))==next(x for x in old('NOVEMBER_LIVE_UPGRADE.md').splitlines() if x.startswith('| '+key+' |'))
assert '| N19 | P1 · 로컬 수용·#25 실행 승인/운영 대기 |' in nov
assert '| N20 | P2 · 개발 조사 → 총괄 |' in nov
for key in ['N19','N20']:
    assert next(x for x in nov.splitlines() if x.startswith('| '+key+' |')).count('|')==next(x for x in old('NOVEMBER_LIVE_UPGRADE.md').splitlines() if x.startswith('| N19 |')).count('|')
files=sorted(subprocess.check_output(['git','diff','--name-only'],cwd=ROOT).decode().splitlines())
assert files==sorted('docs/'+n for n in ['LIMITED_LIVE_TEST_PLAN.md','LIVE_READINESS.md','NOVEMBER_LIVE_UPGRADE.md','OPS_DAILY_CHECK.md','PROGRESS.md'])
r=subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True)
assert r.returncode==0,r.stdout
result={'version':'1.125','section':93,'progress':315,'plan_bash_unchanged':141,'F_blocks_unchanged':15,'OPS_bash_unchanged':7,
    'fixed_execution_source':'342f461359b9e3c9d5941f6df95b905b65dce9a7','candidate':'ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8',
    'runtime_F_pins_S_preserved':True,'N19_local_accepted_not_runtime_complete':True,'N20_investigation_only':True,
    'tracked_docs':files,'git_diff_check':0,'no_server_DB_network_Gradle_Spring_tests_jar_build':True}
(OUT/'doc-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows=[sha(p.read_bytes())+'  '+p.name for p in sorted(OUT.iterdir(),key=lambda p:p.name) if p.is_file() and p.name!='SHA256SUMS']
(OUT/'SHA256SUMS').write_text('\n'.join(rows)+'\n',encoding='utf-8')
print(json.dumps({'plan_OPS_F_unchanged':[141,7,15],'review_files':len(rows),'diff_check':0}))
