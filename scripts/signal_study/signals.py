# -*- coding: utf-8 -*-
"""
신호 생성 · 미래수익 · 비중첩 선택 — SIGNAL_STUDY_PREREG.md v5 §3 §4

관측치(observation) 하나 = (코인, 신호, h, 신호봉 t) 하나.
    entry = O[t+1],  exit = O[t+1+h],  r = (exit-entry)/entry - c

🔴 지표는 **구간마다 한 번만** 초기화하고 이어서 계산한다(§5).
   평가 시점마다 다시 시작하지 않는다.
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
from indicators import (adx, bollinger_lower, ema, macd_hist, rsi,  # noqa: E402
                        volume_ma_excl_current)
from segments import (WARMUP_BARS, assign_segments, eligible_mask,  # noqa: E402
                      gap_in_lookback, load_candles)

SIGNALS = ["S1", "S2", "S3", "S4", "S5"]
HORIZONS = [4, 12, 24]
COST_MAIN = 0.30      # %  가격/포지션 기준
COST_STRESS = 0.50


def compute_signals(g: pd.DataFrame) -> pd.DataFrame:
    """
    한 코인 프레임(assign_segments 적용됨)에 S1~S5 불리언 컬럼을 붙인다.
    지표는 **구간별로** 계산해 이어 붙인다.
    """
    g = g.copy()
    n = len(g)
    for s in SIGNALS:
        g[s] = False

    for seg, sg in g.groupby("segment", sort=True):
        i0 = sg.index[0]
        c = sg["close"].to_numpy(float)
        o = sg["open"].to_numpy(float)
        h_ = sg["high"].to_numpy(float)
        l_ = sg["low"].to_numpy(float)
        v = sg["volume"].to_numpy(float)
        m = len(sg)
        if m < WARMUP_BARS + 2:
            continue

        # ── S1: MACD 히스토그램 0선 상향 돌파 ──────────────────────────
        hist = macd_hist(c, 12, 26, 9)
        s1 = np.zeros(m, dtype=bool)
        prev, cur = hist[:-1], hist[1:]
        ok = ~np.isnan(prev) & ~np.isnan(cur)
        s1[1:] = ok & (prev < 0) & (cur > 0)     # 강부등호 — 정확히 0 은 신호 아님

        # ── S2: ADX(14) > 25  AND  종가 > EMA200 ───────────────────────
        _, _, _, a = adx(h_, l_, c, 14)
        e200 = ema(c, 200)
        s2 = (~np.isnan(a)) & (~np.isnan(e200)) & (a > 25.0) & (c > e200)

        # ── S3: RSI(14) 과매도 최초 진입 ────────────────────────────────
        r = rsi(c, 14)
        s3 = np.zeros(m, dtype=bool)
        pr, cr = r[:-1], r[1:]
        ok = ~np.isnan(pr) & ~np.isnan(cr)
        s3[1:] = ok & (pr >= 30.0) & (cr < 30.0)

        # ── S4: 볼린저 하단 이탈 후 복귀 ────────────────────────────────
        lower = bollinger_lower(c, 20, 2.0)
        s4 = np.zeros(m, dtype=bool)
        pc, cc = c[:-1], c[1:]
        pl, cl = lower[:-1], lower[1:]
        ok = ~np.isnan(pl) & ~np.isnan(cl)
        s4[1:] = ok & (pc < pl) & (cc >= cl)     # 이탈은 강, 복귀는 약

        # ── S5: 거래량 급증 양봉 ────────────────────────────────────────
        vma = volume_ma_excl_current(v, 20)
        s5 = (~np.isnan(vma)) & (v > 2.0 * vma) & (c > o)

        for name, arr in (("S1", s1), ("S2", s2), ("S3", s3), ("S4", s4), ("S5", s5)):
            g.loc[i0:i0 + m - 1, name] = arr
    return g


def forward_return(g: pd.DataFrame, h: int) -> np.ndarray:
    """r = (O[t+1+h] − O[t+1]) / O[t+1]  (비용 차감 전, %). 부적격은 NaN."""
    o = g["open"].to_numpy(float)
    n = len(o)
    out = np.full(n, np.nan)
    e = np.full(n, np.nan)
    x = np.full(n, np.nan)
    if n > h + 1:
        e[:n - h - 1] = o[1:n - h]
        x[:n - h - 1] = o[1 + h:n]
    with np.errstate(invalid="ignore", divide="ignore"):
        out = (x - e) / e * 100.0
    return out


def select_nonoverlapping(idx: np.ndarray, h: int) -> np.ndarray:
    """
    비중첩 채택 (§4). 시간순으로 훑으며 t' ≥ t_prev + h 인 것만 채택.
    `idx` 는 신호가 성립하고 적격인 봉의 **정수 위치** 오름차순 배열.
    """
    kept = []
    last = None
    for t in idx:
        if last is None or t >= last + h:
            kept.append(t)
            last = t
    return np.asarray(kept, dtype=int)


def build_observations(cost: float = COST_MAIN) -> pd.DataFrame:
    """
    전 코인 × 전 신호 × 전 h 의 **채택 관측치**와, 같은 규칙의 **비교군**을 만든다.

    반환 컬럼:
        coin, signal, h, t_idx, time, ret(비용차감 %), group('signal'|'baseline'), gap20
    """
    df = load_candles()
    rows = []

    for coin, g0 in df.groupby("coin_pair", sort=True):
        g = assign_segments(g0)
        g = compute_signals(g)
        gap20 = gap_in_lookback(g, 20)
        times = g["time"].to_numpy()

        for h in HORIZONS:
            elig = eligible_mask(g, h)
            fr = forward_return(g, h) - cost          # 비용 차감

            # 비교군 — 같은 적격 규칙·같은 체결·같은 비용을 적용한 **모든 적격 봉**
            base_idx = np.flatnonzero(elig & ~np.isnan(fr))
            for t in base_idx:
                rows.append((coin, "BASE", h, int(t), times[t], fr[t], "baseline", bool(gap20[t])))

            for s in SIGNALS:
                sig = g[s].to_numpy(bool)
                cand = np.flatnonzero(sig & elig & ~np.isnan(fr))
                kept = select_nonoverlapping(cand, h)
                for t in kept:
                    rows.append((coin, s, h, int(t), times[t], fr[t], "signal", bool(gap20[t])))

    return pd.DataFrame(rows, columns=["coin", "signal", "h", "t_idx", "time",
                                       "ret", "group", "gap20"])


def main() -> int:
    obs = build_observations()
    CACHE = Path(__file__).resolve().parent / "cache"
    obs.to_csv(CACHE / "observations.csv.gz", index=False, compression="gzip")
    print(f"✓ 관측치 저장 ({len(obs):,} 행)")
    print()

    sig = obs[obs["group"] == "signal"]
    print("채택된 비중첩 신호 수 (코인 × 신호, h 별)")
    for h in HORIZONS:
        print(f"\n── h = {h} " + "─" * 60)
        piv = (sig[sig["h"] == h]
               .pivot_table(index="coin", columns="signal", values="t_idx", aggfunc="count")
               .fillna(0).astype(int))
        print(piv.to_string())

    print("\n\n비교군(적격 봉) 수")
    base = obs[obs["group"] == "baseline"]
    print(base.pivot_table(index="coin", columns="h", values="t_idx",
                           aggfunc="count").to_string())

    print("\n\n직전 20봉에 허용 공백이 포함된 채택 신호 비율 (데이터 품질 지표)")
    q = (sig.groupby(["signal", "h"])["gap20"].mean() * 100).unstack()
    print(q.round(3).to_string())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
