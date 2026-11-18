"""월 성적표 v2 합성 사례 — 기대값은 손으로 계산해 적었다(도구 출력에서 거꾸로 만들지 않음). 금액은 가상값.
v1 S01~S13 은 기대 금액을 바꾸지 않고 입력 형식(scorecard-input-2)·칸 이름만 옮겼다. R1~R4 는 총괄 v1.190 §3 반례, 그 뒤는 주변 경계.
사용: python -I cases_scorecard.py --out <폴더>   종료값: 전부 통과 0, 하나라도 실패 1.
"""
import argparse, copy, importlib.util, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('scorecard', os.path.join(HERE, 'scorecard.py'))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)

PERIOD = {'from': '2026-12-01T00:00:00+09:00', 'to': '2027-01-01T00:00:00+09:00'}
PROV = {'code_sha': '0e1d2a02', 'code_sha_source': '외부 등록표(가상)', 'ruleset_hash': 'aaaaaaaaaaaa', 'registry_ref': 'reg-가상-1'}
REGIME = [{'at': '2026-12-02T09:00:00+09:00', 'regime': 'TREND_UP', 'source': 'position.market_regime', 'rule_version': '제품 정의'}]


def rt(buy_at='2026-12-02T09:00:00+09:00', sell_at='2026-12-05T09:00:00+09:00', bf='50000', bfee='25', sf='52000', sfee='26',
       qty='0.001', stored='1949'):
    """매수 50,000+25 → 매도 52,000−26: 실현 = 51,974 − 50,025 = 1,949(수수료 양쪽 반영)."""
    return {'buy': {'settled_at': buy_at, 'qty': qty, 'funds': bf, 'fee': bfee},
            'sells': [{'settled_at': sell_at, 'qty': qty, 'funds': sf, 'fee': sfee, 'stored_realized_pnl': stored}],
            'position_fee_stored': str(int(bfee) + int(sfee))}


def snap(krw, positions=None, pending=None):
    return {'available_krw': krw, 'positions': positions or [], 'pending_buys': pending or []}


def sess(i, start='100000', end='101949', trades=None, kind='DYNAMIC', mode='REAL', status='RUNNING', **kw):
    x = {'kind': kind, 'id': i, 'mode': mode, 'strategy_type': 'COMPOSITE_BREAKOUT', 'timeframe': 'H1', 'status_end': status,
         'provenance': dict(PROV), 'start': snap(start), 'end': snap(end), 'flows': [],
         'trades': [rt()] if trades is None else trades, 'market_regime': list(REGIME)}
    x.update(kw)
    return x


def inp(sessions, **kw):
    d = {'version': 'scorecard-input-2', 'period': dict(PERIOD), 'sessions': sessions,
         'benchmarks': {'btc_price_start': None, 'btc_price_end': None}}
    d.update(kw)
    return d


def card(r, k):
    return next(c for c in r['sessions'] if c['session'] == k)


def group(r, gt, name):
    return next((a for a in r['aggregates'] if a['group'] == gt and a['name'] == name), None)


def daily(*pairs):
    return [{'at': f'2026-12-{d:02d}T00:00:00+09:00', 'equity': e} for d, e in pairs]


CASES = []      # (이름, 이유, 입력, 검사) — 검사는 결과를 받아 참/거짓 목록
BADCASES = []   # (이름, 이유, 입력, 오류 메시지에 들어 있어야 할 말) — build 가 Bad 로 거절해야 함


def case(name, why, d, check):
    CASES.append((name, why, d, check))


def bad(name, why, d, needle):
    BADCASES.append((name, why, d, needle))


# ── v1 S01~S13 (기대 금액 그대로) ───────────────────────────────
def c1(r):
    c = card(r, 'DYNAMIC#1')
    return [c['month_pnl'] == '1949', c['realized_in_period'] == '1949', c['fees_paid'] == '51',
            c['month_pnl'] != '1898', c['unrealized_change'] == '0', c['unrealized_start'] == '0', c['unrealized_end'] == '0',
            c['identity_check'] == '일치', c['stored_realized_mismatch'] == [], group(r, 'money', '실자금')['month_pnl'] == '1949']
