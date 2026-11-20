"""W2 병행 N03 보고 분류·표시 후보(총괄 v1.212 §2 W2). 로컬 파일 입력 전용 — SSH·psql·DB·HTTP·Telegram 호출 없음.
설치본 c669ccdc·read.sh 는 바꾸지 않는다. 설치본의 real_open_positions/real_unresolved_orders/real_buys_window 는 ID 없는
합계라 등록 세션 거래와 미등록 실자금을 나눌 수 없다(종전 분모 충돌: dec-parallel-20261118 §2-1·§2-3,
dec-parallel-registry-20261120 §1). 이 후보는 (종류, ID)가 붙은 상태 파일을 받는다 — 그런 읽기는 아직 승인 전이다.

입력
  --registry / --registry-sha256  등록표 v3 와 고정 해시(W1 과 같은 검증: P7 v8 load_registry)
  --state   상태 파일(input_version 'parallel-in-1'). 허용 필드만 받는다(STATE_SCHEMA). 그 밖의 필드 — 특히 성과·잔액·금액 —
            가 하나라도 있으면 처리 전에 거절하고, 오류에는 필드 경로만 적고 값은 출력하지 않는다.
  --p7-result / --p7-result-sha256  (선택) 수용된 P7 v8 결과 JSON. 계좌 일치는 그 overall·account_sum.status 를 그대로 인용한다.
            이 도구는 계좌 식을 새로 계산하지 않는다. 없으면 '미참조'다.
  --out-json / --out-md

분류
  실자금 항목(세션·주문·포지션)마다 등록 LIVE / 등록 DYNAMIC REAL / 미등록 REAL. PAPER 는 실자금 분류에 넣지 않는다.
  위험 항목은 등록 여부와 따로 남긴다 — P0, UNKNOWN 주문, 미정산, CLOSING(청산 대기), 미등록 실자금, 미분류 ERROR(뉴스 포함, 원문 표지 그대로).
  등록된 세션이라는 이유로 위험을 정상으로 바꾸지 않고, 뉴스 오류를 새 분류로 옮겨 정상 처리하지 않는다.
  522~587 모의 구역은 상태 개수(total/running/not_running)만 받는다.
종료값: 위험 없음 0 · 위험 있음 20 · 입력 무효 30 · 오류 40
"""
import argparse, hashlib, json, os, sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pins  # noqa: E402

TOOL, VERSION, IN_VERSION = 'parallel_report', 'w2-1', 'parallel-in-1'
EXIT = {'NO_RISK_ITEMS': 0, 'RISK_PRESENT': 20, 'INVALID': 30, 'ERROR': 40}
SESSION_KEYS = {'kind', 'id', 'mode', 'status'}
ORDER_KEYS = {'kind', 'session_id', 'order_id', 'side', 'coin_pair', 'state'}
POSITION_KEYS = {'kind', 'session_id', 'position_id', 'coin_pair', 'status'}
STATE_SCHEMA = {
    'input_version': str, 'synthetic': bool, 'source_note': str, 'observed_label': str,
    'sessions': (list, SESSION_KEYS), 'orders': (list, ORDER_KEYS), 'positions': (list, POSITION_KEYS),
    'settlement_unresolved': int,
    'p0_events': (list, {'code', 'label'}),
    'error_logs': (list, {'label', 'count'}),
    'paper_522_587': (dict, {'total', 'running', 'not_running'}),
}
ORDER_STATES = {'PENDING', 'INTENT_READY', 'SUBMITTING', 'SUBMITTED', 'PARTIAL_FILLED', 'FILLED', 'CANCELLED', 'FAILED',
                'INTENT_REJECTED', 'UNKNOWN'}
UNRESOLVED_ORDER = {'PENDING', 'INTENT_READY', 'SUBMITTING', 'SUBMITTED', 'PARTIAL_FILLED'}
POS_STATUS = {'OPEN', 'CLOSING', 'CLOSED'}
CATS = ('REGISTERED_LIVE', 'REGISTERED_DYNAMIC_REAL', 'UNREGISTERED_REAL')
CAT_KO = {'REGISTERED_LIVE': '등록 LIVE', 'REGISTERED_DYNAMIC_REAL': '등록 DYNAMIC REAL', 'UNREGISTERED_REAL': '미등록 REAL'}
LINKS = ['docs/evidence/dec-parallel-20261118/README.md (§2-1 real_* 3키 오경보, §2-3 영향표)',
         'docs/evidence/dec-parallel-registry-20261120/README.md (§1 고정 명령 충돌, §4 N03 초안)']


