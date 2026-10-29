"""제한 실거래 시험 후보(업비트 KRW-BTC 일봉 종가 > 20일 이동평균이면 보유, 아니면 현금) 개발 자료 계산.

규칙은 docs/LIMITED_LIVE_TEST_PLAN.md §2·§3 에 계산 전에 고정했다. 이 파일은 그 규칙을 그대로 옮긴 것이고, 변형을 비교하지 않는다.
실행: python scripts/limited_live/btc_sma20.py [저장 폴더]   (기본 d:/tmp/sma20)
- 입력은 리뷰 48차에 받아 해시로 고정한 scripts/candidate_n/run_48/btc_d1.csv 만 쓴다. 새로 받지 않는다.
- 통계 함수(Newey–West D, 정상 블록 부트스트랩)는 scripts/candidate_n/btc_donchian_n.py 의 것을 그대로 가져다 쓴다.
"""
import csv
import hashlib
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "candidate_n"))
import btc_donchian_n as base  # noqa: E402  (OUT 은 이 파일의 인자로 따로 정한다)

sys.stdout.reconfigure(encoding="utf-8")
OUT = sys.argv[1] if len(sys.argv) > 1 else "d:/tmp/sma20"
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "candidate_n", "run_48", "btc_d1.csv")
SRC_SHA = "9ead5c5e42dacf5ae2a7f78ed82d97597fbb06f1dba6500e536a6ef96ef7e606"
SMA_N = 20
POOL, ALLOC = 100_000.0, 20_000.0
ARMS = {"주 비용": 0.0005 + 0.001, "스트레스": 0.0005 + 0.002}
WIN = 56  # 8주
STAGE_LOSS, DAY_LOSS, CAP_LOSS, POS_MDD = -15_000.0, -3_000.0, 0.85, 0.80
ANN = 365


