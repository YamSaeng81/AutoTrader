CREATE TABLE fw_tmp AS SELECT version FROM flyway_schema_history;
DROP TABLE flyway_schema_history;
CREATE TABLE flyway_schema_history(installed_rank int PRIMARY KEY, version varchar(50), description varchar(200) NOT NULL, type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum int, installed_by varchar(100) NOT NULL, installed_on timestamp NOT NULL DEFAULT now(), execution_time int NOT NULL, success boolean NOT NULL);
INSERT INTO flyway_schema_history SELECT row_number() OVER (ORDER BY version::int), version, 'x', 'SQL', 'V' || version || '__x.sql', 0, 'postgres', (now() AT TIME ZONE 'UTC') - interval '100 days' + version::int * interval '10 minutes', 1, true FROM fw_tmp;
INSERT INTO flyway_schema_history VALUES (200, '97', 'fail', 'SQL', 'V97__fail.sql', 0, 'postgres', now() AT TIME ZONE 'UTC', 1, false), (201, NULL, 'rep', 'SQL', 'R__rep.sql', 0, 'postgres', now() AT TIME ZONE 'UTC', 1, true);
DROP TABLE fw_tmp;
INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, trading_mode, status) VALUES (1,'X','H1',1,1,1,'REAL','STOPPED');
DROP TABLE position_cleanup;
