"""Run only the embedded file classifier on synthetic local text, never SQL."""
from pathlib import Path
import json
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHA = '474d103343fb4ccc531136b79e9ece5b4d9cc912'
source = subprocess.check_output(['git', 'show', SHA + ':docs/evidence/d81d4-20261120/d4/blocks-d81/U변수.sh'], cwd=ROOT).decode()
code = re.search(r"pf3ok\(\) \{ python3 - \"\$1\" <<'PYEOF'\n(.*?)\nPYEOF", source, re.S)[1]
work = HERE / 'work' / 'pf3-round2'
assert ROOT in work.resolve().parents and not work.exists()
work.mkdir(parents=True)
script = work / 'pf3.py'
script.write_text(code, encoding='utf-8', newline='\n')
prefix = ['LIVE | 0', 'DYNAMIC | 0', 'LIVE | 0', 'DYNAMIC | 0']
valid = ['10', '1', '0', '100', '1', '1', '99', '1', '0', '0', 'LIVE', '1',
         '2026-11-01 00:00:00+00', '-1', 'STOP_LOSS', '1']
cases = [('empty-r3', None), ('valid', valid)]
for name, index, value in [('invalid-cost', 3, 'not-a-number'), ('infinite-sell-qty', 7, 'Infinity'),
                          ('invalid-no-trade-flag', 9, '2'), ('missing-sell-net', 6, '')]:
    row = valid.copy()
    row[index] = value
    cases.append((name, row))
rows = []
for name, row in cases:
    f = work / (name + '.txt')
    f.write_text('\n'.join(prefix + ([' | '.join(row)] if row is not None else [])) + '\n', encoding='utf-8', newline='\n')
    r = subprocess.run([sys.executable, '-I', '-X', 'utf8', str(script), str(f)], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    rows.append({'case': name, 'rc': r.returncode, 'stdout': r.stdout.decode('utf-8'), 'stderr': r.stderr.decode('utf-8')})
(HERE / 'pf3-probe.json').write_text(json.dumps({'source': SHA, 'cases': rows,
    'limits': 'Synthetic text only; no database query, product evaluation, socket or server action.'}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps([{'case': x['case'], 'rc': x['rc']} for x in rows]))
