"""Read-only, narrowed preflight through existing local API and read-only SQL."""
import datetime
import decimal
import json
import pathlib
import subprocess
import urllib.request

ROOT = pathlib.Path.home() / "crypto_trader/AutoTrader"
EXPECTED_SHA = "1b719645ef907cb1aa45ffa01438fcfda7566758"
EXPECTED_IMAGE = "sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89"

def run(args, **kw):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, check=False, **kw)
    if result.returncode or result.stderr:
        raise RuntimeError("COMMAND_FAILED_OR_STDERR")
    return result.stdout

def api(path, token):
    start = datetime.datetime.now(datetime.timezone.utc).isoformat()
    req = urllib.request.Request("http://127.0.0.1:8080" + path,
        headers={"Authorization": "Bearer " + token}, method="GET")
    with urllib.request.urlopen(req, timeout=25) as response:
        if response.status != 200:
            raise RuntimeError("API_HTTP_FAILED")
        data = json.loads(response.read(), parse_float=decimal.Decimal)
    if data.get("success") is not True or not isinstance(data.get("data"), dict) or "error" in data["data"]:
        raise RuntimeError("API_DATA_FAILED")
    return data["data"], {"before": start, "after": datetime.datetime.now(datetime.timezone.utc).isoformat()}

def number(value):
    if isinstance(value, bool) or value is None:
        raise ValueError("INVALID_DECIMAL")
    n = decimal.Decimal(str(value))
    if not n.is_finite() or n < 0:
        raise ValueError("INVALID_DECIMAL")
    return str(n)

