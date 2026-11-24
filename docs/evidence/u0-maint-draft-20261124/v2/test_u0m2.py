"""U0-maint2.sh 판정·기록 부분 파일 대역 4건(총괄 v1.240 §3-1). 실제/가짜 SSH·생산 별칭·PATH 치환 없음, PG 없음.
U0-maint2.sh 에서 '# ── v1.240' 줄부터 ok-U0 줄까지만 잘라 로컬 bash 로 돌린다. 앞에 붙이는 것은 U0 의 stop() 과
U변수.sh(실행 폴더 사본)의 h256()·pf3ok() 원문, DB 호출 대역 DC=stubdc(정해진 r1-diag-4 출력 파일을 cat), 그리고 윈도 환경 보정 둘
(PYTHONIOENCODING=utf-8, python3 출력 CR 제거 — 서버 리눅스 UTF-8·LF 와 맞춤)뿐이다.
잘라낸 부분에서 바꾸는 것은 MAINT4·M4GATE 빈칸 두 줄(시험용 커밋 64a34eec·그 커밋의 maint_gate4 해시)뿐이다.
  maint_pass         pf3ok DYNAMIC 9 + 운영 READ_OK r1.tsv(1446)  → 종료 0, ok-U0 있음, r1-gate MAINT_DEPLOY_ONLY
  gate0_write_fail   pf3ok 0 경로, r1-gate.txt 자리에 디렉터리    → 종료 1, 'r1-gate 필수 기록 실패(pf3ok 0 경로)', ok-U0 없음
  verdict_write_fail predeploy-verdict.txt 자리에 디렉터리         → 종료 1, '배포 전 판정 기록 실패'(기준 미충족으로 읽지 않음), ok-U0 없음
  new_branch         pf3ok DYNAMIC 3 + 격리 PG minute_edge 출력(KNOWN 2·OTHER 1) → 종료 1, MAINT_STOP·'조건 미충족', ok-U0 없음
사용: python -I test_u0m2.py --u0m2 <U0-maint2.sh> --uvars <U변수.sh> --out <새 폴더>   (저장소 루트에서 실행 — git show 를 쓴다)
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
EVI = os.path.dirname(os.path.dirname(HERE))
PROD_TSV = os.path.join(EVI, 'r1-diag4-read-20261124-1446', 'r1.tsv')
EDGE_TSV = os.path.join(EVI, 'r1-diag4-20261124', 'runs', 'pg-r1d4', 'minute_edge', 'out.tsv')
COMMIT = '64a34eec6ae747814187019e7c1f26175d260034'
UVARS_SHA = None  # 실행 폴더 사본 해시는 결과에 기록만 한다


def pre(live, dyn):
    return f'LIVE | {live}\nDYNAMIC | {dyn}\nLIVE | 0\nDYNAMIC | 0\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--u0m2', required=True)
    ap.add_argument('--uvars', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=False)
    seg = open(a.u0m2, 'rb').read().decode('utf-8').split('\n')
    i0 = next(i for i, l in enumerate(seg) if l.startswith('# ── v1.240 §3-1'))
    i1 = next(i for i, l in enumerate(seg) if l.startswith('touch "$M/ok-U0"'))
    body = seg[i0:i1 + 1]
    gate_sha = hashlib.sha256(subprocess.run(['git', 'show', f'{COMMIT}:docs/evidence/u0-maint-draft-20261124/maint_gate4.py'],
                                             capture_output=True, check=True).stdout).hexdigest()
    n = 0
    for k, l in enumerate(body):
        if l.startswith('MAINT4=""'):
            body[k] = f'MAINT4={COMMIT}   # 시험 대역'; n += 1
        elif l.startswith('M4GP=') and 'M4GATE=""' in l:
            body[k] = l.replace('M4GATE=""', f'M4GATE={gate_sha}'); n += 1
    assert n == 2
    uv = open(a.uvars, 'rb').read().decode('utf-8').split('\n')
    h256 = next(l for l in uv if l.startswith('h256() {'))
    p0 = next(i for i, l in enumerate(uv) if l.startswith('pf3ok() {'))
    p1 = next(i for i in range(p0, len(uv)) if uv[i] == '}')
    pf3 = '\n'.join(uv[p0:p1 + 1])
    head = ['(', 'set -euo pipefail', 'stop() { echo "🔴 멈춤: $*"; exit 1; }', h256, pf3,
            'stubdc() { cat "$STUB_TSV"; }', 'DC=stubdc',
            # 윈도 python 은 표준 출력을 CRLF 로 쓴다(서버 리눅스는 LF) — 시험 대역에서만 CR 을 지운다. 종료값은 pipefail 로 python 의 것이 남는다
            "python3() { command python3 \"$@\" | tr -d '\\015'; }"]   # 8진 표기 — 스크립트에 CR 문자를 넣으면 Git Bash 가 해석 때 지운다
    script = '\n'.join(head + body + [')']) + '\n'
    sp = os.path.join(a.out, 'segment.sh')
    open(sp, 'wb').write(script.encode('utf-8'))
    cases = {
        'maint_pass': (pre(0, 9), PROD_TSV, None, 0, ['MAINT_DEPLOY_ONLY', '✅ U0 통과'], True),
        'gate0_write_fail': (pre(0, 0), PROD_TSV, 'r1-gate.txt', 1, ['r1-gate 필수 기록 실패(pf3ok 0 경로)'], False),
        'verdict_write_fail': (pre(0, 9), PROD_TSV, 'predeploy-verdict.txt', 1, ['배포 전 판정 기록 실패'], False),
        'new_branch': (pre(0, 3), EDGE_TSV, None, 1, ['MAINT_STOP', '유지보수 배포 조건 미충족'], False),
    }
    res = {}
    for name, (pr, stub, blocker, rc, words, okfile) in cases.items():
        m = os.path.abspath(os.path.join(a.out, name, 'M'))
        os.makedirs(m)
        open(os.path.join(m, 'predeploy-read.txt'), 'wb').write(pr.encode('utf-8'))
        if blocker:
            os.makedirs(os.path.join(m, blocker))
        env = dict(os.environ, PYTHONIOENCODING="utf-8", M=m.replace('\\', '/'), STUB_TSV=stub.replace('\\', '/'))
        p = subprocess.run(['bash', sp.replace('\\', '/')], capture_output=True, env=env)
        so, se = p.stdout.decode('utf-8', 'replace'), p.stderr.decode('utf-8', 'replace')
        d = os.path.join(a.out, name)
        for fn, b in (('stdout.txt', so), ('stderr.txt', se), ('rc.txt', f'{p.returncode}\n')):
            open(os.path.join(d, fn), 'wb').write(b.encode('utf-8'))
        has_ok = os.path.exists(os.path.join(m, 'ok-U0'))
        wrong = ('보수적 사전 기준 미충족 — 배포 전' in so and name == 'verdict_write_fail')
        res[name] = {'rc': p.returncode, 'ok_U0': has_ok, 'words': {w: w in so for w in words},
                     'not_misclassified': not wrong,
                     'ok': p.returncode == rc and has_ok == okfile and all(w in so for w in words) and not wrong}
    ok = all(v['ok'] for v in res.values())
    summ = {'ok': ok, 'results': res, 'segment_sha256': hashlib.sha256(script.encode('utf-8')).hexdigest(),
            'u0m2_sha256': hashlib.sha256(open(a.u0m2, 'rb').read()).hexdigest(),
            'uvars_sha256': hashlib.sha256(open(a.uvars, 'rb').read()).hexdigest(),
            'test_commit': COMMIT, 'gate_sha256': gate_sha, 'ssh_calls': 0, 'pg_calls': 0}
    open(os.path.join(a.out, 'summary.json'), 'wb').write((json.dumps(summ, ensure_ascii=False, indent=1) + '\n').encode('utf-8'))
    print(json.dumps({'ok': ok, 'results': {k: (v['rc'], v['ok_U0'], v['ok']) for k, v in res.items()}}, ensure_ascii=False))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))
    sys.exit(main())
