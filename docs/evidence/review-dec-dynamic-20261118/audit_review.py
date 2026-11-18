"""12월 DYNAMIC 경로 초안: 고정 Git·예약 인자·순수 금액/대조식만 확인."""
import hashlib
import json
import re
import subprocess
from decimal import Decimal as D, ROUND_DOWN
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
REV = "0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d"
checks, files, probes = [], [], []


def check(name, ok):
    checks.append({"name": name, "passed": bool(ok)})


def source(path, rev=REV):
    data = subprocess.check_output(["git", "show", rev + ":" + path], cwd=ROOT)
    files.append({"revision": rev, "path": path, "sha256": hashlib.sha256(data).hexdigest()})
    return data.decode()


base = "web-api/src/main/java/com/cryptoautotrader/api/"
s = source(base + "service/DynamicTradingService.java")
dto = source(base + "dto/DynamicSessionRequest.java")
entity = source(base + "entity/DynamicSessionEntity.java")
reservation = source(base + "service/CapitalReservationService.java")
check("exact PAPER selects paper branch", 'boolean isPaper = "PAPER".equals(req.getTradingMode());' in s)
check("non-PAPER selects default", '.tradingMode(isPaper ? "PAPER" : DynamicSessionEntity.DEFAULT_TRADING_MODE)' in s)
check("default is REAL", 'DEFAULT_TRADING_MODE = "REAL"' in entity)
mode_field = dto.split("private String tradingMode;", 1)[0].rsplit("/**", 1)[1]
check("mode field has no validation annotation", "@NotNull" not in mode_field and "@Pattern" not in mode_field and "@NotBlank" not in mode_field)
check("available money times effective ratio", 'BalanceAmounts.buyRequest(session.getAvailableKrw().multiply(effectiveRatio))' in s)
check("EMA multiplier can reduce full ratio", 'sizeMultiplier != null ? sizeMultiplier : BigDecimal.ONE' in s)
check("registered cap rejects oversize", re.search(r'amount.compareTo\(limits.getPerPositionCapKrw\(\)\) > 0\)\s*\{\s*return Decision.reject\(PER_POSITION_CAP', reservation))
check("allocation includes CREATED", reservation.count('List.of("RUNNING", "PAUSED", "CREATED")') >= 2)
check("coin exposure lookup scoped by session kind", '.countBySessionKindAndCoinPairAndStatusAndSessionIdNot(' in s and 'sessionKind(session), coinPair, "OPEN", sid' in s)
for balance, mult, want, rejected in (("25000", "1", "20000.00", False), ("25001", "1", "20000.80", True),
                                      ("25001", "0.5", "10000.40", False), ("25000.01", "1", "20000.00", False)):
    amount = (D(balance) * D("0.8") * D(mult)).quantize(D(".01"), rounding=ROUND_DOWN)
    check("pure sizing " + balance + " multiplier " + mult, amount == D(want) and (amount > D("20000")) == rejected)
    probes.append({"available": balance, "ratio": "0.8", "multiplier": mult, "request": str(amount), "over_20000": amount > D("20000")})

ops = source("scripts/ops/daily_check.sh", "c669ccdc")
sql = source("scripts/ops/daily_checks.sql", "c669ccdc")
check("installed checker hash is known shell hash", hashlib.sha256(ops.encode()).hexdigest() == "ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc")
check("checker expects zero outside SID", 'chk_zero real_open_positions' in ops and '밖 실자금 보유(OPEN·CLOSING)' in ops)
check("SQL outside-SID query includes DYNAMIC", "d.trading_mode = 'REAL'" in sql and "p.session_kind = 'DYNAMIC'" in sql)
runbook_path = "docs/LIMITED_LIVE_TEST_RUNBOOK.md"
runbook = source(runbook_path, "e0119f0a")
check("current runbook equals committed source", (ROOT / runbook_path).read_bytes() == subprocess.check_output(["git", "show", "e0119f0a:" + runbook_path], cwd=ROOT))
check("F1 requires only SID203 eligible", '실행 가능 실자금 세션이 203:CREATED 하나가 아님' in runbook)
check("F3 whole-account KRW compares to SID only", 'abs((k3 - k1) - (avail - 20200)) <= 1' in runbook)
sid_delta, unrelated_dynamic_delta = D(0), D(-10000)
check("other-coin DYNAMIC movement alone breaks old F3 KRW equation", abs(unrelated_dynamic_delta - sid_delta) > 1)
probes.append({"case": "parallel_F3_equation_only", "SID203_delta": "0", "other_coin_dynamic_account_KRW_delta": "-10000", "old_F3_KRW_match": False,
               "scope": "pure equation illustration, no actual order/fee model or product execution"})

folder = ROOT / "docs/evidence/n03-1118-1000"
read = (folder / "read.sh").read_bytes()
wrapper = (folder / "sched/n03run.sh").read_text(encoding="utf-8")
schedule = (folder / "sched/scheduled.txt").read_text(encoding="utf-8")
check("reader fixed digest", hashlib.sha256(read).hexdigest().startswith("41e4f337"))
check("wrapper report and ledger arguments", 'read.sh 20261118-1000 n03-1118-1000 20261118' in wrapper)
check("saved schedule 10:13 KST", '01:13:00Z(10:13:00 KST)' in schedule)
for rel in ("read.sh", "sched/n03run.sh", "sched/scheduled.txt"):
    data = (folder / rel).read_bytes()
    files.append({"path": "docs/evidence/n03-1118-1000/" + rel, "sha256": hashlib.sha256(data).hexdigest(), "scope": "prepared, execution not observed"})

draft = ROOT / "docs/evidence/dec-dynamic-path-20261118/README.md"
data = draft.read_bytes()
target = HERE / "submitted-draft.txt"
if target.exists():
    assert target.read_bytes() == data, "draft changed: keep the earlier snapshot and choose a new observation name"
else:
    target.write_bytes(data)
files.append({"path": str(draft.relative_to(ROOT)), "sha256": hashlib.sha256(data).hexdigest(), "scope": "uncommitted draft snapshot, no final acceptance"})
result = {"production_source": REV, "scope": "static Git/working-file reads and Decimal examples only; no network, DB, Java tests, build or scheduled task execution",
          "passed": sum(x["passed"] for x in checks), "failed": sum(not x["passed"] for x in checks), "checks": checks, "sources": files, "probes": probes}
(HERE / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(json.dumps({k: result[k] for k in ("passed", "failed", "scope")}, ensure_ascii=False))
raise SystemExit(bool(result["failed"]))
