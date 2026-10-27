"""전향 검증 집계기 — 로컬 CSV 계산기 (2026-10-27, 설계 docs/FORWARD_AGGREGATOR_DESIGN.md)

    python scripts/forward_aggregate/aggregate.py count  <입력 폴더>               팔별 청산 왕복 수만 (손익 계산 없음)
    python scripts/forward_aggregate/aggregate.py verify <입력 폴더>               입력·구조·잔액 검산·측정 분류
    python scripts/forward_aggregate/aggregate.py final  <입력 폴더> <출력 폴더>   verify 통과 + 종료 뒤에만 — 판정

입력 폴더: extract.sql 이 만든 CSV(sessions·positions·orders·candles·signal_counts·meta) + 선택 no_trade_bars.csv.
설정: 이 스크립트 옆의 forward_config.json(--config=<경로> 로 바꿀 수 있다).

🔴 네트워크·DB 에 붙지 않는다. CSV 만 읽는다.
🔴 final 은 스냅샷 시각이 종료 시각(T_end) 이후일 때만 돈다 — 중간 성과를 계산하지 않는다(사전 등록 §7).
🔴 판정 기준·블록·시드는 사전 등록(SUPERTREND_VALIDATION_PREREG.md §3·§4·§5) 그대로다. 결과를 보고 고치지 않는다.
"""
from __future__ import annotations  # noqa: F401 — dict | None 표기

import csv
import hashlib
import json
import os
import statistics
import sys
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ledger_rounding as lr  # noqa: E402

# ── 사전 등록 고정값 ────────────────────────────────────────────────────────
END_TRADES = 200            # §5 팔당 200거래
MIN_TRADES = 100            # §5 6개월 시점 팔당 100거래 미만이면 판정 불가
MIN_IMPROVED = 15           # §4(가) / 22
MIN_POSITIVE = 12           # §4(나) / 22
MIN_PER_TRADE = Decimal("0.10")   # §4(나) 주 비용, %
L_MAIN = 30                 # §3
L_SENS = (14, 60)
BOOT_B = 2000
SEED = 20260924
POSITION_REALIZED_HALF = Decimal("0.000000005")   # 포지션 realized_pnl NUMERIC(20,8) 저장 한 번의 최대 반올림
HOUR = timedelta(hours=1)
DAY = timedelta(days=1)

NA = "NA"


class InputError(Exception):
    """입력·구조·회계 검사 실패 — 계산하지 않는다(fail closed)."""


# ── 읽기 ────────────────────────────────────────────────────────────────────

def ts(s):
    """'2026-10-01T06:16:12.634313Z' → UTC datetime. 빈 값은 None."""
    if s is None or s == "":
        return None
    if not s.endswith("Z"):
        raise InputError(f"시각은 UTC 'Z' 표기여야 한다: {s!r}")
    return datetime.fromisoformat(s[:-1]).replace(tzinfo=timezone.utc)


def dec(s):
    return None if s is None or s == "" else Decimal(s)


def read_csv(path, required=True):
    if not os.path.exists(path):
        if required:
            raise InputError(f"입력 파일 없음: {path}")
        return []
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


@dataclass
class Trade:
    position: dict
    buy: dict
    sell: dict | None

    @property
    def buy_at(self):
        return self.buy["filled_at"]

    @property
    def sell_at(self):
        return self.sell["filled_at"] if self.sell else None

    def net_proceeds(self):
        proceeds = self.sell["quantity"] * self.sell["price"]
        return proceeds - proceeds * lr.FEE_RATE

    def realized(self):
        """재구성 입력(size·avg_price·매도가)으로 다시 계산한 실현손익 — DB 에 저장된 realized_pnl 이 아니다."""
        return self.net_proceeds() - self.position["size"] * self.position["avg_price"]

    def entry_amount(self):
        """거래당 순수익률의 분모 — 체결가 기준 진입 금액(수수료 제외)."""
        return self.position["size"] * self.position["entry_price"]


@dataclass
class Data:
    cfg: dict
    sessions: dict              # id → row
    trades: dict                # session id → [Trade] (매수 시각 순)
    candles: dict = field(default_factory=dict)   # coin → {open_time: close}
    no_trade: set = field(default_factory=set)    # {(coin, open_time)}
    signal_counts: list = field(default_factory=list)
    snapshot: datetime | None = None
    hashes: dict = field(default_factory=dict)


