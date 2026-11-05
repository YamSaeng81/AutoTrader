"""Validate the new operating pins and preserve other reviewed commands."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '28e21831bdd2b40b4735924682fe9b93ccfad417'
NEW = '1103f48505a1457029f6555547a87aa986535738'
IMAGE = 'sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62'

def before(name):
    return subprocess.check_output(['git', 'show', f'{REF}:docs/{name}'], cwd=ROOT).decode('utf-8')

def current(name):
    return (ROOT / 'docs' / name).read_text(encoding='utf-8')

def blocks(value):
    return re.findall(r'^```bash\n(.*?)^```', value, re.M | re.S)

plan = current('LIMITED_LIVE_TEST_PLAN.md')
old, new = blocks(before('LIMITED_LIVE_TEST_PLAN.md')), blocks(plan)
assert len(old) == len(new) == 117
changed = [i for i, (a,b) in enumerate(zip(old, new)) if a != b]
assert changed == [12, 13]
oldF, newF = old[12].splitlines(), new[12].splitlines()
diffF = [i for i, (a,b) in enumerate(zip(oldF, newF)) if a != b]
assert len(diffF) == 1 and oldF[diffF[0]].startswith('NEW=')
assert len(oldF) == len(newF)
assert newF[diffF[0]].startswith('NEW=' + NEW + '   # v1.116:')
old1, new1 = old[13].splitlines(), new[13].splitlines()
diff = [i for i, (a,b) in enumerate(zip(old1, new1)) if a != b]
assert len(old1) == len(new1) and len(diff) == 3
assert old1[diff[0]].replace('HEAD 가 fe01207 가', 'HEAD 가 1103f48 이') == new1[diff[0]]
assert new1[diff[1]].startswith('IMGF1=' + IMAGE + '   # v1.116:')
assert '~/maint-n15n16/image-new.txt' in new1[diff[2]]
assert old1[diff[2]].replace('~/maint-n13/image-new.txt', '~/maint-n15n16/image-new.txt').replace('#22 배포 이미지(9cb5d84f…)', '#23 배포 이미지(9818254c…)') == new1[diff[2]]
fixed = json.loads((OUT / 'n06-blocks.json').read_text(encoding='utf-8'))
assert len(fixed['blocks']) == 15 and fixed['final_S'] == 'unfixed until 2026-11-30'
assert fixed['operating_head'] == NEW and fixed['operating_image'] == IMAGE
for row in fixed['blocks']:
    index = row['index_zero_based']
    assert hashlib.sha256(old[index].encode()).hexdigest() == row['old_sha256']
    assert hashlib.sha256(new[index].encode()).hexdigest() == row['new_sha256']
    assert row['changed'] == (index in changed)
assert sum(row['changed'] for row in fixed['blocks']) == 2
assert blocks(before('OPS_DAILY_CHECK.md')) == blocks(current('OPS_DAILY_CHECK.md'))
assert len(blocks(current('OPS_DAILY_CHECK.md'))) == 7
assert before('LIVE_ENTRY_PREREG.md') == current('LIVE_ENTRY_PREREG.md')
assert plan.splitlines()[2].startswith('> **v1.116 ')
assert plan.count('**84.') == 1 and plan.index('**84.') < plan.index('## 6. 시작·중단·종료 조건')
assert '> **마지막 갱신**: 2026-11-05 (304차' in current('PROGRESS.md')
assert '> **이전 갱신**: 2026-11-05 (303차)' in current('PROGRESS.md')
nov = current('NOVEMBER_LIVE_UPGRADE.md')
assert '| 운영 backend | `' + NEW + '`' in nov and '| 운영 이미지 | `' + IMAGE + '` |' in nov
for name in ('N15', 'N16'):
    a = next(s for s in before('NOVEMBER_LIVE_UPGRADE.md').splitlines() if s.startswith(f'| {name} |'))
    b = next(s for s in nov.splitlines() if s.startswith(f'| {name} |'))
    assert a.count('|') == b.count('|')
    assert '| P0 · 완료(운영 반영 수용, 총괄11-05) |' in b and '총괄v1.116' in b
old5 = next(s for s in before('NOVEMBER_LIVE_UPGRADE.md').splitlines() if s.startswith('| N05 |'))
new5 = next(s for s in nov.splitlines() if s.startswith('| N05 |'))
assert old5.count('|') == new5.count('|') and '지금 진행' in new5 and '총괄v1.116' in new5
files = sorted(subprocess.check_output(['git', 'diff', '--name-only'], cwd=ROOT).decode().splitlines())
expected = sorted('docs/' + name for name in ('LIMITED_LIVE_TEST_PLAN.md', 'LIVE_READINESS.md', 'NOVEMBER_LIVE_UPGRADE.md', 'OPS_DAILY_CHECK.md', 'PROGRESS.md'))
assert files == expected
check = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
assert check.returncode == 0
bash = Path('C:/Program Files/Git/bin/bash.exe')
syntax = []
for index in changed:
    result = subprocess.run([str(bash), '--noprofile', '--norc', '-n'], input=new[index].encode('utf-8'), capture_output=True)
    assert result.returncode == 0, result.stderr.decode('utf-8', errors='replace')
    syntax.append({'block_index': index, 'bash_n_exit': result.returncode})
result = {'source_before': REF, 'plan_blocks_total': 117, 'only_F_variable_and_F1_first_changed': changed,
          'remaining_plan_bash_unchanged': 115, 'F_blocks_changed_unchanged': [2, 13],
          'changed_block_syntax': syntax, 'OPS_bash_unchanged': 7, 'PREREG_entire_document_unchanged': True,
          'tracked_docs': files, 'table_columns_preserved': True, 'v1.116_section_84_progress_304': True,
          'final_S_risk_limits_cron_ledger_SID203_start_unchanged': True, 'git_diff_check': 0}
(OUT / 'doc-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
rows = [f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}'
        for p in sorted(OUT.iterdir(), key=lambda p: p.name) if p.is_file() and p.name != 'SHA256SUMS']
(OUT / 'SHA256SUMS').write_text('\n'.join(rows) + '\n', encoding='utf-8')
for row in rows:
    digest, name = row.split('  ', 1)
    assert hashlib.sha256((OUT / name).read_bytes()).hexdigest() == digest
print(json.dumps({'documents': len(files), 'F_changed': 2, 'F_preserved': 13, 'other_plan_bash': 115, 'OPS_bash': 7, 'bash_n': '2/2', 'checksums': len(rows)}, ensure_ascii=False))
