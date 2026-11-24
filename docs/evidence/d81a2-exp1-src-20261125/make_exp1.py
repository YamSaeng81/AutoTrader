"""D81A2-EXP1 정상 R1=0 전용 실행 출처 생성기(총괄 v1.241 §2).
고정 출처 65a384e5(D81A2)의 9블록·추출기·실행 틀을 git show 로 꺼내 지정 문자열만 정확히 1회씩 바꾼다.
  U변수 1행: 기록 경로 ~/maint-d81 → ~/maint-d81-20261127-exp1, `mkdir -p "$M"` 제거(새 경로 생성은 U0 만 한다)
  U0 4행: 경로 확인값을 새 경로로
  U0 5행 뒤: 새 경로가 이미 있으면(파일·디렉터리·링크) 멈춤 → 없을 때만 mkdir(‑p 아님)
  U0 106~107행: pf3ok 판정 기록·표시 확인, 0 → r1-gate 기록·재확인, 1 → 원래 멈춤(유지보수 예외 없음), 그 밖 → 형식 이상 멈춤
  U0 109행: ok-U0 — 이미 있으면 낡은 표지로 거절, 임시 파일에 고유 내용을 쓰고 다시 읽어 같을 때만 이름 바꾸기
  U1·U1되돌림·U2·U3·U4·U되돌림·U뒤관측·F·G: 바이트 그대로
  extract.py: 블록 경로·U변수/U0 고정값·주석. run.sh: 기본 실행 폴더·U변수/U0 고정값·판 표지·주석
U0-maint2(756ba396)의 MAINT4/M4GATE·r1-diag-4·maint_gate4·KNOWN 예외는 넣지 않는다. pf3ok·배포 전 SQL·제품·보호 기준 불변.
사용: python -I make_exp1.py   (저장소 루트에서. 출력 폴더 d4/·runner/ 가 이미 있으면 멈춘다)
"""
import hashlib, os, subprocess, sys

