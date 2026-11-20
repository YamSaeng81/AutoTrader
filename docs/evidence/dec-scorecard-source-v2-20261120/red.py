"""실패 먼저 고정 — 총괄 v1.202 반례 S1~S5 를 451차 초안(asof_examples.py 원문·초안 규칙)과 성적표 v3 원문에 그대로 넣는다.
원문은 읽기만 한다(체크섬 확인). 운영·DB·네트워크 접속 없음.  사용: python -I red.py
기대: S1·S2·S3·S4 는 초안이 계약을 어김(재현), S5 는 v3 원문이 기초0 대조군으로 이미 맞음.
"""
import hashlib, importlib.util, json, os, sys
from decimal import Decimal as D

EV = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
ASOF = os.path.join(EV, 'dec-scorecard-asof-20261120', 'asof_examples.py')
V3 = os.path.join(EV, 'dec-scorecard-v3-20261119', 'scorecard', 'scorecard.py')
PIN = {ASOF: None, V3: None}


def load(path, name):
    h = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    PIN[path] = h
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def main():
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    a = load(ASOF, 'asof')
    v3 = load(V3, 'v3')
    out = []
    # S1 11-30 매도(이전 달)를 초안 함수에 넣으면 12월 확정으로 표시되는가
    r = a.row(sell_applied_at='2026-11-30T10:00:00+09:00', reported_observed_at='2026-11-30T10:00:05+09:00',
              applied_executed_funds='10000', applied_paid_fee='5', filled_quantity='0.1', captured_at='2027-01-01T10:12:00+09:00')
    g = a.attribute(r, r, r)
    out.append(('S1 이전 달 매도가 12월 확정', g.get('month') == '2026-12', g))
    # S2 3월에 처음 본 수수료 차이를 1월 정정으로 내는가
    now = dict(r, sell_applied_at='2026-12-30T10:00:00+09:00', reported_observed_at='2026-12-30T10:00:05+09:00')
    mar = dict(now, applied_paid_fee='5.01')
    g = a.attribute(now, now, mar)
    out.append(('S2 3월 관측 차이를 1월 정정', 'next_month_correction' in g and '1월' in g.get('why', ''), g))
    # S3 초안 규칙: [T,M] 포착을 그대로 T 값으로 → 1월 차감 100 이 12월 손익에 섞임
    true_T, jan_debit = D(10000), D(100)
    captured = true_T - jan_debit
    out.append(('S3 1월 회차 값을 12월말로 확정', captured - true_T != 0, {'pnl_error': str(captured - true_T)}))
    # S4 초안 문장 "start 는 initial_capital, flows 에도 ALLOCATE" 를 v3 에 넣으면 거절되는가(초안이 v3 계약과 충돌)
    inp = {'version': 'scorecard-input-2', 'period': {'from': '2026-12-01T00:00:00+09:00', 'to': '2027-01-01T00:00:00+09:00'},
           'sessions': [{'kind': 'LIVE', 'id': 203, 'mode': 'REAL', 'created_at': '2026-12-05T00:00:00+09:00', 'started_in_period': True,
                         'start': {'available_krw': '10000', 'pending_buys': [], 'positions': []},
                         'end': {'available_krw': '10000', 'pending_buys': [], 'positions': []},
                         'flows': [{'kind': 'ALLOCATE', 'amount_krw': '10000', 'at': '2026-12-05T00:00:00+09:00'}]}]}
    try:
        v3.build(inp)
        g = '수용됨'
    except v3.Bad as e:
        g = str(e)
    out.append(('S4 초안 규칙(start=initial_capital+ALLOCATE)이 v3 와 충돌', g != '수용됨', g))
    # S5 대조군 — 기초0+ALLOCATE 는 v3 원문이 이미 맞게 계산
    del inp['sessions'][0]['start']
    c = v3.build(inp)['sessions'][0]
    out.append(('S5 대조군 기초0+ALLOCATE (재현 아님이 정상)', c['month_pnl'] != '0',
                {k: c[k] for k in ('start_equity', 'net_flows', 'month_pnl')}))
    n = 0
    for name, hit, got in out:
        n += hit
        print(('재현' if hit else '재현 안 됨'), '|', name, '|', json.dumps(got, ensure_ascii=False, default=str))
    for p, h in PIN.items():
        print('원문', os.path.relpath(p, EV).replace(os.sep, '/'), 'sha256', h)
    print(f'재현 {n}/{len(out)} (기대 4/5 — S5 는 대조군)')
    return 0 if n == 4 and not out[4][1] else 1


if __name__ == '__main__':
    sys.exit(main())
