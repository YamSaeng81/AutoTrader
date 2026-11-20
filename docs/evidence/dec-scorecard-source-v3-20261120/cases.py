"""성적표 원천 v3·성적표 v4 후보 시험 — 총괄 v1.203 §2-1~3(S1~S3). 가상 입력. 운영·DB·네트워크 접속 없음.
사용: python -I cases.py --out <폴더>    종료값: 전부 통과 0, 하나라도 실패 1.
RED: v1.203 검토 입력(review-09f6bd4a/contract-probes.json S1~S3)을 452차 원문 convert v2 + scorecard v3 에 넣어 실패를 먼저 고정.
그다음 새 사본(convert.py v3 → scorecard/scorecard.py v4)에서 직접 반례·월 경계·이월 원가·새 세션 영향 범위만 확인한다.
27건 전체를 다시 돌리지 않는다. 같은 시계 선언(app/db)은 시험용 가상 선언이며 운영 측정이 아니다.
"""
import argparse, copy, hashlib, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.normpath(os.path.join(HERE, '..'))


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


OLD_C = load(os.path.join(EV, 'dec-scorecard-source-v2-20261120', 'convert.py'), 'convert_v2')
OLD_V = load(os.path.join(EV, 'dec-scorecard-v3-20261119', 'scorecard', 'scorecard.py'), 'scorecard_v3')
C = load(os.path.join(HERE, 'convert.py'), 'convert_v3')
V = load(os.path.join(HERE, 'scorecard', 'scorecard.py'), 'scorecard_v4')
PROBES = os.path.join(EV, 'review-09f6bd4a', 'contract-probes.json')
FROM, T, M = '2026-12-01T00:00:00+09:00', '2027-01-01T00:00:00+09:00', '2027-01-01T10:30:00+09:00'
BOUNDS = ['2027-02-01T00:00:00+09:00', '2027-03-01T00:00:00+09:00', '2027-04-01T00:00:00+09:00']
SAME = {'same_source': [{'axes': ['app', 'db'], 'basis': '시험용 가상 선언 — 운영 시계 측정 아님'}]}


def snap(krw, at, ax='db', ev=None, positions=()):
    return {'captured_at': at, 'axis': ax, 'snapshot_id': 'db-1', 'gap_evidence': ev,
            'data': {'available_krw': krw, 'pending_buys': [], 'positions': list(positions)}}


def cap(at, qty, funds, fee, ref, final=True, pend=False, ax='db', ro=None, vev=None):
    c = {'ref': ref, 'captured_at': at, 'axis': ax, 'qty': qty, 'funds': funds, 'fee': fee, 'reported_observed_at': ro,
         'settlement_final': final, 'fill_accounting_pending': pend}
    if vev is not None:
        c['value_evidence'] = vev
    return c


def vev(frm=T, to='2027-01-01T10:12:00+09:00', fields=('qty', 'funds', 'fee', 'settlement_final'), kind='no_change', ax='db'):
    return {'kind': kind, 'ref': 'ev-order-unchanged', 'axis': ax, 'from': frm, 'to': to, 'covers_fields': list(fields)}


def buy(oid, at, caps, ref='trade-1'):
    return {'id': oid, 'side': 'BUY', 'trade_ref': ref, 'filled_at': at, 'captures': caps}


def sell(oid, at, caps, ref='trade-1'):
    return {'id': oid, 'side': 'SELL', 'trade_ref': ref, 'sell_applied_at': at, 'captures': caps}


def src(orders=(), clock=SAME, events=(), **kw):
    s = {'kind': 'LIVE', 'id': 203, 'mode': 'REAL', 'strategy_type': 'X', 'timeframe': 'H1',
         'created_at': '2026-11-01T00:00:00+09:00', 'initial_capital': '20200',
         'boundary': {'start': snap('20200', FROM), 'end': snap('20200', T)}, 'orders': list(orders)}
    s.update(kw)
    out = {'version': 'scorecard-source-3', 'period': {'from': FROM, 'to': T, 'axis': 'db'}, 'deadline_M': M,
           'month_bounds': BOUNDS, 'registry_events': list(events), 'sessions': [s]}
    if clock is not None:
        out['clock'] = clock
    return out


