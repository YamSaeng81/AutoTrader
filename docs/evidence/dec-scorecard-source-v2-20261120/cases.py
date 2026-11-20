"""성적표 원천 v2 변환기 시험 — 총괄 v1.202 반례(S1~S5)를 먼저, 그다음 영향 경계만. 가상 입력. 운영·DB·네트워크 접속 없음.
사용: python -I cases.py --out <폴더>    종료값: 전부 통과 0, 하나라도 실패 1.
각 사례는 convert() → 성적표 v3 원문 build() 까지 이어서 확인한다(v3 원문은 읽기만).
"""
import argparse, copy, hashlib, importlib.util, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.normpath(os.path.join(HERE, '..'))
V3 = os.path.join(EV, 'dec-scorecard-v3-20261119', 'scorecard', 'scorecard.py')


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


C = load(os.path.join(HERE, 'convert.py'), 'convert')
V = load(V3, 'v3')
FROM, T, M = '2026-12-01T00:00:00+09:00', '2027-01-01T00:00:00+09:00', '2027-01-01T10:30:00+09:00'
BOUNDS = ['2027-02-01T00:00:00+09:00', '2027-03-01T00:00:00+09:00', '2027-04-01T00:00:00+09:00']


def snap(krw, at, sid='db-1', ev=None, ax='db'):
    return {'captured_at': at, 'axis': ax, 'snapshot_id': sid, 'gap_evidence': ev,
            'data': {'available_krw': krw, 'pending_buys': [], 'positions': []}}


def cap(at, qty, funds, fee, ro, ref, final=True, pend=False):
    return {'ref': ref, 'captured_at': at, 'qty': qty, 'funds': funds, 'fee': fee, 'reported_observed_at': ro,
            'settlement_final': final, 'fill_accounting_pending': pend}


def base(**kw):
    s = {'kind': 'LIVE', 'id': 203, 'mode': 'REAL', 'strategy_type': 'X', 'timeframe': 'H1',
         'created_at': '2026-11-01T00:00:00+09:00', 'initial_capital': '20200',
         'boundary': {'start': snap('20200', FROM), 'end': snap('20200', '2027-01-01T00:00:00+09:00')}, 'orders': []}
    s.update(kw)
    return {'version': 'scorecard-source-2', 'period': {'from': FROM, 'to': T}, 'deadline_M': M, 'month_bounds': BOUNDS,
            'registry_events': [], 'sessions': [s]}


def ev(ref, typ, amt, at, sess=(('LIVE', 203)), **kw):
    e = {'ref': ref, 'type': typ, 'amount': amt, 'currency': 'KRW', 'applied_at': at, 'recorded_at': at, 'axis': 'app',
         'session': list(sess) if typ in ('ALLOCATE', 'RECOVER') else None}
    e.update(kw)
    return e


def run(src):
    try:
        inp, rep = C.convert(copy.deepcopy(src))
    except C.Bad as e:
        return {'rc': 2, 'error': str(e)}
    try:
        sc = V.build(copy.deepcopy(inp))
    except V.Bad as e:
        return {'rc': 'v3-2', 'error': str(e), 'input': inp, 'report': rep}
    return {'rc': 0, 'input': inp, 'report': rep, 'card': sc['sessions'][0], 'account': sc['account']}


CASES = []


def case(name, src, check):
    CASES.append((name, src, check))


# ── 반례 먼저 ─────────────────────────────────────────────
TR = 'trade-1'
nov_buy = {'id': 1, 'side': 'BUY', 'trade_ref': TR, 'filled_at': '2026-11-20T10:00:00+09:00',
           'captures': [cap('2027-01-01T10:12:00+09:00', '0.1', '9000', '4', '2026-11-20T10:00:03+09:00', 'c1')]}
nov_sell = {'id': 2, 'side': 'SELL', 'trade_ref': TR, 'sell_applied_at': '2026-11-30T10:00:00+09:00',
            'captures': [cap('2027-01-01T10:12:00+09:00', '0.1', '10000', '5', '2026-11-30T10:00:05+09:00', 'c2')]}
