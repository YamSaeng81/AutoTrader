"""배포 후보 확인 (2026-10-19) — 운영 버전 fc0c039(V92)에서 후보(V93)로 올리고, 실제 프로세스·PostgreSQL 에서 본다.

① fc0c039 jar 로 Flyway V92 ② 후보 기동 → Flyway V92→V93 업그레이드 ③ 점검 구멍이 있는 캐시(대역 거래소에도 없음) → LIVE 판정 불가 →
결측 재수집 조회 → 운영자 방식으로 점검 구간 등록 → 판정 재개 ④ 재기동 — 조회 전에는 다시 판정 불가, 조회 뒤 판정 재개 ⑤ 실제 상태 확인
응답으로 DOWN(503×3) → DEGRADED(느린 응답) → UP 과 LIVE 자동 재시작(등록 풀).
"""
import os, sys, time, subprocess
os.environ.setdefault('RT_APP_PORT', '18091')
os.environ.setdefault('RT_OTHER_PORT', '18092')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rt  # noqa: E402

# 운영 버전(fc0c039) jar — 작업 폴더 밖 증거 보관소에 보존한 것(sha256 은 그 폴더의 MANIFEST.md). RT_OLD_JAR 로 바꿀 수 있다.
OLD_JAR = os.environ.get('RT_OLD_JAR', r'D:\Claude Code\evidence\crypto-auto-trader\restart-test-2026-10-19-full-3b781d8\fc0c039-web-api-0.1.0.jar')
OLD_PORT = 18093
MARKET_PROPS = {'upbit.market-data.base-url': f'http://127.0.0.1:{rt.DOUBLE_PORT}/v1'}
COIN = 'KRW-DOGE'
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
    logpath = os.path.join(rt.WORK, f'v93-old-{label}.log')
    logf = open(logpath, 'w', encoding='utf-8')
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
            rt.log(f'fc0c039 앱 준비 ({time.time() - t0:.0f}s)')
            return proc, True
        except Exception:
            time.sleep(1)
    return proc, False


def kill(proc):
    if proc and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        time.sleep(3)


def wait_log(text, timeout, what):
    try:
        rt.wait_until(lambda: text in rt.app_log(), timeout, 3, what)
        return True
    except Exception as e:
        rt.log(f'  대기 초과: {e!r}')
        return False


def hour_start(epoch):
    return epoch - epoch % 3600


rt.log('배포 후보 확인 — fc0c039(V92) → 후보(V93): 점검 구간 조회·판정·재기동·거래소 복구')
if not os.path.exists(OLD_JAR):
    raise SystemExit(f'운영 버전 jar 가 없다 — 증거 보관소의 jar 를 두거나 fc0c039 에서 bootJar 를 만들어 RT_OLD_JAR 로 준다: {OLD_JAR}')
rt.fresh_db()
rt.start_double(fresh=True)

# ① 운영 버전으로 V92
old, ok = launch_old('v92-migrate')
v = one('SELECT max(version::int) FROM flyway_schema_history WHERE success')
check('①  fc0c039 jar 기동·Flyway V92', ok and v == '92', v)
kill(old)

# ② 후보 — V92 → V93
rt.start_app(label='v93-upgrade', props=MARKET_PROPS)
log = rt.app_log()
rows = rt.psql("SELECT version, success FROM flyway_schema_history WHERE version::int >= 92 ORDER BY installed_rank")
check('②  후보 기동·Flyway V92→V93 업그레이드(이 기동에서 V93 한 건만 적용)',
      one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '93' and rows == [['92', 't'], ['93', 't']]
      and 'Migrating schema "public" to version "93' in log, str(rows))
check('②  점검 구간 표 생성(빈 표)', one('SELECT count(*) FROM exchange_maintenance_window') == '0')
rt.kill_app()

# ③ 점검 구멍 — 마지막 완료 봉 기준 20~24시간 전 5칸. 대역 거래소에도 없다.
now = int(time.time())
cur = hour_start(now)
hole = [cur - g * 3600 for g in range(21, 26)]                  # g=1 이 마지막 완료 봉
win_start, win_end = hole[-1], hole[0] + 3600 - 5 * 60            # 마지막 점검 칸은 봉 중간에 끝난다(06:55 처럼) — 경계 봉
excl = ','.join(str(g) for g in range(21, 26))
for coin in (COIN, 'KRW-BTC'):
    rt.psql(f"DELETE FROM market_data_cache WHERE coin_pair = '{coin}' AND timeframe = 'H1'")
    rt.psql("INSERT INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume) "
            f"SELECT date_trunc('hour', now()) - (g || ' hours')::interval, '{coin}', 'H1', 250 + (519 - g) * 0.1, "
            f"250 + (519 - g) * 0.1 + 0.05, 250 + (519 - g) * 0.1 - 0.05, 250 + (519 - g) * 0.1, 1000 "
            f"FROM generate_series(0, 519) g WHERE g NOT IN ({excl})")
rt.ctl('POST', '/_ctl/market', {'candles': {COIN: {'close': '301.9', 'absent': hole},
                                            'KRW-BTC': {'close': '100000000', 'absent': hole}}})
live = int(one("INSERT INTO live_trading_session (strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, "
               f"status, session_type, started_at) VALUES ('HARNESS_SIGNAL', '{COIN}', 'H1', 10000, 10000, 10000, 'RUNNING', 'REAL', now()) "
               "RETURNING id"))
