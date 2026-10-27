"""전향 집계기 시험 (2026-10-27) — 가짜 데이터만. 실행: python scripts/forward_aggregate/test_aggregate.py

설계 docs/FORWARD_AGGREGATOR_DESIGN.md §9 의 F1~F29(F20 은 test_ledger_rounding.py). 가짜 장부는 ledger_rounding 의
엔진 모형(PaperTradingService 금액식 + PostgreSQL 반올림)으로 만들어 회계가 실제 엔진과 같게 맞는다.
🔴 운영 데이터를 쓰지 않는다.
"""
import contextlib
import copy
import csv
import io
import json
import os
import re
import shutil
import sys
from datetime import timedelta
from decimal import Decimal, ROUND_DOWN, ROUND_HALF_UP

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import aggregate as ag  # noqa: E402

results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)[:600]))


from fakedata import (ALL_BARS, CFG, COINS, SID, SIX, T0, WORK, Fake, at, bars_all,  # noqa: E402
                      fill_arms, no_trade_evidence, z)


def load(d, cfg=CFG, market=True):
    return ag.load(d, cfg, with_market=market)


def raises(fn, pattern=""):
    try:
        fn()
    except ag.InputError as e:
        return re.search(pattern, str(e)) is not None
    return False


def final(d, cfg=CFG):
    out = d + "_out"
    shutil.rmtree(out, ignore_errors=True)
    return ag.cmd_final(load(d, cfg), out), out


# ════════════════════════════════════════════════════════════════════════════
# F13 · 입력 검증 — 66세션 고정
f = Fake()
d = f.write("f13_base", SIX + timedelta(hours=1))
check("기본 가짜 입력은 읽힌다", load(d) is not None)
check("F13 세션 65개 → 계산 안 함", raises(lambda: load(f.write("f13a", SIX, lambda t: t["sessions"].pop())),
                                       "세션 집합"))
check("F13 세션 67개 → 계산 안 함", raises(lambda: load(f.write("f13b", SIX, lambda t: t["sessions"].append(
    dict(t["sessions"][0], id=999)))), "세션 집합"))


def swap_coin(t):
    t["sessions"][0]["coin_pair"] = "KRW-BTC"


check("F13 코인·팔 대응이 표와 다름 → 계산 안 함", raises(lambda: load(f.write("f13c", SIX, swap_coin)), "코인"))


def swap_arm(t):
    t["sessions"][0]["strategy_name"] = CFG["arms"]["A"] if t["sessions"][0]["strategy_name"] != CFG["arms"]["A"] \
        else CFG["arms"]["B"]


check("F13 전략(팔) 이 표와 다름 → 계산 안 함", raises(lambda: load(f.write("f13d", SIX, swap_arm)), "전략"))

# F12 · 주문·포지션 짝
f = Fake()
f.trip(SID[("KRW-NEAR", "A")], T0 + timedelta(hours=1), 1000, T0 + timedelta(hours=2), 1010)


def dup_sell(t):
    s = dict(next(o for o in t["orders"] if o["side"] == "SELL"), id=9999)
    t["orders"].append(s)


check("F12 포지션 하나에 매도 2건 → 계산 중단", raises(lambda: load(f.write("f12a", SIX, dup_sell)), "매도 2"))


def orphan(t):
    t["orders"].append(dict(t["orders"][0], id=9998, position_id=""))


check("F12 짝 없는 주문 → 계산 중단", raises(lambda: load(f.write("f12b", SIX, orphan)), "짝 포지션 없음"))


def before_t0(t):
    t["orders"][0]["filled_at"] = z(T0 - timedelta(minutes=5))


check("T0 이전 매수 → 계산 중단", raises(lambda: load(f.write("f12c", SIX, before_t0)), "T0 이전"))

# ════════════════════════════════════════════════════════════════════════════
# F2 · 왕복 1건 손계산 / 저장 realized_pnl 과 재계산 값의 구분
f = Fake()
sid = SID[("KRW-NEAR", "A")]
f.trip(sid, T0 + timedelta(hours=1), 1000, T0 + timedelta(hours=2), 1010)
d = f.write("f2", SIX)
data = load(d)
tr = data.trades[sid][0]
invest = Decimal("800000.00")
fee_b = invest * Decimal("0.0005")
px_b = Decimal("1001.00000000")
qty = ((invest - fee_b) / px_b).quantize(Decimal("1e-8"), rounding=ROUND_DOWN)
avg = (invest / qty).quantize(Decimal("1e-8"), rounding=ROUND_HALF_UP)
px_s = Decimal("1008.99000000")
proceeds = qty * px_s
realized_hand = proceeds - proceeds * Decimal("0.0005") - qty * avg
check("F2 재계산 실현손익 = 손계산 (양쪽 수수료·슬리피지 포함)", tr.realized() == realized_hand, (tr.realized(), realized_hand))
check("F2 거래당 순수익률 분모 = size × 매수 체결가", tr.entry_amount() == qty * px_b)
check("F2 verify 통과", ag.verify(data) == [], ag.verify(data))
# 리뷰 2차 — 저장 realized_pnl(NUMERIC(20,8) 반올림)과 재계산 값은 다른 값이다
diffs = []
f2 = Fake()
f2.bars("KRW-SC", ALL_BARS, SIX)
for k in range(40):
    f2.trip(SID[("KRW-SC", "B")], T0 + timedelta(hours=2 * k + 1), 1000 + k * 0.37,
            T0 + timedelta(hours=2 * k + 2), 1003 + k * 0.41)