case('S1 11-30 매도는 12월 거래가 아님', base(orders=[nov_buy, nov_sell]),
     lambda r: r['rc'] == 0 and r['input']['sessions'][0]['trades'] == [] and
     any(o['order'] == 2 and o['month'] == '이전 달' for o in r['report']['sessions'][0]['orders']) and r['card']['realized_in_period'] == '0')

dec_sell = dict(nov_sell, sell_applied_at='2026-12-30T10:00:00+09:00',
                captures=[cap('2027-01-01T10:12:00+09:00', '0.1', '10000', '5', '2026-12-30T10:00:05+09:00', 'c2a'),
                          cap('2027-03-03T09:00:00+09:00', '0.1', '10000', '5.01', '2027-03-03T08:59:00+09:00', 'c2b')])
dec_buy = dict(nov_buy, filled_at='2026-12-20T10:00:00+09:00',
               captures=[cap('2027-01-01T10:12:00+09:00', '0.1', '9000', '4', '2026-12-20T10:00:03+09:00', 'c1')])
case('S2 3월에 처음 본 수수료 차이는 귀속 미확정(1월로 보내지 않음)', base(orders=[dec_buy, dec_sell]),
     lambda r: r['rc'] == 0 and r['report']['sessions'][0]['corrections'][0]['attribution'] == '귀속 미확정' and
     r['report']['sessions'][0]['corrections'][0]['prev_capture'] == 'c2a' and
     r['report']['sessions'][0]['corrections'][0]['known_delta'] == {'fee': '0.01'} and
     r['input']['sessions'][0]['trades'][0]['sells'][0]['fee'] == '5' and r['card']['fees_paid'] == '9')
jan2 = copy.deepcopy(dec_sell)
jan2['captures'][1].update(captured_at='2027-01-20T09:00:00+09:00', reported_observed_at='2027-01-20T08:59:00+09:00')
case('S2b 같은 달 안 두 포착 사이 차이는 그 달(1월)', base(orders=[dec_buy, jan2]),
     lambda r: r['rc'] == 0 and r['report']['sessions'][0]['corrections'][0]['attribution'] == '2027-01')

case('S3 1월1일10:00 회차를 근거 없이 12월말로 쓰지 않음', base(boundary={'start': snap('10000', FROM), 'end': snap('9900', '2027-01-01T10:00:00+09:00')}),
     lambda r: r['rc'] == 0 and 'end' not in r['input']['sessions'][0] and r['card']['month_pnl'] is None and
     r['report']['sessions'][0]['values'][1]['gap_seconds'] == 36000)
NOCH = {'kind': 'no_change', 'ref': 'app-log:no-order-no-transfer', 'axis': 'db', 'from': T, 'to': '2027-01-01T10:00:00+09:00'}
case('S3b 공백 전체를 덮는 같은 축 불변 근거가 있으면 확정', base(boundary={'start': snap('10000', FROM), 'end': snap('10000', '2027-01-01T10:00:00+09:00', ev=NOCH)}),
     lambda r: r['rc'] == 0 and r['card']['month_pnl'] == '0')
case('S3c 근거 축이 다르면 미확정', base(boundary={'start': snap('10000', FROM), 'end': snap('10000', '2027-01-01T10:00:00+09:00', ev=dict(NOCH, axis='app'))}),
     lambda r: r['rc'] == 0 and r['card']['month_pnl'] is None)
case('S3d 근거가 공백 일부만 덮으면 미확정', base(boundary={'start': snap('10000', FROM), 'end': snap('10000', '2027-01-01T10:00:00+09:00', ev=dict(NOCH, to='2027-01-01T09:00:00+09:00'))}),
     lambda r: r['rc'] == 0 and r['card']['month_pnl'] is None)
case('S3e M 뒤 포착은 근거가 있어도 미확정', base(boundary={'start': snap('10000', FROM), 'end': snap('10000', '2027-01-02T10:00:00+09:00', ev=dict(NOCH, to='2027-01-02T10:00:00+09:00'))}),
     lambda r: r['rc'] == 0 and r['card']['month_pnl'] is None)
