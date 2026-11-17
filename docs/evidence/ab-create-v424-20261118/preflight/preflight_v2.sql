-- 새 모의 9세션 생성 직전 사전 확인 v2 (총괄 v1.177 §3-3·v1.178 §4-3, 2026-11-17) — 읽기 전용, 끝에 ROLLBACK. 운영 실행은 승인 뒤.
--   psql -X -q --csv -v ON_ERROR_STOP=1 -v at='' -v n_new=9 -v cap=120 -f preflight_v2.sql
--   at: 판단 기준 시각(빈 문자열이면 DB now()). 격리 PG 시험은 고정 시각을 넣는다.
-- v1(create-bundle-0e1d2a02/preflight/preflight.sql)의 BTC 봉 질의는 개수·최소·최대뿐이었다. v2 는 운영 규칙을 그대로 옮긴다:
--   · 조회 창 = 판단 시각 − 500봉(PaperTradingService.CANDLE_LOOKBACK) ~ 판단 시각, 닫힌 봉 = 시작 + 주기 ≤ 판단 시각
--   · B1 최신 완료 봉: 필요 시작 = floor(판단 − 2분, 주기) − 주기 (CandleFreshnessGuard.GRACE·problem) — daily_checks.sql btc_h1_ok 와 같은 식
--   · 결측: 첫~마지막 닫힌 봉 사이 빠진 칸 ≤ 2 (MAX_MISSING_SLOTS). 🔴 공식 점검 공백 제외(ExchangeMaintenanceWindows)는 적용하지 않는다 —
--     2칸 초과면 '결측'으로 두고 점검 공백인지는 사람이 확인한다(보수적).
--   · 최소 이력: 창 안 봉 수(진행 봉 포함, 엔진의 candles.size()) ≥ 전략 최소(BundleMinCandlesTest, 시험 브랜치 커밋 67ce2ce6 출력: DAILY_SMA20 457 ·
--     COMPOSITE_MTF_CONFIRMED 100 · COMPOSITE_MEANREV_BB 20). 한 (코인·주기)에 여러 전략이면 최댓값.
--   · B3(KRW-BTC H1 만): 최근 20일 마감 봉(23:00 UTC 시작) 중 있는 개수 = 20 (daily_checks.sql btc_h1_daily 와 같은 식). SMA20 은 하루라도 없으면 HOLD.
--   · 판정 status: none(닫힌 봉 없음) / stale(B1 실패) / missing(결측 > 2 또는 최소 이력 미달 또는 B3 < 20) / normal.
--   · 전략 활성: StrategyEnablementGate 와 같은 의미 — 행이 없으면 활성, 행이 있으면 is_active IS TRUE 일 때만(NULL 은 비활성). 전략 층 → (전략·주기) 층.
--   · 세션: 전체 RUNNING + n_new ≤ cap. 522~587 은 개수에만. 중복: RUNNING·522~587 밖·같은 전략/코인/주기/자본/파라미터, forwardTag=SMA20-FWD-1 은 상태 무관.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
\echo @@db_now
SELECT now() AS db_now, coalesce(nullif(:'at', '')::timestamptz, now()) AS judge_at;
\echo @@enablement
WITH want(strategy_name, timeframe) AS (VALUES ('DAILY_SMA20', 'H1'), ('COMPOSITE_MEANREV_BB', 'M15'), ('COMPOSITE_MTF_CONFIRMED', 'M15'))
SELECT w.strategy_name, w.timeframe,
       (t.strategy_name IS NOT NULL) AS type_row, t.is_active AS type_active,
       (f.strategy_name IS NOT NULL) AS tf_row, f.is_active AS tf_active,
       ((t.strategy_name IS NULL OR t.is_active IS TRUE) AND (f.strategy_name IS NULL OR f.is_active IS TRUE)) AS enabled