data2 = load(f2.write("f2b", SIX))
for t in data2.trades[SID[("KRW-SC", "B")]]:
    diffs.append(t.position["realized_pnl"] - t.realized())
check("저장 realized_pnl ≠ 재계산 값인 사례가 있고, 차이 ≤ 0.000000005", any(x != 0 for x in diffs) and
      all(abs(x) <= Decimal("0.000000005") for x in diffs), [str(x) for x in diffs[:5]])
check("저장 realized_pnl 이 조금 어긋나도 verify 통과(상한 안)", ag.verify(data2) == [], ag.verify(data2))


def bump_pos_realized(t):
    p = next(p for p in t["positions"] if p["realized_pnl"])
    p["realized_pnl"] = str(Decimal(p["realized_pnl"]) + Decimal("0.00000002"))


check("저장 realized_pnl 이 상한(0.000000005) 넘게 어긋나면 verify 실패",
      any("realized_pnl" in e for e in ag.verify(load(f2.write("f2c", SIX, bump_pos_realized)))))

# ════════════════════════════════════════════════════════════════════════════
# F15 · 잔액 검산
f = Fake()
f.bars("KRW-ONG", ALL_BARS, SIX)
for k in range(30):
    f.trip(SID[("KRW-ONG", "OFF")], T0 + timedelta(hours=2 * k + 1), 1000 + k, T0 + timedelta(hours=2 * k + 2),
           999 + k)


def tamper(delta):
    def m(t):
        r = next(s for s in t["sessions"] if s["id"] == SID[("KRW-ONG", "OFF")])
        r["available_krw"] = str(Decimal(r["available_krw"]) + delta)
    return m


check("F15 잔액 검산 — 정상 장부 통과", ag.verify(load(f.write("f15a", SIX))) == [])
check("F15 잔액이 상한(0.005×30=0.15) 넘게 어긋나면 실패",
      any("available_krw" in e for e in ag.verify(load(f.write("f15b", SIX, tamper(Decimal("0.31")))))))
f0 = Fake()
d15c = f0.write("f15c", SIX, lambda t: t["sessions"][5].update(available_krw="1000000.01"))
check("F29·거래 0건 세션은 허용오차 0 — 0.01원만 어긋나도 실패",
      any("available_krw" in e for e in ag.verify(load(d15c))))

# F6·F7·F8 · 확인된 종료와 그 밖의 비 RUNNING
f = Fake()
d = f.write("f6", SIX)
check("F6 576~578 확인된 종료 → verify 통과", ag.verify(load(d)) == [], ag.verify(load(d)))
data = load(d)
pts = ag.equity_path(data, 577, SIX)
check("F6 BLAST 세션 자산은 정지 뒤에도 1,000,000 (가상 체결 없음)", all(e == Decimal("1000000") for _, _, e in pts)
      and len(pts) > 150)
f.status[SID[("KRW-CRO", "B")]] = "STOPPED"
f.stopped[SID[("KRW-CRO", "B")]] = T0 + timedelta(days=10)
check("F7 지정되지 않은 세션이 STOPPED → 측정 결측(판정 불가)",
      any("지정된 종료가 아니다" in e for e in ag.verify(load(f.write("f7", SIX)))))
f = Fake()


def late_order(t):
    t["positions"].append({"id": 777, "session_id": 576, "coin_pair": "KRW-BLAST", "side": "BUY",
                           "invested_krw": "800000.00", "size": "800", "entry_price": "1000", "avg_price": "1000",
                           "realized_pnl": "", "position_fee": "400.00", "status": "OPEN", "opened_at": "",
                           "closed_at": ""})
    t["orders"].append({"id": 7777, "session_id": 576, "position_id": 777, "coin_pair": "KRW-BLAST", "side": "BUY",
                        "order_type": "MARKET", "price": "1000", "quantity": "800", "filled_quantity": "800",
                        "state": "FILLED", "filled_at": z(at("2026-10-28T00:00:00"))})


errs = ag.verify(load(f.write("f8", SIX, late_order)))
check("F8 확인된 종료 세션에 정지 뒤 주문 → 회계 검증 실패", any("정지 뒤 주문" in e for e in errs), errs)