case('S3f 월초 기초도 같은 규칙(11-30 23:00 포착, 근거 없음)', base(boundary={'start': snap('10000', '2026-11-30T23:00:00+09:00'), 'end': snap('10000', T)}),
     lambda r: r['rc'] == 0 and 'start' not in r['input']['sessions'][0] and r['card']['month_pnl'] is None)

NEW = {'created_at': '2026-12-05T00:00:00+09:00', 'initial_capital': '10000'}
case('S4 기간 중 생성 세션에 기초 포착을 주면 거절', base(**NEW, boundary={'start': snap('10000', FROM), 'end': snap('10000', T)}),
     lambda r: r['rc'] == 2 and 'S4' in r['error'])
s5 = base(**NEW, boundary={'start': None, 'end': snap('10000', T)})
s5['registry_events'] = [ev('alloc-203-1', 'ALLOCATE', '10000', '2026-12-05T00:00:00+09:00')]
case('S5 기간 중 생성 세션 = 기초0 + ALLOCATE', s5,
     lambda r: r['rc'] == 0 and r['input']['sessions'][0].get('started_in_period') is True and 'start' not in r['input']['sessions'][0] and
     (r['card']['start_equity'], r['card']['net_flows'], r['card']['month_pnl']) == ('0', '10000', '0'))
s5b = copy.deepcopy(s5)
s5b['registry_events'] = []
case('S5b 배정 이벤트 없으면 initial_capital 로 만들지 않고 미확정', s5b,
     lambda r: r['rc'] == 0 and r['input']['sessions'][0]['flows'] is None and r['card']['month_pnl'] is None)
s5c = copy.deepcopy(s5)
s5c['registry_events'][0]['amount'] = '12000'
case('S5c 최초 배정 ≠ initial_capital 이면 거절(대조 원천)', s5c, lambda r: r['rc'] == 2 and '대조 실패' in r['error'])
s5d = copy.deepcopy(s5)
s5d['registry_events'] = None
case('S5d 등록표 이벤트 목록 자체가 없으면 순입금 미확정', s5d, lambda r: r['rc'] == 0 and r['card']['month_pnl'] is None)

# ── 영향 경계 ───────────────────────────────────────────────
part = dict(dec_buy, filled_at='2026-12-31T23:59:00+09:00',
            captures=[cap('2027-01-01T10:12:00+09:00', '0.1', '10000', '5', '2027-01-01T00:01:10+09:00', 'p1')])
case('B1 부분 체결 월 경계(잔량 1월 관측) → 정산 시각 null·수수료 미확정', base(orders=[part]),
     lambda r: r['rc'] == 0 and r['input']['sessions'][0]['trades'][0]['buy']['settled_at'] is None and r['card']['fees_paid'] is None)
case('B2 정산 미확정 포착 → 미확정', base(orders=[dict(dec_buy, captures=[dict(dec_buy['captures'][0], settlement_final=False)])]),
     lambda r: r['rc'] == 0 and r['input']['sessions'][0]['trades'][0]['buy']['settled_at'] is None)
case('B3 주문 포착이 [T,M] 밖 → 미확정(현재 행 복원 안 함)',
     base(orders=[dict(dec_buy, captures=[dict(dec_buy['captures'][0], captured_at='2027-01-05T00:00:00+09:00')])]),
     lambda r: r['rc'] == 0 and r['input']['sessions'][0]['trades'][0]['buy']['settled_at'] is None)
carry_sell = dict(dec_sell, captures=dec_sell['captures'][:1])
case('B4 이월 매수(11월) + 12월 매도 → 이월 원가 별도·이월 왕복', base(orders=[nov_buy, carry_sell]),
     lambda r: r['rc'] == 0 and r['report']['sessions'][0]['carry_in_buys'][0]['cost_krw'] == '9004' and
     r['card']['round_trips'][0]['carry_in'] is True and r['card']['realized_in_period'] == '991')
jan_sell = dict(carry_sell, sell_applied_at='2027-01-01T00:00:05+09:00')
case('B5 T 이후 반영 매도는 이 달에서 제외', base(orders=[dec_buy, jan_sell]),
     lambda r: r['rc'] == 0 and r['input']['sessions'][0]['trades'][0]['sells'] == [])
