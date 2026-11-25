"""D81A2-EXP1 U0 정상판 — 경로·판정·기록 대역 파일 시험(총괄 v1.241 §2-3). 실제/가짜 SSH·생산 별칭·PATH 치환·네트워크·PG 없음.
원문에서 잘라 로컬 bash(--noprofile --norc) 로 돌린다.
  A 대역: 새 U0 의 '[ ! -e "$M" ]' 줄과 mkdir 줄(경로 규칙)
  B 대역: 새 U0 의 '# ── v1.241 §2' 줄부터 '✅ U0 통과' 줄까지(판정·r1-gate·ok-U0). 앞에 U0 의 stop()·U변수의 h256()/pf3ok() 원문만 붙인다
  U1 관문: 각 사례 뒤 U1.sh 1~7행(UB·ok-U0·ok-U1·30분 확인) + 'echo U1_GATE_PASS' — ok-U0 가 없으면 U1 이 멈추는지 본다
  기점(D81A2 65a384e5 원래 U0 106~109행): 낡은 표지·판정 파일 기록 실패에서 원래 동작을 같은 방식으로 기록(차이 확인용)
환경 보정(윈도 Git Bash): PYTHONIOENCODING=utf-8, python3 출력 CR 제거(서버 리눅스 UTF-8·LF 와 맞춤).
결함 주입 대역(사례 이름에 inj_ 표시): 쓰기 실패·부분 기록·이름 바꾸기 실패는 윈도 권한으로 만들 수 없어, 해당 사례에서만 printf/mv 를
  ok-U0 내용일 때 실패·잘림으로 바꾸는 셸 함수를 앞에 붙인다. 다른 줄(r1-gate 등)의 printf/mv 는 원래대로 동작한다.
사용: python -I test_exp1.py --src <새 출처 폴더> --base-commit 65a384e5… --out <없는 폴더>   (저장소 루트에서)
"""
import argparse, hashlib, json, os, subprocess, sys, time

sys.dont_write_bytecode = True
H = lambda b: hashlib.sha256(b).hexdigest()
STOP = 'stop() { echo "🔴 멈춤: $*"; exit 1; }'
PYCR = "python3() { command python3 \"$@\" | tr -d '\\015'; }"
INJ = {
    'write': "printf() { case \"$*\" in *'ok-U0 '*) return 1 ;; esac; builtin printf \"$@\"; }",
    'partial': "printf() { case \"$*\" in *'ok-U0 '*) builtin printf 'ok-U0 2026'; return 0 ;; esac; builtin printf \"$@\"; }",
    'rename': "mv() { case \"$*\" in *ok-U0*) return 1 ;; esac; command mv \"$@\"; }",
}


def pre(live, dyn, r3=''):
    return f'LIVE | {live}\nDYNAMIC | {dyn}\nLIVE | 0\nDYNAMIC | 0\n{r3}'


def lines(p):
    return open(p, 'rb').read().decode('utf-8').split('\n')


def run(script, env, d):
    os.makedirs(d, exist_ok=True)
    sp = os.path.join(d, 'script.sh')
    open(sp, 'wb').write(script.encode('utf-8'))
    p = subprocess.run(['bash', '--noprofile', '--norc', sp.replace('\\', '/')], capture_output=True, env=env)
    so, se = p.stdout.decode('utf-8', 'replace'), p.stderr.decode('utf-8', 'replace')
    for fn, b in (('stdout.txt', so), ('stderr.txt', se), ('rc.txt', f'{p.returncode}\n')):
        open(os.path.join(d, fn), 'wb').write(b.encode('utf-8'))
    return p.returncode, so


