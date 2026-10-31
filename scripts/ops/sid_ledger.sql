-- SID 203 실거래 비용·순손익 기록 (읽기 전용) — 리뷰 2026-10-31 56일 운영 점검 2번. LIMITED_LIVE_TEST_PLAN.md §5-9 6번.
-- 실행: psql -X -v sid=203 -v init=20200 -f sid_ledger.sql
--   sid = 실거래 시험 세션(LIVE·REAL) · init = 시작 잔액(세션 initial_capital 과 같아야 한다 — L0 에서 대조)
-- 읽기 전용 트랜잭션이라 임시 뷰를 만들지 않는다 — 주문 묶음(_o)을 조회마다 다시 적는다.
-- 무엇을 내나(KST 날짜별 — 시작일부터 정지일·마지막 체결일 중 늦은 날까지, 운영 중이면 지금까지. 날 끝 = 다음 날 00:00 KST(지난 날) 또는 지금):
-- 🔴 조회 시점의 최신 반영값으로 재구성한 기록이다(리뷰 2026-10-31 보완 3). 주문의 반영·보고값은 누적 최신값이라 늦은 수수료 정정·추가 체결이 오면
--    과거 날짜의 값도 달라진다. 그날 본 값은 날짜별 원본 파일(~/sid203-ledger/YYYYMMDD.txt, 덮어쓰지 않음)로 남기고, 다시 돌린 값과 다르면 차이를 보고한다.
--   L0 세션·조회 시각 · L1 날짜별(신호·관문 차단 사유·주문·체결·수수료·슬리피지·현금·보유·평가액·순손익) · L2 주문별 · L3 왕복별 비용 · L4 지금 대조 · L5 중단 기능별 기록
-- 🔴 순손익 = (현금 + 보유 평가액) − 시작 잔액. realized_pnl 합계는 참고로만 낸다(판정에 쓰지 않는다).
--    현금 = 시작 잔액 − Σ매수(반영 대금 + 반영 수수료) + Σ매도(반영 대금 − 반영 수수료) — F3 장부와 같은 식, 체결 있는 주문의 applied_* 만.
--    🔴 원시 현금(8자리)과 잔액 기대(주문별 두 자리)는 다르다 (리뷰 2026-10-31 반올림 결정). 세션 잔액은 주문마다 누적 금액을 둘째 자리 HALF_UP 으로
--       반올림한 값만 받는다 — 매수 −요청액 + round(요청액 − 반영 대금 − 반영 수수료, 2), 매도 + round(반영 대금 − 반영 수수료, 2). L4 의 balance_expected 가
--       그 식이고 세션 잔액과 같아야 한다(balance_diff 0). ledger_cash(원시)와 세션 잔액의 차이(raw_diff)는 반올림이라 참고로 낸다.
--    체결 있는 주문의 보고값·반영값 중 하나라도 없거나 회계 반영 대기면 그 날 이후 현금·평가·순손익은 비운다(0 으로 대체하지 않는다 — "확인 불가").
--    보유 평가액 = 보유 수량 × 그 날 끝(KST 24:00, 운영 중인 날은 지금) 이전 마지막 마감 H1 봉 종가(market_data_cache). 봉이 없으면 비운다.
--    슬리피지(원) = 매수: 반영 대금 − 신호가 × 체결량, 매도: 신호가 × 체결량 − 반영 대금 (양수 = 비용). 신호가(order.signal_price)가 없는 주문(정지·보호 청산 등)은 비우고 건수를 따로 센다.
-- 🔴 범위: 이 조회는 session_kind='LIVE' AND session_id=:sid 만 읽는다. 22코인 비용 집계(scripts/forward_aggregate — paper_trading 522~587 의 CSV 추출)와
--    입력이 겹치지 않고, 그 집계에 넣지 않는다. 이 시험은 BTC 포지션 1개라 1단계 비용 판정(왕복 30건·코인 ≥ 8)을 채우지 못한다(§6-3).
\set ON_ERROR_STOP on
\pset null '-'
BEGIN TRANSACTION READ ONLY;

