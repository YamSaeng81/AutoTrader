"""sid_ledger.sql 시험 (로컬 — 운영에 닿지 않는다). 리뷰 2026-10-31 56일 운영 점검 2번.

    python scripts/ops/test_sid_ledger.py

시험 DB: cat-rt-pg 컨테이너. 원본 crypto_auto_trader 에는 격리 리허설 4차 기록(SID 203 STOPPED 20,182.36·매수·매도 각 1)이 보존돼 있다 —
원본은 읽기 전용 조회만 하고, 바꾸는 경우는 사본(rt_ledger)에서만 한다.
기대값은 조회와 따로 계산한다(시작 잔액·체결 대금·수수료·봉 종가에서 Decimal 로).
"""
import os
import subprocess
import sys
from decimal import Decimal as D

HERE = os.path.dirname(os.path.abspath(__file__))
SQL_FILE = os.path.join(HERE, "sid_ledger.sql")
DB = "rt_ledger"
PG = ["docker", "exec", "-i", "cat-rt-pg", "psql", "-X", "-q", "-U", "trader"]

results = []
sys.stdout.reconfigure(encoding="utf-8")


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "\n     " + str(detail).replace("\n", "\n     ")))


def sql(db, text, at=False):
    r = subprocess.run(PG + ["-d", db, "-v", "ON_ERROR_STOP=1"] + (["-At", "-F", "|"] if at else []), input=text.encode("utf-8"), capture_output=True)
    if r.returncode != 0:
        raise SystemExit("SQL 실패: " + r.stderr.decode("utf-8", "replace"))
    return r.stdout.decode("utf-8")


def ledger(db, init="20200"):
    """조회 파일을 그대로 돌려 구역(L0~L4)별로 {열: 값} 행 목록을 돌려준다. 빈 값은 '-'(\\pset null)."""
    r = subprocess.run(PG + ["-d", db, "-A", "-F", "|", "-v", "sid=203", "-v", "init=" + init, "-f", "-"],
                       stdin=open(SQL_FILE, "rb"), capture_output=True)
    out = r.stdout.decode("utf-8")
    sec, rows, hdr = None, {}, None
    for line in out.splitlines():
        if line.startswith("== L"):
            sec, hdr = line[3:5], None
            rows[sec] = []
        elif sec and "|" in line:
            if hdr is None:
                hdr = line.split("|")
            else:
                rows[sec].append(dict(zip(hdr, line.split("|"))))
    return r.returncode, rows, out + r.stderr.decode("utf-8", "replace")


# 1. 보존된 리허설 4차 기록(원본, 읽기 전용) — 정지 청산 매도가 stopped_at 뒤에 체결된 실제 자료
rc, R, raw = ledger("crypto_auto_trader")
l1 = R.get("L1", [])
check("원본 리허설 — 종료 0·L0~L4 모두 나온다·끝 표시", rc == 0 and all(k in R for k in ("L0", "L1", "L2", "L3", "L4")) and "== 끝" in raw, raw)
check("원본 리허설 — L0 initial_capital = init (t)", R["L0"][0]["init_ok"] == "t", R["L0"])
exp_cash = D("20200") - (D("19600") + D("9.8")) + (D("19600") - D("7.84"))   # 20182.36
check("원본 리허설 — 날 끝 현금 = 20200 − (19600+9.80) + (19600−7.84) = 20182.36 · BTC 0 · 순손익 −17.64(정지 뒤 체결한 매도 포함)",
      len(l1) == 1 and D(l1[0]["cash"]) == exp_cash and D(l1[0]["btc"]) == 0 and D(l1[0]["net_pnl"]) == exp_cash - D("20200")
      and l1[0]["fills"] == "1/1", l1)
l4 = R["L4"][0]
check("원본 리허설 — 장부 현금 = 세션 잔액 20182.36 · 순체결량 0 = 열린 포지션 0",
      D(l4["ledger_cash"]) == D(l4["available_krw"]) == exp_cash and D(l4["net_btc"]) == 0 and D(l4["open_size"]) == 0, l4)
check("원본 리허설 — realized_pnl 합계(−7.84)는 참고로만, 순손익(−17.64)과 다르다(매수 수수료 빠짐)",
      D(l4["realized_pnl_sum_ref"]) == D("-7.84") and D(l1[0]["net_pnl"]) == D("-17.64"), l4)