def load_config(path):
    with open(path, encoding="utf-8") as f:
        cfg = json.load(f)
    cfg["t0"] = ts(cfg["t0"])
    cfg["six_months_at"] = ts(cfg["six_months_at"])
    cfg["t_end"] = ts(cfg.get("t_end"))
    cfg["initial_capital"] = Decimal(cfg["initial_capital"])
    for c in cfg["confirmed_ends"]:
        c["stopped_at"] = ts(c["stopped_at"])
        c["cash"] = Decimal(c["cash"])
    sessions = cfg["sessions"]
    ids = [s["id"] for s in sessions]
    coins = sorted({s["coin"] for s in sessions})
    if len(ids) != 66 or len(set(ids)) != 66 or len(coins) != 22:
        raise InputError(f"설정의 세션은 66개·22코인이어야 한다: 세션 {len(set(ids))}, 코인 {len(coins)}")
    for coin in coins:
        arms = sorted(s["arm"] for s in sessions if s["coin"] == coin)
        if arms != ["A", "B", "OFF"]:
            raise InputError(f"설정: {coin} 의 팔이 OFF·B·A 하나씩이 아니다: {arms}")
    return cfg


def load(indir, cfg, with_market=True):
    files = ["sessions.csv", "positions.csv", "orders.csv", "meta.csv"]
    if with_market:
        files += ["candles.csv", "signal_counts.csv"]
    hashes = {f: sha256(os.path.join(indir, f)) for f in files if os.path.exists(os.path.join(indir, f))}

    meta = read_csv(os.path.join(indir, "meta.csv"))
    if len(meta) != 1:
        raise InputError("meta.csv 는 스냅샷 한 줄이어야 한다")
    snapshot = ts(meta[0]["snapshot_ts"])

    sessions = {}
    for r in read_csv(os.path.join(indir, "sessions.csv")):
        sid = int(r["id"])
        if sid in sessions:
            raise InputError(f"세션 중복: {sid}")
        sessions[sid] = {"id": sid, "coin_pair": r["coin_pair"], "strategy_name": r["strategy_name"],
                         "timeframe": r["timeframe"], "status": r["status"],
                         "initial_capital": dec(r["initial_capital"]), "available_krw": dec(r["available_krw"]),
                         "realized_pnl": dec(r["realized_pnl"]), "total_fee": dec(r["total_fee"]),
                         "started_at": ts(r["started_at"]), "stopped_at": ts(r["stopped_at"])}

    positions = {}
    for r in read_csv(os.path.join(indir, "positions.csv")):
        pid = int(r["id"])
        if pid in positions:
            raise InputError(f"포지션 중복: {pid}")
        positions[pid] = {"id": pid, "session_id": int(r["session_id"]), "coin_pair": r["coin_pair"],
                          "side": r["side"], "invested_krw": dec(r["invested_krw"]), "size": dec(r["size"]),
                          "entry_price": dec(r["entry_price"]), "avg_price": dec(r["avg_price"]),
                          "realized_pnl": dec(r["realized_pnl"]), "position_fee": dec(r["position_fee"]),
                          "status": r["status"]}

    orders = []
    for r in read_csv(os.path.join(indir, "orders.csv")):
        orders.append({"id": int(r["id"]), "session_id": int(r["session_id"]),
                       "position_id": int(r["position_id"]) if r["position_id"] else None,
                       "coin_pair": r["coin_pair"], "side": r["side"], "price": dec(r["price"]),
                       "quantity": dec(r["quantity"]), "filled_quantity": dec(r["filled_quantity"]),
                       "state": r["state"], "filled_at": ts(r["filled_at"])})

    data = Data(cfg=cfg, sessions=sessions, trades=build_trades(cfg, sessions, positions, orders),
                snapshot=snapshot, hashes=hashes)
    if with_market:
        for r in read_csv(os.path.join(indir, "candles.csv")):
            data.candles.setdefault(r["coin_pair"], {})[ts(r["time"])] = Decimal(r["close"])
        for r in read_csv(os.path.join(indir, "no_trade_bars.csv"), required=False):
            data.no_trade.add((r["coin_pair"], ts(r["time"])))
        data.signal_counts = read_csv(os.path.join(indir, "signal_counts.csv"))
    return data


# ── 입력·구조 검사 ──────────────────────────────────────────────────────────

