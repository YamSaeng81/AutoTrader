# -*- coding: utf-8 -*-
"""
신호 유효성 선별 실험 — 지표 구현 (docs/SIGNAL_STUDY_PREREG.md v5 §3)

저장소(Java)와 **독립적으로** 구현한다. 이 실험은 저장소와 무관하게 재현 가능해야 하고,
특히 ADX 는 저장소 구현이 단순평균(Wave 3-K 중단)이라 개념을 정확히 구현한 쪽으로 검정한다.

⚠️ 모든 함수는 **하나의 연속 구간(segment)** 을 입력으로 받는다.
   구간 경계와 워밍업은 호출자(§5)가 처리한다. 여기서는 시계열이 등간격이라고 가정한다.

반환 규약
---------
모든 함수는 입력과 같은 길이의 배열을 돌려주고, 값이 정의되지 않는 앞부분은 NaN 이다.
"NaN 이면 그 시점에 지표가 없다" 는 뜻이며, 호출자는 NaN 을 신호 판정에서 제외해야 한다.
"""
from __future__ import annotations

import numpy as np


# ──────────────────────────────────────────────────────────────────────────
# 기본 평활
# ──────────────────────────────────────────────────────────────────────────

def sma(x: np.ndarray, n: int) -> np.ndarray:
    """단순이동평균. 앞 n-1 개는 NaN."""
    x = np.asarray(x, dtype=float)
    out = np.full(x.shape, np.nan)
    if len(x) < n:
        return out
    c = np.cumsum(np.insert(x, 0, 0.0))
    out[n - 1:] = (c[n:] - c[:-n]) / n
    return out


def ema(x: np.ndarray, n: int) -> np.ndarray:
    """
    지수이동평균. **첫 n 개의 SMA 를 시드**로 쓰고 이후 alpha = 2/(n+1) 로 갱신한다.

    시드 방식이 결과를 바꾸므로 사전 등록 §3 의 규약을 그대로 따른다.
    앞 n-1 개는 NaN, index n-1 이 시드(SMA), 그 뒤부터 재귀.
    """
    x = np.asarray(x, dtype=float)
    out = np.full(x.shape, np.nan)
    if len(x) < n:
        return out
    alpha = 2.0 / (n + 1.0)
    out[n - 1] = x[:n].mean()
    for i in range(n, len(x)):
        out[i] = alpha * x[i] + (1.0 - alpha) * out[i - 1]
    return out


def wilder_smooth(x: np.ndarray, n: int) -> np.ndarray:
    """
    Wilder 평활 — **첫 n 개의 합**을 시드로, 이후 S_t = S_{t-1} - S_{t-1}/n + x_t.

    ⚠️ 평균이 아니라 **합** 기준이다. ATR/ADX 원전(Wilder)의 정의이며,
       +DI = 100 * 평활(+DM) / 평활(TR) 처럼 **비율**로 쓰이므로 합/평균 어느 쪽이든
       같은 결과가 나오지만, 시드를 합으로 두는 쪽이 원전 표기와 일치한다.

    NaN 입력(선행 구간)은 시드 계산에서 그대로 전파된다 — 호출자가 잘라서 넘겨야 한다.
    """
    x = np.asarray(x, dtype=float)
    out = np.full(x.shape, np.nan)
    if len(x) < n:
        return out
    out[n - 1] = np.sum(x[:n])
    for i in range(n, len(x)):
        out[i] = out[i - 1] - out[i - 1] / n + x[i]
    return out


def pop_std(x: np.ndarray, n: int) -> np.ndarray:
    """모집단 표준편차(n 으로 나눔) 이동값. 앞 n-1 개는 NaN."""
    x = np.asarray(x, dtype=float)
    out = np.full(x.shape, np.nan)
    if len(x) < n:
        return out
    for i in range(n - 1, len(x)):
        w = x[i - n + 1: i + 1]
        out[i] = np.sqrt(np.mean((w - w.mean()) ** 2))
    return out