# ════════════════════════════════════════════════════════════════════════════
# F3·F4·F27·F28·F29·F9·F10·F11 · 일별 평가
coin = "KRW-POLYX"
sid = SID[(coin, "B")]
f = Fake()
b1 = at("2026-10-03T00:00:00")
f.bars(coin, ALL_BARS, SIX, lambda t: 1000 + (t - ALL_BARS).total_seconds() / 3600 * 0.5)
f.buy(sid, at("2026-10-02T10:00:00"), 1000)                      # d=10-02 매수, 10-04 매도 → 경계 두 개를 넘겨 보유
f.sell(sid, at("2026-10-04T09:00:00"), 1100)
data = load(f.write("f3", SIX))
tr = data.trades[sid][0]
bar = data.candles[coin][at("2026-10-02T23:00:00")]
cash_after_buy = Decimal("1000000") - tr.position["invested_krw"]
check("F3 경계 10-03 00:00Z: E = 현금 + 보유 × 10-02 23:00Z 봉 종가",
      ag.equity(data, sid, b1, False) == cash_after_buy + tr.position["size"] * bar)
e3 = ag.equity(data, sid, at("2026-10-04T00:00:00"), False)
check("F27 이전부터 보유한 무체결일(10-03): 가격 변화만큼 E 가 바뀐다 · 100만원 아님",
      e3 != ag.equity(data, sid, b1, False) and e3 != Decimal("1000000"))
e5 = ag.equity(data, sid, at("2026-10-06T00:00:00"), False)
cash5 = Decimal("1000000") - tr.position["invested_krw"] + tr.net_proceeds()
check("F28 실현손익 뒤 현금만 보유한 무체결일(10-05): E = 그 현금 (1,000,000 − 투자금 + 매도 순수령액) · 100만원 아님",
      e5 == cash5 and e5 != Decimal("1000000") and e5 == ag.equity(data, sid, at("2026-10-07T00:00:00"), False))
# 엔진 실현손익(순수령액 − 수량×평균단가)은 평균단가 8자리 반올림 때문에 현금 변화(순수령액 − 투자금)와 원 미만으로 다르다
gap = abs((tr.net_proceeds() - tr.position["invested_krw"]) - tr.realized())
check("현금 변화 vs 엔진 실현손익 차이 ≤ 수량 × 0.000000005 (평균단가 반올림) — 자산은 현금 기준",
      gap <= tr.position["size"] * Decimal("0.000000005"), gap)
check("F29 T0 부터 체결 0 세션: E = 1,000,000",
      ag.equity(data, SID[(coin, "A")], at("2026-11-01T00:00:00"), False) == Decimal("1000000"))
# F4 · 경계 직전·정각
f = Fake()
f.bars(coin, ALL_BARS, SIX)
f.buy(sid, at("2026-10-05T23:59:59.999999"), 1000)
f.sell(sid, at("2026-10-07T00:00:00"), 1000)
data = load(f.write("f4", SIX))
b = at("2026-10-06T00:00:00")
check("F4 23:59:59.999999Z 매수 → 10-05 에 귀속(경계 10-06 00:00Z 에 보유)",
      ag.ledger_at(data.trades[sid], b, False, Decimal(1000000))[1] != [])
b = at("2026-10-07T00:00:00")
check("F4 정각 00:00:00Z 매도 → 경계(<) 에서는 아직 보유, 다음 날로 귀속",
      ag.ledger_at(data.trades[sid], b, False, Decimal(1000000))[1] != [] and
      ag.ledger_at(data.trades[sid], b, True, Decimal(1000000))[1] == [])
# F9·F10·F11 · 봉 결측 — 무거래 예외는 확인 근거(조회 시각·요청·응답 원문)가 있어야만 (리뷰 4차)
f = Fake()
f.bars(coin, ALL_BARS, SIX)
f.buy(sid, at("2026-10-08T10:00:00"), 1000)
f.sell(sid, at("2026-10-09T10:00:00"), 1000)
missing = at("2026-10-08T23:00:00")
del f.candles[coin][missing]
SNAP = at("2026-10-12T00:00:00")
d10 = f.write("f10", SNAP)
data = load(d10)
check("F10 보유 중 봉 결측 + 확인 없음 → 평가에서 측정 결측",
      raises(lambda: ag.equity(data, sid, at("2026-10-09T00:00:00"), False), "측정 결측"))
errs = ag.verify(data)
check("F10 verify 가 측정 결측을 실패로 보고(final 전에)", any("측정 결측" in e and "2026-10-08T23:00" in e for e in errs),
      errs)
check("F10 verify 오류에 가격이 없다", not any(re.search(r"\b1000\b|close|종가 \d", e) for e in errs), errs)
f.no_trade.append((coin, missing))                        # 리뷰 재현 — 코인·시각 두 필드만
check("F9 근거 없는 예외 행(코인·시각만) → 계산 안 함", raises(lambda: load(f.write("f9a", SNAP)), "근거 필드 없음"))
f.no_trade[-1] = no_trade_evidence(coin, missing)
d9 = f.write("f9", SNAP)
data = load(d9)
check("F9 근거 갖춘 무거래 예외 → verify 통과", ag.verify(data) == [], ag.verify(data))
check("F9 직전 봉(22:00Z) 종가 이월",
      ag.equity(data, sid, at("2026-10-09T00:00:00"), False) ==
      ag.ledger_at(data.trades[sid], at("2026-10-09T00:00:00"), False, Decimal(1000000))[0]
      + data.trades[sid][0].position["size"] * data.candles[coin][at("2026-10-08T22:00:00")])
