import copy
import importlib.util
import json
import pathlib

root = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("driver", root / "driver.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
base = json.loads((root.parent / "postdeploy-next-20261128/preflight-1.json").read_text())
base["non_btc_holding_currencies"] = ["APENFT"]
base["db"].update(engine_h1_ready=True, engine_h1_count=500, engine_h1_missing=0)
cases = [("known_good", base, True)]
for name, section, key, value in [
    ("insufficient_krw", "chance", "krw_available", "30199.999"),
    ("krw_locked", "chance", "krw_locked", "0.0001"),
    ("btc_existing", "chance", "btc_available", "0.00000001"),
    ("fee_over_budget", "chance", "bid_fee", "0.01"),
    ("minimum_too_large", "chance", "bid_min_total", "10000"),
    ("active_order", "db", "active_real_orders", 1),
    ("wrong_allocation", "db", "live_alloc", "30000"),
    ("other_holding", None, "non_btc_holding_currencies", ["APENFT", "ETH"]),
    ("engine_input_old", "db", "engine_h1_ready", False),
    ("engine_input_short", "db", "engine_h1_count", 479),
    ("engine_input_gap", "db", "engine_h1_missing", 3),
]:
    data = copy.deepcopy(base)
    (data if section is None else data[section])[key] = value
    cases.append((name, data, False))
results = [{"case": name, "pass": module.validate_preflight(data) == expected} for name, data, expected in cases]
out = root / "driver-results.json"
if out.exists():
    raise SystemExit("Existing result; no overwrite")
out.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8", newline="")
print(f"Driver preflight cases: {sum(r['pass'] for r in results)}/{len(results)}; network/DB/SSH calls 0")
raise SystemExit(0 if all(r["pass"] for r in results) else 1)