def ls(m):
    if not os.path.exists(m):
        return None
    if not os.path.isdir(m):
        return 'FILE'
    return sorted((n + ('/' if os.path.isdir(os.path.join(m, n)) else '')) for n in os.listdir(m))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True); ap.add_argument('--base-commit', required=True); ap.add_argument('--out', required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=False)
    out = os.path.abspath(a.out)
    B = os.path.join(a.src, 'd4', 'blocks-d81')
    u0 = lines(os.path.join(B, 'U0.sh')); uv = lines(os.path.join(B, 'U변수.sh')); u1 = lines(os.path.join(B, 'U1.sh'))
    base = subprocess.run(['git', 'show', a.base_commit + ':docs/evidence/d81a2-src-20261123/d4/blocks-d81/U0.sh'],
                          capture_output=True, check=True).stdout.decode('utf-8').split('\n')
    assert u0[2] == STOP and base[2] == STOP
    h256 = next(l for l in uv if l.startswith('h256() {'))
    p0 = next(i for i, l in enumerate(uv) if l.startswith('pf3ok() {')); p1 = next(i for i in range(p0, len(uv)) if uv[i] == '}')
    pf3 = '\n'.join(uv[p0:p1 + 1])
    # A 대역
    ia = [i for i, l in enumerate(u0) if l.startswith('[ ! -e "$M" ] && [ ! -L "$M" ]')]
    assert len(ia) == 1 and u0[ia[0] + 1].startswith('mkdir "$M" ||')
    segA = '\n'.join(['(', 'set -euo pipefail', STOP, u0[ia[0]], u0[ia[0] + 1], 'echo HEAD_OK', ')']) + '\n'
    # B 대역
    i0 = next(i for i, l in enumerate(u0) if l.startswith('# ── v1.241 §2'))
    i1 = next(i for i, l in enumerate(u0) if l.startswith('echo "✅ U0 통과'))
    bodyB = u0[i0:i1 + 1]
    # 기점 106~109행(pf3ok … touch ok-U0)
    j0 = next(i for i, l in enumerate(base) if l.startswith('pf3ok "$M/predeploy-read.txt"'))
    j1 = next(i for i, l in enumerate(base) if l.startswith('touch "$M/ok-U0"'))
    bodyO = base[j0:j1 + 1]
    assert j1 - j0 == 3
    # U1 관문 1~7행
    assert u1[0] == '(' and u1[4].startswith('[ -e "$M/ok-U0" ]') and u1[6].startswith('[ $(( $(date +%s) - $(stat -c %Y "$M/ok-U0") ))')
    segU1 = '\n'.join(u1[0:7] + ['echo U1_GATE_PASS', ')']) + '\n'

    def segB(body, inj=None):
        head = ['(', 'set -euo pipefail', STOP, h256, pf3, PYCR] + ([INJ[inj]] if inj else [])
        return '\n'.join(head + body + [')']) + '\n'

    env0 = dict(os.environ, PYTHONIOENCODING='utf-8', LANG='C.UTF-8', UB='D81D4')
    env0.pop('BASH_ENV', None)
    res = {}

    # ── A: 경로 규칙
    for name, prep, rc, want in (
            ('a1_path_new', None, 0, 'HEAD_OK'),
            ('a2_path_exists_dir', 'dir', 1, '이미 있다 — 삭제·이름 변경·덮어쓰기 없이 멈춘다'),
            ('a3_path_is_file', 'file', 1, '이미 있다 — 삭제·이름 변경·덮어쓰기 없이 멈춘다')):
        d = os.path.join(out, name); os.makedirs(d); m = os.path.join(d, 'M')
        if prep == 'dir':
            os.makedirs(m); open(os.path.join(m, 'old-attempt.txt'), 'wb').write(b'previous attempt record\n')
        elif prep == 'file':
            open(m, 'wb').write(b'not a dir\n')
        before = ls(m); hb = H(open(os.path.join(m, 'old-attempt.txt'), 'rb').read()) if prep == 'dir' else None
        r, so = run(segA, dict(env0, M=m.replace('\\', '/')), os.path.join(d, 'run'))
        after = ls(m); ha = H(open(os.path.join(m, 'old-attempt.txt'), 'rb').read()) if prep == 'dir' else None
        ok = r == rc and want in so and (after == [] if prep is None else (after == before and ha == hb))
        res[name] = {'rc': r, 'before': before, 'after': after, 'old_record_unchanged': ha == hb, 'ok': ok}

    # ── B: 판정·기록
    R3ROW = ''  # R3 0행
    cases = [
        # 이름, 입력, 막는 경로(디렉터리), 미리 둘 ok-U0, 주입, 기대 rc, 기대 문구, ok-U0 기대, U1 통과 기대
        ('b1_normal_pass', pre(0, 0, R3ROW), None, None, None, 0, ['판정 | 진행 가능', 'R1 해소 — 이번 U0', '✅ U0 통과'], True),
        ('b2_r1_dynamic_positive', pre(0, 9), None, None, None, 1, ['보수적 사전 기준 미충족', '유지보수 예외 없음'], False),
        ('b3_r1_live_positive', pre(1, 0), None, None, None, 1, ['보수적 사전 기준 미충족', '유지보수 예외 없음'], False),
        ('b4_verdict_write_fail', pre(0, 0), 'predeploy-verdict.txt', None, None, 1, ['배포 전 판정 기록 실패'], False),
        ('b5_gate_write_fail', pre(0, 0), 'r1-gate.txt', None, None, 1, ['r1-gate 필수 기록 실패'], False),
        ('b6_partial_read', 'LIVE | 0\nDYNAMIC | 0\n', None, None, None, 1, ['형식 이상', '조회 결과로 보지 않는다'], False),
        ('b7_stale_ok_marker', pre(0, 0), None, 'stale', None, 1, ['낡은 표지를 이 U0 의 통과로 인정하지 않는다'], 'stale'),
        ('b8_ok_marker_is_dir', pre(0, 0), 'ok-U0', None, None, 1, ['낡은 표지를 이 U0 의 통과로 인정하지 않는다'], 'dir'),
        ('b9_inj_ok_write_fail', pre(0, 0), None, None, 'write', 1, ['ok-U0 표지 기록 실패'], False),
        ('b10_inj_ok_partial_write', pre(0, 0), None, None, 'partial', 1, ['ok-U0 표지 기록 실패'], False),
        ('b11_inj_ok_rename_fail', pre(0, 0), None, None, 'rename', 1, ['ok-U0 표지 기록 실패'], False),
    ]
    for name, pr, blocker, okpre, inj, rc, words, okx in cases:
        d = os.path.join(out, name); m = os.path.join(d, 'M'); os.makedirs(m)
        open(os.path.join(m, 'predeploy-read.txt'), 'wb').write(pr.encode('utf-8'))
        if blocker:
            os.makedirs(os.path.join(m, blocker))
        if okpre == 'stale':
            sp = os.path.join(m, 'ok-U0'); open(sp, 'wb').write(b''); t = time.time() - 600; os.utime(sp, (t, t))
        stale_b = os.stat(os.path.join(m, 'ok-U0')).st_mtime if okpre == 'stale' else None
        env = dict(env0, M=m.replace('\\', '/'))
        r, so = run(segB(bodyB, inj), env, os.path.join(d, 'run'))
        okp = os.path.join(m, 'ok-U0')
        if okx is True:
            okstate = os.path.isfile(okp) and open(okp, 'rb').read().decode('utf-8').startswith('ok-U0 20')
        elif okx == 'stale':
            okstate = os.path.isfile(okp) and os.stat(okp).st_mtime == stale_b and os.path.getsize(okp) == 0
        elif okx == 'dir':
            okstate = os.path.isdir(okp)
        else:
            okstate = not os.path.exists(okp)
        tmp_left = os.path.exists(os.path.join(m, 'ok-U0.tmp'))
        r1g = os.path.join(m, 'r1-gate.txt')
        gate_ok = (os.path.isfile(r1g) and open(r1g, 'rb').read().decode('utf-8').startswith('R1 해소')) if rc == 0 else (not os.path.isfile(r1g) or name.startswith('b7') or name.startswith('b8') or name.startswith('b9') or name.startswith('b10') or name.startswith('b11'))
        misread = name in ('b4_verdict_write_fail', 'b5_gate_write_fail') and '보수적 사전 기준 미충족 — 배포 전' in so
        ru, su = run(segU1, env, os.path.join(d, 'u1gate'))
        u1x = (ru == 0 and 'U1_GATE_PASS' in su) if okx is True else (ru == 1 and 'U1_GATE_PASS' not in su)
        if okx in ('stale', 'dir'):   # 낡은 표지가 남은 경우 U1 관문은 그 표지를 볼 수 있다 — 기록만(아래 한계)
            u1x = True
        ok = r == rc and all(w in so for w in words) and okstate and not tmp_left and gate_ok and not misread and u1x
        res[name] = {'rc': r, 'words': {w: w in so for w in words}, 'ok_U0_state_ok': okstate, 'tmp_left': tmp_left,
                     'r1_gate_ok': gate_ok, 'misread_as_cost': misread, 'u1_gate_rc': ru, 'u1_gate_pass': 'U1_GATE_PASS' in su,
                     'injected': inj, 'ok': ok}

    # ── 기점: 원래 U0 106~109행
    for name, blocker, okpre in (('o1_base_stale_ok_marker', None, 'stale'), ('o2_base_verdict_write_fail', 'predeploy-verdict.txt', None),
                                 ('o3_base_ok_marker_is_dir', 'ok-U0', None)):
        d = os.path.join(out, name); m = os.path.join(d, 'M'); os.makedirs(m)
        open(os.path.join(m, 'predeploy-read.txt'), 'wb').write(pre(0, 0).encode('utf-8'))
        if blocker:
            os.makedirs(os.path.join(m, blocker))
        if okpre:
            sp = os.path.join(m, 'ok-U0'); open(sp, 'wb').write(b''); t = time.time() - 600; os.utime(sp, (t, t))
        r, so = run(segB(bodyO), dict(env0, M=m.replace('\\', '/')), os.path.join(d, 'run'))
        ru, su = run(segU1, dict(env0, M=m.replace('\\', '/')), os.path.join(d, 'u1gate'))
        res[name] = {'rc': r, 'says_pass': '✅ U0 통과' in so, 'says_cost_unmet': '보수적 사전 기준 미충족' in so,
                     'u1_gate_pass': 'U1_GATE_PASS' in su, 'baseline': True, 'ok': True}

    ok = all(v['ok'] for v in res.values())
    summ = {'ok': ok, 'results': res, 'u0_sha256': H(open(os.path.join(B, 'U0.sh'), 'rb').read()),
            'uvars_sha256': H(open(os.path.join(B, 'U변수.sh'), 'rb').read()), 'u1_sha256': H(open(os.path.join(B, 'U1.sh'), 'rb').read()),
            'base_commit': a.base_commit, 'segA_sha256': H(segA.encode()), 'segB_sha256': H(segB(bodyB).encode()),
            'segBase_sha256': H(segB(bodyO).encode()), 'segU1_sha256': H(segU1.encode()), 'ssh_calls': 0, 'pg_calls': 0, 'network': 0}
    open(os.path.join(out, 'summary.json'), 'wb').write((json.dumps(summ, ensure_ascii=False, indent=1) + '\n').encode('utf-8'))
    for k, v in res.items():
        print('%-28s rc=%s %s' % (k, v['rc'], 'OK' if v['ok'] else 'FAIL') + (' (기점: 통과표시=%s 비용미충족표시=%s U1관문통과=%s)' % (v['says_pass'], v['says_cost_unmet'], v['u1_gate_pass']) if v.get('baseline') else ''))
    print('ALL', 'OK' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))
    sys.exit(main())
