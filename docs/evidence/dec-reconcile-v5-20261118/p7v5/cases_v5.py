"""P7 v5 시계 축 시험(총괄 v1.189 §2-5). PG·네트워크·DB 접속 없음. 저장 입력은 읽기만 하고 결과는 --out 에 새로 쓴다.

사용: python -I cases_v5.py --impl <reconcile.py> --out <폴더>
  K  새 시계 사례 — 격리 PG 스냅샷(dec-pg-cashflow-20261118/run1/snaps, 체크섬 확인)을 v5 입력으로 조립한 01b 등에 변형을 준다.
     v4 에 먼저 돌려 실패를 고정하고(--impl ../../dec-reconcile-v4-20261118/p7v4/reconcile.py) v5 에 돌린다.
  P  PG 30쌍 — 같은 스냅샷을 v5 계약(구간 단계·측정 표본·같은 시계 선언)으로 조립. 기대 = v4 저장 결과에서 시계 변경분만 바꾼 값.
  R  회귀 — v4 저장 순수 입력 99건에 합성 가정 축(모든 축 같은 시계 선언, basis 명시)을 붙여 v4 저장 결과와 전체 비교(clock 보고 제외).
  L  v4 형식 그대로(축 정보 없음) 99건 — 같은 시계로 승격하지 않음을 본다: v4 에 없던 이상이 새로 생기지 않고 ERROR 가 없어야 한다.
  F  파일 입력 — 바뀐 시각 검사와 관련된 CLI 3건.
종료값: 전부 통과 0, 하나라도 실패 1.
"""
import argparse, copy, hashlib, importlib.util, io, json, math, os, subprocess, sys
from contextlib import redirect_stdout
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.normpath(os.path.join(HERE, '..', '..'))
V4 = os.path.join(EV, 'dec-reconcile-v4-20261118')
PG = os.path.join(EV, 'dec-pg-cashflow-20261118')
SYN_BASIS = '합성 입력 가정: v4 cases.py 가 모든 시각을 한 시계로 생성(측정 아님)'
PG_APP = '격리 PG 시험: 엔진(세션·주문 created_at, Instant.now())과 수집기가 같은 JVM(P7CashflowPgTest) — 측정 아님, 같은 프로세스 시계'
PG_EX = '대역 거래소(같은 JVM): done_query 기간은 조립기가 단계 시작 시각으로 기록, 대역은 모든 응답을 기억 — 실제 거래소 시계 아님'


def iso(t):
    return None if t is None else t.isoformat().replace('+00:00', 'Z')


def ts(s):
    return datetime.fromisoformat(s.replace('Z', '+00:00'))


def sums(folder):
    out = {}
    for line in open(os.path.join(folder, 'SHA256SUMS'), encoding='utf-8'):
        h, p = line.split(None, 1)
        out[p.strip().lstrip('*')] = h
    return out


def read_checked(folder, rel, table):
    raw = open(os.path.join(folder, rel), 'rb').read()
    h = hashlib.sha256(raw).hexdigest()
    if table.get(rel) != h:
        raise SystemExit(f'원문 체크섬 불일치 {folder}/{rel}')
    return json.loads(raw.decode('utf-8')), h


# ── PG 스냅샷 → v5 관측 ────────────────────────────────────────
PG_SUMS = None


def snap(label):
    return read_checked(PG, f'run1/snaps/{label}.json', PG_SUMS)


def pg_obs(sn, same=(('app', 'collector', PG_APP), ('exchange', 'collector', PG_EX))):
    """at = 표본 직전 수집기 시각 c0, at_db = 그 연결의 DB clock_timestamp, rtt_ms = 같은 벽시계(Instant) 왕복.
    단계 k 는 표본 k 의 c1 뒤에 시작하고 표본 k+1 의 c0 전에 끝난다 → [c1_k, c0_{k+1}], 마지막 끝은 미기록.
    c0 는 µs 버림, rtt 는 µs 올림 + 1µs 로 범위를 넓힌다(좁히지 않음). 단조 시계 왕복은 기록되지 않아 mono_rtt_ms 없음."""
    st = sn['steps']
    steps, samples = [], []
    for i, x in enumerate(st):
        c0 = ts(x['at'])
        c1 = c0 + timedelta(microseconds=math.ceil(x['rtt_ms'] * 1000) + 1)
        end = ts(st[i + 1]['at']) if i + 1 < len(st) else None
        steps.append({'step': x['step'], 'start': iso(c1), 'end': iso(end)})
        samples.append({'pair': ['db', 'collector'], 'step': x['step'], 'c0': x['at'], 'd': x['at_db'], 'c1': iso(c1)})
    src = {'environment': 'local-isolated', 'db': 'cat-rt-pg/p7_cashflow(Flyway 새 DB)', 'exchange_account': 'stand-in-exchange-book(P7CashflowPgTest)'}
    ob = {'meta': {'source': src, 'app_code_sha': '0e1d2a02',
                   'app_code_sha_ref': '로컬 작업 사본 D:/tmp/p7pg-0e1d2a02 = 운영 반영 커밋 0e1d2a02 + 시험 클래스 1개(제품 변경 없음). 운영 관측 아님',
                   'steps': steps,
                   'clock': {'version': 'p7-clock-1', 'samples': samples,
                             'same_source': [{'axes': [a, b], 'basis': why} for a, b, why in same]}},
          'db_fp_pre': sn['db_fp_pre'], 'db': copy.deepcopy(sn['db']), 'db_fp_post': sn['db_fp_post'],
          'external_flows': [], 'query_errors': []}
    start = {s['step']: s['start'] for s in steps}
    for side in ('exchange_before', 'exchange_after'):
        ex = copy.deepcopy(sn[side])
        ex['done_query'] = {'from': '2026-01-01T00:00:00Z', 'to': start[side], 'complete': True, 'pages': 1}
        ob[side] = ex
    return ob


