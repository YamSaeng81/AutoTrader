"""W6 통합 4사례 — 합성 입력만. 한 시나리오의 W1·W2·W3 은 같은 P0(시험용 가상 정상 입력)에서 파생한 등록표·관측을 쓴다.
W1 관측·W2 상태는 P0 현재 관측에서 금액 필드를 뺀 사본이다. W4 는 12월 월 성적표(cases_w4 의 같은 이름 사례 입력), W5 는 cases_w5 형식 정상안.
사용: python -I cases_w6.py --run <고유호출명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import cases_w3, cases_w4, cases_w5  # noqa: E402  (입력 생성 함수만 재사용)

TOOL = os.path.join(HERE, 'run_offline.py')
OBS_KEYS = ('kind', 'id', 'mode', 'status', 'strategy_type', 'timeframe', 'coin_pair', 'created_at')


def w(path, obj):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    return os.path.basename(path)


def unregistered(d):
    s = dict(d['now']['db']['sessions'][0], id=94, created_at='2026-11-19T00:00:00+00:00')   # 합성: 등록 밖 DYNAMIC REAL 가동
    d['now']['db']['sessions'].append(s)
    return d


def build(sd, name, mutate=None, w4_case='N1_normal_round_trip'):
    os.makedirs(sd)
    d = cases_w3.p0()
    if mutate:
        mutate(d)
    for k in ('registry', 'base', 'now'):
        w(os.path.join(sd, f'{k}.json'), d[k])
    reg_sha = hashlib.sha256(open(os.path.join(sd, 'registry.json'), 'rb').read()).hexdigest()
    now = d['now']['db']
    w(os.path.join(sd, 'f1_observed.json'), {'observation_version': 'f1-obs-1', 'synthetic': True, 'source_note': f'합성(W6 {name}) — P0 현재 관측에서 금액 제거',
                                            'sessions': [{k: s[k] for k in OBS_KEYS} for s in now['sessions']]})
    w(os.path.join(sd, 'n03_state.json'), {
        'input_version': 'parallel-in-1', 'synthetic': True, 'source_note': f'합성(W6 {name}) — P0 현재 관측에서 금액 제거', 'observed_label': name,
        'sessions': [{k: s[k] for k in ('kind', 'id', 'mode', 'status')} for s in now['sessions']],
        'orders': [{'kind': o['session_kind'], 'session_id': o['session_id'], 'order_id': o['id'], 'side': o['side'],
                    'coin_pair': o['coin_pair'], 'state': o['state']} for o in now['orders']],
        'positions': [{'kind': p['session_kind'], 'session_id': p['session_id'], 'position_id': p['id'], 'coin_pair': p['coin_pair'],
                       'status': p['status']} for p in now['positions']],
        'settlement_unresolved': 0, 'p0_events': [], 'error_logs': [], 'paper_522_587': {'total': 66, 'running': 63, 'not_running': 3}})
    w4dir = os.path.join(sd, 'w4in')
    os.makedirs(w4dir)
    man = next(c[1] for c in cases_w4.CASES if c[0] == w4_case)
    mp = cases_w4.write_case(w4dir, man)
    w(os.path.join(sd, 'dynamic_plan.json'), cases_w5.OK)
    sc = {'scenario_version': 'offline-scenario-1', 'name': name, 'synthetic': True,
          'w1': {'registry': 'registry.json', 'registry_sha256': reg_sha, 'observed': 'f1_observed.json'},
          'w2': {'registry': 'registry.json', 'registry_sha256': reg_sha, 'state': 'n03_state.json', 'p7_from_w3': True},
          'w3': {'registry': 'registry.json', 'base': 'base.json', 'now': 'now.json'},
          'w4': {'manifest': os.path.relpath(mp, sd)},
          'w5': {'plan': 'dynamic_plan.json', 'dto_java': os.path.relpath(cases_w5.DTO, sd), 'limits_java': os.path.relpath(cases_w5.LIM, sd)}}
    w(os.path.join(sd, 'scenario.json'), sc)
    return os.path.join(sd, 'scenario.json')


LV, NA, ND, FA = 'LOCAL_VERIFIED', 'NEEDS_APPROVAL', 'NEEDS_DATA', 'FAILED'
CASES = [
    ('I1_normal_parallel', None, 'N1_normal_round_trip', NA, {'w1': LV, 'w2': LV, 'w3': LV, 'w4': LV, 'w5': NA}, True),
    ('I2_unregistered_real', unregistered, 'N1_normal_round_trip', FA, {'w1': FA, 'w2': FA, 'w3': FA, 'w4': LV, 'w5': NA}, False),
    ('I3_account_anomaly_and_hold', cases_w3.bad_balance_closing, 'N1_normal_round_trip', FA,
     {'w1': LV, 'w2': FA, 'w3': FA, 'w4': LV, 'w5': NA}, False),
    ('I4_time_cost_unconfirmed', None, 'C2_buy_cost_unconfirmed', ND, {'w1': LV, 'w2': LV, 'w3': LV, 'w4': ND, 'w5': NA}, True),
]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    lines, summary = [], []
    for name, mut, w4c, overall, mods, local_ok in CASES:
        sp = build(os.path.join(run, name, 'in'), name, mut, w4c)
        out = os.path.join(run, name, 'out')
        p = subprocess.run([sys.executable, '-I', TOOL, '--scenario', sp, '--out', out], capture_output=True)
        open(os.path.join(run, name, 'rc.txt'), 'w', newline='\n').write(f'{p.returncode}\n')
        s = json.load(open(os.path.join(out, 'summary.json'), encoding='utf-8'))
        got = {k: m['status'] for k, m in s.get('modules', {}).items()}
        checks = {'overall': s['overall'] == overall, 'modules': got == mods, 'local_success': s.get('local_success') == local_ok,
                  'operational_ready_false': s.get('operational_ready') is False, 'exit': p.returncode == s['exit'],
                  'stderr_empty': p.stderr == b'',
                  'one_approval_per_step': len(s.get('next_approvals', [])) == 5}
        q = s.get('w2_account_quote') or {}
        # 막힌 앞 단계가 뒤 단계의 성공으로 보이지 않는지: W3 가 불통과면 W2 인용도 P7 OK·일치가 아니어야 한다
        checks['w2_quote_consistent'] = (q.get('p7_overall') == 'OK') == (got.get('w3') == LV)
        ok = all(checks.values())
        summary.append({'case': name, 'ok': ok, 'rc': p.returncode, 'overall': s['overall'], 'modules': got, 'checks': checks,
                        'w2_account_quote': q})
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} rc={p.returncode} 전체 {s['overall']} {got}" + ('' if ok else f' {checks}'))
    n = sum(x['ok'] for x in summary)
    lines.append(f'{n}/{len(summary)} 기대대로 (통합 4사례, 합성 입력, 운영 준비 표시 0)')
    json.dump({'synthetic': True, 'cases': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1)
    open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
