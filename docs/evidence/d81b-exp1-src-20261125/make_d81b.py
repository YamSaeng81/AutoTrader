"""D81B-EXP1 실행 출처 생성기(총괄 v1.247 §2) — B 포함판 1b719645 단일 배포용.
고정 출처 794480ea(D81A2-EXP1)의 9블록·추출기·실행 틀을 git show 로 꺼내 지정 문자열만 정확히 1회씩 바꾼다.
  U변수: 1행 기록 경로 ~/maint-d81-20261127-exp1 → ~/maint-d81b-20261127-exp1(+판 설명), 2행 NEW → 1b719645,
         DIFFN(git diff --name-only OLD NEW, 48경로), RARX(운영 0e1d2a02 보존 jar ↔ 1b719645 LF jar 를 U1 비교기 v2 원문으로 직접 비교, 42줄),
         JARN "(끝) 895 900", JARM e8f42507…, 감시 스크립트 설명의 후보명(블롭 동일 확인값은 그대로)
  U0:    4행 핀(경로·NEW·JARN·JARM), 후보 없음/diff 경로 수 오류 문구
  U1:    jar 차이 줄 수·항목 수·JARM 오류 문구(검사 식은 그대로)
  U1되돌림·U2·U3·U4·U되돌림·U뒤관측·F·G: 바이트 그대로. UB=D81D4·OLD·이미지·V96·SQL v3·pf3ok·복구 관문 4판 불변.
  extract.py: 1행 설명·블록 경로·U변수/U0/U1 고정 해시·주석. run.sh: 2행 설명·기본 실행 폴더·U변수/U0/U1 고정 해시·판 표지.
자체 점검: 같은 서식 함수로 옛 d81 입력(git diff OLD d81c3f2f · 저장 d81 jar 차이 목록)을 넣으면 794480ea 원문 DIFFN·RARX 와 바이트가 같아야 한다.
사용: python -I -X utf8 make_d81b.py   (저장소 루트에서. 출력 폴더 d4/·runner/ 가 이미 있으면 멈춘다. 서버·ssh 호출 없음)
"""
import hashlib, os, re, subprocess, sys

