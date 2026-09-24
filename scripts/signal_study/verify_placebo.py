# -*- coding: utf-8 -*-
"""
위약(placebo) 점검 — SIGNAL_STUDY_PREREG.md v5 §6

🔴 이 점검을 통과하지 못하면 본 실험을 시작하지 않는다.

두 가지를 본다
-------------
**(A) 위약 배치 편향** — 무작위 비중첩 배치가 적격 봉을 동일 확률로 고르지 않으면
     위약과 비교군의 기대 차이가 0 이 아니게 되어 size 추정이 무효가 된다.

     "0 과 유의하게 다르지 않다" 는 쓰지 않는다 — 검출 실패이지 편향 부재의 증명이 아니다.
     **동등성 기준을 숫자로 고정한다** (단위 %p):

        통과      |m| ≤ 0.03  AND  CI95 ⊂ [−0.05, +0.05]
        판정유보  |m| ≤ 0.03  이지만 CI95 가 구간 밖  → R=2,000 재판정, 그래도 유보면 NA
        실패      |m| > 0.03                          → 위약 구성 결함. 착수 금지

     근거: 최소 효과 크기 0.30%p 의 10% · 17%.

**(B) 기각률(size)** — `p_기본대비` 가 α=0.05 에서 기각되는 비율.
     ≤ 0.10 이면 **사전에 정한 중단 기준을 넘지 않았다**는 뜻이다.
     오류율이 충분히 통제됐다는 인증이 아니다.

⚠️ `p_절대` 는 위약에서도 기각될 수 있다 — 시장이 비용보다 많이 올랐으면 무작위 진입도
   양수다. 오류가 아니라 시장 드리프트이며, 정확히 그래서 ②(기본 대비)가 필요하다.
   `p_절대` 는 기술적으로 보고만 하고 size 판정에 쓰지 않는다.

🔴 무효 복제 처리는 **본 실험과 동일**하다. 그러지 않으면 점검한 절차와 실행하는 절차가
   달라져 오류율 추정이 의미를 잃는다.
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

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bootstrap import (BLOCK_MAIN, MIN_COINS, MIN_SIGNALS_PER_COIN,  # noqa: E402
                       SEED, run_bootstrap)
from segments import assign_segments, eligible_mask, load_candles  # noqa: E402
from signals import COST_MAIN, HORIZONS, SIGNALS, forward_return, select_nonoverlapping  # noqa: E402

CACHE = Path(__file__).resolve().parent / "cache"

R_PLACEBO = 500
R_PLACEBO_EXTENDED = 2000
BIAS_M_MAX = 0.03        # %p
BIAS_CI_BOUND = 0.05     # %p
SIZE_MAX = 0.10
ALPHA = 0.05
B_SIZE = 400             # size 점검용 부트스트랩 복제 수 (시간 절약, 본 실험은 2000)


def placebo_indices(rng, elig_idx: np.ndarray, k: int, h: int) -> np.ndarray:
    """
    적격 봉 중 무작위로 고르되 비중첩(t' ≥ t_prev + h)을 만족하는 k개를 채택한다.

    ⚠️ 무작위 순열에서 앞에서부터 비중첩 조건을 적용하면 배치가 균일하지 않을 수 있다 —
       그 편향을 재는 것이 (A) 의 목적이다. 여기서는 구성만 하고 판정은 (A) 가 한다.
    """
    perm = rng.permutation(elig_idx)
    kept = []
    for t in perm:
        if all(abs(int(t) - int(u)) >= h for u in kept):
            kept.append(int(t))
            if len(kept) >= k:
                break
    return np.array(sorted(kept), dtype=int)


def main() -> int:
    print("=" * 78)
    print("위약 점검 — SIGNAL_STUDY_PREREG.md v5 §6")
    print("=" * 78)

    obs = pd.read_csv(CACHE / "observations.csv.gz", parse_dates=["time"])
    obs["time"] = pd.to_datetime(obs["time"], utc=True)
    real_sig = obs[obs["group"] == "signal"]
    base_all = obs[obs["group"] == "baseline"]

    # 코인별 적격 인덱스와 수익을 미리 준비
    df = load_candles()
    prep = {}
    for coin, g0 in df.groupby("coin_pair", sort=True):
        g = assign_segments(g0)
        times = g["time"].to_numpy()
        for h in HORIZONS:
            elig = eligible_mask(g, h)
            fr = forward_return(g, h) - COST_MAIN
            idx = np.flatnonzero(elig & ~np.isnan(fr))
            prep[(coin, h)] = (idx, fr, times)

    rng = np.random.default_rng(SEED)
    rows = []

    for s in SIGNALS:
        for h in HORIZONS:
            rs = real_sig[(real_sig["signal"] == s) & (real_sig["h"] == h)]
            bh = base_all[base_all["h"] == h]
            counts = rs.groupby("coin")["t_idx"].count()
            coins = sorted(counts[counts >= MIN_SIGNALS_PER_COIN].index.tolist())
            if len(coins) < MIN_COINS:
                rows.append(dict(signal=s, h=h, status="NA(코인부족)",
                                 m=np.nan, lo=np.nan, hi=np.nan,
                                 rej_diff=np.nan, rej_abs=np.nan))
                continue

            deltas = []
            rej_d = rej_a = 0
            n_run = 0
            for r in range(R_PLACEBO):
                frames = []
                okrun = True
                for c in coins:
                    idx, fr, times = prep[(c, h)]
                    k = int(counts[c])
                    pick = placebo_indices(rng, idx, k, h)
                    if len(pick) == 0:
                        okrun = False
                        break
                    frames.append(pd.DataFrame({
                        "coin": c,
                        "time": pd.to_datetime(times[pick], utc=True),
                        "ret": fr[pick]}))
                if not okrun:
                    continue
                psig = pd.concat(frames, ignore_index=True)
                bsub = bh[bh["coin"].isin(coins)][["coin", "time", "ret"]]

                # (A) 위약군 − 비교군 평균 차이 (코인별 동일 가중)
                d = np.mean([psig.loc[psig.coin == c, "ret"].mean()
                             - bsub.loc[bsub.coin == c, "ret"].mean() for c in coins])
                deltas.append(d)
                n_run += 1

                # (B) 기각률 — 본 실험과 동일 절차(무효 복제 처리 포함)
                res = run_bootstrap(psig, bsub, coins, BLOCK_MAIN,
                                    B=B_SIZE, seed=int(rng.integers(1, 2**31)))
                if res and res["invalid_rate"] <= 0.05 and not np.isnan(res["p_diff"]):
                    if res["p_diff"] <= ALPHA:
                        rej_d += 1
                    if res["p_abs"] <= ALPHA:
                        rej_a += 1

            deltas = np.asarray(deltas, float)
            m = float(deltas.mean())
            se = float(deltas.std(ddof=1) / np.sqrt(len(deltas)))
            lo, hi = m - 1.96 * se, m + 1.96 * se

            if abs(m) > BIAS_M_MAX:
                status = "실패(편향)"
            elif lo >= -BIAS_CI_BOUND and hi <= BIAS_CI_BOUND:
                status = "통과"
            else:
                status = "판정유보"

            rows.append(dict(signal=s, h=h, status=status, m=m, lo=lo, hi=hi,
                             rej_diff=rej_d / max(n_run, 1),
                             rej_abs=rej_a / max(n_run, 1)))
            print(f"  {s} h={h:<3} m={m:+.4f}  CI95=[{lo:+.4f},{hi:+.4f}]  "
                  f"기각률(대비)={rej_d/max(n_run,1):.3f}  (절대)={rej_a/max(n_run,1):.3f}  {status}",
                  flush=True)

    res = pd.DataFrame(rows)
    res.to_csv(CACHE / "placebo_check.csv", index=False)

    print("\n" + "=" * 78)
    print("(A) 배치 편향 — 동등성 |m|≤0.03 AND CI95⊂[−0.05,+0.05]")
    print(res[["signal", "h", "m", "lo", "hi", "status"]].to_string(index=False))
    fails = res[res["status"] == "실패(편향)"]
    holds = res[res["status"] == "판정유보"]

    print("\n(B) 기각률 — p_기본대비, α=0.05, 중단 기준 0.10")
    print(res[["signal", "h", "rej_diff", "rej_abs"]].to_string(index=False))
    over = res[res["rej_diff"] > SIZE_MAX]

    print("\n" + "=" * 78)
    ok = True
    if len(fails):
        print(f"✗ 배치 편향 실패 {len(fails)}건 — 위약 구성을 고친 뒤 전체 재점검. 착수 금지")
        ok = False
    if len(holds):
        print(f"⚠ 판정유보 {len(holds)}건 — R={R_PLACEBO_EXTENDED} 로 재판정 필요")
    if len(over):
        print(f"✗ 기각률 초과 {len(over)}건 — 부트스트랩 설정 재검토 후 착수")
        ok = False
    if ok and not len(holds):
        print("✓ 위약 점검 통과 — 본 실험 착수 가능")
        print("  ⚠️ 기각률 ≤0.10 은 사전 중단 기준을 넘지 않았다는 뜻이다.")
        print("     오류율이 충분히 통제됐다는 인증이 아니며, 통과 조합의 p값을")
        print("     그만큼 낙관적으로 읽지 않는다.")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
