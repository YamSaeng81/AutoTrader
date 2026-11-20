"""W2 직접 영향 6사례 — 합성 입력만. runs/<호출명>/ 에 사례별 입력·JSON·Markdown·종료값을 남긴다(덮어쓰기 금지).
사용: python -I cases_w2.py --run <고유호출명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, copy, hashlib, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REG = os.path.join(ROOT, 'fixtures', 'w1', 'registry.json')   # W1 과 같은 합성 등록표(LIVE 203 + DYNAMIC 900)
P7 = os.path.join(ROOT, 'fixtures', 'w2', 'p7_result_synthetic.json')
TOOL = os.path.join(HERE, 'parallel_report.py')

BASE = {
    'input_version': 'parallel-in-1', 'synthetic': True, 'source_note': '합성(W2) — 운영 관측 아님', 'observed_label': 'W2-합성',
    'sessions': [{'kind': 'LIVE', 'id': 203, 'mode': 'REAL', 'status': 'RUNNING'},
                 {'kind': 'DYNAMIC', 'id': 900, 'mode': 'REAL', 'status': 'RUNNING'},
                 {'kind': 'DYNAMIC', 'id': 522, 'mode': 'PAPER', 'status': 'RUNNING'}],
    'orders': [{'kind': 'DYNAMIC', 'session_id': 900, 'order_id': 1, 'side': 'BUY', 'coin_pair': 'KRW-ETH', 'state': 'FILLED'},
               {'kind': 'DYNAMIC', 'session_id': 522, 'order_id': 2, 'side': 'BUY', 'coin_pair': 'KRW-XRP', 'state': 'FILLED'}],
    'positions': [{'kind': 'DYNAMIC', 'session_id': 900, 'position_id': 11, 'coin_pair': 'KRW-ETH', 'status': 'OPEN'}],
    'settlement_unresolved': 0, 'p0_events': [], 'error_logs': [],
    'paper_522_587': {'total': 66, 'running': 63, 'not_running': 3},
}


def var(f):
    d = copy.deepcopy(BASE)
    f(d)
    return d


CASES = [
    ('N1_normal_parallel', BASE, 'NO_RISK_ITEMS', [], 'DYNAMIC 900 ETH 매수·보유가 등록 DYNAMIC REAL 로 분류(무단 실자금 아님)'),
    ('C2_unregistered_real', var(lambda d: (d['sessions'].append({'kind': 'DYNAMIC', 'id': 94, 'mode': 'REAL', 'status': 'RUNNING'}),
                                          d['orders'].append({'kind': 'DYNAMIC', 'session_id': 94, 'order_id': 3, 'side': 'BUY',
                                                              'coin_pair': 'KRW-SOL', 'state': 'SUBMITTED'}))),
     'RISK_PRESENT', ['UNREGISTERED_REAL_ORDER', 'UNREGISTERED_REAL_SESSION_ACTIVE'], ''),
    ('C3_registered_session_unknown', var(lambda d: d['orders'].append({'kind': 'DYNAMIC', 'session_id': 900, 'order_id': 4, 'side': 'SELL',
                                                                        'coin_pair': 'KRW-ETH', 'state': 'UNKNOWN'})),
     'RISK_PRESENT', ['ORDER_UNKNOWN'], '등록 세션이어도 UNKNOWN 은 위험으로 남음'),
    ('C4_closing', var(lambda d: d['positions'][0].update(status='CLOSING')), 'RISK_PRESENT', ['POSITION_CLOSING_PENDING'], ''),
    ('C5_p0_and_news_error', var(lambda d: (d['p0_events'].append({'code': 'DB_CONN_TIMEOUT', 'label': '연결 획득 시간 초과(합성)'}),
                                           d['error_logs'].append({'label': 'NewsCollector ERROR(합성 원문 표지)', 'count': 7}))),
     'RISK_PRESENT', ['P0', 'UNCLASSIFIED_ERROR'], '뉴스 오류는 원문 표지 그대로 미분류 ERROR'),
    ('C6_forbidden_performance_field', var(lambda d: d['paper_522_587'].update(pnl_krw='-123456.78')), 'INVALID', [],
     '성과 필드 거절, 값 미출력'),
]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    reg_sha = hashlib.sha256(open(REG, 'rb').read()).hexdigest()
    p7_sha = hashlib.sha256(open(P7, 'rb').read()).hexdigest()
    lines, summary = [], []
    for name, st, verdict, codes, note in CASES:
        d = os.path.join(run, name)
        os.makedirs(d)
        sp = os.path.join(d, 'state.json')
        with open(sp, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(st, f, ensure_ascii=False, indent=1)
        oj, om = os.path.join(d, 'out.json'), os.path.join(d, 'out.md')
        p = subprocess.run([sys.executable, '-I', TOOL, '--registry', REG, '--registry-sha256', reg_sha, '--state', sp,
                            '--p7-result', P7, '--p7-result-sha256', p7_sha, '--out-json', oj, '--out-md', om], capture_output=True)
        open(os.path.join(d, 'rc.txt'), 'w', newline='\n').write(f'{p.returncode}\n')
        o = json.load(open(oj, encoding='utf-8'))
        raw = open(oj, encoding='utf-8').read() + open(om, encoding='utf-8').read()
        checks = {'verdict': o['verdict'] == verdict, 'exit': p.returncode == o['exit'], 'stderr_empty': p.stderr == b''}
        if verdict != 'INVALID':
            checks['risk_codes'] = sorted({r['code'] for r in o['risks']}) == sorted(codes)
            g = o['groups']
            checks['eth_buy_registered_dynamic'] = any(x['order_id'] == 1 for x in g['REGISTERED_DYNAMIC_REAL']['orders']) and \
                not any(x['order_id'] == 1 for x in g['UNREGISTERED_REAL']['orders'])
            checks['paper_excluded'] = not any(x.get('session_id') == 522 for c in g.values() for x in c['orders'])
            checks['p7_quoted_not_computed'] = o['account_check']['status'] == 'QUOTED'
            checks['no_green_on_risk'] = (o['verdict'] == 'NO_RISK_ITEMS') == (not o['risks'])
        else:
            checks['value_not_echoed'] = '123456' not in raw
            checks['field_named'] = 'pnl_krw' in raw
        ok = all(checks.values())
        summary.append({'case': name, 'ok': ok, 'rc': p.returncode, 'verdict': o['verdict'], 'checks': checks, 'note': note})
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} rc={p.returncode} {o['verdict']} "
                     f"{sorted({r['code'] for r in o.get('risks', [])})} {'' if ok else checks}")
    n = sum(s['ok'] for s in summary)
    lines.append(f'{n}/{len(summary)} 기대대로, 등록표 sha256 {reg_sha}, 합성 P7 결과 sha256 {p7_sha}')
    json.dump({'synthetic': True, 'cases': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1)
    open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
