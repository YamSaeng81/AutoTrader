"""DAILY_SMA20 구현 대조 — 전략(Java) 일봉 신호 ↔ 계산기(btc_sma20.py) 신호, 날짜별 (docs/LIMITED_LIVE_TEST_PLAN.md §5-3).

실행: python scripts/limited_live/parity_check.py <출력 폴더>
- <출력 폴더>/strategy_signals.csv 는 DailySmaParityRunTest(-Dsma20.h1, -Dsma20.out)가 만든다.
- 계산기 신호는 btc_sma20.load()·signal() 그대로(입력 btc_d1.csv 해시 확인 포함).
- 판정(실행 전 고정): 겹치는 날 중 반대 신호(BUY↔SELL) 0건이어야 "일치". 전략 HOLD(마감 봉 누락)는 따로 센다.
- 같은 기간의 순수 규칙 결과(두 비용 팔)를 결합 규칙과 나란히 적기 위해 함께 계산한다(보고만).
"""
import csv
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import btc_sma20 as calc  # noqa: E402

sys.stdout.reconfigure(encoding="utf-8")
OUT = sys.argv[1] if len(sys.argv) > 1 else "d:/tmp/sma20_run"


def main():
    d, h, l, c, sha = calc.load()
    s = calc.signal(c)
    pure = {d[t]: ("BUY" if s[t] == 1 else "SELL") for t in range(len(d)) if s[t] >= 0}
    rows = list(csv.DictReader(open(os.path.join(OUT, "strategy_signals.csv"), encoding="utf-8")))
    java = {r["date_utc"]: r["action"] for r in rows}

    both = sorted(set(pure) & set(java))
    opposite = [x for x in both if java[x] in ("BUY", "SELL") and java[x] != pure[x]]
    hold = [x for x in both if java[x] == "HOLD"]
    only_java = sorted(set(java) - set(pure))
    res = {
        "계산기 입력 SHA-256": sha,
        "겹치는 날": len(both), "첫날": both[0] if both else None, "끝날": both[-1] if both else None,
        "일치": len(both) - len(opposite) - len(hold), "반대 신호": len(opposite), "반대 신호 날짜": opposite[:20],
        "전략 HOLD": len(hold), "전략 HOLD 날짜": hold[:40],
        "전략에만 있는 날(계산기 준비 구간 등)": len(only_java), "그 날짜": only_java[:40],
        "판정": "일치" if not opposite else "불일치",
    }

    # 같은 기간 순수 규칙(보고만): 전략이 처음 신호를 낸 날 종가 ~ 마지막 날 종가
    first = next((x for x in both if java[x] != "HOLD"), None)
    if first:
        a, b = d.index(first), d.index(both[-1])
        res["같은 기간 순수 규칙"] = {"구간": f"{d[a]}~{d[b]}"}
        for name, cost in calc.ARMS.items():
            eq, trades, cost_paid, _ = calc.run(s, h, l, c, cost, a, b)
            st, _ = calc.summary(eq)
            res["같은 기간 순수 규칙"][name] = {
                "풀 기간 수익률": st["기간 수익률"], "세션(2만 원) 기준 수익률": float((eq[-1] - calc.POOL) / calc.ALLOC),
                "진입": trades, "누적 비용(원)": cost_paid}
    print(json.dumps(res, ensure_ascii=False, indent=1))
    json.dump(res, open(os.path.join(OUT, "parity.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