\echo '== L0 세션 (initial_capital = init 이어야 한다 · queried_kst = 이 기록을 재구성한 시각)'
SELECT now() AT TIME ZONE 'Asia/Seoul' AS queried_kst, id, coin_pair, status, initial_capital, :init::numeric AS init, initial_capital = :init::numeric AS init_ok, available_krw,
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

\echo '== L3 왕복별 — 사전 등록 정의(LIVE_ENTRY_PREREG.md 비용의 정의): 왕복 실측 비용(%) = 불리(진입) + 불리(청산) + 0.10 · §6-2 > 0.40% 대조'
-- 불리 슬리피지(%) = 매수 (체결가 − 신호가)/신호가 × 100, 매도 (신호가 − 체결가)/신호가 × 100 (양수 = 비용, 유리하면 음수 그대로). 체결가·신호가는 그 쪽 주문의 체결량 가중
--   (주문 1건이면 그 주문의 값). 0.10 은 수수료 편당 0.05% × 2 로 고정 — 실제 수수료는 fees·fee_pct_actual 로 따로 적고 비용률에 넣지 않는다.
-- rt_status: 열린 포지션 = '왕복 미완료' · 진입/청산 기준가(신호가) 없음 · 정산 확인 불가 · 정산 불일치 · 매수/매도 체결량 불일치 = '측정 불가(사유)' — 이 둘은 cost_pct 를 비운다(빠진 값을 빼고 계산하지 않는다).
-- 정산 불일치(리뷰 2026-10-31 장부 보완 1) = 그 왕복의 체결 주문 중 보고값≠반영값(대금 또는 수수료)이거나 그 주문에 미해결 정산 불일치 기록(order_settlement_discrepancy, resolved_at 없음)이 있음.
--   어느 값을 쓰느냐에 따라 비용률이 0.40% 양쪽으로 갈릴 수 있어 cost_pct·cost_gt_040·fee_pct_actual·net_krw 를 비운다. 기록이 해결되고 값이 같아지면 다시 '측정'.
-- cost_gt_040 은 반올림 전 값으로 판정한다(0.4000 은 f).
WITH _o AS (
  SELECT o.id, o.position_id, o.side, o.filled_quantity AS q, o.signal_price AS sp, o.applied_executed_funds AS af, o.applied_paid_fee AS afee,
         (o.filled_quantity > 0 AND (o.reported_executed_funds IS NULL OR o.reported_paid_fee IS NULL OR o.applied_executed_funds IS NULL
                                      OR o.applied_paid_fee IS NULL OR o.fill_accounting_pending)) AS unk,
         (coalesce(o.reported_executed_funds <> o.applied_executed_funds OR o.reported_paid_fee <> o.applied_paid_fee, false)
          OR EXISTS (SELECT 1 FROM order_settlement_discrepancy x WHERE x.order_id = o.id AND x.resolved_at IS NULL)) AS mism
    FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :sid AND o.filled_quantity > 0),
_p AS (
  SELECT p.id, p.status, p.exit_reason, p.realized_pnl,
         coalesce(sum(o.q) FILTER (WHERE o.side = 'BUY'), 0) AS bq, coalesce(sum(o.q) FILTER (WHERE o.side = 'SELL'), 0) AS sq,
         sum(o.af) FILTER (WHERE o.side = 'BUY') AS buy_funds, sum(o.af) FILTER (WHERE o.side = 'SELL') AS sell_funds,
         sum(o.sp * o.q) FILTER (WHERE o.side = 'BUY') AS buy_ref, sum(o.sp * o.q) FILTER (WHERE o.side = 'SELL') AS sell_ref,
         count(*) FILTER (WHERE o.side = 'BUY' AND o.sp IS NULL) AS buy_noref, count(*) FILTER (WHERE o.side = 'SELL' AND o.sp IS NULL) AS sell_noref,
         sum(o.afee) AS fees, count(*) FILTER (WHERE o.unk) AS unk, count(*) FILTER (WHERE o.mism) AS mism
    FROM position p JOIN _o o ON o.position_id = p.id
   WHERE p.session_kind = 'LIVE' AND p.session_id = :sid
   GROUP BY p.id, p.status, p.exit_reason, p.realized_pnl),