def ev(ref, typ, amt, at, ax='app'):
    return {'ref': ref, 'type': typ, 'amount': amt, 'currency': 'KRW', 'applied_at': at, 'recorded_at': at, 'axis': ax,
            'session': ['LIVE', 203] if typ in ('ALLOCATE', 'RECOVER') else None}


AT_T = T
LATE = '2027-01-01T10:12:00+09:00'


def run(s, C=C, V=V):
    try:
        inp, rep = C.convert(copy.deepcopy(s))
    except C.Bad as e:
        return {'rc': 2, 'error': str(e)}
    try:
        sc = V.build(copy.deepcopy(inp))
    except V.Bad as e:
        return {'rc': 'sc-2', 'error': str(e), 'input': inp, 'report': rep}
    return {'rc': 0, 'input': inp, 'report': rep, 'card': sc['sessions'][0]}


CASES = []


def case(name):
    def deco(f):
        CASES.append((name, f))
        return f
    return deco


probes = {p['name']: p for p in json.load(open(PROBES, encoding='utf-8'))['probes']}


# ── RED: 452차 원문에서 실패 고정 ─────────────────────────────
@case('RED-S1 원문: 미확정 매수 원가로 확정 실현 −10015')
def _():
    r = run(probes['S1_unknown_buy_cost_used_for_confirmed_realized']['source'], OLD_C, OLD_V)
    return r['card']['realized_in_period'] == '-10015', r['card']['realized_in_period']


@case('RED-S2 원문: 1월 정정 수수료가 12월 확정 수수료 11')
def _():
    r = run(probes['S2_January_correction_also_in_December_confirmed_fee']['source'], OLD_C, OLD_V)
    return r['card']['fees_paid'] == '11', r['card']['fees_paid']


@case('RED-S3 원문: 관계 없는 축(db) 이벤트로 순입금 확정')
def _():
    r = run(probes['S3_event_axis_without_relation_is_confirmed']['source'], OLD_C, OLD_V)
    return r['report']['sessions'][0]['flows']['status'] == '확정', r['report']['sessions'][0]['flows']


# ── S1 값 미상 ≠ 시각 미상 ───────────────────────────────────
def s1_source():
    return src([buy(1, '2026-12-20T10:00:00+09:00', [cap('2027-01-02T10:00:00+09:00', '0.1', '20000', '10', 'b-late')]),
                sell(2, '2026-12-30T10:00:00+09:00', [cap(AT_T, '0.1', '10000', '5', 's-T')])])


@case('S1 매수 원가 미확정 → 실현·승패·왕복 미확정, 숫자 미전달')
def _():
    r = run(s1_source())
    c, b = r['card'], r['input']['sessions'][0]['trades'][0]['buy']
    ok = (b['value_status'] == '미확정' and b['funds'] is None and b['settled_at'] is not None
          and c['realized_in_period'] is None and c['realized_known_part'] == '0' and c['wins'] is None
          and c['round_trips_closed'] is None and len(c['round_trips_unconfirmed']) == 1 and c['fees_paid'] is None)
    return ok, dict(buy=b, realized=c['realized_in_period'], known=c['realized_known_part'], fees=c['fees_paid'], fees_known=c['fees_known_part'])


