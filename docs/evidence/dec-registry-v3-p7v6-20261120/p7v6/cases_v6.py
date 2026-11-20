"""P7 v6 직접 반례 시험(총괄 v1.202 §2-4). 기준 입력 = v4 저장 사례 G3_exact_two_sessions(LIVE203 + DYNAMIC900, 체크섬 확인)에
cases_v5.synth 의 합성 같은 시계 선언을 붙이고 등록표만 registry-3 로 바꾼 것. 가상 값. PG·네트워크·DB 접속 없음.
사용: python -I cases_v6.py --impl <reconcile.py> --out <폴더>   (v5 원문에 먼저 돌려 실패를 고정한다)
"""
import argparse, copy, hashlib, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.normpath(os.path.join(HERE, '..', '..'))
V5DIR = os.path.join(EV, 'dec-reconcile-v5-20261118', 'p7v5')


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


cv = load(os.path.join(V5DIR, 'cases_v5.py'), 'cases_v5')
cv.PG_SUMS = cv.sums(cv.PG)
G3 = cv.synth(dict(cv.r_cases())['G3_exact_two_sessions']['input'])
RS = {'expected': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'confirmed': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      'confirmed_source': 'position.ruleset_hash(가상) + 등록 기대 설정'}


def ev(ref, typ, amt, at, sess=None, cur='KRW', rec=None):
    return {'ref': ref, 'type': typ, 'amount': amt, 'currency': cur, 'applied_at': at, 'recorded_at': rec or at, 'axis': 'app',
            'session': sess}


def base():
    d = copy.deepcopy(G3)
    r = d['registry']
    r['registry_version'] = 'registry-3'
    for e in r['sessions']:
        e['ruleset'] = dict(RS)
    # 기준(11-17 app 축) 미배정 79,800 = 100,000 − LIVE203 배정 20,200(11-06, 기준에 이미 반영). DYNAMIC900 배정은 기준 뒤.
    r['capital_events'] = [ev('alloc-203', 'ALLOCATE', '20200', '2026-11-06T10:07:36Z', ['LIVE', 203]),
                           ev('alloc-900', 'ALLOCATE', '20000', '2026-11-18T01:56:05.591018Z', ['DYNAMIC', 900])]
    r['cash_base'] = {'amount_krw': '79800', 'base_at': '2026-11-17T00:00:00Z', 'axis': 'app',
                      'source': '가상 — 승인된 계좌 읽기 전', 'includes_event_refs': ['alloc-203']}
    r['holdings_base'] = {'coins': {}, 'base_at': '2026-11-17T00:00:00Z', 'axis': 'app', 'source': '가상', 'includes_event_refs': []}
    return d


def now(d):
    return d['now']['db']


C = []


def case(name, d, want, need=(), forbid=(), check=None):
    C.append((name, d, want, need, forbid, check))


def S(r):
    return r.get('account_sum') or {}


case('V0 registry-3 기준 — 합산 일치', base(), 'OK', forbid=('ACCOUNT_SUM_UNAVAILABLE',),
     check=lambda r: S(r).get('status') == '일치' and S(r)['currencies']['KRW']['unallocated'] == '59800')
# P3 — 기준에 이미 든 배정을 다시 빼지 않는다
d = base()
d['registry']['cash_base']['includes_event_refs'] = []
case('P3a 기준 이전 배정이 포함 선언 없음 → 빼지 않고 KRW 합산 보류', d, 'HOLD', need=('ACCOUNT_SUM_BASE_BOUNDARY',), forbid=('ACCOUNT_SUM_MISMATCH',),
     check=lambda r: S(r)['currencies']['KRW']['status'] == '보류' and 'alloc-203' in S(r)['events']['held'])
d = base()
d['registry']['cash_base']['includes_event_refs'] = ['alloc-203', 'alloc-900']
case('P3b 기준 뒤 배정을 기준 포함으로 선언 → 모순 이상', d, 'ANOMALY', need=('ACCOUNT_SUM_BASE_EVENT_CONTRADICTION',))
d = base()
d['registry']['capital_events'][1]['applied_at'] = d['registry']['capital_events'][1]['recorded_at'] = '2026-11-17T00:00:00Z'
case('P3c 기준과 같은 시각 배정(포함 선언 없음) → 불명 경계 보류', d, 'HOLD', need=('ACCOUNT_SUM_BASE_BOUNDARY',))
# P1·P2 — 미등록 실자금
d = base()
now(d)['sessions'].append(dict(now(d)['sessions'][0], id=901, available_krw='0.00', created_at='2026-11-18T01:56:05.9Z'))
case('P1 주문·포지션 없는 미등록 가동 세션 → 이상, 합산 안 맞춤', d, 'ANOMALY', need=('UNREGISTERED_REAL_SESSION_ACTIVE',),
     check=lambda r: S(r).get('status') == '불일치' and 'currencies' not in S(r))
