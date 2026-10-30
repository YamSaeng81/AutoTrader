"""업비트 주문 API 대역 — 실제 프로세스 재시작 시험 전용 (2026-10-13).

앱과 **별도 프로세스**로 돈다. 앱을 강제 종료해도 접수·체결 기록이 남아야 외부 주문과 로컬 DB 의
불일치를 재현할 수 있다. 상태는 매 변경마다 JSON 파일에 쓴다.

앱이 쓰는 엔드포인트:
  POST   /v1/orders                 주문 생성 (identifier 포함)
  GET    /v1/order?uuid=|identifier= 주문 조회 (없으면 404)
  DELETE /v1/order?uuid=|identifier= 주문 취소 (없으면 404, 종결이면 400)
  GET    /v1/accounts               계좌
  GET    /v1/orders/chance          수수료율

시험 구동기가 쓰는 제어 엔드포인트(/_ctl):
  POST /_ctl/plan   {"bid": {...}, "ask": {...}}  다음 생성 요청의 처리 방식
        fill:  "done" | "wait" | "partial_wait"     (체결 방식)
        hold_ms: 응답 전 대기(그동안 앱을 죽일 수 있다)
        drop:  true 면 접수는 기록하고 응답하지 않고 연결을 끊는다(응답 유실)
        reject: {"status":400,"name":"insufficient_funds_bid"} — 주문을 만들지 않고 거절
        error_after_create: {"status":400,"name":"create_bid_error"} — 주문은 만들고 오류로 응답
  POST /_ctl/fill   {"uuid":..., "state":"done|cancel|wait", "volume":"..", "funds":".."}  체결 진행
  GET  /_ctl/orders  전체 주문
  POST /_ctl/reset
  POST /_ctl/market {"ticker": {"KRW-DOGE": {"price": "300", "trade_age_sec": 600}},
                     "orderbook": {"KRW-DOGE": {"bid": "299", "size": "100"}}, "candles": {"KRW-BTC": {"close": "100000000"}},
                     "fail": false}
        공개 시세 응답(2026-10-15 리뷰 8회차 후속) — 앱이 -Dupbit.market-data.base-url 로 이 대역을 볼 때만 쓰인다.
        설정이 없으면(기본) /v1/ticker·/v1/orderbook·/v1/candles 는 503 — 외부 차단과 같은 실패. fail=true 도 503.
        시세의 trade_timestamp 는 지금 − trade_age_sec(체결이 조용한 코인), 호가 timestamp 는 지금.
        candles 의 "absent": [봉 시작 epoch 초, ...] — 그 봉은 대역(거래소)에도 없다(점검·무체결, 2026-10-19).
        (2026-10-30, SMA20 리허설 — 설정이 없으면 종전과 같다) candles 의 "slope": 시간당 종가 변화, "anchor": 종가가 "close" 인
        봉의 시작 epoch 초(없으면 지금 봉), "spread": 고가·저가 폭. "fill_price": {"KRW-BTC": "..."} 시장가 체결가(없으면 300),
        "coin_currency": 계좌의 코인 이름(없으면 DOGE), "markets": [{"market":..,"korean_name":..,"english_name":..}] 상태 확인 목록.
  POST /_ctl/health {"mode": "ok" | "fail" | "slow"}  상태 확인(/v1/market/all) 응답 — fail 은 503, slow 는 3.5초 뒤 200 (2026-10-19)
"""
import calendar
import json
import os
import sys
import threading
import time
import uuid as uuidlib
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

STATE_FILE = sys.argv[2] if len(sys.argv) > 2 else 'exchange_state.json'
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
PRICE = Decimal('300')          # DOGE 가격
FEE_RATE = Decimal('0.0005')
ASK_FEE_RATE = Decimal('0.0004')
lock = threading.Lock()


def load():
    if os.path.exists(STATE_FILE):
        with open(STATE_FILE, encoding='utf-8') as f:
            return json.load(f)
    return {'orders': {}, 'plan': {}, 'krw': '1000000', 'coin': '0', 'creates': []}


