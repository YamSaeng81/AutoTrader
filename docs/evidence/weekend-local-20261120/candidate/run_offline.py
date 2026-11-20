"""W6 파일 기반 통합 예행(총괄 v1.212 §2 W6). W1~W5 후보를 한 번에 실행해 모듈별 종료값·전체 결과를 저장한다.
파일 입력 외 호출 금지 — 각 모듈을 `python -I <모듈>` 로 파일 인자만 주어 부른다. SSH·psql·DB·HTTP·거래소·Telegram 없음.
모듈의 기존 시험(cases_w*)을 반복하지 않는다.

입력: --scenario S (version 'offline-scenario-1', 경로는 시나리오 파일 기준 상대)
  {w1:{registry, registry_sha256, observed}|null, w2:{registry, registry_sha256, state, p7_from_w3:bool}|null,
   w3:{registry, base, now}|{db_bundle}|null, w4:{manifest}|null, w5:{plan, dto_java, limits_java}|null}
  실행 순서 W3 → W1 → W2 → W4 → W5. W2 의 계좌 일치는 같은 시나리오 W3 의 P7 원문 출력을 해시로 인용한다(새로 계산하지 않음).
출력: --out 새 폴더/<wN>/ 모듈 산출, summary.json, summary.md
분류: LOCAL_VERIFIED / NEEDS_DATA / NEEDS_APPROVAL / FAILED (+ 입력 없음 NOT_RUN → NEEDS_DATA)
  전체 = FAILED 하나라도 → FAILED, 아니면 NEEDS_DATA → NEEDS_APPROVAL → LOCAL_VERIFIED 순.
  local_success(로컬 파일 검사 성공)와 operational_ready(운영 준비)는 따로 낸다. 이 실행기는 operational_ready 를 참으로 만들지 않는다.
종료값: LOCAL_VERIFIED 0 · NEEDS_APPROVAL 5 · NEEDS_DATA 10 · FAILED 20 · 실행기 오류 40
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pins  # noqa: E402

TOOL, VERSION = 'run_offline', 'w6-1'
EXIT = {'LOCAL_VERIFIED': 0, 'NEEDS_APPROVAL': 5, 'NEEDS_DATA': 10, 'FAILED': 20}
RANK = ['LOCAL_VERIFIED', 'NEEDS_APPROVAL', 'NEEDS_DATA', 'FAILED']
APPROVAL_CODES = {'APPROVAL_VALUE_UNDECIDED', 'VALUE_UNDECIDED', 'DEPLOY_DOES_NOT_SUPPORT', 'DEPLOY_UNOBSERVED',
                  'INVEST_RATIO_DEFAULT', 'SERVER_CAP_UNKNOWN', 'INVEST_RATIO_AMBIGUOUS'}
CLASS = {
    'w1': {'SET_MATCH': 'LOCAL_VERIFIED', 'HOLD': 'NEEDS_DATA', 'MISMATCH': 'FAILED', 'INVALID': 'FAILED', 'ERROR': 'FAILED'},
    'w2': {'NO_RISK_ITEMS': 'LOCAL_VERIFIED', 'RISK_PRESENT': 'FAILED', 'INVALID': 'FAILED', 'ERROR': 'FAILED'},
    'w3': {'F3_PASS': 'LOCAL_VERIFIED', 'NEEDS_MORE_SOURCES': 'NEEDS_DATA', 'F3_NOT_PASS': 'FAILED', 'BUNDLE_REJECTED': 'FAILED',
           'ERROR': 'FAILED'},
    'w4': {'BUILT_CORE_CONFIRMED': 'LOCAL_VERIFIED', 'BUILT_WITH_UNCONFIRMED': 'NEEDS_DATA', 'PARTIAL': 'FAILED', 'INVALID': 'FAILED',
           'ERROR': 'FAILED'},
    # W5: 형식 정상이어도 실제 실행 승인 전이므로 NEEDS_APPROVAL. NOT_READY 는 사유가 결정/승인 항목뿐일 때만 NEEDS_APPROVAL
    'w5': {'FORMAT_OK_NOT_APPROVED': 'NEEDS_APPROVAL', 'REJECT': 'FAILED', 'INVALID': 'FAILED', 'ERROR': 'FAILED'},
}
NAMES = {'w1': '등록표 F1 비교', 'w2': '병행 N03 분류', 'w3': 'P7→F3 연결', 'w4': '월 성적표', 'w5': 'DYNAMIC 계획 검증'}
# 운영 단계별 정확히 필요한 다음 승인 1건과 근거(파일 위치는 이 폴더 기준). 같은 원문을 관리 문서에 복사하지 않는다.
NEXT_APPROVALS = [
    {'step': 'd81 배포', 'next_approval': 'd81c3f2f 운영 배포(설치·재기동 단계별 확인) 1건',
     'evidence': 'runs/w5-run3/C5_observed_code_0e1d2a02 — 0e1d2a02 는 DynamicEntryLimits 없음·모드 비엄격(W5 DEPLOY_DOES_NOT_SUPPORT)'},
    {'step': '병행 감시 설치', 'next_approval': '종류+ID 단위 세션·주문·포지션 상태(금액 없음) 읽기의 N03 신규 설치 1건',
     'evidence': 'candidate/parallel_report.py 입력 계약 — 설치본 real_* 는 ID 없는 합계(dec-parallel-20261118 §2-1)'},
    {'step': '계좌 읽기', 'next_approval': 'P7 전체 관측 1회 수집(지문 전후·거래소 전후·코드 SHA) 승인 1건',
     'evidence': 'runs/w3-run1/C4_db_partial_bundle_only — DB 부분 묶음만으로는 추가 원천 필요'},
    {'step': '실제 생성', 'next_approval': '총원금·월 손실 상한·DYNAMIC 전략/배정액 사용자 결정 1건(→ 등록표 v3 실제 값·W5 승인 참조)',
     'evidence': 'runs/w5-run3/R0_actual_plan_skeleton — NOT_READY(VALUE_UNDECIDED·APPROVAL_VALUE_UNDECIDED)'},
    {'step': '시작', 'next_approval': '생성 뒤 W1 집합 일치 + W3 F3 통과 결과를 붙인 시작 승인 1건',
     'evidence': 'W1 SET_MATCH 는 F1 전체 통과 아님(별도 확인 항목 6개), W3 F3_PASS 만 계좌 통과'},
]


def rel(base, p):
    return p if os.path.isabs(p) else os.path.normpath(os.path.join(base, p))


def call(mod, args, out_dir):
    os.makedirs(out_dir)
    p = subprocess.run([sys.executable, '-I', os.path.join(HERE, mod), *args], capture_output=True)
    with open(os.path.join(out_dir, 'rc.txt'), 'w', newline='\n') as f:
        f.write(f'{p.returncode}\n')
    with open(os.path.join(out_dir, 'stderr.txt'), 'wb') as f:
        f.write(p.stderr)
    return p.returncode


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def run(sc, base, out):
    res = {}
    # W3
    w = sc.get('w3')
    if w:
        d = os.path.join(out, 'w3')
        args = ['--db-bundle', rel(base, w['db_bundle'])] if 'db_bundle' in w else \
            ['--registry', rel(base, w['registry']), '--base', rel(base, w['base']), '--now', rel(base, w['now'])]
        rc = call('check_parallel.py', args + ['--out', os.path.join(d, 'out.json')], d)
        o = load(os.path.join(d, 'out.json'))
        res['w3'] = {'rc': rc, 'verdict': o['verdict'], 'detail': o.get('f3', {}).get('reasons') or o.get('needs_sources')
                     or o.get('bundle_reason')}
        if 'p7_output' in o:
            pins.dump(o['p7_output'], os.path.join(d, 'p7.json'))
    # W1
    w = sc.get('w1')
    if w:
        d = os.path.join(out, 'w1')
        rc = call('registry_f1.py', ['--registry', rel(base, w['registry']), '--registry-sha256', w['registry_sha256'],
                                     '--observed', rel(base, w['observed']), '--out', os.path.join(d, 'out.json')], d)
        o = load(os.path.join(d, 'out.json'))
        res['w1'] = {'rc': rc, 'verdict': o['verdict'], 'detail': o.get('summary_line')}
    # W2
    w = sc.get('w2')
    if w:
        d = os.path.join(out, 'w2')
        args = ['--registry', rel(base, w['registry']), '--registry-sha256', w['registry_sha256'], '--state', rel(base, w['state']),
                '--out-json', os.path.join(d, 'out.json'), '--out-md', os.path.join(d, 'out.md')]
        p7 = os.path.join(out, 'w3', 'p7.json')
        if w.get('p7_from_w3') and os.path.exists(p7):
            args += ['--p7-result', p7, '--p7-result-sha256', hashlib.sha256(open(p7, 'rb').read()).hexdigest()]
        rc = call('parallel_report.py', args, d)
        o = load(os.path.join(d, 'out.json'))
        res['w2'] = {'rc': rc, 'verdict': o['verdict'], 'detail': sorted({r['code'] for r in o.get('risks', [])}) or o.get('problems'),
                     'account_check': o.get('account_check')}
    # W4
    w = sc.get('w4')
    if w:
        d = os.path.join(out, 'w4')
        os.makedirs(d)
        p = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'build_monthly.py'), '--manifest', rel(base, w['manifest']),
                            '--out', os.path.join(d, 'out')], capture_output=True)
        open(os.path.join(d, 'rc.txt'), 'w', newline='\n').write(f'{p.returncode}\n')
        o = load(os.path.join(d, 'out', 'result.json'))
        res['w4'] = {'rc': p.returncode, 'verdict': o['verdict'], 'detail': o.get('unconfirmed_core_fields') or o.get('sessions_failed')}
    # W5
    w = sc.get('w5')
    if w:
        d = os.path.join(out, 'w5')
        rc = call('check_dynamic_plan.py', ['--plan', rel(base, w['plan']), '--dto-java', rel(base, w['dto_java']),
                                            '--limits-java', rel(base, w['limits_java']), '--out', os.path.join(d, 'out.json')], d)
        o = load(os.path.join(d, 'out.json'))
        res['w5'] = {'rc': rc, 'verdict': o['verdict'], 'detail': o.get('summary_line'),
                     'not_ready_codes': [x['code'] for x in o.get('not_ready', [])]}
    return res


def classify(res):
    mods = {}
    for k in ('w1', 'w2', 'w3', 'w4', 'w5'):
        r = res.get(k)
        if r is None:
            mods[k] = {'status': 'NEEDS_DATA', 'why': '입력 없음(NOT_RUN) — 성공으로 세지 않음'}
            continue
        v = r['verdict']
        if k == 'w5' and v == 'NOT_READY':
            st = 'NEEDS_APPROVAL' if set(r['not_ready_codes']) <= APPROVAL_CODES else 'NEEDS_DATA'
        else:
            st = CLASS[k].get(v, 'FAILED')
        mods[k] = {'status': st, 'verdict': v, 'rc': r['rc'], 'detail': r.get('detail')}
    overall = max((m['status'] for m in mods.values()), key=RANK.index)
    return mods, overall


def render(s):
    L = [f"# 오프라인 통합 예행 — {s['scenario']} ({'합성' if s['synthetic'] else '입력 표지 확인'})", '',
         f"- 전체: **{s['overall']}** · 로컬 파일 검사 성공 {s['local_success']} · 운영 준비 {s['operational_ready']}", '',
         '| 모듈 | 판정 | 분류 | 종료값 |', '|---|---|---|---|']
    for k, m in s['modules'].items():
        L.append(f"| {k} {NAMES[k]} | {m.get('verdict', '-')} | {m['status']} | {m.get('rc', '-')} |")
    L += ['', '## 운영 단계별 다음 승인(각 1건)', '', '| 단계 | 다음 승인 | 근거 |', '|---|---|---|']
    L += [f"| {a['step']} | {a['next_approval']} | {a['evidence']} |" for a in s['next_approvals']]
    return '\n'.join(L) + '\n'


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--scenario', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if os.path.exists(a.out):
        sys.stderr.write('출력 폴더가 이미 있음 — 덮어쓰지 않음\n')
        return 41
    os.makedirs(a.out)
    try:
        sc = load(a.scenario)
        if sc.get('scenario_version') != 'offline-scenario-1':
            raise ValueError('scenario_version')
        res = run(sc, os.path.dirname(os.path.abspath(a.scenario)), a.out)
        mods, overall = classify(res)
        s = {'tool': TOOL, 'version': VERSION, 'scenario': sc.get('name'), 'synthetic': sc.get('synthetic'), 'modules': mods,
             'overall': overall, 'local_success': all(m['status'] != 'FAILED' and 'verdict' in m for m in mods.values()),
             'operational_ready': False,
             'operational_ready_note': '로컬 합성 파일 예행 — 운영 배포·계좌 읽기·생성·시작 승인 전. 이 결과로 운영 준비를 표시하지 않는다',
             'next_approvals': NEXT_APPROVALS, 'w2_account_quote': res.get('w2', {}).get('account_check')}
        s['exit'] = EXIT[overall]
    except Exception as e:  # noqa: BLE001
        s = {'tool': TOOL, 'version': VERSION, 'overall': 'RUNNER_ERROR', 'error': type(e).__name__, 'message': str(e)[:200], 'exit': 40}
    pins.dump(s, os.path.join(a.out, 'summary.json'))
    if s['exit'] != 40:
        with open(os.path.join(a.out, 'summary.md'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(render(s))
    return s['exit']


if __name__ == '__main__':
    sys.exit(main())