d = base()
o = dict(now(d)['orders'][0], id=77, session_kind='LIVE', session_id=999, position_id=None, state='SUBMITTED', filled_quantity='0',
         exchange_order_id=None, client_order_id='c-77', reported_executed_funds=None, reported_paid_fee=None,
         applied_executed_funds=None, applied_paid_fee=None)
now(d)['orders'].append(o)
case('P2 포지션 없는 미등록 주문 → 이상', d, 'ANOMALY', need=('UNREGISTERED_REAL_ORDER',), check=lambda r: S(r).get('status') == '불일치')
# A1 — DYNAMIC BTC 포함
d = base()
for x in now(d)['orders'] + now(d)['positions']:
    if x['coin_pair'] == 'KRW-ETH':
        x['coin_pair'] = 'KRW-BTC'
d['registry']['sessions'][1]['rules']['excluded_coins'] = []
for side in ('exchange_before', 'exchange_after'):
    acc = d['now'][side]['accounts']
    acc['BTC'] = {'balance': '0.0034', 'locked': '0'}
    del acc['ETH']
case('A1 DYNAMIC 이 BTC 보유 — LIVE+DYNAMIC 합으로 대조', d, 'OK', check=lambda r: S(r)['currencies']['BTC']['sessions_plus_unallocated'] == '0.00340000')
d = copy.deepcopy(C[-1][1])
for side in ('exchange_before', 'exchange_after'):
    d['now'][side]['accounts']['BTC']['balance'] = '0.0002'
case('A1b 거래소 BTC 가 LIVE 몫뿐 → 합산 불일치', d, 'ANOMALY', need=('ACCOUNT_SUM_MISMATCH',))
# OPEN/CLOSING·예약 중복
d = base()
now(d)['positions'][0]['status'] = 'CLOSING'
case('B1 CLOSING 보유는 그 코인 합산 보류', d, 'HOLD', need=('ACCOUNT_SUM_POSITION_CLOSING',), forbid=('ACCOUNT_SUM_MISMATCH',),
     check=lambda r: S(r)['currencies']['BTC']['status'] == '보류' and S(r)['currencies']['KRW']['status'] == '일치')
d = base()
now(d)['orders'].append(dict(now(d)['orders'][1], id=88, position_id=None, state='SUBMITTED', filled_quantity='0', reserved_krw='1000',
                             exchange_order_id='u-88', client_order_id='c-88', reported_executed_funds=None, reported_paid_fee=None,
                             applied_executed_funds=None, applied_paid_fee=None))
now(d)['sessions'][0]['available_krw'] = '3000.00'   # 예약 1,000 은 가용에서 이미 빠짐(DYNAMIC900)
case('B2 체결0 미체결 매수 예약은 KRW 합에 더함(진행 중이라 보류)', d, 'HOLD', check=lambda r: S(r)['currencies']['KRW']['reserved_buys'] == '1000')
d = base()
now(d)['sessions'][0]['available_krw'] = '3000.00'
now(d)['orders'].append(dict(now(d)['orders'][1], id=89, position_id=None, state='PARTIAL_FILLED', filled_quantity='0.001', reserved_krw='1000',
                             exchange_order_id='u-89', client_order_id='c-89'))
case('B3 부분 체결 예약 → KRW·코인 합산 보류(중복 계산 안 함)', d, 'HOLD', need=('ACCOUNT_SUM_PARTIAL_RESERVATION',),
     check=lambda r: S(r)['currencies']['KRW']['reserved_buys'] == '0')
