import sys
src, dst = sys.argv[1], sys.argv[2]
L = open(src, encoding='utf-8', newline='').read().split('\n')
guard = '\n'.join(L[38:62])   # set .. META SELECT (lines 39..62)
assert L[61].strip() == "current_setting('lock_timeout');", L[61]
pc = '\n'.join(L[101:148])    # pc AS ( ... )))))),  (lines 102..148)
assert L[101].startswith('pc AS ('), L[101]
assert L[147].rstrip().endswith(')))))),'), L[147]
assert L[38].startswith('\\set ON_ERROR_STOP'), L[38]
guard = guard.replace("'META', 'r1-diag-2'", "'META', 'r1-diag-3'")
assert "'r1-diag-3'" in guard and guard.count("'META'") == 1
head = r"""-- R1 DYNAMIC NO_SOURCE 연결 읽기 r1-diag-3 (총괄 v1.237 §3-2 최소 추가 증거안 초안). **운영 실행 승인 아님. PG 문법·실행 미검증.**
-- 목적: r1-diag-2(4134c631)가 ④ NO_SOURCE 로 분류한 DYNAMIC CLOSED 포지션을, 주문 없이 닫는 네 제품 경로 중 어디서 생겼는지 금액 없이 가른다.
--   경로 E(예약 거절, 0e1d2a02 DynamicTradingService:1764~1774) — 같은 틱 트랜잭션에서 size=0 슬롯을 저장하고 바로 CLOSED. 주문 행 없음.
--      같은 틱의 strategy_log 행에 was_executed=false, blocked_reason='공유 풀 예약 거절(<코드>): …' 가 남는다(:893/:1272 → updateSignalQuality).
--   경로 D(세션 삭제 :716) · 경로 O(고아 정리 주문 없음 :3254, opened_at 뒤 5분 이상) · 경로 S(정지 settleEmptyPosition :2626, 매수 주문 없음).
-- pc CTE 는 r1-diag-2.sql 102~148행을 글자 그대로 옮겼다. 그 밖의 ORIG/SPLIT 분해는 다시 내지 않는다(r1-diag-2 결과 수용).
-- 추가로 읽는 열(분류·비교만, 값 출력 없음): position.coin_pair·size·opened_at · dynamic_session.status·stopped_at
--   strategy_log.session_type·session_id·coin_pair·was_executed·blocked_reason(앞 접두와 괄호 안 코드만)·created_at
-- 읽지 않는 것: 금액·수량·가격·잔액·손익 값, blocked_reason 의 괄호 뒤 원문, 행 ID, paper_trading 스키마. 출력은 전부 개수·코드·분 단위 시각.
-- 출력(탭, NULL=\N): META 10열 · 그 밖 tag+c1~c4 5열 · END 2열
--   NS       n_rows  n_sessions                          (DYNAMIC NO_SOURCE 대상 수와 고유 세션 수)
--   SZ       class   rows      class: ZERO | NULL | POSITIVE   (size 값 자체는 내지 않는다. POSITIVE 는 0 아님·NULL 아님)
--   GAP      bucket  rows      bucket: G_LT_5S | G_5S_5M | G_GE_5M | G_NULL  (closed_at - opened_at)
--   SS       status  rows      (읽는 시점의 세션 상태: RUNNING STOPPED EMERGENCY_STOPPED DELETED NO_ROW OTHER)
--   STOPNEAR rows              (closed_at 이 세션 stopped_at ±5초 안 — 경로 D/S 후보)
--   LINK     class   rows      class: REJECT_LINKED | OTHER_LOG_ONLY | NO_LOG
--            REJECT_LINKED = 같은 세션·같은 코인의 strategy_log(DYNAMIC, was_executed=false, blocked_reason 접두 '공유 풀 예약 거절(')가
--            created_at ∈ [opened_at - 6분, opened_at + 5초] 에 1건 이상. (6분 = 회차 승인 마감 유예 5분 + 여유 1분)
--   CODE     code    rows      (REJECT_LINKED 행의 괄호 안 코드, ^[A-Z][A-Z0-9_]{0,39}$ 밖이면 '(nonconforming)'. 여러 건이면 가장 늦은 로그)
--   PATH     class   rows      class: E_ONLY | D_OR_S_ONLY | O_ONLY | MULTI | NONE  (배타 조합 — 합 = n_rows)
--   EXP      last_expiry_min_utc  first_expiry_min_utc   (max/min(closed_at)+30일을 분 단위로 자른 시각)
--   NEW      rows_closed_after_prev_read                 (closed_at >= 2026-11-01T04:49:14Z — 지난 읽기 AGE 상한 뒤, 새 발생 표지)
-- 보호값: statement_timeout=15s·lock_timeout=2s 확인(R1D_GUARD), READ ONLY·REPEATABLE READ·ROLLBACK. r1-diag-2 와 같다.
"""
body = r"""
WITH
""" + pc + r"""
ns AS (
  SELECT c.pid, c.sid, c.t AS closed_at, c.opened_at, p.coin_pair, p.size,
         d.status AS sst, d.stopped_at
  FROM pc c
  JOIN position p ON p.id = c.pid
  LEFT JOIN dynamic_session d ON d.id = c.sid
  WHERE c.kind = 'DYNAMIC' AND c.n_any AND NOT c.q_any),
lk AS (
  SELECT n.pid,
         (SELECT substring(sl.blocked_reason FROM '^공유 풀 예약 거절\(([^)]{0,60})\)')
            FROM strategy_log sl
           WHERE sl.session_type = 'DYNAMIC' AND sl.session_id = n.sid AND sl.coin_pair = n.coin_pair
             AND sl.was_executed = false AND sl.blocked_reason LIKE '공유 풀 예약 거절(%'
             AND n.opened_at IS NOT NULL
             AND sl.created_at >= n.opened_at - interval '6 minutes' AND sl.created_at <= n.opened_at + interval '5 seconds'
           ORDER BY sl.created_at DESC LIMIT 1) AS code,
         EXISTS (SELECT 1 FROM strategy_log sl
                  WHERE sl.session_type = 'DYNAMIC' AND sl.session_id = n.sid AND sl.coin_pair = n.coin_pair
                    AND n.opened_at IS NOT NULL
                    AND sl.created_at >= n.opened_at - interval '6 minutes' AND sl.created_at <= n.opened_at + interval '5 seconds') AS any_log
  FROM ns n),
fl AS (
  SELECT n.*, l.code, l.any_log,
         COALESCE(l.code IS NOT NULL AND n.size = 0 AND n.closed_at - n.opened_at < interval '5 seconds', false) AS e_flag,
         COALESCE(n.stopped_at IS NOT NULL AND abs(extract(epoch FROM (n.closed_at - n.stopped_at))) <= 5, false) AS ds_flag,
         COALESCE(n.closed_at - n.opened_at >= interval '5 minutes' AND NOT (n.stopped_at IS NOT NULL
            AND abs(extract(epoch FROM (n.closed_at - n.stopped_at))) <= 5), false) AS o_flag
  FROM ns n JOIN lk l ON l.pid = n.pid),
res AS (
  SELECT 10 AS ord, 'NS' AS tag, (SELECT count(*) FROM fl)::text AS c1, (SELECT count(DISTINCT sid) FROM fl)::text AS c2, NULL::text AS c3, NULL::text AS c4
  UNION ALL
  SELECT 20, 'SZ', z.cls, (SELECT count(*) FROM fl WHERE CASE z.cls WHEN 'ZERO' THEN size = 0 WHEN 'NULL' THEN size IS NULL ELSE size <> 0 END)::text, NULL, NULL
  FROM (VALUES ('ZERO'), ('NULL'), ('POSITIVE')) z(cls)
  UNION ALL
  SELECT 30, 'GAP', g.b, (SELECT count(*) FROM fl WHERE CASE g.b
            WHEN 'G_NULL' THEN opened_at IS NULL
            WHEN 'G_LT_5S' THEN closed_at - opened_at < interval '5 seconds'
            WHEN 'G_5S_5M' THEN closed_at - opened_at >= interval '5 seconds' AND closed_at - opened_at < interval '5 minutes'
            ELSE closed_at - opened_at >= interval '5 minutes' END)::text, NULL, NULL
  FROM (VALUES ('G_LT_5S'), ('G_5S_5M'), ('G_GE_5M'), ('G_NULL')) g(b)
  UNION ALL
  SELECT 40, 'SS', s.st, (SELECT count(*) FROM fl WHERE CASE
            WHEN s.st = 'NO_ROW' THEN sst IS NULL
            WHEN s.st = 'OTHER' THEN sst IS NOT NULL AND sst NOT IN ('RUNNING', 'STOPPED', 'EMERGENCY_STOPPED', 'DELETED')
            ELSE sst = s.st END)::text, NULL, NULL
  FROM (VALUES ('RUNNING'), ('STOPPED'), ('EMERGENCY_STOPPED'), ('DELETED'), ('NO_ROW'), ('OTHER')) s(st)
  UNION ALL
  SELECT 50, 'STOPNEAR', (SELECT count(*) FROM fl WHERE ds_flag)::text, NULL, NULL, NULL
  UNION ALL
  SELECT 60, 'LINK', k.cls, (SELECT count(*) FROM fl WHERE CASE k.cls
            WHEN 'REJECT_LINKED' THEN code IS NOT NULL
            WHEN 'OTHER_LOG_ONLY' THEN code IS NULL AND any_log
            ELSE code IS NULL AND NOT any_log END)::text, NULL, NULL
  FROM (VALUES ('REJECT_LINKED'), ('OTHER_LOG_ONLY'), ('NO_LOG')) k(cls)
  UNION ALL
  SELECT 70, 'CODE', x.cd, count(*)::text, NULL, NULL
  FROM (SELECT CASE WHEN code ~ '^[A-Z][A-Z0-9_]{0,39}$' THEN code ELSE '(nonconforming)' END AS cd FROM fl WHERE code IS NOT NULL) x
  GROUP BY x.cd
  UNION ALL
  SELECT 80, 'PATH', p.cls, (SELECT count(*) FROM fl WHERE CASE p.cls
            WHEN 'E_ONLY' THEN e_flag AND NOT ds_flag AND NOT o_flag
            WHEN 'D_OR_S_ONLY' THEN ds_flag AND NOT e_flag AND NOT o_flag
            WHEN 'O_ONLY' THEN o_flag AND NOT e_flag AND NOT ds_flag
            WHEN 'NONE' THEN NOT e_flag AND NOT ds_flag AND NOT o_flag
            ELSE (e_flag::int + ds_flag::int + o_flag::int) >= 2 END)::text, NULL, NULL
  FROM (VALUES ('E_ONLY'), ('D_OR_S_ONLY'), ('O_ONLY'), ('MULTI'), ('NONE')) p(cls)
  UNION ALL
  SELECT 90, 'EXP',
         to_char(date_trunc('minute', (SELECT max(closed_at) FROM fl) + interval '30 days') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"'),
         to_char(date_trunc('minute', (SELECT min(closed_at) FROM fl) + interval '30 days') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"'),
         NULL, NULL
  UNION ALL
  SELECT 95, 'NEW', (SELECT count(*) FROM fl WHERE closed_at >= timestamptz '2026-11-01 04:49:14+00')::text, NULL, NULL, NULL
)
SELECT tag, c1, c2, c3, c4 FROM res ORDER BY ord, c1;
SELECT 'END', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"');
ROLLBACK;
"""
out = head + guard + '\n' + body
open(dst, 'w', encoding='utf-8', newline='\n').write(out)
a = '\n'.join(L[101:148])
print('pc verbatim:', a in out, 'pc lines', len(L[101:148]), 'guard first', L[38][:30], 'guard last', L[61][:40])