used = list(data.no_trade_used.values())
check("F9 사용한 예외가 기록된다(조회 시각·요청·응답 해시)", len(used) == 1 and used[0]["time"] == z(missing) and
      len(used[0]["response_sha256"]) == 64 and used[0]["checked_at"] and used[0]["request"], used)
h10, h9 = load(d10).hashes, data.hashes
check("입력 해시에 예외 파일·고정 설정이 들어간다(예외 파일을 더하면 해시 목록이 바뀐다)",
      "no_trade_bars.csv" in h9 and "no_trade_bars.csv" not in h10 and "forward_config.json" in h9 and h9 != h10,
      (sorted(h10), sorted(h9)))
for label, ev, pat in [
        ("조회 시각이 봉 마감 전", no_trade_evidence(coin, missing, checked_at=missing + timedelta(minutes=30)), "마감 전"),
        ("응답 상태 404", no_trade_evidence(coin, missing, status="404"), "200"),
        ("응답에 그 봉이 있음", no_trade_evidence(coin, missing, include_bar=True), "그 봉이 있다"),
        ("응답이 뒤 봉을 담지 않음", no_trade_evidence(coin, missing, around=(2, 0)), "앞·뒤"),
        ("다른 마켓의 응답", no_trade_evidence(coin, missing, market="KRW-BTC"), "캔들 목록이 아니다"),
        ("H1 캔들 조회가 아닌 요청", no_trade_evidence(coin, missing, request="https://api.upbit.com/v1/ticker"),
         "H1 캔들"),
        ("응답 원문이 JSON 아님", dict(no_trade_evidence(coin, missing), response_body="<html>"), "캔들 목록이 아니다"),
        # 리뷰 5차 재현 — 요청 market 은 다른 마켓인데 다른 매개변수에 대상 코인 이름만 넣은 경우
        ("요청 market 이 다른 마켓(note 에 대상 코인)", no_trade_evidence(
            coin, missing, request=f"https://api.upbit.com/v1/candles/minutes/60?market=KRW-BTC&note={coin}&count=4"),
         "H1 캔들"),
        ("요청 market 이 두 번", no_trade_evidence(
            coin, missing, request=f"https://api.upbit.com/v1/candles/minutes/60?market={coin}&market=KRW-BTC"), "H1 캔들"),
        ("요청 경로가 H1 이 아님(minutes/600)", no_trade_evidence(
            coin, missing, request=f"https://api.upbit.com/v1/candles/minutes/600?market={coin}"), "H1 캔들"),
        ("요청 경로 뒤에 덧붙임", no_trade_evidence(
            coin, missing, request=f"https://api.upbit.com/v1/candles/minutes/60/x?market={coin}"), "H1 캔들"),
        ("요청 호스트가 업비트 아님", no_trade_evidence(
            coin, missing, request=f"https://api.upbit.com.evil.example/v1/candles/minutes/60?market={coin}"), "H1 캔들"),
        ("요청이 https 아님", no_trade_evidence(
            coin, missing, request=f"http://api.upbit.com/v1/candles/minutes/60?market={coin}"), "H1 캔들"),
        ("요청에 모르는 매개변수", no_trade_evidence(
            coin, missing, request=f"https://api.upbit.com/v1/candles/minutes/60?market={coin}&note=x"), "H1 캔들")]:
    f.no_trade[-1] = ev
    check(f"F9 근거 결함 — {label} → 계산 안 함", raises(lambda: load(f.write("f9x", SNAP)), pat))
f.no_trade.clear()
f = Fake()
f.bars(coin, ALL_BARS, SIX)
del f.candles[coin][at("2026-10-20T23:00:00")]
data = load(f.write("f11", SIX))
check("F11 보유 0 인 날 봉 결측은 결측이 아니다", ag.equity(data, sid, at("2026-10-21T00:00:00"), False)
      == Decimal("1000000") and ag.verify(data) == [])
# 종료 전 verify 의 적재 여유 — 스냅샷 직전 1시간 안의 경계는 아직 보지 않고, T_end 를 주면 끝까지 본다
f = Fake()
f.bars(coin, ALL_BARS, SIX)
f.buy(sid, at("2026-10-14T20:00:00"), 1000)
del f.candles[coin][at("2026-10-14T23:00:00")]
data = load(f.write("lag", at("2026-10-15T00:30:00")))
check("종료 전 verify: 스냅샷 30분 전 경계의 막 마감된 봉은 아직 결측으로 보지 않는다", ag.verify(data) == [],
      ag.verify(data))
