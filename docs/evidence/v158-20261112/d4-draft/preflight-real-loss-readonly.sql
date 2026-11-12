-- v1.158 손실 세 보호식 — 배포 전 읽기 전용 확인(실행 금지 초안: 운영 실행은 총괄 승인 뒤, D4 U0 앞에서만)
-- 제품(4a4a04a3 RiskManagementService.checkRealLossGuard)과 같은 원천·같은 조건을 그대로 옮겼다. 쓰기 없음.
-- 판정: dyn_loss_* < 0 인데 running_dynamic_real_capital = 0 이면 배포 직후 모든 실자금 신규 매수가 "[DYNAMIC REAL 보호식] 판정 불가" 로 거절된다.
BEGIN TRANSACTION READ ONLY;
WITH w(label, since) AS (VALUES ('1d', now() - interval '1 day'), ('7d', now() - interval '7 days'), ('30d', now() - interval '30 days')),
fill AS (  -- 기존 원천 ① 비세션 FILL (TradeLogRepository.sumRealizedLossSince)
  SELECT w.label, COALESCE(SUM(pnl) FILTER (WHERE pnl < 0), 0) AS v FROM w LEFT JOIN (
    SELECT created_at, CAST(detail_json->>'realizedPnl' AS NUMERIC) AS pnl FROM trade_log WHERE event_type = 'FILL') t ON t.created_at >= w.since GROUP BY w.label),
sess AS (  -- 기존 원천 ② 확정 LIVE 세션 매도 / 새 원천 DYNAMIC 세션 매도 (OrderRepository.sum{Live,DynamicReal}SessionRealizedLossSince)
  SELECT w.label,
    COALESCE(SUM(pnl) FILTER (WHERE pnl < 0 AND kind = 'LIVE'), 0) AS live_v,
    COALESCE(SUM(pnl) FILTER (WHERE pnl < 0 AND kind = 'DYNAMIC'), 0) AS dyn_v
  FROM w LEFT JOIN (
    SELECT o.session_kind AS kind, o.sell_applied_at AS at,
           o.applied_executed_funds - COALESCE(o.applied_paid_fee, 0) - o.filled_quantity * p.avg_price AS pnl
    FROM "order" o JOIN position p ON p.id = o.position_id
    WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind IN ('LIVE', 'DYNAMIC')
      AND o.sell_applied_at IS NOT NULL AND o.applied_executed_funds IS NOT NULL) s ON s.at >= w.since
  GROUP BY w.label),
cap AS (
  SELECT (SELECT COALESCE(SUM(initial_capital) FILTER (WHERE initial_capital > 0), 0) FROM live_trading_session WHERE status = 'RUNNING') AS running_live_capital,
         (SELECT COALESCE(SUM(initial_capital) FILTER (WHERE initial_capital > 0), 0) FROM dynamic_session WHERE status = 'RUNNING' AND trading_mode <> 'PAPER') AS running_dynamic_real_capital)
SELECT f.label, f.v AS legacy_fill_loss, s.live_v AS legacy_live_session_loss, s.dyn_v AS dynamic_real_loss,
       c.running_live_capital, c.running_dynamic_real_capital,
       (s.dyn_v < 0 AND c.running_dynamic_real_capital <= 0) AS dynamic_judgement_impossible
FROM fill f JOIN sess s USING (label) CROSS JOIN cap c ORDER BY f.label;
SELECT * FROM risk_config;   -- 한도(일·주·월 %)·portfolio_limit_krw 대조용
ROLLBACK;
