"""기록 확정 보완 직접 영향 3건 (총괄 v1.209 §3-2): 정상 대조 1 · 체크섬 쓰기 실패 1 · 마지막 확정(os.replace) 실패 1.
입력은 옛 조립기 run1/inputs/normal 보존 원문(합성 시계 포함)만 쓴다. PG·psql·네트워크·제품 시험 없음.
사용: python -I tests_commit.py <새 run 폴더>  (이미 있으면 멈춘다. 실패 회차도 지우거나 덮어쓰지 않는다)"""
import builtins, contextlib, hashlib, importlib.util, io, json, os, subprocess, sys
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
OLD = os.path.join(HERE, '..', 'dec-collect-assembler-20261120')
GATE = os.path.join(HERE, '..', 'dec-read-gate-v3-20261120')
IND = os.path.join(OLD, 'run1', 'inputs', 'normal')
ASM = os.path.join(HERE, 'assemble.py')


def rd(p):
    with open(p, 'rb') as f:
        return f.read()


def load():
    spec = importlib.util.spec_from_file_location('assemble_commit', ASM)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def args_for(out):
    a = []
    for k in ('registry', 'stdout', 'stderr', 'psql-rc', 'clock'):
        a += ['--' + k, os.path.join(IND, k)]
    return a + ['--sql', os.path.join(GATE, 'read.sql'), '--parser', os.path.join(GATE, 'parse.py'), '--out', out]


def cli_verify(out):
    p = subprocess.run([sys.executable, '-I', ASM, '--verify', out], capture_output=True)
    return p.returncode, p.stdout.decode('ascii', 'replace').strip()


def failure_checks(m, out, rc, faults):
    names = sorted(os.listdir(out))
    ok, reason = m.verify_committed(out)
    vrc, vout = cli_verify(out)
    try:
        with open(os.path.join(out, 'bundle.json'), encoding='utf-8') as f:   # 다음 단계가 파일을 직접 여는 경우
            direct = json.load(f)
    except FileNotFoundError:
        direct = 'absent'
    r = {'rc': rc, 'faults': faults, 'files': names, 'verify': [ok, reason], 'cli_verify': [vrc, vout],
         'direct_bundle_json': direct if direct == 'absent' else {'rc': direct.get('rc')}}
    r['pass'] = (rc == 42 and len(faults) == 1 and direct == 'absent' and 'bundle.json.uncommitted' in names
                 and ok is False and vrc == 44)
    return r


def main(run):
    os.mkdir(run)   # 고유 경로를 호출 전에 정한다. 있으면 FileExistsError 로 멈춘다
    m = load()
    res = {'assembler_sha256': hashlib.sha256(rd(ASM)).hexdigest(), 'inputs': 'dec-collect-assembler-20261120/run1/inputs/normal',
           'cases': {}}

    # 1) 정상 대조: 옛 run1/normal 과 원문·파서 결과 바이트 동일, bundle 은 commit 키만 추가
    out = os.path.join(run, 'normal')
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = m.main(args_for(out))
    old = os.path.join(OLD, 'run1', 'normal')
    same, diff = [], []
    for n in sorted(os.listdir(old)):
        if n in ('bundle.json', 'SHA256SUMS'):
            continue
        (same if rd(os.path.join(old, n)) == rd(os.path.join(out, n)) else diff).append(n)
    ob = json.loads(rd(os.path.join(old, 'bundle.json')))
    nb = json.loads(rd(os.path.join(out, 'bundle.json')))
    commit = nb.pop('commit', None)
    ok, _ = m.verify_committed(out)
    vrc, vout = cli_verify(out)
    res['cases']['normal'] = c = {'rc': rc, 'stdout': buf.getvalue().strip(), 'same_bytes': same, 'diff_bytes': diff,
                                  'bundle_equal_except_commit': nb == ob, 'commit': commit,
                                  'files': sorted(os.listdir(out)), 'verify': ok, 'cli_verify': [vrc, vout]}
    c['pass'] = (rc == 0 and not diff and len(same) == 9 and c['bundle_equal_except_commit'] and commit == m.COMMIT_RULE
                 and 'bundle.json.uncommitted' not in c['files'] and ok is True and vrc == 0)

    # 2) 체크섬 쓰기 실패(총괄 재현과 같은 주입 지점)
    out = os.path.join(run, 'sums_write_fail')
    real_open, faults = builtins.open, []

    def deny_sums(file, mode='r', *a, **k):
        if os.path.abspath(file) == os.path.abspath(os.path.join(out, 'SHA256SUMS')) and 'w' in mode:
            faults.append('SHA256SUMS write denied')
            raise PermissionError('synthetic SHA256SUMS write denial')
        return real_open(file, mode, *a, **k)
    buf = io.StringIO()
    with patch('builtins.open', deny_sums), contextlib.redirect_stdout(buf):
        rc = m.main(args_for(out))
    res['cases']['sums_write_fail'] = failure_checks(m, out, rc, faults)
    res['cases']['sums_write_fail']['stdout'] = buf.getvalue().strip()

    # 3) 마지막 확정 단계(os.replace) 실패 — 체크섬은 써진 뒤
    out = os.path.join(run, 'final_rename_fail')
    real_replace, faults = os.replace, []

    def deny_replace(src, dst, *a, **k):
        if os.path.abspath(dst) == os.path.abspath(os.path.join(out, 'bundle.json')):
            faults.append('final os.replace denied')
            raise PermissionError('synthetic final rename denial')
        return real_replace(src, dst, *a, **k)
    buf = io.StringIO()
    with patch('os.replace', deny_replace), contextlib.redirect_stdout(buf):
        rc = m.main(args_for(out))
    r = failure_checks(m, out, rc, faults)
    r['sums_written_before_failure'] = os.path.exists(os.path.join(out, 'SHA256SUMS'))
    r['pass'] = r['pass'] and r['sums_written_before_failure']
    r['stdout'] = buf.getvalue().strip()
    res['cases']['final_rename_fail'] = r

    res['total'] = len(res['cases'])
    res['passed'] = sum(x['pass'] for x in res['cases'].values())
    res['note'] = '시계 값은 합성(옛 run1 입력 그대로). 실패는 프로세스 안 주입이며 실제 디스크 고갈·운영 실행이 아니다'
    with open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    print(json.dumps({'passed': res['passed'], 'total': res['total'], 'assembler_sha256': res['assembler_sha256']}))
    return 0 if res['passed'] == res['total'] else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