class Reject(ValueError):
    pass


def check_state(st):
    """허용 필드만 받는다. 거절 사유에는 경로·필드 이름만 쓰고 값은 쓰지 않는다."""
    probs = []
    if isinstance(st, dict) and '__load_error__' in st:
        return [f"상태 파일 {st['__load_error__'][0]}"]
    if not isinstance(st, dict):
        return ['상태 파일이 객체 아님']
    for k in sorted(set(st) - set(STATE_SCHEMA)):
        probs.append(f'허용되지 않은 최상위 필드 {k!r}(값 미출력)')
    for k, spec in STATE_SCHEMA.items():
        if k not in st:
            probs.append(f'필드 누락 {k!r}')
            continue
        v = st[k]
        if isinstance(spec, tuple):
            typ, keys = spec
            items = v if typ is list else [v]
            if not isinstance(v, typ):
                probs.append(f'{k}: 형식')
                continue
            for i, x in enumerate(items):
                path = f'{k}[{i}]' if typ is list else k
                if not isinstance(x, dict):
                    probs.append(f'{path}: 객체 아님')
                    continue
                for e in sorted(set(x) - keys):
                    probs.append(f'{path}: 허용되지 않은 필드 {e!r}(값 미출력)')
                for m in sorted(keys - set(x)):
                    probs.append(f'{path}: 필드 누락 {m!r}')
        elif typ_bad(v, spec):
            probs.append(f'{k}: 형식')
    if probs:
        return probs
    if st['input_version'] != IN_VERSION:
        probs.append(f'input_version 은 {IN_VERSION!r}')
    if st['settlement_unresolved'] < 0:
        probs.append('settlement_unresolved 음수')
    for i, s in enumerate(st['sessions']):
        if s['kind'] not in ('LIVE', 'DYNAMIC') or typ_bad(s['id'], int) or not isinstance(s['status'], str):
            probs.append(f'sessions[{i}]: kind/id/status 형식')
    for i, o in enumerate(st['orders']):
        if o['kind'] not in ('LIVE', 'DYNAMIC') or typ_bad(o['session_id'], int) or o['state'] not in ORDER_STATES \
                or o['side'] not in ('BUY', 'SELL'):
            probs.append(f'orders[{i}]: kind/session_id/side/state 형식')
    for i, p in enumerate(st['positions']):
        if p['kind'] not in ('LIVE', 'DYNAMIC') or typ_bad(p['session_id'], int) or p['status'] not in POS_STATUS:
            probs.append(f'positions[{i}]: kind/session_id/status 형식')
    for i, e in enumerate(st['error_logs']):
        if not isinstance(e['label'], str) or typ_bad(e['count'], int) or e['count'] < 1:
            probs.append(f'error_logs[{i}]: label/count 형식')
    pp = st['paper_522_587']
    if any(typ_bad(pp[k], int) or pp[k] < 0 for k in pp) or pp['running'] + pp['not_running'] != pp['total']:
        probs.append('paper_522_587: 개수 형식/합계')
    return probs


def typ_bad(v, t):
    return isinstance(v, bool) if t is int and isinstance(v, int) else not isinstance(v, t)


def real(x):
    # 제품 규칙: 정확히 'PAPER' 가 아니면 REAL 분기(세션 mode). 주문·포지션은 세션 표의 mode 를 따른다.
    return x.get('mode') != 'PAPER'