# ──────────────────────────────────────────────────────────────────────────
# 신호용 지표
# ──────────────────────────────────────────────────────────────────────────

def macd_hist(close: np.ndarray, fast: int = 12, slow: int = 26, signal: int = 9) -> np.ndarray:
    """
    MACD 히스토그램 = (EMA_fast - EMA_slow) - EMA_signal(그 차이).

    ⚠️ signal EMA 는 MACD 선이 **정의된 구간부터** 계산한다.
       NaN 을 그대로 넣으면 시드가 오염된다.
    """
    close = np.asarray(close, dtype=float)
    macd = ema(close, fast) - ema(close, slow)
    out = np.full(close.shape, np.nan)
    valid = ~np.isnan(macd)
    if valid.sum() < signal:
        return out
    start = int(np.argmax(valid))          # 첫 유효 인덱스
    sig_part = ema(macd[start:], signal)
    out[start:] = macd[start:] - sig_part
    return out


def rsi(close: np.ndarray, n: int = 14) -> np.ndarray:
    """
    Wilder RSI. 첫 n 개 변화량의 단순평균을 시드로 쓴다.

    ⚠️ 무변동(avg_gain = avg_loss = 0)은 **50(중립)** 으로 둔다.
       0 나눗셈을 100(과매수)으로 처리하는 것은 방향이 없는 것을 최고 과매수로 읽는 오류다
       (저장소가 2026-09-21 Wave 3-H 에서 고친 것과 같은 판단).
    """
    close = np.asarray(close, dtype=float)
    out = np.full(close.shape, np.nan)
    if len(close) < n + 1:
        return out
    d = np.diff(close)
    gain = np.where(d > 0, d, 0.0)
    loss = np.where(d < 0, -d, 0.0)

    ag = gain[:n].mean()
    al = loss[:n].mean()

    def _rsi(g: float, l: float) -> float:
        if g == 0.0 and l == 0.0:
            return 50.0
        if l == 0.0:
            return 100.0
        rs = g / l
        return 100.0 - 100.0 / (1.0 + rs)

    out[n] = _rsi(ag, al)                   # d[i] 는 close[i+1] 에 대응
    for i in range(n, len(d)):
        ag = (ag * (n - 1) + gain[i]) / n
        al = (al * (n - 1) + loss[i]) / n
        out[i + 1] = _rsi(ag, al)
    return out


def bollinger_lower(close: np.ndarray, n: int = 20, k: float = 2.0) -> np.ndarray:
    """볼린저 하단 = SMA(n) - k * 모집단표준편차(n)."""
    return sma(close, n) - k * pop_std(close, n)


def volume_ma_excl_current(volume: np.ndarray, n: int = 20) -> np.ndarray:
    """
    **현재 봉을 제외한** 직전 n 봉 거래량 평균.

    현재 봉을 포함하면 급증 자체가 평균을 끌어올려 조건이 둔해진다(§3 S5).
    index i 의 값은 volume[i-n : i] 의 평균이다. 앞 n 개는 NaN.
    """
    volume = np.asarray(volume, dtype=float)
    out = np.full(volume.shape, np.nan)
    if len(volume) <= n:
        return out
    m = sma(volume, n)                      # m[i] = mean(volume[i-n+1 : i+1])
    out[n:] = m[n - 1:-1]                   # 한 칸 밀면 현재 봉 제외
    return out


# ──────────────────────────────────────────────────────────────────────────
# ADX — Wilder. 사전 등록 §3 S2 의 검증 대상
# ──────────────────────────────────────────────────────────────────────────

