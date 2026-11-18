"""P7 격리 PG 실제 현금흐름 — 스냅샷(P7CashflowPgTest 출력) → 대조기 v4 입력 조립·판정 (총괄 v1.188 §3-1·§3-2).
네트워크·DB 접속 없음. 스냅샷은 읽기만 하고 입력·결과는 --out 에 새로 쓴다.

사용: python -I assemble.py --snaps <run/snaps> --impl <p7v4/reconcile.py> --out <run/pairs>
축(axis): collector = 수집 단계 시각을 수집기(JVM) 시계로, db = 같은 단계에서 읽은 DB clock_timestamp 로.
종료값: 모든 기대 일치 0, 하나라도 다르면 1.
"""
import argparse, copy, hashlib, importlib.util, json, os, sys
from datetime import datetime
from decimal import Decimal as D

SRC = {'environment': 'local-isolated', 'db': 'cat-rt-pg/p7_cashflow(Flyway 새 DB)', 'exchange_account': 'stand-in-exchange-book(P7CashflowPgTest)'}
APP = '0e1d2a02'
APP_REF = '로컬 작업 사본 D:/tmp/p7pg-0e1d2a02 = 운영 반영 커밋 0e1d2a02 + 시험 클래스 1개(제품 변경 없음). 운영 관측 아님'


def ts(s):
    return datetime.fromisoformat(s.replace('Z', '+00:00'))


def load(snaps, label):
    p = os.path.join(snaps, label + '.json')
    raw = open(p, 'rb').read()
    return json.loads(raw.decode('utf-8')), hashlib.sha256(raw).hexdigest()


def obs(snap, axis):
    key = 'at' if axis == 'collector' else 'at_db'
    steps = [{'step': s['step'], 'at': s[key]} for s in snap['steps']]
    at = {s['step']: s['at'] for s in steps}
    ob = {'meta': {'source': dict(SRC), 'app_code_sha': APP, 'app_code_sha_ref': APP_REF, 'steps': steps},
          'db_fp_pre': snap['db_fp_pre'], 'db': copy.deepcopy(snap['db']), 'db_fp_post': snap['db_fp_post'],
          'external_flows': [], 'query_errors': []}
    for side in ('exchange_before', 'exchange_after'):
        ex = copy.deepcopy(snap[side])
        # 대역 거래소는 모든 응답을 기억하므로 완료 목록이 시작부터 그 시각까지 완전하다(실제 거래소 조회 증거 아님)
        ex['done_query'] = {'from': '2026-01-01T00:00:00Z', 'to': at[side], 'complete': True, 'pages': 1}
        ob[side] = ex
    return ob


def registry(base, now, drop=(), eff_axis='db'):
    """새 세션의 등록 적용 시작 = 기준 관측 끝. eff_axis=db 면 DB observed_at(DB 시계), app 이면 기준 마지막 수집 단계의 JVM 시각
    (이 시험에서는 앱과 수집기가 같은 JVM 이라 앱 시계와 같다 — 세션 created_at 은 앱이 Instant.now() 로 쓴다)."""
    eff_new = base['db']['observed_at'] if eff_axis == 'db' else base['steps'][-1]['at']
    b = {(s['kind'], s['id']) for s in base['db']['sessions']}
    out = []
    for s in now['db']['sessions']:
        if s['mode'] != 'REAL' or (s['kind'], s['id']) in drop:
            continue
        existing = (s['kind'], s['id']) in b
        out.append({'kind': s['kind'], 'id': s['id'], 'mode': 'REAL', 'strategy_type': s['strategy_type'], 'timeframe': s['timeframe'],
                    'initial_capital': s['initial_capital'], 'coin_pair': s['coin_pair'] if s['kind'] == 'LIVE' else 'DYNAMIC',
                    'code_sha': APP, 'effective_from': s['created_at'] if existing else eff_new, 'effective_to': None,
                    'session_created_at': s['created_at'] if existing else None,
                    'rules': {'max_open_positions': 1, 'max_buy_request_krw': '20000'}})
    return {'registry_version': 'p7pg-local-1', 'observation_source': dict(SRC), 'sessions': out}


def codes(r):
    return sorted({x['code'] for x in r.get('anomalies', []) + r.get('holds', [])})


