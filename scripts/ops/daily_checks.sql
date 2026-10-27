-- 정기 점검 조회 (10:00·22:00 KST 자동 점검용, 읽기 전용) — scripts/ops/daily_check.sh 가 실행한다.
-- 사람이 읽는 정비용 조회(maint_checks.sql)와 달리 기계가 비교하도록 "키|값" 한 줄씩 낸다. 마지막 줄 END|ok 가 없으면 출력 누락으로 본다.
-- 실행: psql -X -At -F'|' -v since='<ISO 시각>' -f daily_checks.sql   (since = 점검 구간 시작, 보통 12시간 전)
-- 🔴 읽기 전용 트랜잭션 안에서만 실행한다. 쓰기 문장은 없다. 오류가 나면 그 자리에서 멈춘다(ON_ERROR_STOP).
\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;

SELECT 'flyway_version', max(version::int) FROM flyway_schema_history WHERE success;

-- 실자금 보유(OPEN·CLOSING) — 기대 0 (STAGE_1 비활성)
SELECT 'real_open_positions', count(*)
  FROM position p
  LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
  LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id
 WHERE p.status IN ('OPEN', 'CLOSING') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL');

-- 실자금 미결 주문 — 기대 0
SELECT 'real_unresolved_orders', count(*)
  FROM "order" o
  LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
  LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
 WHERE o.state IN ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED')
   AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL');

-- 정리 대기 — 기대 0
SELECT 'cleanup_pending', count(*) FROM position_cleanup WHERE done_at IS NULL;

-- 격리 대조 대상(§6.13 ⑤-1) — 기대 0
SELECT 'quarantine_targets', count(*)
  FROM position p JOIN "order" o ON o.position_id = p.id
 WHERE p.session_kind IN ('LIVE','DYNAMIC') AND p.status IN ('OPEN','CLOSING')
   AND o.side = 'SELL' AND o.sell_applied_at IS NULL AND o.filled_quantity > 0 AND o.state IN ('FILLED','CANCELLED');

-- 검증 66세션(522~587) — 기대: 원래 66개 중 지정 종료 세션(daily_check.sh S66_ENDED_IDS)만 STOPPED, 나머지 RUNNING
SELECT 's66_running', count(*) FILTER (WHERE status = 'RUNNING') FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587;
SELECT 's66_total', count(*) FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587;
-- RUNNING 아닌 검증 세션 "id:상태" 목록(없으면 -) — 개수가 아니라 어느 세션인지로 비교한다(리뷰 2026-10-27: 기대값을 63 으로 낮추지 않는다)
SELECT 's66_not_running', coalesce(string_agg(id || ':' || coalesce(status, 'NULL'), ' ' ORDER BY id), '-')
  FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587 AND status IS DISTINCT FROM 'RUNNING';
-- 검증 코인 목록 — 캔들 404 를 검증 코인과 그 밖으로 가른다
SELECT 's66_coins', coalesce(string_agg(DISTINCT coin_pair, ' ' ORDER BY coin_pair), '-') FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587;

-- 점검 구간 안의 실자금 신규 매수 — 기대 0 (STAGE_1 비활성 — 공유 풀 미등록이라 실자금 진입은 거절된다)
SELECT 'real_buys_window', count(*)
  FROM "order" o
  LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
  LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
 WHERE o.side = 'BUY' AND o.created_at >= :'since'::timestamptz
   AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL');

-- 격리 해제 기록(V94 뒤에만 테이블이 있다) — 기대 0. 테이블이 없으면 -1
SELECT (to_regclass('public.sell_quarantine_release') IS NOT NULL)::int AS has_release_table \gset
\if :has_release_table
SELECT 'quarantine_release_rows', count(*) FROM sell_quarantine_release;
\else
SELECT 'quarantine_release_rows', -1;
\endif

-- 점검 구간 안의 텔레그램 전송 — 유형별 실패 건수(tg_fail|유형|건수), 큐 진입 실패, 성공 건수, 마지막 성공 시각
SELECT 'tg_fail', type, count(*) FROM telegram_notification_log
 WHERE sent_at >= :'since'::timestamptz AND NOT success GROUP BY type ORDER BY type;
SELECT 'tg_queue_rejected', count(*) FROM telegram_notification_log
 WHERE sent_at >= :'since'::timestamptz AND message_text LIKE '[큐 포화%';
SELECT 'tg_ok_window', count(*) FROM telegram_notification_log WHERE sent_at >= :'since'::timestamptz AND success;
SELECT 'tg_last_ok', coalesce(to_char(max(sent_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI'), 'none') FROM telegram_notification_log WHERE success;

ROLLBACK;
SELECT 'END', 'ok';