def build_trades(cfg, sessions, positions, orders):
    """66세션 고정 확인 + 포지션·주문 짝 검사. 하나라도 어긋나면 InputError."""
    errors = []
    by_id = {s["id"]: s for s in cfg["sessions"]}
    if set(sessions) != set(by_id):
        errors.append(f"세션 집합이 설정과 다르다: 빠짐 {sorted(set(by_id) - set(sessions))}, "
                      f"남음 {sorted(set(sessions) - set(by_id))}")
    for sid, s in sessions.items():
        want = by_id.get(sid)
        if want is None:
            continue
        if s["coin_pair"] != want["coin"]:
            errors.append(f"세션 {sid}: 코인 {s['coin_pair']} ≠ 설정 {want['coin']}")
        if s["strategy_name"] != cfg["arms"][want["arm"]]:
            errors.append(f"세션 {sid}: 전략 {s['strategy_name']} ≠ 팔 {want['arm']} 의 {cfg['arms'][want['arm']]}")
        if s["timeframe"] != cfg["timeframe"]:
            errors.append(f"세션 {sid}: 타임프레임 {s['timeframe']}")
        if s["initial_capital"] != cfg["initial_capital"]:
            errors.append(f"세션 {sid}: 초기자본 {s['initial_capital']}")

    trades = {sid: [] for sid in sessions}
    by_pos = {}
    for o in orders:
        if o["session_id"] not in sessions:
            errors.append(f"주문 {o['id']}: 66세션 밖 session_id {o['session_id']}")
            continue
        if o["state"] != "FILLED":
            errors.append(f"주문 {o['id']}: 상태 {o['state']} — 모의 주문은 FILLED 만 있어야 한다")
        if o["filled_at"] is None:
            errors.append(f"주문 {o['id']}: filled_at 없음")
        if o["position_id"] is None or o["position_id"] not in positions:
            errors.append(f"주문 {o['id']}: 짝 포지션 없음({o['position_id']})")
            continue
        by_pos.setdefault(o["position_id"], []).append(o)

    for pid, p in positions.items():
        if p["session_id"] not in sessions:
            errors.append(f"포지션 {pid}: 66세션 밖 session_id {p['session_id']}")
            continue
        os_ = by_pos.get(pid, [])
        buys = [o for o in os_ if o["side"] == "BUY"]
        sells = [o for o in os_ if o["side"] == "SELL"]
        if len(buys) != 1 or len(sells) > 1 or len(buys) + len(sells) != len(os_):
            errors.append(f"포지션 {pid}: 매수 {len(buys)}·매도 {len(sells)}·기타 {len(os_) - len(buys) - len(sells)} "
                          "— 모의 엔진은 매수 1 + 매도 0~1 (부분 매도 없음)")
            continue
        buy, sell = buys[0], (sells[0] if sells else None)
        for o in os_:
            if o["session_id"] != p["session_id"] or o["coin_pair"] != p["coin_pair"]:
                errors.append(f"주문 {o['id']}: 세션·코인이 포지션 {pid} 와 다르다")
            if o["quantity"] != p["size"] or o["filled_quantity"] != p["size"]:
                errors.append(f"주문 {o['id']}: 수량 {o['quantity']}/{o['filled_quantity']} ≠ 포지션 size {p['size']}")
        if buy["price"] != p["entry_price"]:
            errors.append(f"포지션 {pid}: 매수가 {buy['price']} ≠ entry_price {p['entry_price']}")
        if p["coin_pair"] != sessions[p["session_id"]]["coin_pair"]:
            errors.append(f"포지션 {pid}: 코인이 세션과 다르다")
        if (p["status"] == "CLOSED") != (sell is not None):
            errors.append(f"포지션 {pid}: 상태 {p['status']} 인데 매도 {'있음' if sell else '없음'}")
        if buy["filled_at"] and buy["filled_at"] < cfg["t0"]:
            errors.append(f"포지션 {pid}: T0 이전 매수 {buy['filled_at']}")
        if sell and buy["filled_at"] and sell["filled_at"] and sell["filled_at"] < buy["filled_at"]:
            errors.append(f"포지션 {pid}: 매도가 매수보다 앞선다")
        trades[p["session_id"]].append(Trade(p, buy, sell))

    for sid, ts_ in trades.items():
        ts_.sort(key=lambda t: (t.buy_at, t.buy["id"]))
        for a, b in zip(ts_, ts_[1:]):        # 세션당 동시 보유 1 — 다음 매수는 앞 포지션 청산 뒤
            if a.sell is None or b.buy_at < a.sell_at:
                errors.append(f"세션 {sid}: 포지션 {a.position['id']}·{b.position['id']} 보유 구간이 겹친다")
    if errors:
        raise InputError("\n".join(errors))
    return trades


