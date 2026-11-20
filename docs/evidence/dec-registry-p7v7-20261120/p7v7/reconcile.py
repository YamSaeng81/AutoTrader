"""P7 v7 파일 입력 후보(총괄 v1.203 §2-4·§3-2) — v6(dec-registry-v3-p7v6-20261120/p7v6, 원문 보존) 사본에 합산 경계만 보완했다.
  1. 미래 기준값: cash_base·holdings_base 의 base_at 이 현재 관측(db 추출 시각) 이전인지 그 축으로 확인한다.
     확정적으로 뒤면 ACCOUNT_SUM_BASE_IN_FUTURE 이상(그 기준의 통화 합산 불일치), 선후 불명이면 그 통화 합산 보류.
  2. 기준 포함 이벤트도 현재 관측 이전이어야 한다. 현재 관측 뒤로 확정되면 ACCOUNT_SUM_INCLUDED_EVENT_AFTER_OBSERVATION 이상,
     불명이면 그 통화 보류(현재 관측 뒤 이벤트를 기준값 안에 숨기지 못함).
  3. 미배정 현금·비세션 보유가 음수가 되면 ACCOUNT_SUM_NEGATIVE_UNALLOCATED 이상(불가능한 잔액).
  account_sum 숫자 일치는 F3 통과가 아니다 — 통과 판정은 f3.py(전체 판정 OK·등록표 유효·관측 안정·출처/조회/지문 확인·합산 일치)가 한다.
  v6 의 다른 판정은 바꾸지 않았다.

이하 v6 설명(원문 유지)
P7 v6 파일 입력 후보 — v5(dec-reconcile-v5-20261118/p7v5, 2ca03476) 사본에 총괄 v1.202 §2-4 등록표 v3 계좌 합산만 더했다.

v6 추가(registry_version == 'registry-3' 일 때만 계산)
  등록표 최상위 cash_base{amount_krw, base_at, axis, source, includes_event_refs}·holdings_base{coins, base_at, axis, source,
  includes_event_refs}·capital_events[{ref, type, amount, currency, applied_at, recorded_at, axis, session}] 와 항목별
  ruleset{expected, confirmed, confirmed_source}. 하나라도 빠지거나 형식이 틀리면 REGISTRY_INVALID — 기본 시각·0 을 넣지 않는다.
  이벤트: 고유 ref·유한 양수·종류 ALLOCATE/RECOVER(KRW·등록 세션 연결 필수)/DEPOSIT/WITHDRAW(세션 없음)·기록 ≥ 적용.
  기준과의 경계: includes_event_refs 에 든 이벤트는 기준값에 이미 들어 있으므로 다시 적용하지 않는다(P3). 든 것으로 선언했는데
  적용이 기준 뒤로 확정되면 이상, 선언 없이 기준 이전(또는 같은 시각·시계 관계 불명)이면 그 통화 합산만 보류한다.
  현재 관측의 db 추출 뒤에 적용된 이벤트는 넣지 않고, 선후가 불명이면 보류한다.
  합산(A1/A2, 현재 관측): 거래소 KRW(가용+잠김) = Σ 등록 세션 available_krw + Σ 체결0 미체결 매수 reserved_krw + 미배정 현금,
  코인별 거래소 보유 = Σ 등록 세션 OPEN 포지션 수량(LIVE·DYNAMIC, BTC 포함) + 비세션 보유. CLOSING 포지션은 그 코인, 부분 체결
  예약은 KRW·코인 합산을 보류한다(중복 계산 방지). 미등록 실자금 세션/주문/포지션(v5 이상)이 있으면 합산을 맞추지 않는다.
  registry-3 가 아닌 등록표는 ACCOUNT_SUM_UNAVAILABLE 보류(통과로 보지 않음). v5 의 다른 판정은 바꾸지 않았다.
  ruleset.confirmed 가 없으면 RULESET_UNCONFIRMED 보류(ruleset_snapshot 만으로 세션 지문을 확정하지 않음).

이하 v5 설명(원문 유지)
P7 v5 병행 대조기 — v4(dec-reconcile-v4-20261118/p7v4, 4706dc50) 사본에 총괄 v1.189 §2 시계 축만 보완.

v5 시계 축(총괄 v1.189 §2)
  축: collector = 수집 단계(meta.steps)·표본의 수집기 벽시계, db = DB 시계(observed_at 은 추출 트랜잭션 시작 now()),
      app = 앱이 쓴 행 시각(세션·주문 created_at — 0e1d2a02 엔티티 @PrePersist Instant.now()), exchange = 거래소 주문 생성 시각과
      done_query 기간. 등록 effective_from/to 는 등록표 effective_axis 로 선언한다(없으면 축 미선언).
  모두 UTC 표기여도 같은 시계로 보지 않는다. 관계는 관측 meta.clock 에서만 온다:
    samples      = 같은 연결의 왕복 표본 {pair:[X,'collector'], step, c0, d, c1, mono_rtt_ms?}. X 시계 d 를 수집기 c0~c1 사이에 읽었으므로
                   (X − collector) ∈ [d−c1, d−c0]. 단조 시계 왕복과 벽시계 왕복의 차이가 MONO_TOL 을 넘으면 그 표본은 시계 이동으로 버리고,
                   이하면 그 차이만큼 범위를 넓힌다. 관측 수집 구간(collector 축) 밖 표본은 오래된 표본으로 버린다. 표본 순서에서 X 시계가
                   거꾸로 가면 시계 이동으로 보고 그 관계를 쓰지 않는다. 평균·단일 상수 오프셋은 받지 않는다(키가 있으면 관측 무효).
                   시각 t 를 옮길 때는 t 를 앞뒤로 감싸는 표본(전부 t 이전인 표본 ~ 전부 t 이후인 표본)의 범위 외포만 쓴다
                   (표본 사이 단조 변화 가정, 표본 밖·관측 사이로 외삽하지 않음). 한 관측 안에서도 차이가 ms 단위로 움직이므로 관측 전체에 한 범위를 쓰지 않는다.
    same_source  = 같은 물리 시계라는 선언 {axes:[A,B], basis}. 기준·현재 관측 모두에 같은 축 쌍 선언이 있을 때만 쓴다.
  등록표 effective_axis(전체 기본값) 또는 항목별 effective_axis 로 적용 시작/끝의 축을 선언한다.
  비교는 범위로 한다: 확정 참/거짓이면 v4 판정 그대로, 경계 겹침·관계 없음·축 미선언이면 CLOCK_AXIS_UNCONFIRMED 로 그 비교만 보류한다
  (막는 통화 없음 — 확정된 현금·보유·계약 이상은 그대로 남는다).
  단계는 {step, start, end} 구간(사건이 그 안에 있음, 마지막 end 는 미기록 가능) 또는 v4 형식 {step, at} 점이다.

이하 v4 설명(원문 유지)
P7 v4 병행 대조기 — v3(dec-reconcile-v3-20261118/p7v3, 98495f0b) 사본에 총괄 v1.188 §2 경계 U1~U3 만 보완. 이하 v3 설명 유지.
운영 설치본·RUNBOOK·F 원문과 무관하다. 입력은 모두 파일이다(네트워크·DB 접속 없음).

  --registry  등록표(JSON)   --base  기준 관측 묶음(JSON)   --now  현재 관측 묶음(JSON)
관측 묶음 = {meta, db_fp_pre, exchange_before, db(extract.sql 출력), exchange_after, db_fp_post, external_flows, query_errors}
  meta = {source:{environment, db, exchange_account}, app_code_sha?, app_code_sha_ref?, steps:[{step, at}×5]}
  exchange_* = {accounts, open_orders, done_order_ids, done_query?:{from, to, complete, pages}}

v2 대비 보완
  1. 입력 계약 — 필수 구조·비어 있지 않은 지문(md5 16진 32자)·시간대가 있는 시각·수집 순서·숫자 유한성/범위·키 중복·규칙 형식을 먼저 확인한다.
     결측/읽기 실패(보류)와 유효하지 않은 등록(이상)과 유효하지 않은 관측(보류)을 서로 다른 코드로 낸다. 예외는 ERROR 로 내며 OK·무출력으로 가리지 않는다.
  2. 출처 연결 — 등록 code_sha 는 관측 meta.app_code_sha(외부 수집 메타데이터)와 대조한다. DB 에 세션별 코드 SHA 는 없다고 본다.
     관측 환경/DB/거래소 계정은 등록표 observation_source 와 기준·현재가 모두 같아야 한다.
  3. 등록 적용 시작 ≠ 세션 생성 — session_created_at 이 null 이면 새 세션(생성 ≥ 적용 시작), 값이 있으면 기존 세션의 승인된 신규 구간
     (DB 생성 시각 = 등록값, 적용 시작 ≥ 생성)이다.
  4. 영향 범위 보류 — 보류마다 막는 통화(blocks)를 적는다. 막히지 않은 항목은 그대로 계산·판정한다(CLOSING 표시만으로 확정 잔차·담보 부족을 가리지 않음).
     항목별 상태(일치/불일치/보류)와 계좌 판정·전체 판정이 서로 모순되지 않게 낸다.
  5. 거래소 주문 조회 범위 — DB 체결 uuid → 거래소 완료 목록 방향도 본다. 조회 기간·완료 여부가 확인된 범위 안에서 빠진 것만 이상이고,
     범위 밖/완료 미확인은 보류다(OK 로도, 미등록 주문으로도 쓰지 않는다).
성과 평가(손익률 등)는 하지 않는다 — 현금·수량 대조만 한다.
"""
import argparse, json, re, sys
from decimal import Decimal as D, ROUND_HALF_UP, InvalidOperation
from datetime import datetime, timedelta

ACTIVE = {'PENDING', 'INTENT_READY', 'SUBMITTING', 'SUBMITTED', 'PARTIAL_FILLED'}
TERMINAL_NOFILL = {'CANCELLED', 'FAILED', 'INTENT_REJECTED'}
# v4: 제품 상태 집합(검토 기준 main 294645f1 의 TradingConstants.ACTIVE_ORDER_STATES·OrderEntity·서비스가 쓰는 문자열).
#     집합 밖 문자열은 정산 완료로 세지 않고 그 코인·KRW 를 보류한다. 실제 UNKNOWN 은 기존대로 경보(ORDER_UNKNOWN).
SETTLED = {'FILLED'} | TERMINAL_NOFILL
ORDER_STATES = ACTIVE | SETTLED | {'UNKNOWN'}
ORDER_TYPES = {'MARKET', 'LIMIT'}
POSITION_STATUSES = {'OPEN', 'CLOSING', 'CLOSED'}
RULES = {'max_filled_buys_in_window', 'max_open_positions', 'excluded_coins', 'max_buy_request_krw'}
REQUIRED = ('kind', 'id', 'mode', 'strategy_type', 'timeframe', 'initial_capital', 'coin_pair', 'code_sha',
            'effective_from', 'effective_to', 'session_created_at', 'rules')
