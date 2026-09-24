# -*- coding: utf-8 -*-
"""
시작봉 M1 해소 — docs/EXIT_PATH_STUDY_PREREG.md **v3.2 개정**

개정 내용 (§4.1)
    v3.1 은 진입을 포함한 부분 봉을 통째로 **불명**으로 돌렸다.
    v3.2 는 그 구간만 **M1 으로 세분**한다 — §4.3 이 H1→M15 에 대해 정한 세분 원칙을
    한 단계 더 적용하는 것이다.

        진입 후 완전한 M1 봉들  → §4.2 규칙으로 순서대로 판정 (주 창 앞에 이어 붙인다)
        진입을 포함한 M1 봉     → 남은 불명 (최대 1분)

🔴 **격자·비용·판정 기준은 그대로다** (§7). 바뀌는 것은 시작봉의 해상도뿐이다.
🔴 **M1 도 완전한 해결이 아니다.** 진입이 분봉 중간이면 그 1분 안의 선후는 여전히 모른다.
   더 세밀한 체결 데이터가 없으므로 남은 불명은 **그대로 보고한다.**
⚠️ 업비트가 생략한 무거래 분봉은 체결이 없었다는 뜻이므로 접촉 없음으로 본다.
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
from run_study import (A_GRID, B_GRID, BLOCK_MAIN_D, BLOCK_SENS_D, B_BOOT,  # noqa: E402
                       CACHE, CELLS, COST_MAIN, H_GRID, NCELL, SEED, STP_R,
                       TGT_R, block_ids, boot_ci, load, make_entries,
                       neighbours, pick_tf)

ONE_MIN = np.timedelta64(1, "m")


def load_m1():
    f = CACHE / "candles_M1.csv.gz"
    if not f.exists():
        print("✗ candles_M1.csv.gz 가 없다. fetch_m1.py 를 먼저 돌린다.")
        raise SystemExit(1)
    df = pd.read_csv(f)
    df["time"] = pd.to_datetime(df["time"], utc=True, format="ISO8601")
    out = {}
    for coin, g in df.groupby("coin_pair", sort=False):
        g = g.sort_values("time")
        out[coin] = (g["time"].dt.tz_convert("UTC").dt.tz_localize(None)
                      .to_numpy("datetime64[ns]"),
                     g["open"].to_numpy(float), g["high"].to_numpy(float),
                     g["low"].to_numpy(float))
    return out


def evaluate(entries, series, m1, H, cost):
    """반환 (pess, opt, code, resid) — resid: 남은 불명(=진입 포함 M1 봉) 여부."""
    n = len(entries)
    pess = np.full((n, NCELL), np.nan)
    opt = np.full((n, NCELL), np.nan)
    codes = np.full((n, NCELL), -1, dtype=np.int8)
    resid = np.zeros((n, NCELL), dtype=bool)
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

        O, Hh, Ll = o[i0:i1], h[i0:i1], l[i0:i1]
        s_hi, s_lo = -np.inf, np.inf

        if j >= 0:                                   # 부분 시작 봉이 있다
            mm = m1.get(coin)
            bar_end = times[i0]                      # 다음 주 봉의 시작 = 시작봉의 끝
            if mm is not None:
                mt, mo, mh, ml = mm
                k0 = int(np.searchsorted(mt, t_in, side="left"))
                k1 = int(np.searchsorted(mt, bar_end, side="left"))
                if k1 > k0:                          # 진입 후 완전한 M1 봉들
                    O = np.concatenate([mo[k0:k1], O])
                    Hh = np.concatenate([mh[k0:k1], Hh])
                    Ll = np.concatenate([ml[k0:k1], Ll])
                kp = k0 - 1                          # 진입을 포함한 M1 봉
                if kp >= 0 and mt[kp] < t_in and t_in < mt[kp] + ONE_MIN:
                    s_hi, s_lo = mh[kp], ml[kp]
                # 그 분봉이 없으면(무거래) 접촉 없음 → ±inf 유지
            else:
                s_hi, s_lo = h[j], l[j]              # M1 미확보 → v3.1 과 동일

        code, fill = P.resolve(O, Hh, Ll, tgt, stp, s_hi, s_lo)
        codes[i] = code
        resid[i] = (code == P.AMBIG)

        end_close = c[i1 - 1]
        r_none = (end_close - entry) / entry * 100.0 - cost
        r_stp = (fill - entry) / entry * 100.0 - cost
        r = np.where(code == P.TARGET, TGT_R - cost,
            np.where(code == P.STOP, r_stp, r_none))
        amb = code == P.AMBIG
        pess[i] = np.where(amb, -STP_R - cost, r)
        opt[i] = np.where(amb, TGT_R - cost, r)
    return pess, opt, codes, resid


def main() -> int:
    pos, series = load()
    m1 = load_m1()
    dyn = pos[pos.session_kind == "DYN_PAPER"].reset_index(drop=True)
    entries, tns = make_entries(dyn)
    rng = np.random.default_rng(SEED)

    print("=" * 96)
    print("시작봉 M1 해소 재계산 — PREREG v3.2 (격자·비용·판정 기준 불변)")
    print(f"  M1 {sum(len(v[0]) for v in m1.values()):,}캔들 / {len(m1)}코인")
    print("=" * 96)

    old = pd.read_csv(CACHE / "grid_results.csv")
    rows = []
    stats = {}
    for H in H_GRID:
        pe_, op_, cd, _ = evaluate(entries, series, m1, H, COST_MAIN)
        pe = np.nanmean(pe_, axis=0)
        op = np.nanmean(op_, axis=0)
        lows = {}
        for days in (BLOCK_MAIN_D, *BLOCK_SENS_D):
            lows[days] = boot_ci(pe_, block_ids(tns, days), rng, B=B_BOOT)
        stats[H] = (pe, op, lows)
        for k in range(NCELL):
            col = cd[:, k]
            v = col[col >= 0]
            rows.append(dict(H=H, a=CELLS[k][0], b=CELLS[k][1], n=len(v),
                             목표=np.mean(v == P.TARGET) * 100,
                             손절=np.mean(v == P.STOP) * 100,
                             미도달=np.mean(v == P.NONE) * 100,
                             불명=np.mean(v == P.AMBIG) * 100,
                             비관=pe[k], 낙관=op[k],
                             하한3d=lows[3][0][k], 하한1d=lows[1][0][k],
                             하한7d=lows[7][0][k]))
    new = pd.DataFrame(rows)
    new.to_csv(CACHE / "grid_results_m1.csv", index=False)

    cmp = old[["H", "a", "b", "불명", "비관", "낙관"]].merge(
        new[["H", "a", "b", "불명", "비관", "낙관"]], on=["H", "a", "b"],
        suffixes=("_v31", "_m1"))
    cmp["불명감소"] = cmp["불명_v31"] - cmp["불명_m1"]
    print("\n불명률 · 경계 변화")
    print(cmp.round(2).to_string(index=False))

    def cls(d, ps, os_):
        return np.where((d[ps] > 0) & (d[os_] > 0), "양수",
               np.where((d[ps] <= 0) & (d[os_] <= 0), "낙관까지 비양수", "판정불가"))
    print("\n§4.3 경계 분류 변화")
    a = pd.Series(cls(cmp, "비관_v31", "낙관_v31")).value_counts()
    b = pd.Series(cls(cmp, "비관_m1", "낙관_m1")).value_counts()
    print(pd.DataFrame({"v3.1": a, "M1 해소 후": b}).fillna(0).astype(int).to_string())

    cands = []
    for H in H_GRID:
        pe, op, lows = stats[H]
        ok = (pe > 0) & (op > 0)
        for days in (BLOCK_MAIN_D, *BLOCK_SENS_D):
            ok &= lows[days][0] > 0
        for k in range(NCELL):
            if ok[k] and all(ok[j] for j in neighbours(k)):
                cands.append((H, k))
    print(f"\n§5.4 후보: {len(cands)}개")
    for H, k in cands:
        print(f"  H={H} a={CELLS[k][0]:g}% b={CELLS[k][1]:g}%")

    print("\n" + "=" * 96)
    print(f"판정: {'후보 없음' if not cands else '후보 ' + str(len(cands)) + '개 — 위약 재점검 필요'}")
    print(f"  남은 불명(진입 포함 1분봉) 최대 {new['불명'].max():.1f}% · 평균 {new['불명'].mean():.1f}%")
    print("=" * 96)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