def closed_trips(data, arm=None, until=None):
    """청산된 왕복 — (매도 filled_at, 매도 주문 id, 세션 id). until 이 있으면 매도 filled_at ≤ until."""
    arm_of = {s["id"]: s["arm"] for s in data.cfg["sessions"]}
    out = []
    for sid, ts_ in data.trades.items():
        if arm is not None and arm_of[sid] != arm:
            continue
        for t in ts_:
            if t.sell is not None and (until is None or t.sell_at <= until):
                out.append((t.sell_at, t.sell["id"], sid))
    out.sort()
    return out


# ── 잔액 검산·측정 분류 (verify) ────────────────────────────────────────────

def ledger_at(trades, t, inclusive, initial):
    """시각 t 의 재구성 현금과 보유 목록. inclusive=True 면 filled_at ≤ t, 아니면 < t (설계 §2-2)."""
    def before(x):
        return x is not None and (x <= t if inclusive else x < t)
    cash = initial
    holding = []
    n_buy = n_sell = 0
    for tr in trades:
        if before(tr.buy_at):
            cash -= tr.position["invested_krw"]
            n_buy += 1
            if before(tr.sell_at):
                cash += tr.net_proceeds()
                n_sell += 1
            else:
                holding.append(tr)
    return cash, holding, n_buy, n_sell


def verify(data, t_end=None):
    """§3-4 ① 잔액 검산 + 포지션 저장값 대조 + 확인된 종료 + 상태. 실패 목록을 돌려준다(빈 목록 = 통과)."""
    cfg = data.cfg
    errors = []
    confirmed = {c["id"]: c for c in cfg["confirmed_ends"]}
    for sid, s in sorted(data.sessions.items()):
        trades = data.trades[sid]
        # ① 추출 시점 잔액 검산 — 스냅샷에 보이는 체결 전부(설계 §3-4 ①). 재구성은 ledger_rounding 과 같은 식이다.
        rec = lr.reconstruct(cfg["initial_capital"],
                             [t.position for t in trades],
                             [o for t in trades for o in ([t.buy] + ([t.sell] if t.sell else []))])
        for key, col in (("available", "available_krw"), ("total_fee", "total_fee"), ("realized", "realized_pnl")):
            diff = s[col] - rec[key]
            tol = lr.tolerance(rec["n_buy"], rec["n_sell"])[key]
            if abs(diff) > tol:
                errors.append(f"세션 {sid}: {col} 저장값 {s[col]} − 재구성 {rec[key]} = {diff} > 허용오차 {tol}")
        # 포지션 저장값 — 재구성에 쓰지 않지만 저장 반올림 상한 안인지 본다(재계산 값과 저장값은 다른 값이다)
        for t in trades:
            p = t.position
            buy_fee = p["invested_krw"] * lr.FEE_RATE
            if t.sell is None:
                if abs(p["position_fee"] - buy_fee) > lr.HALF_UNIT:
                    errors.append(f"포지션 {p['id']}: position_fee {p['position_fee']} vs 매수 수수료 {buy_fee}")
            else:
                sell_fee = t.sell["quantity"] * t.sell["price"] * lr.FEE_RATE
                if abs(p["position_fee"] - (buy_fee + sell_fee)) > 2 * lr.HALF_UNIT:
                    errors.append(f"포지션 {p['id']}: position_fee {p['position_fee']} vs 양쪽 수수료 {buy_fee + sell_fee}")
                if p["realized_pnl"] is None or abs(p["realized_pnl"] - t.realized()) > POSITION_REALIZED_HALF:
                    errors.append(f"포지션 {p['id']}: 저장 realized_pnl {p['realized_pnl']} vs 재계산 {t.realized()}")
        # 상태 — 확인된 종료 / 종료 뒤 정지 / 그 밖의 비 RUNNING = 측정 결측
        if sid in confirmed:
            c = confirmed[sid]
            if s["status"] != "STOPPED" or s["stopped_at"] is None:
                errors.append(f"세션 {sid}: 확인된 종료로 지정됐는데 상태 {s['status']}")
            elif abs(s["stopped_at"] - c["stopped_at"]) >= timedelta(milliseconds=1):
                errors.append(f"세션 {sid}: stopped_at {s['stopped_at']} ≠ 기록 {c['stopped_at']}")
            late = [o for t in trades for o in ([t.buy] + ([t.sell] if t.sell else []))
                    if o["filled_at"] > c["stopped_at"]]
            if late:
                errors.append(f"세션 {sid}: 정지 뒤 주문 {len(late)}건")
            cash, holding, nb, ns = ledger_at(trades, c["stopped_at"], True, cfg["initial_capital"])
            if holding:
                errors.append(f"세션 {sid}: 정지 시각에 보유 {len(holding)}")
            if abs(cash - c["cash"]) > lr.tolerance(nb, ns)["available"]:
                errors.append(f"세션 {sid}: 정지 시각 재구성 현금 {cash} ≠ 확인된 현금 {c['cash']}")
        elif s["status"] != "RUNNING":
            if t_end is None or s["stopped_at"] is None or s["stopped_at"] < t_end:
                errors.append(f"세션 {sid}: 상태 {s['status']}(정지 {s['stopped_at']}) — 지정된 종료가 아니다 → 측정 결측(판정 불가)")
    return errors


