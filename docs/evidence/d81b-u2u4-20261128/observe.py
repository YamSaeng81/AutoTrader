"""One-shot post-deploy observation using the accepted, hash-checking runner."""
import datetime
import subprocess
import time

KST = datetime.timezone(datetime.timedelta(hours=9))
BASH = r"C:\Program Files\Git\bin\bash.exe"

def observe(block, hour, minute, second):
    target = datetime.datetime(2026, 11, 28, hour, minute, second, tzinfo=KST)
    print(f"WAIT {block} until {target.isoformat()}", flush=True)
    while time.time() < target.timestamp():
        time.sleep(min(30, max(0, target.timestamp() - time.time())))
    command = (
        'export PATH="/c/Users/SangJoon_Lee/.cache/codex-runtimes/'
        'codex-primary-runtime/dependencies/python:$PATH"; '
        'export D4SSH=/usr/bin/ssh; '
        'exec bash /d/tmp/d81brun-20261127-exp1/run.sh '
        f'{block} deploy user-approved-20261128'
    )
    result = subprocess.run([BASH, "-lc", command], check=False)
    print(f"RESULT {block} rc={result.returncode}", flush=True)
    return result.returncode

if __name__ == "__main__":
    rc = observe("U3", 12, 15, 45)
    if rc:
        raise SystemExit(rc)
    raise SystemExit(observe("U4", 12, 40, 45))
