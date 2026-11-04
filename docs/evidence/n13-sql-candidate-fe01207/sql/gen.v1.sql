-- N13 성능 측정용 생성 자료 (로컬 격리 PostgreSQL 전용, 운영 자료 아님)
-- psql 변수: hours(코인당 시간 수), scen(S1|S2|S3), seed(0~1 실수)
-- 기준 시각 T0 = 2026-11-04 12:00:00+00. cutoff4 = T0-4h, cutoff24 = T0-24h.
-- 코인 40개. 코인·시간마다 HOLD(가격 있음) 행 1~29(평균 15), 그 밖 행(BUY/SELL/가격 NULL HOLD) 0~29(평균 약 15).
-- 완료 표시는 앱과 같게 "시간대 첫 적격 HOLD 행"(대표)에만 price_after_* 를 넣는다.
--   S1 완료 앞쪽 집중: cutoff 보다 6시간 이상 앞 시간대는 모두 완료, 그 중 코인당 5개 시간대만 미완료(조회 실패로 남은 형태)
--   S2 미평가 다수: cutoff 앞 시간대 중 30%만 완료(무작위)
--   S3 결과 0: cutoff 앞 시간대 모두 완료
-- 4h 와 24h 완료는 따로 정한다(S2 는 서로 다른 무작위).
\set ON_ERROR_STOP 1
SELECT setseed(:seed);
TRUNCATE strategy_log RESTART IDENTITY;

CREATE TEMP TABLE g_hour AS
SELECT c AS coin_no,
       format('KRW-C%s', lpad(c::text, 3, '0')) AS coin_pair,
       timestamptz '2026-11-04 12:00:00+00' - make_interval(hours => h) AS hour_start,
       h AS hours_ago,
       1 + floor(random() * 29)::int AS n_hold,
       floor(random() * 30)::int     AS n_other,
       random() AS r4, random() AS r24, random() AS rstuck
FROM generate_series(1, 40) c, generate_series(1, :hours) h;

-- 대표가 될 HOLD 행은 시간대 안의 서로 다른 초(동률 없음)에 둔다.
INSERT INTO strategy_log (strategy_name, coin_pair, signal, reason, indicators_json, market_regime,
                          created_at, session_type, session_id, signal_price, was_executed, timeframe)
SELECT 'STRAT_' || (1 + (i % 6)),
       gh.coin_pair,
       'HOLD',
       'HOLD — 진입 조건 미충족 (생성 자료) 지표 요약 ' || repeat('x', 40 + (i % 30)),
       jsonb_build_object('rsi', round((random() * 100)::numeric, 2), 'ema9', round((random() * 1000)::numeric, 4),
                          'ema21', round((random() * 1000)::numeric, 4), 'atr', round((random() * 10)::numeric, 4),
                          'note', repeat('y', 20)),
       'RANGE',
       gh.hour_start + make_interval(secs => (i * 3600.0 / (gh.n_hold + 1))::int),
       'DYN_PAPER', 1 + (i % 66), 1000 + gh.coin_no + random(), false, '1h'
FROM g_hour gh, generate_series(1, 29) i
WHERE i <= gh.n_hold;

INSERT INTO strategy_log (strategy_name, coin_pair, signal, reason, indicators_json, market_regime,
                          created_at, session_type, session_id, signal_price, was_executed, timeframe)
SELECT 'STRAT_' || (1 + (i % 6)),
       gh.coin_pair,
       CASE WHEN i % 3 = 0 THEN 'BUY' WHEN i % 3 = 1 THEN 'SELL' ELSE 'HOLD' END,
       '기타 행 (생성 자료) ' || repeat('z', 40 + (i % 30)),
       jsonb_build_object('rsi', round((random() * 100)::numeric, 2), 'note', repeat('y', 40)),
       'TREND',
       gh.hour_start + make_interval(secs => floor(random() * 3600)::int),
       'DYN_PAPER', 1 + (i % 66),
       CASE WHEN i % 3 = 2 THEN NULL ELSE 1000 + gh.coin_no + random() END,
       false, '1h'
