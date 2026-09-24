# -*- coding: utf-8 -*-
"""
본 실험 — SIGNAL_STUDY_PREREG.md v5

🔴 **한 번만 실행한다.** 결과를 보고 조건·기준·신호를 고치지 않는다(§12).

합격 판정 (§9) — 네 조건을 **모두** 만족
    ① 비용 차감 평균수익 점추정 > 0
    ② 기본 분포 대비 차이 점추정 > 0
    ③ 비용 차감 평균수익 점추정 ≥ 0.30%   (점추정 기준 선별. 통계적 보장 아님)
    ④ max(p_절대, p_기본대비) 가 Holm 보정 후 유의 (α=0.05)

NA 조건
    · 포함 코인 < 6
    · 무효 복제 비율 > 5%
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
from bootstrap import (B, BLOCK_MAIN, BLOCK_SENS, INVALID_REPLICATE_MAX,  # noqa: E402
                       MIN_COINS, MIN_SIGNALS_PER_COIN, SEED, holm, run_bootstrap)
from signals import COST_MAIN, COST_STRESS, HORIZONS, SIGNALS  # noqa: E402

CACHE = Path(__file__).resolve().parent / "cache"
ALPHA = 0.05
MIN_EFFECT = 0.30      # %  ③


def load_obs(cost: float) -> pd.DataFrame:
    """관측치 캐시는 COST_MAIN 으로 만들어져 있다. 다른 비용은 차액만 보정한다."""
    obs = pd.read_csv(CACHE / "observations.csv.gz", parse_dates=["time"])
    obs["time"] = pd.to_datetime(obs["time"], utc=True)
    if cost != COST_MAIN:
        obs["ret"] = obs["ret"] - (cost - COST_MAIN)
    return obs


def analyse(cost: float, L: int, label: str, time_from=None) -> pd.DataFrame:
    obs = load_obs(cost)
    if time_from is not None:
        obs = obs[obs["time"] >= pd.Timestamp(time_from, tz="UTC")]
    sig_all = obs[obs["group"] == "signal"]
    base_all = obs[obs["group"] == "baseline"]

    rows = []
    for s in SIGNALS:
        for h in HORIZONS:
            rs = sig_all[(sig_all["signal"] == s) & (sig_all["h"] == h)]
            bh = base_all[base_all["h"] == h]
            counts = rs.groupby("coin")["t_idx"].count()
            incl = sorted(counts[counts >= MIN_SIGNALS_PER_COIN].index.tolist())
            excl = sorted(set(counts.index) - set(incl))

            base = dict(signal=s, h=h, n_coins=len(incl),
                        incl=",".join(c.replace("KRW-", "") for c in incl),
                        excl=",".join(c.replace("KRW-", "") for c in excl),
                        n_obs=int(counts[incl].sum()) if incl else 0)

            if len(incl) < MIN_COINS:
                rows.append({**base, "verdict": "NA(코인부족)"})
                continue

            res = run_bootstrap(rs, bh[bh["coin"].isin(incl)], incl, L, B=B, seed=SEED)
            if res is None:
                rows.append({**base, "verdict": "NA(추정불가)"})
                continue
            if res["invalid_rate"] > INVALID_REPLICATE_MAX:
                rows.append({**base, "verdict": "NA(불안정)",
                             "invalid": res["invalid_rate"]})
                continue

            rows.append({**base,
                         "abs": res["theta_abs"], "abs_lo": res["ci_abs"][0],
                         "abs_hi": res["ci_abs"][1],
                         "diff": res["theta_diff"], "diff_lo": res["ci_diff"][0],
                         "diff_hi": res["ci_diff"][1],
                         "p_abs": res["p_abs"], "p_diff": res["p_diff"],
                         "p_comb": max(res["p_abs"], res["p_diff"]),
                         "invalid": res["invalid_rate"], "verdict": ""})

    df = pd.DataFrame(rows)
    testable = df["verdict"] == ""
    if testable.any():
        adj, rej = holm(df.loc[testable, "p_comb"].tolist(), ALPHA)
        df.loc[testable, "p_holm"] = adj
        df.loc[testable, "holm_sig"] = rej
        ok = (testable
              & (df["abs"] > 0) & (df["diff"] > 0)
              & (df["abs"] >= MIN_EFFECT) & df["holm_sig"].fillna(False))
        df.loc[testable, "verdict"] = np.where(ok[testable], "통과", "탈락")
    df.insert(0, "분석", label)
    return df


def show(df: pd.DataFrame, title: str):
    print("\n" + "=" * 100)
    print(title)
    print("=" * 100)
    cols = ["signal", "h", "n_coins", "n_obs", "abs", "abs_lo", "abs_hi",
            "diff", "diff_lo", "diff_hi", "p_abs", "p_diff", "p_comb",
            "p_holm", "invalid", "verdict"]
    d = df[[c for c in cols if c in df.columns]].copy()
    for c in ("abs", "abs_lo", "abs_hi", "diff", "diff_lo", "diff_hi"):
        if c in d:
            d[c] = d[c].astype(float).round(4)
    for c in ("p_abs", "p_diff", "p_comb", "p_holm", "invalid"):
        if c in d:
            d[c] = d[c].astype(float).round(4)
    print(d.to_string(index=False))


def main() -> int:
    print("=" * 100)
    print("본 실험 — SIGNAL_STUDY_PREREG.md v5")
    print(f"  비용(주) {COST_MAIN}%  블록 {BLOCK_MAIN}봉  B={B}  시드 {SEED}  α={ALPHA}")
    print(f"  합격: 절대>0 AND 대비>0 AND 절대≥{MIN_EFFECT}% AND Holm 유의")
    print("=" * 100)

    main_df = analyse(COST_MAIN, BLOCK_MAIN, "주분석")
    show(main_df, f"① 주 결과 (비용 {COST_MAIN}%, 블록 {BLOCK_MAIN}봉)")

    print("\n포함/제외 코인")
    print(main_df[["signal", "h", "n_coins", "incl", "excl"]].to_string(index=False))

    frames = [main_df]

    d = analyse(COST_STRESS, BLOCK_MAIN, "비용스트레스")
    show(d, f"② 비용 스트레스 (비용 {COST_STRESS}%)")
    frames.append(d)

    for L in BLOCK_SENS:
        d = analyse(COST_MAIN, L, f"블록{L}")
        show(d, f"③ 블록 길이 민감도 (L={L}봉)")
        frames.append(d)

    d = analyse(COST_MAIN, BLOCK_MAIN, "후기재검증", time_from="2025-07-01")
    show(d, "④ 후기 재검증 (2025-07-01~)  ⚠️ 독립 검증 아님 — 이미 열람된 구간")
    frames.append(d)

    allres = pd.concat(frames, ignore_index=True)
    allres.to_csv(CACHE / "study_results.csv", index=False)

    print("\n" + "=" * 100)
    print("판정 요약 (주 분석)")
    print("=" * 100)
    print(main_df["verdict"].value_counts().to_string())
    passed = main_df[main_df["verdict"] == "통과"]
    if len(passed):
        print("\n통과 조합:")
        print(passed[["signal", "h", "abs", "diff", "p_holm"]].to_string(index=False))
        print("\n→ 전진(forward) 검증 후보. **채택이 아니다.**")
    else:
        print("\n통과 조합 없음.")
        print("→ 결론: 검사한 5개 신호 × 3개 보유기간에서, 이 코인·기간·비용 가정 하에")
        print("   비용을 넘는 경제적 효과를 확인하지 못했다.")
        print("   ⚠️ '바탕 신호에 방향성이 전혀 없다'는 뜻이 아니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
