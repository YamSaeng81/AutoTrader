SID=203; M="D:/tmp/w3-evidence/run5/f"; mkdir -p "$M"
PSQL="docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader"
export RT_SHIFT='56 days 1 minute'
EGS_LOCAL='1|0|1|true|-|-|0'   # 로컬: 실자금 LIVE 1 · DYNAMIC 0 · 기준 행 1 · 위험 한도 3.0/15.0 · 과거 비상 정지 없음
START_DATE=2026-12-01   # 시작 예정일(KST). 연기하면 이 줄을 문서에서 고쳐 커밋한 뒤 그 값으로 쓴다
NEW=1899b49f633f8aefdc66b15c42debdaba8b3852c   # v1.132: #26(11-06 13:36 KST) 결과 수용. 이전 값 ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8(#25). v1.129: #25(11-06 10:32 KST) 결과 수용. 이전 값 7a944723c2959898226c2ad80f6b079ab5678296(#24). v1.123: #24(11-05 19:41 KST) 결과 수용. 이전 값 1103f48505a1457029f6555547a87aa986535738(#23). v1.116: 개입 #23(2026-11-05 15:29 KST) 운영 결과 수용 뒤 기대 HEAD. 이전 값 fe01207f3f60e97e308925157f84a1bb2da04388(#22). 이전 값 6995c52259e3de4c7c922a32f9fb2aa9ddd49f00(#21). 이전 값 b1d7664db73222aec5d86f2e9137debef0ff4df3(#20). 이전 값 ea4490099f0fa6b614fe71102534b0ba84c0eb7f(#19). 이전 값 5bb0544926a694832e75234670bb8f99dfe33bb0(#18), cad393956e131c257195fcd08fcac18c83802788(#17), f0872e2547c918a7b5146f027d325bd441ddf0d7(#16), 82d15d42fa22bb7d0f24a8243ebc888a2a7f071c
sc() { $PSQL -At -F ' | ' -c "
  SELECT count(*) AS total,
         count(*) FILTER (WHERE status = 'RUNNING' AND id NOT IN (576,577,578)) AS running_63,
         count(*) FILTER (WHERE status = 'STOPPED' AND id IN (576,577,578))     AS stopped_3,
         coalesce(string_agg(id || ':' || status, ' ' ORDER BY id) FILTER (WHERE NOT (
             (id IN (576,577,578) AND status = 'STOPPED') OR
             (id NOT IN (576,577,578) AND status = 'RUNNING'))), '-') AS mismatch
    FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587" ; }
svc() { for s in backend db redis db-backup frontend; do docker inspect --format "$s {{.Image}} {{.State.StartedAt}}" "$($DC ps -q $s)"; done ; }
EG="SELECT (SELECT count(*) FROM live_trading_session WHERE session_type = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')) || '|' ||
           (SELECT count(*) FROM dynamic_session WHERE trading_mode = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')) || '|' ||
           (SELECT count(*) FROM capital_stage_baseline) || '|' ||
           (SELECT (max_daily_loss_pct = 3.0 AND max_portfolio_drawdown_pct = 15.0)::text FROM risk_config ORDER BY id DESC LIMIT 1) || '|' ||
           (SELECT coalesce(string_agg(id::text, ',' ORDER BY id), '-') FROM live_trading_session WHERE session_type = 'REAL' AND status = 'EMERGENCY_STOPPED') || '|' ||
           (SELECT coalesce(string_agg(id::text, ',' ORDER BY id), '-') FROM dynamic_session WHERE trading_mode = 'REAL' AND status = 'EMERGENCY_STOPPED') || '|' ||
           ((SELECT count(*) FROM live_trading_session WHERE session_type = 'REAL' AND status NOT IN ('RUNNING','PAUSED','CREATED','EMERGENCY_STOPPED','STOPPED','DELETED')) +
            (SELECT count(*) FROM dynamic_session WHERE trading_mode = 'REAL' AND status NOT IN ('RUNNING','PAUSED','CREATED','EMERGENCY_STOPPED','STOPPED','DELETED')))"
EGS='1|0|1|true|157,161,166,167,168,183,184,185,186,187,188,189,190,191,192,193,194,195,196,200|92|0'
LIVE1="SELECT coalesce(string_agg(id || ':' || status, ',' ORDER BY id), '-') FROM live_trading_session WHERE session_type = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')"
BASE="SELECT stage || '|' || to_char(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '|' || total_cap_krw FROM capital_stage_baseline"
BASEX='STAGE_1|2026-10-30 04:16:57.997762|100000.00'
KEEP="SELECT session_type = 'REAL' AND strategy_type = 'DAILY_SMA20' AND coin_pair = 'KRW-BTC' AND timeframe = 'H1'
  AND initial_capital = 20200 AND invest_ratio = 0.995 AND max_investment = 20000 AND stop_loss_pct = 5.0
  AND max_hold_hours = 0 AND coalesce(strategy_params::text, '{}') = '{}'
  AND to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') = '2026-11-06 10:07:36.870440' FROM live_trading_session WHERE id = $SID"
# v1.56 (N08): BTC H1 입력 — B1 필요 완료 봉(운영 CandleFreshnessGuard 와 같은 2분 유예) 충족 | 최신 완료 봉 | 필요 봉 | B3 최근 20일 마감 봉(23:00 UTC 시작) 개수/20
BTCIN="WITH h AS (SELECT max(time) FILTER (WHERE time + interval '1 hour' <= now()) AS done, date_trunc('hour', now() - interval '2 minutes') - interval '1 hour' AS need FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1'),
  d AS (SELECT (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '1 hour' - make_interval(days => k)) AT TIME ZONE 'UTC' AS t FROM generate_series(0, 19) k)
SELECT coalesce(h.done >= h.need, false) || '|' || coalesce(to_char(h.done AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI'), '-') || '|' || to_char(h.need AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI')
  || '|' || (SELECT count(c.close) FROM d JOIN market_data_cache c ON c.coin_pair = 'KRW-BTC' AND c.timeframe = 'H1' AND c.time = d.t) || '/20' FROM h"
$PSQL -x -c "SELECT id, status, available_krw, started_at, started_at AT TIME ZONE 'Asia/Seoul' AS started_kst, (started_at + interval '56 days') AT TIME ZONE 'Asia/Seoul' AS end_kst FROM live_trading_session WHERE id = $SID;" | tee "$M/start-check.txt"
$PSQL -At -c "$KEEP" | tee -a "$M/start-check.txt"                                  # t
$PSQL -At -c "$LIVE1" | tee -a "$M/start-check.txt"                                 # 203:RUNNING
$PSQL -At -c "$EG" | tee -a "$M/start-check.txt"; $PSQL -At -c "$BASE" | tee -a "$M/start-check.txt"   # EGS · BASEX
