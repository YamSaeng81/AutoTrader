"""R13 참고 분석 — 역사적 검사(2026-09-24)에서 '사후 차감'과 '엔진 재실행' 스트레스의 차이 (2026-10-28, 리뷰 정정 반영).

실행: python scripts/supertrend_filter/posthoc_vs_rerun.py [d:/tmp/stval_out]
입력: SupertrendValidationRunner 출력 trades.csv·summary.csv (주 비용 MAIN·스트레스 STRESS 를 각각 엔진으로 실행한 결과).
🔴 trades.csv 의 netPnl 은 매수 수수료가 빠져 있다(SupertrendValidationRunner.java:302 — 매도 수수료만 뺀다, R4).
   여기서는 왕복마다 진입금액 × 0.05% 를 빼서 바로잡고 쓴다. 바로잡은 MAIN 왕복 합이 summary 의 MAIN 총수익률과
   같은지(근사 오차)를 함께 출력한다 — 같으면 남는 차이는 비용에 따른 거래 경로 차이다.
사후 차감: 바로잡은 MAIN 왕복마다 편도 0.1%p 를 더 뺀다 — 진입금액 × 0.001 + 청산금액(≈ 진입금액 + 순손익) × 0.001.
🔴 참고 분석이다(리뷰 2026-10-28). 이 표의 최대 차이를 전향 모의 엔진의 오차 상한으로 쓰지 않는다. 전향 자료를 읽지 않는다.
"""
import csv
import os
import statistics as st
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
d = sys.argv[1] if len(sys.argv) > 1 else "d:/tmp/stval_out"
CAP = 1e7
BUY_FEE = 0.0005
T = defaultdict(list)
for r in csv.DictReader(open(os.path.join(d, "trades.csv"), encoding="utf-8")):
    e = float(r["entryAmount"])
    T[(r["coin"], r["arm"], r["cost"])].append((e, float(r["netPnl"]) - BUY_FEE * e))   # 매수 수수료 보정
S = {(r["coin"], r["arm"], r["cost"]): r for r in csv.DictReader(open(os.path.join(d, "summary.csv"), encoding="utf-8"))}
coins = sorted({k[0] for k in T})
print(f"코인 {len(coins)} · 사후 차감 대 재실행(스트레스 0.2%×2) · 매수 수수료 보정")
print("팔  | 근사 오차(MAIN 왕복 합 − MAIN 요약) 최대절대 | 평균 순수익 사후/재실행/차이 | 거래당 순수익률 사후/재실행/차이 | 코인별 차이 최소/최대/평균절대")
for arm in ("A", "B", "OFF"):
    ph, rr, pt_ph, pt_rr, diff, approx = [], [], [], [], [], []
    for c in coins:
        m = T.get((c, arm, "MAIN"), [])
        approx.append(sum(p for _, p in m) / CAP * 100 - float(S[(c, arm, "MAIN")]["totalReturnPct"]))
        adj = [(e, p - 0.001 * (e + (e + p))) for e, p in m]
        a = sum(p for _, p in adj) / CAP * 100
        b = float(S[(c, arm, "STRESS")]["totalReturnPct"])
        ph.append(a), rr.append(b), diff.append(a - b)
        if adj:
            pt_ph.append(st.mean(p / e * 100 for e, p in adj))
        s = T.get((c, arm, "STRESS"), [])
        if s:
            pt_rr.append(st.mean(p / e * 100 for e, p in s))
    print(f"{arm:3} | {max(abs(x) for x in approx):.3f} | "
          f"{st.mean(ph):+.3f} / {st.mean(rr):+.3f} / {st.mean(ph) - st.mean(rr):+.3f} | "
          f"{st.mean(pt_ph):+.4f} / {st.mean(pt_rr):+.4f} / {st.mean(pt_ph) - st.mean(pt_rr):+.4f} | "
          f"{min(diff):+.2f} / {max(diff):+.2f} / {st.mean(abs(x) for x in diff):.2f}")
