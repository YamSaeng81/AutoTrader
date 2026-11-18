-- 로컬 대역 p7_parallel = rt_ledger(V94, SID203 LIVE REAL RUNNING 1행) 사본 + 아래 행. 운영과 무관.
INSERT INTO dynamic_session (id,strategy_type,timeframe,initial_capital,available_krw,total_asset_krw,status,trading_mode) VALUES (900,'COMPOSITE_BREAKOUT','H1',20000,4000,20000,'RUNNING','REAL');
-- 병행A: 정상 매수 체결·보유
INSERT INTO position (id,session_kind,session_id,coin_pair,side,entry_price,avg_price,size,status,opened_at) VALUES (9001,'DYNAMIC',900,'KRW-ETH','LONG',5000000,5000000,0.0032,'OPEN',now());
INSERT INTO "order" (session_kind,session_id,position_id,coin_pair,side,order_type,quantity,state,filled_quantity,created_at) VALUES ('DYNAMIC',900,9001,'KRW-ETH','BUY','PRICE',0.0032,'FILLED',0.0032,now());
-- 병행B: A + 제출 직후 미체결 매수
INSERT INTO "order" (session_kind,session_id,coin_pair,side,order_type,quantity,state,filled_quantity,created_at) VALUES ('DYNAMIC',900,'KRW-XRP','BUY','PRICE',10,'SUBMITTED',0,now());
