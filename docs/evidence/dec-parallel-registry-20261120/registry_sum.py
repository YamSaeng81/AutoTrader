"""병행 등록표 계좌 합산 대조의 좁은 예시(총괄 v1.201 §3-2). 가상 값만 — 운영·DB·거래소 접속 없음.

계약(README §3):
  A1 코인별: 거래소 보유(가용+잠김) == Σ 등록 세션의 열린 포지션 수량(LIVE+DYNAMIC 모두, BTC 포함) + 등록표의 비세션 보유.
  A2 KRW: 거래소 KRW(가용+잠김) == Σ 등록 세션 available_krw + Σ 미체결 매수 reserved_krw + 등록표의 미배정 현금.
  A3 배정(ALLOCATE)/회수(RECOVER)는 계좌 내부 이동이다. 계좌 입출금(DEPOSIT/WITHDRAW)으로 세지 않으며 미배정 현금만 옮긴다.
  A4 등록 밖 세션의 실자금 주문·포지션이 하나라도 있으면 합산을 맞추지 않고 이상(UNREGISTERED_REAL)으로 낸다.
"""
from decimal import Decimal as D


def check(reg, db, ex):
    out = []
    ids = {(s['kind'], s['id']) for s in reg['sessions']}
    for p in db['positions']:
        if (p['kind'], p['sid']) not in ids:
            out.append(f"UNREGISTERED_REAL {p['kind']}#{p['sid']} {p['coin']}")
    if out:
        return out
    unalloc = D(reg['unallocated_krw_base'])
    for f in reg['account_flows']:
        unalloc += D(f['amount']) * (1 if f['type'] == 'DEPOSIT' else -1)
    for e in reg['capital_events']:
        unalloc += D(e['amount']) * (-1 if e['type'] == 'ALLOCATE' else 1)
    krw = sum((D(s['available_krw']) for s in db['sessions']), D(0)) + \
        sum((D(r) for r in db['reserved_krw']), D(0)) + unalloc
    if krw != D(ex['KRW']):
        out.append(f"KRW 불일치 거래소 {ex['KRW']} ≠ 세션+예약+미배정 {krw}")
    coins = {}
    for p in db['positions']:
        coins[p['coin']] = coins.get(p['coin'], D(0)) + D(p['size'])
    for c, q in reg['non_session_holdings'].items():
        coins[c] = coins.get(c, D(0)) + D(q)
    for c in sorted(set(coins) | (set(ex) - {'KRW'})):
        if coins.get(c, D(0)) != D(ex.get(c, '0')):
            out.append(f"{c} 불일치 거래소 {ex.get(c, '0')} ≠ 세션+비세션 {coins.get(c, D(0))}")
    return out or ['OK']


REG = {'sessions': [{'kind': 'LIVE', 'id': 203}, {'kind': 'DYNAMIC', 'id': 93}],
       'unallocated_krw_base': '50000', 'account_flows': [],
       'capital_events': [{'type': 'ALLOCATE', 'amount': '30000', 'sid': 93}],
       'non_session_holdings': {}}
DB = {'sessions': [{'available_krw': '200'}, {'available_krw': '10000'}], 'reserved_krw': ['0'],
      'positions': [{'kind': 'LIVE', 'sid': 203, 'coin': 'BTC', 'size': '0.0002'},
                    {'kind': 'DYNAMIC', 'sid': 93, 'coin': 'BTC', 'size': '0.0001'}]}
EX = {'KRW': '30200', 'BTC': '0.0003'}

CASES = [
    ('P1 정상: LIVE·DYNAMIC 둘 다 BTC 보유', REG, DB, EX, ['OK']),
    ('P2 BTC를 LIVE만으로 대조(DYNAMIC 보유 누락)', REG,
     dict(DB, positions=DB['positions'][:1]), EX, ['BTC 불일치 거래소 0.0003 ≠ 세션+비세션 0.0002']),
    ('P3 배정을 입금으로 셈', dict(REG, capital_events=[],
     account_flows=[{'type': 'DEPOSIT', 'amount': '30000'}]), DB, EX,
     ['KRW 불일치 거래소 30200 ≠ 세션+예약+미배정 90200']),
    ('P4 등록 밖 DYNAMIC#94 실자금 포지션', REG,
     dict(DB, positions=DB['positions'] + [{'kind': 'DYNAMIC', 'sid': 94, 'coin': 'XRP', 'size': '1'}]), EX,
     ['UNREGISTERED_REAL DYNAMIC#94 XRP']),
]

if __name__ == '__main__':
    ok = 0
    for name, r, d, e, want in CASES:
        got = check(r, d, e)
        ok += got == want
        print('PASS' if got == want else 'FAIL', name, '→', got)
    print(f'{ok}/{len(CASES)}')
    raise SystemExit(0 if ok == len(CASES) else 1)
