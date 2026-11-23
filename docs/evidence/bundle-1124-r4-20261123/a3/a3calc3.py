# A3 미배정 현금 산출 확인기 v3(합성 입력 전용, 조회 없음). 총괄 v1.228 §3-2.
# v2 원문 bundle-1124-r3-20261123/a3/a3calc2.py(f9552229) 는 보존한다. v3 가 바꾼 것은 변화 근거 구간의 비교 하나뿐이다:
#   - v2 는 변화 근거가 DB 축(axis=db)일 때도 stability.from/to(DB 시각)를 서버 축 호출 구간과 그대로 비교했다(총괄 반례: 00~05초 근거가
#     ±2초 관계에서 서버 축으로 확실히 덮는 것은 02~03초뿐인데 '충분').
#   - v3: DB–서버 관계 근거에 적용 범위(valid_from·valid_to, 서버 축)를 요구한다. 범위가 없거나 변화 근거 구간(±최대 차이)을 덮지 않으면 보류.
#     그 범위 안에서 서버 시각 = DB 시각 + d, |d| ≤ max_skew_s 이므로 확실히 덮는 서버 구간 = [from + k, to − k]. 이 구간이 두 호출 구간 전체를
#     포함할 때만 변화 근거를 쓴다. 축 표지만 server 로 바꾸거나 오차 구간의 합집합([from − k, to + k])으로 통과시키지 않는다.
#   - 시각은 소수 초(최대 6자리, 마이크로초)도 받는다 — 읽기 원문이 서버 date %6N·DB clock_timestamp() 마이크로초를 그대로 남긴다.
# 이하 v2 설명.
# 앞 판 bundle-1124-r2-20261123/a3/a3calc.py 는 보존한다. v2 가 바꾼 것:
#   - DB now() 를 스냅숏 시각으로 쓰지 않는다. 변화 근거가 DB 시계일 때만 DB–서버 관계 근거와 함께 축 정합 확인에 쓴다. 장부 읽기도 계좌 호출처럼 서버 시계 전후 구간[t0,t1]을 받는다.
#   - 앱 시계(fetchedAt)와 서버 시계의 관계는 '원천·범위·최대 차이(초)'가 적힌 근거가 있을 때만 쓴다. 없으면 보류.
#   - 두 읽기(계좌·장부)를 모두 덮는 구간에서 계좌 KRW·세션 잔액·주문이 변하지 않았다는 근거(원천·덮는 구간)가 없으면 보류.
#   - 진행 주문·정리 대기·구간 이벤트는 값과 원천이 모두 있어야 한다. 키가 없으면 0으로 두지 않고 보류.
#   - 세션 합 S 는 대상(배정 기록의 포함 범위)과 자금 원천을 받아야 한다. RUNNING/CREATED 합만으로
#     다른 상태의 배정 잔액이 계좌에 없다고 단정하지 않는다 → 배정/회수 기록으로 전체 포함을 확인하지 못하면 보류.
# 시각은 모두 UTC ISO 'YYYY-MM-DDTHH:MM:SSZ'. 반올림 범위·기대 총액·필요액 판정 규칙은 v1 과 같다.
import json, sys
from datetime import datetime, timedelta
from decimal import Decimal as D
P = lambda s: datetime.strptime(s, '%Y-%m-%dT%H:%M:%S.%fZ' if '.' in s else '%Y-%m-%dT%H:%M:%SZ')
def has_src(x):
    return isinstance(x, dict) and bool(str(x.get('source', '')).strip()) and bool(str(x.get('scope', '')).strip())
