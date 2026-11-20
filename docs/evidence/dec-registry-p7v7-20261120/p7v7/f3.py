"""F3 파일 판정기 후보(총괄 v1.203 §2-5·§3-2) — P7 v7 출력 JSON 하나를 읽어 F3 통과 여부만 낸다. 운영·DB·네트워크 접속 없음.
통과 조건(모두 필요 — 하나라도 아니면 불통과, 보류를 경고로 낮추지 않는다):
  overall == OK(이상·보류 0) · registry_status == valid · 두 관측 ok · observation_stable · 지문 3값 일치(보류 코드 없음)
  · 코드 출처 확인(code.now 존재·기준과 같음) · 거래소 완료 조회 구간 '일치' · account 판정 '일치' · account_sum.status == '일치'.
account_sum.status == '일치' 단독으로는 통과시키지 않는다(P1 규칙 지문 미확인·P2 지문 불일치·P4 조회 미완료 반례).
사용: python -I f3.py --p7 <P7 출력.json>   종료값: 0 통과, 1 불통과, 2 입력 읽기 실패.
"""
import argparse, json, sys


def judge(o):
    why = []
    if not isinstance(o, dict):
        return False, ['P7 출력이 객체 아님']
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
