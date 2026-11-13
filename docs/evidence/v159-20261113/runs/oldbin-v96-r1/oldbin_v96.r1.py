"""v1.159 §5 — 실제 V94 DB 복제에 V96 만 적용한 뒤 보존 구판 b5cd1d0 jar 를 실제 OS 프로세스로 기동한다 (로컬 전용).

순서
  ① 빈 DB 에 보존 구판 jar(a7ef759f…)를 java -jar 로 기동 → Flyway V1..V94. 정지.
  ② V94 스키마에 대표 행을 SQL 로 넣는다(LIVE·DYNAMIC REAL·PAPER 종전 세션(키 없음)·LIVE_ALIGNED 세션, 포지션·주문). 정지 상태 행만.
  ③ 그 DB 를 템플릿으로 복제한다(V94 복제본). 원본 V94 DB 는 그대로 둔다.
  ④ 복제본에 후보 jar(0834382a…)를 java -jar 로 기동 → Flyway 가 V96 하나만 적용(V95 없음). 정지.
     V94 열의 표 내용 해시가 ② 와 같은지(기존 행 불변) 본다.
  ⑤ 새 열에 값을 넣는다(LIVE_ALIGNED 포지션 exit_decided_at·세션 mdd_peak_krw). 구판이 쓰기를 할 종전 PAPER RUNNING 세션 1행
     (mdd_peak_krw 값 있음)을 넣는다.
  ⑥ 같은 복제본에 보존 구판 jar 를 다시 java -jar 로 기동 → Flyway validate(이력 불변·96 그대로·95 없음), 60초 생존,
     스키마 관련 ERROR 0, 읽기(API GET: 목록·LIVE_ALIGNED 세션 상세·포지션), 쓰기(API POST 정지 → 그 행 STOPPED),
     새 열 값 보존(구판 UPDATE 뒤에도), 다른 행 V94 열 불변.
  ⑦ 후보 jar 를 다시 기동 → 추가 마이그레이션 없음.

외부 접속 차단(프록시 127.0.0.1:9). 운영 접속 없음. 결과: RT 폴더의 로그·oldbin-result.json.
사용: python -I -X utf8 oldbin_v96.py <출력 폴더(없는 경로)> <구판 jar> <후보 jar>
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

OUT, OLD_JAR, NEW_JAR = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT)
JAVA = 'C:/Program Files/Java/jdk-17/bin/java.exe'
TOKEN = 'ob-token'
PGPORT = 15437
STAMP = time.strftime('%H%M%S')
DB94 = f'v159_ob_v94_{STAMP}'
DB96 = f'v159_ob_v96_{STAMP}'
results = []
LOG = open(os.path.join(OUT, 'run.log'), 'w', encoding='utf-8')


def log(*a):
    s = time.strftime('%H:%M:%S') + ' ' + ' '.join(str(x) for x in a)
    print(s, flush=True)
    LOG.write(s + '\n')
    LOG.flush()


def check(name, ok, detail=''):
    results.append({'name': name, 'ok': bool(ok), 'detail': str(detail)})
    log(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail else ''))


def psql(db, sql):
    r = subprocess.run(['docker', 'exec', '-i', 'v152-pg', 'psql', '-X', '-q', '-U', 'trader', '-d', db, '-v', 'ON_ERROR_STOP=1',
                        '-At', '-F', '|'], input=sql, capture_output=True, text=True, encoding='utf-8')
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return r.stdout.strip()


def sha(path):
    with open(path, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()


def api(port, method, path):
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', method=method,
                                 headers={'Authorization': f'Bearer {TOKEN}'}, data=b'' if method == 'POST' else None)
    with urllib.request.urlopen(req, timeout=20) as r:
        raw = r.read()
        return r.status, (json.loads(raw) if raw else None)


def launch(jar, db, port, label):
    env = dict(os.environ, DB_URL=f'jdbc:postgresql://localhost:{PGPORT}/{db}', DB_USERNAME='trader', DB_PASSWORD='',
               REDIS_HOST='localhost', REDIS_PORT='16379',
               UPBIT_ACCESS_KEY='ob-access-key', UPBIT_SECRET_KEY='ob-secret-key-0123456789abcdef0123456789abcdef',
               API_AUTH_TOKEN=TOKEN, TELEGRAM_ENABLED='false', TELEGRAM_BOT_TOKEN='', DISCORD_BOT_TOKEN='')
    logpath = os.path.join(OUT, f'{label}.log')
    props = ['-Xmx768m', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', f'-Dserver.port={port}',
             '-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=9', '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=9',
             '-Dhttp.nonProxyHosts=localhost|127.0.0.1', '-Dtelegram.enabled=false']
    proc = subprocess.Popen([JAVA] + props + ['-jar', jar], stdout=open(logpath, 'w', encoding='utf-8'),
                            stderr=subprocess.STDOUT, env=env)
    proc.logpath = logpath
    t0 = time.time()
    while time.time() - t0 < 300:
        if proc.poll() is not None:
            log(f'{label}: 기동 중 종료 rc={proc.returncode} ({time.time() - t0:.0f}s)')
            return proc, False
        try:
            api(port, 'GET', '/actuator/health')
            log(f'{label}: 기동 ({time.time() - t0:.0f}s) pid={proc.pid}')
            return proc, True
        except Exception:
            time.sleep(1)
    return proc, False


def kill(proc):
    if proc and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        time.sleep(3)


def errors(proc):
    lg = open(proc.logpath, encoding='utf-8', errors='replace').read()
    errs = [l for l in lg.splitlines() if re.search(r'\sERROR\s', l)]
    schema = [l for l in errs if re.search(r'(?i)flyway|schema|validat|migration|column|exit_decided|mdd_peak', l)]
    return lg, errs, schema


def flyway(db):
    return psql(db, "SELECT string_agg(coalesce(version,'-') || ':' || success || ':' || coalesce(checksum::text,'-'), ',' ORDER BY installed_rank) "
                    "FROM flyway_schema_history")


V94COLS = {}


def table_hash(db, table):
    cols = V94COLS[table]
    sql = f"SELECT md5(coalesce(string_agg(t::text, E'\\n' ORDER BY id), '')) || '|' || count(*) FROM (SELECT {', '.join(cols)} FROM {table}) t"
    return psql(db, sql)


TABLES = ['live_trading_session', 'dynamic_session', 'position', '"order"', 'risk_config',
          'paper_trading.virtual_balance', 'paper_trading.position', 'paper_trading."order"']

log('jar', OLD_JAR, sha(OLD_JAR))
log('jar', NEW_JAR, sha(NEW_JAR))
meta = {'old_jar': OLD_JAR, 'old_sha': sha(OLD_JAR), 'new_jar': NEW_JAR, 'new_sha': sha(NEW_JAR), 'db94': DB94, 'db96': DB96}

# ① 구판으로 V94
psql('postgres', f'CREATE DATABASE {DB94}')
old, ok = launch(OLD_JAR, DB94, 18191, '1-old-on-empty')
fw94 = flyway(DB94)
check('①  보존 구판 jar 실제 기동·Flyway V94', ok and psql(DB94, 'SELECT max(version::int) FROM flyway_schema_history WHERE success') == '94'
      and ':false:' not in fw94, fw94[-60:])
kill(old)

# V94 시점의 열 목록(새 열이 생기기 전)
for t in TABLES:
    schema, name = (t.split('.') + [None])[:2] if '.' in t else ('public', t)
    name = name.strip('"')
    cols = psql(DB94, f"SELECT string_agg(quote_ident(column_name), ',' ORDER BY ordinal_position) FROM information_schema.columns "
                      f"WHERE table_schema = '{schema}' AND table_name = '{name}'")
    V94COLS[t] = cols.split(',')
check('①  V94 에 새 열 없음(exit_decided_at·mdd_peak_krw)',
      '"exit_decided_at"' not in V94COLS['paper_trading.position'] and 'exit_decided_at' not in V94COLS['paper_trading.position']
      and 'mdd_peak_krw' not in V94COLS['paper_trading.virtual_balance'])

# ② 대표 행(정지 상태)
seed = """
INSERT INTO live_trading_session (strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status)
  VALUES ('DAILY_SMA20', 'KRW-BTC', 'D1', 20200, 20200, 20200, 'STOPPED');