def load():
    raw = open(SRC, "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    if sha != SRC_SHA:
        raise SystemExit(f"입력 해시 불일치: {sha}")
    rows = list(csv.DictReader(raw.decode("utf-8").splitlines()))
    d = [r["date_utc"] for r in rows]
    o, h, l, c = (np.array([float(r[k]) for r in rows]) for k in ("open", "high", "low", "close"))
    return d, h, l, c, sha


def signal(c):
    s = np.full(len(c), -1)
    for t in range(SMA_N - 1, len(c)):
        s[t] = 1 if c[t] > c[t - SMA_N + 1 : t + 1].mean() else 0
    return s


def run(s, h, l, c, cost, a, b):
    """a 종가에 현금 POOL 로 시작해 b 종가까지. 매일 종가에 신호를 보고 그 종가에 체결한다.
    진입은 배정액 ALLOC(비용 포함), 청산은 전량. 중단선은 기록만 하고 매매를 바꾸지 않는다(§3-4)."""
    cash, units, entry_val, peak = POOL, 0.0, 0.0, 0.0
    eq, trades, cost_paid = [], 0, 0.0
    halts = {"누적 −15,000": None, "24시간 −3,000": None, "CAPITAL_LOSS −15%": None, "MAX_DRAWDOWN −20%": None}
    for t in range(a, b + 1):
        prev_eq = eq[-1] if eq else POOL
        if units > 0 and t > a:
            low_eq = cash + units * l[t]
            day = t - a
            if halts["누적 −15,000"] is None and low_eq - POOL <= STAGE_LOSS:
                halts["누적 −15,000"] = day
            if halts["24시간 −3,000"] is None and low_eq - prev_eq <= DAY_LOSS:
                halts["24시간 −3,000"] = day
            if halts["CAPITAL_LOSS −15%"] is None and units * l[t] <= CAP_LOSS * ALLOC:
                halts["CAPITAL_LOSS −15%"] = day
            if halts["MAX_DRAWDOWN −20%"] is None and units * l[t] <= POS_MDD * peak:
                halts["MAX_DRAWDOWN −20%"] = day
            peak = max(peak, units * h[t])
        if s[t] == 1 and units == 0:
            units = ALLOC / (c[t] * (1 + cost))
            cash -= ALLOC
            cost_paid += ALLOC - units * c[t]
            entry_val = units * c[t]
            peak = entry_val
            trades += 1
        elif s[t] == 0 and units > 0:
            gross = units * c[t]
            cash += gross * (1 - cost)
            cost_paid += gross * cost
            units = 0.0
        eq.append(cash + units * c[t])
    return np.array(eq), trades, cost_paid, halts


def sr(r):
    return float(r.mean() / r.std(ddof=1) * math.sqrt(ANN)) if r.std() > 0 else float("nan")


def summary(eq, start_val=POOL):
    r = np.concatenate([[eq[0] / start_val - 1], eq[1:] / eq[:-1] - 1])
    yrs = len(r) / ANN
    return {
        "기간 수익률": float(eq[-1] / start_val - 1),
        "CAGR": float((eq[-1] / start_val) ** (1 / yrs) - 1),
        "샤프(현금 대비)": sr(r),
        "MDD(고점 대비)": base.mdd(r),
        "최대 손실(초기 자본 대비)": float(min(0.0, (eq.min() - start_val) / start_val)),
    }, r


def main():
    os.makedirs(OUT, exist_ok=True)
    d, h, l, c, sha = load()
    s = signal(c)
    a, b = SMA_N - 1, len(c) - 1
    print(f"입력 {os.path.normpath(SRC)} SHA-256 {sha}, 일봉 {len(c)}개 {d[0]} ~ {d[-1]}")
    print(f"평가 {d[a]} 종가 ~ {d[b]} 종가, {b - a}일 ({(b - a) / ANN:.2f}년)")
    res = {"입력 SHA-256": sha, "평가 시작": d[a], "평가 끝": d[b], "평가 일수": b - a, "팔": {}}

    bh = POOL - ALLOC + ALLOC / c[a] * c  # 비교 지표: 같은 2만 원을 a 종가에 사서 보유(비용 0)
    bh_full = c[a:] / c[a]
    res["BTC 보유(비교 지표, 비용 0)"] = {"2만 원 보유·풀 기준": summary(bh[a + 1 : b + 1])[0], "BTC 전액 보유": summary(bh_full[1:], 1.0)[0]}
    rng = np.random.default_rng(base.SEED)
    years = sorted({x[:4] for x in d[a:]})
    for name, cost in ARMS.items():
        eq, trades, cost_paid, _ = run(s, h, l, c, cost, a, b)
        stats, r = summary(eq)
        dnw, lag = base.newey_west_d(r)
        boot_sr, boot_mean = base.stationary_bootstrap_ir(r, rng)
        q = np.percentile(boot_sr, [5, 50, 95])
        in_days = int(sum(1 for t in range(a, b) if s[t] == 1))
        arm = dict(stats)
        arm.update({
            "진입 횟수": trades, "연 진입 횟수": trades / ((b - a) / ANN), "누적 비용(원)": cost_paid,
            "보유 일 비율": in_days / (b - a),
            "NW D": dnw, "NW 지연": lag, "부트 D": float(boot_mean.var(ddof=1) / (r.var(ddof=1) / len(r))),
            "부트 SR 5·50·95%": [float(x) for x in q], "부트 SR≤0 비율": float((boot_sr <= 0).mean()),
        })
        # 시간순 구간 대조: 달력 연도, 그리고 앞·뒤 절반
        seg = {}
        bounds = []
        for y in years:
            idx = [i for i in range(a, b + 1) if d[i][:4] == y]
            bounds.append((y, max(a, idx[0] - 1), idx[-1]))  # 전년 마지막 종가에서 시작해 그해 수익을 모두 담는다
        half = (a + b) // 2
        bounds += [("앞 절반", a, half), ("뒤 절반", half, b)]
        for label, x0, x1 in bounds:
            e, n_tr, _, _ = run(s, h, l, c, cost, x0, x1)
            st, rr = summary(e)
            seg[label] = {"구간": f"{d[x0]}~{d[x1]}", "일수": x1 - x0, "기간 수익률(풀)": st["기간 수익률"], "샤프": st["샤프(현금 대비)"],
                          "진입": n_tr, "BTC 기간 수익률": float(c[x1] / c[x0] - 1)}
        arm["시간순 구간"] = seg
        # 위험 진단: 매일 새로 시작하는 8주 시험(현금 10만 원에서 시작)
        dd8, loss8, hits = [], [], {k: 0 for k in ("누적 −15,000", "24시간 −3,000", "CAPITAL_LOSS −15%", "MAX_DRAWDOWN −20%", "하나라도")}
        n_win = 0
        for x0 in range(a, b - WIN + 1):
            e, _, _, hl = run(s, h, l, c, cost, x0, x0 + WIN)
            n_win += 1
            dd8.append(float((1 - e / np.maximum.accumulate(np.concatenate([[POOL], e]))[1:]).max()))
            loss8.append(float(min(0.0, e.min() - POOL)))
            any_hit = False
            for k, v in hl.items():
                if v is not None:
                    hits[k] += 1
                    any_hit = True
            hits["하나라도"] += any_hit
        arm["8주 위험 진단"] = {
            "창 수(겹침, 독립 아님)": n_win,
            "풀 고점 대비 낙폭 50·95·최대": [float(np.percentile(dd8, 50)), float(np.percentile(dd8, 95)), float(max(dd8))],
            "초기 자본 대비 최저 손실(원) 5%·최저": [float(np.percentile(loss8, 5)), float(min(loss8))],
            "중단선 발동 창 비율": {k: v / n_win for k, v in hits.items()},
        }
        res["팔"][name] = arm
        print(f"\n[{name}] 편도 {cost:.2%}")
        print(json.dumps(arm, ensure_ascii=False, indent=1, default=float))
    print("\n[BTC 보유 비교 지표]")
    print(json.dumps(res["BTC 보유(비교 지표, 비용 0)"], ensure_ascii=False, indent=1, default=float))
    json.dump(res, open(os.path.join(OUT, "result.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=float)


if __name__ == "__main__":
    main()
