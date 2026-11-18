"""Read fixed Git blobs and saved results; no network, DB, Java tests, or builds."""
from pathlib import Path, PurePosixPath
from decimal import Decimal as D
import hashlib
import json
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SUBMIT = "76387b25f45dfdf85827c72a1c8a9ea75be3b0f9"
BASE = "0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d"
MODE = "18f3a7956ae28b59a6f4235889fe60cb00c399fa"
P1 = "2302104c5bc78f82c7f890960a67d39a21540dac"
PREFIX = "docs/evidence/dec-parallel-20261118/"
N03 = "docs/evidence/n03-1118-1000/"
checks = []


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def blob(path, rev=SUBMIT):
    return git("show", f"{rev}:{path}")


def text(path, rev=SUBMIT):
    return blob(path, rev).decode("utf-8-sig")


def check(name, condition, detail=None):
    checks.append({"name": name, "ok": bool(condition), "detail": detail})


def manifest(folder):
    entries = []
    for line in text(folder + "/SHA256SUMS").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        if not match:
            check("manifest format", False, line)
            continue
        expected, name = match.groups()
        name = str(PurePosixPath(name.replace("\\", "/")))
        if name.startswith("/") or ".." in PurePosixPath(name).parts:
            raise ValueError(name)
        path = folder + "/" + name
        data = blob(path)
        check(path, hashlib.sha256(data).hexdigest() == expected
              and (ROOT / path).read_bytes() == data)
        entries.append(path)
    tracked = set(git("ls-tree", "-r", "--name-only", SUBMIT, folder).decode().splitlines())
    check(folder + " coverage", tracked == set(entries) | {folder + "/SHA256SUMS"})
    return len(entries)


counts = {folder: manifest("docs/evidence/" + folder) for folder in
          ("dec-parallel-20261118", "n03-1118-1000", "review-dec-dynamic-20261118")}
check("local refs", git("rev-parse", "HEAD").decode().strip() == SUBMIT
      and git("rev-parse", "refs/remotes/origin/main").decode().strip() == SUBMIT)
check("mode parent", git("rev-parse", MODE + "^").decode().strip() == BASE)
check("P1 parent", git("rev-parse", P1 + "^").decode().strip() == BASE)
mode_diff = git("diff", BASE, MODE)
check("submitted mode diff", mode_diff == blob(PREFIX + "mode/candidate.diff").replace(b"\r\n", b"\n"))
p1_diff = git("diff", BASE, P1)
check("submitted P1 diff", p1_diff == blob(PREFIX + "p1/test.diff").replace(b"\r\n", b"\n"))
mode_paths = git("diff", "--name-only", BASE, MODE).decode().splitlines()
product = [p for p in mode_paths if "/src/main/" in p]
check("mode scope", len(mode_paths) == 8 and len(product) == 2
      and all(p.endswith(("DynamicSessionRequest.java", "DynamicTradingService.java")) for p in product))
check("P1 test only", git("diff", "--name-only", BASE, P1).decode().strip()
      == "web-api/src/test/java/com/cryptoautotrader/api/service/DynamicPerPositionCapBoundaryTest.java")
service = text("web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java", MODE)
create = service[service.index("public DynamicSessionEntity createSession("):]
guard = 'if (!"REAL".equals(mode) && !"PAPER".equals(mode))'
check("mode rejected before strategy and allocation", create.index(guard) < create.index("StrategyRegistry.get")
      < create.index("checkSessionAllocation"))
check("existing mode default retained", '.tradingMode(isPaper ? "PAPER" : DynamicSessionEntity.DEFAULT_TRADING_MODE)' in create)
check("test callers explicit REAL", mode_diff.count(b'+        req.setTradingMode("REAL")') == 8
      and mode_diff.count(b'+        explicit.setTradingMode("REAL")') == 1
      and mode_diff.count(b'+        dyn.setTradingMode("REAL")') == 1
      and mode_diff.count(b'+        dynReq.setTradingMode("REAL")') == 1)
