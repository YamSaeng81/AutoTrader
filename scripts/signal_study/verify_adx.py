# -*- coding: utf-8 -*-
"""
ADX 검증 — docs/SIGNAL_STUDY_PREREG.md v5 §3 S2

🔴 **이 검증을 통과하지 못하면 본 실험을 시작하지 않는다.**

| # | 검증 | 이 파일에서 |
|---|---|---|
| ① | 40봉 손계산 대조 (첫 ADX + 재귀 갱신 3회 이상) | `check_hand_reference` |
| ② | 해석적 속성 (+DI>0, −DI=0, DX=100) | `check_monotone_properties` |
| ③ | 경계 (0≤ADX≤100, TR=0 처리) | `check_bounds` |
| ④ | 저장소 구현과의 차이 정량 (참고) | `report_repo_difference` (별도 실행) |

독립성에 대하여
--------------
①의 "손계산"은 `indicators.adx` 와 **다른 경로**로 계산한다:
Wilder 정의를 그대로 옮긴 **순수 스칼라 루프**이며, numpy 배열 연산·인덱스 되돌리기·
`wilder_smooth` 헬퍼를 전혀 쓰지 않는다.

⚠️ 그래도 **완전한 독립은 아니다.** 같은 사람이 같은 정의 이해를 바탕으로 두 번 썼으므로,
   "정의 자체를 잘못 이해한 경우"는 두 경로가 함께 틀릴 수 있다.
   이 대조가 잡는 것은 **인덱싱·시드·평활 구현 오류**이고, 그 범위를 넘지 않는다.
   ②의 해석적 속성이 그 공백을 일부 메운다.
"""
from __future__ import annotations

import sys
from pathlib import Path

# Windows 콘솔 기본 인코딩(cp949)에서 한국어·기호 출력이 깨지므로 UTF-8 로 고정한다.
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from indicators import adx, directional_movement  # noqa: E402

TOL = 1e-9


# ──────────────────────────────────────────────────────────────────────────
# 독립 경로 — Wilder 정의를 스칼라 루프로 그대로 옮긴다
# ──────────────────────────────────────────────────────────────────────────

def adx_reference(high, low, close, n=14):
    """
    numpy 벡터 연산·헬퍼를 쓰지 않는 참조 구현.
    리스트와 for 문만 쓴다. 반환: (pdi, ndi, dx, adx) — 길이 = 입력, 앞부분 None.
    """
    m = len(close)
    tr = [None] * m
    pdm = [None] * m
    ndm = [None] * m

    for i in range(1, m):
        up = high[i] - high[i - 1]
        dn = low[i - 1] - low[i]
        pdm[i] = up if (up > dn and up > 0) else 0.0
        ndm[i] = dn if (dn > up and dn > 0) else 0.0
        a = high[i] - low[i]
        b = abs(high[i] - close[i - 1])
        c = abs(low[i] - close[i - 1])
        tr[i] = a if (a >= b and a >= c) else (b if b >= c else c)

    # 첫 n 개(index 1..n)의 합을 시드로, 이후 S = S - S/n + x
    str_ = [None] * m
    spdm = [None] * m
    sndm = [None] * m
    if m >= n + 1:
        s_tr = s_p = s_n = 0.0
        for i in range(1, n + 1):
            s_tr += tr[i]
            s_p += pdm[i]
            s_n += ndm[i]
        str_[n] = s_tr
        spdm[n] = s_p
        sndm[n] = s_n
        for i in range(n + 1, m):
            s_tr = s_tr - s_tr / n + tr[i]
            s_p = s_p - s_p / n + pdm[i]
            s_n = s_n - s_n / n + ndm[i]
            str_[i] = s_tr
            spdm[i] = s_p
            sndm[i] = s_n

    pdi = [None] * m
    ndi = [None] * m
    dx = [None] * m
    for i in range(m):
        if str_[i] is None:
            continue
        if str_[i] == 0.0:
            pdi[i] = 0.0
            ndi[i] = 0.0
            dx[i] = None
            continue
        pdi[i] = 100.0 * spdm[i] / str_[i]
        ndi[i] = 100.0 * sndm[i] / str_[i]
        tot = pdi[i] + ndi[i]
        dx[i] = None if tot == 0.0 else 100.0 * abs(pdi[i] - ndi[i]) / tot

    adx_ = [None] * m
    have = [i for i in range(m) if dx[i] is not None]
    if len(have) >= n:
        acc = 0.0
        for i in have[:n]:
            acc += dx[i]
        first = have[n - 1]
        adx_[first] = acc / n
        prev = adx_[first]
        for i in have[n:]:
            prev = (prev * (n - 1) + dx[i]) / n
            adx_[i] = prev
    return pdi, ndi, dx, adx_


# ──────────────────────────────────────────────────────────────────────────
# 검증 대상 시계열 — 40봉. 재귀 갱신이 충분히 관측되도록 방향을 바꾼다
# ──────────────────────────────────────────────────────────────────────────

