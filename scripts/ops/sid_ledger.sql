-- SID 203 실거래 비용·순손익 기록 (읽기 전용) — 리뷰 2026-10-31 56일 운영 점검 2번. LIMITED_LIVE_TEST_PLAN.md §5-9 6번.
-- 실행: psql -X -v sid=203 -v init=20200 -f sid_ledger.sql
--   sid = 실거래 시험 세션(LIVE·REAL) · init = 시작 잔액(세션 initial_capital 과 같아야 한다 — L0 에서 대조)
-- 읽기 전용 트랜잭션이라 임시 뷰를 만들지 않는다 — 주문 묶음(_o)을 조회마다 다시 적는다.
-- 무엇을 내나(KST 날짜별 — 시작일부터 정지일·마지막 체결일 중 늦은 날까지, 운영 중이면 지금까지. 날 끝 = 다음 날 00:00 KST(지난 날) 또는 지금. 과거 기록에서 다시 계산하므로 언제 돌려도 같은 날의 값은 같다):
--   L0 세션 · L1 날짜별(신호·관문 차단 사유·주문·체결·수수료·슬리피지·현금·보유·평가액·순손익) · L2 주문별 · L3 왕복별 비용 · L4 지금 대조
-- 🔴 순손익 = (현금 + 보유 평가액) − 시작 잔액. realized_pnl 합계는 참고로만 낸다(판정에 쓰지 않는다).
--    현금 = 시작 잔액 − Σ매수(반영 대금 + 반영 수수료) + Σ매도(반영 대금 − 반영 수수료) — F3 장부와 같은 식, 체결 있는 주문의 applied_* 만.
--    체결 있는 주문의 보고값·반영값 중 하나라도 없거나 회계 반영 대기면 그 날 이후 현금·평가·순손익은 비운다(0 으로 대체하지 않는다 — "확인 불가").
--    보유 평가액 = 보유 수량 × 그 날 끝(KST 24:00, 운영 중인 날은 지금) 이전 마지막 마감 H1 봉 종가(market_data_cache). 봉이 없으면 비운다.
--    슬리피지(원) = 매수: 반영 대금 − 신호가 × 체결량, 매도: 신호가 × 체결량 − 반영 대금 (양수 = 비용). 신호가(order.signal_price)가 없는 주문(정지·보호 청산 등)은 비우고 건수를 따로 센다.
-- 🔴 범위: 이 조회는 session_kind='LIVE' AND session_id=:sid 만 읽는다. 22코인 비용 집계(scripts/forward_aggregate — paper_trading 522~587 의 CSV 추출)와
--    입력이 겹치지 않고, 그 집계에 넣지 않는다. 이 시험은 BTC 포지션 1개라 1단계 비용 판정(왕복 30건·코인 ≥ 8)을 채우지 못한다(§6-3).
\set ON_ERROR_STOP on
\pset null '-'
BEGIN TRANSACTION READ ONLY;

\echo '== L0 세션 (initial_capital = init 이어야 한다)'
SELECT id, coin_pair, status, initial_capital, :init::numeric AS init, initial_capital = :init::numeric AS init_ok, available_krw,
       started_at AT TIME ZONE 'Asia/Seoul' AS started_kst, stopped_at AT TIME ZONE 'Asia/Seoul' AS stopped_kst,
       (started_at + interval '56 days') AT TIME ZONE 'Asia/Seoul' AS end_kst
  FROM live_trading_session WHERE id = :sid AND session_type = 'REAL';

\echo '== L1 날짜별 — 신호(B/S/H·실행) · 관문 차단 사유 · 주문(생성 B/S·체결 B/S) · 대금·수수료(반영/보고) · 슬리피지 · 날 끝 현금·BTC·평가 봉·평가액·자산·순손익'
WITH _s AS (
  -- 마지막 날 = 정지일과 마지막 체결일 중 늦은 날(정지 청산 매도는 stopped_at 뒤에 체결된다 — 리허설 4차: 정지 08:07:31, 매도 체결 08:07:34), 운영 중이면 오늘
  SELECT l.id, l.coin_pair, l.started_at, greatest(coalesce(l.stopped_at, now()), (SELECT max(coalesce(filled_at, created_at)) FROM "order" WHERE session_kind = 'LIVE' AND session_id = :sid AND filled_quantity > 0)) AS until_at
    FROM live_trading_session l WHERE l.id = :sid AND l.session_type = 'REAL' AND l.started_at IS NOT NULL),
