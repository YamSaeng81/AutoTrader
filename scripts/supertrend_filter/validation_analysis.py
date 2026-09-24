# -*- coding: utf-8 -*-
"""
백테스트 미사용 22코인 일반화 검사 — 분석
docs/SUPERTREND_VALIDATION_PREREG.md v3

입력 (SupertrendValidationRunner 산출물, 기본 d:/tmp/stval_out)
    summary.csv       coin,arm,cost,totalReturnPct,trades,mddPct,expectancyPct,finalEquity,calls
    equity_daily.csv  coin,arm,cost,date,equity
    trades.csv        coin,arm,cost,entryTime,exitTime,entryAmount,netPnl

🔴 기준은 착수 전 고정됐다. 결과를 보고 고치지 않는다 (§7).
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

import numpy as np
import pandas as pd

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "d:/tmp/stval_out")
INITIAL = 10_000_000.0

# ── 사전 고정 상수 (§3 §4) ────────────────────────────────────────────────
L_MAIN = 30                 # 일
L_SENS = (14, 60)
B = 2000
SEED = 20260924
MIN_IMPROVED = 15           # / 22   (가)
MIN_POSITIVE = 12           # / 22   (나)
MIN_PER_TRADE = 0.10        # %      (나) 주 비용
COSTS = ["MAIN", "STRESS"]


def per_trade_return(trades: pd.DataFrame) -> pd.Series:
    """
    §4 거래당 순수익률 — **진입 포지션 금액 기준**(가격 기준).
        ① 코인별로 왕복 거래 단순평균  ② 코인별 평균을 동일가중
    🔴 expectancyPct(= 총수익률/거래수, 초기자본 기준)와 섞지 않는다.
    """
    t = trades[trades["entryAmount"] > 0].copy()
    t["r"] = t["netPnl"] / t["entryAmount"] * 100.0
    return t.groupby(["arm", "cost", "coin"])["r"].mean()


def block_bootstrap(eq: pd.DataFrame, coins, L: int, rng):
    """
    §3 패널 시간블록 부트스트랩 — 전 코인·전 팔에 **같은 블록 집합**을 적용하고
    짝지은 차이 δ = 평균_코인( Σ r_A − Σ r_B ) 를 재계산한다.

    🔴 관측된 운용 성과의 **불확실성 분석**이다. 재배열된 시장의 재운용 시뮬레이션이 아니다.
    """
    piv = eq.pivot_table(index="date", columns=["coin", "arm"], values="dret")
    piv = piv.sort_index()
    days = piv.index.to_numpy()
    origin = days.min()
    blk = ((days - origin) / np.timedelta64(1, "D") // L).astype(int)
    ublk = np.unique(blk)
    groups = [np.flatnonzero(blk == b) for b in ublk]
    K = len(ublk)

    def theta(rows):
        sub = piv.iloc[rows]
        d = []
        for c in coins:
            if (c, "A") in sub.columns and (c, "B") in sub.columns:
                d.append(np.nansum(sub[(c, "A")].to_numpy())
                         - np.nansum(sub[(c, "B")].to_numpy()))
        return float(np.mean(d)) if d else np.nan

    obs = theta(np.arange(len(piv)))
    rep = np.empty(B)
    for i in range(B):
        pick = rng.integers(0, K, size=K)
        rep[i] = theta(np.concatenate([groups[p] for p in pick]))
    lo, hi = np.nanpercentile(rep, [2.5, 97.5])
    p = (1 + int(np.sum((rep - obs) >= obs))) / (np.sum(~np.isnan(rep)) + 1)
    return dict(theta=obs, lo=float(lo), hi=float(hi), p=float(p), K=K)


def main() -> int:
    summ = pd.read_csv(OUT / "summary.csv")
    eqd = pd.read_csv(OUT / "equity_daily.csv", parse_dates=["date"])
    trd = pd.read_csv(OUT / "trades.csv")
    coins = sorted(summ["coin"].unique())
    rng = np.random.default_rng(SEED)

    print("=" * 104)
    print("22코인 일반화 검사 — SUPERTREND_VALIDATION_PREREG.md v3")
    print(f"  코인 {len(coins)}  ·  블록 주 {L_MAIN}일 / 민감도 {L_SENS}  ·  B={B}  시드 {SEED}")
    print("=" * 104)

    # ── ④-1 회계 검증: 독립 재구성 자산 vs 엔진 finalEquity ────────────────
    last_eq = (eqd.sort_values("date").groupby(["coin", "arm", "cost"])["equity"]
               .last().rename("recon").reset_index())
    chk = summ.merge(last_eq, on=["coin", "arm", "cost"])
    chk["err"] = (chk["recon"] - chk["finalEquity"]).abs()
    chk["err_rel"] = chk["err"] / chk["finalEquity"] * 100

    # 일별 차분
    eqd = eqd.sort_values(["coin", "arm", "cost", "date"])
    eqd["dret"] = (eqd.groupby(["coin", "arm", "cost"])["equity"].diff() / INITIAL * 100)

    def w(cost, arm, col="totalReturnPct"):
        return (summ[(summ["cost"] == cost) & (summ["arm"] == arm)]
                .set_index("coin")[col].reindex(coins))

    # ── ① (가) A − B 개선 기준 ────────────────────────────────────────────
    print("\n① (가) 변경안의 가치 — A − B  [주 비용]")
    diff = w("MAIN", "A") - w("MAIN", "B")
    bs = block_bootstrap(eqd[eqd["cost"] == "MAIN"], coins, L_MAIN, rng)
    improved = int((diff > 0).sum())
    print(f"   평균차   {diff.mean():+8.3f}%p      기준 > 0        {'✔' if diff.mean() > 0 else '✗'}")
    print(f"   중앙차   {diff.median():+8.3f}%p      기준 > 0        {'✔' if diff.median() > 0 else '✗'}")
    print(f"   개선코인 {improved:>4d}/{len(coins)}         기준 ≥ {MIN_IMPROVED}      "
          f"{'✔' if improved >= MIN_IMPROVED else '✗'}")
    print(f"   구간하한 {bs['lo']:+8.3f}%p      기준 > 0        {'✔' if bs['lo'] > 0 else '✗'}"
          f"   (θ={bs['theta']:+.3f}, 상한 {bs['hi']:+.3f}, p={bs['p']:.4f}, 블록 {bs['K']}개)")
    ga = (diff.mean() > 0 and diff.median() > 0
          and improved >= MIN_IMPROVED and bs["lo"] > 0)
    ga_fail = diff.mean() <= 0 or diff.median() <= 0
    verdict_a = "근거 있음" if ga else ("근거 없음" if ga_fail else "판단 보류")
    print(f"   → (가) {verdict_a}")
    print("   ⚠️ 평균·중앙값·개선 코인 수는 같은 22개 값의 세 요약이다 — 독립 증거 셋이 아니다.")

    # ── ② (나) A 자체의 수익 후보 기준 ────────────────────────────────────
    print("\n② (나) 수익 후보 자격 — A 자체")
    ptr = per_trade_return(trd)
    nb_ok = {}
    for cost in COSTS:
        a = w(cost, "A")
        pt = ptr.loc["A", cost].reindex(coins).mean()
        pos = int((a > 0).sum())
        ex = w(cost, "A", "expectancyPct").mean()
        need_pt = MIN_PER_TRADE if cost == "MAIN" else 0.0
        chk_mean = a.mean() > 0
        chk_med = a.median() > 0
        chk_pos = pos >= MIN_POSITIVE
        chk_pt = pt >= need_pt if cost == "MAIN" else pt > 0
        print(f"   [{cost}]")
        print(f"     평균 순수익      {a.mean():+8.3f}%   기준 > 0          {'✔' if chk_mean else '✗'}")
        print(f"     중앙값           {a.median():+8.3f}%   "
              f"{'기준 > 0          ' + ('✔' if chk_med else '✗') if cost == 'MAIN' else '(보고만)'}")
        print(f"     양수 코인        {pos:>4d}/{len(coins)}      "
              f"{'기준 ≥ ' + str(MIN_POSITIVE) + '       ' + ('✔' if chk_pos else '✗') if cost == 'MAIN' else '(보고만)'}")
        print(f"     거래당 순수익률  {pt:+8.4f}%   "
              f"기준 {'≥ 0.10%' if cost == 'MAIN' else '> 0'}     {'✔' if chk_pt else '✗'}"
              f"   (진입금액 기준)")
        print(f"     [참고] expectancyPct {ex:+.4f}%  ← 초기자본 기준. 판정에 쓰지 않는다")
        nb_ok[cost] = (chk_mean and chk_pt) if cost == "STRESS" else \
                      (chk_mean and chk_med and chk_pos and chk_pt)
    nb = all(nb_ok.values())
    print(f"   → (나) {'충족' if nb else '미충족'}  (MAIN {nb_ok['MAIN']} · STRESS {nb_ok['STRESS']})")

    # ── ③ 최종 판정 ──────────────────────────────────────────────────────
    print("\n③ 사전 기준에 따른 최종 판정")
    if verdict_a == "근거 없음":
        final = "A 변경안을 접는다. B 유지"
    elif verdict_a == "판단 보류":
        final = "판단 보류 — 전향 검증에서 다시 본다"
    elif nb:
        final = "A 를 전향 검증 후보로. 배포는 전향 검증 이후 판단"
    else:
        final = ("A 의 상대적 개선은 관측됐으나 수익 후보 기준은 충족하지 못했다. "
                 "배포하지 않는다")
    print(f"   🔴 {final}")

    # ── ④ 보조 ───────────────────────────────────────────────────────────
    print("\n④-1 블록 길이 민감도 (A−B, 주 비용)")
    for L in (L_MAIN, *L_SENS):
        r = block_bootstrap(eqd[eqd["cost"] == "MAIN"], coins, L, np.random.default_rng(SEED))
        print(f"   L={L:>3}일  θ {r['theta']:+8.3f}  95% [{r['lo']:+8.3f}, {r['hi']:+8.3f}]  "
              f"p {r['p']:.4f}  블록 {r['K']}")

    print("\n④-2 코인별 분포 (주 비용, %)")
    tab = pd.DataFrame({"OFF": w("MAIN", "OFF"), "B": w("MAIN", "B"), "A": w("MAIN", "A")})
    tab["A-B"] = tab["A"] - tab["B"]
    tab["B-OFF"] = tab["B"] - tab["OFF"]
    print(tab.round(2).to_string())
    print(f"\n   보조 비교 B−OFF: 평균 {tab['B-OFF'].mean():+.3f}%p  "
          f"중앙 {tab['B-OFF'].median():+.3f}%p  개선 {int((tab['B-OFF'] > 0).sum())}/{len(coins)}"
          "   (판정에 쓰지 않는다)")

    print("\n④-3 회계 검증 — 🔴 핵심은 독립 재구성 자산 ↔ 엔진 finalEquity 일치")
    print(f"   최대 절대오차 {chk['err'].max():,.2f}원   최대 상대오차 {chk['err_rel'].max():.6f}%")
    print(f"   100원 초과 조합 {int((chk['err'] > 100).sum())} / {len(chk)}")
    print("   ⚠️ 일별 차분 합 = 최종 변화 검사는 **집계 누락**만 잡는다(망원합).")
    print("      거래 회계의 정확성 근거는 위의 finalEquity 일치다.")

    tab.to_csv(OUT / "per_coin.csv")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
