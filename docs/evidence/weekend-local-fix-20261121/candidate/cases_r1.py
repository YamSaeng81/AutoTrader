"""R1(W4 주문 원천) 직접 영향 4경계 — 총괄 v1.213 §4-1. 합성 입력만. 입력은 원본 cases_w4 의 생성 함수(해시 확인)로 만들고
주문 참조만 바꾼다. 기대 금액은 cases_w4 주석의 손 계산(정상 왕복 989.5, 손실 신규 세션 −509.75)을 그대로 쓴다.
B2 는 키 없음/잘못된 타입 두 변형. 기존 W4 6사례·성적표·변환기 시험은 다시 돌리지 않는다.
사용: python -I cases_r1.py --run <고유호출명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, json, os, subprocess, sys
from decimal import Decimal as D

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import orig  # noqa: E402

W4 = orig.load('cases_w4')
TOOL = os.path.join(HERE, 'build_monthly_r1.py')
L203, D900, RT1, RT_LOSS, F, T = W4.L203, W4.D900, W4.RT1, W4.RT_LOSS, W4.F, W4.T
sess, man, cap = W4.sess, W4.manifest, W4.cap


def patch_orders(d, key, content):
    """매니페스트의 한 세션 주문 참조를 바꾼다. content=None 이면 참조 자체를 null 로(주문 원천 없음)."""
    mp = os.path.join(d, 'manifest.json')
    m = json.load(open(mp, encoding='utf-8'))
    s = next(x for x in m['sessions'] if f"{x['kind']}#{x['id']}" == key)
    if content is None:
        s['orders'] = None
    else:
        with open(os.path.join(d, s['orders']['file']), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(content, f, ensure_ascii=False, indent=1)
    with open(mp, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(m, f, ensure_ascii=False, indent=1)


NONE_FIELDS = ('realized_in_period', 'fees_paid', 'buy_funds', 'round_trips_closed', 'wins', 'losses', 'unrealized_change')
NORMAL = man([sess(L203, cap(F, '20200'), cap(T, '21189.5'), RT1)])
CASES = [
    # (이름, 매니페스트, 주문 참조 변경[(세션, 내용|None)], 기대 verdict, 기대)
    ('B1_orders_not_provided', NORMAL, [('LIVE#203', None)], 'BUILT_WITH_UNCONFIRMED',
     {'cards': {'LIVE#203': dict({'month_pnl': '989.50', 'month_pnl_status': '확정', 'trade_state': '미확정(주문 원천 없음)',
                                  'identity_check': '미확정(주문 원천 없음)', 'orders_source': '미제공'}, **{f: None for f in NONE_FIELDS})},
      'included': {'zero_trade': 0, 'orders_source_missing': 1}, 'agg_real': ('989.50', '확정'),
      'md_row': ('LIVE#203', {4: '미확정(주문 원천 없음)', 6: '미확정', 9: '미확정(주문 원천 없음)'}, ['불일치']),
      'orders_source_missing': ['LIVE#203']}),
    ('B2a_orders_key_missing', NORMAL, [('LIVE#203', {})], 'PARTIAL',
     {'failed': {'LIVE#203': 'ORDERS_KEY_MISSING'}, 'built': [], 'agg_real': (None, '미확정'), 'included': {'zero_trade': 0, 'excluded': 1}}),
    ('B2b_orders_type_invalid', NORMAL, [('LIVE#203', {'orders': {'id': 1}})], 'PARTIAL',
     {'failed': {'LIVE#203': 'ORDERS_TYPE_INVALID'}, 'built': [], 'agg_real': (None, '미확정'), 'included': {'zero_trade': 0, 'excluded': 1}}),
    ('B3_empty_orders_with_other', man([sess(L203, cap(F, '20200'), cap(T, '20200'), []), sess(D900, None, cap(T, '19490.25'), RT_LOSS)]),
     [], 'BUILT_CORE_CONFIRMED',
     {'cards': {'LIVE#203': {'month_pnl': '0.00', 'trade_state': '거래0', 'round_trips_closed': 0, 'fees_paid': '0'},
                'DYNAMIC#900': {'month_pnl': '-509.75', 'realized_in_period': '-509.75', 'fees_paid': '9.75', 'losses': 1}},
      'included': {'zero_trade': 1, 'orders_source_missing': 0}, 'agg_real': ('-509.75', '확정'), 'orders_source_missing': []}),
    # DYNAMIC 900 은 기간 중 생성인데 기초 포착을 줌 → 변환기 v4 계약 위반(convert.py "기간 안 새 세션에 기초 포착을 줌")
    ('B4_session_fail_with_other', man([sess(L203, cap(F, '20200'), cap(T, '21189.5'), RT1),
                                        sess(D900, cap(F, '0'), cap(T, '19490.25'), RT_LOSS)]), [], 'PARTIAL',
     {'failed': {'DYNAMIC#900': 'Bad'}, 'built': ['LIVE#203'],
      'cards': {'LIVE#203': {'month_pnl': '989.50', 'realized_in_period': '989.50', 'fees_paid': '10.50', 'round_trips_closed': 1,
                             'identity_check': '일치'}},
      'agg_real': (None, '미확정'), 'agg_real_known': '989.50', 'agg_real_unconfirmed': ['DYNAMIC#900'], 'account_gap_na': True}),
]


def eq(got, v):
    if isinstance(v, str) and got is not None and not v.startswith(('미확정', '거래', '일치', '확정', '미제공')):
        try:
            return D(got) == D(v)
        except Exception:  # noqa: BLE001
            return False
    return got == v


def check(out, r, rc, stderr, verdict, exp):
    c = {'verdict': r['verdict'] == verdict, 'exit': rc == r['exit'], 'stderr_empty': stderr == b''}
    card = json.load(open(os.path.join(out, 'scorecard.json'), encoding='utf-8'))
    by = {x['session']: x for x in card['sessions']}
    for k, fs in exp.get('cards', {}).items():
        for f, v in fs.items():
            got = by.get(k, {}).get(f, '<없음>')
            c[f'{k}.{f}'] = eq(got, v)
            if not c[f'{k}.{f}']:
                c[f'{k}.{f}=got'] = got
    for k, v in exp.get('included', {}).items():
        c[f'included.{k}'] = card['included'].get(k) == v
    real = next((a for a in card['aggregates'] if a['group'] == 'money' and a['name'] == '실자금'), None)
    if 'agg_real' in exp:
        pv, st = exp['agg_real']
        c['agg_real'] = real is not None and eq(real['month_pnl'], pv) and real['status'] == st
    if 'agg_real_known' in exp:
        c['agg_real_known'] = real is not None and eq(real['known_part_sum'], exp['agg_real_known'])
        c['agg_real_unconfirmed'] = real is not None and real['unconfirmed_sessions'] == exp['agg_real_unconfirmed']
    if exp.get('account_gap_na'):
        c['account_gap_na'] = str(card['account_minus_real_sessions']).startswith('미확인')
    if 'failed' in exp:
        got = {f['session']: f['error'] for f in r['sessions_failed']}
        c['failed'] = got == exp['failed']
        c['built'] = r['sessions_built'] == exp['built']
    if 'orders_source_missing' in exp:
        c['orders_source_missing'] = r['orders_source_missing'] == exp['orders_source_missing']
    if 'md_row' in exp:
        # r1-run1: 표시 칸의 '거래0 아님' 문구를 금지 문자열로 잡은 시험 결함 → 칸 번호(거래·이 달 실현·수수료)로 대조
        k, cells, mustnot = exp['md_row']
        row = next((l for l in open(os.path.join(out, 'scorecard.md'), encoding='utf-8') if l.startswith(f'| {k} |')), '')
        col = [x.strip() for x in row.split('|')]
        c['md_row'] = all(len(col) > i and col[i] == v for i, v in cells.items()) and not any(m in row for m in mustnot)
    smap = json.load(open(os.path.join(out, 'source_map.json'), encoding='utf-8'))
    c['source_map_reason'] = all(v.get('why') for v in smap['values'] if v.get('status') in ('미확정', '거절', '변환 실패'))
    # 원천 없는 거래 값이 0/거래0/확정 승패로 남지 않음(모든 카드 공통)
    c['no_zero_from_missing'] = all(x['trade_state'] != '거래0' and x['wins'] is None for x in card['sessions'] if x.get('orders_source') == '미제공')
    return c


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    lines, summary = [], []
    for name, m, patches, verdict, exp in CASES:
        d = os.path.join(run, name)
        os.makedirs(os.path.join(d, 'in'))
        mp = W4.write_case(os.path.join(d, 'in'), m)
        for key, content in patches:
            patch_orders(os.path.join(d, 'in'), key, content)
        out = os.path.join(d, 'out')
        p = subprocess.run([sys.executable, '-I', TOOL, '--manifest', mp, '--out', out], capture_output=True)
        open(os.path.join(d, 'rc.txt'), 'w', newline='\n').write(f'{p.returncode}\n')
        open(os.path.join(d, 'stderr.txt'), 'wb').write(p.stderr)
        r = json.load(open(os.path.join(out, 'result.json'), encoding='utf-8'))
        c = check(out, r, p.returncode, p.stderr, verdict, exp) if r['verdict'] not in ('INVALID', 'ERROR') else \
            {'verdict': False, 'message': r.get('message')}
        ok = all(v is True for v in c.values() if isinstance(v, bool)) and not any(k.endswith('=got') for k in c)
        summary.append({'case': name, 'ok': ok, 'rc': p.returncode, 'verdict': r['verdict'], 'checks': c})
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} rc={p.returncode} {r['verdict']}" + ('' if ok else f' {c}'))
    n = sum(s['ok'] for s in summary)
    lines.append(f'{n}/{len(summary)} 기대대로 (R1 직접 영향 4경계·5변형, 합성 입력, 기대 금액은 cases_w4 손 계산)')
    json.dump({'synthetic': True, 'cases': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1, default=str)
    open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
