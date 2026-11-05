"""Check the review-only docs and evidence without app tests or a build."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '0bf874f3d0851440baffe5651f425b13e712e68d'
NAMES = ['LIMITED_LIVE_TEST_PLAN.md', 'LIVE_READINESS.md', 'NOVEMBER_LIVE_UPGRADE.md', 'OPS_DAILY_CHECK.md', 'PROGRESS.md']

def old(name):
    return subprocess.check_output(['git','show',REF+':docs/'+name],cwd=ROOT).decode('utf-8').replace('\r\n','\n')

def new(name):
    return (ROOT/'docs'/name).read_text(encoding='utf-8')

def blocks(text):
    return re.findall(r'^```bash\n(.*?)^```', text, re.M|re.S)

plan = new(NAMES[0])
assert len(blocks(plan)) == 117 and blocks(plan) == blocks(old(NAMES[0]))
ops = new(NAMES[3])
assert len(blocks(ops)) == 7 and blocks(ops) == blocks(old(NAMES[3]))
assert plan.count('**86.') == 1 and plan.index('**86.') < plan.index('## 6. 시작·중단·종료 조건')
assert plan.splitlines()[2].startswith('> **v1.118 ')
hashes = json.loads((ROOT/'docs/evidence/k23-result-review-28e2183/n06-blocks.json').read_text(encoding='utf-8'))
assert len(hashes['blocks']) == 15
for row in hashes['blocks']:
    current = blocks(plan)[row['index_zero_based']]
    assert hashlib.sha256(current.encode()).hexdigest() == row['new_sha256'], row['block']
assert old('LIVE_ENTRY_PREREG.md') == new('LIVE_ENTRY_PREREG.md')
prog = new(NAMES[4])
assert prog.count('> **마지막 갱신**: 2026-11-05 (306차,') == 1
assert prog.count('> **이전 갱신**: 2026-11-05 (305차)') == 1
nov = new(NAMES[2])
assert nov.count('| N17 |') == 1
task = next(row for row in nov.splitlines() if row.startswith('| N17 |'))
assert task.count('|') == next(row for row in nov.splitlines() if row.startswith('| N16 |')).count('|')
assert 'P1' in task and '운영변경 승인 아님' in task
assert '| 운영 backend | `1103f48505a1457029f6555547a87aa986535738`' in nov
assert '| 운영 이미지 | `sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62` |' in nov
for code in ['N15','N16']:
    row = next(row for row in nov.splitlines() if row.startswith('| '+code+' |'))
    assert '| P0 · 완료(운영 반영 수용, 총괄11-05) |' in row
    assert row == next(row for row in old(NAMES[2]).splitlines() if row.startswith('| '+code+' |'))
paths = sorted(subprocess.check_output(['git','diff','--name-only'],cwd=ROOT).decode().splitlines())
assert paths == sorted('docs/'+name for name in NAMES), paths
assert subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True).returncode == 0
result = {'source_before':REF, 'version':'1.118', 'section':86, 'progress':306,
    'tracked_doc_changes':paths, 'plan_bash_unchanged':117, 'OPS_bash_unchanged':7,
    'N06_frozen_blocks_unchanged':15, 'PREREG_unchanged':True,
    'operating_pins_and_N15_N16_complete_unchanged':True, 'N17_priority':'P1 local work only',
    'no_server_deployment_approval':True, 'git_diff_check_exit':0}
(OUT/'doc-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows = [hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name
    for p in sorted(OUT.iterdir(),key=lambda p:p.name) if p.is_file() and p.name != 'SHA256SUMS']
(OUT/'SHA256SUMS').write_text('\n'.join(rows)+'\n',encoding='utf-8')
for row in rows:
    expected,name = row.split('  ',1)
    assert hashlib.sha256((OUT/name).read_bytes()).hexdigest() == expected
print(json.dumps({'docs':5, 'unchanged_plan_OPS_F':[117,7,15], 'checksums':len(rows), 'diff_check':0}))