def calc(c):
    a = c.get('acct', {})
    if 'error' in a or 'totalKrwBalance' not in a:
        return '미확정', '계좌 조회 실패/KRW 필드 없음 — 0으로 채우지 않는다'
    ca, cl = c.get('calls', {}).get('account'), c.get('calls', {}).get('ledger')
    for nm, x in (('계좌', ca), ('장부', cl)):
        if not (isinstance(x, dict) and x.get('t0') and x.get('t1') and x.get('clock') == 'server'):
            return '보류', '%s 호출의 서버 시계 전후 구간(t0·t1) 없음 — DB now()·앱 시각으로 대신하지 않는다' % nm
        if P(x['t0']) > P(x['t1']):
            return '보류', '%s 호출 구간 t0>t1 — 시각 기록 이상' % nm
    cr = c.get('clock_relation')
    if not (has_src(cr) and cr.get('axes') == ['app', 'server'] and 'max_skew_s' in cr):
        return '보류', '앱 시계(fetchedAt)–서버 시계 관계의 원천·범위·최대 차이 근거 없음 — 같은 시계라고 가정하지 않는다'
    k = timedelta(seconds=float(cr['max_skew_s']))
    f = P(a['fetchedAt']) if a.get('fetchedAt') else None
    if f is None or not (P(ca['t0']) - k <= f <= P(ca['t1']) + k):
        return '보류', 'fetchedAt 이 계좌 호출 구간(±최대 차이) 밖 — 축 관계 불일치'
    lo_t, hi_t = min(P(ca['t0']), P(cl['t0'])), max(P(ca['t1']), P(cl['t1']))
    st = c.get('stability')
    if not (has_src(st) and st.get('from') and st.get('to') and 'changed' in st):
        return '보류', '두 읽기 사이 변화 유무의 원천·덮는 구간 근거 없음'
    if st.get('axis') not in ('server', 'db'):
        return '보류', '변화 확인 근거의 시계 축(server/db) 미기재'
    if st['axis'] == 'server':
        g_from, g_to = P(st['from']), P(st['to'])   # 같은 축 — 변환 없음
    else:   # 장부 주문·체결 시각(DB 시계)으로 변화 없음을 본 경우: DB–서버 관계 근거(적용 범위 포함)와 db_now 정합이 필요
        dr = c.get('db_clock_relation')
        if not (has_src(dr) and dr.get('axes') == ['db', 'server'] and 'max_skew_s' in dr and cl.get('db_now')):
            return '보류', '변화 근거가 DB 시계인데 DB–서버 시계 관계의 원천·범위·최대 차이 또는 db_now 없음'
        kd = timedelta(seconds=float(dr['max_skew_s']))
        if not (P(cl['t0']) - kd <= P(cl['db_now']) <= P(cl['t1']) + kd):
            return '보류', 'db_now %s 가 장부 호출 구간(±최대 차이) 밖 — DB 시계 축 불일치, 변화 근거를 쓸 수 없음' % cl['db_now']
        if not (dr.get('valid_from') and dr.get('valid_to')):
            return '보류', 'DB–서버 시계 관계의 적용 범위(valid_from·valid_to, 서버 축) 불명 — 변화 근거 구간을 서버 축으로 옮길 수 없음'
        v0, v1 = P(dr['valid_from']), P(dr['valid_to'])
        if not (v0 <= P(st['from']) - kd and P(st['to']) + kd <= v1):
            return '보류', 'DB–서버 관계 적용 범위 %s~%s 가 변화 근거 구간 %s~%s(±%ss) 를 덮지 않음' % (dr['valid_from'], dr['valid_to'], st['from'], st['to'], dr['max_skew_s'])
        g_from, g_to = P(st['from']) + kd, P(st['to']) - kd   # 모든 허용 차이에서 확실히 덮는 서버 구간
        if g_from > g_to:
            return '보류', '변화 근거 구간 %s~%s 가 최대 차이 ±%ss 보다 짧아 서버 축에서 확실히 덮는 구간이 없음' % (st['from'], st['to'], dr['max_skew_s'])
    if not (g_from <= lo_t and hi_t <= g_to):
        return '보류', '변화 근거가 서버 축에서 확실히 덮는 구간 %s~%s 이 두 읽기 구간 %s~%s 를 포함하지 않음' % (g_from.isoformat(), g_to.isoformat(), lo_t.isoformat(), hi_t.isoformat())
    if st['changed'] is not False:
        return '보류', '두 읽기 사이 변화 있음/불명 — 산출하지 않는다'
    for key in ('active_orders', 'cleanup_pending', 'events_in_gap'):
        v = c.get(key)
        if not (has_src(v) and isinstance(v.get('count'), int)):
            return '보류', '%s 값·원천 누락 — 기본 0으로 두지 않는다' % key
        if v['count'] != 0:
            return '보류', '진행 주문·정리 대기·구간 이벤트 있음(%s=%d) — 예약/배정 중복 차감을 피하려 산출하지 않는다' % (key, v['count'])
    if D(str(a['lockedKrw'])) != 0:
        return '보류', '묶인 KRW 있음 — 산출하지 않는다'
    al = c.get('allocation_scope')
    if not (has_src(al) and al.get('all_allocations_listed') is True and str(al.get('fund_source', '')).strip()):
        return '보류', '세션 합 S 의 대상(배정/회수 기록으로 전체 포함 확인)·자금 원천 미확인 — 미배정 확정 보류'
    R = D(str(a['totalKrwBalance']))
    S = sum(D(s['available_krw']) for s in c['sessions'])
    lo, hi = R - D('0.5') - S, R + D('0.5') - S
    two = D(str(a['availableKrw'])) + D(str(a['lockedKrw']))
    note = '' if two == R else ' · 참고: availableKrw+lockedKrw=%s ≠ totalKrwBalance=%s' % (two, R)
    ref = ' · 참고 DB now()=%s(스냅숏 시각으로 쓰지 않음, DB 축 정합 확인에만 씀)' % cl.get('db_now') if cl.get('db_now') else ''
    out = '미배정 원천 범위 [%s, %s)%s%s' % (lo, hi, note, ref)
    if 'expected_total' in c:
        E = D(c['expected_total'])
        if not (R - D('0.5') <= E < R + D('0.5')):
            return '보류', '계좌 KRW 총액 %s 이 기록 기대 %s 의 반올림 범위 밖 — 허용 오차로 흡수하지 않고 보고' % (R, E)
    if 'need' in c:
        N = D(c['need'])
        if lo >= N: return '충분', out
        if hi <= N: return '부족', out
        return '미확정', out + ' · 필요 %s 가 범위 안 — 반올림 경계' % N
    return '범위만', out
if __name__ == '__main__':
    for p in sys.argv[1:]:
        c = json.load(open(p, encoding='utf-8')); v, m = calc(c)
        e = c.get('expect_v3', c.get('expect_v2', c.get('expect')))
        print('%s\t%s\t기대 %s\t%s\t%s' % (p.replace('\\', '/').split('/')[-1], v, e, 'OK' if v == e else 'MISMATCH', m))