_o AS (
  SELECT o.id, o.position_id, o.side, o.state, o.filled_quantity AS q, o.signal_price AS sp, o.created_at, coalesce(o.filled_at, o.created_at) AS ft,
         o.reported_executed_funds AS rf, o.reported_paid_fee AS rfee, o.applied_executed_funds AS af, o.applied_paid_fee AS afee,
         o.fill_accounting_pending AS pend,
         (o.filled_quantity > 0 AND (o.reported_executed_funds IS NULL OR o.reported_paid_fee IS NULL OR o.applied_executed_funds IS NULL
                                      OR o.applied_paid_fee IS NULL OR o.fill_accounting_pending)) AS unk,
         CASE WHEN o.filled_quantity > 0 AND o.signal_price IS NOT NULL AND o.applied_executed_funds IS NOT NULL THEN
              CASE o.side WHEN 'BUY' THEN o.applied_executed_funds - o.signal_price * o.filled_quantity
                          ELSE o.signal_price * o.filled_quantity - o.applied_executed_funds END END AS slip
    FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :sid),
_d AS (
  SELECT g::date AS d, least(((g::date + 1)::timestamp AT TIME ZONE 'Asia/Seoul'), now()) AS day_end,
         (g::date::timestamp AT TIME ZONE 'Asia/Seoul') AS day_start, s.coin_pair
    FROM _s s, generate_series((s.started_at AT TIME ZONE 'Asia/Seoul')::date, (s.until_at AT TIME ZONE 'Asia/Seoul')::date, interval '1 day') g),
