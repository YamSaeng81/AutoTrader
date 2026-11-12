"""sid_ledger.sql 시험 (로컬 — 운영에 닿지 않는다). 리뷰 2026-10-31 56일 운영 점검 2번.

    python scripts/ops/test_sid_ledger.py

시험 DB: cat-rt-pg 컨테이너. 원본 crypto_auto_trader 에는 격리 리허설 4차 기록(SID 203 STOPPED 20,182.36·매수·매도 각 1)이 보존돼 있다 —
원본은 읽기 전용 조회만 하고, 바꾸는 경우는 사본(rt_ledger)에서만 한다.
기대값은 조회와 따로 계산한다(시작 잔액·체결 대금·수수료·봉 종가에서 Decimal 로).
"""
import os
import shutil
import subprocess
import sys
from decimal import Decimal as D, ROUND_HALF_UP

HERE = os.path.dirname(os.path.abspath(__file__))
SQL_FILE = os.path.join(HERE, "sid_ledger.sql")
DB = "rt_ledger"
PG = ["docker", "exec", "-i", "cat-rt-pg", "psql", "-X", "-q", "-U", "trader"]

results = []
sys.stdout.reconfigure(encoding="utf-8")


def r2(x):
    """잔액 반영값 — 둘째 자리 HALF_UP(운영 코드 BalanceAmounts·PostgreSQL round 와 같다). 🔴 Python round() 는 Decimal 에서 HALF_EVEN 이라 쓰지 않는다."""
    return x.quantize(D("0.01"), rounding=ROUND_HALF_UP)


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
check("원본 리허설 — 종료 0·L0~L5 모두 나온다·끝 표시·L0 조회 시각(재구성 시점)", rc == 0 and all(k in R for k in ("L0", "L1", "L2", "L3", "L4", "L5")) and "== 끝" in raw
      and R["L0"][0]["queried_kst"][:2] == "20", raw)
check("원본 리허설 — L0 initial_capital = init (t)", R["L0"][0]["init_ok"] == "t", R["L0"])
exp_cash = D("20200") - (D("19600") + D("9.8")) + (D("19600") - D("7.84"))   # 20182.36
check("원본 리허설 — 날 끝 현금 = 20200 − (19600+9.80) + (19600−7.84) = 20182.36 · BTC 0 · 순손익 −17.64(정지 뒤 체결한 매도 포함)",
      len(l1) == 1 and D(l1[0]["cash"]) == exp_cash and D(l1[0]["btc"]) == 0 and D(l1[0]["net_pnl"]) == exp_cash - D("20200")
      and l1[0]["fills"] == "1/1", l1)
l4 = R["L4"][0]
check("원본 리허설 — 장부 현금 = 세션 잔액 20182.36 · 순체결량 0 = 열린 포지션 0",
      D(l4["ledger_cash"]) == D(l4["available_krw"]) == exp_cash and D(l4["net_btc"]) == 0 and D(l4["open_size"]) == 0, l4)
exp_bal = D("20200") - D("20000") + r2(D("20000") - D("19600") - D("9.8")) + r2(D("19600") - D("7.84"))   # 리뷰 2026-10-31 반올림 결정
check("원본 리허설 — 잔액 기대(주문별 두 자리: −요청 20000 + 환급 390.20 + 순입금 19592.16) = 세션 잔액 20182.36 · balance_diff 0 · raw_diff 0",
      D(l4["balance_expected"]) == exp_bal == D(l4["available_krw"]) and D(l4["balance_diff"]) == 0 and D(l4["raw_diff"]) == 0, l4)
check("원본 리허설 — realized_pnl 합계(−7.84)는 참고로만, 순손익(−17.64)과 다르다(매수 수수료 빠짐)",
      D(l4["realized_pnl_sum_ref"]) == D("-7.84") and D(l1[0]["net_pnl"]) == D("-17.64"), l4)
l2 = {r["id"]: r for r in R["L2"]}
check("원본 리허설 — 매수 슬리피지 = 19600 − 12255000×0.0016 = −8.00(유리) · 매도는 신호가 없어 '기준가 없음'(0 으로 채우지 않음)",
      D(l2["1"]["slip_krw"]) == D("-8.00") and l2["2"]["slip_krw"] == "-" and l2["2"]["note"] == "기준가 없음", R["L2"])
l3 = R["L3"][0]
# 🔴 리뷰 2026-10-31 보완 1: 종전 판은 청산 기준가가 없는데도 빠진 슬리피지를 빼고 0.0492% 를 냈다 — 완전한 왕복 비용률이 아니다.
check("원본 리허설 — 정지 청산 매도에 신호가가 없어 왕복 '측정 불가(청산 기준가 없음)' · 비용률·판정 비움(종전 0.0492% 아님) · 진입 불리 −0.04079967% · 실제 수수료 17.64 따로 · 순 −17.64",
      l3["rt_status"] == "측정 불가(청산 기준가 없음)" and l3["cost_pct"] == "-" and l3["cost_gt_040"] == "-"
      and D(l3["entry_unf_pct"]) == round((D("19600") - D("12255000") * D("0.0016")) / (D("12255000") * D("0.0016")) * 100, 8)
      and l3["exit_unf_pct"] == "-" and D(l3["fees"]) == D("17.64") and D(l3["net_krw"]) == D("-17.64"), l3)
l5 = {r["kind"]: r for r in R["L5"]}
check("원본 리허설 — L5 출처별: 서킷브레이커 0 · 포지션 한도 0 · 공유 풀 차단 0 · 최종 STOPPED '일반 정지'(사유는 운영자 기록)",
      l5["서킷브레이커"]["n"] == "0" and l5["포지션 한도 도달"]["n"] == "0" and l5["공유 풀 손실 한도(진입 차단)"]["n"] == "0"
      and l5["세션 최종 상태"]["n"] == "STOPPED" and l5["세션 최종 상태"]["detail"].startswith("일반 정지"), R["L5"])
before = sql("crypto_auto_trader", 'SELECT status, available_krw FROM live_trading_session WHERE id = 203; SELECT count(*), sum(filled_quantity) FROM "order";', at=True)
check("원본 리허설 — 조회 뒤에도 원본 그대로(STOPPED 20182.36 · 주문 2)", before.split() == ["STOPPED|20182.36", "2|0.00320000"], before)

# 2. 사본 — 여러 날 보유(날 끝 평가)·확인 불가·시작 전·init 불일치
sql("postgres", "DROP DATABASE IF EXISTS %s; CREATE DATABASE %s TEMPLATE crypto_auto_trader;" % (DB, DB))


