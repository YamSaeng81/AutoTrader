"""후보 B 전향 검증 집계기 — 새 실험 집계 경로 (2026-10-28, docs/B_VALIDATION_PREREG.md §5·§5-1·§6·§7 ⓔ)

    python scripts/forward_aggregate/b_aggregate.py count  <입력 폴더>               --config=<b 설정>   팔별 청산 왕복 수만
    python scripts/forward_aggregate/b_aggregate.py verify <입력 폴더>               --config=<b 설정>   입력·잔액 검산·측정 분류
    python scripts/forward_aggregate/b_aggregate.py final  <입력 폴더> <출력 폴더>   --config=<b 설정>   종료 뒤에만 — 판정

설정: 42세션 대응표·T0 를 사전 등록 §10 시작 기록에서 옮긴 JSON. 아직 시작 전이라 운영 설정 파일은 없다 — --config= 가 필수다.
입력 폴더: extract.sql 과 같은 형식의 CSV, 단 sessions.csv 에 slippage_rate 열(V95)이 있어야 한다.

🔴 aggregate.py(66세션 경로)는 고치지 않는다. 장부 재구성·일별 평가·왕복 매칭·검산은 거기서 가져다 쓴다(§6 "계산 정의는 현재 집계기와 같다").
🔴 이 파일이 맡는 것: 42세션·21코인·MAIN/STRESS 입력 검사, 세션 슬리피지 저장값 대조, §5 두 팔 공동 종료, §5-1 정지 규칙, §6 판정.
🔴 네트워크·DB 에 붙지 않는다. final 은 종료 뒤에만 돈다 — 중간 성과를 계산하지 않는다.
"""
from __future__ import annotations  # noqa: F401

import json
import os
import statistics
import sys
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import aggregate as ag  # noqa: E402
from aggregate import InputError, NA, fmt, iso, ts  # noqa: E402

STRATEGY = "COMPOSITE_MTF_MOMENTUM"                 # §1
TIMEFRAME = "H1"
INITIAL_CAPITAL = Decimal("1000000")               # §3
ARMS = ("MAIN", "STRESS")
SLIPPAGE = {"MAIN": Decimal("0.001"), "STRESS": Decimal("0.002")}   # §3 편도 0.1% · 0.2% (저장 비율)
COINS = ("KRW-ANKR", "KRW-BCH", "KRW-CRO", "KRW-CVC", "KRW-DKA", "KRW-ELF", "KRW-G", "KRW-GLM", "KRW-HIVE",
         "KRW-INJ", "KRW-IOTA", "KRW-JUP", "KRW-NEAR", "KRW-ONG", "KRW-POLYX", "KRW-POWR", "KRW-PUNDIX",
         "KRW-SC", "KRW-T", "KRW-WAVES", "KRW-WAXP")   # §2 — 전향 66세션 22코인 중 BLAST 제외(BWalkForwardRunner.COINS 와 같음)
END_TRADES = ag.END_TRADES          # §5 팔당 200
MIN_TRADES = ag.MIN_TRADES          # §5 6개월 시점 두 팔 모두 ≥ 100
MIN_POSITIVE = ag.MIN_POSITIVE      # §6 양수 코인 ≥ 12 / 21 (기존 숫자 유지)
MIN_PER_TRADE = ag.MIN_PER_TRADE    # §6 주 비용 거래당 ≥ 0.10%