state = load()


def save():
    tmp = STATE_FILE + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(state, f, ensure_ascii=False, indent=1)
    os.replace(tmp, STATE_FILE)


def view(o):
    """업비트 응답 형식."""
    r = {k: o[k] for k in ('uuid', 'side', 'ord_type', 'state', 'market', 'identifier', 'created_at')}
    for k in ('price', 'volume', 'remaining_volume', 'executed_volume', 'executed_funds', 'paid_fee'):
        if o.get(k) is not None:
            r[k] = o[k]
    return r


def apply_fill(o, volume, funds):
    """체결량을 누적값으로 맞추고 계좌를 움직인다(증가분만)."""
    prev_v = Decimal(o.get('executed_volume') or '0')
    prev_f = Decimal(o.get('executed_funds') or '0')
    v, f = Decimal(volume), Decimal(funds)
    dv, df = v - prev_v, f - prev_f
    # 매도 수수료율을 앱의 추정(0.05%)과 다르게 둔다 — 앱이 거래소 보고 수수료를 쓰는지 구분된다 (2026-10-14)
    rate = ASK_FEE_RATE if o['side'] == 'ask' else FEE_RATE
    fee = (f * rate).quantize(Decimal('0.00000001'))
    dfee = fee - Decimal(o.get('paid_fee') or '0')
    if o['side'] == 'bid':
        state['krw'] = str(Decimal(state['krw']) - df - dfee)
        state['coin'] = str(Decimal(state['coin']) + dv)
    else:
        state['krw'] = str(Decimal(state['krw']) + df - dfee)
        state['coin'] = str(Decimal(state['coin']) - dv)
    o['executed_volume'] = str(v)
    o['executed_funds'] = str(f)
    o['paid_fee'] = str(fee)
    if o.get('volume') is not None:
        o['remaining_volume'] = str(Decimal(o['volume']) - v)


def fill_price(o):
    fp = ((state.get('market') or {}).get('fill_price') or {}).get(o.get('market'))
    return Decimal(fp) if fp is not None else PRICE