@case('S1b 원가 확인된 다른 거래의 부분 합 보존')
def _():
    s = s1_source()
    s['sessions'][0]['orders'] += [buy(3, '2026-12-10T10:00:00+09:00', [cap(AT_T, '0.1', '9000', '4', 'b2-T')], 'trade-2'),
                                   sell(4, '2026-12-12T10:00:00+09:00', [cap(AT_T, '0.1', '10000', '5', 's2-T')], 'trade-2')]
    c = run(s)['card']
    ok = (c['realized_in_period'] is None and c['realized_known_part'] == '991' and c['wins_known_part'] == 1
          and c['round_trips_closed_known_part'] == 1 and c['wins'] is None and c['fees_known_part'] == '14')
    return ok, dict(known=c['realized_known_part'], wins_known=c['wins_known_part'], fees_known=c['fees_known_part'])


@case('S1c 성적표 v4: 값 미확정인데 숫자를 주면 거절')
def _():
    inp = run(s1_source())['input']
    inp['sessions'][0]['trades'][0]['buy'].update(qty='0.1', funds='20000', fee='10')
    try:
        V.build(inp)
    except V.Bad as e:
        return '최신 포착 값' in str(e), str(e)
    return False, '통과됨'


@case('S1d 이월 매수(11월) 원가 미확정 + 12월 매도 → 이월 원가 null·이 달 실현 미확정')
def _():
    s = src([buy(1, '2026-11-20T10:00:00+09:00', [cap('2026-12-01T00:00:00+09:00', '0.1', '9000', '4', 'b-from')]),
             sell(2, '2026-12-10T10:00:00+09:00', [cap(AT_T, '0.1', '10000', '5', 's-T')])])
    r = run(s)
    cb = r['report']['sessions'][0]['carry_in_buys'][0]
    c = r['card']
    return (cb['cost_krw'] is None and cb['status'] == '미확정' and c['realized_in_period'] is None
            and c['fees_paid'] == '5'), dict(carry=cb, realized=c['realized_in_period'], fees=c['fees_paid'])


@case('S1e 이월 매수 원가를 T 포착으로 확인 → 이월 원가 9004·이월 왕복 확정')
def _():
    s = src([buy(1, '2026-11-20T10:00:00+09:00', [cap(AT_T, '0.1', '9000', '4', 'b-T')]),
             sell(2, '2026-12-10T10:00:00+09:00', [cap(AT_T, '0.1', '10000', '5', 's-T')])])
    r = run(s)
    cb, c = r['report']['sessions'][0]['carry_in_buys'][0], r['card']
    return (cb['cost_krw'] == '9004' and c['realized_in_period'] == '991' and c['round_trips'][0]['carry_in'] is True
            and c['fees_paid'] == '5'), dict(carry=cb, realized=c['realized_in_period'])


# ── S2 C4 철회 ────────────────────────────────────────────────
@case('S2 경계 미확정 수수료 4 → 1월 6 확정: 12월 확정 수수료에 넣지 않음, 정정은 1월')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(AT_T, '0.1', '9000', '4', 'b-T', final=False, ro='2026-12-20T10:00:03+09:00'),
                                                  cap('2027-01-01T10:20:00+09:00', '0.1', '9000', '6', 'jan-fee', ro='2026-12-20T10:00:03+09:00')]),
             sell(2, '2026-12-30T10:00:00+09:00', [cap(AT_T, '0.1', '10000', '5', 's-T')])])
    r = run(s)
    c, cor = r['card'], r['report']['sessions'][0]['corrections']
    ok = c['fees_paid'] is None and c['fees_known_part'] == '5' and c['realized_in_period'] is None and cor[0]['attribution'] == '2027-01'
    return ok, dict(fees=c['fees_paid'], fees_known=c['fees_known_part'], correction=cor)


@case('S2b 포착 시각 = T·정산 확정 → 확정')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(AT_T, '0.1', '9000', '4', 'b-T')]),
             sell(2, '2026-12-30T10:00:00+09:00', [cap(AT_T, '0.1', '10000', '5', 's-T')])])
    c = run(s)['card']
    return c['fees_paid'] == '9' and c['realized_in_period'] == '991' and c['wins'] == 1, dict(fees=c['fees_paid'], realized=c['realized_in_period'])