check("T_end 를 주면 그 경계까지 검사해 결측을 잡는다", any("측정 결측" in e for e in ag.verify(data, at("2026-10-15T00:20:00"))))
# 리뷰 5차 — 검사 범위와 보류한 최근 경계를 출력한다
ag.verify(data)
sc = data.mark_scope
check("예비 verify 범위: 스냅샷 − 1시간까지, 끝점 검사 없음, 보류 경계 = 보유 세션의 10-15 00:00Z",
      sc["checked_until"] == z(at("2026-10-14T23:30:00")) and sc["end_point_checked"] is False and sc["grace"] and
      sc["deferred_boundaries"] == [(sid, z(at("2026-10-15T00:00:00")))], sc)
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    rc = ag.main(["verify", os.path.join(WORK, "lag")])
vout = buf.getvalue()
check("verify 출력에 검사 종료 시각·유예·보류 경계가 나온다",
      rc == 0 and "2026-10-14T23:30:00" in vout and "예비" in vout and "보류된 최근 경계 1건" in vout and
      f"세션 {sid} 2026-10-15T00:00:00" in vout and "verify 통과" in vout, vout)
ag.verify(data, at("2026-10-15T00:20:00"))
check("명시적 T_end 에는 유예 없음 — 범위 = T_end·끝점 포함·보류 0",
      data.mark_scope["grace"] is None and data.mark_scope["end_point_checked"] is True and
      data.mark_scope["deferred_boundaries"] == [] and "유예 없음" in ag.describe_scope(data.mark_scope),
      data.mark_scope)
gl = Fake()                                            # final 은 유예 없이 끝점 봉까지 — 최근 봉 결측을 거절한다
gl.bars(coin, ALL_BARS, SIX + timedelta(days=1))
gl.buy(sid, SIX - timedelta(hours=5), 1000)
del gl.candles[coin][SIX.replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)]   # 끝점 평가 봉(05:00Z)
glast = gl.write("grace_final", SIX + timedelta(minutes=10))
gdata = load(glast)
check("같은 입력의 예비 verify 는 통과(끝점은 종료 전 검사 대상 아님)", ag.verify(gdata) == [], ag.verify(gdata))
check("final 은 끝점의 최근 봉 결측을 거절(유예 미적용)", raises(lambda: final(glast), "측정 결측"))

# ════════════════════════════════════════════════════════════════════════════
# F19 · 건수 전용 모드 — 필요한 필드만 읽는다(리뷰 4차)
f = Fake()
fill_arms(f, 5, T0 + timedelta(hours=1))
d = f.write("f19", T0 + timedelta(days=5))
out = ag.cmd_count(ag.load_counts(d, CFG))
flat = json.dumps({k: v for k, v in out.items() if k != "inputs_sha256"}, ensure_ascii=False)
check("F19 count 출력 키는 건수·도달 여부·시각·입력 해시뿐", set(out) == {"snapshot", "closed_round_trips", "reached_200",
                                                              "reached_100", "six_months_at", "inputs_sha256"}, out)
check("F19 count 출력에 손익·자산·수익률 관련 값 없음",
      not re.search(r"pnl|equity|krw|return|profit|realized|price|available|수익|손익|자산", flat, re.I), flat)
check("F19 팔별 왕복 5", out["closed_round_trips"] == {"OFF": 5, "B": 5, "A": 5}, out)
os.remove(os.path.join(d, "candles.csv"))
check("F19 count 는 캔들 없이 돈다(가격을 읽지 않는다)", ag.cmd_count(ag.load_counts(d, CFG))["closed_round_trips"]
      == out["closed_round_trips"])
SECRET = "777777.77"                                     # 금액·가격 칸을 모두 이 값으로 — 읽거나 출력하면 드러난다


def poison(t):
    for name, cols in (("sessions", ("initial_capital", "available_krw", "realized_pnl", "total_fee")),
                       ("positions", ("invested_krw", "size", "entry_price", "avg_price", "realized_pnl", "position_fee")),
                       ("orders", ("price", "quantity", "filled_quantity"))):
        for r in t[name]:
            for c in cols:
                r[c] = SECRET if name != "orders" or c != "price" else "NOT_A_NUMBER"


dp = Fake()
fill_arms(dp, 3, T0 + timedelta(hours=1))
dpd = dp.write("f19p", T0 + timedelta(days=5), poison)
calls = {"dec": 0, "Decimal": 0}
real_dec, real_D = ag.dec, ag.Decimal


def counting_dec(s):
    calls["dec"] += 1
    return real_dec(s)


class CountingDecimal(Decimal):
    def __new__(cls, *a, **k):
        calls["Decimal"] += 1
        return real_D.__new__(real_D, *a, **k)


ag.dec, ag.Decimal = counting_dec, CountingDecimal
try:
    pout = ag.cmd_count(ag.load_counts(dpd, CFG))
except Exception as e:                    # 금액 칸을 변환하려 들면 'NOT_A_NUMBER' 에서 터진다 — 실패로 센다
    pout = {"closed_round_trips": None, "error": repr(e)}
finally:
    ag.dec, ag.Decimal = real_dec, real_D
