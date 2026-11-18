"""P7 v3 파일 시험 — 저장된 v2 35시나리오 입력(체크섬 확인) + 총괄 v1.187 반례7개와 경계 변형.
PG·네트워크·DB 접속 없음. v2 원문은 읽기만 하고, 변환한 입력·결과는 이 회차의 out 경로에 새로 쓴다.

사용: python -I cases.py --impl <reconcile.py> --out <폴더>
  같은 시험을 v2 대조기(--impl ../../dec-reconcile-20261118/p7v2/reconcile.py)에 먼저 돌려 실패를 고정한 뒤 v3 에 돌린다.
종료값: 전부 통과 0, 하나라도 실패 1.
"""
import argparse, copy, hashlib, importlib.util, io, json, os, subprocess, sys
from contextlib import redirect_stdout
from datetime import datetime, timedelta
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
V2 = os.path.normpath(os.path.join(HERE, '..', '..', 'dec-reconcile-20261118'))
SRC = {'environment': 'local-isolated', 'db': 'cat-rt-pg/p7v2-scenario-copy', 'exchange_account': 'fake-exchange-fixed-balances'}
APP_SHA = '0e1d2a02'
APP_SHA_FULL = '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d'
SHA_REF = '로컬 가정: v2 시나리오의 등록 예시값. 운영 관측 출처 아님'


def iso(t):
    return t.isoformat().replace('+00:00', 'Z')


def at(s, ms):
    return iso(datetime.fromisoformat(s.replace('Z', '+00:00')) + timedelta(milliseconds=ms))


# ── v2 저장 입력 → v3 입력(합성 수집 메타데이터) ───────────────
def v2_inputs():
    sums = {}
    for line in open(os.path.join(V2, 'SHA256SUMS'), encoding='utf-8'):
        h, p = line.split(None, 1)
        sums[p.strip().lstrip('*')] = h
    out = {}
    for fn in sorted(os.listdir(os.path.join(V2, 'p7v2', 'out'))):
        rel = f'p7v2/out/{fn}'
        raw = open(os.path.join(V2, rel), 'rb').read()
        if hashlib.sha256(raw).hexdigest() != sums[rel]:
            raise SystemExit(f'v2 원문 체크섬 불일치 {rel}')
        out[fn[:-5]] = (json.loads(raw.decode('utf-8')), sums[rel])
    return out


def adapt(d):
    """v2 저장 입력에 v3 필수 메타데이터를 덧붙인다. 원래 값은 바꾸지 않는다."""
    reg = copy.deepcopy(d['scenario']['reg'])
    reg['observation_source'] = dict(SRC)
    for e in reg.get('sessions', []):
        if isinstance(e, dict):
            e.setdefault('session_created_at', None)
    obs = {}
    for name in ('base', 'now'):
        ob = copy.deepcopy(d[name])
        t = ob['db']['observed_at']
        ob['meta'] = {'source': dict(SRC), 'app_code_sha': APP_SHA, 'app_code_sha_ref': SHA_REF,
                      'steps': [{'step': s, 'at': at(t, ms)} for s, ms in
                                (('db_fp_pre', -2), ('exchange_before', -1), ('db_extract', 0), ('exchange_after', 1), ('db_fp_post', 2))]}
        for side, ms in (('exchange_before', -1), ('exchange_after', 1)):
            if ob.get(side) is not None:
                ob[side]['done_query'] = {'from': '2026-10-01T00:00:00Z', 'to': at(t, ms), 'complete': True, 'pages': 1}
        obs[name] = ob
    return reg, obs['base'], obs['now']


