"""Fixed-blob audit and new synthetic contract probes. No network, DB, shell scripts or product execution."""
import copy
import hashlib
import json
import posixpath
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SHA = "95a932933aab76f32ba69f256980e7efce8dce64"
checks, probes = [], []


def git(*args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", *args], cwd=ROOT)


def blob(path, sha=SHA):
    return git("show", f"{sha}:{path}")


def check(name, ok, detail=None):
    checks.append({"name": name, "pass": bool(ok), "detail": detail})


def subject(path):
    # These reviewed sources only define file-input calculations; __main__ is not run.
    ns = {"__name__": "review_subject", "__file__": path}
    exec(compile(blob(path), path, "exec"), ns)
    return ns


def probe(name, reproduced, observed):
    probes.append({"name": name, "reproduced": bool(reproduced), "observed": observed})


check("saved_refs", git("rev-parse", "HEAD").decode().strip() == SHA == git("rev-parse", "refs/remotes/origin/main").decode().strip())
paths = git("diff-tree", "--no-commit-id", "--name-only", "-r", SHA).decode().splitlines()
check("scope", len(paths) == 22 and all(p.startswith("docs/") for p in paths), len(paths))
evidence = [p for p in paths if p.startswith("docs/evidence/")]
check("committed_evidence_bytes", all(blob(p) == (ROOT / p).read_bytes() for p in evidence), len(evidence))
for folder in ["dec-scorecard-asof-20261120", "dec-parallel-registry-20261120", "review-b428b44a"]:
    base = "docs/evidence/" + folder + "/"
    entries = []
    for line in blob(base + "SHA256SUMS").decode().splitlines():
        m = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        if not m or ".." in Path(m[2]).parts or Path(m[2]).is_absolute():
            raise ValueError("Invalid manifest")
        entries.append((m[1], posixpath.normpath(base + m[2])))
    check(folder + "_manifest", all(hashlib.sha256(blob(p)).hexdigest() == h for h, p in entries), len(entries))
check("original_reports_unchanged", git("diff", "b428b44a", SHA, "--", "docs/evidence/n03-1119-2200", "docs/evidence/n03-1120-1000").decode().count("diff --git") == 2 and all(p.endswith("CORRECTION-20261120.md") for p in paths if "/n03-" in p))
fixed = ["docs/evidence/d81d4r-20261120", "docs/evidence/dec-reconcile-v5-20261118", "docs/evidence/dec-scorecard-v3-20261119", "docs/LIMITED_LIVE_TEST_RUNBOOK.md"]
check("execution_and_existing_tools_unchanged", not git("diff", "b428b44a", SHA, "--", *fixed))
engine = blob("web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java", "d81c3f2f").decode()
pool = blob("web-api/src/main/java/com/cryptoautotrader/api/service/PoolLossGuard.java", "d81c3f2f").decode()
rules = blob("web-api/src/main/java/com/cryptoautotrader/api/entity/RulesetSnapshotEntity.java", "d81c3f2f").decode()
runbook = blob("docs/LIMITED_LIVE_TEST_RUNBOOK.md").decode()
check("filled_at_is_application_observation", engine.count("setFilledAt(Instant.now())") == 5)
check("pool_retention_and_scope", "Duration.ofDays(8)" in pool and "pool_equity_snapshot (stage," in pool)
check("ruleset_snapshot_is_not_session_identity", '@Table(name = "ruleset_snapshot")' in rules and "private String rulesetHash" in rules and "sessionId" not in rules)
check("fixed_F1_single_live_no_dynamic", "EGS='1|0|1|true|" in runbook)
check("preserved_example_summaries", blob("docs/evidence/dec-scorecard-asof-20261120/run1.out.txt").decode().strip().endswith("6/6") and blob("docs/evidence/dec-parallel-registry-20261120/run1.out.txt").decode().strip().endswith("4/4"))

a = subject("docs/evidence/dec-scorecard-asof-20261120/asof_examples.py")
r = copy.deepcopy(a["CASES"][0][2])
old = dict(r, sell_applied_at="2026-11-30T10:00:00+09:00", reported_observed_at="2026-11-30T10:00:05+09:00")
got = a["attribute"](old, old, old)
probe("S1_previous_month_sell_labeled_December", got.get("month") == "2026-12", got)
march = dict(r, applied_paid_fee="5.01", reported_observed_at="2027-03-03T09:00:00+09:00")
got = a["attribute"](r, r, march)
probe("S2_March_observation_assigned_January", got.get("next_month_correction") == {"applied_paid_fee": "0.01"} and "1월" in got.get("why", ""), got)

s = subject("docs/evidence/dec-scorecard-v3-20261119/scorecard/scorecard.py")
period = (s["tstamp"]("2026-12-01T00:00:00+09:00", "from"), s["tstamp"]("2027-01-01T00:00:00+09:00", "to"))
snap = lambda v: {"available_krw": v, "pending_buys": [], "positions": []}
x = {"kind": "LIVE", "id": 203, "mode": "REAL", "created_at": "2026-11-01T00:00:00+09:00", "start": snap("10000"), "end": snap("9900"), "flows": [], "trades": []}
got = s["session_card"](x, period, {})
probe("S3_post_boundary_cash_cannot_be_T_cash", got["month_pnl"] == "-100", {"true_T_cash": "10000", "January_only_debit": "100", "captured_after_debit": "9900", "supplied_as_end_month_pnl": got["month_pnl"], "note": "Arithmetic consequence of proposed mapping, not a scorecard-v3 product defect"})
new = dict(x, created_at="2026-12-05T10:00:00+09:00", started_in_period=True, start=snap("10000"), end=snap("10000"), flows=[{"kind": "ALLOCATE", "amount_krw": "10000", "at": "2026-12-05T10:00:00+09:00"}])
try:
    s["session_card"](new, period, {})
    probe("S4_new_session_initial_capital_as_start_rejected", False, "accepted")
except s["Bad"] as exc:
    probe("S4_new_session_initial_capital_as_start_rejected", True, str(exc))
new["start"] = None
got = s["session_card"](new, period, {})
probe("S5_new_session_zero_start_control", got["month_pnl"] == "0", {k: got[k] for k in ["start_equity", "net_flows", "month_pnl"]})

g = subject("docs/evidence/dec-parallel-registry-20261120/registry_sum.py")
db = copy.deepcopy(g["DB"])
db["sessions"].append({"kind": "DYNAMIC", "id": 94, "status": "RUNNING", "available_krw": "0"})
got = g["check"](g["REG"], db, g["EX"])
probe("P1_unregistered_zero_position_session_ignored", got == ["OK"], got)
db = copy.deepcopy(g["DB"])
db["orders"] = [{"kind": "DYNAMIC", "sid": 94, "state": "INTENT_READY", "reserved_krw": "0"}]
got = g["check"](g["REG"], db, g["EX"])
probe("P2_unregistered_order_without_position_ignored", got == ["OK"], got)
reg = copy.deepcopy(g["REG"])
reg["unallocated_krw_base"] = "20000"  # This base already includes the earlier 30000 allocation.
reg["base_at"] = "2026-12-02T00:00:00+09:00"
reg["capital_events"][0]["at"] = "2026-12-01T00:00:00+09:00"
got = g["check"](reg, g["DB"], g["EX"])
probe("P3_allocation_before_base_deducted_twice", len(got) == 1 and "200" in got[0] and got != ["OK"], {"base_already_after_allocation": "20000", "true_unallocated": "20000", "observed": got})

audit = {"fixed_commit": SHA, "pass": sum(x["pass"] for x in checks), "fail": sum(not x["pass"] for x in checks), "checks": checks}
result = {"method": "New synthetic file-input cases only; original 6/4 suites were not rerun", "reproduced": sum(x["reproduced"] for x in probes), "not_reproduced": sum(not x["reproduced"] for x in probes), "cases": probes}
for name, data in [("checks.json", audit), ("contract-probes.json", result)]:
    target = OUT / name
    if target.exists():
        raise FileExistsError(target)
    target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"audit_pass": audit["pass"], "audit_fail": audit["fail"], "probes_reproduced": result["reproduced"], "probes_not_reproduced": result["not_reproduced"]}))
raise SystemExit(bool(audit["fail"] or result["not_reproduced"]))
