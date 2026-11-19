-- 비용 기준 후보 배포 전 읽기안 (총괄 v1.191 §2-5) — 읽기 전용 SELECT 만. 이 개발 세션은 실행하지 않았다(운영 조회 권한 없음).
-- 실행 시점·사람·출처는 별도 승인으로 정한다. 결과는 개수·합계만 남기고 행 원문은 필요할 때만.

-- R1. 30일 창의 판정 불가 대상 — 0 이 아니면 배포 즉시 해당 종류의 신규 REAL 진입이 30일 동안 막힌다(후보 동작).
SELECT k.kind,
  (SELECT COUNT(*) FROM "order" o
    WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind = k.kind
      AND o.sell_applied_at IS NOT NULL AND o.sell_applied_at >= now() - interval '30 days' AND o.applied_executed_funds IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM (SELECT bo.position_id FROM "order" bo
                                     WHERE bo.side = 'BUY' AND bo.position_id IS NOT NULL AND bo.filled_quantity > 0
                                     GROUP BY bo.position_id
                                     HAVING COUNT(*) = COUNT(bo.applied_executed_funds) AND COUNT(*) = COUNT(bo.applied_paid_fee)) b
                      WHERE b.position_id = o.position_id)) AS sells_without_cost,
  (SELECT COUNT(*) FROM position p
    WHERE p.session_kind = k.kind AND p.session_id IS NOT NULL AND p.status = 'CLOSED'
      AND p.closed_at >= now() - interval '30 days'
      AND (SELECT COALESCE(SUM(bq.filled_quantity), 0) FROM "order" bq WHERE bq.position_id = p.id AND bq.side = 'BUY' AND bq.filled_quantity > 0)
        > (SELECT COALESCE(SUM(sq.filled_quantity), 0) FROM "order" sq WHERE sq.position_id = p.id AND sq.side = 'SELL'
             AND sq.sell_applied_at IS NOT NULL AND sq.applied_executed_funds IS NOT NULL)) AS closes_unconfirmed_qty
FROM (VALUES ('LIVE'), ('DYNAMIC')) k(kind);

-- R2. 30일 창의 손실 합 — 종전 식(평균가) vs 후보 식(확정 매수 단가). 후보가 더 엄격하면 배포 직후 거절이 생길 수 있다.
SELECT o.session_kind,
  COALESCE(SUM(CASE WHEN o.applied_executed_funds - COALESCE(o.applied_paid_fee, 0) - o.filled_quantity * p.avg_price < 0
                    THEN o.applied_executed_funds - COALESCE(o.applied_paid_fee, 0) - o.filled_quantity * p.avg_price END), 0) AS loss_old,
  COALESCE(SUM(CASE WHEN o.applied_executed_funds - COALESCE(o.applied_paid_fee, 0) - (o.filled_quantity * b.cost) / b.qty < 0
                    THEN o.applied_executed_funds - COALESCE(o.applied_paid_fee, 0) - (o.filled_quantity * b.cost) / b.qty END), 0) AS loss_new,
  COUNT(*) AS sells
FROM "order" o
JOIN position p ON p.id = o.position_id
LEFT JOIN (SELECT bo.position_id, SUM(bo.applied_executed_funds + bo.applied_paid_fee) AS cost, SUM(bo.filled_quantity) AS qty
           FROM "order" bo WHERE bo.side = 'BUY' AND bo.position_id IS NOT NULL AND bo.filled_quantity > 0
           GROUP BY bo.position_id
           HAVING COUNT(*) = COUNT(bo.applied_executed_funds) AND COUNT(*) = COUNT(bo.applied_paid_fee)) b ON b.position_id = o.position_id
WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind IN ('LIVE', 'DYNAMIC')
  AND o.sell_applied_at IS NOT NULL AND o.sell_applied_at >= now() - interval '30 days' AND o.applied_executed_funds IS NOT NULL
GROUP BY o.session_kind;

-- R3. RUNNING REAL 세션의 최근 청산 — 후보 연속 손실이 배포 직후 발동/판정 불가가 되는지(세션별 최근 5건, 저장 손익 vs 비용 기준).
--     DYNAMIC 은 이번 가동(started_at) 뒤 청산만 센다(제품 규칙). 비용 기준 = 확정 매도 순대금 − 확정 매도 수량 × 확정 매수 단가.
SELECT p.session_kind, p.session_id, p.id AS position_id, p.closed_at, p.realized_pnl AS stored,
  (SELECT SUM(s.applied_executed_funds - COALESCE(s.applied_paid_fee, 0)) FROM "order" s
     WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NOT NULL AND s.applied_executed_funds IS NOT NULL)
  - (SELECT SUM(s.filled_quantity) FROM "order" s
       WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NOT NULL AND s.applied_executed_funds IS NOT NULL)
    * (SELECT SUM(b.applied_executed_funds + b.applied_paid_fee) / NULLIF(SUM(b.filled_quantity), 0) FROM "order" b
         WHERE b.position_id = p.id AND b.side = 'BUY' AND b.filled_quantity > 0) AS cost_basis
FROM position p
WHERE p.status = 'CLOSED' AND p.session_kind IN ('LIVE', 'DYNAMIC')
  AND ((p.session_kind = 'LIVE' AND p.session_id IN (SELECT id FROM live_trading_session WHERE status = 'RUNNING'))
    OR (p.session_kind = 'DYNAMIC' AND p.session_id IN (SELECT id FROM dynamic_session WHERE status = 'RUNNING' AND trading_mode = 'REAL')))
ORDER BY p.session_kind, p.session_id, p.closed_at DESC NULLS FIRST;
