"""격리 해제 후보 확인 (2026-10-19) — 운영 버전 3b781d8(V93)에서 후보(V94)로 올리고, 실제 프로세스·PostgreSQL·거래소 대역에서 본다.

상황: V87 이전(반영 표시가 없던 시절)에 9.8 개를 사서 3.0 개를 판 실자금 LIVE 포지션 — 그 매도는 이미 반영돼 수량 6.8, 반영 표시만 없다(격리).
① 3b781d8 jar 로 V93 ② 후보 기동 → V94 업그레이드·경계(V87 설치 시각) 확정·기동 점검이 격리 포지션을 알림 ③ 정지 — 매도가 거래소로 나가지 않고, 옛 매도를
자동 반영하지 않는다(수량·잔액 불변) ④ 대조 수량이 틀린 해제 요청 거절(기록·표시 없음) ⑤ 올바른 해제 — 기록·반영 표시, 잔액·수량 불변, 커밋 뒤 알림 로그
⑥ 재기동 — 기동 점검에서 빠짐 ⑦ 정지가 남긴 정리 작업이 남은 6.8 을 한 번 팔고, 대금이 한 번 들어간다.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

os.environ.setdefault('RT_APP_PORT', '18091')
os.environ.setdefault('RT_OTHER_PORT', '18092')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rt  # noqa: E402

OLD_JAR = os.environ.get('RT_OLD_JAR', r'D:\Claude Code\evidence\crypto-auto-trader\prod-jars\3b781d8-web-api-0.1.0.jar')
OLD_PORT = 18093
results = []


def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    rt.log(f'  {"PASS" if ok else "FAIL"} {name} {detail}')


def one(sql):
    return rt.one(sql)


def post_json(path, body):
    req = urllib.request.Request(f'http://127.0.0.1:{rt.APP_PORT}{path}', method='POST', data=json.dumps(body).encode(),
                                 headers={'Authorization': f'Bearer {rt.TOKEN}', 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def launch_old(label):
    env = dict(os.environ, DB_URL='jdbc:postgresql://localhost:15432/crypto_auto_trader', DB_USERNAME='trader',
               DB_PASSWORD='rtpass', REDIS_HOST='localhost', REDIS_PORT='16379',
               UPBIT_ACCESS_KEY='rt-access-key', UPBIT_SECRET_KEY='rt-secret-key-0123456789abcdef0123456789abcdef',
               API_AUTH_TOKEN=rt.TOKEN, TELEGRAM_ENABLED='false', TELEGRAM_BOT_TOKEN='', DISCORD_BOT_TOKEN='')
    logf = open(os.path.join(rt.WORK, f'v94-old-{label}.log'), 'w', encoding='utf-8')
    props = ['-Xmx768m', '-Dfile.encoding=UTF-8', f'-Dserver.port={OLD_PORT}',
             '-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=9', '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=9',
             '-Dhttp.nonProxyHosts=localhost|127.0.0.1', '-Dtelegram.enabled=false']
    proc = subprocess.Popen([rt.JAVA] + props + ['-jar', OLD_JAR], stdout=logf, stderr=subprocess.STDOUT, env=env)
    t0 = time.time()
    while time.time() - t0 < 240:
        if proc.poll() is not None:
            return proc, False
        try:
            rt.api('GET', '/actuator/health', port=OLD_PORT)
            return proc, True
        except Exception:
            time.sleep(1)
    return proc, False


def kill(proc):
    if proc and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        time.sleep(3)


def double_buy():
    req = urllib.request.Request(f'http://127.0.0.1:{rt.DOUBLE_PORT}/v1/orders', method='POST',
                                 data=json.dumps({'side': 'bid', 'market': 'KRW-DOGE', 'price': '3000', 'ord_type': 'price',
                                                  'identifier': 'legacy-v80-buy'}).encode(),
                                 headers={'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())


rt.log('격리 해제 후보 확인 — 3b781d8(V93) → 후보(V94)')
if not os.path.exists(OLD_JAR):
    raise SystemExit(f'운영 버전 jar 가 없다 — RT_OLD_JAR 로 준다: {OLD_JAR}')
rt.fresh_db()
rt.start_double(fresh=True)

# ① 운영 버전으로 V93
old, ok = launch_old('v93-migrate')
v = one('SELECT max(version::int) FROM flyway_schema_history WHERE success')
check('①  3b781d8 jar 기동·Flyway V93', ok and v == '93', v)
kill(old)

# 격리 상황 — V87 이전 매수 9.8·매도 3.0(반영됨, 표시 없음) → 수량 6.8. 대역 계좌에는 9.8 이 있다(DB 에서 반영된 3.0 은 그 시절 다른 경로로 판 셈).
buy = double_buy()
live = int(one("INSERT INTO live_trading_session (strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, "
               "status, session_type, started_at) VALUES ('HARNESS_SIGNAL', 'KRW-DOGE', 'H1', 10000, 7958.53, 10000, 'RUNNING', 'REAL', "
               "now() - interval '30 days') RETURNING id"))
pos = int(one("INSERT INTO position (coin_pair, side, entry_price, avg_price, size, invested_krw, status, session_kind, session_id, opened_at, "
              f"stop_loss_price, entry_allocation_krw) VALUES ('KRW-DOGE', 'BUY', 300, 300, 6.8, 2941.47, 'OPEN', 'LIVE', {live}, "
              "now() - interval '30 days', 100, 3000) RETURNING id"))
one("INSERT INTO \"order\" (position_id, coin_pair, side, order_type, price, quantity, state, exchange_order_id, filled_quantity, executed_funds, "
    f"session_id, session_kind, created_at, filled_at) VALUES ({pos}, 'KRW-DOGE', 'BUY', 'MARKET', 300, 3000, 'FILLED', '{buy['uuid']}', 9.8, 2940, "
    f"{live}, 'LIVE', now() - interval '30 days', now() - interval '30 days') RETURNING id")
legacy = int(one("INSERT INTO \"order\" (position_id, coin_pair, side, order_type, price, quantity, state, exchange_order_id, filled_quantity, "
                 f"executed_funds, session_id, session_kind, created_at, filled_at) VALUES ({pos}, 'KRW-DOGE', 'SELL', 'MARKET', 300, 3.0, 'FILLED', "
                 f"'legacy-sell-uuid', 3.0, 900, {live}, 'LIVE', now() - interval '29 days', now() - interval '29 days') RETURNING id"))
rt.log(f'  LIVE#{live} pos#{pos} 수량 6.8 · 옛 매도 #{legacy}(3.0, 반영 표시 없음)')

# ② 후보 — V94
rt.start_app(label='v94-upgrade')
lg = rt.app_log()
check('②  후보 기동·Flyway V93→V94(이 기동에서 V94 한 건)',
      one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '94' and 'to version "94' in lg)
check('②  기동 점검이 격리 포지션을 알림', '매도가 막힌 실자금 포지션 1개' in lg)
rt.api('POST', '/api/v1/restart-harness/signal?action=HOLD', timeout=10)

# ③ 정지 — 매도가 나가지 않고, 옛 매도를 자동 반영하지 않는다
creates0 = len(rt.exchange_orders()['creates'])
rt.api('POST', f'/api/v1/trading/sessions/{live}/stop', timeout=30)
time.sleep(40)   # 정리 작업(10초)·청산 확정(5초) 여러 회차
lg = rt.app_log()
check('③  정지 — 거래소 매도 생성 0 · 옛 매도 반영 표시 없음 · 수량·잔액 불변',
      len(rt.exchange_orders()['creates']) == creates0
      and one(f'SELECT sell_applied_at IS NULL FROM "order" WHERE id = {legacy}') == 't'
      and float(one(f'SELECT size FROM position WHERE id = {pos}')) == 6.8
      and float(one(f'SELECT available_krw FROM live_trading_session WHERE id = {live}')) == 7958.53,
      f'creates={len(rt.exchange_orders()["creates"]) - creates0} size={one(f"SELECT size FROM position WHERE id = {pos}")} '
      f'krw={one(f"SELECT available_krw FROM live_trading_session WHERE id = {live}")}')
check('③  경계 확정 로그(V87 설치 시각)', '[판별 불가 매도] 경계 = V87 설치 시각' in lg)

# ④ 대조 수량이 틀린 해제 — 거절
code, body = post_json(f'/api/v1/trading/orders/{legacy}/quarantine-release',
                       {'releasedBy': '시험 운영자', 'reason': '업비트 주문 내역 대조', 'evidence': 'legacy-sell-uuid 체결 3.0', 'confirmedPositionSize': 6.7})
check('④  대조 수량 6.7(지금 6.8) — 거절·기록 없음·표시 없음',
      code >= 400 and one(f'SELECT count(*) FROM sell_quarantine_release WHERE order_id = {legacy}') == '0'
      and one(f'SELECT sell_applied_at IS NULL FROM "order" WHERE id = {legacy}') == 't', f'HTTP {code} {body[:160]}')

# ⑤ 올바른 해제
code, body = post_json(f'/api/v1/trading/orders/{legacy}/quarantine-release',
                       {'releasedBy': '시험 운영자', 'reason': '업비트 주문 내역 대조 — 3.0 매도가 수량에 반영돼 있다',
                        'evidence': 'legacy-sell-uuid 체결 3.0', 'confirmedPositionSize': 6.8})
time.sleep(2)
lg = rt.app_log()
krw_after_release = one(f'SELECT available_krw FROM live_trading_session WHERE id = {live}')
check('⑤  해제 — 기록·반영 표시, 잔액 불변, 커밋 뒤 알림 로그',
      code == 200 and one(f'SELECT released_by FROM sell_quarantine_release WHERE order_id = {legacy}') == '시험 운영자'
      and one(f'SELECT sell_applied_at IS NOT NULL FROM "order" WHERE id = {legacy}') == 't'
      and float(krw_after_release) == 7958.53 and '매도 격리 해제 — 주문 #' in lg, f'HTTP {code} krw={krw_after_release}')
rt.kill_app()

# ⑥ 재기동 — 기동 점검에서 빠짐. ⑦ 정리 작업이 남은 6.8 을 한 번 판다.
rt.start_app(label='v94-restart')
rt.api('POST', '/api/v1/restart-harness/signal?action=HOLD', timeout=10)
lg = rt.app_log()
check('⑥  재기동 — 기동 점검 "격리 포지션 없음"', '반영 표시 없이 체결된 매도가 있는 열린 실자금 포지션 없음' in lg)
rt.wait_until(lambda: one(f'SELECT status FROM position WHERE id = {pos}') == 'CLOSED', 120, 3, '정리 매도·확정')
sells = rt.psql(f"SELECT id, state, coalesce(applied_executed_funds::text,''), coalesce(applied_paid_fee::text,''), "
                f"sell_applied_at IS NOT NULL FROM \"order\" WHERE position_id = {pos} AND side = 'SELL' AND id <> {legacy}")
krw_final = one(f'SELECT available_krw FROM live_trading_session WHERE id = {live}')
expected = None
if len(sells) == 1 and sells[0][2] and sells[0][3]:
    expected = round(7958.53 + float(sells[0][2]) - float(sells[0][3]), 2)
check('⑦  정리 매도 한 번(거래소 생성 1)·체결·반영 → CLOSED, 대금 한 번(잔액 = 7,958.53 + 반영 대금 − 반영 수수료)',
      one(f'SELECT status FROM position WHERE id = {pos}') == 'CLOSED' and len(sells) == 1 and sells[0][1] == 'FILLED' and sells[0][4] == 't'
      and len(rt.exchange_orders()['creates']) == creates0 + 1 and expected is not None and abs(float(krw_final) - expected) < 0.01,
      f'sells={sells} krw={krw_final} expected={expected}')
rt.kill_app()
rt.stop_double()

passed = sum(1 for r in results if r[1])
rt.log(f'격리 해제 후보 확인 {passed}/{len(results)} 통과')
for n, ok_, dd in results:
    rt.log(f'{"PASS" if ok_ else "FAIL"} {n} {dd}')
