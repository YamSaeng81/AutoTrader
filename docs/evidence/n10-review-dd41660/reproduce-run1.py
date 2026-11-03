"""Run the installed shell against malformed DB output, without external access."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path.cwd()
HERE = ROOT / "docs/evidence/n10-critical-fields-8435935"
WORK = ROOT / "core-engine/build/review-n10-dd41660"
SOURCE = "dd416603227704d19e37cfeccc601243a573917a"
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


cases += [
    ("empty_third_column", change("sid_unknown_orders", "0|"), "white"),
    ("nonempty_third_column", change("sid_unknown_orders", "0|extra"), "white"),
    ("btc_empty_third_column", change("btc_h1_ok", "true|"), "white"),
    ("tg_empty_fourth_column", baseline.replace("END|ok", "tg_fail|CUSTOM|1|\nEND|ok"), "white"),
    ("blank_s66_total65", change("s66_not_running", "").replace("s66_total|66", "s66_total|65"), "white_red_s66"),
    ("duplicate_s66_total65", change("s66_total", "65").replace("s66_not_running|-", "s66_not_running|-\ns66_not_running|-"), "white_red_s66"),
    ("blank_count_other_red", change("sid_unknown_orders", "").replace("real_open_positions|0", "real_open_positions|1"), "white_red_other"),
    ("valid_none_dash", change("sid_status", "none").replace("sid_available_krw|20200.00", "sid_available_krw|-"), "red_none"),
    ("valid_tg_rows", baseline.replace("END|ok", "tg_fail|CUSTOM|2\ntg_fail|TRADE_SUMMARY|1\nEND|ok"), "green"),
    ("valid_critical_tg", baseline.replace("END|ok", "tg_fail|STOP_LOSS|1\nEND|ok"), "red_tg"),
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
        "white_red_s66": "⚪" in report and "🔴 검증 세션 수 65(원래 66)" in report and run.returncode == 1,
        "white_red_other": "⚪" in report and "🔴 SID 203 밖 실자금 보유(OPEN·CLOSING) 1" in report and run.returncode == 1,
        "red_none": "🔴 SID 203 상태 none" in report and "⚪" not in report and run.returncode == 1,
        "red_tg": "🔴 텔레그램 중요 경보 전송 실패 STOP_LOSS 1건" in report and "⚪" not in report and run.returncode == 1,
    }
    item = {"case": name, "expected": expected, "exit": run.returncode, "header": header,
            "meets_expected": matches[expected], "external_command_called": forbidden}
    results.append(item)
    print(json.dumps(item, ensure_ascii=False))

write(WORK / "review.json", json.dumps({"source_sha": SOURCE,
      "shell_sha256": hashlib.sha256(shell).hexdigest(), "cases": results,
      "scope": "Local full original shell with SQL output stubbed, DRY_RUN. No database, HTTP, Docker, SSH, crontab, or source mutation."}, ensure_ascii=False, indent=2) + "\n")
