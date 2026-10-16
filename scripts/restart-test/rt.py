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
# 🔴 build/ 밖에 둔다 (2026-10-14) — `gradlew clean` 이 build/ 를 지워 실행 로그·덤프가 사라진 일이 있었다.
WORK = os.environ.get('RT_WORK', os.path.join(ROOT, 'restart-test-work'))
os.makedirs(WORK, exist_ok=True)
APP_PORT = int(os.environ.get('RT_APP_PORT', 18081))   # 다른 프로그램이 쓰고 있으면 바꾼다(기본 그대로)
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


def psql_background(sql, label):
    """별도 psql 연결로 SQL 을 백그라운드 실행한다(잠금을 쥐고 기다리는 용도). 프로세스를 돌려준다."""
    out = open(os.path.join(WORK, f'psql-{label}.log'), 'w', encoding='utf-8')
    return subprocess.Popen(['docker', 'exec', '-i', 'cat-rt-pg', 'psql', '-U', 'trader', '-d', 'crypto_auto_trader',
                             '-v', 'ON_ERROR_STOP=1', '-At', '-c', sql], stdout=out, stderr=subprocess.STDOUT)


def deadlocks():
    return int(one("SELECT deadlocks FROM pg_stat_database WHERE datname = 'crypto_auto_trader'"))


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


def dump_db(label):
    """🔴 삭제 전 덤프 (2026-10-14) — 컨테이너가 있으면 그 DB 를 `pg_dump -Fc` 로 떠 WORK/dumps 에 둔다.

    덤프가 실패하면 예외 — 호출자(fresh_db)는 삭제하지 않는다. 증거가 덤프 없이 사라지는 일을 막는다.
    """
    r = subprocess.run(['docker', 'inspect', 'cat-rt-pg'], capture_output=True)
    if r.returncode != 0:
        return None
    os.makedirs(os.path.join(WORK, 'dumps'), exist_ok=True)
    name = f'{time.strftime("%Y%m%d-%H%M%S")}-{label}.dump'
    subprocess.run(['docker', 'exec', 'cat-rt-pg', 'sh', '-c',
                    f'pg_dump -U trader -d crypto_auto_trader -Fc -f /tmp/{name} 2>/dev/null'], check=True)
    dest = os.path.join(WORK, 'dumps', name)
    env = dict(os.environ, MSYS_NO_PATHCONV='1')
    subprocess.run(['docker', 'cp', f'cat-rt-pg:/tmp/{name}', dest], check=True, env=env)
    if os.path.getsize(dest) == 0:
        raise RuntimeError('덤프가 비었다 — 삭제하지 않는다')
    log(f'  DB 덤프: {dest} ({os.path.getsize(dest)} bytes)')
    return dest


def fresh_db():
    """DB 컨테이너를 새로 만든다 — 스키마는 다음 앱 기동에서 Flyway 가 적용한다. 🔴 지우기 전에 덤프한다."""
    dump_db('before-fresh')
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


POOL_DEFAULT = {
    'trading.capital-pool.stage': 'RESTART_TEST',
    # 2026-10-14: 손실 한도(총 자본 × risk_config %)가 시나리오가 SQL 로 닫은 포지션(매도 없는 청산 = 현금 흐름상 손실)으로 차지
    # 않게 크게 둔다. 손실 한도 자체는 S14 가 STAGE_1 정확한 값으로 본다. 가드는 모든 시나리오에서 켜져 있다.
    'trading.capital-pool.total-cap-krw': '1000000000',
    'trading.capital-pool.per-position-cap-krw': '100000',
    # 시나리오가 남긴 열린 포지션이 슬롯을 채워 엉뚱한 거절이 나지 않게 넉넉히 (슬롯 거절 자체는 S13·단위 시험이 본다)
    'trading.capital-pool.max-concurrent-positions': '1000',
}
_others = []    # 두 번째 이후 인스턴스 (단일 인스턴스 시험)


def start_app(crashpoint=None, label='', props=None):
    """주 인스턴스(APP_PORT)를 띄운다. 준비되면 None, 기동 중 끝나면 종료 코드."""
    global _app
    if port_in_use(APP_PORT):
        raise RuntimeError(f'포트 {APP_PORT} 를 다른 프로세스가 쓰고 있다 — 이전 앱이 살아 있다')
    _app, rc = launch(APP_PORT, crashpoint=crashpoint, label=label, props=props)
    return rc


