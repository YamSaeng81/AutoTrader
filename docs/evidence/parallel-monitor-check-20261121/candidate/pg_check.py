"""병행 감시 SQL 격리 PG 검증 1묶음(총괄 v1.215 §3-1). 새 실행 4경계만. 운영·기존 시험 DB·제품 애플리케이션은 쓰지 않는다.

전제: dec-read-gate-v3-20261120/setup_pg.py(98b3eab0, 해시 확인)로 만든 네트워크 없는(--network none)·tmpfs 일회용 컨테이너와
      d81c3f2f V1~V96 을 적용한 템플릿 DB 'base'. 경계마다 base 에서 새 DB 를 복제하고 합성 픽스처를 넣는다.
흐름: 실제 psql 로 SQL 파일 실행(-X -q -v ON_ERROR_STOP=1 -f) → 표준 출력/오류/종료값 보존 → 변환기 → (CONVERTED 일 때만) 고정 W1/W2.
픽스처: 제품 d81c3f2f 형식을 따른다 — DYNAMIC PAPER 세션의 주문·포지션은 session_kind='DYN_PAPER'
        (DynamicTradingService.sessionKind, V67). 금액 열 필수값은 합성 표지 값이며 SQL 출력·변환 결과에 나오면 실패다.
경계(4): B1 정상 식별/상태·정산 표지 · B2 소유자 없는/알 수 없는 종류 · B3 등록 DYNAMIC 의 PAPER 변경 · B4 과거 미등록 종결 주문(경보 유지 기대)
         + B1 을 세션 시간대 UTC / Asia/Seoul 두 번 실행해 S/O/P/U/CNT 줄이 같은지(좁은 대조).
사용: python -I pg_check.py --container <이름> --run <고유 회차명> --sql <SQL> --sql-sha256 <해시> --conv <변환기> --conv-sha256 <해시>
종료값: 4경계+시간대 대조가 모두 기대대로면 0, 아니면 1(실패도 원문은 모두 보존). 실행 환경 전제 실패는 2.
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EVI = os.path.dirname(ROOT)
FIX = os.path.join(EVI, 'weekend-local-fix-20261121', 'candidate')
ORIG_LOADER_SHA = '3d7a1dccfa57359bccef2e71abc3132390ae10d04a8b668bb6d3e7a0a8161b0c'
assert hashlib.sha256(open(os.path.join(FIX, 'orig.py'), 'rb').read()).hexdigest() == ORIG_LOADER_SHA, 'orig.py 해시 불일치'
sys.path.insert(0, FIX)
import orig  # noqa: E402

REG = os.path.normpath(os.path.join(orig.ORIG, '..', 'fixtures', 'w1', 'registry.json'))   # 합성 registry-3(LIVE 203 + DYNAMIC 900)
REG_SHA = hashlib.sha256(open(REG, 'rb').read()).hexdigest()
assert REG_SHA == orig._sums()['fixtures/w1/registry.json'] == '14599ba6734d11a541b6e8335f1e9aa0cf04c9f24ea4492e02dd50c3a15b0393'
W1, W2 = orig.path('registry_f1'), orig.path('parallel_report')
W1_SHA, W2_SHA = orig.verify('registry_f1'), orig.verify('parallel_report')

# 금액 열 합성 표지 — SQL 출력·변환 결과·W 출력 어디에도 나오면 실패
AMT = ('731911', '642817', '553729', '464631', '375543')
BASE = f"""
INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, created_at)
VALUES (203,'DAILY_SMA20','KRW-BTC','H1',{AMT[0]},{AMT[1]},{AMT[0]},'CREATED','2026-10-30 04:16:58.044684+00');
INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode, created_at)
VALUES (900,'COMPOSITE_BREAKOUT','H1',{AMT[2]},{AMT[2]},{AMT[2]},'RUNNING','REAL','2026-12-01 00:05:00+00'),
       (901,'COMPOSITE_BREAKOUT','H1',{AMT[3]},{AMT[3]},{AMT[3]},'RUNNING','PAPER','2026-11-15 00:00:00+00');
INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at, closed_at)
VALUES (11,'KRW-ETH','LONG',{AMT[4]},{AMT[4]},0.001,'OPEN','DYNAMIC',900,{AMT[4]},'2026-12-01 01:00:01+00',NULL),
       (13,'KRW-XRP','LONG',{AMT[3]},{AMT[3]},7,'OPEN','DYN_PAPER',901,{AMT[3]},'2026-11-16 00:00:01+00',NULL);
INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, settlement_final,
                    fill_accounting_pending, applied_executed_funds)
