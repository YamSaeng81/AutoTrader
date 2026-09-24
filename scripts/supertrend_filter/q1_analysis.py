# -*- coding: utf-8 -*-
"""
Q1 — 상위봉 동의·비동의별 이후 순수익  (docs/SUPERTREND_FILTER_PREREG.md v2 §3)

입력
    d:/tmp/stfilter_signals.csv     SupertrendFilterGateRunner 의 신호 덤프
        coin,strategy,variant,time,action,blocked
    scripts/signal_study/cache/candles_h1.csv.gz   같은 13코인 H1

절차 (사전 등록 그대로)
    · 대상: **필터 적용 전** 기본 BUY 신호 전체
    · 분할: 동의(blocked=false) vs 비동의(blocked=true)
    · 이후 순수익: 진입 O[t+1], 청산 O[t+1+h], h ∈ {4,12,24}, 비용 0.30% 차감
    · 비중첩 채택: 각 군 안에서 t' >= t + h
    · 🔴 양 군에서 **같은 코인 집합**: 두 군 모두 채택 관측치 >= 20 인 코인만.
      공통 코인 < 6 이면 **NA**
    · θ = 평균_코인( 평균(동의)_코인 − 평균(비동의)_코인 )
    · 패널 시간블록 부트스트랩 L=72, B=2000, 시드 20260923, **중심화 p값**
      (signal_study/bootstrap.py 를 그대로 재사용 — 같은 절차)
    · 9검정(3전략 × 3h) Holm α=0.05 — **변형 B 에만**. A 는 탐색적 보고

🔴 해석 제한
    Q1 은 독립 검증이 아니라 **Q2 를 설명하는 보조 근거**다.
    Q1 이 유의하지 않거나 NA 여도 Q2 의 관측 결과는 취소되지 않는다.
    Q1 이 양수여도 Q2 개선의 원인이 BUY 선별뿐이라고 확정할 수 없다 —
    래퍼는 **SELL 과 이후 상태 이력에도** 영향을 준다.
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

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "signal_study"))
from bootstrap import holm, run_bootstrap  # noqa: E402

SIGNALS_CSV = Path("d:/tmp/stfilter_signals.csv")
CANDLES = ROOT / "scripts" / "signal_study" / "cache" / "candles_h1.csv.gz"
OUT = Path(__file__).resolve().parent / "q1_results.csv"

HORIZONS = [4, 12, 24]
COST = 0.30
L_BLOCK = 72
B = 2000
SEED = 20260923
MIN_PER_COIN = 20
MIN_COINS = 6
ALPHA = 0.05
STRATEGIES = ["MTF_CONFIRMED", "MTF_BTC", "MTF_MOMENTUM"]


def load_candles():
    df = pd.read_csv(CANDLES)
    df["time"] = pd.to_datetime(df["time"], utc=True, format="ISO8601")
    out = {}
    for coin, g in df.groupby("coin_pair", sort=False):
        g = g.sort_values("time")
        out[coin.replace("KRW-", "")] = (
            g["time"].dt.tz_convert("UTC").dt.tz_localize(None).to_numpy("datetime64[ns]"),
            g["open"].to_numpy(float))
    return out


def select_nonoverlapping(idx: np.ndarray, h: int) -> np.ndarray:
    kept, last = [], None
    for t in idx:
        if last is None or t >= last + h:
            kept.append(int(t))
            last = int(t)
    return np.asarray(kept, dtype=int)


def build(sig: pd.DataFrame, candles) -> pd.DataFrame:
    """신호 덤프 + 캔들 → (coin, group, h, time, ret) 관측치."""
    rows = []
    for coin, g0 in sig.groupby("coin", sort=True):
        if coin not in candles:
            continue
        times, o = candles[coin]
        pos = {t: i for i, t in enumerate(times)}
        n = len(o)
        for grp, g1 in g0.groupby("group", sort=True):
            ti = np.array(sorted(pos[t] for t in g1["t"] if t in pos), dtype=int)
            for h in HORIZONS:
                ok = ti[(ti + 1 + h) < n]
                kept = select_nonoverlapping(ok, h)
                if len(kept) == 0:
                    continue
                entry = o[kept + 1]
                exitp = o[kept + 1 + h]
                ret = (exitp - entry) / entry * 100.0 - COST
                for t, r in zip(kept, ret):
                    rows.append((coin, grp, h, times[t], r))
    return pd.DataFrame(rows, columns=["coin", "group", "h", "time", "ret"])


def analyse(obs: pd.DataFrame, label: str) -> pd.DataFrame:
    rows = []
    for h in HORIZONS:
        a = obs[(obs["h"] == h) & (obs["group"] == "agree")]
        d = obs[(obs["h"] == h) & (obs["group"] == "disagree")]
        ca = a.groupby("coin")["ret"].count()
        cd = d.groupby("coin")["ret"].count()
        common = sorted(set(ca[ca >= MIN_PER_COIN].index) & set(cd[cd >= MIN_PER_COIN].index))
        excl = sorted((set(ca.index) | set(cd.index)) - set(common))
        base = dict(h=h, n_coins=len(common),
                    n_agree=int(ca.reindex(common).sum()) if common else 0,
                    n_disagree=int(cd.reindex(common).sum()) if common else 0,
                    common=",".join(common), excluded=",".join(excl))
        if len(common) < MIN_COINS:
            rows.append({**base, "verdict": "NA(공통코인부족)"})
            continue
        A = a[a["coin"].isin(common)][["coin", "time", "ret"]].copy()
        D = d[d["coin"].isin(common)][["coin", "time", "ret"]].copy()
        A["time"] = pd.to_datetime(A["time"], utc=True)
        D["time"] = pd.to_datetime(D["time"], utc=True)
        res = run_bootstrap(A, D, common, L_BLOCK, B=B, seed=SEED)
        if res is None:
            rows.append({**base, "verdict": "NA(추정불가)"})
            continue
        rows.append({**base,
                     "theta": res["theta_diff"],
                     "ci_lo": res["ci_diff"][0], "ci_hi": res["ci_diff"][1],
                     "mean_agree": res["theta_abs"],
                     "p": res["p_diff"], "invalid": res["invalid_rate"],
                     "verdict": ""})
    df = pd.DataFrame(rows)
    df.insert(0, "variant", label)
    return df


def main() -> int:
    if not SIGNALS_CSV.exists():
        print(f"✗ {SIGNALS_CSV} 없음 — 게이트 러너를 -Dstfilter.csv 로 먼저 돌린다")
        return 1
    raw = pd.read_csv(SIGNALS_CSV)
    raw = raw[raw["action"] == "BUY"].copy()          # Q1 은 BUY 선별 능력만 본다
    raw["t"] = pd.to_datetime(raw["time"], utc=True, format="ISO8601") \
                 .dt.tz_convert("UTC").dt.tz_localize(None).to_numpy("datetime64[ns]")
    raw["group"] = np.where(raw["blocked"].astype(str).str.lower() == "true",
                            "disagree", "agree")
    candles = load_candles()

    print("=" * 108)
    print("Q1 — 상위봉 동의·비동의별 이후 순수익 (PREREG v2 §3)")
    print(f"  비용 {COST}%  h={HORIZONS}  블록 {L_BLOCK}봉  B={B}  시드 {SEED}")
    print(f"  코인당 최소 {MIN_PER_COIN}건 · 공통 코인 최소 {MIN_COINS} · Holm α={ALPHA} (변형 B 만)")
    print("=" * 108)

    frames = []
    for variant in ["B", "A"]:
        for strat in STRATEGIES:
            sub = raw[(raw["variant"] == variant) & (raw["strategy"] == strat)]
            obs = build(sub, candles)
            df = analyse(obs, variant)
            df.insert(1, "strategy", strat)
            frames.append(df)
    allres = pd.concat(frames, ignore_index=True)

    # Holm — 변형 B 의 검정 가능한 9칸에만
    mask = (allres["variant"] == "B") & (allres["verdict"] == "")
    if mask.any():
        adj, rej = holm(allres.loc[mask, "p"].tolist(), ALPHA)
        allres.loc[mask, "p_holm"] = adj
        allres.loc[mask, "holm_sig"] = rej
    allres.to_csv(OUT, index=False)

    for variant, title in [("B", "① 주 분석 B (운영과 동일) — Holm 보정 적용"),
                           ("A", "② 보조 A (완결 H4 만) — 탐색적, 보정 없음")]:
        print(f"\n{title}")
        d = allres[allres["variant"] == variant]
        cols = ["strategy", "h", "n_coins", "n_agree", "n_disagree", "theta",
                "ci_lo", "ci_hi", "mean_agree", "p", "p_holm", "holm_sig", "verdict"]
        show = d[[c for c in cols if c in d.columns]].copy()
        for c in ("theta", "ci_lo", "ci_hi", "mean_agree", "p", "p_holm"):
            if c in show:
                show[c] = show[c].astype(float).round(4)
        print(show.to_string(index=False))

    print("\n포함/제외 코인 (변형 B)")
    print(allres[allres["variant"] == "B"][["strategy", "h", "n_coins", "excluded"]]
          .to_string(index=False))

    print("\n" + "=" * 108)
    print("🔴 Q1 은 독립 검증이 아니라 Q2 를 설명하는 보조 근거다.")
    print("   · Q1 이 유의하지 않거나 NA 여도 Q2 의 관측 결과는 취소되지 않는다")
    print("   · Q1 이 양수여도 Q2 개선의 원인이 BUY 선별뿐이라고 확정할 수 없다 —")
    print("     래퍼는 SELL 과 이후 상태 이력에도 영향을 준다")
    print("=" * 108)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
