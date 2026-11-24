"""r1-diag-1 분류 ↔ 원래 R1 합계 연결 대조 (총괄 v1.233 §3-3). 파일 입력만 — PG·제품·SQL 실행 없음.

fixture.json 의 주문/포지션/세션 행을 받아
  (1) 제품 원식(countRealSessionCostUndeterminedSince) 을 그대로 옮긴 계산 = ORIG
  (2) r1-diag-1.sql 의 sa/pc 분해와 같은 정의의 태그 행(SPLIT·AM·PM·AX·AF·PX·PF·PE·UQ·AGE)
을 만들고 shape_check 규칙으로 서로를 대조한다. SQL 의 NULL 은 None. 3값 논리는 쓰는 곳에서 명시적으로 처리한다.
"""
import json, sys
from datetime import datetime, timedelta, timezone

def ts(s):
    return None if s is None else datetime.fromisoformat(s.replace("Z", "+00:00"))

def gt0(v):            # SQL: v > 0  (NULL → 거짓)
    return v is not None and v > 0

def load(path):
    d = json.load(open(path, encoding="utf-8"))
    now = ts(d["now"]); since = now - timedelta(days=30)
    for o in d["orders"]:
        o["sell_applied_at"] = ts(o.get("sell_applied_at"))
    for p in d["positions"]:
        p["closed_at"] = ts(p.get("closed_at"))
    return d, now, since

def orders_of(d, pid):
    return [o for o in d["orders"] if pid is not None and o.get("position_id") == pid]

def buy_cost_ok(d, pid):
    """BUY_COST_BY_POSITION 에 그 포지션 행이 있는가: 체결(>0) BUY 가 하나 이상이고 그 대금·수수료가 모두 NULL 아님."""
    if pid is None:
        return False
    bs = [o for o in orders_of(d, pid) if o["side"] == "BUY" and gt0(o.get("filled_quantity"))]
    return len(bs) > 0 and all(o.get("applied_executed_funds") is not None and o.get("applied_paid_fee") is not None for o in bs)

def sell_fields_known(o):
    return (o.get("applied_executed_funds") is not None and o.get("applied_paid_fee") is not None
            and gt0(o.get("filled_quantity")))

def in_a(d, o, kind, since):
    return (o["side"] == "SELL" and o.get("session_id") is not None and o.get("session_kind") == kind
            and o["sell_applied_at"] is not None and o["sell_applied_at"] >= since
            and (not sell_fields_known(o) or not buy_cost_ok(d, o.get("position_id"))))

def confirmed_no_trade(d, p):
    os_ = orders_of(d, p["id"])
    if (p.get("exit_reason") or "") == "ENTRY_REJECTED" and (p.get("size") if p.get("size") is not None else -1) == 0 and not os_:
        return True
    buys = [o for o in os_ if o["side"] == "BUY"]
    if not buys:
        return False
    def unfilled_confirmed(b):
        q = b.get("filled_quantity")
        if q is None or q != 0:
            return False
        st = b.get("state")
        return st == "FAILED" or (st == "CANCELLED" and (b.get("fee_source") is None or b.get("cancel_confirmed_at") is not None))
    if not all(unfilled_confirmed(b) for b in buys):
        return False
    return not any(o["side"] == "SELL" and o["sell_applied_at"] is not None for o in os_)

def q_short(d, p):
    os_ = orders_of(d, p["id"])
    bq = sum(o["filled_quantity"] for o in os_ if o["side"] == "BUY" and gt0(o.get("filled_quantity")))
    sq = sum(o.get("filled_quantity") or 0 for o in os_ if o["side"] == "SELL" and o["sell_applied_at"] is not None
             and o.get("applied_executed_funds") is not None)
    return bq > sq

def no_source(d, p):
    os_ = orders_of(d, p["id"])
    traded = any((o["side"] == "BUY" and gt0(o.get("filled_quantity"))) or (o["side"] == "SELL" and o["sell_applied_at"] is not None) for o in os_)
    return (not traded) and not confirmed_no_trade(d, p)

def in_p(d, p, kind, since):
    return (p.get("session_kind") == kind and p.get("session_id") is not None and p.get("status") == "CLOSED"
            and p["closed_at"] is not None and p["closed_at"] >= since and (q_short(d, p) or no_source(d, p)))

def mode_of(d, kind, sid):
    tbl = d["dynamic_session"] if kind == "DYNAMIC" else d["live_trading_session"]
    col = "trading_mode" if kind == "DYNAMIC" else "session_type"
    row = next((s for s in tbl if s["id"] == sid), None)
    if row is None:
        return "NO_ROW"
    return row.get(col) if row.get(col) in ("REAL", "PAPER") else "OTHER"

def run(d, now, since):
    rows = []
    kinds = ["LIVE", "DYNAMIC"]
    for k in kinds:   # ORIG: 제품 원식 = 두 COUNT 의 합
        rows.append(("ORIG", k, str(sum(in_a(d, o, k, since) for o in d["orders"]) + sum(in_p(d, p, k, since) for p in d["positions"]))))
    for k in kinds:
        A = [o for o in d["orders"] if in_a(d, o, k, since)]
        P = [p for p in d["positions"] if in_p(d, p, k, since)]
        rows.append(("SPLIT", k, str(len(A)), str(len(P)), str(len(A) + len(P))))
        for br, items in (("A", A), ("P", P)):
            for m in ("REAL", "PAPER", "NO_ROW", "OTHER"):
                rows.append(("AM" if br == "A" else "PM", k, m, str(sum(mode_of(d, k, x["session_id"]) == m for x in items))))
        f = lambda o: not sell_fields_known(o)
        c = lambda o: not buy_cost_ok(d, o.get("position_id"))
        rows += [("AX", k, "FIELDS_ONLY", str(sum(f(o) and not c(o) for o in A))),
                 ("AX", k, "COST_ONLY", str(sum(c(o) and not f(o) for o in A))),
                 ("AX", k, "FIELDS_AND_COST", str(sum(f(o) and c(o) for o in A)))]
        rows += [("PX", k, "QTY_SHORT", str(sum(q_short(d, p) and not no_source(d, p) for p in P))),
                 ("PX", k, "NO_SOURCE", str(sum(no_source(d, p) and not q_short(d, p) for p in P))),
                 ("PX", k, "BOTH", str(sum(q_short(d, p) and no_source(d, p) for p in P)))]
        ers = {}
        for p in P:
            e = (p.get("exit_reason") or "(null)")[:40]; ers[e] = ers.get(e, 0) + 1
        rows += [("PE", k, e, str(n)) for e, n in sorted(ers.items())]
        apos = {o["position_id"] for o in A if o.get("position_id") is not None}
        anull = sum(o.get("position_id") is None for o in A)
        ppos = {p["id"] for p in P}
        rows.append(("UQ", k, str(len(A)), str(len(apos)), str(anull), str(len(P)), str(len(apos & ppos)),
                     str(len(apos | ppos)), str(len(apos | ppos) + anull)))
        for br, items, key in (("A", A, "sell_applied_at"), ("P", P, "closed_at")):
            for b, lo, hi in (("D00_06", 0, 6), ("D07_13", 7, 13), ("D14_20", 14, 20), ("D21_29", 21, 29), ("D30P", 30, 10**9)):
                n = sum(lo <= int((x[key] - since).total_seconds() // 86400) <= hi for x in items)
                rows.append(("AGE", k, br, b, str(n)))
    return rows

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", newline=chr(10))
    d, now, since = load(sys.argv[1])
    for r in run(d, now, since):
        print("\t".join(r))
