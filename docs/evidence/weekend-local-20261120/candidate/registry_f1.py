"""W1 등록표 기반 F1 비교기 후보(총괄 v1.212 §2 W1). 로컬 파일 입력 전용 — SSH·psql·DB·HTTP·거래소 호출 없음.

입력
  --registry         등록표 v3 JSON(registry_version == 'registry-3'). 검증은 P7 v8 reconcile.load_registry() 를 그대로 쓴다
                     (고정 156c3828 해시 확인 후 import, 판정식 복제 없음).
  --registry-sha256  F 변수로 고정할 등록표 파일 sha256(필수). 다르면 비교하지 않는다.
  --observed         금액 없는 세션 관측(observation_version 'f1-obs-1'). 활성 상태(RUNNING/PAUSED/CREATED)는 모드와 관계없이
                     모두 넣는다(PAPER 혼입을 보려면). 세션 필드는 정확히 OBS_KEYS — 금액·잔액 필드가 있으면 값은 출력하지 않고 거절한다.
  --out              결과 JSON 경로(없으면 표준 출력)

비교(이 도구가 담당하는 F1 부분)
  기대 = 등록 항목의 (kind, id) 집합, 관측 = 활성 상태이면서 PAPER 가 아닌(제품 규칙: 정확히 'PAPER' 가 아니면 REAL 분기) 세션의
  (kind, id) 집합. 종류+ID 집합이 같아야 한다 — 개수만 같으면 통과가 아니다. 누락/초과는 종류+ID 로 낸다.
  등록 세션마다 고정 조건: mode 정확히 'REAL', strategy_type, timeframe, LIVE coin_pair, 생성 시각(기존 세션은 session_created_at 과
  같아야 함, 새 세션은 적용 시작 ≤ 생성 — 적용 축이 app 이 아니면 시계 관계가 없어 보류).
  알 수 없는 상태(EG 일곱째 값과 같은 범주)는 불일치로 낸다. effective_to 가 있는 항목은 시계 판정 없이 기대 여부를 정할 수 없어 보류.

담당하지 않는 것(별도 확인 필요 — 기존 F1 에서 지우지 않는다)
  SID203 KEEP(자본·투자 비율·손절 등 금액 포함 저장값), LIVE1 의 SID203 상태 값, EG 의 비상 정지 목록·capital_stage_baseline 개수·
  risk_config, BASE=BASEX, 코드 SHA·이미지·설치 장부, ruleset 확인 지문(F3), 계좌 합산(P7/F3).
  그래서 이 도구의 SET_MATCH 는 'F1 통과'가 아니다. 결과의 f1_overall 은 항상 NOT_DETERMINED_BY_THIS_TOOL 이다.

종료값: SET_MATCH 0 · HOLD 10 · MISMATCH 20 · INVALID 30(등록표·해시·관측 형식) · ERROR 40(고정 원천 해시 불일치·예상 밖 예외)
"""
import argparse, hashlib, os, sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pins  # noqa: E402

TOOL = 'registry_f1'
VERSION = 'w1-1'
OBS_VERSION = 'f1-obs-1'
OBS_TOP = {'observation_version', 'synthetic', 'source_note', 'sessions'}
OBS_KEYS = ('kind', 'id', 'mode', 'status', 'strategy_type', 'timeframe', 'coin_pair', 'created_at')
ACTIVE = {'RUNNING', 'PAUSED', 'CREATED'}
KNOWN_STATUS = ACTIVE | {'EMERGENCY_STOPPED', 'STOPPED', 'DELETED'}
EXIT = {'SET_MATCH': 0, 'HOLD': 10, 'MISMATCH': 20, 'INVALID': 30, 'ERROR': 40}
NOT_COVERED = [
    'SID203 KEEP 저장값(initial_capital·invest_ratio·max_investment·stop_loss_pct·max_hold_hours·strategy_params·생성 시각)',
    'LIVE1 의 SID203 상태 값(CREATED/RUNNING) — 단계별 기대 상태',
    'EG 비상 정지 목록(LIVE 157…200 / DYNAMIC 92)·capital_stage_baseline 개수·risk_config 값',
    'BASE = BASEX(capital_stage_baseline 행)',
    '운영 코드 SHA·이미지·설치 장부(F1 다른 줄)',
    'ruleset 확인 지문(F3)·계좌 합산(P7 v8 account_sum → F3 v9)',
]


def _key(k):
    return {'kind': k[0], 'id': k[1]}


def _sorted(keys):
    return [_key(k) for k in sorted(keys)]


