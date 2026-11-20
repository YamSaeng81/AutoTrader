
INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, created_at)
VALUES (203,'X','KRW-BTC','H1',20200,11000.5,20200,'RUNNING','2026-11-06 19:07:36+09'),
       (100,'X','KRW-ETH','H1',10000,10000,10000,'STOPPED','2026-10-01 09:00:00+09');
INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode, created_at)
VALUES (900,'D','H1',30000,30000,30000,'RUNNING','REAL','2026-11-10 09:00:00+09'),
       (950,'D','H1',777777,777777,777777,'RUNNING','PAPER','2026-11-10 09:00:00+09');
INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at, closed_at)
VALUES (7,'KRW-BTC','LONG',92000000,92000000,0.0001,'OPEN','LIVE',203,9200.09,'2026-11-20 09:00:01+09',NULL),
       (8,'KRW-BTC','LONG',90000000,90000000,0.0001,'CLOSED','LIVE',203,9000,'2026-10-21 09:00:01+09','2026-11-20 08:00:00+09'),
       (20,'KRW-ETH','LONG',5000000,5000000,0.001,'CLOSED','LIVE',100,5000,'2026-10-02 09:00:00+09','2026-10-03 09:00:00+09'),
       (30,'KRW-XRP','LONG',777,777,7,'OPEN','DYNAMIC',950,5439,'2026-11-11 09:00:00+09',NULL);
INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, filled_at,
                    sell_applied_at, settlement_final, fee_source, applied_executed_funds, applied_paid_fee)
VALUES (11,7,'KRW-BTC','BUY','MARKET',0.0001,'FILLED','LIVE',203,'2026-11-20 09:00:00+09','2026-11-20 09:00:01+09',NULL,true,'EXCHANGE',9195.5,4.59),
       (12,8,'KRW-BTC','BUY','MARKET',0.0001,'FILLED','LIVE',203,'2026-10-21 09:00:00+09','2026-10-21 09:00:01+09',NULL,true,'EXCHANGE',9000,4.5),
       (13,8,'KRW-BTC','SELL','MARKET',0.0001,'FILLED','LIVE',203,'2026-11-18 23:59:00+09','2026-11-20 07:59:00+09','2026-11-20 08:00:00+09',true,'EXCHANGE',9100,4.55),
       (14,NULL,'KRW-BTC','BUY','MARKET',0.0001,'SUBMITTED','LIVE',203,'2026-11-20 10:00:00+09',NULL,NULL,false,NULL,NULL,NULL),
       (21,20,'KRW-ETH','BUY','MARKET',0.001,'FILLED','LIVE',100,'2026-10-02 09:00:00+09','2026-10-02 09:00:01+09',NULL,true,'EXCHANGE',5000,2.5),
       (31,30,'KRW-XRP','BUY','MARKET',7,'FILLED','DYNAMIC',950,'2026-11-11 09:00:00+09','2026-11-11 09:00:01+09',NULL,true,'EXCHANGE',777777,388.9),
       (41,NULL,'KRW-SOL','BUY','MARKET',0.1,'FILLED','DYNAMIC',900,'2026-11-12 09:00:00+09','2026-11-12 09:00:01+09',NULL,true,'EXCHANGE',20000,10);
INSERT INTO order_settlement_discrepancy(id, order_id, kind, resolved_at) VALUES (1,11,'FEE_DECREASE',NULL),(2,21,'FEE_DECREASE','2026-10-04 09:00:00+09');
INSERT INTO "order"(id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, settlement_final) VALUES (51,'KRW-BTC','BUY','MARKET',1,'CANCELLED','DYNAMIC',999,'2026-01-01 09:00:00+09',true);