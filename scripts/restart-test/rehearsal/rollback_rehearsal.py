"""복구 리허설 (2026-10-16) — 후보 배포 실패를 가정하고, 진입 차단을 먼저 건 상태에서 V80(수정하지 않은 main jar)으로 되돌린다.

보는 것: ① 되돌리기 전 미해결 행 점검 ② 기동 전에 건 진입 차단으로 V80 기동 직후부터 신규 주문 0 ③ 같은 V80 실행에서 기존 보유의 청산이 그대로 동작
④ 보유가 닫혀 V80 DYNAMIC 이 스캔으로 돌아간 뒤에도 신규 주문 0. V80 은 주소가 api.upbit.com 고정이라 시험 JVM 만 그 이름을 127.0.0.1 로 풀고(TLS 중계 →
거래소 대역) 시험 인증서를 신뢰한다. 다른 이름은 풀리지 않는다(외부 접속 없음).
"""
import os, sys, json, time, subprocess, urllib.request
os.environ.setdefault('RT_APP_PORT', '18091')
os.environ.setdefault('RT_OTHER_PORT', '18092')
sys.path.insert(0, r'D:\tmp\wt-supply\scripts\restart-test')
import rt  # noqa: E402

MAIN_JAR = r'D:\tmp\wt-main\web-api\build\libs\web-api-0.1.0.jar'
TLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tls')
OLD_PORT = 18093
UNREG = {'trading.capital-pool.stage': None, 'trading.capital-pool.total-cap-krw': None,
         'trading.capital-pool.per-position-cap-krw': None, 'trading.capital-pool.max-concurrent-positions': None}
results = []


def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    rt.log(f'  {"PASS" if ok else "FAIL"} {name} {detail}')


one = rt.one


def launch_old(label, via_double):
    env = dict(os.environ, DB_URL='jdbc:postgresql://localhost:15432/crypto_auto_trader', DB_USERNAME='trader',
               DB_PASSWORD='rtpass', REDIS_HOST='localhost', REDIS_PORT='16379',
               UPBIT_ACCESS_KEY='rt-access-key', UPBIT_SECRET_KEY='rt-secret-key-0123456789abcdef0123456789abcdef',
               API_AUTH_TOKEN=rt.TOKEN, TELEGRAM_ENABLED='false', TELEGRAM_BOT_TOKEN='', DISCORD_BOT_TOKEN='')
    props = ['-Xmx768m', '-Dfile.encoding=UTF-8', f'-Dserver.port={OLD_PORT}', '-Dtelegram.enabled=false']
    if via_double:
        props += [f'-Djdk.net.hosts.file={os.path.join(TLS, "hosts.txt")}',
                  f'-Djavax.net.ssl.trustStore={os.path.join(TLS, "trust.p12")}',
                  '-Djavax.net.ssl.trustStorePassword=changeit', '-Djavax.net.ssl.trustStoreType=PKCS12']
    else:
        props += ['-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=9', '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=9',
                  '-Dhttp.nonProxyHosts=localhost|127.0.0.1']
    logpath = os.path.join(rt.WORK, f'rollback-old-{label}.log')
    proc = subprocess.Popen([rt.JAVA] + props + ['-jar', MAIN_JAR], stdout=open(logpath, 'w', encoding='utf-8'),
                            stderr=subprocess.STDOUT, env=env)
    t0 = time.time()
    while time.time() - t0 < 240:
        if proc.poll() is not None:
            return proc, logpath, False, t0
        try:
            rt.api('GET', '/actuator/health', port=OLD_PORT)
            return proc, logpath, True, t0
        except Exception:
            time.sleep(1)
    return proc, logpath, False, t0


def kill(proc):
    if proc and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        time.sleep(3)


