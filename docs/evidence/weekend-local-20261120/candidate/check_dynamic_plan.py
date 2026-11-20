"""W5 DYNAMIC 시작 본문·배포 선행조건 검증기(총괄 v1.212 §2 W5). 로컬 파일 입력 전용 — 생성/시작 요청을 보내지 않는다.
SSH·psql·DB·HTTP·토큰 없음. 정상이어도 '로컬 형식 점검 완료·실제 실행 승인 전'이다.

입력
  --plan        검토할 계획 JSON(plan_version 'dynamic-plan-1'):
                {plan_version, synthetic, request{…DynamicSessionRequest 본문…},
                 approval{capital_ref, loss_limit_ref, strategy_ref, synthetic},
                 deploy{observed_code_sha, source}, server_limits{per_position_cap_krw, source}|null,
                 live_link{kind:'LIVE', id, create:false}}
  --dto-java    d81c3f2f DynamicSessionRequest.java 원문(고정 b2a9311e 해시 확인) — 서버가 받는 필드 목록을 여기서 읽는다
  --limits-java d81c3f2f DynamicEntryLimits.java 원문(고정 94e54397 해시 확인) — 키 이름·최소 주문을 여기서 읽는다

서버 계약(d81c3f2f 원문 인용, 이 도구는 그 규칙을 검사만 한다)
  M1 tradingMode 정확히 'REAL'/'PAPER'(DynamicTradingService.java:506-511). 0e1d2a02 는 'PAPER' 가 아니면 REAL(:481) — 그래서
     관측 배포가 0e1d2a02 면 이 계획을 보낼 수 없다(NOT_READY).
  M2 investRatio: normalizeRatio(:3836-3842) — 1 초과면 ÷100, 1 이하면 그대로 비율, [0.01, 1] 로 자른다. 기본 0.80.
     DTO 주석 '1~100(기본 80)'과 다르다: 1 은 1% 가 아니라 100%, 0.5 는 50%. 그래서 1 이하 값·잘리는 값은 의도 확인 필요로 낸다.
  M3 maxOrderKrw·excludeCoins 는 DTO 필드가 아니라 strategyParams 의 키(DynamicEntryLimits). 함께 명시·정수 원·최소 주문 이상·
     실자금은 포지션당 상한 이하(상한 기준이 없으면 서버가 거절)·KRW-XXX 대문자·중복 없음.
  M4 initialCapital ≥ 10000(@DecimalMin), strategyType·timeframe 비어 있지 않음. timeframe 은 TimeframeUtils 의 M1~D1.
정책 검사(총괄 계약): REAL 만(이 계획은 실자금 DYNAMIC 시작안), BTC 신규 매수 제외(excludeCoins 에 KRW-BTC), 새 LIVE 생성 금지(기존
SID203 연결), 자본·손실 상한·전략 승인 참조 필수. 기술 상한(포지션당 상한)은 승인 금액의 대용이 아니다.
모르는 요청 필드는 서버가 무시하므로(형식상 통과처럼 보임) 미확정 사유로 보존한다.

종료값: FORMAT_OK_NOT_APPROVED 0 · NOT_READY 10 · REJECT 20 · INVALID 30 · ERROR 40
"""
import argparse, os, re, sys
from decimal import Decimal as D, InvalidOperation

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pins  # noqa: E402

TOOL, VERSION, PLAN = 'check_dynamic_plan', 'w5-2', 'dynamic-plan-1'
EXIT = {'FORMAT_OK_NOT_APPROVED': 0, 'NOT_READY': 10, 'REJECT': 20, 'INVALID': 30, 'ERROR': 40}
TIMEFRAMES = ('M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1')   # d81c3f2f web-api/.../util/TimeframeUtils.java:19-25
SUPPORTED_DEPLOY = ('d81c3f2f91c0239aacb0e1176dde9b898f4d7466',)   # DynamicEntryLimits·엄격 모드가 있는 고정 리비전
KNOWN_UNSUPPORTED = {'0e1d2a02': '0e1d2a02 에는 DynamicEntryLimits 가 없고 tradingMode 를 "PAPER" 아니면 REAL 로 저장(:481)'}
MIN_CAPITAL = D('10000')
SHA_RE = re.compile(r'^[0-9a-f]{7,40}$')
COIN = re.compile(r'^KRW-[A-Z0-9]+$')
STRAT = re.compile(r'^[A-Z][A-Z0-9_]*$')


