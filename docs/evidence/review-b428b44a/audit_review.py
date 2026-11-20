"""Read fixed Git evidence only; never execute the submitted scripts."""
import hashlib
import json
import posixpath
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SHA = "b428b44a3d6bbafdd3682dfaa4d9f1cf44cb226b"
PREFIX = "docs/evidence/"
DIRS = ["n03-1119-2200", "n03-1120-1000", "review-a9c7e5a9"]
checks = []


def git(*args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", *args], cwd=ROOT)


def blob(path, sha=SHA):
    return git("show", f"{sha}:{path}")


def text(folder, name):
    return blob(PREFIX + folder + "/" + name).decode("utf-8")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def check(name, ok, detail=None):
    checks.append({"name": name, "pass": bool(ok), "detail": detail})


check("saved_refs", git("rev-parse", "HEAD").decode().strip() == SHA == git("rev-parse", "refs/remotes/origin/main").decode().strip())
changed = git("diff-tree", "--no-commit-id", "--name-only", "-r", SHA).decode().splitlines()
check("documentation_only", len(changed) == 67 and all(p.startswith("docs/") for p in changed), len(changed))
counts = {}
for folder in DIRS:
    base = PREFIX + folder + "/"
    names = git("ls-tree", "-r", "--name-only", SHA, "--", base).decode().splitlines()
    check(folder + "_blob_copies", all((ROOT / p).read_bytes() == blob(p) for p in names), len(names))
    entries = []
    for line in text(folder, "SHA256SUMS").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        if not match:
            raise ValueError("Invalid manifest syntax")
        h, rel = match.groups()
        if Path(rel).is_absolute() or ".." in Path(rel).parts:
            raise ValueError("Invalid manifest path")
        entries.append((h, posixpath.normpath(base + rel)))
    check(folder + "_manifest", all(digest(blob(p)) == h for h, p in entries), len(entries))
    check(folder + "_coverage", len({p for _, p in entries}) == len(entries) and {p for _, p in entries} == set(names) - {base + "SHA256SUMS"})
    counts[folder] = {"files": len(names), "manifest": len(entries)}

old = blob(PREFIX + "n03-1119-1000/read.sh", "f55f5af0")
for folder, tag, stamp in [(DIRS[0], "20261119-2200", "10:31:28"), (DIRS[1], "20261120-1000", "10:31:32")]:
    check(folder + "_fixed_reader", blob(PREFIX + folder + "/read.sh") == old and digest(old) == "41e4f33747f47ed4089ffe8ca192964529cfea24b8d150a7b1a8300c572f73ad")
    remote = dict((name, h) for h, name in re.findall(r"(?m)^([0-9a-f]{64})  (.+)$", text(folder, "server-files.txt")))
    local = dict((name, h) for h, name in re.findall(r"(?m)^([0-9a-f]{64})  (.+)$", text(folder, "local-sha256.txt")))
    check(folder + "_server_hashes", len(remote) == 5 and len(local) == 4 and all(remote.get(n) == h == digest(blob(PREFIX + folder + "/" + n)) for n, h in local.items()))
    check(folder + "_read_record", "read rc=0" in text(folder, "pc-time.txt") and f"server 2026-11-20 {stamp} KST" in text(folder, "run-time.txt") and not blob(PREFIX + folder + "/read-stderr.txt"))
    inst = text(folder, "install_state.txt")
    errors = blob(PREFIX + folder + "/error_lines.txt")
    check(folder + "_error_extract_hash", "error_lines sha256 " + digest(errors) in inst)
    fe = text(folder, "frontend-image.txt")
    check(folder + "_installation", "HEAD 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d" in inst and "backend Image=sha256:2c5d8427" in inst and "RestartCount=0 Status=running" in inst and "Image=sha256:9d7f123a" in fe and "RestartCount=0 Status=running" in fe)
    check(folder + "_recorded_zero_counters", "Hikari 누수 경고 0 · unleaked 0 · 획득 실패 0" in text(folder, "observations.txt") and all(line == "0" or line.endswith(":0") for line in text(folder, "token-check.txt").splitlines()))
    raw = text(folder, tag + ".txt.raw")
    kv = dict(line.split("|", 1) for line in raw.splitlines() if re.match(r"^[a-z_][a-z0-9_]*\|", line))
    expected = {"flyway_version": "96", "sid_status": "CREATED", "sid_available_krw": "20200.00", "s66_running": "63", "s66_total": "66", "btc_h1_ok": "true", "btc_h1_daily": "20"}
    zero = ["real_open_positions", "real_unresolved_orders", "cleanup_pending", "quarantine_targets", "real_buys_window", "sid_open_positions", "sid_active_orders", "sid_stale_orders", "sid_unknown_orders", "sid_settle_unknown", "sid_settle_mismatch", "settlement_unresolved"]
    check(folder + "_reported_database_values", all(kv.get(k) == v for k, v in expected.items()) and all(kv.get(k) == "0" for k in zero))

a, b = DIRS[:2]
report_a = text(a, "20261119-2200.txt")
report_b = text(b, "20261120-1000.txt")
check("report_error_counts", "전체 0" in report_a and not text(a, "error_lines.txt") and "전체 23" in report_b and len(text(b, "error_lines.txt").splitlines()) == 23 and "미분류 ERROR 3건" in report_b)
check("unclassified_news_only", all(s in report_b for s in ["CryptoPanicSource", "UpbitNoticeSource", "RssNewsSource"]) and "ClaudeProvider" not in report_b)
check("additional_errors_preserved", text(b, "error_lines.txt").count("캔들 수집 실패: request timed out") == 3 and "TelegramNotificationService" in text(b, "error_lines.txt") and "ClaudeProvider" in text(b, "error_lines.txt"))
check("report_specific_send_receipt", "2026-11-19 22:00:03 KST 보고 전송 성공" in report_a and text(a, "last_send") == text(b, "last_send") and text(a, "last_send").startswith("2026-11-20 10:00:04"))
script = blob("scripts/ops/daily_check.sh", "c669ccdc").decode()
check("send_receipt_source", digest(script.encode()) == "ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc" and '[ $SENT -eq 1 ]' in script and 'tee -a "$REPORT" > "$OUT_DIR/last_send"' in script and '[ "$code" = "200" ]' in script)
check("last_server_stamp", "조회 2026-11-20 10:31:37 KST" in text(b, "frontend-image.txt"))
ledger = blob(PREFIX + b + "/ledger-20261120.txt")
check("ledger_remote_hash", digest(ledger) + "  20261120.txt" in text(b, "ledger-files.txt") and "과거 장부 파일 그대로(maint-n17 기준)" in text(b, "ledger-files.txt"))
check("ledger_values", bool(re.search(r"CREATED\s*\|\s*20200\.00\s*\|\s*20200\s*\|\s*0\.00\s*\|\s*20200\s*\|\s*0\.00\s*\|\s*0\s*\|\s*0\s*\|\s*0", ledger.decode())))
result = {"fixed_commit": SHA, "method": "Git blobs and preserved local files only; no submitted script executed", "pass": sum(x["pass"] for x in checks), "fail": sum(not x["pass"] for x in checks), "counts": counts, "checks": checks}
(OUT / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: result[k] for k in ["pass", "fail", "counts"]}, ensure_ascii=False))
if result["fail"]:
    print(json.dumps([x for x in checks if not x["pass"]], ensure_ascii=False))
raise SystemExit(bool(result["fail"]))