def check_observation(ob):
    """(sessions, problems). 금액·알 수 없는 필드는 이름만 적고 값은 출력하지 않는다."""
    R = pins.load('p7_v8')
    probs = []
    if isinstance(ob, dict) and '__load_error__' in ob:
        return [], [f"관측 파일 {ob['__load_error__'][0]}: {ob['__load_error__'][1]}"]
    if not isinstance(ob, dict):
        return [], ['관측이 객체 아님']
    extra = set(ob) - OBS_TOP
    if extra:
        probs.append(f'관측 최상위 알 수 없는 필드 {sorted(extra)}(값 미출력)')
    if ob.get('observation_version') != OBS_VERSION:
        probs.append(f'observation_version 은 {OBS_VERSION!r} 이어야 함')
    if not isinstance(ob.get('synthetic'), bool):
        probs.append('synthetic(참/거짓) 표시 필수')
    ss = ob.get('sessions')
    if not isinstance(ss, list):
        return [], probs + ['sessions 목록 없음']
    seen = set()
    for i, s in enumerate(ss):
        p = f'sessions[{i}]'
        if not isinstance(s, dict):
            probs.append(f'{p}: 객체 아님')
            continue
        miss = [k for k in OBS_KEYS if k not in s]
        extra = sorted(set(s) - set(OBS_KEYS))
        if miss:
            probs.append(f'{p}: 필드 누락 {miss}')
        if extra:
            probs.append(f'{p}: 금액 없는 관측에 없는 필드 {extra}(값 미출력)')
        if miss or extra:
            continue
        try:
            if s['kind'] not in ('LIVE', 'DYNAMIC'):
                raise R.Bad('kind')
            R.posint(s['id'], 1)
            if not isinstance(s['status'], str):
                raise R.Bad('status 문자열 아님')
            if s['mode'] is not None and not isinstance(s['mode'], str):
                raise R.Bad('mode 문자열/null 아님')
            for k in ('strategy_type', 'timeframe'):
                if s[k] is not None and not isinstance(s[k], str):
                    raise R.Bad(f'{k} 문자열/null 아님')
            if s['coin_pair'] is not None and not isinstance(s['coin_pair'], str):
                raise R.Bad('coin_pair 문자열/null 아님')
            R.tstamp(s['created_at'])
            k = (s['kind'], s['id'])
            if k in seen:
                raise R.Bad(f'(kind, id) 중복 {list(k)}')
            seen.add(k)
        except R.Bad as x:
            probs.append(f'{p}: {x}')
    return ss, probs


def compare(reg, reg_bytes, reg_sha_expected, ob):
    R = pins.load('p7_v8')
    out = {'tool': TOOL, 'version': VERSION, 'p7_v8_sha256': pins.verify('p7_v8')[1],
           'f1_overall': 'NOT_DETERMINED_BY_THIS_TOOL', 'covered': '활성 REAL 종류+ID 집합 일치와 등록 세션 고정 조건(모드·전략·주기·코인·생성 시각)',
           'not_covered_separate_check_required': NOT_COVERED,
           'synthetic_input': ob.get('synthetic') if isinstance(ob, dict) else None,
           'invalid': [], 'holds': [], 'missing': [], 'extra': [], 'fixed_mismatch': [], 'status_unknown': [],
           'paper_active_ignored': []}
    got = hashlib.sha256(reg_bytes).hexdigest() if reg_bytes is not None else None
    out['registry_sha256'] = got
    if got is None or got != reg_sha_expected.lower():
        out['invalid'].append({'code': 'REGISTRY_HASH_MISMATCH', 'expected': reg_sha_expected, 'actual': got})
    res = R.Result()
    entries, rstatus = R.load_registry(reg, res)
    out['registry_status'] = rstatus
    if rstatus != 'valid':
        out['invalid'] += [{'code': a['code'], **{k: v for k, v in a.items() if k != 'code'}} for a in res.anomalies + res.holds]
    elif reg.get('registry_version') != R.REGISTRY_V3:
        out['invalid'].append({'code': 'REGISTRY_NOT_V3', 'detail': 'registry-3 만 받는다'})
    sessions, oprobs = check_observation(ob)
    out['invalid'] += [{'code': 'OBSERVATION_INVALID', 'detail': d} for d in oprobs]
    if out['invalid']:
        out['verdict'] = 'INVALID'
        return out

    axis_default = reg.get('effective_axis')
    expected = set(entries)
    obs = {(s['kind'], s['id']): s for s in sessions}
    active_real = set()
    for k, s in obs.items():
        if s['status'] not in KNOWN_STATUS:
            out['status_unknown'].append({**_key(k), 'status': s['status']})
            continue
        if s['status'] not in ACTIVE:
            continue
        if s['mode'] == 'PAPER':
            out['paper_active_ignored'].append(_key(k))
        else:
            active_real.add(k)
    out['expected'] = {kd: sorted(i for kk, i in expected if kk == kd) for kd in ('LIVE', 'DYNAMIC')}
    out['observed_active_real'] = {kd: sorted(i for kk, i in active_real if kk == kd) for kd in ('LIVE', 'DYNAMIC')}
    for k in sorted(expected - active_real):
        s = obs.get(k)
        why = '관측에 없음' if s is None else f"status {s['status']}" if s['status'] not in ACTIVE else f"mode {s['mode']!r}(PAPER — 실자금 아님)"
        out['missing'].append({**_key(k), 'why': why})
    for k in sorted(active_real - expected):
        out['extra'].append({**_key(k), 'why': '등록되지 않은 활성 REAL', 'mode': obs[k]['mode'], 'status': obs[k]['status']})
    for k in sorted(expected & active_real):
        e, s = entries[k], obs[k]
        diffs = []
        if s['mode'] != 'REAL':
            diffs.append(f"mode {s['mode']!r}≠'REAL'(정확한 대문자 REAL 아님 — 제품은 REAL 분기)")
        for f in ('strategy_type', 'timeframe'):
            if s[f] != e[f]:
                diffs.append(f'{f} {s[f]!r}≠{e[f]!r}')
        if e['kind'] == 'LIVE' and s['coin_pair'] != e['coin_pair']:
            diffs.append(f"coin_pair {s['coin_pair']!r}≠{e['coin_pair']!r}")
        created = R.tstamp(s['created_at'])
        axis = e.get('effective_axis', axis_default)
        if e['session_created_at'] is not None:
            if created != R.tstamp(e['session_created_at']):
                diffs.append(f"기존 세션 생성 시각 {s['created_at']}≠등록 {e['session_created_at']}")
        elif axis == 'app':
            if created < R.tstamp(e['effective_from']):
                diffs.append('새 세션 등록인데 세션 생성이 등록 적용 시각보다 앞섬')
        else:
            out['holds'].append({'code': 'CLOCK_AXIS_UNCONFIRMED', **_key(k),
                                 'detail': f'적용 축 {axis!r} 와 앱 생성 시각의 관계가 이 입력에 없음 — P7 시계 판정 대상'})
        if diffs:
            out['fixed_mismatch'].append({**_key(k), 'diffs': diffs})
        if e['effective_to'] is not None:
            out['holds'].append({'code': 'REGISTRY_ENTRY_HAS_END', **_key(k),
                                 'detail': 'effective_to 가 있음 — 관측 시점에 기대 항목인지 시계 판정 없이 정하지 않음'})
    if out['missing'] or out['extra'] or out['fixed_mismatch'] or out['status_unknown']:
        out['verdict'] = 'MISMATCH'
    elif out['holds']:
        out['verdict'] = 'HOLD'
    else:
        out['verdict'] = 'SET_MATCH'
    return out


