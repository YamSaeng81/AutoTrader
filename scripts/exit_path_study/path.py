# -*- coding: utf-8 -*-
"""
경로 판정 엔진 — docs/EXIT_PATH_STUDY_PREREG.md v3 §4

한 봉에 대한 판정 순서 (§4.2) — **시가를 먼저 본다. 갭은 봉 내부 접촉보다 앞선다.**

    1. open ≤ 손절가           → STOP   (체결 = open)
    2. open ≥ 목표가           → TARGET (체결 = 목표가, 갭 이득 없음)
    3. high ≥ 목표가 AND low ≤ 손절가 → AMBIG
    4. high ≥ 목표가           → TARGET
    5. low  ≤ 손절가           → STOP
    아무것도 아니면 다음 봉

🔴 시작 봉 (§4.1)
    진입 시각이 봉 중간이면 그 봉의 고·저에 **진입 전 움직임**이 섞인다.
    따라서 탐색은 시작 시각이 t_in **이상**인 봉부터 한다.
    t_in 을 포함한 부분 봉은 그 전체 고·저가 목표가나 손절가에 닿았으면
    → 순서를 알 수 없으므로 AMBIG (닿지 않았으면 어느 쪽도 접촉하지 않았음이 확실).

🔴 주기 선택 (§4.3)
    §4.3 은 "H1 에서 동시 접촉이면 M15 로 해소"인데, 구현은 **처음부터 가용한
    가장 짧은 주기**를 쓴다. 결과는 같다 — M15 로 풀리는 경우는 M15 판정과 일치하고,
    M15 에서도 동시 접촉이면 어느 쪽이든 AMBIG 이다.
    M15 커버리지가 부족한 포지션만 H1 을 쓴다.
"""
from __future__ import annotations

import numpy as np

TARGET, STOP, NONE, AMBIG = 1, 2, 0, 3
_NAMES = {TARGET: "TARGET", STOP: "STOP", NONE: "NONE", AMBIG: "AMBIG"}


def name(code: int) -> str:
    return _NAMES[int(code)]


def resolve(o: np.ndarray, h: np.ndarray, l: np.ndarray,
            tgt: np.ndarray, stp: np.ndarray,
            start_hi: float, start_lo: float) -> tuple[np.ndarray, np.ndarray]:
    """
    한 포지션의 창(窓) 봉들에 대해 여러 (목표가, 손절가) 조합을 동시에 판정한다.

    o,h,l      : (n,)  창 안의 봉 (시작 시각이 t_in 이상인 것만)
    tgt, stp   : (m,)  조합별 목표가·손절가 (절대 가격)
    start_hi/lo: 부분 시작 봉의 고·저 (없으면 -inf / +inf)

    반환 (code, fill)
        code : (m,) TARGET / STOP / NONE / AMBIG
        fill : (m,) 체결가. NONE 이면 NaN (호출자가 창 끝 종가를 쓴다)
    """
    m = len(tgt)
    code = np.full(m, NONE, dtype=np.int8)
    fill = np.full(m, np.nan, dtype=float)

    # ── §4.1 부분 시작 봉: 어느 한쪽이라도 닿았으면 순서 불명 ──────────────
    start_amb = (start_hi >= tgt) | (start_lo <= stp)
    code[start_amb] = AMBIG

    if len(o) == 0:
        return code, fill
    live = ~start_amb
    if not live.any():
        return code, fill

    O = o[:, None]
    H = h[:, None]
    L = l[:, None]
    T = tgt[None, :]
    S = stp[None, :]

    gap_stop = O <= S                       # 1
    gap_tgt = (~gap_stop) & (O >= T)        # 2
    hit_t = H >= T
    hit_s = L <= S
    rest = ~gap_stop & ~gap_tgt
    both = rest & hit_t & hit_s             # 3
    only_t = rest & hit_t & ~hit_s          # 4
    only_s = rest & ~hit_t & hit_s          # 5

    bar_code = np.where(gap_stop, STOP,
               np.where(gap_tgt, TARGET,
               np.where(both, AMBIG,
               np.where(only_t, TARGET,
               np.where(only_s, STOP, NONE))))).astype(np.int8)

    decisive = bar_code != NONE
    any_dec = decisive.any(axis=0)
    first = np.argmax(decisive, axis=0)     # 첫 결정 봉

    cols = np.flatnonzero(live & any_dec)
    if len(cols):
        rows = first[cols]
        code[cols] = bar_code[rows, cols]
        # 체결가: STOP 은 min(손절가, 시가) — 갭 하향 반영. TARGET 은 목표가 정확히.
        c = code[cols]
        f = np.where(c == STOP, np.minimum(stp[cols], o[rows]),
             np.where(c == TARGET, tgt[cols], np.nan))
        fill[cols] = f
    return code, fill


def window(times: np.ndarray, t_in: np.datetime64, t_end: np.datetime64):
    """
    창 안의 봉 인덱스 구간과 부분 시작 봉 인덱스를 돌려준다.

    반환 (i0, i1, j_partial)
        봉 인덱스 [i0, i1) 이 탐색 대상 (시작 시각 t_in 이상, t_end 미만)
        j_partial 은 t_in 을 **내부에** 포함한 봉 (없으면 -1)
    """
    i0 = int(np.searchsorted(times, t_in, side="left"))
    i1 = int(np.searchsorted(times, t_end, side="left"))
    j = i0 - 1
    j_partial = j if (j >= 0 and times[j] < t_in) else -1
    return i0, i1, j_partial