def main():
    if run(["git", "rev-parse", "HEAD"]).decode().strip() != EXPECTED_SHA:
        raise RuntimeError("HEAD_MISMATCH")
    if run(["git", "status", "--porcelain", "--untracked-files=no"]).strip():
        raise RuntimeError("TRACKED_FILES_CHANGED")
    runtime = run(["docker", "inspect", "--format", "{{.Image}}|{{.State.StartedAt}}", "autotrader-backend-1"]).decode().strip().split("|")
    if runtime != [EXPECTED_IMAGE, "2026-11-28T03:10:38.948790758Z"]:
        raise RuntimeError("IMAGE_MISMATCH")
    values = [line.split("=", 1)[1].strip() for line in (ROOT / ".env").read_text().splitlines() if line.startswith("API_AUTH_TOKEN=")]
    if len(values) != 1 or not values[0]:
        raise RuntimeError("AUTH_NOT_CONFIGURED")
    token = values[0]
    sql = r'''
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='15s';
SET LOCAL lock_timeout='2s';
SELECT json_build_object(
 'live_alloc', (SELECT COALESCE(sum(initial_capital),0) FROM live_trading_session WHERE session_type='REAL' AND status IN ('CREATED','RUNNING','PAUSED')),
 'dynamic_alloc', (SELECT COALESCE(sum(initial_capital),0) FROM dynamic_session WHERE trading_mode='REAL' AND status IN ('CREATED','RUNNING','PAUSED')),
 'real_live_states', (SELECT COALESCE(json_agg(json_build_object('id',id,'status',status) ORDER BY id),'[]'::json) FROM live_trading_session WHERE session_type='REAL' AND status IN ('CREATED','RUNNING','PAUSED')),
 'real_dynamic_states', (SELECT COALESCE(json_agg(json_build_object('id',id,'status',status) ORDER BY id),'[]'::json) FROM dynamic_session WHERE trading_mode='REAL' AND status IN ('CREATED','RUNNING','PAUSED')),
 'active_real_orders', (SELECT count(*) FROM "order" o LEFT JOIN live_trading_session l ON o.session_kind='LIVE' AND l.id=o.session_id LEFT JOIN dynamic_session d ON o.session_kind='DYNAMIC' AND d.id=o.session_id WHERE o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED') AND (l.session_type='REAL' OR d.trading_mode='REAL')),
 'open_real_positions', (SELECT count(*) FROM position p LEFT JOIN live_trading_session l ON p.session_kind='LIVE' AND l.id=p.session_id LEFT JOIN dynamic_session d ON p.session_kind='DYNAMIC' AND d.id=p.session_id WHERE p.status IN ('OPEN','CLOSING') AND (l.session_type='REAL' OR d.trading_mode='REAL')),
 'cleanup_pending', (SELECT count(*) FROM position_cleanup WHERE done_at IS NULL),
 'sid203_ok', (SELECT status='CREATED' AND available_krw=20200 AND started_at IS NULL FROM live_trading_session WHERE id=203),
 'engine_h1_ready', (SELECT COALESCE(max(time) FILTER (WHERE time+interval '1 hour'<=now()) >= date_trunc('hour',now()-interval '2 minutes')-interval '1 hour',false) FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time>=now()-interval '500 hours' AND time<=now()),
 'engine_h1_count', (SELECT count(*) FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time>=now()-interval '500 hours' AND time<=now()),
 'engine_h1_missing', (SELECT COALESCE((extract(epoch FROM max(time)-min(time))/3600)::bigint+1-count(*),999999) FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time>=now()-interval '500 hours' AND time+interval '1 hour'<=now()),
 'official_window_registered', EXISTS(SELECT 1 FROM exchange_maintenance_window WHERE id=2 AND exchange='UPBIT' AND scope='ALL_MARKETS' AND starts_at=TIMESTAMPTZ '2026-11-08 17:00:00+00' AND ends_at=TIMESTAMPTZ '2026-11-08 22:00:00+00' AND source='https://docs.upbit.com/kr/changelog/server_maintenance_1109' AND source_timezone='Asia/Seoul'),
 'engine_h1_covered_missing', (
   WITH have AS(SELECT time FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time>=now()-interval '500 hours' AND time+interval '1 hour'<=now()),
   slots AS(SELECT generate_series(min(time),max(time),interval '1 hour') AS time FROM have)
   SELECT count(*) FROM slots s LEFT JOIN have h ON h.time=s.time
   JOIN exchange_maintenance_window w ON w.id=2 AND w.exchange='UPBIT' AND w.scope='ALL_MARKETS' AND w.starts_at=TIMESTAMPTZ '2026-11-08 17:00:00+00' AND w.ends_at=TIMESTAMPTZ '2026-11-08 22:00:00+00' AND w.starts_at<=s.time AND s.time+interval '1 hour'<=w.ends_at AND w.ends_at<=now()
   WHERE h.time IS NULL
 )
);
ROLLBACK;
'''
    chance, chance_time = api("/api/v1/settings/upbit/order-chance?market=KRW-BTC", token)
    market = chance["market"]
    if market["id"] != "KRW-BTC" or chance["bid_account"]["currency"] != "KRW" or chance["ask_account"]["currency"] != "BTC":
        raise RuntimeError("MARKET_OR_CURRENCY_MISMATCH")
    db_before = datetime.datetime.now(datetime.timezone.utc).isoformat()
    db_raw = run(["docker", "compose", "-f", "docker-compose.prod.yml", "exec", "-T", "-e", "PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=2000", "db", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "trader", "-d", "crypto_auto_trader"], input=sql.encode())
    db = json.loads(db_raw, parse_float=decimal.Decimal)
    db_after = datetime.datetime.now(datetime.timezone.utc).isoformat()
    summary, summary_time = api("/api/v1/account/summary", token)
    if summary.get("apiKeyConfigured") is not True:
        raise RuntimeError("ACCOUNT_NOT_CONFIGURED")
    selected = {
        "krw_available": number(chance["bid_account"]["balance"]),
        "krw_locked": number(chance["bid_account"]["locked"]),
        "btc_available": number(chance["ask_account"]["balance"]),
        "btc_locked": number(chance["ask_account"]["locked"]),
        "bid_fee": number(chance["bid_fee"]), "ask_fee": number(chance["ask_fee"]),
        "bid_min_total": number(market["bid"]["min_total"]),
        "ask_min_total": number(market["ask"]["min_total"]),
        "market_state": market["state"], "bid_types": market["bid_types"], "ask_types": market["ask_types"],
    }
    holdings = summary.get("holdings")
    if not isinstance(holdings, list):
        raise RuntimeError("HOLDINGS_MISSING")
    result = {
        "status": "READ_OK", "observations_only": True,
        "clock_axis": "server UTC brackets; not an atomic exchange/DB snapshot",
        "chance_time": chance_time, "db_time": {"before": db_before, "after": db_after},
        "summary_time": summary_time, "chance": selected, "db": db,
        "gap_absence_proof_backend_started_at": runtime[1],
        "account_holding_currency_count": len(holdings),
        "non_btc_holding_currencies": [h["currency"] for h in holdings if h["currency"] != "BTC"],
        "non_btc_positive_holdings_count": sum(1 for h in holdings if h["currency"] != "BTC" and decimal.Decimal(number(h["balance"]))+decimal.Decimal(number(h["locked"])) > 0),
        "automatic_create_or_start": False,
    }
    print(json.dumps(result, ensure_ascii=True, default=str, sort_keys=True))

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"status": "READ_FAILED", "reason": type(exc).__name__}))
        raise SystemExit(1)
