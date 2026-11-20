"""W5 직접 영향 5사례(+현재 실제 운용안 골격 1) — 합성 입력만. 요청은 보내지 않는다.
runs/<호출명>/ 에 사례별 계획·출력·종료값을 남긴다(덮어쓰기 금지). 승인 참조·포지션당 상한 20000·관측 SHA 는 합성이다.
사용: python -I cases_w5.py --run <고유호출명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, copy, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FX = os.path.join(ROOT, 'fixtures', 'w5')
DTO = os.path.join(FX, 'DynamicSessionRequest.d81c3f2f.java.txt')
LIM = os.path.join(FX, 'DynamicEntryLimits.d81c3f2f.java.txt')
TOOL = os.path.join(HERE, 'check_dynamic_plan.py')

OK = {
    'plan_version': 'dynamic-plan-1', 'synthetic': True,
    'request': {'strategyType': 'COMPOSITE_BREAKOUT', 'timeframe': 'H1', 'initialCapital': '20000', 'investRatio': 80,
                'stopLossPct': '5.0', 'tradingMode': 'REAL',
                'strategyParams': {'maxOrderKrw': 10000, 'excludeCoins': ['KRW-BTC']}},
    'approval': {'capital_ref': 'SYN-APPROVAL-CAPITAL(합성)', 'loss_limit_ref': 'SYN-APPROVAL-LOSS(합성)',
                 'strategy_ref': 'SYN-APPROVAL-STRATEGY(합성)', 'synthetic': True},
    'deploy': {'observed_code_sha': 'd81c3f2f', 'source': '합성 관측'},
    'server_limits': {'per_position_cap_krw': '20000', 'source': '합성 — RUNBOOK STAGE_1 포지션당 20000 표기를 시험값으로만 사용'},
    'live_link': {'kind': 'LIVE', 'id': 203, 'create': False},
}


def v(f):
    d = copy.deepcopy(OK)
    f(d)
    return d


ACTUAL_SKELETON = {   # 현시점 실제 운용안: 자본·손실 상한·전략·배정이 사용자 결정 전 → 값 없음
    'plan_version': 'dynamic-plan-1', 'synthetic': False,
    'request': {'strategyType': None, 'timeframe': None, 'initialCapital': None, 'investRatio': None, 'tradingMode': 'REAL',
                'strategyParams': {'maxOrderKrw': None, 'excludeCoins': ['KRW-BTC']}},
    'approval': {'capital_ref': None, 'loss_limit_ref': None, 'strategy_ref': None, 'synthetic': False},
    'deploy': {'observed_code_sha': None, 'source': '운영 코드 관측 없음(이번 작업에서 운영 접속 안 함)'},
    'server_limits': None, 'live_link': {'kind': 'LIVE', 'id': 203, 'create': False},
}

CASES = [
    ('N1_format_ok_synthetic_approval', OK, 'FORMAT_OK_NOT_APPROVED', set(), set()),
    ('C2_approval_value_undecided', v(lambda d: d['approval'].update(capital_ref=None, loss_limit_ref=None)), 'NOT_READY',
     set(), {'APPROVAL_VALUE_UNDECIDED'}),
    ('C3a_mode_missing', v(lambda d: d['request'].pop('tradingMode')), 'REJECT', {'TRADING_MODE_INVALID'}, set()),
    ('C3b_mode_lowercase', v(lambda d: d['request'].update(tradingMode='real')), 'REJECT', {'TRADING_MODE_INVALID'}, set()),
    ('C4a_limits_pair_incomplete', v(lambda d: d['request']['strategyParams'].pop('excludeCoins')), 'REJECT',
     {'ENTRY_LIMITS_PAIR_INCOMPLETE'}, set()),
    ('C4b_max_order_above_cap', v(lambda d: d['request']['strategyParams'].update(maxOrderKrw=25000)), 'REJECT',
     {'MAX_ORDER_ABOVE_SERVER_CAP'}, set()),
    ('C5_observed_code_0e1d2a02', v(lambda d: d['deploy'].update(observed_code_sha='0e1d2a02')), 'NOT_READY',
     set(), {'DEPLOY_DOES_NOT_SUPPORT'}),
    # w5-run1 에서 골격이 REJECT 로 나온 것(미정 null 을 형식 위반으로 섞음)을 고친 뒤 기대를 NOT_READY 로 고정
    ('R0_actual_plan_skeleton', ACTUAL_SKELETON, 'NOT_READY', set(),
     {'APPROVAL_VALUE_UNDECIDED', 'DEPLOY_UNOBSERVED', 'VALUE_UNDECIDED', 'INVEST_RATIO_DEFAULT', 'SERVER_CAP_UNKNOWN'}),
]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    lines, summary = [], []
    for name, plan, verdict, rej, nr in CASES:
        d = os.path.join(run, name)
        os.makedirs(d)
        pp, oj = os.path.join(d, 'plan.json'), os.path.join(d, 'out.json')
        with open(pp, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(plan, f, ensure_ascii=False, indent=1)
        p = subprocess.run([sys.executable, '-I', TOOL, '--plan', pp, '--dto-java', DTO, '--limits-java', LIM, '--out', oj],
                           capture_output=True)
        open(os.path.join(d, 'rc.txt'), 'w', newline='\n').write(f'{p.returncode}\n')
        o = json.load(open(oj, encoding='utf-8'))
        rc = {x['code'] for x in o.get('reject', [])}
        nc = {x['code'] for x in o.get('not_ready', [])}
        checks = {'exit': p.returncode == o['exit'], 'stderr_empty': p.stderr == b'', 'not_sent': o['sent_request'] is False}
        if verdict is None:   # 실제 운용안 골격: 통과가 아니면 된다(REJECT 또는 NOT_READY), 미정 항목이 사유로 보여야 한다
            checks['not_ready_or_reject'] = o['verdict'] in ('NOT_READY', 'REJECT')
            checks['undecided_listed'] = nr <= nc
        else:
            checks['verdict'] = o['verdict'] == verdict
            checks['reject_codes'] = rc == rej
            checks['not_ready_codes'] = nr <= nc and (bool(nc) == bool(nr) or verdict == 'REJECT')
        if name.startswith('N1'):
            checks['wording'] = o['summary_line'].startswith('로컬 형식 점검 완료·실제 실행 승인 전')
            checks['ratio_80_is_0_80'] = o['derived']['effective_invest_ratio'] == '0.8'
            checks['contract_from_java'] = o['server_contract']['limit_keys'] == ['maxOrderKrw', 'excludeCoins'] and \
                'tradingMode' in o['server_contract']['dto_fields'] and 'maxOrderKrw' not in o['server_contract']['dto_fields']
        ok = all(checks.values())
        summary.append({'case': name, 'ok': ok, 'rc': p.returncode, 'verdict': o['verdict'], 'reject': sorted(rc),
                        'not_ready': sorted(nc), 'checks': checks})
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} rc={p.returncode} {o['summary_line']}" + ('' if ok else f' {checks}'))
    n = sum(s['ok'] for s in summary)
    lines.append(f'{n}/{len(summary)} 기대대로 (직접 영향 5사례 7변형 + 실제 운용안 골격 1, 요청 전송 0)')
    json.dump({'synthetic': True, 'cases': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1)
    open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