INSERT INTO dynamic_session (strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode)
  VALUES ('COMPOSITE_MOMENTUM_ICHIMOKU_V2', 'H1', 100000, 100000, 100000, 'STOPPED', 'REAL');
INSERT INTO position (coin_pair, side, entry_price, avg_price, size, session_kind, session_id, status)
  VALUES ('KRW-BTC', 'LONG', 100000000, 100000000, 0, 'LIVE', 1, 'CLOSED');
INSERT INTO "order" (coin_pair, side, order_type, quantity, state, session_kind, session_id, position_id, filled_quantity)
  VALUES ('KRW-BTC', 'SELL', 'MARKET', 0.0001, 'FILLED', 'LIVE', 1, 1, 0.0001);
INSERT INTO paper_trading.virtual_balance (total_krw, available_krw, initial_capital, strategy_name, coin_pair, timeframe, status, strategy_params)
  VALUES (1000000, 1000000, 1000000, 'DAILY_SMA20', 'KRW-BTC', 'D1', 'STOPPED', NULL),
         (1000000, 990000, 1000000, 'DAILY_SMA20', 'KRW-BTC', 'D1', 'STOPPED', '{"paperExecutionMode":"LIVE_ALIGNED"}'::jsonb);
INSERT INTO paper_trading.position (coin_pair, side, entry_price, avg_price, size, session_id, status, session_kind)
  VALUES ('KRW-BTC', 'LONG', 100000000, 100000000, 0.0001, 2, 'CLOSED', 'PAPER');