def java_fields(text):
    return re.findall(r'^\s*private\s+[\w.<>, ]+?\s+(\w+)\s*;', text, re.M)


def java_const(text, name):
    m = re.search(rf'{name}\s*=\s*(?:"([^"]+)"|BigDecimal\.valueOf\((\d+)\))', text)
    if not m:
        raise pins.PinError(f'DynamicEntryLimits 에서 {name} 를 찾지 못함')
    return m.group(1) or m.group(2)


def load_java(path, key):
    want = pins.pins()[key]['sha256']
    raw = open(path, 'rb').read()
    got = pins.hashlib.sha256(raw).hexdigest()
    if got != want:
        raise pins.PinError(f'{key} 해시 불일치: 기대 {want[:8]} / 실제 {got[:8]}')
    return raw.decode('utf-8')


def dec(v):
    if isinstance(v, bool) or isinstance(v, float) or not isinstance(v, (str, int)):
        return None
    try:
        x = D(str(v))
    except InvalidOperation:
        return None
    return x if x.is_finite() else None


def ratio_effective(v):
    """d81 normalizeRatio 를 그대로 옮긴 해석(표시용). (유효 비율, 주의 사유|None)."""
    x = dec(v)
    if x is None:
        return None, 'investRatio 숫자 아님'
    raw = x / 100 if x > 1 else x
    eff = max(D('0.01'), min(D(1), raw))
    why = None
    if x <= 1:
        why = f'investRatio {v} 는 비율로 해석(=유효 {eff}) — DTO 주석 1~100(%) 과 다름, 의도 확인 필요'
    if eff != raw:
        why = f'investRatio {v} 가 [0.01, 1] 로 잘림(유효 {eff})'
    return eff, why