FROM g_hour gh, generate_series(1, 29) i
WHERE i <= gh.n_other;

-- 완료 여부(시간대 단위)
CREATE TEMP TABLE g_done AS
SELECT coin_pair, hour_start,
       CASE :'scen'
         WHEN 'S1' THEN hour_start + interval '1 hour' <= timestamptz '2026-11-04 12:00:00+00' - interval '4 hour' - interval '6 hour'
                        AND NOT (rstuck < 5.0 / :hours)
         WHEN 'S2' THEN hour_start + interval '1 hour' <= timestamptz '2026-11-04 12:00:00+00' - interval '4 hour' AND r4 < 0.30
         ELSE hour_start < timestamptz '2026-11-04 12:00:00+00' - interval '4 hour'
       END AS done4,
       CASE :'scen'
         WHEN 'S1' THEN hour_start + interval '1 hour' <= timestamptz '2026-11-04 12:00:00+00' - interval '24 hour' - interval '6 hour'
                        AND NOT (rstuck < 5.0 / :hours)
         WHEN 'S2' THEN hour_start + interval '1 hour' <= timestamptz '2026-11-04 12:00:00+00' - interval '24 hour' AND r24 < 0.30
         ELSE hour_start < timestamptz '2026-11-04 12:00:00+00' - interval '24 hour'
       END AS done24
FROM g_hour;

CREATE TEMP TABLE g_rep AS
SELECT DISTINCT ON (l.coin_pair, date_trunc('hour', l.created_at)) l.id, l.coin_pair, date_trunc('hour', l.created_at) AS h
FROM strategy_log l
WHERE l.signal = 'HOLD' AND l.signal_price IS NOT NULL
ORDER BY l.coin_pair, date_trunc('hour', l.created_at), l.created_at;

UPDATE strategy_log s SET price_after_4h = s.signal_price * 1.01, return_4h_pct = 1.0
FROM g_rep r JOIN g_done d ON d.coin_pair = r.coin_pair AND d.hour_start = r.h
WHERE s.id = r.id AND d.done4;

UPDATE strategy_log s SET price_after_24h = s.signal_price * 0.99, return_24h_pct = -1.0
FROM g_rep r JOIN g_done d ON d.coin_pair = r.coin_pair AND d.hour_start = r.h
WHERE s.id = r.id AND d.done24;

ANALYZE strategy_log;

SELECT 'rows' AS k, count(*)::text AS v FROM strategy_log
UNION ALL SELECT 'pages', relpages::text FROM pg_class WHERE relname = 'strategy_log'
UNION ALL SELECT 'size_bytes', pg_relation_size('strategy_log')::text
UNION ALL SELECT 'hold_priced', count(*)::text FROM strategy_log WHERE signal='HOLD' AND signal_price IS NOT NULL
UNION ALL SELECT 'done4_rows', count(*)::text FROM strategy_log WHERE price_after_4h IS NOT NULL
UNION ALL SELECT 'done24_rows', count(*)::text FROM strategy_log WHERE price_after_24h IS NOT NULL
UNION ALL SELECT 'l4_rows', count(*)::text FROM strategy_log WHERE signal='HOLD' AND signal_price IS NOT NULL AND price_after_4h IS NULL AND created_at < timestamptz '2026-11-04 08:00:00+00'
UNION ALL SELECT 'l24_rows', count(*)::text FROM strategy_log WHERE signal='HOLD' AND signal_price IS NOT NULL AND price_after_24h IS NULL AND created_at < timestamptz '2026-11-03 12:00:00+00'
UNION ALL SELECT 'content_md5', md5(string_agg(id::text || '|' || coin_pair || '|' || coalesce(signal,'') || '|' || created_at::text || '|' || coalesce(signal_price::text,'') || '|' || coalesce(price_after_4h::text,'') || '|' || coalesce(price_after_24h::text,''), ',' ORDER BY id)) FROM strategy_log;