def reset(started="2026-10-28 08:50:00+09", status="RUNNING", stopped=None):
    sql(DB, 'DELETE FROM order_settlement_discrepancy; DELETE FROM position_cleanup; DELETE FROM trade_log; DELETE FROM "order"; DELETE FROM position; DELETE FROM position_limit_breach; '
            "DELETE FROM strategy_log WHERE session_type = 'PAPER' AND session_id = 203; "
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

sql(DB, "UPDATE \"order\" SET applied_paid_fee = 10.0, reported_paid_fee = 10.0 WHERE id = 1;")   # 며칠 뒤 온 수수료 정정
rc, R2, raw = ledger(DB)
d2 = {r["kst_date"]: r for r in R2["L1"]}
check("늦은 수수료 정정(9.8 → 10.0) — 다시 돌리면 지난 날(10-28)의 현금도 590.00 으로 바뀐다(조회 시점 최신 반영값으로 재구성한 기록 — 그날 값은 날짜별 원본 파일)",
      rc == 0 and D(days["2026-10-28"]["cash"]) == cash and D(d2["2026-10-28"]["cash"]) == D("20200") - D("19600") - D("10.0"), R2["L1"])

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



def rt(bq="0.001", sq="0.001", b_sp="10000000", s_sp="10000000", b_af="9990", s_af="9960", b_fee="4.995", s_fee="4.98", pos_status="CLOSED", sell=True, s_fee_rep="",
       b_rf=None):
    """왕복 1개(포지션 2, 매수 id 11 · 매도 id 12). 값은 문자열, None 은 NULL. s_fee_rep 를 비우면 보고 수수료 = 반영 수수료, b_rf 를 비우면 매수 보고 대금 = 반영 대금."""
    v = lambda x: "NULL" if x is None else x
    sql(DB, "INSERT INTO position (id, coin_pair, side, entry_price, avg_price, size, status, session_id, session_kind) "
            "VALUES (2, 'KRW-BTC', 'LONG', 10000000, 10000000, %s, '%s', 203, 'LIVE');" % ("0" if pos_status == "CLOSED" else bq, pos_status))
    ins = ('INSERT INTO "order" (id, position_id, coin_pair, side, order_type, quantity, state, filled_quantity, session_id, session_kind, created_at, filled_at, '
           "signal_price, reported_executed_funds, reported_paid_fee, applied_executed_funds, applied_paid_fee, fill_accounting_pending) VALUES ")
    sql(DB, ins + "(11, 2, 'KRW-BTC', 'BUY', 'MARKET', %s, 'FILLED', %s, 203, 'LIVE', '2026-10-28 09:00:10+09', '2026-10-28 09:00:10+09', %s, %s, %s, %s, %s, false);"
            % (bq, bq, v(b_sp), b_rf or b_af, b_fee, b_af, b_fee))
    if sell:
        sql(DB, ins + "(12, 2, 'KRW-BTC', 'SELL', 'MARKET', %s, 'FILLED', %s, 203, 'LIVE', '2026-10-29 09:00:10+09', '2026-10-29 09:00:10+09', %s, %s, %s, %s, %s, false);"
                % (sq, sq, v(s_sp), s_af, v(s_fee) if s_fee_rep == "" else v(s_fee_rep), s_af, v(s_fee)))
    rc, R, raw = ledger(DB)
    return rc, (R.get("L3") or [{}])[0], R, raw


# 3. 왕복 비용률 — 사전 등록 정의(불리 진입 + 불리 청산 + 0.10) · 측정 불가 · 왕복 미완료 (리뷰 2026-10-31 보완 1)
def prereg(b_af, b_ref, s_af, s_ref):
    """LIVE_ENTRY_PREREG.md 비용의 정의 — 조회와 따로 계산"""
    return (D(b_af) - D(b_ref)) / D(b_ref) * 100 + (D(s_ref) - D(s_af)) / D(s_ref) * 100 + D("0.10")


reset(); rc, l3, R, raw = rt()
exp = prereg("9990", "10000", "9960", "10000")                                   # −0.10 + 0.40 + 0.10 = 0.40
old = (D("4.995") + D("4.98") + (D("9990") - D("10000")) + (D("10000") - D("9960"))) / D("9990") * 100   # 종전 식 (수수료 + 슬리피지) / 매수 대금
check("경계 — 사전 등록 정의 0.40000000%% → cost_gt_040 f (종전 식이면 %.8f%% 로 > 0.40 — 판단이 갈리는 사례) · 실제 수수료 9.975 는 따로" % old,
      rc == 0 and exp == D("0.40") and old > D("0.40") and l3.get("rt_status") == "측정" and D(l3["cost_pct"]) == exp and l3["cost_gt_040"] == "f"
      and D(l3["fees"]) == D("9.975"), l3)
reset(); rc, l3, R, raw = rt(s_af="9959.99")
check("경계 바로 위 — 청산 9959.99 → 0.40010000% → cost_gt_040 t (반올림 전 값으로 판정)",
      rc == 0 and D(l3["cost_pct"]) == round(prereg("9990", "10000", "9959.99", "10000"), 8) == D("0.40010000") and l3["cost_gt_040"] == "t", l3)
reset(); rc, l3, R, raw = rt(b_sp=None)
check("진입 기준가 없음 — '측정 불가(진입 기준가 없음)' · 비용률·판정 비움", rc == 0 and l3["rt_status"] == "측정 불가(진입 기준가 없음)"
      and l3["cost_pct"] == "-" and l3["cost_gt_040"] == "-", l3)
reset(); rc, l3, R, raw = rt(s_fee=None)
check("청산 반영 수수료 없음 — '측정 불가(정산 확인 불가)' · 비용률·순손익 비움", rc == 0 and l3["rt_status"] == "측정 불가(정산 확인 불가)"
      and l3["cost_pct"] == "-" and l3["net_krw"] == "-", l3)
reset(); rc, l3, R, raw = rt(s_fee_rep=None)
check("청산 보고 수수료 없음 — '측정 불가(정산 확인 불가)'", rc == 0 and l3["rt_status"] == "측정 불가(정산 확인 불가)" and l3["cost_pct"] == "-", l3)
reset(); rc, l3, R, raw = rt(sq="0.0009", s_af="8964")
check("매수 0.001 · 매도 0.0009 — '측정 불가(매수/매도 수량 불일치)' · 비용률·순손익 비움", rc == 0 and l3["rt_status"] == "측정 불가(매수/매도 수량 불일치)"
      and l3["cost_pct"] == "-" and l3["net_krw"] == "-", l3)
reset(); rc, l3, R, raw = rt(pos_status="OPEN", sell=False)
check("열린 포지션 — '왕복 미완료' · 비용률·판정 비움(진입 불리 −0.1 은 적는다)", rc == 0 and l3["rt_status"] == "왕복 미완료"
      and l3["cost_pct"] == "-" and l3["cost_gt_040"] == "-" and D(l3["entry_unf_pct"]) == D("-0.1"), l3)

# 3-1. 정산 불일치 — 보고값≠반영값이거나 미해결 불일치 기록이 있으면 측정 불가 (리뷰 2026-10-31 장부 보완 1)
reset(); rc, l3, R, raw = rt(b_af="10000", b_rf="10040", s_af="9980")
by_applied, by_reported = prereg("10000", "10000", "9980", "10000"), prereg("10040", "10000", "9980", "10000")   # 0.30 · 0.70
check("매수 반영 10,000·보고 10,040 — 반영값이면 %s%%(≤ 0.40), 보고값이면 %s%%(> 0.40)로 갈림 → '측정 불가(정산 불일치)' · 비용률·판정·실제 수수료율·순손익 비움"
      % (by_applied, by_reported),
      rc == 0 and by_applied <= D("0.40") < by_reported and l3.get("rt_status") == "측정 불가(정산 불일치)" and l3["cost_pct"] == "-"
      and l3["cost_gt_040"] == "-" and l3["fee_pct_actual"] == "-" and l3["net_krw"] == "-" and R["L2"][0]["note"] == "보고≠반영", l3)
reset(); rc, l3, R, raw = rt(b_af="10000", s_af="9980")
check("같은 왕복, 보고값 = 반영값·불일치 기록 없음 — '측정' %s%% → f (아래 두 사례의 기준)" % by_applied,
      rc == 0 and l3.get("rt_status") == "측정" and D(l3["cost_pct"]) == by_applied and l3["cost_gt_040"] == "f", l3)
sql(DB, "INSERT INTO order_settlement_discrepancy (id, order_id, detected_at, kind, previous_value, received_value) "
        "VALUES (901, 999, '2026-10-29 09:00:20+09', 'FEE_DECREASE', 4.98, 4.90);")
rc, R, raw = ledger(DB); l3 = R["L3"][0]
check("미해결 불일치 기록이 다른 주문(999)에만 — 이 왕복은 '측정' 그대로", rc == 0 and l3["rt_status"] == "측정" and D(l3["cost_pct"]) == by_applied, l3)
sql(DB, "INSERT INTO order_settlement_discrepancy (id, order_id, detected_at, kind, previous_value, received_value) "
        "VALUES (902, 12, '2026-10-29 09:00:20+09', 'FEE_DECREASE', 4.98, 4.90);")
rc, R, raw = ledger(DB); l3 = R["L3"][0]
check("값은 같지만 청산 주문(12)에 미해결 불일치 기록 — '측정 불가(정산 불일치)' · 비용률·판정·순손익 비움",
      rc == 0 and l3["rt_status"] == "측정 불가(정산 불일치)" and l3["cost_pct"] == "-" and l3["cost_gt_040"] == "-" and l3["net_krw"] == "-", l3)
sql(DB, "UPDATE order_settlement_discrepancy SET resolved_at = '2026-10-29 10:00+09', resolved_by = 't', resolution_note = 't' WHERE id = 902;")
rc, R, raw = ledger(DB); l3 = R["L3"][0]
check("그 기록 해결 뒤 — '측정' %s%% → f · 순손익 = 9980 − 4.98 − 10000 − 4.995 로 복귀" % by_applied,
      rc == 0 and l3["rt_status"] == "측정" and D(l3["cost_pct"]) == by_applied and l3["cost_gt_040"] == "f"
      and D(l3["net_krw"]) == D("9980") - D("4.98") - D("10000") - D("4.995"), l3)

# 4. 중단 기능별 기록(L5) — 서킷브레이커와 다른 중단 사유를 구분 (리뷰 2026-10-31 보완 4)
reset(status="EMERGENCY_STOPPED", stopped="2026-10-29 10:00:00+09")
sql(DB, "UPDATE live_trading_session SET circuit_breaker_trip_count = 1, circuit_breaker_reason = 'MDD 20% 초과', "
        "circuit_breaker_triggered_at = '2026-10-29 10:00:00+09' WHERE id = 203;")
rc, R, raw = ledger(DB)
l5 = {r["kind"]: r for r in R["L5"]}
check("서킷브레이커 비상정지 — 서킷브레이커 1·사유·시각 · 최종 EMERGENCY_STOPPED '서킷브레이커 발동 기록과 함께'",
      rc == 0 and l5["서킷브레이커"]["n"] == "1" and l5["서킷브레이커"]["detail"] == "MDD 20% 초과" and l5["서킷브레이커"]["last_kst"] == "2026-10-29 10:00:00"
      and l5["세션 최종 상태"]["n"] == "EMERGENCY_STOPPED" and "서킷브레이커 발동 기록과 함께" in l5["세션 최종 상태"]["detail"], R["L5"])
sql(DB, "UPDATE live_trading_session SET circuit_breaker_trip_count = 0, circuit_breaker_reason = NULL, circuit_breaker_triggered_at = NULL WHERE id = 203;")
rc, R, raw = ledger(DB)
l5 = {r["kind"]: r for r in R["L5"]}
check("서킷브레이커 기록 없는 비상정지(거래소 장애 일괄·수동) — 서킷브레이커 0 이어도 '발동 없음'으로 끝내지 않고 '사유는 앱 로그·텔레그램으로 확인'",
      rc == 0 and l5["서킷브레이커"]["n"] == "0" and "서킷브레이커 기록 없음" in l5["세션 최종 상태"]["detail"]
      and "앱 로그" in l5["세션 최종 상태"]["detail"], R["L5"])
reset(status="STOPPED", stopped="2026-10-29 10:00:00+09")
sql(DB, "INSERT INTO position_limit_breach (position_id, session_kind, session_id, code, value_krw, cost_krw, detail, breached_at, stop_action, stop_at) "
        "VALUES (1, 'LIVE', 203, 'CAPITAL_LOSS', 17000, 20000, 't', '2026-10-29 09:59:00+09', 'AUTO_STOPPED', '2026-10-29 10:00:00+09'), "
        "(9, 'LIVE', 204, 'MAX_DRAWDOWN', 1, 1, 't', '2026-10-29 09:59:00+09', NULL, NULL);"
        "INSERT INTO strategy_log (strategy_name, coin_pair, signal, reason, created_at, session_type, session_id, was_executed, blocked_reason) VALUES "
        "('DAILY_SMA20', 'KRW-BTC', 'BUY', 't', '2026-10-28 09:00:05+09', 'LIVE', 203, false, '예약 거절(POOL_LOSS_LIMIT): 누적 손실 한도 도달 — t'), "
        "('DAILY_SMA20', 'KRW-BTC', 'BUY', 't', '2026-10-28 09:00:05+09', 'PAPER', 203, false, '예약 거절(POOL_LOSS_LIMIT): 다른 세션 종류');")
rc, R, raw = ledger(DB)
l5 = {r["kind"]: r for r in R["L5"]}
check("포지션 한도 자동 정지·공유 풀 손실 한도 차단 — 각각 1(SID 203 LIVE 만, 204·PAPER 제외) · 최종 STOPPED '일반 정지'",
      rc == 0 and l5["포지션 한도 도달"]["n"] == "1" and "CAPITAL_LOSS 정지 AUTO_STOPPED" in l5["포지션 한도 도달"]["detail"]
      and l5["공유 풀 손실 한도(진입 차단)"]["n"] == "1" and l5["세션 최종 상태"]["n"] == "STOPPED", R["L5"])

# 5. §5-8 F5-3 완료 조회 — 문서에서 그대로 꺼내 돌린다. 최종 상태는 STOPPED 또는 EMERGENCY_STOPPED (리뷰 2026-10-31 보완 4)
PLAN = os.path.join(HERE, "..", "..", "docs", "LIMITED_LIVE_TEST_RUNBOOK.md")
_doc = open(PLAN, encoding="utf-8").read()
_i = _doc.index('$PSQL -At -F \' | \' -c "SELECT *, CASE WHEN status IN')
_q = _doc[_doc.index('"', _i) + 1:_doc.index(' x" | tee "$E/stop-check.txt"', _i) + 2]
STOP_CHECK = _q.replace('\\"', '"').replace("$SID", "203") + ";"


def stop_check(db):
    r = subprocess.run(PG + ["-d", db, "-At", "-F", " | ", "-c", STOP_CHECK], capture_output=True)
    return r.returncode, r.stdout.decode("utf-8").strip() + r.stderr.decode("utf-8", "replace")


rc, out = stop_check("crypto_auto_trader")
F5_OK = "DB 정리 조건 충족 — 정산·거래소 대조 별도 확인 필요"   # 리뷰 2026-10-31 장부 보완 2 — 확인 범위대로(종전 '완료 조건 충족 — 상태 … 그대로')
check("F5-3 완료 조회(문서 그대로) — 보존 4차 기록: 'STOPPED | 20182.36 | … | 0 | 0 | 0 | 0 | " + F5_OK + "'",
      rc == 0 and out.startswith("STOPPED | 20182.36 | ") and out.endswith(" | 0 | 0 | 0 | 0 | " + F5_OK), out)
check("F5-3 — 끝 문구에 '완료 조건 충족'·'그대로' 가 없다(정산·거래소 대조 전 완료로 읽히지 않게)", "완료 조건 충족" not in out and "그대로" not in out, out)
reset(status="EMERGENCY_STOPPED", stopped="2026-10-29 10:00:00+09"); rt()
rc, out = stop_check(DB)
check("F5-3 — 비상정지 뒤 청산 완료(포지션 CLOSED·활성 주문 0): 첫 칸 EMERGENCY_STOPPED · '" + F5_OK + "'(STOPPED 를 요구하지 않음)",
      rc == 0 and out.startswith("EMERGENCY_STOPPED | ") and out.endswith(" | 0 | 0 | 0 | 0 | " + F5_OK), out)
reset(status="EMERGENCY_STOPPED", stopped="2026-10-29 10:00:00+09"); rt(pos_status="OPEN", sell=False)
rc, out = stop_check(DB)
check("F5-3 — 비상정지인데 열린 포지션 1: '미완료'", rc == 0 and out.startswith("EMERGENCY_STOPPED | ") and out.endswith(" | 0 | 1 | 0 | 0 | 미완료"), out)
reset(status="RUNNING"); rt()
rc, out = stop_check(DB)
check("F5-3 — RUNNING 이면 주문·포지션이 0 이어도 '미완료'", rc == 0 and out.startswith("RUNNING | ") and out.endswith(" | 미완료"), out)

# 4. 일반 청산 → 장부 연결 (리뷰 2026-10-31 "일반 청산 → 비용 장부 연결") — 실제 LIVE 경로가 만든 행을 그대로 넣는다.
#    DailySma20LiveOrderPathTest 통합 ⑤ 손절 · ⑥ 정지 청산 · ⑦ 익절 · ⑧ 전략 SELL 이 왕복을 마친 뒤 web-api/build/sid-ledger-link/<사례>.tsv 를 남긴다
#    (그 시험이 매도 signal_price = 청산 판단 당시 가격(마지막 봉 종가)을 먼저 단언한다). 파일이 없으면 그 시험부터 돌린다 — 실패로 센다.
#    기대값은 파일이 아니라 그 시험의 입력(판단가·체결 대금·수수료)에서 따로 계산한다.
LINK = os.path.join(HERE, "..", "..", "web-api", "build", "sid-ledger-link")


def link(name):
    path = os.path.join(LINK, name + ".tsv")
    if not os.path.exists(path):
        return None
    rows = [l.rstrip("\n").split("\t") for l in open(path, encoding="utf-8") if l.strip()]
    q = lambda x: "NULL" if x == "\\N" else "'%s'" % x
    reset()
    srow = [r for r in rows if r[0] == "S"][0]
    sql(DB, "UPDATE live_trading_session SET available_krw = %s WHERE id = 203;" % srow[3])
    for r in rows:
        if r[0] == "P":
            sql(DB, "INSERT INTO position (id, coin_pair, side, entry_price, avg_price, size, status, exit_reason, realized_pnl, session_id, session_kind) "
                    "VALUES (%s, 'KRW-BTC', 'BUY', %s, %s, %s, %s, %s, %s, 203, 'LIVE');" % (r[1], r[4], r[5], r[6], q(r[2]), q(r[3]), q(r[7])))
    for r in rows:
        if r[0] == "O":
            sql(DB, 'INSERT INTO "order" (id, position_id, coin_pair, side, order_type, quantity, state, filled_quantity, signal_price, reported_executed_funds, '
                    "reported_paid_fee, applied_executed_funds, applied_paid_fee, fill_accounting_pending, created_at, filled_at, session_id, session_kind) "
                    "VALUES (%s, %s, 'KRW-BTC', %s, 'MARKET', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 203, 'LIVE');"
                    % (r[1], r[2], q(r[3]), r[5], q(r[4]), r[6], q(r[7]), q(r[8]), q(r[9]), q(r[10]), q(r[11]), r[12], q(r[13]), q(r[14])))
    rc, R, raw = ledger(DB)
    return rc, R, raw, rows


B_REF, B_AF, B_FEE, Q = D("12495000"), D("19980"), D("9.99"), D("0.0016")    # 매수: 판단가 12,495,000 · 대금 19,980 · 수수료 9.99 (통합 ①과 같음)
CASES = [  # 사례 · 청산 사유 · 매도 판단가 · 매도 대금 · 매도 수수료 · 시험이 본 세션 잔액(NUMERIC(20,2))
    ("stop_loss", "STOP_LOSS", D("11800000"), D("18880"), D("9.44"), D("19080.57")),
    ("take_profit", "TAKE_PROFIT", D("13500000"), D("21590"), D("10.795"), D("21789.22")),
    ("strategy_sell", "STRATEGY_SIGNAL", D("12237750"), D("19570"), D("9.785"), D("19770.23")),
]
for name, why, s_sp, s_af, s_fee, avail in CASES:
    got = link(name)
    if got is None:
        check("일반 청산 연결 %s — web-api/build/sid-ledger-link/%s.tsv 없음: DailySma20LiveOrderPathTest 를 먼저 돌린다" % (name, name), False)
        continue
    rc, R, raw, rows = got
    sell = [r for r in rows if r[0] == "O" and r[3] == "SELL"]
    l3 = (R.get("L3") or [{}])[0]
    exp = prereg(B_AF, B_REF * Q, s_af, s_sp * Q)
    net = s_af - (B_FEE + s_fee) - B_AF
    check("일반 청산 연결 %s — 실제 경로 행: 청산 사유 %s · 매도 signal_price = 판단가 %s · 매수 signal_price = 12495000" % (name, why, s_sp),
          len(sell) == 1 and D(sell[0][7]) == s_sp and [r for r in rows if r[0] == "P"][0][3] == why
          and D([r for r in rows if r[0] == "O" and r[3] == "BUY"][0][7]) == B_REF, rows)
    check("일반 청산 연결 %s — L3 '측정' · cost_pct = 불리 진입 + 불리 청산 + 0.10 = %.8f%% · 0.40 판정 f · 순손익 %s(= 매도 대금 − 수수료 합 − 매수 대금, 반영값)"
          % (name, round(exp, 8), net),
          rc == 0 and l3.get("rt_status") == "측정" and D(l3["cost_pct"]) == round(exp, 8) and l3["cost_gt_040"] == "f"
          and D(l3["net_krw"]) == net and D(l3["fees"]) == B_FEE + s_fee, l3)
    l4 = R["L4"][0]
    cash = D("20200") - B_AF - B_FEE + s_af - s_fee
    # 🔴 기록: 세션 잔액 열은 NUMERIC(20,2), 반영 대금·수수료는 8자리 — 원시 장부 현금과 세션 잔액은 0.005 다를 수 있다(§5-9 10). 리뷰 결정(2026-10-31)으로 잔액 대조는 아래 잔액 기대(주문별 두 자리)로 한다.
    check("일반 청산 연결 %s — L4 장부 현금 %s · 세션 잔액 %s · 차이 %s (기록 — 잔액 열 둘째 자리 저장)" % (name, cash, avail, avail - cash),
          D(l4["ledger_cash"]) == cash and D(l4["available_krw"]) == avail and D(l4["net_btc"]) == 0 and D(l4["open_size"]) == 0, l4)
    bal = D("20200") - D("20000") + r2(D("20000") - B_AF - B_FEE) + r2(s_af - s_fee)
    check("일반 청산 연결 %s — L4 잔액 기대(주문별 두 자리) %s = 세션 잔액 · balance_diff 0 · raw_diff %s(원시 장부와의 반올림, 참고)" % (name, bal, avail - cash),
          D(l4["balance_expected"]) == bal == avail and D(l4["balance_diff"]) == 0 and D(l4["raw_diff"]) == avail - cash, l4)

got = link("forced_stop")
if got is None:
    check("정지 청산 연결 — web-api/build/sid-ledger-link/forced_stop.tsv 없음: DailySma20LiveOrderPathTest 를 먼저 돌린다", False)
else:
    rc, R, raw, rows = got
    l3 = (R.get("L3") or [{}])[0]
    l2s = [r for r in R["L2"] if r["side"] == "SELL"]
    check("정지 청산 연결 — 정지 정리 매도 signal_price 없음 → L2 '기준가 없음' · L3 '측정 불가(청산 기준가 없음)' · 비용률·판정 비움(승인한 처리 그대로) · 순손익 −7.98",
          rc == 0 and len(l2s) == 1 and l2s[0]["signal_price"] == "-" and l2s[0]["note"] == "기준가 없음"
          and l3.get("rt_status") == "측정 불가(청산 기준가 없음)" and l3["cost_pct"] == "-" and l3["cost_gt_040"] == "-"
          and l3["exit_reason"] == "FORCED_STOP" and D(l3["net_krw"]) == D("19992") - D("19.98") - B_AF == D("-7.98"), l3)

# 5. 셋째 자리 수수료(리뷰 2026-10-31 반올림 결정) — 원시 장부와 잔액 기대를 따로 낸다. 기대값은 입력에서 따로 계산한다.
def sub_cent(avail):
    reset()
    sql(DB, "UPDATE live_trading_session SET available_krw = %s WHERE id = 203;" % avail)
    sql(DB, "INSERT INTO position (id, coin_pair, side, entry_price, avg_price, size, status, session_id, session_kind) "
            "VALUES (1, 'KRW-BTC', 'LONG', 12495000, 12495000, 0.0016, 'CLOSED', 203, 'LIVE');"
            "INSERT INTO \"order\" (id, position_id, coin_pair, side, order_type, quantity, state, filled_quantity, session_id, session_kind, created_at, filled_at, "
            "signal_price, reported_executed_funds, reported_paid_fee, applied_executed_funds, applied_paid_fee, fill_accounting_pending) VALUES "
            "(1, 1, 'KRW-BTC', 'BUY', 'MARKET', 20000, 'FILLED', 0.0016, 203, 'LIVE', '2026-10-28 09:00:10+09', '2026-10-28 09:00:10+09', 12495000, 19980, 9.994, 19980, 9.994, false),"
            "(2, 1, 'KRW-BTC', 'SELL', 'MARKET', 0.0016, 'FILLED', 0.0016, 203, 'LIVE', '2026-10-29 09:00:10+09', '2026-10-29 09:00:10+09', 13500000, 21590, 10.805, 21590, 10.805, false);")
    return ledger(DB)


raw_cash = D("20200") - (D("19980") + D("9.994")) + (D("21590") - D("10.805"))                                     # 21789.201
bal = D("20200") - D("20000") + r2(D("20000") - D("19980") - D("9.994")) + r2(D("21590") - D("10.805"))  # 10.006→10.01, 21579.195→21579.20 = 21789.21
rc, R, raw = sub_cent(bal)
l4 = R["L4"][0]
check("셋째 자리 수수료 — 원시 장부 현금 %s · 잔액 기대 %s(주문별 두 자리) = 세션 잔액 · balance_diff 0 · raw_diff %s(참고)" % (raw_cash, bal, bal - raw_cash),
      rc == 0 and D(l4["ledger_cash"]) == raw_cash and D(l4["balance_expected"]) == bal and D(l4["balance_diff"]) == 0
      and D(l4["raw_diff"]) == bal - raw_cash == D("0.009"), l4)
rc, R, raw = sub_cent(bal + D("0.01"))
check("셋째 자리 수수료 — 세션 잔액이 0.01 다르면 balance_diff 0.01 로 드러난다(허용 오차 없음)",
      rc == 0 and D(R["L4"][0]["balance_diff"]) == D("0.01"), R["L4"])

reset(started=None, status="CREATED")
rc, R, raw = ledger(DB)
check("시작 전(CREATED·started_at 없음) — L1 날짜 행 0 · L0 상태 CREATED", rc == 0 and R["L1"] == [] and R["L0"][0]["status"] == "CREATED", raw)
rc, R, raw = ledger(DB, init="20000")
check("init 가 initial_capital 과 다르면 L0 init_ok = f", rc == 0 and R["L0"][0]["init_ok"] == "f", R["L0"])

# 6. §5-8 F3-2 일상 장부 읽기(리뷰 2026-11-01 3번) — 문서의 판정 코드를 그대로 꺼내, 실제 장부 조회 출력(psql -A -F '|' 파일)에 돌린다.
_f32 = _doc[_doc.index("**F3-2. "):_doc.index("**F4. 종료 시각 고정**")]
READ_PY = _f32[_f32.index("<<'PY'\n") + len("<<'PY'\n"):_f32.index("\nPY\n")]
M1_SQL = _f32[_f32.index("<<'SQL'\n") + len("<<'SQL'\n"):_f32.index("\nSQL\n") + 1]   # v1.42 보완 2 — 장부 뒤에 붙는 정산 불일치 검사
F32_BLOCK = _f32[_f32.index("```bash\n") + len("```bash\n"):_f32.index("\n```\n", _f32.index("```bash\n")) + 1]
# ~~v1.41 블록 줄 확인('… -f - < ~/ops-scripts/sid_ledger.sql > "$F"')~~ → v1.42: 장부 + M1 을 한 psql 에 넣고, 조회 종료가 0 일 때만 파서를 돌린다
check("F3-2 — 블록 조회 줄이 장부 + M1 을 -A -F '|' 로 새 파일(set -C)에 남기고, 조회 종료 0 일 때만 파서를 돌린다",
      "( set -C -o pipefail; cd ~/crypto_trader/AutoTrader && { cat ~/ops-scripts/sid_ledger.sql && cat <<'SQL'" in _f32
      and "psql -X -A -F '|' -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -v sid=203 -v init=20200 -f - > \"$F\" 2>&1 ); RC=$?" in _f32
      and "if [ \"$RC\" -ne 0 ]; then" in _f32 and "else python3 - \"$F\" <<'PY'" in _f32 and "\nPY\nfi\n" in _f32
      and "BEGIN TRANSACTION READ ONLY;" in M1_SQL and M1_SQL.rstrip().endswith("\\echo '== 정산 검사 끝'"), _f32[:900])
import tempfile
_tmp = tempfile.mkdtemp(prefix="ledger-read-")


def read_case(name, adjust="0", cut=0):
    """세션 잔액을 잔액 기대(+adjust)로 맞춘 뒤 운영과 같은 형식으로 조회해 문서의 판정 코드를 돌린다. cut = 끝에서 버릴 줄 수(조회 중단 흉내).
    rt() 의 매수 주문은 요청액 칸에 BTC 수량(0.001)이 들어 있어 잔액 기대가 셋째 자리가 된다(두 자리 세션 잔액과 0.001 어긋남) — 실제 LIVE 매수처럼
    원 단위 요청액(두 자리) 10,010원으로 바꾼다. 첫 실행 65/70 의 실패 5건이 이 시험 자료 문제였다(판정 코드는 그대로)."""
    sql(DB, "UPDATE \"order\" SET quantity = 10010 WHERE session_id = 203 AND side = 'BUY' AND id = 11;")
    rc, R, raw = ledger(DB)
    be = R["L4"][0]["balance_expected"]
    if be != "-":
        sql(DB, "UPDATE live_trading_session SET available_krw = %s + %s WHERE id = 203;" % (be, adjust))
    # v1.42: 운영 블록과 같게 장부 + M1 을 한 입력으로, -q 없이(BEGIN·ROLLBACK·(n rows) 줄까지 운영 출력과 같은 모양)
    r = subprocess.run([a for a in PG if a != "-q"] + ["-d", DB, "-A", "-F", "|", "-v", "ON_ERROR_STOP=1", "-v", "sid=203", "-v", "init=20200", "-f", "-"],
                       input=open(SQL_FILE, "rb").read() + M1_SQL.encode("utf-8"), capture_output=True)
    lines = r.stdout.decode("utf-8").splitlines(True)
    p = os.path.join(_tmp, name + ".txt")
    if isinstance(cut, str):                       # v1.42: 이 표시로 시작하는 줄부터 버린다(구역 단위 조회 중단 흉내)
        lines = lines[:next(i for i, x in enumerate(lines) if x.startswith(cut))]
    else:
        lines = lines[:len(lines) - cut]
    open(p, "w", encoding="utf-8", newline="\n").write("".join(lines))
    x = subprocess.run([sys.executable, "-", p], input=READ_PY.encode("utf-8"), capture_output=True, env=dict(os.environ, PYTHONIOENCODING="utf-8"))
    out = x.stdout.decode("utf-8") + x.stderr.decode("utf-8", "replace")
    return x.returncode, out, out.strip().splitlines()[-1] if out.strip() else ""


reset()
rc, out, last = read_case("r_none")
# v1.42: ✅ 줄 끝에 "· 정산 불일치 0" 을 붙였다(M1) — 종전 기대 "… · balance_diff 0" 으로 끝남
check("F3-2 — 시작 뒤 매수 없음: ✅ 완료 왕복 0건 · balance_diff 0 · 정산 불일치 0 · 종료 0", rc == 0 and last == "✅ 판정: 현재 조회 기준 일치 — 완료 왕복 0건 모두 측정·0.40% 이하 · 왕복 미완료 0건 · balance_diff 0 · 정산 불일치 0", out)
reset(); rt()
rc, out, last = read_case("r_040")
check("F3-2 — 경계 0.40000000%(해당 아님): ✅ 완료 왕복 1건 · L3 줄에 비용 0.40000000% · > 0.40 f",
      rc == 0 and last.startswith("✅ 판정: 현재 조회 기준 일치 — 완료 왕복 1건") and "L3 포지션 2 CLOSED - · 측정 · 비용 0.40000000% · > 0.40 f" in out, out)
reset(); rt(s_af="9959.99")
rc, out, last = read_case("r_over")
check("F3-2 — 0.40010000% → 🔴 §6-2 중단 조건(포지션 2) · 종료 1", rc == 1 and last.startswith("🔴 판정: §6-2 중단 조건 — 왕복 실측 비용 > 0.40%(포지션 2)"), out)
reset(status="STOPPED", stopped="2026-10-29 10:00:00+09"); rt(s_af="9959.99")
rc, out, last = read_case("r_over_stopped")
check("F3-2 — 정지 뒤라도 > 0.40 이면 중단 조건 줄이 먼저(⛔ 로 묻히지 않음)", rc == 1 and last.startswith("🔴 판정: §6-2 중단 조건"), out)
reset(); rt(b_sp=None)
rc, out, last = read_case("r_noref")
check("F3-2 — 진입 기준가 없음 → ❔ 측정 불가(사유 그대로) · 0.40% 대조 안 함", rc == 1 and last.startswith("❔ 판정: 측정 불가 — 포지션 2 측정 불가(진입 기준가 없음)"), out)
reset(); rt(b_af="10000", b_rf="10040", s_af="9980")
rc, out, last = read_case("r_mism")
# ~~v1.41 기대: 완료 왕복의 보고≠반영 → '❔ 판정: 측정 불가 — 포지션 2 측정 불가(정산 불일치)'~~ → v1.42(리뷰 2026-11-01 F3-2 보완 2): OPEN·CLOSED 무관 🔴 정산
check("F3-2 — 완료 왕복의 매수 보고 10,040 ≠ 반영 10,000 → 🔴 정산(보고≠반영 1건) · L3 줄은 '측정 불가(정산 불일치)' 그대로",
      rc == 1 and last.startswith("🔴 판정: 정산 — 보고≠반영 1건 · 미해결 정산 불일치 기록 0건") and "측정 불가(정산 불일치)" in out
      and "주문 11 BUY 보고 10040" in out and " 반영 10000" in out, out)
reset(); rt(pos_status="OPEN", sell=False)
rc, out, last = read_case("r_open")
check("F3-2 — 보유 중(왕복 미완료) → ✅ 완료 왕복 0건 · 왕복 미완료 1건", rc == 0 and last.endswith("완료 왕복 0건 모두 측정·0.40% 이하 · 왕복 미완료 1건 · balance_diff 0 · 정산 불일치 0"), out)
reset(); rt()
rc, out, last = read_case("r_diff", adjust="0.01")
check("F3-2 — 세션 잔액이 잔액 기대보다 0.01 많음 → 🔴 정산 balance_diff 0.01(허용 오차 없음)", rc == 1 and last.startswith("🔴 판정: 정산 — balance_diff 0.01000000 "), out)
reset(); rt(sq="0.0009", s_af="8964")
rc, out, last = read_case("r_qty")
check("F3-2 — 매도 수량 부족(순체결량 0.0001 ≠ 열린 포지션 0) → 🔴 정산", rc == 1 and last.startswith("🔴 판정: 정산 — ") and "순체결량 0.00010000 / 열린 포지션 수량 0" in last, out)
reset(); buy(pending=True)
rc, out, last = read_case("r_pend")
check("F3-2 — 회계 반영 대기 → ❔ 확인 불가(1건)", rc == 1 and last.startswith("❔ 판정: 확인 불가 — ") and "(1건)" in last, out)
reset(status="EMERGENCY_STOPPED", stopped="2026-10-29 10:00:00+09")
rc, out, last = read_case("r_emerg")
check("F3-2 — 비상정지 → ⛔ 세션 상태 EMERGENCY_STOPPED", rc == 1 and last.startswith("⛔ 판정: 세션 상태 EMERGENCY_STOPPED"), out)
reset(); rt()
# ~~v1.41: (1, "끝 표시 없음"), (12, "L4·L5 빠짐")~~ → v1.42: 출력 끝이 M1 뒤 '== 정산 검사 끝' 으로 바뀌어 줄 수 대신 구역 표시로도 자른다
for i, (cut, what) in enumerate(((1, "정산 검사 끝 표시 없음"), ("== M1", "장부는 끝까지 · M1 빠짐"), ("== 끝", "장부 끝 표시·M1 빠짐"), ("== L4", "L4·L5·M1 빠짐"))):
    rc, out, last = read_case("r_cut%d" % i, cut=cut)
    check("F3-2 — 조회 중단(%s) → ❔ 끝까지 돌지 않음 · 정상 판정 없음" % what, rc == 1 and last.startswith("❔ 판정: 장부 조회가 끝까지 돌지 않음") and "✅" not in out, out)

# 6-1. v1.42 보완 2(리뷰 2026-11-01) — 보유 중 정산 불일치. 리뷰 재현: 매수 보고 10,040 · 반영 10,000, 잔액·수량은 반영값 기준으로 맞음 → v1.41 은 ✅
reset(); rt(pos_status="OPEN", sell=False, b_af="10000", b_rf="10040")
rc, out, last = read_case("r_open_mism")
check("F3-2 — 보유 중(왕복 미완료) 매수 보고 10,040 ≠ 반영 10,000, 잔액·수량은 반영값 기준 일치 → 🔴 정산(보고≠반영 1건) · ✅ 아님",
      rc == 1 and last.startswith("🔴 판정: 정산 — 보고≠반영 1건 · 미해결 정산 불일치 기록 0건(포지션 OPEN·CLOSED 무관)")
      and "왕복 미완료" in out and "balance_diff 0.00" in out and "✅" not in out, out)
reset(); rt(pos_status="OPEN", sell=False, b_af="10000")
rc, out, last = read_case("r_open_ok")
check("F3-2 — 같은 보유, 보고 = 반영·기록 없음 → ✅ · M1 0 · 0", rc == 0 and last.startswith("✅ 판정: ") and "M1 보고≠반영(반영 끝난 주문) 0 · 미해결 정산 불일치 기록 0 · -" in out, out)
sql(DB, "INSERT INTO order_settlement_discrepancy (id, order_id, detected_at, kind, previous_value, received_value) "
        "VALUES (903, 999, '2026-10-28 09:00:20+09', 'FEE_DECREASE', 4.98, 4.90);")
rc, out, last = read_case("r_open_other")
check("F3-2 — 미해결 기록이 SID 203 주문이 아닌 주문(999)에만 → ✅(203 만 센다)", rc == 0 and last.startswith("✅ 판정: "), out)
sql(DB, "INSERT INTO order_settlement_discrepancy (id, order_id, detected_at, kind, previous_value, received_value) "
        "VALUES (904, 11, '2026-10-28 09:00:20+09', 'FEE_DECREASE', 4.995, 4.90);")
rc, out, last = read_case("r_open_disc")
check("F3-2 — 보유 중, 값은 같지만 매수 주문(11)에 미해결 정산 불일치 기록 → 🔴 정산(기록 1건) · detail 에 기록 904 FEE_DECREASE",
      rc == 1 and last.startswith("🔴 판정: 정산 — 보고≠반영 0건 · 미해결 정산 불일치 기록 1건") and "주문 11 미해결 기록 904 FEE_DECREASE" in out, out)
sql(DB, "UPDATE order_settlement_discrepancy SET resolved_at = '2026-10-28 10:00+09', resolved_by = 't', resolution_note = 't' WHERE id = 904;")
rc, out, last = read_case("r_open_resolved")
check("F3-2 — 그 기록 해결 뒤 → ✅ 복귀", rc == 0 and last.startswith("✅ 판정: "), out)
# v1.44(리뷰 2026-11-01) — 누락과 확정 불일치가 한 주문에 함께: 보고 대금 NULL · 보고 수수료 5 · 반영 수수료 4 → 🔴 유지, 상세의 NULL 은 '미확인'(0 대체 없음)
reset(); rt(pos_status="OPEN", sell=False, b_af="10000", b_fee="4")
sql(DB, "UPDATE \"order\" SET reported_executed_funds = NULL, reported_paid_fee = 5 WHERE id = 11;")
rc, out, last = read_case("r_null_and_mism")
check("F3-2 — 보고 대금 없음 + 보고 수수료 5 ≠ 반영 4 → 🔴 정산(보고≠반영 1건, ❔ 로 내리지 않음) · 상세 '주문 11 BUY 보고 미확인/5… 반영 10000…/4…'",
      rc == 1 and last.startswith("🔴 판정: 정산 — 보고≠반영 1건") and "주문 11 BUY 보고 미확인/5" in out and " 반영 10000" in out and "✅" not in out, out)
reset(); buy(pending=True, rfee="9.9")
rc, out, last = read_case("r_pend_mism")
check("F3-2 — 회계 반영 대기 주문의 보고 9.9 ≠ 반영 9.8 → M1 에 넣지 않고 ❔ 확인 불가(1건) 유지", rc == 1 and last.startswith("❔ 판정: 확인 불가 — ")
      and "M1 보고≠반영(반영 끝난 주문) 0 · " in out, out)
reset(); buy(rfee=None)
rc, out, last = read_case("r_rep_missing")
check("F3-2 — 보고 수수료 없음(값 누락) → M1 에 넣지 않고 ❔ 확인 불가(1건) 유지", rc == 1 and last.startswith("❔ 판정: 확인 불가 — ") and "(1건)" in last, out)
reset(status="STOPPED", stopped="2026-10-29 10:00:00+09"); rt(pos_status="OPEN", sell=False, b_af="10000", b_rf="10040")
rc, out, last = read_case("r_stopped_mism")
check("F3-2 — 정지 뒤 보고≠반영 → 판정 줄은 ⛔(순서대로), M1 줄에 1건이 보인다", rc == 1 and last.startswith("⛔ 판정: 세션 상태 STOPPED")
      and "M1 보고≠반영(반영 끝난 주문) 1 · " in out, out)
shutil.rmtree(_tmp, ignore_errors=True)

# 7. v1.42 보완 1(리뷰 2026-11-01) — F3-2 블록 전체를 문서에서 꺼내 Git Bash 로 돌린다. 리뷰 재현: 같은 분 재실행(set -C 가 덮어쓰기를 막음) → v1.41 은 '조회 종료 1' 뒤 ✅·종료 0.
#    가짜: docker(compose … exec -T db psql → 시험 컨테이너 psql, -d crypto_auto_trader → rt_ledger, FAKE_FAIL 이면 앞 5줄만 내고 종료 2) · date(분 고정) · python3(이 파이썬)
GIT_BASH = r"C:\Program Files\Git\bin\bash.exe"
BASH = GIT_BASH if os.name == "nt" and os.path.exists(GIT_BASH) else (shutil.which("bash") or GIT_BASH)


def px(p):
    """bash PATH 에 넣을 경로 — 'C:/…' 는 콜론 때문에 PATH 가 갈라진다(첫 실행에서 가짜 docker·date·curl 이 안 잡힌 원인). '/c/…' 로 바꾼다."""
    p = p.replace("\\", "/")
    return "/" + p[0].lower() + p[2:] if len(p) > 2 and p[1] == ":" else p

_blk = tempfile.mkdtemp(prefix="f32-block-")
_bh, _bb = os.path.join(_blk, "home"), os.path.join(_blk, "bin")
os.makedirs(os.path.join(_bh, "crypto_trader", "AutoTrader")); os.makedirs(os.path.join(_bh, "ops-scripts")); os.makedirs(_bb)
shutil.copy(SQL_FILE, os.path.join(_bh, "ops-scripts", "sid_ledger.sql"))
_real_docker = (shutil.which("docker") or "docker").replace("\\", "/")
for _n, _body in (("docker", '[ "$1 $2 $3 $4 $5 $6 $7" = "compose -f docker-compose.prod.yml exec -T db psql" ] || { echo "가짜 docker: 예상 밖 $*" >&2; exit 99; }\n'
                             'shift 7; a=(); for x in "$@"; do [ "$x" = crypto_auto_trader ] && x=%s; a+=("$x"); done\n'
                             'if [ -n "$FAKE_FAIL" ]; then "%s" exec -i cat-rt-pg psql "${a[@]}" | head -5; echo "psql: error: 가짜 조회 실패" >&2; exit 2; fi\n'
                             'exec "%s" exec -i cat-rt-pg psql "${a[@]}"\n' % (DB, _real_docker, _real_docker)),
                  ("date", '[ "$1" = "+%Y%m%d-%H%M" ] && { echo "$FAKE_MIN"; exit 0; }; exec /usr/bin/date "$@"\n'),
                  ("python3", 'exec "%s" "$@"\n' % sys.executable.replace("\\", "/"))):
    with open(os.path.join(_bb, _n), "w", encoding="utf-8", newline="\n") as f:
        f.write("#!/usr/bin/env bash\n" + _body)


def block(minute, fail=False):
    env = dict(os.environ, HOME=_bh.replace("\\", "/"), FAKE_MIN=minute, PYTHONIOENCODING="utf-8")   # 경로 변환은 켠 채로 — 윈도우 파이썬이 /tmp/… 를 읽게
    env.pop("FAKE_FAIL", None)
    if fail:
        env["FAKE_FAIL"] = "1"
    guard = 'export PATH="%s:$PATH"; for c in docker date python3; do [ "$(command -v $c)" = "%s/$c" ] || { echo "가짜 $c 가 아님: $(command -v $c)"; exit 97; }; done\n' % (px(_bb), px(_bb))
    r = subprocess.run([BASH, "-c", guard + F32_BLOCK], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)   # 화면 순서 그대로(마지막 줄 = 판정)
    out = r.stdout.decode("utf-8", "replace")
    return r.returncode, out, out.strip().splitlines()[-1] if out.strip() else ""


def rfile(minute):
    p = os.path.join(_bh, "sid203-read", minute + ".txt")
    return open(p, "rb").read() if os.path.exists(p) else None


reset(); rt()
_tmp = tempfile.mkdtemp(prefix="ledger-read-")   # read_case 로 세션 잔액만 맞춘다
read_case("align")
rc, out, last = block("20261201-1000")
first = rfile("20261201-1000")
check("F3-2 블록 — 정상: '조회 종료 0' · ✅ · 종료 0 · 파일 20261201-1000.txt 생김", rc == 0 and "조회 종료 0" in out and last.startswith("✅ 판정: ") and first, out)
rc, out, last = block("20261201-1000")
check("F3-2 블록 — 리뷰 재현(같은 분 재실행): '조회 종료 1' · ❔ 조회하지 않음 · ✅ 없음 · 종료 0 아님 · 기존 파일 그대로",
      rc != 0 and "조회 종료 1" in out and last.startswith("❔ 판정: 조회하지 않음 — 같은 분의 파일이 이미 있다(set -C)") and "✅" not in out
      and rfile("20261201-1000") == first, out)
rc, out, last = block("20261201-1000", fail=True)
check("F3-2 블록 — 같은 분 + 조회 실패: ❔ 조회하지 않음 · ✅ 없음 · 기존 파일 그대로", rc != 0 and last.startswith("❔ 판정: 조회하지 않음") and "✅" not in out
      and rfile("20261201-1000") == first, out)
rc, out, last = block("20261201-1001", fail=True)
part = rfile("20261201-1001")
check("F3-2 블록 — 새 분 + 조회 실패(종료 2): '조회 종료 2' · ❔ 장부 조회 실패(파서 실행 안 함) · ✅ 없음 · 일부 출력 파일 보존",
      rc != 0 and "조회 종료 2" in out and last.startswith("❔ 판정: 장부 조회 실패(종료 2) — 판정하지 않는다(파서 실행 안 함)") and "✅" not in out
      and "== 장부 읽기" not in out and part and b"== L0" in part and "가짜 조회 실패".encode("utf-8") in part, out)
reset(); rt(pos_status="OPEN", sell=False, b_af="10000", b_rf="10040"); read_case("align2")
rc, out, last = block("20261201-1002")
check("F3-2 블록 — 보유 중 보고≠반영(리뷰 재현 2) → '조회 종료 0' · 🔴 정산 · 종료 1", rc == 1 and "조회 종료 0" in out
      and last.startswith("🔴 판정: 정산 — 보고≠반영 1건"), out)
shutil.rmtree(_tmp, ignore_errors=True)

# 8. v1.42 F6 중도 정지(리뷰 2026-11-01) — 문서의 F6 블록을 그대로 꺼내 로컬 대역으로 돌린다. 운영 세션·운영 API 에 닿지 않는다.
#    대역: PSQL = 시험 컨테이너 rt_ledger · curl = 가짜(호출을 기록하고 정해 둔 응답) · .env = 가짜 토큰 · HOME·M = 임시 폴더.
_f6 = _doc[_doc.index("**F6. "):]
F6_BLOCK = _f6[_f6.index("```bash\n") + len("```bash\n"):_f6.index("\n```\n", _f6.index("```bash\n")) + 1]
_f52 = _doc[_doc.index("**F5-2. "):_doc.index("**F5-3. ")]
F6_REASON = "REASON='§6-2 왕복 실측 비용 > 0.40%(포지션 2, cost_pct 0.40010000) — 로컬 대역'"
check("F6 — 블록에 56일 조건이 없고, F5-2 와 같은 정지 표지·정지 요청 줄을 쓴다",
      "56 days" not in F6_BLOCK and "56 days" in _f52
      and all(x in F6_BLOCK and x in _f52 for x in ('[ ! -e "$E/stop-sent" ] || stop "이미 정지 요청을 보냈다 — 다시 보내지 않는다. F5-3 확인부터"',
                                                    "date '+%F %T %Z' | tee \"$E/t-stop.txt\"; touch \"$E/stop-sent\"",
                                                    'curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/trading/sessions/$SID/stop | tee "$E/stop.json"; echo')),
      F6_BLOCK)


def f6_env():
    d = tempfile.mkdtemp(prefix="f6-")
    for sub in ("home", "bin", "work", "home/start-sid203"):
        os.makedirs(os.path.join(d, sub), exist_ok=True)
    open(os.path.join(d, "home", "start-sid203", "account-f1.json"), "w").write('{"f1":1}')
    open(os.path.join(d, "work", ".env"), "w", newline="\n").write("X=1\nAPI_AUTH_TOKEN=local-fake-token\n")
    basis = os.path.join(d, "home", "sid203-read-20261215-1003.txt")
    open(basis, "w", encoding="utf-8").write("🔴 판정: §6-2 중단 조건 — 로컬 대역\n")
    with open(os.path.join(d, "bin", "curl"), "w", encoding="utf-8", newline="\n") as f:
        f.write('#!/usr/bin/env bash\necho "$*" >> "$CURL_LOG"\n'
                'case "$*" in *"/stop"*) [ -n "$STOP_RC" ] && exit "$STOP_RC"; printf \'%s\' "$STOP_RESP" ;; *) printf \'{"krw":"1"}\' ;; esac\n')
    return d, basis.replace("\\", "/")


def f6(d, basis, sid="203", reason=F6_REASON, fill=True, stop_rc="", resp='{"status":"STOPPED"}'):
    blk = F6_BLOCK
    if fill:
        blk = blk.replace(blk[blk.index("REASON='<"):blk.index("\n", blk.index("REASON='<"))], reason)
        blk = blk.replace(blk[blk.index("BASIS='<"):blk.index("\n", blk.index("BASIS='<"))], "BASIS='%s'" % basis)
    u = lambda *x: os.path.join(d, *x).replace("\\", "/")
    env = dict(os.environ, HOME=u("home"), M=u("home", "start-sid203"), SID=sid, CURL_LOG=u("curl.log"), STOP_RC=stop_rc, STOP_RESP=resp,
               PSQL="docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d " + DB, PYTHONIOENCODING="utf-8")
    guard = 'export PATH="%s:$PATH"; [ "$(command -v curl)" = "%s/curl" ] || { echo "가짜 curl 이 아님: $(command -v curl)"; exit 97; }; cd "%s"\n' % (px(u("bin")), px(u("bin")), u("work"))
    r = subprocess.run([BASH, "-c", guard + blk], env=env, capture_output=True)
    out = r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    log = open(u("curl.log"), encoding="utf-8").read().splitlines() if os.path.exists(u("curl.log")) else []
    E = os.path.join(d, "home", "end-sid203")
    rd = lambda n: open(os.path.join(E, n), encoding="utf-8").read() if os.path.exists(os.path.join(E, n)) else None
    return r.returncode, out, log, rd


def status203():
    return sql(DB, "SELECT status FROM live_trading_session WHERE id = 203;", at=True).strip()


reset(started="2026-10-28 08:50:00+09")                         # 시작 4일 뒤 — 56일 전
d, basis = f6_env()
rc, out, log, rd = f6(d, basis)
posts = [x for x in log if "-X POST" in x]
check("F6 — RUNNING · 시작 + 56일 전: 정지 요청 1회(POST …/sessions/203/stop) · 계좌 조회 1회 · stop-sent·t-stop·stop.json · 종료 0",
      rc == 0 and len(log) == 2 and len(posts) == 1 and posts[0].endswith("http://localhost:8080/api/v1/trading/sessions/203/stop")
      and "Bearer local-fake-token" in posts[0] and rd("stop-sent") == "" and rd("t-stop.txt") and rd("stop.json") == '{"status":"STOPPED"}'
      and rd("account-f1.json") == '{"f1":1}', out + "\n" + "\n".join(log))
why = rd("midstop-reason.txt") or ""
check("F6 — 사유·시각·근거 기록: '== 중도 정지 판단 … KST · 56일 대기 조건 적용 안 함' · 사유 · 근거 경로 · 근거 sha256 · 조회 상태 RUNNING",
      "== 중도 정지 판단 " in why and " KST · 56일 대기 조건 적용 안 함" in why and "사유: §6-2 왕복 실측 비용 > 0.40%(포지션 2" in why
      and ("근거: " + basis) in why and basis.split("/")[-1] in why.split("\n")[3] and "조회 상태: SID 203 RUNNING" in why
      and "F6 정지 요청 보냄 " in why, why)   # v1.44: 실제로 보냈을 때만 이 줄(사람 판단 정지 기록 근거)
rc, out, log, rd = f6(d, basis)
check("F6 — 다시 붙이면: 🔴 멈춤 이미 정지 요청을 보냈다 · 새 호출 0(누적 POST 1) · 사유 기록에 덧붙이지 않음",
      rc == 1 and "🔴 멈춤: 이미 정지 요청을 보냈다" in out and sum("-X POST" in x for x in log) == 1 and len(log) == 2
      and rd("midstop-reason.txt") == why, out)
f52 = _f52[_f52.index("```bash\n") + len("```bash\n"):_f52.index("\n```\n", _f52.index("```bash\n")) + 1]
env52 = dict(os.environ, E=os.path.join(d, "home", "end-sid203").replace("\\", "/"), SID="203", CURL_LOG=os.path.join(d, "curl.log").replace("\\", "/"),
             PSQL="docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d " + DB)
r52 = subprocess.run([BASH, "-c", 'export PATH="%s:$PATH"; [ "$(command -v curl)" = "%s/curl" ] || exit 97; cd "%s"\n' % (px(d + "/bin"), px(d + "/bin"), (d + "/work").replace("\\", "/")) + f52],
                     env=env52, capture_output=True)
log = open(os.path.join(d, "curl.log"), encoding="utf-8").read().splitlines()
check("F6 뒤 56일 F5-2 블록(문서 그대로) — 같은 표지로 멈춤 · 새 호출 0", r52.returncode == 1 and "이미 정지 요청을 보냈다" in r52.stdout.decode("utf-8", "replace")
      and len(log) == 2, r52.stdout.decode("utf-8", "replace"))
for st in ("STOPPED", "EMERGENCY_STOPPED"):
    reset(status=st, stopped="2026-10-29 10:00:00+09")
    d, basis = f6_env()
    rc, out, log, rd = f6(d, basis)
    check("F6 — 이미 %s: 정지 요청 없음(curl 0) · stop-sent 없음 · '⛔ 이미 %s' 기록 · 종료 0 · DB 상태 %s 그대로" % (st, st, st),
          # ~~v1.42 기대 '⛔ 이미 %s — 정지 요청을 보내지 않는다(상태 보존)'~~ → v1.44: 'F6 정지 요청 없음' 을 남기고 '정지 요청 보냄' 줄은 없다
          rc == 0 and log == [] and rd("stop-sent") is None and ("⛔ 이미 %s — F6 정지 요청 없음(상태 보존)" % st) in (rd("midstop-reason.txt") or "")
          and "F6 정지 요청 보냄" not in rd("midstop-reason.txt")
          and ("조회 상태: SID 203 %s" % st) in rd("midstop-reason.txt") and status203() == st, out)
reset(status="CREATED", started=None)
d, basis = f6_env()
rc, out, log, rd = f6(d, basis)
check("F6 — CREATED: 🔴 멈춤 RUNNING 이 아님 · curl 0 · stop-sent 없음 · 상태 그대로", rc == 1 and "🔴 멈춤: SID 203 상태 'CREATED' — RUNNING 이 아님" in out
      and log == [] and rd("stop-sent") is None and status203() == "CREATED", out)
reset()
for what, kw, msg in (("사유·근거 <…> 그대로", dict(fill=False), "🔴 멈춤: 사유·근거의 <…> 를 채우지 않았다"),
                      ("사유 빈 값", dict(reason="REASON=''"), "🔴 멈춤: 사유가 비었다"),
                      ("SID 204", dict(sid="204"), "🔴 멈춤: SID 가 203 이 아님(204)")):
    d, basis = f6_env()
    rc, out, log, rd = f6(d, basis, **kw)
    check("F6 — %s: %s · curl 0 · stop-sent 없음" % (what, msg), rc == 1 and msg in out and log == [] and rd("stop-sent") is None, out)
d, basis = f6_env()
rc, out, log, rd = f6(d, basis[:-4] + "-없음.txt")
check("F6 — 근거 파일 없음: 🔴 멈춤 판정 근거 파일이 없다 · curl 0", rc == 1 and "🔴 멈춤: 판정 근거 파일이 없다" in out and log == [] and rd("stop-sent") is None, out)
d, basis = f6_env()
rc, out, log, rd = f6(d, basis, stop_rc="28", resp="")
check("F6 — 응답 불명확(정지 요청 curl 시간 초과 28): 표지는 남고 POST 1회 · 종료 0 아님", rc != 0 and rd("stop-sent") == "" and sum("-X POST" in x for x in log) == 1, out)
rc, out, log, rd = f6(d, basis)
check("F6 — 그 뒤 다시 붙여도 재요청 없음(조회부터) · 누적 POST 1", rc == 1 and "이미 정지 요청을 보냈다" in out and sum("-X POST" in x for x in log) == 1, out)
check("F6 — 대역 시험 동안 시험 DB SID 203 상태는 정지 요청으로 바뀌지 않음(가짜 curl) · RUNNING", status203() == "RUNNING")
shutil.rmtree(_blk, ignore_errors=True)

after = sql("crypto_auto_trader", 'SELECT status, available_krw FROM live_trading_session WHERE id = 203; SELECT count(*), sum(filled_quantity) FROM "order";', at=True)
check("끝 — 원본 시험 DB 그대로(STOPPED 20182.36 · 주문 2)", after.split() == ["STOPPED|20182.36", "2|0.00320000"], after)

n_ok = sum(1 for _, ok in results if ok)
print(f"sid_ledger 시험 {n_ok}/{len(results)} 통과")
sys.exit(0 if n_ok == len(results) else 1)
