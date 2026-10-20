-- 계획 정비 점검 조회 (2026-10-21 정비용, 읽기 전용) — docs/MAINTENANCE_2026-10-21.md
-- 실행: docker compose -f docker-compose.prod.yml exec -T db psql -U trader -d crypto_auto_trader -f - < scripts/ops/maint_checks.sql
-- 🔴 읽기 전용 트랜잭션 안에서만 실행한다. 쓰기 문장은 없다.
BEGIN TRANSACTION READ ONLY;

\echo '== Q1 Flyway 최고 버전 (Docker 단계 93 · 앱 단계 뒤 94) · V87 설치 시각 = 판별 불가 매도의 경계 (2026-10-16 05:07Z 기대)'
SELECT max(version::int) AS flyway_version FROM flyway_schema_history WHERE success;
SELECT version, description, installed_on, success FROM flyway_schema_history WHERE version IN ('87', '93', '94') ORDER BY installed_rank;

\echo '== Q2 실자금 세션 (LIVE·DYNAMIC REAL) — 상태·가용·invest_ratio 기록'
SELECT 'LIVE' AS kind, id, status, coin_pair, available_krw, NULL::numeric AS invest_ratio
  FROM live_trading_session WHERE session_type = 'REAL'
UNION ALL
SELECT 'DYNAMIC', id, status, current_coin_pair, available_krw, invest_ratio
  FROM dynamic_session WHERE trading_mode = 'REAL'
 ORDER BY 1, 2;

\echo '== Q3 실자금 보유 (OPEN·CLOSING) — 기대 0행'
SELECT p.id, p.session_kind, p.session_id, p.coin_pair, p.status, p.size
  FROM position p
  LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
  LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id
 WHERE p.status IN ('OPEN', 'CLOSING') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL')
 ORDER BY p.id;

\echo '== Q4 실자금 미결 주문 — 기대 0'
SELECT count(*) AS real_unresolved_orders
  FROM "order" o
  LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
  LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
 WHERE o.state IN ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED')
   AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL');

\echo '== Q5 정리 대기 — 기대 0'
SELECT count(*) AS cleanup_pending FROM position_cleanup WHERE done_at IS NULL;

\echo '== Q6 격리 대조 대상 (§6.13 ⑤-1) — 기대 0행. 행이 나오면 정비 보류'
SELECT p.id AS position_id, p.session_kind, p.session_id, p.coin_pair, p.status, p.size, o.id AS order_id, o.state, o.filled_quantity, o.created_at
  FROM position p JOIN "order" o ON o.position_id = p.id
 WHERE p.session_kind IN ('LIVE','DYNAMIC') AND p.status IN ('OPEN','CLOSING')
   AND o.side = 'SELL' AND o.sell_applied_at IS NULL AND o.filled_quantity > 0 AND o.state IN ('FILLED','CANCELLED')
 ORDER BY p.id, o.id;

\echo '== Q7 검증 66세션 상태 — 기대 RUNNING 66'
SELECT status, count(*) FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587 GROUP BY status ORDER BY status;

\echo '== Q8 정비 시작(13:00 KST) 뒤 실자금 신규 매수 — 기대 0'
SELECT count(*) AS real_buys_since_maint
  FROM "order" o
  LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
  LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
 WHERE o.side = 'BUY' AND o.created_at >= TIMESTAMPTZ '2026-10-21 13:00:00+09'
   AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL');

\echo '== Q9 격리 해제 기록 (V94 뒤에만 존재) — 기대 0행. V93 에서는 "relation does not exist" 가 정상'
SELECT count(*) AS quarantine_release_rows FROM sell_quarantine_release;

\echo '== Q10 최근 7일 텔레그램 전송 기록 (참고 — 재기동 뒤에도 알림이 나가는가)'
SELECT type, success, count(*), max(sent_at) AS last_sent FROM telegram_notification_log
 WHERE sent_at >= now() - interval '7 days' GROUP BY type, success ORDER BY type, success;

ROLLBACK;
