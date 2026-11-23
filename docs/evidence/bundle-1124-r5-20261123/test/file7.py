"""R2 파일 경계 7개(총괄 v1.229 §4). 파일 전용 a3-proj.py 와 PC 마무리 a3-finish.sh 만 실행한다.
SSH·서버 별칭·가짜 SSH·docker·curl·운영 호출 없음. a3-remote.sh 는 실행하지 않는다 — remote.out 은 a3-remote.sh 3)절의 출력 규칙
(변환 통과면 proj.out 그대로, 불통과면 첫 고정 사유 줄 + 'STOP <rc> PROJECTION_FAILED', 종료값 = 변환 종료값)을 이 파일이 흉내 내어 만든다.
합성 값만 쓴다. 경계 5의 토큰 형태 표지는 실제 비밀값이 아닌 합성 문자열이며, 그 입력 파일은 저장소에 남기지 않고 해시만 기록한다.
사용: python -I file7.py --work <저장소 밖 임시 폴더(없어야 함)> --out <저장소 기록 폴더(없어야 함)>
종료값: 7경계 모두 기대대로 0, 아니면 1(원문 보존).
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
READ = os.path.join(os.path.dirname(HERE), 'read')
PROJ, FIN = os.path.join(READ, 'a3-proj.py'), os.path.join(READ, 'a3-finish.sh')
BASH = r'C:\Program Files\Git\usr\bin\bash.exe'
SENT = '1234567890:' + 'SYNTHETIC_NOT_A_REAL_CREDENTIAL_x9'        # 합성 토큰 형태 표지(실제 값 아님)
TOKRE = re.compile(r'[0-9]{8,10}:[A-Za-z0-9_-]{30,}|Bearer\s+[A-Za-z0-9._~+/=-]{8,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|[Aa]uthorization')
ERRMARK = 'SYNTH_ERR_MARKER_7f3a'                                    # 서비스 오류 문구 반출 여부 확인용
DATA_TAGS = ('ACCT ', 'ACCT_VALUATION', 'META\t', 'SCOPE\t', 'TSES\t', 'OBUY\t', 'CNT\t', 'XCNT\t', 'POS\t', 'MAXTS\t', 'MORD\t', 'END\t')

LEDGER = ('META\ta3-pre-2\t2026-11-24T01:10:00.100000Z\t2026-11-24T01:10:00.100000Z\t96\t203\tfalse\n'
          'SCOPE\tOK\tLIVE\t203\n'
          'TSES\tLIVE\t203\tREAL\tCREATED\t20200.00000000\t20200.00000000\t-\t-\n'
          'CNT\t0\t0\t0\n'
          'XCNT\t0\t0\t0\t0\t0\n'
          'MAXTS\t-\t-\t-\t-\t-\t-\n'
          'END\t2026-11-24T01:10:00.200000Z\n')
NORMAL = {'success': True, 'data': {
    'apiKeyConfigured': True, 'availableKrw': 100000, 'lockedKrw': 0, 'totalKrwBalance': 100000, 'totalAssetKrw': 100000,
    'fetchedAt': '2026-11-24T01:10:01.123456Z',
    'holdings': [{'currency': 'BTC', 'balance': 0.00012, 'locked': 0, 'currentPrice': 135000000, 'avgBuyPrice': 134000000},
                 {'currency': 'ETH', 'balance': 0.001, 'locked': 0, 'currentPrice': 0, 'avgBuyPrice': 5000000}]}}
HEAD = ['SERVER_START 2026-11-24T01:10:00.000001Z', 'WINDOW_OK SQL 2026-11-24 10:10',
        'LEDGER_CALL 2026-11-24T01:10:00.010000Z 2026-11-24T01:10:00.300000Z rc=0', 'WINDOW_OK ACCOUNT 2026-11-24 10:10',
        'APP_CLOCK_1 server=2026-11-24T01:10:00.400000Z..2026-11-24T01:10:00.500000Z app=2026-11-24T01:10:00.450000Z',
        'ACCOUNT_CALL 2026-11-24T01:10:00.600000Z 2026-11-24T01:10:01.200000Z curl_rc=0 http=200',
        'APP_CLOCK_2 server=2026-11-24T01:10:01.300000Z..2026-11-24T01:10:01.400000Z app=2026-11-24T01:10:01.350000Z']
TAIL = ['SERVER_SHA256SUMS ' + 'a' * 64, 'STDERR_BYTES 0', 'SERVER_END 2026-11-24T01:10:01.500000Z']


def j(o):
    return json.dumps(o, ensure_ascii=False)


# (이름, 계좌 본문 bytes, 기대 proj rc, 기대 proj 첫 줄, 변형, 기대 finish rc, 기대 finish 고정 사유)
CASES = [
    ('b1_normal', j(NORMAL), 0, 'ACCT availableKrw=100000', None, 0, None),
    ('b2_invalid_json', '{"success": true, "data": {"apiKeyConfigured": tru', 6, 'ACCT_FAIL JSON_INVALID', None, 6, 'REMOTE_FAILED'),
    ('b3_service_error', j({'success': True, 'data': {'apiKeyConfigured': True, 'error': '계좌 정보 조회 실패: ' + ERRMARK}}),
     6, 'ACCT_FAIL SERVICE_ERROR', None, 6, 'REMOTE_FAILED'),
    ('b4_key_not_configured', j({'success': True, 'data': {'apiKeyConfigured': False, 'message': 'Upbit API Key가 설정되지 않았습니다. ' + ERRMARK}}),
     6, 'ACCT_FAIL KEY_NOT_CONFIGURED', None, 6, 'REMOTE_FAILED'),
    # 5: (가) 계좌 필드의 토큰 표지는 변환이 FIELD_INVALID 로 막는다 (나) 변환을 지나친 줄이라 가정해 remote.out 에 표지를 넣고 원격 0 → PC 가 막는다
    ('b5_token_shape', j({**NORMAL, 'data': {**NORMAL['data'], 'fetchedAt': SENT}}), 6, 'ACCT_FAIL FIELD_INVALID', 'inject_token', 9,
     'EXPORT_TOKEN_SHAPE_FOUND'),
    ('b6_export_check_read_failure', j(NORMAL), 0, 'ACCT availableKrw=100000', 'remote_out_is_dir', 4, 'EXPORT_CHECK_FAILED'),
    ('b7_final_record_failure', j(NORMAL), 0, 'ACCT availableKrw=100000', 'result_is_dir', 4, 'RESULT_RECORD_FAILED'),
]


def sha(b):
    return hashlib.sha256(b).hexdigest()


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def wb(p, b):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--work', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=False)
    os.makedirs(a.out, exist_ok=False)
    src = {n: sha(open(p, 'rb').read()) for n, p in (('a3-proj.py', PROJ), ('a3-finish.sh', FIN), ('file7.py', os.path.abspath(__file__)))}
    results, ok_all = [], True
    for name, acct, prc_exp, pfirst_exp, mut, frc_exp, why_exp in CASES:
        w = os.path.join(a.work, name)
        srv, out = os.path.join(w, 'server'), os.path.join(w, 'a3pre-20261124-1010')
        wb(os.path.join(srv, 'account.json'), acct)
        wb(os.path.join(srv, 'ledger.tsv'), LEDGER)
        p = run([sys.executable, '-I', PROJ, os.path.join(srv, 'account.json'), os.path.join(srv, 'ledger.tsv')])
        pout = p.stdout.decode('utf-8').replace('\r\n', '\n')
        lines = [ln for ln in pout.split('\n') if ln]
        if p.returncode == 0:
            body, rrc = lines, 0
        else:
            first = next((ln for ln in lines if re.match(r'^(ACCT_FAIL|LEDGER_FAIL) [A-Z_]+$', ln)), 'LEDGER_FAIL READ_FAILED')
            body, rrc = [first, f'STOP {p.returncode} PROJECTION_FAILED'], p.returncode
        if mut == 'inject_token':      # (나) 변환 우회 가정: 정상 변환 줄 + 표지를 허용 표지 줄에 넣는다
            body =[ln.replace('fetchedAt=2026-11-24T01:10:01.123456Z', 'fetchedAt=' + SENT) for ln in
                    ['ACCT availableKrw=100000 lockedKrw=0 totalKrwBalance=100000 fetchedAt=2026-11-24T01:10:01.123456Z '
                     'btc_balance=0.00012 btc_locked=0 btc_currentPrice=135000000 other_holdings=1',
                     'ACCT_VALUATION UNKNOWN price_unknown_n=1'] + LEDGER.rstrip('\n').split('\n')]
            rrc = 0
        os.makedirs(out)
        wb(os.path.join(out, 'INCOMPLETE.txt'), '미완료 — 합성 경계\n')
        wb(os.path.join(out, 'args.txt'), 'date 2026-11-24\nwindow 10:00-10:30\nkind LIVE\nsid 203\n')
        wb(os.path.join(out, 'run.txt'), f'pc_utc - -\nssh_rc {rrc}\nssh_stderr_bytes 0\n')
        remote_out = '\n'.join(HEAD + body + TAIL) + '\n'
        if mut == 'remote_out_is_dir':
            os.makedirs(os.path.join(out, 'remote.out'))
        else:
            wb(os.path.join(out, 'remote.out'), remote_out)
        if mut == 'result_is_dir':
            os.makedirs(os.path.join(out, 'RESULT.txt'))
        f = run([BASH, FIN, out.replace('\\', '/'), str(rrc), '0'])
        fout = f.stdout.decode('utf-8', 'replace').replace('\r\n', '\n')
        ferr = f.stderr.decode('utf-8', 'replace')
        res_path = os.path.join(out, 'RESULT.txt')
        result_ok = os.path.isfile(res_path) and open(res_path, encoding='utf-8').read().startswith('OK a3-finish-2')
        incomplete = os.path.exists(os.path.join(out, 'INCOMPLETE.txt'))
        consumable = result_ok and not incomplete
        body_printed = any(ln.startswith(DATA_TAGS) for ln in fout.split('\n'))
        failed_txt = open(os.path.join(out, 'FAILED.txt'), encoding='utf-8').read() if os.path.isfile(os.path.join(out, 'FAILED.txt')) else ''
        leak = {'token_in_proj_out': bool(TOKRE.search(pout)), 'token_in_finish_out': bool(TOKRE.search(fout + ferr)),
                'errmark_in_proj_out': ERRMARK in pout, 'errmark_in_finish_out': ERRMARK in fout + ferr,
                'json_error_text': any(s in pout + fout for s in ('Expecting', 'JSONDecodeError', 'line 1 column'))}
        exp_ok = name == 'b1_normal'
        checks = {
            'proj_rc': p.returncode == prc_exp,
            'proj_first_line': (lines[0].startswith(pfirst_exp) if lines else False),
            'proj_stderr_empty': p.stderr == b'',
            'finish_rc': f.returncode == frc_exp,
            'finish_reason': (why_exp is None and failed_txt == '') or (why_exp is not None and failed_txt.strip().endswith(why_exp)),
            'consumable': consumable == exp_ok,
            'body_printed': body_printed == exp_ok,
            'incomplete_kept': incomplete == (not exp_ok),
            'no_leak': not any(leak.values()),
        }
        if name == 'b1_normal':
            checks['valuation_unknown_but_collected'] = 'ACCT_VALUATION UNKNOWN price_unknown_n=1' in pout and p.returncode == 0
            checks['btc_price_known'] = 'btc_currentPrice=135000000 ' in pout
        if name in ('b2_invalid_json', 'b3_service_error', 'b4_key_not_configured', 'b5_token_shape'):
            checks['only_fixed_reason_lines'] = all(re.match(r'^(STOP [0-9]+ [A-Z_0-9]+|ACCT_FAIL [A-Z_]+|LEDGER_FAIL [A-Z_]+|🔴 .*)$', ln)
                                                    for ln in fout.split('\n') if ln)
        ok = all(checks.values())
        ok_all &= ok
        rec = os.path.join(a.out, name)
        keep_inputs = name != 'b5_token_shape'
        files = {}
        for root, dirs, fs in os.walk(w):
            for fn in fs:
                fp = os.path.join(root, fn)
                files[os.path.relpath(fp, w).replace('\\', '/')] = sha(open(fp, 'rb').read())
        if keep_inputs:
            wb(os.path.join(rec, 'account.json'), acct)
            wb(os.path.join(rec, 'remote.out'), '' if mut == 'remote_out_is_dir' else remote_out)
        wb(os.path.join(rec, 'proj.out'), pout)
        wb(os.path.join(rec, 'finish.stdout'), fout)
        wb(os.path.join(rec, 'finish.stderr'), ferr)
        r = {'boundary': name, 'ok': ok, 'checks': checks, 'leak': leak, 'proj_rc': p.returncode, 'remote_rc_emulated': rrc,
             'finish_rc': f.returncode, 'failed_txt': failed_txt.strip(), 'result_ok': result_ok, 'incomplete_kept': incomplete,
             'mutation': mut, 'inputs_committed': keep_inputs, 'work_file_sha256': files}
        wb(os.path.join(rec, 'result.json'), json.dumps(r, ensure_ascii=False, indent=1) + '\n')
        results.append(r)
    summary = {'sources_sha256': src, 'passed': sum(r['ok'] for r in results), 'total': len(results),
               'boundaries': {r['boundary']: r['ok'] for r in results}}
    wb(os.path.join(a.out, 'summary.json'), json.dumps(summary, ensure_ascii=False, indent=1) + '\n')
    for r in results:
        print(('PASS ' if r['ok'] else 'FAIL ') + r['boundary'], '' if r['ok'] else {k: v for k, v in r['checks'].items() if not v})
    print(f"{summary['passed']}/{summary['total']}")
    # 저장소 기록 폴더에 토큰 형태가 들어가지 않았는지 확인
    for root, _, fs in os.walk(a.out):
        for fn in fs:
            if TOKRE.search(open(os.path.join(root, fn), encoding='utf-8', errors='replace').read()):
                print('🔴 기록 폴더에 토큰 형태:', fn)
                ok_all = False
    return 0 if ok_all else 1


if __name__ == '__main__':
    sys.exit(main())
