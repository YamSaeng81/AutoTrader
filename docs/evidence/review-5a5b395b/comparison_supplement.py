"""Correct the review's decimal string comparison using the same saved card."""
from decimal import Decimal
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = '5a5b395bef154674ff1ef1009f3c858239f1550e'
REL = 'docs/evidence/weekend-local-fix-20261121/runs/r1-run2/B1_orders_not_provided/out/scorecard.json'
target = HERE / 'comparison-supplement.json'
if target.exists():
    raise SystemExit('Existing audit output; do not overwrite')
raw = subprocess.run(['git', 'show', BASE + ':' + REL], cwd=ROOT, capture_output=True, check=True).stdout
card = json.loads(raw)
c = card['sessions'][0]
ok = (Decimal(c['month_pnl']) == Decimal('989.50') and
      all(c[k] is None for k in ('realized_in_period', 'fees_paid', 'buy_funds', 'round_trips_closed', 'wins', 'losses')) and
      card['included']['zero_trade'] == 0 and '989.5' not in c['identity_check'])
out = {'base': BASE, 'saved_card': REL, 'prior_check': 'Missing orders retains equity PnL and unknown trade metrics',
       'review_expectation_defect': 'Exact string comparison required 989.50; saved 989.5 has the same decimal value',
       'saved_month_pnl': c['month_pnl'], 'expected_decimal': '989.50', 'corrected_check_passed': ok,
       'candidate_rerun': False, 'candidate_changed': False, 'original_checks_rewritten': False}
target.write_text(json.dumps(out, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps(out))
raise SystemExit(not ok)
