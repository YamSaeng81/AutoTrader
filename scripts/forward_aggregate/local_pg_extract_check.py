"""extract.sql 로컬 PostgreSQL 왕복 검증 (2026-10-27) — 가짜 장부를 넣고 추출해 원래 CSV 와 같은지 본다.

실행: python scripts/forward_aggregate/local_pg_extract_check.py
대상: 로컬 컨테이너 cat-rt-pg 의 전용 DB fa_storage_check 만(운영 스키마 V94 를 Flyway 로 적용한 DB —
web-api 의 PaperLedgerStoragePrecisionPgTest 가 만든다). 다른 DB 이름·컨테이너로는 돌지 않는다.
🔴 운영 DB 에 붙지 않는다. 넣은 가짜 행은 끝에 지운다.

확인하는 것: ① 추출 CSV 를 계산기로 읽은 값(세션·포지션·주문·캔들·신호 건수)이 가짜 원본과 같다 — 금액 자릿수·
마이크로초 시각·UTC 변환 ② 추출이 한 트랜잭션(REPEATABLE READ READ ONLY)이고 ROLLBACK 으로 끝난다
③ 추출본으로 verify 통과·count 일치 ④ 종료 전이므로 final 은 거부된다.
"""
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from decimal import Decimal

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import aggregate as ag  # noqa: E402
from fakedata import ALL_BARS, CFG, COINS, SID, T0, Fake, at, fill_arms  # noqa: E402

CONTAINER = "cat-rt-pg"
DB = "fa_storage_check"
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")
results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)[:800]))


def psql(sql, *extra):
    r = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
                        "-U", "trader", "-d", DB, *extra], input=sql.encode("utf-8"), capture_output=True, env=ENV)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace"))
    return r.stdout.decode("utf-8")


def q(v):
    return "NULL" if v in (None, "") else "'" + str(v).replace("'", "''") + "'"


CLEAN = """
DELETE FROM paper_trading."order" WHERE session_id BETWEEN 522 AND 587;
DELETE FROM paper_trading.position WHERE session_id BETWEEN 522 AND 587;
DELETE FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587;
DELETE FROM strategy_log WHERE session_type = 'PAPER' AND session_id BETWEEN 522 AND 587;
DELETE FROM market_data_cache WHERE timeframe = 'H1' AND coin_pair IN ({coins});
""".format(coins=",".join(q(c) for c in COINS))


def seed_sql(tables):
    out = ["BEGIN;", CLEAN]
    for s in tables["sessions"]:
        out.append("INSERT INTO paper_trading.virtual_balance (id, total_krw, available_krw, initial_capital, strategy_name,"
                   " coin_pair, timeframe, status, telegram_enabled, realized_pnl, total_fee, version, started_at, stopped_at)"
                   f" VALUES ({s['id']}, {q(s['available_krw'])}, {q(s['available_krw'])}, {q(s['initial_capital'])},"
                   f" {q(s['strategy_name'])}, {q(s['coin_pair'])}, 'H1', {q(s['status'])}, false, {q(s['realized_pnl'])},"
                   f" {q(s['total_fee'])}, 0, {q(s['started_at'])}, {q(s['stopped_at'])});")
    for p in tables["positions"]:
        out.append("INSERT INTO paper_trading.position (id, session_id, coin_pair, side, entry_price, avg_price, size,"
                   " realized_pnl, position_fee, status, opened_at, closed_at, invested_krw, session_kind) VALUES"
                   f" ({p['id']}, {p['session_id']}, {q(p['coin_pair'])}, 'BUY', {q(p['entry_price'])}, {q(p['avg_price'])},"
                   f" {q(p['size'])}, {q(p['realized_pnl'] or '0')}, {q(p['position_fee'])}, {q(p['status'])},"
                   f" {q(p['opened_at'])}, {q(p['closed_at'])}, {q(p['invested_krw'])}, 'PAPER');")
    for o in tables["orders"]:
        out.append("INSERT INTO paper_trading.\"order\" (id, session_id, position_id, coin_pair, side, order_type, price,"
                   " quantity, filled_quantity, state, filled_at, created_at) VALUES"
                   f" ({o['id']}, {o['session_id']}, {o['position_id']}, {q(o['coin_pair'])}, {q(o['side'])}, 'MARKET',"
                   f" {q(o['price'])}, {q(o['quantity'])}, {q(o['filled_quantity'])}, 'FILLED', {q(o['filled_at'])},"
                   f" {q(o['filled_at'])});")
    vals = [f"({q(c['time'])}, {q(c['coin_pair'])}, 'H1', {c['close']}, {c['close']}, {c['close']}, {c['close']}, 1)"
            for c in tables["candles"]]
    for i in range(0, len(vals), 2000):
        out.append("INSERT INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume) VALUES "
                   + ",".join(vals[i:i + 2000]) + ";")
    for r in tables["signal_counts"]:
        coin = next(s["coin"] for s in CFG["sessions"] if s["id"] == int(r["session_id"]))
        for k in range(int(r["n"])):                      # 그날 안에 n 행 — 00:00:00Z 와 23:59:59.999999Z 경계 포함
            t = "00:00:00" if k == 0 else ("23:59:59.999999" if k == 1 else f"{k % 24:02d}:30:00")
            out.append("INSERT INTO strategy_log (strategy_name, coin_pair, reason, session_type, session_id, created_at)"
                       f" VALUES ('X', {q(coin)}, 'fake', 'PAPER', {r['session_id']}, '{r['day']}T{t}Z');")
    out.append("COMMIT;")
    return "\n".join(out)