@case('S2c T 뒤 포착 + reported_observed_at < T 만 → 미확정(옛 C4 철회, 매도도 같음)')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(LATE, '0.1', '9000', '4', 'b', ro='2026-12-20T10:00:03+09:00')]),
             sell(2, '2026-12-30T10:00:00+09:00', [cap(LATE, '0.1', '10000', '5', 's', ro='2026-12-30T10:00:05+09:00')])])
    r = run(s)
    t = r['input']['sessions'][0]['trades'][0]
    return (t['buy']['value_status'] == '미확정' and t['sells'][0]['value_status'] == '미확정'
            and r['card']['fees_paid'] is None), [o['why'] for o in r['report']['sessions'][0]['orders']]


@case('S2d T 뒤 포착 + 값별 불변 근거(전 값·[T,포착] 전체) → 확정')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(LATE, '0.1', '9000', '4', 'b', vev=vev())]),
             sell(2, '2026-12-30T10:00:00+09:00', [cap(LATE, '0.1', '10000', '5', 's', vev=vev())])])
    c = run(s)['card']
    return c['realized_in_period'] == '991' and c['fees_paid'] == '9', c['realized_in_period']


@case('S2e 근거가 fee 를 덮지 않음 → 미확정')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(LATE, '0.1', '9000', '4', 'b', vev=vev(fields=('qty', 'funds', 'settlement_final')))])])
    r = run(s)
    w = r['report']['sessions'][0]['orders'][0]['why']
    return r['input']['sessions'][0]['trades'][0]['buy']['value_status'] == '미확정' and 'fee' in w, w


@case('S2f exact_restore → 보류(미확정)')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(LATE, '0.1', '9000', '4', 'b', vev=vev(kind='exact_restore'))])])
    r = run(s)
    w = r['report']['sessions'][0]['orders'][0]['why']
    return r['input']['sessions'][0]['trades'][0]['buy']['value_status'] == '미확정' and 'exact_restore' in w, w


@case('S2g 근거가 [T,포착] 일부만 덮음 → 미확정')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(LATE, '0.1', '9000', '4', 'b', vev=vev(frm='2027-01-01T05:00:00+09:00'))])])
    r = run(s)
    return r['input']['sessions'][0]['trades'][0]['buy']['value_status'] == '미확정', r['report']['sessions'][0]['orders'][0]['why']


@case('S2h 월 경계 부분 체결(T 포착 정산 대기) → 값 미확정, 시각은 유지')
def _():
    s = src([buy(1, '2026-12-31T23:59:00+09:00', [cap(AT_T, '0.05', '4500', '2', 'b-T', final=False, pend=True),
                                                  cap(LATE, '0.1', '9000', '4', 'b-late', ro='2027-01-01T00:00:10+09:00')])])
    r = run(s)
    b = r['input']['sessions'][0]['trades'][0]['buy']
    return b['value_status'] == '미확정' and b['settled_at'] is not None and r['card']['fees_paid'] is None, b


# ── S3 시각 축 관계 ───────────────────────────────────────────
@case('S3 이벤트 축 app→db, 기간 db·생성 app 관계 선언 없음 → 순입금 미확정')
def _():
    s = src([], clock=None, events=[ev('alloc-1', 'ALLOCATE', '10000', '2026-12-05T00:00:00+09:00', ax='db')],
            created_at='2026-12-05T00:00:00+09:00', initial_capital='10000',
            boundary={'start': None, 'end': snap('10000', T)})
    r = run(s)
    return r['report']['sessions'][0]['flows']['status'] == '미확정' and r['card']['month_pnl'] is None, r['report']['sessions'][0]['flows']


