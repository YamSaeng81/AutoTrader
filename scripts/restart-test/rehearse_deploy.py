"""배포 리허설 (2026-10-16) — 운영(V80, main)에서 배포 후보(fc0c039)로 올리고, 다시 V80 코드로 되돌린다.

운영 상태를 본뜬다: 실자금 LIVE 세션(보유 0) · 실자금 DYNAMIC 세션(V80 시절에 체결된 보유 1 — SUI 대신 대역의 DOGE) ·
자본 풀 미등록 · risk_config 3.0/15.0. 보는 것: 마이그레이션 V81~V92, 기존 포지션·주문의 추적·청산, 신규 진입 중단, 되돌림.
"""
import os, sys, json, time, subprocess, urllib.request, urllib.error
os.environ.setdefault('RT_APP_PORT', '18091')
os.environ.setdefault('RT_OTHER_PORT', '18092')
sys.path.insert(0, r'D:\tmp\wt-supply\scripts\restart-test')
import rt  # noqa: E402

MAIN_JAR = r'D:\tmp\wt-main\web-api\build\libs\web-api-0.1.0.jar'
OLD_PORT = 18093
UNREG = {'trading.capital-pool.stage': None, 'trading.capital-pool.total-cap-krw': None,
         'trading.capital-pool.per-position-cap-krw': None, 'trading.capital-pool.max-concurrent-positions': None}
results = []


def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    rt.log(f'  {"PASS" if ok else "FAIL"} {name} {detail}')


def one(sql):
    return rt.one(sql)


def launch_old(label):
    env = dict(os.environ, DB_URL='jdbc:postgresql://localhost:15432/crypto_auto_trader', DB_USERNAME='trader',
               DB_PASSWORD='rtpass', REDIS_HOST='localhost', REDIS_PORT='16379',
               UPBIT_ACCESS_KEY='rt-access-key', UPBIT_SECRET_KEY='rt-secret-key-0123456789abcdef0123456789abcdef',
               API_AUTH_TOKEN=rt.TOKEN, TELEGRAM_ENABLED='false', TELEGRAM_BOT_TOKEN='', DISCORD_BOT_TOKEN='')
    logpath = os.path.join(rt.WORK, f'rehearsal-old-{label}.log')
    logf = open(logpath, 'w', encoding='utf-8')
    props = ['-Xmx768m', '-Dfile.encoding=UTF-8', f'-Dserver.port={OLD_PORT}',
             '-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=9', '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=9',
             '-Dhttp.nonProxyHosts=localhost|127.0.0.1', '-Dtelegram.enabled=false']
    proc = subprocess.Popen([rt.JAVA] + props + ['-jar', MAIN_JAR], stdout=logf, stderr=subprocess.STDOUT, env=env)
    t0 = time.time()
    while time.time() - t0 < 240:
        if proc.poll() is not None:
            rt.log(f'old app exited rc={proc.returncode}')
            return proc, logpath, False
        try:
            rt.api('GET', '/actuator/health', port=OLD_PORT)
            rt.log(f'old app ready ({time.time() - t0:.0f}s)')
            return proc, logpath, True
        except Exception:
            time.sleep(1)
    return proc, logpath, False


def kill(proc):
    if proc and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        time.sleep(3)


