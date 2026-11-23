# A3 미배정 현금 산출 확인기 v2(합성 입력 전용, 조회 없음). 총괄 v1.227 §3-2.
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
P = lambda s: datetime.strptime(s, '%Y-%m-%dT%H:%M:%SZ')
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
    if not (P(st['from']) <= lo_t and hi_t <= P(st['to'])):
        return '보류', '변화 확인 구간 %s~%s 이 두 읽기 구간 %sZ~%sZ 를 덮지 않음' % (st['from'], st['to'], lo_t.isoformat(), hi_t.isoformat())
    if st['changed'] is not False:
        return '보류', '두 읽기 사이 변화 있음/불명 — 산출하지 않는다'
    if st.get('axis') not in ('server', 'db'):
        return '보류', '변화 확인 근거의 시계 축(server/db) 미기재'
    if st['axis'] == 'db':   # 장부 주문·체결 시각(DB 시계)으로 변화 없음을 본 경우: DB–서버 관계 근거와 db_now 정합이 필요
        dr = c.get('db_clock_relation')
        if not (has_src(dr) and dr.get('axes') == ['db', 'server'] and 'max_skew_s' in dr and cl.get('db_now')):
            return '보류', '변화 근거가 DB 시계인데 DB–서버 시계 관계의 원천·범위·최대 차이 또는 db_now 없음'
        kd = timedelta(seconds=float(dr['max_skew_s']))
        if not (P(cl['t0']) - kd <= P(cl['db_now']) <= P(cl['t1']) + kd):
            return '보류', 'db_now %s 가 장부 호출 구간(±최대 차이) 밖 — DB 시계 축 불일치, 변화 근거를 쓸 수 없음' % cl['db_now']
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
        e = c.get('expect_v2', c.get('expect'))
        print('%s\t%s\t기대 %s\t%s\t%s' % (p.replace('\\', '/').split('/')[-1], v, e, 'OK' if v == e else 'MISMATCH', m))
