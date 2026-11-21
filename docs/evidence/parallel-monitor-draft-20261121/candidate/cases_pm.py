"""병행 감시 변환기 직접 영향 5사례 — 총괄 v1.214 §3-1. **합성 입력만**(psql 출력 모양의 TSV 를 이 파일이 만든다).
실제 SQL 실행·운영 관측·P7/F3 통과의 증거가 아니다. 변환 성공 때만 고정 W1(registry_f1)·W2(parallel_report) 원본을
파일 입력으로 호출해 기대 분류까지 연결한다. 기존 W 묶음·PG·제품 시험·빌드는 반복하지 않는다.
사례: P1 정상 · P2 개수 같고 ID 교체(+등록 DYNAMIC 이 PAPER) · P3 소유자 없는 주문/포지션(+알 수 없는 상태)
      · P4 금액 열 혼입 · P5 조회 실패/부분 출력(명시적 성공 빈 목록 대조 포함, aux 미제공)
사용: python -I cases_pm.py --run <고유호출명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FIX = os.path.normpath(os.path.join(ROOT, '..', 'weekend-local-fix-20261121', 'candidate'))
ORIG_LOADER_SHA = '3d7a1dccfa57359bccef2e71abc3132390ae10d04a8b668bb6d3e7a0a8161b0c'
assert hashlib.sha256(open(os.path.join(FIX, 'orig.py'), 'rb').read()).hexdigest() == ORIG_LOADER_SHA, 'orig.py 해시 불일치'
sys.path.insert(0, FIX)
import orig  # noqa: E402

REG = os.path.normpath(os.path.join(orig.ORIG, '..', 'fixtures', 'w1', 'registry.json'))   # 합성 registry-3(LIVE 203 + DYNAMIC 900)
REG_SHA = hashlib.sha256(open(REG, 'rb').read()).hexdigest()
assert REG_SHA == orig._sums()['fixtures/w1/registry.json'], '등록표 fixture 해시 불일치'
W1, W2 = orig.path('registry_f1'), orig.path('parallel_report')
orig.verify('registry_f1'), orig.verify('parallel_report')
SQL = os.path.join(ROOT, 'sql', 'state.sql')
SQL_SHA = hashlib.sha256(open(SQL, 'rb').read()).hexdigest()
TOOL = os.path.join(HERE, 'convert_state.py')

T0, T1 = '2026-12-02T01:00:00.000000+00:00', '2026-12-02T01:00:00.420000+00:00'
META = ['META', 'pm-state-1', T0, T0, '96', 'repeatable read']
S203 = ['S', 'LIVE', '203', 'REAL', 'CREATED', 'DAILY_SMA20', 'H1', 'KRW-BTC', '2026-10-30T04:16:58.044684+00:00']
S157 = ['S', 'LIVE', '157', 'REAL', 'STOPPED', 'DAILY_SMA20', 'H1', 'KRW-BTC', '2026-10-01T00:00:00.000000+00:00']
D92 = ['S', 'DYNAMIC', '92', 'REAL', 'EMERGENCY_STOPPED', 'COMPOSITE_BREAKOUT', 'H1', '\\N', '2026-10-01T00:00:00.000000+00:00']
D900 = ['S', 'DYNAMIC', '900', 'REAL', 'RUNNING', 'COMPOSITE_BREAKOUT', 'H1', '\\N', '2026-12-01T00:05:00.000000+00:00']
D901P = ['S', 'DYNAMIC', '901', 'PAPER', 'RUNNING', 'COMPOSITE_BREAKOUT', 'H1', '\\N', '2026-11-15T00:00:00.000000+00:00']
O1 = ['O', '1', 'DYNAMIC', '900', 'BUY', 'KRW-ETH', 'FILLED', 'OK']
P11 = ['P', '11', 'DYNAMIC', '900', 'KRW-ETH', 'OPEN', 'OK']
U0 = ['U', '0', '0']
END = ['END', T1]
AUX_OK = {'p0_events': [], 'error_logs': [], 'paper_522_587': {'total': 66, 'running': 63, 'not_running': 3}}
AMOUNT = '20200.00'   # 혼입 금액 값 — 변환기 결과·표준 출력·오류 어디에도 나오면 안 됨


def tsv(*rows, cnt=None):
    """psql 출력 모양. cnt 를 주지 않으면 실제 행 수로 CNT 를 만든다(DYN PAPER 미표시 개수는 합성 2·1)."""
    body = [r for r in rows]
    s = sum(1 for r in body if r[0] == 'S')
    o = sum(1 for r in body if r[0] == 'O')
    p = sum(1 for r in body if r[0] == 'P')
    c = ['CNT', *map(str, cnt or (s, o, p, 2, 1))]
    return ('\n'.join('\t'.join(r) for r in [META, *body, U0, c, END]) + '\n').encode('utf-8')


def swap(row, i, v):
    r = list(row)
    r[i] = v
    return r


NORMAL = tsv(S157, S203, D92, D900, D901P, O1, P11)
NOREND = b''.join(l + b'\n' for l in NORMAL.split(b'\n')[:4])   # META + S 3줄, END 없음
EMPTY_OK = tsv()

# (이름, tsv 바이트, rc 텍스트, stderr 바이트, aux 덮어쓰기, 기대 변환 verdict, 기대 코드/문제 접두어, W1 기대, W2 기대, 금지 문자열)
CASES = [
    ('P1_normal_identity_state', NORMAL, '0', b'', {}, 'CONVERTED', [],
     ('SET_MATCH', [], []), ('NO_RISK_ITEMS', []), []),
    ('P2a_same_count_id_swapped', tsv(S157, S203, D92, swap(D900, 2, '902'), D901P, swap(O1, 3, '902'), swap(P11, 3, '902')),
     '0', b'', {}, 'CONVERTED', [],
     ('MISMATCH', [('DYNAMIC', 900)], [('DYNAMIC', 902)]),
     ('RISK_PRESENT', ['UNREGISTERED_REAL_SESSION_ACTIVE', 'UNREGISTERED_REAL_ORDER', 'UNREGISTERED_REAL_POSITION']), []),
    ('P2b_registered_dynamic_is_paper', tsv(S157, S203, D92, swap(D900, 3, 'PAPER'), D901P), '0', b'', {}, 'CONVERTED', [],
     ('MISMATCH', [('DYNAMIC', 900)], []), ('NO_RISK_ITEMS', []), []),
    ('P3a_ownerless_order_position', tsv(S157, S203, D92, D900, D901P, O1, P11,
                                         ['O', '2', 'LIVE', '\\N', 'BUY', 'KRW-XRP', 'FILLED', 'NO_SESSION_ID'],
                                         ['P', '12', 'DYNAMIC', '777', 'KRW-SOL', 'OPEN', 'NO_SESSION_ROW']),
     '0', b'', {}, 'COLLECTION_NOT_PASSED', ['ORDER_OWNER_NO_SESSION_ID', 'POSITION_OWNER_NO_SESSION_ROW'], None, None, []),
    ('P3b_unknown_status_mode', tsv(S157, swap(S203, 4, 'SUSPENDED'), D92, swap(D900, 3, 'SIM'), D901P, swap(O1, 6, 'WEIRD'), P11),
     '0', b'', {}, 'COLLECTION_NOT_PASSED', ['SESSION_STATUS_UNKNOWN', 'DYNAMIC_MODE_UNKNOWN', 'ORDER_STATE_UNKNOWN'], None, None, []),
    ('P4a_amount_column_appended', tsv(S157, S203 + [AMOUNT], D92, D900, D901P, O1, P11), '0', b'', {}, 'INVALID',
     ['줄 3 S: 열 수 10≠9'], None, None, [AMOUNT]),
    ('P4b_amount_in_status_slot', tsv(S157, swap(S203, 4, AMOUNT), D92, D900, D901P, O1, P11), '0', b'', {}, 'INVALID',
     ['줄 3 S status: 형식 위반'], None, None, [AMOUNT]),
    ('P5a_query_failed_partial_rows', NOREND, '3', b'psql:state.sql:40: ERROR:  canceling statement due to user request\n', {},
     'INVALID', ['QUERY_FAILED'], None, None, []),
    ('P5b_failed_empty_output', b'', '0', b'', {}, 'INVALID', ['OUTPUT_EMPTY'], None, None, []),
    ('P5c_truncated_no_end', NOREND, '0', b'', {}, 'INVALID', ['OUTPUT_TRUNCATED'], None, None, []),
    ('P5d_row_count_mismatch', tsv(S157, S203, D92, D900, O1, P11, cnt=(5, 1, 1, 2, 1)), '0', b'', {}, 'INVALID',
     ['ROW_COUNT_MISMATCH'], None, None, []),
    ('P5e_explicit_success_empty', EMPTY_OK, '0', b'', {}, 'CONVERTED', [],
     ('MISMATCH', [('DYNAMIC', 900), ('LIVE', 203)], []), ('NO_RISK_ITEMS', []), []),
    ('P5f_aux_p0_not_provided', NORMAL, '0', b'', {'p0_events': None}, 'COLLECTION_NOT_PASSED', ['AUX_NOT_PROVIDED'], None, None, []),
]


def write(p, b):
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def call(args, d):
    p = subprocess.run([sys.executable, '-I', *args], capture_output=True)
    write(os.path.join(d, 'rc.txt'), f'{p.returncode}\n')
    write(os.path.join(d, 'stdout.txt'), p.stdout)
    write(os.path.join(d, 'stderr.txt'), p.stderr)
    return p


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    lines, summary = [], []
    for name, raw, rc, err, auxo, verdict, codes, w1x, w2x, forbid in CASES:
        d = os.path.join(run, name)
        i, cv = os.path.join(d, 'in'), os.path.join(d, 'conv')
        os.makedirs(i)
        write(os.path.join(i, 'state.tsv'), raw)
        write(os.path.join(i, 'state.rc'), rc + '\n')
        write(os.path.join(i, 'state.stderr'), err)
        write(os.path.join(i, 'n03-source.txt'), f'합성 N03 원천 표지({name}) — 운영 관측 아님\n')
        aux = {'aux_version': 'pm-aux-1', 'synthetic': True,
               'source': {'file': 'n03-source.txt', 'sha256': hashlib.sha256(open(os.path.join(i, 'n03-source.txt'), 'rb').read()).hexdigest(),
                          'rc': 0}, **AUX_OK, **auxo}
        # pm-run1: 'aux.json' 은 Windows 예약 장치 이름(AUX)이라 git 이 색인하지 못함 → 원문은 runs/pm-run1.tar.gz 로 보존, 이름만 n03-aux.json
        write(os.path.join(i, 'n03-aux.json'), json.dumps(aux, ensure_ascii=False, indent=1) + '\n')
        p = call([TOOL, '--sql', SQL, '--sql-sha256', SQL_SHA, '--tsv', os.path.join(i, 'state.tsv'), '--rc', os.path.join(i, 'state.rc'),
                  '--stderr', os.path.join(i, 'state.stderr'), '--aux', os.path.join(i, 'n03-aux.json'), '--label', f'합성-{name}',
                  '--synthetic', 'true', '--out-dir', cv], d)
        r = json.load(open(os.path.join(cv, 'result.json'), encoding='utf-8'))
        emitted = [n for n in ('f1-obs.json', 'parallel-in.json') if os.path.exists(os.path.join(cv, n))]
        got_codes = sorted({f['code'] for f in r.get('collection_failures', [])})
        c = {'verdict': r['verdict'] == verdict, 'exit': p.returncode == r['exit'], 'stderr_empty': p.stderr == b'',
             'consumable_only_when_converted': r['consumable'] is (verdict == 'CONVERTED'),
             'outputs_only_when_converted': emitted == (['f1-obs.json', 'parallel-in.json'] if verdict == 'CONVERTED' else []),
             'codes': all(x in got_codes for x in codes if x.isupper() and ' ' not in x) if verdict == 'COLLECTION_NOT_PASSED' else True,
             'problem_prefix': all(any(pr.startswith(x) for pr in r['problems']) for x in codes) if verdict == 'INVALID' else True}
        blob = b''.join(open(os.path.join(cv, n), 'rb').read() for n in os.listdir(cv)) + p.stdout + p.stderr
        c['forbidden_values_absent'] = not any(f.encode() in blob for f in forbid)
        rec = {'case': name, 'conv': r['verdict'], 'conv_rc': p.returncode, 'codes': got_codes, 'problems': r['problems']}
        if verdict == 'CONVERTED':
            w1d, w2d = os.path.join(d, 'w1'), os.path.join(d, 'w2')
            os.makedirs(w1d), os.makedirs(w2d)
            q1 = call([W1, '--registry', REG, '--registry-sha256', REG_SHA, '--observed', os.path.join(cv, 'f1-obs.json'),
                       '--out', os.path.join(w1d, 'w1.json')], w1d)
            q2 = call([W2, '--registry', REG, '--registry-sha256', REG_SHA, '--state', os.path.join(cv, 'parallel-in.json'),
                       '--out-json', os.path.join(w2d, 'w2.json'), '--out-md', os.path.join(w2d, 'w2.md')], w2d)
            o1 = json.load(open(os.path.join(w1d, 'w1.json'), encoding='utf-8'))
            o2 = json.load(open(os.path.join(w2d, 'w2.json'), encoding='utf-8'))
            v1, miss, extra = w1x
            v2, risks = w2x
            c.update({'w1_verdict': o1['verdict'] == v1 and q1.returncode == o1['exit'],
                      'w1_missing': sorted((x['kind'], x['id']) for x in o1.get('missing', [])) == sorted(miss),
                      'w1_extra': sorted((x['kind'], x['id']) for x in o1.get('extra', [])) == sorted(extra),
                      'w1_not_f1_pass': o1.get('f1_overall') == 'NOT_DETERMINED_BY_THIS_TOOL',
                      'w2_verdict': o2['verdict'] == v2 and q2.returncode == o2['exit'],
                      'w2_risk_codes': sorted({x['code'] for x in o2.get('risks', [])}) == sorted(risks),
                      'w2_no_p7_quote': o2.get('account_check', {}).get('status') == 'NOT_REFERENCED',
                      'w1w2_stderr_empty': q1.stderr == b'' and q2.stderr == b''})
            rec.update({'w1': o1['verdict'], 'w1_rc': q1.returncode, 'w2': o2['verdict'], 'w2_rc': q2.returncode,
                        'w2_risks': sorted({x['code'] for x in o2.get('risks', [])})})
        ok = all(v is True for v in c.values())
        rec.update({'ok': ok, 'checks': c})
        summary.append(rec)
        tail = f" → W1 {rec['w1']}({rec['w1_rc']}) · W2 {rec['w2']}({rec['w2_rc']}) {rec['w2_risks']}" if 'w1' in rec else ''
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} 변환 {r['verdict']}({p.returncode}) {got_codes or ''}{tail}"
                     + ('' if ok else f' {c}'))
    n = sum(x['ok'] for x in summary)
    lines.append(f'{n}/{len(summary)} 기대대로 (직접 영향 5사례·{len(summary)}변형, 합성 입력 — 실제 SQL 실행·P7/F3 통과 증거 아님)')
    json.dump({'synthetic': True, 'sql_sha256': SQL_SHA, 'registry_sha256': REG_SHA, 'cases': summary},
              open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'), ensure_ascii=False, indent=1)
    open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