check("count 경로는 금액을 변환하지 않는다(dec·Decimal 호출 0)", calls == {"dec": 0, "Decimal": 0}, calls)
check("금액 칸이 숫자가 아니어도 count 는 돈다(읽지 않으므로)", pout["closed_round_trips"] == {"OFF": 3, "B": 3, "A": 3}, pout)
check("count 출력에 금액 값이 없다", SECRET not in json.dumps(pout), pout)


def count_error_output(mutate):
    dd = dp.write("f19e", T0 + timedelta(days=5), lambda t: (poison(t), mutate(t)))
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            rc = ag.main(["count", dd])
    except Exception as e:                # InputError 가 아닌 예외로 죽으면 그 자체가 실패다
        return -1, buf.getvalue() + repr(e)
    return rc, buf.getvalue()


def two_sells(t):
    t["orders"].append(dict(next(o for o in t["orders"] if o["side"] == "SELL"), id=99999))


def bad_pair(t):
    t["orders"][0]["position_id"] = "424242"


def bad_session(t):
    t["sessions"][0]["strategy_name"] = "X"


def before_t0_round_trip(t):                     # T0 이전에 끝난 왕복 — 매수·매도 둘 다 T0 앞
    b = next(o for o in t["orders"] if o["side"] == "BUY")
    s = next(o for o in t["orders"] if o["side"] == "SELL" and o["position_id"] == b["position_id"])
    b["filled_at"] = z(T0 - timedelta(hours=3))
    s["filled_at"] = z(T0 - timedelta(hours=2))


check("count — T0 이전에 끝난 왕복은 입력 오류로 거절(1건으로 세지 않는다)",
      raises(lambda: ag.load_counts(dp.write("f19t0", T0 + timedelta(days=5), before_t0_round_trip), CFG), "T0 이전 매수"))

for label, m in (("매도 2건", two_sells), ("짝 없는 주문", bad_pair), ("세션 대응 불일치", bad_session),
                 ("T0 이전 매수", before_t0_round_trip)):
    rc, text = count_error_output(m)
    check(f"count 오류({label}) — 실패하고 출력에 가격·금액이 없다",
          rc == 1 and "계산하지 않는다" in text and SECRET not in text and "NOT_A_NUMBER" not in text and
          not re.search(r"가격|매수가|price|entry|krw|손익|pnl", text, re.I), text)

# ── 종료 전에는 final 을 거부 — 중간 성과 계산 금지 ──
check("종료 전 스냅샷 → final 거부", raises(lambda: final(f.write("early", T0 + timedelta(days=5))), "종료 전"))

# ════════════════════════════════════════════════════════════════════════════
# F16 · 종료 세 경우
f = Fake()
bars_all(f, SIX + timedelta(days=1))
fill_arms(f, 99, T0 + timedelta(hours=1), step=timedelta(hours=36))
f.trip(SID[("KRW-IOTA", "OFF")], SIX - timedelta(days=2), 1000, SIX - timedelta(days=1), 1000)
f.trip(SID[("KRW-IOTA", "B")], SIX - timedelta(days=2), 1000, SIX - timedelta(days=1), 1000)
res, _ = final(f.write("f16a", SIX + timedelta(hours=1)))
check("F16 6개월 시점 한 팔(A) 99건 → 판정 불가, 성과 계산 안 함",
      res["sample"] == "판정 불가" and "verdict_a" not in res and res["round_trips"]["A"] == 99, res)

f = Fake()
bars_all(f, SIX + timedelta(days=1))
fill_arms(f, 120, T0 + timedelta(hours=1), step=timedelta(hours=30))
res, out_dir = final(f.write("f16b", SIX + timedelta(hours=1)))
check("F16 모든 팔 100~199 → 6개월 종료·표본 부족 명시 판정", res["end_reason"] == "6개월 경과" and
      res["sample"] == "표본 부족 명시" and "verdict_a" in res, res.get("sample"))
check("F1·(가) 세 팔 손익 같음 → A−B 평균 0 → 근거 없음", res["verdict_a"] == "근거 없음", res["verdict_a"])
rows = list(csv.DictReader(open(os.path.join(out_dir, "equity_daily.csv"), encoding="utf-8")))
check("F5 일별 점 = T0 부분일 + 경계 + T_end — 세션당 183개(T0 날짜부터 T_end 날짜까지)",
      sum(1 for r in rows if r["session_id"] == "522") == 183)
check("F5 첫 점 라벨 = T0 날짜, 마지막 점 시각 = T_end",
      [r for r in rows if r["session_id"] == "522"][0]["day"] == "2026-10-01" and
      [r for r in rows if r["session_id"] == "522"][-1]["at"] == z(SIX))
check("블록 수 = 7 (183일 / 30)", res["bootstrap_main"]["K"] == 7, res["bootstrap_main"])

