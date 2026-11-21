"""W6 보완 w6-2(총괄 v1.213 §3 R2·§4-2). 원본 run_offline.py(w6-1, 59b80aa5)는 고치지 않고 분류표·승인 목록·Markdown 함수를 그대로 쓴다.

바뀐 계약 — 모듈마다 '실제 프로세스 종료값 · JSON 의 선언 exit · verdict 에 대한 모듈 종료값 표' 세 가지가 서로 맞아야 분류한다.
  · JSON 없음/읽기 실패/verdict 없음(기록 미완료)        → FAILED(OUTPUT_INCOMPLETE)
  · 실제 rc ≠ JSON exit, 또는 JSON exit ≠ 표[verdict]     → FAILED(EXIT_CONTRACT_MISMATCH). JSON 은 실패 원문으로 그대로 보존한다.
  · 셋이 맞으면 원본 분류표 그대로 — 계약대로인 종료10(보류/추가 원천 필요) 등은 FAILED 로 올리지 않는다.
  계약이 깨진 W3 의 P7 출력은 W2 계좌 인용 입력으로 쓰지 않는다(p7.json 을 만들지 않음). 모듈별 stdout·stderr·실제/선언 종료값을 남긴다.
모듈 호출 경로: W1·W2·W3·W5 는 원본 후보 파일(해시 확인)을 그대로, W4 는 R1 보완 build_monthly_r1.py.
입력·출력 형식은 원본과 같다(--scenario offline-scenario-1, --out 새 폴더). 종료값: 0 · 5 · 10 · 20 · 실행기 오류 40.
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import orig  # noqa: E402

TOOL, VERSION = 'run_offline_r2', 'w6-2'
# 모듈 종료값 표(각 후보 원문의 EXIT 와 같음 — 아래 module_exit_tables() 가 원본 파일에서 다시 읽어 대조)
EXIT_TABLE = {
    'w1': {'SET_MATCH': 0, 'HOLD': 10, 'MISMATCH': 20, 'INVALID': 30, 'ERROR': 40},
    'w2': {'NO_RISK_ITEMS': 0, 'RISK_PRESENT': 20, 'INVALID': 30, 'ERROR': 40},
    'w3': {'F3_PASS': 0, 'NEEDS_MORE_SOURCES': 10, 'F3_NOT_PASS': 20, 'BUNDLE_REJECTED': 30, 'ERROR': 40},
    'w4': {'BUILT_CORE_CONFIRMED': 0, 'BUILT_WITH_UNCONFIRMED': 10, 'PARTIAL': 20, 'INVALID': 30, 'ERROR': 40},
    'w5': {'FORMAT_OK_NOT_APPROVED': 0, 'NOT_READY': 10, 'REJECT': 20, 'INVALID': 30, 'ERROR': 40},
}
SRC = {'w1': 'registry_f1', 'w2': 'parallel_report', 'w3': 'check_parallel', 'w5': 'check_dynamic_plan'}


def default_modules():
    m = {k: orig.path(v) for k, v in SRC.items()}
    m['w4'] = os.path.join(HERE, 'build_monthly_r1.py')
    return m


def module_exit_tables():
    """원본 후보의 EXIT 사전을 해시 확인 뒤 읽어 위 표와 같은지 확인한다(표를 손으로 옮긴 오류 방지)."""
    bad = []   # W4 는 R1 보완(build_monthly_r1)이 원본 build_monthly 의 EXIT 를 그대로 쓴다
    for k, name in dict(SRC, w4='build_monthly').items():
        if orig.load(name).EXIT != EXIT_TABLE[k]:
            bad.append(k)
    if bad:
        raise orig.OrigError(f'모듈 종료값 표 불일치: {bad}')


def call(script, args, d, json_path):
    """모듈을 실행하고 실제 rc·stdout·stderr 를 남긴 뒤 JSON 을 읽는다. 읽기 실패는 None(기록 미완료)."""
    os.makedirs(d, exist_ok=True)
    p = subprocess.run([sys.executable, '-I', script, *args], capture_output=True)
    for n, b in (('stdout.txt', p.stdout), ('stderr.txt', p.stderr)):
        with open(os.path.join(d, n), 'wb') as f:
            f.write(b)
    with open(os.path.join(d, 'rc.txt'), 'w', newline='\n') as f:
        f.write(f'{p.returncode}\n')
    try:
        with open(json_path, encoding='utf-8') as f:
            o = json.load(f)
    except (OSError, ValueError):
        o = None
    return p.returncode, o


def contract(k, rc, o):
    """실제 rc · JSON exit · 표[verdict] 일치 여부. 반환 (ok, code, 설명)."""
    if not isinstance(o, dict) or 'verdict' not in o:
        return False, 'OUTPUT_INCOMPLETE', f'JSON 없음/읽기 실패/verdict 없음(실제 rc {rc})'
    v, je = o['verdict'], o.get('exit')
    te = EXIT_TABLE[k].get(v)
    if te is None:
        return False, 'EXIT_CONTRACT_MISMATCH', f'표에 없는 verdict {v!r}(실제 rc {rc}, JSON exit {je})'
    if not (rc == je == te):
        return False, 'EXIT_CONTRACT_MISMATCH', f'실제 rc {rc} · JSON exit {je} · 표[{v}] {te} 불일치'
    return True, None, f'실제 rc = JSON exit = 표[{v}] = {rc}'


def run(sc, base, out, modules=None):
    O = orig.load('run_offline')
    mods = modules or default_modules()
    rel = O.rel
    res = {}

    def rec(k, rc, o, extra):
        ok, code, why = contract(k, rc, o)
        r = {'rc': rc, 'json_exit': (o or {}).get('exit') if isinstance(o, dict) else None,
             'verdict': o.get('verdict') if isinstance(o, dict) else None, 'contract_ok': ok, 'contract_code': code, 'contract': why}
        if ok:
            r.update(extra(o))
        res[k] = r
        return ok

    w = sc.get('w3')
    if w:
        d = os.path.join(out, 'w3')
        args = ['--db-bundle', rel(base, w['db_bundle'])] if 'db_bundle' in w else \
            ['--registry', rel(base, w['registry']), '--base', rel(base, w['base']), '--now', rel(base, w['now'])]
        rc, o = call(mods['w3'], args + ['--out', os.path.join(d, 'out.json')], d, os.path.join(d, 'out.json'))
        if rec('w3', rc, o, lambda o: {'detail': o.get('f3', {}).get('reasons') or o.get('needs_sources') or o.get('bundle_reason')}) \
                and 'p7_output' in o:
            O.pins.dump(o['p7_output'], os.path.join(d, 'p7.json'))   # 계약이 맞을 때만 W2 인용 입력을 만든다
    w = sc.get('w1')
    if w:
        d = os.path.join(out, 'w1')
        rc, o = call(mods['w1'], ['--registry', rel(base, w['registry']), '--registry-sha256', w['registry_sha256'],
                                  '--observed', rel(base, w['observed']), '--out', os.path.join(d, 'out.json')], d, os.path.join(d, 'out.json'))
        rec('w1', rc, o, lambda o: {'detail': o.get('summary_line')})
    w = sc.get('w2')
    if w:
        d = os.path.join(out, 'w2')
        args = ['--registry', rel(base, w['registry']), '--registry-sha256', w['registry_sha256'], '--state', rel(base, w['state']),
                '--out-json', os.path.join(d, 'out.json'), '--out-md', os.path.join(d, 'out.md')]
        p7 = os.path.join(out, 'w3', 'p7.json')
        quoted = bool(w.get('p7_from_w3') and os.path.exists(p7))
        if quoted:
            args += ['--p7-result', p7, '--p7-result-sha256', hashlib.sha256(open(p7, 'rb').read()).hexdigest()]
        rc, o = call(mods['w2'], args, d, os.path.join(d, 'out.json'))
        rec('w2', rc, o, lambda o: {'detail': sorted({r['code'] for r in o.get('risks', [])}) or o.get('problems'),
                                    'account_check': o.get('account_check')})
        res['w2']['p7_quoted'] = quoted
        if w.get('p7_from_w3') and not quoted:
            res['w2']['p7_quote_note'] = 'W3 결과 계약 불일치/없음 — P7 인용 입력을 넘기지 않음'
    w = sc.get('w4')
    if w:
        d = os.path.join(out, 'w4')
        rc, o = call(mods['w4'], ['--manifest', rel(base, w['manifest']), '--out', os.path.join(d, 'out')], d,
                     os.path.join(d, 'out', 'result.json'))
        rec('w4', rc, o, lambda o: {'detail': o.get('unconfirmed_core_fields') or o.get('sessions_failed')})
    w = sc.get('w5')
    if w:
        d = os.path.join(out, 'w5')
        rc, o = call(mods['w5'], ['--plan', rel(base, w['plan']), '--dto-java', rel(base, w['dto_java']),
                                  '--limits-java', rel(base, w['limits_java']), '--out', os.path.join(d, 'out.json')], d,
                     os.path.join(d, 'out.json'))
        rec('w5', rc, o, lambda o: {'detail': o.get('summary_line'), 'not_ready_codes': [x['code'] for x in o.get('not_ready', [])]})
    return res


def classify(res):
    """계약이 맞는 모듈만 원본 classify() 로 분류하고, 깨진 모듈은 FAILED 로 둔다."""
    O = orig.load('run_offline')
    ok_res = {k: r for k, r in res.items() if r['contract_ok']}
    mods, _ = O.classify(ok_res)
    for k, r in res.items():
        if not r['contract_ok']:
            mods[k] = {'status': 'FAILED', 'verdict': r['verdict'], 'rc': r['rc'], 'detail': r['contract'], 'contract': r['contract_code']}
        else:
            mods[k]['contract'] = 'OK'
            mods[k]['json_exit'] = r['json_exit']
    overall = max((m['status'] for m in mods.values()), key=O.RANK.index)
    return mods, overall


def summarize(sc, res):
    O = orig.load('run_offline')
    mods, overall = classify(res)
    s = {'tool': TOOL, 'version': VERSION, 'scenario': sc.get('name'), 'synthetic': sc.get('synthetic'), 'modules': mods,
         'overall': overall, 'local_success': all(m['status'] != 'FAILED' and 'verdict' in m for m in mods.values()),
         'operational_ready': False,
         'operational_ready_note': '로컬 합성 파일 예행 — 운영 배포·계좌 읽기·생성·시작 승인 전. 이 결과로 운영 준비를 표시하지 않는다',
         'next_approvals': O.NEXT_APPROVALS, 'w2_account_quote': res.get('w2', {}).get('account_check'),
         'w2_p7_quoted': res.get('w2', {}).get('p7_quoted'), 'raw': res}
    s['exit'] = O.EXIT[overall]
    return s


def main(argv=None, modules=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--scenario', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if os.path.exists(a.out):
        sys.stderr.write('출력 폴더가 이미 있음 — 덮어쓰지 않음\n')
        return 41
    os.makedirs(a.out)
    O = None
    try:
        O = orig.load('run_offline')
        module_exit_tables()
        with open(a.scenario, encoding='utf-8') as f:
            sc = json.load(f)
        if sc.get('scenario_version') != 'offline-scenario-1':
            raise ValueError('scenario_version')
        s = summarize(sc, run(sc, os.path.dirname(os.path.abspath(a.scenario)), a.out, modules))
    except Exception as e:  # noqa: BLE001
        s = {'tool': TOOL, 'version': VERSION, 'overall': 'RUNNER_ERROR', 'error': type(e).__name__, 'message': str(e)[:200], 'exit': 40}
    with open(os.path.join(a.out, 'summary.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(s, f, ensure_ascii=False, indent=1)
    if s['exit'] != 40:
        with open(os.path.join(a.out, 'summary.md'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(O.render(s))
    return s['exit']


if __name__ == '__main__':
    sys.exit(main())
