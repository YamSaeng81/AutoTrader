"""F3 v9 직접 파일 대조(총괄 v1.207 §3-1). 정상 P0(cases_v8 의 V8 출력)과 총괄 결함 9종을 파일로 써서 CLI 로 판정한다."""
import copy, importlib.util, json, os, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__)); EV = os.path.dirname(HERE)
def load(p, n):
    s = importlib.util.spec_from_file_location(n, p); m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m
pc = load(os.path.join(EV, 'dec-registry-p7v8-20261120/p7v8/cases_v8.py'), 'c8')
F9 = os.path.join(HERE, 'f3v9/f3.py'); f9 = load(F9, 'f3v9')
base = pc.ev(pc.V8, pc.P0)
cases = [('정상', None, None)] + [(f, f, v) for f, v in [('code', ['x']), ('exchange_coverage', ['x']), ('account', ['x']),
         ('account_sum', ['x']), ('anomalies', ['x']), ('holds', ['x']), ('observation', []), ('observation_stable', 'true')]] \
        + [('exchange_coverage.window', 'window', ['x'])]
tmp = tempfile.mkdtemp(); out = []; ok_all = True
def cli(path):
    r = subprocess.run([sys.executable, '-I', F9, '--p7', path], capture_output=True)
    return r.returncode, json.loads(r.stdout.decode('utf-8'))
for name, field, val in cases:
    o = copy.deepcopy(base)
    if field == 'window': o['exchange_coverage']['window'] = val
    elif field: o[field] = val
    p = os.path.join(tmp, 'in.json'); open(p, 'w', encoding='utf-8').write(json.dumps(o, ensure_ascii=False))
    rc, res = cli(p); want = 0 if field is None else 1
    good = rc == want and (res['f3'] == ('통과' if want == 0 else '불통과')) and (want == 0) == (res['reasons'] == [])
    ok_all &= good; out.append({'case': name, 'rc': rc, 'want_rc': want, 'result': res, 'ok': good})
for name, data in [('JSON 아님', b'{bad'), ('UTF-8 아님', b'\xff\xfe')]:
    p = os.path.join(tmp, 'bad.json'); open(p, 'wb').write(data)
    rc, res = cli(p); good = rc == 2 and res['f3'] == '읽기 실패'; ok_all &= good
    out.append({'case': name, 'rc': rc, 'want_rc': 2, 'result': res, 'ok': good})
rc, res = cli(os.path.join(tmp, 'missing.json')); good = rc == 2; ok_all &= good
out.append({'case': '파일 없음', 'rc': rc, 'want_rc': 2, 'result': res, 'ok': good})
res_all = {'all_ok': ok_all, 'passed': sum(x['ok'] for x in out), 'total': len(out),
           'normal_v8_v9_same': f9.judge(base) == pc.F8.judge(base), 'cases': out}
with open(os.path.join(HERE, 'probe-v9.out.json'), 'w', encoding='utf-8', newline='\n') as fh:
    fh.write(json.dumps(res_all, ensure_ascii=False, indent=1) + '\n')
sys.exit(0 if ok_all else 1)
