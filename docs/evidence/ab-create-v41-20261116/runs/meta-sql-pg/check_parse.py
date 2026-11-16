"""실제 psql 출력(격리 v152-pg/v41meta)을 v4.1 생성기의 MetaDB·parse_meta·서명 대조로 읽는다. 운영 아님."""
import json, sys
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "create"))
import ab_create as c
db = c.MetaDB(["docker", "exec", "-i", "v152-pg", "psql", "-U", "trader", "-d", "v41meta"])
m, p = db.preflight()
assert p is None, p
plan = c.bodies_from_protocol(c.load_protocol(HERE.parents[1] / "tool" / "protocol.json")[0])
dup = sorted({x["pair_id"] + x["arm"] for x in plan for r in m["rows"] if r["status"] == "RUNNING" and c.row_signature(r) == c.body_signature(x["body"])})
print(json.dumps({"preflight": {"running_total": m["running_total"], "max_id": m["max_id"], "row_ids": [r["id"] for r in m["rows"]], "duplicates": dup}}, ensure_ascii=False))
a, p2 = db.after(586)
assert p2 is None, p2
print(json.dumps({"after_586": {"row_ids": [r["id"] for r in a["rows"]], "started": [r["startedAt"] for r in a["rows"]]}}, ensure_ascii=False))
print(json.dumps({"precision_588": c.precision_contract_ok("2026-11-16T08:25:52.8159264Z", a["rows"][0]["startedAt"]),
                  "precision_588_1us_off": c.precision_contract_ok("2026-11-16T08:25:52.8159274Z", a["rows"][0]["startedAt"])}))
bad = c.MetaDB(["docker", "exec", "-i", "v152-pg", "psql", "-U", "trader", "-d", "v41meta_없음"])
print(json.dumps({"wrong_db": bad.preflight()[1]}, ensure_ascii=False))
missing = c.MetaDB(["존재하지_않는_실행파일"])
print(json.dumps({"no_psql": missing.preflight()[1]}, ensure_ascii=False))
assert dup == ["P1A", "P1B"] and m["running_total"] == 4 and [r["id"] for r in m["rows"]] == [588]
assert [r["id"] for r in a["rows"]] == [588, 600, 601]
print("CHECK OK")