case('S01_fee_not_double_counted', '왕복 수수료 51원은 실현손익 1,949에 이미 반영 — 1,898(다시 차감)이 아님', inp([sess(1)]), c1)


def c2(r):
    c = card(r, 'DYNAMIC#20')
    a = group(r, 'money', '실자금')
    return [c['month_pnl'] is None, c['month_pnl_status'] == '미확정', any('CLOSING' in w for w in c['unconfirmed_reasons']),
            a['month_pnl'] is None, a['unconfirmed_sessions'] == ['DYNAMIC#20'], a['known_part_sum'] == '0']
x = sess(20, end='50000', trades=[{'buy': rt()['buy'], 'sells': []}])
x['end']['positions'] = [{'id': 11, 'coin': 'KRW-ETH', 'size': '0.001', 'status': 'CLOSING', 'price': '52000000', 'cost_krw': '50025'}]
case('S02_closing_unconfirmed', '기말 CLOSING 포지션 → 그 세션·실자금 손익 미확정(0원·가격 평가로 채우지 않음)', inp([x]), c2)


def c3(r):
    a = group(r, 'money', '실자금')
    inc = r['included']
    return [a['month_pnl'] == '-2500', inc == {'sessions': 3, 'real': 3, 'paper': 0, 'zero_trade': 1, 'loss': 1, 'stopped': 1, 'unconfirmed': 0},
            card(r, 'DYNAMIC#3')['trade_state'] == '거래0', card(r, 'DYNAMIC#3')['month_pnl'] == '0']
loss = sess(2, end='97000', trades=[rt(sf='47025', sfee='0', stored='-3000')])
zero = sess(3, end='100000', trades=[])
stop = sess(4, start='20000', end='20500', status='STOPPED', trades=[rt(bf='10000', bfee='0', sf='10500', sfee='0', stored='500')])
case('S03_loss_zero_stopped_included', '손실 −3,000·거래0·중단 +500 을 모두 포함 → 합계 −2,500', inp([loss, zero, stop]), c3)

dep = sess(5, end='149000', trades=[], flows=[{'at': '2026-12-10T00:00:00+09:00', 'amount_krw': '50000', 'kind': 'ALLOCATE'}])
case('S04_deposit_excluded', '기초 100,000 + 배정 50,000 → 기말 149,000 이면 손익 −1,000(입금을 수익으로 세지 않음)', inp([dep]),
     lambda r: [card(r, 'DYNAMIC#5')['month_pnl'] == '-1000', card(r, 'DYNAMIC#5')['net_flows'] == '50000'])

p = sess(6, end='49975', trades=[{'buy': rt()['buy'], 'sells': []}])
p['end']['positions'] = [{'id': 12, 'coin': 'KRW-ETH', 'size': '0.001', 'status': 'OPEN', 'price': None, 'cost_krw': '50025'}]
case('S05_missing_price_unconfirmed', '기말 OPEN 보유의 평가 가격 없음 → 미확정', inp([p]),
     lambda r: [card(r, 'DYNAMIC#6')['month_pnl'] is None, any('가격 없음' in w for w in card(r, 'DYNAMIC#6')['unconfirmed_reasons'])])

q = sess(7, provenance={'ruleset_hashes_seen': ['aaaa', 'bbbb']}, market_regime=None)
case('S06_provenance_unknown_and_split', '코드 SHA·시장 상황 없음 → 미확인, 구간 중 지문 2개 → 분리 필요 표시', inp([q]),
     lambda r: [card(r, 'DYNAMIC#7')['provenance']['code_sha'] == '미확인', card(r, 'DYNAMIC#7')['market_regime'] == '미확인',
                card(r, 'DYNAMIC#7')['provenance']['ruleset_hashes'] == ['aaaa', 'bbbb'],
                any('지문 변경' in f for f in card(r, 'DYNAMIC#7')['flags'])])

