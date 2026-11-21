"""변환기→R2 합성 연결 3경계 4변형(총괄 v1.216 §3-3). 로컬 파일 전용 — SSH·psql·DB·HTTP 없음. 기존 R2 6/6·13사례는 다시 돌리지 않는다.

입력 출처(모두 합성, 운영 관측 아님)
  · 정상 상태 TSV: 이 파일의 NORMAL_TSV — **수기 합성 pm-state-3 형식 출력**이며 PG 출력이 아니다. 기존 PG 결과의 정산 표지를 고쳐 만든 것이 아니다
    (pg-run1 G1b 실제 출력은 미해결 정산 표지가 있어 그대로 W2 위험이 된다).
  · P0 결과: parallel-monitor-check-20261121 p0-run1 Q1(P0 없음)·Q2(P0 발생)·Q3(로그 수집 실패 → 검사 미완료)의 저장된 p0-result.json 사본(해시 확인).
  · 수집 불통과 변환 결과: 이 폴더 pg-run1 G1a 의 저장된 conv 폴더(격리 PG 실제 psql 출력 → pm-conv-4) 그대로.
  · 등록표: 합성 registry-3(weekend-local-20261120 fixtures/w1/registry.json 14599ba6). 실제 승인 등록표·실제 ID 아님.
경계
  L1  CONVERTED 정상 → R2 1회, W1 SET_MATCH·W2 NO_RISK_ITEMS 둘 다 LOCAL_VERIFIED, R2 overall 은 미연결 W3~W5 때문에 NEEDS_DATA
  L2a P0_CHECK_INCOMPLETE → 변환 COLLECTION_NOT_PASSED → R2 0회   L2b COLLECTION_NOT_PASSED(G1a) → R2 0회
  L3  P0_PRESENT → 변환 CONVERTED → W2 RISK_PRESENT(P0) → R2 w2 FAILED·overall FAILED, 연결 LINKED_NOT_VERIFIED
사용: python -I cases_link.py --run <고유 회차명>   종료값: 4/4 기대대로 0, 아니면 1
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EVI = os.path.dirname(ROOT)
SQL = os.path.join(ROOT, 'sql', 'state-3.sql')
SQL_SHA = '6d71df61a59d144afe9cfa1affc250c6e219c6195612aa97d2580fa8de1bec6b'
CONV = os.path.join(HERE, 'convert_state_4.py')
CONV_SHA = '1416564e5df04cc7b59b5e4eaafce50a096c02a7ce333dd661ffb8d8be8edfcd'
LINK = os.path.join(HERE, 'link_r2.py')
R2 = os.path.join(EVI, 'weekend-local-fix-20261121', 'candidate', 'run_offline_r2.py')
R2_SHA = 'e203ea6c1e6a4f28ecbe59e0783a370e936453abe251f7acb5a96de6a712bd4f'
REG = os.path.join(EVI, 'weekend-local-20261120', 'fixtures', 'w1', 'registry.json')
REG_SHA = '14599ba6734d11a541b6e8335f1e9aa0cf04c9f24ea4492e02dd50c3a15b0393'
P0D = os.path.join(EVI, 'parallel-monitor-check-20261121', 'runs', 'p0-run1')
P0 = {'Q1': ('Q1_valid_no_p0', '3ac1c1a0128a51d590f9512eda636567b991f76f792ea6e9ed33509ab87a1a40'),
      'Q2': ('Q2_db_acquire_timeout', '72cdcf7407ffe0d4ac24128e53400ca18073d37bb62dc885b74c35f6ba103b74'),
      'Q3': ('Q3_log_collection_failed', '3f6e49d0c48e58ede80045e9c230432745cb8ab021ef751efc6c5ad761234a5a')}
G1A_CONV = os.path.join(ROOT, 'runs', 'pg-run1', 'G1a_unregistered_real_now_paper', 'conv')
NORMAL_TSV = ('META\tpm-state-3\t2026-12-03T00:00:00.000000+00:00\t2026-12-03T00:00:00.000100+00:00\t96\trepeatable read\n'
              'S\tDYNAMIC\t900\tREAL\tRUNNING\tCOMPOSITE_BREAKOUT\tH1\t\\N\t2026-12-01T00:05:00.000000+00:00\n'
              'S\tDYNAMIC\t901\tPAPER\tRUNNING\tCOMPOSITE_BREAKOUT\tH1\t\\N\t2026-11-15T00:00:00.000000+00:00\n'
              'S\tLIVE\t203\tREAL\tCREATED\tDAILY_SMA20\tH1\tKRW-BTC\t2026-10-30T04:16:58.044684+00:00\n'
              'O\t1\tDYNAMIC\t900\tBUY\tKRW-ETH\tFILLED\tOK\n'
              'P\t11\tDYNAMIC\t900\tKRW-ETH\tOPEN\tOK\n'
              'U\t0\t0\n'
              'CNT\t3\t1\t1\t1\t1\n'
              'END\t2026-12-03T00:00:00.000200+00:00\n')
CASES = [
    ('L1_converted_normal', 'Q1', {'conv': 'CONVERTED', 'link': 'LINKED_W1_W2_VERIFIED', 'r2_calls': 1, 'r2_rc': 10, 'r2_overall': 'NEEDS_DATA',
                                   'w1': ('LOCAL_VERIFIED', 'SET_MATCH'), 'w2': ('LOCAL_VERIFIED', 'NO_RISK_ITEMS'), 'w2_detail': [],
                                   'others': {'w3': 'NEEDS_DATA', 'w4': 'NEEDS_DATA', 'w5': 'NEEDS_DATA'}}),
    ('L2a_p0_check_incomplete', 'Q3', {'conv': 'COLLECTION_NOT_PASSED', 'conv_codes': ['AUX_P0_CHECK_INCOMPLETE'], 'link': 'NOT_LINKED',
                                       'r2_calls': 0}),
    ('L2b_collection_not_passed_g1a', None, {'conv': 'COLLECTION_NOT_PASSED',
                                             'conv_codes': ['ORDER_OWNER_KIND_MODE_MISMATCH', 'POSITION_OWNER_KIND_MODE_MISMATCH'],
                                             'link': 'NOT_LINKED', 'r2_calls': 0}),
    ('L3_p0_present', 'Q2', {'conv': 'CONVERTED', 'link': 'LINKED_NOT_VERIFIED', 'r2_calls': 1, 'r2_rc': 20, 'r2_overall': 'FAILED',
                             'w1': ('LOCAL_VERIFIED', 'SET_MATCH'), 'w2': ('FAILED', 'RISK_PRESENT'), 'w2_detail': ['P0'],
                             'others': {'w3': 'NEEDS_DATA', 'w4': 'NEEDS_DATA', 'w5': 'NEEDS_DATA'}}),
]


def sha(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()


def wb(p, b):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def sh(args, d, tag):
    p = subprocess.run([sys.executable, '-I', *args], capture_output=True)
    wb(os.path.join(d, f'{tag}.rc'), f'{p.returncode}\n')
    wb(os.path.join(d, f'{tag}.stdout'), p.stdout)
    wb(os.path.join(d, f'{tag}.stderr'), p.stderr)
    return p


def make_conv(d, q):
    i = os.path.join(d, 'in')
    wb(os.path.join(i, 'state.tsv'), NORMAL_TSV)
    wb(os.path.join(i, 'state.rc'), '0\n')
    wb(os.path.join(i, 'state.stderr'), b'')
    wb(os.path.join(i, 'SOURCE.txt'), '수기 합성 pm-state-3 형식 상태 출력(cases_link.py NORMAL_TSV) — PG 출력·운영 관측 아님\n')
    folder, want = P0[q]
    pb = open(os.path.join(P0D, folder, 'p0-result.json'), 'rb').read()
    assert hashlib.sha256(pb).hexdigest() == want, f'{q} 저장 P0 결과 해시 불일치'
    wb(os.path.join(i, 'p0-result.json'), pb)
    wb(os.path.join(i, 'n03-source.txt'), '합성 N03 원천 표지(pm-link-1 시험) — 운영 관측 아님\n')
    aux = {'aux_version': 'pm-aux-2', 'synthetic': True,
           'n03': {'source': {'file': 'n03-source.txt', 'sha256': sha(os.path.join(i, 'n03-source.txt')), 'rc': 0},
                   'error_logs': [], 'paper_522_587': {'total': 66, 'running': 63, 'not_running': 3}},
           'p0': {'source': {'file': 'p0-result.json', 'sha256': want}}}
    wb(os.path.join(i, 'n03-p0-aux.json'), json.dumps(aux, ensure_ascii=False, indent=1) + '\n')
    cv = os.path.join(d, 'conv')
    p = sh([CONV, '--sql', SQL, '--sql-sha256', SQL_SHA, '--tsv', os.path.join(i, 'state.tsv'), '--rc', os.path.join(i, 'state.rc'),
            '--stderr', os.path.join(i, 'state.stderr'), '--aux', os.path.join(i, 'n03-p0-aux.json'), '--label', f'pm-link-1 {q}',
            '--synthetic', 'true', '--out-dir', cv], d, 'conv')
    return cv, p.returncode


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    assert sha(SQL) == SQL_SHA and sha(CONV) == CONV_SHA and sha(R2) == R2_SHA and sha(REG) == REG_SHA, '고정 해시 불일치'
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    out = [f'link_r2 sha256={sha(LINK)}', f'convert_state_4 sha256={CONV_SHA}', f'run_offline_r2 sha256={R2_SHA}', f'registry sha256={REG_SHA}',
           f'G1a conv result.json sha256={sha(os.path.join(G1A_CONV, "result.json"))}']
    res, ok_all = [], True
    for name, q, x in CASES:
        d = os.path.join(run, name)
        os.makedirs(d)
        if q:
            cv, crc = make_conv(d, q)
        else:
            cv, crc = G1A_CONV, None
        cr = json.load(open(os.path.join(cv, 'result.json'), encoding='utf-8'))
        lo = os.path.join(d, 'link')
        p = sh([LINK, '--conv-dir', cv, '--registry', REG, '--registry-sha256', REG_SHA, '--r2', R2, '--r2-sha256', R2_SHA,
                '--label', name, '--out', lo], d, 'link')
        lk = json.load(open(os.path.join(lo, 'link.json'), encoding='utf-8'))
        c = {'conv_verdict': cr['verdict'] == x['conv'] and (crc is None or crc == cr['exit']),
             'link_status': lk['status'] == x['link'] and p.returncode == lk['exit'], 'link_stderr_empty': p.stderr == b'',
             'r2_calls': lk['r2_calls'] == x['r2_calls'], 'r2_dir': os.path.isdir(os.path.join(lo, 'r2')) == (x['r2_calls'] == 1),
             'operational_ready_false': lk['operational_ready'] is False}
        if 'conv_codes' in x:
            c['conv_codes'] = sorted({f['code'] for f in cr.get('collection_failures', [])}) == sorted(x['conv_codes'])
        if x['r2_calls']:
            c.update({'r2_rc': lk.get('r2_rc') == x['r2_rc'], 'r2_overall': lk.get('r2_overall') == x['r2_overall'],
                      'r2_operational_ready_false': lk.get('r2_operational_ready') is False,
                      'w1': (lk['w1']['status'], lk['w1']['verdict']) == x['w1'] and lk['w1']['contract'] == 'OK',
                      'w2': (lk['w2']['status'], lk['w2']['verdict']) == x['w2'] and lk['w2']['contract'] == 'OK',
                      'w2_detail': sorted(lk['w2']['detail'] or []) == x['w2_detail'] if x['w2_detail'] == [] else
                      set(x['w2_detail']) <= set(lk['w2']['detail'] or []),
                      'others': lk.get('other_modules') == x['others'],
                      'r2_stderr_empty': open(os.path.join(lo, 'r2.stderr'), 'rb').read() == b''})
            if name.startswith('L3'):
                c['p0_carried'] = bool(lk.get('p0_events_in'))
        ok = all(v is True for v in c.values())
        ok_all &= ok
        res.append({'case': name, 'ok': ok, 'checks': c, 'conv': cr['verdict'], 'link': lk['status'], 'link_rc': p.returncode,
                    'r2_calls': lk['r2_calls'], 'r2_rc': lk.get('r2_rc'), 'r2_overall': lk.get('r2_overall'),
                    'w1': lk.get('w1'), 'w2': lk.get('w2')})
        tail = (f" → R2 {lk.get('r2_overall')}({lk.get('r2_rc')}) W1 {lk['w1']['verdict']}/{lk['w1']['status']} "
                f"W2 {lk['w2']['verdict']}/{lk['w2']['status']} {lk['w2']['detail']}") if lk['r2_calls'] else ' → R2 호출 0회'
        out.append(f"{'PASS' if ok else 'FAIL'} {name} 변환 {cr['verdict']} · 연결 {lk['status']}({p.returncode}){tail}"
                   + ('' if ok else f" 불일치={[k for k, v in c.items() if v is not True]}"))
    n = sum(r['ok'] for r in res)
    out.append(f'{n}/{len(res)} 기대대로 (합성 연결 — 실제 운영 관측·실제 등록표 대조 아님, operational_ready=false)')
    json.dump({'cases': res}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'), ensure_ascii=False, indent=1)
    wb(os.path.join(run, 'cases_link.out.txt'), '\n'.join(out) + '\n')
    print('\n'.join(out))
    return 0 if ok_all else 1


if __name__ == '__main__':
    sys.exit(main())
