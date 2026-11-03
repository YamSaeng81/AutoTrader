"""Run the installed shell against malformed DB output, without external access."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
WORK = ROOT / "core-engine/build/review-n10-replay"
SOURCE = "8435935e975ceb6c2e16a09ef5b82530574a7da2"
BASH = r"C:\Program Files\Git\bin\bash.exe"


def git_bytes(path):
    return subprocess.check_output(["git", "show", f"{SOURCE}:{path}"], cwd=ROOT)


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data.encode("utf-8") if isinstance(data, str) else data)


def unix(path):
    return subprocess.check_output(
        [BASH, "--noprofile", "--norc", "-c", 'cygpath -u "$1"', "_", str(path)],
        cwd=WORK, text=True, encoding="utf-8"
    ).strip()


shell = git_bytes("scripts/ops/daily_check.sh")
write(WORK / "daily_check.sh", shell)
write(WORK / "INSTALLED_SHA", SOURCE + "\n")
write(WORK / "docker-compose.prod.yml", "# Local fixture only\n")
write(WORK / "empty.sql", "-- Never executed by a database\n")
write(WORK / "logs.txt", "")
write(WORK / "psql.sh", '#!/usr/bin/env bash\ncat > /dev/null\ncat "$REVIEW_SQL_OUTPUT"\n')
write(WORK / "bin/git", '#!/usr/bin/env bash\ncase "$*" in *rev-parse*) echo fixture;; *status*) :;; *) exit 99;; esac\n')
for cmd in ("docker", "curl", "crontab", "ssh", "scp"):
    write(WORK / f"bin/{cmd}", '#!/usr/bin/env bash\nprintf "%s\\n" "$0 $*" >> "$REVIEW_FORBIDDEN"\nexit 99\n')

baseline = (HERE / "cases/normal/input.txt").read_text(encoding="utf-8")


def change(key, value):
    return "\n".join(key + "|" + value if line.startswith(key + "|") else line
                     for line in baseline.splitlines()) + "\n"


cases = [
    ("normal", baseline, "green"),
    ("blank_sid_status", change("sid_status", ""), "white"),
    ("blank_unknown_orders", change("sid_unknown_orders", ""), "white"),
    ("blank_settle_mismatch", change("sid_settle_mismatch", ""), "white"),
    ("blank_unresolved_settlement", change("settlement_unresolved", ""), "white"),
    ("blank_other_real_orders", change("real_unresolved_orders", ""), "white"),
    ("blank_status_with_stale_btc", change("sid_status", "").replace("btc_h1_ok|true", "btc_h1_ok|false"), "white"),
    ("duplicate_unknown_1_then_0", baseline.replace("sid_unknown_orders|0", "sid_unknown_orders|1\nsid_unknown_orders|0"), "white"),
    ("unknown_orders_one", change("sid_unknown_orders", "1"), "red"),
    ("missing_sid_status", "\n".join(line for line in baseline.splitlines() if not line.startswith("sid_status|")) + "\n", "white"),
]

work_u = unix(WORK)
results = []
for name, sql_output, expected in cases:
    folder = WORK / "cases" / name
    write(folder / "input.txt", sql_output)
    folder_u = work_u + "/cases/" + name
    env = os.environ.copy()
    for name_to_clear in ("PSQL_CMD", "APP_STATE", "LOGS_FILE", "ALERTS_CMD", "TG_API", "SID_OK_STATUS", "BTC_NOW", "LOCK_MODE", "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
        env.pop(name_to_clear, None)
    env.update({
        "REPO_DIR": ".", "OUT_DIR": "cases/" + name + "/output", "SQL_FILE": "empty.sql",
        "PSQL_CMD": "bash ./psql.sh", "REVIEW_SQL_OUTPUT": folder_u + "/input.txt",
        "APP_STATE": "running|0|2026-10-01T00:00:00Z|sha256:fixture",
        "LOGS_FILE": "logs.txt", "ALERTS_CMD": "printf '🟢 경보 없음 · 실행 누락 없음\\n'; exit 0",
        "DRY_RUN": "1", "TZ_EXPECT": "", "SID_OK_STATUS": "CREATED",
        "REVIEW_FORBIDDEN": folder_u + "/forbidden.txt",
    })
    run = subprocess.run([BASH, "--noprofile", "--norc", "-c", 'export PATH="$PWD/bin:$PATH"; bash ./daily_check.sh'],
                         cwd=WORK, env=env, capture_output=True, timeout=30)
    output = run.stdout.decode("utf-8", errors="replace")
    error = run.stderr.decode("utf-8", errors="replace")
    write(folder / "stdout.txt", output)
    write(folder / "stderr.txt", error)
    reports = list((folder / "output").glob("*.txt"))
    if len(reports) != 1:
        raise RuntimeError(f"{name}: expected one report, got {reports}")
    report = reports[0].read_text(encoding="utf-8")
    write(folder / "report.txt", report)
    forbidden = (folder / "forbidden.txt").exists()
    if forbidden:
        raise RuntimeError(f"{name}: forbidden external command invoked")
    header = report.splitlines()[0]
    matches = {
        "green": "🟢 점검 항목 정상" in header and run.returncode == 0,
        "white": "⚪" in report and run.returncode != 0 and "🟢 점검 항목 정상" not in header,
        "red": "🔴 SID 203 UNKNOWN 주문 1" in report and run.returncode != 0,
    }
    item = {"case": name, "expected": expected, "exit": run.returncode, "header": header,
            "meets_expected": matches[expected], "external_command_called": forbidden}
    results.append(item)
    print(json.dumps(item, ensure_ascii=False))

write(WORK / "review.json", json.dumps({"source_sha": SOURCE,
      "shell_sha256": hashlib.sha256(shell).hexdigest(), "cases": results,
      "scope": "Local full original shell with SQL output stubbed, DRY_RUN. No database, HTTP, Docker, SSH, crontab, or source mutation."}, ensure_ascii=False, indent=2) + "\n")