dd = sess(8, daily_equity=daily((1, '100000'), (2, '103000'), (3, '99000'), (4, '101000')))
case('S07_drawdown_daily_samples_only', '일별 100,000→103,000→99,000→101,000: 표본 낙폭 −4,000(−3.88%), 표본 없으면 미확인', inp([dd, sess(9)]),
     lambda r: [card(r, 'DYNAMIC#8')['drawdown']['max_amount_krw'] == '-4000', card(r, 'DYNAMIC#8')['drawdown']['max_pct'] == '-3.88',
                '장중 최대 낙폭 아님' in card(r, 'DYNAMIC#8')['drawdown']['label'],
                card(r, 'DYNAMIC#9')['drawdown']['max_amount_krw'] is None, '복원하지 않음' in card(r, 'DYNAMIC#9')['drawdown']['label']])

case('S08_stored_realized_mismatch', '저장값 1,898(수수료 다시 차감된 값)과 주문 원천 1,949 → 불일치 표시', inp([sess(10, trades=[rt(stored='1898')])]),
     lambda r: [len(card(r, 'DYNAMIC#10')['stored_realized_mismatch']) == 1,
                card(r, 'DYNAMIC#10')['stored_realized_mismatch'][0]['diff'] == '-51',
                any('어느 쪽도 고르지 않음' in f for f in card(r, 'DYNAMIC#10')['flags']), card(r, 'DYNAMIC#10')['realized_in_period'] == '1949'])

case('S09_btc_benchmark', '같은 기초 100,000 을 BTC 100,000,000→110,000,000 보유 → +10,000', inp([sess(11)],
     benchmarks={'btc_price_start': '100000000', 'btc_price_end': '110000000'}),
     lambda r: [card(r, 'DYNAMIC#11')['benchmark']['btc_hold_pnl'] == '10000.0', card(r, 'DYNAMIC#11')['benchmark']['cash_pnl'] == '0'])
case('S09b_btc_benchmark_missing', 'BTC 가격 없음 → 미확인', inp([sess(12)]),
     lambda r: [card(r, 'DYNAMIC#12')['benchmark']['btc_hold_pnl'] == '미확인'])

ACC = {'start': {'krw': '100000', 'coins': {}, 'prices': {}}, 'end': {'krw': '101949', 'coins': {}, 'prices': {}}, 'deposits': []}
case('S10_account_matches_sessions', '계좌 손익 1,949 = 실자금 세션 합 → 차 0', inp([sess(13)], account=ACC),
     lambda r: [r['account']['month_pnl'] == '1949', r['account_minus_real_sessions'] == '0'])
acc2 = copy.deepcopy(ACC)
acc2['end']['krw'] = '101900'
case('S10b_account_gap_shown', '계좌 기말 101,900 → 계좌−실자금 세션 −49 를 그대로 표시(세션 합으로 덮지 않음)', inp([sess(14)], account=acc2),
     lambda r: [r['account_minus_real_sessions'] == '-49'])
acc3 = copy.deepcopy(ACC)
acc3['end']['coins'] = {'ETH': '0.001'}
case('S10c_account_coin_without_price', '계좌 기말 코인 가격 없음 → 계좌 손익 미확정', inp([sess(15)], account=acc3),
     lambda r: [r['account']['month_pnl'] is None, any('가격 없음' in w for w in r['account']['reasons'])])

x11 = sess(16, end='100500', trades=[], started_in_period=True, created_at='2026-12-03T00:00:00+09:00',
           flows=[{'at': '2026-12-03T00:00:00+09:00', 'amount_krw': '100000', 'kind': 'ALLOCATE'}])
x11['start'] = None
case('S11_new_session_in_period', '기간 안 새 배정 100,000 → 기말 100,500: 손익 500, 수익률 0.50%', inp([x11]),
     lambda r: [card(r, 'DYNAMIC#16')['month_pnl'] == '500', card(r, 'DYNAMIC#16')['month_return_pct'] == '0.50'])
x12 = sess(17, trades=[])
x12['start'] = None
case('S11b_missing_start_unconfirmed', '기간 전부터 운용인데 기초 없음 → 미확정', inp([x12]),
     lambda r: [card(r, 'DYNAMIC#17')['month_pnl'] is None, any('기초 평가 없음' in w for w in card(r, 'DYNAMIC#17')['unconfirmed_reasons'])])