# ── 종료 시점 (count·final) ─────────────────────────────────────────────────

def arm_counts(data, until):
    return {arm: len(closed_trips(data, arm, until)) for arm in ("OFF", "B", "A")}


def determine_end(data):
    """(T_end, 사유). 200 공통 시점 = 세 팔 각각의 200번째 청산 중 가장 늦은 것 (설계 §5). 그 청산은 포함(≤)."""
    six = data.cfg["six_months_at"]
    t200 = {}
    for arm in ("OFF", "B", "A"):
        trips = closed_trips(data, arm)
        if len(trips) >= END_TRADES:
            t200[arm] = trips[END_TRADES - 1][0]
    if len(t200) == 3 and max(t200.values()) <= six:
        return max(t200.values()), "200거래 공통 시점"
    return six, "6개월 경과"


def cmd_count(data):
    """건수 전용 — 손익·자산·수익률을 계산하지 않는다(설계 §5, R11)."""
    counts = arm_counts(data, data.snapshot)
    out = {"snapshot": iso(data.snapshot), "closed_round_trips": counts,
           "reached_200": {a: n >= END_TRADES for a, n in counts.items()},
           "reached_100": {a: n >= MIN_TRADES for a, n in counts.items()},
           "six_months_at": iso(data.cfg["six_months_at"])}
    return out


# ── 평가 (final) ────────────────────────────────────────────────────────────

def mark_price(data, coin, last_open):
    """last_open 시작 봉의 종가. 없으면 '거래소에도 없는 봉'(no_trade_bars)일 때만 직전 봉으로 거슬러 간다(설계 §4-1)."""
    closes = data.candles.get(coin, {})
    o = last_open
    floor = data.cfg["t0"] - 2 * DAY
    while o >= floor:
        if o in closes:
            return closes[o]
        if (coin, o) not in data.no_trade:
            raise InputError(f"측정 결측: {coin} 봉 {iso(o)} 이 캐시에 없고 무거래 확인도 없다 — 판정 불가")
        o -= HOUR
    raise InputError(f"측정 결측: {coin} 의 평가 가격을 찾지 못했다(T0 이전까지)")


def equity(data, sid, t, inclusive):
    trades = data.trades[sid]
    cash, holding, _, _ = ledger_at(trades, t, inclusive, data.cfg["initial_capital"])
    if not holding:
        return cash
    coin = data.sessions[sid]["coin_pair"]
    last_open = t.replace(minute=0, second=0, microsecond=0) - HOUR    # 종가 시각 ≤ t 인 마지막 봉
    px = mark_price(data, coin, last_open)
    return cash + sum(tr.position["size"] for tr in holding) * px


def equity_path(data, sid, t_end):
    """[(구간 시작 날짜 라벨, E 구간 끝)] — 경계(<)마다 한 점 + T_end(≤) 한 점. 출발 E[T0] = 초기자본."""
    t0 = data.cfg["t0"]
    b = datetime.combine(t0.date() + DAY, datetime.min.time(), tzinfo=timezone.utc)
    points = []
    label = t0.date()
    while b <= t_end:
        points.append((label, b, equity(data, sid, b, inclusive=False)))
        label = b.date()
        b += DAY
    points.append((label, t_end, equity(data, sid, t_end, inclusive=True)))
    return points


