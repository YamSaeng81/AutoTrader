"""후보 B 집계 경로 시험 (2026-10-28) — 가짜 데이터만. 실행: python scripts/forward_aggregate/test_b_aggregate.py

docs/B_VALIDATION_PREREG.md §5·§5-1·§6·§7 ⓔ. 가짜 장부는 fakedata.Fake 에 B 설정·팔별 세션 슬리피지를 넣어 만든다
(ledger_rounding 엔진 모형 — MAIN 0.1%, STRESS 0.2%). 🔴 운영 데이터를 쓰지 않는다. 66세션 경로 시험은 test_aggregate.py 그대로다.
"""
import copy
import json
import os
import re
import shutil
import sys
from datetime import timedelta
from decimal import Decimal

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import aggregate as ag  # noqa: E402
import b_aggregate as bg  # noqa: E402
from fakedata import WORK, Fake, at, z  # noqa: E402

results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)[:600]))


def raises(fn, pattern=""):
    try:
        fn()
    except ag.InputError as e:
        return re.search(pattern, str(e)) is not None
    return False


T0 = at("2027-01-04T03:00:00.250000")
SIX = at("2027-07-04T03:00:00.250000")
COINS = list(bg.COINS)
SLIP = {"MAIN": "0.001000", "STRESS": "0.002000"}       # DB NUMERIC(7,6) 저장 모양


def raw_cfg(**over):
    sessions, sid = [], 1001
    for c in COINS:
        for arm in ("MAIN", "STRESS"):
            sessions.append({"id": sid, "coin": c, "arm": arm})
            sid += 1
    cfg = {"strategy": bg.STRATEGY, "timeframe": "H1", "t0": z(T0), "six_months_at": z(SIX),
           "initial_capital": "1000000", "confirmed_ends": [], "t_end": None, "sessions": sessions}
    cfg.update(over)
    return cfg


def cfg_file(raw, name="b_config.json"):
    p = os.path.join(WORK, name)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(raw, f, ensure_ascii=False)
    return p


def load_cfg(**over):
    return bg.load_config(cfg_file(raw_cfg(**over)))


CFG = load_cfg()
SID = {(s["coin"], s["arm"]): s["id"] for s in CFG["sessions"]}
BARS_FROM = (T0 - timedelta(days=2)).replace(minute=0, second=0, microsecond=0)


def fake(cfg=CFG):
    return Fake(cfg, SLIP)


def bars(f, end, skip=()):
    for c in COINS:
        if c not in skip:
            f.bars(c, BARS_FROM, end)


def fill(f, per_arm, start, profit=lambda coin, arm: 1.0, arms=("MAIN", "STRESS"), skip=(), step=timedelta(hours=3)):
    """팔마다 왕복 per_arm 건 — 코인을 돌아가며, 두 팔이 같은 시각·같은 신호가로 매매한다(슬리피지만 다름)."""
    coins = [c for c in COINS if c not in skip]
    t = start
    for k in range(per_arm):
        coin = coins[k % len(coins)]
        for arm in arms:
            f.trip(SID[(coin, arm)], t + timedelta(minutes=1), 1000, t + timedelta(hours=1, minutes=1),
                   1000 * profit(coin, arm))
        t += step
    return t


def final(d, cfg=CFG):
    out = d + "_out"
    shutil.rmtree(out, ignore_errors=True)
    bg.check_slippage(d, cfg)
    return bg.cmd_final(ag.load(d, cfg), out), out


# ════════════════════════════════════════════════════════════════════════════
# B1 · 설정 — 42세션·21코인(§2 집합 그대로)·코인마다 MAIN·STRESS
check("B1 기본 설정은 읽힌다(42세션·21코인)", len(CFG["sessions"]) == 42)
check("B1 코인 목록 = 전향 66세션 22코인 − BLAST",
      set(COINS) == {s["coin"] for s in ag.load_config(os.path.join(HERE, "forward_config.json"))["sessions"]} - {"KRW-BLAST"})
r = raw_cfg()
r["sessions"] = r["sessions"][:-1]
check("B1 세션 41개 → 거절", raises(lambda: bg.load_config(cfg_file(r)), "42개"))
r = raw_cfg()
for s in r["sessions"]:
    if s["coin"] == "KRW-NEAR":
        s["coin"] = "KRW-BLAST"