case('S12_sell_outside_period', '11월 매수·11월 매도 왕복은 12월 실현·수수료에 넣지 않음', inp([sess(18, end='100000', trades=[
    rt(buy_at='2026-11-02T09:00:00+09:00', sell_at='2026-11-05T09:00:00+09:00')])]),
     lambda r: [card(r, 'DYNAMIC#18')['realized_in_period'] == '0', card(r, 'DYNAMIC#18')['fees_paid'] == '0',
                card(r, 'DYNAMIC#18')['round_trips_closed'] == 0])

case('S13_real_stored_excludes_buy_fee', 'REAL 저장값 1,974(매수 수수료 25 미반영) vs 주문 원천 1,949 → 차 +25 불일치 표시, 월손익은 잔액 기준 1,949',
     inp([sess(21, trades=[rt(stored='1974')])]),
     lambda r: [card(r, 'DYNAMIC#21')['stored_realized_mismatch'][0]['diff'] == '25', card(r, 'DYNAMIC#21')['month_pnl'] == '1949'])


# ── R1 이월 미실현(총괄 S1) ─────────────────────────────────────
def r1_input(cost=None):
    pos = {'id': 1, 'coin': 'KRW-RND', 'status': 'OPEN', 'size': '1', 'price': '110'}
    if cost is not None:
        pos['cost_krw'] = cost
    x = sess(901, end='120', trades=[rt(buy_at='2026-11-25T09:00:00+09:00', bf='100', bfee='0', sf='120', sfee='0', qty='1', stored='20')])
    x['start'] = snap('0', [pos])
    return x


def cr1(r):
    c = card(r, 'DYNAMIC#901')
    return [c['month_pnl'] == '10', c['realized_in_period'] == '20', c['unrealized_end'] == '0', c['unrealized_start'] is None,
            c['unrealized_change'] == '-10', 'unrealized_derived' not in c, c['identity_check'] == '미확인',
            c['round_trips'] == [{'trade': 0, 'lifetime_pnl': '20', 'this_month_part': '20', 'carry_in': True}],
            any('이월 왕복' in f for f in c['flags'])]
case('R1_carry_in_unrealized', '[총괄 S1] 11월 원가100·12월 기초 평가110·12월 매도120: 월손익10, 실현20, 기말 미실현0, 증감−10, 기초 원가 없음→기초 미실현 미확인',
     inp([r1_input()]), cr1)
case('R1b_carry_in_with_cost', '같은 입력에 기초 보유 원가100 → 기초 미실현10, 20+(0−10)=10 항등식 일치', inp([r1_input('100')]),
     lambda r: [card(r, 'DYNAMIC#901')['unrealized_start'] == '10', card(r, 'DYNAMIC#901')['identity_check'] == '일치'])

# 여러 달에 걸친 매도: 11-10 매수2개 200, 11-20 1개 120 매도(+20), 12-05 1개 130 매도(+30). 12월 기초 KRW120+보유1×115(원가100), 기말 KRW250.
multi = sess(902, trades=[{'buy': {'settled_at': '2026-11-10T09:00:00+09:00', 'qty': '2', 'funds': '200', 'fee': '0'},
                           'sells': [{'settled_at': '2026-11-20T09:00:00+09:00', 'qty': '1', 'funds': '120', 'fee': '0'},
                                     {'settled_at': '2026-12-05T09:00:00+09:00', 'qty': '1', 'funds': '130', 'fee': '0'}]}],
             start=None, end=None)
multi['start'] = snap('120', [{'id': 2, 'coin': 'KRW-RND', 'status': 'OPEN', 'size': '1', 'price': '115', 'cost_krw': '100'}])
multi['end'] = snap('250')
case('R1c_sells_across_months', '11월 일부 매도(+20)·12월 나머지 매도(+30): 12월 실현30, 월손익15, 증감−15=0−15, 왕복 생애50·이 달 몫30 분리',
     inp([multi]), lambda r: [card(r, 'DYNAMIC#902')['month_pnl'] == '15', card(r, 'DYNAMIC#902')['realized_in_period'] == '30',
                              card(r, 'DYNAMIC#902')['unrealized_change'] == '-15', card(r, 'DYNAMIC#902')['identity_check'] == '일치',
                              card(r, 'DYNAMIC#902')['round_trips'][0] == {'trade': 0, 'lifetime_pnl': '50', 'this_month_part': '30', 'carry_in': True},
                              card(r, 'DYNAMIC#902')['wins'] == 1])
