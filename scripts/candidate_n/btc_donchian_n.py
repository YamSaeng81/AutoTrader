"""리뷰 48차 — 후보(업비트 KRW-BTC 일봉 Donchian 앙상블) 개발 자료 최소 계산.

규칙은 docs/CANDIDATE_N_REPORT.md §3 에 계산 전에 고정했다. 이 파일은 그 규칙을 그대로 옮긴 것이고, 변형을 비교하지 않는다.
실행: python scripts/candidate_n/btc_donchian_n.py [저장 폴더]   (기본 d:/tmp/cand_n)
- 저장 폴더에 btc_d1.csv 가 없으면 업비트 공개 시세(/v1/candles/days, 인증 없음)로 개발 구간 일봉만 받는다. 응답 본문을 그대로 저장한다.
- 있으면 다시 받지 않고 그 파일(SHA-256 출력)로 계산한다.
"""
import csv
import hashlib
import json
import math
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone
from statistics import NormalDist

import numpy as np

sys.stdout.reconfigure(encoding="utf-8")
OUT = sys.argv[1] if len(sys.argv) > 1 else "d:/tmp/cand_n"
FIRST, LAST = "2022-01-01", "2026-09-21"  # 일봉 시작일(UTC 00:00) 양 끝 포함. 마지막 봉은 2026-09-22 00:00 UTC 에 끝난다
LOOKBACKS = (5, 10, 20, 30, 60, 90, 150, 250, 360)
VOL_N, TARGET, CAP, ANN = 90, 0.25, 1.0, 365
ARMS = {"주 비용": 0.0005 + 0.001, "스트레스": 0.0005 + 0.002, "비용 0(구현 대조용)": 0.0}
BOOT_REPS, BLOCK, SEED = 5000, 20, 20261029
ERRORS = ((0.05, 0.80), (0.20, 0.50))
WEEKS_LIMIT = 8
Z = NormalDist().inv_cdf


def fetch(path):
    raw = os.path.join(OUT, "raw")
    os.makedirs(raw, exist_ok=True)
    rows, to, i = {}, "2026-09-22T00:00:00Z", 0
    while True:
        url = f"https://api.upbit.com/v1/candles/days?market=KRW-BTC&to={to}&count=200"
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=20) as r:
            body = r.read()
        with open(os.path.join(raw, f"days_{i:02d}.json"), "wb") as f:
            f.write(body)
        with open(os.path.join(raw, f"days_{i:02d}.url.txt"), "w", encoding="utf-8") as f:
            f.write(url + "\n" + datetime.now(timezone.utc).isoformat() + "\n")
        i += 1
        data = json.loads(body)
        if not data:
            break
        for c in data:
            rows[c["candle_date_time_utc"][:10]] = c
        oldest = min(c["candle_date_time_utc"] for c in data)
        if oldest[:10] <= FIRST:
            break
        to = oldest + "Z"
        time.sleep(0.3)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["date_utc", "open", "high", "low", "close"])
        for d in sorted(d for d in rows if FIRST <= d <= LAST):
            c = rows[d]
            w.writerow([d, c["opening_price"], c["high_price"], c["low_price"], c["trade_price"]])


def simulate(close):
    """논문 §4.3 그대로(상한만 100%). 반환: 일별 앙상블 목표 비중 w[t] (t 의 종가로 계산, t 종가 직후 체결)."""
    T = len(close)
    ret = np.r_[np.nan, close[1:] / close[:-1] - 1]
    sigma = np.full(T, np.nan)
    for t in range(VOL_N, T):
        sigma[t] = np.std(ret[t - VOL_N + 1:t + 1], ddof=1) * math.sqrt(ANN)
    W = np.zeros((len(LOOKBACKS), T))
    for k, n in enumerate(LOOKBACKS):
        pos, stop = 0, 0.0
        for t in range(n - 1, T):
            win = close[t - n + 1:t + 1]
            up, mid = win.max(), 0.5 * (win.max() + win.min())
            c = close[t]
            if pos == 0:
                if c >= up:
                    pos, stop = 1, mid
            elif c >= up or c > stop:
                stop = max(stop, mid)
            else:
                pos = 0
            if pos and not np.isnan(sigma[t]):
                W[k, t] = min(TARGET / sigma[t], CAP)
    return ret, W.mean(axis=0), W