red = text(PREFIX + "mode/red-summary.txt")
green = text(PREFIX + "mode/green-summary.txt")
check("red saved summary", "tests=15 fail=13 err=0 skip=0" in red)
rows = re.findall(r"tests=(\d+) fail=(\d+) err=(\d+) skip=(\d+) time=", green)
check("green saved summary", len(rows) == 7 and sum(int(r[0]) for r in rows) == 48
      and all(tuple(map(int, r[1:])) == (0, 0, 0) for r in rows))
check("P1 saved summary", "tests=8 fail=0 err=0 skip=0" in text(PREFIX + "p1/summary.txt"))
sql = blob(PREFIX + "daily_checks.c669ccdc.sql")
check("installed SQL exact", sql == blob("scripts/ops/daily_checks.sql", "c669ccdc"))
for name, expected in [("n03-base.txt", (0, 0, 0)), ("n03-parallel-eth.txt", (1, 0, 1)),
                       ("n03-parallel-eth-xrp.txt", (1, 1, 2))]:
    values = dict(line.split("|", 1) for line in text(PREFIX + name).splitlines())
    check("saved local SQL " + name, tuple(int(values[k]) for k in
          ("real_open_positions", "real_unresolved_orders", "real_buys_window")) == expected)
check("sizing exact boundary", (D("25000.01") * D(".8")).quantize(D(".01"), rounding="ROUND_DOWN") == D("20000"))
check("ETH cash conflict", D("-16008") != 0 and D("-50") != 0)
# Independent counterexamples to the *proposed* aggregate contract, not production failures.
counterexamples = {
    "existing_BTC_at_dynamic_baseline": {
        "baseline_position": "0.0002", "end_position": "0.0002", "interval_net_fills": "0",
        "account_coin_delta": "0", "delta_positions_matches": True, "current_positions_equals_delta": False},
    "session_count_tolerance": {
        "sessions": 2, "known_rounding": "0", "unexplained_cash_gap": "1.50",
        "draft_2_won_tolerance_accepts": abs(D("1.50")) <= D(2), "exact_ledger_matches": False},
}
check("baseline counterexample", D(".0002") - D(".0002") == D(0) and D(".0002") != D(0))
check("tolerance counterexample", D(0) < D("1.50") <= D(2))
for name in ("20261118-1000.txt", "20261118-1000.txt.msg", "20261118-1000.txt.raw", "last_send"):
    digest = hashlib.sha256(blob(N03 + name)).hexdigest()
    check("server/local hash " + name, digest + "  " + name in text(N03 + "server-files.txt")
          and digest + "  " + name in text(N03 + "local-sha256.txt"))
ledger = hashlib.sha256(blob(N03 + "ledger-20261118.txt")).hexdigest()
check("ledger hash", ledger + "  20261118.txt" in text(N03 + "ledger-files.txt"))
report = text(N03 + "20261118-1000.txt")
check("N03 transmitted and SID state", "2026-11-18 10:00:04 KST" in report
      and "SID 203: CREATED" in report and "잔액 20200.00" in report)
check("N03 classification retained", "미분류 ERROR 1건" in report and "tokenpost_rss: request timed out" in report)
check("read actual time and rc", "server 2026-11-18 10:13:21 KST" in text(N03 + "run-time.txt")
      and text(N03 + "read-stderr.txt").strip() == "read rc=0")
check("P0 saved observation", "누수 경고 0" in text(N03 + "observations.txt")
      and "획득 실패 0" in text(N03 + "observations.txt"))
changed = git("diff", "--name-only", "10e6ce21", SUBMIT).decode().splitlines()
check("correction commit scope", set(changed) == {PREFIX + "SHA256SUMS", PREFIX + "mode/red-summary.txt"})
check("correction text identical excluding line endings", blob(PREFIX + "mode/red-summary.txt", "10e6ce21").replace(b"\r\n", b"\n")
      == blob(PREFIX + "mode/red-summary.txt"))
result = {"scope": "fixed Git blobs, submitted summaries and pure Decimal examples only",
          "submission": SUBMIT, "mode": MODE, "P1": P1, "manifests": counts,
          "summary": {"passed": sum(c["ok"] for c in checks), "failed": sum(not c["ok"] for c in checks)},
          "counterexamples": counterexamples, "checks": checks}
(HERE / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(json.dumps(result["summary"]))
raise SystemExit(1 if result["summary"]["failed"] else 0)