def double_buy():
    """대역 계좌에 DOGE 9.8 을 실제로 사 둔다 — V80 시절 체결된 매수와 같은 거래소 주문(uuid)."""
    req = urllib.request.Request(f'http://127.0.0.1:{rt.DOUBLE_PORT}/v1/orders', method='POST',
                                 data=json.dumps({'side': 'bid', 'market': 'KRW-DOGE', 'price': '2940', 'ord_type': 'price',
                                                  'identifier': 'legacy-v80-buy'}).encode(),
                                 headers={'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())


def seed_candles(coin, close, tf='H1'):
    rt.psql(f"DELETE FROM market_data_cache WHERE coin_pair = '{coin}' AND timeframe = '{tf}'")
    rt.psql("INSERT INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume) "
            f"SELECT date_trunc('hour', now()) - (g || ' hours')::interval, '{coin}', '{tf}', {close} + (519 - g) * 0.01, "
            f"{close} + (519 - g) * 0.01 + 0.5, {close} + (519 - g) * 0.01 - 0.5, {close} + (519 - g) * 0.01 - 5.19, 1000 FROM generate_series(0, 519) g")


rt.log('배포 리허설 — V80(main) → fc0c039 → V80')
rt.fresh_db()
rt.start_double(fresh=True)

# ① 운영과 같은 V80 스키마
old, oldlog, ok = launch_old('v80-migrate')
check('R① V80(main) 앱 기동·마이그레이션', ok and one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '80',
      one('SELECT max(version::int) FROM flyway_schema_history WHERE success'))
kill(old)

# ② V80 시절 상태 — 운영 조회 결과를 본뜬다
rt.psql("UPDATE risk_config SET max_daily_loss_pct = 3.0, max_portfolio_drawdown_pct = 15.0")
if one('SELECT count(*) FROM risk_config') == '0':
    rt.psql("INSERT INTO risk_config (max_daily_loss_pct, max_portfolio_drawdown_pct) VALUES (3.0, 15.0)")
live_id = int(one("INSERT INTO live_trading_session (strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, "
                  "status, session_type, started_at) VALUES ('HARNESS_SIGNAL', 'KRW-DOGE', 'H1', 10000, 10000, 10000, 'RUNNING', 'REAL', now()) RETURNING id"))
dyn_id = int(one("INSERT INTO dynamic_session (strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, scan_state, "
                 "trading_mode, invest_ratio, stop_loss_pct, max_candidate_size, target_watch_size, min_atr_pct, max_spread_pct, "
                 "watchlist_refresh_min, started_at, current_coin_pair) VALUES ('COMPOSITE_MOMENTUM_ICHIMOKU_V2', 'H1', 10000, 7058.53, 10000, "
                 "'RUNNING', 'POSITION_MONITORING', 'REAL', 0.3, 5, 30, 10, 0.5, 0.1, 60, now() - interval '1 day', 'KRW-DOGE') RETURNING id"))
buy = double_buy()
pos_id = int(one("INSERT INTO position (coin_pair, side, entry_price, avg_price, size, invested_krw, status, session_kind, session_id, "
                 f"opened_at, stop_loss_price) VALUES ('KRW-DOGE', 'BUY', 300, 300.15, 9.8, 2941.47, 'OPEN', 'DYNAMIC', {dyn_id}, "
                 "now() - interval '3 hours', 250) RETURNING id"))
buy_order = int(one("INSERT INTO \"order\" (position_id, coin_pair, side, order_type, price, quantity, state, exchange_order_id, filled_quantity, "
                    f"executed_funds, session_id, session_kind, created_at, filled_at) VALUES ({pos_id}, 'KRW-DOGE', 'BUY', 'MARKET', 300, 3000, "
                    f"'FILLED', '{buy['uuid']}', 9.8, 2940, {dyn_id}, 'DYNAMIC', now() - interval '3 hours', now() - interval '3 hours') RETURNING id"))
rt.psql(f"UPDATE dynamic_session SET current_position_id = {pos_id} WHERE id = {dyn_id}")
seed_candles('KRW-DOGE', 310)
seed_candles('KRW-BTC', 100000000)
rt.log(f'  V80 상태: LIVE#{live_id}(보유 0) · DYNAMIC#{dyn_id} 보유 pos#{pos_id}(매수 주문 #{buy_order}, uuid {buy["uuid"]})')

# ③ 배포 후보로 올린다 — 자본 풀 미등록(운영과 같음)
rt.start_app(label='rehearsal-candidate', props=UNREG)
log = rt.app_log()
check('R③ 후보 기동·마이그레이션 V92', one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '92')
alloc = one(f'SELECT entry_allocation_krw::text FROM position WHERE id = {pos_id}')
check('R③ V90 이 기존 보유의 진입 배정액을 채움(첫 시장가 매수 요청액 3,000)', alloc is not None and float(alloc) == 3000, alloc)
check('R③ 기존 매수 주문 상태 불변(FILLED)·불일치 기록 0',
      one(f'SELECT state FROM "order" WHERE id = {buy_order}') == 'FILLED'
      and one('SELECT count(*) FROM order_settlement_discrepancy') == '0',
      one(f'SELECT state FROM "order" WHERE id = {buy_order}'))

# ④ 신규 진입 중단 — 두 세션 모두
creates0 = len(rt.exchange_orders()['creates'])
for kind, sid in (('LIVE', live_id), ('DYNAMIC', dyn_id)):
    try:
        rt.api('POST', f'/api/v1/restart-harness/buy?kind={kind}&sessionId={sid}&krw=3000', timeout=20)
        r = 'ok'
    except Exception as e:
        r = repr(e)[:80]
    n = one(f"SELECT count(*) FROM \"order\" WHERE session_kind = '{kind}' AND session_id = {sid} AND side = 'BUY' AND id <> {buy_order}")
    check(f'R④ {kind}#{sid} 신규 매수 거절(자본 풀 미등록)·주문 행 0', r != 'ok' and n == '0', r)
rt.api('POST', '/api/v1/restart-harness/signal?action=BUY', timeout=10)
time.sleep(130)    # LIVE 평가(60초) 두 회차
check('R④ 엔진 평가의 BUY 신호도 진입 없음(거래소 생성 0)', len(rt.exchange_orders()['creates']) == creates0
      and one(f"SELECT count(*) FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = {live_id}") == '0')
rt.api('POST', '/api/v1/restart-harness/signal?action=HOLD', timeout=10)
log = rt.app_log()
check('R④ 거절 사유가 로그에 남음(공유 풀 미등록)', '공유 풀 한도가 사전 등록과 맞지 않는다' in log and 'POOL_NOT_REGISTERED' in log)

# ⑤ 기존 보유의 위험 관리·청산 — 손절 조건을 만든다(종가 ≈ 310 ≤ 손절가 320)
rt.psql(f'UPDATE position SET stop_loss_price = 320 WHERE id = {pos_id}')
t0 = time.time()
try:
    rt.wait_until(lambda: one(f'SELECT status FROM position WHERE id = {pos_id}') == 'CLOSED', 240, 3, '기존 보유 청산')
except Exception as e:
    rt.log(f'  대기 초과: {e!r}')
st = rt.psql(f"SELECT status, coalesce(exit_reason,''), size::text FROM position WHERE id = {pos_id}")[0]
sells = rt.psql(f"SELECT id, state, coalesce(sell_applied_at::text,'') FROM \"order\" WHERE position_id = {pos_id} AND side = 'SELL'")
avail = one(f'SELECT available_krw::text FROM dynamic_session WHERE id = {dyn_id}')
check('R⑤ 기존 보유를 엔진이 손절 — 매도 1건·체결·반영·CLOSED', st[0] == 'CLOSED' and len(sells) == 1 and sells[0][1] == 'FILLED' and sells[0][2] != '',
      f'{st} {sells} ({time.time() - t0:.0f}초)')
check('R⑤ 매도 대금 한 번 반영(잔액 7,058.53 → 약 9,997)', avail is not None and 9990 < float(avail) < 10010, avail)
rt.kill_app()

# ⑥ 되돌림 — V80 코드로 V92 스키마에서 기동
old, oldlog, ok = launch_old('rollback')
text = open(oldlog, encoding='utf-8', errors='replace').read()
check('R⑥ V80 앱이 V92 스키마에서 기동(Flyway 상위 버전 무시)', ok, '' if ok else text[-400:])
if ok:
    # 복구 뒤 진입 중단 유지 — LIVE 는 보유 0 이라 정지(매도 없음), DYNAMIC 은 invest_ratio 0(보유를 팔지 않는다)
    try:
        rt.api('POST', f'/api/v1/trading/sessions/{live_id}/stop', port=OLD_PORT, timeout=30)
        r = 'ok'
    except Exception as e:
        r = repr(e)[:120]
    check('R⑥ V80 앱으로 LIVE 정지 — 보유 0 이라 매도 없음', r == 'ok' and one(f'SELECT status FROM live_trading_session WHERE id = {live_id}') == 'STOPPED'
          and one(f"SELECT count(*) FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = {live_id}") == '0', r)
    rt.psql(f'UPDATE dynamic_session SET invest_ratio = 0 WHERE id = {dyn_id}')
    time.sleep(70)
    text = open(oldlog, encoding='utf-8', errors='replace').read()
    errs = [l for l in text.splitlines() if ' ERROR ' in l and ('column' in l.lower() or 'SQL' in l or 'null value' in l)]
    check('R⑥ V80 앱 운행 중 스키마 관련 오류 없음(1분)', not errs, '\n'.join(errs[:3]))
kill(old)

passed = sum(1 for r in results if r[1])
rt.log(f'리허설 {passed}/{len(results)} 통과')
for n, ok_, d in results:
    rt.log(f'{"PASS" if ok_ else "FAIL"} {n} {d}')