nos = sess(903, trades=[rt(sell_at=None)])
case('R1d_missing_settle_time', '매도 정산 시각 없음 → 이 달 실현 미확정(생성 시각으로 대신하지 않음), 잔액 기준 월손익은 확정', inp([nos]),
     lambda r: [card(r, 'DYNAMIC#903')['realized_in_period'] is None, card(r, 'DYNAMIC#903')['month_pnl'] == '1949',
                card(r, 'DYNAMIC#903')['unrealized_change'] is None,
                any('정산 시각 없음' in w for w in card(r, 'DYNAMIC#903')['unconfirmed_reasons'])])
pend = sess(904, end='50000', trades=[])
pend['end'] = snap('50000', pending=[{'order_id': 7, 'reserved_krw': '50000', 'filled_qty': '0'}])
case('R1e_pending_buy_reserved', '매수 예약만 있고 포지션 없음: 가용 50,000 + 예약 50,000 = 기말 100,000 → 손익 0(예약을 손실로 세지 않음)', inp([pend]),
     lambda r: [card(r, 'DYNAMIC#904')['end_equity'] == '100000', card(r, 'DYNAMIC#904')['month_pnl'] == '0'])
pend2 = copy.deepcopy(pend)
pend2['id'] = 905
pend2['end']['pending_buys'][0]['reserved_krw'] = None
pend3 = copy.deepcopy(pend)
pend3['id'] = 906
pend3['end']['pending_buys'][0]['filled_qty'] = '0.5'
case('R1f_pending_buy_unknown', '예약 금액 없음·일부 체결 예약 → 기말 평가 미확정', inp([pend2, pend3]),
     lambda r: [card(r, 'DYNAMIC#905')['month_pnl'] is None, card(r, 'DYNAMIC#906')['month_pnl'] is None,
                any('예약 금액 없음' in w for w in card(r, 'DYNAMIC#905')['unconfirmed_reasons']),
                any('일부 체결' in w for w in card(r, 'DYNAMIC#906')['unconfirmed_reasons'])])
nopb = sess(907)
del nopb['end']['pending_buys']
bad('R1g_pending_buys_required', '스냅숏에 pending_buys 목록이 없으면 예약 0 으로 채우지 않고 계약 위반', inp([nopb]), 'pending_buys')
v1at = sess(908)
v1at['trades'][0]['buy']['at'] = v1at['trades'][0]['buy'].pop('settled_at')
bad('R1h_v1_at_rejected', 'v1 이름 at(주문 생성 시각일 수 있음) → 계약 위반', inp([v1at]), 'settled_at')


# ── R2 월 밖 입출금(총괄 S2) ────────────────────────────────────
bad('R2_out_of_period_flow', '[총괄 S2] 12월 기초/기말100, 1월 배정50 → 12월 손익 −50 확정이 아니라 계약 위반 거절',
    inp([sess(910, start='100', end='100', trades=[], flows=[{'at': '2027-01-02T00:00:00+09:00', 'amount_krw': '50', 'kind': 'ALLOCATE'}])]), '기간')
case('R2b_flows_missing_unconfirmed', '입출금 이력 자체가 없음(None) → 0 으로 채우지 않고 미확정', inp([sess(911, trades=[], flows=None)]),
     lambda r: [card(r, 'DYNAMIC#911')['month_pnl'] is None, any('이력 없음' in w for w in card(r, 'DYNAMIC#911')['unconfirmed_reasons'])])
early = sess(912, end='100', trades=[], started_in_period=True, created_at='2026-12-10T00:00:00+09:00',
             flows=[{'at': '2026-12-05T00:00:00+09:00', 'amount_krw': '100', 'kind': 'ALLOCATE'}])
early['start'] = None
bad('R2c_flow_before_created', '세션 생성(12-10) 전 배정(12-05) → 계약 위반', inp([early]), '생성')
bad('R2d_flow_sign', '배정 금액 음수 → 계약 위반(방향은 kind 로)', inp([sess(913, trades=[], flows=[
    {'at': '2026-12-05T00:00:00+09:00', 'amount_krw': '-50', 'kind': 'ALLOCATE'}])]), '양수')
