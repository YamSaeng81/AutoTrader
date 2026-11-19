"""변형 검출: scorecard.py 에 결함을 하나씩 넣은 임시 사본으로 cases_scorecard.py 를 돌려, 사례가 실패로 잡는지 본다.
사용: python -I mutate.py --out <폴더>   종료값: 모든 변형 검출 0, 하나라도 놓치면 1. 원본 파일은 바꾸지 않는다.
"""
import argparse, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

MUTANTS = [
    ('M01_flow_period_check_off', "        if not (period[0] <= at < period[1]):\n            raise Bad(f'{p}.at",
     "        if False:\n            raise Bad(f'{p}.at"),
    ('M02_paper_into_account_gap', "real = next((a for a in agg if a['group'] == 'money' and a['name'] == REAL), None)",
     "real = {'month_pnl': s(sum((D(c['month_pnl']) for c in cards if c['month_pnl'] is not None), D(0)))}"),
    ('M03_pct_at_amount_max', "        if dd < max_pct:\n            max_pct, pct_window = dd, (pk2_at, at)",
     "        if a - adj_peak == max_amt and adj_peak > 0:\n            max_pct, pct_window = (a - adj_peak) / adj_peak * 100, (pk2_at, at)"),
    ('M04_unrealized_end_from_change', "'unrealized_end': s(u_end),", "'unrealized_end': s(change),"),
    ('M05_pending_reserved_ignored', "            total += rk\n", "            pass\n"),
    ('M06_flows_none_as_zero', "    if x.get('flows') is None:\n        return None, []", "    if x.get('flows') is None:\n        return D(0), []"),
    ('M07_drawdown_ignores_flows', "        cum += f_t\n", "        f_t = D(0)\n"),
    ('M08_lifetime_eq_month', "'lifetime_pnl': life,", "'lifetime_pnl': month_part,"),
    ('M09_missing_settle_as_outside', "                sells_known = False\n                realized_ok = False\n", "                continue\n"),
    ('M10_reverse_order_allowed', "        if pts and at <= pts[-1][0]:", "        if pts and at < pts[-1][0] and False:"),
    # v3 (총괄 v1.191 §3)
    ('M11_chronology_check_off', "            if bat is not None and sat < bat:", "            if False:"),
    ('M12_unknown_fees_as_confirmed', "'fees_paid': fees if not fees_why else None,", "'fees_paid': fees,"),
    ('M13_unknown_buy_funds_as_confirmed', "'buy_funds': buy_funds if not buys_why else None,", "'buy_funds': buy_funds,"),
    ('M14_strict_chronology_rejects_same_instant', "            if bat is not None and sat < bat:", "            if bat is not None and sat <= bat:"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    src = open(os.path.join(HERE, 'scorecard.py'), encoding='utf-8').read()
    lines, missed = [], 0
    for name, old, new in MUTANTS:
        if src.count(old) != 1:
            lines.append(f'ERROR | {name} | 대상 문자열 {src.count(old)}회(1회여야 함)')
            missed += 1
            continue
        with tempfile.TemporaryDirectory() as td:
            open(os.path.join(td, 'scorecard.py'), 'w', encoding='utf-8', newline='\n').write(src.replace(old, new))
            shutil.copy(os.path.join(HERE, 'cases_scorecard.py'), td)
            p = subprocess.run([sys.executable, '-I', os.path.join(td, 'cases_scorecard.py'), '--out', os.path.join(td, 'o')], capture_output=True)
            out = p.stdout.decode('utf-8', 'replace').splitlines()
        failed = [l.split(' | ')[1] for l in out if l.startswith('FAIL')]
        caught = p.returncode == 1 and failed
        missed += not caught
        lines.append(f"{'검출' if caught else '놓침'} | {name} | rc={p.returncode} 실패 사례 {len(failed)}: {', '.join(failed[:6])}")
    lines.append(f'변형 {len(MUTANTS)} 검출 {len(MUTANTS) - missed} 놓침 {missed}')
    open(os.path.join(a.out, 'mutate.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if missed else 0


if __name__ == '__main__':
    sys.exit(main())