_c AS (
  SELECT _p.*,
         CASE WHEN status <> 'CLOSED' THEN '왕복 미완료'
              WHEN buy_noref + sell_noref > 0 OR unk > 0 OR mism > 0 OR bq <> sq OR buy_funds IS NULL OR sell_funds IS NULL THEN
                   '측정 불가(' || concat_ws(' · ',
                       CASE WHEN buy_noref > 0 THEN '진입 기준가 없음' END, CASE WHEN sell_noref > 0 OR sell_funds IS NULL THEN '청산 기준가 없음' END,
                       CASE WHEN unk > 0 THEN '정산 확인 불가' END, CASE WHEN mism > 0 THEN '정산 불일치' END,
                       CASE WHEN bq <> sq THEN '매수/매도 수량 불일치' END) || ')'
              ELSE '측정' END AS rt_status,
         (buy_funds - buy_ref) / nullif(buy_ref, 0) * 100 AS entry_unf, (sell_ref - sell_funds) / nullif(sell_ref, 0) * 100 AS exit_unf
    FROM _p)
SELECT id AS pos, status, exit_reason, rt_status, bq AS buy_qty, sq AS sell_qty, buy_funds, sell_funds,
       round(entry_unf, 8) AS entry_unf_pct, round(exit_unf, 8) AS exit_unf_pct,
       CASE WHEN rt_status = '측정' THEN round(entry_unf + exit_unf + 0.10, 8) END AS cost_pct,
       CASE WHEN rt_status = '측정' THEN entry_unf + exit_unf + 0.10 > 0.40 END AS cost_gt_040,
       fees, CASE WHEN unk = 0 AND mism = 0 THEN round(fees / nullif(buy_funds, 0) * 100, 4) END AS fee_pct_actual,
       CASE WHEN unk = 0 AND mism = 0 AND bq = sq AND status = 'CLOSED' THEN sell_funds - (fees) - buy_funds END AS net_krw,
       realized_pnl AS realized_pnl_ref
  FROM _c ORDER BY id;

\echo '== L4 지금 대조 — 세션 잔액 = 잔액 기대(주문별 두 자리, balance_diff 0) · 장부 현금(원시 8자리, raw_diff 참고) · 순체결량 = 열린 포지션 수량 · realized_pnl 합계(참고)'
WITH _o AS (
  SELECT o.id, o.position_id, o.side, o.state, o.filled_quantity AS q, o.quantity AS qty, o.signal_price AS sp, o.created_at, coalesce(o.filled_at, o.created_at) AS ft,
         o.reported_executed_funds AS rf, o.reported_paid_fee AS rfee, o.applied_executed_funds AS af, o.applied_paid_fee AS afee,
         o.fill_accounting_pending AS pend,
         (o.filled_quantity > 0 AND (o.reported_executed_funds IS NULL OR o.reported_paid_fee IS NULL OR o.applied_executed_funds IS NULL
                                      OR o.applied_paid_fee IS NULL OR o.fill_accounting_pending)) AS unk,
         CASE WHEN o.filled_quantity > 0 AND o.signal_price IS NOT NULL AND o.applied_executed_funds IS NOT NULL THEN
              CASE o.side WHEN 'BUY' THEN o.applied_executed_funds - o.signal_price * o.filled_quantity
                          ELSE o.signal_price * o.filled_quantity - o.applied_executed_funds END END AS slip
    FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :sid),
_k AS (
  SELECT CASE WHEN (SELECT count(*) FROM _o WHERE unk) > 0 THEN NULL ELSE
         :init::numeric - coalesce((SELECT sum(af + afee) FROM _o WHERE side = 'BUY' AND q > 0), 0)
                        + coalesce((SELECT sum(af - afee) FROM _o WHERE side = 'SELL' AND q > 0), 0) END AS ledger_cash,
         -- 잔액 기대: 매수 −요청액 + round(요청액 − 대금 − 수수료, 2) · 매도 + round(대금 − 수수료, 2) — 주문마다 두 자리(HALF_UP, PostgreSQL round 는 0 에서 먼 쪽)
         CASE WHEN (SELECT count(*) FROM _o WHERE unk) > 0 THEN NULL ELSE
         :init::numeric - coalesce((SELECT sum(qty - round(qty - af - afee, 2)) FROM _o WHERE side = 'BUY' AND q > 0), 0)
                        + coalesce((SELECT sum(round(af - afee, 2)) FROM _o WHERE side = 'SELL' AND q > 0), 0) END AS balance_expected)