VALUES (1,11,'KRW-ETH','BUY','MARKET',0.001,'FILLED','DYNAMIC',900,'2026-12-01 01:00:00+00',true,false,{AMT[4]}),
       (3,13,'KRW-XRP','BUY','MARKET',7,'FILLED','DYN_PAPER',901,'2026-11-16 00:00:00+00',true,true,{AMT[3]});
-- 제외되어야 하는 정산 표지: 해결된 불일치(주문1), 모의 소유 주문3 의 미해결 불일치·반영 대기
INSERT INTO order_settlement_discrepancy(order_id, kind, resolved_at) VALUES (1,'FEE_DECREASE','2026-12-01 02:00:00+00'),(3,'FEE_DECREASE',NULL);
"""
INS_O = ('INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, '
         'settlement_final, fill_accounting_pending) VALUES ')
BOUNDARIES = [
    # (이름, 픽스처 추가분, 기대)
    ('B1_normal_identity_settlement',
     INS_O + "(2,NULL,'KRW-ETH','BUY','MARKET',0.001,'SUBMITTED','DYNAMIC',900,'2026-12-01 03:00:00+00',false,true);\n"
             "INSERT INTO order_settlement_discrepancy(order_id, kind, resolved_at) VALUES (1,'FUNDS_DECREASE',NULL);\n",
     {'conv': 'CONVERTED', 'codes': [], 'u': {'fill_accounting_pending': 1, 'open_discrepancy': 1},
      'rows': {'sessions': 3, 'orders': 2, 'positions': 1, 'dyn_paper_orders_not_listed': 1, 'dyn_paper_positions_not_listed': 1},
      'orders': [1, 2], 'positions': [11],
      'w1': ('SET_MATCH', [], []), 'w2': ('RISK_PRESENT', ['SETTLEMENT_UNRESOLVED'])}),
    ('B2_ownerless_unknown_kind',
     INS_O + "(4,NULL,'KRW-SOL','BUY','MARKET',1,'FILLED','LIVE',NULL,'2026-12-01 04:00:00+00',true,false),"
             "(5,NULL,'KRW-SOL','BUY','MARKET',1,'FILLED','PAPER',203,'2026-12-01 04:00:00+00',true,false),"
             "(6,NULL,'KRW-ADA','BUY','MARKET',1,'FILLED','DYN_PAPER',900,'2026-12-01 04:00:00+00',true,false);\n"
             "INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at) "
             f"VALUES (14,'KRW-SOL','LONG',{AMT[4]},{AMT[4]},1,'OPEN','DYNAMIC',777,{AMT[4]},'2026-12-01 04:00:01+00');\n",
     {'conv': 'COLLECTION_NOT_PASSED',
      'codes': ['ORDER_OWNER_NO_SESSION_ID', 'ORDER_OWNER_UNCLASSIFIED', 'ORDER_OWNER_UNKNOWN_KIND', 'POSITION_OWNER_NO_SESSION_ROW'],
      'fail_ids': {'ORDER_OWNER_NO_SESSION_ID': [4], 'ORDER_OWNER_UNKNOWN_KIND': [5], 'ORDER_OWNER_UNCLASSIFIED': [6],
                   'POSITION_OWNER_NO_SESSION_ROW': [14]}}),
    ('B3_registered_dynamic_now_paper',
     "UPDATE dynamic_session SET trading_mode = 'PAPER' WHERE id = 900;\n",
     {'conv': 'CONVERTED', 'codes': [], 'u': {'fill_accounting_pending': 0, 'open_discrepancy': 0},
      'rows': {'sessions': 3, 'orders': 0, 'positions': 0, 'dyn_paper_orders_not_listed': 2, 'dyn_paper_positions_not_listed': 2},
      'orders': [], 'positions': [],
      'w1': ('MISMATCH', [('DYNAMIC', 900)], []), 'w2': ('NO_RISK_ITEMS', [])}),
    ('B4_past_unregistered_closed',
     "INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, created_at) "
     f"VALUES (157,'DAILY_SMA20','KRW-BTC','H1',{AMT[1]},{AMT[1]},{AMT[1]},'STOPPED','2026-10-01 00:00:00+00');\n"
     "INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at, closed_at) "
     f"VALUES (20,'KRW-BTC','LONG',{AMT[1]},{AMT[1]},0.0001,'CLOSED','LIVE',157,{AMT[1]},'2026-10-02 00:00:01+00','2026-10-03 00:00:00+00');\n"
     + INS_O + "(21,20,'KRW-BTC','BUY','MARKET',0.0001,'FILLED','LIVE',157,'2026-10-02 00:00:00+00',true,false),"
               "(22,20,'KRW-BTC','SELL','MARKET',0.0001,'FILLED','LIVE',157,'2026-10-03 00:00:00+00',true,false);\n",
     {'conv': 'CONVERTED', 'codes': [], 'u': {'fill_accounting_pending': 0, 'open_discrepancy': 0},
      'rows': {'sessions': 4, 'orders': 3, 'positions': 2, 'dyn_paper_orders_not_listed': 1, 'dyn_paper_positions_not_listed': 1},
      'orders': [1, 21, 22], 'positions': [11, 20],
      'w1': ('SET_MATCH', [], []), 'w2': ('RISK_PRESENT', ['UNREGISTERED_REAL_ORDER']), 'w2_risk_orders': [21, 22]}),
]
AUX_OK = {'p0_events': [], 'error_logs': [], 'paper_522_587': {'total': 66, 'running': 63, 'not_running': 3}}
TAGS = ('S', 'O', 'P', 'U', 'CNT')


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def wb(p, b):
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def sha(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()


class Env(Exception):
    pass


def psql(cont, db, args, stdin=None, env=()):
    e = [x for k in env for x in ('-e', k)]
    return sh(['docker', 'exec', '-i', *e, cont, 'psql', '-X', '-q', '-U', 'postgres', '-d', db, *args], input=stdin)


def run_sql(cont, db, inner, d, tz):
    """제출 호출 형식 그대로: psql -X -q -v ON_ERROR_STOP=1 -f state.sql > state.tsv 2> state.stderr; echo $? > state.rc"""
    os.makedirs(d)
    p = psql(cont, db, ['-v', 'ON_ERROR_STOP=1', '-f', inner], env=(f'PGTZ={tz}',))
    wb(os.path.join(d, 'state.tsv'), p.stdout)
    wb(os.path.join(d, 'state.stderr'), p.stderr)
    wb(os.path.join(d, 'state.rc'), f'{p.returncode}\n')
    t = psql(cont, db, ['-tAc', 'SHOW timezone'], env=(f'PGTZ={tz}',))
    wb(os.path.join(d, 'session_timezone.txt'), t.stdout)
    return p


def convert(conv, sql, sql_sha, d, name):
    i = os.path.join(d, 'in')
    wb(os.path.join(i, 'n03-source.txt'), f'합성 N03 원천 표지(격리 PG {name}) — 운영 관측 아님\n')
    aux = {'aux_version': 'pm-aux-1', 'synthetic': True,
           'source': {'file': 'n03-source.txt', 'sha256': sha(os.path.join(i, 'n03-source.txt')), 'rc': 0}, **AUX_OK}
    wb(os.path.join(i, 'n03-aux.json'), json.dumps(aux, ensure_ascii=False, indent=1) + '\n')
    cv = os.path.join(d, 'conv')
    p = sh([sys.executable, '-I', conv, '--sql', sql, '--sql-sha256', sql_sha, '--tsv', os.path.join(i, 'state.tsv'),
            '--rc', os.path.join(i, 'state.rc'), '--stderr', os.path.join(i, 'state.stderr'), '--aux', os.path.join(i, 'n03-aux.json'),
            '--label', f'격리PG-{name}', '--synthetic', 'true', '--out-dir', cv])
    wb(os.path.join(d, 'conv.rc'), f'{p.returncode}\n')
    wb(os.path.join(d, 'conv.stdout'), p.stdout)
    wb(os.path.join(d, 'conv.stderr'), p.stderr)
    return p, json.load(open(os.path.join(cv, 'result.json'), encoding='utf-8')), cv


def call_w(d, args):
    os.makedirs(d)
    p = sh([sys.executable, '-I', *args])
    wb(os.path.join(d, 'rc.txt'), f'{p.returncode}\n')
    wb(os.path.join(d, 'stdout.txt'), p.stdout)
    wb(os.path.join(d, 'stderr.txt'), p.stderr)
    return p


def lines_of(raw, tags=TAGS):
    return [ln for ln in raw.decode('utf-8', 'replace').split('\n') if ln.split('\t')[0] in tags]


def main(argv=None):
    ap = argparse.ArgumentParser()
    for k in ('container', 'run', 'sql', 'sql-sha256', 'conv', 'conv-sha256'):
        ap.add_argument(f'--{k}', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    out = []
    try:
        if sha(a.sql) != a.sql_sha256 or sha(a.conv) != a.conv_sha256:
            raise Env('SQL/변환기 해시가 지정값과 다름')
        inner = f'/tmp/{a.run}-state.sql'
        r = sh(['docker', 'cp', a.sql, f'{a.container}:{inner}'])
        if r.returncode:
            raise Env(f'docker cp 실패 {r.stderr!r}')
        env = {
            'server_version': psql(a.container, 'postgres', ['-tAc', 'SELECT version()']).stdout.decode().strip(),
            'psql_version': sh(['docker', 'exec', a.container, 'psql', '--version']).stdout.decode().strip(),
            'network_mode': sh(['docker', 'inspect', '-f', '{{.HostConfig.NetworkMode}}', a.container]).stdout.decode().strip(),
            'container_tz_default': psql(a.container, 'postgres', ['-tAc', 'SHOW timezone']).stdout.decode().strip(),
            'sql': os.path.relpath(a.sql, EVI).replace('\\', '/'), 'sql_sha256': a.sql_sha256,
            'conv': os.path.relpath(a.conv, EVI).replace('\\', '/'), 'conv_sha256': a.conv_sha256,
            'w1_registry_f1_sha256': W1_SHA, 'w2_parallel_report_sha256': W2_SHA, 'registry_sha256': REG_SHA,
            'pg_check_sha256': sha(os.path.abspath(__file__))}
        if env['network_mode'] != 'none':
            raise Env(f"컨테이너 네트워크가 none 아님: {env['network_mode']}")
    except Env as e:
        wb(os.path.join(run, 'env_failure.txt'), f'{e}\n')
        print(f'ENV_FAIL {e}')
        return 2
    wb(os.path.join(run, 'env.json'), json.dumps(env, ensure_ascii=False, indent=1) + '\n')
    out += [f'{k}={v}' for k, v in env.items()]
    summary, ok_all = [], True
    for name, delta, x in BOUNDARIES:
        d = os.path.join(run, name)
        os.makedirs(d)
        db = ('c_' + a.run + '_' + name.split('_')[0]).lower().replace('-', '_')
        for q in (f'DROP DATABASE IF EXISTS {db}', f'CREATE DATABASE {db} TEMPLATE base'):
            r = psql(a.container, 'postgres', ['-v', 'ON_ERROR_STOP=1', '-c', q])
            if r.returncode:
                wb(os.path.join(run, 'env_failure.txt'), f'{name}: {q} 실패 {r.stderr!r}\n')
                print(f'ENV_FAIL {name} {q}')
                return 2
        fx = (BASE + delta).encode('utf-8')
        wb(os.path.join(d, 'fixture.sql'), fx)
        r = psql(a.container, db, ['-v', 'ON_ERROR_STOP=1'], stdin=fx)
        wb(os.path.join(d, 'fixture.rc'), f'{r.returncode}\n')
        wb(os.path.join(d, 'fixture.stderr'), r.stderr)
        if r.returncode:
            print(f'ENV_FAIL {name} 픽스처 rc={r.returncode}')
            return 2
        p = run_sql(a.container, db, inner, os.path.join(d, 'in'), 'UTC')
        raw = p.stdout
        c = {'psql_rc0': p.returncode == 0, 'psql_stderr_empty': p.stderr == b''}
        q, res, cv = convert(a.conv, a.sql, a.sql_sha256, d, name)
        codes = sorted({f['code'] for f in res.get('collection_failures', [])})
        c['conv_verdict'] = res['verdict'] == x['conv'] and q.returncode == res['exit']
        c['conv_codes'] = codes == sorted(x['codes'])
        if 'fail_ids' in x:
            got = {}
            for f in res.get('collection_failures', []):
                got.setdefault(f['code'], []).append(f.get('order_id', f.get('position_id')))
            c['fail_ids'] = {k: sorted(v) for k, v in got.items()} == x['fail_ids']
        blob = raw + p.stderr + b''.join(open(os.path.join(cv, n), 'rb').read() for n in os.listdir(cv))
        rec = {'boundary': name, 'psql_rc': p.returncode, 'stderr_bytes': len(p.stderr), 'stdout_lines': len(raw.split(b'\n')) - 1,
               'tag_counts': {t: len(lines_of(raw, (t,))) for t in ('META', 'S', 'O', 'P', 'U', 'CNT', 'END')},
               'conv': res['verdict'], 'conv_rc': q.returncode, 'codes': codes, 'problems': res.get('problems', [])}
        if res['verdict'] == 'CONVERTED':
            parsed = json.load(open(os.path.join(cv, 'parallel-in.json'), encoding='utf-8'))
            c['u'] = res['settlement_unresolved_parts'] == x['u']
            c['rows'] = res['rows'] == x['rows']
            c['order_ids'] = sorted(o['order_id'] for o in parsed['orders']) == x['orders']
            c['position_ids'] = sorted(p_['position_id'] for p_ in parsed['positions']) == x['positions']
            w1d, w2d = os.path.join(d, 'w1'), os.path.join(d, 'w2')
            q1 = call_w(w1d, [W1, '--registry', REG, '--registry-sha256', REG_SHA, '--observed', os.path.join(cv, 'f1-obs.json'),
                              '--out', os.path.join(w1d, 'w1.json')])
            q2 = call_w(w2d, [W2, '--registry', REG, '--registry-sha256', REG_SHA, '--state', os.path.join(cv, 'parallel-in.json'),
                              '--out-json', os.path.join(w2d, 'w2.json'), '--out-md', os.path.join(w2d, 'w2.md')])
            o1 = json.load(open(os.path.join(w1d, 'w1.json'), encoding='utf-8'))
            o2 = json.load(open(os.path.join(w2d, 'w2.json'), encoding='utf-8'))
            v1, miss, extra = x['w1']
            v2, risks = x['w2']
            rcodes = sorted({r_['code'] for r_ in o2.get('risks', [])})
            c.update({'w1_verdict': o1['verdict'] == v1 and q1.returncode == o1['exit'],
                      'w1_missing': sorted((m['kind'], m['id']) for m in o1.get('missing', [])) == sorted(miss),
                      'w1_extra': sorted((m['kind'], m['id']) for m in o1.get('extra', [])) == sorted(extra),
                      'w2_verdict': o2['verdict'] == v2 and q2.returncode == o2['exit'],
                      'w2_risk_codes': rcodes == sorted(risks),
                      'w_stderr_empty': q1.stderr == b'' and q2.stderr == b''})
            if 'w2_risk_orders' in x:
                c['w2_risk_orders'] = sorted(r_['order_id'] for r_ in o2['risks'] if r_['code'] == 'UNREGISTERED_REAL_ORDER') == x['w2_risk_orders']
            blob += b''.join(open(os.path.join(dd, n), 'rb').read() for dd in (w1d, w2d) for n in os.listdir(dd))
            rec.update({'u': res['settlement_unresolved_parts'], 'rows': res['rows'], 'w1': o1['verdict'], 'w1_rc': q1.returncode,
                        'w1_missing': o1.get('missing', []), 'w2': o2['verdict'], 'w2_rc': q2.returncode, 'w2_risks': rcodes})
        c['amount_markers_absent'] = not any(m.encode() in blob for m in AMT)
        if name.startswith('B1'):
            pk = run_sql(a.container, db, inner, os.path.join(d, 'in-kst'), 'Asia/Seoul')
            tz_utc = open(os.path.join(d, 'in', 'session_timezone.txt'), encoding='utf-8').read().strip()
            tz_kst = open(os.path.join(d, 'in-kst', 'session_timezone.txt'), encoding='utf-8').read().strip()
            same = lines_of(raw) == lines_of(pk.stdout)
            c['tz_kst_rc0_stderr_empty'] = pk.returncode == 0 and pk.stderr == b''
            c['tz_sessions_differ'] = tz_utc == 'UTC' and tz_kst == 'Asia/Seoul'
            c['tz_same_rows'] = same and len(lines_of(raw)) > 0
            rec['tz'] = {'utc': tz_utc, 'kst': tz_kst, 'same_S_O_P_U_CNT_lines': same, 'compared_lines': len(lines_of(raw))}
        ok = all(v is True for v in c.values())
        ok_all &= ok
        rec.update({'ok': ok, 'checks': c})
        summary.append(rec)
        tail = f" → W1 {rec['w1']}({rec['w1_rc']}) · W2 {rec['w2']}({rec['w2_rc']}) {rec['w2_risks']}" if 'w1' in rec else ''
        out.append(f"{'PASS' if ok else 'FAIL'} {name} psql rc={p.returncode} stderr={len(p.stderr)}B 행={rec['tag_counts']} "
                   f"변환 {res['verdict']}({q.returncode}) {codes or ''}{tail}" + ('' if ok else f" 불일치={[k for k, v in c.items() if v is not True]}"))
    n = sum(r_['ok'] for r_ in summary)
    out.append(f'{n}/{len(summary)} 기대대로 (격리 PG 4경계, 합성 픽스처 — 운영 관측·P7/F3/F1 통과 증거 아님)')
    json.dump({'env': env, 'boundaries': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1)
    wb(os.path.join(run, 'pg_check.out.txt'), '\n'.join(out) + '\n')
    print('\n'.join(out))
    return 0 if ok_all else 1


if __name__ == '__main__':
    sys.exit(main())
