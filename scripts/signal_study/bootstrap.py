# -*- coding: utf-8 -*-
"""
패널 시간 블록 부트스트랩 — SIGNAL_STUDY_PREREG.md v5 §6

🔴 가짜 가격 경로를 만들지 않는다
--------------------------------
블록을 이어 붙여 합성 가격을 만들고 거기서 지표·미래수익을 다시 계산하면
이음매에서 왜곡된다. 순서는 반드시:

    1. 원래 시간축에서 지표 → 신호 → 미래수익 → 비중첩 채택을 전부 계산  (signals.py)
    2. 그 결과로 나온 **관측치**를 시간 블록 단위로 재표집             (이 파일)

절차
----
    1) 연구 기간을 길이 L 의 연속·비중첩 시간 블록으로 분할 (블록 수 K)
    2) 복제마다 블록 K개를 복원추출
    3) 뽑힌 블록에 **신호 봉 t 가 속하는 관측치**를 수집 — 13코인 동시에
    4) 신호군과 비교군을 **같은 블록**으로 함께 재표집
    5) 통계량 재계산

3·4 가 코인 간 동시 상관과 차이의 불확실성을 보존한다.

통계량
------
    θ_절대     = 코인별 평균의 단순평균 (신호군)
    θ_기본대비 = 코인별 (신호평균 − 비교평균) 의 단순평균

동일 가중 대상은 **점추정에서 확정된 포함 코인 집합으로 고정**한다.
복제에서 포함 코인 중 하나라도 비면 **그 복제 전체를 무효** 처리한다(§6).

p값 (중심화)
-----------
    δ*_b = θ*_b − θ̂            (b ∈ 유효 복제)
    p    = (1 + #{δ*_b ≥ θ̂}) / (B_유효 + 1)
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

SEED = 20260923
B = 2000
BLOCK_MAIN = 72          # 봉(시간)
BLOCK_SENS = (24, 168)
MIN_SIGNALS_PER_COIN = 20
MIN_COINS = 6
INVALID_REPLICATE_MAX = 0.05     # 무효 복제 비율 상한


def block_id(times: np.ndarray, origin: np.datetime64, L: int) -> np.ndarray:
    """시각 → 블록 인덱스 (길이 L 시간의 연속·비중첩 블록)."""
    hours = (times - origin) / np.timedelta64(1, "h")
    return np.floor(hours / L).astype(np.int64)


def point_estimate(sig: pd.DataFrame, base: pd.DataFrame, coins: list[str]):
    """포함 코인 집합에 대한 θ_절대 · θ_기본대비."""
    a = []
    d = []
    for c in coins:
        s = sig.loc[sig["coin"] == c, "ret"]
        b = base.loc[base["coin"] == c, "ret"]
        if len(s) == 0 or len(b) == 0:
            return None, None
        a.append(s.mean())
        d.append(s.mean() - b.mean())
    return float(np.mean(a)), float(np.mean(d))


def run_bootstrap(sig: pd.DataFrame, base: pd.DataFrame, coins: list[str],
                  L: int, B: int = B, seed: int = SEED):
    """
    반환: dict(theta_abs, theta_diff, p_abs, p_diff, ci_abs, ci_diff,
               n_valid, invalid_rate)
    """
    th_a, th_d = point_estimate(sig, base, coins)
    if th_a is None:
        return None

    # 🔴 datetime64[ns] 로 명시 변환한다.
    #    tz-aware 시리즈의 to_numpy() 는 object dtype 을 돌려주어 산술이 실패한다.
    st = sig["time"].dt.tz_convert("UTC").dt.tz_localize(None).to_numpy("datetime64[ns]")
    bt = base["time"].dt.tz_convert("UTC").dt.tz_localize(None).to_numpy("datetime64[ns]")
    origin = min(st.min(), bt.min())
    sig_b = block_id(st, origin, L)
    base_b = block_id(bt, origin, L)
    blocks = np.unique(np.concatenate([sig_b, base_b]))
    K = len(blocks)

    # 블록 → (코인별 합, 개수) 를 미리 집계해 복제 루프를 가볍게 한다
    sig_g = pd.DataFrame({"coin": sig["coin"].to_numpy(), "blk": sig_b,
                          "ret": sig["ret"].to_numpy()})
    base_g = pd.DataFrame({"coin": base["coin"].to_numpy(), "blk": base_b,
                           "ret": base["ret"].to_numpy()})
    ci = {c: i for i, c in enumerate(coins)}
    bi = {b: i for i, b in enumerate(blocks)}
    nC, nB = len(coins), K

    def agg(df):
        s = np.zeros((nB, nC))
        n = np.zeros((nB, nC))
        gg = df[df["coin"].isin(ci)].groupby(["blk", "coin"])["ret"].agg(["sum", "count"])
        for (b, c), row in gg.iterrows():
            s[bi[b], ci[c]] = row["sum"]
            n[bi[b], ci[c]] = row["count"]
        return s, n

    ss, sn = agg(sig_g)
    bs, bn = agg(base_g)

    rng = np.random.default_rng(seed)
    ta = np.empty(B)
    td = np.empty(B)
    valid = np.zeros(B, dtype=bool)

    for r in range(B):
        pick = rng.integers(0, nB, size=nB)          # 블록 K개 복원추출
        s_sum = ss[pick].sum(axis=0)
        s_cnt = sn[pick].sum(axis=0)
        b_sum = bs[pick].sum(axis=0)
        b_cnt = bn[pick].sum(axis=0)
        # 🔴 포함 코인 중 하나라도 비면 복제 전체 무효
        if (s_cnt == 0).any() or (b_cnt == 0).any():
            continue
        sm = s_sum / s_cnt
        bm = b_sum / b_cnt
        ta[r] = sm.mean()
        td[r] = (sm - bm).mean()
        valid[r] = True

    nv = int(valid.sum())
    inv_rate = 1.0 - nv / B
    if nv == 0:
        return dict(theta_abs=th_a, theta_diff=th_d, p_abs=np.nan, p_diff=np.nan,
                    ci_abs=(np.nan, np.nan), ci_diff=(np.nan, np.nan),
                    n_valid=0, invalid_rate=inv_rate)

    ta_v, td_v = ta[valid], td[valid]
    # 중심화 p값
    p_a = (1 + int(np.sum((ta_v - th_a) >= th_a))) / (nv + 1)
    p_d = (1 + int(np.sum((td_v - th_d) >= th_d))) / (nv + 1)
    return dict(
        theta_abs=th_a, theta_diff=th_d, p_abs=p_a, p_diff=p_d,
        ci_abs=(float(np.percentile(ta_v, 2.5)), float(np.percentile(ta_v, 97.5))),
        ci_diff=(float(np.percentile(td_v, 2.5)), float(np.percentile(td_v, 97.5))),
        n_valid=nv, invalid_rate=inv_rate,
    )


def holm(pvals: list[float], alpha: float = 0.05):
    """Holm 보정. 반환: (조정 p 리스트, 기각 여부 리스트) — 입력 순서 유지."""
    m = len(pvals)
    order = sorted(range(m), key=lambda i: pvals[i])
    adj = [0.0] * m
    run = 0.0
    for k, i in enumerate(order):
        val = (m - k) * pvals[i]
        run = max(run, val)
        adj[i] = min(1.0, run)
    return adj, [adj[i] <= alpha for i in range(m)]