STEPS = ('db_fp_pre', 'exchange_before', 'db_extract', 'exchange_after', 'db_fp_post')
SOURCE_KEYS = ('environment', 'db', 'exchange_account')
FLOW_KINDS = ('DEPOSIT', 'WITHDRAW', 'MANUAL_TRADE')
FP_RE = re.compile(r'^[0-9a-f]{32}$')
SHA_RE = re.compile(r'^[0-9a-f]{7,40}$')
COIN_RE = re.compile(r'^KRW-[A-Z0-9]+$')
CUR_RE = re.compile(r'^[A-Z0-9]+$')
CENT = D('0.01')
ALL = '*'
EXIT = {'OK': 0, 'HOLD': 10, 'ANOMALY': 20, 'ERROR': 30}
# v5 시계 축
AXES = ('collector', 'db', 'app', 'exchange')
CLOCK_VERSION = 'p7-clock-1'
CLOCK_KEYS = {'version', 'samples', 'same_source'}
SAMPLE_KEYS = {'pair', 'step', 'c0', 'd', 'c1', 'mono_rtt_ms'}
# 단조 시계 왕복과 벽시계 왕복의 차이가 이보다 크면 표본 중 시계가 움직였다고 보고 버린다(판정 유예가 아니라 표본 거절 기준 —
# 이하일 때도 그 차이만큼 범위를 넓히므로 통과 쪽으로 느슨해지지 않는다).
MONO_TOL = timedelta(microseconds=500)
ZERO = timedelta(0)


class Bad(ValueError):
    pass


# ── 값 검사 ────────────────────────────────────────────────────
def num(v, lo=None):
    """유한 십진수만 받는다(문자열·정수·Decimal). 실수형·불리언·NaN·무한대·범위 밖은 Bad."""
    if isinstance(v, bool) or isinstance(v, float) or not isinstance(v, (str, int, D)):
        raise Bad(f'숫자 형식 아님({type(v).__name__})')
    try:
        x = D(str(v).strip()) if isinstance(v, str) else D(v)
    except InvalidOperation:
        raise Bad(f'숫자 아님({v!r})')
    if not x.is_finite():
        raise Bad(f'유한하지 않음({v!r})')
    if lo is not None and x < lo:
        raise Bad(f'범위 밖({v!r} < {lo})')
    return x


def tstamp(v):
    """시간대가 있는 ISO 시각만 받는다."""
    if not isinstance(v, str):
        raise Bad('시각 형식 아님')
    try:
        t = datetime.fromisoformat(v.replace('Z', '+00:00'))
    except ValueError:
        raise Bad(f'시각 아님({v!r})')
    if t.tzinfo is None or t.utcoffset() is None:
        raise Bad(f'시간대 없음({v!r})')
    return t


def posint(v, lo=0):
    if isinstance(v, bool) or not isinstance(v, int) or v < lo:
        raise Bad(f'정수 {lo} 이상 아님({v!r})')
    return v


def nestr(v):
    if not isinstance(v, str) or not v.strip():
        raise Bad('비어 있지 않은 문자열 아님')
    return v


def dec(v):
    return None if v is None else num(v)


def r2(x):
    return x.quantize(CENT, rounding=ROUND_HALF_UP)


def ts(v):
    return None if v is None else tstamp(v)


def sha_eq(a, b):
    a, b = a.lower(), b.lower()
    return len(a) >= 7 and len(b) >= 7 and (a.startswith(b) or b.startswith(a))


class Result:
    def __init__(self):
        self.anomalies, self.holds = [], []

    def anomaly(self, code, **kw):
        self.anomalies.append({'code': code, **kw})

    def hold(self, code, blocks=(), **kw):
        self.holds.append({'code': code, 'blocks': sorted(set(blocks)), **kw})

    def blocked(self, cur):
        return any(ALL in h['blocks'] or cur in h['blocks'] for h in self.holds)


# ── v5 시계 축 ──────────────────────────────────────────────────
class ClockCtx:
    """한 관측의 시계 관계. samples[X] = [(c0, d, c1)] 수집 구간 안 표본(수집기 c0~c1 사이에 X 시계 d 를 읽음), static = 같은 시계 선언."""

    def __init__(self, name):
        self.name, self.samples, self.static = name, {}, set()
        self.report = {'meta': 'absent', 'steps_timing': None, 'samples': {}, 'relations': {}, 'same_source': []}


def _pair(a, b):
    return tuple(sorted((a, b)))


def step_bounds(ob, name):
    """(start, end) collector 축. 점 형식은 (at, at). end 미기록은 None."""
    s = next(x for x in ob['meta']['steps'] if x['step'] == name)
    if 'at' in s:
        t = tstamp(s['at'])
        return t, t
    return tstamp(s['start']), ts(s.get('end'))


def v_clock(probs, path, c):
    if not isinstance(c, dict) or set(c) - CLOCK_KEYS or c.get('version') != CLOCK_VERSION:
        probs.append(f'{path}: 객체·키 {sorted(CLOCK_KEYS)}·version {CLOCK_VERSION} 아님(평균/고정 오프셋 키는 받지 않음)')
        return
    if not isinstance(c.get('samples', []), list) or not isinstance(c.get('same_source', []), list):
        probs.append(f'{path}: samples/same_source 목록 아님')
        return
    for i, x in enumerate(c.get('samples') or []):
        p = f'{path}.samples[{i}]'
        if not isinstance(x, dict) or set(x) - SAMPLE_KEYS or not {'pair', 'step', 'c0', 'd', 'c1'} <= set(x):
            probs.append(f'{p}: 키 {sorted(SAMPLE_KEYS)}')
            continue
        pr = x['pair']
        if not (isinstance(pr, list) and len(pr) == 2 and pr[1] == 'collector' and pr[0] in AXES and pr[0] != 'collector'):
            probs.append(f'{p}.pair: [X, collector] 아님')
        if x['step'] not in STEPS:
            probs.append(f'{p}.step: 단계 이름 아님')
        for k in ('c0', 'd', 'c1'):
            _each(probs, f'{p}.{k}', tstamp, x[k])
        if x.get('mono_rtt_ms') is not None:
            _each(probs, f'{p}.mono_rtt_ms', num, x['mono_rtt_ms'], D(0))
    for i, x in enumerate(c.get('same_source') or []):
        p = f'{path}.same_source[{i}]'
        if not isinstance(x, dict) or set(x) != {'axes', 'basis'} or not (
                isinstance(x['axes'], list) and len(x['axes']) == 2 and
                all(a in AXES for a in x['axes']) and x['axes'][0] != x['axes'][1]):
            probs.append(f'{p}: {{axes:[A,B], basis}} 아님')
            continue
        _each(probs, f'{p}.basis', nestr, x['basis'])