l2 = {r["id"]: r for r in R["L2"]}
check("원본 리허설 — 매수 슬리피지 = 19600 − 12255000×0.0016 = −8.00(유리) · 매도는 신호가 없어 '기준가 없음'(0 으로 채우지 않음)",
      D(l2["1"]["slip_krw"]) == D("-8.00") and l2["2"]["slip_krw"] == "-" and l2["2"]["note"] == "기준가 없음", R["L2"])
l3 = R["L3"][0]
check("원본 리허설 — 왕복 비용률 = (17.64 − 8.00) / 19600 = 0.0492% · 순 −17.64 · 기준가 없음 1",
      D(l3["cost_pct"]) == round((D("17.64") - D("8")) / D("19600") * 100, 4) == D("0.0492") and D(l3["net_krw"]) == D("-17.64") and l3["no_ref"] == "1",
      l3)
before = sql("crypto_auto_trader", 'SELECT status, available_krw FROM live_trading_session WHERE id = 203; SELECT count(*), sum(filled_quantity) FROM "order";', at=True)
check("원본 리허설 — 조회 뒤에도 원본 그대로(STOPPED 20182.36 · 주문 2)", before.split() == ["STOPPED|20182.36", "2|0.00320000"], before)

# 2. 사본 — 여러 날 보유(날 끝 평가)·확인 불가·시작 전·init 불일치
sql("postgres", "DROP DATABASE IF EXISTS %s; CREATE DATABASE %s TEMPLATE crypto_auto_trader;" % (DB, DB))


def reset(started="2026-10-28 08:50:00+09", status="RUNNING", stopped=None):
    sql(DB, 'DELETE FROM order_settlement_discrepancy; DELETE FROM position_cleanup; DELETE FROM trade_log; DELETE FROM "order"; DELETE FROM position; '
            "DELETE FROM strategy_log WHERE session_id = 203; "
            "UPDATE live_trading_session SET status = '%s', started_at = %s, stopped_at = %s, available_krw = 20200 WHERE id = 203;"
            % (status, "'%s'" % started if started else "NULL", "'%s'" % stopped if stopped else "NULL"))


def buy(fee="9.8", rfee="9.8", pending=False, at="2026-10-28 09:00:10+09"):
    sql(DB, "INSERT INTO position (id, coin_pair, side, entry_price, avg_price, size, status, session_id, session_kind) "
            "VALUES (1, 'KRW-BTC', 'LONG', 12250000, 12250000, 0.0016, 'OPEN', 203, 'LIVE');"
            "INSERT INTO \"order\" (id, position_id, coin_pair, side, order_type, quantity, state, filled_quantity, session_id, session_kind, created_at, filled_at, "
            "signal_price, reported_executed_funds, reported_paid_fee, applied_executed_funds, applied_paid_fee, fill_accounting_pending) "
            "VALUES (1, 1, 'KRW-BTC', 'BUY', 'MARKET', 0.0016, 'FILLED', 0.0016, 203, 'LIVE', '%s', '%s', 12255000, 19600, %s, 19600, %s, %s);"
            % (at, at, rfee or "NULL", fee or "NULL", "true" if pending else "false"))
    sql(DB, "INSERT INTO strategy_log (strategy_name, coin_pair, signal, reason, created_at, session_type, session_id, signal_price, was_executed) "
            "VALUES ('DAILY_SMA20', 'KRW-BTC', 'BUY', 't', '%s', 'LIVE', 203, 12255000, true), "
            "('DAILY_SMA20', 'KRW-BTC', 'BUY', 't', '2026-10-29 09:00:05+09', 'LIVE', 203, 12300000, false);" % at)
    sql(DB, "UPDATE strategy_log SET blocked_reason = '이미 포지션 보유 중' WHERE session_id = 203 AND NOT was_executed;")


def close_at(day_end):
    """날 끝(KST 자정 또는 지금) 이전 마지막 마감 H1 봉 종가 — 조회와 따로 계산"""
    return D(sql(DB, "SELECT close FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1' AND time + interval '1 hour' <= %s "
                     "ORDER BY time DESC LIMIT 1;" % day_end, at=True).strip())