def classify(reg_entries, st):
    sess = {(s['kind'], s['id']): s for s in st['sessions']}
    paper = {k for k, s in sess.items() if not real(s)}

    def cat(kind, sid):
        if (kind, sid) in reg_entries:
            return 'REGISTERED_LIVE' if kind == 'LIVE' else 'REGISTERED_DYNAMIC_REAL'
        return 'UNREGISTERED_REAL'

    groups = {c: {'sessions': [], 'orders': [], 'positions': []} for c in CATS}
    risks = []
    for k, s in sorted(sess.items()):
        if k in paper:
            continue
        c = cat(*k)
        groups[c]['sessions'].append({'kind': k[0], 'id': k[1], 'status': s['status'], 'mode': s['mode']})
        if c == 'UNREGISTERED_REAL' and s['status'] in ('RUNNING', 'PAUSED', 'CREATED'):
            risks.append({'code': 'UNREGISTERED_REAL_SESSION_ACTIVE', 'kind': k[0], 'id': k[1], 'status': s['status']})
    for o in st['orders']:
        k = (o['kind'], o['session_id'])
        if k in paper:
            continue
        c = cat(*k)
        groups[c]['orders'].append({x: o[x] for x in ('kind', 'session_id', 'order_id', 'side', 'coin_pair', 'state')})
        if c == 'UNREGISTERED_REAL':
            risks.append({'code': 'UNREGISTERED_REAL_ORDER', 'kind': k[0], 'session_id': k[1], 'order_id': o['order_id'],
                          'state': o['state']})
        if o['state'] == 'UNKNOWN':
            risks.append({'code': 'ORDER_UNKNOWN', 'category': c, 'kind': k[0], 'session_id': k[1], 'order_id': o['order_id']})
    for p in st['positions']:
        k = (p['kind'], p['session_id'])
        if k in paper or p['status'] == 'CLOSED':
            continue
        c = cat(*k)
        groups[c]['positions'].append({x: p[x] for x in ('kind', 'session_id', 'position_id', 'coin_pair', 'status')})
        if c == 'UNREGISTERED_REAL':
            risks.append({'code': 'UNREGISTERED_REAL_POSITION', 'kind': k[0], 'session_id': k[1], 'position_id': p['position_id']})
        if p['status'] == 'CLOSING':
            risks.append({'code': 'POSITION_CLOSING_PENDING', 'category': c, 'kind': k[0], 'session_id': k[1],
                          'position_id': p['position_id'], 'coin_pair': p['coin_pair']})
    if st['settlement_unresolved']:
        risks.append({'code': 'SETTLEMENT_UNRESOLVED', 'count': st['settlement_unresolved']})
    for e in st['p0_events']:
        risks.append({'code': 'P0', 'p0_code': e['code'], 'label': e['label']})
    for e in st['error_logs']:
        risks.append({'code': 'UNCLASSIFIED_ERROR', 'label': e['label'], 'count': e['count'],
                      'note': '원문 표지 그대로 — 새 분류로 옮겨 정상 처리하지 않음'})
    unresolved = [o for c in CATS for o in groups[c]['orders'] if o['state'] in UNRESOLVED_ORDER]
    return groups, risks, sorted(paper), unresolved


def p7_quote(path, sha):
    """수용된 P7 결과를 인용만 한다. 해시가 다르면 인용하지 않는다."""
    if not path:
        return {'status': 'NOT_REFERENCED', 'note': 'P7 결과 미제공 — 계좌 일치 미확인'}
    raw = open(path, 'rb').read()
    got = hashlib.sha256(raw).hexdigest()
    if not sha or got != sha.lower():
        return {'status': 'REF_HASH_MISMATCH', 'actual_sha256': got}
    o = json.loads(raw.decode('utf-8'))
    acc = o.get('account_sum') if isinstance(o.get('account_sum'), dict) else {}
    return {'status': 'QUOTED', 'sha256': got, 'p7_overall': o.get('overall'), 'account_sum_status': acc.get('status'),
            'note': 'P7 v8 결과 인용 — 이 도구는 계좌 식을 계산하지 않음. account_sum 일치는 F3 통과가 아님'}