SRC = '794480ea1041714c6f531d32c8051f9982f01609'
OLDD = 'docs/evidence/d81a2-exp1-src-20261125/'
HERE = os.path.dirname(os.path.abspath(__file__))
NAMES = ['U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'U4', 'U되돌림', 'U뒤관측']
CHANGED = ('U변수', 'U0', 'U1')
OLD = '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d'
D81 = 'd81c3f2f91c0239aacb0e1176dde9b898f4d7466'
NEW = '1b719645ef907cb1aa45ffa01438fcfda7566758'
OLDM, NEWM = '~/maint-d81-20261127-exp1', '~/maint-d81b-20261127-exp1'
OJARM = '33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509'
JARM = 'e8f425079d0fb77a9f224ba027ad16a18415e74238dac41edcbe2f9a2018a546'
JDIFF = os.path.join(HERE, 'jarcmp', 'jar-diff-0e1d2a02-vs-1b719645.txt')   # U1 비교기 v2 원문 출력(UTF-8, LF)
D81JD = 'docs/evidence/dec-final-abc-20261119/jar-d81c3f2f/jar-diff-vs-0e1d2a02.txt'
H = lambda b: hashlib.sha256(b).hexdigest()


def git(*a):
    return subprocess.run(['git', '-c', 'core.quotepath=false'] + list(a), capture_output=True, check=True).stdout


def show(p):
    return git('show', SRC + ':' + p)


def rep(t, a, b, what):
    n = t.count(a)
    if n != 1:
        print('치환 대상 %r 가 %d 회(1 이어야 함): %s' % (a[:70], n, what)); sys.exit(1)
    return t.replace(a, b)


def fmt_diffn(paths, comment):
    return "DIFFN=\"$(printf '%s\\n' " + " \\\n  ".join(paths) + ")\"   # " + comment


def fmt_rarx(lines, comment):
    return "RARX=\"$(printf '%s\\n' " + " \\\n  ".join("'%s'" % x for x in lines) + ")\"   # " + comment


def span(t, start, what):
    """start 로 시작하는 줄부터 그 정의가 끝나는 줄(`)"   # ` 포함)까지 — 정확히 1개."""
    m = list(re.finditer(r'^' + re.escape(start) + r'.*?\)"   # [^\n]*$', t, re.M | re.S))
    if len(m) != 1:
        print('정의 구간 %s 가 %d 개' % (what, len(m))); sys.exit(1)
    return m[0].group(0)


def main():
    d4, rn = os.path.join(HERE, 'd4'), os.path.join(HERE, 'runner')
    if os.path.exists(d4) or os.path.exists(rn):
        print('출력 폴더가 이미 있다 — 멈춤'); sys.exit(1)
    # 입력 — DIFFN 은 고정 Git, RARX 는 보존 jar 직접 비교 결과
    np = git('diff', '--name-only', OLD, NEW).decode('utf-8').split('\n')[:-1]
    op = git('diff', '--name-only', OLD, D81).decode('utf-8').split('\n')[:-1]
    prod = [x for x in np if x.startswith('web-api/src/main/java/')]; test = [x for x in np if x.startswith('web-api/src/test/java/')]
    if len(np) != 48 or len(prod) != 16 or len(test) != 32 or any(re.search(r"[\s'\"$\\]", x) for x in np):
        print('DIFFN 입력이 48경로(제품 Java 16·시험 32)·안전 문자 아님'); sys.exit(1)
    jb = open(JDIFF, 'rb').read()
    if b'\r' in jb:
        print('jar 차이 원문에 CR'); sys.exit(1)
    jl = jb.decode('utf-8').split('\n')
    if jl[-1] != '' or jl[-2] != '(끝) 895 900':
        print('jar 차이 원문 끝 줄이 (끝) 895 900 아님'); sys.exit(1)
    RAR = jl[:-2]
    if len(RAR) != 42 or sum(x.startswith('변경 ') for x in RAR) != 37 or sum(x.startswith('추가 ') for x in RAR) != 5 or any("'" in x for x in RAR):
        print('jar 차이가 42줄(변경 37·추가 5·삭제 0) 아님'); sys.exit(1)
    d81 = [x for x in open(D81JD, encoding='utf-8').read().split('\n')[1:] if x]
    d81 = sorted(d81, key=lambda x: x.split(' ', 1)[1])   # mkd81.py 와 같은 비교기 순서
    os.makedirs(os.path.join(d4, 'blocks-d81')); os.makedirs(rn)
    out, src = {}, {}
    for n in NAMES:
        b = show(OLDD + 'd4/blocks-d81/' + n + '.sh'); src[n] = H(b)
        t = b.decode('utf-8')
        if n == 'U변수':
            t = rep(t, 'M=' + OLDM + '; OPS=', 'M=' + NEWM + '; OPS=', n)
            t = rep(t, '   # v1.241 §2 D81A2-EXP1: 기록 경로 새 이름. mkdir 은 U0 만(새 경로가 이미 있으면 U0 가 멈춘다)',
                    '   # v1.247 §2 D81B-EXP1: 후보 1b719645(B 포함, 제품 c43e1f1a 동일)·기록 경로 ~/maint-d81b-20261127-exp1. 이 줄 뒤 설명의 d81c3f2f 는 원판 이력. mkdir 은 U0 만(새 경로가 이미 있으면 U0 가 멈춘다)', n)
            t = rep(t, 'NEW=' + D81 + '\n', 'NEW=' + NEW + '\n', n)
            od = span(t, 'DIFFN="$(printf', 'DIFFN')
            oc = od.rsplit(')"   # ', 1)[1]
            if fmt_diffn(op, oc) != od:
                print('자체 점검 실패: 옛 d81 DIFFN 재생성 ≠ 794480ea 원문'); sys.exit(1)
            t = rep(t, od, fmt_diffn(np, 'git diff --name-only OLD NEW — 48경로(제품 Java 16·시험 32, 리소스·마이그레이션·프런트·build.gradle 0). make_d81b.py 가 고정 Git 에서 기계 생성(v1.247 §2)'), n)
            orx = span(t, 'RARX="$(printf', 'RARX')
            if fmt_rarx(d81, orx.rsplit(')"   # ', 1)[1]) != orx:
                print('자체 점검 실패: 옛 d81 RARX 재생성 ≠ 794480ea 원문'); sys.exit(1)
            t = rep(t, orx, fmt_rarx(RAR, '운영 0e1d2a02 보존 jar(5c53c9e3…, JARM b6587953) 895항목 대 후보 1b719645 LF jar 900항목(c631cf4d…), U1 비교기 v2 원문 직접 비교 — 변경 37·추가 5·삭제 0(v1.247 §2, d81b-exp1-src-20261125/jarcmp)'), n)
            t = rep(t, 'JARN="(끝) 895 898"   # 총괄 v1.198 §2 확정 — 운영 LF 895항목 → 후보 898항목',
                    'JARN="(끝) 895 900"   # 총괄 v1.247 §1 확정 — 운영 LF 895항목 → 후보 900항목', n)
            ol = [x for x in t.split('\n') if x.startswith('JARM=' + OJARM)]
            if len(ol) != 1:
                print('JARM 줄 %d 개' % len(ol)); sys.exit(1)
            t = rep(t, ol[0], 'JARM=%s   # 총괄 v1.247 §1 수용 — 후보 1b719645 LF jar 항목 이름·sha256 목록(900줄, one-entry-final-20261125/jar-1b719645/jar-manifest-1b719645.lf.txt = d81b-exp1-src-20261125/jarcmp/jar-manifest-1b719645.u1.txt)의 sha256' % JARM, n)
            t = rep(t, '운영 0e1d2a02 과 후보 d81c3f2f 의 start_66.py', '운영 0e1d2a02 과 후보 1b719645 의 start_66.py', n)
            t = rep(t, '(운영 0e1d2a02 · 후보 d81c3f2f — 같다, mkd81.py 확인)', '(운영 0e1d2a02 · 후보 1b719645 — 같다, make_d81b.py 확인)', n)
        elif n == 'U0':
            t = rep(t, '[ "$M" = "$HOME/maint-d81-20261127-exp1" ]', '[ "$M" = "$HOME/maint-d81b-20261127-exp1" ]', n)
            t = rep(t, '[ "$NEW" = %s ]' % D81, '[ "$NEW" = %s ]' % NEW, n)
            t = rep(t, '[ "${JARN:-}" = "(끝) 895 898" ]', '[ "${JARN:-}" = "(끝) 895 900" ]', n)
            t = rep(t, '[ "${JARM:-}" = %s ]' % OJARM, '[ "${JARM:-}" = %s ]' % JARM, n)
            t = rep(t, 'stop "후보 d81c3f2f 없음"', 'stop "후보 1b719645 없음"', n)
            t = rep(t, '기대 43경로(제품 Java 14·시험 29)가 아님', '기대 48경로(제품 Java 16·시험 32)가 아님', n)
        elif n == 'U1':
            t = rep(t, '기대 38줄(변경 35·추가 3·삭제 0)이 아님', '기대 42줄(변경 37·추가 5·삭제 0)이 아님', n)
            t = rep(t, '항목 수가 895 → 898 이 아님', '항목 수가 895 → 900 이 아님', n)
            t = rep(t, '후보 d81c3f2f LF 목록(JARM 33a06f0e…)과 다름', '후보 1b719645 LF 목록(JARM e8f42507…)과 다름', n)
        nb = t.encode('utf-8')
        if (nb == b) == (n in CHANGED):
            print('변경 여부 이상: %s' % n); sys.exit(1)
        if n != 'U변수' and (b'd81c3f2f' in nb) != (n == 'U변수'):
            print('옛 후보 문자열 잔존: %s' % n); sys.exit(1)
        open(os.path.join(d4, 'blocks-d81', n + '.sh'), 'wb').write(nb)
        out[n] = H(nb)
    with open(os.path.join(d4, 'blocks-d81.sha256'), 'wb') as f:
        for n in NAMES:
            f.write(('%s *%s.sh\n' % (out[n], n)).encode('utf-8'))
    # 추출기
    t = show(OLDD + 'runner/extract.py').decode('utf-8')
    t = rep(t, t.split('\n', 1)[0] + '\n',
            '# D81B-EXP1 실행 틀 추출기(총괄 v1.247 §2) — d81a2-exp1-src-20261125/runner/extract.py(794480ea)에서 블록 경로(d81b-exp1-src-20261125)와 U변수·U0·U1 고정 해시(후보 1b719645·JARM/JARN/차이 목록·기록 경로 ~/maint-d81b-20261127-exp1)만 바꿨다.\n', 'extract 1행')
    t = rep(t, "D = 'docs/evidence/d81a2-exp1-src-20261125/d4/'", "D = 'docs/evidence/d81b-exp1-src-20261125/d4/'", 'extract D')
    for k in CHANGED:
        t = rep(t, "'%s': '%s'" % (k, src[k]), "'%s': '%s'" % (k, out[k]), 'extract ' + k)
    t = rep(t, '#   U 9블록: 출처 커밋의 docs/evidence/d81a2-exp1-src-20261125/', '#   U 9블록: 출처 커밋의 docs/evidence/d81b-exp1-src-20261125/', 'extract 주석')
    open(os.path.join(rn, 'extract.py'), 'wb').write(t.encode('utf-8'))
    # 실행 틀
    t = show(OLDD + 'runner/run.sh').decode('utf-8')
    l2 = t.split('\n')[1] + '\n'
    t = rep(t, l2, '# D81B-EXP1 실행 틀(총괄 v1.247 §2) — d81a2-exp1-src-20261125/runner/run.sh(794480ea)에서 기본 실행 폴더(/d/tmp/d81brun-20261127-exp1)·U변수/U0/U1 고정 해시·판 표지 문자열만 바꿨다. 기록 계약·전송 줄은 그대로. 서버 기록 ~/maint-d81b-20261127-exp1(U변수).\n', 'run 2행')
    t = rep(t, 'D=${D4RUN:-/d/tmp/d81run-20261127-exp1};', 'D=${D4RUN:-/d/tmp/d81brun-20261127-exp1};', 'run D')
    for k in CHANGED:
        t = rep(t, "'%s': '%s'" % (k, src[k]), "'%s': '%s'" % (k, out[k]), 'run ' + k)
    t = rep(t, ' 판=D81A2X1"', ' 판=D81BX1"', 'run 표지')
    open(os.path.join(rn, 'run.sh'), 'wb').write(t.encode('utf-8'))
    for n in NAMES:
        print('%-8s %s → %s %s' % (n, src[n][:16], out[n][:16], '바뀜' if n in CHANGED else '그대로'))
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))
    sys.exit(main())