# ── 대조기 불러오기 ─────────────────────────────────────────────
def load_impl(path):
    spec = importlib.util.spec_from_file_location('impl', path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def run_pure(m, reg, base, now):
    fn = getattr(m, 'evaluate', None) or m.reconcile
    try:
        return fn(copy.deepcopy(reg), copy.deepcopy(base), copy.deepcopy(now))
    except Exception as e:  # v2 는 예외를 그대로 던진다
        return {'overall': f'EXCEPTION:{type(e).__name__}', 'message': str(e), 'anomalies': [], 'holds': []}


def codes(r):
    return sorted({x['code'] for x in r.get('anomalies', []) + r.get('holds', [])})


def invariants(r):
    """v3 출력 계약: 계좌 항목 상태·계좌 판정·전체 판정이 서로 모순되지 않는다."""
    acc = r.get('account')
    probs = []
    if r.get('overall') not in ('OK', 'HOLD', 'ANOMALY', 'ERROR'):
        probs.append(f"overall={r.get('overall')}")
    if not isinstance(acc, dict) or 'verdict' not in acc:
        return probs
    sts = [acc['krw'].get('status')] + [v.get('status') for k in ('coins', 'sessions', 'backing') for v in acc.get(k, {}).values()]
    if None in sts:
        probs.append('항목 status 없음')
    has_acc_anom = any(a['code'].startswith('ACCOUNT_') or a['code'] == 'SESSION_LEDGER_MISMATCH' for a in r['anomalies'])
    if acc['verdict'] == '불일치' and not has_acc_anom:
        probs.append('계좌 불일치인데 계좌 이상 없음')
    if has_acc_anom and acc['verdict'] != '불일치':
        probs.append(f"계좌 이상인데 verdict={acc['verdict']}")
    if acc['verdict'] == '일치' and r['overall'] == 'OK' and r['holds']:
        probs.append('OK 인데 보류 있음')
    if acc['verdict'] == '보류' and r['overall'] == 'OK':
        probs.append('계좌 보류인데 OK')
    return probs


# ── 새 반례·경계 변형 (총괄 P1~P7 + 보완 계약) ──────────────────
def S(i):  # 등록 세션 i
    return lambda d: d[0]['sessions'][i]


CASES = []


def case(name, src, edit, expect, codes_=(), forbid=(), check=None, why=''):
    CASES.append(dict(name=name, src=src, edit=edit, expect=expect, codes=list(codes_), forbid=list(forbid), check=check, why=why))


def set_(path_fn, **kw):
    return lambda d: path_fn(d).update(kw)


def nofp(keys):
    def f(d):
        for ob in (d[1], d[2]):
            for k in keys:
                if k == 'db.fingerprint':
                    ob['db'].pop('fingerprint', None)
                else:
                    ob.pop(k, None)
    return f


def acc_status(path, want):
    def f(r):
        a = r.get('account') or {}
        cur = a
        for k in path:
            cur = cur.get(k, {}) if isinstance(cur, dict) else {}
        return cur == want or f'{".".join(path)}={cur!r}≠{want!r}'
    return f


def stable_false(r):
    return r.get('observation_stable') is False or f"observation_stable={r.get('observation_stable')}"


case('N00_control', 'A1_eth_hold', lambda d: None, 'OK', check=acc_status(('verdict',), '일치'), why='변경 없는 대조군')
# P1 — 등록 code_sha ↔ 외부 수집 메타데이터
case('N01a_code_sha_not_hex', 'A1_eth_hold', set_(S(1), code_sha='wrong-code'), 'ANOMALY', ['REGISTRY_INVALID'], why='총괄 P1: 형식이 커밋 SHA 아님')
case('N01b_code_sha_other_commit', 'A1_eth_hold', set_(S(1), code_sha='c2a09f9c'), 'ANOMALY', ['REGISTRY_CONTRACT_MISMATCH'],
     why='형식은 맞으나 관측 app_code_sha 0e1d2a02 와 다름')
case('N01c_app_sha_unobserved', 'A1_eth_hold', lambda d: [d[2]['meta'].pop(k) for k in ('app_code_sha', 'app_code_sha_ref')],
     'HOLD', ['CODE_SOURCE_UNCONFIRMED'], ['REGISTRY_CONTRACT_MISMATCH'], why='코드 출처 미관측은 보류(일치로 쓰지 않음)')
case('N01d_code_changed_in_window', 'A1_eth_hold', lambda d: d[1]['meta'].update(app_code_sha='89336440'), 'HOLD',
     ['CODE_CHANGED_IN_WINDOW'], why='기준·현재 배포 코드가 다름')
case('N01e_source_db_other', 'A1_eth_hold', lambda d: d[2]['meta']['source'].update(db='other-db'), 'HOLD',
     ['OBSERVATION_SOURCE_MISMATCH'], check=acc_status(('verdict',), '보류'), why='등록표가 정한 DB 가 아닌 곳을 읽음')
case('N01f_full_sha_prefix', 'A1_eth_hold', lambda d: [d[i]['meta'].update(app_code_sha=APP_SHA_FULL) for i in (1, 2)], 'OK',
     why='관측이 40자 전체 SHA, 등록이 8자 접두')
# P2 — 지문
case('N02a_fingerprints_missing', 'A1_eth_hold', nofp(('db_fp_pre', 'db_fp_post', 'db.fingerprint')), 'HOLD', ['FINGERPRINT_MISSING'],
     check=stable_false, why='총괄 P2: 결측 세 값이 같아도 안정 확인 아님')
case('N02b_fingerprint_post_missing', 'A1_eth_hold', nofp(('db_fp_post',)), 'HOLD', ['FINGERPRINT_MISSING'], check=stable_false)
case('N02c_fingerprint_empty', 'A1_eth_hold', lambda d: [d[i].update(db_fp_pre='', db_fp_post='') or d[i]['db'].update(fingerprint='') for i in (1, 2)],
     'HOLD', ['OBSERVATION_INVALID'], why='빈 문자열 지문')
# P3 — 관측 시각·순서
case('N03a_now_before_base', 'A1_eth_hold', set_(lambda d: d[2]['db'], observed_at='2020-01-01T00:00:00Z'), 'HOLD',
     ['OBSERVATION_ORDER_INVALID'], why='총괄 P3')
case('N03b_naive_timestamp', 'A1_eth_hold', lambda d: d[2]['db'].update(observed_at=d[2]['db']['observed_at'].rstrip('Z')), 'HOLD',
     ['OBSERVATION_INVALID'], why='시간대 없는 시각')
case('N03c_steps_not_monotonic', 'A1_eth_hold', lambda d: d[2]['meta']['steps'][1].update(at=at(d[2]['db']['observed_at'], 5)), 'HOLD',
     ['OBSERVATION_INVALID'])
case('N03d_steps_misordered', 'A1_eth_hold', lambda d: d[2]['meta']['steps'].reverse(), 'HOLD', ['OBSERVATION_INVALID'])
case('N03g_step_missing', 'A1_eth_hold', lambda d: d[2]['meta']['steps'].pop(), 'HOLD', ['OBSERVATION_INVALID'],
     why='뒤 DB 지문 단계 기록 없음(시각 순서는 맞음)')
case('N03h_db_time_outside_collection', 'A1_eth_hold', lambda d: d[2]['meta']['steps'][1].update(at=at(d[2]['db']['observed_at'], 0.5)) or
     d[2]['meta']['steps'][2].update(at=at(d[2]['db']['observed_at'], 0.6)), 'HOLD', ['DB_TIME_OUTSIDE_COLLECTION'],
     why='거래소 앞 읽기가 DB 추출 시각보다 늦음')
case('N03e_collection_overlap', 'A1_eth_hold', lambda d: d[2]['meta']['steps'][0].update(at=at(d[1]['db']['observed_at'], 1)), 'HOLD',
     ['COLLECTION_OVERLAP'], why='현재 수집이 기준 수집 종료 전에 시작')
case('N03f_schema_changed', 'A1_eth_hold', set_(lambda d: d[2]['db'], flyway_version=95), 'HOLD', ['SCHEMA_CHANGED_IN_WINDOW'])


# P4/P5 — 보류는 영향 범위만 막는다
def closing(d):
    d[2]['db']['positions'][0]['status'] = 'CLOSING'


case('N04a_closing_does_not_hide_krw_residual', 'G2_unexplained_1_50_two_sessions', closing, 'ANOMALY',
     ['ACCOUNT_KRW_MISMATCH', 'POSITION_CLOSING'], check=acc_status(('krw', 'status'), '불일치'), why='총괄 P4')


def closing_plus_inflight(d):
    closing(d)
    now = d[2]['db']
    s = next(x for x in now['sessions'] if x['kind'] == 'DYNAMIC' and x['id'] == 900)
    s['available_krw'] = str(Decimal(s['available_krw']) - 3000)   # 예약 3000 차감(세션 장부와 맞춤)
    now['orders'].append({'id': 9999, 'session_kind': 'DYNAMIC', 'session_id': 900, 'position_id': None, 'coin_pair': 'KRW-XRP',
                          'side': 'BUY', 'order_type': 'MARKET', 'quantity': '3000.00000000', 'reserved_krw': '3000.00000000',
                          'client_order_id': 'c-u-new', 'state': 'INTENT_READY', 'filled_quantity': None, 'exchange_order_id': None,
                          'created_at': at(now['observed_at'], -1).replace('Z', '+00:00'), 'reported_executed_funds': None,
                          'reported_paid_fee': None, 'applied_executed_funds': None, 'applied_paid_fee': None, 'fill_accounting_pending': False})


case('N04b_inflight_still_blocks_krw', 'G2_unexplained_1_50_two_sessions', closing_plus_inflight, 'HOLD',
     ['ORDER_IN_FLIGHT', 'ORDER_EFFECT_PENDING', 'POSITION_CLOSING'], ['ACCOUNT_KRW_MISMATCH'], check=acc_status(('krw', 'status'), '보류'),
     why='KRW 에 영향 주는 미확정 매수가 있으면 KRW 는 여전히 보류(보류를 전부 이상으로 올리지 않음)')
case('N05a_closing_does_not_hide_unbacked', 'F4_coin_withdraw_declared', closing, 'ANOMALY',
     ['ACCOUNT_COIN_UNBACKED', 'POSITION_CLOSING'], check=acc_status(('verdict',), '불일치'), why='총괄 P5')
case('N05b_flows_unknown_does_not_hide_unbacked', 'F4_coin_withdraw_declared', lambda d: d[2].update(external_flows=None), 'ANOMALY',
     ['ACCOUNT_COIN_UNBACKED', 'EXTERNAL_FLOWS_UNCONFIRMED'], ['ACCOUNT_COIN_MISMATCH'],
     why='입출금 이력 미확인은 증감식만 막고, 이력과 무관한 실보유 담보 부족은 막지 않음')


# P6 — 거래소 조회 범위
def done(d, ids=None, **q):
    for side in ('exchange_before', 'exchange_after'):
        if ids is not None:
            d[2][side]['done_order_ids'] = list(ids)
        if q.get('drop'):
            d[2][side].pop('done_query')
        else:
            d[2][side]['done_query'].update({k: v for k, v in q.items() if k != 'drop'})


case('N06a_db_fill_missing_on_exchange', 'A1_eth_hold', lambda d: done(d, ids=[]), 'ANOMALY', ['DB_FILL_NOT_ON_EXCHANGE'], why='총괄 P6')
case('N06b_query_incomplete', 'A1_eth_hold', lambda d: done(d, ids=[], complete=False), 'HOLD', ['EXCHANGE_COVERAGE_UNCONFIRMED'],
     ['DB_FILL_NOT_ON_EXCHANGE', 'UNREGISTERED_REAL_ORDER'], why='페이지가 남은 조회는 누락 판정 근거 아님')
case('N06c_order_outside_query_window', 'A1_eth_hold', lambda d: done(d, ids=[], **{'from': at(d[2]['db']['observed_at'], -1)}), 'HOLD',
     ['EXCHANGE_COVERAGE_GAP'], ['DB_FILL_NOT_ON_EXCHANGE', 'UNREGISTERED_REAL_ORDER'], why='조회 기간 밖은 누락/미등록으로 단정 안 함')
case('N06d_query_range_unknown', 'A1_eth_hold', lambda d: done(d, drop=True), 'HOLD', ['EXCHANGE_COVERAGE_UNCONFIRMED'],
     why='조회 범위가 없으면 OK 로 쓰지 않음')
case('N06e_fill_without_exchange_id', 'A1_eth_hold', lambda d: d[2]['db']['orders'][0].update(exchange_order_id=None), 'ANOMALY',
     ['DB_FILL_WITHOUT_EXCHANGE_ID'])
# P7 — 등록 규칙·숫자 형식(구조화된 거절)
for nm, edit, why in (
        ('N07a_rule_nan', lambda d: d[0]['sessions'][1]['rules'].update(max_buy_request_krw='NaN'), '총괄 P7'),
        ('N07b_rule_bool', lambda d: d[0]['sessions'][1]['rules'].update(max_open_positions=True), 'bool 은 정수 아님'),
        ('N07c_rule_negative', lambda d: d[0]['sessions'][0]['rules'].update(max_filled_buys_in_window=-1), ''),
        ('N07d_rule_coins_string', lambda d: d[0]['sessions'][1]['rules'].update(excluded_coins='KRW-BTC'), ''),
        ('N07e_rule_coins_duplicate', lambda d: d[0]['sessions'][1]['rules'].update(excluded_coins=['KRW-BTC', 'KRW-BTC']), ''),
        ('N07f_capital_infinity', set_(S(1), initial_capital='Infinity'), ''),
        ('N07g_cap_zero', lambda d: d[0]['sessions'][1]['rules'].update(max_buy_request_krw='0'), ''),
        ('N07h_window_reversed', set_(S(1), effective_to='2026-11-17T00:00:00Z'), ''),
        ('N07i_unknown_field', set_(S(1), approved_by='x'), '알 수 없는 등록 필드'),
        ('N07j_source_missing', lambda d: d[0].pop('observation_source'), '등록표 관측 출처 없음')):
    case(nm, 'A1_eth_hold', edit, 'ANOMALY', ['REGISTRY_INVALID'], why=why)
# 관측 숫자·키 중복 — 예외 없이 구조화된 보류
for nm, edit in (
        ('N08a_balance_nan', lambda d: d[2]['exchange_after']['accounts']['KRW'].update(balance='NaN')),
        ('N08b_locked_negative', lambda d: d[2]['exchange_after']['accounts']['KRW'].update(locked='-1')),
        ('N08c_balance_float', lambda d: d[2]['exchange_after']['accounts']['ETH'].update(balance=0.0032)),
        ('N08d_filled_not_number', lambda d: d[2]['db']['orders'][0].update(filled_quantity='abc')),
        ('N08e_flow_unknown_kind', lambda d: d[2].update(external_flows=[{'kind': 'GIFT', 'currency': 'KRW', 'amount': '1', 'ref': 'x'}])),
        ('N08f_query_window_reversed', lambda d: done(d, **{'from': '2027-01-01T00:00:00Z'})),
        ('N09a_duplicate_order_id', lambda d: d[2]['db']['orders'].append(copy.deepcopy(d[2]['db']['orders'][0]))),
        ('N09b_duplicate_exchange_uuid', lambda d: d[2]['db']['orders'].append(dict(copy.deepcopy(d[2]['db']['orders'][0]), id=77))),
        ('N09c_duplicate_session_row', lambda d: d[2]['db']['sessions'].append(copy.deepcopy(d[2]['db']['sessions'][0]))),
        ('N09d_duplicate_done_uuid', lambda d: done(d, ids=['u-eth-buy', 'u-eth-buy']))):
    case(nm, 'A1_eth_hold', edit, 'HOLD', ['OBSERVATION_INVALID'], ['ACCOUNT_KRW_MISMATCH'])
# 등록 적용 시작 ≠ 세션 생성
SID203_CREATED = '2026-11-06T10:07:36.87044+00:00'
case('N10a_existing_session_new_window', 'A1_eth_hold', set_(S(0), session_created_at=SID203_CREATED, effective_from='2026-11-18T00:00:00Z'),
     'OK', why='기존 SID203 의 승인된 신규 구간(생성 11-06, 적용 11-18)')
case('N10b_existing_session_created_mismatch', 'A1_eth_hold', set_(S(0), session_created_at='2026-11-06T10:07:37Z',
                                                                   effective_from='2026-11-18T00:00:00Z'),
     'ANOMALY', ['REGISTRY_CONTRACT_MISMATCH'])
case('N10c_new_session_rule_on_old_row', 'A1_eth_hold', set_(S(0), effective_from='2026-11-18T00:00:00Z'), 'ANOMALY',
     ['REGISTRY_CONTRACT_MISMATCH'], why='session_created_at=null 은 새 세션 — 이전 생성 행이면 불일치')
case('N10d_created_after_effective', 'A1_eth_hold', set_(S(0), session_created_at='2026-11-19T00:00:00Z'), 'ANOMALY', ['REGISTRY_INVALID'])
case('N11_internal_error_is_error', 'A1_eth_hold', 'PATCH', 'ERROR', why='내부 예외가 OK/무출력으로 가려지지 않음')


# ── 파일 입력(명령줄·종료값) ─────────────────────────────────────
def write(path, obj=None, text=None):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text if text is not None else json.dumps(obj, ensure_ascii=False, indent=1))


