# -*- coding: utf-8 -*-
"""
진입 후 경로 분석 — docs/EXIT_PATH_STUDY_PREREG.md v3

🔴 **한 번만 실행한다.** 결과를 보고 격자·비용·블록·판정 기준을 고치지 않는다 (§7).

    PGPASSWORD 불필요 (캐시 사용).  python scripts/exit_path_study/run_study.py
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
import path as P  # noqa: E402

CACHE = Path(__file__).resolve().parent / "cache"

# ── 사전 고정 상수 (§3 §5) ────────────────────────────────────────────────
A_GRID = np.array([0.5, 1.0, 2.0, 3.0, 5.0])      # 목표 %
B_GRID = np.array([1.0, 2.0, 3.0, 5.0])           # 손절 %
H_GRID = [24, 48, 72]                              # 봉(시간)
COST_MAIN = 0.30
COST_STRESS = 0.50
BLOCK_MAIN_D = 3
BLOCK_SENS_D = (1, 7)
B_BOOT = 2000
R_PLACEBO = 200
SEED = 20260923
M15_COVERAGE_MIN = 0.90

CELLS = [(a, b) for a in A_GRID for b in B_GRID]   # 20
TGT_R = np.array([a for a, _ in CELLS])
STP_R = np.array([b for _, b in CELLS])
NCELL = len(CELLS)


# ── 데이터 ────────────────────────────────────────────────────────────────
def load():
    pos = pd.read_csv(CACHE / "positions.csv.gz")
    for c in ("opened_at", "closed_at"):
        pos[c] = pd.to_datetime(pos[c], utc=True, format="ISO8601")
    series = {}
    for tf in ("H1", "M15"):
        df = pd.read_csv(CACHE / f"candles_{tf}.csv.gz")
        df["time"] = pd.to_datetime(df["time"], utc=True, format="ISO8601")
        for coin, g in df.groupby("coin_pair", sort=False):
            g = g.sort_values("time")
            series[(coin, tf)] = (
                g["time"].dt.tz_convert("UTC").dt.tz_localize(None)
                 .to_numpy("datetime64[ns]"),
                g["open"].to_numpy(float), g["high"].to_numpy(float),
                g["low"].to_numpy(float), g["close"].to_numpy(float))
    return pos, series


def pick_tf(series, coin, t_in, t_end, H):
    """가용한 가장 짧은 주기. M15 커버리지가 90% 미만이면 H1."""
    s = series.get((coin, "M15"))
    if s is not None:
        i0, i1, _ = P.window(s[0], t_in, t_end)
        if (i1 - i0) >= M15_COVERAGE_MIN * H * 4:
            return "M15", s
    return "H1", series.get((coin, "H1"))


def evaluate(entries, series, H, cost):
    """
    entries: list of (coin, t_in(datetime64[ns]), entry_price)
    반환 (pnl_pess, pnl_opt, code) — 각 (n, NCELL).  창이 없으면 그 행은 NaN.
    """
    n = len(entries)
    pess = np.full((n, NCELL), np.nan)
    opt = np.full((n, NCELL), np.nan)
    codes = np.full((n, NCELL), -1, dtype=np.int8)
    dt = np.timedelta64(H, "h")

    for i, (coin, t_in, entry) in enumerate(entries):
        t_end = t_in + dt
        tf, s = pick_tf(series, coin, t_in, t_end, H)
        if s is None:
            continue
        times, o, h, l, c = s
        i0, i1, j = P.window(times, t_in, t_end)
        if i1 <= i0:
            continue
        tgt = entry * (1 + TGT_R / 100.0)
        stp = entry * (1 - STP_R / 100.0)
        s_hi = h[j] if j >= 0 else -np.inf
        s_lo = l[j] if j >= 0 else np.inf
        code, fill = P.resolve(o[i0:i1], h[i0:i1], l[i0:i1], tgt, stp, s_hi, s_lo)
        codes[i] = code

        end_close = c[i1 - 1]
        r_none = (end_close - entry) / entry * 100.0 - cost
        r_tgt = TGT_R - cost
        r_stp = (fill - entry) / entry * 100.0 - cost          # fill ≤ 손절가
        r = np.where(code == P.TARGET, r_tgt,
            np.where(code == P.STOP, r_stp, r_none))
        amb = code == P.AMBIG
        pess[i] = np.where(amb, -STP_R - cost, r)              # 불명 → 손절
        opt[i] = np.where(amb, TGT_R - cost, r)                # 불명 → 목표
    return pess, opt, codes


# ── 블록 부트스트랩 (§5.1) ────────────────────────────────────────────────
def block_ids(t_in_ns: np.ndarray, days: int) -> np.ndarray:
    origin = t_in_ns.min().astype("datetime64[D]").astype("datetime64[ns]")
    d = (t_in_ns - origin) / np.timedelta64(1, "D")
    return np.floor(d / days).astype(np.int64)


def boot_ci(vals: np.ndarray, blk: np.ndarray, rng, B=B_BOOT):
    """vals (n, NCELL) → 블록 재표집 평균의 2.5/97.5 분위."""
    ok = ~np.isnan(vals[:, 0])
    vals, blk = vals[ok], blk[ok]
    ub = np.unique(blk)
    groups = [np.flatnonzero(blk == b) for b in ub]
    K = len(ub)
    out = np.empty((B, vals.shape[1]))
    for r in range(B):
        pick = rng.integers(0, K, size=K)
        idx = np.concatenate([groups[p] for p in pick])
        out[r] = vals[idx].mean(axis=0)
    return np.percentile(out, 2.5, axis=0), np.percentile(out, 97.5, axis=0)


# ── 후보 선정 절차 (§5.4) — 위약에서도 동일하게 수행 ──────────────────────
def neighbours(k: int):
    ia, ib = divmod(k, len(B_GRID))
    for da, db in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        ja, jb = ia + da, ib + db
        if 0 <= ja < len(A_GRID) and 0 <= jb < len(B_GRID):
            yield ja * len(B_GRID) + jb


def select_candidates(mean_by_block: dict) -> list[tuple[int, int]]:
    """
    mean_by_block[(H, days)] = (pess_mean, opt_mean)  각 (NCELL,)
    조건: 양쪽 경계 > 0  AND  인접칸도 양쪽 경계 > 0  AND  세 블록 길이 부호 유지
    (블록 길이는 점추정을 바꾸지 않으므로 부호 유지는 구간 하한 부호로 본다)
    """
    cands = []
    for H in H_GRID:
        pos_ok = np.ones(NCELL, dtype=bool)
        for days in (BLOCK_MAIN_D, *BLOCK_SENS_D):
            pe, op, lo_p, lo_o = mean_by_block[(H, days)]
            pos_ok &= (pe > 0) & (op > 0) & (lo_p > 0)
        for k in range(NCELL):
            if pos_ok[k] and all(pos_ok[j] for j in neighbours(k)):
                cands.append((H, k))
    return cands


B_PLACEBO = 300


def cell_stats(pess, opt, blk, rng, B=B_BOOT):
    """칸별 점추정과 세 블록 길이의 비관 경계 구간 하한."""
    ok = ~np.isnan(pess[:, 0])
    pe = np.nanmean(pess, axis=0)
    op = np.nanmean(opt, axis=0)
    lows = {}
    for days in (BLOCK_MAIN_D, *BLOCK_SENS_D):
        lo, hi = boot_ci(pess, block_ids(blk, days), rng, B=B)
        lows[days] = (lo, hi)
    return pe, op, lows, int(ok.sum())


def procedure(entries, series, blk_ns, rng, cost=COST_MAIN, B=B_BOOT):
    """§5.4 후보 선정 절차 전체. 반환 (후보 리스트, 칸별 통계 dict)."""
    stats = {}
    for H in H_GRID:
        pess, opt, codes = evaluate(entries, series, H, cost)
        pe, op, lows, n = cell_stats(pess, opt, blk_ns, rng, B=B)
        stats[H] = dict(pess=pess, opt=opt, codes=codes, pe=pe, op=op,
                        lows=lows, n=n)
    cands = []
    for H in H_GRID:
        st = stats[H]
        ok = (st["pe"] > 0) & (st["op"] > 0)
        for days in (BLOCK_MAIN_D, *BLOCK_SENS_D):
            ok &= st["lows"][days][0] > 0
        for k in range(NCELL):
            if ok[k] and all(ok[j] for j in neighbours(k)):
                cands.append((H, k))
    return cands, stats


def make_entries(pos: pd.DataFrame):
    t = pos["opened_at"].dt.tz_convert("UTC").dt.tz_localize(None).to_numpy("datetime64[ns]")
    return [(c, t[i], float(p)) for i, (c, p) in
            enumerate(zip(pos["coin_pair"], pos["entry_price"]))], t


def placebo_entries(pos, series, rng):
    """같은 코인·같은 기간, 무작위 시각. 진입가 = 그 봉의 시가."""
    lo = pos["opened_at"].min().tz_convert("UTC").tz_localize(None).to_numpy()
    hi = pos["opened_at"].max().tz_convert("UTC").tz_localize(None).to_numpy()
    ent, ts = [], []
    for coin, g in pos.groupby("coin_pair", sort=False):
        s = series.get((coin, "H1"))
        if s is None:
            continue
        times, o = s[0], s[1]
        cand = np.flatnonzero((times >= lo) & (times <= hi))
        if len(cand) == 0:
            continue
        pick = rng.choice(cand, size=min(len(g), len(cand)), replace=False)
        for j in pick:
            ent.append((coin, times[j], float(o[j])))
            ts.append(times[j])
    return ent, np.array(ts, dtype="datetime64[ns]")


def fmt_cell(k):
    return f"a={CELLS[k][0]:g}%/b={CELLS[k][1]:g}%"


def main() -> int:
    pos, series = load()
    dyn = pos[pos.session_kind == "DYN_PAPER"].reset_index(drop=True)

    print("=" * 96)
    print("진입 후 경로 분석 — EXIT_PATH_STUDY_PREREG.md v3.1")
    print(f"  주 분석 DYN_PAPER {len(dyn)}건 / {dyn.coin_pair.nunique()}코인  "
          f"{dyn.opened_at.min():%Y-%m-%d} ~ {dyn.closed_at.max():%Y-%m-%d}")
    print(f"  격자 a{list(A_GRID)} × b{list(B_GRID)} × H{H_GRID} = {NCELL*len(H_GRID)}칸")
    print(f"  비용 {COST_MAIN}%  블록 주{BLOCK_MAIN_D}일/민감도{BLOCK_SENS_D}  "
          f"B={B_BOOT}  위약 R={R_PLACEBO}(B={B_PLACEBO})  시드 {SEED}")
    print("=" * 96)

    rng = np.random.default_rng(SEED)
    entries, tns = make_entries(dyn)
    cands, stats = procedure(entries, series, tns, rng)

    # ── ① 결과 비율 ──────────────────────────────────────────────────────
    print("\n① 결과 비율 · ③ 비용 차감 평균손익 (%, 가격기준)")
    rows = []
    for H in H_GRID:
        st = stats[H]
        cd = st["codes"]
        for k in range(NCELL):
            col = cd[:, k]
            val = col[col >= 0]
            n = max(len(val), 1)
            rows.append(dict(
                H=H, a=CELLS[k][0], b=CELLS[k][1], n=len(val),
                목표=np.mean(val == P.TARGET) * 100,
                손절=np.mean(val == P.STOP) * 100,
                미도달=np.mean(val == P.NONE) * 100,
                불명=np.mean(val == P.AMBIG) * 100,
                비관=st["pe"][k], 낙관=st["op"][k],
                하한3d=st["lows"][3][0][k], 상한3d=st["lows"][3][1][k],
                하한1d=st["lows"][1][0][k], 하한7d=st["lows"][7][0][k]))
    grid = pd.DataFrame(rows)
    grid.to_csv(CACHE / "grid_results.csv", index=False)
    with pd.option_context("display.width", 200):
        print(grid.round(2).to_string(index=False))

    print("\n③ 양수 칸 (점추정 기준)")
    posc = grid[(grid["비관"] > 0) & (grid["낙관"] > 0)]
    print(f"  양쪽 경계 모두 > 0 인 칸: {len(posc)} / {len(grid)}")
    if len(posc):
        print(posc[["H", "a", "b", "비관", "낙관", "하한3d", "하한1d", "하한7d"]]
              .round(3).to_string(index=False))

    print(f"\n§5.4 후보 (양쪽경계 + 3개 블록 하한 + 인접칸): {len(cands)}개")
    for H, k in cands:
        print(f"  H={H} {fmt_cell(k)}")

    # ── ⑥ 위약 진입 대비 + §5.3 위약 후보 발생률 ──────────────────────────
    print(f"\n§5.3 위약 탐색 {R_PLACEBO}회 — 절차 전체 반복", flush=True)
    hits = 0
    pl_pe = {H: [] for H in H_GRID}
    for r in range(R_PLACEBO):
        pe_, pts = placebo_entries(dyn, series, rng)
        c2, s2 = procedure(pe_, series, pts, rng, B=B_PLACEBO)
        if c2:
            hits += 1
        for H in H_GRID:
            pl_pe[H].append(s2[H]["pe"])
        if (r + 1) % 25 == 0:
            print(f"    {r+1}/{R_PLACEBO}  누적 후보 발생 {hits}", flush=True)
    rate = hits / R_PLACEBO
    print(f"\n  위약 후보 발생률 = {hits}/{R_PLACEBO} = {rate:.3f}")

    print("\n⑥ 위약 진입 대비 (실제 비관 − 위약 평균 비관)")
    for H in H_GRID:
        pm = np.mean(np.array(pl_pe[H]), axis=0)
        d = stats[H]["pe"] - pm
        best = int(np.argmax(d))
        print(f"  H={H}  최대 우위 {fmt_cell(best)}  실제 {stats[H]['pe'][best]:+.3f}%  "
              f"위약 {pm[best]:+.3f}%  차 {d[best]:+.3f}%p  "
              f"(우위 칸 {int((d>0).sum())}/{NCELL})")

    # ── 판정 ─────────────────────────────────────────────────────────────
    print("\n" + "=" * 96)
    if not cands:
        verdict = "후보 없음"
        why = "60칸 어디서도 §5.4 선정 조건을 만족하지 못했다."
    elif rate <= 0.05:
        verdict = "후보 있음"
        why = f"후보 {len(cands)}개, 위약 후보 발생률 {rate:.3f} ≤ 0.05."
    else:
        verdict = "판단 보류"
        why = f"후보 {len(cands)}개가 나왔으나 위약 발생률 {rate:.3f} > 0.05 — 우연과 구분되지 않는다."
    print(f"판정: {verdict}")
    print(f"  {why}")
    print("=" * 96)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
