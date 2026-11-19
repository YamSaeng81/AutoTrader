"""제품 OrderRepository 의 조회문 원문(자바 문자열 상수 연결)을 그대로 풀어 배포 전 읽기안 v2 를 만든다 — 손으로 옮기지 않는다."""
import re, sys, hashlib
src_path = sys.argv[1]; out_path = sys.argv[2]; sha = sys.argv[3]
src = open(src_path, encoding='utf-8').read()

def java_concat(expr, consts):
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"|([A-Z_]{3,})', expr)
    out = []
    for lit, name in parts:
        if name:
            out.append(consts[name])
        else:
            out.append(lit.replace('\\"', '"'))
    return ''.join(out)

consts = {}
for name in ('SELL_FIELDS_KNOWN', 'CONFIRMED_NO_TRADE', 'BUY_COST_BY_POSITION'):
    m = re.search(r'String ' + name + r' = (.*?);\n', src, re.S)
    consts[name] = java_concat(m.group(1), consts)

def query_of(method):
    end = re.search(r'\s' + method + r'\(', src).start()
    start = src.rindex('@Query(value = ', 0, end) + len('@Query(value = ')
    expr = src[start:end]
    expr = expr[:expr.rindex('nativeQuery')]
    return java_concat(expr, consts)

q_count = query_of('countRealSessionCostUndeterminedSince')
q_live = query_of('sumLiveSessionRealizedLossSince')
q_dyn = query_of('sumDynamicRealSessionRealizedLossSince')
q_parts = query_of('costBasisPartsByPosition')

since = "(now() - interval '30 days')"
r1 = "SELECT k.kind, (" + q_count.replace(':kind', 'k.kind').replace(':since', since) + ") AS undetermined_30d\nFROM (VALUES ('LIVE'), ('DYNAMIC')) k(kind);"
r2 = ("SELECT 'LIVE' AS kind, (" + q_live.replace(':since', since) + ") AS loss_30d\nUNION ALL\n"
      "SELECT 'DYNAMIC', (" + q_dyn.replace(':since', since) + ");")
# R3: 세션 연속 손실 입력 — 제품 checkCircuitBreaker 의 조회 범위 그대로(LIVE: 그 세션 CLOSED 전체, DYNAMIC: started_at 뒤), 포지션별 재료 행
parts_sel = q_parts.split(' FROM position p WHERE p.id IN (:ids)')[0]
r3 = (parts_sel + ",\n  p.session_kind, p.session_id, p.closed_at, p.realized_pnl AS stored_pnl, p.exit_reason,\n"
      "  ROW_NUMBER() OVER (PARTITION BY p.session_kind, p.session_id ORDER BY p.closed_at DESC NULLS LAST) AS newest_rank\n"
      "FROM position p\n"
      "WHERE p.status = 'CLOSED' AND (\n"
      "  (p.session_kind = 'LIVE' AND p.session_id IN (SELECT id FROM live_trading_session WHERE status = 'RUNNING' AND session_type = 'REAL'))\n"
      "  OR (p.session_kind = 'DYNAMIC' AND EXISTS (SELECT 1 FROM dynamic_session d WHERE d.id = p.session_id AND d.status = 'RUNNING'\n"
      "        AND d.trading_mode = 'REAL' AND (d.started_at IS NULL OR p.closed_at > d.started_at))))\n"
      "ORDER BY p.session_kind, p.session_id, newest_rank;")

hdr = f"""-- 비용 기준 후보 배포 전 읽기안 v2 (총괄 v1.192 §2-5) — 읽기 전용 SELECT 만. 개발 세션은 운영에서 실행하지 않았다.
-- 조회문은 후보 {sha} 의 OrderRepository 상수·@Query 원문을 기계적으로 풀어 만들었다(gen_predeploy.py). :since = now() - 30일.
-- 실행 시점·사람·출처는 별도 승인. 결과는 개수·합계만 남기고 행 원문은 필요할 때만.
-- v1(dec-costbasis-20261119/predeploy_read.sql)의 R3 설명(최근5건·DYNAMIC started_at)은 SQL에 없었고 NULL 원가 집계도 제품과 달랐다 — v2 로 대체.
"""
body = (hdr +
 "\n-- R1. 전역 관문의 판정 불가 수(30일, 제품 countRealSessionCostUndeterminedSince 그대로). 0 이 아니면 배포 직후 그 종류의 신규 REAL 진입이 거절된다.\n"
 "--     해제: 확정 원천이 정산/정정으로 갖춰지면 다음 조회에서 빠진다. 원천이 끝내 없으면 30일 창에서 빠질 때까지 전역 관문은 거절.\n" + r1 + "\n"
 "\n-- R2. 전역 손실 합(30일, 제품 두 식 그대로 — 판정 불가 행은 합에 없고 R1 이 센다).\n" + r2 + "\n"
 "\n-- R3. 세션 연속 손실 입력(제품 조회 범위 그대로: LIVE 는 RUNNING REAL 세션의 CLOSED 전체, DYNAMIC 은 RUNNING REAL 의 started_at 뒤).\n"
 "--     열: [id, 체결BUY, 원가미상BUY, 매수원가합, 매수수량합, 확정SELL, 매도순대금합, 매도수량합, 필수값미상SELL, 무거래확정] + 세션·시각·저장손익.\n"
 "--     newest_rank 1 부터 읽어 판정 불가(무거래 아님인데 체결BUY·확정SELL 0, 원가/필수값 미상, 매도수량<매수수량)가 연속 손실 한도 안에 있으면 그 세션 진입 차단.\n"
 "--     🔴 이 범위는 30일 창과 별개다 — LIVE 는 시간 제한이 없어 30일 뒤에도 차단이 남을 수 있다.\n" + r3 + "\n")
open(out_path, 'w', encoding='utf-8', newline='\n').write(body)
print('ok', hashlib.sha256(body.encode()).hexdigest()[:12])
