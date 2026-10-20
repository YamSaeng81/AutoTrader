"""V94 배포 실패 시 되돌리기 확인 (2026-10-20, 계획 정비 10-21 대비) — V94 스키마에서 운영 버전 3b781d8 앱이 기동하는가.

정비 절차의 복구 경로: 스키마는 V94 로 둔 채(되돌리지 않는다) 앱만 3b781d8 로 되돌린다.
① 3b781d8 jar 로 V93 ② 현재 main(후보) 기동 → V94 ③ 3b781d8 jar 를 V94 스키마에서 다시 기동 — 기동 성공·Flyway 가 V94 를 그대로 둠·
   스키마 검증(ddl-auto validate) 통과·60초 동안 ERROR 없음 ④ 되돌린 뒤 후보를 다시 기동 — 추가 마이그레이션 없이 V94 그대로.
"""
import os
import re
import subprocess
import sys
import time

os.environ.setdefault('RT_APP_PORT', '18091')
os.environ.setdefault('RT_OTHER_PORT', '18092')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rt  # noqa: E402

# rehearse_v94 는 가져오면 본문이 실행되므로 같은 함수를 여기 둔다.
OLD_JAR = os.environ.get('RT_OLD_JAR', r'D:\Claude Code\evidence\crypto-auto-trader\prod-jars\3b781d8-web-api-0.1.0.jar')
OLD_PORT = 18093
results = []
one = rt.one


def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    rt.log(f'  {"PASS" if ok else "FAIL"} {name} {detail}')


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


rt.log('V94 되돌리기 확인 — V94 스키마에서 3b781d8 기동')
if not os.path.exists(OLD_JAR):
    raise SystemExit(f'운영 버전 jar 가 없다 — RT_OLD_JAR 로 준다: {OLD_JAR}')
rt.fresh_db()
rt.start_double(fresh=True)

# ① 운영 버전으로 V93
old, ok = launch_old('rb-v93')
check('①  3b781d8 jar 기동·Flyway V93', ok and one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '93')
kill(old)

# ② 후보 — V94
rt.start_app(label='rb-v94')
check('②  후보 기동·Flyway V94', one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '94')
rt.kill_app()

# ③ 되돌리기 — V94 스키마에서 3b781d8
rows_before = one('SELECT count(*) FROM flyway_schema_history')
old, ok = launch_old('rb-old-on-v94')
time.sleep(60)
alive = old.poll() is None
kill(old)
lg = open(os.path.join(rt.WORK, 'v94-old-rb-old-on-v94.log'), encoding='utf-8', errors='replace').read()
errors = [l for l in lg.splitlines() if re.search(r'\sERROR\s', l)]
schema_err = [l for l in errors if re.search(r'(?i)flyway|schema|validat|migration|sell_quarantine', l)]
check('③  3b781d8 가 V94 스키마에서 기동·60초 생존', ok and alive)
check('③  Flyway 이력 불변(V94 그대로·행 추가 없음)',
      one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '94'
      and one('SELECT count(*) FROM flyway_schema_history') == rows_before, f'rows={rows_before}')
check('③  스키마·마이그레이션 관련 ERROR 없음', not schema_err, f'ERROR {len(errors)}줄, 스키마 관련 {len(schema_err)}줄')
for l in errors[:10]:
    rt.log(f'    ERROR 줄: {l[:200]}')

# ④ 다시 후보
rt.start_app(label='rb-v94-again')
check('④  후보 재기동·V94 그대로', one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '94'
      and one('SELECT count(*) FROM flyway_schema_history') == rows_before)
rt.kill_app()
rt.stop_double()

passed = sum(1 for r in results if r[1])
rt.log(f'V94 되돌리기 확인 {passed}/{len(results)} 통과')
for n, ok_, dd in results:
    rt.log(f'{"PASS" if ok_ else "FAIL"} {n} {dd}')