case('R2e_recover_in_period', '기초100·12월 회수50·기말50 → 손익0(회수를 손실로 세지 않음)', inp([sess(914, start='100', end='50', trades=[], flows=[
    {'at': '2026-12-05T00:00:00+09:00', 'amount_krw': '50', 'kind': 'RECOVER'}])]),
     lambda r: [card(r, 'DYNAMIC#914')['month_pnl'] == '0', card(r, 'DYNAMIC#914')['net_flows'] == '-50'])
late = sess(915, end='100', trades=[], created_at='2026-12-10T00:00:00+09:00')
bad('R2f_created_in_period_not_flagged', '생성 시각이 기간 안인데 started_in_period 아님 → 기초 평가와 모순, 계약 위반', inp([late]), 'started_in_period')
acc_out = copy.deepcopy(ACC)
acc_out['deposits'] = [{'at': '2027-01-03T00:00:00+09:00', 'amount_krw': '10'}]
bad('R2g_account_deposit_out_of_period', '계좌 입출금도 월 밖이면 계약 위반', inp([sess(916)], account=acc_out), '기간 밖')
acc_old = copy.deepcopy(ACC)
del acc_old['deposits']
acc_old['net_deposits_krw'] = '0'
bad('R2h_account_net_without_time', '시각 없는 계좌 순입금 합계(v1)는 월 밖 검사가 불가 → 계약 위반', inp([sess(917)], account=acc_old), 'deposits')


# ── R3 실자금/모의 계좌 혼합(총괄 S3) ───────────────────────────
def cr3(r):
    return [r['account_minus_real_sessions'] == '0', group(r, 'money', '실자금')['month_pnl'] == '10',
            group(r, 'money', '모의')['month_pnl'] == '10', not any(a['group'] == 'all' for a in r['aggregates']),
            group(r, 'kind', '실자금|LIVE')['sessions'] == ['LIVE#903'], group(r, 'kind', '모의|PAPER')['sessions'] == ['PAPER#904']]
acc10 = {'start': {'krw': '100', 'coins': {}, 'prices': {}}, 'end': {'krw': '110', 'coins': {}, 'prices': {}}, 'deposits': []}
case('R3_paper_real_account_mix', '[총괄 S3] 실계좌+10·LIVE+10·PAPER+10 → 계좌−실자금 0(모의를 빼지 않음), 혼합 전체 합 없음',
     inp([sess(903, kind='LIVE', start='100', end='110', trades=[]), sess(904, kind='PAPER', mode='PAPER', start='100', end='110', trades=[])],
         account=acc10), cr3)
case('R3b_dynamic_real_and_paper_split', 'DYNAMIC REAL+10·DYNAMIC PAPER+10 → 같은 종류라도 돈 종류별 묶음, 계좌는 REAL만',
     inp([sess(920, start='100', end='110', trades=[]), sess(921, mode='PAPER', start='100', end='110', trades=[])], account=acc10),
     lambda r: [r['account_minus_real_sessions'] == '0', group(r, 'kind', '실자금|DYNAMIC')['sessions'] == ['DYNAMIC#920'],
                group(r, 'kind', '모의|DYNAMIC')['sessions'] == ['DYNAMIC#921'], card(r, 'DYNAMIC#921')['money'] == '모의'])
case('R3c_groups_by_version', '코드 버전·전략/주기별 묶음도 돈 종류 안에서만', inp([sess(922, trades=[]), sess(923, trades=[],
     provenance=dict(PROV, code_sha='aff357e2'))]),
     lambda r: [group(r, 'code_version', '실자금|0e1d2a02')['sessions'] == ['DYNAMIC#922'],
                group(r, 'code_version', '실자금|aff357e2')['sessions'] == ['DYNAMIC#923'],
                group(r, 'strategy_timeframe', '실자금|COMPOSITE_BREAKOUT/H1')['sessions'] == ['DYNAMIC#922', 'DYNAMIC#923']])