check("B1 코인 교체(NEAR → BLAST) → 거절", raises(lambda: bg.load_config(cfg_file(r)), r"빠짐 \['KRW-NEAR'\].*남음 \['KRW-BLAST'\]"))
r = raw_cfg()
r["sessions"][1]["arm"] = "MAIN"
check("B1 한 코인에 MAIN 두 개 → 거절", raises(lambda: bg.load_config(cfg_file(r)), "MAIN·STRESS 하나씩"))
check("B1 전략이 다르면 거절", raises(lambda: bg.load_config(cfg_file(raw_cfg(strategy="COMPOSITE_MTF_MOMENTUM_CLOSED"))), "전략"))
check("B1 6개월 시점이 T0 + 6개월이 아니면 거절",
      raises(lambda: bg.load_config(cfg_file(raw_cfg(six_months_at=z(SIX + timedelta(days=1))))), "T0 \\+ 6개월"))
one_arm = [{"id": SID[("KRW-NEAR", "MAIN")], "stopped_at": z(T0 + timedelta(days=3)), "cash": "1000000", "basis": "시험"}]
check("B1 §5-1 ① 확인된 종료가 한 팔만 → 거절",
      raises(lambda: bg.load_config(cfg_file(raw_cfg(confirmed_ends=one_arm))), "한 팔만"))

# ════════════════════════════════════════════════════════════════════════════
# B2 · 세션 슬리피지 저장값 — 두 팔을 가르는 유일한 설정
f = fake()
bars(f, T0 + timedelta(days=1))
end = fill(f, 3, T0 + timedelta(hours=1))
d = f.write("b2_base", end)
try:
    bg.check_slippage(d, CFG)
    ok = True
except ag.InputError as e:
    ok = e
check("B2 MAIN 0.001000 · STRESS 0.002000 저장값은 통과", ok is True, ok)


def set_slip(sid, val):
    def m(t):
        for row in t["sessions"]:
            if row["id"] == sid:
                row["slippage_rate"] = val
    return m


stress_sid = SID[("KRW-ANKR", "STRESS")]
check("B2 STRESS 세션에 0.001 → 거절",
      raises(lambda: bg.check_slippage(f.write("b2a", end, set_slip(stress_sid, "0.001000")), CFG), f"세션 {stress_sid}.*STRESS"))
check("B2 저장값 NULL → 거절(종전 상수로 돌았는지 B 세션인지 구별할 수 없다)",
      raises(lambda: bg.check_slippage(f.write("b2b", end, set_slip(SID[("KRW-ANKR", "MAIN")], "")), CFG), "NULL"))


def drop_col(t):
    for row in t["sessions"]:
        row.pop("slippage_rate")


check("B2 slippage_rate 열 없음(66세션용 추출) → 거절", raises(lambda: bg.check_slippage(f.write("b2c", end, drop_col), CFG), "열이 없다"))
data = ag.load(d, CFG)
buy_px = {arm: data.trades[SID[("KRW-ANKR", arm)]][0].buy["price"] for arm in ("MAIN", "STRESS")}
check("B2 가짜 장부 체결가 — 같은 신호가 1000 에서 MAIN 1001 · STRESS 1002",
      buy_px == {"MAIN": Decimal("1001.00000000"), "STRESS": Decimal("1002.00000000")}, buy_px)
check("B2 기존 66세션 장부 모형은 그대로(세션값 없음 = 0.1%)",
      Fake().led[522].slippage == Decimal("0.001"))

# ════════════════════════════════════════════════════════════════════════════
# B3 · count — 팔별 청산 왕복 수만
f = fake()
bars(f, T0 + timedelta(days=20))
t = fill(f, 50, T0 + timedelta(hours=1))
t = fill(f, 20, t, arms=("MAIN",))
f.buy(SID[("KRW-CRO", "STRESS")], t + timedelta(minutes=1), 1000)        # 미청산 — 세지 않는다
d = f.write("b3", t + timedelta(hours=2))
c = bg.cmd_count(ag.load_counts(d, CFG))
check("B3 팔별 청산 왕복 MAIN 70 · STRESS 50 (미청산 제외)", c["closed_round_trips"] == {"MAIN": 70, "STRESS": 50}, c)
check("B3 도달 표시와 6개월 시점만 — 손익 없음",
      set(c) == {"snapshot", "closed_round_trips", "reached_200", "reached_100", "six_months_at", "inputs_sha256"}
      and c["reached_100"] == {"MAIN": False, "STRESS": False})