def double_buy(krw):
    req = urllib.request.Request(f'http://127.0.0.1:{rt.DOUBLE_PORT}/v1/orders', method='POST',
                                 data=json.dumps({'side': 'bid', 'market': 'KRW-DOGE', 'price': krw, 'ord_type': 'price',
                                                  'identifier': 'legacy-v80-buy'}).encode(), headers={'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())


def asks():
    return [c for c in rt.exchange_orders()['creates'] if c['side'] == 'ask']


def bids():
    return [c for c in rt.exchange_orders()['creates'] if c['side'] == 'bid']


rt.log('복구 리허설 — 후보 실패 가정 → 진입 차단 선적용 → V80(main jar 그대로)')
rt.fresh_db()
rt.start_double(fresh=True)
rt.ctl('POST', '/_ctl/market', {})     # 시세 응답 끔(후보 구간)

old, log, ok, _ = launch_old('migrate', via_double=False)
check('R0 V80 스키마', ok and one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '80')
kill(old)

# 운영 SUI 행을 본뜬 V80 시절 상태 — 세션 invest_ratio 0.8·가용 2,101.09·보유 1(시장가 매수 1건 FILLED, uuid 있음)
rt.psql("UPDATE risk_config SET max_daily_loss_pct = 3.0, max_portfolio_drawdown_pct = 15.0")
live_id = int(one("INSERT INTO live_trading_session (strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, "
                  "status, session_type, started_at) VALUES ('COMPOSITE_MOMENTUM_ICHIMOKU_V2', 'KRW-DOGE', 'M5', 10000, 10000, 10000, 'RUNNING', 'REAL', now()) RETURNING id"))
dyn_id = int(one("INSERT INTO dynamic_session (strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, scan_state, "
                 "trading_mode, invest_ratio, stop_loss_pct, max_candidate_size, target_watch_size, min_atr_pct, max_spread_pct, "
                 "watchlist_refresh_min, started_at, current_coin_pair) VALUES ('COMPOSITE_MOMENTUM_ICHIMOKU_V2', 'H1', 10000, 2101.09, 10000, "
                 "'RUNNING', 'POSITION_MONITORING', 'REAL', 0.8, 5, 30, 10, 0.5, 0.1, 60, now() - interval '1 day', 'KRW-DOGE') RETURNING id"))
b = double_buy('8404.36')
o = [x for x in rt.exchange_orders()['orders'] if x['uuid'] == b['uuid']][0]
vol = o.get('executed_volume') or str(round(8404.36 / 300 * (1 - 0.0005), 8))
pos_id = int(one("INSERT INTO position (coin_pair, side, entry_price, avg_price, size, invested_krw, status, session_kind, session_id, opened_at, "
                 f"stop_loss_price, take_profit_price) VALUES ('KRW-DOGE', 'BUY', 300, 300, {vol}, 8404.36, 'OPEN', 'DYNAMIC', {dyn_id}, "
                 "now() - interval '3 hours', 285, 345) RETURNING id"))
buy_order = int(one("INSERT INTO \"order\" (position_id, coin_pair, side, order_type, price, quantity, state, exchange_order_id, filled_quantity, "
                    f"executed_funds, session_id, session_kind, created_at, filled_at) VALUES ({pos_id}, 'KRW-DOGE', 'BUY', 'MARKET', 300, 8404.36, "
                    f"'FILLED', '{b['uuid']}', {vol}, 8404.36, {dyn_id}, 'DYNAMIC', now() - interval '3 hours', now() - interval '3 hours') RETURNING id"))
rt.psql(f"UPDATE dynamic_session SET current_position_id = {pos_id} WHERE id = {dyn_id}")
rt.log(f'  V80 상태: LIVE#{live_id}(보유 0) · DYNAMIC#{dyn_id} 보유 pos#{pos_id} {vol}개 · 매수 #{buy_order}')

# 후보 배포(실패 가정) — 한 회차 돌고 멈춘다. 자동 재기동하지 않는다.
rt.start_app(label='rollback-candidate', props=UNREG)
time.sleep(75)
rt.kill_app()
rt.log('  후보 중지(실패 가정) — 자동 재기동하지 않는다')

# ① 되돌리기 전 점검 — 후보가 만든 미해결 행이 있으면 V80 으로 되돌리지 않는다
unresolved = rt.psql(
    "SELECT 'order', id::text, state FROM \"order\" WHERE state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED') "
    "OR fill_accounting_pending OR (reserved_krw IS NOT NULL AND state NOT IN ('FILLED','FAILED','CANCELLED')) "
    "UNION ALL SELECT 'cleanup', position_id::text, reason FROM position_cleanup WHERE done_at IS NULL "
    "UNION ALL SELECT 'closing', id::text, status FROM position WHERE status = 'CLOSING'")
check('R① 되돌리기 전 점검: 후보가 남긴 미해결 주문·예약·정리 대기·청산 중 포지션 0', not unresolved, str(unresolved))
cand_orders = one(f"SELECT count(*) FROM \"order\" WHERE id > {buy_order}")
check('R① 후보 구간 신규 주문 0(자본 풀 미등록)', cand_orders == '0', cand_orders)

# ② 기동 전에 진입 차단을 건다
rt.psql(f"UPDATE live_trading_session SET status = 'STOPPED', stopped_at = now() WHERE id = {live_id}")
rt.psql(f"UPDATE dynamic_session SET invest_ratio = 0 WHERE id = {dyn_id}")
rt.ctl('POST', '/_ctl/market', {'ticker': {'KRW-DOGE': {'price': '300', 'trade_age_sec': 1}}, 'orderbook': {'KRW-DOGE': {'bid': '299'}},
                                'candles': {'KRW-DOGE': {'close': '310'}, 'KRW-BTC': {'close': '100000000'}}})
bids0, asks0 = len(bids()), len(asks())
max_order = int(one('SELECT coalesce(max(id), 0) FROM "order"'))

old, log, ok, t_start = launch_old('rollback', via_double=True)
check('R② V80(main jar 그대로) 기동 — 진입 차단을 먼저 건 상태', ok)
samples = []
for _ in range(15):    # 기동 직후부터 150초
    samples.append((round(time.time() - t_start), len(bids()) - bids0,
                    int(one(f"SELECT count(*) FROM \"order\" WHERE id > {max_order} AND side = 'BUY'"))))
    time.sleep(10)
check('R② 기동 직후부터 150초 동안 신규 매수 0(거래소 생성·주문 행)', all(s[1] == 0 and s[2] == 0 for s in samples), str(samples[-3:]))

# ③ 같은 V80 실행에서 기존 보유의 청산이 동작한다 — 손절가를 현재가 위로
rt.psql(f"UPDATE position SET stop_loss_price = 320 WHERE id = {pos_id}")
t1 = time.time()
try:
    rt.wait_until(lambda: one(f'SELECT status FROM position WHERE id = {pos_id}') == 'CLOSED', 240, 3, 'V80 기존 보유 청산')
except Exception as e:
    rt.log(f'  대기 초과: {e!r}')
st = rt.psql(f"SELECT status, coalesce(exit_reason,'') FROM position WHERE id = {pos_id}")[0]
sells = rt.psql(f"SELECT id, state FROM \"order\" WHERE position_id = {pos_id} AND side = 'SELL'")
check('R③ V80 이 기존 보유를 청산 — 매도 1건·체결·CLOSED·거래소 매도 생성 1', st[0] == 'CLOSED' and len(sells) == 1 and sells[0][1] == 'FILLED'
      and len(asks()) - asks0 == 1, f'{st} {sells} ({time.time() - t1:.0f}초)')
avail = one(f'SELECT available_krw::text FROM dynamic_session WHERE id = {dyn_id}')
check('R③ 매도 대금 한 번 반영(가용 2,101.09 + 약 8,400)', avail is not None and 10400 < float(avail) < 10520, avail)

# ④ 보유가 닫힌 뒤 — V80 DYNAMIC 이 스캔으로 돌아가도 invest_ratio 0 이라 신규 매수 0
time.sleep(150)
scan = one(f'SELECT scan_state FROM dynamic_session WHERE id = {dyn_id}')
newbuys = one(f"SELECT count(*) FROM \"order\" WHERE id > {max_order} AND side = 'BUY'")
check('R④ 보유 청산 뒤에도 신규 매수 0(스캔 상태에서 150초)', newbuys == '0' and len(bids()) - bids0 == 0, f'scan_state={scan} buys={newbuys}')
text = open(log, encoding='utf-8', errors='replace').read()
check('R④ (참고) V80 DYNAMIC 의 매수 시도 흔적', True, f'"가용 KRW 부족" {text.count("가용 KRW 부족")}회 · 스캔 시작 {text.count("SCANNING 시작")}회')
kill(old)
rt.ctl('POST', '/_ctl/market', {})

passed = sum(1 for r in results if r[1])
rt.log(f'복구 리허설 {passed}/{len(results)} 통과')
for n, ok_, d in results:
    rt.log(f'{"PASS" if ok_ else "FAIL"} {n} {d}')