def pg_registry(bs, ns, drop=(), new_axis='db'):
    """기존 세션: 적용 시작 = 세션 created_at(앱 축). 새 세션: db = 기준 DB observed_at(DB 축) /
    collector = 기준 마지막 표본 c0(수집기 축, v4 APPAXIS 와 같은 값). 축을 항목마다 선언한다."""
    b = {(s['kind'], s['id']) for s in bs['db']['sessions']}
    eff_new = bs['db']['observed_at'] if new_axis == 'db' else bs['steps'][-1]['at']
    out = []
    for s in ns['db']['sessions']:
        if s['mode'] != 'REAL' or (s['kind'], s['id']) in drop:
            continue
        old = (s['kind'], s['id']) in b
        out.append({'kind': s['kind'], 'id': s['id'], 'mode': 'REAL', 'strategy_type': s['strategy_type'], 'timeframe': s['timeframe'],
                    'initial_capital': s['initial_capital'], 'coin_pair': s['coin_pair'] if s['kind'] == 'LIVE' else 'DYNAMIC',
                    'code_sha': '0e1d2a02', 'effective_from': s['created_at'] if old else eff_new,
                    'effective_axis': 'app' if old else new_axis, 'effective_to': None,
                    'session_created_at': s['created_at'] if old else None,
                    'rules': {'max_open_positions': 1, 'max_buy_request_krw': '20000'}})
    return {'registry_version': 'p7pg-local-1',
            'observation_source': {'environment': 'local-isolated', 'db': 'cat-rt-pg/p7_cashflow(Flyway 새 DB)',
                                   'exchange_account': 'stand-in-exchange-book(P7CashflowPgTest)'},
            'sessions': out}


def pg_pair(b, n, drop=(), new_axis='db'):
    (bs, bh), (ns, nh) = snap(b), snap(n)
    return {'registry': pg_registry(bs, ns, drop, new_axis), 'base': pg_obs(bs), 'now': pg_obs(ns)}, (bh, nh)


