"""월 성적표 합성 사례 — 기대값은 손으로 계산해 적었다(도구 출력에서 거꾸로 만들지 않음). 금액은 가상값.
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
    return {'buy': {'at': buy_at, 'qty': qty, 'funds': bf, 'fee': bfee},
            'sells': [{'at': sell_at, 'qty': qty, 'funds': sf, 'fee': sfee, 'stored_realized_pnl': stored}],
            'position_fee_stored': str(int(bfee) + int(sfee))}


def sess(i, start='100000', end='101949', trades=None, kind='DYNAMIC', status='RUNNING', **kw):
    x = {'kind': kind, 'id': i, 'mode': 'REAL', 'strategy_type': 'COMPOSITE_BREAKOUT', 'timeframe': 'H1', 'status_end': status,
         'provenance': dict(PROV), 'start': {'available_krw': start, 'positions': []},
         'end': {'available_krw': end, 'positions': []}, 'flows': [],
         'trades': [rt()] if trades is None else trades, 'market_regime': list(REGIME)}
    x.update(kw)
    return x


def inp(sessions, **kw):
    d = {'version': 'scorecard-input-1', 'period': dict(PERIOD), 'sessions': sessions,
         'benchmarks': {'btc_price_start': None, 'btc_price_end': None}}
    d.update(kw)
    return d


def card(r, k):
    return next(c for c in r['sessions'] if c['session'] == k)


def group(r, gt, name):
    return next(a for a in r['aggregates'] if a['group'] == gt and a['name'] == name)


CASES = []


def case(name, why, d, check):
    CASES.append((name, why, d, check))


# S1 수수료 이중 차감 금지
def c1(r):
    c = card(r, 'DYNAMIC#1')
    return [c['month_pnl'] == '1949', c['realized_from_orders'] == '1949', c['fees_paid'] == '51',
            c['month_pnl'] != '1898', c['unrealized_derived'] == '0', c['stored_realized_mismatch'] == [],
            group(r, 'all', '전체')['month_pnl'] == '1949']
case('S01_fee_not_double_counted', '왕복 수수료 51원은 실현손익 1,949에 이미 반영 — 1,898(다시 차감)이 아님', inp([sess(1)]), c1)


# S2 CLOSING 은 0원이 아니라 미확정
def c2(r):
    c = card(r, 'DYNAMIC#20')
    a = group(r, 'all', '전체')
    return [c['month_pnl'] is None, c['month_pnl_status'] == '미확정', any('CLOSING' in w for w in c['unconfirmed_reasons']),
            a['month_pnl'] is None, a['unconfirmed_sessions'] == ['DYNAMIC#20'], a['known_part_sum'] == '0']
x = sess(20, end='50000', trades=[{'buy': rt()['buy'], 'sells': []}])
x['end']['positions'] = [{'id': 11, 'coin': 'KRW-ETH', 'size': '0.001', 'status': 'CLOSING', 'price': '52000000'}]
case('S02_closing_unconfirmed', '기말 CLOSING 포지션 → 그 세션·전체 손익 미확정(0원·가격 평가로 채우지 않음)', inp([x]), c2)


# S3 손실·거래0·중단 포함
def c3(r):
    a = group(r, 'all', '전체')
    inc = r['included']
    return [a['month_pnl'] == '-2500', inc == {'sessions': 3, 'zero_trade': 1, 'loss': 1, 'stopped': 1, 'unconfirmed': 0},
            card(r, 'DYNAMIC#3')['trade_state'] == '거래0', card(r, 'DYNAMIC#3')['month_pnl'] == '0']
loss = sess(2, end='97000', trades=[rt(sf='47025', sfee='0', stored='-3000')])
zero = sess(3, end='100000', trades=[])
stop = sess(4, start='20000', end='20500', status='STOPPED', trades=[rt(bf='10000', bfee='0', sf='10500', sfee='0', stored='500')])
case('S03_loss_zero_stopped_included', '손실 −3,000·거래0·중단 +500 을 모두 포함 → 합계 −2,500', inp([loss, zero, stop]), c3)


# S4 순입금은 손익이 아님
def c4(r):
    c = card(r, 'DYNAMIC#5')
    return [c['month_pnl'] == '-1000', c['net_flows'] == '50000']
dep = sess(5, end='149000', trades=[], flows=[{'at': '2026-12-10T00:00:00+09:00', 'amount_krw': '50000', 'kind': 'ALLOCATE'}])
case('S04_deposit_excluded', '기초 100,000 + 배정 50,000 → 기말 149,000 이면 손익 −1,000(입금을 수익으로 세지 않음)', inp([dep]), c4)


# S5 가격 없는 보유
def c5(r):
    c = card(r, 'DYNAMIC#6')
    return [c['month_pnl'] is None, any('가격 없음' in w for w in c['unconfirmed_reasons'])]
p = sess(6, end='49975', trades=[{'buy': rt()['buy'], 'sells': []}])
p['end']['positions'] = [{'id': 12, 'coin': 'KRW-ETH', 'size': '0.001', 'status': 'OPEN', 'price': None}]
case('S05_missing_price_unconfirmed', '기말 OPEN 보유의 평가 가격 없음 → 미확정', inp([p]), c5)


# S6 출처 미확인·지문 변경
def c6(r):
    c = card(r, 'DYNAMIC#7')
    return [c['provenance']['code_sha'] == '미확인', c['market_regime'] == '미확인', c['provenance']['ruleset_hashes'] == ['aaaa', 'bbbb'],
            any('지문 변경' in f for f in c['flags'])]
q = sess(7, provenance={'ruleset_hashes_seen': ['aaaa', 'bbbb']}, market_regime=None)
case('S06_provenance_unknown_and_split', '코드 SHA·시장 상황 없음 → 미확인, 구간 중 지문 2개 → 분리 필요 표시', inp([q]), c6)


# S7 낙폭: 일별 표본만
def c7(r):
    a, b = card(r, 'DYNAMIC#8')['drawdown'], card(r, 'DYNAMIC#9')['drawdown']
    return [a['value_krw'] == '-4000', a['pct'] == '-3.88', '장중 최대 낙폭 아님' in a['label'], b['value_krw'] is None, '복원하지 않음' in b['label']]
dd = sess(8, daily_equity=[{'date': '2026-12-01', 'equity': '100000'}, {'date': '2026-12-02', 'equity': '103000'},
                           {'date': '2026-12-03', 'equity': '99000'}, {'date': '2026-12-04', 'equity': '101000'}])
case('S07_drawdown_daily_samples_only', '일별 100,000→103,000→99,000→101,000: 표본 낙폭 −4,000(−3.88%), 표본 없으면 미확인', inp([dd, sess(9)]), c7)


# S8 저장 실현손익과 주문 원천 불일치
def c8(r):
    c = card(r, 'DYNAMIC#10')
    m = c['stored_realized_mismatch']
    return [len(m) == 1, m[0]['stored'] == '1898', m[0]['diff'] == '-51', any('어느 쪽도 고르지 않음' in f for f in c['flags']),
            c['realized_from_orders'] == '1949']
case('S08_stored_realized_mismatch', '저장값 1,898(수수료 다시 차감된 값)과 주문 원천 1,949 → 불일치 표시', inp([sess(10, trades=[rt(stored='1898')])]), c8)


# S9 BTC 보유 비교
def c9(r):
    return [card(r, 'DYNAMIC#11')['benchmark']['btc_hold_pnl'] == '10000.0', card(r, 'DYNAMIC#11')['benchmark']['cash_pnl'] == '0']
case('S09_btc_benchmark', '같은 기초 100,000 을 BTC 100,000,000→110,000,000 보유 → +10,000', inp([sess(11)],
     benchmarks={'btc_price_start': '100000000', 'btc_price_end': '110000000'}), c9)
case('S09b_btc_benchmark_missing', 'BTC 가격 없음 → 미확인', inp([sess(12)]),
     lambda r: [card(r, 'DYNAMIC#12')['benchmark']['btc_hold_pnl'] == '미확인'])


# S10 계좌 대조
ACC = {'start': {'krw': '100000', 'coins': {}, 'prices': {}}, 'end': {'krw': '101949', 'coins': {}, 'prices': {}}, 'net_deposits_krw': '0'}
case('S10_account_matches_sessions', '계좌 손익 1,949 = 세션 합 → 차 0', inp([sess(13)], account=ACC),
     lambda r: [r['account']['month_pnl'] == '1949', r['account_minus_sessions'] == '0'])
acc2 = copy.deepcopy(ACC)
acc2['end']['krw'] = '101900'
case('S10b_account_gap_shown', '계좌 기말 101,900 → 계좌−세션 −49 를 그대로 표시(세션 합으로 덮지 않음)', inp([sess(14)], account=acc2),
     lambda r: [r['account_minus_sessions'] == '-49'])
acc3 = copy.deepcopy(ACC)
acc3['end']['coins'] = {'ETH': '0.001'}
case('S10c_account_coin_without_price', '계좌 기말 코인 가격 없음 → 계좌 손익 미확정', inp([sess(15)], account=acc3),
     lambda r: [r['account']['month_pnl'] is None, any('가격 없음' in w for w in r['account']['reasons'])])


# S11 기간 안 새 세션
x11 = sess(16, start=None, end='100500', trades=[], started_in_period=True,
           flows=[{'at': '2026-12-03T00:00:00+09:00', 'amount_krw': '100000', 'kind': 'ALLOCATE'}])
x11['start'] = None
case('S11_new_session_in_period', '기간 안 새 배정 100,000 → 기말 100,500: 손익 500, 수익률 0.50%', inp([x11]),
     lambda r: [card(r, 'DYNAMIC#16')['month_pnl'] == '500', card(r, 'DYNAMIC#16')['month_return_pct'] == '0.50'])
x12 = sess(17, trades=[])
x12['start'] = None
case('S11b_missing_start_unconfirmed', '기간 전부터 운용인데 기초 없음 → 미확정', inp([x12]),
     lambda r: [card(r, 'DYNAMIC#17')['month_pnl'] is None])


# S12 기간 밖 매도는 이번 달 실현이 아님
case('S12_sell_outside_period', '11월 매수·11월 매도 왕복은 12월 실현·수수료에 넣지 않음', inp([sess(18, end='100000', trades=[
    rt(buy_at='2026-11-02T09:00:00+09:00', sell_at='2026-11-05T09:00:00+09:00')])]),
     lambda r: [card(r, 'DYNAMIC#18')['realized_from_orders'] == '0', card(r, 'DYNAMIC#18')['fees_paid'] == '0',
                card(r, 'DYNAMIC#18')['round_trips'] == 0])


# S13 REAL 저장 실현손익이 매수 수수료를 빼지 않은 경우(코드 읽기: 실자금 정산 평균가 = 대금/수량) — 주문 원천으로 다시 계산해 차이를 드러낸다
case('S13_real_stored_excludes_buy_fee', 'REAL 저장값 1,974(=51,974−50,000, 매수 수수료 25 미반영) vs 주문 원천 1,949 → 차 +25 불일치 표시, 월손익은 잔액 기준 1,949',
     inp([sess(21, trades=[rt(stored='1974')])]),
     lambda r: [card(r, 'DYNAMIC#21')['stored_realized_mismatch'][0]['diff'] == '25', card(r, 'DYNAMIC#21')['month_pnl'] == '1949'])


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
    # 입력 계약(CLI): 실수형·세션 중복은 산출 없이 rc2
    for name, mut in (('C01_float_rejected', lambda d: d['sessions'][0]['end'].__setitem__('available_krw', 101949.0)),
                      ('C02_duplicate_session', lambda d: d['sessions'].append(copy.deepcopy(d['sessions'][0]))),
                      ('C03_unknown_position_status', lambda d: d['sessions'][0]['end'].__setitem__(
                          'positions', [{'id': 1, 'coin': 'KRW-ETH', 'size': '1', 'status': 'OPENED', 'price': '1'}]))):
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
    # 표시 예시: 여러 사례를 한 성적표로
    demo = inp([sess(1), loss, zero, stop, dep, x, dd], account=None,
               benchmarks={'btc_price_start': '100000000', 'btc_price_end': '110000000'})
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