def series_40():
    """
    40봉. 첫 ADX 는 index 27 이므로, index 28~39 에서 재귀 갱신 12회를 관측한다.
    상승 → 하락 → 횡보를 섞어 +DM/−DM 이 모두 발생하게 한다.
    """
    highs, lows, closes = [], [], []
    price = 100.0
    for i in range(40):
        if i < 16:
            price += 1.3          # 상승
        elif i < 28:
            price -= 1.1          # 하락
        else:
            price += 0.15 * (1 if i % 2 == 0 else -1)   # 횡보
        h = price + 0.8
        l = price - 0.8
        highs.append(h)
        lows.append(l)
        closes.append(price)
    return np.array(highs), np.array(lows), np.array(closes)


# ──────────────────────────────────────────────────────────────────────────
# ① 손계산(독립 경로) 대조
# ──────────────────────────────────────────────────────────────────────────

def check_hand_reference() -> bool:
    h, l, c = series_40()
    pdi_a, ndi_a, dx_a, adx_a = adx(h, l, c, 14)
    pdi_r, ndi_r, dx_r, adx_r = adx_reference(list(h), list(l), list(c), 14)

    first_adx = next((i for i, v in enumerate(adx_r) if v is not None), None)
    print(f"  첫 ADX index = {first_adx}  (기대 27 = 2n−1, n=14)")
    if first_adx != 27:
        print("  ✗ 첫 ADX 위치가 이론값과 다르다")
        return False

    updates = sum(1 for v in adx_r[first_adx + 1:] if v is not None)
    print(f"  재귀 갱신 횟수 = {updates}  (요구 ≥ 3)")
    if updates < 3:
        print("  ✗ 재귀 갱신 관측이 부족하다")
        return False

    ok = True
    for name, A, R in (("+DI", pdi_a, pdi_r), ("−DI", ndi_a, ndi_r),
                       ("DX", dx_a, dx_r), ("ADX", adx_a, adx_r)):
        for i in range(len(c)):
            a = A[i]
            r = R[i]
            a_nan = (a is None) or (isinstance(a, float) and np.isnan(a))
            r_nan = r is None
            if a_nan != r_nan:
                print(f"  ✗ {name}[{i}] 정의 여부 불일치: impl={a} ref={r}")
                ok = False
                break
            if not a_nan and abs(a - r) > TOL:
                print(f"  ✗ {name}[{i}] 값 불일치: impl={a!r} ref={r!r}")
                ok = False
                break
    if ok:
        print(f"  ✓ 40봉 전 구간 4개 계열이 독립 경로와 일치 (허용오차 {TOL})")
        print(f"    첫 ADX = {adx_r[first_adx]:.10f}")
        print(f"    +3회   = " + ", ".join(
            f"{adx_r[i]:.10f}" for i in range(first_adx + 1, first_adx + 4)))
    return ok


# ──────────────────────────────────────────────────────────────────────────
# ② 해석적 속성
# ──────────────────────────────────────────────────────────────────────────

def check_monotone_properties() -> bool:
    """
    고가·저가가 일정하게 상승하는 지정 예제.

    🔴 `+DI = 100` 을 기대하지 않는다 — 고저폭이 증분보다 크면 +DI 는 100 미만이다.
       (증분 1, 고저폭 1.6 → +DM=1, TR≈1.6+ → +DI ≈ 60 대)
       검사는 `+DI > 0` · `−DI = 0` · `DX = 100` 이다.
    """
    ok = True
    n = 60

    # 상승
    price = np.arange(n, dtype=float) * 1.0 + 100.0
    h = price + 0.8
    l = price - 0.8
    pdi, ndi, dx, a = adx(h, l, price, 14)
    i = n - 1
    print(f"  상승: +DI={pdi[i]:.4f}  −DI={ndi[i]:.4f}  DX={dx[i]:.4f}  ADX={a[i]:.4f}")
    if not (pdi[i] > 0):
        print("  ✗ 상승인데 +DI 가 0 이하"); ok = False
    if abs(ndi[i]) > TOL:
        print("  ✗ 상승인데 −DI 가 0 이 아니다"); ok = False
    if abs(dx[i] - 100.0) > 1e-6:
        print("  ✗ 상승 단조인데 DX 가 100 이 아니다"); ok = False
    if abs(a[i] - 100.0) > 1e-6:
        print("  ✗ ADX 가 100 으로 수렴하지 않았다"); ok = False
    if pdi[i] > 100.0 + TOL:
        print("  ✗ +DI 가 100 을 넘었다"); ok = False

    # 하락 (대칭)
    price2 = 200.0 - np.arange(n, dtype=float) * 1.0
    h2 = price2 + 0.8
    l2 = price2 - 0.8
    pdi2, ndi2, dx2, a2 = adx(h2, l2, price2, 14)
    print(f"  하락: +DI={pdi2[i]:.4f}  −DI={ndi2[i]:.4f}  DX={dx2[i]:.4f}  ADX={a2[i]:.4f}")
    if abs(pdi2[i]) > TOL:
        print("  ✗ 하락인데 +DI 가 0 이 아니다"); ok = False
    if not (ndi2[i] > 0):
        print("  ✗ 하락인데 −DI 가 0 이하"); ok = False
    if abs(dx2[i] - 100.0) > 1e-6:
        print("  ✗ 하락 단조인데 DX 가 100 이 아니다"); ok = False

    # +DM = TR 이 되도록 구성하면 +DI = 100 이 나오는지 (사전등록의 단서 확인)
    #   고저폭을 0 으로 두고 증분만 주면 TR = |H_t − C_{t−1}| = 증분 = +DM
    step = np.arange(n, dtype=float) * 1.0 + 100.0
    pdi3, ndi3, dx3, _ = adx(step, step, step, 14)
    print(f"  +DM=TR 구성: +DI={pdi3[i]:.4f} (기대 100)")
    if abs(pdi3[i] - 100.0) > 1e-6:
        print("  ✗ +DM=TR 인 구성에서 +DI 가 100 이 아니다"); ok = False

    if ok:
        print("  ✓ 해석적 속성 통과")
    return ok


