"""Finite server observer: read one registered LIVE session, stop once, verify gate.

Never creates/starts a session or submits exchange orders directly. Stop can liquidate.
"""
import datetime
import decimal
import hashlib
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
APP = pathlib.Path.home() / "crypto_trader/AutoTrader"
D = decimal.Decimal

def decide(snapshot, deadline, now):
    if snapshot.get("contract_ok") is not True:
        return "CONTRACT_MISMATCH"
    buys = snapshot["buys"]
    if len(buys) > 1:
        return "BUY_LIMIT_VIOLATION"
    if snapshot["status"] in ("STOPPED", "EMERGENCY_STOPPED"):
        return "ALREADY_STOPPED"
    for order in buys:
        if order["funds"] is not None and order["fee"] is not None:
            if D(str(order["funds"])) + D(str(order["fee"])) > D("10000"):
                return "COST_LIMIT_VIOLATION"
        if order["state"] == "FILLED" and not order["pending"] and order["funds"] is not None and order["fee"] is not None:
            return "BUY_SETTLED"
        if order["state"] in ("FAILED", "CANCELLED"):
            return "BUY_TERMINAL_NO_RETRY"
        if order["state"] == "UNKNOWN":
            return "ORDER_UNKNOWN"
    if now >= deadline:
        return "OBSERVATION_DEADLINE"
    return None

