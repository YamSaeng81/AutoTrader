"""병행 감시 SQL G1 보완 격리 PG 검증 1묶음 2경계(총괄 v1.216 §3-1). 운영·기존 시험 DB·제품 애플리케이션은 쓰지 않는다.

전제: dec-read-gate-v3-20261120/setup_pg.py(98b3eab0)로 만든 네트워크 없는(--network none)·tmpfs 일회용 컨테이너와
      d81c3f2f V1~V96 을 적용한 템플릿 DB 'base'. 경계마다 base 에서 새 DB 를 복제하고 합성 픽스처를 넣는다.
호출 형식: 실제 읽기안과 같은 **표준 입력 `-f -`** + PGOPTIONS 보호값(statement_timeout 15s · lock_timeout 2s)을 docker exec -e 로 넘긴다.
          같은 형식으로 SHOW 두 값을 읽어 적용 여부를 확인한다(제안 값 확인일 뿐 운영 승인 아님).
흐름: psql 출력 보존 → 변환기 pm-conv-4(pm-aux-2: 합성 N03 원천 + 저장된 p0-run1 Q1 P0 결과 사본) → CONVERTED 일 때만 고정 W1/W2(합성 registry-3).
경계(2):
  G1a 미등록 REAL DYNAMIC 세션 950 에 DYNAMIC 실자금 주문 31·32/포지션 31 을 만든 뒤 세션 모드만 PAPER 로 변경
      → 실자금 행 유지(owner KIND_MODE_MISMATCH)·수집 불통과·U 에 그대로 포함. 대조로 pm-state-2 를 같은 DB 에 실행해 행이 숨는 것을 남긴다.
  G1b 일치하는 DYN_PAPER/PAPER(901·902)와 정상 REAL(900) 혼재 → 모의는 개수만 빠지고 REAL 행·U 는 유지, W1 SET_MATCH·W2 정산 위험.
사용: python -I pg_check_2.py --container <이름> --run <고유 회차명>
종료값: 2경계 모두 기대대로 0, 아니면 1(원문 보존). 실행 환경 전제 실패 2.
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

SQL = os.path.join(ROOT, 'sql', 'state-3.sql')
SQL_SHA = '6d71df61a59d144afe9cfa1affc250c6e219c6195612aa97d2580fa8de1bec6b'
CONV = os.path.join(HERE, 'convert_state_4.py')
CONV_SHA = None   # 아래 main 에서 --conv-sha256 으로 받는다(파일 고정 뒤 README 에 기록)
SQL2 = os.path.join(EVI, 'parallel-monitor-check-20261121', 'sql', 'state-2.sql')
SQL2_SHA = '4c04efff32b096b420341b5fe10cc84475b0dc421147ae5a27eccd0f086aab94'
P0_SAVED = os.path.join(EVI, 'parallel-monitor-check-20261121', 'runs', 'p0-run1', 'Q1_valid_no_p0', 'p0-result.json')
P0_SAVED_SHA = '3ac1c1a0128a51d590f9512eda636567b991f76f792ea6e9ed33509ab87a1a40'
PGOPTIONS = 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s'
REG = os.path.normpath(os.path.join(orig.ORIG, '..', 'fixtures', 'w1', 'registry.json'))   # 합성 registry-3(LIVE 203 + DYNAMIC 900)
REG_SHA = hashlib.sha256(open(REG, 'rb').read()).hexdigest()
assert REG_SHA == orig._sums()['fixtures/w1/registry.json'] == '14599ba6734d11a541b6e8335f1e9aa0cf04c9f24ea4492e02dd50c3a15b0393'
W1, W2 = orig.path('registry_f1'), orig.path('parallel_report')
W1_SHA, W2_SHA = orig.verify('registry_f1'), orig.verify('parallel_report')

AMT = ('731911', '642817', '553729', '464631', '375543', '286457')
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
INSERT INTO order_settlement_discrepancy(order_id, kind, resolved_at) VALUES (1,'FEE_DECREASE','2026-12-01 02:00:00+00'),(3,'FEE_DECREASE',NULL);
"""
INS_O = ('INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, '
         'settlement_final, fill_accounting_pending) VALUES ')
INS_P = ('INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at) '
         'VALUES ')
INS_D = ('INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode, '
         'created_at) VALUES ')
