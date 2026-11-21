"""R2(W6 종료값 계약) 직접 영향 3경계 + 두 보완을 연결한 정상 통합 1회 — 총괄 v1.213 §4-2. 합성 입력만.
입력은 원본 cases_w6.build()(해시 확인)로 I1(정상 병행) 시나리오를 만든다.
실패 종료값은 함수 값을 바꾸지 않고 실제 OS 프로세스로 만든다: 사례 폴더의 감싸기 스크립트가 원본 모듈을 그대로 실행한 뒤
  E1 은 성공 JSON 을 둔 채 종료값만 실패로, E2 는 종료값 0 으로 끝내며 JSON 을 실패로 바꾸거나(E2a) 지운다(E2b).
E3 은 감싸기 없이 원본 W3 에 DB 부분 묶음을 넣어 계약대로의 종료10(NEEDS_MORE_SOURCES)을 낸다.
기존 W6 4사례·W1~W5 사례·PG·제품 회귀·빌드는 다시 돌리지 않는다.
사용: python -I cases_r2.py --run <고유호출명>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import orig  # noqa: E402

for _n in ('cases_w3', 'cases_w5'):   # cases_w6 이 import 하는 입력 생성기도 원본 체크섬으로 확인
    orig.verify(_n)
W6 = orig.load('cases_w6')
W4 = orig.load('cases_w4')
TOOL = os.path.join(HERE, 'run_offline_r2.py')

WRAP = '''import json, os, subprocess, sys
sys.dont_write_bytecode = True
REAL = {real!r}
p = subprocess.run([sys.executable, '-I', REAL, *sys.argv[1:]])
out = sys.argv[sys.argv.index('--out') + 1]
mode = {mode!r}
if mode == 'json_fail':
    o = json.load(open(out, encoding='utf-8'))
    o['verdict'], o['exit'] = 'MISMATCH', 20
    json.dump(o, open(out, 'w', encoding='utf-8', newline='\\n'), ensure_ascii=False, indent=1)
elif mode == 'json_remove':
    os.remove(out)
sys.exit({rc})
'''


def wrapper(d, key, mode, rc):
    real = orig.path(dict(w1='registry_f1', w3='check_parallel')[key])
    p = os.path.join(d, f'wrap_{key}.py')
    with open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write(f'# 시험용 감싸기(사례 {os.path.basename(d)}) — 원본 {os.path.basename(real)} 를 그대로 실행한 뒤 {mode}, 종료값 {rc}\n')
        f.write(WRAP.format(real=real, mode=mode, rc=rc))
    return p


LV, NA, ND, FA = 'LOCAL_VERIFIED', 'NEEDS_APPROVAL', 'NEEDS_DATA', 'FAILED'
# (이름, 감싸기 {모듈: (mode, rc)}, W3 를 DB 부분 묶음으로, 기대 전체, 기대 모듈 상태, 기대 계약 코드, W2 P7 인용 여부)
CASES = [
    ('E1a_w1_success_json_rc42', {'w1': ('none', 42)}, False, FA, {'w1': FA, 'w2': LV, 'w3': LV, 'w4': LV, 'w5': NA},
     {'w1': 'EXIT_CONTRACT_MISMATCH'}, True),
    ('E1b_w3_success_json_rc40', {'w3': ('none', 40)}, False, FA, {'w1': LV, 'w2': None, 'w3': FA, 'w4': LV, 'w5': NA},
     {'w3': 'EXIT_CONTRACT_MISMATCH'}, False),
    ('E2a_w1_rc0_failed_json', {'w1': ('json_fail', 0)}, False, FA, {'w1': FA, 'w2': LV, 'w3': LV, 'w4': LV, 'w5': NA},
     {'w1': 'EXIT_CONTRACT_MISMATCH'}, True),
    ('E2b_w1_rc0_json_missing', {'w1': ('json_remove', 0)}, False, FA, {'w1': FA, 'w2': LV, 'w3': LV, 'w4': LV, 'w5': NA},
     {'w1': 'OUTPUT_INCOMPLETE'}, True),
    ('E3_w3_contract_exit10', {}, True, ND, {'w1': LV, 'w2': None, 'w3': ND, 'w4': LV, 'w5': NA}, {}, False),
    ('N1_normal_integrated', {}, False, NA, {'w1': LV, 'w2': LV, 'w3': LV, 'w4': LV, 'w5': NA}, {}, True),
]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', required=True)
    a = ap.parse_args(argv)
    run = os.path.join(ROOT, 'runs', a.run)
    os.makedirs(run, exist_ok=False)
    R2 = __import__('run_offline_r2')
    lines, summary = [], []
    for name, wraps, bundle, overall, mods, codes, quoted in CASES:
        d = os.path.join(run, name)
        sp = W6.build(os.path.join(d, 'in'), name)
        if bundle:
            sc = json.load(open(sp, encoding='utf-8'))
            # r2-run1: bundle.json 파일 경로를 넘긴 시험 입력 결함(W3 는 묶음 폴더를 받음 → BUNDLE_REJECTED 30, 계약 일치) → 폴더로 수정
            sc['w3'] = {'db_bundle': os.path.relpath(os.path.dirname(W4.BUNDLE), os.path.dirname(sp))}
            json.dump(sc, open(sp, 'w', encoding='utf-8', newline='\n'), ensure_ascii=False, indent=1)
        modules = R2.default_modules()
        for k, (mode, rc) in wraps.items():
            modules[k] = wrapper(d, k, mode, rc)
        out = os.path.join(d, 'out')
        if wraps:
            # 감싸기 경로는 시험 전용 인자(modules)로만 넘긴다 — 실행기 명령줄에는 모듈 경로를 바꾸는 옵션이 없다
            code = f"import sys; sys.dont_write_bytecode=True; sys.path.insert(0, {HERE!r}); import run_offline_r2 as R; " \
                   f"sys.exit(R.main(['--scenario', {sp!r}, '--out', {out!r}], modules={modules!r}))"
            p = subprocess.run([sys.executable, '-I', '-c', code], capture_output=True)
        else:
            p = subprocess.run([sys.executable, '-I', TOOL, '--scenario', sp, '--out', out], capture_output=True)
        open(os.path.join(d, 'rc.txt'), 'w', newline='\n').write(f'{p.returncode}\n')
        open(os.path.join(d, 'stderr.txt'), 'wb').write(p.stderr)
        s = json.load(open(os.path.join(out, 'summary.json'), encoding='utf-8'))
        got = {k: m['status'] for k, m in s.get('modules', {}).items()}
        c = {'overall': s.get('overall') == overall, 'exit': p.returncode == s.get('exit'), 'stderr_empty': p.stderr == b'',
             'operational_ready_false': s.get('operational_ready') is False,
             'modules': all(got.get(k) == v for k, v in mods.items() if v is not None),
             'contract_codes': all(s['modules'][k].get('contract') == v for k, v in codes.items()),
             'others_contract_ok': all(m.get('contract') == 'OK' for k, m in s['modules'].items() if k not in codes),
             'p7_quoted': s.get('w2_p7_quoted') is quoted,
             'quote_not_ok_when_not_quoted': quoted or (s.get('w2_account_quote') or {}).get('p7_overall') != 'OK',
             'local_success': s.get('local_success') is (overall != FA)}
        for k in codes:   # 깨진 모듈의 원문(JSON·stdout·stderr·실제 rc) 보존
            md = os.path.join(out, k)
            c[f'{k}_raw_kept'] = all(os.path.exists(os.path.join(md, n)) for n in ('rc.txt', 'stdout.txt', 'stderr.txt')) and \
                (codes[k] == 'OUTPUT_INCOMPLETE' or os.path.exists(os.path.join(md, 'out.json')))
        if not quoted and 'w3' in codes:
            c['no_p7_from_broken_w3'] = not os.path.exists(os.path.join(out, 'w3', 'p7.json'))
        ok = all(v is True for v in c.values())
        summary.append({'case': name, 'ok': ok, 'rc': p.returncode, 'overall': s.get('overall'), 'modules': got, 'checks': c})
        lines.append(f"{'PASS' if ok else 'FAIL'} {name} rc={p.returncode} 전체 {s.get('overall')} {got}" + ('' if ok else f' {c}'))
    n = sum(x['ok'] for x in summary)
    lines.append(f'{n}/{len(summary)} 기대대로 (R2 3경계·5변형 + 정상 통합 1회, 합성 입력, 운영 준비 표시 0)')
    json.dump({'synthetic': True, 'cases': summary}, open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n'),
              ensure_ascii=False, indent=1)
    open(os.path.join(run, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines))
    return 0 if n == len(summary) else 1


if __name__ == '__main__':
    sys.exit(main())
