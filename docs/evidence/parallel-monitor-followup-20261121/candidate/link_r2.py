"""변환기→R2 연결 후보 pm-link-1 (총괄 v1.216 §3-3). 로컬 파일 전용 — SSH·psql·DB·HTTP 호출 없음. 운영 준비 표시 아님.

입력
  --conv-dir        변환기(pm-conv-4) 출력 폴더(result.json, CONVERTED 이면 f1-obs.json·parallel-in.json)
  --registry / --registry-sha256   등록표와 고정 해시(이번 로컬 연결은 합성 registry-3 14599ba6 — 실제 승인 등록표가 아님)
  --r2 / --r2-sha256               R2 실행기(weekend-local-fix-20261121/candidate/run_offline_r2.py e203ea6c)
  --label / --out   새 결과 폴더(이미 있으면 멈춤)
동작
  · result.json 이 CONVERTED·consumable=true 이고 선언된 출력 해시가 실제 파일과 같을 때만 R2 를 **1회** 호출한다.
    시나리오에는 W1(registry_f1)·W2(parallel_report)만 넣는다. W3/P7·W4 월 성적표·W5 는 붙이지 않는다(R2 는 이들을 NEEDS_DATA 로 둔다).
  · 그 밖(COLLECTION_NOT_PASSED·INVALID·ERROR·해시 불일치·형식 위반)은 R2 호출 0회, NOT_LINKED 로 남긴다 — 빈 목록·정상으로 바꾸지 않는다.
  · P0 발생(parallel-in p0_events 비어 있지 않음)은 W2 위험(P0)·R2 FAILED 로 그대로 남고, 연결 결과도 성공이 아니다.
판정(status)·종료값
  LINKED_W1_W2_VERIFIED 0   R2 가 W1·W2 를 계약대로 소비했고 둘 다 LOCAL_VERIFIED(다른 모듈 미연결은 R2 overall 에 그대로 남김)
  LINKED_NOT_VERIFIED   20  R2 를 호출했으나 W1/W2 중 하나라도 LOCAL_VERIFIED 아님(위험·불일치·계약 실패 포함)
  NOT_LINKED            30  변환 결과가 소비 가능하지 않음 — R2 호출 0회
  ERROR                 40
operational_ready 는 항상 false.
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
TOOL, VERSION = 'link_r2', 'pm-link-1'
EXIT = {'LINKED_W1_W2_VERIFIED': 0, 'LINKED_NOT_VERIFIED': 20, 'NOT_LINKED': 30, 'ERROR': 40}
CONV_VERSION = 'pm-conv-4'
R2_SHA = 'e203ea6c1e6a4f28ecbe59e0783a370e936453abe251f7acb5a96de6a712bd4f'


def sha(p):
    with open(p, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()


def dump(o, p):
    with open(p, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(o, f, ensure_ascii=False, indent=1)
        f.write('\n')


def consumable(cd):
    """(ok, 사유, result) — 소비 가능한 변환 결과인지."""
    try:
        r = json.load(open(os.path.join(cd, 'result.json'), encoding='utf-8'))
    except (OSError, ValueError):
        return False, 'CONV_RESULT_UNREADABLE', None
    if not isinstance(r, dict) or r.get('version') != CONV_VERSION:
        return False, 'CONV_VERSION', r
    if r.get('verdict') != 'CONVERTED' or r.get('consumable') is not True or r.get('exit') != 0:
        return False, f"CONV_NOT_CONSUMABLE:{r.get('verdict')}", r
    outs = r.get('outputs')
    if not isinstance(outs, dict) or set(outs) != {'f1-obs.json', 'parallel-in.json'}:
        return False, 'CONV_OUTPUTS_MISSING', r
    for n, h in outs.items():
        p = os.path.join(cd, n)
        if not os.path.isfile(p) or sha(p) != h:
            return False, 'CONV_OUTPUT_HASH_MISMATCH', r
    return True, None, r


def run(a):
    out = {'tool': TOOL, 'version': VERSION, 'label': a.label, 'operational_ready': False, 'r2_calls': 0,
           'not_attached': ['W3/P7', 'W4 월 성적표', 'W5', '새 생성·실전 시작'],
           'registry': {'path_note': '합성 registry-3(fixtures/w1/registry.json) — 실제 승인 등록표 아님', 'sha256': a.registry_sha256}}
    if sha(a.r2) != a.r2_sha256 or a.r2_sha256 != R2_SHA or sha(a.registry) != a.registry_sha256:
        raise RuntimeError('R2 또는 등록표 해시가 고정값과 다름')
    cd = os.path.abspath(a.conv_dir)
    ok, why, r = consumable(cd)
    out['conv'] = {'dir_result_sha256': sha(os.path.join(cd, 'result.json')) if os.path.isfile(os.path.join(cd, 'result.json')) else None,
                   'verdict': (r or {}).get('verdict') if isinstance(r, dict) else None,
                   'collection_failure_codes': sorted({f.get('code') for f in (r or {}).get('collection_failures', [])})
                   if isinstance(r, dict) else None}
    if not ok:
        out.update({'status': 'NOT_LINKED', 'reason': why, 'note': '변환 결과가 소비 가능하지 않아 R2 를 호출하지 않았다(빈 목록·정상으로 바꾸지 않음)'})
        return out
    pin = json.load(open(os.path.join(cd, 'parallel-in.json'), encoding='utf-8'))
    out['p0_events_in'] = pin.get('p0_events')
    sc = {'scenario_version': 'offline-scenario-1', 'name': f'pm-link-1 {a.label}', 'synthetic': bool(r.get('synthetic')),
          'w1': {'registry': os.path.abspath(a.registry), 'registry_sha256': a.registry_sha256, 'observed': os.path.join(cd, 'f1-obs.json')},
          'w2': {'registry': os.path.abspath(a.registry), 'registry_sha256': a.registry_sha256, 'state': os.path.join(cd, 'parallel-in.json')}}
    sp = os.path.join(a.out, 'scenario.json')
    dump(sc, sp)
    ro = os.path.join(a.out, 'r2')
    p = subprocess.run([sys.executable, '-I', os.path.abspath(a.r2), '--scenario', sp, '--out', ro], capture_output=True)
    out['r2_calls'] = 1
    for n, b in (('r2.stdout', p.stdout), ('r2.stderr', p.stderr)):
        with open(os.path.join(a.out, n), 'wb') as f:
            f.write(b)
    try:
        s = json.load(open(os.path.join(ro, 'summary.json'), encoding='utf-8'))
    except (OSError, ValueError):
        s = None
    if not isinstance(s, dict) or s.get('exit') != p.returncode:
        out.update({'status': 'LINKED_NOT_VERIFIED', 'reason': 'R2_SUMMARY_MISSING_OR_EXIT_MISMATCH', 'r2_rc': p.returncode})
        return out
    mods = s.get('modules', {})
    w = {k: {'status': mods.get(k, {}).get('status'), 'verdict': mods.get(k, {}).get('verdict'), 'contract': mods.get(k, {}).get('contract'),
             'detail': mods.get(k, {}).get('detail')} for k in ('w1', 'w2')}
    out.update({'r2_rc': p.returncode, 'r2_overall': s.get('overall'), 'r2_operational_ready': s.get('operational_ready'),
                'r2_summary_sha256': sha(os.path.join(ro, 'summary.json')), 'w1': w['w1'], 'w2': w['w2'],
                'other_modules': {k: m.get('status') for k, m in mods.items() if k not in ('w1', 'w2')}})
    good = all(w[k]['status'] == 'LOCAL_VERIFIED' and w[k]['contract'] == 'OK' for k in ('w1', 'w2')) and s.get('operational_ready') is False
    out['status'] = 'LINKED_W1_W2_VERIFIED' if good else 'LINKED_NOT_VERIFIED'
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    for k in ('conv-dir', 'registry', 'registry-sha256', 'r2', 'r2-sha256', 'label', 'out'):
        ap.add_argument(f'--{k}', required=True)
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=False)
    try:
        out = run(a)
    except Exception as e:  # noqa: BLE001
        out = {'tool': TOOL, 'version': VERSION, 'status': 'ERROR', 'error': type(e).__name__, 'message': str(e)[:200],
               'operational_ready': False}
    out['exit'] = EXIT[out['status']]
    dump(out, os.path.join(a.out, 'link.json'))
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
