"""월 손실 식 정정 확인(총괄 v1.227 §3-3) — 합성 입력 3건만. 운영·DB·네트워크 접속 없음. 새 변환기·성적표를 만들지 않는다.
고정 변환기 v4(dec-scorecard-source-v4-20261120/convert.py f63a857f)·성적표 v5(scorecard/scorecard.py b95acebd)를 해시 확인 뒤 그대로 부른다.
  M1 매수 원금: 초기 20,200 → 매수 대금 20,000·수수료 5 체결, 남은 현금 195·보유 평가 20,000 → 순자산 손익 −5 (현금 식 −20,005)
  M2 매수 예약: 체결 전 현금 200·남은 매수 예약금 20,000 → 손익 0 (현금 식 −20,000)
  M3 경계 뒤 수수료 정정: 12-31 23:40 경계 전 관측(수수료 5) → 01-01 10:00 경계 뒤 관측(수수료 6). T 포착·값별 근거 없음, 새 주문 0
     → 12월 수수료·실현·월손익은 미확정이어야 한다(관측 기간 성과와 달력월 확정 합계를 구분).
사용: python -I -X utf8 loss_cases.py --out <없는 폴더>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, copy, hashlib, importlib.util, json, os, re, sys
from decimal import Decimal as D

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
V4 = os.path.normpath(os.path.join(HERE, '..', '..', 'dec-scorecard-source-v4-20261120'))
PIN = {'convert.py': 'f63a857fcdb623176c3c8635e6ed41149808c9a5554648ce45bc06a38c40fa32',
       os.path.join('scorecard', 'scorecard.py'): 'b95acebdd9719e6b25ee6b62bfc03a4791992f5f8ed5e3c62d10d3635529a143'}


def load(rel, name):
    p = os.path.join(V4, rel)
    h = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    if h != PIN[rel]:
        sys.exit(f'고정 해시 불일치 {rel}: {h}')
    spec = importlib.util.spec_from_file_location(name, p)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


C = load('convert.py', 'convert_v4')
V = load(os.path.join('scorecard', 'scorecard.py'), 'scorecard_v5')
FROM, T, M = '2026-12-01T00:00:00+09:00', '2027-01-01T00:00:00+09:00', '2027-01-01T10:30:00+09:00'
PRE, POST = '2026-12-31T23:40:00+09:00', '2027-01-01T10:00:00+09:00'   # 경계 전 관측 / 경계 뒤 관측
SAME = {'same_source': [{'axes': ['app', 'db'], 'basis': '시험용 가상 선언 — 운영 시계 측정 아님'}]}


def snap(krw, at, positions=(), pending=()):
    return {'captured_at': at, 'axis': 'db', 'snapshot_id': 'db-1', 'gap_evidence': None,
            'data': {'available_krw': krw, 'pending_buys': list(pending), 'positions': list(positions)}}


def cap(at, qty, funds, fee, ref, final=True):
    return {'ref': ref, 'captured_at': at, 'axis': 'db', 'qty': qty, 'funds': funds, 'fee': fee, 'reported_observed_at': None,
            'settlement_final': final, 'fill_accounting_pending': False}


def src(orders, end):
    s = {'kind': 'LIVE', 'id': 203, 'mode': 'REAL', 'strategy_type': 'X', 'timeframe': 'H1',
         'created_at': '2026-11-01T00:00:00+09:00', 'initial_capital': '20200',
         'boundary': {'start': snap('20200', FROM), 'end': end}, 'orders': list(orders)}
    return {'version': 'scorecard-source-3', 'period': {'from': FROM, 'to': T, 'axis': 'db'}, 'deadline_M': M,
            'month_bounds': ['2027-02-01T00:00:00+09:00'], 'registry_events': [], 'sessions': [s], 'clock': SAME}


def run(s):
    try:
        inp, rep = C.convert(copy.deepcopy(s))
    except C.Bad as e:
        return {'rc': 2, 'error': str(e)}
    try:
        sc = V.build(copy.deepcopy(inp))
    except V.Bad as e:
        return {'rc': 'sc-2', 'error': str(e)}
    return {'rc': 0, 'input': inp, 'report': rep, 'card': sc['sessions'][0]}


def cash_formula(end_cash):          # 철회한 식: 실현 손익 = available_krw − 배정 시작액
    return D(end_cash) - D('20200')


CASES = []
BUY = {'id': 1, 'side': 'BUY', 'trade_ref': 'trade-1', 'filled_at': '2026-12-20T10:00:00+09:00'}

# M1 매수 원금 — 보유 평가 원천(가격)이 T 포착에 있음
pos = {'id': 11, 'coin': 'KRW-BTC', 'status': 'OPEN', 'size': '0.0002', 'cost_krw': '20005', 'price': '100000000'}
m1 = src([dict(BUY, captures=[cap(T, '0.0002', '20000', '5', 'b-T')])], snap('195', T, positions=[pos]))
# M2 매수 예약 — 체결 전, 예약금은 가용 KRW 에서 이미 빠짐
m2 = src([], snap('200', T, pending=[{'order_id': 7, 'reserved_krw': '20000', 'filled_qty': '0'}]))
# M3 경계 뒤 수수료 정정 — 매수 12-20·매도 12-30, 경계 전 23:40 관측과 경계 뒤 10:00 관측만 있음(T 포착·값별 근거 없음, 새 주문 0)
m3 = src([dict(BUY, captures=[cap(PRE, '0.0002', '20000', '5', 'b-pre'), cap(POST, '0.0002', '20000', '6', 'b-post')]),
          {'id': 2, 'side': 'SELL', 'trade_ref': 'trade-1', 'sell_applied_at': '2026-12-30T10:00:00+09:00',
           'captures': [cap(PRE, '0.0002', '20100', '5', 's-pre'), cap(POST, '0.0002', '20100', '5', 's-post')]}],
         dict(snap('20290', POST), gap_evidence=None))


def judge(name, r, exp):
    if r['rc'] != 0:
        return name, False, {'rc': r['rc'], 'error': r.get('error')}
    c = r['card']
    got = {k: c.get(k) for k in ('start_equity', 'end_equity', 'net_flows', 'month_pnl', 'month_pnl_status',
                                 'realized_in_period', 'realized_known_part', 'fees_paid', 'fees_known_part')}
    got['why'] = {k: v for k, v in c.items() if ('why' in k or 'unconfirmed' in k) and v}
    got['corrections'] = r['report']['sessions'][0].get('corrections')
    # run1 은 '-5' 와 '-5.0000' 을 문자열로 비교해 M1 을 실패로 냈다(시험 비교 결함). 숫자는 Decimal 값으로 비교한다.
    eq = lambda a, b: (a is None and b is None) or (a is not None and b is not None and (D(a) == D(b) if re.fullmatch(r'-?[0-9.]+', str(b)) else a == b))
    ok = all(eq(got.get(k), v) for k, v in exp.items())
    return name, ok, got


res = [judge('M1 매수 원금 20,000·수수료 5', run(m1), {'month_pnl': '-5', 'month_pnl_status': '확정', 'end_equity': '20195'}),
       judge('M2 남은 매수 예약 20,000', run(m2), {'month_pnl': '0', 'month_pnl_status': '확정', 'end_equity': '20200'}),
       judge('M3 경계 뒤 수수료 정정(새 주문 0)', run(m3), {'month_pnl': None, 'month_pnl_status': '미확정', 'fees_paid': None,
                                                     'realized_in_period': None})]
extra = {'M1 철회한 현금 식': str(cash_formula('195')), 'M2 철회한 현금 식': str(cash_formula('200'))}

if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('--out', required=True); a = ap.parse_args()
    os.makedirs(a.out, exist_ok=False)
    lines = []
    for n, ok, got in res:
        lines.append(f"{'OK  ' if ok else 'FAIL'} {n}\n     {json.dumps(got, ensure_ascii=False, default=str)}")
    for k, v in extra.items():
        lines.append(f'참고 {k}: {v}원')
    passed = sum(1 for _, ok, _ in res if ok)
    lines.append(f'합계 {len(res)} 통과 {passed} 실패 {len(res) - passed} · 변환기 v4 f63a857f · 성적표 v5 b95acebd')
    txt = '\n'.join(lines) + '\n'
    open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write(txt)
    print(txt, end='')
    sys.exit(0 if passed == len(res) else 1)