SELECT l.status, l.available_krw, k.balance_expected, l.available_krw - k.balance_expected AS balance_diff,
       k.ledger_cash, l.available_krw - k.ledger_cash AS raw_diff,
       (SELECT count(*) FROM _o WHERE unk) AS unk_orders,
       coalesce((SELECT sum(q) FROM _o WHERE side = 'BUY' AND q > 0), 0) - coalesce((SELECT sum(q) FROM _o WHERE side = 'SELL' AND q > 0), 0) AS net_btc,
       (SELECT coalesce(sum(size), 0) FROM position WHERE session_kind = 'LIVE' AND session_id = :sid AND status IN ('OPEN', 'CLOSING')) AS open_size,
       (SELECT c.close || ' @' || to_char((c.time + interval '1 hour') AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') FROM market_data_cache c
         WHERE c.coin_pair = l.coin_pair AND c.timeframe = 'H1' AND c.time + interval '1 hour' <= now() ORDER BY c.time DESC LIMIT 1) AS last_mark,
       (SELECT sum(realized_pnl) FROM position WHERE session_kind = 'LIVE' AND session_id = :sid) AS realized_pnl_sum_ref,
       (SELECT sum(afee) FROM _o WHERE q > 0) AS fees_total, (SELECT round(sum(slip), 2) FROM _o WHERE q > 0) AS slip_total
  FROM live_trading_session l, _k k WHERE l.id = :sid;

\echo '== L5 중단 기능별 기록 — 서킷브레이커 · 포지션 한도 · 공유 풀 손실 한도(진입 차단 — 정지 아님) · 세션 최종 상태. 사람 판단 정지(§6-2)는 운영자 기록으로'
-- 출처마다 따로 낸다. 한 출처의 기록이 없다고 다른 중단 기능까지 "발동 없음"으로 적지 않는다.
SELECT '서킷브레이커' AS kind, circuit_breaker_trip_count::text AS n, '-' AS first_kst,
       coalesce(to_char(circuit_breaker_triggered_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS'), '-') AS last_kst,
       coalesce(circuit_breaker_reason, '-') AS detail
  FROM live_trading_session WHERE id = :sid
UNION ALL
SELECT '포지션 한도 도달', count(*)::text, coalesce(to_char(min(breached_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS'), '-'),
       coalesce(to_char(max(breached_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS'), '-'),
       coalesce(string_agg('포지션 ' || position_id || ' ' || code || ' 정지 ' || coalesce(stop_action, '-'), '; ' ORDER BY breached_at), '-')
  FROM position_limit_breach WHERE session_kind = 'LIVE' AND session_id = :sid
UNION ALL
SELECT '공유 풀 손실 한도(진입 차단)', count(*)::text, coalesce(to_char(min(created_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS'), '-'),
       coalesce(to_char(max(created_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS'), '-'), coalesce(left(max(blocked_reason), 120), '-')
  FROM strategy_log WHERE session_type = 'LIVE' AND session_id = :sid AND blocked_reason LIKE '%(POOL_LOSS_LIMIT)%'
UNION ALL
SELECT '세션 최종 상태', status, '-', coalesce(to_char(stopped_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS'), '-'),
       CASE status
         WHEN 'EMERGENCY_STOPPED' THEN CASE WHEN circuit_breaker_triggered_at >= started_at
              THEN '비상정지 — 서킷브레이커 발동 기록과 함께'
              ELSE '비상정지 — 서킷브레이커 기록 없음: 거래소 장애 일괄 비상정지·수동 비상정지 등. 사유는 앱 로그(비상 정지)·텔레그램으로 확인' END
         WHEN 'STOPPED' THEN '일반 정지 — 56일 종료 정지(F5-2 기록)·포지션 한도 자동 정지(위 줄)·사람 판단 정지(§6-2) 중 무엇인지 운영자 기록으로 확인'
         ELSE '정지 아님' END
  FROM live_trading_session WHERE id = :sid;

ROLLBACK;
\echo '== 끝'
