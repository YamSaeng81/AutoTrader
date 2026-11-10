"""청산 A/B 전향 탐색 집계기 (총괄 v1.143 판단 3 · v1.144 E) — 4쌍 8세션, 42일 고정 구간.

입력(모두 CSV, 생성 뒤 읽기 전용 추출로 만든다 — 이 도구는 DB 에 접속하지 않는다):
  arms.csv       pair_id,arm,session_id,strategy,coin,timeframe,initial_capital,invest_ratio,max_investment,
                 max_hold_hours,stop_loss_pct,params_json,ruleset_hash,code_sha,start_ts,end_ts
  positions.csv  session_id,position_id,opened_at,closed_at,size,buy_cash_out,sell_cash_in,status
                 (buy_cash_out = 매수로 나간 현금(수수료 포함), sell_cash_in = 매도로 들어온 현금(수수료 뺀 뒤). 열린 포지션은
                  closed_at·sell_cash_in 이 빈 칸)
  marks.csv      coin,ts,price   (일별 평가 시각의 닫힌 봉 종가. 없으면 그 시각 평가 불가)
  intervals.csv  start_ts,end_ts,kind,note   (kind: CODE_CHANGE | OUTAGE | COLLECTION_GAP — 판정에서 해석할 구간 표시)

고정 규칙(결과 전에 정함):
  - 주지표: 팔마다 (끝 시각 순자산 / 초기 자본 − 1). 순자산 = 현금 + Σ 열린 수량 × 평가가 × (1 − 예상 청산 비용).
    예상 청산 비용 = 수수료 0.05% + 모의 슬리피지 0.1% (PAPER 어댑터와 같다). 4쌍의 (B − A) 를 동일 가중 평균한 값 하나.
  - 끝 시각에 열린 포지션의 평가가가 없으면 그 쌍은 판정 불가이고, 주지표도 판정 불가다. 쌍을 빼고 평균하지 않는다.
  - 설정 검사: 정확히 4쌍 8세션, 쌍마다 A·B 하나씩, A 의 stop_loss_pct 는 빈 값·B 는 5, 그 밖의 설정·시작/끝 시각이 쌍 안에서
    같아야 한다. 어긋나면 계산하지 않는다(설정 거절).
  - CODE_CHANGE 구간이 있으면 주지표 옆에 "해석 불가 구간 있음"을 표시한다(값을 빼거나 고치지 않는다). OUTAGE·COLLECTION_GAP 도 표시만.
  - 진단: 일별 순자산, 거래 없는 날 포함. 같은 달력 주(월요일 시작)의 모든 팔을 함께 복원추출하는 주 블록 부트스트랩
    (2,000회, 시드 20261110)의 90% 구간 — 진단 값이며 통과 기준이 아니다.
  - 반복 투자금 합을 분모로 쓰지 않는다. 승격·연장·튜닝 판단을 하지 않는다.
"""
from __future__ import annotations

import csv
import json
import random
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

FEE = Decimal("0.0005")
SLIP = Decimal("0.001")
EXIT_COST = FEE + SLIP
PAIRS = 4
SEED = 20261110
REPS = 2000
SAME_KEYS = ("strategy", "coin", "timeframe", "initial_capital", "invest_ratio", "max_investment",
             "max_hold_hours", "params_json", "code_sha", "start_ts", "end_ts")


class ConfigRejected(Exception):
    pass


def ts(s: str) -> datetime:
    d = datetime.fromisoformat(s.replace("Z", "+00:00"))
    if d.tzinfo is None:
        raise ConfigRejected(f"시각에 시간대가 없다: {s}")
    return d.astimezone(timezone.utc)


def dec(s: str) -> Decimal | None:
    s = (s or "").strip()
    return Decimal(s) if s else None


def read(path: Path) -> list[dict]:
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


@dataclass
class Arm:
    pair_id: str
    arm: str
    session_id: str
    row: dict
    capital: Decimal
    start: datetime
    end: datetime


