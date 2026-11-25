-- r3 합성 경계 사례(총괄 v1.245 §3). 새 DB oe2_gate_r3 전용. 기존 r1·r2 DB에는 쓰지 않는다.
-- r2 합성 정상 경로는 position(…, "CLOSED", "0")으로 size 0을 넣어 제품 LIVE 전량 매도(CLOSED·size 유지)와 달랐다.
-- 이번 정상 사례는 r2 실제 세션 24처럼 CLOSED·size 0.00019900·TAKE_PROFIT과 BUY/SELL FILLED·매도 반영 표시를 모두 갖춘다.
\set ON_ERROR_STOP 1
CREATE FUNCTION pg_temp.sess() RETURNS bigint LANGUAGE sql AS $$
  INSERT INTO live_trading_session (session_type, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw,
    invest_ratio, max_investment, stop_loss_pct, max_hold_hours, strategy_params, status, started_at, stopped_at)
  VALUES ('REAL','DAILY_SMA20','KRW-BTC','H1',10000,10089.50,10089.50,0.995,9950,5.00,0,'{"maxEntries":1}'::jsonb,'STOPPED',
    now() - interval '3600 seconds', now() - interval '60 seconds') RETURNING id $$;
CREATE FUNCTION pg_temp.pos(sid bigint, st text, sz numeric) RETURNS bigint LANGUAGE sql AS $$
  INSERT INTO position (coin_pair, side, entry_price, avg_price, size, realized_pnl, status, session_kind, session_id, opened_at, closed_at, exit_reason)
  VALUES ('KRW-BTC','BUY',50000000,50000000,sz,CASE WHEN st IS DISTINCT FROM 'OPEN' THEN 94.47525 ELSE 0 END,st,'LIVE',sid,
    now() - interval '3000 seconds', CASE WHEN st IS DISTINCT FROM 'OPEN' AND st IS DISTINCT FROM 'CLOSING' THEN now() - interval '120 seconds' END,
    CASE WHEN st IS DISTINCT FROM 'OPEN' AND st IS DISTINCT FROM 'CLOSING' THEN 'TAKE_PROFIT' END) RETURNING id $$;
CREATE FUNCTION pg_temp.ord(sid bigint, pid bigint, sd text, qty numeric, applied boolean) RETURNS bigint LANGUAGE sql AS $$
  INSERT INTO "order" (coin_pair, side, order_type, quantity, state, session_kind, session_id, position_id, exchange_order_id, fee_source, sell_applied_at)
  VALUES ('KRW-BTC',sd,'MARKET',qty,'FILLED','LIVE',sid,pid,'g-' || gen_random_uuid(),'REPORTED',CASE WHEN applied THEN now() END) RETURNING id $$;
CREATE FUNCTION pg_temp.cleanup(sid bigint, pid bigint, done boolean) RETURNS void LANGUAGE sql AS $$
  INSERT INTO position_cleanup (position_id, session_kind, session_id, reason, pending_at, done_at, attempts, last_note)
  VALUES (pid,'LIVE',sid,'STOP',now() - interval '100 seconds',CASE WHEN done THEN now() - interval '90 seconds' END,CASE WHEN done THEN 1 ELSE 0 END,'r3 합성') $$;
-- 전량 매도 왕복: BUY FILLED 9950원 · SELL FILLED 0.00019900 · 매도 반영 표시(applied) · 정리 이력(cleanup: NULL=행 없음)
CREATE FUNCTION pg_temp.trip(name text, st text, sz numeric, applied boolean, cl boolean) RETURNS void LANGUAGE plpgsql AS $$
DECLARE s bigint; p bigint; BEGIN
  s := pg_temp.sess(); p := pg_temp.pos(s, st, sz);
  PERFORM pg_temp.ord(s, p, 'BUY', 9950, false); PERFORM pg_temp.ord(s, p, 'SELL', 0.00019900, applied);
  IF cl IS NOT NULL THEN PERFORM pg_temp.cleanup(s, p, cl); END IF;
  INSERT INTO r3_manifest VALUES (name, s); END $$;
CREATE TEMP TABLE r3_manifest (name text, sid bigint);
SELECT pg_temp.trip('c_closed_size_sold', 'CLOSED', 0.00019900, true, true);
DO $$ DECLARE s bigint; p bigint; BEGIN   -- OPEN·size 0 (매수 FILLED, 매도 없음)
  s := pg_temp.sess(); p := pg_temp.pos(s, 'OPEN', 0); PERFORM pg_temp.ord(s, p, 'BUY', 9950, false);
  INSERT INTO r3_manifest VALUES ('c_open_size0', s); END $$;
DO $$ DECLARE s bigint; p bigint; BEGIN   -- CLOSING·size 0 (매수 FILLED, 매도 없음)
  s := pg_temp.sess(); p := pg_temp.pos(s, 'CLOSING', 0); PERFORM pg_temp.ord(s, p, 'BUY', 9950, false);
  INSERT INTO r3_manifest VALUES ('c_closing_size0', s); END $$;
DO $$ DECLARE s bigint; p bigint; BEGIN   -- 부분 매도 뒤 OPEN·양수 잔량 (매도 0.0001 FILLED 반영, 잔량 0.000099)
  s := pg_temp.sess(); p := pg_temp.pos(s, 'OPEN', 0.00009900);
  PERFORM pg_temp.ord(s, p, 'BUY', 9950, false); PERFORM pg_temp.ord(s, p, 'SELL', 0.00010000, true);
  INSERT INTO r3_manifest VALUES ('c_partial_open', s); END $$;
SELECT pg_temp.trip('c_closed_sell_unapplied', 'CLOSED', 0.00019900, false, true);
SELECT pg_temp.trip('c_closed_cleanup_pending', 'CLOSED', 0.00019900, true, false);
SELECT pg_temp.trip('c_pos_status_unknown', 'HELD', 0.00019900, true, true);
SELECT pg_temp.trip('c_pos_status_null', NULL, 0.00019900, true, true);
\copy (SELECT name, sid FROM r3_manifest ORDER BY sid) TO STDOUT
