"""One-shot daily-close trial; no cron, no order call, no automatic retry."""
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
KST = datetime.timezone(datetime.timedelta(hours=9))
TARGET = datetime.datetime(2026, 11, 29, 9, 3, tzinfo=KST).timestamp()
LATEST_START = TARGET + 300


def eligibility(now, cancelled=False):
    if cancelled:
        return "CANCELLED"
    if now < TARGET:
        return "WAITING"
    if now >= LATEST_START:
        return "MISSED_WINDOW"
    return "READY"


def record(name, data):
    path = ROOT / name
    tmp = path.with_suffix(".uncommitted")
    with tmp.open("x", encoding="utf-8") as f:
        json.dump(data, f)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def main():
    manifest = json.loads((ROOT / "manifest.json").read_text())
    for name, expected in manifest.items():
        if pathlib.Path(name).name != name or hashlib.sha256((ROOT/name).read_bytes()).hexdigest() != expected:
            raise RuntimeError("SOURCE_HASH_MISMATCH")
    with (ROOT / "scheduler-once").open("x") as f:
        f.write(str(os.getpid()) + "\n")
    wall, mono = time.time(), time.monotonic()
    while True:
        now = time.time()
        if abs((now-wall)-(time.monotonic()-mono)) > 2:
            raise RuntimeError("CLOCK_MOVED_NO_START")
        status = eligibility(now, (ROOT / "CANCEL").exists())
        record("scheduler-state.json", {"status":status,"pid":os.getpid(),"at":datetime.datetime.now(KST).isoformat(),"target_kst":"2026-11-29T09:03:00+09:00"})
        if status != "WAITING":
            break
        time.sleep(min(30, max(0.1, TARGET-now)))
    if status != "READY":
        record("scheduler-final.json", {"status":status,"trial_started":False})
        return
    # Earlier attempts spent zero of the common 10,000-KRW BUY+fee budget.
    for folder, sid in (("live-smoke-20261128-exp1",204),("live-smoke-20261128-exp3",205)):
        old=json.loads((ROOT.parent/folder/"observer-output/final.json").read_text())
        if old.get("status")!="COMPLETE" or old.get("sid")!=sid or old.get("gate")!="PASS" or old["state"]["status"]!="STOPPED" or old["state"]["buys"]:
            raise RuntimeError("PRIOR_ATTEMPT_NOT_ZERO_COST_CLOSED")
    cfg={"operator_ready":True,"external_freeze":True,"excluded_existing_currency":"APENFT","product":"1b719645ef907cb1aa45ffa01438fcfda7566758","image":"sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89","run_tag":"20261129-0903","deadline_epoch":TARGET+1200,"launched_at":datetime.datetime.now(KST).isoformat()}
    record("run-config.json",cfg)
    with (ROOT / "driver.log").open("xb") as log:
        p=subprocess.Popen([sys.executable,"-u",str(ROOT/"driver.py")],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    record("driver-launch.json",{"pid":p.pid,"at":datetime.datetime.now(KST).isoformat()})
    rc=p.wait(timeout=max(1,TARGET+1200-time.time())+420)
    record("scheduler-final.json",{"status":"DRIVER_RETURNED","driver_rc":rc,"trial_started":True,"result_confirmed":rc==0 and (ROOT/"driver-output/final.json").is_file()})


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        record("scheduler-error.json",{"status":"UNCONFIRMED","category":type(exc).__name__,"reason":str(exc) if str(exc).isupper() else "DETAIL_WITHHELD"})
        raise SystemExit(1)
