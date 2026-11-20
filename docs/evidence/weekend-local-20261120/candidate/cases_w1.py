"""W1 직접 영향 사례 실행기 — 합성 입력만. 각 사례 입력·출력·종료값을 runs/<호출명>/ 아래에 남긴다(덮어쓰기 금지).
사용: python -I cases_w1.py --run <고유호출명>   종료값: 전부 기대대로 0, 하나라도 다르면 1.
"""
import argparse, copy, hashlib, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REG = os.path.join(ROOT, 'fixtures', 'w1', 'registry.json')
TOOL = os.path.join(HERE, 'registry_f1.py')

S203 = {'kind': 'LIVE', 'id': 203, 'mode': 'REAL', 'status': 'CREATED', 'strategy_type': 'DAILY_SMA20', 'timeframe': 'H1',
        'coin_pair': 'KRW-BTC', 'created_at': '2026-10-30T04:16:58.044684+00:00'}
D900 = {'kind': 'DYNAMIC', 'id': 900, 'mode': 'REAL', 'status': 'RUNNING', 'strategy_type': 'COMPOSITE_BREAKOUT', 'timeframe': 'H1',
        'coin_pair': None, 'created_at': '2026-12-01T00:05:00+00:00'}
D92 = {'kind': 'DYNAMIC', 'id': 92, 'mode': 'REAL', 'status': 'EMERGENCY_STOPPED', 'strategy_type': 'COMPOSITE_BREAKOUT',
       'timeframe': 'H1', 'coin_pair': None, 'created_at': '2026-10-01T00:00:00+00:00'}


def obs(*ss):
    return {'observation_version': 'f1-obs-1', 'synthetic': True, 'source_note': '합성(W1) — 운영 관측 아님',
            'sessions': [copy.deepcopy(s) for s in ss]}


def keys(lst):
    return sorted((x['kind'], x['id']) for x in lst)


CASES = []


def case(name, ob, verdict, missing=(), extra=(), fixed=(), reg_sha=None, note=''):
    CASES.append(dict(name=name, ob=ob, verdict=verdict, missing=sorted(missing), extra=sorted(extra), fixed=sorted(fixed),
                      reg_sha=reg_sha, note=note))


case('N1_normal_live1_dynamic1', obs(S203, D900, D92), 'SET_MATCH', note='고정1+동적1, 비활성 비상정지 DYNAMIC 92 는 기대 집합 밖')
case('C2_same_count_other_id', obs(S203, dict(D900, id=901), D92), 'MISMATCH', missing=[('DYNAMIC', 900)], extra=[('DYNAMIC', 901)],
     note='활성 REAL 개수 2=2 지만 ID 다름')
case('C3_unregistered_real_added', obs(S203, D900, dict(D900, id=94, created_at='2026-12-02T00:00:00+00:00')), 'MISMATCH',
     extra=[('DYNAMIC', 94)])
case('C4_registered_missing', obs(S203, dict(D900, status='STOPPED')), 'MISMATCH', missing=[('DYNAMIC', 900)])
case('C5_paper_mixed', obs(S203, dict(D900, mode='PAPER'), dict(D900, id=522, kind='DYNAMIC', mode='PAPER')), 'MISMATCH',
     missing=[('DYNAMIC', 900)], note='등록 DYNAMIC 900 이 PAPER 로 관측 — 활성 개수는 같아도 REAL 이 아님')
case('C6_fixed_condition_mismatch', obs(dict(S203, mode='real'), dict(D900, timeframe='M15')), 'MISMATCH',
     fixed=[('DYNAMIC', 900), ('LIVE', 203)], note='소문자 real(제품은 REAL 분기)·주기 M15')
# 입력 계약 보조 확인 2건(직접 영향 6사례와 별도 표시)
case('G1_registry_hash_mismatch', obs(S203, D900), 'INVALID', reg_sha='0' * 64)
amt = obs(S203, D900)
amt['sessions'][1]['available_krw'] = '987654.32'
case('G2_amount_field_rejected', amt, 'INVALID', note='금액 필드 값은 결과에 나오면 안 됨')


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    reg_sha = hashlib.sha256(open(REG, 'rb').read()).hexdigest()
    lines, ok_all, summary = [], True, []
    for c in CASES:
        d = os.path.join(run, c['name'])
        os.makedirs(d)
        op = os.path.join(d, 'observed.json')
        with open(op, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(c['ob'], f, ensure_ascii=False, indent=1)
        outp = os.path.join(d, 'out.json')
        p = subprocess.run([sys.executable, '-I', TOOL, '--registry', REG, '--registry-sha256', c['reg_sha'] or reg_sha,
                            '--observed', op, '--out', outp], capture_output=True)
        with open(os.path.join(d, 'rc.txt'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(f'{p.returncode}\n')
        o = json.load(open(outp, encoding='utf-8'))
        raw = open(outp, encoding='utf-8').read()
        checks = {
            'verdict': o['verdict'] == c['verdict'],
            'exit': p.returncode == o['exit'] == {'SET_MATCH': 0, 'HOLD': 10, 'MISMATCH': 20, 'INVALID': 30}[c['verdict']],
            'f1_overall_not_pass': o['f1_overall'] == 'NOT_DETERMINED_BY_THIS_TOOL',
            'pass_mark_only_on_match': ('집합 일치' in o['summary_line']) == (c['verdict'] == 'SET_MATCH'),
            'stderr_empty': p.stderr == b'',
        }
        if c['verdict'] != 'INVALID':
            checks['missing'] = keys(o['missing']) == c['missing']
            checks['extra'] = keys(o['extra']) == c['extra']
            checks['fixed'] = keys(o['fixed_mismatch']) == c['fixed']
        if c['name'].startswith('G2'):
            checks['amount_value_not_echoed'] = '987654' not in raw
        ok = all(checks.values())
        ok_all &= ok
        summary.append({'case': c['name'], 'ok': ok, 'rc': p.returncode, 'verdict': o['verdict'], 'checks': checks, 'note': c['note']})
        lines.append(f"{'PASS' if ok else 'FAIL'} {c['name']} rc={p.returncode} {o['summary_line']}")
    n = sum(s['ok'] for s in summary)
    lines.append(f'{n}/{len(summary)} 기대대로 (직접 영향 6 + 입력 계약 보조 2), 등록표 sha256 {reg_sha}')
    with open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump({'synthetic': True, 'registry_sha256': reg_sha, 'cases': summary}, f, ensure_ascii=False, indent=1)
    with open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if ok_all else 1


if __name__ == '__main__':
    sys.exit(main())
