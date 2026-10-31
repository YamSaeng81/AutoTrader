"""SMA20 격리 리허설 (2026-10-30, LIMITED_LIVE_TEST_PLAN §5-9 2단계) — 운영 배포본 코드로 12-01 당일 흐름을 한 번 이어 본다.

실제 앱 프로세스 · PostgreSQL(cat-rt-pg) · 별도 프로세스 거래소 대역에서:
  시작 전 확인(문서 F1 의 DB·API 줄 그대로) → 세션 생성 API(문서의 생성 본문 그대로, SID 203) → 정시 10분 전 시작 API 1회(F2)
  → 정시 마감 봉 BUY → 회차 마감(정시 + 5분) 뒤 승인·매수 → 거래소 접수 직후 강제 종료(AFTER_EXCHANGE_ACCEPT)
  → 재기동 · 식별자 조회로 정산 → F3 판정(문서 블록 그대로) → 정지 API 1회 → 청산 매도 체결 · 정리 완료
  → F3 다시(잔여량 대조) → 강제 종료 · 재기동 뒤 변화 없음.

운영과 다른 점(문서에 적는다): 일봉 마감 시각만 옮긴다(-Dharness.daily-close-hour-utc, 시험 빌드 전용 — 규칙은 같다).
거래소는 대역이다(체결가 = 시세, 시장가 매수는 예약의 98% 체결 · 수수료 매수 0.05% · 매도 0.04%). 외부 인터넷은 막는다.
F1 의 운영 전용 줄(git HEAD · 이미지 · backend env · 66세션 · 경보 · 텔레그램)과 F2 의 날짜·08:50 창·운영자 확인 파일 줄은
실행하지 않고, 뺀 줄을 로그에 그대로 남긴다.

실행: RT_WORK=<증거 폴더> RT_PLAN_DOC=<main 의 docs/LIMITED_LIVE_TEST_PLAN.md> python rehearse_sma20.py
"""
import json
import os
import subprocess
import sys
import time
import traceback
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rt  # noqa: E402

# 🔴 (2026-10-30 리뷰) 비어 있지 않은 증거 폴더는 거부한다 — 1차 폴더 이름 바꾸기가 실패해 2차가 같은 폴더를 다시 써 1차 앱 로그가 덮였다.
if os.listdir(rt.WORK):
    raise SystemExit(f'증거 폴더가 비어 있지 않다 — 새 폴더를 RT_WORK 로 준다: {rt.WORK}')

PLAN = os.environ['RT_PLAN_DOC']
SID = 203
COIN = 'KRW-BTC'
P = 12_250_000          # 마감 봉 종가 = 체결가. 매수 19,600 ÷ P = 0.0016 BTC 로 나누어떨어진다
S = 5_000               # 시간당 종가 상승 — 단위 시험(uptrend)과 같은 기울기
M = os.path.join(rt.WORK, 'f').replace('\\', '/')
results = []


class Abort(Exception):
    pass


def check(name, ok, detail=''):
    """🔴 (2026-10-30 리뷰) 모든 점검은 다음 단계의 전제다 — 실패하면 그 자리에서 멈춘다(정리는 finally 가 한다)."""
    results.append((name, bool(ok), detail))
    rt.log(f'  {"PASS" if ok else "FAIL"} {name} {detail}')
    if not ok:
        raise Abort(name)


def one(sql):
    return rt.one(sql)


def utc(epoch):
    return time.strftime('%H:%M:%S', time.gmtime(epoch)) + 'Z'


def api_json(method, path, body=None, timeout=15):
    req = urllib.request.Request(f'http://127.0.0.1:{rt.APP_PORT}{path}', method=method,
                                 headers={'Authorization': f'Bearer {rt.TOKEN}', 'Content-Type': 'application/json'},
                                 data=json.dumps(body).encode() if body is not None else (b'' if method == 'POST' else None))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


# ── 문서 블록 ──────────────────────────────────────────────────────────────
def doc_block(marker):
    """문서에서 marker 로 시작하는 줄 다음의 첫 ```bash 블록 본문."""
    lines = open(PLAN, encoding='utf-8').read().splitlines()
    i = next(k for k, l in enumerate(lines) if l.startswith(marker))
    j = next(k for k in range(i, len(lines)) if lines[k].strip() == '```bash')
    e = next(k for k in range(j + 1, len(lines)) if lines[k].strip() == '```')
    return lines[j + 1:e]


