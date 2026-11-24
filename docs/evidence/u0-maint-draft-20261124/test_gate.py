"""maint_gate4.py 파일 시험(PG·SSH 없음). 입력은 r1-diag4-20261124/runs/pg-r1d4 의 보존 PG 출력과, 그 출력을 고친 합성 파일이다.
합성 파일은 PG 결과가 아니다 — 판정기 분기만 확인한다(통과 경로의 실제 PG 확인은 수용 뒤 별도 사례로 남긴다).
사용: python -I test_gate.py --out <새 폴더>   종료값: 전부 기대대로 0, 아니면 1.
"""
import argparse, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
RUNS = os.path.join(HERE, '..', 'r1-diag4-20261124', 'runs', 'pg-r1d4')
CHK = os.path.join(HERE, '..', 'r1-diag4-20261124', 'check', 'shape_check4.py')
GATE = os.path.join(HERE, 'maint_gate4.py')
N = chr(92) + 'N'


def verdict(live, dyn, r3rows=0, r3und=0):
    return (f"R1 판정 불가(30일) LIVE {live} · DYNAMIC {dyn}\nR2 손실 합(30일, 기록만) LIVE 0 · DYNAMIC 0\n"
            f"R3 RUNNING REAL 세션 청산 행 {r3rows} · 판정 불가 {r3und}\n판정 | 🔴 보수적 사전 기준 미충족 — …\n")


def known_only():
    """minute_edge 실제 출력에서 OTHER 1건을 지운 합성본(ORIG/SPLIT/EXP 를 손으로 맞춤)."""
    src = open(os.path.join(RUNS, 'minute_edge', 'out.tsv'), 'rb').read().decode('utf-8').split('\n')
    o = []
    for ln in src:
        f = ln.split('\t')
        if f[:2] == ['ORIG', 'DYNAMIC']:
            f[2] = '2'
        elif f[:2] == ['SPLIT', 'DYNAMIC']:
            f[2:5] = ['0', '2', '2']
        elif f[:3] == ['EXP', 'DYNAMIC', 'OTHER']:
            f[3:7] = ['0', N, N, N]
        elif f[:3] == ['EXP', 'DYNAMIC', 'ALL']:
            f[3:7] = ['2', '2026-11-27T00:00:00.000000+00:00', '2026-11-30T23:59:00.000000+00:00', '2026-12-01T00:00:00.000000+00:00']
        o.append('\t'.join(f))
    return '\n'.join(o)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=False)
    w = lambda n, s: open(os.path.join(a.out, n), 'wb').write(s.encode('utf-8')) and os.path.join(a.out, n)
    ko = w('known_only.synthetic.tsv', known_only())
    cases = {
        # 이름: (출력 파일, 판정 줄, 기대 종료값, 기대 첫 낱말)
        'pg_normal_other_branches': (os.path.join(RUNS, 'normal', 'out.tsv'), verdict(3, 8), 1, 'MAINT_STOP'),
        'pg_running_real': (os.path.join(RUNS, 'running_real', 'out.tsv'), verdict(0, 0), 1, 'MAINT_STOP'),
        'pg_minute_edge_other1': (os.path.join(RUNS, 'minute_edge', 'out.tsv'), verdict(0, 3), 1, 'MAINT_STOP'),
        'pg_new_ap': (os.path.join(RUNS, 'new_ap', 'out.tsv'), verdict(1, 4), 1, 'MAINT_STOP'),
        'pg_empty_not_called': (os.path.join(RUNS, 'empty', 'out.tsv'), verdict(0, 0), 3, 'MAINT_FORMAT'),
        'syn_known_only_pass': (ko, verdict(0, 2), 0, 'MAINT_DEPLOY_ONLY'),
        'syn_pf3ok_count_mismatch': (ko, verdict(0, 3), 1, 'MAINT_STOP'),
        'syn_r3_nonzero': (ko, verdict(0, 2, 1, 1), 1, 'MAINT_STOP'),
        'syn_verdict_format': (ko, 'R1 판정 불가 LIVE 0\n', 3, 'MAINT_FORMAT'),
    }
    res = {}
    sc = subprocess.run([sys.executable, '-I', CHK, ko], capture_output=True)
    res['syn_known_only_shape_check4'] = {'rc': sc.returncode, 'stdout': sc.stdout.decode('utf-8').strip(), 'ok': sc.returncode == 0}
    for name, (tsv, vd, rc, word) in cases.items():
        vp = w(name + '.verdict.txt', vd)
        p = subprocess.run([sys.executable, '-I', GATE, tsv, vp], capture_output=True)
        so = p.stdout.decode('utf-8')
        w(name + '.stdout', so)
        res[name] = {'rc': p.returncode, 'first': so.split(' ')[0] if so else '', 'stderr_empty': p.stderr == b'',
                     'lines': so.strip().split('\n'), 'ok': p.returncode == rc and so.startswith(word) and p.stderr == b''}
    ok = all(v['ok'] for v in res.values())
    w('summary.json', json.dumps({'ok': ok, 'results': res, 'pg_calls': 0, 'ssh_calls': 0}, ensure_ascii=False, indent=1) + '\n')
    print(json.dumps({'ok': ok, 'results': {k: (v['rc'], v['ok']) for k, v in res.items()}}, ensure_ascii=False))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))
    sys.exit(main())