def directional_movement(high: np.ndarray, low: np.ndarray, close: np.ndarray):
    """
    TR · +DM · −DM 을 계산한다. 모두 index 0 은 NaN (직전 종가가 필요).

        upMove   = H_t - H_{t-1}
        downMove = L_{t-1} - L_t
        +DM = upMove   if (upMove > downMove and upMove > 0)   else 0
        -DM = downMove if (downMove > upMove and downMove > 0) else 0
        TR  = max(H_t - L_t, |H_t - C_{t-1}|, |L_t - C_{t-1}|)
    """
    high = np.asarray(high, dtype=float)
    low = np.asarray(low, dtype=float)
    close = np.asarray(close, dtype=float)
    m = len(high)
    tr = np.full(m, np.nan)
    pdm = np.full(m, np.nan)
    ndm = np.full(m, np.nan)
    for i in range(1, m):
        up = high[i] - high[i - 1]
        dn = low[i - 1] - low[i]
        pdm[i] = up if (up > dn and up > 0) else 0.0
        ndm[i] = dn if (dn > up and dn > 0) else 0.0
        tr[i] = max(high[i] - low[i],
                    abs(high[i] - close[i - 1]),
                    abs(low[i] - close[i - 1]))
    return tr, pdm, ndm


def adx(high: np.ndarray, low: np.ndarray, close: np.ndarray, n: int = 14):
    """
    Wilder ADX(n). (plus_di, minus_di, dx, adx) 를 돌려준다. 모두 입력과 같은 길이, 앞부분 NaN.

    첫 값이 나오는 시점
    -------------------
      TR/DM        index 1 부터
      평활(n)      index n     (TR/DM 의 첫 n 개 합)
      DX           평활이 있는 시점부터 → index n
      **ADX**      DX n 개를 모아야 하므로 **index 2n - 1**

      n=14 → 첫 ADX 는 index 27 (28번째 봉). **20봉으로는 산출 자체가 불가능하다.**

    TR 합이 0 인 경우
    -----------------
      완전 무변동 구간이다. 방향이 없으므로 +DI = -DI = 0 으로 두고 DX 는 NaN 으로 둔다.
      (DX 는 0/0 이라 정의되지 않는다. 0 으로 두면 "방향 없음"이 "추세 없음"으로
       ADX 를 끌어내려 무변동이 마치 측정된 값처럼 보인다.)
    """
    tr, pdm, ndm = directional_movement(high, low, close)
    m = len(close)

    # 평활은 index 1 부터의 시계열에 대해 수행한 뒤 원래 위치로 되돌린다
    str_ = np.full(m, np.nan)
    spdm = np.full(m, np.nan)
    sndm = np.full(m, np.nan)
    if m >= n + 1:
        str_[1:] = wilder_smooth(tr[1:], n)
        spdm[1:] = wilder_smooth(pdm[1:], n)
        sndm[1:] = wilder_smooth(ndm[1:], n)

    pdi = np.full(m, np.nan)
    ndi = np.full(m, np.nan)
    dx = np.full(m, np.nan)
    for i in range(m):
        if np.isnan(str_[i]):
            continue
        if str_[i] == 0.0:
            pdi[i] = 0.0
            ndi[i] = 0.0
            dx[i] = np.nan          # 0/0 — 정의되지 않음
            continue
        pdi[i] = 100.0 * spdm[i] / str_[i]
        ndi[i] = 100.0 * sndm[i] / str_[i]
        s = pdi[i] + ndi[i]
        dx[i] = np.nan if s == 0.0 else 100.0 * abs(pdi[i] - ndi[i]) / s

    # ADX = DX 의 Wilder 평활(평균 기준). 첫 값은 DX n 개의 단순평균.
    adx_ = np.full(m, np.nan)
    idx = [i for i in range(m) if not np.isnan(dx[i])]
    if len(idx) >= n:
        first = idx[n - 1]
        adx_[first] = np.mean([dx[i] for i in idx[:n]])
        prev = adx_[first]
        for i in idx[n:]:
            prev = (prev * (n - 1) + dx[i]) / n
            adx_[i] = prev
    return pdi, ndi, dx, adx_
