"""W3 현재판(1899b49) 영향 경로 리허설 (2026-11-06, 주말 지시 §5) — rehearse_sma20.py(10-31 4차)와 겹치지 않는 차이만 잇는다.

실제 앱 프로세스 · PostgreSQL(cat-rt-pg) · 별도 프로세스 거래소 대역. 외부 인터넷은 막는다(rt.launch 프록시). 운영 DB·계좌·URL·토큰 없음.
  ① 새 DB · SID 203 생성(문서 본문) → F1(문서 DB·API 줄) → F2 시작 1회 → 시작 확인
  ② 마감 봉 BUY → 회차 마감 뒤 승인 · 매수 체결(강제 종료 없음 — 4차가 이미 본 경로) → F3 ✅ 보유
  ③ RUNNING 재시작 요청 — 기록 변화 없음
  ④ 시세 이상: 대역 ticker 0 → -5 (약 4분) — 매도 0 · 포지션 OPEN · 주문 0 · N19/N17/N20 고정 WARN 관측 · ERROR 0
  ⑤ 회복: 손절선(5%) 아래 신선한 정상 가격 → 보호 매도 1회 · STOP_LOSS · F3 ✅ 청산
  ⑥ 56일 종료(시계 주입: F5 조회의 now() 만 +56일 1분 — 서버 시각·데이터 무변경)
       F5-1/F5-2 주입 전: due f · 정지 거절 → 주입 뒤: due t · end_kst = F2 기록 · 정지 1회 → 두 번째 거절
       F5-3: W4 가 발급한 실행 사본(f53.exec.sh, 출처 6298f5b)의 git fetch 만 로컬 git show 로 바꿔 실행 → ⛔ STOPPED · 차이 0 · DB 정리 조건 충족
       F5-4: 설치본과 같은 해시의 장부 SQL(8bfe8a9) → 출력 보존
  ⑦ 거래소 주문 호출 수(매수 1 · 매도 1) · 강제 종료 · 재기동 뒤 기록 전체 그대로

실행: RT_WORK=<빈 폴더> RT_PLAN_DOC=<계획 문서> RT_F53_EXEC=<W4 f53.exec.sh> RT_LEDGER_SQL=<sid_ledger.sql> RT_REPO=<main 저장소> python rehearse_w3.py
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import judge  # noqa: E402
import rt  # noqa: E402

if os.listdir(rt.WORK):
    raise SystemExit(f'증거 폴더가 비어 있지 않다 — 새 폴더를 RT_WORK 로 준다: {rt.WORK}')

PLAN = os.environ['RT_PLAN_DOC']
F53_EXEC = os.environ['RT_F53_EXEC']
LEDGER_SQL = os.environ['RT_LEDGER_SQL']
REPO = os.environ['RT_REPO'].replace('\\', '/')
F53_EXEC_SHA = '2d5e5a9e905ec096a0bc4dfa08087a3090977c25d0f947cace498a3d1dba8a77'   # W4 ok-6298f5b manifest
LEDGER_SHA = '02a8585021fb2a1a52d9b3b56b60e957cb71b9ca3c11bf819595df15cf334f1c'     # 운영 설치본(F1 N18 줄)과 같은 해시
SID = 203
COIN = 'KRW-BTC'
P = 12_250_000
S = 5_000
P_RECOVER = 11_600_000          # 손절가 12,250,000 × 0.95 = 11,637,500 아래
SHIFT = '56 days 1 minute'
M = os.path.join(rt.WORK, 'f').replace('\\', '/')
E = os.path.join(rt.WORK, 'e').replace('\\', '/')
results = []


class Abort(Exception):
    pass


def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    rt.log(f'  {"PASS" if ok else "FAIL"} {name} {detail}')
    if not ok:
        raise Abort(name)


def note(name, detail):
    """멈춤 조건이 아닌 관측 — 값만 남긴다."""
    rt.log(f'  NOTE {name} {detail}')
    results.append(('(관측) ' + name, True, detail))


def one(sql):
    return rt.one(sql)


def utc(epoch):
    return time.strftime('%H:%M:%S', time.gmtime(epoch)) + 'Z'


def sha_file(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()


def restart_request():
    """RUNNING 세션에 시작 요청 — (HTTP 상태, 본문). 성공 응답도 상태 코드를 그대로 돌려준다."""
    req = urllib.request.Request(f'http://127.0.0.1:{rt.APP_PORT}/api/v1/trading/sessions/{SID}/start', method='POST', data=b'',
                                 headers={'Authorization': f'Bearer {rt.TOKEN}', 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


def api_json(method, path, body=None, timeout=15):
    req = urllib.request.Request(f'http://127.0.0.1:{rt.APP_PORT}{path}', method=method,
                                 headers={'Authorization': f'Bearer {rt.TOKEN}', 'Content-Type': 'application/json'},
                                 data=json.dumps(body).encode() if body is not None else (b'' if method == 'POST' else None))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


# ── 문서 블록(rehearse_sma20 과 같은 방식) ─────────────────────────────────────
def doc_block(marker, after=None):
    lines = open(PLAN, encoding='utf-8').read().splitlines()
    i = next(k for k, l in enumerate(lines) if l.startswith(marker))
    j = next(k for k in range(i, len(lines)) if lines[k].strip() == '```bash')
    e = next(k for k in range(j + 1, len(lines)) if lines[k].strip() == '```')
    return lines[j + 1:e]


DROP_F_VARS = ('cd ~/crypto_trader', 'DC="docker compose', 'M=~/start-sid203', 'PSQL="$DC exec')
DROP_F1 = ('{ git rev-parse HEAD', '[ "$(head -1 "$M/git-f1.txt")"', 'svc | tee', '[ "$(docker inspect',
           '$DC exec -T backend env', '[ "$(cat "$M/pool-f1.txt")"', 'sc | tee', 'python3 scripts/prospective/start_66.py',
           'echo "경보 종료코드', '{ [ "$AE" -le 1 ]', 'echo "== 경보', "{ date '+%F %T %Z'; curl", '[ "$(tail -1 "$M/telegram-f1.txt")"',
           '[ "$(cat ~/ops-scripts/SID_LEDGER_SHA)"', '{ echo "INSTALLED_SHA', '[ "$(cat "$M/install-f1.txt")"')
DROP_F2 = ('[ -s "$M/operator-confirm.txt" ]', "! grep -q '다른 것 있음'", '[ "$(TZ=Asia/Seoul date +%F)" = "$START_DATE" ]',
           'HM=$(TZ=Asia/Seoul date +%H%M)', 'sc | tee')
DROP_F5 = ('ls ~/ops-scripts/sid_ledger.sql',)
PRINT_ONLY = {'[ "$($PSQL -At -c "$EG" | tee "$M/eg-f1.txt")" = "$EGS" ] || stop "EG 가 EGS 와 다름 — eg-f1.txt 를 보고"':
              '[ "$($PSQL -At -c "$EG" | tee "$M/eg-f1.txt")" = "$EGS_LOCAL" ] || stop "EG 가 EGS_LOCAL 과 다름 — eg-f1.txt 를 보고"',
              '[ "$($PSQL -At -c "$BASE" | tee "$M/base-f1.txt")" = "$BASEX" ] || stop "기준 행이 생성 때와 다름"':
              '$PSQL -At -c "$BASE" | tee "$M/base-f1.txt"',
              '[ "$($PSQL -At -c "$EG")" = "$EGS" ] || stop "EG 가 EGS 와 다름"':
              '[ "$($PSQL -At -c "$EG")" = "$EGS_LOCAL" ] || stop "EG 가 EGS_LOCAL 과 다름"'}
# 로컬 치환(값이 아니라 위치만 바꾼다) — 바꾼 줄은 <label>-dropped.txt 에 남는다
REPLACE = [('E=~/end-sid203', f'E="{E}"'),
           ('git fetch origin && git show "$SHA"', f'git -C "{REPO}" show "$SHA"'),
           ('< ~/ops-scripts/sid_ledger.sql', f'< "{LEDGER_SQL}"'),
           ('sha256sum ~/ops-scripts/sid_ledger.sql', f'sha256sum "{LEDGER_SQL}"'),
           ('( M=$E; . "$E/f3-block.sh" )', '( M=$E; . "$E/f3-block.local.sh" )')]
# F5-3 은 F3 를 실행 중에 git 에서 꺼낸다 — 원문 f3-block.sh 와 그 해시 출력은 그대로 두고, 로컬 주소·토큰만 바꾼 사본을 실행한다
F3_LOCALIZE = ("sed -e 's#http://localhost:8080#http://127.0.0.1:%d#g' -e 's#^TOKEN=.*#TOKEN=%s#' "
               '"$E/f3-block.sh" > "$E/f3-block.local.sh"   # W3 로컬 치환 사본(원문·해시 출력 그대로)')


def localize(lines, drops, label):
    out, dropped = [], []
    for l in lines:
        s = l.strip()
        if any(s.startswith(d) for d in drops):
            dropped.append(l)
            continue
        if s in PRINT_ONLY:
            dropped.append(l + '   → ' + PRINT_ONLY[s])
            l = l.replace(s, PRINT_ONLY[s])
        for a, b in REPLACE:
            if a in l:
                dropped.append(l + '   → (위치 치환) ' + a + ' → ' + b)
                l = l.replace(a, b)
        l = l.replace('http://localhost:8080', f'http://127.0.0.1:{rt.APP_PORT}')
        l = l.replace("TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)", f'TOKEN={rt.TOKEN}')
        out.append(l)
    with open(os.path.join(rt.WORK, f'{label}-dropped.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(dropped) + '\n')
    rt.log(f'  {label}: {len(lines)}줄 중 {len(dropped)}줄 제외·변경(기록 {label}-dropped.txt)')
    return out


PSQL_PLAIN = 'docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader'


def write_clock_psql():
    """시계 주입 psql — 인자 안의 now() 를 (now() + interval SHIFT) 로 바꿔 넘긴다. 데이터·서버 시각은 그대로다."""
    p = os.path.join(rt.WORK, 'psql_clock.sh')
    with open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write('#!/bin/bash\n# W3 시계 주입: F5 조회의 now() 만 앞당긴다(RT_SHIFT). 기록 목적 — 운영에서 쓰지 않는다\n'
                # 치환 문자열 안의 작은따옴표는 매개변수 확장에서 따옴표로 처리돼 $RT_SHIFT 가 펼쳐지지 않았다(사전 확인) — 변수에 먼저 담는다
                'r="(now() + interval \'$RT_SHIFT\')"\n'
                'a=(); for x in "$@"; do a+=("${x//now()/$r}"); done\n'
                f'exec {PSQL_PLAIN} "${{a[@]}}"\n')
    return p.replace('\\', '/')


def run_bash(label, body_lines, keep_created_at, clock=False):
    psql = f'bash {CLOCK_PSQL}' if clock else PSQL_PLAIN   # 경로에 공백이 없다(RT_WORK) — 따옴표 없이 단어 분리
    pre = [f'SID={SID}; M="{M}"; mkdir -p "$M"', f'PSQL="{psql}"',
           f"export RT_SHIFT='{SHIFT}'",
           "EGS_LOCAL='1|0|1|true|-|-|0'   # 로컬: 실자금 LIVE 1 · DYNAMIC 0 · 기준 행 1 · 위험 한도 3.0/15.0 · 과거 비상 정지 없음"]
    fv = localize(doc_block('**F 변수**'), DROP_F_VARS, 'fvars')
    fv = [l.replace("'2026-10-30 04:16:58.044684'", f"'{keep_created_at}'") for l in fv]
    if clock:   # F 변수 안의 PSQL 정의는 이미 빠졌다(DROP_F_VARS) — 주입 psql 이 그대로 남는다
        pre.append('# 🔴 시계 주입 실행: 이 셸의 $PSQL 은 now() 를 +' + SHIFT + ' 로 바꾼다')
    path = os.path.join(rt.WORK, f'{label}.sh')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(pre + fv + body_lines) + '\n')
    env = dict(os.environ, MSYS_NO_PATHCONV='1', PYTHONIOENCODING='utf-8')
    r = subprocess.run(['bash', path], capture_output=True, text=True, encoding='utf-8', errors='replace', env=env)
    out = r.stdout + r.stderr
    with open(os.path.join(rt.WORK, f'{label}.out'), 'w', encoding='utf-8') as f:
        f.write(out + f'\n(종료코드 {r.returncode})\n')
    rt.log(f'  {label} 종료코드 {r.returncode}\n' + '\n'.join('    | ' + l for l in out.splitlines()))
    return r.returncode, out


# ── 자료·기록 ───────────────────────────────────────────────────────────────
def seed_cache(anchor, cur_hour):
    kmax = (cur_hour - anchor) // 3600 - 1
    rt.psql(f"DELETE FROM market_data_cache WHERE coin_pair = '{COIN}' AND timeframe = 'H1'")
    rt.psql("INSERT INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume) "
            f"SELECT to_timestamp({anchor}) + (k || ' hours')::interval, '{COIN}', 'H1', {P} + {S} * (k - 1), "
            f"{P} + {S} * k + 1000, {P} + {S} * (k - 1) - 1000, {P} + {S} * k, 100 "
            f"FROM generate_series({kmax - 519}, {kmax}) k")


def record():
    r = rt.psql_json(
        "SELECT json_build_object("
        f"'session', (SELECT row_to_json(s) FROM live_trading_session s WHERE id = {SID}), "
        f"'orders', (SELECT coalesce(json_agg(o ORDER BY o.id), '[]') FROM \"order\" o WHERE session_kind = 'LIVE' AND session_id = {SID}), "
        f"'positions', (SELECT coalesce(json_agg(p ORDER BY p.id), '[]') FROM position p WHERE session_kind = 'LIVE' AND session_id = {SID}), "
        "'cleanup', (SELECT coalesce(json_agg(c ORDER BY c.position_id, c.pending_at), '[]') FROM position_cleanup c), "
        "'discrepancies', (SELECT coalesce(json_agg(d ORDER BY d.id), '[]') FROM order_settlement_discrepancy d))")
    r['exchange'] = rt.exchange_orders()
    return r


_MISSING = object()


def differences(a, b, path=''):
    if isinstance(a, dict) and isinstance(b, dict):
        out = []
        for k in sorted(set(a) | set(b), key=str):
            out += differences(a.get(k, _MISSING), b.get(k, _MISSING), f'{path}.{k}')
        return out
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return [f'{path} 개수 {len(a)} → {len(b)}']
        return [d for i, (x, y) in enumerate(zip(a, b)) for d in differences(x, y, f'{path}[{i}]')]
    if a is _MISSING or b is _MISSING or a != b:
        return [f"{path}: {'(없음)' if a is _MISSING else repr(a)} → {'(없음)' if b is _MISSING else repr(b)}"]
    return []


def save(name, data):
    with open(os.path.join(rt.WORK, name), 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)


def market(price, age=0, fill=None, bid=None):
    rt.ctl('POST', '/_ctl/market', {
        'candles': {COIN: {'close': str(P), 'slope': str(S), 'anchor': ANCHOR, 'spread': '1000'}},
        'ticker': {COIN: {'price': str(price), 'trade_age_sec': age}},
        'orderbook': {COIN: {'bid': str(bid if bid is not None else P - 1000), 'size': '100'}},
        'fill_price': {COIN: str(fill if fill is not None else P)}, 'coin_currency': 'BTC',
        'markets': [{'market': COIN, 'korean_name': '비트코인', 'english_name': 'Bitcoin'}]})


PATTERNS = {
    'N19 폴백 건너뜀(0·음수)': '[§9] REST 폴백 시세 항목 건너뜀 — 가격이 아님',
    'N17 강제 갱신 가격 아님': 'REST 강제 갱신 시세가 가격이 아님',
    'N20 보강 건너뜀(0·음수)': '[시세 보강] 시세 항목 건너뜀 — 가격이 아님',
    'N16 처리기 가격 아님 무시': '가격이 아닌 실시간 시세 무시',
    'SL 미점검 경고': 'SL 미점검 경고',
    'REST 폴백 활성화': 'REST ticker fallback 활성화',
    '실시간 손절(WS 경로)': '실시간 손절 발동',
    'P0 연결 획득 시간 초과': 'Connection is not available',
    '누수 경고': 'Connection leak detection',
}


def log_counts(text):
    return {k: text.count(v) for k, v in PATTERNS.items()}


def xval(out, key):
    """psql -x 출력에서 key 의 값."""
    for l in out.splitlines():
        if l.split('|')[0].strip() == key and '|' in l:
            return l.split('|', 1)[1].strip()
    return None


def errors_in(text):
    return [l for l in text.splitlines() if ' ERROR ' in l]


# run3/run4: 하네스는 외부 인터넷을 막는다(rt.launch 프록시) — 업비트 웹소켓 재연결(분당 약 2줄)·뉴스 수집 실패 ERROR 는 이 환경 때문에 늘 생긴다.
# ④ 의 "새 ERROR 0줄" 은 이 두 종류만 따로 세어 기록하고, 그 밖의 ERROR 는 한 줄이라도 있으면 실패로 둔다.
# v1.134 §3-4: 공통 꼬리만으로 빼지 않고 실제 관측 원문만 뺀다 — judge.ENV_ERROR_PATTERNS(test_judge.py 가짜 입력 검증).
is_env_error = judge.is_env_error


def summary():
    errs = errors_in(rt.app_log()) if rt._app is not None else []
    rt.log(f'마지막 앱 로그 ERROR {len(errs)}줄' + ''.join('\n    ' + l[:220] for l in errs[:10]))
    passed, total, notes = judge.tally(results)   # v1.134 §3-3: 관측 메모는 단언 수와 분리
    rt.log(f'결과 단언 {passed}/{total} 통과 · 관측 {notes}')
    for name, ok, detail in results:
        print(('PASS ' if ok else 'FAIL ') + name + (' — ' + detail if detail else ''))
    sys.exit(0 if passed == total else 1)


# ── 시작 ─────────────────────────────────────────────────────────────────
os.makedirs(M, exist_ok=True)
os.makedirs(E, exist_ok=True)
CLOCK_PSQL = write_clock_psql()
now = time.time()
T = (int(now) // 3600 + 1) * 3600
if T - now < 14 * 60:
    T += 3600
ANCHOR = T - 3600
close_hour = time.gmtime(ANCHOR).tm_hour
rt.log(f'W3 리허설 — 마감 봉 {utc(ANCHOR)} 시작(마감 {utc(T)}), 회차 마감 {utc(T + 300)}, 시작 요청 {utc(T - 600)} 무렵')
PROPS = {'trading.capital-pool.stage': 'STAGE_1', 'trading.capital-pool.total-cap-krw': '100000',
         'trading.capital-pool.per-position-cap-krw': '20000', 'trading.capital-pool.max-concurrent-positions': '3',
         'upbit.market-data.base-url': f'http://127.0.0.1:{rt.DOUBLE_PORT}/v1',
         'harness.daily-close-hour-utc': str(close_hour)}

if rt.port_in_use(rt.DOUBLE_PORT) or rt.port_in_use(rt.APP_PORT):
    raise SystemExit(f'포트 {rt.DOUBLE_PORT}·{rt.APP_PORT} 중 사용 중 — 이전 대역·앱이 살아 있다. 정리한 뒤 다시')
check('⓪  W4 실행 사본 f53.exec.sh 해시 = 발급 manifest', sha_file(F53_EXEC) == F53_EXEC_SHA, sha_file(F53_EXEC)[:16])
check('⓪  장부 SQL 해시 = 운영 설치본 02a85850…', sha_file(LEDGER_SQL) == LEDGER_SHA, sha_file(LEDGER_SQL)[:16])
rt.fresh_db()
subprocess.run(['docker', 'start', 'cat-rt-redis'], capture_output=True)
rt.start_double(fresh=True)
try:
    market(P)

    # ① 새 DB · 생성 · F1 · F2
    rt.start_app(label='w3-create', props=PROPS)
    check('①  Flyway 적용(새 DB) · 최고 V94', one('SELECT count(*) FROM flyway_schema_history WHERE NOT success') == '0'
          and one('SELECT max(version::int) FROM flyway_schema_history WHERE success') == '94',
          'max V' + str(one('SELECT max(version::int) FROM flyway_schema_history WHERE success')))
    rt.psql("UPDATE risk_config SET max_daily_loss_pct = 3.0, max_portfolio_drawdown_pct = 15.0 WHERE id = (SELECT max(id) FROM risk_config)")
    seed_cache(ANCHOR, (int(time.time()) // 3600) * 3600)
    rt.psql(f"SELECT setval(pg_get_serial_sequence('live_trading_session', 'id'), {SID - 1})")
    body_line = next(l for l in open(PLAN, encoding='utf-8') if l.strip().startswith("-d '{\"strategyType\":\"DAILY_SMA20\""))
    body = json.loads(body_line.strip()[len("-d '"):body_line.strip().rindex("'")])
    created = api_json('POST', '/api/v1/trading/sessions', body)
    rt.log(f'  생성 응답: {json.dumps(created, ensure_ascii=False)[:300]}')
    check('①  세션 생성 — SID 203 · CREATED', one("SELECT string_agg(id || ':' || status, ',') FROM live_trading_session") == f'{SID}:CREATED')
    created_at = one(f"SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') FROM live_trading_session WHERE id = {SID}")
    rt.kill_app()
    rt.start_app(label='w3-run', props=PROPS)
    check('①  마감 시각 이동 등록(시험 빌드)', f'마감 봉 = {close_hour}:00 UTC' in rt.app_log())
    wait_s = T - 1200 - time.time()
    if wait_s > 0:
        rt.log(f'  F1 시각({utc(T - 1200)})까지 {wait_s:.0f}초 대기')
        time.sleep(wait_s)
    rc, out = run_bash('F1-local', localize(doc_block('**F1.'), DROP_F1, 'F1'), created_at)
    eg = open(os.path.join(M, 'eg-f1.txt'), encoding='utf-8').read().strip() if os.path.exists(os.path.join(M, 'eg-f1.txt')) else None
    check('①  F1 통과 — 종료코드 0 · ok-F1 · EG 로컬 기대', rc == 0 and os.path.exists(os.path.join(M, 'ok-F1')) and eg == '1|0|1|true|-|-|0', f'rc={rc} EG={eg}')
    note('①  F1 BTC 입력(btc-f1.txt)', open(os.path.join(M, 'btc-f1.txt'), encoding='utf-8').read().strip() if os.path.exists(os.path.join(M, 'btc-f1.txt')) else '없음')
    wait_s = T - 600 - time.time()
    if wait_s > 0:
        rt.log(f'  시작 요청 시각({utc(T - 600)})까지 {wait_s:.0f}초 대기')
        time.sleep(wait_s)
    rc, out = run_bash('F2-local', localize(doc_block('**F2.'), DROP_F2, 'F2'), created_at)
    check('①  F2 시작 요청 1회 — start-sent', rc == 0 and os.path.exists(os.path.join(M, 'start-sent')))
    time.sleep(60)
    start_check = [l for l in open(PLAN, encoding='utf-8').read().split('- 시작 확인(1분 뒤')[1].split('```bash')[1].split('```')[0].splitlines()
                   if l.strip() and not l.strip().startswith('sc |')]
    rc, out = run_bash('F2-check', start_check, created_at)
    check('①  시작 확인 — RUNNING · 203:RUNNING', 'RUNNING' in out and f'{SID}:RUNNING' in out)

    # ② 마감 봉 BUY → 승인·체결
    check('②  마감 봉 BUY 판단', rt.wait_until(lambda: one(
        f"SELECT count(*) FROM strategy_log WHERE session_type = 'LIVE' AND session_id = {SID} AND signal = 'BUY'") != '0',
        max(60, T - time.time() + 240), 5, 'BUY'))
    check('②  회차 마감 뒤 매수 체결 — FILLED · 0.0016 · 19,600 · 9.8 · 잔액 590.2', rt.wait_until(lambda: one(
        f"SELECT o.state = 'FILLED' AND o.filled_quantity = 0.0016 AND o.applied_executed_funds = 19600 AND o.applied_paid_fee = 9.8 "
        f"AND l.available_krw = 590.2 FROM \"order\" o JOIN live_trading_session l ON l.id = o.session_id WHERE o.session_id = {SID} AND o.side = 'BUY'") == 't',
        max(120, T + 300 - time.time() + 240), 3, '매수 체결'),
        one(f"SELECT state || ':' || filled_quantity || ':' || created_at FROM \"order\" WHERE session_id = {SID}"))
    check('②  승인 시각 ≥ 회차 마감', one(f"SELECT (min(created_at) >= to_timestamp({T + 300}))::text FROM \"order\" WHERE session_id = {SID}") == 'true')
    sl = one(f"SELECT coalesce(stop_loss_price::text, '-') || ' / ' || avg_price FROM position WHERE session_id = {SID} AND status = 'OPEN'")
    note('②  포지션 손절가 / 평균가', sl)
    rc, out = run_bash('F3-hold', localize(doc_block('**F3.'), (), 'F3-hold'), created_at)
    check('②  F3 — ✅ 매수 1건 보유 중', '✅ 판정: 매수 1건 보유 중' in out)
    note('②  F3 입력 판정', ' / '.join(l for l in out.splitlines() if '입력 판정' in l))

    # ③ RUNNING 재시작 요청
    # run3: 체결 직후 PositionLimitGuard 정기 평가가 최고 평가액(peak_value_*)을 처음 기록(늘어날 때만 갱신)하는 것이 비교 구간에 들어와
    # 거짓 실패가 났다 — 첫 기록을 기다린 뒤 비교한다(대역 가격 고정이라 이후 변화 없음). 비교 항목은 줄이지 않는다.
    # v1.134 §3-3: 대기 실패면 NOTE 로 계속하지 않고 여기서 멈춘다.
    check('③  비교 전 최고 평가액 첫 기록 대기', rt.wait_until(lambda: one(
        f"SELECT (peak_value_krw IS NOT NULL)::text FROM position WHERE session_id = {SID} AND status = 'OPEN'") == 'true',
        120, 3, '최고 평가액 첫 기록'), one(
        f"SELECT coalesce(peak_value_krw::text, '-') || ' @ ' || coalesce(peak_value_at::text, '-') FROM position WHERE session_id = {SID} AND status = 'OPEN'"))
    before = record()
    status, body = restart_request()
    time.sleep(10)
    d = differences(before, record())
    # v1.134 §3-1: 기록 차이 0 만으로 통과시키지 않는다 — 실제 HTTP 409·거절 사유를 함께 단언
    check('③  RUNNING 재시작 요청 — HTTP 409 거절 · 기록 변화 없음', judge.restart_rejected(status, body, d),
          f'HTTP {status} {body[:300]} · 차이 {d[:5]}')

    # ④ 시세 이상(0 → -5)
    log_before = rt.app_log()
    c0 = log_counts(log_before)
    e0 = len(errors_in(log_before))
    t_anom = time.time()
    market(0)
    rt.log('  ④ 대역 ticker 0 — 130초')
    time.sleep(130)
    market(-5)
    rt.log('  ④ 대역 ticker -5 — 130초')
    time.sleep(130)
    r4 = record()
    log4 = rt.app_log()
    c4 = {k: v - c0[k] for k, v in log_counts(log4).items()}
    save('anomaly-counts.json', c4)
    check('④  시세 이상 동안 매도 0 · 포지션 OPEN · 주문 1건(매수) · 거래소 bid 1', [p['status'] for p in r4['positions']] == ['OPEN']
          and len(r4['orders']) == 1 and [c['side'] for c in r4['exchange']['creates']] == ['bid'],
          str([(o['side'], o['state']) for o in r4['orders']]))
    check('④  0·음수 거름 고정 WARN 관측(N19 폴백 또는 N17 강제 갱신 또는 N20 보강 중 하나 이상)',
          c4['N19 폴백 건너뜀(0·음수)'] + c4['N17 강제 갱신 가격 아님'] + c4['N20 보강 건너뜀(0·음수)'] > 0, json.dumps(c4, ensure_ascii=False))
    check('④  P0 연결 획득 시간 초과 0 · 누수 경고 0', c4['P0 연결 획득 시간 초과'] == 0 and c4['누수 경고'] == 0)
    new_all = errors_in(log4)[e0:]
    new_err = [l for l in new_all if not is_env_error(l)]
    note('④  구간 환경 ERROR(외부 차단: 웹소켓 재연결·뉴스 수집) 줄 수', str(len(new_all) - len(new_err)))
    check('④  시세 이상 구간 새 ERROR 0줄(환경 ERROR 제외)', not new_err, ' | '.join(l[:160] for l in new_err[:5]))
    note('④  관측 줄 수(구간)', json.dumps(c4, ensure_ascii=False))

    # ⑤ 회복 — 손절선 아래 신선한 정상 가격
    market(P_RECOVER, fill=P_RECOVER, bid=P_RECOVER - 1000)
    rt.log(f'  ⑤ 대역 ticker {P_RECOVER} — 보호 매도 대기')
    check('⑤  보호 매도 1회 — 포지션 CLOSED(STOP_LOSS) · 매도 FILLED', rt.wait_until(lambda: one(
        f"SELECT status || ':' || coalesce(exit_reason, '-') FROM position WHERE session_id = {SID}") == 'CLOSED:STOP_LOSS'
        and one(f"SELECT count(*) FROM \"order\" WHERE session_id = {SID} AND side = 'SELL' AND state = 'FILLED'") == '1', 300, 3, '손절'),
        one(f"SELECT string_agg(side || ':' || state, ',' ORDER BY id) FROM \"order\" WHERE session_id = {SID}"))
    time.sleep(20)
    r5 = record()
    check('⑤  세션 RUNNING 유지 · 거래소 bid 1 · ask 1(중복 매도 없음)', r5['session']['status'] == 'RUNNING'
          and [c['side'] for c in r5['exchange']['creates']] == ['bid', 'ask'], str([c['side'] for c in r5['exchange']['creates']]))
    c5 = {k: v - c0[k] for k, v in log_counts(rt.app_log()).items()}
    note('⑤  손절 경로 관측(누적)', json.dumps(c5, ensure_ascii=False))
    rc, out = run_bash('F3-exit', localize(doc_block('**F3.'), (), 'F3-exit'), created_at)
    check('⑤  F3 — ✅ 매수 뒤 정상 사유 청산 · 거래소 BTC 증감 0', '✅ 판정: 매수 뒤 정상 사유' in out and '거래소 BTC 증감 0' in out)

    # ⑥ 56일 종료 — 시계 주입
    f5v = localize(doc_block('**F5. 56일'), DROP_F5, 'F5-vars')
    f51 = localize(doc_block('**F5-1.'), (), 'F5-1')
    f52 = localize(doc_block('**F5-2.'), (), 'F5-2')
    rc, out = run_bash('F5-1-before', f5v + f51, created_at)
    check('⑥  F5-1 주입 전 — due f(아직 56일 전)', rc == 0 and xval(out, 'due') == 'f', f'due={xval(out, "due")}')
    rc, out = run_bash('F5-2-before', f5v + f52, created_at)
    check('⑥  F5-2 주입 전 — "아직 실제 시작 + 56일 전" 멈춤 · stop-sent 없음 · RUNNING',
          rc != 0 and '아직 실제 시작 + 56일 전' in out and not os.path.exists(os.path.join(E, 'stop-sent'))
          and one(f'SELECT status FROM live_trading_session WHERE id = {SID}') == 'RUNNING', f'rc={rc}')
    rc, out = run_bash('F5-1-shifted', f5v + f51, created_at, clock=True)
    ends = [l.split('|')[-1].strip() for l in out.splitlines() if 'end_kst' in l]
    started_kst = one(f"SELECT to_char(started_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS.US') FROM live_trading_session WHERE id = {SID}")
    # v1.134 §3-2: 종료값 0 과 실제 started_at + 56일 비교를 함께
    check('⑥  F5-1 주입 뒤 — 종료 0 · due t · end_kst 모두 = 실제 started_at + 56일', judge.f51_shifted_ok(rc, xval(out, 'due'), ends, started_kst),
          f'rc={rc} started {started_kst} end_kst {ends}')
    before_stop = record()
    rc, out = run_bash('F5-2-shifted', f5v + f52, created_at, clock=True)
    check('⑥  F5-2 주입 뒤 — 정지 요청 1회(stop-sent)', rc == 0 and os.path.exists(os.path.join(E, 'stop-sent')), f'rc={rc}')
    check('⑥  세션 STOPPED', rt.wait_until(lambda: one(f'SELECT status FROM live_trading_session WHERE id = {SID}') == 'STOPPED', 60, 2, 'STOPPED'))
    rc, out = run_bash('F5-2-again', f5v + f52, created_at, clock=True)
    check('⑥  F5-2 두 번째 — "이미 정지 요청을 보냈다" 멈춤', rc != 0 and '이미 정지 요청을 보냈다' in out, f'rc={rc}')
    time.sleep(90)
    f53 = localize(open(F53_EXEC, encoding='utf-8').read().splitlines(), (), 'F5-3')
    k = next(i for i, l in enumerate(f53) if l.startswith('head -1 "$E/f3-block.sh"'))
    f53.insert(k + 1, F3_LOCALIZE % (rt.APP_PORT, rt.TOKEN))
    with open(os.path.join(rt.WORK, 'F5-3-dropped.txt'), 'a', encoding='utf-8') as f:
        f.write('(삽입, 해시 출력 줄 다음) ' + f53[k + 1] + '\n')
    rc, out = run_bash('F5-3-w4exec', f5v + f53, created_at)
    check('⑥  F5-3(W4 실행 사본) — F3 추출 해시 bb86fb39 · ⛔ STOPPED · 거래소 BTC 증감 0 · DB 정리 조건 충족',
          judge.f53_ok(rc, out, 'bb86fb397da00523f564a8f02a1960c98bb1ac03887629fe74372990929fa2bb'), f'rc={rc}')
    krw_line = [l for l in out.splitlines() if l.startswith('거래소 KRW 증감')]
    note('⑥  F5-3 KRW 대조 줄', ' / '.join(krw_line))
    rc, out = run_bash('F5-4-ledger', f5v + localize(doc_block('**F5-4.'), (), 'F5-4'), created_at)
    check('⑥  F5-4 장부 실행 — 종료코드 0 · ERROR 없음', rc == 0 and 'ERROR' not in out, f'rc={rc}')
    note('⑥  F5-4 balance_diff 줄', ' / '.join(l for l in out.splitlines() if 'balance_diff' in l)[:300])
    r6 = record()
    check('⑦  거래소 주문 호출 — 매수 1 · 매도 1(정지가 새 매도를 내지 않음)', [c['side'] for c in r6['exchange']['creates']] == ['bid', 'ask'],
          str([c['side'] for c in r6['exchange']['creates']]))
    d = differences(before_stop, r6)
    note('⑦  정지 전후 기록 차이', '; '.join(d[:12]))

    # ⑦ 강제 종료 · 재기동 — 기록 그대로
    save('final-before.json', r6)
    rt.kill_app()
    rt.start_app(label='w3-final-restart', props=PROPS)
    time.sleep(90)
    after = record()
    save('final-after.json', after)
    d = differences(r6, after)
    check('⑦  강제 종료·재기동 뒤 기록 전체 그대로', d == [], f'차이 {len(d)}' + ''.join('\n      ' + x for x in d[:20]))
    allc = log_counts(open(os.path.join(rt.WORK, sorted(f for f in os.listdir(rt.WORK) if f.startswith('app-') and 'w3-run' in f)[0]), encoding='utf-8', errors='replace').read())
    check('⑦  P0 — 실행 로그 연결 획득 시간 초과 0', allc['P0 연결 획득 시간 초과'] == 0, json.dumps(allc, ensure_ascii=False))
    rt.kill_app()
    rt.stop_double()
except Abort as e:
    rt.log(f'🔴 필수 단계 실패로 중단: {e}')
except Exception as e:
    rt.log('🔴 실행기 예외로 중단:\n' + traceback.format_exc())
    results.append((f'실행기 예외 {type(e).__name__}', False, str(e)))
finally:
    rt.kill_all()
    rt.stop_double()
summary()