# ──────────────────────────────────────────────────────────────────────────
# ③ 경계
# ──────────────────────────────────────────────────────────────────────────

def check_bounds() -> bool:
    ok = True
    rng = np.random.default_rng(20260923)
    n = 400
    steps = rng.normal(0, 1.0, n).cumsum()
    price = 1000.0 + steps
    h = price + np.abs(rng.normal(0, 0.5, n))
    l = price - np.abs(rng.normal(0, 0.5, n))
    pdi, ndi, dx, a = adx(h, l, price, 14)

    for name, arr in (("+DI", pdi), ("−DI", ndi), ("DX", dx), ("ADX", a)):
        v = arr[~np.isnan(arr)]
        if len(v) == 0:
            print(f"  ✗ {name} 가 전부 NaN"); ok = False; continue
        if v.min() < -TOL or v.max() > 100.0 + 1e-6:
            print(f"  ✗ {name} 범위 이탈: [{v.min():.6f}, {v.max():.6f}]"); ok = False
        else:
            print(f"  {name} 범위 [{v.min():.4f}, {v.max():.4f}] ✓")

    # 완전 무변동 — TR 합 = 0
    flat = np.full(60, 500.0)
    pdi_f, ndi_f, dx_f, adx_f = adx(flat, flat, flat, 14)
    i = 59
    print(f"  무변동: +DI={pdi_f[i]}  −DI={ndi_f[i]}  DX={dx_f[i]}  ADX={adx_f[i]}")
    if not (pdi_f[i] == 0.0 and ndi_f[i] == 0.0):
        print("  ✗ 무변동에서 ±DI 가 0 이 아니다"); ok = False
    if not np.isnan(dx_f[i]):
        print("  ✗ 무변동에서 DX 가 정의됐다 — 0/0 이므로 NaN 이어야 한다"); ok = False
    if not np.isnan(adx_f[i]):
        print("  ✗ 무변동에서 ADX 가 값을 가졌다 — DX 가 없으므로 산출 불가여야 한다"); ok = False

    # 짧은 입력 — 20봉으로는 ADX 가 나오면 안 된다
    hs, ls, cs = series_40()
    _, _, _, a20 = adx(hs[:20], ls[:20], cs[:20], 14)
    if not np.all(np.isnan(a20)):
        print("  ✗ 20봉에서 ADX 가 산출됐다 — 이론상 불가(첫 ADX 는 index 27)"); ok = False
    else:
        print("  20봉 → ADX 전부 NaN ✓ (첫 ADX 는 index 27)")

    if ok:
        print("  ✓ 경계 검사 통과")
    return ok


def main() -> int:
    print("=" * 74)
    print("ADX 검증 — SIGNAL_STUDY_PREREG.md v5 §3 S2")
    print("=" * 74)
    results = {}
    print("\n[①] 40봉 독립 경로 대조")
    results["①"] = check_hand_reference()
    print("\n[②] 해석적 속성")
    results["②"] = check_monotone_properties()
    print("\n[③] 경계")
    results["③"] = check_bounds()

    print("\n" + "=" * 74)
    for k, v in results.items():
        print(f"  {k}  {'통과' if v else '실패'}")
    allok = all(results.values())
    print("=" * 74)
    if allok:
        print("✓ ADX 검증 통과 — 다음 단계(위약 점검)로 진행 가능")
    else:
        print("✗ ADX 검증 실패 — 본 실험을 시작하지 않는다 (사전등록 §12)")
    return 0 if allok else 1


if __name__ == "__main__":
    raise SystemExit(main())
