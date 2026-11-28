"""One finite, server-side smoke flow; uses pinned v3 steps and a detached observer."""
import datetime
import decimal
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
D = decimal.Decimal
PRODUCT = "1b719645ef907cb1aa45ffa01438fcfda7566758"
IMAGE = "sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89"
KST = datetime.timezone(datetime.timedelta(hours=9))

def record(path, value):
    tmp = path.with_suffix(".uncommitted")
    with tmp.open("x", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=True)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)

def validate_preflight(data):
    if data.get("status") != "READ_OK":
        return False
    c, db = data["chance"], data["db"]
    return (
        D(c["krw_available"]) >= D("30200") and D(c["krw_locked"]) == 0
        and D(c["btc_available"]) == 0 and D(c["btc_locked"]) == 0
        and D(c["bid_fee"]) >= 0 and D(c["ask_fee"]) >= 0
        and D("9950") * (1 + D(c["bid_fee"])) <= D("10000")
        and D(c["bid_min_total"]) <= D("9950") and D(c["ask_min_total"]) <= D("9950")
        and c["market_state"] == "active" and "price" in c["bid_types"] and "market" in c["ask_types"]
        and D(str(db["live_alloc"])) == D("20200") and D(str(db["dynamic_alloc"])) == 0
        and db["real_live_states"] == [{"id": 203, "status": "CREATED"}] and db["real_dynamic_states"] == []
        and db["sid203_ok"] is True
        and db["engine_h1_ready"] is True and db["engine_h1_count"] >= 480
        and db["official_window_registered"] is True
        and 0 <= db["engine_h1_missing"] - db["engine_h1_covered_missing"] <= 2
        and data["gap_absence_proof_backend_started_at"] == "2026-11-28T03:10:38.948790758Z"
        and all(type(db[k]) is int and db[k] == 0 for k in ("active_real_orders", "open_real_positions", "cleanup_pending"))
        and set(data["non_btc_holding_currencies"]) <= {"APENFT"}
    )

def main():
    manifest = json.loads((HERE / "manifest.json").read_text())
    for name, expected in manifest.items():
        if hashlib.sha256((HERE / name).read_bytes()).hexdigest() != expected:
            raise RuntimeError("SOURCE_HASH_MISMATCH")
    cfg = json.loads((HERE / "run-config.json").read_text())
    if cfg.get("operator_ready") is not True or cfg.get("external_freeze") is not True or cfg.get("product") != PRODUCT or cfg.get("image") != IMAGE:
        raise RuntimeError("CONFIG_REJECTED")
    now = datetime.datetime.now(KST)
    if now.date().isoformat() != "2026-11-28" or not 60 <= cfg["deadline_epoch"] - time.time() <= 1800:
        raise RuntimeError("TIME_REJECTED")
    out = HERE / "driver-output"
    out.mkdir()
    observer = None
    try:
        env = dict(os.environ, APPROVED_DATE=now.date().isoformat(), WIN_START=now.strftime("%H%M"),
            WIN_END=(now + datetime.timedelta(minutes=10)).strftime("%H%M"), EXPECTED_SHA=PRODUCT,
            EXPECTED_IMAGE=IMAGE, POOL_EXPECTED="3|20000|STAGE_1|100000", ALLOC_BEFORE="20200",
            RUN_TAG=cfg["run_tag"], SID="", OPERATOR_READY="yes", EXTERNAL_FREEZE="yes",
            KSTPY=(HERE / "kst.py").read_text(), APIPY=(HERE / "safe_api.py").read_text())
        record(out / "phase.json", {"phase": "PREFLIGHT", "pid": os.getpid(), "config": cfg})
        p = subprocess.run([sys.executable, "-I", str(HERE / "preflight.py")], capture_output=True)
        (out / "preflight.json").write_bytes(p.stdout)
        if p.returncode or p.stderr or not validate_preflight(json.loads(p.stdout)):
            raise RuntimeError("PREFLIGHT_REJECTED")

        def phase(name):
            record(out / "phase.json", {"phase": name, "pid": os.getpid(), "sid": env["SID"], "at": datetime.datetime.now(KST).isoformat()})
            (out / ("sent-phase-" + name)).open("x").close()
            result = subprocess.run(["bash", str(HERE / "smoke.sh")], env=dict(env, STEP=name), capture_output=True)
            (out / (name + ".out")).write_bytes(result.stdout)
            (out / (name + ".err")).write_bytes(result.stderr)
            record(out / (name + "-rc.json"), {"rc": result.returncode, "stderr_bytes": len(result.stderr)})
            if result.returncode or result.stderr:
                raise RuntimeError("PHASE_FAILED_" + name.upper())

        phase("p0")
        phase("create")
        session = json.loads((pathlib.Path.home() / ("smoke-" + cfg["run_tag"]) / "create.json").read_text())["data"]
        sid = session["id"]
        if type(sid) is not int or sid <= 0 or sid == 203:
            raise RuntimeError("CREATED_SID_REJECTED")
        env["SID"] = str(sid)
        record(out / "session.json", session)
        phase("verify")
        observer_cfg = {"sid": sid, "operator_ready": True, "external_freeze": True, "excluded_existing_currency": "APENFT",
            "expected_sha": PRODUCT, "expected_image": IMAGE, "deadline_epoch": cfg["deadline_epoch"], "output": str(HERE / "observer-output")}
        record(HERE / "observer-config.json", observer_cfg)
        with (HERE / "observer.log").open("xb") as log:
            observer = subprocess.Popen([sys.executable, "-u", str(HERE / "observer.py"), str(HERE / "observer-config.json")], stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
        armed = HERE / "observer-output/armed.json"
        end = time.monotonic() + 20
        while not armed.exists():
            if observer.poll() is not None or time.monotonic() >= end:
                raise RuntimeError("OBSERVER_NOT_ARMED_NO_START")
            time.sleep(0.25)
        if json.loads(armed.read_text())["sid"] != sid or observer.poll() is not None:
            raise RuntimeError("OBSERVER_TARGET_REJECTED")
        record(out / "observer-armed.json", {"sid": sid, "pid": observer.pid})
        try:
            phase("start")
        except Exception:
            (HERE / "cancel-start.flag").open("x").close()
            raise
        record(out / "phase.json", {"phase": "OBSERVING", "sid": sid, "pid": os.getpid(), "observer_pid": observer.pid, "deadline_epoch": cfg["deadline_epoch"]})
        observer_rc = observer.wait(timeout=max(1, cfg["deadline_epoch"] - time.time()) + 360)
        if observer_rc or not (HERE / "observer-output/final.json").is_file():
            raise RuntimeError("OBSERVER_UNCONFIRMED")
        final = json.loads((HERE / "observer-output/final.json").read_text())
        record(out / "final.json", {"status": "COMPLETE", "sid": sid, "observer": final})
    except Exception as exc:
        record(out / "error.json", {"status": "UNCONFIRMED", "category": type(exc).__name__, "reason": str(exc) if str(exc).startswith(("PREFLIGHT_", "PHASE_", "OBSERVER_", "CREATED_")) else "DETAIL_WITHHELD"})
        raise

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"status": "UNCONFIRMED", "category": type(exc).__name__}))
        raise SystemExit(1)
