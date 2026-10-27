"""F20 시험 — 잔액 검산 허용오차 규칙 (2026-10-27) — 가짜 데이터만. 실행: python scripts/forward_aggregate/test_ledger_rounding.py

규칙(설계 §3-4 R5): 허용오차 = 반올림이 들어가는 저장 횟수 × 0.005원.
1·50·300건 시험은 이 규칙이 성립하는지 확인하는 자료다 — 관측 최대값으로 허용오차를 정하지 않는다.
"""
import math
import os
import random
import sys
from decimal import Decimal

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ledger_rounding as lr  # noqa: E402

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)))


def price_of(p, places):
    return Decimal(f"{p:.{places}f}")


def simulate(seed, n_trips, start_price, places, ratio, same_tick_prob):
    """무작위 가격으로 왕복 n_trips 건. same_tick_prob 확률로 청산 직후 같은 틱에 다시 매수(반올림 전 값으로 이어짐)."""
    rng = random.Random(seed)
    led = lr.PaperLedger(1_000_000, ratio)
    p = start_price
    same_tick = False
    done = 0
    while done < n_trips:
        if not same_tick:
            led.reload()
        p *= math.exp(rng.gauss(0, 0.03))
        if not led.buy(price_of(p, places)):
            break
        for _ in range(rng.randint(1, 3)):   # 보유 중 틱 — 세션을 다시 읽는다(총자산만 쓰므로 잔액 불변)
            led.reload()
        p *= math.exp(rng.gauss(0, 0.03))
        led.sell(price_of(p, places))
        done += 1
        same_tick = rng.random() < same_tick_prob
    led.reload()
    return led, done


# ── 1. 재구성 = 엔진의 정확한 값 (재구성 쪽 오차 0) · 2. 1·50·300건에서 규칙 성립 ──
REGIMES = [("저가 5원", 5.0, 6), ("중가 1,000원", 1000.0, 4), ("고가 700,000원", 700_000.0, 2)]
summary = {}
exact_ok = True
bound_ok = True
full_ok = True
fail_detail = None
total_runs = 0
for n in (1, 50, 300):
    worst = {k: Decimal(0) for k in lr.PaperLedger.KEYS}
    worst_ratio = Decimal(0)
    runs = 0
    for _, start, places in REGIMES:
        for ratio in ("0.80", "1.00"):
            for same_tick_prob in (0.0, 0.5):
                for seed in range(20):
                    led, done = simulate(seed * 7919 + n, n, start, places, ratio, same_tick_prob)
                    full_ok &= (done == n)
                    rec = lr.reconstruct(1_000_000, led.positions, led.orders)
                    if any(rec[k] != led.exact[k] for k in lr.PaperLedger.KEYS):
                        exact_ok = False
                    for k, (diff, tol, ok) in lr.check(led.db, rec).items():
                        if not ok:
                            bound_ok = False
                            fail_detail = (n, seed, k, diff, tol)
                        worst[k] = max(worst[k], abs(diff))
                        if tol > 0:
                            worst_ratio = max(worst_ratio, abs(diff) / tol)
                    runs += 1
    summary[n] = (runs, worst, worst_ratio)
    total_runs += runs

check(f"모든 시뮬레이션이 정한 왕복 수를 채움 ({total_runs}회)", full_ok)
check(f"재구성 = 엔진의 반올림 없는 값 ({total_runs}회 전부 차이 0)", exact_ok)
check("1·50·300건 × 가격대 3 × 투자비율 2 × 같은 틱 이어짐 2 × 시드 20 — 모든 차이 ≤ 허용오차", bound_ok, fail_detail)
for n, (runs, worst, ratio) in summary.items():
    tol = lr.tolerance(n, n)
    print(f"     {n:>3}건 ({runs}회) 관측 최대 |차이|  잔액 {worst['available']:.6f} / 상한 {tol['available']}"
          f" · 수수료 {worst['total_fee']:.6f} / 상한 {tol['total_fee']}"
          f" · 실현손익 {worst['realized']:.6f} / 상한 {tol['realized']}  · 최대 차이/상한 {ratio:.3f}")

