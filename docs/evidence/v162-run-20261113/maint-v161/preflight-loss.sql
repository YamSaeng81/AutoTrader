-- ⛔ 실행 금지 초안 — v1.159(398차) 배포 전 실자금 손실 관문 확인 v2. 운영 실행은 D4 운영 승인 범위에서 U0 앞에서만.
-- 제품 4a4a04a3 RiskManagementService.checkRealLossGuard 의 손실 세 보호식을 같은 원천·분모·반올림·비교로 옮겼다. 쓰기 없음.
--
-- 기준 시각: 한 트랜잭션의 now() 하나(또는 시험용 SET LOCAL preflight.asof). 창은 Java Instant.minus(1/7/30 DAYS) 와 같게
--            24·168·720 시간이다('1 day' 는 시간대 DST 에 따라 23/25시간이 될 수 있어 쓰지 않는다). 포함 조건은 >= 다.
-- 원천    : 기존 = 비세션 trade_log FILL 손실 행 + 확정 LIVE 세션 매도 손실 행, DYNAMIC REAL = 확정 DYNAMIC 세션 매도 손실 행.
--            손실 행만 더한다(이익 비상쇄). DYN_PAPER·추정 청산(반영 없음)은 원천에 없다.
-- 분모    : 기존 = RUNNING live_trading_session 초기자본 합(>0 만, session_type 무관 — 제품과 같다) → 없으면 risk_config.portfolio_limit_krw(>0)
--            → 최종 폴백 10,000,000. DYNAMIC REAL = RUNNING dynamic_session(trading_mode <> 'PAPER') 초기자본 합(>0 만).
--            전체 REAL = 위 두 RUNNING 합 → 0 이면 설정/최종 폴백. 🔴 DYNAMIC 자본은 기존 식의 분모에 들어가지 않는다.
-- 반올림  : 제품 = |손실|.divide(분모, 4, HALF_UP) × 100. 여기서는 정수 나눗셈 div()·나머지로 같은 HALF_UP 을 정확히 계산한다
--            (numeric 나눗셈 뒤 round() 의 이중 반올림을 피한다). 비교: 손실률 > 한도 → 거절, 동률 허용.
-- 판정 불가: DYNAMIC REAL 손실 < 0 인데 RUNNING DYNAMIC REAL 자본 0 → 제품은 거절. risk_config 행 없음 → 제품은 거절.
--            한도 NULL → 제품은 비교에서 예외(진입 중단). 여기서는 모두 "판정 불가"로 출력한다.
-- 순서    : 제품과 같다 — 기존(일·주·월) → DYNAMIC 판정 불가 → DYNAMIC REAL(일·주·월) → 전체 REAL(일·주·월). 첫 거절 사유를 product_first 로 낸다.
-- 범위    : 손실 관문만이다. checkRisk 의 포지션 수·자본 사용률·포트폴리오 낙폭은 이 조회에 없다.
--
-- 🔴 배포 판단: 마지막 행 preflight 가 '진행 가능' 이 아니면(거절·판정 불가·필수값 누락) 배포 전 중단·보고한다. 조회 실패도 중단이다.
--    한도·자본·세션·손실 행을 바꿔 통과시키지 않는다.
BEGIN TRANSACTION READ ONLY;
WITH
t0 AS (SELECT COALESCE(NULLIF(current_setting('preflight.asof', true), '')::timestamptz, now()) AS asof),
w(ord, label, since) AS (
  SELECT 1, '1d',  asof - interval '24 hours'  FROM t0 UNION ALL
  SELECT 2, '7d',  asof - interval '168 hours' FROM t0 UNION ALL
  SELECT 3, '30d', asof - interval '720 hours' FROM t0),
cfg_all AS (SELECT count(*) AS n FROM risk_config),
cfg AS (SELECT max_daily_loss_pct AS lim1, max_weekly_loss_pct AS lim2, max_monthly_loss_pct AS lim3, portfolio_limit_krw AS plimit
        FROM risk_config ORDER BY id DESC LIMIT 1),                                       -- 제품 findTopByOrderByIdDesc
cap AS (SELECT
  (SELECT COALESCE(SUM(initial_capital) FILTER (WHERE initial_capital > 0), 0) FROM live_trading_session WHERE status = 'RUNNING') AS live_cap,
  (SELECT COALESCE(SUM(initial_capital) FILTER (WHERE initial_capital > 0), 0) FROM dynamic_session
     WHERE status = 'RUNNING' AND trading_mode <> 'PAPER') AS dyn_cap),
src AS (SELECT w.ord, w.label,
  (SELECT COALESCE(SUM(pnl), 0) FROM (SELECT CAST(detail_json->>'realizedPnl' AS NUMERIC) AS pnl FROM trade_log
     WHERE event_type = 'FILL' AND created_at >= w.since) x WHERE pnl < 0) AS fill_loss,
  (SELECT COALESCE(SUM(pnl), 0) FROM (SELECT o.applied_executed_funds - COALESCE(o.applied_paid_fee, 0) - o.filled_quantity * p.avg_price AS pnl
     FROM "order" o JOIN position p ON p.id = o.position_id
     WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind = 'LIVE'
       AND o.sell_applied_at IS NOT NULL AND o.sell_applied_at >= w.since AND o.applied_executed_funds IS NOT NULL) x WHERE pnl < 0) AS live_loss,
  (SELECT COALESCE(SUM(pnl), 0) FROM (SELECT o.applied_executed_funds - COALESCE(o.applied_paid_fee, 0) - o.filled_quantity * p.avg_price AS pnl
     FROM "order" o JOIN position p ON p.id = o.position_id
     WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind = 'DYNAMIC'
       AND o.sell_applied_at IS NOT NULL AND o.sell_applied_at >= w.since AND o.applied_executed_funds IS NOT NULL) x WHERE pnl < 0) AS dyn_loss
  FROM w),
