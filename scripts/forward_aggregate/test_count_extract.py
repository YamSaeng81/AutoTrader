"""건수 전용 추출 시험 (2026-10-28) — 가짜 데이터만. 실행: python scripts/forward_aggregate/test_count_extract.py

count_extract.sql 이 aggregate.py count 가 읽는 열(COUNT_FIELDS)만 내보내는지, 그 열만 남긴 CSV 로도 count 결과가
전체 추출과 같은지 본다. 🔴 운영 데이터를 쓰지 않는다. 로컬 PostgreSQL 왕복은 local_pg_extract_check.py 와 같은
방식으로 따로 한다(이 시험은 DB 없이 돈다).
"""
import csv
import os
import re
import shutil
import sys
from datetime import timedelta

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import aggregate as ag  # noqa: E402
from fakedata import CFG, T0, WORK, Fake, fill_arms  # noqa: E402

results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)[:600]))


def copies(sql):
    """\\copy (SELECT <열> FROM <표> ...) TO '<파일>' → {파일: (열 이름들, 표)}"""
    out = {}
    for m in re.finditer(r"\\copy \(SELECT (.+?) FROM (\S+) .*?\) TO '([\w.]+)'|\\copy \(SELECT (.+?)\) TO '([\w.]+)'", sql):
        cols, table, name = (m.group(1), m.group(2), m.group(3)) if m.group(1) else (m.group(4), None, m.group(5))
        names = []
        for c in re.split(r",(?![^()]*\))", cols):          # 괄호 안의 쉼표(to_char 인자)는 나누지 않는다
            c = c.strip()
            a = re.search(r"\bAS\s+(\w+)\s*$", c, re.I)
            names.append(a.group(1) if a else c.split(".")[-1])
        out[name] = (tuple(names), table)
    return out


sql = open(os.path.join(HERE, "count_extract.sql"), encoding="utf-8").read()
full_sql = open(os.path.join(HERE, "extract.sql"), encoding="utf-8").read()
body = re.sub(r"--[^\n]*", "", sql)
got = copies(body)
full = copies(re.sub(r"--[^\n]*", "", full_sql))

# ── 열: COUNT_FIELDS 와 정확히 같다 ─────────────────────────────────────────────
want = dict(ag.COUNT_FIELDS, **{"meta.csv": ("snapshot_ts",)})
check("내보내는 파일 = meta·sessions·positions·orders 넷뿐", set(got) == set(want), sorted(got))
for name, cols in want.items():
    check(f"{name} 열 = count 로더가 읽는 열과 정확히 같다", got.get(name, ((), None))[0] == tuple(cols), got.get(name))
ALL = " ".join(" ".join(c) for c, _ in got.values())
check("금액·가격·수량·손익·잔액 열이 없다",
      not re.search(r"price|quantity|krw|pnl|fee|capital|amount|size|close|status", ALL, re.I), ALL)
check("캔들·신호 집계를 읽지 않는다", not re.search(r"market_data_cache|strategy_log", body))

# ── 범위·표: 전체 추출과 같은 세션 범위·같은 표 ─────────────────────────────────
for name in ("sessions.csv", "positions.csv", "orders.csv"):
    check(f"{name} 표가 전체 추출과 같다", got[name][1] == full[name][1], (got[name][1], full[name][1]))
check("세션 범위 522~587 이 전체 추출과 같다",
      body.count("BETWEEN 522 AND 587") == 3 and "BETWEEN 522 AND 587" in full_sql)
check("시각 형식이 전체 추출과 같다(UTC 마이크로초 Z)",
      body.count("'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'") == 2)

# ── 읽기 전용 ──────────────────────────────────────────────────────────────────
stmts = [s for s in body.split("\n") if s.strip()]
first_sql = next(s for s in stmts if not s.strip().startswith("\\"))
check("첫 SQL 문장 = REPEATABLE READ READ ONLY 트랜잭션",
      re.match(r"\s*BEGIN\s+TRANSACTION\s+ISOLATION\s+LEVEL\s+REPEATABLE\s+READ\s+READ\s+ONLY\s*;", first_sql, re.I),
      first_sql)
check("마지막 문장 = ROLLBACK", stmts[-1].strip().upper() == "ROLLBACK;", stmts[-1])
check("쓰기 문장 없음", not re.search(r"\b(INSERT|UPDATE|DELETE|TRUNCATE|ALTER|DROP|CREATE|GRANT|COMMIT)\b",
                                 re.sub(r"\\copy[^\n]*", "", body), re.I))
check("모든 \\copy 가 TO 파일(FROM 파일로 읽어 들이지 않음)",
      all(re.search(r"\)\s+TO\s+'", ln) for ln in stmts if ln.startswith("\\copy")))
check("모든 \\copy 가 트랜잭션 안(BEGIN 뒤·ROLLBACK 앞)",
      all(body.find("BEGIN") < m.start() < body.rfind("ROLLBACK") for m in re.finditer(r"\\copy", body)))

# ── 같은 장부: 열을 줄인 CSV 로도 count 결과가 같다 ─────────────────────────────
f = Fake()
fill_arms(f, 7, T0 + timedelta(hours=1))
f.buy(522, T0 + timedelta(days=4), 1000)                # 열린 포지션 1 — 청산 왕복으로 세지 않는다
d = f.write("count_full", T0 + timedelta(days=5))
slim = os.path.join(WORK, "count_slim")
os.makedirs(slim)
for name, cols in want.items():
    with open(os.path.join(d, name), encoding="utf-8", newline="") as src, \
            open(os.path.join(slim, name), "w", encoding="utf-8", newline="") as dst:
        w = csv.DictWriter(dst, fieldnames=list(cols))
        w.writeheader()
        for r in csv.DictReader(src):
            w.writerow({k: r[k] for k in cols})
a = ag.cmd_count(ag.load_counts(d, CFG))
b = ag.cmd_count(ag.load_counts(slim, CFG))
strip = lambda o: {k: v for k, v in o.items() if k != "inputs_sha256"}   # noqa: E731
check("열을 줄인 추출의 count = 전체 추출의 count", strip(a) == strip(b), (strip(a), strip(b)))
check("팔별 청산 왕복 7(열린 포지션 제외)", b["closed_round_trips"] == {"OFF": 7, "B": 7, "A": 7}, b)
check("줄인 폴더에는 캔들·신호 파일이 없다", sorted(os.listdir(slim)) == sorted(want))
check("입력 해시는 줄인 파일 기준으로 다시 계산된다", a["inputs_sha256"] != b["inputs_sha256"])

shutil.rmtree(WORK, ignore_errors=True)
print(f"\n{sum(results)}/{len(results)} 통과")
sys.exit(0 if all(results) else 1)