# F22 · 200번째 청산 = T_end, 같은 시각 다른 체결 포함 / F17·F23 · T_end 에 열린 포지션
f = Fake()
bars_all(f, SIX + timedelta(days=1))
t = fill_arms(f, 199, T0 + timedelta(hours=1), step=timedelta(hours=7))
f.trip(SID[("KRW-IOTA", "OFF")], t, 1000, t + timedelta(hours=1), 1000)
f.trip(SID[("KRW-IOTA", "B")], t, 1000, t + timedelta(hours=1), 1000)
t_end = t + timedelta(hours=5)
f.buy(SID[("KRW-IOTA", "A")], t_end - timedelta(hours=1), 1000)
i_last = f.sell(SID[("KRW-IOTA", "A")], t_end, 1000)                 # A 의 200번째 — 가장 늦은 팔
f.trip(SID[("KRW-SC", "OFF")], t_end - timedelta(hours=2), 1000, t_end, 1020)    # 같은 시각 다른 체결
f.buy(SID[("KRW-WAVES", "B")], t_end - timedelta(hours=3), 1000)                 # T_end 에 열린 포지션
f.sell(SID[("KRW-WAVES", "B")], t_end + timedelta(hours=2), 1050)                # 스냅샷엔 CLOSED, 매도는 T_end 뒤
res, out_dir = final(f.write("f22", t_end + timedelta(days=1)))
check("F22 T_end = 가장 늦은 팔의 200번째 청산 filled_at", res["t_end"] == z(t_end) and
      res["end_reason"] == "200거래 공통 시점", res.get("t_end"))
check("F22 그 청산(A 200번째)과 같은 시각 다른 체결(SC OFF) 모두 포함(≤)",
      res["round_trips"]["A"] == 200 and res["round_trips"]["OFF"] == 201, res["round_trips"])
check("F23 스냅샷엔 CLOSED 지만 매도가 T_end 뒤 → 거래 수 제외(B=200)", res["round_trips"]["B"] == 200,
      res["round_trips"])
eqr = [r for r in csv.DictReader(open(os.path.join(out_dir, "equity_daily.csv"), encoding="utf-8"))
       if r["session_id"] == str(SID[("KRW-WAVES", "B")])][-1]
data = load(f.write("f22", t_end + timedelta(days=1)))
wt = [x for x in data.trades[SID[("KRW-WAVES", "B")]] if x.sell_at > t_end][0]
check("F17·F23 T_end 에 보유로 평가(미실현 포함)",
      Decimal(eqr["equity"]) == ag.ledger_at(data.trades[SID[("KRW-WAVES", "B")]], t_end, True, Decimal(1000000))[0]
      + wt.position["size"] * data.candles["KRW-WAVES"][t_end.replace(minute=0, second=0, microsecond=0)
                                                       - timedelta(hours=1)])


def bad_t_end(c):
    c2 = copy.deepcopy(c)
    c2["t_end"] = t_end + timedelta(seconds=1)
    return c2


check("설정 t_end 가 계산한 종료와 다르면 거부",
      raises(lambda: ag.cmd_final(load(f.write("f22", t_end + timedelta(days=1)), bad_t_end(CFG)), WORK + "/x"),
             "t_end"))

# ════════════════════════════════════════════════════════════════════════════
# F24·F25 · (나) 표시와 거래당 N
f = Fake()
bars_all(f, SIX + timedelta(days=1))
fill_arms(f, 200, T0 + timedelta(hours=1), step=timedelta(hours=7),
          profit={"OFF": 1.0, "B": 1.0, "A": 1.02})
res, out_dir = final(f.write("f24a", SIX + timedelta(hours=1)))
check("F24 주 비용 네 항목 충족 + 스트레스 NA → (나) '미확정 유지 · 실전 진입 불가'",
      res["verdict_b"].startswith("미확정 유지 · 실전 진입 불가") and res["stress"] == "NA", res.get("verdict_b"))
check("(가) A 우세(21/22 코인 개선, BLAST 는 0) → 개선 21", res["improved"] == 21, res["improved"])
check("F25 거래 있는 코인 N = 21/22 (BLAST 거래 없음 — 0 을 만들지 않는다)", res["a_per_trade_n"] == 21, res)
rep = open(os.path.join(out_dir, "report.txt"), encoding="utf-8").read()
check("F25 보고에 N/22 와 코인별 왕복 수 표시", "N=21/22" in rep and "'KRW-BLAST': 0" in rep)
check("F24 보고에 '충족' 이라는 (나) 판정이 없다", "(나) 충족" not in rep)
f = Fake()
bars_all(f, SIX + timedelta(days=1))
fill_arms(f, 200, T0 + timedelta(hours=1), step=timedelta(hours=7),
          profit={"OFF": 1.0, "B": 1.0, "A": 0.99})
res, _ = final(f.write("f24b", SIX + timedelta(hours=1)))
check("F24 주 비용 항목 미달 → (나) 미충족", res["verdict_b"] == "미충족", res.get("verdict_b"))
# F25 · 거래 있는 코인 5개
f = Fake()
bars_all(f, SIX + timedelta(days=1))
fill_arms(f, 100, T0 + timedelta(hours=1), step=timedelta(hours=40))
data = load(f.write("f25", SIX + timedelta(hours=1)))


KEEP5 = [c for c in COINS if c != "KRW-BLAST"][:5]