nomode = sess(924)
del nomode['mode']
bad('R3d_mode_required', '모드 없음 → 실자금/모의를 모르므로 계약 위반', inp([nomode]), 'mode')
bad('R3e_live_paper_contradiction', 'LIVE 인데 PAPER 모드 → 계약 위반', inp([sess(925, kind='LIVE', mode='PAPER')]), 'LIVE')
case('R3f_no_real_sessions', '모의 세션만 있고 계좌가 있으면 계좌−실자금은 미확인(모의로 메우지 않음)',
     inp([sess(926, kind='PAPER', mode='PAPER', start='100', end='110', trades=[])], account=acc10),
     lambda r: [r['account_minus_real_sessions'].startswith('미확인'), group(r, 'money', '실자금') is None])


# ── R4 낙폭 비율(총괄 S4) ──────────────────────────────────────
case('R4_drawdown_percentage', '[총괄 S4] 일별100→50→1,000→600: 최대 금액 낙폭−400, 최대 비율 낙폭−50%(따로 계산)',
     inp([sess(930, trades=[], daily_equity=daily((1, '100'), (2, '50'), (3, '1000'), (4, '600')))]),
     lambda r: [card(r, 'DYNAMIC#930')['drawdown']['max_amount_krw'] == '-400', card(r, 'DYNAMIC#930')['drawdown']['max_pct'] == '-50.00',
                card(r, 'DYNAMIC#930')['drawdown']['max_amount_window'][0].startswith('2026-12-03'),
                card(r, 'DYNAMIC#930')['drawdown']['max_pct_window'][0].startswith('2026-12-01')])
case('R4b_withdrawal_not_loss', '1,000→(12-02 회수500)→500→500: 금액·비율 낙폭 0(단순 출금은 손실 아님)',
     inp([sess(931, start='1000', end='500', trades=[], flows=[{'at': '2026-12-01T12:00:00+09:00', 'amount_krw': '500', 'kind': 'RECOVER'}],
               daily_equity=daily((1, '1000'), (2, '500'), (3, '500')))]),
     lambda r: [card(r, 'DYNAMIC#931')['drawdown']['max_amount_krw'] == '0', card(r, 'DYNAMIC#931')['drawdown']['max_pct'] == '0.00',
                card(r, 'DYNAMIC#931')['month_pnl'] == '0'])
case('R4c_allocation_not_gain', '100→(배정900)→1,000→600: 배정을 수익으로 세지 않음, 금액−400·비율−40%',
     inp([sess(932, start='100', end='600', trades=[], flows=[{'at': '2026-12-01T12:00:00+09:00', 'amount_krw': '900', 'kind': 'ALLOCATE'}],
               daily_equity=daily((1, '100'), (2, '1000'), (3, '600')))]),
     lambda r: [card(r, 'DYNAMIC#932')['drawdown']['max_amount_krw'] == '-400', card(r, 'DYNAMIC#932')['drawdown']['max_pct'] == '-40.00'])
bad('R4d_reverse_order', '표본 시각 역순 → 계약 위반', inp([sess(933, daily_equity=daily((2, '100'), (1, '90')))]), '역순')
bad('R4e_duplicate_sample', '같은 시각 표본 중복 → 계약 위반', inp([sess(934, daily_equity=daily((1, '100'), (1, '90')))]), '중복')
case('R4f_missing_days_shown', '12-01→12-04 사이 표본 없음 → 결측 구간1 표시, 채우지 않음',
     inp([sess(935, daily_equity=daily((1, '100'), (4, '90'), (5, '95')))]),
     lambda r: [len(card(r, 'DYNAMIC#935')['drawdown']['missing_intervals']) == 1, '결측 구간 1' in card(r, 'DYNAMIC#935')['drawdown']['label'],
                card(r, 'DYNAMIC#935')['drawdown']['samples'] == 3])
case('R4g_flows_unknown', '일별 표본은 있으나 입출금 이력 없음 → 낙폭 미확정(출금과 손실 구분 불가)',
     inp([sess(936, flows=None, daily_equity=daily((1, '100'), (2, '50')))]),
     lambda r: [card(r, 'DYNAMIC#936')['drawdown']['max_amount_krw'] is None, '미확정' in card(r, 'DYNAMIC#936')['drawdown']['label']])
