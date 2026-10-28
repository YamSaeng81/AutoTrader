"""R13 근거 — 역사적 검사(2026-09-24)에서 '사후 차감'이 '엔진 재실행' 스트레스를 얼마나 대신하는가 (2026-10-28).

실행: python scripts/supertrend_filter/posthoc_vs_rerun.py [d:/tmp/stval_out]
입력: SupertrendValidationRunner 출력 trades.csv·summary.csv (주 비용 MAIN·스트레스 STRESS 를 각각 엔진으로 실행한 결과).
사후 차감: MAIN 의 각 왕복에 편도 0.1%p 를 더 뺀다 — 진입금액 × 0.001 + 청산금액(≈ 진입금액 + 순손익) × 0.001.
코인 순수익(사후) = 차감한 순손익 합 / 초기자본 1,000만 — 복리(자산 변화에 따른 포지션 크기 변화)는 반영하지 않는 근사다.
🔴 전향 자료를 읽지 않는다. 역사적 출력만 쓴다.
"""
import csv
import os
import statistics as st
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
d = sys.argv[1] if len(sys.argv) > 1 else "d:/tmp/stval_out"
CAP = 1e7
T = defaultdict(list)
for r in csv.DictReader(open(os.path.join(d, "trades.csv"), encoding="utf-8")):
    T[(r["coin"], r["arm"], r["cost"])].append((float(r["entryAmount"]), float(r["netPnl"])))
S = {(r["coin"], r["arm"], r["cost"]): r for r in csv.DictReader(open(os.path.join(d, "summary.csv"), encoding="utf-8"))}
coins = sorted({k[0] for k in T})
print(f"코인 {len(coins)} · 사후 차감 대 재실행(스트레스 0.2%×2)")
print("팔  | 평균 순수익 사후/재실행 | 중앙값 사후/재실행 | 양수 코인 사후/재실행 | 거래당 순수익률 사후/재실행 | 코인별 차이 최소/최대/평균절대")
for arm in ("A", "B", "OFF"):
    ph, rr, pt_ph, pt_rr, diff = [], [], [], [], []
    for c in coins:
        adj = [(e, p - 0.001 * (e + (e + p))) for e, p in T.get((c, arm, "MAIN"), [])]
        a = sum(p for _, p in adj) / CAP * 100
        b = float(S[(c, arm, "STRESS")]["totalReturnPct"])
        ph.append(a), rr.append(b), diff.append(a - b)
        if adj:
            pt_ph.append(st.mean(p / e * 100 for e, p in adj))
        s = T.get((c, arm, "STRESS"), [])
        if s:
            pt_rr.append(st.mean(p / e * 100 for e, p in s))
    print(f"{arm:3} | {st.mean(ph):+.3f} / {st.mean(rr):+.3f} | {st.median(ph):+.3f} / {st.median(rr):+.3f} | "
          f"{sum(x > 0 for x in ph)} / {sum(x > 0 for x in rr)} | {st.mean(pt_ph):+.4f} / {st.mean(pt_rr):+.4f} | "
          f"{min(diff):+.2f} / {max(diff):+.2f} / {st.mean(abs(x) for x in diff):.2f}")