x AS (
SELECT d.d, d.day_start, d.day_end, d.coin_pair,
  (SELECT count(*) FILTER (WHERE signal = 'BUY') || '/' || count(*) FILTER (WHERE signal = 'SELL') || '/' || count(*) FILTER (WHERE signal = 'HOLD')
          || ' 실행 ' || count(*) FILTER (WHERE was_executed)
     FROM strategy_log WHERE session_type = 'LIVE' AND session_id = :sid AND created_at >= d.day_start AND created_at < d.day_end) AS signals,
  (SELECT string_agg(r || '×' || n, '; ' ORDER BY n DESC, r) FROM (
     SELECT left(blocked_reason, 80) AS r, count(*) AS n FROM strategy_log
      WHERE session_type = 'LIVE' AND session_id = :sid AND blocked_reason IS NOT NULL AND created_at >= d.day_start AND created_at < d.day_end
      GROUP BY 1) b) AS blocked,
  (SELECT count(*) FILTER (WHERE side = 'BUY') || '/' || count(*) FILTER (WHERE side = 'SELL') FROM _o WHERE created_at >= d.day_start AND created_at < d.day_end) AS orders,
  (SELECT count(*) FILTER (WHERE side = 'BUY') || '/' || count(*) FILTER (WHERE side = 'SELL') FROM _o WHERE q > 0 AND ft >= d.day_start AND ft < d.day_end) AS fills,
  (SELECT sum(af) FROM _o WHERE q > 0 AND ft >= d.day_start AND ft < d.day_end) AS funds,
  (SELECT sum(afee) || ' / ' || sum(rfee) FROM _o WHERE q > 0 AND ft >= d.day_start AND ft < d.day_end) AS fee_applied_reported,
  (SELECT round(sum(slip), 2) || CASE WHEN count(*) FILTER (WHERE slip IS NULL) > 0 THEN ' (기준가 없음 ' || count(*) FILTER (WHERE slip IS NULL) || ')' ELSE '' END
     FROM _o WHERE q > 0 AND ft >= d.day_start AND ft < d.day_end) AS slippage,
  (SELECT count(*) FROM _o WHERE q > 0 AND ft < d.day_end AND unk) AS unk_upto,
  :init::numeric - coalesce((SELECT sum(af + afee) FROM _o WHERE side = 'BUY' AND q > 0 AND ft < d.day_end), 0)
                 + coalesce((SELECT sum(af - afee) FROM _o WHERE side = 'SELL' AND q > 0 AND ft < d.day_end), 0) AS cash_calc,
  coalesce((SELECT sum(q) FROM _o WHERE side = 'BUY' AND q > 0 AND ft < d.day_end), 0)
    - coalesce((SELECT sum(q) FROM _o WHERE side = 'SELL' AND q > 0 AND ft < d.day_end), 0) AS btc,
  (SELECT c.close FROM market_data_cache c WHERE c.coin_pair = d.coin_pair AND c.timeframe = 'H1' AND c.time + interval '1 hour' <= d.day_end ORDER BY c.time DESC LIMIT 1) AS px,
  (SELECT c.time + interval '1 hour' FROM market_data_cache c WHERE c.coin_pair = d.coin_pair AND c.timeframe = 'H1' AND c.time + interval '1 hour' <= d.day_end ORDER BY c.time DESC LIMIT 1) AS px_close_at
FROM _d d)
SELECT d AS kst_date, signals, coalesce(blocked, '-') AS blocked, orders, fills, coalesce(funds, 0) AS funds, coalesce(fee_applied_reported, '-') AS fee_appl_rep,
       coalesce(slippage, '-') AS slippage,
       CASE WHEN unk_upto > 0 THEN NULL ELSE cash_calc END AS cash,
       btc, CASE WHEN btc = 0 THEN '-' ELSE px || ' @' || to_char(px_close_at AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') END AS mark,
       CASE WHEN btc = 0 THEN 0 ELSE round(btc * px, 2) END AS holding_value,
       CASE WHEN unk_upto > 0 THEN NULL WHEN btc = 0 THEN cash_calc ELSE round(cash_calc + btc * px, 2) END AS equity,
       CASE WHEN unk_upto > 0 THEN NULL WHEN btc = 0 THEN cash_calc - :init ELSE round(cash_calc + btc * px - :init, 2) END AS net_pnl,
       CASE WHEN unk_upto > 0 THEN '확인 불가(반영값·보고값 없음·반영 대기 ' || unk_upto || ')' WHEN btc <> 0 AND px IS NULL THEN '평가 봉 없음' ELSE '' END AS note
  FROM x ORDER BY d;

\echo '== L2 주문별 — 체결가 = 반영 대금 / 체결량 · 슬리피지(원·%, 양수 = 비용) · 수수료율 · 보고값과 반영값'
WITH _o AS (
  SELECT o.id, o.position_id, o.side, o.state, o.filled_quantity AS q, o.signal_price AS sp, o.created_at, coalesce(o.filled_at, o.created_at) AS ft,
         o.reported_executed_funds AS rf, o.reported_paid_fee AS rfee, o.applied_executed_funds AS af, o.applied_paid_fee AS afee,
         o.fill_accounting_pending AS pend,
         (o.filled_quantity > 0 AND (o.reported_executed_funds IS NULL OR o.reported_paid_fee IS NULL OR o.applied_executed_funds IS NULL
                                      OR o.applied_paid_fee IS NULL OR o.fill_accounting_pending)) AS unk,
         CASE WHEN o.filled_quantity > 0 AND o.signal_price IS NOT NULL AND o.applied_executed_funds IS NOT NULL THEN
              CASE o.side WHEN 'BUY' THEN o.applied_executed_funds - o.signal_price * o.filled_quantity
                          ELSE o.signal_price * o.filled_quantity - o.applied_executed_funds END END AS slip
    FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :sid)
SELECT id, position_id AS pos, side, state, to_char(created_at AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI:SS') AS created_kst,
       to_char(ft AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI:SS') AS fill_kst, q AS filled, sp AS signal_price,
       CASE WHEN q > 0 AND af IS NOT NULL THEN round(af / q, 2) END AS avg_fill,
       round(slip, 2) AS slip_krw, CASE WHEN slip IS NOT NULL AND sp * q <> 0 THEN round(slip / (sp * q) * 100, 4) END AS slip_pct,
       af AS funds_applied, rf AS funds_reported, afee AS fee_applied, rfee AS fee_reported,
       CASE WHEN af > 0 AND afee IS NOT NULL THEN round(afee / af * 100, 4) END AS fee_pct, pend,
       CASE WHEN unk THEN '확인 불가' WHEN q > 0 AND (rf <> af OR rfee <> afee) THEN '보고≠반영' WHEN q > 0 AND sp IS NULL THEN '기준가 없음' ELSE '' END AS note
  FROM _o ORDER BY id;

\echo '== L3 왕복별(청산된 포지션) — 비용 = 수수료 + 슬리피지(기준가 있는 것만), 비용률 = 비용 / 매수 대금 (§6-2 왕복 실측 비용 > 0.40% 대조)'
WITH _o AS (
  SELECT o.id, o.position_id, o.side, o.state, o.filled_quantity AS q, o.signal_price AS sp, o.created_at, coalesce(o.filled_at, o.created_at) AS ft,
         o.reported_executed_funds AS rf, o.reported_paid_fee AS rfee, o.applied_executed_funds AS af, o.applied_paid_fee AS afee,
         o.fill_accounting_pending AS pend,
         (o.filled_quantity > 0 AND (o.reported_executed_funds IS NULL OR o.reported_paid_fee IS NULL OR o.applied_executed_funds IS NULL
                                      OR o.applied_paid_fee IS NULL OR o.fill_accounting_pending)) AS unk,
         CASE WHEN o.filled_quantity > 0 AND o.signal_price IS NOT NULL AND o.applied_executed_funds IS NOT NULL THEN
              CASE o.side WHEN 'BUY' THEN o.applied_executed_funds - o.signal_price * o.filled_quantity
                          ELSE o.signal_price * o.filled_quantity - o.applied_executed_funds END END AS slip
    FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :sid)
SELECT p.id AS pos, p.status, p.exit_reason,
       sum(o.af) FILTER (WHERE o.side = 'BUY') AS buy_funds, sum(o.af) FILTER (WHERE o.side = 'SELL') AS sell_funds,
       sum(o.afee) AS fees, round(sum(o.slip), 2) AS slip_krw, count(*) FILTER (WHERE o.q > 0 AND o.slip IS NULL) AS no_ref,
       round((sum(o.afee) + coalesce(sum(o.slip), 0)) / nullif(sum(o.af) FILTER (WHERE o.side = 'BUY'), 0) * 100, 4) AS cost_pct,
       sum(o.af - o.afee) FILTER (WHERE o.side = 'SELL') - sum(o.af + o.afee) FILTER (WHERE o.side = 'BUY') AS net_krw,
       p.realized_pnl AS realized_pnl_ref,
       bool_or(o.unk) AS unk
  FROM position p JOIN _o o ON o.position_id = p.id AND o.q > 0
 WHERE p.session_kind = 'LIVE' AND p.session_id = :sid
 GROUP BY p.id, p.status, p.exit_reason, p.realized_pnl ORDER BY p.id;

\echo '== L4 지금 대조 — 세션 잔액 = 장부 현금 · 순체결량 = 열린 포지션 수량 · 지금 자산·순손익 · realized_pnl 합계(참고)'
WITH _o AS (
  SELECT o.id, o.position_id, o.side, o.state, o.filled_quantity AS q, o.signal_price AS sp, o.created_at, coalesce(o.filled_at, o.created_at) AS ft,
         o.reported_executed_funds AS rf, o.reported_paid_fee AS rfee, o.applied_executed_funds AS af, o.applied_paid_fee AS afee,
         o.fill_accounting_pending AS pend,
         (o.filled_quantity > 0 AND (o.reported_executed_funds IS NULL OR o.reported_paid_fee IS NULL OR o.applied_executed_funds IS NULL
                                      OR o.applied_paid_fee IS NULL OR o.fill_accounting_pending)) AS unk,
         CASE WHEN o.filled_quantity > 0 AND o.signal_price IS NOT NULL AND o.applied_executed_funds IS NOT NULL THEN
              CASE o.side WHEN 'BUY' THEN o.applied_executed_funds - o.signal_price * o.filled_quantity
                          ELSE o.signal_price * o.filled_quantity - o.applied_executed_funds END END AS slip
    FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :sid)
SELECT l.status, l.available_krw,
       CASE WHEN (SELECT count(*) FROM _o WHERE unk) > 0 THEN NULL ELSE
       :init::numeric - coalesce((SELECT sum(af + afee) FROM _o WHERE side = 'BUY' AND q > 0), 0)
                      + coalesce((SELECT sum(af - afee) FROM _o WHERE side = 'SELL' AND q > 0), 0) END AS ledger_cash,
       (SELECT count(*) FROM _o WHERE unk) AS unk_orders,
       coalesce((SELECT sum(q) FROM _o WHERE side = 'BUY' AND q > 0), 0) - coalesce((SELECT sum(q) FROM _o WHERE side = 'SELL' AND q > 0), 0) AS net_btc,
       (SELECT coalesce(sum(size), 0) FROM position WHERE session_kind = 'LIVE' AND session_id = :sid AND status IN ('OPEN', 'CLOSING')) AS open_size,
       (SELECT c.close || ' @' || to_char((c.time + interval '1 hour') AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') FROM market_data_cache c
         WHERE c.coin_pair = l.coin_pair AND c.timeframe = 'H1' AND c.time + interval '1 hour' <= now() ORDER BY c.time DESC LIMIT 1) AS last_mark,
       (SELECT sum(realized_pnl) FROM position WHERE session_kind = 'LIVE' AND session_id = :sid) AS realized_pnl_sum_ref,
       (SELECT sum(afee) FROM _o WHERE q > 0) AS fees_total, (SELECT round(sum(slip), 2) FROM _o WHERE q > 0) AS slip_total
  FROM live_trading_session l WHERE l.id = :sid;

ROLLBACK;
\echo '== 끝'