olddate = sess(937, daily_equity=[{'date': '2026-12-01', 'equity': '1'}, {'date': '2026-12-02', 'equity': '1'}])
bad('R4h_v1_date_rejected', 'v1 이름 date(시각·시간대 없음) → 계약 위반', inp([olddate]), 'date')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    lines, fails = [], 0
    for name, why, d, check in CASES:
        try:
            r = sc.build(copy.deepcopy(d))
            res = check(r)
            ok = all(res)
            detail = '' if ok else f'검사 {[i for i, v in enumerate(res) if not v]} 실패'
        except Exception as e:  # noqa: BLE001
            r, ok, detail = None, False, f'{type(e).__name__}: {e}'
        fails += not ok
        lines.append(f"{'PASS' if ok else 'FAIL'} | {name} | {why}" + (f' | {detail}' if detail else ''))
        with open(os.path.join(a.out, name + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'why': why, 'input': d, 'result': r}, f, ensure_ascii=False, indent=1)
    for name, why, d, needle in BADCASES:
        try:
            sc.build(copy.deepcopy(d))
            ok, msg = False, '거절 안 됨'
        except sc.Bad as e:
            msg = str(e)
            ok = needle in msg
        except Exception as e:  # noqa: BLE001
            ok, msg = False, f'{type(e).__name__}: {e}'
        fails += not ok
        lines.append(f"{'PASS' if ok else 'FAIL'} | {name} | {why} | 거절: {msg}")
        with open(os.path.join(a.out, name + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'why': why, 'input': d, 'rejected': msg}, f, ensure_ascii=False, indent=1)
    # 입력 계약(CLI): 실수형·세션 중복·모르는 포지션 상태·월 밖 입출금은 산출 없이 rc2
    for name, mut in (('C01_float_rejected', lambda d: d['sessions'][0]['end'].__setitem__('available_krw', 101949.0)),
                      ('C02_duplicate_session', lambda d: d['sessions'].append(copy.deepcopy(d['sessions'][0]))),
                      ('C03_unknown_position_status', lambda d: d['sessions'][0]['end'].__setitem__(
                          'positions', [{'id': 1, 'coin': 'KRW-ETH', 'size': '1', 'status': 'OPENED', 'price': '1'}])),
                      ('C04_out_of_period_flow_cli', lambda d: d['sessions'][0].__setitem__(
                          'flows', [{'at': '2026-11-30T23:59:59+09:00', 'amount_krw': '1', 'kind': 'ALLOCATE'}]))):
        d = inp([sess(99)])
        mut(d)
        src = os.path.join(a.out, name + '.input.json')
        with open(src, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(d, f, ensure_ascii=False, indent=1)
        p = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'scorecard.py'), '--input', src, '--out', os.path.join(a.out, name)],
                           capture_output=True)
        ok = p.returncode == 2 and not os.path.exists(os.path.join(a.out, name, 'scorecard.json'))
        fails += not ok
        lines.append(f"{'PASS' if ok else 'FAIL'} | {name} | rc={p.returncode} {p.stdout.decode('utf-8', 'replace').strip()}")
    # 표시 예시: 실자금·모의·이월·낙폭을 한 성적표로
    demo = inp([sess(1), loss, zero, stop, dep, x, dd, r1_input('100'), multi, pend,
                sess(904, kind='PAPER', mode='PAPER', start='100', end='110', trades=[])],
               account=None, benchmarks={'btc_price_start': '100000000', 'btc_price_end': '110000000'})
    src = os.path.join(a.out, 'demo.input.json')
    with open(src, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(demo, f, ensure_ascii=False, indent=1)
    p = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'scorecard.py'), '--input', src, '--out', os.path.join(a.out, 'demo')],
                       capture_output=True)
    ok = p.returncode == 0
    fails += not ok
    lines.append(f"{'PASS' if ok else 'FAIL'} | D01_demo_cli | rc={p.returncode} {p.stdout.decode('utf-8', 'replace').strip()}")
    lines.append(f'합계 {len(lines)} 통과 {len(lines) - fails} 실패 {fails}')
    with open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
