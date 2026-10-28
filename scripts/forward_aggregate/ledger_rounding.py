"""전향 집계기 F20 — 모의 엔진 장부의 반올림 모형과 잔액 검산 허용오차 규칙 (2026-10-27)

설계: docs/FORWARD_AGGREGATOR_DESIGN.md §3-4 (R5).
엔진(PaperTradingService)의 금액 계산을 Decimal 로 그대로 흉내 내고, 세션 컬럼 NUMERIC(20,2) 에 쓸 때마다
PostgreSQL 처럼 반올림한다(가장 가까운 값, 정확히 절반이면 0 에서 먼 쪽 — PostgreSQL 15 로컬에서 확인).
재구성은 집계기가 쓸 원자료(포지션·주문 행)만으로 한다.

🔴 운영 데이터를 쓰지 않는다. 가짜 데이터 시험 전용이다.
"""
from decimal import (Decimal, ROUND_DOWN, ROUND_HALF_DOWN, ROUND_HALF_UP,
                     getcontext, localcontext)

getcontext().prec = 100          # Java BigDecimal 곱셈은 정확하다 — 중간 반올림이 끼지 않을 만큼 넉넉히

FEE_RATE = Decimal("0.0005")     # TradingConstants.FEE_RATE
SLIPPAGE = Decimal("0.001")      # TradingConstants.PAPER_SLIPPAGE_PCT
MIN_ORDER_KRW = Decimal("5000")  # PaperTradingService.MIN_ORDER_KRW
HALF_UNIT = Decimal("0.005")     # NUMERIC(20,2) 저장 한 번의 최대 반올림 오차
S2 = Decimal("0.01")
S8 = Decimal("0.00000001")


def pg_round(x, scale):
    """NUMERIC(p, scale) 저장 — PostgreSQL 은 가장 가까운 값으로, 절반은 0 에서 먼 쪽으로 반올림한다."""
    return x.quantize(Decimal(1).scaleb(-scale), rounding=ROUND_HALF_UP)


def java_divide_down(a, b, scale):
    """BigDecimal.divide(b, scale, RoundingMode.DOWN) — 정확한 몫을 scale 자리에서 버린다."""
    with localcontext() as ctx:
        ctx.rounding = ROUND_DOWN
        return (a / b).quantize(Decimal(1).scaleb(-scale), rounding=ROUND_DOWN)


def java_divide_half_up(a, b, scale):
    """BigDecimal.divide(b, scale, RoundingMode.HALF_UP)."""
    return (a / b).quantize(Decimal(1).scaleb(-scale), rounding=ROUND_HALF_UP)