def a_only_5(t):
    a_ids = {SID[(c, "A")] for c in COINS if c not in KEEP5}
    drop = {p["id"] for p in t["positions"] if p["session_id"] in a_ids}
    t["positions"] = [p for p in t["positions"] if p["id"] not in drop]
    t["orders"] = [o for o in t["orders"] if o["position_id"] not in drop]
    for s in t["sessions"]:
        if s["id"] in a_ids:
            s.update(available_krw="1000000.00", realized_pnl="0.00", total_fee="0.00")
    # A 는 5코인 × 20 = 100 건이 되도록 남은 5코인 거래 수를 보충하지 않는다 — 대신 A 왕복 수를 확인한다


d25 = f.write("f25b", SIX + timedelta(hours=1), a_only_5)
data = load(d25)
n_a = len(ag.closed_trips(data, "A"))
check("F25 준비: A 는 5코인만 거래", len({s for _, _, s in ag.closed_trips(data, "A")}) == 5, n_a)
# A 가 5코인 25건뿐이면 종료 판정은 표본 미달이다 — 거래당 계산은 본체 함수(round_trip_returns·per_trade_stats)로 직접 본다
per_coin, _ = ag.round_trip_returns(data, SIX)
avg5, n5, cnt5 = ag.per_trade_stats(per_coin, "A", COINS)
manual = sum(sum(per_coin[(c, "A")]) / len(per_coin[(c, "A")]) for c in KEEP5) / 5
check("F25 거래당 평균의 분모 = 거래 있는 코인 5, 0 코인은 평균에 넣지 않음", n5 == 5 and avg5 == manual and
      sum(1 for v in cnt5.values() if v == 0) == 17, (n5, cnt5))
none_avg, none_n, _ = ag.per_trade_stats({}, "A", COINS)
check("F25 N=0 → NA(None)", none_avg is None and none_n == 0)

# F14 · 주문 0 + 신호 기록 드묾 → 측정 가능, 판정 불가 아님
f = Fake()
f.signal = [{"session_id": 530, "day": "2026-10-02", "n": 1}]
d = f.write("f14", SIX)
check("F14 주문 0 + 신호 기록 드묾 → verify 통과(운용 공백은 보고만)", ag.verify(load(d)) == [])

# F18 · 부트스트랩 — 역사적 분석기와 같은 값
sys.path.insert(0, os.path.join(ROOT, "scripts", "supertrend_filter"))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import validation_analysis as va  # noqa: E402

rng = np.random.default_rng(7)
dates = pd.date_range("2026-10-01", periods=95, freq="D")
coins5 = ["KRW-C1", "KRW-C2", "KRW-C3", "KRW-C4", "KRW-C5"]
rows = []
series = {"A": {}, "B": {}}
for c in coins5:
    for arm in ("A", "B"):
        r = rng.normal(0, 1, len(dates))
        series[arm][c] = list(r)
        for dt, v in zip(dates, r):
            rows.append({"coin": c, "arm": arm, "date": dt, "dret": v})
hist = va.block_bootstrap(pd.DataFrame(rows), coins5, 30, np.random.default_rng(ag.SEED))
mine = ag.block_bootstrap(list(dates.date), series["A"], series["B"], coins5, 30, np.random.default_rng(ag.SEED))
check("F18 부트스트랩 θ·구간·p·블록 수 = 역사적 분석기(validation_analysis.block_bootstrap)",
      all(abs(hist[k] - mine[k]) < 1e-12 for k in ("theta", "lo", "hi", "p")) and hist["K"] == mine["K"],
      (hist, mine))

# F26 · 추출 SQL 정적 검사
sql = open(os.path.join(HERE, "extract.sql"), encoding="utf-8").read()
stmts = [s for s in re.sub(r"--[^\n]*", "", sql).split("\n") if s.strip()]
first_sql = next(s for s in stmts if not s.strip().startswith("\\"))
check("F26 첫 SQL 문장 = REPEATABLE READ READ ONLY 트랜잭션",
      re.match(r"\s*BEGIN\s+TRANSACTION\s+ISOLATION\s+LEVEL\s+REPEATABLE\s+READ\s+READ\s+ONLY\s*;", first_sql, re.I),
      first_sql)
check("F26 마지막 문장 = ROLLBACK", stmts[-1].strip().upper() == "ROLLBACK;", stmts[-1])
body = re.sub(r"--[^\n]*", "", sql)
check("F26 쓰기 문장 없음", not re.search(r"\b(INSERT|UPDATE|DELETE|TRUNCATE|ALTER|DROP|CREATE|GRANT|COMMIT)\b",
                                     re.sub(r"\\copy[^\n]*", "", body), re.I))
check("F26 모든 \\copy 가 트랜잭션 안(BEGIN 뒤·ROLLBACK 앞)",
      all(body.find("BEGIN") < m.start() < body.rfind("ROLLBACK") for m in re.finditer(r"\\copy", body)))

shutil.rmtree(WORK, ignore_errors=True)
print(f"\n{sum(results)}/{len(results)} 통과")
sys.exit(0 if all(results) else 1)
