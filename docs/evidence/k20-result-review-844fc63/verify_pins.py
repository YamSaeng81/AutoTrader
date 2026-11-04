from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
E = ROOT / 'docs/evidence/k20-result-review-844fc63'
REF = '844fc63f7dce4e2c964c33e37e99a3bf39886d24'
NEW = 'b1d7664db73222aec5d86f2e9137debef0ff4df3'
IMG = 'sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6'
old = subprocess.check_output(['git','show',f'{REF}:docs/LIMITED_LIVE_TEST_PLAN.md'],cwd=ROOT).decode('utf-8')
now = (ROOT / 'docs/LIMITED_LIVE_TEST_PLAN.md').read_text(encoding='utf-8')
blocks = lambda t: re.findall(r'^```bash\n(.*?)^```', t.replace('\r\n','\n'), re.M|re.S)
fblocks = lambda t: blocks(t[t.index('**F 변수**'):t.index('### 5-9.')])
a,b = fblocks(old),fblocks(now)
assert len(a) == len(b) == 15
assert [i for i,(x,y) in enumerate(zip(a,b)) if x!=y] == [0,1]
assert f'NEW={NEW} ' in b[0]
assert f'IMGF1={IMG} ' in b[1]
assert '"$(cat ~/maint-n11/image-new.txt)" = "$IMGF1"' in b[1]
assert 'HEAD 가 b1d7664' in b[1]
assert 'cat ~/maint-n08/image-new.txt' not in b[1]
labels = ['F 변수','F1 첫 블록','F1 운영자 확인','F2 요청','F2 확인','F2-1','F3','F3-2','F5 변수','F5-1','F5-2','F5-3','F5-4','F6 사유','F6 요청']
rows = [{'block':label,'old_sha256':hashlib.sha256(x.encode()).hexdigest(),'new_sha256':hashlib.sha256(y.encode()).hexdigest(),'changed':x!=y} for label,x,y in zip(labels,a,b)]
syntax = []
for i in [0,1]:
    r = subprocess.run([r'C:\Program Files\Git\bin\bash.exe','--noprofile','--norc','-n'],input=b[i].encode(),capture_output=True)
    assert r.returncode == 0, r.stderr
    syntax.append({'block':labels[i],'exit':r.returncode,'stderr':r.stderr.decode('utf-8')})
assert len(blocks(old)) == len(blocks(now)) == 76
assert sum(x!=y for x,y in zip(blocks(old),blocks(now))) == 2
opsold = subprocess.check_output(['git','show',f'{REF}:docs/OPS_DAILY_CHECK.md'],cwd=ROOT).decode('utf-8')
ops = (ROOT/'docs/OPS_DAILY_CHECK.md').read_text(encoding='utf-8')
assert blocks(opsold) == blocks(ops) and len(blocks(ops)) == 7
progress_old = subprocess.check_output(['git','show',f'{REF}:docs/PROGRESS.md'],cwd=ROOT).decode('utf-8').replace('\r\n','\n')
progress = (ROOT/'docs/PROGRESS.md').read_text(encoding='utf-8')
without_new = '\n'.join(l for l in progress.split('\n') if '(278차, 총괄 로컬 반영·커밋 전)' not in l)
assert without_new.replace('> **이전 갱신**: 2026-11-04 (277차)', '> **마지막 갱신**: 2026-11-04 (277차)',1) == progress_old
for row in rows[:2]:
    assert row['new_sha256'] in now
nov=(ROOT/'docs/NOVEMBER_LIVE_UPGRADE.md').read_text(encoding='utf-8')
assert f'| 운영 backend | `{NEW}`' in nov and f'| 운영 이미지 | `{IMG}`' in nov
assert '| N12 |' in nov and '**완료(2026-11-04, 총괄 v1.90 §58)**' in nov
result={'source_before':REF,'new_backend_sha':NEW,'new_image':IMG,'image_record':'~/maint-n11/image-new.txt','blocks':rows,'changed_blocks':2,'unchanged_blocks':13,'syntax':syntax,'plan_top_level_bash_count':76,'plan_historical_quoted_bash_count':1,'ops_7_blocks_unchanged':True,'progress_prior_history_preserved':True,'final_S':'unfixed until 2026-11-30'}
E.mkdir(exist_ok=True,parents=True)
(E/'n06-blocks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'changed':[r for r in rows if r['changed']],'syntax':syntax,'unchanged_blocks':13,'ops':7,'progress_history':'preserved'},ensure_ascii=False,indent=2))