# ── 3. 상한은 실제로 도달한다 — 관측 최대값을 허용오차로 쓰면 안 되는 이유 ──
# 엔진 식 한 번으로 상한에 닿는 값: 가용 6,262.50원 × 0.80 = 투자 5,010.00원 → 수수료 2.505원 → 저장 2.51원(+0.005)
led = lr.PaperLedger("6262.50", "0.80")
led.buy(Decimal("1000"))
rec = lr.reconstruct("6262.50", led.positions, led.orders)
diff, tol, ok = lr.check(led.db, rec)["total_fee"]
check("엔진 식 한 번에 상한 도달 (수수료 2.505 → 2.51, 차이 = 0.005 = 상한)", diff == Decimal("0.005") and tol == diff and ok,
      (diff, tol))
# 같은 크기의 끝자리 5 가 반복되면 저장마다 +0.005 씩 쌓인다 — 300번이면 1.5원(고정 1원 허용오차는 정상 장부를 실패시킨다)
mem = db = Decimal(0)
exact = Decimal(0)
for _ in range(300):
    mem = db + Decimal("2.505")
    exact += Decimal("2.505")
    db = lr.pg_round(mem, 2)
check("반올림이 같은 방향으로 300번 → 누적 1.5원 = 300 × 0.005 (선형 누적)", db - exact == Decimal("1.500"), db - exact)
check("300건 왕복의 허용오차: 잔액 1.5원 · 수수료 3.0원 — 고정 1원보다 크다",
      lr.tolerance(300, 300) == {"available": Decimal("1.500"), "total_fee": Decimal("3.000"),
                                 "realized": Decimal("1.500")})

# ── 4. 거래 0건 — 허용오차 0, 차이도 정확히 0 ──
led = lr.PaperLedger(1_000_000, "0.80")
rec = lr.reconstruct(1_000_000, led.positions, led.orders)
res = lr.check(led.db, rec)
check("거래 0건: 허용오차 0 · 차이 0", all(t == 0 and d == 0 and ok for d, t, ok in res.values()), res)

# ── 5. 같은 틱 이어짐(청산 뒤 반올림 전 값으로 매수) — 매수 저장에도 반올림이 생기지만 상한은 매도 수로 충분 ──
led = lr.PaperLedger(1_000_000, "0.80")
led.reload()
led.buy(Decimal("1234.5678"))
led.reload()
led.sell(Decimal("1301.2345"))
led.buy(Decimal("1299.8765"))      # reload 없음 — mem 은 반올림 전 잔액
led.reload()
led.sell(Decimal("1250.4321"))
led.reload()
rec = lr.reconstruct(1_000_000, led.positions, led.orders)
res = lr.check(led.db, rec)
check("같은 틱 청산→매수: 잔액 차이 ≤ 0.005 × 매도 수", all(ok for _, _, ok in res.values()), res)

# ── 6. 검출력 — 상한을 넘는 불일치는 반드시 잡는다 ──
led, done = simulate(42, 50, 1000.0, 4, "0.80", 0.5)
orders = [o for o in led.orders]
last_sell = max(i for i, o in enumerate(orders) if o["side"] == "SELL")
del orders[last_sell]
res = lr.check(led.db, lr.reconstruct(1_000_000, led.positions, orders))
check("매도 주문 1건 누락 → 잔액 검산 실패", not res["available"][2], res["available"])

caught = 0
trials = 0
for seed in range(30):
    led, done = simulate(seed, 50, 5.0, 6, "0.80", 0.5)
    rec = lr.reconstruct(1_000_000, led.positions, led.orders)
    tol = lr.tolerance(rec["n_buy"], rec["n_sell"])["available"]
    for sign in (1, -1):
        tampered = dict(led.db)
        tampered["available"] += sign * (2 * tol + Decimal("0.01"))   # 자연 오차가 반대쪽 끝(±상한)이어도 넘는 크기
        trials += 1
        caught += 0 if lr.check(tampered, rec)["available"][2] else 1
check("상한의 2배를 넘는 조작은 60/60 검출", caught == trials, f"{caught}/{trials}")
print("     📌 상한 이내의 불일치는 원리상 검출되지 않는다 — 허용오차가 가리는 범위는 저장 횟수 × 0.005원 이다")

print(f"\n{sum(results)}/{len(results)} 통과")
sys.exit(0 if all(results) else 1)