SRC = '65a384e563a90d949c94a4e343ea491939e6f5f7'
OLDD = 'docs/evidence/d81a2-src-20261123/'
NEWD = 'docs/evidence/d81a2-exp1-src-20261125/'
HERE = os.path.dirname(os.path.abspath(__file__))
NAMES = ['U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'U4', 'U되돌림', 'U뒤관측']
NEWM = '~/maint-d81-20261127-exp1'
H = lambda b: hashlib.sha256(b).hexdigest()


def show(p):
    return subprocess.run(['git', 'show', SRC + ':' + p], capture_output=True, check=True).stdout


def rep(t, a, b, what):
    n = t.count(a)
    if n != 1:
        print('치환 대상 %r 가 %d 회(1 이어야 함): %s' % (a[:60], n, what)); sys.exit(1)
    return t.replace(a, b)


U0_V_OLD = '''pf3ok "$M/predeploy-read.txt" > "$M/predeploy-verdict.txt" && PV=0 || PV=$?; cat "$M/predeploy-verdict.txt"
[ "$PV" = 0 ] || stop "🔴 배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 $PV — 1 보수적 사전 기준 미충족 / 3 형식 이상·읽기 실패) — 배포 전 중단·보고. 한도·자본·세션·과거 행을 바꾸지 않는다(원문 predeploy-read.txt 보존)"
'''
U0_V_NEW = '''# ── v1.241 §2 정상 R1=0 전용 — 판정·필수 기록 실패 보완(U0-maint2 756ba396 의 정상 부분만. 유지보수 예외·추가 진단 SQL 없음) ──
# 판정 파일·r1-gate·표시의 실패는 기록 실패로 멈춘다 — "보수적 사전 기준 미충족"(비용 미확정)과 섞지 않는다. 어느 쪽이든 ok-U0 없음(U1 금지)
PV=0; pf3ok "$M/predeploy-read.txt" > "$M/predeploy-verdict.txt" || PV=$?
case "$PV" in 0) VL='판정 | 진행 가능' ;; 1) VL='판정 | 🔴 보수적 사전 기준 미충족' ;; *) VL= ;; esac
if [ -n "$VL" ]; then LC_ALL=C grep -q "^$VL" "$M/predeploy-verdict.txt" 2>/dev/null || stop "🔴 배포 전 판정 기록 실패(pf3ok 종료 $PV 인데 predeploy-verdict.txt 에 맞는 판정 줄 없음) — 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고"; fi
cat "$M/predeploy-verdict.txt" || stop "🔴 배포 전 판정 표시 실패 — 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고"
case "$PV" in
  0) G0="R1 해소 — 이번 U0 의 배포 전 읽기 v3 에서 R1 두 종류 0 · R3 판정 불가 0(원래 U0 기준, 유지보수 예외 없음)"
     { printf '%s\\n' "$G0" > "$M/r1-gate.txt" && [ "$(cat "$M/r1-gate.txt")" = "$G0" ]; } 2>/dev/null || stop "🔴 r1-gate 필수 기록 실패 — 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고"
     cat "$M/r1-gate.txt" || stop "🔴 r1-gate 표시 실패 — 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고" ;;
  1) stop "🔴 배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 1 — 보수적 사전 기준 미충족: R1 판정 불가 또는 R3 판정 불가 행) — 배포 전 중단·보고. 유지보수 예외 없음, ok-U0 없음(U1 금지). 한도·자본·세션·과거 행을 바꾸지 않는다(원문 predeploy-read.txt 보존)" ;;
  *) stop "🔴 배포 전 읽기 v3 형식 이상·읽기 실패(pf3ok 종료 $PV) — 조회 결과로 보지 않는다. 배포 전 중단·보고, ok-U0 없음(U1 금지). 원문 predeploy-read.txt 보존" ;;
esac
'''
U0_OK_OLD = 'touch "$M/ok-U0"; echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"\n'
U0_OK_NEW = '''[ ! -e "$M/ok-U0" ] && [ ! -L "$M/ok-U0" ] && [ ! -e "$M/ok-U0.tmp" ] && [ ! -L "$M/ok-U0.tmp" ] || stop "🔴 ok-U0(또는 임시 표지)가 이미 있다 — 낡은 표지를 이 U0 의 통과로 인정하지 않는다. U1 금지, ls -l $M 를 보고"
OKT="ok-U0 $(date -u '+%Y-%m-%dT%H:%M:%SZ') 경로=$M pid=$$ R1=0 R3 판정 불가 0"
{ printf '%s\\n' "$OKT" > "$M/ok-U0.tmp" && [ -f "$M/ok-U0.tmp" ] && [ "$(cat "$M/ok-U0.tmp")" = "$OKT" ] && mv -n "$M/ok-U0.tmp" "$M/ok-U0" && [ ! -e "$M/ok-U0.tmp" ] && [ -f "$M/ok-U0" ] && [ "$(cat "$M/ok-U0")" = "$OKT" ]; } 2>/dev/null || { rm -f "$M/ok-U0.tmp" 2>/dev/null; [ ! -e "$M/ok-U0" ] || [ "$(cat "$M/ok-U0" 2>/dev/null)" != "$OKT" ] || rm -f "$M/ok-U0" 2>/dev/null; stop "🔴 ok-U0 표지 기록 실패 — U1 로 넘어가지 않는다(U1 금지). ls -l $M 로 ok-U0 가 남았는지 확인·보고(남았으면 U1 을 돌리지 않는다)"; }
echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"
'''
U0_HEAD_OLD = '''[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 시작했다'''
U0_HEAD_NEW = '''[ ! -e "$M" ] && [ ! -L "$M" ] || stop "기록 경로 $M 이 이미 있다 — 삭제·이름 변경·덮어쓰기 없이 멈춘다. 다른 시도 기록을 이 시도의 전제로 쓰지 않는다(새 시도 이름·새 출처로만 다시). ls -la $M 를 보고"   # v1.241 §2
mkdir "$M" || stop "🔴 기록 경로 $M 를 만들지 못함 — 기록 실패, 아무것도 바꾸지 않았다"
[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 시작했다'''


def main():
    d4, rn = os.path.join(HERE, 'd4'), os.path.join(HERE, 'runner')
    if os.path.exists(d4) or os.path.exists(rn):
        print('출력 폴더가 이미 있다 — 멈춤'); sys.exit(1)
    os.makedirs(os.path.join(d4, 'blocks-d81')); os.makedirs(rn)
    out = {}
    for n in NAMES:
        b = show(OLDD + 'd4/blocks-d81/' + n + '.sh')
        t = b.decode('utf-8')
        if n == 'U변수':
            t = rep(t, 'M=~/maint-d81; mkdir -p "$M"; OPS=', 'M=' + NEWM + '; OPS=', n)
            l1, rest = t.split('\n', 1)
            t = l1 + '   # v1.241 §2 D81A2-EXP1: 기록 경로 새 이름. mkdir 은 U0 만(새 경로가 이미 있으면 U0 가 멈춘다)\n' + rest
        elif n == 'U0':
            t = rep(t, '[ "$M" = "$HOME/maint-d81" ]', '[ "$M" = "$HOME/maint-d81-20261127-exp1" ]', n)
            t = rep(t, U0_HEAD_OLD, U0_HEAD_NEW, n)
            t = rep(t, U0_V_OLD, U0_V_NEW, n)
            t = rep(t, U0_OK_OLD, U0_OK_NEW, n)
        nb = t.encode('utf-8')
        open(os.path.join(d4, 'blocks-d81', n + '.sh'), 'wb').write(nb)
        out[n] = H(nb)
        assert (nb == b) == (n not in ('U변수', 'U0')), n
    with open(os.path.join(d4, 'blocks-d81.sha256'), 'wb') as f:
        for n in NAMES:
            f.write(('%s *%s.sh\n' % (out[n], n)).encode('utf-8'))
    old = {n: H(show(OLDD + 'd4/blocks-d81/' + n + '.sh')) for n in ('U변수', 'U0')}
    # 추출기
    t = show(OLDD + 'runner/extract.py').decode('utf-8')
    t = rep(t, t.split('\n', 1)[0] + '\n',
            '# D81A2-EXP1 실행 틀 추출기(총괄 v1.241 §2) — d81a2-src-20261123/runner/extract.py(65a384e5)에서 블록 경로(d81a2-exp1-src-20261125)와 U변수·U0 고정 해시(기록 경로 ~/maint-d81-20261127-exp1, 정상 R1=0 U0 기록 보완)만 바꿨다.\n', 'extract 1행')
    t = rep(t, "D = 'docs/evidence/d81a2-src-20261123/d4/'", "D = 'docs/evidence/d81a2-exp1-src-20261125/d4/'", 'extract D')
    t = rep(t, "'U변수': '%s'" % old['U변수'], "'U변수': '%s'" % out['U변수'], 'extract U변수')
    t = rep(t, "'U0': '%s'" % old['U0'], "'U0': '%s'" % out['U0'], 'extract U0')
    t = rep(t, '#   U 9블록: 출처 커밋의 docs/evidence/d81a2-src-20261123/', '#   U 9블록: 출처 커밋의 docs/evidence/d81a2-exp1-src-20261125/', 'extract 주석')
    open(os.path.join(rn, 'extract.py'), 'wb').write(t.encode('utf-8'))
    # 실행 틀
    t = show(OLDD + 'runner/run.sh').decode('utf-8')
    l2 = t.split('\n')[1] + '\n'
    t = rep(t, l2, '# D81A2-EXP1 실행 틀(총괄 v1.241 §2) — d81a2-src-20261123/runner/run.sh(65a384e5)에서 기본 실행 폴더(/d/tmp/d81run-20261127-exp1)·U변수/U0 고정 해시·판 표지 문자열만 바꿨다. 기록 계약·전송 줄은 그대로. 서버 기록 ~/maint-d81-20261127-exp1(U변수).\n', 'run 2행')
    t = rep(t, 'D=${D4RUN:-/d/tmp/d81run};', 'D=${D4RUN:-/d/tmp/d81run-20261127-exp1};', 'run D')
    t = rep(t, "'U변수': '%s'" % old['U변수'], "'U변수': '%s'" % out['U변수'], 'run U변수')
    t = rep(t, "'U0': '%s'" % old['U0'], "'U0': '%s'" % out['U0'], 'run U0')
    t = rep(t, ' 판=D81A2"', ' 판=D81A2X1"', 'run 표지')
    open(os.path.join(rn, 'run.sh'), 'wb').write(t.encode('utf-8'))
    for n in NAMES:
        print('%-8s %s %s' % (n, out[n][:16], '바뀜' if n in old else '그대로'))
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))
    sys.exit(main())