acc = base()
acc['account'] = {'start': {'captured_at': FROM, 'axis': 'exchange', 'snapshot_id': 'ex-1', 'gap_evidence': None, 'data': {'krw': '50000'}},
                  'end': {'captured_at': '2027-01-01T10:05:00+09:00', 'axis': 'exchange', 'snapshot_id': 'ex-2', 'gap_evidence': None, 'data': {'krw': '50000'}}}
case('B6 계좌 기말도 같은 규칙(DB 포착과 별개) → 미확인 손익', acc,
     lambda r: r['rc'] == 0 and r['account']['month_pnl'] is None and r['report']['account']['same_db_snapshot'] is False)
acc2 = copy.deepcopy(acc)
acc2['account']['end']['axis'] = 'db'
case('B7 계좌 조회를 DB 축으로 선언하면 거절', acc2, lambda r: r['rc'] == 2)
dup = copy.deepcopy(s5)
dup['registry_events'].append(copy.deepcopy(dup['registry_events'][0]))
case('B8 같은 ref 이벤트 중복 → 거절', dup, lambda r: r['rc'] == 2 and '중복' in r['error'])
neg = copy.deepcopy(s5)
neg['registry_events'][0]['amount'] = '-10000'
case('B9 음수 금액 → 거절', neg, lambda r: r['rc'] == 2)
rec = copy.deepcopy(s5)
rec['registry_events'][0]['recorded_at'] = '2026-12-04T00:00:00+09:00'
case('B10 기록 시각 < 적용 시각 → 거절', rec, lambda r: r['rc'] == 2)
dep = copy.deepcopy(s5)
dep['registry_events'].append(dict(ev('dep-1', 'DEPOSIT', '5000', '2026-12-06T00:00:00+09:00'), session=['LIVE', 203]))
case('B11 계좌 입금에 세션 연결 → 거절', dep, lambda r: r['rc'] == 2)
cur = copy.deepcopy(s5)
cur['registry_events'][0]['currency'] = 'USDT'
case('B12 KRW 아닌 통화 → 거절', cur, lambda r: r['rc'] == 2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(os.path.join(a.out, 'cases'), exist_ok=True)
    lines = [f"convert.py sha256={hashlib.sha256(open(os.path.join(HERE, 'convert.py'), 'rb').read()).hexdigest()}",
             f"scorecard v3 sha256={hashlib.sha256(open(V3, 'rb').read()).hexdigest()}"]
    fails = 0
    for i, (name, src, check) in enumerate(CASES):
        r = run(src)
        try:
            ok = bool(check(r))
        except Exception as e:  # noqa: BLE001
            ok, r['check_error'] = False, repr(e)
        fails += not ok
        with open(os.path.join(a.out, 'cases', f'{i:02d}.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'name': name, 'source': src, 'result': r}, f, ensure_ascii=False, indent=1, default=str)
        lines.append(f"{'PASS' if ok else 'FAIL'} | {name} | rc={r['rc']}" + (f" | {r.get('error')}" if r.get('error') else
                     f" | 월손익={r.get('card', {}).get('month_pnl')}"))
    # 파일 입력 1건: CLI 변환 → v3 원문 호출까지
    fdir = os.path.join(a.out, 'file')
    os.makedirs(fdir, exist_ok=True)
    with open(os.path.join(fdir, 'source.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(s5, f, ensure_ascii=False, indent=1)
    p = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'convert.py'), '--source', os.path.join(fdir, 'source.json'),
                        '--out', fdir, '--scorecard', V3], capture_output=True, text=True, encoding='utf-8')
    ok = p.returncode == 0 and os.path.exists(os.path.join(fdir, 'scorecard', 'scorecard.json')) and not p.stderr.strip()
    fails += not ok
    lines.append(f"{'PASS' if ok else 'FAIL'} | F1 파일 변환 → v3 원문 호출 | rc={p.returncode} | {p.stdout.strip().splitlines()[-1] if p.stdout.strip() else p.stderr.strip()[:200]}")
    lines.append(f'합계 {len(CASES) + 1} 통과 {len(CASES) + 1 - fails} 실패 {fails}')
    open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
