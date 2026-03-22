-- market_regime 컬럼 길이 확장: TRANSITIONAL(12자) 등 긴 값 처리를 위해 VARCHAR(10) → VARCHAR(20)
ALTER TABLE backtest_trade   ALTER COLUMN market_regime TYPE VARCHAR(20);
ALTER TABLE strategy_log     ALTER COLUMN market_regime TYPE VARCHAR(20);
ALTER TABLE strategy_signal  ALTER COLUMN market_regime TYPE VARCHAR(20);