def add_months(t, n):
    m = t.month - 1 + n
    try:
        return t.replace(year=t.year + m // 12, month=m % 12 + 1)
    except ValueError:
        raise InputError(f"T0 {iso(t)} + {n}개월 이 달력에 없는 날이다 — 설정에 6개월 시점을 직접 적고 근거를 남긴다")


def load_config(path):
    """B 설정 — 42세션·21코인(§2 집합 그대로)·코인마다 MAIN·STRESS 하나씩. 어긋나면 InputError."""
    with open(path, encoding="utf-8") as f:
        cfg = json.load(f)
    cfg["_path"] = path
    errors = []
    if cfg.get("strategy") != STRATEGY or cfg.get("timeframe") != TIMEFRAME:
        errors.append(f"전략·타임프레임이 {STRATEGY}·{TIMEFRAME} 가 아니다: {cfg.get('strategy')}·{cfg.get('timeframe')}")
    cfg["t0"] = ts(cfg["t0"])
    cfg["six_months_at"] = ts(cfg["six_months_at"])
    cfg["t_end"] = ts(cfg.get("t_end"))
    if cfg["six_months_at"] != add_months(cfg["t0"], 6):
        errors.append(f"six_months_at {iso(cfg['six_months_at'])} ≠ T0 + 6개월 {iso(add_months(cfg['t0'], 6))}")
    cfg["initial_capital"] = Decimal(cfg["initial_capital"])
    if cfg["initial_capital"] != INITIAL_CAPITAL:
        errors.append(f"초기자본 {cfg['initial_capital']} ≠ {INITIAL_CAPITAL}")
    sessions = cfg["sessions"]
    ids = [s["id"] for s in sessions]
    if len(ids) != 42 or len(set(ids)) != 42:
        errors.append(f"세션은 서로 다른 42개여야 한다: {len(set(ids))}개(행 {len(ids)})")
    coins = {s["coin"] for s in sessions}
    if coins != set(COINS):
        errors.append(f"코인 집합이 사전 등록 §2 와 다르다: 빠짐 {sorted(set(COINS) - coins)}, 남음 {sorted(coins - set(COINS))}")
    for coin in sorted(coins):
        arms = sorted(s["arm"] for s in sessions if s["coin"] == coin)
        if arms != sorted(ARMS):
            errors.append(f"{coin} 의 팔이 MAIN·STRESS 하나씩이 아니다: {arms}")
    # §5-1 ①: 거래지원 종료는 그 코인의 두 팔을 함께 정지한다 — 한 팔만 적힌 설정은 받지 않는다
    by_id = {s["id"]: s for s in sessions}
    for c in cfg["confirmed_ends"]:
        c["stopped_at"] = ts(c["stopped_at"])
        c["cash"] = Decimal(c["cash"])
        if c["id"] not in by_id:
            errors.append(f"확인된 종료 {c['id']}: 42세션 밖")
    ended = {c["id"] for c in cfg["confirmed_ends"]}
    for coin in sorted({by_id[i]["coin"] for i in ended if i in by_id}):
        pair = {s["id"] for s in sessions if s["coin"] == coin}
        if not pair <= ended:
            errors.append(f"{coin}: 확인된 종료가 한 팔만 있다 {sorted(pair & ended)} — 두 팔을 함께 정지한다(§5-1 ①)")
    if errors:
        raise InputError("\n".join(errors))
    # aggregate.build_trades·load_counts 는 팔 → 전략 이름으로 세션을 대조한다. 두 팔은 같은 전략이고 슬리피지로 갈린다.
    cfg["arms"] = {arm: STRATEGY for arm in ARMS}
    return cfg


def check_slippage(indir, cfg):
    """sessions.csv 의 세션 슬리피지 저장값(V95)이 팔의 값과 같은지 — 슬리피지만이 두 팔을 가른다(§3). NULL 도 받지 않는다."""
    arm_of = {s["id"]: s["arm"] for s in cfg["sessions"]}
    rows = ag.read_csv(os.path.join(indir, "sessions.csv"))
    if rows and "slippage_rate" not in rows[0]:
        raise InputError("sessions.csv 에 slippage_rate 열이 없다 — 두 팔을 구별할 수 없다")
    errors = []
    for r in rows:
        sid = int(r["id"])
        if sid not in arm_of:
            continue                                  # 세션 집합 검사는 공용 로더가 한다
        raw = (r.get("slippage_rate") or "").strip()
        if not raw:
            errors.append(f"세션 {sid}: 세션 슬리피지 저장값 없음(NULL) — B 세션은 값을 넣어 만든다(§3)")
        elif Decimal(raw) != SLIPPAGE[arm_of[sid]]:
            errors.append(f"세션 {sid}: 세션 슬리피지 {raw} ≠ {arm_of[sid]} 팔 {SLIPPAGE[arm_of[sid]]}")
    if errors:
        raise InputError("\n".join(errors))


def arm_counts(data, until):
    return {arm: len(ag.closed_trips(data, arm, until)) for arm in ARMS}


def determine_end(data):
    """(T_end, 사유). §5 — 두 팔 각각의 200번째 청산 중 늦은 쪽(포함 ≤), 그것이 6개월 시점 이후면 6개월 시점."""
    six = data.cfg["six_months_at"]
    t200 = {}
    for arm in ARMS:
        trips = ag.closed_trips(data, arm)
        if len(trips) >= END_TRADES:
            t200[arm] = trips[END_TRADES - 1][0]
    if len(t200) == len(ARMS) and max(t200.values()) <= six:
        return max(t200.values()), "두 팔 200거래 공동 시점"
    return six, "6개월 경과"


def cmd_count(data):
    """건수 전용 — 손익·자산·수익률을 계산하지 않는다. 종료 시점 추정 없음."""
    counts = arm_counts(data, data.snapshot)
    return {"snapshot": iso(data.snapshot), "closed_round_trips": counts,
            "reached_200": {a: n >= END_TRADES for a, n in counts.items()},
            "reached_100": {a: n >= MIN_TRADES for a, n in counts.items()},
            "six_months_at": iso(data.cfg["six_months_at"]), "inputs_sha256": data.hashes}


def judge(net, per_coin_trades, coins):
    """§6 표 — 주 비용(MAIN) 네 항목, 스트레스(STRESS) 두 항목. 항목별 (값, 충족 여부)."""
    out = {}
    for arm in ARMS:
        vals = [net[(c, arm)] for c in coins]
        pt, n_pt, counts = ag.per_trade_stats(per_coin_trades, arm, coins)
        out[arm] = {"mean": sum(vals) / len(coins), "median": statistics.median(vals),
                    "positive": sum(1 for v in vals if v > 0), "per_trade": pt, "per_trade_n": n_pt,
                    "round_trips": counts}
    m, s = out["MAIN"], out["STRESS"]
    checks = {
        "MAIN 평균 순수익 > 0": m["mean"] > 0,
        "MAIN 중앙값 > 0": m["median"] > 0,
        f"MAIN 양수 코인 ≥ {MIN_POSITIVE}/{len(coins)}": m["positive"] >= MIN_POSITIVE,
        "MAIN 거래당 순수익률 ≥ 0.10%": m["per_trade"] is not None and m["per_trade"] >= MIN_PER_TRADE,
        "STRESS 평균 순수익 > 0": s["mean"] > 0,
        "STRESS 거래당 순수익률 > 0": s["per_trade"] is not None and s["per_trade"] > 0,
    }
    return out, checks


def cmd_final(data, outdir):
    cfg = data.cfg
    t_end, reason = determine_end(data)
    if cfg["t_end"] is not None and cfg["t_end"] != t_end:
        raise InputError(f"설정 t_end {iso(cfg['t_end'])} ≠ 계산한 종료 {iso(t_end)}")
    if data.snapshot < t_end:
        raise InputError(f"종료 전이다 — 스냅샷 {iso(data.snapshot)} < T_end {iso(t_end)}. "
                         "final 은 종료 뒤에만 돈다(중간 성과 계산 금지)")
    errors = ag.verify(data, t_end)
    if errors:
        # §5-1 ⑦: 보유가 남았거나 평가 자료가 미확정이면 가상 청산·0 수익으로 채우지 않는다 — 계산하지 않고 판정 불가
        raise InputError("verify 실패 — 계산하지 않는다. 판정 시점까지 확정되지 않으면 결과는 판정 불가(§5-1 ⑦)\n"
                         + "\n".join(errors))

    os.makedirs(outdir, exist_ok=True)
    counts = arm_counts(data, t_end)
    report = [f"후보 B 전향 검증 최종 집계 — T_end {iso(t_end)} ({reason})",
              f"스냅샷 {iso(data.snapshot)} · 팔별 청산 왕복 {counts}"]
    sample = "정상" if all(n >= END_TRADES for n in counts.values()) else \
        ("표본 부족 명시" if all(n >= MIN_TRADES for n in counts.values()) else "판정 불가")
    result = {"t_end": iso(t_end), "end_reason": reason, "snapshot": iso(data.snapshot),
              "round_trips": counts, "sample": sample, "inputs_sha256": data.hashes,
              "confirmed_ends": [{"id": c["id"], "stopped_at": iso(c["stopped_at"]), "cash": str(c["cash"])}
                                 for c in cfg["confirmed_ends"]],
              "no_trade_used": sorted(data.no_trade_used.values(), key=lambda e: (e["coin_pair"], e["time"]))}
    report.append(f"무거래 봉 예외 사용 {len(result['no_trade_used'])}건 — 근거는 result.json")
    if sample == "판정 불가":
        result["verdict"] = "판정 불가"
        report.append("🔴 6개월 시점에 한 팔이라도 100거래 미만 — 판정 불가 (사전 등록 §5). 성과는 계산하지 않는다.")
        ag._write(outdir, report, result)
        return result

    arm_of = {s["id"]: (s["coin"], s["arm"]) for s in cfg["sessions"]}
    coins = list(COINS)
    init = cfg["initial_capital"]
    net, eq_rows = {}, []
    for sid in sorted(data.sessions):
        coin, arm = arm_of[sid]
        pts = ag.equity_path(data, sid, t_end)
        prev, total = init, Decimal(0)
        for label, at, e in pts:
            total += (e - prev) / init * 100
            eq_rows.append([coin, arm, sid, label.isoformat(), iso(at), str(e)])
            prev = e
        net[(coin, arm)] = (pts[-1][2] - init) / init * 100
        if total != net[(coin, arm)]:                    # 망원합 — Decimal 이라 정확히 같아야 한다
            raise InputError(f"세션 {sid}: 일별 변화 합 ≠ 최종 순수익")
    ag._csv(outdir, "equity_daily.csv", ["coin", "arm", "session_id", "day", "at", "equity"], eq_rows)

    per_coin_trades, trade_rows = ag.round_trip_returns(data, t_end)
    ag._csv(outdir, "round_trips.csv", ["coin", "arm", "session_id", "position_id", "buy_at", "sell_at",
                                        "entry_amount", "realized_recomputed", "return_pct"], trade_rows)

    stats, checks = judge(net, per_coin_trades, coins)
    verdict = "충족" if all(checks.values()) else "미충족"
    for arm in ARMS:
        st = stats[arm]
        report += ["", f"{arm} (슬리피지 편도 {SLIPPAGE[arm] * 100}%, 수수료 편도 0.05%)",
                   f"   평균 순수익 {fmt(st['mean'], 3)}% · 중앙값 {fmt(Decimal(st['median']), 3)}% · "
                   f"양수 코인 {st['positive']}/{len(coins)}",
                   f"   거래당 순수익률 {fmt(st['per_trade'])}% (거래 있는 코인 N={st['per_trade_n']}/{len(coins)})"]
    report += ["", "§6 항목: " + ", ".join(f"{k} {'✔' if v else '✗'}" for k, v in checks.items()),
               f"→ {verdict}" + ("" if verdict != "충족" else " — 실전 진입 승인이 아니다. 사전 등록 §8 의 별도 확인·승인을 따른다")]
    if sample == "표본 부족 명시":
        report.append("⚠️ 6개월 시점 두 팔 모두 ≥ 100 이지만 200 미만 — 표본 부족을 명시한다 (사전 등록 §5)")
    if cfg["confirmed_ends"]:
        report.append("📌 확인된 종료(§5-1): " + ", ".join(f"세션 {c['id']} {iso(c['stopped_at'])}"
                                                       for c in cfg["confirmed_ends"])
                      + " — 분모 21코인·42세션 유지, 정지 뒤는 확인된 현금으로 잇는다")

    ag._csv(outdir, "per_coin.csv", ["coin", "net_MAIN", "net_STRESS", "MAIN_round_trips", "STRESS_round_trips",
                                     "MAIN_per_trade_mean", "STRESS_per_trade_mean"],
            [[c, str(net[(c, "MAIN")]), str(net[(c, "STRESS")]),
              stats["MAIN"]["round_trips"][c], stats["STRESS"]["round_trips"][c]]
             + [(str(sum(v) / len(v)) if (v := per_coin_trades.get((c, arm))) else NA) for arm in ARMS]
             for c in coins])
    ag._csv(outdir, "ops_gap_signal_counts.csv", ["session_id", "day", "n"],
            [[r["session_id"], r["day"], r["n"]] for r in data.signal_counts])
    result.update({"verdict": verdict, "checks": checks,
                   "stats": {arm: {k: (str(v) if isinstance(v, Decimal) else v) for k, v in st.items()}
                             for arm, st in stats.items()}})
    ag._write(outdir, report, result)
    return result


def main(argv):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = [a for a in argv if not a.startswith("--config=")]
    cfg_path = next((a.split("=", 1)[1] for a in argv if a.startswith("--config=")), None)
    if cfg_path is None or len(args) < 2 or args[0] not in ("count", "verify", "final") \
            or (args[0] == "final" and len(args) < 3):
        print(__doc__)
        return 2
    try:
        cfg = load_config(cfg_path)
        check_slippage(args[1], cfg)
        if args[0] == "count":
            print(json.dumps(cmd_count(ag.load_counts(args[1], cfg)), ensure_ascii=False, indent=2))
            return 0
        data = ag.load(args[1], cfg)
        if args[0] == "verify":
            errors = ag.verify(data, cfg["t_end"])
            for e in errors:
                print("🔴 " + e)
            print(ag.describe_scope(data.mark_scope))
            print("verify " + ("통과" if not errors else f"실패 {len(errors)}건"))
            return 0 if not errors else 1
        cmd_final(data, args[2])
        print(open(os.path.join(args[2], "report.txt"), encoding="utf-8").read())
        return 0
    except InputError as e:
        print("🔴 계산하지 않는다 — " + str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