# 이벤트 시각
d = base()
d['registry']['capital_events'].append(ev('dep-late', 'DEPOSIT', '5000', '2026-11-18T02:30:00Z'))
case('E1 현재 관측 뒤 입금은 넣지 않음', d, 'OK', check=lambda r: 'dep-late' in S(r)['events']['after_observation'])
d = base()
d['registry']['capital_events'].append(ev('dep-in', 'DEPOSIT', '5000', '2026-11-18T01:00:00Z'))
case('E2 기록된 입금이 거래소에 없음 → 합산 불일치', d, 'ANOMALY', need=('ACCOUNT_SUM_MISMATCH',))
# 등록표 v3 형식 — 기본값 없음
for name, edit in [
    ('R1 cash_base 없음', lambda r: r.pop('cash_base')),
    ('R2 cash_base.base_at 없음', lambda r: r['cash_base'].pop('base_at')),
    ('R3 이벤트 ref 중복', lambda r: r['capital_events'].append(copy.deepcopy(r['capital_events'][1]))),
    ('R4 음수 금액', lambda r: r['capital_events'][1].update(amount='-20000')),
    ('R5 미등록 세션 배정', lambda r: r['capital_events'][1].update(session=['DYNAMIC', 999])),
    ('R6 입금에 세션 연결', lambda r: r['capital_events'].append(ev('d', 'DEPOSIT', '1', '2026-11-18T01:00:00Z', ['LIVE', 203]))),
    ('R7 기록 < 적용', lambda r: r['capital_events'][1].update(recorded_at='2026-11-18T00:00:00Z')),
    ('R8 포함 선언 ref 가 이벤트에 없음', lambda r: r['cash_base']['includes_event_refs'].append('x')),
    ('R9 ruleset 없음', lambda r: r['sessions'][0].pop('ruleset')),
    ('R10 확인 지문에 출처 없음', lambda r: r['sessions'][0]['ruleset'].update(confirmed_source=None)),
    ('R11 버전 없이 cash_base 만', lambda r: r.update(registry_version='p7v2-draft-local')),
    ('R12 배정 통화 KRW 아님', lambda r: r['capital_events'][1].update(currency='USDT')),
    ('R13 금액 NaN', lambda r: r['capital_events'][1].update(amount='NaN')),
]:
    d = base()
    edit(d['registry'])
    case(name + ' → REGISTRY_INVALID', d, 'ANOMALY', need=('REGISTRY_INVALID',))
d = base()
d['registry']['sessions'][1]['ruleset'].update(confirmed=None, confirmed_source=None)
case('F1 확인 지문 없음 → RULESET_UNCONFIRMED 보류', d, 'HOLD', need=('RULESET_UNCONFIRMED',))
d = base()
d['registry']['sessions'][1]['ruleset']['confirmed'] = 'b' * 32
case('F2 확인 지문 ≠ 기대 → 이상', d, 'ANOMALY', need=('RULESET_MISMATCH',))
d = copy.deepcopy(G3)
case('L1 옛 등록표(registry-3 아님) → 합산 보류(통과 아님)', d, 'HOLD', need=('ACCOUNT_SUM_UNAVAILABLE',))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--impl', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    impl = os.path.abspath(a.impl)
    m = load(impl, 'impl')
    os.makedirs(os.path.join(a.out, 'cases'), exist_ok=True)
    lines = [f"impl {os.path.relpath(impl, EV).replace(os.sep, '/')} sha256={hashlib.sha256(open(impl, 'rb').read()).hexdigest()}"]
    fails = 0
    for i, (name, d, want, need, forbid, check) in enumerate(C):
        r = cv.run(m, d)
        cs = cv.allcodes(r)
        bad = []
        if r.get('overall') != want:
            bad.append(f"overall {r.get('overall')}≠{want}")
        bad += [f'코드 없음 {c}' for c in need if c not in cs]
        bad += [f'금지 코드 {c}' for c in forbid if c in cs]
        if check is not None:
            try:
                if not check(r):
                    bad.append('세부 확인 실패')
            except Exception as e:  # noqa: BLE001
                bad.append(f'세부 확인 예외 {type(e).__name__}')
        fails += bool(bad)
        with open(os.path.join(a.out, 'cases', f'{i:02d}.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'name': name, 'expect': {'overall': want, 'need': need, 'forbid': forbid}, 'registry': d['registry'], 'result': r},
                      f, ensure_ascii=False, indent=1, default=str)
        lines.append(f"{'PASS' if not bad else 'FAIL'} | {name} | {r.get('overall')} | {','.join(cs)}" + (f" | {'; '.join(bad)}" if bad else ''))
    lines.append(f'합계 {len(C)} 통과 {len(C) - fails} 실패 {fails}')
    open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