# (이름, 기준, 현재, 축, 기대 판정, 필수 코드, 금지 코드, 변조, 설명)
def tamper_krw(d):
    a = d['now']['exchange_after']['accounts']['KRW']
    a['balance'] = str(D(a['balance']) - 1)
    d['now']['exchange_before']['accounts']['KRW'] = dict(a)


def tamper_coin(d):
    for side in ('exchange_before', 'exchange_after'):
        a = d['now'][side]['accounts']['RND']
        a['balance'] = str(D(a['balance']) + D('0.1'))


def stale_exchange(base_snap):
    def f(d):   # DB 는 정정 뒤, 거래소는 정정 전 응답 그대로 — 정정이 거래소와 대조되는지
        for side in ('exchange_before', 'exchange_after'):
            d['now'][side]['accounts'] = copy.deepcopy(base_snap[side]['accounts'])
    return f


def tamper_ledger(kind):
    def f(d):
        for s in d['now']['db']['sessions']:
            if s['kind'] == kind:
                s['available_krw'] = str(D(s['available_krw']) + D('0.01'))
    return f


PAIRS = []
for p, kind in (('L', 'LIVE'), ('D', 'DYNAMIC')):
    prev0 = '00-start' if p == 'L' else 'L7-idle'
    PAIRS += [
        (f'{p}01_new_allocation', prev0, f'{p}1-allocated', 'db', 'OK', [], [], 'APPAXIS', f'{kind} 새 배정(세션 행 생성) — 거래소 유입 아님. 적용 시작은 앱 시계 축'),
        (f'{p}01b_new_allocation_db_axis_limit', prev0, f'{p}1-allocated', 'db', 'ANOMALY', ['REGISTRY_CONTRACT_MISMATCH'], [], None,
         '한계 재현: 적용 시작을 DB 시계로 두면 앱 시계로 쓴 created_at 이 1.4초 앞서 보여 거짓 이상(v4 는 축 차이를 모름)'),
        (f'{p}02_submitted_in_flight', f'{p}1-allocated', f'{p}2-submitted', 'db', 'HOLD', ['ORDER_IN_FLIGHT'], [], None, '실제 매수 경로 예약·제출 — 그 코인·KRW 보류'),
        (f'{p}03_partial_fill_active', f'{p}2-submitted', f'{p}3-partial', 'db', 'HOLD', ['ORDER_IN_FLIGHT'], [], None, '부분 체결(활성) — 보류'),
        (f'{p}04_reserve_to_cancel_refund', f'{p}1-allocated', f'{p}4-cancelled', 'db', 'OK', [], [], None, '예약→부분 체결→취소 종결·잔액 환급'),
        (f'{p}05_fee_corrections', f'{p}4-cancelled', f'{p}5-corrected', 'db', 'OK', [], [], None, '늦은 수수료 정정 3회(settle APPLIED)'),
        (f'{p}06_same_response_repeated', f'{p}5-corrected', f'{p}6-repeated', 'db', 'OK', [], [], None, '같은 응답 반복(NO_CHANGE)'),
        (f'{p}07_zero_trade_existing_holding', f'{p}6-repeated', f'{p}7-idle', 'db', 'OK', [], [], None, '거래0 · 기존 보유(포지션·XRP)'),
        (f'{p}08_full_window', prev0, f'{p}7-idle', 'db', 'OK', [], [], 'APPAXIS', '새 배정부터 끝까지 한 구간(적용 시작 앱 시계 축)'),
        (f'{p}09_collector_clock_axis', f'{p}1-allocated', f'{p}4-cancelled', 'collector', 'HOLD', ['DB_TIME_OUTSIDE_COLLECTION'], [], None,
         '같은 구간을 수집기 시계로 — DB 시계와 축이 달라 보류(§3-2)'),
        (f'{p}N1_exchange_krw_minus_1', f'{p}1-allocated', f'{p}4-cancelled', 'db', 'ANOMALY', ['ACCOUNT_KRW_MISMATCH'], [], tamper_krw, '음성 대조: 거래소 KRW −1'),
        (f'{p}N2_exchange_coin_plus', f'{p}1-allocated', f'{p}4-cancelled', 'db', 'ANOMALY', ['ACCOUNT_COIN_MISMATCH'], [], tamper_coin, '음성 대조: 거래소 RND +0.1'),
        (f'{p}N3_correction_not_on_exchange', f'{p}4-cancelled', f'{p}5-corrected', 'db', 'ANOMALY', ['ACCOUNT_KRW_MISMATCH'], [], 'STALE',
         '음성 대조: DB 정정, 거래소는 정정 전'),
        (f'{p}N4_session_ledger_plus_1c', f'{p}6-repeated', f'{p}7-idle', 'db', 'ANOMALY', ['SESSION_LEDGER_MISMATCH'], [], tamper_ledger(kind),
         '음성 대조: 세션 잔액 +0.01'),
        (f'{p}N5_unregistered_session', f'{p}1-allocated', f'{p}4-cancelled', 'db', 'ANOMALY', ['UNREGISTERED_REAL_SESSION_ACTIVE'], [], 'DROP',
         '음성 대조: 등록표에서 이 세션 제외'),
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--snaps', required=True)
    ap.add_argument('--impl', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    spec = importlib.util.spec_from_file_location('reconcile_v4', a.impl)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    os.makedirs(a.out, exist_ok=True)
    impl_sha = hashlib.sha256(open(a.impl, 'rb').read()).hexdigest()
    lines = [f'impl {os.path.basename(a.impl)} sha256={impl_sha}']
    # 시계 차이 요약(스냅샷별 db − 수집기, 왕복 상한)
    clock = {}
    for fn in sorted(os.listdir(a.snaps)):
        s, h = load(a.snaps, fn[:-5])
        offs = [(ts(x['at_db']) - ts(x['at'])).total_seconds() * 1000 for x in s['steps']]
        clock[fn[:-5]] = {'offset_ms_min': round(min(offs), 3), 'offset_ms_max': round(max(offs), 3),
                          'rtt_ms_max': max(x['rtt_ms'] for x in s['steps']), 'sha256': h}
    fails = 0
    for name, b, n, axis, want, need, forbid, edit, why in PAIRS:
        bs, bh = load(a.snaps, b)
        ns, nh = load(a.snaps, n)
        kind = 'LIVE' if name.startswith('L') else 'DYNAMIC'   # 구간 이름의 종류(L/D)의 세션 — 종류마다 하나
        drop = {(x['kind'], x['id']) for x in ns['db']['sessions'] if x['kind'] == kind} if edit == 'DROP' else ()
        d = {'registry': registry(bs, ns, drop, 'app' if edit == 'APPAXIS' else 'db'), 'base': obs(bs, axis), 'now': obs(ns, axis)}
        if edit == 'STALE':
            stale_exchange(bs)(d)
        elif callable(edit):
            edit(d)
        r = m.evaluate(d['registry'], d['base'], d['now'])
        cs = codes(r)
        bad = []
        if r.get('overall') != want:
            bad.append(f"overall {r.get('overall')}≠{want}")
        bad += [f'코드 없음 {c}' for c in need if c not in cs]
        bad += [f'금지 코드 {c}' for c in forbid if c in cs]
        if want == 'OK' and r.get('anomalies'):
            bad.append('이상 있음')
        acc = r.get('account') or {}
        k = acc.get('krw', {})
        fails += bool(bad)
        lines.append(f"{'PASS' if not bad else 'FAIL'} | {name} | {r.get('overall')} | {','.join(cs)} | "
                     f"KRW Δ거래소={k.get('exchange_delta')} Δ장부={k.get('session_ledger_delta')} 원시={k.get('raw_residual')} "
                     f"설명={k.get('explained_rounding')} 조정={k.get('adjusted_residual')} [{k.get('status')}]" + (f" | {'; '.join(bad)}" if bad else ''))
        with open(os.path.join(a.out, name + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'why': why, 'base_snapshot': {'label': b, 'sha256': bh}, 'now_snapshot': {'label': n, 'sha256': nh}, 'axis': axis,
                       'expect': {'overall': want, 'codes': need, 'forbid': forbid}, 'input': d, 'result': r}, f,
                      ensure_ascii=False, indent=1, default=str)
    lines.append(f'합계 {len(PAIRS)} 통과 {len(PAIRS) - fails} 실패 {fails}')
    with open(os.path.join(a.out, 'clock.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(clock, f, ensure_ascii=False, indent=1)
    with open(os.path.join(a.out, 'pairs.out.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
