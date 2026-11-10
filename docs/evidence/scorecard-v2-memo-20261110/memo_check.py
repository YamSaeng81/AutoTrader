"""G 메모 숫자 재현 — 보존 파일만 읽는다. 실행: python -I -X utf8 memo_check.py <docs/evidence 경로>"""
import csv, io, json, statistics, sys
from pathlib import Path

E = Path(sys.argv[1])
v2 = json.loads((E / "scorecard-v2-20261110/out/scorecard_v2.json").read_text(encoding="utf-8"))
print("gate_hold", json.dumps(v2["gate_hold"], ensure_ascii=False, sort_keys=True))
print("gate_vs_executed", json.dumps(v2["gate_vs_executed"], sort_keys=True))
print("signal_link PAPER/BUY", json.dumps(v2["signal_link"]["PAPER/BUY"], ensure_ascii=False, sort_keys=True))
cool = {k: n for k, n in v2["linked_buy_outcome_top"]["DYN_PAPER"].items() if "BLACK_SWAN 쿨다운" in k}
print("DYN_PAPER 쿨다운 미실행 행", sorted(cool.values(), reverse=True))


def section(text, name):
    i = text.find("@@" + name)
    s = text[i:].split("\n", 1)[1]
    j = s.find("\n@@")
    return list(csv.DictReader(io.StringIO(s if j < 0 else s[:j])))


raw = (E / "paper-scorecard-20261110/raw/raw.txt").read_text(encoding="utf-8")
print("snapshot meta", section(raw, "meta")[0])
pp = section(raw, "paper_positions")
print("paper_positions rows", len(pp), "not CLOSED", sum(1 for r in pp if r["status"] != "CLOSED"))
raw4 = (E / "scorecard-v2-20261110/raw/raw4.txt").read_text(encoding="utf-8")
bh = section(raw4, "bh_closed")
ends = sorted({r["ended_at"] for r in bh})
tx = ends[-1]
running = {(r["engine"], r["id"]) for r in bh if r["ended_at"] == tx}
print("latest ended_at (UTC, transaction_timestamp)", tx, "rows", len(running))
hc = list(csv.DictReader(open(E / "scorecard-v2-20261110/out/hold_compare_v2.csv", encoding="utf-8")))
rr = [x for x in hc if (x["engine"], x["sid"]) in running]
print("hold rows ending at tx", len(rr), "usable", [(x["engine"], x["sid"], x["session_ret_pct"], x["hold_ret_net_pct"]) for x in rr if x["usable"] == "True"])
paper_usable = [x for x in hc if x["engine"] == "PAPER" and x["usable"] == "True"]
print("PAPER usable", len(paper_usable), "beat_hold", sum(float(x["session_ret_pct"]) > float(x["hold_ret_net_pct"]) for x in paper_usable),
      "in_market median", statistics.median(float(x["in_market_pct"]) for x in paper_usable if x["in_market_pct"]))
