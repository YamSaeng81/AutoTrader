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
  POST /_ctl/fill   {"uuid":..., "state":"done|cancel|wait", "volume":"..", "funds":".."}  체결 진행
  GET  /_ctl/orders  전체 주문
  POST /_ctl/reset
"""
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
    fee = (f * FEE_RATE).quantize(Decimal('0.00000001'))
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


def default_fill(o, mode):
    if o['side'] == 'bid':
        total = Decimal(o['price'])            # 시장가 매수: price = 총액
        full_funds = (total * Decimal('0.98')).quantize(Decimal('0.00000001'))   # 2,940 / 3,000
    else:
        full_funds = (Decimal(o['volume']) * PRICE).quantize(Decimal('0.00000001'))
    full_vol = full_funds / PRICE
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
        with lock:
            if u.path == '/v1/order':
                o = self.find(q)
                return self.reply(404, {'error': {'name': 'order_not_found'}}) if o is None else self.reply(200, view(o))
            if u.path == '/v1/accounts':
                return self.reply(200, [
                    {'currency': 'KRW', 'balance': state['krw'], 'locked': '0', 'avg_buy_price': '0', 'unit_currency': 'KRW'},
                    {'currency': 'DOGE', 'balance': state['coin'], 'locked': '0', 'avg_buy_price': '300', 'unit_currency': 'KRW'}])
            if u.path == '/v1/market/all':
                return self.reply(200, [{'market': 'KRW-DOGE', 'korean_name': '도지코인', 'english_name': 'Dogecoin'}])
            if u.path == '/v1/orders/chance':
                return self.reply(200, {'bid_fee': '0.0005', 'ask_fee': '0.0005'})
            if u.path == '/v1/orders':
                return self.reply(200, [view(o) for o in state['orders'].values() if o['state'] == 'wait'])
            if u.path == '/_ctl/orders':
                return self.reply(200, {'orders': list(state['orders'].values()), 'creates': state['creates'],
                                        'krw': state['krw'], 'coin': state['coin']})
        self.reply(404, {'error': {'name': 'not_found', 'path': u.path}})

    def do_POST(self):
        u = urlparse(self.path)
        b = self.body()
        if u.path == '/v1/orders':
            with lock:
                side = b.get('side')
                plan = state['plan'].get(side) or {}
                now = time.strftime('%Y-%m-%dT%H:%M:%S+09:00')
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