def main():
    names = subprocess.run(["docker", "exec", CONTAINER, "psql", "-U", "trader", "-d", "postgres", "-Atc",
                            "select datname from pg_database"], capture_output=True, env=ENV).stdout.decode()
    if DB not in names.split():
        print(f"🔴 {CONTAINER} 에 {DB} 가 없다 — PaperLedgerStoragePrecisionPgTest 를 먼저 돌려 스키마를 만든다")
        return 2

    # 가짜 장부 — 시각은 T0 이후 ~ 어제(로컬 시계 기준 스냅샷보다 앞)
    f = Fake()
    now = datetime.now(timezone.utc)
    # 스냅샷 직전까지 마감된 봉 전부 — 열린 포지션의 경계 평가에 필요하다(verify 가 평가 가격을 검사한다, 리뷰 4차)
    last_bar = (now - timedelta(hours=2)).replace(minute=0, second=0, microsecond=0)
    for c in COINS:
        if c == "KRW-BLAST":
            f.bars(c, ALL_BARS, at("2026-10-26T02:00:00"))   # 거래지원 종료 — 그 뒤 봉 없음
        else:
            f.bars(c, ALL_BARS, last_bar, lambda t: 1000 + (t.hour % 7) * 0.123456)
    fill_arms(f, 12, T0 + timedelta(hours=1), step=timedelta(hours=20))
    sid = SID[("KRW-NEAR", "A")]
    f.buy(sid, at("2026-10-20T23:59:59.999999"), 1234.5678)          # 경계 직전 마이크로초 + 추출 시점 보유
    f.signal = [{"session_id": 522, "day": "2026-10-01", "n": 3}, {"session_id": 587, "day": "2026-10-20", "n": 5}]
    snapshot_fake = now
    work = tempfile.mkdtemp(prefix="fa_pg_")
    src_dir = f.write("pg_src", snapshot_fake)
    tables = {}
    import csv
    for n in ("sessions", "positions", "orders", "candles", "signal_counts"):
        with open(os.path.join(src_dir, n + ".csv"), encoding="utf-8", newline="") as fh:
            tables[n] = list(csv.DictReader(fh))

    try:
        psql(seed_sql(tables))
        # 추출 — 컨테이너 안 빈 폴더에서 extract.sql 실행
        sql = open(os.path.join(HERE, "extract.sql"), encoding="utf-8").read()
        r = subprocess.run(["docker", "exec", "-i", CONTAINER, "sh", "-c",
                            f"rm -rf /tmp/fa_x && mkdir /tmp/fa_x && cd /tmp/fa_x && psql -X -U trader -d {DB} -f -"],
                           input=sql.encode("utf-8"), capture_output=True, env=ENV)
        log = (r.stdout + r.stderr).decode("utf-8", "replace")
        check("extract.sql 실행 성공", r.returncode == 0, log)
        check("트랜잭션: BEGIN … ROLLBACK (쓰기 없음)", "BEGIN" in log and "ROLLBACK" in log and "COMMIT" not in log, log)
        out_dir = os.path.join(work, "extracted")
        subprocess.run(["docker", "cp", f"{CONTAINER}:/tmp/fa_x", out_dir], check=True, env=ENV)
        check("CSV 6개", sorted(os.listdir(out_dir)) == ["candles.csv", "meta.csv", "orders.csv", "positions.csv",
                                                         "sessions.csv", "signal_counts.csv"], os.listdir(out_dir))

        a = ag.load(src_dir, CFG)
        b = ag.load(out_dir, CFG)
        check("세션 66행 — 금액·상태·시각 같음", a.sessions == b.sessions,
              [(k, a.sessions[k], b.sessions.get(k)) for k in a.sessions if a.sessions[k] != b.sessions.get(k)][:2])

        def norm(data):
            out = {}
            for s, ts_ in data.trades.items():
                rows = []
                for t in ts_:
                    p = dict(t.position)
                    if p["status"] == "OPEN":
                        p["realized_pnl"] = None          # 열린 포지션의 저장값(0)은 쓰지 않는다
                    rows.append((p, t.buy, t.sell))
                out[s] = rows
            return out
        na, nb = norm(a), norm(b)
        check("포지션·주문 — 금액 8자리·마이크로초 시각 같음", na == nb,
              [(k, na[k][:1], nb[k][:1]) for k in na if na[k] != nb[k]][:1])
        check("경계 직전 23:59:59.999999Z 가 그대로 왔다",
              any(t.buy_at == at("2026-10-20T23:59:59.999999") for t in b.trades[sid]))
        check("캔들 같음(BLAST 는 10-26 02:00Z 까지)", a.candles == b.candles and
              max(b.candles["KRW-BLAST"]) == at("2026-10-26T02:00:00"))
        sa = sorted((r["session_id"], r["day"], int(r["n"])) for r in a.signal_counts)
        sb = sorted((r["session_id"], r["day"], int(r["n"])) for r in b.signal_counts)
        check("신호 기록 일별 건수 같음(UTC 날짜 경계)", sa == sb, (sa, sb))
        snap = b.snapshot
        check("스냅샷 시각 = 추출 트랜잭션 시각(지금)", abs(snap - datetime.now(timezone.utc)) < timedelta(minutes=5), snap)
        check("추출본 verify 통과", ag.verify(b) == [], ag.verify(b)[:3])
        check("추출본 count = 원본 count", ag.cmd_count(ag.load_counts(out_dir, CFG))["closed_round_trips"]
              == {"OFF": 12, "B": 12, "A": 12})
        # 건수 전용 추출(2026-10-28, LIVE_READINESS §4) — 같은 장부에서 count_extract.sql → count
        csql = open(os.path.join(HERE, "count_extract.sql"), encoding="utf-8").read()
        r = subprocess.run(["docker", "exec", "-i", CONTAINER, "sh", "-c",
                            f"rm -rf /tmp/fa_c && mkdir /tmp/fa_c && cd /tmp/fa_c && psql -X -U trader -d {DB} -f -"],
                           input=csql.encode("utf-8"), capture_output=True, env=ENV)
        clog = (r.stdout + r.stderr).decode("utf-8", "replace")
        check("count_extract.sql 실행 성공", r.returncode == 0, clog)
        check("count_extract 트랜잭션: BEGIN … ROLLBACK (쓰기 없음)",
              "BEGIN" in clog and "ROLLBACK" in clog and "COMMIT" not in clog, clog)
        cdir = os.path.join(work, "count_extracted")
        subprocess.run(["docker", "cp", f"{CONTAINER}:/tmp/fa_c", cdir], check=True, env=ENV)
        check("건수 추출 CSV 4개", sorted(os.listdir(cdir)) == ["meta.csv", "orders.csv", "positions.csv", "sessions.csv"],
              os.listdir(cdir))
        heads = {n: open(os.path.join(cdir, n), encoding="utf-8").readline().strip() for n in os.listdir(cdir)}
        check("건수 추출 헤더 = count 로더가 읽는 열",
              all(heads[n] == ",".join(cols) for n, cols in ag.COUNT_FIELDS.items()) and heads["meta.csv"] == "snapshot_ts",
              heads)
        full_count = ag.cmd_count(ag.load_counts(out_dir, CFG))
        slim_count = ag.cmd_count(ag.load_counts(cdir, CFG))
        check("건수 추출의 count = 전체 추출의 count(해시·스냅샷 제외)",
              {k: v for k, v in slim_count.items() if k not in ("inputs_sha256", "snapshot")}
              == {k: v for k, v in full_count.items() if k not in ("inputs_sha256", "snapshot")}, (slim_count, full_count))
        try:
            ag.cmd_final(b, os.path.join(work, "final"))
            refused = False
        except ag.InputError as e:
            refused = "종료 전" in str(e)
        check("종료 전 → final 거부(중간 성과 계산 안 함)", refused)
        check("열린 포지션의 저장 realized_pnl 은 0 — 계산기는 쓰지 않는다",
              all(t.position["realized_pnl"] == Decimal("0E-8") or t.position["realized_pnl"] == 0
                  for t in b.trades[sid] if t.sell is None))
    finally:
        psql("BEGIN;" + CLEAN + "COMMIT;")
        left = psql("SELECT (SELECT count(*) FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587)"
                    " + (SELECT count(*) FROM paper_trading.position WHERE session_id BETWEEN 522 AND 587);", "-At")
        check("가짜 행 정리됨", left.strip() == "0", left)
        shutil.rmtree(work, ignore_errors=True)

    print(f"\n{sum(results)}/{len(results)} 통과")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
