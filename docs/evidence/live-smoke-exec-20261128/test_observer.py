"""Pure decision tests; no API, SSH, subprocess, DB, or artificial production signals."""
import importlib.util
import json
import pathlib

root = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("observer", root / "observer.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
base = {"contract_ok": True, "status": "RUNNING", "buys": []}
order = {"state": "FILLED", "funds": "9950", "fee": "4.975", "pending": False}
cases = [
    ("no_signal_wait", base, 100, 90, None),
    ("no_signal_deadline", base, 100, 100, "OBSERVATION_DEADLINE"),
    ("filled_stop", dict(base, buys=[order]), 100, 90, "BUY_SETTLED"),
    ("pending_wait", dict(base, buys=[dict(order, pending=True)]), 100, 90, None),
    ("fee_missing_wait", dict(base, buys=[dict(order, fee=None)]), 100, 90, None),
    ("failed_no_retry", dict(base, buys=[dict(order, state="FAILED")]), 100, 90, "BUY_TERMINAL_NO_RETRY"),
    ("unknown_stop", dict(base, buys=[dict(order, state="UNKNOWN")]), 100, 90, "ORDER_UNKNOWN"),
    ("over_budget", dict(base, buys=[dict(order, fee="51")]), 100, 90, "COST_LIMIT_VIOLATION"),
    ("two_buys", dict(base, buys=[order, order]), 100, 90, "BUY_LIMIT_VIOLATION"),
    ("contract_mismatch", dict(base, contract_ok=False), 100, 90, "CONTRACT_MISMATCH"),
    ("already_stopped", dict(base, status="STOPPED"), 100, 90, "ALREADY_STOPPED"),
]
results = [{"case": n, "actual": module.decide(s, d, t), "expected": exp, "pass": module.decide(s, d, t) == exp} for n,s,d,t,exp in cases]
out = root / "observer-results.json"
if out.exists():
    raise SystemExit("Existing result; not overwritten")
out.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8", newline="")
print(f"Observer decision cases: {sum(r['pass'] for r in results)}/{len(results)}; network/DB/SSH calls 0")
raise SystemExit(0 if all(r["pass"] for r in results) else 1)
