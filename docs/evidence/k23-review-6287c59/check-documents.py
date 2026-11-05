"""Check that the ruling leaves executable blocks and operational pins intact."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '6287c59'


def old(name):
    return subprocess.check_output(['git', 'show', f'{REF}:docs/{name}'], cwd=ROOT).decode('utf-8')


def current(name):
    return (ROOT / 'docs' / name).read_text(encoding='utf-8')


def blocks(text):
    return re.findall(r'^```bash\n(.*?)^```', text, re.M | re.S)


plan = current('LIMITED_LIVE_TEST_PLAN.md')
assert blocks(old('LIMITED_LIVE_TEST_PLAN.md')) == blocks(plan)
assert len(blocks(plan)) == 109
fixed = json.loads((ROOT / 'docs/evidence/k22-result-review-ed119aa/n06-blocks.json').read_text(encoding='utf-8'))['blocks']
hashes = [hashlib.sha256(b.encode()).hexdigest() for b in blocks(plan)]
assert len(fixed) == 15 and all(hashes.count(r['new_sha256']) == 1 for r in fixed)
for name in ('OPS_DAILY_CHECK.md', 'LIVE_ENTRY_PREREG.md'):
    assert old(name) == current(name), name
assert 'v1.112' in plan.splitlines()[2] and plan.count('**80.') == 1
assert plan.index('**80.') < plan.index('## 6. 시작·중단·종료 조건')
assert '> **마지막 갱신**: 2026-11-05 (300차' in current('PROGRESS.md')
nov = current('NOVEMBER_LIVE_UPGRADE.md')
for name in ('N15', 'N16'):
    prev = next(l for l in old('NOVEMBER_LIVE_UPGRADE.md').splitlines() if l.startswith(f'| {name} |'))
    now = next(l for l in nov.splitlines() if l.startswith(f'| {name} |'))
    assert prev.count('|') == now.count('|')
    assert '총괄v1.112' in now
paths = subprocess.check_output(['git', 'diff', '--name-only'], cwd=ROOT).decode().splitlines()
assert sorted(paths) == sorted('docs/' + n for n in ('LIMITED_LIVE_TEST_PLAN.md', 'LIVE_READINESS.md', 'NOVEMBER_LIVE_UPGRADE.md', 'PROGRESS.md'))
assert subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True).returncode == 0
result = {'review_source': subprocess.check_output(['git', 'rev-parse', REF], cwd=ROOT).decode().strip(),
          'plan_bash_unchanged': 109, 'F_blocks_unchanged': 15,
          'OPS_and_PREREG_entire_documents_unchanged': True,
          'four_tracked_documents': paths, 'v1.112_section_80_progress_300': True,
          'table_columns_preserved': True, 'operational_pins_and_final_S_unchanged': True,
          'git_diff_check': 0}
(OUT / 'doc-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
rows = []
for p in sorted(OUT.iterdir(), key=lambda p: p.name):
    if p.is_file() and p.name != 'SHA256SUMS':
        rows.append(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}')
(OUT / 'SHA256SUMS').write_text('\n'.join(rows) + '\n', encoding='utf-8')
for row in rows:
    want, filename = row.split('  ', 1)
    assert hashlib.sha256((OUT / filename).read_bytes()).hexdigest() == want
print(json.dumps({'unchanged_bash': 109, 'F': 15, 'docs': 4, 'evidence_checksums': len(rows)}, ensure_ascii=False))
