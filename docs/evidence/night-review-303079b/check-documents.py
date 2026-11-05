"""Check the accepted #24 pins and N18 insertion; seal this review folder."""
from pathlib import Path
import hashlib,json,re,subprocess

ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
REF='303079b'
BASH='C:/Program Files/Git/bin/bash.exe'
def current(n):return (ROOT/'docs'/n).read_text(encoding='utf-8')
def before(n):return subprocess.check_output(['git','show',REF+':docs/'+n],cwd=ROOT).decode()
def blocks(s):return re.findall(r'^```bash\n(.*?)^```',s,re.M|re.S)
def sha(b):return hashlib.sha256(b).hexdigest()

old,new=blocks(before('LIMITED_LIVE_TEST_PLAN.md')),blocks(current('LIMITED_LIVE_TEST_PLAN.md'))
assert len(old)==len(new)==133
assert [i for i,(a,b) in enumerate(zip(old,new)) if a!=b]==[12,13]
assert blocks(before('OPS_DAILY_CHECK.md'))==blocks(current('OPS_DAILY_CHECK.md'))
assert len(blocks(current('OPS_DAILY_CHECK.md')))==7
assert before('LIVE_ENTRY_PREREG.md')==current('LIVE_ENTRY_PREREG.md')
pins=json.loads((OUT/'n06-blocks.json').read_text(encoding='utf-8'))
assert len(pins['blocks'])==15 and sum(r['changed'] for r in pins['blocks'])==2
for r in pins['blocks']:
    i=r['index_zero_based']
    assert sha(old[i].encode())==r['old_sha256']
    assert sha(new[i].encode())==r['new_sha256']
addition=(ROOT/'docs/evidence/n18-f1-install-check/add.txt').read_text(encoding='utf-8')
assert new[13].count(addition)==1
new_f1_without_install=new[13].replace(addition,'',1).splitlines()
old_f1=old[13].splitlines()
diff=[i for i,(a,b) in enumerate(zip(old_f1,new_f1_without_install)) if a!=b]
assert len(old_f1)==len(new_f1_without_install) and diff==[14,16,17]
assert 'HEAD 가 7a94472 가' in new_f1_without_install[14]
assert new_f1_without_install[16].startswith('IMGF1='+pins['operating_image'])
assert '~/maint-n17/image-new.txt' in new_f1_without_install[17]
assert 'NEW='+pins['operating_head'] in new[12]
plan=current('LIMITED_LIVE_TEST_PLAN.md')
assert plan.splitlines()[2].startswith('> **v1.123 ')
assert plan.count('**91.')==1 and plan.index('**91.')<plan.index('## 6. 시작·중단·종료 조건')
assert '> **마지막 갱신**: 2026-11-06 (312차,' in current('PROGRESS.md')
assert '> **이전 갱신**: 2026-11-05 (311차,' in current('PROGRESS.md')
nov=current('NOVEMBER_LIVE_UPGRADE.md')
assert '| 운영 backend | `'+pins['operating_head']+'`' in nov
assert '| 운영 이미지 | `'+pins['operating_image']+'` |' in nov
for key in ['N15','N16']:
    assert next(r for r in nov.splitlines() if r.startswith('| '+key+' |'))==next(r for r in before('NOVEMBER_LIVE_UPGRADE.md').splitlines() if r.startswith('| '+key+' |'))
for key in ['N17','N18','N19']:
    row=next(r for r in nov.splitlines() if r.startswith('| '+key+' |'))
    assert row.count('|')==next(r for r in before('NOVEMBER_LIVE_UPGRADE.md').splitlines() if r.startswith('| '+key+' |')).count('|')
syntax=[]
for i in [12,13]:
    r=subprocess.run([BASH,'--noprofile','--norc','-n'],input=new[i].encode(),capture_output=True)
    assert r.returncode==0,r.stderr
    syntax.append({'index':i,'bash_n':0})
expected=sorted('docs/'+n for n in ['LIMITED_LIVE_TEST_PLAN.md','LIVE_READINESS.md','NOVEMBER_LIVE_UPGRADE.md','OPS_DAILY_CHECK.md','PROGRESS.md'])
changed=sorted(subprocess.check_output(['git','diff','--name-only'],cwd=ROOT).decode().splitlines())
assert changed==expected,changed
dc=subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True)
assert dc.returncode==0,dc.stdout
result={'version':'1.123','section':91,'progress':312,'plan_bash':133,'only_changed':[12,13],
    'F_changed_unchanged':[2,13],'other_plan_bash_unchanged':131,'OPS_bash_unchanged':7,
    'N18_exact_reviewed_insertion':True,'other_F1_changes_only_accepted_runtime_pins':diff,
    'changed_blocks_syntax':syntax,'N15_N16_complete_rows_unchanged':True,
    'PREREG_unchanged':True,'final_S_unfixed':True,'tracked_docs':changed,'git_diff_check':0,
    'application_server_DB_untouched_by_this_review':True}
(OUT/'doc-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
manifest=[sha(p.read_bytes())+'  '+p.name for p in sorted(OUT.iterdir(),key=lambda p:p.name) if p.is_file() and p.name!='SHA256SUMS']
(OUT/'SHA256SUMS').write_text('\n'.join(manifest)+'\n',encoding='utf-8')
print(json.dumps({'F_changed_unchanged':[2,13],'other_plan_bash_unchanged':131,'OPS':7,'review_files':len(manifest),'diff_check':0}))