def line(out):
    """RUNBOOK §3 문안 형식의 한 줄 요약. 통과 표지는 SET_MATCH 일 때만, 그때도 F1 전체 통과가 아님을 붙인다."""
    if out['verdict'] == 'INVALID':
        return f"F1-등록표 비교 입력 무효: {', '.join(sorted({i['code'] for i in out['invalid']}))}"
    tag = {'SET_MATCH': '집합 일치(F1 전체 통과 아님 — 별도 확인 필요 항목 남음)', 'HOLD': '보류', 'MISMATCH': '불일치'}[out['verdict']]
    extra = ', '.join(f"{x['kind']}#{x['id']}" for x in out['extra']) or '-'
    miss = ', '.join(f"{x['kind']}#{x['id']}" for x in out['missing']) or '-'
    fixed = ', '.join(f"{x['kind']}#{x['id']}" for x in out['fixed_mismatch']) or '-'
    unk = ', '.join(f"{x['kind']}#{x['id']}" for x in out['status_unknown']) or '-'
    return (f"F1 기대 {out['expected']} / 관측 {out['observed_active_real']} / {tag} "
            f"(초과 {extra}, 누락 {miss}, 고정 조건 불일치 {fixed}, 알 수 없는 상태 {unk})")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--registry', required=True)
    ap.add_argument('--registry-sha256', required=True)
    ap.add_argument('--observed', required=True)
    ap.add_argument('--out')
    a = ap.parse_args(argv)
    try:
        R = pins.load('p7_v8')
        try:
            with open(a.registry, 'rb') as f:
                reg_bytes = f.read()
        except OSError:
            reg_bytes = None
        out = compare(R.load_file(a.registry), reg_bytes, a.registry_sha256, R.load_file(a.observed))
    except pins.PinError as e:
        out = {'tool': TOOL, 'version': VERSION, 'verdict': 'ERROR', 'error': 'PinError', 'message': str(e),
               'f1_overall': 'NOT_DETERMINED_BY_THIS_TOOL'}
    except Exception as e:  # noqa: BLE001 — 무출력 대신 구조화된 ERROR
        out = {'tool': TOOL, 'version': VERSION, 'verdict': 'ERROR', 'error': type(e).__name__,
               'message': '예상 밖 예외(입력 값은 출력하지 않음)', 'f1_overall': 'NOT_DETERMINED_BY_THIS_TOOL'}
    out['summary_line'] = line(out) if out['verdict'] != 'ERROR' else f"F1-등록표 비교 오류: {out['error']}"
    out['exit'] = EXIT[out['verdict']]
    pins.dump(out, a.out)
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
