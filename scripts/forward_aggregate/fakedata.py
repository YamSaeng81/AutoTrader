"""전향 집계기 가짜 장부 생성기 (2026-10-27) — 시험·로컬 PostgreSQL 검증 공용. 🔴 운영 데이터를 쓰지 않는다.

66세션 장부를 ledger_rounding 의 엔진 모형(PaperTradingService 금액식 + PostgreSQL 반올림)으로 만들고,
extract.sql 이 내는 것과 같은 형식의 CSV 로 쓴다.
"""
import csv
import os
import shutil
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import aggregate as ag  # noqa: E402
import ledger_rounding as lr  # noqa: E402

CFG_PATH = os.path.join(HERE, "forward_config.json")
CFG = ag.load_config(CFG_PATH)
T0 = CFG["t0"]
SIX = CFG["six_months_at"]
UTC = timezone.utc
SID = {(s["coin"], s["arm"]): s["id"] for s in CFG["sessions"]}
COINS = sorted({s["coin"] for s in CFG["sessions"]})
WORK = tempfile.mkdtemp(prefix="fa_test_")


def at(s):
    return datetime.fromisoformat(s).replace(tzinfo=UTC)


def z(t):
    return ag.iso(t)


class Fake:
    """66세션 가짜 장부. 매수·매도는 엔진 모형으로, 시각은 직접 준다."""

    def __init__(self):
        self.led = {s["id"]: lr.PaperLedger(CFG["initial_capital"], "0.80") for s in CFG["sessions"]}
        self.coin = {s["id"]: s["coin"] for s in CFG["sessions"]}
        self.arm = {s["id"]: s["arm"] for s in CFG["sessions"]}
        self.pos = []          # (session id, ledger pos dict, buy order row, sell order row|None)
        self.status = {sid: "RUNNING" for sid in self.led}
        self.stopped = {}
        for c in CFG["confirmed_ends"]:
            self.status[c["id"]] = "STOPPED"
            self.stopped[c["id"]] = c["stopped_at"]
        self.candles = {}      # coin → {open: close}
        self.no_trade = []
        self.signal = []
        self.next_order = 1
        self.extra_orders = []

    def buy(self, sid, when, px):
        led = self.led[sid]
        led.reload()
        assert led.buy(Decimal(str(px))), "최소 주문 미달"
        p = led.positions[-1]
        o = {"id": self.next_order, "side": "BUY", "price": led.orders[-1]["price"], "quantity": p["size"],
             "filled_at": when}
        self.next_order += 1
        self.pos.append([sid, p, o, None])
        return len(self.pos) - 1

    def sell(self, sid, when, px):
        led = self.led[sid]
        led.reload()
        led.sell(Decimal(str(px)))
        idx = max(i for i, e in enumerate(self.pos) if e[0] == sid)
        o = {"id": self.next_order, "side": "SELL", "price": led.orders[-1]["price"],
             "quantity": self.pos[idx][1]["size"], "filled_at": when}
        self.next_order += 1
        self.pos[idx][3] = o
        led.reload()
        return idx

    def trip(self, sid, buy_at, buy_px, sell_at, sell_px):
        self.buy(sid, buy_at, buy_px)
        return self.sell(sid, sell_at, sell_px)

    def bars(self, coin, start, end, price=lambda t: 1000):
        d = self.candles.setdefault(coin, {})
        t = start
        while t <= end:
            d[t] = Decimal(str(price(t)))
            t += timedelta(hours=1)

    def write(self, name, snapshot, mutate=None):
        d = os.path.join(WORK, name)
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)
        sessions = []
        for sid, led in self.led.items():
            led.reload()
            sessions.append({"id": sid, "coin_pair": self.coin[sid], "strategy_name": CFG["arms"][self.arm[sid]],
                             "timeframe": "H1", "status": self.status[sid], "initial_capital": "1000000.00",
                             "available_krw": str(led.db["available"]), "realized_pnl": str(led.db["realized"]),
                             "total_fee": str(led.db["total_fee"]), "started_at": z(T0 - timedelta(seconds=59)),
                             "stopped_at": z(self.stopped.get(sid))})
        positions, orders = [], []
        for i, (sid, p, b, s) in enumerate(self.pos, start=1):
            positions.append({"id": i, "session_id": sid, "coin_pair": self.coin[sid], "side": "BUY",
                              "invested_krw": str(p["invested_krw"]), "size": str(p["size"]),
                              "entry_price": str(p["entry_price"]), "avg_price": str(p["avg_price"]),
                              "realized_pnl": "" if p["realized_pnl"] is None else str(p["realized_pnl"]),
                              "position_fee": str(p["position_fee"]), "status": "CLOSED" if s else "OPEN",
                              # 엔진은 closed_at 을 매도 filled_at 과 다른 Instant.now() 로 남긴다 — 일부러 어긋나게 둔다(F21)
                              "opened_at": z(b["filled_at"] - timedelta(milliseconds=3)),
                              "closed_at": z(s["filled_at"] - timedelta(milliseconds=7)) if s else ""})
            for o in (b, s):
                if o:
                    orders.append({"id": o["id"], "session_id": sid, "position_id": i, "coin_pair": self.coin[sid],
                                   "side": o["side"], "order_type": "MARKET", "price": str(o["price"]),
                                   "quantity": str(o["quantity"]), "filled_quantity": str(o["quantity"]),
                                   "state": "FILLED", "filled_at": z(o["filled_at"])})
        orders += self.extra_orders
        candles = [{"coin_pair": c, "time": z(t), "close": str(v)}
                   for c, m in self.candles.items() for t, v in sorted(m.items())]
        tables = {"sessions": sessions, "positions": positions, "orders": orders, "candles": candles,
                  "signal_counts": self.signal or [{"session_id": 522, "day": "2026-10-01", "n": 17}],
                  "meta": [{"snapshot_ts": z(snapshot)}]}
        if self.no_trade:
            tables["no_trade_bars"] = [{"coin_pair": c, "time": z(t), "evidence": "가짜"} for c, t in self.no_trade]
        if mutate:
            mutate(tables)
        for n, rows in tables.items():
            cols = list(rows[0].keys()) if rows else {"orders": ["id", "session_id", "position_id", "coin_pair", "side",
                                                                 "order_type", "price", "quantity", "filled_quantity",
                                                                 "state", "filled_at"],
                                                      "positions": ["id", "session_id", "coin_pair", "side", "invested_krw",
                                                                    "size", "entry_price", "avg_price", "realized_pnl",
                                                                    "position_fee", "status", "opened_at", "closed_at"],
                                                      "candles": ["coin_pair", "time", "close"]}[n]
            with open(os.path.join(d, n + ".csv"), "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, fieldnames=cols)
                w.writeheader()
                w.writerows(rows)
        return d


def fill_arms(f, per_arm, start, step=timedelta(hours=3), profit=None):
    """세 팔에 왕복 per_arm 건씩 — 코인을 돌아가며, 한 왕복은 1시간 보유. profit[arm] 은 매도가 배율."""
    profit = profit or {"OFF": 1.0, "B": 1.0, "A": 1.0}
    t = start
    for k in range(per_arm):
        for arm in ("OFF", "B", "A"):
            coin = COINS[k % 22]
            if coin == "KRW-BLAST":
                coin = COINS[(k + 1) % 22]
            f.trip(SID[(coin, arm)], t + timedelta(minutes=1), 1000, t + timedelta(hours=1, minutes=1),
                   1000 * profit[arm])
        t += step
    return t


ALL_BARS = (T0 - timedelta(days=2)).replace(minute=0, second=0, microsecond=0)


def bars_all(f, end, price=lambda t: 1000):
    for c in COINS:
        if c != "KRW-BLAST":
            f.bars(c, ALL_BARS, end, price)
