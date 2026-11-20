"""월 성적표 원천/as-of 계약의 좁은 예시(총괄 v1.201 §3-1). 가상 주문 행만 쓴다 — 운영·DB·네트워크 접속 없음.

규칙(README §2의 R1~R5를 코드로 옮긴 것):
  R1 월 귀속 시각 = 로컬 관측 시각(BUY filled_at, SELL sell_applied_at). 거래소 체결 시각이 아니다.
  R2 월 마감 T 의 값은 'T 직후 경계 포착(capture)'이 있을 때만 확정한다. 포착은 T 이후 관측 마감 M 이전에 읽은 행 복사본이다.
  R3 포착 행이 settlement_final=true · fill_accounting_pending=false · reported_observed_at < T 이면 그 값이 T 시점 값으로 확정된다.
     그 밖(T 이후 관측·미확정 정산)은 '월 경계 걸침 미확정'.
  R4 포착이 없으면 현재 행(누적·덮어쓰기)으로 과거 월말 값을 복원하지 않는다 → 미확정.
  R5 포착 뒤 현재 행 값이 바뀌었으면 이미 낸 달을 고치지 않고 '다음 달 정정' 차분으로 남긴다.
"""
from decimal import Decimal as D
from datetime import datetime

T = datetime.fromisoformat('2027-01-01T00:00:00+09:00')   # 12월 마감
M = datetime.fromisoformat('2027-01-01T10:30:00+09:00')   # 관측 마감(예: 10:00 보고 회수 뒤)


def t(s):
    return None if s is None else datetime.fromisoformat(s)


def attribute(row, capture, current):
    """row: 귀속에 쓰는 시각 필드 이름이 든 주문 메타. capture: T~M 사이 읽은 행(None 가능). current: 지금 행."""
    key = 'filled_at' if row['side'] == 'BUY' else 'sell_applied_at'
    if capture is None:
        return {'status': '미확정', 'why': 'R4 경계 포착 없음 — 현재 누적 행으로 월말 값 복원 불가'}
    cap_t = t(capture['captured_at'])
    if not (T <= cap_t <= M):
        return {'status': '미확정', 'why': f'R2 포착 시각 {capture["captured_at"]} 이 [T, M] 밖'}
    at = t(capture[key])
    if at is None or at >= T:
        return {'status': '12월 아님', 'why': f'R1 {key}={capture[key]} — 12월 귀속 없음(다음 달 이후)'}
    settled = (capture['settlement_final'] and not capture['fill_accounting_pending']
               and t(capture['reported_observed_at']) < T)
    if not settled:
        return {'status': '미확정', 'why': 'R3 월 경계 걸침 — T 이후 관측이 있거나 정산 미확정(부분 체결 가능)'}
    out = {'status': '확정', 'month': '2026-12', 'funds': capture['applied_executed_funds'],
           'fee': capture['applied_paid_fee'], 'qty': capture['filled_quantity']}
    diff = {k: str(D(current[k]) - D(capture[k])) for k in ('applied_executed_funds', 'applied_paid_fee', 'filled_quantity')
            if D(current[k]) != D(capture[k])}
    if diff:
        out['next_month_correction'] = diff
        out['why'] = 'R5 포착 뒤 값 변경 — 12월은 그대로, 차분은 1월 정정'
    return out


def row(**kw):
    base = {'side': 'SELL', 'filled_at': None, 'sell_applied_at': None, 'settlement_final': True,
            'fill_accounting_pending': False, 'reported_observed_at': None,
            'applied_executed_funds': '0', 'applied_paid_fee': '0', 'filled_quantity': '0', 'captured_at': None}
    base.update(kw)
    return base


CASES = []

# E0 기준: 12-30 매도, 12-30 관측 확정, 포착=현재
r = row(sell_applied_at='2026-12-30T10:00:00+09:00', reported_observed_at='2026-12-30T10:00:05+09:00',
        applied_executed_funds='10000', applied_paid_fee='5', filled_quantity='0.1',
        captured_at='2027-01-01T10:12:00+09:00')
CASES.append(('E0 확정 매도', r, r, r, '확정'))

# E1 부분 체결 월 경계: 12-31 23:59 첫 관측 0.04, 01-01 00:01 잔량 체결 관측(0.1). filled_at 은 첫 관측만 남는다.
cur = row(side='BUY', filled_at='2026-12-31T23:59:00+09:00', reported_observed_at='2027-01-01T00:01:10+09:00',
          applied_executed_funds='10000', applied_paid_fee='5', filled_quantity='0.1',
          captured_at='2027-01-01T10:12:00+09:00')
CASES.append(('E1 부분 체결 월 경계', cur, cur, cur, '미확정'))

# E2 늦은 수수료 정정: 12-30 매도 확정, 포착 뒤 01-03 수수료 +0.01 (증가 정정은 discrepancy 에 남지 않음)
cap = dict(r)
now = dict(r, applied_paid_fee='5.01', reported_observed_at='2027-01-03T09:00:00+09:00')
CASES.append(('E2 늦은 수수료 정정', r, cap, now, '확정'))

# E3 포착 없이 01-05에 현재 행만 읽음 — 위 E2 행이라도 12월 값 복원 금지
CASES.append(('E3 포착 없음', r, None, now, '미확정'))

# E4 포착이 관측 마감 뒤(01-02) — 경계 포착으로 쓰지 않음
late = dict(r, captured_at='2027-01-02T09:00:00+09:00')
CASES.append(('E4 늦은 포착', r, late, late, '미확정'))

# E5 정산 미확정 상태로 포착(settlement_final=false)
pend = dict(r, settlement_final=False)
CASES.append(('E5 정산 미확정 포착', r, pend, pend, '미확정'))

if __name__ == '__main__':
    ok = 0
    for name, meta, capture, current, want in CASES:
        got = attribute(meta, capture, current)
        hit = got['status'] == want
        ok += hit
        print(('PASS' if hit else 'FAIL'), name, '→', got)
    print(f'{ok}/{len(CASES)}')
    raise SystemExit(0 if ok == len(CASES) else 1)