den AS (SELECT c.live_cap, c.dyn_cap,
  CASE WHEN c.live_cap > 0 THEN c.live_cap WHEN f.plimit > 0 THEN f.plimit ELSE 10000000 END AS legacy_den,
  CASE WHEN c.live_cap > 0 THEN 'RUNNING LIVE' WHEN f.plimit > 0 THEN '설정 portfolio_limit_krw' ELSE '최종 폴백 10,000,000' END AS legacy_den_src,
  CASE WHEN c.live_cap + c.dyn_cap > 0 THEN c.live_cap + c.dyn_cap WHEN f.plimit > 0 THEN f.plimit ELSE 10000000 END AS total_den,
  CASE WHEN c.live_cap + c.dyn_cap > 0 THEN 'RUNNING LIVE+DYNAMIC REAL' WHEN f.plimit > 0 THEN '설정 portfolio_limit_krw' ELSE '최종 폴백 10,000,000' END AS total_den_src
  FROM cap c LEFT JOIN cfg f ON true),
f3(formula, ford) AS (VALUES ('기존', 1), ('DYNAMIC REAL', 2), ('전체 REAL', 3)),
rows0 AS (
  SELECT f3.ford, f3.formula, s.ord, s.label,
    CASE f3.ford WHEN 1 THEN s.fill_loss + s.live_loss WHEN 2 THEN s.dyn_loss ELSE s.fill_loss + s.live_loss + s.dyn_loss END AS loss,
    CASE f3.ford WHEN 1 THEN d.legacy_den WHEN 2 THEN d.dyn_cap ELSE d.total_den END AS den,
    CASE f3.ford WHEN 1 THEN d.legacy_den_src WHEN 2 THEN 'RUNNING DYNAMIC REAL' ELSE d.total_den_src END AS den_src,
    CASE s.ord WHEN 1 THEN c.lim1 WHEN 2 THEN c.lim2 ELSE c.lim3 END AS lim
  FROM f3 CROSS JOIN src s CROSS JOIN den d LEFT JOIN cfg c ON true),
rows1 AS (
  SELECT r.*,
    -- 제품 pct(): 손실 >= 0 또는 분모 <= 0 이면 0. 그 밖은 |손실|/분모 를 소수 4자리 HALF_UP 한 뒤 ×100.
    CASE WHEN r.loss >= 0 OR r.den <= 0 THEN 0::numeric
         ELSE (div(abs(r.loss) * 10000, r.den)
               + CASE WHEN 2 * (abs(r.loss) * 10000 - div(abs(r.loss) * 10000, r.den) * r.den) >= r.den THEN 1 ELSE 0 END) * 0.01
    END AS pct,
    (r.ford = 2 AND r.loss < 0 AND r.den <= 0) AS impossible
  FROM rows0 r),
rows2 AS (
  SELECT r.*,
    CASE WHEN (SELECT n FROM cfg_all) = 0 THEN '판정 불가(risk_config 행 없음)'
         WHEN r.lim IS NULL THEN '판정 불가(한도 NULL)'
         WHEN r.impossible THEN '판정 불가(DYNAMIC REAL 손실 있음·자본 0)'
         WHEN r.pct > r.lim THEN '거절'
         ELSE '허용' END AS verdict
  FROM rows1 r),
first_bad AS (
  -- 제품의 검사 순서: 기존(일·주·월) → DYNAMIC 판정 불가(창 무관, 한 번) → DYNAMIC REAL(일·주·월) → 전체 REAL(일·주·월)
  SELECT formula || ' ' || label || ' ' || verdict
         || CASE WHEN verdict = '거절' THEN ' ' || pct::text || '% > ' || lim::text || '%' ELSE '' END AS reason
  FROM rows2 WHERE verdict <> '허용'
  ORDER BY CASE WHEN ford = 1 THEN 0 WHEN impossible THEN 1 WHEN ford = 2 THEN 2 ELSE 3 END, ord
  LIMIT 1)
SELECT formula, label, loss_krw, denominator_krw, denominator_source, loss_pct, limit_pct, verdict FROM (
  SELECT ford, ord, formula, label, loss AS loss_krw, den AS denominator_krw, den_src AS denominator_source,
         pct AS loss_pct, lim AS limit_pct, verdict
  FROM rows2
  UNION ALL
  SELECT 9, 9, '판정', '-', NULL, NULL, NULL, NULL, NULL,
    CASE WHEN (SELECT n FROM cfg_all) = 0 THEN '🔴 중단: 판정 불가(risk_config 행 없음)'
         WHEN EXISTS (SELECT 1 FROM first_bad) THEN '🔴 중단: ' || (SELECT reason FROM first_bad)
         ELSE '진행 가능(손실 세 보호식 모두 허용)' END) u
ORDER BY ford, ord;
ROLLBACK;
