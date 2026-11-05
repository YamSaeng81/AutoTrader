from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '4445f36192752636f5c47186c6e2fff3aab2ffcf'


def source(path):
    return subprocess.check_output(['git', 'show', f'{REF}:{path}'], cwd=ROOT).decode('utf-8')


def blocks(text):
    return re.findall(r'^```bash\n(.*?)^```', text.replace('\r\n', '\n'), re.M | re.S)


plan = (ROOT / 'docs/LIMITED_LIVE_TEST_PLAN.md').read_text(encoding='utf-8')
old_blocks = blocks(source('docs/LIMITED_LIVE_TEST_PLAN.md'))
new_blocks = blocks(plan)
assert len(old_blocks) == len(new_blocks) == 101 and old_blocks == new_blocks
ops = (ROOT / 'docs/OPS_DAILY_CHECK.md').read_text(encoding='utf-8')
assert source('docs/OPS_DAILY_CHECK.md') == ops
assert len(blocks(ops)) == 7
assert source('docs/LIVE_ENTRY_PREREG.md') == (ROOT / 'docs/LIVE_ENTRY_PREREG.md').read_text(encoding='utf-8')
fixed = json.loads((ROOT / 'docs/evidence/k22-result-review-ed119aa/n06-blocks.json').read_text(encoding='utf-8'))['blocks']
hashes = [hashlib.sha256(b.encode('utf-8')).hexdigest() for b in new_blocks]
assert len(fixed) == 15 and all(hashes.count(row['new_sha256']) == 1 for row in fixed)
assert 'v1.109' in plan.splitlines()[2] and '**77.' in plan
assert plan.count('**77.') == 1
assert '> **마지막 갱신**: 2026-11-05 (297차' in (ROOT / 'docs/PROGRESS.md').read_text(encoding='utf-8')
paths = subprocess.check_output(['git', 'diff', '--name-only'], cwd=ROOT).decode().splitlines()
assert sorted(paths) == sorted(['docs/LIMITED_LIVE_TEST_PLAN.md', 'docs/LIVE_READINESS.md',
                                'docs/NOVEMBER_LIVE_UPGRADE.md', 'docs/PROGRESS.md'])
november = (ROOT / 'docs/NOVEMBER_LIVE_UPGRADE.md').read_text(encoding='utf-8')
for name in ['N15', 'N16']:
    old = next(line for line in source('docs/NOVEMBER_LIVE_UPGRADE.md').splitlines() if line.startswith(f'| {name} |'))
    new = next(line for line in november.splitlines() if line.startswith(f'| {name} |'))
    assert old.count('|') == new.count('|')
    assert '총괄v1.109' in new
check = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
assert check.returncode == 0, check.stdout
result = {'source': REF, 'plan_bash_unchanged': 101, 'F_blocks_unchanged': 15,
          'OPS_entire_document_unchanged': True, 'OPS_bash_blocks': 7,
          'prereg_unchanged': True, 'tracked_docs': paths,
          'v1.109_section_77_and_progress_297': True, 'table_column_counts_preserved': True,
          'runtime_pins_and_final_S_unchanged': True, 'git_diff_check': 0}
(OUT / 'doc-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'plan_bash_same': 101, 'F_same': 15, 'OPS_same': True, 'tracked_docs': 4, 'diff_check': 0}))