rt.log(f'  LIVE#{live} {COIN} H1 · 점검 구멍 {time.strftime("%m-%d %H:%M", time.gmtime(hole[-1]))}~'
       f'{time.strftime("%H:%M", time.gmtime(hole[0]))} UTC (5칸) · 등록할 구간 {time.strftime("%H:%M", time.gmtime(win_start))}~'
       f'{time.strftime("%H:%M", time.gmtime(win_end))} UTC')

rt.start_app(label='v93-run', props=MARKET_PROPS)
rt.api('POST', '/api/v1/restart-harness/signal?action=HOLD', timeout=10)
check('③a 점검 구간 미등록 — LIVE 판정 불가(결측 5칸)', wait_log(f'{COIN}:H1 판정 불가 (LIVE)', 120, '판정 불가')
      and '결측 5칸' in rt.app_log())
check('③b 결측 재수집 — 대역 거래소 조회에서도 없음(조회 시점 기록)',
      wait_log(f'[결측 재수집] {COIN}:H1 5칸은 조회 시점', 200, '재수집 조회'))
time.sleep(70)
check('③c 조회만으로는 판정 불가 유지(점검 구간 없음)', f'{COIN}:H1 판정 재개' not in rt.app_log())
# 운영자 방식 등록 — 근거·원문 시간대·확인 시각·확인자
rt.psql("INSERT INTO exchange_maintenance_window (exchange, scope, starts_at, ends_at, source, source_timezone, confirmed_at, confirmed_by, note) "
        f"VALUES ('UPBIT', 'ALL_MARKETS', to_timestamp({win_start}), to_timestamp({win_end}), '시험 — 거래소 대역 점검', 'UTC', now(), "
        "'rehearse_v93', '마지막 칸은 경계 봉(재개 뒤 5분 포함) — 일반 결측 1칸으로 남는다')")
check('③d 구간 등록 뒤 — 점검 공백 제외 로그·판정 재개(경계 봉 1칸은 일반 결측으로 남아도 허용 2 이내)',
      wait_log(f'{COIN}:H1 판정 재개 (LIVE)', 200, '판정 재개') and '공식 점검 공백을 결측에서 뺀다' in rt.app_log())
errs = [l for l in rt.app_log().splitlines() if ' ERROR ' in l and ('exchange_maintenance_window' in l or 'SQL' in l)]
check('③e 점검 구간 조회 관련 오류 없음', not errs, '\n'.join(errs[:3]))
rt.kill_app()

# ④ 재기동 — 조회 기록은 메모리에 있다: 첫 조회(기동 2분 뒤) 전에는 판정 불가, 조회 뒤 판정 재개
rt.start_app(label='v93-restart', props=MARKET_PROPS)
rt.api('POST', '/api/v1/restart-harness/signal?action=HOLD', timeout=10)
first_stale = wait_log(f'{COIN}:H1 판정 불가 (LIVE)', 120, '재기동 뒤 판정 불가')
resumed = wait_log(f'{COIN}:H1 판정 재개 (LIVE)', 260, '재기동 뒤 판정 재개')
lg = rt.app_log()
order_ok = first_stale and resumed and lg.index(f'{COIN}:H1 판정 불가 (LIVE)') < lg.index('[결측 재수집]') < lg.index(f'{COIN}:H1 판정 재개 (LIVE)')
check('④  재기동 — 거래소 재조회 전 판정 불가 → 재조회 → 판정 재개(순서)', order_ok)

# ⑤ 실제 상태 확인 경로 — DOWN → DEGRADED → UP, 등록 풀 LIVE 자동 재시작
rt.ctl('POST', '/_ctl/health', {'mode': 'fail'})
down = wait_log('거래소 DOWN 감지', 150, 'DOWN')
try:
    rt.wait_until(lambda: one(f'SELECT status FROM live_trading_session WHERE id = {live}') == 'EMERGENCY_STOPPED', 30, 1, '비상 정지')
except Exception as e:
    rt.log(f'  대기 초과: {e!r}')
check('⑤a 503 연속 → DOWN → LIVE 비상 정지', down and one(f'SELECT status FROM live_trading_session WHERE id = {live}') == 'EMERGENCY_STOPPED')
rt.ctl('POST', '/_ctl/health', {'mode': 'slow'})
deg = wait_log('거래소 상태 변경: DOWN → DEGRADED', 80, 'DEGRADED')
check('⑤b 느린 응답 → DEGRADED, 재시작하지 않음', deg and one(f'SELECT status FROM live_trading_session WHERE id = {live}') == 'EMERGENCY_STOPPED')
rt.ctl('POST', '/_ctl/health', {'mode': 'ok'})
up = wait_log('복구 감지 (직전 DEGRADED)', 80, '복구')
try:
    rt.wait_until(lambda: one(f'SELECT status FROM live_trading_session WHERE id = {live}') == 'RUNNING', 60, 1, '재시작')
except Exception as e:
    rt.log(f'  대기 초과: {e!r}')
lg = rt.app_log()
check('⑤c DEGRADED → UP — 복구 이벤트·자동 재시작(RUNNING)·트랜잭션 거절 없음',
      up and one(f'SELECT status FROM live_trading_session WHERE id = {live}') == 'RUNNING'
      and '세션 자동 재시작 완료: id=' + str(live) in lg and '호출자의 트랜잭션 안에서만' not in lg)
rt.kill_app()

passed = sum(1 for r in results if r[1])
rt.log(f'배포 후보 확인 {passed}/{len(results)} 통과')
for n, ok_, d in results:
    rt.log(f'{"PASS" if ok_ else "FAIL"} {n} {d}')