FROM want w
LEFT JOIN strategy_type_enabled t ON t.strategy_name = w.strategy_name
LEFT JOIN strategy_timeframe_enabled f ON f.strategy_name = w.strategy_name AND f.timeframe = w.timeframe
ORDER BY 1, 2;
\echo @@candles
WITH r AS (SELECT coalesce(nullif(:'at', '')::timestamptz, now()) AS t),
  want(coin_pair, timeframe, minutes, min_required) AS (VALUES ('KRW-BTC', 'H1', 60, 457), ('KRW-BTC', 'M15', 15, 100), ('KRW-ETH', 'M15', 15, 100)),
  w AS (SELECT want.*, r.t,
               r.t - make_interval(mins => 500 * want.minutes) AS from_t,
               to_timestamp(floor(extract(epoch FROM r.t - interval '2 minutes') / (want.minutes * 60)) * want.minutes * 60)
                 - make_interval(mins => want.minutes) AS need
        FROM want, r),
  c AS (SELECT w.coin_pair, w.timeframe, w.minutes, w.min_required, w.need, w.t,
               count(m.time) AS n_window,
               count(m.time) FILTER (WHERE m.time + make_interval(mins => w.minutes) <= w.t) AS n_closed,
               min(m.time) FILTER (WHERE m.time + make_interval(mins => w.minutes) <= w.t) AS first_closed,
               max(m.time) FILTER (WHERE m.time + make_interval(mins => w.minutes) <= w.t) AS last_closed
        FROM w LEFT JOIN market_data_cache m ON m.coin_pair = w.coin_pair AND m.timeframe = w.timeframe AND m.time >= w.from_t AND m.time <= w.t
        GROUP BY 1, 2, 3, 4, 5, 6),
  d AS (SELECT (date_trunc('day', r.t AT TIME ZONE 'UTC') - interval '1 hour' - make_interval(days => k)) AT TIME ZONE 'UTC' AS t FROM r, generate_series(0, 19) k),
  b3 AS (SELECT count(m.close) AS n FROM d JOIN market_data_cache m ON m.coin_pair = 'KRW-BTC' AND m.timeframe = 'H1' AND m.time = d.t),
  v AS (SELECT c.*,
               CASE WHEN c.last_closed IS NULL THEN NULL
                    ELSE (extract(epoch FROM c.last_closed - c.first_closed) / (c.minutes * 60))::bigint + 1 - c.n_closed END AS missing,
               CASE WHEN c.coin_pair = 'KRW-BTC' AND c.timeframe = 'H1' THEN (SELECT n FROM b3) END AS b3_daily
        FROM c)
SELECT coin_pair, timeframe, min_required, n_window, n_closed, first_closed, last_closed, need AS need_latest_closed,
       (last_closed IS NOT NULL AND last_closed >= need) AS b1_ok, missing, b3_daily,
       CASE WHEN last_closed IS NULL THEN 'none'
            WHEN last_closed < need THEN 'stale'
            WHEN missing > 2 OR n_window < min_required OR (b3_daily IS NOT NULL AND b3_daily < 20) THEN 'missing'
            ELSE 'normal' END AS status
FROM v ORDER BY 1, 2;
\echo @@sessions
SELECT count(*) FILTER (WHERE status = 'RUNNING') AS running_total,
       count(*) FILTER (WHERE status = 'RUNNING' AND id BETWEEN 522 AND 587) AS running_prereg,
       coalesce(max(id), 0) AS max_id, :n_new::int AS n_new, :cap::int AS cap,
       count(*) FILTER (WHERE status = 'RUNNING') + :n_new::int <= :cap::int AS cap_ok
FROM paper_trading.virtual_balance;
\echo @@dup_candidates
SELECT id, status, strategy_name, coin_pair, timeframe, initial_capital, strategy_params::text AS params_json, started_at
FROM paper_trading.virtual_balance
WHERE id NOT BETWEEN 522 AND 587
  AND ((status = 'RUNNING' AND strategy_name IN ('COMPOSITE_MEANREV_BB', 'COMPOSITE_MTF_CONFIRMED', 'DAILY_SMA20')
        AND strategy_params::jsonb ? 'paperExecutionMode')
       OR strategy_params::jsonb ->> 'forwardTag' = 'SMA20-FWD-1')
ORDER BY id;
\echo @@end
ROLLBACK;
