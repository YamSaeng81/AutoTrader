"""Check the v1.128 documentation delta and seal this review."""
from pathlib import Path
import hashlib,json,re,subprocess
ROOT=Path(__file__).resolve().parents[3];OUT=Path(__file__).resolve().parent;REF='7542a62'
names=['LIMITED_LIVE_TEST_PLAN.md','LIVE_READINESS.md','NOVEMBER_LIVE_UPGRADE.md','OPS_DAILY_CHECK.md','PROGRESS.md']
def git(*a):return subprocess.check_output(['git',*a],cwd=ROOT)
def old(n):return git('show',REF+':docs/'+n).decode().replace('\r\n','\n')
def cur(n):return (ROOT/'docs'/n).read_text(encoding='utf-8')
def blocks(s):return re.findall(r'^```bash\n(.*?)^```',s,re.M|re.S)
def sha(b):return hashlib.sha256(b).hexdigest()
plan=cur(names[0]);a=blocks(plan)
assert a==blocks(old(names[0])) and len(a)==141
assert blocks(cur('OPS_DAILY_CHECK.md'))==blocks(old('OPS_DAILY_CHECK.md')) and len(blocks(cur('OPS_DAILY_CHECK.md')))==7
assert cur('LIVE_ENTRY_PREREG.md')==old('LIVE_ENTRY_PREREG.md')
pins=json.loads((ROOT/'docs/evidence/night-review-303079b/n06-blocks.json').read_text(encoding='utf-8'))
assert len(pins['blocks'])==15 and pins['final_S']=='unfixed until 2026-11-30'
for row in pins['blocks']:assert sha(a[row['index_zero_based']].encode())==row['new_sha256']
assert plan.splitlines()[2].startswith('> **v1.128 ')
assert plan.count('**96.')==1 and plan.index('**96.')<plan.index('## 6. 시작·중단·종료 조건')
assert '> **마지막 갱신**: 2026-11-06 (321차,' in cur('PROGRESS.md')
assert '> **이전 갱신**: 2026-11-06 (320차,' in cur('PROGRESS.md')
nov=cur('NOVEMBER_LIVE_UPGRADE.md')
for key in ['운영 backend','운영 이미지','N15','N16','N17','N18','N19']:
    assert next(x for x in nov.splitlines() if x.startswith('| '+key+' |'))==next(x for x in old('NOVEMBER_LIVE_UPGRADE.md').splitlines() if x.startswith('| '+key+' |'))
assert '| N20 | P2 · 로컬 수용·배포안 보완 |' in nov
assert next(x for x in nov.splitlines() if x.startswith('| N20 |')).count('|')==next(x for x in old('NOVEMBER_LIVE_UPGRADE.md').splitlines() if x.startswith('| N20 |')).count('|')
assert sorted(git('diff','--name-only').decode().splitlines())==sorted('docs/'+n for n in names)
assert subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True).returncode==0
r=json.loads((OUT/'review.json').read_text(encoding='utf-8'))
assert r['root_ordering_fragment_inputs']==24 and r['D2_server_approved'] is False
result={'version':'1.128','section':96,'progress':321,'plan_OPS_F_blocks_unchanged':[141,7,15],
        'N25_fixed_source_unchanged':'342f461359b9e3c9d5941f6df95b905b65dce9a7 §92 UB=92',
        'N20_D1_standin_accepted_D2_local_only':True,'runtime_F_pins_S_preserved':True,'git_diff_check':0}
(OUT/'doc-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows=[sha(p.read_bytes())+'  '+p.name for p in sorted(OUT.iterdir(),key=lambda p:p.name) if p.is_file() and p.name!='SHA256SUMS']
(OUT/'SHA256SUMS').write_text('\n'.join(rows)+'\n',encoding='utf-8')
assert all(sha((OUT/row.split(maxsplit=1)[1]).read_bytes())==row.split(maxsplit=1)[0] for row in rows)
print(json.dumps({'plan_OPS_F_unchanged':[141,7,15],'review_files':len(rows),'diff_check':0}))