def start_other(port, label='', props=None, timeout=240):
    """같은 DB 를 쓰는 다른 인스턴스 — (프로세스, 기동 결과: None=준비됨 / 종료 코드)."""
    if port_in_use(port):
        raise RuntimeError(f'포트 {port} 사용 중')
    proc, rc = launch(port, label=label, props=props, timeout=timeout)
    _others.append((proc, port))
    return proc, rc


def launch(port, crashpoint=None, label='', props=None, timeout=240):
    """props: 덮어쓸 시스템 속성 {이름: 값}, 값이 None 이면 그 속성을 아예 주지 않는다."""
    global _app_seq
    _app_seq += 1
    argfile = os.path.join(WORK, 'java-args.txt')
    with open(argfile, 'w', encoding='utf-8') as f:
        f.write('-cp\n"' + classpath().replace('\\', '/') + '"\n')
    props_list = [
        '-Xmx768m', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        f'-Dupbit.api.base-url=http://127.0.0.1:{DOUBLE_PORT}/v1',
        f'-Dupbit.health-check-url=http://127.0.0.1:{DOUBLE_PORT}/v1/market/all',
        # 외부 인터넷 호출 차단 — 공개 시세·뉴스 등은 실패로 끝난다. 대역(localhost)만 통과.
        '-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=9',
        '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=9',
        '-Dhttp.nonProxyHosts=localhost|127.0.0.1',
        f'-Dserver.port={port}',
        '-Dspring.profiles.active=restart-harness',
        '-Dtelegram.enabled=false',
    ]
    merged = dict(POOL_DEFAULT)
    merged.update(props or {})
    props_list += [f'-D{k}={v}' for k, v in merged.items() if v is not None]
    if crashpoint:
        props_list.append(f'-Dcrashpoint={crashpoint}')
    env = dict(os.environ,
               DB_URL='jdbc:postgresql://localhost:15432/crypto_auto_trader', DB_USERNAME='trader',
               DB_PASSWORD='rtpass', REDIS_HOST='localhost', REDIS_PORT='16379',
               UPBIT_ACCESS_KEY='rt-access-key', UPBIT_SECRET_KEY='rt-secret-key-0123456789abcdef0123456789abcdef',
               API_AUTH_TOKEN=TOKEN, TELEGRAM_ENABLED='false', TELEGRAM_BOT_TOKEN='', DISCORD_BOT_TOKEN='')
    logpath = os.path.join(WORK, f'app-{_app_seq:02d}{"-" + label if label else ""}.log')
    logf = open(logpath, 'w', encoding='utf-8')
    proc = subprocess.Popen([JAVA] + props_list + ['@' + argfile, 'com.cryptoautotrader.api.CryptoAutoTraderApplication'],
                            stdout=logf, stderr=subprocess.STDOUT, env=env)
    proc.logpath = logpath
    t0 = time.time()
    while time.time() - t0 < timeout:
        if proc.poll() is not None:
            log(f'app #{_app_seq} (:{port}) exited during startup rc={proc.returncode} ({time.time() - t0:.0f}s)')
            return proc, proc.returncode
        try:
            api('GET', '/actuator/health', port=port)
            log(f'app #{_app_seq} (:{port}) ready ({time.time() - t0:.0f}s, crashpoint={crashpoint})')
            return proc, None
        except Exception:
            time.sleep(1)
    raise RuntimeError('app did not become ready')


def app_log(proc=None):
    proc = proc or _app
    with open(proc.logpath, encoding='utf-8', errors='replace') as f:
        return f.read()


def kill_proc(proc, port):
    if proc is not None and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        proc.wait()
    for _ in range(50):
        if not port_in_use(port):
            return
        time.sleep(0.2)


def kill_all():
    kill_app()
    while _others:
        proc, port = _others.pop()
        kill_proc(proc, port)


def app_alive():
    return _app is not None and _app.poll() is None


def wait_app_exit(timeout=60, proc=None):
    proc = proc or _app
    t0 = time.time()
    while time.time() - t0 < timeout:
        if proc.poll() is not None:
            return proc.returncode
        time.sleep(0.2)
    return None


def kill_app():
    """외부 강제 종료 — 종료 훅·커밋 없이 프로세스를 끝낸다."""
    kill_proc(_app, APP_PORT)


def stop_app_gracefully():
    # Windows 콘솔 프로세스에는 정상 종료 신호를 보낼 수 없다 — 시험에서는 강제 종료로 통일한다.
    kill_app()


def api(method, path, timeout=10, port=APP_PORT):
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', method=method,
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