def ms(td):
    return str(D(td // timedelta(microseconds=1)) / 1000)


def build_clock(name, ob):
    ctx = ClockCtx(name)
    st = ob['meta']['steps']
    ctx.report['steps_timing'] = 'point' if 'at' in st[0] else 'interval'
    b0 = step_bounds(ob, st[0]['step'])[0]
    last = step_bounds(ob, st[-1]['step'])
    span = (b0, last[1] if last[1] is not None else last[0])   # 이 관측 수집 구간(collector 축) — 표본 신선도 기준
    c = ob['meta'].get('clock')
    if c is None:
        return ctx   # 축 정보 없음 — 어떤 교차 축 비교도 확정하지 않는다(같은 시계로 승격하지 않음)
    ctx.report['meta'] = 'present'
    for x in c.get('same_source') or []:
        ctx.static.add((_pair(*x['axes']), x['basis']))
    ctx.report['same_source'] = sorted([list(p), b] for p, b in ctx.static)
    fresh, rep = {}, {}
    for x in c.get('samples') or []:
        X = x['pair'][0]
        c0, d, c1 = tstamp(x['c0']), tstamp(x['d']), tstamp(x['c1'])
        r = rep.setdefault(X, {'used': 0, 'stale': 0, 'shift': 0})
        if c1 < c0:
            r['shift'] += 1   # 벽시계가 거꾸로 감
            continue
        widen = ZERO
        if x.get('mono_rtt_ms') is not None:
            mono = timedelta(microseconds=int(num(x['mono_rtt_ms']) * 1000))
            widen = abs((c1 - c0) - mono)
            if widen > MONO_TOL:
                r['shift'] += 1   # 단조 시계와 벽시계 왕복이 다름 — 표본 중 벽시계 이동
                continue
        if c0 < span[0] or c1 > span[1]:
            r['stale'] += 1   # 이 관측의 수집 구간 밖에서 읽은 표본
            continue
        r['used'] += 1
        fresh.setdefault(X, []).append((c0 - widen, d, c1 + widen))
    ctx.report['samples'] = rep
    for X, smp in sorted(fresh.items()):
        smp.sort(key=lambda v: (v[0], v[2]))
        # 표본 순서대로 두 시계가 모두 앞으로 가야 한다. 수집기 시각은 뒤인데 X 시각이 앞이면 시계 역행(이동)으로 보고 관계를 쓰지 않는다.
        back = any(smp[i + 1][0] >= smp[i][2] and smp[i + 1][1] < smp[i][1] for i in range(len(smp) - 1))
        offs = [(dd - cc1, dd - cc0) for cc0, dd, cc1 in smp]
        if back:
            ctx.report['relations'][f'{X}-collector'] = {'status': 'shift', 'detail': '표본 순서와 X 시계 순서가 어긋남(역행) — 시계 이동 의심'}
            continue
        ctx.samples[X] = smp
        ctx.report['relations'][f'{X}-collector'] = {
            'status': 'measured', 'samples': len(smp),
            'offset_ms_each': [[ms(lo), ms(hi)] for lo, hi in offs],
            'note': '시각을 앞뒤로 감싸는 표본들의 범위만 사용(표본 사이 단조 변화 가정, 표본 밖 외삽 없음)'}
    return ctx


def static_of(ctxs):
    """쓸 수 있는 같은 시계 선언 — 주어진 모든 관측에 같은 축 쌍 선언이 있을 때만(관측 사이 시각에도 적용)."""
    sets = [{p for p, _b in c.static} for c in ctxs]
    return set.intersection(*sets) if sets else set()


def measured_step(c, ax, lo, hi):
    """한 관측의 측정 표본으로 [lo, hi] 를 옮긴다: [(축, lo', hi')]. 앞(전부 그 시각 이전)·뒤(전부 이후) 표본이 모두 있어야 하고,
    그 사이 표본 범위의 외포를 쓴다(표본 밖 외삽 없음)."""
    out = []
    for X, smp in c.samples.items():
        if ax == 'collector':
            ia = [i for i, v in enumerate(smp) if v[2] <= lo]
            ib = [i for i, v in enumerate(smp) if v[0] >= hi]
            if ia and ib:
                part = smp[max(ia):min(ib) + 1]
                out.append((X, lo + min(dd - cc1 for cc0, dd, cc1 in part), hi + max(dd - cc0 for cc0, dd, cc1 in part)))
        elif ax == X:
            ia = [i for i, v in enumerate(smp) if v[1] <= lo]
            ib = [i for i, v in enumerate(smp) if v[1] >= hi]
            if ia and ib:
                part = smp[max(ia):min(ib) + 1]
                out.append(('collector', lo + min(cc0 - dd for cc0, dd, cc1 in part), hi + max(cc1 - dd for cc0, dd, cc1 in part)))
    return out


def convert(t, A, B, ctxs):
    """축 A 의 시각 t 를 축 B 범위 (lo, hi) 로. 근거 있는 경로가 없으면 None."""
    if A == B:
        return t, t
    static = static_of(ctxs)
    out = []

    def walk(ax, lo, hi, seen):
        if ax == B:
            out.append((lo, hi))
            return
        for p in static:
            if ax in p:
                nb = p[1] if p[0] == ax else p[0]
                if nb not in seen:
                    walk(nb, lo, hi, seen | {nb})
        for c in ctxs:
            for nb, nlo, nhi in measured_step(c, ax, lo, hi):
                if nb not in seen:
                    walk(nb, nlo, nhi, seen | {nb})
    walk(A, t, t, {A})
    if not out:
        return None
    lo, hi = max(r[0] for r in out), min(r[1] for r in out)
    return (lo, hi) if lo <= hi else None


def le3(x, ax, y, ay, ctxs):
    """x ≤ y 를 범위로 판정: True(확정)/False(확정적으로 x > y)/None(축 미선언·관계 근거 없음·경계 겹침)."""
    if ax is None or ay is None:
        return None
    X = convert(x, ax, ay, ctxs)
    if X is not None:
        if X[1] <= y:
            return True
        if X[0] > y:
            return False
    Y = convert(y, ay, ax, ctxs)
    if Y is not None:
        if x <= Y[0]:
            return True
        if x > Y[1]:
            return False
    return None


def why3(x, ax, y, ay, ctxs):
    if ax is None or ay is None:
        return '축 미선언'
    return '경계 겹침(오차 범위 안)' if (convert(x, ax, ay, ctxs) or convert(y, ay, ax, ctxs)) else '축 관계 근거 없음'


def clock_hold(res, check, x, ax, y, ay, ctxs, **kw):
    res.hold('CLOCK_AXIS_UNCONFIRMED', blocks=(), check=check, left={'axis': ax, 'at': x.isoformat()},
             right={'axis': ay, 'at': y.isoformat()}, reason=why3(x, ax, y, ay, ctxs), **kw)


# ── 등록표 ─────────────────────────────────────────────────────
def check_rules(r):
    if not isinstance(r, dict):
        raise Bad('rules 가 객체 아님')
    unknown = set(r) - RULES
    if unknown:
        raise Bad(f'알 수 없는 규칙 {sorted(unknown)}')
    if 'max_filled_buys_in_window' in r:
        posint(r['max_filled_buys_in_window'], 0)
    if 'max_open_positions' in r:
        posint(r['max_open_positions'], 1)
    if 'max_buy_request_krw' in r:
        if num(r['max_buy_request_krw']) <= 0:
            raise Bad('max_buy_request_krw ≤ 0')
    if 'excluded_coins' in r:
        ex = r['excluded_coins']
        if not isinstance(ex, list) or not all(isinstance(c, str) and COIN_RE.match(c) for c in ex):
            raise Bad('excluded_coins 형식(KRW-XXX 목록)')
        if len(set(ex)) != len(ex):
            raise Bad('excluded_coins 중복')


def check_ruleset(r):
    """v6: 등록 기대 지문 / 실제 확인 지문 / 확인 출처를 구분한다. 확인 출처 없는 확인 지문은 받지 않는다."""
    if not isinstance(r, dict) or set(r) != {'expected', 'confirmed', 'confirmed_source'}:
        raise Bad('ruleset{expected, confirmed, confirmed_source} 형식')
    for k in r:
        if r[k] is not None:
            nestr(r[k])
    if (r['confirmed'] is None) != (r['confirmed_source'] is None):
        raise Bad('ruleset.confirmed 와 confirmed_source 는 함께 있거나 함께 없어야 함')


def check_entry(e, axis=None, v3=False):
    if not isinstance(e, dict):
        raise Bad('항목이 객체 아님')
    miss = [k for k in REQUIRED + (('ruleset',) if v3 else ()) if k not in e]
    if miss:
        raise Bad(f'필드 누락 {miss}')
    extra = set(e) - set(REQUIRED) - {'effective_axis'} - ({'ruleset'} if v3 else set())   # v5: 항목별 축 선언(선택)
    if extra:
        raise Bad(f'알 수 없는 필드 {sorted(extra)}')
    bad = []
    if v3:
        try:
            check_ruleset(e['ruleset'])
        except Bad as x:
            bad.append(f'ruleset: {x}')
    if 'effective_axis' in e:
        if e['effective_axis'] not in AXES:
            bad.append(f"effective_axis {e['effective_axis']!r} 는 {list(AXES)} 밖")
        axis = e['effective_axis']
    if e['kind'] not in ('LIVE', 'DYNAMIC'):
        bad.append('kind')
    if e['mode'] != 'REAL':
        bad.append('mode≠REAL')
    def capital(v):
        if num(v) <= 0:
            raise Bad('0 이하')
    for k, f in (('id', lambda v: posint(v, 1)), ('strategy_type', nestr), ('timeframe', nestr),
                 ('initial_capital', capital), ('effective_from', tstamp), ('rules', check_rules)):
        try:
            f(e[k])
        except Bad as x:
            bad.append(f'{k}: {x}')
    if not (isinstance(e['code_sha'], str) and SHA_RE.match(e['code_sha'])):
        bad.append('code_sha: 16진 7~40자 아님')
    if e['kind'] == 'LIVE' and not (isinstance(e['coin_pair'], str) and COIN_RE.match(e['coin_pair'])):
        bad.append('LIVE coin_pair')
    if e['kind'] == 'DYNAMIC' and e['coin_pair'] != 'DYNAMIC':
        bad.append('DYNAMIC coin_pair≠DYNAMIC')
    try:
        f0 = tstamp(e['effective_from'])
        if e['effective_to'] is not None and tstamp(e['effective_to']) <= f0:
            bad.append('effective_to ≤ effective_from')
        if e['session_created_at'] is not None:
            sc = tstamp(e['session_created_at'])
            # v5: session_created_at 은 앱 축(DB 행 값 복사). 적용 시작도 앱 축으로 선언됐을 때만 등록표 안에서 비교하고,
            #     다른 축이면 관측의 시계 관계로 대조 단계(contract_diff)에서 범위 판정한다.
            if axis == 'app' and sc > f0:
                bad.append('기존 세션인데 적용 시작이 생성보다 앞섬')
    except Bad as x:
        bad.append(f'시각: {x}')
    if bad:
        raise Bad('; '.join(bad))


REGISTRY_V3 = 'registry-3'
EVENT_TYPES = {'ALLOCATE': -1, 'RECOVER': 1, 'DEPOSIT': 1, 'WITHDRAW': -1}   # 미배정 보유에 대한 부호


def check_money(reg, keys):
    """v6 등록표 자금 메타데이터 검사. 문제 목록(빈 목록이면 통과). 기본값을 넣지 않는다."""
    probs = []
    refs = {}
    evs = reg.get('capital_events')
    if not isinstance(evs, list):
        probs.append('capital_events 목록 필수(없으면 빈 목록)')
        evs = []
    for i, e in enumerate(evs):
        p = f'capital_events[{i}]'
        try:
            if not isinstance(e, dict) or set(e) != {'ref', 'type', 'amount', 'currency', 'applied_at', 'recorded_at', 'axis', 'session'}:
                raise Bad('필드{ref,type,amount,currency,applied_at,recorded_at,axis,session} 형식')
            nestr(e['ref'])
            if e['ref'] in refs:
                raise Bad(f"ref {e['ref']!r} 중복 — 같은 이벤트를 두 번 적용하지 않음")
            refs[e['ref']] = e
            if e['type'] not in EVENT_TYPES:
                raise Bad(f"type {e['type']!r}")
            if num(e['amount']) <= 0:
                raise Bad('amount 양수만(방향은 type)')
            if not (isinstance(e['currency'], str) and CUR_RE.match(e['currency'])):
                raise Bad('currency 형식')
            if tstamp(e['recorded_at']) < tstamp(e['applied_at']):
                raise Bad('recorded_at < applied_at')
            if e['axis'] not in AXES:
                raise Bad(f"axis {e['axis']!r}")
            if e['type'] in ('ALLOCATE', 'RECOVER'):
                if e['currency'] != 'KRW':
                    raise Bad('배정/회수는 KRW 만')
                s = e['session']
                if not (isinstance(s, list) and len(s) == 2 and tuple(s) in keys):
                    raise Bad('배정/회수는 등록 세션 [kind, id] 연결 필수')
            elif e['session'] is not None:
                raise Bad('계좌 입출금에는 세션 연결을 두지 않음')
        except Bad as x:
            probs.append(f'{p}: {x}')
    for name, req in (('cash_base', {'amount_krw', 'base_at', 'axis', 'source', 'includes_event_refs'}),
                      ('holdings_base', {'coins', 'base_at', 'axis', 'source', 'includes_event_refs'})):
        b = reg.get(name)
        try:
            if not isinstance(b, dict) or set(b) != req:
                raise Bad(f'{sorted(req)} 형식')
            tstamp(b['base_at'])
            nestr(b['source'])
            if b['axis'] not in AXES:
                raise Bad(f"axis {b['axis']!r}")
            if name == 'cash_base':
                num(b['amount_krw'], D(0))
            else:
                if not isinstance(b['coins'], dict):
                    raise Bad('coins 객체')
                for c, q in b['coins'].items():
                    if not CUR_RE.match(str(c)) or c == 'KRW':
                        raise Bad(f'coins.{c} 통화 형식')
                    num(q, D(0))
            inc = b['includes_event_refs']
            if not isinstance(inc, list) or len(set(map(str, inc))) != len(inc):
                raise Bad('includes_event_refs 중복 없는 목록')
            for r in inc:
                if r not in refs:
                    raise Bad(f'includes_event_refs {r!r} 가 capital_events 에 없음')
                cur_ok = (refs[r]['currency'] == 'KRW') == (name == 'cash_base')
                if not cur_ok:
                    raise Bad(f'includes_event_refs {r!r} 통화가 이 기준과 다름')
        except Bad as x:
            probs.append(f'{name}: {x}')
    return probs


def load_registry(reg, res):
    """(entries, status). status: valid / invalid(🔴) / unreadable(❔)."""
    if isinstance(reg, dict) and '__load_error__' in reg:
        kind, msg = reg['__load_error__']
        if kind == 'unreadable':
            res.hold('REGISTRY_UNREADABLE', blocks=(), detail=msg)
            return {}, 'unreadable'
        res.anomaly('REGISTRY_INVALID', detail=msg)
        return {}, 'invalid'
    probs = []
    if not isinstance(reg, dict) or not isinstance(reg.get('sessions'), list):
        probs.append({'detail': 'sessions 목록 없음'})
    else:
        try:
            nestr(reg.get('registry_version'))
        except Bad:
            probs.append({'detail': 'registry_version 없음'})
        src = reg.get('observation_source')
        if not isinstance(src, dict) or set(src) != set(SOURCE_KEYS) or \
                not all(isinstance(src[k], str) and src[k].strip() for k in SOURCE_KEYS):
            probs.append({'detail': 'observation_source{environment, db, exchange_account} 형식'})
        axis = reg.get('effective_axis')
        if axis is not None and axis not in AXES:
            probs.append({'detail': f'effective_axis {axis!r} 는 {list(AXES)} 밖'})
            axis = None
        seen = set()
        v3 = reg.get('registry_version') == REGISTRY_V3
        for i, e in enumerate(reg['sessions']):
            try:
                check_entry(e, axis, v3)
                key = (e['kind'], e['id'])
                if key in seen:
                    raise Bad('중복 키')
                seen.add(key)
            except Bad as x:
                probs.append({'index': i, 'detail': str(x)})
        if v3:
            probs += [{'detail': d} for d in check_money(reg, seen)]
        else:
            extra = {'cash_base', 'holdings_base', 'capital_events'} & set(reg)
            if extra:
                probs.append({'detail': f'{sorted(extra)} 는 registry_version {REGISTRY_V3!r} 에서만 — 버전 없이 일부만 쓰지 않음'})
    if probs:
        # 🔴 등록표가 하나라도 잘못되면 전체를 믿지 않는다 — 모든 실자금 주문이 등록 밖으로 잡힌다.
        for p in probs:
            res.anomaly('REGISTRY_INVALID', **p)
        return {}, 'invalid'
    return {(e['kind'], e['id']): e for e in reg['sessions']}, 'valid'


# ── 관측 구조 검사 ──────────────────────────────────────────────
def _each(probs, path, fn, *a):
    try:
        return fn(*a)
    except Bad as x:
        probs.append(f'{path}: {x}')
        return None


def _list(probs, path, v, item=None):
    if not isinstance(v, list):
        probs.append(f'{path}: 목록 아님')
        return []
    if item:
        for i, x in enumerate(v):
            _each(probs, f'{path}[{i}]', item, x)
    return v


def _keys(x, req):
    if not isinstance(x, dict):
        raise Bad('객체 아님')
    miss = [k for k in req if k not in x]
    if miss:
        raise Bad(f'필드 누락 {miss}')


def v_session(s):
    _keys(s, ('kind', 'id', 'mode', 'strategy_type', 'timeframe', 'initial_capital', 'available_krw', 'status', 'created_at', 'coin_pair'))
    if s['kind'] not in ('LIVE', 'DYNAMIC'):
        raise Bad('kind')
    posint(s['id'], 1); num(s['initial_capital'], D(0)); num(s['available_krw'], D(0)); tstamp(s['created_at'])
    if s['mode'] is not None and not isinstance(s['mode'], str):
        raise Bad('mode')


def v_order(o):
    _keys(o, ('id', 'session_kind', 'session_id', 'position_id', 'coin_pair', 'side', 'order_type', 'quantity', 'reserved_krw',
              'client_order_id', 'state', 'filled_quantity', 'exchange_order_id', 'created_at', 'reported_executed_funds',
              'reported_paid_fee', 'applied_executed_funds', 'applied_paid_fee', 'fill_accounting_pending'))
    posint(o['id'], 1); posint(o['session_id'], 1)
    if o['session_kind'] not in ('LIVE', 'DYNAMIC'):
        raise Bad('session_kind')
    if not (isinstance(o['coin_pair'], str) and COIN_RE.match(o['coin_pair'])):
        raise Bad('coin_pair')
    if o['side'] not in ('BUY', 'SELL'):
        raise Bad('side')
    nestr(o['state']); nestr(o['order_type']); num(o['quantity'], D(0)); tstamp(o['created_at'])
    for k in ('reserved_krw', 'filled_quantity', 'reported_executed_funds', 'reported_paid_fee', 'applied_executed_funds', 'applied_paid_fee'):
        if o[k] is not None:
            num(o[k], D(0))
    if o['position_id'] is not None:
        posint(o['position_id'], 1)
    if not isinstance(o['fill_accounting_pending'], bool):
        raise Bad('fill_accounting_pending')
    for k in ('exchange_order_id', 'client_order_id'):
        if o[k] is not None:
            nestr(o[k])


def v_position(p):
    _keys(p, ('id', 'session_kind', 'session_id', 'coin_pair', 'size', 'status'))
    posint(p['id'], 1); posint(p['session_id'], 1); num(p['size'], D(0)); nestr(p['status'])
    if not (isinstance(p['coin_pair'], str) and COIN_RE.match(p['coin_pair'])):
        raise Bad('coin_pair')


def v_flow(f):
    _keys(f, ('kind', 'currency', 'amount', 'ref'))
    if f['kind'] not in FLOW_KINDS:
        raise Bad(f"kind {f['kind']!r}")
    if not (isinstance(f['currency'], str) and CUR_RE.match(f['currency'])):
        raise Bad('currency')
    num(f['amount']); nestr(f['ref'])


def v_exchange(probs, path, ex):
    if not isinstance(ex, dict):
        probs.append(f'{path}: 객체 아님')
        return
    acc = ex.get('accounts')
    if not isinstance(acc, dict):
        probs.append(f'{path}.accounts: 객체 아님')
    else:
        for cur, a in acc.items():
            if not CUR_RE.match(str(cur)):
                probs.append(f'{path}.accounts.{cur}: 통화 형식')
            elif not isinstance(a, dict) or set(a) != {'balance', 'locked'}:
                probs.append(f'{path}.accounts.{cur}: balance/locked 아님')
            else:
                _each(probs, f'{path}.accounts.{cur}.balance', num, a['balance'], D(0))
                _each(probs, f'{path}.accounts.{cur}.locked', num, a['locked'], D(0))
    for k in ('open_orders', 'done_order_ids'):
        lst = _list(probs, f'{path}.{k}', ex.get(k), nestr)
        if len(set(map(str, lst))) != len(lst):
            probs.append(f'{path}.{k}: 중복 uuid')
    q = ex.get('done_query')
    if q is not None:
        try:
            _keys(q, ('from', 'to', 'complete', 'pages'))
            if tstamp(q['from']) > tstamp(q['to']):
                raise Bad('from > to')
            if not isinstance(q['complete'], bool):
                raise Bad('complete')
            posint(q['pages'], 1)
        except Bad as x:
            probs.append(f'{path}.done_query: {x}')


def v_meta(probs, path, m):
    if not isinstance(m, dict):
        probs.append(f'{path}: 객체 아님')
        return
    src = m.get('source')
    if not isinstance(src, dict) or set(src) != set(SOURCE_KEYS) or not all(isinstance(src[k], str) and src[k].strip() for k in SOURCE_KEYS):
        probs.append(f'{path}.source: environment/db/exchange_account 형식')
    if m.get('app_code_sha') is not None:
        if not (isinstance(m['app_code_sha'], str) and SHA_RE.match(m['app_code_sha'])):
            probs.append(f'{path}.app_code_sha: 16진 7~40자 아님')
        _each(probs, f'{path}.app_code_sha_ref', nestr, m.get('app_code_sha_ref'))
    st = m.get('steps')
    if not isinstance(st, list) or [s.get('step') if isinstance(s, dict) else None for s in st] != list(STEPS):
        probs.append(f'{path}.steps: 순서 {list(STEPS)} 아님')
        return
    point = all('at' in s for s in st)
    interval = all('start' in s and 'at' not in s for s in st)
    if not (point or interval) or any(set(s) - ({'step', 'at'} if point else {'step', 'start', 'end'}) for s in st):
        probs.append(f'{path}.steps: 모든 단계가 {{step, at}} 이거나 모두 {{step, start, end}} 이어야 함')
        return
    prev = None
    for i, s in enumerate(st):
        if point:
            t = _each(probs, f"{path}.steps.{s['step']}", tstamp, s.get('at'))
            if t is not None and prev is not None and t < prev:
                probs.append(f"{path}.steps.{s['step']}: 앞 단계보다 이른 시각")
            prev = t if t is not None else prev
            continue
        a = _each(probs, f"{path}.steps.{s['step']}.start", tstamp, s.get('start'))
        b = None
        if s.get('end') is None:
            if i < len(st) - 1:
                probs.append(f"{path}.steps.{s['step']}.end: 마지막 단계만 미기록 가능")
        else:
            b = _each(probs, f"{path}.steps.{s['step']}.end", tstamp, s['end'])
        if a is not None and b is not None and b < a:
            probs.append(f"{path}.steps.{s['step']}: end < start")
        if a is not None and prev is not None and a < prev:
            probs.append(f"{path}.steps.{s['step']}: 앞 단계 끝보다 이른 시작")
        prev = b if b is not None else a
    if m.get('clock') is not None:
        v_clock(probs, f'{path}.clock', m['clock'])


def validate_observation(name, ob, res):
    """구조 검사. 반환: 'ok' / 'missing' / 'unreadable' / 'invalid'. 지문 결측은 구조 결함이 아니라 안정성 보류로 따로 본다."""
    if isinstance(ob, dict) and '__load_error__' in ob:
        kind, msg = ob['__load_error__']
        res.hold('OBSERVATION_UNREADABLE' if kind == 'unreadable' else 'OBSERVATION_INVALID', blocks=[ALL], at=name, detail=msg)
        return 'unreadable' if kind == 'unreadable' else 'invalid'
    if not isinstance(ob, dict):
        res.hold('OBSERVATION_MISSING', blocks=[ALL], at=name, detail='관측 묶음 없음')
        return 'missing'
    if ob.get('query_errors'):
        # 조회 실패는 결측과 함께 그대로 보인다(결측 판정이 실패 사유를 가리지 않음).
        res.hold('QUERY_FAILED', blocks=[ALL], at=name, detail=ob['query_errors'])
    miss = [k for k in ('meta', 'db', 'exchange_before', 'exchange_after') if ob.get(k) is None]
    if miss:
        res.hold('OBSERVATION_MISSING', blocks=[ALL], at=name, detail=miss)
        return 'missing'
    probs = []
    v_meta(probs, f'{name}.meta', ob['meta'])
    db = ob['db']
    if not isinstance(db, dict):
        probs.append(f'{name}.db: 객체 아님')
    else:
        _each(probs, f'{name}.db.observed_at', tstamp, db.get('observed_at'))
        _each(probs, f'{name}.db.flyway_version', posint, db.get('flyway_version'), 1)
        _each(probs, f'{name}.db.snapshot', nestr, db.get('snapshot'))
        ss = _list(probs, f'{name}.db.sessions', db.get('sessions'), v_session)
        os_ = _list(probs, f'{name}.db.orders', db.get('orders'), v_order)
        ps = _list(probs, f'{name}.db.positions', db.get('positions'), v_position)
        _list(probs, f'{name}.db.unresolved_discrepancies', db.get('unresolved_discrepancies'))
        for label, items, key in (('sessions', ss, lambda s: (s.get('kind'), s.get('id'))), ('orders', os_, lambda o: o.get('id')),
                                  ('positions', ps, lambda p: p.get('id')),
                                  ('orders.exchange_order_id', [o for o in os_ if isinstance(o, dict) and o.get('exchange_order_id')],
                                   lambda o: o.get('exchange_order_id'))):
            keys = [key(x) for x in items if isinstance(x, dict)]
            dup = sorted({str(k) for k in keys if keys.count(k) > 1})
            if dup:
                probs.append(f'{name}.db.{label}: 중복 키 {dup}')
    v_exchange(probs, f'{name}.exchange_before', ob['exchange_before'])
    v_exchange(probs, f'{name}.exchange_after', ob['exchange_after'])
    if ob.get('external_flows') is not None:
        _list(probs, f'{name}.external_flows', ob['external_flows'], v_flow)
    for k in ('db_fp_pre', 'db_fp_post'):
        v = ob.get(k)
        if v is not None and not (isinstance(v, str) and FP_RE.match(v)):
            probs.append(f'{name}.{k}: md5 16진 32자 아님')
    fp = db.get('fingerprint') if isinstance(db, dict) else None
    if fp is not None and not (isinstance(fp, str) and FP_RE.match(fp)):
        probs.append(f'{name}.db.fingerprint: md5 16진 32자 아님')
    if probs:
        res.hold('OBSERVATION_INVALID', blocks=[ALL], at=name, detail=probs)
        return 'invalid'
    return 'failed' if ob.get('query_errors') else 'ok'


def check_stability(name, ob, res, ctx=None):
    stable = True
    fps = (ob.get('db_fp_pre'), ob['db'].get('fingerprint'), ob.get('db_fp_post'))
    if any(v is None for v in fps):
        # 결측 세 값이 서로 같아도 안정 확인이 아니다.
        res.hold('FINGERPRINT_MISSING', blocks=[ALL], at=name,
                 missing=[k for k, v in zip(('db_fp_pre', 'db.fingerprint', 'db_fp_post'), fps) if v is None])
        stable = False
    elif not (fps[0] == fps[1] == fps[2]):
        res.hold('UNSTABLE_DB', blocks=[ALL], at=name, detail='추출 전·중·후 DB 지문이 다름 — 읽는 사이 주문/반영 변화')
        stable = False
    eb, ea = ob['exchange_before'], ob['exchange_after']
    for k in ('accounts', 'open_orders', 'done_order_ids'):
        if eb.get(k) != ea.get(k):
            res.hold('UNSTABLE_EXCHANGE', blocks=[ALL], at=name, detail=f'거래소 전후 {k} 다름')
            stable = False
    if ea.get('open_orders'):
        res.hold('EXCHANGE_OPEN_ORDERS', blocks=[ALL], at=name, ids=ea['open_orders'])
        stable = False
    t = tstamp(ob['db']['observed_at'])
    eb0 = step_bounds(ob, 'exchange_before')[0]
    ea0, ea1 = step_bounds(ob, 'exchange_after')
    ea1 = ea1 if ea1 is not None else ea0
    ctxs = [ctx]
    lo_ok, hi_ok = le3(eb0, 'collector', t, 'db', ctxs), le3(t, 'db', ea1, 'collector', ctxs)
    if lo_ok is False or hi_ok is False:
        res.hold('DB_TIME_OUTSIDE_COLLECTION', blocks=[ALL], at=name, detail='DB 추출 시각이 거래소 앞/뒤 읽기 사이가 아님')
        stable = False
    elif lo_ok is None or hi_ok is None:
        # v5: DB 시계와 수집기 시계의 관계가 없거나 오차 범위가 경계에 걸리면 이 비교만 보류(값 계산은 계속).
        if lo_ok is None:
            clock_hold(res, 'db_extract_after_exchange_before', eb0, 'collector', t, 'db', ctxs, at=name)
        if hi_ok is None:
            clock_hold(res, 'db_extract_before_exchange_after_end', t, 'db', ea1, 'collector', ctxs, at=name)
    return stable


def check_pair(base, now, reg_src, res):
    """기준·현재 관측 사이의 시각·출처·코드 관계."""
    ok = True
    t0, t1 = tstamp(base['db']['observed_at']), tstamp(now['db']['observed_at'])
    if not t0 < t1:
        res.hold('OBSERVATION_ORDER_INVALID', blocks=[ALL], detail=f'현재 관측({t1.isoformat()})이 기준({t0.isoformat()})보다 늦지 않음')
        ok = False
    else:
        b0, b1 = step_bounds(base, base['meta']['steps'][-1]['step'])
        n_start = step_bounds(now, now['meta']['steps'][0]['step'])[0]
        if n_start < (b1 if b1 is not None else b0):
            res.hold('COLLECTION_OVERLAP', blocks=[ALL], detail='현재 관측 수집이 기준 수집 종료 전에 시작됨')
            ok = False
        elif b1 is None:
            res.hold('COLLECTION_ORDER_UNCONFIRMED', blocks=(), detail='기준 관측 마지막 단계 끝 시각 미기록 — 현재 수집이 그 뒤인지 확인 불가')
    if base['db']['flyway_version'] != now['db']['flyway_version']:
        res.hold('SCHEMA_CHANGED_IN_WINDOW', blocks=[ALL], base=base['db']['flyway_version'], now=now['db']['flyway_version'])
        ok = False
    srcs = {'registry': reg_src, 'base': base['meta']['source'], 'now': now['meta']['source']}
    if reg_src is None:
        if base['meta']['source'] != now['meta']['source']:
            res.hold('OBSERVATION_SOURCE_MISMATCH', blocks=[ALL], detail=srcs)
            ok = False
    elif not (reg_src == base['meta']['source'] == now['meta']['source']):
        res.hold('OBSERVATION_SOURCE_MISMATCH', blocks=[ALL], detail=srcs)
        ok = False
    return ok


# ── 주문별 금액 ────────────────────────────────────────────────
def deducted(o):
    """매수 제출 때 세션에서 뺀 금액(DynamicTradingService/LiveTradingService restorableKrw 와 같은 규칙)."""
    if o.get('reserved_krw') is not None:
        return dec(o['reserved_krw'])
    if o.get('client_order_id') is not None:
        return D(0)
    return dec(o['quantity'])


def order_effects(o):
    """(거래소 KRW 증감, 세션 장부 증감, 코인 수량 증감, 설명 가능한 반올림, 확정 여부)"""
    side, st = o['side'], o['state']
    fq = dec(o.get('filled_quantity')) or D(0)
    af, fee = dec(o.get('applied_executed_funds')), dec(o.get('applied_paid_fee'))
    applied = af is not None and fee is not None and not o.get('fill_accounting_pending') and st in SETTLED
    if side == 'BUY':
        if fq > 0 and applied:
            x = dec(o['quantity']) - af - fee          # 미사용 환급(8자리 원시)
            ex, led = -(af + fee), -deducted(o) + r2(x)
            return ex, led, fq, ex - led, True
        if fq == 0 and st in TERMINAL_NOFILL:
            return D(0), D(0), D(0), D(0), True     # 예약 복원 → 세션 증감 0
        return D(0), -deducted(o), D(0), D(0), False  # 진행 중·반영 대기
    if fq > 0 and applied:
        y = af - fee
        return y, r2(y), -fq, y - r2(y), True
    if fq == 0 and st in TERMINAL_NOFILL:
        return D(0), D(0), D(0), D(0), True
    return D(0), D(0), D(0), D(0), False


def unsettled(st):
    """진행 중·UNKNOWN·집합 밖(미확인) 상태 — 정산 완료로 세지 않는다."""
    return st not in SETTLED


def is_paper(s):
    # 제품 규칙: DYNAMIC 은 정확히 'PAPER' 가 아니면 REAL 분기. LIVE 도 같은 보수 규칙을 쓴다.
    return s is not None and s.get('mode') == 'PAPER'


def coin_of(o):
    return o['coin_pair'].split('-', 1)[1]


def contract_diff(e, s, app_sha, axis=None, ctxs=(), res=None):
    """(diffs, clock_unconfirmed). 시각 관계는 범위로 판정하고 미확정이면 diff 가 아니라 보류로 낸다."""
    diffs, unk = [], False
    if s.get('mode') != e['mode']:
        diffs.append(f"mode {s.get('mode')}≠{e['mode']}")
    for k in ('strategy_type', 'timeframe'):
        if s.get(k) != e[k]:
            diffs.append(f'{k} {s.get(k)}≠{e[k]}')
    if dec(s.get('initial_capital')) != dec(e['initial_capital']):
        diffs.append(f"initial_capital {s.get('initial_capital')}≠{e['initial_capital']}")
    if e['kind'] == 'LIVE' and s.get('coin_pair') != e['coin_pair']:
        diffs.append(f"coin_pair {s.get('coin_pair')}≠{e['coin_pair']}")
    created = tstamp(s['created_at'])
    f0 = tstamp(e['effective_from'])
    key = [e['kind'], e['id']]
    if e['session_created_at'] is None:
        r = le3(f0, axis, created, 'app', ctxs)
        if r is False:
            diffs.append('새 세션 등록인데 세션 생성이 등록 적용 시각보다 앞섬')
        elif r is None:
            unk = True
            clock_hold(res, 'new_session_created_after_effective_from', f0, axis, created, 'app', ctxs, session=key)
    else:
        if created != tstamp(e['session_created_at']):   # 같은 앱 축 값의 복사 — 같아야 한다
            diffs.append(f"기존 세션 생성 시각 {s['created_at']}≠{e['session_created_at']}")
        if axis != 'app':
            sc = tstamp(e['session_created_at'])
            r = le3(sc, 'app', f0, axis, ctxs)
            if r is False:
                diffs.append('기존 세션인데 적용 시작이 생성보다 앞섬')
            elif r is None:
                unk = True
                clock_hold(res, 'existing_session_effective_from_after_created', sc, 'app', f0, axis, ctxs, session=key)
    if app_sha is not None and not sha_eq(e['code_sha'], app_sha):
        diffs.append(f"code_sha {e['code_sha']}≠관측 {app_sha}")
    return diffs, unk


def total(acc, cur):
    a = acc.get(cur)
    return D(0) if a is None else dec(a['balance']) + dec(a['locked'])


def status(blocked, ok):
    return '보류' if blocked else ('일치' if ok else '불일치')


# ── 본 대조 ────────────────────────────────────────────────────
def reconcile(reg, base, now, krw_quantum=D(0)):
    res = Result()
    entries, reg_status = load_registry(reg, res)
    obs = {'base': validate_observation('base', base, res), 'now': validate_observation('now', now, res)}
    out = {'registry_status': reg_status, 'observation': obs, 'observation_stable': False}
    if reg_status == 'unreadable' or any(v != 'ok' for v in obs.values()):
        # 읽지 못한 등록표·구조를 믿을 수 없거나 조회가 실패한 관측으로는 어떤 계산·이상 판정도 내지 않는다(전부 보류).
        # (등록표를 못 읽은 것을 '모든 주문이 미등록'으로 단정하지 않는다. 내용이 잘못된 등록표는 이상으로 남는다.)
        return finish(res, out, None)
    # v5: 관측별 시계 관계
    cb, cn = build_clock('base', base), build_clock('now', now)
    pair = [cb, cn]
    eff_axis = reg.get('effective_axis') if reg_status == 'valid' else None
    out['clock'] = {'effective_axis': eff_axis, 'base': cb.report, 'now': cn.report,
                    'same_source_both': sorted(list(p) for p in static_of(pair))}
    stable = check_stability('base', base, res, cb) & check_stability('now', now, res, cn)
    reg_src = reg.get('observation_source') if reg_status == 'valid' else None
    stable &= check_pair(base, now, reg_src, res)
    out['observation_stable'] = stable

    # 코드 출처 — 외부 수집 메타데이터(배포 이미지/커밋 기록)로만 확인한다. DB 에는 세션별 코드 SHA 가 없다.
    app0, app1 = base['meta'].get('app_code_sha'), now['meta'].get('app_code_sha')
    if app1 is None:
        res.hold('CODE_SOURCE_UNCONFIRMED', blocks=(), detail='현재 관측의 app_code_sha 없음 — 등록 code_sha 대조 불가')
    elif app0 is not None and not sha_eq(app0, app1):
        res.hold('CODE_CHANGED_IN_WINDOW', blocks=(), base=app0, now=app1, detail='구간 중 배포 코드 변경 — 기준을 새로 잡아야 함')
    elif app0 is None:
        res.hold('CODE_SOURCE_UNCONFIRMED', blocks=(), detail='기준 관측의 app_code_sha 없음 — 구간 중 코드 변경 여부 미확인')
    out['code'] = {'base': app0, 'now': app1, 'base_ref': base['meta'].get('app_code_sha_ref'), 'now_ref': now['meta'].get('app_code_sha_ref')}

    db0, db1 = base['db'], now['db']
    t0 = tstamp(db0['observed_at'])
    s0 = {(s['kind'], s['id']): s for s in db0['sessions']}
    s1 = {(s['kind'], s['id']): s for s in db1['sessions']}
    o0 = {o['id']: o for o in db0['orders']}
    o1 = {o['id']: o for o in db1['orders']}

    # 세션 등록 대조
    registered = {}
    for key, e in entries.items():
        s = s1.get(key)
        if s is None:
            res.anomaly('REGISTERED_SESSION_ROW_MISSING', session=list(key))
            registered[key] = e   # 🔴 행이 사라져도 그 주문은 계속 센다
            continue
        d, _unk = contract_diff(e, s, app1, e.get('effective_axis', eff_axis), pair, res)
        if d:
            res.anomaly('REGISTRY_CONTRACT_MISMATCH', session=list(key), detail=d)
        else:
            registered[key] = e
    for key, s in s1.items():
        if is_paper(s) or key in registered:
            continue
        if s.get('status') != 'STOPPED':
            res.anomaly('UNREGISTERED_REAL_SESSION_ACTIVE', session=list(key), status=s.get('status'), mode=s.get('mode'))

    # 주문 분류 — 미확정 실자금 주문은 그 코인·KRW 계산만 막는다
    win = {}
    unrec_sessions = set()   # v4: 상태/종류 미확인 주문이 있는 세션 — 그 세션 장부 대조만 보류
    for oid, o in o1.items():
        key = (o['session_kind'], o['session_id'])
        s = s1.get(key)
        if is_paper(s):
            continue
        prev = o0.get(oid)
        changed = prev is None or any(prev.get(k) != o.get(k) for k in
                                      ('state', 'filled_quantity', 'applied_executed_funds', 'applied_paid_fee', 'fill_accounting_pending'))
        e = registered.get(key)
        created = tstamp(o['created_at'])
        in_window = e is not None
        if e is not None:
            # v5: 주문 생성(앱 축)과 등록 적용 구간(선언 축)을 범위로 비교. 미확정이면 등록 여부만 보류하고 계산에는 넣는다.
            f0, ax = tstamp(e['effective_from']), e.get('effective_axis', eff_axis)
            r_from = le3(f0, ax, created, 'app', pair)
            r_to = True if e['effective_to'] is None else le3(created, 'app', tstamp(e['effective_to']), ax, pair)
            in_window = r_from is not False and r_to is not False
            if in_window and (changed or unsettled(o['state'])):
                if r_from is None:
                    clock_hold(res, 'order_created_after_effective_from', f0, ax, created, 'app', pair, order=oid, session=list(key))
                if r_to is None:
                    clock_hold(res, 'order_created_before_effective_to', created, 'app', tstamp(e['effective_to']), ax, pair,
                               order=oid, session=list(key))
        if (changed or unsettled(o['state'])) and not in_window:
            res.anomaly('UNREGISTERED_REAL_ORDER', order=oid, session=list(key), state=o['state'],
                        coin=o['coin_pair'], side=o['side'], session_row='없음' if s is None else s.get('status'))
        cur = coin_of(o)
        if o['state'] == 'UNKNOWN':
            res.anomaly('ORDER_UNKNOWN', order=oid, session=list(key))
        elif o['state'] in ACTIVE:
            res.hold('ORDER_IN_FLIGHT', blocks=('KRW', cur), order=oid, session=list(key), state=o['state'], created_at=o['created_at'])
        elif o['state'] not in ORDER_STATES:
            unrec_sessions.add(key)
            res.hold('ORDER_STATE_UNRECOGNIZED', blocks=('KRW', cur), order=oid, session=list(key), state=o['state'],
                     detail='제품 주문 상태 집합 밖 — 정산 완료로 계산하지 않음')
        if o['order_type'] not in ORDER_TYPES:
            unrec_sessions.add(key)
            res.hold('ORDER_TYPE_UNRECOGNIZED', blocks=('KRW', cur), order=oid, session=list(key), order_type=o['order_type'],
                     detail='주문 종류 MARKET/LIMIT 밖 — 예약 금액·요청 상한 규칙을 확정할 수 없음')
        fq = dec(o.get('filled_quantity')) or D(0)
        if fq > 0:
            if o.get('fill_accounting_pending'):
                res.hold('ACCOUNTING_PENDING', blocks=('KRW', cur), order=oid, session=list(key))
            elif None in (o.get('reported_executed_funds'), o.get('reported_paid_fee'),
                          o.get('applied_executed_funds'), o.get('applied_paid_fee')):
                res.hold('SETTLEMENT_UNCONFIRMED', blocks=('KRW', cur), order=oid, session=list(key))
            elif dec(o['reported_executed_funds']) != dec(o['applied_executed_funds']) or \
                    dec(o['reported_paid_fee']) != dec(o['applied_paid_fee']):
                res.anomaly('SETTLEMENT_MISMATCH', order=oid, session=list(key))
        if (changed or unsettled(o['state'])) and not order_effects(o)[4]:
            res.hold('ORDER_EFFECT_PENDING', blocks=('KRW', cur), order=oid, session=list(key), state=o['state'],
                     detail='구간 주문의 반영 미확정 — 이 통화와 KRW 계산만 보류')
        if changed:
            win[oid] = (o, prev, key, in_window)
    for d in db1.get('unresolved_discrepancies') or []:
        res.anomaly('SETTLEMENT_DISCREPANCY_UNRESOLVED', id=d.get('id'), order=d.get('order_id'), kind=d.get('kind'))

    # 포지션 — CLOSING 은 그 포지션의 보류 항목일 뿐, 확정 주문으로 계산한 잔차·담보를 가리지 않는다.
    #  (매도 진행 중이면 그 주문이 ORDER_IN_FLIGHT/ORDER_EFFECT_PENDING 으로 해당 통화를 막는다)
    p1 = db1['positions']
    for p in p1:
        key = (p['session_kind'], p['session_id'])
        if is_paper(s1.get(key)):
            continue
        if key not in registered:
            res.anomaly('UNREGISTERED_REAL_POSITION', position=p['id'], session=list(key), coin=p['coin_pair'])
        if p['status'] == 'CLOSING':
            res.hold('POSITION_CLOSING', blocks=(), position=p['id'], session=list(key), coin=p['coin_pair'])
        elif p['status'] not in POSITION_STATUSES:
            res.hold('POSITION_STATUS_UNRECOGNIZED', blocks=(coin_of(p),), position=p['id'], session=list(key), status=p['status'],
                     detail='포지션 상태 OPEN/CLOSING/CLOSED 밖 — 그 코인 계산 보류')

    # 거래소 ↔ DB 주문 대응(양방향)
    ea = now['exchange_after']
    db_uuids = {o['exchange_order_id'] for o in o1.values() if o.get('exchange_order_id')}
    flows_raw = now.get('external_flows')
    declared_manual = {f['ref'] for f in (flows_raw or []) if f['kind'] == 'MANUAL_TRADE'}
    for u in list(ea['done_order_ids']) + list(ea['open_orders']):
        if u not in db_uuids and u not in declared_manual:
            res.anomaly('EXCHANGE_ORDER_NOT_IN_DB', uuid=u)
    coverage = {'query': ea.get('done_query'), 'checked': [], 'gaps': []}
    q = ea.get('done_query')
    need = []
    for oid, (o, prev, key, in_window) in win.items():
        fq = dec(o.get('filled_quantity')) or D(0)
        if fq > 0 and o['state'] in SETTLED:
            need.append(o)
    unconfirmed = []
    for o in need:
        u = o.get('exchange_order_id')
        if not u:
            res.anomaly('DB_FILL_WITHOUT_EXCHANGE_ID', order=o['id'], session=[o['session_kind'], o['session_id']])
            continue
        if q is None or not q['complete']:
            unconfirmed.append(o['id'])
            continue
        # v5: 거래소 주문 생성 시각을 DB 의도 생성 시각과 같다고 가정하지 않는다. 의도 생성(앱 축) 뒤, 현재 DB 추출(db 축,
        #     그 uuid 를 이미 기록) 전에 생겼으므로 조회 기간이 그 범위를 덮는지 본다.
        oc, t1x = tstamp(o['created_at']), tstamp(now['db']['observed_at'])
        qf, qt = tstamp(q['from']), tstamp(q['to'])
        r_lo, r_hi = le3(qf, 'exchange', oc, 'app', pair), le3(t1x, 'db', qt, 'exchange', pair)
        if r_lo is None or r_hi is None:
            unk_ids = coverage.setdefault('clock_unconfirmed', [])
            unk_ids.append(o['id'])
            if r_lo is None:
                clock_hold(res, 'exchange_query_from_before_order', qf, 'exchange', oc, 'app', pair, order=o['id'])
            if r_hi is None:
                clock_hold(res, 'exchange_query_to_after_db_extract', t1x, 'db', qt, 'exchange', pair, order=o['id'])
            if r_lo is not False and r_hi is not False:
                continue
        if r_lo is False or r_hi is False:
            coverage['gaps'].append(o['id'])
            res.hold('EXCHANGE_COVERAGE_GAP', blocks=(), order=o['id'], uuid=u, created_at=o['created_at'],
                     query_from=q['from'], query_to=q['to'], detail='조회 기간 밖 — 누락/미등록으로 단정하지 않음')
            continue
        coverage['checked'].append(o['id'])
        if u not in ea['done_order_ids']:
            res.anomaly('DB_FILL_NOT_ON_EXCHANGE', order=o['id'], uuid=u, detail='완료로 확인된 조회 기간 안인데 거래소 완료 목록에 없음')
    if unconfirmed:
        res.hold('EXCHANGE_COVERAGE_UNCONFIRMED', blocks=(), orders=unconfirmed,
                 detail='거래소 완료 주문 조회 범위/완료 여부 미확인' if q is None else '거래소 완료 주문 조회가 완료되지 않음(페이지 남음)')
    # v4(총괄 U1): 거래소→DB 방향은 새 DB 체결 유무와 무관하게 비교 구간 전체의 조회 범위·완료를 독립 확인한다.
    #   DB 에 새 체결이 없다는 것으로 거래소의 무거래를 증명하지 않는다.
    #   구간 = [기준 관측의 거래소 앞 읽기, 현재 관측의 거래소 뒤 읽기]. 조회가 이 구간을 덮고 완료여야 '일치'.
    #   (조회 기간은 거래소 주문 생성 시각 기준 — DB 의도 생성 시각과 같다고 가정하지 않으므로 끝은 거래소 뒤 읽기 시각까지 요구)
    wf = step_bounds(base, 'exchange_before')[0]
    # 끝 = 현재 관측 거래소 뒤 읽기 시작(조회 요청은 그 단계 안에서 나가므로 요청 시각 ≥ 시작). v4 점 형식에서는 v4 와 같은 값.
    wt = step_bounds(now, 'exchange_after')[0]
    w_from, w_to = wf.isoformat().replace('+00:00', 'Z'), wt.isoformat().replace('+00:00', 'Z')
    clock_unk = False
    if q is None:
        why = '조회 범위 없음'
    elif not q['complete']:
        why = '조회 미완료(페이지 남음)'
    else:
        r_lo = le3(tstamp(q['from']), 'exchange', wf, 'collector', pair)
        r_hi = le3(wt, 'collector', tstamp(q['to']), 'exchange', pair)
        if r_lo is False or r_hi is False:
            why = '조회 기간이 비교 구간을 덮지 않음'
        elif r_lo is None or r_hi is None:
            why, clock_unk = '거래소 시계와 수집기 시계의 관계 미확인 — 조회 기간이 비교 구간을 덮는지 확정 불가', True
            if r_lo is None:
                clock_hold(res, 'exchange_query_from_before_window', tstamp(q['from']), 'exchange', wf, 'collector', pair)
            if r_hi is None:
                clock_hold(res, 'exchange_query_to_after_window', wt, 'collector', tstamp(q['to']), 'exchange', pair)
        else:
            why = None
    coverage['window'] = {'from': w_from, 'to': w_to, 'status': '보류' if why else '일치', 'reason': why}
    if why and not clock_unk:
        res.hold('EXCHANGE_WINDOW_UNCONFIRMED', blocks=(), window_from=w_from, window_to=w_to,
                 query=None if q is None else {k: q[k] for k in ('from', 'to', 'complete')},
                 detail=why + ' — 거래소 쪽 미등록 주문 없음을 확인하지 못함')
    out['exchange_coverage'] = coverage

    # 세션별 규칙(전략·엔진별로 등록표가 정한다 — 단독 규칙을 그대로 복사하지 않는다)
    for key, e in registered.items():
        r = e['rules']
        mine = [o for o in o1.values() if (o['session_kind'], o['session_id']) == key]
        win_mine, win_unk = [], set()
        for o in mine:
            r_w = le3(t0, 'db', tstamp(o['created_at']), 'app', pair)
            if r_w is not False:
                win_mine.append(o)
            if r_w is None:
                win_unk.add(o['id'])

        def rule_hit(code, orders, **kw):
            # v5: 구간 안이 확정된 주문만으로 위반이면 이상, 구간 여부가 미확정인 주문이 있어야만 위반이면 보류
            if all(i in win_unk for i in orders):
                res.hold('CLOCK_AXIS_UNCONFIRMED', blocks=(), check='rule_window', rule=code, orders=orders, session=list(key),
                         reason=why3(t0, 'db', t0, 'app', pair), detail='주문이 기준 관측 뒤인지 확정되지 않아 규칙 위반을 확정하지 않음', **kw)
            else:
                res.anomaly(code, session=list(key), **kw)
        if e['kind'] == 'LIVE':
            for o in win_mine:
                if o['coin_pair'] != e['coin_pair']:
                    rule_hit('RULE_COIN_OUT_OF_CONTRACT', [o['id']], order=o['id'], coin=o['coin_pair'])
        if 'max_filled_buys_in_window' in r:
            fb = [o['id'] for o in win_mine if o['side'] == 'BUY' and (dec(o.get('filled_quantity')) or 0) > 0]
            sure = [i for i in fb if i not in win_unk]
            if len(sure) > r['max_filled_buys_in_window']:
                res.anomaly('RULE_FILLED_BUYS', session=list(key), count=len(sure), limit=r['max_filled_buys_in_window'])
            elif len(fb) > r['max_filled_buys_in_window']:
                rule_hit('RULE_FILLED_BUYS', [i for i in fb if i in win_unk], count=len(fb), limit=r['max_filled_buys_in_window'])
        if 'max_open_positions' in r:
            n = sum(1 for p in p1 if (p['session_kind'], p['session_id']) == key)
            if n > r['max_open_positions']:
                res.anomaly('RULE_OPEN_POSITIONS', session=list(key), count=n, limit=r['max_open_positions'])
        for o in win_mine:
            if o['side'] == 'BUY' and o['coin_pair'] in r.get('excluded_coins', []):
                rule_hit('RULE_EXCLUDED_COIN_BUY', [o['id']], order=o['id'], coin=o['coin_pair'])
            if 'max_buy_request_krw' in r and o['side'] == 'BUY' and o['order_type'] == 'MARKET' and \
                    dec(o['quantity']) > num(r['max_buy_request_krw']):
                rule_hit('RULE_BUY_REQUEST_CAP', [o['id']], order=o['id'], quantity=o['quantity'])
        per_pos = {}
        for o in mine:
            if o['side'] == 'BUY' and (dec(o.get('filled_quantity')) or 0) > 0 and o.get('position_id') is not None:
                per_pos.setdefault(o['position_id'], []).append(o['id'])
        for pid, ids in per_pos.items():
            if len(ids) > 1:
                res.anomaly('RULE_DUP_BUY_POSITION', session=list(key), position=pid, orders=ids)

    # 외부 입출금 — 이력이 없으면 증감식(KRW·코인)만 보류. 실보유 담보 검사는 이력과 무관하므로 막지 않는다.
    if flows_raw is None:
        res.hold('EXTERNAL_FLOWS_UNCONFIRMED', blocks=('DELTA',), detail='구간 외부 입출금 이력이 입력되지 않음')
    ext = {}
    for f in flows_raw or []:
        ext[f['currency']] = ext.get(f['currency'], D(0)) + num(f['amount'])
    flows_blocked = flows_raw is None

    ex_sum, led_sum, explained = D(0), D(0), D(0)
    coin_net, per_session = {}, {}
    for oid, (o, prev, key, in_window) in win.items():
        if not in_window:
            continue
        ex1, led1, q1, rnd1, fin1 = order_effects(o)
        ex0, led0, q0, rnd0, fin0 = order_effects(prev) if prev else (D(0),) * 4 + (True,)
        ex_sum += ex1 - ex0
        led_sum += led1 - led0
        explained += rnd1 - rnd0
        cur = coin_of(o)
        coin_net[cur] = coin_net.get(cur, D(0)) + (q1 - q0)
        per_session[key] = per_session.get(key, D(0)) + (led1 - led0)
    acc0, acc1 = base['exchange_after']['accounts'], ea['accounts']
    dk = total(acc1, 'KRW') - total(acc0, 'KRW')
    raw = dk - ext.get('KRW', D(0)) - led_sum
    adjusted = raw - explained
    krw_blk = res.blocked('KRW') or flows_blocked
    krw_ok = abs(adjusted) <= krw_quantum
    account = {
        'krw': {'exchange_delta': str(dk), 'external': str(ext.get('KRW', D(0))), 'session_ledger_delta': str(led_sum),
                'raw_residual': str(raw), 'explained_rounding': str(explained), 'adjusted_residual': str(adjusted),
                'quantum': str(krw_quantum), 'status': status(krw_blk, krw_ok)},
        'coins': {}, 'base_holdings': {}, 'internal_allocations': [], 'sessions': {}, 'backing': {},
    }
    if not krw_blk and not krw_ok:
        res.anomaly('ACCOUNT_KRW_MISMATCH', adjusted_residual=str(adjusted), raw_residual=str(raw))
    for cur in sorted(set(acc0) | set(acc1)):
        if cur != 'KRW':
            account['base_holdings'][cur] = {'amount': str(total(acc0, cur)), 'source': 'base.exchange_after'}
    pos_delta = {}
    for snap, sign in ((db0, -1), (db1, 1)):
        for p in snap['positions']:
            if (p['session_kind'], p['session_id']) in registered:
                cur = p['coin_pair'].split('-', 1)[1]
                pos_delta[cur] = pos_delta.get(cur, D(0)) + sign * dec(p['size'])
    bad_coins = {}
    for cur in sorted((set(acc0) | set(acc1) | set(coin_net) | set(pos_delta)) - {'KRW'}):
        de = total(acc1, cur) - total(acc0, cur) - ext.get(cur, D(0))
        cn, pd = coin_net.get(cur, D(0)), pos_delta.get(cur, D(0))
        ok = de == cn == pd
        blk = res.blocked(cur) or flows_blocked
        account['coins'][cur] = {'exchange_delta_minus_external': str(de), 'net_filled': str(cn),
                                 'open_position_delta': str(pd), 'match': ok, 'status': status(blk, ok)}
        if not blk and not ok:
            bad_coins[cur] = account['coins'][cur]
    if bad_coins:
        res.anomaly('ACCOUNT_COIN_MISMATCH', detail=bad_coins)
    # 세션 장부: Δavailable = Σ주문 장부 증감 (새 세션의 기준 = 초기 배정, 내부 이전). 관측 전체 보류일 때만 막는다.
    for key in registered:
        s_now, s_base = s1.get(key), s0.get(key)
        if s_now is None:
            continue
        if s_base is None:
            start = dec(s_now['initial_capital'])
            account['internal_allocations'].append({'session': list(key), 'allocated': str(start), 'note': '구간 안 새 배정 — 거래소 유입 아님'})
        else:
            start = dec(s_base['available_krw'])
        got = dec(s_now['available_krw']) - start
        want = per_session.get(key, D(0))
        blk = res.blocked(ALL) or key in unrec_sessions
        account['sessions'][f'{key[0]}:{key[1]}'] = {'available_delta': str(got), 'order_ledger_delta': str(want), 'status': status(blk, got == want)}
        if not blk and got != want:
            res.anomaly('SESSION_LEDGER_MISMATCH', session=list(key), available_delta=str(got), order_ledger_delta=str(want))
    # 보유 뒷받침: 거래소 실보유가 등록 세션의 열린 포지션 수량·세션 현금을 덮지 못하면 이상이다(입출금 이력과 무관).
    reg_open = {}
    for p in p1:
        if (p['session_kind'], p['session_id']) in registered:
            cur = p['coin_pair'].split('-', 1)[1]
            reg_open[cur] = reg_open.get(cur, D(0)) + dec(p['size'])
    need_krw = sum((dec(s1[k]['available_krw']) for k in registered if k in s1), D(0))
    for cur, need_amt in sorted(reg_open.items()) + [('KRW', need_krw)]:
        have = total(acc1, cur)
        blk = res.blocked(cur)
        account['backing'][cur] = {'exchange_total': str(have), 'registered_need': str(need_amt), 'status': status(blk, have >= need_amt)}
        if not blk and have < need_amt:
            res.anomaly('ACCOUNT_KRW_UNBACKED' if cur == 'KRW' else 'ACCOUNT_COIN_UNBACKED', currency=cur, detail=account['backing'][cur])
    out['account_sum'] = account_sum(reg, reg_status, registered, s1, o1, p1, acc1, res, pair, tstamp(db1['observed_at']), krw_quantum)
    return finish(res, out, account)


def account_sum(reg, reg_status, registered, s1, o1, p1, acc1, res, pair, t_now, q):
    """v6 계좌 합산(A1/A2) — 현재 관측 한 시점. 상태: 일치/불일치/보류. v5 의 계산·판정은 건드리지 않는다."""
    if reg_status != 'valid':   # REGISTRY_INVALID 이상이 이미 있음 — 합산하지 않는다
        return {'status': '불일치', 'reason': '등록표 무효 — 합산하지 않음'}
    if reg.get('registry_version') != REGISTRY_V3:
        res.hold('ACCOUNT_SUM_UNAVAILABLE', blocks=(), detail=f'등록표 {REGISTRY_V3} 아님 — 미배정 현금 기준·자금 이벤트 없음(0·기본 시각으로 채우지 않음)')
        return {'status': '보류', 'reason': 'registry-3 아님'}
    unreg = sorted({a['code'] for a in res.anomalies if a['code'] in (
        'UNREGISTERED_REAL_SESSION_ACTIVE', 'UNREGISTERED_REAL_ORDER', 'UNREGISTERED_REAL_POSITION',
        'REGISTERED_SESSION_ROW_MISSING', 'REGISTRY_CONTRACT_MISMATCH')})
    if unreg:
        return {'status': '불일치', 'reason': f'등록 밖 실자금/등록 계약 이상 {unreg} — 합산을 맞추지 않음'}
    blk, contra = set(), set()

    def hold(code, cur, **kw):
        blk.add(cur)
        res.hold(code, blocks=(), currency=cur, **kw)

    for key in sorted(registered):
        rs = registered[key]['ruleset']
        if rs['confirmed'] is None:
            res.hold('RULESET_UNCONFIRMED', blocks=(), session=list(key), expected=rs['expected'],
                     detail='세션/포지션에서 확인한 지문·연결 근거 없음 — ruleset_snapshot 전역 원문만으로 확정하지 않음')
        elif rs['expected'] is not None and rs['confirmed'] != rs['expected']:
            res.anomaly('RULESET_MISMATCH', session=list(key), expected=rs['expected'], confirmed=rs['confirmed'])
    # v7: 기준 시각이 현재 관측 이전인지(그 축 → db 범위 판정)
    base_cur = {'cash_base': {'KRW'}, 'holdings_base': set(reg['holdings_base']['coins']) | {c for c in acc1 if c != 'KRW'}}
    for name in ('cash_base', 'holdings_base'):
        b = reg[name]
        r_b = le3(tstamp(b['base_at']), b['axis'], t_now, 'db', pair)
        if r_b is False:
            res.anomaly('ACCOUNT_SUM_BASE_IN_FUTURE', base=name, base_at=b['base_at'], observed_at=t_now.isoformat(),
                        detail='기준값 시각이 현재 관측 뒤로 확정 — 기준으로 쓰지 않음')
            contra |= base_cur[name]
        elif r_b is None:
            for cur in sorted(base_cur[name]):
                hold('ACCOUNT_SUM_BASE_TIME_UNCONFIRMED', cur, base=name, detail='기준값 시각과 현재 관측의 선후 불명')
    # 미배정 현금·비세션 보유 = 기준 + 기준 뒤 이벤트
    unalloc = {'KRW': num(reg['cash_base']['amount_krw'])}
    for c, v in reg['holdings_base']['coins'].items():
        unalloc[c] = num(v)
    events = {'applied': [], 'in_base': [], 'after_observation': [], 'held': []}
    for e in reg['capital_events']:
        cur = e['currency']
        b = reg['cash_base'] if cur == 'KRW' else reg['holdings_base']
        at, ax, bat, bax = tstamp(e['applied_at']), e['axis'], tstamp(b['base_at']), b['axis']
        if e['ref'] in b['includes_event_refs']:
            r_n = le3(at, ax, t_now, 'db', pair)   # v7: 기준에 든 이벤트도 현재 관측 이전이어야 한다
            if r_n is False:
                res.anomaly('ACCOUNT_SUM_INCLUDED_EVENT_AFTER_OBSERVATION', ref=e['ref'], detail='기준 포함 선언 이벤트가 현재 관측 뒤로 확정')
                contra.add(cur)
            elif r_n is None:
                hold('ACCOUNT_SUM_EVENT_TIME_UNCONFIRMED', cur, ref=e['ref'], detail='기준 포함 이벤트의 현재 관측 전후 불명')
            r = le3(at, ax, bat, bax, pair)
            if r is False:
                res.anomaly('ACCOUNT_SUM_BASE_EVENT_CONTRADICTION', ref=e['ref'], detail='기준에 포함으로 선언했는데 적용이 기준 뒤로 확정')
                contra.add(cur)
            elif r is None:
                hold('ACCOUNT_SUM_BASE_BOUNDARY', cur, ref=e['ref'], detail='기준 포함 선언 이벤트의 기준 선후 불명')
            events['in_base'].append(e['ref'])
            continue
        after_base = le3(bat, bax, at, ax, pair) if not (ax == bax and at == bat) else None
        if after_base is not True or le3(at, ax, bat, bax, pair) is True:
            # 기준 이전이거나 같은 시각·선후 불명인데 기준 포함 여부가 선언되지 않음 → 그 통화 합산 보류(빼지도 더하지도 않음)
            hold('ACCOUNT_SUM_BASE_BOUNDARY', cur, ref=e['ref'], detail='기준 이전/같은 시각/불명 경계인데 includes_event_refs 에 없음')
            events['held'].append(e['ref'])
            continue
        r_now = le3(at, ax, t_now, 'db', pair)
        if r_now is False:
            events['after_observation'].append(e['ref'])
            continue
        if r_now is None:
            hold('ACCOUNT_SUM_EVENT_TIME_UNCONFIRMED', cur, ref=e['ref'], detail='현재 관측 전후 불명')
            events['held'].append(e['ref'])
            continue
        unalloc[cur] = unalloc.get(cur, D(0)) + EVENT_TYPES[e['type']] * num(e['amount'])
        events['applied'].append(e['ref'])
    need = {'KRW': sum((dec(s1[k]['available_krw']) for k in registered if k in s1), D(0))}
    reserved = D(0)
    for o in o1.values():
        key = (o['session_kind'], o['session_id'])
        if key not in registered or o['side'] != 'BUY' or o['state'] not in ACTIVE:
            continue
        fq = dec(o.get('filled_quantity')) or D(0)
        if fq > 0:
            hold('ACCOUNT_SUM_PARTIAL_RESERVATION', 'KRW', order=o['id'], detail='부분 체결 예약 — 남은 예약·포지션 중복 계산 위험')
            blk.add(coin_of(o))
        elif o.get('reserved_krw') is None:
            hold('ACCOUNT_SUM_RESERVATION_UNKNOWN', 'KRW', order=o['id'])
        else:
            reserved += dec(o['reserved_krw'])
    need['KRW'] += reserved
    for p in p1:
        key = (p['session_kind'], p['session_id'])
        if key not in registered:
            continue
        cur = coin_of(p)
        if p['status'] == 'OPEN':
            need[cur] = need.get(cur, D(0)) + dec(p['size'])
        elif p['status'] == 'CLOSING':
            hold('ACCOUNT_SUM_POSITION_CLOSING', cur, position=p['id'], detail='CLOSING 보유량은 매도 진행분과 겹칠 수 있어 합산 보류')
    for cur, v in unalloc.items():
        if v < 0 and cur not in blk and cur not in contra:
            res.anomaly('ACCOUNT_SUM_NEGATIVE_UNALLOCATED', currency=cur, amount=str(v), detail='미배정 잔액이 음수 — 불가능한 잔액')
            contra.add(cur)
        need[cur] = need.get(cur, D(0)) + v
    rows, bad = {}, {}
    for cur in sorted(set(need) | (set(acc1))):
        have, want = total(acc1, cur), need.get(cur, D(0))
        ok = abs(have - want) <= q if cur == 'KRW' else have == want
        b = cur in blk or res.blocked(cur)
        rows[cur] = {'exchange_total': str(have), 'sessions_plus_unallocated': str(want),
                     'status': '불일치' if cur in contra else status(b, ok)}   # 기준 모순은 이미 이상으로 보고
        if cur == 'KRW':
            rows[cur].update(reserved_buys=str(reserved), unallocated=str(unalloc['KRW']))
        if not b and not ok and cur not in contra:
            bad[cur] = rows[cur]
    if bad:
        res.anomaly('ACCOUNT_SUM_MISMATCH', detail=bad)
    sts = [r['status'] for r in rows.values()]
    return {'status': '불일치' if '불일치' in sts else ('보류' if '보류' in sts else '일치'), 'currencies': rows, 'events': events}


def finish(res, out, account):
    if account is not None:
        sts = [account['krw']['status']] + [v['status'] for k in ('coins', 'sessions', 'backing') for v in account[k].values()]
        account['verdict'] = '불일치' if '불일치' in sts else ('보류' if '보류' in sts else '일치')
    out['account'] = account
    out['anomalies'], out['holds'] = res.anomalies, res.holds
    out['overall'] = 'ANOMALY' if res.anomalies else ('HOLD' if res.holds else 'OK')
    # 일관성: 계좌 항목이 불일치면 이상이 있어야 하고, 보류면 보류 사유가 있어야 한다.
    if account is not None:
        if account['verdict'] == '불일치' and not res.anomalies:
            raise AssertionError('계좌 불일치인데 이상 없음')
        if account['verdict'] == '보류' and not res.holds:
            raise AssertionError('계좌 보류인데 보류 사유 없음')
    sm = out.get('account_sum')
    if sm is not None:   # v6 — 합산 결과도 같은 일관성
        if sm['status'] == '불일치' and not res.anomalies:
            raise AssertionError('계좌 합산 불일치인데 이상 없음')
        if sm['status'] == '보류' and not res.holds:
            raise AssertionError('계좌 합산 보류인데 보류 사유 없음')
    return {k: out[k] for k in ('overall', 'registry_status', 'observation', 'observation_stable', 'anomalies', 'holds',
                                'account', 'account_sum', 'code', 'exchange_coverage', 'clock') if k in out}


def evaluate(reg, base, now, krw_quantum=D(0)):
    """예외를 성공/무출력으로 가리지 않는다 — 구조화된 ERROR 로 돌려준다."""
    try:
        return reconcile(reg, base, now, krw_quantum)
    except Exception as e:  # noqa: BLE001
        return {'overall': 'ERROR', 'error': type(e).__name__, 'message': str(e), 'anomalies': [], 'holds': []}


# ── 파일 입력 ─────────────────────────────────────────────────
def _no_dup(pairs):
    seen = {}
    for k, v in pairs:
        if k in seen:
            raise Bad(f'JSON 키 중복 {k!r}')
        seen[k] = v
    return seen


def _no_const(c):
    raise Bad(f'JSON 비표준 숫자 {c}')


def load_file(path):
    """읽기 실패(unreadable)와 내용 결함(invalid: 문법·키 중복·NaN/Infinity·실수형)을 구별한다."""
    # v4(총괄 U3): 바이트 읽기(OSError → 읽기 실패)와 UTF-8 디코딩(→ 내용 결함)을 나눈다. v3 는 디코딩 오류가 밖으로 나가 무출력 rc1 이었다.
    #   디코딩 오류 설명에는 위치만 적고 입력 바이트·원문은 출력하지 않는다.
    try:
        with open(path, 'rb') as f:
            raw = f.read()
    except OSError as e:
        return {'__load_error__': ('unreadable', f'{type(e).__name__}: {e}')}
    try:
        text = raw.decode('utf-8')
    except UnicodeDecodeError as e:
        return {'__load_error__': ('invalid', f'UTF-8 디코딩 실패(바이트 위치 {e.start}, 길이 {len(raw)})')}
    try:
        return json.loads(text, object_pairs_hook=_no_dup, parse_constant=_no_const, parse_float=lambda s: _no_const(s))
    except (Bad, json.JSONDecodeError) as e:
        return {'__load_error__': ('invalid', f'{type(e).__name__}: {e}')}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--registry', required=True)
    ap.add_argument('--base', required=True)
    ap.add_argument('--now', required=True)
    ap.add_argument('--krw-quantum', default='0')
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    try:
        q = num(a.krw_quantum, D(0))
    except Bad as e:
        out = {'overall': 'ERROR', 'error': 'Bad', 'message': f'--krw-quantum {e}', 'anomalies': [], 'holds': []}
    else:
        try:
            out = evaluate(load_file(a.registry), load_file(a.base), load_file(a.now), q)
        except Exception as e:  # noqa: BLE001 — 파일 단계의 예상 밖 예외도 무출력 대신 구조화된 ERROR
            out = {'overall': 'ERROR', 'error': type(e).__name__, 'message': '파일 입력 단계 예외', 'anomalies': [], 'holds': []}
    print(json.dumps(out, ensure_ascii=False, indent=1, default=str))
    return EXIT[out['overall']]


if __name__ == '__main__':
    sys.exit(main())