def default_fill(o, mode):
    price = fill_price(o)
    if o['side'] == 'bid':
        total = Decimal(o['price'])            # 시장가 매수: price = 총액
        full_funds = (total * Decimal('0.98')).quantize(Decimal('0.00000001'))   # 2,940 / 3,000
    else:
        full_funds = (Decimal(o['volume']) * price).quantize(Decimal('0.00000001'))
    full_vol = full_funds / price
    if mode == 'done':
        apply_fill(o, str(full_vol), str(full_funds))
        o['state'] = 'done'
    elif mode == 'partial_wait':
        apply_fill(o, str(full_vol / 2), str(full_funds / 2))
        o['state'] = 'wait'
    else:
        o['state'] = 'wait'


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        sys.stderr.write('[double] ' + (fmt % args) + '\n')

    def reply(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get('Content-Length') or 0)
        raw = self.rfile.read(n) if n else b''
        return json.loads(raw) if raw else {}

    def find(self, q):
        if 'uuid' in q:
            return state['orders'].get(q['uuid'][0])
        if 'identifier' in q:
            for o in state['orders'].values():
                if o.get('identifier') == q['identifier'][0]:
                    return o
        return None

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == '/v1/market/all':
            # 잠금 밖 — 느린 응답이 다른 요청을 막지 않게
            mode = (state.get('health') or 'ok')
            if mode == 'fail':
                return self.reply(503, {'error': {'name': 'server_maintenance', 'message': '시험 대역 — 점검'}})
            if mode == 'slow':
                time.sleep(3.5)
            markets = (state.get('market') or {}).get('markets')
            return self.reply(200, markets or [{'market': 'KRW-DOGE', 'korean_name': '도지코인', 'english_name': 'Dogecoin'}])
        with lock:
            if u.path == '/v1/order':
                o = self.find(q)
                return self.reply(404, {'error': {'name': 'order_not_found'}}) if o is None else self.reply(200, view(o))
            if u.path == '/v1/accounts':
                return self.reply(200, [
                    {'currency': 'KRW', 'balance': state['krw'], 'locked': '0', 'avg_buy_price': '0', 'unit_currency': 'KRW'},
                    {'currency': (state.get('market') or {}).get('coin_currency', 'DOGE'), 'balance': state['coin'], 'locked': '0',
                     'avg_buy_price': '300', 'unit_currency': 'KRW'}])
            if u.path == '/v1/market/all':
                return self.reply(200, [{'market': 'KRW-DOGE', 'korean_name': '도지코인', 'english_name': 'Dogecoin'}])
            if u.path == '/v1/orders/chance':
                return self.reply(200, {'bid_fee': '0.0005', 'ask_fee': '0.0005'})
            if u.path == '/v1/orders':
                return self.reply(200, [view(o) for o in state['orders'].values() if o['state'] == 'wait'])
            if u.path in ('/v1/ticker', '/v1/orderbook') or u.path.startswith('/v1/candles/'):
                return self.market(u.path, q)
            if u.path == '/_ctl/orders':
                return self.reply(200, {'orders': list(state['orders'].values()), 'creates': state['creates'],
                                        'krw': state['krw'], 'coin': state['coin']})
        self.reply(404, {'error': {'name': 'not_found', 'path': u.path}})

    def market(self, path, q):
        m = state.get('market')
        if not m or m.get('fail'):
            return self.reply(503, {'error': {'name': 'market_unavailable', 'message': '시험 대역 — 시세 응답 꺼짐'}})
        now_ms = int(time.time() * 1000)
        if path == '/v1/ticker':
            out = []
            for c in q.get('markets', [''])[0].split(','):
                t = (m.get('ticker') or {}).get(c)
                if t:
                    out.append({'market': c, 'trade_price': float(t['price']),
                                'trade_timestamp': now_ms - int(t.get('trade_age_sec', 0)) * 1000, 'timestamp': now_ms})
            return self.reply(200, out)
        if path == '/v1/orderbook':
            out = []
            for c in q.get('markets', [''])[0].split(','):
                b = (m.get('orderbook') or {}).get(c)
                if b:
                    bid = Decimal(b['bid'])
                    size = float(b.get('size', '100'))     # 매수 잔량 — 보유량보다 얕은 호가를 만들 수 있다(리뷰 8회차 후속)
                    out.append({'market': c, 'timestamp': now_ms, 'total_ask_size': 100, 'total_bid_size': size,
                                'orderbook_units': [{'ask_price': float(bid + 1), 'bid_price': float(bid), 'ask_size': 100, 'bid_size': size}]})
            return self.reply(200, out)
        # /v1/candles/minutes/60 — H1 만. 업비트처럼 진행 중 봉 포함, to 이전 봉을 최신순으로 count 개.
        coin = q.get('market', [''])[0]
        cfg = (m.get('candles') or {}).get(coin)
        if not cfg or path != '/v1/candles/minutes/60':
            return self.reply(404, {'error': {'name': 404, 'message': 'Code not found'}})
        count = int(q.get('count', ['200'])[0])
        now = int(time.time())
        to = q.get('to', [None])[0]
        to_s = calendar.timegm(time.strptime(to, '%Y-%m-%dT%H:%M:%S')) if to else now + 3600   # UTC
        cur = now - now % 3600
        close = Decimal(cfg['close'])
        slope = Decimal(cfg.get('slope', '0'))
        anchor = int(cfg.get('anchor', cur))
        spread = Decimal(cfg.get('spread', '0'))
        absent = set(int(x) for x in cfg.get('absent', []))
        out = []
        while len(out) < count and cur > now - 600 * 3600:
            if cur < to_s and cur not in absent:
                c = close + slope * (cur - anchor) // 3600
                o_ = c - slope
                out.append({'market': coin, 'candle_date_time_utc': time.strftime('%Y-%m-%dT%H:%M:%S', time.gmtime(cur)),
                            'opening_price': float(o_), 'high_price': float(max(c, o_) + spread), 'low_price': float(min(c, o_) - spread),
                            'trade_price': float(c), 'candle_acc_trade_volume': 10})
            cur -= 3600
        return self.reply(200, out)

    def do_POST(self):
        u = urlparse(self.path)
        b = self.body()
        if u.path == '/v1/orders':
            with lock:
                side = b.get('side')
                plan = state['plan'].get(side) or {}
                now = time.strftime('%Y-%m-%dT%H:%M:%S+09:00')
                rej = plan.get('reject')
                if rej:
                    # 주문을 만들지 않고 거절한다 — 업비트 오류 형식
                    state.setdefault('rejected', []).append({'identifier': b.get('identifier'), 'side': side, 'at': now,
                                                             'name': rej.get('name')})
                    if plan.get('once'):
                        state['plan'].pop(side, None)
                    save()
                    return self.reply(rej.get('status', 400), {'error': {'name': rej.get('name'), 'message': rej.get('name')}})
                o = {'uuid': str(uuidlib.uuid4()), 'side': side, 'ord_type': b.get('ord_type'),
                     'market': b.get('market'), 'identifier': b.get('identifier'), 'created_at': now,
                     'price': b.get('price'), 'volume': b.get('volume'), 'state': 'wait'}
                default_fill(o, plan.get('fill', 'done'))
                state['orders'][o['uuid']] = o
                state['creates'].append({'uuid': o['uuid'], 'identifier': o['identifier'], 'side': side, 'at': now})
                save()
                hold, drop = plan.get('hold_ms', 0), plan.get('drop', False)
                if plan.get('once'):
                    state['plan'].pop(side, None)
                    save()
            if hold:
                time.sleep(hold / 1000.0)
            if drop:
                self.close_connection = True
                self.connection.close()
                return
            err = plan.get('error_after_create')
            if err:
                # 주문은 만들었는데 오류로 응답한다 — 거절로 단정하면 안 되는 경우
                return self.reply(err.get('status', 400), {'error': {'name': err.get('name'), 'message': err.get('name')}})
            return self.reply(201, view(o))
        with lock:
            if u.path == '/_ctl/plan':
                state['plan'].update(b)
                save()
                return self.reply(200, state['plan'])
            if u.path == '/_ctl/fill':
                o = state['orders'].get(b['uuid'])
                if o is None:
                    return self.reply(404, {})
                if 'volume' in b:
                    apply_fill(o, b['volume'], b['funds'])
                o['state'] = b.get('state', o['state'])
                save()
                return self.reply(200, o)
            if u.path == '/_ctl/health':
                state['health'] = b.get('mode', 'ok')
                save()
                return self.reply(200, {'health': state['health']})
            if u.path == '/_ctl/market':
                state['market'] = b
                save()
                return self.reply(200, b)
            if u.path == '/_ctl/reset':
                state.clear()
                state.update({'orders': {}, 'plan': {}, 'krw': '1000000', 'coin': '0', 'creates': []})
                save()
                return self.reply(200, {})
        self.reply(404, {})

    def do_DELETE(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        with lock:
            if u.path == '/v1/order':
                o = self.find(q)
                if o is None:
                    return self.reply(404, {'error': {'name': 'order_not_found'}})
                if o['state'] != 'wait':
                    return self.reply(400, {'error': {'name': 'invalid_state', 'message': o['state']}})
                snapshot = view(o)          # 업비트는 취소 접수 시점의 주문을 돌려준다
                o['state'] = 'cancel'
                save()
                return self.reply(200, snapshot)
        self.reply(404, {})


if __name__ == '__main__':
    print(f'exchange double on :{PORT}, state={STATE_FILE}', flush=True)
    ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
