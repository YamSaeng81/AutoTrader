"""P0 입력 후보 직접 5경계(총괄 v1.215 §3-2). **합성 backend 로그만** — 실제 로그 수집·서버 접근·설치 없음.
각 경계: p0_input(pm-p0-1) → pm-aux-2(N03 원천과 P0 원천 분리) → convert_state_3(pm-conv-3) → CONVERTED 일 때만 고정 W2.
상태 입력은 격리 PG 2회차 B1 의 실제 psql 출력(runs/pg-run2/B1_normal_identity_settlement/in, pm-state-2)을 읽기만 한다.
경계: Q1 유효한 비발생 로그(장기 대여 경고·뉴스 Connection reset·캔들 HTTP 오류·외부 timeout 포함) · Q2 DB 연결 획득 timeout 발생
      · Q3 로그 수집 실패(rc1, 부분 로그) · Q4 빈 로그 · Q5 범위 근거 미제공. Q3~Q5 는 미완료이며 빈 P0 목록으로 소비되면 실패다.
사용: python -I cases_p0.py --run <고유 회차명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EVI = os.path.dirname(ROOT)
FIX = os.path.join(EVI, 'weekend-local-fix-20261121', 'candidate')
assert hashlib.sha256(open(os.path.join(FIX, 'orig.py'), 'rb').read()).hexdigest() == \
    '3d7a1dccfa57359bccef2e71abc3132390ae10d04a8b668bb6d3e7a0a8161b0c'
sys.path.insert(0, FIX)
import orig  # noqa: E402

REG = os.path.normpath(os.path.join(orig.ORIG, '..', 'fixtures', 'w1', 'registry.json'))
REG_SHA = hashlib.sha256(open(REG, 'rb').read()).hexdigest()
W2 = orig.path('parallel_report')
orig.verify('parallel_report')
U_VARS = os.path.join(EVI, 'd81d4r-20261120', 'd4', 'blocks-d81', 'U변수.sh')
U_SHA = 'efb96108113142132da3685669c1a034e7a3050bc891bbacbecd2891137b19fd'
P0T, CONV = os.path.join(HERE, 'p0_input.py'), os.path.join(HERE, 'convert_state_3.py')
SQL = os.path.join(ROOT, 'sql', 'state-2.sql')
STATE = os.path.join(ROOT, 'runs', 'pg-run2', 'B1_normal_identity_settlement', 'in')
sha = lambda p: hashlib.sha256(open(p, 'rb').read()).hexdigest()

N = [  # 정상 줄
    'backend-1  | 01:00:00.100 [main] INFO  c.c.a.CryptoAutoTraderApplication - Started CryptoAutoTraderApplication in 41.2 seconds',
    'backend-1  | 01:00:05.000 [scheduling-1] INFO  c.c.a.s.SignalQualityService - [SignalQuality] 시작 Catchup 시작',
    'backend-1  | 01:00:09.000 [scheduling-1] INFO  c.c.a.s.SignalQualityService - [SignalQuality] 시작 Catchup 완료',
]
LEASE = [
    'backend-1  | 01:02:03.456 [HikariPool-1 housekeeper] WARN  com.zaxxer.hikari.pool.ProxyLeakTask - Connection leak detection triggered for '
    'org.postgresql.jdbc.PgConnection@1a2b3c on thread scheduling-1, stack trace follows',
    'backend-1  | java.lang.Exception: Apparent connection leak detected',
    'backend-1  | \tat com.cryptoautotrader.api.service.SignalQualityService.evaluateHoldBaselineLoop(SignalQualityService.java:412)',
    'backend-1  | 01:03:10.000 [scheduling-1] INFO  com.zaxxer.hikari.pool.ProxyLeakTask - Previously reported leaked connection '
    'org.postgresql.jdbc.PgConnection@1a2b3c on thread scheduling-1 was returned to the pool (unleaked)',
]
NOISE = [  # P0 가 아닌 오류 — 이 도구가 P0 로 바꾸면 실패
    'backend-1  | 01:04:00.000 [news-1] ERROR c.c.a.s.NewsCollectorService - 뉴스 수집 실패: java.net.SocketException: Connection reset',
    'backend-1  | 01:04:30.000 [candle-2] WARN  c.c.a.s.CandleService - 캔들 조회 실패 HTTP 502',
    'backend-1  | 01:04:40.000 [ext-1] WARN  c.c.a.s.ExchangeClient - 외부 API timeout: java.net.SocketTimeoutException: Read timed out',
]
P0 = [
    'backend-1  | 01:05:00.000 [http-nio-8080-exec-3] ERROR o.h.engine.jdbc.spi.SqlExceptionHelper - HikariPool-1 - '
    'Connection is not available, request timed out after 30000ms.',
    'backend-1  | Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.',
]
RANGE = {'since': '2026-11-21T01:00:00Z', 'until': '2026-11-21T01:10:00Z', 'axis': 'server_utc',
         'basis': '합성: 수집 명령 --since/--until 인자(실제 수집 아님)'}
txt = lambda ls: ('\n'.join(ls) + '\n').encode('utf-8')

# (이름, 로그 바이트 또는 None, 수집 rc, 범위 또는 None, 기대 p0 status, 기대 사유, 기대 events 수, 기대 변환, W2 에 P0 위험이 있어야 하는가)
CASES = [
    ('Q1_valid_no_p0', txt(N + LEASE + NOISE), '0', RANGE, 'P0_ABSENT_IN_PROVIDED_LOG', [], 0, 'CONVERTED', False),
    ('Q2_db_acquire_timeout', txt(N + NOISE + P0), '0', RANGE, 'P0_PRESENT', [], 2, 'CONVERTED', True),
    ('Q3_log_collection_failed', txt(N[:2]), '1', RANGE, 'P0_CHECK_INCOMPLETE', ['LOG_COLLECTION_FAILED'], None, 'COLLECTION_NOT_PASSED', None),
    ('Q4_empty_log', b'', '0', RANGE, 'P0_CHECK_INCOMPLETE', ['LOG_EMPTY'], None, 'COLLECTION_NOT_PASSED', None),
    ('Q5_range_not_provided', txt(N + LEASE + NOISE), '0', None, 'P0_CHECK_INCOMPLETE', ['RANGE_NOT_PROVIDED'], None,
     'COLLECTION_NOT_PASSED', None),
]


def wb(p, b):
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def call(args, d, tag):
    p = subprocess.run([sys.executable, '-I', *args], capture_output=True)
    wb(os.path.join(d, f'{tag}.rc'), f'{p.returncode}\n')
    wb(os.path.join(d, f'{tag}.stdout'), p.stdout)
    wb(os.path.join(d, f'{tag}.stderr'), p.stderr)
    return p


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    head = [f'p0_input sha256={sha(P0T)}', f'convert_state_3 sha256={sha(CONV)}', f'U변수.sh sha256={sha(U_VARS)}',
            f'state-2.sql sha256={sha(SQL)}', f'상태 입력(pg-run2 B1) state.tsv sha256={sha(os.path.join(STATE, "state.tsv"))}',
            f'W2 parallel_report sha256={orig.verify("parallel_report")}', f'registry sha256={REG_SHA}']
    lines, summary = list(head), []
    for name, log, rc, rng, st, reasons, nev, conv, w2p0 in CASES:
        d = os.path.join(run, name)
        i = os.path.join(d, 'in')
        os.makedirs(i)
        wb(os.path.join(i, 'backend.log'), log)
        wb(os.path.join(i, 'collect.rc'), rc + '\n')
        if rng is not None:
            wb(os.path.join(i, 'range.json'), json.dumps(rng, ensure_ascii=False, indent=1) + '\n')
        p0d = os.path.join(d, 'p0')
        q = call([P0T, '--log', os.path.join(i, 'backend.log'), '--collect-rc', os.path.join(i, 'collect.rc'),
                  '--range', os.path.join(i, 'range.json'), '--u-vars', U_VARS, '--u-vars-sha256', U_SHA,
                  '--label', f'합성-{name}', '--synthetic', 'true', '--out-dir', p0d], d, 'p0')
        r = json.load(open(os.path.join(p0d, 'p0.json'), encoding='utf-8'))
        c = {'p0_status': r['status'] == st and q.returncode == r['exit'], 'p0_stderr_empty': q.stderr == b'',
             'reasons': sorted(r.get('incomplete_reasons', [])) == sorted(reasons),
             'events': (r['p0_events'] is None) if nev is None else (isinstance(r['p0_events'], list) and len(r['p0_events']) == nev),
             'empty_only_when_absent': r['consumable_as_empty_p0'] is (st == 'P0_ABSENT_IN_PROVIDED_LOG')}
        if name == 'Q1_valid_no_p0':   # 장기 대여 경고는 별도, 뉴스/외부 오류는 P0 아님
            c['lease_separate'] = r['holdobs']['exit'] == 3 and r['long_lease_warnings']['hold'] == 1 and r['p0_events'] == []
        # aux pm-aux-2: N03 원천(합성)과 P0 원천(p0.json)을 따로
        wb(os.path.join(d, 'n03-source.txt'), f'합성 N03 원천 표지({name}) — 운영 관측 아님\n')
        import shutil
        shutil.copyfile(os.path.join(p0d, 'p0.json'), os.path.join(d, 'p0-result.json'))
        aux = {'aux_version': 'pm-aux-2', 'synthetic': True,
               'n03': {'source': {'file': 'n03-source.txt', 'sha256': sha(os.path.join(d, 'n03-source.txt')), 'rc': 0},
                       'error_logs': [], 'paper_522_587': {'total': 66, 'running': 63, 'not_running': 3}},
               'p0': {'source': {'file': 'p0-result.json', 'sha256': sha(os.path.join(d, 'p0-result.json'))}}}
        wb(os.path.join(d, 'n03-p0-aux.json'), json.dumps(aux, ensure_ascii=False, indent=1) + '\n')
        cv = os.path.join(d, 'conv')
        q2 = call([CONV, '--sql', SQL, '--sql-sha256', sha(SQL), '--tsv', os.path.join(STATE, 'state.tsv'),
                   '--rc', os.path.join(STATE, 'state.rc'), '--stderr', os.path.join(STATE, 'state.stderr'),
                   '--aux', os.path.join(d, 'n03-p0-aux.json'), '--label', f'합성-{name}', '--synthetic', 'true', '--out-dir', cv], d, 'conv')
        res = json.load(open(os.path.join(cv, 'result.json'), encoding='utf-8'))
        emitted = sorted(n for n in os.listdir(cv) if n != 'result.json')
        codes = sorted({f['code'] for f in res.get('collection_failures', [])})
        c['conv'] = res['verdict'] == conv and q2.returncode == res['exit']
        c['conv_outputs'] = emitted == (['f1-obs.json', 'parallel-in.json'] if conv == 'CONVERTED' else [])
        c['conv_codes'] = codes == ([] if conv == 'CONVERTED' else ['AUX_P0_CHECK_INCOMPLETE'])
        rec = {'case': name, 'p0': r['status'], 'p0_rc': q.returncode, 'reasons': r.get('incomplete_reasons'),
               'p0_events': r['p0_events'], 'holdobs_exit': (r.get('holdobs') or {}).get('exit'),
               'conv': res['verdict'], 'conv_rc': q2.returncode, 'codes': codes}
        if conv == 'CONVERTED':
            pin = json.load(open(os.path.join(cv, 'parallel-in.json'), encoding='utf-8'))
            c['p0_passed_through'] = pin['p0_events'] == r['p0_events']
            w2d = os.path.join(d, 'w2')
            os.makedirs(w2d)
            q3 = call([W2, '--registry', REG, '--registry-sha256', REG_SHA, '--state', os.path.join(cv, 'parallel-in.json'),
                       '--out-json', os.path.join(w2d, 'w2.json'), '--out-md', os.path.join(w2d, 'w2.md')], w2d, 'w2')
            o2 = json.load(open(os.path.join(w2d, 'w2.json'), encoding='utf-8'))
            rc_ = sorted({x['code'] for x in o2.get('risks', [])})
            c['w2_p0'] = ('P0' in rc_) is w2p0 and q3.returncode == o2['exit'] and q3.stderr == b''
            rec.update({'w2': o2['verdict'], 'w2_rc': q3.returncode, 'w2_risks': rc_})
        ok = all(v is True for v in c.values())
        rec.update({'ok': ok, 'checks': c})
        summary.append(rec)
        tail = f" → W2 {rec['w2']}({rec['w2_rc']}) {rec['w2_risks']}" if 'w2' in rec else ''
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} P0 {r['status']}({q.returncode}) 사유={r.get('incomplete_reasons')} "
                     f"events={None if r['p0_events'] is None else len(r['p0_events'])} holdobs={rec['holdobs_exit']} "
                     f"→ 변환 {res['verdict']}({q2.returncode}) {codes or ''}{tail}" + ('' if ok else f" 불일치={[k for k, v in c.items() if v is not True]}"))
    n = sum(x['ok'] for x in summary)
    lines.append(f'{n}/{len(summary)} 기대대로 (P0 직접 5경계, 합성 로그 — 실제 로그 수집·운영 P0 판정 아님)')
    json.dump({'synthetic': True, 'cases': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1)
    wb(os.path.join(run, 'cases_p0.out.txt'), '\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