def render_md(out):
    L = [f"# 병행 N03 분류 후보 보고 ({'합성 입력' if out.get('synthetic_input') else '입력 표지 확인'})", '']
    if out['verdict'] in ('INVALID', 'ERROR'):
        L += [f"**입력 거절({out['verdict']})** — 처리하지 않았다. 사유(값 미출력):", ''] + [f'- {p}' for p in out['problems']]
        return '\n'.join(L) + '\n'
    L += [f"- 판정: **{'위험 항목 있음' if out['verdict'] == 'RISK_PRESENT' else '위험 항목 없음'}** (위험 {len(out['risks'])}건)",
          f"- 계좌 일치: {out['account_check']['status']}"
          + (f" — P7 {out['account_check']['p7_overall']}, account_sum {out['account_check']['account_sum_status']}"
             if out['account_check']['status'] == 'QUOTED' else ''),
          f"- 모의 522~587: 전체 {out['paper_522_587']['total']} · 실행 {out['paper_522_587']['running']} · 비실행 {out['paper_522_587']['not_running']}",
          '', '## 실자금 분류', '', '| 분류 | 세션 | 미종결 주문 | 열린/청산 대기 포지션 |', '|---|---|---|---|']
    for c in CATS:
        g = out['groups'][c]
        L.append(f"| {CAT_KO[c]} | {', '.join(f'{s['kind']}#{s['id']}:{s['status']}' for s in g['sessions']) or '-'} | "
                 f"{', '.join(f'{o['kind']}#{o['session_id']}/{o['coin_pair']} {o['side']} {o['state']}' for o in g['orders'] if o['state'] != 'FILLED') or '-'} | "
                 f"{', '.join(f'{p['kind']}#{p['session_id']}/{p['coin_pair']} {p['status']}' for p in g['positions']) or '-'} |")
    L += ['', '## 위험 항목(등록 여부와 별도)', '']
    L += [f"- `{r['code']}` " + ', '.join(f'{k}={v}' for k, v in r.items() if k != 'code') for r in out['risks']] or ['- 없음']
    L += ['', '## 근거 연결', ''] + [f'- {x}' for x in LINKS]
    L += ['', '이 보고는 설치본 N03 를 대체하지 않는다. 옛 운영 SQL 을 재실행하지 않았다.']
    return '\n'.join(L) + '\n'


def build(reg, reg_bytes, reg_sha, st, p7_path=None, p7_sha=None):
    R = pins.load('p7_v8')
    out = {'tool': TOOL, 'version': VERSION, 'p7_v8_sha256': pins.verify('p7_v8')[1], 'problems': []}
    # 금지 필드 거절이 먼저다 — 상태 파일 내용은 그 전에 어떤 처리도 하지 않는다.
    out['problems'] += check_state(st)
    got = hashlib.sha256(reg_bytes).hexdigest() if reg_bytes is not None else None
    if got != reg_sha.lower():
        out['problems'].append('REGISTRY_HASH_MISMATCH')
    res = R.Result()
    entries, rstatus = R.load_registry(reg, res)
    if rstatus != 'valid' or (isinstance(reg, dict) and reg.get('registry_version') != R.REGISTRY_V3):
        out['problems'].append(f'REGISTRY_{rstatus.upper()}' if rstatus != 'valid' else 'REGISTRY_NOT_V3')
    if out['problems']:
        out['verdict'] = 'INVALID'
        return out
    out['synthetic_input'] = st['synthetic']
    groups, risks, paper, unresolved = classify(entries, st)
    out.update(groups=groups, risks=risks, paper_sessions_excluded=[{'kind': k, 'id': i} for k, i in paper],
               unresolved_orders=len(unresolved), paper_522_587=st['paper_522_587'],
               account_check=p7_quote(p7_path, p7_sha), evidence_links=LINKS)
    out['verdict'] = 'RISK_PRESENT' if risks else 'NO_RISK_ITEMS'
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--registry', required=True)
    ap.add_argument('--registry-sha256', required=True)
    ap.add_argument('--state', required=True)
    ap.add_argument('--p7-result')
    ap.add_argument('--p7-result-sha256')
    ap.add_argument('--out-json')
    ap.add_argument('--out-md')
    a = ap.parse_args(argv)
    try:
        R = pins.load('p7_v8')
        try:
            reg_bytes = open(a.registry, 'rb').read()
        except OSError:
            reg_bytes = None
        out = build(R.load_file(a.registry), reg_bytes, a.registry_sha256, R.load_file(a.state), a.p7_result, a.p7_result_sha256)
    except pins.PinError as e:
        out = {'tool': TOOL, 'version': VERSION, 'verdict': 'ERROR', 'problems': [f'PinError: {e}']}
    except Exception as e:  # noqa: BLE001
        out = {'tool': TOOL, 'version': VERSION, 'verdict': 'ERROR', 'problems': [f'{type(e).__name__}(입력 값 미출력)']}
    out['exit'] = EXIT[out['verdict']]
    pins.dump(out, a.out_json)
    if a.out_md:
        with open(a.out_md, 'w', encoding='utf-8', newline='\n') as f:
            f.write(render_md(out))
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