def returns(ret, w, start, cost):
    """start 종가에 현금에서 w[start] 로 들어간다. 이후 매일 종가에 목표 비중으로 다시 맞춘다. 비교 기준은 start 종가에 전액 매수."""
    rp, rb, turn = [], [], []
    held = 0.0
    for t in range(start, len(ret)):
        if t > start:
            r = ret[t]
            gross = held * r
            held = held * (1 + r) / (1 + gross)
            rp.append(gross)
            rb.append(r)
        tv = abs(w[t] - held)
        turn.append(tv)
        held = w[t]
        if t > start:
            rp[-1] -= cost * tv
    rp[0] -= cost * turn[0]  # 시작 매수 비용은 첫 기간에 붙인다
    rb[0] -= cost  # 비교 기준도 같은 비용
    return np.array(rp), np.array(rb), np.array(turn)


def newey_west_d(e):
    n = len(e)
    x = e - e.mean()
    g0 = x @ x / n
    L = int(4 * (n / 100) ** (2 / 9))
    lrv = g0 + 2 * sum((1 - k / (L + 1)) * (x[k:] @ x[:-k] / n) for k in range(1, L + 1))
    return lrv / g0, L


def stationary_bootstrap_ir(e, rng):
    """Politis·Romano 정상 블록 부트스트랩(평균 블록 BLOCK 일, 원형 이어 붙임)."""
    n = len(e)
    restart = rng.random((BOOT_REPS, n)) < 1 / BLOCK
    fresh = rng.integers(n, size=(BOOT_REPS, n))
    idx = np.empty((BOOT_REPS, n), dtype=int)
    idx[:, 0] = fresh[:, 0]
    for j in range(1, n):
        idx[:, j] = np.where(restart[:, j], fresh[:, j], (idx[:, j - 1] + 1) % n)
    s = e[idx]
    means = s.mean(axis=1)
    return means / s.std(axis=1, ddof=1) * math.sqrt(ANN), means


def n_years(ir, d, a, pw):
    return ((Z(1 - a) + Z(pw)) / ir) ** 2 * d if ir > 0 else math.inf


def power_8w(ir, d, a):
    yrs = WEEKS_LIMIT * 7 / 365
    return NormalDist().cdf(ir * math.sqrt(yrs / d) - Z(1 - a)) if ir > 0 else float("nan")


def mdd(r):
    eq = np.cumprod(1 + r)
    return float((1 - eq / np.maximum.accumulate(eq)).max())


