# -*- coding: utf-8 -*-
"""
⑥ 위약 비교 정정 — docs/EXIT_PATH_STUDY_PREREG.md v3.1 §5.2

🔴 최초 구현의 결함
    위약 진입을 H1 봉 **시가**에 정렬해 뽑았다. 그러면 위약에는 §4.1 의 시작봉 불명이
    아예 생기지 않는데, 실제 진입은 봉 중간이라 12~59% 가 불명이 된다.
    비관 경계에서 실제 쪽만 불명을 손절로 처리하니 **위약이 구조적으로 유리**했다.
    "전략 진입이 무작위 진입보다 나빴다"로 읽으면 안 되는 수치였다.

정정
    위약 진입 시각에 실제 진입의 **봉 내 오프셋 분포**를 그대로 입혀 불명 구조를 맞춘다.
    진입가는 해당 봉의 시가로 근사한다(그 오프셋 시점의 체결가를 알 수 없다).

판정(§5.4)은 ⑥ 을 쓰지 않고, 이번 실행에서 후보가 0개이므로 이 정정은 판정을 바꾸지 않는다.
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
from run_study import (CELLS, CACHE, COST_MAIN, H_GRID, NCELL, R_PLACEBO, SEED,
                       evaluate, load, make_entries)


def offsets_of(dyn) -> np.ndarray:
    """실제 진입의 H1 봉 내 오프셋(초)."""
    t = dyn["opened_at"].dt.tz_convert("UTC")
    return (t.dt.minute * 60 + t.dt.second).to_numpy()


def placebo_matched(dyn, series, rng, offs):
    ent, ts = [], []
    for coin, g in dyn.groupby("coin_pair", sort=False):
        s = series.get((coin, "H1"))
        if s is None:
            continue
        times, o = s[0], s[1]
        lo = dyn["opened_at"].min().tz_convert("UTC").tz_localize(None).to_numpy()
        hi = dyn["opened_at"].max().tz_convert("UTC").tz_localize(None).to_numpy()
        cand = np.flatnonzero((times >= lo) & (times <= hi))
        if len(cand) == 0:
            continue
        pick = rng.choice(cand, size=min(len(g), len(cand)), replace=False)
        for j in pick:
            off = np.timedelta64(int(rng.choice(offs)), "s")
            ent.append((coin, times[j] + off, float(o[j])))
            ts.append(times[j] + off)
    return ent, np.array(ts, dtype="datetime64[ns]")


def main() -> int:
    pos, series = load()
    dyn = pos[pos.session_kind == "DYN_PAPER"].reset_index(drop=True)
    offs = offsets_of(dyn)
    print(f"실제 진입 봉내 오프셋(초): 중앙값 {np.median(offs):.0f}  "
          f"사분위 {np.percentile(offs,25):.0f}~{np.percentile(offs,75):.0f}  최대 {offs.max()}")

    entries, _ = make_entries(dyn)
    real = {}
    for H in H_GRID:
        pe, op, _ = evaluate(entries, series, H, COST_MAIN)
        real[H] = (np.nanmean(pe, axis=0), np.nanmean(op, axis=0))

    rng = np.random.default_rng(SEED + 1)
    acc = {H: [] for H in H_GRID}
    accO = {H: [] for H in H_GRID}
    for r in range(R_PLACEBO):
        e2, _ = placebo_matched(dyn, series, rng, offs)
        for H in H_GRID:
            pe, op, _ = evaluate(e2, series, H, COST_MAIN)
            acc[H].append(np.nanmean(pe, axis=0))
            accO[H].append(np.nanmean(op, axis=0))
        if (r + 1) % 50 == 0:
            print(f"  {r+1}/{R_PLACEBO}", flush=True)

    rows = []
    for H in H_GRID:
        pm = np.array(acc[H]).mean(axis=0)
        om = np.array(accO[H]).mean(axis=0)
        for k in range(NCELL):
            rows.append(dict(H=H, a=CELLS[k][0], b=CELLS[k][1],
                             실제비관=real[H][0][k], 위약비관=pm[k],
                             차비관=real[H][0][k] - pm[k],
                             실제낙관=real[H][1][k], 위약낙관=om[k],
                             차낙관=real[H][1][k] - om[k]))
    d = pd.DataFrame(rows)
    d.to_csv(CACHE / "placebo_fair.csv", index=False)
    print("\n⑥ 위약 진입 대비 (오프셋 정합 후)")
    print(d.round(3).to_string(index=False))
    print("\n요약")
    for H in H_GRID:
        s = d[d.H == H]
        print(f"  H={H}  비관 우위칸 {int((s['차비관']>0).sum())}/{NCELL}  "
              f"평균차 {s['차비관'].mean():+.3f}%p   |   "
              f"낙관 우위칸 {int((s['차낙관']>0).sum())}/{NCELL}  "
              f"평균차 {s['차낙관'].mean():+.3f}%p")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
