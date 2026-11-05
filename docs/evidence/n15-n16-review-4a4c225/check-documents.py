from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '4a4c225'


def source(path):
    return subprocess.check_output(['git', 'show', f'{REF}:{path}'], cwd=ROOT).decode('utf-8')


def blocks(s):
    return re.findall(r'^```bash\n(.*?)^```', s.replace('\r\n', '\n'), re.M | re.S)


plan_path = 'docs/LIMITED_LIVE_TEST_PLAN.md'
old = blocks(source(plan_path))
plan = (ROOT / plan_path).read_text(encoding='utf-8')
new = blocks(plan)
assert len(old) == len(new) == 101 and old == new
ops_path = 'docs/OPS_DAILY_CHECK.md'
assert blocks(source(ops_path)) == blocks((ROOT / ops_path).read_text(encoding='utf-8'))
assert len(blocks(source(ops_path))) == 7
fixed = json.loads((ROOT / 'docs/evidence/k22-result-review-ed119aa/n06-blocks.json').read_text(encoding='utf-8'))['blocks']
all_hashes = [hashlib.sha256(b.encode('utf-8')).hexdigest() for b in new]
assert len(fixed) == 15
assert all(all_hashes.count(r['new_sha256']) == 1 for r in fixed)
assert 'v1.106' in plan.splitlines()[2] and '**74.' in plan
prereg = (ROOT / 'docs/LIVE_ENTRY_PREREG.md').read_text(encoding='utf-8')
assert 'C+2W 이상' in prereg and '총괄 개정 사양·운영 반영 전' in prereg
paths = subprocess.check_output(['git', 'diff', '--name-only'], cwd=ROOT).decode().splitlines()
assert sorted(paths) == sorted(['docs/LIMITED_LIVE_TEST_PLAN.md', 'docs/LIVE_ENTRY_PREREG.md',
                                'docs/LIVE_READINESS.md', 'docs/NOVEMBER_LIVE_UPGRADE.md', 'docs/PROGRESS.md'])
check = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
assert check.returncode == 0, check.stdout
report = {'source': REF, 'plan_bash_unchanged': 101, 'fixed_F_unchanged': 15,
          'ops_bash_unchanged': 7, 'git_diff_check': 0, 'tracked_docs': paths,
          'new_approval_contract': 'E = candle_close + 2 * min(timeframe/2, 5min); now >= E rejects new reservation/intent',
          'no_runtime_pin_or_final_S_change': True}
(OUT / 'doc-check.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'plan_bash_same': 101, 'F_same': 15, 'OPS_same': 7, 'diff_check': 0, 'docs': len(paths)}, ensure_ascii=False))