def block_bootstrap(piv_dates, series_a, series_b, coins, L, rng):
    """§3 패널 시간블록 부트스트랩 — validation_analysis.block_bootstrap 과 같은 계산(F18 이 대조한다).

    piv_dates: 정렬된 날짜 배열, series_x[coin]: 날짜별 일별 변화(%) 배열(같은 순서).
    """
    import numpy as np
    days = np.array(piv_dates, dtype="datetime64[D]")
    origin = days.min()
    blk = ((days - origin) / np.timedelta64(1, "D") // L).astype(int)
    ublk = np.unique(blk)
    groups = [np.flatnonzero(blk == u) for u in ublk]
    K = len(ublk)
    A = {c: np.asarray(series_a[c], dtype=float) for c in coins}
    Bm = {c: np.asarray(series_b[c], dtype=float) for c in coins}

    def theta(rows):
        return float(np.mean([np.nansum(A[c][rows]) - np.nansum(Bm[c][rows]) for c in coins]))

    obs = theta(np.arange(len(days)))
    rep = np.empty(BOOT_B)
    for i in range(BOOT_B):
        pick = rng.integers(0, K, size=K)
        rep[i] = theta(np.concatenate([groups[p] for p in pick]))
    lo, hi = np.nanpercentile(rep, [2.5, 97.5])
    p = (1 + int(np.sum((rep - obs) >= obs))) / (np.sum(~np.isnan(rep)) + 1)
    return dict(theta=obs, lo=float(lo), hi=float(hi), p=float(p), K=K)


def round_trip_returns(data, t_end):
    """{(coin, arm): [거래당 순수익률 %]} 와 출력 행 — 매도 filled_at ≤ T_end 인 왕복만(설계 §3-2)."""
    arm_of = {s["id"]: (s["coin"], s["arm"]) for s in data.cfg["sessions"]}
    per_coin, rows = {}, []
    for sid, ts_ in data.trades.items():
        coin, arm = arm_of[sid]
        for t in ts_:
            if t.sell is not None and t.sell_at <= t_end:
                r = t.realized() / t.entry_amount() * 100
                per_coin.setdefault((coin, arm), []).append(r)
                rows.append([coin, arm, sid, t.position["id"], iso(t.buy_at), iso(t.sell_at),
                             str(t.entry_amount()), str(t.realized()), str(r)])
    return per_coin, rows


def per_trade_stats(per_coin, arm, coins):
    """R12 — 거래가 있는 N코인의 평균. 왕복 0 코인은 정의되지 않으므로 0 을 만들지 않는다. N=0 이면 None(NA)."""
    means = {c: sum(v) / len(v) for (c, a), v in per_coin.items() if a == arm and v}
    n = len(means)
    return (sum(means.values()) / n) if n else None, n, {c: len(per_coin.get((c, arm), [])) for c in coins}


def iso(t):
    return None if t is None else t.strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def fmt(x, nd=4):
    return NA if x is None else f"{x:+.{nd}f}"


def cmd_final(data, outdir):
    cfg = data.cfg
    t_end, reason = determine_end(data)
    if cfg["t_end"] is not None and cfg["t_end"] != t_end:
        raise InputError(f"설정 t_end {iso(cfg['t_end'])} ≠ 계산한 종료 {iso(t_end)}")
    if data.snapshot < t_end:
        raise InputError(f"종료 전이다 — 스냅샷 {iso(data.snapshot)} < T_end {iso(t_end)}. "
                         "final 은 종료 뒤에만 돈다(중간 성과 계산 금지)")
    errors = verify(data, t_end)
    if errors:
        raise InputError("verify 실패 — 판정하지 않는다\n" + "\n".join(errors))

    os.makedirs(outdir, exist_ok=True)
    counts = arm_counts(data, t_end)
    report = [f"전향 검증 최종 집계 — T_end {iso(t_end)} ({reason})",
              f"스냅샷 {iso(data.snapshot)} · 팔별 청산 왕복 {counts}"]
    sample = "정상" if all(n >= END_TRADES for n in counts.values()) else \
        ("표본 부족 명시" if all(n >= MIN_TRADES for n in counts.values()) else "판정 불가")
    result = {"t_end": iso(t_end), "end_reason": reason, "snapshot": iso(data.snapshot),
              "round_trips": counts, "sample": sample, "inputs_sha256": data.hashes}
    if sample == "판정 불가":
        report.append("🔴 한 팔이라도 100거래 미만 — 판정 불가 (사전 등록 §5). 성과는 계산하지 않는다.")
        _write(outdir, report, result)
        return result

    # 일별 자산·순수익
    arm_of = {s["id"]: (s["coin"], s["arm"]) for s in cfg["sessions"]}
    coins = sorted({c for c, _ in arm_of.values()})
    init = cfg["initial_capital"]
    paths = {sid: equity_path(data, sid, t_end) for sid in data.sessions}
    net = {}           # (coin, arm) → 순수익 %
    daily = {}         # (coin, arm) → [(label, r%)]
    eq_rows = []
    for sid, pts in paths.items():
        coin, arm = arm_of[sid]
        prev = init
        rows = []
        for label, at, e in pts:
            rows.append((label, (e - prev) / init * 100))
            eq_rows.append([coin, arm, sid, label.isoformat(), iso(at), str(e)])
            prev = e
        daily[(coin, arm)] = rows
        net[(coin, arm)] = (pts[-1][2] - init) / init * 100
        if sum(r for _, r in rows) != net[(coin, arm)]:          # §6.5 망원합 — Decimal 이라 정확히 같아야 한다
            raise InputError(f"세션 {sid}: 일별 변화 합 ≠ 최종 순수익")
    _csv(outdir, "equity_daily.csv", ["coin", "arm", "session_id", "day", "at", "equity"], eq_rows)

    # 거래당 순수익률 — 매도 filled_at ≤ T_end 인 왕복만, 재계산 실현손익 / 체결가 기준 진입 금액
    per_coin_trades, trade_rows = round_trip_returns(data, t_end)
    _csv(outdir, "round_trips.csv", ["coin", "arm", "session_id", "position_id", "buy_at", "sell_at",
                                     "entry_amount", "realized_recomputed", "return_pct"], trade_rows)

    def per_trade(arm):
        return per_trade_stats(per_coin_trades, arm, coins)

    # (가) A − B
    diff = {c: net[(c, "A")] - net[(c, "B")] for c in coins}
    mean_d = sum(diff.values()) / len(coins)
    med_d = statistics.median(diff.values())
    improved = sum(1 for v in diff.values() if v > 0)
    dates = [lab for lab, _ in daily[(coins[0], "A")]]
    import numpy as np

    def boot(L):
        return block_bootstrap(dates, {c: [float(r) for _, r in daily[(c, "A")]] for c in coins},
                               {c: [float(r) for _, r in daily[(c, "B")]] for c in coins},
                               coins, L, np.random.default_rng(SEED))
    bs = boot(L_MAIN)
    ga = mean_d > 0 and med_d > 0 and improved >= MIN_IMPROVED and bs["lo"] > 0
    verdict_a = "근거 있음" if ga else ("근거 없음" if (mean_d <= 0 or med_d <= 0) else "판단 보류")
    report += ["", "① (가) 변경안의 가치 — A − B (22코인)",
               f"   평균차 {fmt(mean_d, 3)}%p · 중앙차 {fmt(Decimal(med_d), 3)}%p · 개선 {improved}/22 · "
               f"구간 [{bs['lo']:+.3f}, {bs['hi']:+.3f}] θ={bs['theta']:+.3f} p={bs['p']:.4f} 블록 {bs['K']}",
               f"   → (가) {verdict_a}"]
    sens = {L: boot(L) for L in L_SENS}
    for L, r in sens.items():
        report.append(f"   민감도 L={L}: [{r['lo']:+.3f}, {r['hi']:+.3f}] p={r['p']:.4f} 블록 {r['K']}")

    # (나) A 자체 — 주 비용 네 항목, 스트레스는 NA
    a_net = [net[(c, "A")] for c in coins]
    mean_a = sum(a_net) / len(coins)
    med_a = statistics.median(a_net)
    pos_a = sum(1 for v in a_net if v > 0)
    pt, n_pt, pt_counts = per_trade("A")
    checks = {"평균 순수익 > 0": mean_a > 0, "중앙값 > 0": med_a > 0, f"양수 코인 ≥ {MIN_POSITIVE}/22": pos_a >= MIN_POSITIVE,
              "거래당 순수익률 ≥ 0.10%": (None if pt is None else pt >= MIN_PER_TRADE)}
    if any(v is False for v in checks.values()):
        verdict_b = "미충족"
    else:
        verdict_b = "미확정 유지 · 실전 진입 불가 (스트레스 미검증)"
    report += ["", "② (나) 수익 후보 자격 — A 자체",
               f"   평균 순수익 {fmt(mean_a, 3)}% · 중앙값 {fmt(Decimal(med_a), 3)}% · 양수 코인 {pos_a}/22",
               f"   거래당 순수익률 {fmt(pt)}% (거래 있는 코인 N={n_pt}/22, 코인별 왕복 {pt_counts})",
               "   스트레스(슬리피지 0.2%×2) — NA: 전향 66세션은 0.1% 로만 돌았다. 사후 차감은 판정에 쓰지 않는다",
               f"   항목: {', '.join(f'{k} {NA if v is None else ('✔' if v else '✗')}' for k, v in checks.items())}",
               f"   → (나) {verdict_b}"]

    # 보고만: B − OFF
    bo = {c: net[(c, "B")] - net[(c, "OFF")] for c in coins}
    report += ["", f"[보고만] B − OFF: 평균 {fmt(sum(bo.values()) / len(coins), 3)}%p · "
                   f"중앙 {fmt(Decimal(statistics.median(bo.values())), 3)}%p · 개선 {sum(1 for v in bo.values() if v > 0)}/22"]
    if sample == "표본 부족 명시":
        report.append("⚠️ 6개월 시점 모든 팔 ≥ 100 이지만 200 미만 — 표본 부족을 명시한다 (사전 등록 §5)")
    report += ["", "운용 공백(보고만, 판정 입력 아님): signal_counts.csv — 신호 기록은 실행 흔적이지 정상 가동의 증명이 아니다.",
               "개입 목록: SUPERTREND_VALIDATION_PREREG.md 개입 기록 #1~ 을 최종 보고에 함께 적는다(구간 절단 없음).",
               "📌 KRW-BLAST 576·577·578: 정지 뒤 확인된 현금으로 이은 사후 보완 규칙 적용(사전 등록 당시 없던 규칙)."]

    _csv(outdir, "per_coin.csv", ["coin", "net_OFF", "net_B", "net_A", "A_minus_B", "B_minus_OFF",
                                  "A_round_trips", "A_per_trade_mean"],
         [[c, str(net[(c, 'OFF')]), str(net[(c, 'B')]), str(net[(c, 'A')]), str(diff[c]), str(bo[c]),
           pt_counts[c], (str(sum(per_coin_trades[(c, 'A')]) / len(per_coin_trades[(c, 'A')]))
                          if per_coin_trades.get((c, 'A')) else NA)] for c in coins])
    _csv(outdir, "ops_gap_signal_counts.csv", ["session_id", "day", "n"],
         [[r["session_id"], r["day"], r["n"]] for r in data.signal_counts])
    result.update({"verdict_a": verdict_a, "verdict_b": verdict_b, "a_minus_b_mean": str(mean_d),
                   "a_minus_b_median": str(med_d), "improved": improved, "bootstrap_main": bs,
                   "bootstrap_sens": {str(k): v for k, v in sens.items()},
                   "a_mean": str(mean_a), "a_median": str(med_a), "a_positive": pos_a,
                   "a_per_trade": None if pt is None else str(pt), "a_per_trade_n": n_pt,
                   "stress": NA})
    _write(outdir, report, result)
    return result


def _csv(outdir, name, header, rows):
    with open(os.path.join(outdir, name), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


def _write(outdir, report, result):
    result["run_at"] = iso(datetime.now(timezone.utc))
    with open(os.path.join(outdir, "result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)
    with open(os.path.join(outdir, "report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(report) + "\n")


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    args = [a for a in argv if not a.startswith("--config=")]
    cfg_path = next((a.split("=", 1)[1] for a in argv if a.startswith("--config=")),
                    os.path.join(HERE, "forward_config.json"))
    if len(args) < 2 or args[0] not in ("count", "verify", "final") or (args[0] == "final" and len(args) < 3):
        print(__doc__)
        return 2
    try:
        cfg = load_config(cfg_path)
        if args[0] == "count":
            data = load(args[1], cfg, with_market=False)
            print(json.dumps(cmd_count(data), ensure_ascii=False, indent=2))
            return 0
        data = load(args[1], cfg)
        if args[0] == "verify":
            errors = verify(data, cfg["t_end"])
            for e in errors:
                print("🔴 " + e)
            print("verify " + ("통과" if not errors else f"실패 {len(errors)}건"))
            return 0 if not errors else 1
        res = cmd_final(data, args[2])
        print(open(os.path.join(args[2], "report.txt"), encoding="utf-8").read())
        return 0 if res else 1
    except InputError as e:
        print("🔴 계산하지 않는다 — " + str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
