"""실제 프로세스 재시작 시험 — 공통 구동기 (2026-10-13).

- PostgreSQL·Redis: 별도 컨테이너(cat-rt-pg :15432, cat-rt-redis :16379). 스키마는 앱 기동 시 Flyway 가
  V1~V87 을 실제로 적용한다(시험용 수동 스키마 없음).
- 거래소: exchange_double.py 를 별도 프로세스로 띄운다. 앱을 죽여도 접수·체결 기록이 남는다.
- 앱: restartHarness 클래스패스로 실제 JVM 프로세스를 띄운다. 강제 종료는 ① 강제 종료 지점(-Dcrashpoint,
  Runtime.halt) ② 외부 강제 종료(taskkill /F) 두 가지.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
WORK = os.environ.get('RT_WORK', os.path.join(ROOT, 'build', 'restart-test'))
os.makedirs(WORK, exist_ok=True)
APP_PORT = 18081
DOUBLE_PORT = 18080
TOKEN = 'rt-token'
# 🔴 PATH 의 java 는 실행기 스텁이라 자식으로 실제 JVM 을 띄운다 — 스텁을 죽이면 앱이 남는다. JDK 를 직접 쓴다.
JAVA = os.environ.get('JAVA', 'C:/Program Files/Java/jdk-17/bin/java.exe')

_app = None
_double = None
_app_seq = 0


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


# ── DB ────────────────────────────────────────────────────────────────────
def psql(sql):
    r = subprocess.run(['docker', 'exec', '-i', 'cat-rt-pg', 'psql', '-U', 'trader', '-d', 'crypto_auto_trader',
                        '-v', 'ON_ERROR_STOP=1', '-At', '-F', '|', '-c', sql],
                       capture_output=True, text=True, encoding='utf-8')
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return [line.split('|') for line in r.stdout.strip().splitlines() if line]


def one(sql):
    rows = psql(sql)
    return rows[0][0] if rows else None


# ── 거래소 대역 ─────────────────────────────────────────────────────────────
def start_double(fresh=True):
    global _double
    state = os.path.join(WORK, 'exchange_state.json')
    if fresh and os.path.exists(state):
        os.remove(state)
    out = open(os.path.join(WORK, 'double.log'), 'a', encoding='utf-8')
    _double = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(__file__), 'exchange_double.py'),
                                str(DOUBLE_PORT), state], stdout=out, stderr=out)
    for _ in range(50):
        try:
            ctl('GET', '/_ctl/orders')
            return
        except Exception:
            time.sleep(0.2)
    raise RuntimeError('double did not start')


def stop_double():
    if _double:
        _double.kill()
        _double.wait()


def ctl(method, path, body=None):
    req = urllib.request.Request(f'http://127.0.0.1:{DOUBLE_PORT}{path}', method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read() or b'{}')


def exchange_orders():
    return ctl('GET', '/_ctl/orders')


# ── 앱 ────────────────────────────────────────────────────────────────────
def classpath():
    with open(os.path.join(ROOT, 'web-api', 'build', 'restart-harness-classpath.txt'), encoding='utf-8') as f:
        return f.read().strip()


def port_in_use(port):
    import socket
    with socket.socket() as sk:
        return sk.connect_ex(('127.0.0.1', port)) == 0


def fresh_db():
    """DB 컨테이너를 새로 만든다 — 스키마는 다음 앱 기동에서 Flyway 가 V1~V87 을 적용한다."""
    subprocess.run(['docker', 'rm', '-f', '-v', 'cat-rt-pg'], capture_output=True)   # 익명 볼륨까지
    subprocess.run(['docker', 'run', '-d', '--name', 'cat-rt-pg', '-e', 'POSTGRES_DB=crypto_auto_trader',
                    '-e', 'POSTGRES_USER=trader', '-e', 'POSTGRES_PASSWORD=rtpass', '-p', '127.0.0.1:15432:5432',
                    'timescale/timescaledb@sha256:b26180320dea45a27190318903f63cc2839f190315663a53b6429a937dd02196'], check=True, capture_output=True)
    for _ in range(60):
        r = subprocess.run(['docker', 'exec', 'cat-rt-pg', 'pg_isready', '-U', 'trader', '-d', 'crypto_auto_trader'],
                           capture_output=True)
        if r.returncode == 0:
            time.sleep(2)
            return
        time.sleep(1)
    raise RuntimeError('pg not ready')


def start_app(crashpoint=None, label=''):
    global _app, _app_seq
    if port_in_use(APP_PORT):
        raise RuntimeError(f'포트 {APP_PORT} 를 다른 프로세스가 쓰고 있다 — 이전 앱이 살아 있다')
    _app_seq += 1
    argfile = os.path.join(WORK, 'java-args.txt')
    with open(argfile, 'w', encoding='utf-8') as f:
        f.write('-cp\n"' + classpath().replace('\\', '/') + '"\n')
    props = [
        '-Xmx768m', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        f'-Dupbit.api.base-url=http://127.0.0.1:{DOUBLE_PORT}/v1',
        f'-Dupbit.health-check-url=http://127.0.0.1:{DOUBLE_PORT}/v1/market/all',
        # 외부 인터넷 호출 차단 — 공개 시세·뉴스 등은 실패로 끝난다. 대역(localhost)만 통과.
        '-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=9',
        '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=9',
        '-Dhttp.nonProxyHosts=localhost|127.0.0.1',
        f'-Dserver.port={APP_PORT}',
        '-Dspring.profiles.active=restart-harness',
        '-Dtrading.capital-pool.stage=RESTART_TEST',
        '-Dtrading.capital-pool.total-cap-krw=1000000',
        '-Dtrading.capital-pool.per-position-cap-krw=100000',
        '-Dtrading.capital-pool.max-concurrent-positions=10',
        '-Dtelegram.enabled=false',
    ]
    if crashpoint:
        props.append(f'-Dcrashpoint={crashpoint}')
    env = dict(os.environ,
               DB_URL='jdbc:postgresql://localhost:15432/crypto_auto_trader', DB_USERNAME='trader',
               DB_PASSWORD='rtpass', REDIS_HOST='localhost', REDIS_PORT='16379',
               UPBIT_ACCESS_KEY='rt-access-key', UPBIT_SECRET_KEY='rt-secret-key-0123456789abcdef0123456789abcdef',
               API_AUTH_TOKEN=TOKEN, TELEGRAM_ENABLED='false', TELEGRAM_BOT_TOKEN='', DISCORD_BOT_TOKEN='')
    logf = open(os.path.join(WORK, f'app-{_app_seq:02d}{"-" + label if label else ""}.log'), 'w', encoding='utf-8')
    _app = subprocess.Popen([JAVA] + props + ['@' + argfile, 'com.cryptoautotrader.api.CryptoAutoTraderApplication'],
                            stdout=logf, stderr=subprocess.STDOUT, env=env)
    t0 = time.time()
    while time.time() - t0 < 240:
        if _app.poll() is not None:
            return _app.returncode
        try:
            api('GET', '/actuator/health')
            log(f'app #{_app_seq} ready ({time.time() - t0:.0f}s, crashpoint={crashpoint})')
            return None
        except Exception:
            time.sleep(1)
    raise RuntimeError('app did not become ready')


def app_alive():
    return _app is not None and _app.poll() is None


def wait_app_exit(timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if _app.poll() is not None:
            return _app.returncode
        time.sleep(0.2)
    return None


def kill_app():
    """외부 강제 종료 — 종료 훅·커밋 없이 프로세스를 끝낸다."""
    if app_alive():
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(_app.pid)], capture_output=True)
        _app.wait()
    for _ in range(50):
        if not port_in_use(APP_PORT):
            return
        time.sleep(0.2)


def stop_app_gracefully():
    # Windows 콘솔 프로세스에는 정상 종료 신호를 보낼 수 없다 — 시험에서는 강제 종료로 통일한다.
    kill_app()


def api(method, path, timeout=10):
    req = urllib.request.Request(f'http://127.0.0.1:{APP_PORT}{path}', method=method,
                                 headers={'Authorization': f'Bearer {TOKEN}'}, data=b'' if method == 'POST' else None)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def wait_until(cond, timeout, step=1.0, what=''):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if cond():
                return True
        except Exception:
            pass
        time.sleep(step)
    log(f'  wait_until timeout: {what}')
    return False