def record(path, value):
    data = (json.dumps(value, ensure_ascii=True, default=str, sort_keys=True) + "\n").encode()
    tmp = path.with_suffix(path.suffix + ".uncommitted")
    with tmp.open("xb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)

def sql_query(sql, sid=None, tabular=False):
    args = ["docker", "compose", "-f", "docker-compose.prod.yml", "exec", "-T", "-e",
        "PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=2000",
        "db", "psql", "-X", "-qA", "-F", "|", "-P", "footer=off", "-v", "ON_ERROR_STOP=1", "-U", "trader", "-d", "crypto_auto_trader"]
    if not tabular:
        args.append("-t")
    if sid is not None:
        args += ["-v", "sid=" + str(sid)]
    p = subprocess.run(args, input=sql.encode(), capture_output=True, cwd=APP)
    if p.returncode or p.stderr:
        raise RuntimeError("READ_FAILED")
    return p.stdout

def snapshot(sid):
    sql = f"""
SELECT json_build_object('id',s.id,'status',s.status,
 'contract_ok', (s.session_type='REAL' AND s.strategy_type='DAILY_SMA20' AND s.coin_pair='KRW-BTC' AND s.timeframe='H1'
 AND s.initial_capital=10000 AND s.invest_ratio=0.995 AND s.max_investment=9950 AND s.stop_loss_pct=5 AND s.max_hold_hours=0
 AND jsonb_typeof(s.strategy_params->'maxEntries')='number' AND s.strategy_params->>'maxEntries'='1'),
 'available_krw',s.available_krw,
 'buys',(SELECT COALESCE(json_agg(json_build_object('id',o.id,'state',o.state,'funds',o.applied_executed_funds,'fee',o.applied_paid_fee,'pending',o.fill_accounting_pending) ORDER BY o.id),'[]'::json) FROM "order" o WHERE o.session_kind='LIVE' AND o.session_id=s.id AND upper(o.side)='BUY'),
 'open_positions',(SELECT count(*) FROM position p WHERE p.session_kind='LIVE' AND p.session_id=s.id AND p.status IN ('OPEN','CLOSING')),
 'active_orders',(SELECT count(*) FROM "order" o WHERE o.session_kind='LIVE' AND o.session_id=s.id AND o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')),
 'cleanup_pending',(SELECT count(*) FROM position_cleanup c WHERE c.session_kind='LIVE' AND c.session_id=s.id AND c.done_at IS NULL))
FROM live_trading_session s WHERE s.id={sid};
"""
    return json.loads(sql_query(sql), parse_float=D)

def auth_request(method, path):
    spec = importlib.util.spec_from_file_location("safe_api", HERE / "safe_api.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    values = [x.split("=", 1)[1].strip() for x in (APP / ".env").read_text().splitlines() if x.startswith("API_AUTH_TOKEN=")]
    if len(values) != 1:
        raise RuntimeError("AUTH_NOT_CONFIGURED")
    return mod.request(method, path, "", values[0])

def finish(out, sid, reason):
    before = snapshot(sid)
    record(out / "stop-reason.json", {"reason": reason, "before": before})
    if before.get("contract_ok") is not True:
        raise RuntimeError("TARGET_CONTRACT_MISMATCH_NO_POST")
    if before["status"] not in ("STOPPED", "EMERGENCY_STOPPED"):
        with (out / "sent-stop").open("x") as f:
            f.write(datetime.datetime.now(datetime.timezone.utc).isoformat() + "\n")
            f.flush()
            os.fsync(f.fileno())
        response = auth_request("POST", f"/api/v1/trading/sessions/{sid}/stop")
        record(out / "stop-response.json", response)
    end = time.monotonic() + 300
    while True:
        state = snapshot(sid)
        record(out / "closing-state.json", state)
        if state["status"] == "STOPPED" and not state["active_orders"] and not state["open_positions"] and not state["cleanup_pending"]:
            break
        if time.monotonic() >= end:
            raise RuntimeError("CLOSING_UNCONFIRMED")
        time.sleep(10)
    gate = sql_query((HERE / "recovery-gate-v4.sql").read_text(), sid=sid, tabular=True)
    (out / "gate.txt").write_bytes(gate)
    (out / "gate-rc.txt").write_text("0\n")
    registration = f"sid={sid}\nsession_type=REAL\nstrategy_type=DAILY_SMA20\ncoin_pair=KRW-BTC\ntimeframe=H1\ninitial_capital=10000\ninvest_ratio=0.995\nmax_investment=9950\nmax_entries=1\n"
    (out / "registration.txt").write_text(registration)
    p = subprocess.run([sys.executable, "-I", str(HERE / "gate_check.py"), str(out / "gate.txt"), str(out / "gate-rc.txt"), str(out / "registration.txt")], capture_output=True)
    (out / "gate-verdict.txt").write_bytes(p.stdout)
    if p.returncode or p.stderr or not p.stdout.startswith(b"VERDICT PASS\n"):
        raise RuntimeError("GATE_NOT_PASS")
    chance = auth_request("GET", "/api/v1/settings/upbit/order-chance?market=KRW-BTC")["data"]
    broker = {"btc_balance": chance["ask_account"]["balance"], "btc_locked": chance["ask_account"]["locked"], "krw_balance": chance["bid_account"]["balance"], "krw_locked": chance["bid_account"]["locked"]}
    record(out / "broker-after.json", broker)
    if D(str(broker["btc_balance"])) != 0 or D(str(broker["btc_locked"])) != 0:
        raise RuntimeError("BROKER_BTC_RESIDUAL")
    if reason in ("BUY_LIMIT_VIOLATION", "COST_LIMIT_VIOLATION", "ORDER_UNKNOWN"):
        raise RuntimeError("SAFETY_VIOLATION_REQUIRES_REVIEW")
    record(out / "final.json", {"status": "COMPLETE", "reason": reason, "gate": "PASS", "sid": sid, "state": state, "broker": broker})

def main(config_path):
    cfg = json.loads(pathlib.Path(config_path).read_text())
    sid = cfg["sid"]
    if type(sid) is not int or sid <= 0 or sid == 203 or cfg.get("operator_ready") is not True or cfg.get("external_freeze") is not True:
        raise RuntimeError("CONFIG_REJECTED")
    if cfg.get("excluded_existing_currency") != "APENFT":
        raise RuntimeError("BASELINE_REJECTED")
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=APP, capture_output=True)
    image = subprocess.run(["docker", "inspect", "--format", "{{.Image}}", "autotrader-backend-1"], capture_output=True)
    if head.returncode or image.returncode or head.stderr or image.stderr or head.stdout.decode().strip() != cfg["expected_sha"] or image.stdout.decode().strip() != cfg["expected_image"]:
        raise RuntimeError("RUNTIME_PIN_MISMATCH")
    out = pathlib.Path(cfg["output"])
    out.mkdir()
    try:
        initial = snapshot(sid)
        if initial.get("contract_ok") is not True or initial["status"] != "CREATED" or initial["buys"]:
            raise RuntimeError("INITIAL_TARGET_NOT_CREATED_EMPTY")
        deadline = float(cfg["deadline_epoch"])
        if not 60 <= deadline - time.time() <= 3600:
            raise RuntimeError("DEADLINE_REJECTED")
        record(out / "armed.json", {"sid": sid, "pid": os.getpid(), "deadline_epoch": deadline, "initial": initial})
        mono_start, wall_start = time.monotonic(), time.time()
        while True:
            current = snapshot(sid)
            record(out / "state.json", {"at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "snapshot": current})
            now = time.time()
            if abs((now - wall_start) - (time.monotonic() - mono_start)) > 2:
                reason = "CLOCK_MOVED"
            else:
                reason = decide(current, deadline, now)
            if reason:
                finish(out, sid, reason)
                return
            time.sleep(min(10, max(0.1, deadline - now)))
    except Exception as exc:
        # A read/record failure after arming must not silently leave a running test.
        # The already registered immutable target may receive one stop, never a retry.
        if (out / "armed.json").exists() and not (out / "sent-stop").exists():
            try:
                with (out / "sent-stop").open("x") as f:
                    f.write("observer failure fallback\n")
                    f.flush()
                    os.fsync(f.fileno())
                response = auth_request("POST", f"/api/v1/trading/sessions/{sid}/stop")
                record(out / "fallback-stop-response.json", response)
            except Exception:
                pass  # Never reinterpret a failed/uncertain stop as success or retransmit it.
        record(out / "error.json", {"status": "UNCONFIRMED", "category": type(exc).__name__})
        raise

if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except Exception as exc:
        print(json.dumps({"status": "UNCONFIRMED", "category": type(exc).__name__}))
        raise SystemExit(1)