def check(plan, dto_fields, k_max, k_ex, min_order):
    rej, nr, notes = [], [], []
    if not isinstance(plan, dict) or plan.get('plan_version') != PLAN:
        return None, [f'plan_version {PLAN!r} 아님'], [], []
    if '__load_error__' in plan:
        return None, [f"계획 파일 {plan['__load_error__'][0]}"], [], []
    req = plan.get('request')
    if not isinstance(req, dict):
        return None, ['request 객체 없음'], [], []
    unknown = sorted(set(req) - set(dto_fields))
    if unknown:
        nr.append({'code': 'UNKNOWN_REQUEST_FIELD', 'fields': unknown,
                   'detail': '서버 DTO 에 없는 필드 — 서버가 조용히 무시하므로 의도한 설정이 적용되지 않음'})
    # M1
    mode = req.get('tradingMode')
    if mode not in ('REAL', 'PAPER'):
        rej.append({'code': 'TRADING_MODE_INVALID', 'detail': f"tradingMode {'없음' if mode is None else repr(mode)} — 정확히 REAL/PAPER 만"})
    elif mode != 'REAL':
        rej.append({'code': 'NOT_REAL_PLAN', 'detail': '이 검증기는 실자금 DYNAMIC 시작안만 본다(PAPER 는 별도 절차)'})
    # w5-2: 키가 있고 값이 null 이면 '미정'(사용자 결정 전 → NOT_READY), 키 누락·빈 문자열·잘못된 값은 서버가 거절할 형식 → REJECT
    undecided = [f for f in ('strategyType', 'timeframe', 'initialCapital') if f in req and req[f] is None]
    if isinstance(req.get('strategyParams'), dict) and req['strategyParams'].get(k_max, 0) is None:
        undecided.append(f'strategyParams.{k_max}')
    if undecided:
        nr.append({'code': 'VALUE_UNDECIDED', 'fields': undecided, 'detail': '값 미정(null) — 결정 뒤 다시 검사'})
    # M4
    for f in ('strategyType', 'timeframe'):
        if f in undecided:
            continue
        if not (isinstance(req.get(f), str) and req[f].strip()):
            rej.append({'code': 'REQUIRED_BLANK', 'field': f})
    if isinstance(req.get('strategyType'), str) and req['strategyType'].strip() and not STRAT.match(req['strategyType']):
        rej.append({'code': 'STRATEGY_NAME_FORMAT', 'detail': '정식 전략 이름은 대문자·숫자·밑줄'})
    if isinstance(req.get('timeframe'), str) and req['timeframe'].strip() and req['timeframe'] not in TIMEFRAMES:
        rej.append({'code': 'TIMEFRAME_UNKNOWN', 'detail': f'{TIMEFRAMES} 밖'})
    cap = dec(req.get('initialCapital'))
    if 'initialCapital' in undecided:
        pass
    elif cap is None:
        rej.append({'code': 'INITIAL_CAPITAL_MISSING', 'detail': 'initialCapital 숫자(문자열/정수) 필수 — 실수형 거절'})
    elif cap < MIN_CAPITAL:
        rej.append({'code': 'INITIAL_CAPITAL_BELOW_MIN', 'detail': f'< {MIN_CAPITAL}'})
    # M2
    if 'investRatio' not in req or req['investRatio'] is None:
        nr.append({'code': 'INVEST_RATIO_DEFAULT', 'detail': '미지정이면 서버 기본 0.80 — 명시 값으로 승인받아야 함'})
        eff = None
    else:
        eff, why = ratio_effective(req['investRatio'])
        if eff is None:
            rej.append({'code': 'INVEST_RATIO_INVALID', 'detail': why})
        elif why:
            nr.append({'code': 'INVEST_RATIO_AMBIGUOUS', 'detail': why})
    # M3 + BTC 정책
    sp = req.get('strategyParams')
    limits = {}
    if not isinstance(sp, dict) or not (k_max in sp or k_ex in sp):
        rej.append({'code': 'ENTRY_LIMITS_MISSING', 'detail': f'strategyParams.{k_max}·{k_ex} 필수(매회 상한·BTC 신규 매수 제외)'})
    elif not (k_max in sp and k_ex in sp):
        rej.append({'code': 'ENTRY_LIMITS_PAIR_INCOMPLETE', 'detail': f'{k_max}·{k_ex} 는 함께 명시(서버도 거절)'})
    else:
        m = sp[k_max]
        mx = D(m) if isinstance(m, int) and not isinstance(m, bool) else None
        if m is None:
            pass   # 미정 — VALUE_UNDECIDED 로 이미 남김
        elif mx is None:
            rej.append({'code': 'MAX_ORDER_FORMAT', 'detail': f'{k_max} 는 정수 원(JSON 정수)'})
        elif mx < min_order:
            rej.append({'code': 'MAX_ORDER_BELOW_MIN', 'detail': f'< 최소 주문 {min_order}'})
        ex = sp[k_ex]
        if not (isinstance(ex, list) and all(isinstance(c, str) and COIN.match(c) for c in ex)) or len(set(ex)) != len(ex):
            rej.append({'code': 'EXCLUDE_COINS_FORMAT', 'detail': 'KRW-XXX 대문자 목록·중복 없음'})
        elif 'KRW-BTC' not in ex:
            rej.append({'code': 'BTC_NOT_EXCLUDED', 'detail': 'DYNAMIC 신규 매수에서 KRW-BTC 제외 필수(SID203 BTC 와 분리)'})
        sl = plan.get('server_limits')
        cap_known = isinstance(sl, dict) and dec(sl.get('per_position_cap_krw')) is not None and isinstance(sl.get('source'), str)
        if not cap_known:   # 상한 금액이 미정이어도 상한 기준 출처 누락은 따로 남긴다
            nr.append({'code': 'SERVER_CAP_UNKNOWN', 'detail': '포지션당 상한 값·출처 없음 — 서버도 상한 기준 없으면 거절'})
        if mx is not None:
            limits['maxOrderKrw'] = str(mx)
            if cap_known:
                pc = dec(sl['per_position_cap_krw'])
                limits['per_position_cap_krw'] = str(pc)
                if mx > pc:
                    rej.append({'code': 'MAX_ORDER_ABOVE_SERVER_CAP', 'detail': f'{mx} > 포지션당 상한 {pc}'})
                notes.append('포지션당 상한은 기술 상한이다 — 승인 금액의 대용이 아니다')
    # 승인 참조
    ap = plan.get('approval') if isinstance(plan.get('approval'), dict) else {}
    missing = [k for k in ('capital_ref', 'loss_limit_ref', 'strategy_ref') if not (isinstance(ap.get(k), str) and ap[k].strip())]
    if missing:
        nr.append({'code': 'APPROVAL_VALUE_UNDECIDED', 'fields': missing, 'detail': '총원금·월 손실 상한·최종 전략은 사용자 결정 전'})
    if ap.get('synthetic') is True:
        notes.append('승인 참조가 합성 표지 — 운영 승인 근거로 쓰지 않음')
    # 배포 지원
    dp = plan.get('deploy') if isinstance(plan.get('deploy'), dict) else {}
    sha = dp.get('observed_code_sha')
    if not (isinstance(sha, str) and SHA_RE.match(sha.lower())):
        nr.append({'code': 'DEPLOY_UNOBSERVED', 'detail': '운영 코드 SHA 관측 없음'})
    elif not any(s.startswith(sha.lower()) for s in SUPPORTED_DEPLOY):
        why = next((v for k, v in KNOWN_UNSUPPORTED.items() if sha.lower().startswith(k)), '지원 확인된 고정 리비전 아님')
        nr.append({'code': 'DEPLOY_DOES_NOT_SUPPORT', 'observed': sha, 'detail': why})
    # 기존 LIVE 연결
    ll = plan.get('live_link')
    if not (isinstance(ll, dict) and ll.get('kind') == 'LIVE' and ll.get('id') == 203 and ll.get('create') is False):
        rej.append({'code': 'LIVE_LINK', 'detail': '새 LIVE 를 만들지 않고 기존 SID203 을 연결(live_link {kind:LIVE, id:203, create:false})'})
    return {'effective_invest_ratio': None if eff is None else str(eff), **limits}, [], rej, nr + [{'code': 'NOTE', 'detail': n} for n in notes]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--plan', required=True)
    ap.add_argument('--dto-java', required=True)
    ap.add_argument('--limits-java', required=True)
    ap.add_argument('--out')
    a = ap.parse_args(argv)
    out = {'tool': TOOL, 'version': VERSION, 'sent_request': False}
    try:
        dto = load_java(a.dto_java, 'dynamic_request')
        lim = load_java(a.limits_java, 'dynamic_entry_limits')
        fields = java_fields(dto)
        k_max, k_ex = java_const(lim, 'MAX_ORDER_KRW'), java_const(lim, 'EXCLUDE_COINS')
        min_order = D(java_const(lim, 'MIN_ORDER_KRW'))
        R = pins.load('p7_v8')
        plan = R.load_file(a.plan)
        out.update(server_contract={'dto_fields': fields, 'limit_keys': [k_max, k_ex], 'min_order_krw': str(min_order),
                                    'source_revision': 'd81c3f2f91c0239aacb0e1176dde9b898f4d7466'})
        eff, invalid, rej, nr = check(plan, fields, k_max, k_ex, min_order)
        out['synthetic_input'] = plan.get('synthetic') if isinstance(plan, dict) else None
        if invalid:
            out.update(verdict='INVALID', problems=invalid)
        else:
            blocking_nr = [x for x in nr if x['code'] != 'NOTE']
            out.update(derived=eff, reject=rej, not_ready=blocking_nr, notes=[x['detail'] for x in nr if x['code'] == 'NOTE'],
                       verdict='REJECT' if rej else ('NOT_READY' if blocking_nr else 'FORMAT_OK_NOT_APPROVED'))
    except pins.PinError as e:
        out.update(verdict='ERROR', error='PinError', message=str(e))
    except Exception as e:  # noqa: BLE001
        out.update(verdict='ERROR', error=type(e).__name__, message='예상 밖 예외(입력 값 미출력)')
    out['summary_line'] = {
        'FORMAT_OK_NOT_APPROVED': '로컬 형식 점검 완료·실제 실행 승인 전(요청 전송 안 함)',
        'NOT_READY': f"NOT_READY — {', '.join(x['code'] for x in out.get('not_ready', []))}",
        'REJECT': f"REJECT — {', '.join(x['code'] for x in out.get('reject', []))}",
        'INVALID': f"INVALID — {out.get('problems')}", 'ERROR': f"ERROR — {out.get('error')}"}[out['verdict']]
    out['exit'] = EXIT[out['verdict']]
    pins.dump(out, a.out)
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