# ── 실행 ─────────────────────────────────────────────────────
def load_impl(path):
    spec = importlib.util.spec_from_file_location('impl', path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def run(m, d):
    return m.evaluate(copy.deepcopy(d['registry']), copy.deepcopy(d['base']), copy.deepcopy(d['now']))


def codes(r, kind):
    return sorted({x['code'] for x in r.get(kind) or []})


def allcodes(r):
    return sorted(set(codes(r, 'anomalies')) | set(codes(r, 'holds')))


def clock_checks(r):
    return sorted({h.get('check') for h in r.get('holds') or [] if h['code'] == 'CLOCK_AXIS_UNCONFIRMED'})


def strip_clock(r):
    return {k: v for k, v in r.items() if k != 'clock'}


# ── K: 새 시계 사례 ────────────────────────────────────────────
def drop_same(d, axes, sides=('base', 'now')):
    for side in sides:
        c = d[side]['meta']['clock']
        c['same_source'] = [x for x in c['same_source'] if sorted(x['axes']) != sorted(axes)]


def collector_range(d, t_db, side='base'):
    """db 축 시각 t_db 를 앞뒤로 감싸는 표본(d ≤ t ≤ d')으로 수집기 축 범위 [lo, hi] — 시험 값 만들기용."""
    smp = sorted((ts(x['c0']), ts(x['d']), ts(x['c1'])) for x in d[side]['meta']['clock']['samples'])
    a = max(i for i, v in enumerate(smp) if v[1] <= t_db)
    b = min(i for i, v in enumerate(smp) if v[1] >= t_db)
    part = smp[a:b + 1]
    return t_db + min(c0 - dd for c0, dd, c1 in part), t_db + max(c1 - dd for c0, dd, c1 in part)


def new_session(d):
    return d['now']['db']['sessions'][0]


def k_cases():
    K = []

    def add(name, why, d, want, need=(), forbid=(), checks=None):
        K.append((name, why, d, want, list(need), list(forbid), checks))

    # 01b(L/D) 원형 — 적용 시작은 기준 DB observed_at(db 축), 세션 created_at 은 앱 축
    for p, b0 in (('L', '00-start'), ('D', 'L7-idle')):
        d, _ = pg_pair(b0, f'{p}1-allocated')
        add(f'K01{p}_01b_measured', '01b 원형 + 측정 표본(DB−수집기)·앱=수집기(같은 JVM) 선언 → 생성이 적용 시작 뒤로 확정, 거짓 이상 없음',
            d, 'HOLD', ['COLLECTION_ORDER_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH', 'UNREGISTERED_REAL_SESSION_ACTIVE', 'CLOCK_AXIS_UNCONFIRMED'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    drop_same(d, ('app', 'collector'))
    add('K02_01b_app_clock_unknown', '앱 시계 관계 없음(운영처럼 앱 시각을 읽을 승인 없음) → 그 비교만 보류, 추정값 없음', d, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH', 'UNREGISTERED_REAL_SESSION_ACTIVE'], ['new_session_created_after_effective_from'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    drop_same(d, ('app', 'collector'), sides=('now',))
    add('K03_app_same_declared_one_side', '같은 시계 선언이 현재 관측에만 있음 → 관측 사이 시각에 쓰지 않음(보류)', d, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH'], ['new_session_created_after_effective_from'])
    legacy = json.load(open(os.path.join(PG, 'run1', 'pairs', 'L01b_new_allocation_db_axis_limit.json'), encoding='utf-8'))['input']
    add('K04_01b_v4_input_no_axis', 'v4 형식 저장 입력 그대로(축 정보 없음) → 같은 시계로 승격하지 않고 보류, 이상 없음', legacy, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH', 'UNREGISTERED_REAL_SESSION_ACTIVE'],
        ['db_extract_after_exchange_before', 'db_extract_before_exchange_after_end', 'new_session_created_after_effective_from'])
    # 0 차이·양/음 차이·경계 겹침
    d, _ = pg_pair('00-start', 'L1-allocated')
    e = d['registry']['sessions'][0]
    e['effective_from'], e['effective_axis'] = new_session(d)['created_at'], 'app'
    add('K05_zero_diff_same_axis', '같은 앱 축에서 생성 = 적용 시작(차이 0) → 일치(≥)', d, 'HOLD', ['COLLECTION_ORDER_UNCONFIRMED'],
        ['REGISTRY_CONTRACT_MISMATCH', 'CLOCK_AXIS_UNCONFIRMED'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    s = new_session(d)
    s['created_at'] = iso(ts(s['created_at']) - timedelta(seconds=5))
    add('K06_negative_confirmed', '생성이 적용 시작보다 5초 앞(오차 범위 밖) → 실제 계약 이상 유지', d, 'ANOMALY',
        ['REGISTRY_CONTRACT_MISMATCH'], ['CLOCK_AXIS_UNCONFIRMED'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    eff = ts(d['registry']['sessions'][0]['effective_from'])
    lo, hi = collector_range(d, eff)
    new_session(d)['created_at'] = iso(lo + (hi - lo) / 2)
    add('K07_boundary_overlap', '생성 시각을 적용 시작의 변환 범위 한가운데 둠 → 경계 겹침 보류', d, 'HOLD', ['CLOCK_AXIS_UNCONFIRMED'],
        ['REGISTRY_CONTRACT_MISMATCH'], ['new_session_created_after_effective_from'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    new_session(d)['created_at'] = iso(hi + timedelta(milliseconds=50))
    add('K08_positive_confirmed_50ms', '생성이 변환 범위 위쪽보다 50ms 뒤 → 확정 일치', d, 'HOLD', ['COLLECTION_ORDER_UNCONFIRMED'],
        ['REGISTRY_CONTRACT_MISMATCH', 'CLOCK_AXIS_UNCONFIRMED'])
    # 표본 결함
    d, _ = pg_pair('00-start', 'L1-allocated')
    for x in d['base']['meta']['clock']['samples']:
        for k in ('c0', 'd', 'c1'):
            x[k] = iso(ts(x[k]) - timedelta(hours=1))
    add('K09_stale_samples', '기준 관측 표본이 수집 구간 밖(1시간 전) → 오래된 표본 제외, 비교 보류', d, 'HOLD', ['CLOCK_AXIS_UNCONFIRMED'],
        ['REGISTRY_CONTRACT_MISMATCH'], ['db_extract_after_exchange_before', 'new_session_created_after_effective_from'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    d['base']['meta']['clock']['samples'] = []
    add('K10_missing_samples', '기준 관측 표본 없음 → 보류', d, 'HOLD', ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH'],
        ['db_extract_after_exchange_before', 'new_session_created_after_effective_from'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    for x in d['base']['meta']['clock']['samples']:
        x['mono_rtt_ms'] = '0.1'
    add('K11_shift_mono_vs_wall', '단조 시계 왕복 0.1ms 인데 벽시계 왕복 ≥0.5ms 더 김 → 표본 중 시계 이동, 표본 버림·보류', d, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH'], ['new_session_created_after_effective_from'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    x = d['base']['meta']['clock']['samples'][3]
    x['d'] = iso(ts(x['d']) + timedelta(milliseconds=50))
    add('K12_shift_between_samples', '같은 관측 표본 하나의 차이가 50ms 튐 → 범위 불일치(시계 이동), 그 관계 미사용·보류', d, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH'], ['new_session_created_after_effective_from'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    x = d['base']['meta']['clock']['samples'][2]
    x['mono_rtt_ms'] = str((ts(x['c1']) - ts(x['c0'])) / timedelta(microseconds=1) / 1000)
    add('K13_mono_consistent', '단조 왕복이 벽시계 왕복과 같음 → 표본 사용, 확정 일치', d, 'HOLD', ['COLLECTION_ORDER_UNCONFIRMED'],
        ['CLOCK_AXIS_UNCONFIRMED', 'REGISTRY_CONTRACT_MISMATCH'])
    # 수집 구간 ↔ DB 추출 시각
    d, _ = pg_pair('L1-allocated', 'L4-cancelled')
    d['now']['db']['observed_at'] = iso(ts(d['now']['db']['observed_at']) + timedelta(seconds=10))
    add('K14_db_time_outside_confirmed', 'DB 추출 시각이 측정 범위로도 거래소 뒤 읽기 뒤 → 기존 DB_TIME_OUTSIDE_COLLECTION 유지', d, 'HOLD',
        ['DB_TIME_OUTSIDE_COLLECTION'], ['CLOCK_AXIS_UNCONFIRMED'])
    legacy9 = json.load(open(os.path.join(PG, 'run1', 'pairs', 'L09_collector_clock_axis.json'), encoding='utf-8'))['input']
    add('K15_09_v4_input', 'v4 저장 09 입력(수집기 축, 축 정보 없음) → DB_TIME_OUTSIDE 대신 시계 축 미확인 보류', legacy9, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['DB_TIME_OUTSIDE_COLLECTION'], ['db_extract_after_exchange_before', 'db_extract_before_exchange_after_end'])
    d, _ = pg_pair('L1-allocated', 'L4-cancelled')
    add('K16_09_measured', '같은 09 구간을 측정 표본으로 → DB 추출이 거래소 앞/뒤 읽기 사이로 확정, 계산 일치', d, 'HOLD',
        ['COLLECTION_ORDER_UNCONFIRMED'], ['DB_TIME_OUTSIDE_COLLECTION', 'CLOCK_AXIS_UNCONFIRMED'])
    # 보류가 별개 이상을 숨기지 않음
    d, _ = pg_pair('00-start', 'L1-allocated')
    drop_same(d, ('app', 'collector'))
    for side in ('exchange_before', 'exchange_after'):
        d['now'][side]['accounts']['KRW']['balance'] = '999998.50'
    add('K17_clock_hold_plus_krw_minus_1_50', '시계 보류 + 거래소 KRW −1.50 → 잔차 이상 유지', d, 'ANOMALY',
        ['CLOCK_AXIS_UNCONFIRMED', 'ACCOUNT_KRW_MISMATCH'], ['REGISTRY_CONTRACT_MISMATCH'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    drop_same(d, ('app', 'collector'))
    for snapk in ('base', 'now'):
        for side in ('exchange_before', 'exchange_after'):
            d[snapk][side]['accounts']['KRW']['balance'] = '10000.00'
    add('K18_clock_hold_plus_unbacked', '시계 보류 + 거래소 KRW 10,000 < 세션 현금 16,240.37 → 담보 부족 이상 유지', d, 'ANOMALY',
        ['CLOCK_AXIS_UNCONFIRMED', 'ACCOUNT_KRW_UNBACKED'], ['REGISTRY_CONTRACT_MISMATCH'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    drop_same(d, ('app', 'collector'))
    d['registry']['sessions'][0]['strategy_type'] = 'EMA_CROSS'
    add('K19_clock_hold_plus_strategy_mismatch', '시계 보류 + 등록 전략 불일치 → 계약 이상 유지(시각 외 차이)', d, 'ANOMALY',
        ['CLOCK_AXIS_UNCONFIRMED', 'REGISTRY_CONTRACT_MISMATCH'])
    # 주문 등록 구간·규칙·거래소 조회 기간
    d, _ = pg_pair('L1-allocated', 'L4-cancelled')
    drop_same(d, ('app', 'collector'))
    e = d['registry']['sessions'][0]
    e['effective_from'], e['effective_axis'] = d['base']['db']['observed_at'], 'db'
    add('K20_order_window_app_unknown', '적용 시작 = 기준 DB 추출(db 축), 앱 시계 관계 없음 → 주문·기존 세션 시각 비교를 보류, 미등록 이상 없음·계산 일치',
        d, 'HOLD', ['CLOCK_AXIS_UNCONFIRMED'], ['UNREGISTERED_REAL_ORDER', 'ACCOUNT_KRW_MISMATCH', 'REGISTRY_CONTRACT_MISMATCH'],
        ['order_created_after_effective_from', 'existing_session_effective_from_after_created'])
    d, _ = pg_pair('L1-allocated', 'L4-cancelled')
    e = d['registry']['sessions'][0]
    e['effective_from'], e['effective_axis'] = d['now']['db']['observed_at'], 'db'
    add('K21_order_before_effective_confirmed', '적용 시작 = 현재 DB 추출(관측 범위 안) → 주문이 앞섬 확정 → 미등록 주문 이상', d, 'ANOMALY',
        ['UNREGISTERED_REAL_ORDER'])
    d, _ = pg_pair('L1-allocated', 'L4-cancelled')
    drop_same(d, ('exchange', 'collector'))
    add('K22_exchange_clock_unknown', '거래소 시계 관계 없음 → 조회 기간 덮음·주문 포함을 확정하지 않고 시계 보류', d, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['EXCHANGE_WINDOW_UNCONFIRMED', 'DB_FILL_NOT_ON_EXCHANGE', 'EXCHANGE_COVERAGE_GAP'],
        ['exchange_query_from_before_order', 'exchange_query_from_before_window', 'exchange_query_to_after_db_extract',
         'exchange_query_to_after_window'])
    d, _ = pg_pair('L1-allocated', 'L4-cancelled')
    d['now']['exchange_after']['done_query']['to'] = d['now']['meta']['steps'][1]['start']
    d['now']['exchange_before']['done_query']['to'] = d['now']['meta']['steps'][1]['start']
    add('K23_query_ends_before_db_extract', '조회 끝 = 현재 거래소 앞 읽기(현재 DB 추출 전) → 거래소 생성 시각을 덮지 못함 확정(의도 생성과 같다고 보지 않음)',
        d, 'HOLD', ['EXCHANGE_COVERAGE_GAP', 'EXCHANGE_WINDOW_UNCONFIRMED'], ['CLOCK_AXIS_UNCONFIRMED', 'DB_FILL_NOT_ON_EXCHANGE'])
    for name, drop in (('K24_rule_cap_window_unknown', True), ('K25_rule_cap_confirmed', False)):
        d, _ = pg_pair('L1-allocated', 'L4-cancelled')
        d['registry']['sessions'][0]['rules']['max_buy_request_krw'] = '1000'
        if drop:
            drop_same(d, ('app', 'collector'))
            add(name, '요청 상한 1,000 < 주문 16,159.16, 주문이 기준 뒤인지 미확정 → 규칙 위반 보류', d, 'HOLD', ['CLOCK_AXIS_UNCONFIRMED'],
                ['RULE_BUY_REQUEST_CAP'], None)
        else:
            add(name, '같은 상한 위반, 시계 관계 측정 → 규칙 이상 확정', d, 'ANOMALY', ['RULE_BUY_REQUEST_CAP'], ['CLOCK_AXIS_UNCONFIRMED'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    last = d['base']['meta']['steps'][-1]
    last['end'] = iso(ts(last['start']) + timedelta(milliseconds=1))
    add('K26_base_last_end_recorded', '기준 마지막 단계 끝 기록(합성 1ms) → 관측 사이 순서 확정, 전체 OK', d, 'OK', [], ['COLLECTION_ORDER_UNCONFIRMED'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    d['base']['meta']['clock']['samples'] = [x for x in d['base']['meta']['clock']['samples'] if x['step'] in ('db_fp_pre', 'exchange_before', 'db_extract')]
    add('K29_not_bracketed', '기준 표본이 DB 추출 앞쪽에만 있음(뒤 표본 없음) → 적용 시작 시각을 감싸지 못해 외삽하지 않고 보류', d, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH'], ['new_session_created_after_effective_from', 'db_extract_before_exchange_after_end'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    smp = d['base']['meta']['clock']['samples']
    kept = [x for x in smp if x['step'] in ('db_fp_pre', 'exchange_before', 'db_extract')]
    late = dict(kept[-1], step='db_fp_post')
    for k in ('c0', 'd', 'c1'):
        late[k] = iso(ts(late[k]) + timedelta(hours=1))
    d['base']['meta']['clock']['samples'] = kept + [late]
    add('K30_stale_sample_not_bracket', 'K29 + 1시간 뒤 표본(수집 구간 밖) → 오래된 표본으로 버려 감싸는 표본으로 쓰지 않음(보류 유지)', d, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH'], ['new_session_created_after_effective_from'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    for x in d['base']['meta']['clock']['samples']:
        x['mono_rtt_ms'] = str((ts(x['c1']) - ts(x['c0'])) / timedelta(microseconds=1) / 1000 - 0.4)
    eff = ts(d['registry']['sessions'][0]['effective_from'])
    lo, hi = collector_range(d, eff)
    new_session(d)['created_at'] = iso(hi + timedelta(microseconds=200))
    add('K31_mono_gap_widens_range', '단조/벽시계 왕복 차이 0.4ms(허용 이하) → 범위를 0.4ms 넓힘 → 넓히기 전 경계 0.2ms 뒤 생성은 겹침 보류', d, 'HOLD',
        ['CLOCK_AXIS_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH'], ['new_session_created_after_effective_from'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    d['base']['meta']['clock']['offset_ms'] = 1400
    add('K27_constant_offset_rejected', '단일 평균 오프셋 키 → 시계 메타데이터 무효(관측 무효 보류), 통과로 쓰지 않음', d, 'HOLD',
        ['OBSERVATION_INVALID'], ['REGISTRY_CONTRACT_MISMATCH'])
    d, _ = pg_pair('00-start', 'L1-allocated')
    del d['registry']['sessions'][0]['effective_axis']
    add('K28_registry_axis_undeclared', '등록 적용 시작 축 미선언 → 보류(축 미선언)', d, 'HOLD', ['CLOCK_AXIS_UNCONFIRMED'],
        ['REGISTRY_CONTRACT_MISMATCH'], ['new_session_created_after_effective_from'])
    return K


# ── P: PG 30쌍 v5 ─────────────────────────────────────────────
def p_cases():
    asm = load_impl(os.path.join(PG, 'assemble.py'))
    P = []
    for name, b, n, axis, want, need, forbid, edit, why in asm.PAIRS:
        stored, _ = read_checked(PG, f'run1/pairs/{name}.json', PG_SUMS)
        kind = 'LIVE' if name.startswith('L') else 'DYNAMIC'
        bs, ns = snap(b)[0], snap(n)[0]
        drop = {(x['kind'], x['id']) for x in ns['db']['sessions'] if x['kind'] == kind} if edit == 'DROP' else ()
        d = {'registry': pg_registry(bs, ns, drop, 'collector' if edit == 'APPAXIS' else 'db'), 'base': pg_obs(bs), 'now': pg_obs(ns)}
        if edit == 'STALE':
            asm.stale_exchange(bs)(d)
        elif callable(edit):
            edit(d)
        P.append((name, why, d, stored['result']))
    return P


def p_expect(name, v4):
    """v4 결과에서 시계 변경분만 바꾼 기대값: (이상 코드, 보류 코드, 비교할 계좌)."""
    an, ho = set(codes(v4, 'anomalies')), set(codes(v4, 'holds'))
    acc = v4.get('account')
    ref = None
    if '01b' in name:      # 거짓 이상 제거 — 같은 구간의 앱 축 등록(01) 결과와 같아야 한다
        an -= {'REGISTRY_CONTRACT_MISMATCH', 'UNREGISTERED_REAL_SESSION_ACTIVE'}
        ref = name.replace('01b_new_allocation_db_axis_limit', '01_new_allocation')
    if '09_' in name:      # 수집기 축 보류 해소 — 같은 구간(04)과 같은 계산
        ho -= {'DB_TIME_OUTSIDE_COLLECTION'}
        ref = name.replace('09_collector_clock_axis', '04_reserve_to_cancel_refund')
    ho |= {'COLLECTION_ORDER_UNCONFIRMED'}   # 저장 스냅샷은 마지막 단계 끝을 기록하지 않음
    if ref:
        acc = read_checked(PG, f'run1/pairs/{ref}.json', PG_SUMS)[0]['result']['account']
    return sorted(an), sorted(ho), acc


# ── R/L: v4 저장 99건 ──────────────────────────────────────────
def synth(inp):
    d = copy.deepcopy(inp)
    if isinstance(d.get('registry'), dict) and '__load_error__' not in d['registry']:
        d['registry']['effective_axis'] = 'app'
    for side in ('base', 'now'):
        ob = d.get(side)
        if isinstance(ob, dict) and isinstance(ob.get('meta'), dict):
            ob['meta']['clock'] = {'version': 'p7-clock-1', 'samples': [], 'same_source': [
                {'axes': [a, b], 'basis': SYN_BASIS} for a, b in
                (('collector', 'db'), ('collector', 'app'), ('collector', 'exchange'), ('db', 'app'), ('db', 'exchange'), ('app', 'exchange'))]}
    return d


# v4 형식 그대로 넣었을 때 v4 와 다른 이상 코드가 나오는 사례와 이유(시각 비교를 같은 축으로 승격하지 않은 결과)
LEGACY_EXPLAINED = {
    'N10d_created_after_effective': '등록표의 session_created_at>effective_from 은 축 미선언이라 등록표 검사에서 확정하지 않는다(REGISTRY_INVALID 아님). '
                                    '같은 앱 축 값인 DB created_at≠session_created_at 은 확정 불일치라 REGISTRY_CONTRACT_MISMATCH 로 남는다',
}


def r_cases():
    tab = sums(V4)
    out = []
    folder = os.path.join(V4, 'p7v4', 'run-v4', 'cases')
    for fn in sorted(os.listdir(folder)):
        rel = f'p7v4/run-v4/cases/{fn}'
        d, _ = read_checked(V4, rel, tab)
        if 'input' not in d:
            continue
        out.append((fn[:-5], d))
    return out


# ── F: 파일 입력 ───────────────────────────────────────────────
def f_cases(impl, outdir):
    fdir = os.path.join(outdir, 'files')
    os.makedirs(fdir, exist_ok=True)
    d, _ = pg_pair('L1-allocated', 'L4-cancelled')
    bad = copy.deepcopy(d['now'])
    bad['meta']['clock']['offset_ms'] = 1400
    tab = sums(V4)
    v4f = {k: open(os.path.join(V4, f'p7v4/run-v4/files/{k}.json'), 'rb').read() for k in ('reg', 'base', 'now')}
    for k, raw in v4f.items():
        if hashlib.sha256(raw).hexdigest() != tab[f'p7v4/run-v4/files/{k}.json']:
            raise SystemExit(f'v4 파일 입력 체크섬 불일치 {k}')
    files = {'v5_reg.json': d['registry'], 'v5_base.json': d['base'], 'v5_now.json': d['now'], 'v5_now_offset.json': bad}
    for fn, obj in files.items():
        with open(os.path.join(fdir, fn), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
    for k, raw in v4f.items():
        open(os.path.join(fdir, f'v4_{k}.json'), 'wb').write(raw)
    F = [('F11_v5_files_pg04', ['v5_reg.json', 'v5_base.json', 'v5_now.json'], 10, 'HOLD', ['COLLECTION_ORDER_UNCONFIRMED'], ['CLOCK_AXIS_UNCONFIRMED']),
         ('F12_v5_constant_offset_file', ['v5_reg.json', 'v5_base.json', 'v5_now_offset.json'], 10, 'HOLD', ['OBSERVATION_INVALID'], []),
         ('F13_v4_files_no_axis', ['v4_reg.json', 'v4_base.json', 'v4_now.json'], 10, 'HOLD', ['CLOCK_AXIS_UNCONFIRMED'], [])]
    res = []
    for name, (r, b, n), rc_want, want, need, forbid in F:
        p = subprocess.run([sys.executable, '-I', impl, '--registry', r, '--base', b, '--now', n], cwd=fdir, capture_output=True)
        try:
            out = json.loads(p.stdout.decode('utf-8'))
        except ValueError:
            out = {'overall': 'NO_JSON'}
        res.append((name, p.returncode, rc_want, out, want, need, forbid, p.stderr.decode('utf-8', 'replace')))
    return res


def main():
    global PG_SUMS
    ap = argparse.ArgumentParser()
    ap.add_argument('--impl', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--only', default='KPRLF')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    PG_SUMS = sums(PG)
    impl = os.path.abspath(a.impl)
    m = load_impl(impl)
    os.makedirs(os.path.join(a.out, 'cases'), exist_ok=True)
    try:
        shown = os.path.relpath(impl, EV).replace(os.sep, '/')
    except ValueError:   # 다른 드라이브(변형 작업 폴더)
        shown = os.path.basename(impl)
    lines = [f"impl {shown} sha256={hashlib.sha256(open(impl, 'rb').read()).hexdigest()}"]
    fails = total = 0

    def write(name, obj):
        with open(os.path.join(a.out, 'cases', name + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(obj, f, ensure_ascii=False, indent=1, default=str)

    def line(ok, grp, name, r, extra=''):
        nonlocal fails, total
        total += 1
        fails += not ok
        lines.append(f"{'PASS' if ok else 'FAIL'} | {grp} | {name} | {r.get('overall')} | {','.join(allcodes(r))}" + (f' | {extra}' if extra else ''))

    if 'K' in a.only:
        for name, why, d, want, need, forbid, checks in k_cases():
            r = run(m, d)
            cs = allcodes(r)
            bad = []
            if r.get('overall') != want:
                bad.append(f"overall {r.get('overall')}≠{want}")
            bad += [f'코드 없음 {c}' for c in need if c not in cs]
            bad += [f'금지 코드 {c}' for c in forbid if c in cs]
            if checks is not None and not set(checks) <= set(clock_checks(r)):
                bad.append(f'시계 비교 {sorted(set(checks) - set(clock_checks(r)))} 없음')
            if want != 'ANOMALY' and r.get('anomalies'):
                bad.append('이상 있음')
            write(name, {'why': why, 'expect': {'overall': want, 'codes': need, 'forbid': forbid, 'clock_checks': checks}, 'input': d, 'result': r})
            line(not bad, 'K', name, r, '; '.join(bad) + (f" [clock: {','.join(clock_checks(r))}]" if clock_checks(r) else ''))
    if 'P' in a.only:
        for name, why, d, v4 in p_cases():
            r = run(m, d)
            an, ho, acc = p_expect(name, v4)
            bad = []
            if codes(r, 'anomalies') != an:
                bad.append(f"이상 {codes(r, 'anomalies')}≠{an}")
            if codes(r, 'holds') != ho:
                bad.append(f"보류 {codes(r, 'holds')}≠{ho}")
            if r.get('account') != acc:
                bad.append('계좌 계산이 v4(또는 같은 구간 기준)와 다름')
            write('P_' + name, {'why': why, 'expect': {'anomalies': an, 'holds': ho, 'account_from': 'v4 저장 결과'}, 'input': d, 'result': r})
            k = (r.get('account') or {}).get('krw', {})
            line(not bad, 'P', name, r, '; '.join(bad) + f" KRW 조정={k.get('adjusted_residual')} [{k.get('status')}]")
    if 'R' in a.only or 'L' in a.only:
        for name, d in r_cases():
            v4 = d['result']
            patch = name == 'N11_internal_error_is_error'   # v4 cases.py 와 같은 방식으로 내부 예외를 넣는다
            orig = m.order_effects
            if patch:
                m.order_effects = lambda o: (_ for _ in ()).throw(RuntimeError('시험용 내부 예외'))
            if 'R' in a.only:
                r = run(m, synth(d['input']))
                same = strip_clock(r) == v4
                diff = '' if same else '; '.join(f'{k}' for k in sorted(set(v4) | set(r)) if k != 'clock' and r.get(k) != v4.get(k))
                line(same, 'R', name, r, '' if same else f'v4 저장 결과와 다른 키: {diff}')
            if 'L' in a.only:
                r = run(m, d['input'])
                new_an = sorted(set(codes(r, 'anomalies')) - set(codes(v4, 'anomalies')))
                bad = []
                if (r.get('overall') == 'ERROR') != patch:
                    bad.append(f"overall {r.get('overall')} (내부 예외 주입 사례만 ERROR 여야 함)")
                if new_an:
                    bad.append(f'v4 에 없던 이상 {new_an}')
                gone = sorted(set(codes(v4, 'anomalies')) - set(codes(r, 'anomalies')))
                if new_an and name in LEGACY_EXPLAINED:
                    bad = [x for x in bad if not x.startswith('v4 에 없던')]
                    gone.append('설명: ' + LEGACY_EXPLAINED[name])
                line(not bad, 'L', name, r, '; '.join(bad) + (f' 사라진 이상/설명={gone}' if gone else ''))
            m.order_effects = orig
    if 'F' in a.only:
        for name, rc, rc_want, out, want, need, forbid, err in f_cases(impl, a.out):
            cs = allcodes(out)
            bad = []
            if rc != rc_want:
                bad.append(f'rc {rc}≠{rc_want}')
            if out.get('overall') != want:
                bad.append(f"overall {out.get('overall')}≠{want}")
            bad += [f'코드 없음 {c}' for c in need if c not in cs]
            bad += [f'금지 코드 {c}' for c in forbid if c in cs]
            if err.strip():
                bad.append('stderr 있음')
            write(name, {'rc': rc, 'result': out})
            line(not bad, 'F', name, out, f'rc={rc} ' + '; '.join(bad))
    lines.append(f'합계 {total} 통과 {total - fails} 실패 {fails}')
    with open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
