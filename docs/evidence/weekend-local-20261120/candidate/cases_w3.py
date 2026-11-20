"""W3 직접 영향 사례 — 합성/보존 로컬 입력만. runs/<호출명>/ 에 사례별 입력·출력·종료값을 남긴다(덮어쓰기 금지).
입력 원천(읽기만): review-09f6bd4a/contract-probes.json 의 P0(시험용 가상 정상 입력, 체크섬 기록),
조립기 보존 결과 dec-collect-assembler-commit-20261120/run1/normal(확정 묶음). 변형은 실행 폴더 안 사본에만 한다.
사용: python -I cases_w3.py --run <고유호출명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, copy, hashlib, json, os, shutil, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EV = os.path.dirname(ROOT)
PROBES = os.path.join(EV, 'review-09f6bd4a', 'contract-probes.json')
BUNDLE = os.path.join(EV, 'dec-collect-assembler-commit-20261120', 'run1', 'normal')
TOOL = os.path.join(HERE, 'check_parallel.py')


def p0():
    return copy.deepcopy([p for p in json.load(open(PROBES, encoding='utf-8'))['probes'] if p['name'][:2] == 'P0'][0]['source'])


def unconfirmed(d):
    for s in d['registry']['sessions']:
        s['ruleset'].update(confirmed=None, confirmed_source=None)
    return d


def bad_balance_closing(d):
    d['registry']['cash_base']['amount_krw'] = '10000'          # 합성: 미배정 현금이 음수가 되는 기준(확정 이상)
    d['now']['db']['sessions'][0]['available_krw'] = '3000.00'
    d['now']['db']['positions'][1]['status'] = 'CLOSING'         # 합성: ETH 청산 대기(보류)
    return d


def write_full(d, case_dir):
    paths = {}
    for k in ('registry', 'base', 'now'):
        paths[k] = os.path.join(case_dir, f'{k}.json')
        with open(paths[k], 'w', encoding='utf-8', newline='\n') as f:
            json.dump(d[k], f, ensure_ascii=False, indent=1)
    return ['--registry', paths['registry'], '--base', paths['base'], '--now', paths['now']]


def bundle_uncommitted(case_dir):
    b = os.path.join(case_dir, 'bundle')
    shutil.copytree(BUNDLE, b)
    os.replace(os.path.join(b, 'bundle.json'), os.path.join(b, 'bundle.json.uncommitted'))
    return ['--db-bundle', b]


def bundle_tampered(case_dir):
    b = os.path.join(case_dir, 'bundle')
    shutil.copytree(BUNDLE, b)
    with open(os.path.join(b, 'stdout.tsv'), 'ab') as f:
        f.write(b'\n')
    return ['--db-bundle', b]


CASES = [
    # (이름, 인자 생성, 기대 verdict, 추가 확인)
    ('N1_full_normal_pass', lambda d: write_full(p0(), d), 'F3_PASS', {'p7_overall': 'OK', 'account_sum': '일치'}),
    ('C2_sum_equal_fingerprint_unconfirmed', lambda d: write_full(unconfirmed(p0()), d), 'F3_NOT_PASS',
     {'p7_overall': 'HOLD', 'account_sum': '일치', 'hold_code': 'RULESET_UNCONFIRMED'}),
    ('C3_balance_anomaly_and_closing_hold', lambda d: write_full(bad_balance_closing(p0()), d), 'F3_NOT_PASS',
     {'p7_overall': 'ANOMALY', 'anomaly_code': 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED', 'hold_code': 'POSITION_CLOSING'}),
    ('C4_db_partial_bundle_only', lambda d: ['--db-bundle', BUNDLE], 'NEEDS_MORE_SOURCES', {}),
    ('C5a_bundle_uncommitted', bundle_uncommitted, 'BUNDLE_REJECTED', {}),
    ('C5b_bundle_hash_mismatch', bundle_tampered, 'BUNDLE_REJECTED', {}),
]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    src = {'contract_probes_sha256': hashlib.sha256(open(PROBES, 'rb').read()).hexdigest(),
           'bundle_sums_sha256': hashlib.sha256(open(os.path.join(BUNDLE, 'SHA256SUMS'), 'rb').read()).hexdigest()}
    lines, summary = [], []
    for name, mk, verdict, more in CASES:
        d = os.path.join(run, name)
        os.makedirs(d)
        oj = os.path.join(d, 'out.json')
        p = subprocess.run([sys.executable, '-I', TOOL, *mk(d), '--out', oj], capture_output=True)
        open(os.path.join(d, 'rc.txt'), 'w', newline='\n').write(f'{p.returncode}\n')
        o = json.load(open(oj, encoding='utf-8'))
        checks = {'verdict': o['verdict'] == verdict, 'exit': p.returncode == o['exit'], 'stderr_empty': p.stderr == b''}
        if o.get('mode') == 'full':
            p7 = o['p7_output']
            checks['p7_and_f3_preserved'] = all(k in o for k in ('p7_output', 'p7_exit')) and set(o['f3']) == {'pass', 'reasons', 'exit'}
            checks['f3_reasons_iff_not_pass'] = bool(o['f3']['reasons']) == (verdict == 'F3_NOT_PASS')
            if 'p7_overall' in more:
                checks['p7_overall'] = p7['overall'] == more['p7_overall']
            if 'account_sum' in more:
                checks['account_sum'] = p7['account_sum'].get('status') == more['account_sum']
            if 'hold_code' in more:
                checks['hold_code'] = more['hold_code'] in {h['code'] for h in p7['holds']}
            if 'anomaly_code' in more:
                checks['anomaly_code'] = more['anomaly_code'] in {x['code'] for x in p7['anomalies']}
        if verdict == 'NEEDS_MORE_SOURCES':
            checks['bundle_verified_first'] = o.get('bundle_verified') is True
            checks['no_p7_or_f3'] = 'p7_output' not in o and 'f3' not in o
            checks['lists_missing'] = {'db_fp_pre', 'exchange_before', 'app_code_sha'} <= set(o.get('needs_sources', []))
        ok = all(checks.values())
        summary.append({'case': name, 'ok': ok, 'rc': p.returncode, 'verdict': o['verdict'], 'checks': checks})
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} rc={p.returncode} {o['summary_line']}" + ('' if ok else f' {checks}'))
    n = sum(s['ok'] for s in summary)
    lines.append(f"{n}/{len(summary)} 기대대로 (5사례, 5번은 미확정·해시 불일치 두 변형), 원천 {src}")
    json.dump({'synthetic': True, 'sources': src, 'cases': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8',
              newline='\n'), ensure_ascii=False, indent=1)
    open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
