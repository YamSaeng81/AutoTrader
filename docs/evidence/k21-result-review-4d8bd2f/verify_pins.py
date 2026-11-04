from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
E = Path(__file__).resolve().parent
REF = '4d8bd2ff046fc9311eba8fe77583d6db18205e90'
NEW = '6995c52259e3de4c7c922a32f9fb2aa9ddd49f00'
IMG = 'sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07'


def git(path):
    return subprocess.check_output(['git','show',f'{REF}:docs/{path}'],cwd=ROOT).decode().replace('\r\n','\n')


blocks = lambda t: re.findall(r'^```bash\n(.*?)^```',t,re.M|re.S)
fblocks = lambda t: blocks(t[t.index('**F 변수**'):t.index('### 5-9.')])
old, now = git('LIMITED_LIVE_TEST_PLAN.md'), (ROOT/'docs/LIMITED_LIVE_TEST_PLAN.md').read_text(encoding='utf-8')
a,b = fblocks(old),fblocks(now)
assert len(a) == len(b) == 15
assert [i for i,(x,y) in enumerate(zip(a,b)) if x!=y] == [0,1]
assert f'NEW={NEW} ' in b[0] and f'IMGF1={IMG} ' in b[1]
assert 'HEAD 가 6995c52' in b[1]
assert '"$(cat ~/maint-n12/image-new.txt)" = "$IMGF1"' in b[1]
assert 'cat ~/maint-n11/image-new.txt' not in b[1]
expected = next(l for l in now.splitlines() if l.startswith('- 기대: `git-f1`'))
assert '`6995c522…`' in expected and IMG in expected and '~/maint-n12/image-new.txt' in expected
hashes = json.loads((E/'n06-blocks.json').read_text(encoding='utf-8'))
assert all(hashlib.sha256(x.encode()).hexdigest() == r['old_sha256'] and hashlib.sha256(y.encode()).hexdigest() == r['new_sha256'] for x,y,r in zip(a,b,hashes['blocks']))
assert all(hashes['blocks'][i]['new_sha256'] in now for i in [0,1])
assert len(blocks(old)) == len(blocks(now)) == 92
assert sum(x!=y for x,y in zip(blocks(old),blocks(now))) == 2
assert old[old.index('## 6. 시작·중단·종료 조건'):] == now[now.index('## 6. 시작·중단·종료 조건'):]
opsold, ops = git('OPS_DAILY_CHECK.md'), (ROOT/'docs/OPS_DAILY_CHECK.md').read_text(encoding='utf-8')
assert blocks(opsold) == blocks(ops) and len(blocks(ops)) == 7
progress_old = git('PROGRESS.md')
progress = (ROOT/'docs/PROGRESS.md').read_text(encoding='utf-8')
without_new = ''.join(l for l in progress.splitlines(keepends=True) if not l.startswith('> **마지막 갱신**: 2026-11-04 (286차,'))
assert without_new.replace('> **이전 갱신**: 2026-11-04 (285차)', '> **마지막 갱신**: 2026-11-04 (285차)',1) == progress_old
nov = (ROOT/'docs/NOVEMBER_LIVE_UPGRADE.md').read_text(encoding='utf-8')
assert f'| 운영 backend | `{NEW}`' in nov and f'| 운영 이미지 | `{IMG}`' in nov
n12 = next(l for l in nov.splitlines() if l.startswith('| N12 |'))
assert '**완료(2026-11-04, 총괄 v1.98 §66)**' in n12
assert '| N13 | P1' in nov
syntax = []
for i in [0,1]:
    run = subprocess.run([r'C:\Program Files\Git\bin\bash.exe','--noprofile','--norc','-n'],input=b[i].encode(),capture_output=True)
    assert run.returncode == 0 and not run.stderr
    syntax.append({'block':hashes['blocks'][i]['block'],'exit':run.returncode,'stderr':''})
result = {'source_before':REF,'plan_bash_count':92,'changed_blocks':2,'unchanged_F_blocks':13,
          'OPS_7_blocks_unchanged':True,'section_6_7_unchanged':True,'progress_prior_history_exactly_preserved':True,
          'new_HEAD':NEW,'new_image':IMG,'new_image_record':'~/maint-n12/image-new.txt',
          'syntax_only_no_execution':syntax,'N12':'complete within documented scope','N13':'diagnosis assigned',
          'final_S':'unfixed until 2026-11-30'}
(E/'document-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