"""
psql(DB94, seed)
ids = psql(DB94, "SELECT string_agg(id::text, ',' ORDER BY id) FROM paper_trading.virtual_balance") + '/' + psql(DB94, "SELECT string_agg(session_id::text, ',') FROM paper_trading.position")
check('②  대표 행 — PAPER 세션 id 1(키 없음)·2(LIVE_ALIGNED), 포지션은 세션 2', ids == '1,2/2', ids)
before = {t: table_hash(DB94, t) for t in TABLES}
log('V94 표 해시', json.dumps(before, ensure_ascii=False))

# ③ 복제
psql('postgres', f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '{DB94}' AND pid <> pg_backend_pid()")
psql('postgres', f'CREATE DATABASE {DB96} TEMPLATE {DB94}')
check('③  V94 복제본 생성(원본 DB 그대로)', {t: table_hash(DB96, t) for t in TABLES} == before)

# ④ 후보로 V96 하나
new, ok = launch(NEW_JAR, DB96, 18192, '4-new-on-v94clone')
fw96 = flyway(DB96)
vers = [x.split(':')[0] for x in fw96.split(',')]
check('④  후보 jar 실제 기동·Flyway 가 V96 하나만 적용(V95 없음)', ok and vers[-1] == '96' and vers[-2] == '94' and '95' not in vers
      and fw96.startswith(fw94) and fw96.count(',') == fw94.count(',') + 1, fw96[len(fw94):])
kill(new)
after_new = {t: table_hash(DB96, t) for t in TABLES}
check('④  V96 적용·후보 기동 뒤 기존 행의 V94 열 불변', after_new == before,
      {t: (before[t], after_new[t]) for t in TABLES if before[t] != after_new[t]})
cols96 = psql(DB96, "SELECT string_agg(table_name || '.' || column_name || ':' || data_type || ':' || is_nullable || ':' || coalesce(column_default, 'none'), ' ') "
                    "FROM information_schema.columns WHERE table_schema = 'paper_trading' AND column_name IN ('exit_decided_at', 'mdd_peak_krw')")
check('④  두 열 NULL 허용·기본값 없음, 기존 행 NULL', 'YES:none' in cols96 and cols96.count('YES:none') == 2
      and psql(DB96, 'SELECT count(*) FROM paper_trading.position WHERE exit_decided_at IS NOT NULL') == '0'
      and psql(DB96, 'SELECT count(*) FROM paper_trading.virtual_balance WHERE mdd_peak_krw IS NOT NULL') == '0', cols96)

# ⑤ 새 열 값 + 구판이 쓸 RUNNING 종전 PAPER 1행
psql(DB96, """
UPDATE paper_trading.position SET exit_decided_at = '2026-11-13T00:00:00Z' WHERE session_id = 2;
UPDATE paper_trading.virtual_balance SET mdd_peak_krw = 1000123.45 WHERE id = 2;
INSERT INTO paper_trading.virtual_balance (total_krw, available_krw, initial_capital, strategy_name, coin_pair, timeframe, status, started_at, mdd_peak_krw)
  VALUES (500000, 500000, 500000, 'DAILY_SMA20', 'KRW-BTC', 'D1', 'RUNNING', now(), 777.77);
