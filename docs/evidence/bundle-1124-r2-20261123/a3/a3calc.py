# A3 미배정 현금 산출 명세의 파일 사례 확인기(합성 입력 전용, 조회 없음). 총괄 v1.226 §3-2.
# 입력 JSON: acct = 계좌 요약 응답의 data 부분(availableKrw·lockedKrw·totalKrwBalance·fetchedAt 또는 error)
#            t0/t1 = 호출 직전/직후 서버 시각(UTC ISO), db = 같은 회차 장부 SQL의 DB now()(UTC ISO)
#            sessions = [{sid, available_krw(문자열, 소수 2자리)}], active_orders·cleanup_pending·events_in_gap(정수)
#            need = 새 배정에 필요한 금액(선택), expected_total = 자금 이벤트 기록 기반 기대 KRW 총액(선택)
import json, sys
from decimal import Decimal as D
def calc(c):
    a = c.get('acct', {})
    if 'error' in a or 'totalKrwBalance' not in a:
        return '미확정', '계좌 조회 실패/KRW 필드 없음 — 0으로 채우지 않는다'
    if not (c.get('t0') and c.get('t1') and c.get('db')) or not (c['t0'] <= a.get('fetchedAt', '') <= c['t1']):
        return '보류', '호출 전후 구간(t0≤fetchedAt≤t1)·DB 시각이 없음 — 계좌와 장부의 시각 관계 미정'
    if c.get('events_in_gap', 0) or c.get('active_orders', 0) or c.get('cleanup_pending', 0) or D(str(a['lockedKrw'])) != 0:
        return '보류', '구간 안 주문/체결·진행 주문·정리 대기 또는 묶인 KRW 있음 — 예약/배정 중복 차감을 피하려 산출하지 않는다'
    R = D(str(a['totalKrwBalance']))              # 원천 b+l 을 HALF_UP 1회 정수화한 값 → 원천은 [R-0.5, R+0.5)
    S = sum(D(s['available_krw']) for s in c['sessions'])
    lo, hi = R - D('0.5') - S, R + D('0.5') - S    # 미배정 현금 원천 범위(하한 포함·상한 제외)
    two = D(str(a['availableKrw'])) + D(str(a['lockedKrw']))
    note = '' if two == R else ' · 참고: availableKrw+lockedKrw=%s ≠ totalKrwBalance=%s(각각 반올림, 판정에 쓰지 않음)' % (two, R)
    out = '미배정 원천 범위 [%s, %s)%s' % (lo, hi, note)
    if 'expected_total' in c:
        E = D(c['expected_total'])
        if not (R - D('0.5') <= E < R + D('0.5')):
            return '보류', '계좌 KRW 총액 %s 이 기록 기대 %s 의 반올림 범위 밖 — 확정 설명 없는 차이, 허용 오차로 흡수하지 않고 보고' % (R, E)
    if 'need' in c:
        N = D(c['need'])
        if lo >= N: return '충분', out
        if hi <= N: return '부족', out
        return '미확정', out + ' · 필요 %s 가 범위 안 — 반올림 경계' % N
    return '범위만', out
for p in sys.argv[1:]:
    c = json.load(open(p, encoding='utf-8')); v, m = calc(c)
    ok = 'OK' if v == c['expect'] else 'MISMATCH'
    print('%s\t%s\t기대 %s\t%s\t%s' % (p.split('/')[-1], v, c['expect'], ok, m))