def check_config(arms_rows: list[dict]) -> dict[str, dict[str, Arm]]:
    if len(arms_rows) != 2 * PAIRS:
        raise ConfigRejected(f"세션 수 {len(arms_rows)} ≠ {2 * PAIRS}")
    if len({r["session_id"] for r in arms_rows}) != 2 * PAIRS:
        raise ConfigRejected("세션 ID 중복")
    pairs: dict[str, dict[str, Arm]] = {}
    for r in arms_rows:
        if r["arm"] not in ("A", "B"):
            raise ConfigRejected(f"팔 이름 {r['arm']}")
        a = Arm(r["pair_id"], r["arm"], r["session_id"], r, Decimal(r["initial_capital"]), ts(r["start_ts"]), ts(r["end_ts"]))
        if a.arm in pairs.setdefault(a.pair_id, {}):
            raise ConfigRejected(f"쌍 {a.pair_id} 에 {a.arm} 팔이 둘")
        pairs[a.pair_id][a.arm] = a
    if len(pairs) != PAIRS:
        raise ConfigRejected(f"쌍 수 {len(pairs)} ≠ {PAIRS}")
    starts = {a.start for p in pairs.values() for a in p.values()}
    ends = {a.end for p in pairs.values() for a in p.values()}
    if len(starts) != 1 or len(ends) != 1:
        raise ConfigRejected("모든 팔의 시작·끝 시각이 같아야 한다(동시 시작·고정 구간)")
    (start,), (end,) = starts, ends
    if end - start != timedelta(days=42):
        raise ConfigRejected(f"구간 {end - start} ≠ 42일")
    for pid, p in pairs.items():
        if set(p) != {"A", "B"}:
            raise ConfigRejected(f"쌍 {pid} 팔 {sorted(p)}")
        A, B = p["A"].row, p["B"].row
        if (A.get("stop_loss_pct") or "").strip() != "":
            raise ConfigRejected(f"쌍 {pid} A 의 stop_loss_pct 는 빈 값이어야 한다")
        if dec(B.get("stop_loss_pct")) != Decimal("5"):
            raise ConfigRejected(f"쌍 {pid} B 의 stop_loss_pct 는 5 여야 한다")
        for k in SAME_KEYS:
            av, bv = A.get(k), B.get(k)
            if k == "params_json":
                av, bv = json.loads(av or "{}"), json.loads(bv or "{}")
            if av != bv:
                raise ConfigRejected(f"쌍 {pid} 의 {k} 가 A·B 에서 다르다")
    return pairs


def marks_by_coin(rows: list[dict]) -> dict[str, dict[datetime, Decimal]]:
    out: dict[str, dict[datetime, Decimal]] = {}
    for r in rows:
        p = dec(r["price"])
        if p is not None and p > 0:
            out.setdefault(r["coin"], {})[ts(r["ts"])] = p
    return out


def nav_at(arm: Arm, positions: list[dict], marks: dict[datetime, Decimal], t: datetime) -> Decimal | None:
    """t 시각 순자산. 열린 포지션이 있는데 평가가가 없으면 None(평가 불가)."""
    cash = arm.capital
    held = Decimal(0)
    for p in positions:
        opened = ts(p["opened_at"])
        if opened > t:
            continue
        cash -= Decimal(p["buy_cash_out"])
        closed = ts(p["closed_at"]) if (p.get("closed_at") or "").strip() else None
        if closed is not None and closed <= t:
            cash += Decimal(p["sell_cash_in"])
        else:
            held += Decimal(p["size"])
    if held == 0:
        return cash
    price = marks.get(t)
    if price is None:
        return None
    return cash + held * price * (1 - EXIT_COST)


def daily_points(start: datetime, end: datetime) -> list[datetime]:
    out, t = [], start
    while t <= end:
        out.append(t)
        t += timedelta(days=1)
    return out