BOUNDARIES = [
    ('G1a_unregistered_real_now_paper',
     INS_D + f"(950,'COMPOSITE_BREAKOUT','H1',{AMT[5]},{AMT[5]},{AMT[5]},'RUNNING','REAL','2026-12-02 00:00:00+00');\n"
     + INS_P + f"(31,'KRW-SOL','LONG',{AMT[5]},{AMT[5]},1,'OPEN','DYNAMIC',950,{AMT[5]},'2026-12-02 01:00:01+00');\n"
     + INS_O + "(31,31,'KRW-SOL','BUY','MARKET',1,'FILLED','DYNAMIC',950,'2026-12-02 01:00:00+00',false,true),"
               "(32,NULL,'KRW-SOL','SELL','MARKET',1,'SUBMITTED','DYNAMIC',950,'2026-12-02 02:00:00+00',false,false);\n"
     "INSERT INTO order_settlement_discrepancy(order_id, kind, resolved_at) VALUES (32,'FUNDS_DECREASE',NULL);\n"
     "UPDATE dynamic_session SET trading_mode = 'PAPER' WHERE id = 950;\n",
     {'conv': 'COLLECTION_NOT_PASSED',
      'codes': ['ORDER_OWNER_KIND_MODE_MISMATCH', 'POSITION_OWNER_KIND_MODE_MISMATCH'],
      'fail_ids': {'ORDER_OWNER_KIND_MODE_MISMATCH': [31, 32], 'POSITION_OWNER_KIND_MODE_MISMATCH': [31]},
      'raw_O': {1: 'OK', 31: 'KIND_MODE_MISMATCH', 32: 'KIND_MODE_MISMATCH'}, 'raw_P': {11: 'OK', 31: 'KIND_MODE_MISMATCH'},
      'raw_U': [1, 1], 'raw_CNT': [4, 3, 2, 1, 1],
      'state2_contrast': {'O': [1], 'P': [11], 'U': [0, 0], 'CNT': [4, 1, 1, 3, 2]}}),
    ('G1b_matching_paper_mixed_real',
     INS_D + f"(902,'COMPOSITE_BREAKOUT','H1',{AMT[3]},{AMT[3]},{AMT[3]},'RUNNING','PAPER','2026-11-15 00:00:00+00');\n"
     + INS_P + f"(15,'KRW-ADA','LONG',{AMT[3]},{AMT[3]},3,'OPEN','DYN_PAPER',902,{AMT[3]},'2026-11-16 00:00:01+00');\n"
     + INS_O + "(2,NULL,'KRW-ETH','BUY','MARKET',0.001,'SUBMITTED','DYNAMIC',900,'2026-12-01 03:00:00+00',false,true),"
               "(4,15,'KRW-ADA','BUY','MARKET',3,'FILLED','DYN_PAPER',902,'2026-11-16 00:00:00+00',false,true);\n"
     "INSERT INTO order_settlement_discrepancy(order_id, kind, resolved_at) VALUES (1,'FUNDS_DECREASE',NULL),(4,'FUNDS_DECREASE',NULL);\n",
     {'conv': 'CONVERTED', 'codes': [], 'u': {'fill_accounting_pending': 1, 'open_discrepancy': 1},
      'rows': {'sessions': 4, 'orders': 2, 'positions': 1, 'dyn_paper_orders_not_listed': 2, 'dyn_paper_positions_not_listed': 2},
      'orders': [1, 2], 'positions': [11],
      'w1': ('SET_MATCH', [], []), 'w2': ('RISK_PRESENT', ['SETTLEMENT_UNRESOLVED'])}),
]


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def wb(p, b):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def sha(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()


class Env(Exception):
    pass


def psql(cont, db, args, stdin=None, env=()):
    e = [x for k in env for x in ('-e', k)]
    return sh(['docker', 'exec', '-i', *e, cont, 'psql', '-X', '-q', '-U', 'postgres', '-d', db, *args], input=stdin)


def run_sql(cont, db, sql_path, d):
    """읽기안 형식: PGOPTIONS 보호값 + psql -X -q -v ON_ERROR_STOP=1 -f - < sql > state.tsv 2> state.stderr; echo $? > state.rc"""
    p = psql(cont, db, ['-v', 'ON_ERROR_STOP=1', '-f', '-'], stdin=open(sql_path, 'rb').read(), env=(PGOPTIONS,))
    wb(os.path.join(d, 'state.tsv'), p.stdout)
    wb(os.path.join(d, 'state.stderr'), p.stderr)
    wb(os.path.join(d, 'state.rc'), f'{p.returncode}\n')
    g = psql(cont, db, ['-v', 'ON_ERROR_STOP=1', '-f', '-'], stdin=b'\\pset tuples_only on\n\\pset format unaligned\n'
             b'SHOW statement_timeout;\nSHOW lock_timeout;\n', env=(PGOPTIONS,))
    wb(os.path.join(d, 'pgoptions-show.txt'), g.stdout)
    wb(os.path.join(d, 'pgoptions-show.stderr'), g.stderr)
    return p, g


def rows_of(raw, tag):
    return [ln.split('\t') for ln in raw.decode('utf-8', 'replace').split('\n') if ln.split('\t')[0] == tag]


def convert(conv, conv_sha, d, name):
    i = os.path.join(d, 'in')
    wb(os.path.join(i, 'n03-source.txt'), f'합성 N03 원천 표지(격리 PG {name}) — 운영 관측 아님\n')
    pb = open(P0_SAVED, 'rb').read()
    if hashlib.sha256(pb).hexdigest() != P0_SAVED_SHA:
        raise Env('저장된 p0-run1 Q1 결과 해시 불일치')
    wb(os.path.join(i, 'p0-result.json'), pb)
    aux = {'aux_version': 'pm-aux-2', 'synthetic': True,
           'n03': {'source': {'file': 'n03-source.txt', 'sha256': sha(os.path.join(i, 'n03-source.txt')), 'rc': 0},
                   'error_logs': [], 'paper_522_587': {'total': 66, 'running': 63, 'not_running': 3}},
           'p0': {'source': {'file': 'p0-result.json', 'sha256': P0_SAVED_SHA}}}
    wb(os.path.join(i, 'n03-p0-aux.json'), json.dumps(aux, ensure_ascii=False, indent=1) + '\n')
    cv = os.path.join(d, 'conv')
    p = sh([sys.executable, '-I', conv, '--sql', SQL, '--sql-sha256', SQL_SHA, '--tsv', os.path.join(i, 'state.tsv'),
            '--rc', os.path.join(i, 'state.rc'), '--stderr', os.path.join(i, 'state.stderr'), '--aux', os.path.join(i, 'n03-p0-aux.json'),
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


def main(argv=None):
    ap = argparse.ArgumentParser()
    for k in ('container', 'run', 'conv-sha256'):
        ap.add_argument(f'--{k}', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    out = []
    try:
        if sha(SQL) != SQL_SHA or sha(CONV) != a.conv_sha256 or sha(SQL2) != SQL2_SHA:
            raise Env('SQL/변환기/대조 SQL 해시가 지정값과 다름')
        env = {
            'server_version': psql(a.container, 'postgres', ['-tAc', 'SELECT version()']).stdout.decode().strip(),
            'psql_version': sh(['docker', 'exec', a.container, 'psql', '--version']).stdout.decode().strip(),
            'network_mode': sh(['docker', 'inspect', '-f', '{{.HostConfig.NetworkMode}}', a.container]).stdout.decode().strip(),
            'sql': os.path.relpath(SQL, EVI).replace('\\', '/'), 'sql_sha256': SQL_SHA,
            'contrast_sql': os.path.relpath(SQL2, EVI).replace('\\', '/'), 'contrast_sql_sha256': SQL2_SHA,
            'conv': os.path.relpath(CONV, EVI).replace('\\', '/'), 'conv_sha256': a.conv_sha256,
            'p0_result_copy_of': os.path.relpath(P0_SAVED, EVI).replace('\\', '/'), 'p0_result_sha256': P0_SAVED_SHA,
            'invocation': f'docker exec -i -e "{PGOPTIONS}" <컨테이너> psql -X -q -U postgres -d <DB> -v ON_ERROR_STOP=1 -f - < SQL',
            'w1_registry_f1_sha256': W1_SHA, 'w2_parallel_report_sha256': W2_SHA, 'registry_sha256': REG_SHA,
            'pg_check_2_sha256': sha(os.path.abspath(__file__))}
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
        p, g = run_sql(a.container, db, SQL, os.path.join(d, 'in'))
        raw = p.stdout
        show = g.stdout.decode('utf-8', 'replace').split()
        c = {'psql_rc0': p.returncode == 0, 'psql_stderr_empty': p.stderr == b'',
             'pgoptions_applied': g.returncode == 0 and g.stderr == b'' and show == ['15s', '2s']}
        q, res, cv = convert(CONV, a.conv_sha256, d, name)
        codes = sorted({f['code'] for f in res.get('collection_failures', [])})
        c['conv_verdict'] = res['verdict'] == x['conv'] and q.returncode == res['exit']
        c['conv_codes'] = codes == sorted(x['codes'])
        rec = {'boundary': name, 'psql_rc': p.returncode, 'stderr_bytes': len(p.stderr), 'pgoptions_show': show,
               'tag_counts': {t: len(rows_of(raw, t)) for t in ('META', 'S', 'O', 'P', 'U', 'CNT', 'END')},
               'conv': res['verdict'], 'conv_rc': q.returncode, 'codes': codes, 'problems': res.get('problems', [])}
        blob = raw + p.stderr + b''.join(open(os.path.join(cv, n), 'rb').read() for n in os.listdir(cv))
        if 'fail_ids' in x:
            got = {}
            for f in res.get('collection_failures', []):
                got.setdefault(f['code'], []).append(f.get('order_id', f.get('position_id')))
            c['fail_ids'] = {k: sorted(v) for k, v in got.items()} == x['fail_ids']
            ro = {int(r_[1]): r_[7] for r_ in rows_of(raw, 'O')}
            rp = {int(r_[1]): r_[6] for r_ in rows_of(raw, 'P')}
            ru = [int(v) for v in rows_of(raw, 'U')[0][1:]]
            rc_ = [int(v) for v in rows_of(raw, 'CNT')[0][1:]]
            c.update({'raw_O_owner': ro == x['raw_O'], 'raw_P_owner': rp == x['raw_P'], 'raw_U': ru == x['raw_U'],
                      'raw_CNT': rc_ == x['raw_CNT']})
            rec.update({'raw_O': ro, 'raw_P': rp, 'raw_U': ru, 'raw_CNT': rc_})
            # 대조: 같은 DB 에 pm-state-2(4c04efff) — 결함 재현 기록(같은 호출 형식)
            p2, _ = run_sql(a.container, db, SQL2, os.path.join(d, 'contrast-state-2'))
            s2 = {'O': [int(r_[1]) for r_ in rows_of(p2.stdout, 'O')], 'P': [int(r_[1]) for r_ in rows_of(p2.stdout, 'P')],
                  'U': [int(v) for v in rows_of(p2.stdout, 'U')[0][1:]], 'CNT': [int(v) for v in rows_of(p2.stdout, 'CNT')[0][1:]]}
            c['state2_contrast_hides_rows'] = p2.returncode == 0 and p2.stderr == b'' and s2 == x['state2_contrast']
            rec['state2_contrast'] = s2
            blob += p2.stdout + p2.stderr
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
            blob += b''.join(open(os.path.join(dd, n), 'rb').read() for dd in (w1d, w2d) for n in os.listdir(dd))
            rec.update({'u': res['settlement_unresolved_parts'], 'rows': res['rows'], 'w1': o1['verdict'], 'w1_rc': q1.returncode,
                        'w2': o2['verdict'], 'w2_rc': q2.returncode, 'w2_risks': rcodes})
        c['amount_markers_absent'] = not any(m.encode() in blob for m in AMT)
        ok = all(v is True for v in c.values())
        ok_all &= ok
        rec.update({'ok': ok, 'checks': c})
        summary.append(rec)
        tail = f" → W1 {rec['w1']}({rec['w1_rc']}) · W2 {rec['w2']}({rec['w2_rc']}) {rec['w2_risks']}" if 'w1' in rec else ''
        extra_ = f" · pm-state-2 대조 {rec['state2_contrast']}" if 'state2_contrast' in rec else ''
        out.append(f"{'PASS' if ok else 'FAIL'} {name} psql rc={p.returncode} stderr={len(p.stderr)}B PGOPTIONS={show} 행={rec['tag_counts']} "
                   f"변환 {res['verdict']}({q.returncode}) {codes or ''}{tail}{extra_}"
                   + ('' if ok else f" 불일치={[k for k, v in c.items() if v is not True]}"))
    n = sum(r_['ok'] for r_ in summary)
    out.append(f'{n}/{len(summary)} 기대대로 (격리 PG G1 2경계, 합성 픽스처 — 운영 관측·P7/F3/F1 통과 증거 아님)')
    json.dump({'env': env, 'boundaries': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1)
    wb(os.path.join(run, 'pg_check_2.out.txt'), '\n'.join(out) + '\n')
    print('\n'.join(out))
    return 0 if ok_all else 1


if __name__ == '__main__':
    sys.exit(main())