# ════════════════════════════════════════════════════════════════════════════
# B4 · §5 두 팔 공동 종료
f = fake()
bars(f, T0 + timedelta(days=40))
t = fill(f, 200, T0 + timedelta(hours=1), arms=("MAIN",))
t2 = fill(f, 150, T0 + timedelta(hours=1), arms=("STRESS",))
d = f.write("b4a", t + timedelta(hours=2))
te, why = bg.determine_end(ag.load(d, CFG))
check("B4 MAIN 만 200 → 종료는 6개월 시점(한 팔 도달로 끝나지 않는다)", (te, why) == (SIX, "6개월 경과"), (te, why))
t3 = fill(f, 50, max(t, t2), arms=("STRESS",))
d = f.write("b4b", t3 + timedelta(hours=2))
data = ag.load(d, CFG)
te, why = bg.determine_end(data)
st200 = ag.closed_trips(data, "STRESS")[199][0]
check("B4 두 팔 200 → 늦은 쪽(STRESS)의 200번째 청산 시각", (te, why) == (st200, "두 팔 200거래 공동 시점"), (te, why))
check("B4 종료 전 final 은 돌지 않는다",
      raises(lambda: bg.cmd_final(ag.load(f.write("b4c", st200 - timedelta(minutes=1)), CFG), d + "_x"), "종료 전"))

# ════════════════════════════════════════════════════════════════════════════
# B5 · 6개월에 한 팔 100 미만 → 판정 불가(성과 계산 없음)
f = fake()
bars(f, SIX + timedelta(hours=2))
t = fill(f, 120, T0 + timedelta(hours=1), arms=("MAIN",))
fill(f, 99, T0 + timedelta(hours=1), arms=("STRESS",))
res, out = final(f.write("b5", SIX + timedelta(hours=1)))
check("B5 STRESS 99 → 판정 불가", res["verdict"] == "판정 불가" and res["sample"] == "판정 불가", res.get("verdict"))
check("B5 판정 불가면 코인별 성과 파일을 쓰지 않는다", not os.path.exists(os.path.join(out, "per_coin.csv")))

# ════════════════════════════════════════════════════════════════════════════
# B6 · §6 판정 — 충족 / 스트레스만 미달 / 양수 코인 12 경계
WIN = 12


def winners(n, win=1.02, lose=0.9995):
    good = set(COINS[:n])
    return lambda coin, arm: win if coin in good else lose


def run(name, profit, cfg=CFG, skip=(), confirmed=None):
    f = Fake(cfg, SLIP)
    bars(f, T0 + timedelta(days=30))
    t = fill(f, 200, T0 + timedelta(hours=1), profit=profit, skip=skip)
    return final(f.write(name, t + timedelta(hours=3)), cfg)


res, out = run("b6a", lambda c, a: 1.01)
check("B6 모든 코인 이익 → 충족", res["verdict"] == "충족" and all(res["checks"].values()), res.get("checks"))
check("B6 판정 항목은 §6 표 그대로 — 주 비용 네 개 + 스트레스 두 개", list(res["checks"]) == [
    "MAIN 평균 순수익 > 0", "MAIN 중앙값 > 0", "MAIN 양수 코인 ≥ 12/21", "MAIN 거래당 순수익률 ≥ 0.10%",
    "STRESS 평균 순수익 > 0", "STRESS 거래당 순수익률 > 0"], list(res["checks"]))
check("B6 코인별 파일은 21코인 두 팔", sum(1 for _ in open(os.path.join(out, "per_coin.csv"), encoding="utf-8")) == 22)
pc = [l.split(",") for l in open(os.path.join(out, "per_coin.csv"), encoding="utf-8").read().splitlines()[1:]]
check("B6 같은 신호가에서 STRESS 순수익 < MAIN 순수익(모든 코인)", all(Decimal(r[2]) < Decimal(r[1]) for r in pc))