@case('S3b 같은 입력 + app/db 같은 시계 선언 → 순입금 확정(새 세션 기초0 + ALLOCATE, 손익 0)')
def _():
    s = src([], events=[ev('alloc-1', 'ALLOCATE', '10000', '2026-12-05T00:00:00+09:00', ax='db')],
            created_at='2026-12-05T00:00:00+09:00', initial_capital='10000',
            boundary={'start': None, 'end': snap('10000', T)})
    r = run(s)
    return r['report']['sessions'][0]['flows']['status'] == '확정' and r['card']['month_pnl'] == '0', r['card']['month_pnl']


@case('S3c 관계 선언 없음: 앱 관측 시각(app)의 월 귀속도 미확정 → 시각 null')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(AT_T, '0.1', '9000', '4', 'b-T')])], clock=None)
    r = run(s)
    b = r['input']['sessions'][0]['trades'][0]['buy']
    return b['settled_at'] is None and b['value_status'] == '확정' and r['card']['fees_paid'] is None, b


@case('S3d 경계 포착 축 exchange ≠ 기간 축 db → 기말 미확정')
def _():
    s = src([])
    s['sessions'][0]['boundary']['end'] = snap('20200', T, ax='exchange')
    r = run(s)
    v = r['report']['sessions'][0]['values'][1]
    return v['status'] == '미확정' and r['card']['month_pnl'] is None, v['why']


@case('S3e 주문 포착 축 관계 없음 → 값 미확정')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(AT_T, '0.1', '9000', '4', 'b-T', ax='exchange')])])
    r = run(s)
    return r['input']['sessions'][0]['trades'][0]['buy']['value_status'] == '미확정', r['report']['sessions'][0]['orders'][0]['why']


@case('S3f 정정 포착 축 관계 없음 → 귀속 미확정(같은 달 안이어도)')
def _():
    s = src([buy(1, '2026-12-20T10:00:00+09:00', [cap(AT_T, '0.1', '9000', '4', 'b-T'),
                                                  cap('2027-01-05T00:00:00+09:00', '0.1', '9000', '5', 'b-jan', ax='exchange')])])
    cor = run(s)['report']['sessions'][0]['corrections'][0]
    return cor['attribution'] == '귀속 미확정', cor


@case('S3g 경계 불변 근거가 값별 범위를 밝히지 않음(price 누락) → 기말 미확정')
def _():
    s = src([])
    pos = [{'id': 9, 'coin': 'BTC', 'status': 'OPEN', 'size': '0.1', 'price': '100000', 'cost_krw': '9004'}]
    e = {'kind': 'no_change', 'ref': 'ev-sess', 'axis': 'db', 'from': T, 'to': LATE,
         'covers_fields': ['available_krw', 'pending_buys', 'positions']}
    s['sessions'][0]['boundary']['end'] = snap('20200', LATE, ev=e, positions=pos)
    v = run(s)['report']['sessions'][0]['values'][1]
    return v['status'] == '미확정' and 'price' in v['why'], v['why']


@case('V 옛 원천 version scorecard-source-2 → 거절')
def _():
    s = src([])
    s['version'] = 'scorecard-source-2'
    r = run(s)
    return r['rc'] == 2, r.get('error')


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    lines = []
    for f in ('convert.py', os.path.join('scorecard', 'scorecard.py')):
        lines.append(f"{f.replace(os.sep, '/')} sha256={hashlib.sha256(open(os.path.join(HERE, f), 'rb').read()).hexdigest()}")
    n = ok = 0
    for name, fn in CASES:
        n += 1
        try:
            passed, detail = fn()
        except Exception as e:  # noqa: BLE001
            passed, detail = False, f'{type(e).__name__}: {e}'
        ok += bool(passed)
        lines.append(f"{'PASS' if passed else 'FAIL'} | {name} | {json.dumps(detail, ensure_ascii=False, default=str)[:300]}")
    lines.append(f'합계 {n} 통과 {ok} 실패 {n - ok}')
    out = '\n'.join(lines) + '\n'
    open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write(out)
    print(out, end='')
    return 0 if ok == n else 1


if __name__ == '__main__':
    sys.exit(main())
