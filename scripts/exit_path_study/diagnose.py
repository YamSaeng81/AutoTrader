# -*- coding: utf-8 -*-
"""
불명(AMBIG) 출처 분해 — 판정을 바꾸지 않는 **보고용** 진단.

불명은 두 가지에서 온다 (§4.1, §4.3)
    (가) 시작봉 불명 — 진입 시각이 봉 중간이라 그 봉의 고·저에 진입 전 움직임이 섞임
    (나) 봉내 동시접촉 — 한 봉에서 목표가와 손절가를 모두 건드림
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
import path as P
from run_study import (A_GRID, B_GRID, CELLS, CACHE, H_GRID, NCELL, STP_R,
                       TGT_R, load, make_entries, pick_tf)


def main() -> int:
    pos, series = load()
    dyn = pos[pos.session_kind == "DYN_PAPER"].reset_index(drop=True)
    entries, _ = make_entries(dyn)

    tfuse = {"M15": 0, "H1": 0, "none": 0}
    rows = []
    for H in H_GRID:
        start_amb = np.zeros(NCELL)
        bar_amb = np.zeros(NCELL)
        n = 0
        dt = np.timedelta64(H, "h")
        for coin, t_in, entry in entries:
            t_end = t_in + dt
            tf, s = pick_tf(series, coin, t_in, t_end, H)
            if s is None:
                if H == H_GRID[0]:
                    tfuse["none"] += 1
                continue
            times, o, h, l, c = s
            i0, i1, j = P.window(times, t_in, t_end)
            if i1 <= i0:
                continue
            if H == H_GRID[0]:
                tfuse[tf] += 1
            n += 1
            tgt = entry * (1 + TGT_R / 100.0)
            stp = entry * (1 - STP_R / 100.0)
            s_hi = h[j] if j >= 0 else -np.inf
            s_lo = l[j] if j >= 0 else np.inf
            sa = (s_hi >= tgt) | (s_lo <= stp)
            start_amb += sa
            code, _ = P.resolve(o[i0:i1], h[i0:i1], l[i0:i1], tgt, stp, s_hi, s_lo)
            bar_amb += (code == P.AMBIG) & ~sa
        for k in range(NCELL):
            rows.append(dict(H=H, a=CELLS[k][0], b=CELLS[k][1],
                             시작봉불명=start_amb[k] / n * 100,
                             봉내동시=bar_amb[k] / n * 100))
    d = pd.DataFrame(rows)
    print("사용 주기 (H=24 기준):", tfuse)
    print("\n불명 출처 분해 (%)")
    print(d.round(2).to_string(index=False))

    g = pd.read_csv(CACHE / "grid_results.csv")
    g["판정"] = np.where((g["비관"] > 0) & (g["낙관"] > 0), "양수",
                np.where((g["비관"] <= 0) & (g["낙관"] <= 0), "음수", "판정불가(경계갈림)"))
    print("\n§4.3 경계 판정 분류")
    print(g["판정"].value_counts().to_string())
    print("\n판정불가 칸 — 비관 < 0 < 낙관")
    u = g[g["판정"] == "판정불가(경계갈림)"]
    print(u[["H", "a", "b", "불명", "비관", "낙관"]].round(2).to_string(index=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
