
INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, created_at)
VALUES (203,'DAILY_SMA20','KRW-BTC','H1',731911,642817,731911,'CREATED','2026-10-30 04:16:58.044684+00');
INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode, created_at)
VALUES (900,'COMPOSITE_BREAKOUT','H1',553729,553729,553729,'RUNNING','REAL','2026-12-01 00:05:00+00'),
       (901,'COMPOSITE_BREAKOUT','H1',464631,464631,464631,'RUNNING','PAPER','2026-11-15 00:00:00+00');
INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at, closed_at)
VALUES (11,'KRW-ETH','LONG',375543,375543,0.001,'OPEN','DYNAMIC',900,375543,'2026-12-01 01:00:01+00',NULL),
       (13,'KRW-XRP','LONG',464631,464631,7,'OPEN','DYN_PAPER',901,464631,'2026-11-16 00:00:01+00',NULL);
INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, settlement_final,
                    fill_accounting_pending, applied_executed_funds)
VALUES (1,11,'KRW-ETH','BUY','MARKET',0.001,'FILLED','DYNAMIC',900,'2026-12-01 01:00:00+00',true,false,375543),
       (3,13,'KRW-XRP','BUY','MARKET',7,'FILLED','DYN_PAPER',901,'2026-11-16 00:00:00+00',true,true,464631);
INSERT INTO order_settlement_discrepancy(order_id, kind, resolved_at) VALUES (1,'FEE_DECREASE','2026-12-01 02:00:00+00'),(3,'FEE_DECREASE',NULL);
INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode, created_at) VALUES (950,'COMPOSITE_BREAKOUT','H1',286457,286457,286457,'RUNNING','REAL','2026-12-02 00:00:00+00');
INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at) VALUES (31,'KRW-SOL','LONG',286457,286457,1,'OPEN','DYNAMIC',950,286457,'2026-12-02 01:00:01+00');
INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, settlement_final, fill_accounting_pending) VALUES (31,31,'KRW-SOL','BUY','MARKET',1,'FILLED','DYNAMIC',950,'2026-12-02 01:00:00+00',false,true),(32,NULL,'KRW-SOL','SELL','MARKET',1,'SUBMITTED','DYNAMIC',950,'2026-12-02 02:00:00+00',false,false);
INSERT INTO order_settlement_discrepancy(order_id, kind, resolved_at) VALUES (32,'FUNDS_DECREASE',NULL);
UPDATE dynamic_session SET trading_mode = 'PAPER' WHERE id = 950;