def bootstrap_ci(diff_daily: dict[str, list[Decimal | None]], days: list[datetime]) -> tuple[str, str] | None:
    """쌍 평균 (B−A) 일별 수익 차이를 주 블록으로 함께 복원추출 — 일별 값이 하나라도 없으면 None."""
    n = len(days) - 1
    series = []
    for i in range(1, n + 1):
        vals = []
        for v in diff_daily.values():
            if v[i] is None or v[i - 1] is None:
                return None
            vals.append(v[i] - v[i - 1])
        series.append(sum(vals) / len(vals))
    weeks: dict[str, list[Decimal]] = {}
    for i, d in enumerate(days[1:]):
        key = (d - timedelta(days=d.weekday())).date().isoformat()
        weeks.setdefault(key, []).append(series[i])
    blocks = [weeks[k] for k in sorted(weeks)]
    rng = random.Random(SEED)
    stats = []
    for _ in range(REPS):
        pick = [blocks[rng.randrange(len(blocks))] for _ in blocks]
        stats.append(sum(x for b in pick for x in b))
    stats.sort()
    lo, hi = stats[int(0.05 * REPS)], stats[int(0.95 * REPS) - 1]
    q = Decimal("0.000001")
    return str(lo.quantize(q, ROUND_HALF_UP)), str(hi.quantize(q, ROUND_HALF_UP))


def aggregate(arms_rows, pos_rows, mark_rows, interval_rows) -> dict:
    pairs = check_config(arms_rows)
    marks = marks_by_coin(mark_rows)
    by_session: dict[str, list[dict]] = {}
    for p in pos_rows:
        by_session.setdefault(p["session_id"], []).append(p)
    any_arm = next(iter(next(iter(pairs.values())).values()))
    start, end = any_arm.start, any_arm.end
    days = daily_points(start, end)
    flags = sorted({r["kind"] for r in interval_rows if ts(r["start_ts"]) < end and ts(r["end_ts"]) > start})
    out_pairs, diffs, diff_daily, undecidable = {}, [], {}, []
    q = Decimal("0.000001")
    for pid in sorted(pairs):
        p = pairs[pid]
        res, daily = {}, {}
        for arm in ("A", "B"):
            a = p[arm]
            coin_marks = marks.get(a.row["coin"], {})
            pos = by_session.get(a.session_id, [])
            series = [nav_at(a, pos, coin_marks, d) for d in days]
            daily[arm] = [None if v is None else v / a.capital - 1 for v in series]
            end_nav = series[-1]
            res[arm] = {
                "session_id": a.session_id,
                "end_nav": None if end_nav is None else str(end_nav.quantize(Decimal("0.01"), ROUND_HALF_UP)),
                "return": None if end_nav is None else str((end_nav / a.capital - 1).quantize(q, ROUND_HALF_UP)),
                "closed_trades": sum(1 for x in pos if (x.get("closed_at") or "").strip()),
                "open_at_end": sum(1 for x in pos if not (x.get("closed_at") or "").strip() or ts(x["closed_at"]) > end),
                "days_unvalued": sum(1 for v in series if v is None),
            }
        if res["A"]["return"] is None or res["B"]["return"] is None:
            res["B_minus_A"] = None
            undecidable.append(pid)
        else:
            d = Decimal(res["B"]["return"]) - Decimal(res["A"]["return"])
            res["B_minus_A"] = str(d)
            diffs.append(d)
        diff_daily[pid] = [None if a is None or b is None else b - a for a, b in zip(daily["A"], daily["B"])]
        out_pairs[pid] = res
    primary = None if undecidable else str((sum(diffs) / len(diffs)).quantize(q, ROUND_HALF_UP))
    ci = None if undecidable else bootstrap_ci(diff_daily, days)
    return {
        "window": {"start": start.isoformat(), "end": end.isoformat(), "days": len(days) - 1},
        "pairs": out_pairs,
        "primary_mean_B_minus_A": primary,
        "primary_status": "판정 불가(끝 평가가 없는 쌍: " + ",".join(undecidable) + ")" if undecidable else "계산됨(탐색 결과 — 승격 판단 아님)",
        "interval_flags": flags,
        "diagnostic_week_block_90ci": ci,
        "rules": {"exit_cost": str(EXIT_COST), "seed": SEED, "reps": REPS},
    }


def main(argv: list[str]) -> int:
    d = Path(argv[1])
    out = aggregate(read(d / "arms.csv"), read(d / "positions.csv"), read(d / "marks.csv"),
                    read(d / "intervals.csv") if (d / "intervals.csv").exists() else [])
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
