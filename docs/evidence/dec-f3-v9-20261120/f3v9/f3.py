"""F3 파일 판정기 v9(총괄 v1.207 §3-1) — v8(dec-registry-p7v8-20261120/p7v8/f3.py, 원문 보존) 사본.
v9 보완: 형식 결함(shape)이 하나라도 있으면 뒤 필드 접근 전에 사유와 불통과를 반환한다.
  중첩 exchange_coverage.window 가 객체인지도 shape 에서 먼저 확인한다. 예외를 통과·빈 목록으로 바꾸지 않는다.
--- 이하 v8 설명 ---
F3 파일 판정기 v8(총괄 v1.204 §2 F1~F3·v1.206 §4-1).
v8 추가(전체 OK 문자열만 믿지 않는다): anomalies·holds 가 실제 빈 목록(list)인지, 필수 필드의 타입,
overall 과 목록의 논리 일치, 코드 출처 base/now 가 16진 7~40자이고 서로 같으며 출처 참조가 있는지.
이는 파일 내부 일관성 검사이며 수작업으로 꾸민 파일의 진위를 보증하지 않는다.
통과 조건(모두 필요 — 하나라도 아니면 불통과, 보류를 경고로 낮추지 않는다):
  overall == OK(이상·보류 0) · registry_status == valid · 두 관측 ok · observation_stable · 지문 3값 일치(보류 코드 없음)
  · 코드 출처 확인(code.now 존재·기준과 같음) · 거래소 완료 조회 구간 '일치' · account 판정 '일치' · account_sum.status == '일치'.
account_sum.status == '일치' 단독으로는 통과시키지 않는다(P1 규칙 지문 미확인·P2 지문 불일치·P4 조회 미완료 반례).
사용: python -I f3.py --p7 <P7 출력.json>   종료값: 0 통과, 1 불통과, 2 입력 읽기 실패.
"""
import argparse, json, re, sys

SHA_RE = re.compile(r'^[0-9a-fA-F]{7,40}$')


def sha_eq(a, b):
    a, b = a.lower(), b.lower()
    return a.startswith(b) or b.startswith(a)


def shape(o):
    """v8: 필수 필드 타입·목록·논리 모순. 하나라도 있으면 불통과."""
    why = []
    for k in ('anomalies', 'holds'):
        if not isinstance(o.get(k), list):
            why.append(f'{k} 가 목록이 아님/누락 — 빈 목록 확인 불가')
        elif o[k]:
            why.append(f"{k} 비어 있지 않음 {[x.get('code') if isinstance(x, dict) else x for x in o[k]]}")
    for k, t in (('overall', str), ('registry_status', str), ('observation', dict), ('observation_stable', bool),
                 ('code', dict), ('exchange_coverage', dict), ('account', dict), ('account_sum', dict)):
        if not isinstance(o.get(k), t):
            why.append(f'{k} 누락 또는 타입 불일치({t.__name__} 필요)')
    cov = o.get('exchange_coverage')
    if isinstance(cov, dict) and not isinstance(cov.get('window'), dict):
        why.append('exchange_coverage.window 누락 또는 타입 불일치(dict 필요)')
    an, ho = o.get('anomalies'), o.get('holds')
    if isinstance(an, list) and isinstance(ho, list):
        want = 'ANOMALY' if an else ('HOLD' if ho else 'OK')
        if o.get('overall') != want:
            why.append(f"overall {o.get('overall')} 이 목록과 모순(목록상 {want})")
    code = o.get('code')
    if isinstance(code, dict):
        b, n = code.get('base'), code.get('now')
        if not (isinstance(b, str) and SHA_RE.match(b) and isinstance(n, str) and SHA_RE.match(n)):
            why.append('코드 출처 base/now 가 16진 7~40자 문자열 아님')
        elif not sha_eq(b, n):
            why.append(f'코드 출처 base {b} ≠ now {n} — 구간 중 코드 변경인데 보류 없음')
        for k in ('base_ref', 'now_ref'):
            if not (isinstance(code.get(k), str) and code[k].strip()):
                why.append(f'코드 출처 {k} 없음')
    return why


def judge(o):
    why = []
    if not isinstance(o, dict):
        return False, ['P7 출력이 객체 아님']
    why += shape(o)
    if why:  # v9: 형식 결함이면 뒤 필드 접근 없이 불통과
        return False, why
    if o.get('overall') != 'OK':
        why.append(f"전체 판정 {o.get('overall')} — 이상 {[a.get('code') for a in o.get('anomalies') or []]}, "
                   f"보류 {[h.get('code') for h in o.get('holds') or []]}")
    if o.get('registry_status') != 'valid':
        why.append(f"등록표 {o.get('registry_status')}")
    if o.get('observation') != {'base': 'ok', 'now': 'ok'}:
        why.append(f"관측 {o.get('observation')}")
    if o.get('observation_stable') is not True:
        why.append('관측 안정 미확인(지문·거래소 전후·수집 순서)')
    code = o.get('code') or {}
    if not code.get('now') or not code.get('base'):
        why.append('코드 출처 미확인')
    cov = (o.get('exchange_coverage') or {}).get('window') or {}
    if cov.get('status') != '일치':
        why.append(f"거래소 완료 조회 구간 {cov.get('status')}: {cov.get('reason')}")
    acc = o.get('account') or {}
    if acc.get('verdict') != '일치':
        why.append(f"계좌 대조 {acc.get('verdict')}")
    sm = o.get('account_sum') or {}
    if sm.get('status') != '일치':
        why.append(f"계좌 합산 {sm.get('status')}")
    return not why, why


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--p7', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    try:
        o = json.loads(open(a.p7, 'rb').read().decode('utf-8'))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
        print(json.dumps({'f3': '읽기 실패', 'error': type(e).__name__}, ensure_ascii=False))
        return 2
    ok, why = judge(o)
    print(json.dumps({'f3': '통과' if ok else '불통과', 'reasons': why}, ensure_ascii=False))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