class PaperLedger:
    """모의 세션 하나의 장부.

    db    — 세션 행에 저장된 값(NUMERIC(20,2), 쓸 때마다 반올림)
    mem   — 엔진이 들고 있는 엔티티 값. save() 뒤에도 반올림되지 않은 채 남고, 다음 틱에 다시 읽을 때(reload)
            비로소 db 값이 된다. 같은 틱의 청산 뒤 매수는 반올림 전 값으로 이어진다.
    exact — 반올림이 전혀 없는 그림자 장부(엔진이 계산한 값의 정확한 합). 재구성이 이것과 같아야 한다.
    """

    KEYS = ("available", "total_fee", "realized")

    def __init__(self, initial, invest_ratio, slippage=SLIPPAGE):
        self.slippage = Decimal(slippage)   # 세션 슬리피지(V95) — 기본값은 종전 상수
        start = pg_round(Decimal(initial), 2)
        self.db = {"available": start, "total_fee": Decimal(0), "realized": Decimal(0)}
        self.mem = dict(self.db)
        self.exact = dict(self.db)
        self.invest_ratio = Decimal(invest_ratio)
        self.positions = []    # 저장된 포지션 행
        self.orders = []       # 저장된 주문 행
        self.open = None
        self.n_buy = 0
        self.n_sell = 0

    def reload(self):
        self.mem = dict(self.db)

    def _save(self):
        self.db = {k: pg_round(self.mem[k], 2) for k in self.KEYS}

    def buy(self, signal_price):
        """PaperTradingService:937-1023. 최소 주문 미달이면 False."""
        assert self.open is None
        invest = (self.mem["available"] * self.invest_ratio).quantize(S2, rounding=ROUND_DOWN)
        if invest < MIN_ORDER_KRW:
            return False
        price = (signal_price * (1 + self.slippage)).quantize(S8, rounding=ROUND_HALF_UP)
        fee = invest * FEE_RATE
        qty = java_divide_down(invest - fee, price, 8)
        if qty <= 0:
            return False
        avg = java_divide_half_up(invest, qty, 8)
        pos = {"id": len(self.positions) + 1, "invested_krw": pg_round(invest, 8), "size": pg_round(qty, 8),
               "entry_price": pg_round(price, 8), "avg_price": pg_round(avg, 8),
               "position_fee": pg_round(fee, 2), "realized_pnl": None}
        self.positions.append(pos)
        self.orders.append({"position_id": pos["id"], "side": "BUY", "price": pg_round(price, 8),
                            "quantity": pg_round(qty, 8)})
        self.mem["available"] -= invest
        self.mem["total_fee"] += fee
        self.exact["available"] -= invest
        self.exact["total_fee"] += fee
        self._save()
        self.open = pos
        self.n_buy += 1
        return True

    def sell(self, signal_price):
        """PaperTradingService:1040-1083."""
        pos = self.open
        assert pos is not None
        price = (signal_price * (1 - self.slippage)).quantize(S8, rounding=ROUND_HALF_DOWN)
        proceeds = pos["size"] * price
        fee = proceeds * FEE_RATE
        net = proceeds - fee
        realized = net - pos["size"] * pos["avg_price"]
        pos["realized_pnl"] = pg_round(realized, 8)
        pos["position_fee"] = pg_round(pos["position_fee"] + fee, 2)
        self.orders.append({"position_id": pos["id"], "side": "SELL", "price": pg_round(price, 8),
                            "quantity": pos["size"]})
        for book in (self.mem, self.exact):
            book["available"] += net
            book["realized"] += realized
            book["total_fee"] += fee
        self._save()
        self.open = None
        self.n_sell += 1


def reconstruct(initial, positions, orders):
    """집계기가 할 재구성 — 포지션의 invested_krw·size·avg_price 와 매도 주문의 price 만 쓴다(§3-2)."""
    by_pos = {p["id"]: p for p in positions}
    cash = Decimal(initial)
    fee_sum = Decimal(0)
    realized = Decimal(0)
    n_buy = n_sell = 0
    for o in orders:
        p = by_pos[o["position_id"]]
        if o["side"] == "BUY":
            cash -= p["invested_krw"]
            fee_sum += p["invested_krw"] * FEE_RATE
            n_buy += 1
        else:
            proceeds = o["quantity"] * o["price"]
            fee = proceeds * FEE_RATE
            cash += proceeds - fee
            fee_sum += fee
            realized += proceeds - fee - p["size"] * p["avg_price"]
            n_sell += 1
    return {"available": cash, "total_fee": fee_sum, "realized": realized, "n_buy": n_buy, "n_sell": n_sell}


def tolerance(n_buy, n_sell):
    """잔액 검산 허용오차 — 반올림이 들어가는 저장 횟수 × 0.005원 (설계 §3-4 R5 의 상한 규칙).

    available — 매수 저장은 (2자리 값 − 2자리 투자금)이라 반올림이 없고, 매도마다 최대 한 번
    total_fee — 매수·매도 모두 2자리를 넘는 수수료를 더하므로 저장마다 최대 한 번
    realized  — 매도마다 최대 한 번
    """
    return {"available": HALF_UNIT * n_sell,
            "total_fee": HALF_UNIT * (n_buy + n_sell),
            "realized": HALF_UNIT * n_sell}


def check(db, recon):
    """세션 행(db)과 재구성(recon)을 대조한다. {key: (차이, 허용오차, 통과)}"""
    tol = tolerance(recon["n_buy"], recon["n_sell"])
    out = {}
    for k in PaperLedger.KEYS:
        diff = db[k] - recon[k]
        out[k] = (diff, tol[k], abs(diff) <= tol[k])
    return out
