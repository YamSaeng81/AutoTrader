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
PLAN = os.path.join(HERE, "..", "..", "docs", "LIMITED_LIVE_TEST_PLAN.md")
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
