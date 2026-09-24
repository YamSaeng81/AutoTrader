# -*- coding: utf-8 -*-
"""
연속 구간(segment) 분할과 적격 봉 판정 — SIGNAL_STUDY_PREREG.md v5 §5

구간 규칙
--------
직전 봉과의 시간 간격을 Δ(시간)라 하면, 결측 봉 수는 Δ−1 이다.

    Δ ≤ 6   →  같은 구간 (이어서 계산)
    Δ > 6   →  새 구간   (지표 재초기화 + 워밍업 재적용)

워밍업
------
구간 시작부터 700봉은 버린다. 근거는 EMA200 의 초기화 민감도다:

    (1 − 2/201)^50  ≈ 0.607        ← 50봉으로는 시드 차이의 61% 가 남는다
    ln(0.01)/ln(1 − 2/201) ≈ 461   ← 1% 미만까지 필요한 갱신 횟수
    200(SMA 시드) + 461 = 661  →  여유를 두어 700

적격 봉
------
    ① 구간 내 인덱스 ≥ 700                       (지표 연속성)
    ② t+1 … t+1+h 가 같은 구간에 존재하고
       연속 간격이 **정확히 1시간**               (체결 연속성 — 엄격)

①과 ②는 층위가 다르다. ①은 Δ ≤ 6 을 허용하고 ②는 허용하지 않는다.
②는 실제 체결을 모사하므로 결측을 허용할 수 없다.
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

GAP_TOLERANCE_H = 6      # Δ ≤ 6 이면 같은 구간
WARMUP_BARS = 700

CACHE = Path(__file__).resolve().parent / "cache"


def load_candles() -> pd.DataFrame:
    df = pd.read_csv(CACHE / "candles_h1.csv.gz", parse_dates=["time"])
    df["time"] = pd.to_datetime(df["time"], utc=True)
    return df.sort_values(["coin_pair", "time"]).reset_index(drop=True)


def assign_segments(g: pd.DataFrame) -> pd.DataFrame:
    """한 코인의 시계열에 segment id 와 구간 내 인덱스를 붙인다."""
    g = g.sort_values("time").reset_index(drop=True).copy()
    dt_h = g["time"].diff().dt.total_seconds() / 3600.0
    new_seg = (dt_h > GAP_TOLERANCE_H) | dt_h.isna()
    g["segment"] = new_seg.cumsum().astype(int)
    g["seg_idx"] = g.groupby("segment").cumcount()
    # 허용된 공백(Δ ≥ 2) 표시 — 데이터 품질 보고용 (§5)
    g["gap_before"] = (dt_h >= 2) & (dt_h <= GAP_TOLERANCE_H)
    g["gap_before"] = g["gap_before"].fillna(False)
    return g


def eligible_mask(g: pd.DataFrame, h: int) -> np.ndarray:
    """
    적격 봉 t 마스크. g 는 assign_segments 를 거친 한 코인의 프레임.

    ② 체결 연속성: t+1 … t+1+h 가 같은 구간이고 간격이 **정확히 1시간**.
       즉 t 부터 t+1+h 까지 (h+1) 개 연속 간격이 모두 1시간이어야 한다.
    """
    n = len(g)
    seg = g["segment"].to_numpy()
    t = g["time"].to_numpy()
    idx = g["seg_idx"].to_numpy()

    ok = np.zeros(n, dtype=bool)
    need = h + 1                       # t → t+1+h 까지의 간격 수
    hour_ns = np.timedelta64(3600, "s")

    for i in range(n):
        if idx[i] < WARMUP_BARS:                 # ① 지표 연속성
            continue
        j = i + need
        if j >= n:
            continue
        if seg[j] != seg[i]:
            continue
        # t[i] 부터 t[j] 까지 정확히 need 시간이어야 한다 (중간 결측 없음)
        if (t[j] - t[i]) != hour_ns * need:
            continue
        ok[i] = True
    return ok


def gap_in_lookback(g: pd.DataFrame, lookback: int = 20) -> np.ndarray:
    """
    직전 `lookback` 봉 안에 허용된 공백이 있는가 (§5 데이터 품질 보고용).

    ⚠️ 이 비율이 작다고 영향이 미미하다는 보장은 없다 — EMA·Wilder 는 재귀적이라
       룩백 밖 공백의 영향도 남는다. **품질 설명 지표로만** 쓴다.
    """
    gb = g["gap_before"].to_numpy()
    n = len(gb)
    out = np.zeros(n, dtype=bool)
    c = np.concatenate([[0], np.cumsum(gb.astype(int))])
    for i in range(n):
        lo = max(0, i - lookback + 1)
        out[i] = (c[i + 1] - c[lo]) > 0
    return out


def main() -> int:
    df = load_candles()
    print("=" * 78)
    print("연속 구간 분할 — SIGNAL_STUDY_PREREG.md v5 §5")
    print(f"  Δ ≤ {GAP_TOLERANCE_H}h → 같은 구간 / 워밍업 {WARMUP_BARS}봉")
    print("=" * 78)
    print(f"{'코인':<10} {'행':>8} {'구간':>5} {'최장구간':>9} {'워밍업후':>9} "
          f"{'가용률':>7} {'공백(Δ≥2)':>10}")
    print("-" * 78)

    tot_avail = tot_rows = 0
    for coin, g0 in df.groupby("coin_pair", sort=True):
        g = assign_segments(g0)
        nseg = g["segment"].nunique()
        longest = g.groupby("segment").size().max()
        after = int((g["seg_idx"] >= WARMUP_BARS).sum())
        gaps = int(g["gap_before"].sum())
        rate = after / len(g) * 100
        tot_avail += after
        tot_rows += len(g)
        print(f"{coin:<10} {len(g):>8,} {nseg:>5} {longest:>9,} {after:>9,} "
              f"{rate:>6.1f}% {gaps:>10,}")

    print("-" * 78)
    print(f"{'합계':<10} {tot_rows:>8,} {'':>5} {'':>9} {tot_avail:>9,} "
          f"{tot_avail/tot_rows*100:>6.1f}%")
    print()

    # h 별 적격 봉 수 (체결 연속성까지 적용)
    print("h 별 적격 봉 수 (체결 연속성 포함)")
    print("-" * 78)
    print(f"{'코인':<10} " + "".join(f"{'h=' + str(h):>12}" for h in (4, 12, 24)))
    print("-" * 78)
    for coin, g0 in df.groupby("coin_pair", sort=True):
        g = assign_segments(g0)
        row = f"{coin:<10} "
        for h in (4, 12, 24):
            row += f"{int(eligible_mask(g, h).sum()):>12,}"
        print(row)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