DROP_F_VARS = ('cd ~/crypto_trader', 'DC="docker compose', 'M=~/start-sid203', 'PSQL="$DC exec')
DROP_F1 = ('{ git rev-parse HEAD', '[ "$(head -1 "$M/git-f1.txt")"', 'svc | tee', '[ "$(docker inspect',
           '$DC exec -T backend env', '[ "$(cat "$M/pool-f1.txt")"', 'sc | tee', 'python3 scripts/prospective/start_66.py',
           'echo "경보 종료코드', '{ [ "$AE" -le 1 ]', 'echo "== 경보', "{ date '+%F %T %Z'; curl", '[ "$(tail -1 "$M/telegram-f1.txt")"')
DROP_F2 = ('[ -s "$M/operator-confirm.txt" ]', "! grep -q '다른 것 있음'", '[ "$(TZ=Asia/Seoul date +%F)" = "$START_DATE" ]',
           'HM=$(TZ=Asia/Seoul date +%H%M)', 'sc | tee')
# 운영 기대값과 비교하는 줄. BASE 는 생성 시각이 달라 값만 출력한다. EG 는 로컬 기대값(EGS_LOCAL — 과거 비상 정지 목록만 운영과
# 다르다)과 비교해 멈춘다(2026-10-30 2차 실행: EG 를 값 출력만 하게 바꿨다가 위험 한도 불일치 false 를 지나쳤다 — 그 판단을 되돌렸다).
PRINT_ONLY = {'[ "$($PSQL -At -c "$EG" | tee "$M/eg-f1.txt")" = "$EGS" ] || stop "EG 가 EGS 와 다름 — eg-f1.txt 를 보고"':
              '[ "$($PSQL -At -c "$EG" | tee "$M/eg-f1.txt")" = "$EGS_LOCAL" ] || stop "EG 가 EGS_LOCAL 과 다름 — eg-f1.txt 를 보고"',
              '[ "$($PSQL -At -c "$BASE" | tee "$M/base-f1.txt")" = "$BASEX" ] || stop "기준 행이 생성 때와 다름"':
              '$PSQL -At -c "$BASE" | tee "$M/base-f1.txt"',
              '[ "$($PSQL -At -c "$EG")" = "$EGS" ] || stop "EG 가 EGS 와 다름"':
              '[ "$($PSQL -At -c "$EG")" = "$EGS_LOCAL" ] || stop "EG 가 EGS_LOCAL 과 다름"'}


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
        l = l.replace('http://localhost:8080', f'http://127.0.0.1:{rt.APP_PORT}')
        l = l.replace("TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)", f'TOKEN={rt.TOKEN}')
        out.append(l)
    with open(os.path.join(rt.WORK, f'{label}-dropped.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(dropped) + '\n')
    rt.log(f'  {label}: 문서 {len(lines)}줄 중 {len(dropped)}줄 제외·변경(기록 {label}-dropped.txt)')
    return out


def run_bash(label, body_lines, keep_created_at):
    pre = [f'SID={SID}; M="{M}"; mkdir -p "$M"',
           'PSQL="docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader"',
           "EGS_LOCAL='1|0|1|true|-|-|0'   # 로컬: 실자금 LIVE 1 · DYNAMIC 0 · 기준 행 1 · 위험 한도 3.0/15.0 · 과거 비상 정지 없음"]
    fv = localize(doc_block('**F 변수**'), DROP_F_VARS, 'fvars')
    fv = [l.replace("'2026-10-30 04:16:58.044684'", f"'{keep_created_at}'") for l in fv]
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


# ── 자료 ──────────────────────────────────────────────────────────────────
def seed_cache(anchor, cur_hour):
    """닫힌 H1 520봉 — 종가(c) = P + S × (c − anchor)/1h. 대역 거래소가 내는 봉과 같은 식이다."""
    kmax = (cur_hour - anchor) // 3600 - 1
    rt.psql(f"DELETE FROM market_data_cache WHERE coin_pair = '{COIN}' AND timeframe = 'H1'")
    rt.psql("INSERT INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume) "
            f"SELECT to_timestamp({anchor}) + (k || ' hours')::interval, '{COIN}', 'H1', {P} + {S} * (k - 1), "
            f"{P} + {S} * k + 1000, {P} + {S} * (k - 1) - 1000, {P} + {S} * k, 100 "
            f"FROM generate_series({kmax - 519}, {kmax}) k")


def snapshot():
    """⑥ 로그용 한 줄 요약 — ⑦ 대조에는 쓰지 않는다(record() 를 쓴다)."""
    r = record()
    s = r['session']
    return (f"{s['status']} {s['available_krw']} # 주문 " + ','.join(f"{o['side']}:{o['state']}" for o in r['orders'])
            + ' # 포지션 ' + ','.join(f"{p['status']}:{p['exit_reason']}" for p in r['positions'])
            + f" # 거래소 KRW {r['exchange']['krw']} BTC {r['exchange']['coin']}")


def record():
    """🔴 (2026-10-31 리뷰) ⑦ 대조 기록 — 행 전체를 JSON 으로 읽는다.
    종전 snapshot() 은 rt.one() 이 '|' 에서 잘라 'STOPPED' 만 비교했다(4차 ⑦ — 잔액·주문·포지션이 달라도 통과)."""
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
    """두 기록의 다른 곳을 모두 돌려준다(경로: 이전 → 이후)."""
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


def check_comparator(base):
    """가짜 입력으로 비교기가 차이를 잡는지 본다 — 같은 기록은 차이 0, 바꾼 칸은 그 경로로 잡힌다."""
    import copy
    check('⑦  비교기 — 같은 기록은 차이 0', differences(base, copy.deepcopy(base)) == [])
    fakes = [('잔액', lambda r: r['session'].__setitem__('available_krw', 20182.37), '.session.available_krw'),
             ('주문 상태', lambda r: r['orders'][-1].__setitem__('state', 'SUBMITTED'), '.orders[1].state'),
             ('포지션 상태', lambda r: r['positions'][0].__setitem__('status', 'OPEN'), '.positions[0].status'),
             ('정리 대기', lambda r: r['cleanup'].append({'position_id': 0, 'done_at': None}), '.cleanup 개수'),
             ('거래소 BTC', lambda r: r['exchange'].__setitem__('coin', '0.0016'), '.exchange.coin')]
    for name, change, where in fakes:
        fake = copy.deepcopy(base)
        change(fake)
        d = differences(base, fake)
        check(f'⑦  비교기 — 가짜 {name} 변경을 잡는다', len(d) == 1 and d[0].startswith(where), '; '.join(d))


def check_stopped_state(step):
    """정지·청산 뒤 기대 상태 — 잔액 20,182.36 · 매수·매도 각 1건 FILLED · CLOSED · 활성 주문·정리 대기·미해결 불일치 0 · 대역 BTC 0."""
    r = record()
    s, orders, pos, x = r['session'], r['orders'], r['positions'], r['exchange']
    check(f'{step}  세션 STOPPED · 잔액 20,182.36', s['status'] == 'STOPPED' and abs(float(s['available_krw']) - 20182.36) < 1e-9,
          f"{s['status']} {s['available_krw']}")
    check(f'{step}  주문 매수 1 · 매도 1, 둘 다 FILLED', [(o['side'], o['state']) for o in orders] == [('BUY', 'FILLED'), ('SELL', 'FILLED')],
          str([(o['side'], o['state']) for o in orders]))
    check(f'{step}  포지션 1개 CLOSED(FORCED_STOP)', [(p['status'], p['exit_reason']) for p in pos] == [('CLOSED', 'FORCED_STOP')],
          str([(p['status'], p['exit_reason']) for p in pos]))
    active = [o['id'] for o in orders if o['state'] in ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED')]
    pending = [c for c in r['cleanup'] if c['done_at'] is None]
    order_ids = {o['id'] for o in orders}
    unresolved = [d for d in r['discrepancies'] if d['order_id'] in order_ids and d['resolved_at'] is None]
    check(f'{step}  활성 주문 0 · 정리 대기 0 · 미해결 정산 불일치 0', not active and not pending and not unresolved,
          f'활성 {active} · 정리 대기 {len(pending)} · 미해결 {len(unresolved)}')
    check(f'{step}  거래소 대역 — 매수 1 · 매도 1, BTC 0, KRW 999,982.36', [c['side'] for c in x['creates']] == ['bid', 'ask']
          and float(x['coin']) == 0 and abs(float(x['krw']) - 999982.36) < 1e-6, f"KRW {x['krw']} BTC {x['coin']}")
    return r


def final_restart(props, done):
    """⑦ 강제 종료(또는 이미 종료된 상태)에서 재기동 → 기록 전체가 그대로인지 본다."""
    save('final-before.json', done)
    check_comparator(done)
    rt.kill_app()
    rt.start_app(label='sma20-final-restart', props=props)
    time.sleep(90)
    after = record()
    save('final-after.json', after)
    d = differences(done, after)
    check('⑦  강제 종료·재기동 뒤 세션·주문·포지션·정리·정산 불일치·거래소 기록 전체 그대로', d == [],
          f'차이 {len(d)}' + ''.join('\n      ' + x for x in d[:30]))


def summary():
    errs = [l for l in rt.app_log().splitlines() if ' ERROR ' in l] if rt._app is not None else []
    rt.log(f'마지막 앱 로그 ERROR {len(errs)}줄' + ''.join('\n    ' + l[:220] for l in errs[:10]))
    passed = sum(1 for _, ok, _ in results if ok)
    rt.log(f'결과 {passed}/{len(results)} 통과')
    for name, ok, detail in results:
        print(('PASS ' if ok else 'FAIL ') + name + (' — ' + detail if detail else ''))
    sys.exit(0 if passed == len(results) else 1)


# ── ⑦ 만 다시(2026-10-31 리뷰) ─────────────────────────────────────────────
# RT_FINAL_FROM=<4차 증거 폴더> — 새 DB·①~⑥ 없이, 그 실행이 남긴 DB(cat-rt-pg)·대역 상태(사본)로 정지 상태를 다시 확인하고
# 기록 전체를 파일로 남긴 뒤 재기동 대조만 한다. 원래 폴더의 파일은 바꾸지 않는다.
FINAL_FROM = os.environ.get('RT_FINAL_FROM')
if FINAL_FROM:
    import shutil
    if rt.port_in_use(rt.DOUBLE_PORT) or rt.port_in_use(rt.APP_PORT):
        raise SystemExit(f'포트 {rt.DOUBLE_PORT}·{rt.APP_PORT} 중 사용 중 — 이전 대역·앱이 살아 있다. 정리한 뒤 다시')
    with open(os.path.join(FINAL_FROM, 'exchange_state.json'), encoding='utf-8') as f:
        prev_anchor = json.load(f)['market']['candles'][COIN]['anchor']
    props = {'trading.capital-pool.stage': 'STAGE_1', 'trading.capital-pool.total-cap-krw': '100000',
             'trading.capital-pool.per-position-cap-krw': '20000', 'trading.capital-pool.max-concurrent-positions': '3',
             'upbit.market-data.base-url': f'http://127.0.0.1:{rt.DOUBLE_PORT}/v1',
             'harness.daily-close-hour-utc': str(time.gmtime(prev_anchor).tm_hour)}
    rt.log(f'⑦ 만 다시 — 원본 {FINAL_FROM}, 마감 시각(UTC) {props["harness.daily-close-hour-utc"]}시')
    rt.dump_db('before-final-restart')
    shutil.copyfile(os.path.join(FINAL_FROM, 'exchange_state.json'), os.path.join(rt.WORK, 'exchange_state.json'))
    subprocess.run(['docker', 'start', 'cat-rt-redis'], capture_output=True)
    rt.start_double(fresh=False)
    try:
        rt.log('  종전 비교값(rt.one, 잘림): ' + repr(one(
            f"SELECT status || '|' || available_krw FROM live_trading_session WHERE id = {SID}")))
        final_restart(props, check_stopped_state('②'))
    except Abort as e:
        rt.log(f'🔴 필수 단계 실패로 중단: {e}')
    except Exception as e:
        rt.log('🔴 실행기 예외로 중단:\n' + traceback.format_exc())
        results.append((f'실행기 예외 {type(e).__name__}', False, str(e)))
    finally:
        rt.kill_all()
        rt.stop_double()
    summary()


# ── 시작 ─────────────────────────────────────────────────────────────────
os.makedirs(M, exist_ok=True)
now = time.time()
T = (int(now) // 3600 + 1) * 3600
if T - now < 14 * 60:
    T += 3600
anchor = T - 3600                   # 마감 봉 = anchor 에 시작한 H1 봉(T 에 닫힌다)
close_hour = time.gmtime(anchor).tm_hour
rt.log(f'SMA20 격리 리허설 — 마감 봉 {utc(anchor)} 시작(마감 {utc(T)}), 회차 마감 {utc(T + 300)}, 시작 요청 {utc(T - 600)} 무렵')
PROPS = {'trading.capital-pool.stage': 'STAGE_1', 'trading.capital-pool.total-cap-krw': '100000',
         'trading.capital-pool.per-position-cap-krw': '20000', 'trading.capital-pool.max-concurrent-positions': '3',
         'upbit.market-data.base-url': f'http://127.0.0.1:{rt.DOUBLE_PORT}/v1',
         'harness.daily-close-hour-utc': str(close_hour)}

# 🔴 (2026-10-30 2차) 1차의 대역이 살아 있는 채 2차가 같은 포트에 하나 더 띄웠다(Windows 는 같은 포트를 함께 연다) — 시작 전에 막는다.
if rt.port_in_use(rt.DOUBLE_PORT) or rt.port_in_use(rt.APP_PORT):
    raise SystemExit(f'포트 {rt.DOUBLE_PORT}·{rt.APP_PORT} 중 사용 중 — 이전 대역·앱이 살아 있다. 정리한 뒤 다시')
rt.fresh_db()
subprocess.run(['docker', 'start', 'cat-rt-redis'], capture_output=True)
rt.start_double(fresh=True)
try:
    rt.ctl('POST', '/_ctl/market', {
        'candles': {COIN: {'close': str(P), 'slope': str(S), 'anchor': anchor, 'spread': '1000'}},
        'ticker': {COIN: {'price': str(P), 'trade_age_sec': 0}},
        'orderbook': {COIN: {'bid': str(P - 1000), 'size': '100'}},
        'fill_price': {COIN: str(P)}, 'coin_currency': 'BTC',
        'markets': [{'market': COIN, 'korean_name': '비트코인', 'english_name': 'Bitcoin'}]})

    # ① 새 DB — Flyway, 세션 생성(문서의 생성 본문 그대로)
    rt.start_app(label='sma20-create', props=PROPS)
    check('①  Flyway 적용(새 DB)', one('SELECT count(*) FROM flyway_schema_history WHERE NOT success') == '0',
          'max V' + str(one('SELECT max(version::int) FROM flyway_schema_history WHERE success')))
    check('①  공유 풀 등록값(로그) STAGE_1 · 100000 · 20000 · 3',
          '공유 풀 한도' in rt.app_log() and 'STAGE_1' in rt.app_log() and '총상한=100000' in rt.app_log()
          and '포지션당=20000' in rt.app_log() and '동시보유=3' in rt.app_log())
    # 운영 DB 의 위험 한도(24시간 3.0 · 누적 15.0 — #15 E4 기대값, F1 EG 넷째 값)를 재현한다. 새 DB 기본값은 24시간 1.5 라
    # 사전 등록 대조가 진입을 막는다(2026-10-30 2차 실행 RISK_CONFIG_MISMATCH — 기록 보존).
    rt.psql("UPDATE risk_config SET max_daily_loss_pct = 3.0, max_portfolio_drawdown_pct = 15.0 WHERE id = (SELECT max(id) FROM risk_config)")
    check('①  위험 한도 최신 행 = 운영 값 3.0 · 15.0', one(
        "SELECT max_daily_loss_pct = 3.0 AND max_portfolio_drawdown_pct = 15.0 FROM risk_config ORDER BY id DESC LIMIT 1") == 't')
    seed_cache(anchor, (int(time.time()) // 3600) * 3600)
    rt.psql(f"SELECT setval(pg_get_serial_sequence('live_trading_session', 'id'), {SID - 1})")
    body_line = next(l for l in open(PLAN, encoding='utf-8') if l.strip().startswith("-d '{\"strategyType\":\"DAILY_SMA20\""))
    body = json.loads(body_line.strip()[len("-d '"):body_line.strip().rindex("'")])
    rt.log(f'  생성 본문(문서): {body}')
    created = api_json('POST', '/api/v1/trading/sessions', body)
    rt.log(f'  생성 응답: {json.dumps(created, ensure_ascii=False)[:400]}')
    check('①  세션 생성 — SID 203 · CREATED', one("SELECT string_agg(id || ':' || status, ',') FROM live_trading_session") == f'{SID}:CREATED')
    check('①  저장값 대조(문서 쿼리)', one(
        "SELECT status = 'CREATED' AND session_type = 'REAL' AND strategy_type = 'DAILY_SMA20' AND coin_pair = 'KRW-BTC' AND timeframe = 'H1' "
        "AND initial_capital = 20200 AND available_krw = 20200 AND invest_ratio = 0.995 AND max_investment = 20000 AND stop_loss_pct = 5.0 "
        f"AND max_hold_hours = 0 AND coalesce(strategy_params::text, '{{}}') = '{{}}' AND started_at IS NULL FROM live_trading_session WHERE id = {SID}") == 't')
    check('①  기준 행 1개(STAGE_1 · 100000)', one("SELECT count(*) = 1 AND bool_and(stage = 'STAGE_1' AND total_cap_krw = 100000) FROM capital_stage_baseline") == 't',
          one("SELECT string_agg(stage || '|' || total_cap_krw || '|' || started_at, ',') FROM capital_stage_baseline"))
    created_at = one(f"SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') FROM live_trading_session WHERE id = {SID}")
    rt.kill_app()

    # ② 운영과 같은 기동(강제 종료 지점만 켠다) → F1
    rt.start_app(crashpoint='AFTER_EXCHANGE_ACCEPT', label='sma20-run-crash', props=PROPS)
    check('②  마감 시각 이동 등록(시험 빌드)', f'마감 봉 = {close_hour}:00 UTC' in rt.app_log())
    wait_s = T - 1200 - time.time()      # F1 은 시작 20분 전(운영 08:30 → 08:50 과 같은 간격)
    if wait_s > 0:
        rt.log(f'  F1 시각({utc(T - 1200)})까지 {wait_s:.0f}초 대기')
        time.sleep(wait_s)
    rc, out = run_bash('F1-local', localize(doc_block('**F1.'), DROP_F1, 'F1'), created_at)
    eg = open(os.path.join(M, 'eg-f1.txt'), encoding='utf-8').read().strip() if os.path.exists(os.path.join(M, 'eg-f1.txt')) else None
    check('②  F1(문서 DB·API 줄) 통과 — 종료코드 0 · ok-F1 · EG = 1|0|1|true|-|-|0 일 때만 F2',
          rc == 0 and os.path.exists(os.path.join(M, 'ok-F1')) and '✅ F1 기계 점검 통과' in out and eg == '1|0|1|true|-|-|0',
          f'rc={rc} EG={eg}')

    # ③ F2 — 정시 10분 전 시작 1회
    wait_s = T - 600 - time.time()
    if wait_s > 0:
        rt.log(f'  시작 요청 시각({utc(T - 600)})까지 {wait_s:.0f}초 대기')
        time.sleep(wait_s)
    check('③  시작 요청 시각이 마감 2~12분 전', 120 <= T - time.time() <= 720, f'{T - time.time():.0f}초 전')
    rc, out = run_bash('F2-local', localize(doc_block('**F2.'), DROP_F2, 'F2'), created_at)
    check('③  F2 시작 요청 1회 — start-sent 표지', rc == 0 and os.path.exists(os.path.join(M, 'start-sent')))
    time.sleep(60)
    start_check = [l for l in open(PLAN, encoding='utf-8').read().split('- 시작 확인(1분 뒤')[1].split('```bash')[1].split('```')[0].splitlines()
                   if l.strip() and not l.strip().startswith('sc |')]
    rc, out = run_bash('F2-check', start_check, created_at)
    check('③  시작 확인 — RUNNING · 저장값 t · 203:RUNNING', 'RUNNING' in out and f'{SID}:RUNNING' in out and '\nt\n' in out)
    check('③  시작 직후 판단 — 마감 봉 아님(HOLD)', rt.wait_until(lambda: one(
        f"SELECT count(*) FROM strategy_log WHERE session_type = 'LIVE' AND session_id = {SID}") != '0', 150, 5, '첫 판단'),
        one(f"SELECT string_agg(signal || ' ' || coalesce(reason, ''), ' / ' ORDER BY id) FROM strategy_log WHERE session_type = 'LIVE' AND session_id = {SID}"))

    # ④ 정시 마감 봉 BUY → 회차 마감 뒤 승인·매수 → 거래소 접수 직후 강제 종료
    rt.log(f'  마감 {utc(T)} 뒤 BUY 판단 대기')
    check('④  마감 봉 BUY 판단(접수 거절 없음)', rt.wait_until(lambda: one(
        f"SELECT count(*) FROM strategy_log WHERE session_type = 'LIVE' AND session_id = {SID} AND signal = 'BUY'") != '0',
        max(60, T - time.time() + 240), 5, 'BUY'),
        one(f"SELECT signal || ' · ' || coalesce(blocked_reason, '-') || ' · ' || created_at FROM strategy_log WHERE session_type = 'LIVE' AND session_id = {SID} AND signal = 'BUY' ORDER BY id LIMIT 1"))
    if time.time() < T + 290:
        check('④  BUY 판단 뒤 회차 마감 전에는 주문 없음', one(f"SELECT count(*) FROM \"order\" WHERE session_id = {SID}") == '0', utc(time.time()))
    rc = rt.wait_app_exit(timeout=max(60, T + 300 - time.time() + 120))
    xo = rt.exchange_orders()
    check('④  매수 접수 직후 강제 종료(halt 137)', rc == 137, f'rc={rc}')
    check('④  거래소 대역 — 매수 1건 접수·체결(done 0.0016)', len(xo['creates']) == 1 and xo['creates'][0]['side'] == 'bid'
          and xo['orders'][0]['state'] == 'done', json.dumps(xo['orders']))    # /_ctl/orders 의 orders 는 목록(3차 실행기 결함)
    crash_state = one(f"SELECT string_agg(side || ':' || state || ':' || coalesce(exchange_order_id, '-') || ':' || (client_order_id IS NOT NULL), ',') FROM \"order\" WHERE session_id = {SID}")
    check('④  종료 시점 DB — 매수 1건, 거래소 주문번호 미기록', crash_state is not None and crash_state.startswith('BUY:') and ':-:true' in crash_state, crash_state)
    check('④  종료 시점 잔액 — 예약만 빠짐(200)', one(f'SELECT available_krw = 200 FROM live_trading_session WHERE id = {SID}') == 't',
          one(f'SELECT available_krw FROM live_trading_session WHERE id = {SID}'))
    check('④  승인 시각 ≥ 회차 마감', one(f"SELECT (min(created_at) >= to_timestamp({T + 300}))::text FROM \"order\" WHERE session_id = {SID}") == 'true',
          one(f"SELECT min(created_at) FROM \"order\" WHERE session_id = {SID}"))

    # ⑤ 재기동 — 식별자 조회로 정산, 같은 봉 재평가에도 새 매수 없음
    rt.start_app(label='sma20-run-after-crash', props=PROPS)
    check('⑤  재기동 뒤 매수 정산 — FILLED · 0.0016 · 19,600 · 수수료 9.8', rt.wait_until(lambda: one(
        f"SELECT state = 'FILLED' AND filled_quantity = 0.0016 AND applied_executed_funds = 19600 AND applied_paid_fee = 9.8 FROM \"order\" WHERE session_id = {SID} AND side = 'BUY'")
        == 't', 180, 3, '매수 정산'),
        one(f"SELECT state || ':' || filled_quantity || ':' || applied_executed_funds || ':' || applied_paid_fee FROM \"order\" WHERE session_id = {SID} AND side = 'BUY'"))
    check('⑤  잔액 590.2 = 200 + (20,000 − 19,600 − 9.8) · 포지션 OPEN 0.0016',
          one(f'SELECT available_krw = 590.2 FROM live_trading_session WHERE id = {SID}') == 't'
          and one(f"SELECT status = 'OPEN' AND size = 0.0016 FROM position WHERE session_id = {SID}") == 't',
          one(f"SELECT status || ':' || size || ':' || invested_krw FROM position WHERE session_id = {SID}"))
    time.sleep(130)    # 재기동 뒤 평가 두 번 이상
    check('⑤  재기동 뒤 거래소 매수 1건 그대로 · 주문 행 1건', len(rt.exchange_orders()['creates']) == 1
          and one(f"SELECT count(*) FROM \"order\" WHERE session_id = {SID}") == '1')
    rt.log('  재기동 뒤 판단: ' + str(one(
        f"SELECT string_agg(signal || ' · ' || coalesce(blocked_reason, '-'), ' / ' ORDER BY id) FROM strategy_log WHERE session_type = 'LIVE' AND session_id = {SID} "
        f"AND created_at > (SELECT max(created_at) FROM \"order\" WHERE session_id = {SID})")))
    # 🔴 (2026-10-31 4차 전 점검) F3 도 F1·F2 처럼 바꾼다 — 그대로면 토큰을 .env 에서, 계좌를 localhost:8080 에서 읽어 '조회 실패' 가 난다.
    rc, out = run_bash('F3-hold', localize(doc_block('**F3.'), (), 'F3-hold'), created_at)
    check('⑤  F3 판정 — ✅ 매수 1건 보유 중', '✅ 판정: 매수 1건 보유 중' in out)

    # ⑥ 정지 1회 → 청산 매도 → 정리 완료 → 잔여량 대조
    before_stop = snapshot()
    stopped = api_json('POST', f'/api/v1/trading/sessions/{SID}/stop')
    rt.log(f'  정지 응답: {json.dumps(stopped, ensure_ascii=False)[:300]}')
    check('⑥  정지 뒤 포지션 CLOSED(FORCED_STOP) · 정리 대기 0', rt.wait_until(lambda: one(
        f"SELECT status || ':' || coalesce(exit_reason, '-') FROM position WHERE session_id = {SID}") == 'CLOSED:FORCED_STOP'
        and one('SELECT count(*) FROM position_cleanup WHERE done_at IS NULL') == '0', 180, 3, '청산'), snapshot())
    check('⑥  세션 STOPPED · 잔액 20,182.36 = 590.2 + 19,600 − 7.84',
          one(f"SELECT status = 'STOPPED' AND available_krw = 20182.36 FROM live_trading_session WHERE id = {SID}") == 't',
          one(f"SELECT status || ':' || available_krw FROM live_trading_session WHERE id = {SID}"))
    check('⑥  활성 주문 0 · 미해결 정산 불일치 0', one(
        f"SELECT count(*) FROM \"order\" WHERE session_id = {SID} AND state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')") == '0'
        and one(f"SELECT count(*) FROM order_settlement_discrepancy d JOIN \"order\" o ON o.id = d.order_id WHERE o.session_id = {SID} AND d.resolved_at IS NULL") == '0')
    xo = rt.exchange_orders()
    check('⑥  거래소 대역 — 매수 1 · 매도 1, BTC 잔량 0, KRW 999,982.36', [c['side'] for c in xo['creates']] == ['bid', 'ask']
          and float(xo['coin']) == 0 and abs(float(xo['krw']) - 999982.36) < 1e-6, f"KRW {xo['krw']} BTC {xo['coin']}")
    rc, out = run_bash('F3-stopped', localize(doc_block('**F3.'), (), 'F3-stopped'), created_at)
    check('⑥  F3 — ⛔ 세션 상태 STOPPED(정상 운영 아님 판정) · 대조 줄 일치',
          '⛔ 판정: 세션 상태 STOPPED' in out and '거래소 BTC 증감 0' in out and '세션 증감 -17.64' in out and '· 차이 0' in out)
    rt.log(f'  정지 전: {before_stop}\n  정지 뒤: {snapshot()}')

    # ⑦ 강제 종료 · 재기동 — 기록 전체 그대로
    final_restart(PROPS, check_stopped_state('⑥'))
    rt.kill_app()
    rt.stop_double()

except Abort as e:
    rt.log(f'🔴 필수 단계 실패로 중단: {e}')
except Exception as e:
    # 🔴 (2026-10-30 3차) 실행기 예외도 실패로 남긴다 — 3차는 예외가 그대로 빠져나가 결과 요약이 없었다.
    rt.log('🔴 실행기 예외로 중단:\n' + traceback.format_exc())
    results.append((f'실행기 예외 {type(e).__name__}', False, str(e)))
finally:
    rt.kill_all()
    rt.stop_double()
summary()