# 신호가 +0.45% — 왕복 하나의 거래당 순수익률이 MAIN ≈ +0.149% · STRESS ≈ −0.051% (ledger_rounding 모형으로 계산)
res, out = run("b6b", lambda c, a: 1.0045)
want = {k: not k.startswith("STRESS") for k in res["checks"]}
check("B6 신호가 +0.45% → MAIN 네 항목 충족 · STRESS 미달 → 미충족",
      res["verdict"] == "미충족" and res["checks"] == want, res["checks"])

res, _ = run("b6c", winners(WIN))
check(f"B6 양수 코인 {WIN}/21 → 그 항목 충족", res["checks"][f"MAIN 양수 코인 ≥ {WIN}/21"] and res["verdict"] == "충족",
      res["checks"])
res, _ = run("b6d", winners(WIN - 1))
fails = [k for k, v in res["checks"].items() if not v]
check(f"B6 양수 코인 {WIN - 1}/21 → 그 항목만 미달 → 미충족", fails == [f"MAIN 양수 코인 ≥ {WIN}/21"] and res["verdict"] == "미충족",
      res["checks"])

# ════════════════════════════════════════════════════════════════════════════
# B7 · §5-1 검증 도중 거래지원 종료
gone = "KRW-NEAR"
stop_at = T0 + timedelta(days=2)
ends = [{"id": SID[(gone, arm)], "stopped_at": z(stop_at), "cash": "1000000", "basis": "시험"} for arm in ("MAIN", "STRESS")]
cfg_end = load_cfg(confirmed_ends=ends)
res, out = run("b7a", lambda c, a: 1.01, cfg=cfg_end, skip=(gone,))
pc = {l.split(",")[0]: l.split(",") for l in open(os.path.join(out, "per_coin.csv"), encoding="utf-8").read().splitlines()[1:]}
check("B7 두 팔 함께 정지·확인된 현금 → 계산한다, 분모 21코인 유지", res["verdict"] in ("충족", "미충족") and len(pc) == 21, res.get("verdict"))
check("B7 정지 코인은 확인된 현금으로 이어 순수익 0, 교체 없음",
      Decimal(pc[gone][1]) == 0 and Decimal(pc[gone][2]) == 0 and pc[gone][3] == "0", pc.get(gone))
check("B7 정지 코인이 있어도 양수 코인 기준 숫자는 12/21 그대로", f"MAIN 양수 코인 ≥ {WIN}/21" in res["checks"])

f = Fake(cfg_end, SLIP)
bars(f, T0 + timedelta(days=30))
f.buy(SID[(gone, "MAIN")], T0 + timedelta(days=1), 1000)                  # 정지 시각에 보유가 남음
t = fill(f, 200, T0 + timedelta(hours=1), profit=lambda c, a: 1.01, skip=(gone,))
check("B7 정지 시각에 보유가 남으면 계산하지 않는다(가상 청산·0 수익 없음, §5-1 ⑦)",
      raises(lambda: final(f.write("b7b", t + timedelta(hours=3)), cfg_end), r"판정 불가\(§5-1 ⑦\)[\s\S]*정지 시각에 보유"))

f = fake()
bars(f, T0 + timedelta(days=30))
t = fill(f, 200, T0 + timedelta(hours=1), profit=lambda c, a: 1.01, skip=(gone,))
f.status[SID[(gone, "MAIN")]] = "STOPPED"
f.status[SID[(gone, "STRESS")]] = "STOPPED"
f.stopped[SID[(gone, "MAIN")]] = f.stopped[SID[(gone, "STRESS")]] = stop_at
check("B7 설정에 없는 정지(확인 기록 없음) → 측정 결측 → 계산하지 않는다",
      raises(lambda: final(f.write("b7c", t + timedelta(hours=3))), r"§5-1 ⑦[\s\S]*측정 결측"))

# ════════════════════════════════════════════════════════════════════════════
print()
print(f"{sum(results)}/{len(results)} 통과")
shutil.rmtree(WORK, ignore_errors=True)
sys.exit(0 if all(results) else 1)