def main():
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "btc_d1.csv")
    if not os.path.exists(path):
        fetch(path)
    print("자료", path, "SHA-256", hashlib.sha256(open(path, "rb").read()).hexdigest())
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    dates = [r["date_utc"] for r in rows]
    d0 = [datetime.fromisoformat(d) for d in dates]
    gaps = [dates[i] for i in range(1, len(d0)) if (d0[i] - d0[i - 1]).days != 1]
    print(f"일봉 {len(rows)}개 {dates[0]} ~ {dates[-1]} · 날짜 빠짐 {len(gaps)} {gaps[:5]}")
    close = np.array([float(r["close"]) for r in rows])
    ret, w, W = simulate(close)
    start = max(max(LOOKBACKS) - 1, VOL_N)
    print(f"평가 시작(모든 모델·변동성 유효) {dates[start]} 종가 · 평가 일수 {len(close) - 1 - start}")

    # 일봉 종가 ↔ 기존 H1 개발 자료(23:00 UTC 봉 종가) 대조
    for h1 in ("d:/tmp/stfilter/st_btc_h1.csv", "d:/tmp/stval_btc/btc_h1.csv"):
        if os.path.exists(h1):
            last = {r[0][:10]: float(r[4]) for r in csv.reader(open(h1, encoding="utf-8")) if r[0][11:13] == "23"}
            both = [d for d in dates if d in last]
            bad = [d for d in both if abs(last[d] - close[dates.index(d)]) > 0.5]
            print(f"H1 대조 {h1}: 겹치는 날 {len(both)} · 종가 불일치 {len(bad)} {bad[:5]}")

    rng = np.random.default_rng(SEED)
    res = {}
    for arm, cost in ARMS.items():
        rp, rb, turn = returns(ret, w, start, cost)
        e = rp - rb
        n = len(e)
        yrs = n / ANN
        ir = e.mean() / e.std(ddof=1) * math.sqrt(ANN)
        d_nw, L = newey_west_d(e)
        boot, means = stationary_bootstrap_ir(e, rng) if arm != "비용 0(구현 대조용)" else (None, None)
        beta = np.cov(rp, rb, ddof=1)[0, 1] / rb.var(ddof=1)
        resid = rp - beta * rb
        alpha = resid.mean() * ANN
        appraisal = resid.mean() / resid.std(ddof=1) * math.sqrt(ANN)
        r = dict(n=n, yrs=yrs, ir=ir, d_nw=d_nw, L=L,
                 te=e.std(ddof=1) * math.sqrt(ANN), excess_ann=e.mean() * ANN,
                 cagr_p=np.prod(1 + rp) ** (1 / yrs) - 1, cagr_b=np.prod(1 + rb) ** (1 / yrs) - 1,
                 vol_p=rp.std(ddof=1) * math.sqrt(ANN), vol_b=rb.std(ddof=1) * math.sqrt(ANN),
                 sharpe_p=rp.mean() / rp.std(ddof=1) * math.sqrt(ANN), sharpe_b=rb.mean() / rb.std(ddof=1) * math.sqrt(ANN),
                 mdd_p=mdd(rp), mdd_b=mdd(rb), beta=beta, alpha=alpha, appraisal=appraisal,
                 turnover_yr=turn.sum() / yrs, cost_drag=cost * turn.sum() / yrs, avg_w=w[start:].mean())
        if boot is not None:
            se_iid = e.std(ddof=1) / math.sqrt(n)
            r.update(ir_p05=float(np.percentile(boot, 5)), ir_p50=float(np.percentile(boot, 50)),
                     ir_p95=float(np.percentile(boot, 95)), d_boot=float(means.std(ddof=1) / se_iid) ** 2,
                     p_ir_le0=float((boot <= 0).mean()))
        yearly = {}
        for t in range(n):
            y = dates[start + 1 + t][:4]
            yearly.setdefault(y, [1.0, 1.0])
            yearly[y][0] *= 1 + rp[t]
            yearly[y][1] *= 1 + rb[t]
        r["yearly"] = {y: (a - 1, b - 1, a - b) for y, (a, b) in yearly.items()}
        res[arm] = r

    for arm, r in res.items():
        print(f"\n[{arm}] 평가 {r['n']}일({r['yrs']:.2f}년) · 평균 비중 {r['avg_w']:.3f} · 연 회전율 {r['turnover_yr']:.2f} · 연 비용 {r['cost_drag']:.2%}")
        print(f"  전략 CAGR {r['cagr_p']:.1%} 변동성 {r['vol_p']:.1%} 샤프 {r['sharpe_p']:.2f} MDD {r['mdd_p']:.1%}")
        print(f"  BTC  CAGR {r['cagr_b']:.1%} 변동성 {r['vol_b']:.1%} 샤프 {r['sharpe_b']:.2f} MDD {r['mdd_b']:.1%}")
        print(f"  전략−BTC 연 초과 {r['excess_ann']:.1%} · 추적 오차 {r['te']:.1%} · IR {r['ir']:.3f} · D(NW, 지연 {r['L']}) {r['d_nw']:.3f}")
        print(f"  참고: 베타 {r['beta']:.3f} · 알파 {r['alpha']:.1%} · 평가비율 {r['appraisal']:.3f}")
        if "ir_p05" in r:
            print(f"  IR 부트스트랩(정상 블록 평균 {BLOCK}일, {BOOT_REPS}회) 5% {r['ir_p05']:.3f} · 50% {r['ir_p50']:.3f} · 95% {r['ir_p95']:.3f} · IR≤0 비율 {r['p_ir_le0']:.1%} · D(부트) {r['d_boot']:.3f}")
            for name, ir in (("점추정", r["ir"]), ("부트 95%", r["ir_p95"]), ("부트 5%", r["ir_p05"])):
                for a, pw in ERRORS:
                    ny = n_years(ir, r["d_nw"], a, pw)
                    txt = f"{ny:.2f}년({ny * 365 / 7:.0f}주)" if math.isfinite(ny) else "정의 안 됨(IR≤0)"
                    print(f"    N[{name} IR {ir:.3f}, α {a:.0%}, 검정력 {pw:.0%}, D {r['d_nw']:.3f}] = {txt} · 8주 검정력 {power_8w(ir, r['d_nw'], a):.1%}")
        for y, (a, b, x) in r["yearly"].items():
            print(f"  {y}: 전략 {a:+.1%} · BTC {b:+.1%} · 차이 {x:+.1%}")
    json.dump(res, open(os.path.join(OUT, "result.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=float)


if __name__ == "__main__":
    main()