def file_cases(impl, base_inputs, work):
    reg, base, now = base_inputs
    os.makedirs(work, exist_ok=True)
    P = lambda n: os.path.join(work, n)
    write(P('reg.json'), reg); write(P('base.json'), base); write(P('now.json'), now)
    dup = json.dumps(reg, ensure_ascii=False)[:-1] + ', "registry_version": "dup"}'
    write(P('reg_dup.json'), text=dup)
    write(P('now_nan.json'), text=json.dumps(now, ensure_ascii=False).replace('"84000.01635217"', 'NaN'))
    write(P('now_float.json'), text=json.dumps(now, ensure_ascii=False).replace('"0.0032"', '0.0032', 1))
    rows = [
        ('F01_files_ok', ['--registry', P('reg.json'), '--base', P('base.json'), '--now', P('now.json')], 'OK', 0, []),
        ('F02_registry_duplicate_json_key', ['--registry', P('reg_dup.json'), '--base', P('base.json'), '--now', P('now.json')], 'ANOMALY', 20, ['REGISTRY_INVALID']),
        ('F03_observation_nan_literal', ['--registry', P('reg.json'), '--base', P('base.json'), '--now', P('now_nan.json')], 'HOLD', 10, ['OBSERVATION_INVALID']),
        ('F04_observation_json_float', ['--registry', P('reg.json'), '--base', P('base.json'), '--now', P('now_float.json')], 'HOLD', 10, ['OBSERVATION_INVALID']),
        ('F05_registry_unreadable', ['--registry', P('없음.json'), '--base', P('base.json'), '--now', P('now.json')], 'HOLD', 10, ['REGISTRY_UNREADABLE']),
        ('F06_now_unreadable', ['--registry', P('reg.json'), '--base', P('base.json'), '--now', P('없음.json')], 'HOLD', 10, ['OBSERVATION_UNREADABLE']),
        ('F07_quantum_nan', ['--registry', P('reg.json'), '--base', P('base.json'), '--now', P('now.json'), '--krw-quantum', 'NaN'], 'ERROR', 30, []),
    ]
    res = []
    for name, args, want, rc_want, want_codes in rows:
        r = subprocess.run([sys.executable, '-I', impl] + args, capture_output=True)
        r.stdout, r.stderr = (x.decode('utf-8', 'replace') for x in (r.stdout, r.stderr))   # 실패 쪽 출력 인코딩이 달라도 멈추지 않음
        try:
            out = json.loads(r.stdout)
            got = out.get('overall')
        except ValueError:
            out, got = {'stdout': r.stdout[-400:], 'stderr': r.stderr[-400:]}, f'NO_JSON(rc={r.returncode})'
        cs = codes(out) if isinstance(out, dict) and 'anomalies' in out else []
        fails = []
        if got != want:
            fails.append(f'overall {got}≠{want}')
        if r.returncode != rc_want:
            fails.append(f'rc {r.returncode}≠{rc_want}')
        miss = [c for c in want_codes if c not in cs]
        if miss:
            fails.append(f'코드 없음 {miss}')
        if want == 'HOLD' and any(a['code'].startswith('UNREGISTERED') for a in out.get('anomalies', [])):
            fails.append('읽기 실패를 미등록으로 단정')
        res.append((name, not fails, got, cs, fails, {'args': [os.path.basename(a) if os.sep in a else a for a in args], 'rc': r.returncode, 'result': out}))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--impl', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    impl = os.path.abspath(a.impl)
    m = load_impl(impl)
    os.makedirs(os.path.join(a.out, 'cases'), exist_ok=True)
    saved = v2_inputs()
    lines, n_fail = [], 0
    impl_sha = hashlib.sha256(open(impl, 'rb').read()).hexdigest()
    try:
        shown = os.path.relpath(impl, HERE).replace(os.sep, '/')
    except ValueError:   # 다른 드라이브의 변형 사본
        shown = os.path.basename(impl)
    lines.append(f'impl {shown} sha256={impl_sha}')

    def emit(group, name, ok, got, cs, fails, record):
        nonlocal n_fail
        n_fail += not ok
        lines.append(f"{'PASS' if ok else 'FAIL'} | {group} | {name} | {got} | {','.join(cs)}" + (f" | {'; '.join(fails)}" if fails else ''))
        with open(os.path.join(a.out, 'cases', f'{name}.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(record, f, ensure_ascii=False, indent=1, default=str)

    # 1) v2 35시나리오 — 저장된 기대(overall·필수 코드·금지 코드) + v3 출력 계약
    for name, (d, h) in saved.items():
        reg, base, now = adapt(d)
        sc = d['scenario']
        r = run_pure(m, reg, base, now)
        cs = codes(r)
        fails = []
        if r.get('overall') != sc['expect']:
            fails.append(f"overall {r.get('overall')}≠{sc['expect']}")
        miss = [c for c in sc['codes'] if c not in cs]
        bad = [c for c in sc['forbid'] if c in cs]
        if miss:
            fails.append(f'코드 없음 {miss}')
        if bad:
            fails.append(f'금지 코드 {bad}')
        if hasattr(m, 'evaluate'):
            fails += invariants(r)
        emit('v2', name, not fails, r.get('overall'), cs, fails,
             {'source': f'dec-reconcile-20261118/p7v2/out/{name}.json', 'source_sha256': h,
              'expect': {'overall': sc['expect'], 'codes': sc['codes'], 'forbid': sc['forbid']},
              'input': {'registry': reg, 'base': base, 'now': now}, 'result': r})

    # 2) 새 반례·경계 변형
    for c in CASES:
        d, h = saved[c['src']]
        reg, base, now = adapt(d)
        box = [reg, base, now]
        if c['edit'] == 'PATCH':
            orig = getattr(m, 'order_effects', None)
            if orig is not None:
                m.order_effects = lambda o: (_ for _ in ()).throw(RuntimeError('시험용 내부 예외'))
            r = run_pure(m, reg, base, now)
            if orig is not None:
                m.order_effects = orig
        else:
            c['edit'](box)
            r = run_pure(m, *box)
        cs = codes(r)
        fails = []
        if r.get('overall') != c['expect']:
            fails.append(f"overall {r.get('overall')}≠{c['expect']}")
        miss = [x for x in c['codes'] if x not in cs]
        bad = [x for x in c['forbid'] if x in cs]
        if miss:
            fails.append(f'코드 없음 {miss}')
        if bad:
            fails.append(f'금지 코드 {bad}')
        if c['check'] and r.get('overall', '').startswith(('OK', 'HOLD', 'ANOMALY')):
            v = c['check'](r)
            if v is not True:
                fails.append(str(v))
        if hasattr(m, 'evaluate'):
            fails += invariants(r)
        emit('new', c['name'], not fails, r.get('overall'), cs, fails,
             {'source': f"dec-reconcile-20261118/p7v2/out/{c['src']}.json", 'source_sha256': h, 'why': c['why'],
              'expect': {'overall': c['expect'], 'codes': c['codes'], 'forbid': c['forbid']},
              'input': {'registry': box[0], 'base': box[1], 'now': box[2]}, 'result': r})

    # 3) 파일 입력·종료값
    for name, ok, got, cs, fails, rec in file_cases(impl, adapt(saved['A1_eth_hold'][0]), os.path.join(a.out, 'files')):
        emit('file', name, ok, got, cs, fails, rec)

    total = len(lines) - 1
    lines.append(f'합계 {total} 통과 {total - n_fail} 실패 {n_fail}')
    with open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if n_fail else 0


if __name__ == '__main__':
    sys.exit(main())