reset(); buy()
rc, R, raw = ledger(DB)
days = {r["kst_date"]: r for r in R["L1"]}
cash = D("20200") - D("19600") - D("9.8")
ok = rc == 0 and "2026-10-28" in days and "2026-10-30" in days
for d, end in (("2026-10-28", "'2026-10-29 00:00+09'"), ("2026-10-29", "'2026-10-30 00:00+09'"), ("2026-10-30", "'2026-10-31 00:00+09'")):
    px = close_at(end)
    r = days.get(d, {})
    ok = ok and D(r.get("cash", "0")) == cash and D(r.get("btc", "0")) == D("0.0016") and D(r.get("holding_value", "0")) == round(D("0.0016") * px, 2) \
        and D(r.get("equity", "0")) == round(cash + D("0.0016") * px, 2) and D(r.get("net_pnl", "0")) == round(cash + D("0.0016") * px - D("20200"), 2)
check("여러 날 보유 — 날마다 현금 590.20 + 0.0016 × 그 날 자정 이전 마지막 H1 종가 = 자산, 순손익 = 자산 − 20200(따로 계산한 값과 같음)", ok, R["L1"])
check("여러 날 — 신호·관문 차단 사유가 날짜에 붙는다(10-28 BUY 실행 1 · 10-29 BUY 차단 '이미 포지션 보유 중×1')",
      days["2026-10-28"]["signals"] == "1/0/0 실행 1" and days["2026-10-28"]["blocked"] == "-"
      and days["2026-10-29"]["signals"] == "1/0/0 실행 0" and days["2026-10-29"]["blocked"] == "이미 포지션 보유 중×1"
      and days["2026-10-29"]["fills"] == "0/0", R["L1"])
check("여러 날 — L4 순체결량 0.0016 = 열린 포지션 0.0016 · 장부 현금 590.20(세션 잔액 20200 과 다른 것은 이 시험 자료가 잔액을 갱신하지 않아서 — 대조 열로 드러난다)",
      D(R["L4"][0]["net_btc"]) == D("0.0016") == D(R["L4"][0]["open_size"]) and D(R["L4"][0]["ledger_cash"]) == cash
      and D(R["L4"][0]["available_krw"]) == D("20200"), R["L4"])

reset(); buy(fee=None)
rc, R, raw = ledger(DB)
check("반영 수수료 없음 — 그 날부터 현금·자산·순손익 비움 '확인 불가'(0 대체 없음), 보유 수량·평가액은 낸다",
      rc == 0 and all(r["cash"] == "-" and r["equity"] == "-" and r["net_pnl"] == "-" and r["note"].startswith("확인 불가") for r in R["L1"])
      and R["L4"][0]["ledger_cash"] == "-" and R["L4"][0]["unk_orders"] == "1" and R["L2"][0]["note"] == "확인 불가", R["L1"] + R["L4"])
reset(); buy(rfee=None)
rc, R, raw = ledger(DB)
check("보고 수수료 없음 — 같은 '확인 불가'", rc == 0 and all(r["net_pnl"] == "-" for r in R["L1"]) and R["L2"][0]["note"] == "확인 불가", R["L1"])
reset(); buy(pending=True)
rc, R, raw = ledger(DB)
check("회계 반영 대기 — 같은 '확인 불가'", rc == 0 and all(r["net_pnl"] == "-" for r in R["L1"]) and R["L4"][0]["unk_orders"] == "1", R["L1"])
reset(); buy(rfee="9.9")
rc, R, raw = ledger(DB)
check("보고 수수료 9.9 ≠ 반영 9.8 — 주문 줄 '보고≠반영'(현금은 반영값 기준)", rc == 0 and R["L2"][0]["note"] == "보고≠반영"
      and D(R["L1"][0]["cash"]) == cash, R["L2"])

reset(started=None, status="CREATED")
rc, R, raw = ledger(DB)
check("시작 전(CREATED·started_at 없음) — L1 날짜 행 0 · L0 상태 CREATED", rc == 0 and R["L1"] == [] and R["L0"][0]["status"] == "CREATED", raw)
rc, R, raw = ledger(DB, init="20000")
check("init 가 initial_capital 과 다르면 L0 init_ok = f", rc == 0 and R["L0"][0]["init_ok"] == "f", R["L0"])

after = sql("crypto_auto_trader", 'SELECT status, available_krw FROM live_trading_session WHERE id = 203; SELECT count(*), sum(filled_quantity) FROM "order";', at=True)
check("끝 — 원본 시험 DB 그대로(STOPPED 20182.36 · 주문 2)", after.split() == ["STOPPED|20182.36", "2|0.00320000"], after)

n_ok = sum(1 for _, ok in results if ok)
print(f"sid_ledger 시험 {n_ok}/{len(results)} 통과")
sys.exit(0 if n_ok == len(results) else 1)