""")
check('⑤  구판이 정지할 종전 PAPER RUNNING 행 id 3', psql(DB96, "SELECT id FROM paper_trading.virtual_balance WHERE status = 'RUNNING'") == '3')
newcols_before = psql(DB96, "SELECT (SELECT string_agg(id || '=' || coalesce(exit_decided_at::text, 'null'), ',' ORDER BY id) FROM paper_trading.position) || ' / ' || "
                            "(SELECT string_agg(id || '=' || coalesce(mdd_peak_krw::text, 'null'), ',' ORDER BY id) FROM paper_trading.virtual_balance)")
base96 = {t: table_hash(DB96, t) for t in TABLES}
fw_before_old = flyway(DB96)

# ⑥ 구판 재기동
old2, ok = launch(OLD_JAR, DB96, 18191, '6-old-on-v96')
check('⑥  보존 구판 jar 가 V96 복제본에서 실제 기동', ok)
reads = {}
if ok:
    for path in ['/api/v1/paper-trading/sessions', '/api/v1/paper-trading/sessions/2', '/api/v1/paper-trading/sessions/2/positions',
                 '/api/v1/paper-trading/sessions/1']:
        try:
            st, body = api(18191, 'GET', path)
            reads[path] = (st, json.dumps(body, ensure_ascii=False)[:300])
        except Exception as e:  # noqa: BLE001
            reads[path] = ('ERR', str(e)[:300])
    check('⑥  읽기 — 목록·LIVE_ALIGNED 세션 상세·포지션·종전 세션 상세 GET 200', all(v[0] == 200 for v in reads.values()),
          json.dumps(reads, ensure_ascii=False)[:900])
    try:
        st, body = api(18191, 'POST', '/api/v1/paper-trading/sessions/3/stop')
        wrote = (st, json.dumps(body, ensure_ascii=False)[:300])
    except Exception as e:  # noqa: BLE001
        wrote = ('ERR', str(e)[:300])
    check('⑥  쓰기 — 구판 API 정지(POST) 200 · 그 행 STOPPED', wrote[0] == 200
          and psql(DB96, 'SELECT status FROM paper_trading.virtual_balance WHERE id = 3') == 'STOPPED', wrote)
    time.sleep(60)
alive = old2.poll() is None
kill(old2)
lg, errs, schema_errs = errors(old2)
check('⑥  60초 생존', ok and alive)
fw_after_old = flyway(DB96)
check('⑥  Flyway 이력 불변(96 그대로·95 없음·행 추가 없음) — validate 통과', fw_after_old == fw_before_old, fw_after_old[-80:])
check('⑥  스키마·마이그레이션 관련 ERROR 0', not schema_errs, f'ERROR {len(errs)}줄, 스키마 관련 {len(schema_errs)}줄: ' + ' / '.join(schema_errs[:3]))
fw_log = [l for l in lg.splitlines() if re.search(r'(?i)flyway|migrat|validated', l)]
newcols_after = psql(DB96, "SELECT (SELECT string_agg(id || '=' || coalesce(exit_decided_at::text, 'null'), ',' ORDER BY id) FROM paper_trading.position) || ' / ' || "
                           "(SELECT string_agg(id || '=' || coalesce(mdd_peak_krw::text, 'null'), ',' ORDER BY id) FROM paper_trading.virtual_balance)")
check('⑥  새 열 값 보존(구판이 같은 표 행을 UPDATE 한 뒤에도)', newcols_after == newcols_before, f'{newcols_before} → {newcols_after}')
after_old = {t: table_hash(DB96, t) for t in TABLES}
changed = [t for t in TABLES if after_old[t] != base96[t]]
check('⑥  구판 기동 중 V94 열 변경은 정지한 PAPER 행(virtual_balance id 3)이 있는 표뿐', set(changed) <= {'paper_trading.virtual_balance'}, changed)
vb_other = psql(DB96, "SELECT md5(string_agg(t::text, E'\\n' ORDER BY id)) FROM (SELECT " + ', '.join(V94COLS['paper_trading.virtual_balance'])
                + " FROM paper_trading.virtual_balance WHERE id IN (1, 2)) t")
vb_other_base = psql(DB94, "SELECT md5(string_agg(t::text, E'\\n' ORDER BY id)) FROM (SELECT " + ', '.join(V94COLS['paper_trading.virtual_balance'])
                     + " FROM paper_trading.virtual_balance WHERE id IN (1, 2)) t")
check('⑥  정지 대상이 아닌 PAPER 세션 행(1·2) V94 열은 V94 원본과 같다', vb_other == vb_other_base)

# ⑦ 후보 재기동 — 추가 마이그레이션 없음
new2, ok = launch(NEW_JAR, DB96, 18192, '7-new-again')
check('⑦  후보 재기동·추가 마이그레이션 없음', ok and flyway(DB96) == fw_after_old)
kill(new2)

meta.update({'flyway_v94': fw94, 'flyway_v96': fw96, 'reads': reads, 'flyway_log_lines_old_on_v96': fw_log[:20],
             'error_lines_old_on_v96': errs[:20], 'newcols': [newcols_before, newcols_after], 'v96_columns': cols96})
json.dump({'meta': meta, 'results': results}, open(os.path.join(OUT, 'oldbin-result.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
n = sum(r['ok'] for r in results)
log(f'결과 {n}/{len(results)}')
sys.exit(0 if n == len(results) else 1)
