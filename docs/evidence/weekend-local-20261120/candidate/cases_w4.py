"""W4 직접 영향 6사례 — 합성 입력만(작은 거래, 기대 금액은 아래 주석의 손 계산). runs/<호출명>/ 에 사례별 매니페스트·원천 파일·
산출·종료값을 남긴다(덮어쓰기 금지). 기대값은 성적표 코드를 불러 계산하지 않는다.
시계 선언(app=db)·가격·자본은 모두 합성이며 운영 입력으로 쓰지 않는다.
사용: python -I cases_w4.py --run <고유호출명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, copy, json, os, subprocess, sys
from decimal import Decimal as D

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EV = os.path.dirname(ROOT)
TOOL = os.path.join(HERE, 'build_monthly.py')
REG = os.path.join(ROOT, 'fixtures', 'w1', 'registry.json')   # 합성: alloc-900 20000 @ 2026-12-01T00:00Z(app)
BUNDLE = os.path.join(EV, 'dec-collect-assembler-commit-20261120', 'run1', 'normal', 'bundle.json')   # 조립기 현재 행 묶음(읽기만)

F, T, M = '2026-12-01T00:00:00+09:00', '2027-01-01T00:00:00+09:00', '2027-01-01T10:30:00+09:00'
CLOCK = {'same_source': [{'axes': ['app', 'db'], 'basis': '합성 선언(W4 시험) — 운영 시계 측정 아님'}]}
L203 = {'kind': 'LIVE', 'id': 203, 'mode': 'REAL', 'strategy_type': 'DAILY_SMA20', 'timeframe': 'H1',
        'created_at': '2026-10-30T13:16:58+09:00', 'initial_capital': '20200'}
D900 = {'kind': 'DYNAMIC', 'id': 900, 'mode': 'REAL', 'strategy_type': 'COMPOSITE_BREAKOUT', 'timeframe': 'H1',
        'created_at': '2026-12-01T08:50:00+09:00', 'initial_capital': '20000'}


def cap(at, krw):
    return {'captured_at': at, 'axis': 'db', 'snapshot_id': f'syn-{at}', 'gap_evidence': None,
            'data': {'available_krw': krw, 'pending_buys': [], 'positions': []}}


def order(i, side, ref, at, qty, funds, fee, cap_at=T):
    o = {'id': i, 'side': side, 'trade_ref': ref,
         'captures': [{'ref': f'c{i}', 'captured_at': cap_at, 'axis': 'db', 'qty': qty, 'funds': funds, 'fee': fee,
                       'reported_observed_at': None, 'settlement_final': True, 'fill_accounting_pending': False}]}
    o['filled_at' if side == 'BUY' else 'sell_applied_at'] = at
    return o


# 정상 왕복(LIVE 203): 매수 0.001 / 10000 / 수수료 5, 매도 0.001 / 11000 / 수수료 5.5
#   기말 가용 = 20200 − 10000 − 5 + 11000 − 5.5 = 21189.5,  월손익 = 21189.5 − 20200 − 0 = 989.5
#   실현 = (11000 − 5.5) − 0.001 × (10000 + 5)/0.001 = 10994.5 − 10005 = 989.5,  수수료 표시 = 5 + 5.5 = 10.5(다시 빼지 않음)
RT1 = [order(1, 'BUY', 't1', '2026-12-10T10:00:00+09:00', '0.001', '10000', '5'),
       order(2, 'SELL', 't1', '2026-12-20T10:00:00+09:00', '0.001', '11000', '5.5')]
# 둘째 왕복(확정): 매수 0.0005 / 5000 / 2.5, 매도 0.0005 / 5500 / 2.75 → 실현 = 5497.25 − 5002.5 = 494.75
RT2 = [order(3, 'BUY', 't2', '2026-12-11T10:00:00+09:00', '0.0005', '5000', '2.5'),
       order(4, 'SELL', 't2', '2026-12-21T10:00:00+09:00', '0.0005', '5500', '2.75')]
# 손실 왕복(DYNAMIC 900): 매수 0.002 / 10000 / 5, 매도 0.002 / 9500 / 4.75
#   기말 가용 = 20000 − 10005 + 9495.25 = 19490.25, 기초 0(기간 중 생성), 순입금 20000 → 월손익 = 19490.25 − 0 − 20000 = −509.75
#   실현 = 9495.25 − 10005 = −509.75, 수수료 = 9.75
RT_LOSS = [order(5, 'BUY', 't5', '2026-12-02T10:00:00+09:00', '0.002', '10000', '5'),
           order(6, 'SELL', 't5', '2026-12-03T10:00:00+09:00', '0.002', '9500', '4.75')]


def sess(meta, start, end, orders):
    return {'meta': meta, 'start': start, 'end': end, 'orders': orders}


def manifest(sessions, clock=CLOCK, registry=True):
    return {'sessions': sessions, 'clock': clock, 'registry': registry}


CASES = []


def case(name, man, verdict, expect, note=''):
    CASES.append((name, man, verdict, expect, note))


case('N1_normal_round_trip', manifest([sess(L203, cap(F, '20200'), cap(T, '21189.5'), RT1)]), 'BUILT_CORE_CONFIRMED',
     {'LIVE#203': {'month_pnl': '989.50', 'realized_in_period': '989.50', 'fees_paid': '10.50', 'round_trips_closed': 1, 'wins': 1,
                   'losses': 0, 'identity_check': '일치'}})
# 매수 원가 미확정: t1 매수 포착이 관측 마감 M 뒤(2027-01-02) → 값 미확정. t2 는 확정.
#   기말 가용 = 20200 −10005 +10994.5 −5002.5 +5497.25 = 21684.25 → 월손익 1484.25(경계로 확정)
#   실현 = 미확정, 알려진 부분 = 494.75(t2). 수수료 미확정, 알려진 부분 = 5.5 + 2.5 + 2.75 = 10.75
c2 = copy.deepcopy(RT1)
c2[0]['captures'][0]['captured_at'] = '2027-01-02T10:00:00+09:00'
case('C2_buy_cost_unconfirmed', manifest([sess(L203, cap(F, '20200'), cap(T, '21684.25'), c2 + RT2),
                                          sess(D900, None, cap(T, '20000'), [])]), 'BUILT_WITH_UNCONFIRMED',
     {'LIVE#203': {'month_pnl': '1484.25', 'realized_in_period': None, 'realized_known_part': '494.75', 'fees_paid': None,
                   'fees_known_part': '10.75', 'round_trips_closed': None, 'round_trips_closed_known_part': 1},
      'DYNAMIC#900': {'month_pnl': '0.00', 'trade_state': '거래0'}}, '다른 세션 DYNAMIC 900(기간 중 생성, 거래0) 확정 유지')
c3 = copy.deepcopy(RT1)
c3[1]['sell_applied_at'] = None
# 매도 월 귀속 미확정: 매도 정산 시각 없음 → 실현·왕복 미확정, 수수료 알려진 부분 = 매수 5. 월손익은 경계로 989.5
case('C3_sell_month_unconfirmed', manifest([sess(L203, cap(F, '20200'), cap(T, '21189.5'), c3)]), 'BUILT_WITH_UNCONFIRMED',
     {'LIVE#203': {'month_pnl': '989.50', 'realized_in_period': None, 'fees_paid': None, 'fees_known_part': '5.00',
                   'round_trips_closed': None}})
# 시계 관계 없음: app(생성·주문 반영)과 db(기간·경계) 관계 선언 없음 → 생성 소속·기초·주문 월 귀속 미확정. 숫자를 만들지 않는다.
case('C4_no_clock_relation', manifest([sess(L203, cap(F, '20200'), cap(T, '21189.5'), RT1)], clock=None), 'BUILT_WITH_UNCONFIRMED',
     {'LIVE#203': {'month_pnl': None, 'start_equity': None, 'realized_in_period': None}})
# 월말 경계 포착 없음: 기말로 조립기 DB 부분 묶음(현재 행)을 넣음 → 경계 포착으로 받지 않음 → 기말·월손익 미확정, 실현은 확정
case('C5_no_month_end_capture', manifest([sess(L203, cap(F, '20200'), 'BUNDLE', RT1)]), 'BUILT_WITH_UNCONFIRMED',
     {'LIVE#203': {'month_pnl': None, 'end_equity': None, 'realized_in_period': '989.50', 'fees_paid': '10.50'}})
# 거래0(LIVE 203: 기초=기말 20200 → 0) + 손실·기간 중 생성(DYNAMIC 900: −509.75) → 실자금 묶음 합 = −509.75
case('C6_zero_trade_loss_new_session', manifest([sess(L203, cap(F, '20200'), cap(T, '20200'), []),
                                                 sess(D900, None, cap(T, '19490.25'), RT_LOSS)]), 'BUILT_CORE_CONFIRMED',
     {'LIVE#203': {'month_pnl': '0.00', 'trade_state': '거래0'},
      'DYNAMIC#900': {'month_pnl': '-509.75', 'realized_in_period': '-509.75', 'fees_paid': '9.75', 'losses': 1, 'net_flows': '20000.00',
                      'start_equity': '0.00'},
      '_agg_real': '-509.75', '_included': {'zero_trade': 1, 'loss': 1}})


def write_case(d, man):
    def w(name, obj):
        with open(os.path.join(d, name), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
        return {'file': name}
    m = {'version': 'monthly-manifest-1', 'synthetic': True, 'period': {'from': F, 'to': T, 'axis': 'db'}, 'deadline_M': M,
         'month_bounds': [], 'registry': {'file': os.path.relpath(REG, d)} if man['registry'] else None,
         'clock': w('clock.json', man['clock']) if man['clock'] is not None else None, 'benchmarks': None, 'account': None,
         'sessions': []}
    for s in man['sessions']:
        k = f"{s['meta']['kind']}{s['meta']['id']}"
        e = {'kind': s['meta']['kind'], 'id': s['meta']['id'], 'meta': w(f'{k}-meta.json', s['meta']),
             'boundary_start': w(f'{k}-start.json', s['start']) if s['start'] else None,
             'boundary_end': ({'file': os.path.relpath(BUNDLE, d)} if s['end'] == 'BUNDLE' else w(f'{k}-end.json', s['end'])),
             'orders': w(f'{k}-orders.json', {'orders': s['orders']})}
        m['sessions'].append(e)
    w('manifest.json', m)
    return os.path.join(d, 'manifest.json')


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    lines, summary = [], []
    for name, man, verdict, expect, note in CASES:
        d = os.path.join(run, name)
        os.makedirs(os.path.join(d, 'in'))
        mp = write_case(os.path.join(d, 'in'), man)
        out = os.path.join(d, 'out')
        p = subprocess.run([sys.executable, '-I', TOOL, '--manifest', mp, '--out', out], capture_output=True)
        open(os.path.join(d, 'rc.txt'), 'w', newline='\n').write(f'{p.returncode}\n')
        r = json.load(open(os.path.join(out, 'result.json'), encoding='utf-8'))
        checks = {'verdict': r['verdict'] == verdict, 'exit': p.returncode == r['exit'], 'stderr_empty': p.stderr == b''}
        if r['verdict'] not in ('INVALID', 'ERROR'):
            card = json.load(open(os.path.join(out, 'scorecard.json'), encoding='utf-8'))
            smap = json.load(open(os.path.join(out, 'source_map.json'), encoding='utf-8'))
            by = {c['session']: c for c in card['sessions']}
            for k, exp in expect.items():
                if k == '_agg_real':
                    g = next(x for x in card['aggregates'] if x['group'] == 'money' and x['name'] == '실자금')
                    checks['agg_real'] = g['month_pnl'] == exp
                elif k == '_included':
                    checks['included'] = all(card['included'][kk] == vv for kk, vv in exp.items())
                else:
                    for f, v in exp.items():
                        got = by[k][f]
                        if isinstance(v, str) and got is not None and f != 'trade_state' and f != 'identity_check':
                            ok = D(got) == D(v)
                        else:
                            ok = got == v
                        checks[f'{k}.{f}'] = ok
                        if not ok:
                            checks[f'{k}.{f}=got'] = got
            checks['source_map_has_files'] = bool(smap['files_sha256']) and any(v.get('file') for v in smap['values'])
            checks['source_map_unconfirmed_has_reason'] = all(v.get('why') for v in smap['values'] if v.get('status') == '미확정')
            if name.startswith('C5'):
                checks['bundle_not_used_as_capture'] = any('조립기 DB 부분 묶음' in str(v.get('why')) for v in smap['values'])
        ok = all(v is True for v in checks.values() if isinstance(v, bool)) and not any(k.endswith('=got') for k in checks)
        summary.append({'case': name, 'ok': ok, 'rc': p.returncode, 'verdict': r['verdict'], 'checks': checks, 'note': note})
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} rc={p.returncode} {r['verdict']}" + ('' if ok else f' {checks} {r.get("message", "")}'))
    n = sum(s['ok'] for s in summary)
    lines.append(f'{n}/{len(summary)} 기대대로 (기대 금액은 손 계산, 합성 입력)')
    json.dump({'synthetic': True, 'cases': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1, default=str)
    open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
