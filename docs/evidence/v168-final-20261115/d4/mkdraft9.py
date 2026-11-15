# v1.167 D4 초안(V167D4, v167-race-20261115/d4/blocks-d4) → v1.168 최종 시험 SHA 고정 판(V168D4). 총괄 v1.168(416차) §3-6.
# 🔴 미승인·서버 실행 금지 판. 원본 V167D4 는 덮어쓰지 않는다(출력 폴더는 없어야 한다).
# 바꾸는 것: 판 표지 V167D4→V168D4·기록 폴더 ~/maint-v167→~/maint-v168·판 문구 v1.167→v1.168 통합 후보,
#            NEW 99e6ab10→최종 시험 SHA(인자), DIFFN 130→N경로(실제 git diff --name-only b5cd1d0 NEW, 인자 파일).
# 그대로: OLD·GS·RARX 62줄·JARN 881 895·JARM(제품·빌드·프런트 입력이 99e6ab10 과 같을 때만 — 인자로 확인값을 받는다)·IMGOLD·FEIMGOLD·
#         관문/조회문 해시, 블록 순서·JARM 대조 위치·다른 멈춤 조건.
# 바꾸는 곳마다 횟수가 정확히 맞아야 한다(아니면 멈춘다). 원본 표기가 남으면 멈춘다.
# 사용: python -I -X utf8 mkdraft9.py <V167D4 blocks-d4> <출력 폴더> <DIFFN 목록 파일> <NEW 40자리> <시험 경로 수> <JARM 64자리>
import pathlib, re, sys

SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
DIFF = pathlib.Path(sys.argv[3]).read_text(encoding='utf-8').split()
NEW = sys.argv[4]
NTEST = int(sys.argv[5])
JARM = sys.argv[6]
assert re.fullmatch(r'[0-9a-f]{40}', NEW), NEW
assert re.fullmatch(r'[0-9a-f]{64}', JARM), JARM
OLDNEW = '99e6ab109c8a2b3778d2d5ddb430e06d1f308d6f'
OLDJARM = '7df5758fc48231372a9a507b228bafb5bfed1648332a8566e0881d80e2e1176d'
assert JARM == OLDJARM, '제품 입력이 바뀌었으면 이 생성기를 쓰지 않는다(새 jar·JARM 계산 필요)'
N = len(DIFF)
assert N == NTEST + 30, (N, NTEST)   # 비시험 30경로(제품 Java 20·V96 1·build.gradle 1·프런트 8)는 99e6ab10 과 같아야 한다
assert not DST.exists(), DST


def rep(s, x, y, n=1):
    c = s.count(x)
    if c != n:
        raise SystemExit('바꿀 곳 수 다름: %r (기대 %d, 실제 %d)' % (x[:80], n, c))
    return s.replace(x, y)


blocks = {p.name: p.read_text(encoding='utf-8') for p in SRC.glob('*.sh')}
names = ['U0.sh', 'U1.sh', 'U1되돌림.sh', 'U2.sh', 'U3.sh', 'U4.sh', 'UF2.sh', 'U되돌림.sh', 'U뒤관측.sh', 'U변수.sh']
assert sorted(blocks) == sorted(names), sorted(blocks)

out = {}
for n, s in blocks.items():
    s = s.replace('v1.167 통합 후보', 'v1.168 통합 후보')
    s = s.replace('V167D4', 'V168D4')
    s = s.replace('maint-v167', 'maint-v168')
    out[n] = s

short = NEW[:8]
u = out['U변수.sh']
u = rep(u, '(candidate/order-poll-race-b987d7a7 99e6ab10, 88f06345 + 주문 확인 경합 보완 19d1cc61)',
        '(candidate/order-poll-race-b987d7a7 %s, 99e6ab10 + 시험 전용 보완 — 제품·빌드·프런트 입력은 99e6ab10 과 같음)' % short)
u = rep(u, '원본 구조 V161D4(v161-20261113/d4, 그 원본 V160D4·V159D4·SMA20PD4 sma20p-d4-af7ffa58), 생성기 mkdraft8.py(값 변경 + JARM 대조 추가)',
        '원본 구조 V167D4(v167-race-20261115/d4, 그 원본 V161D4·V160D4·V159D4·SMA20PD4), 생성기 mkdraft9.py(값만 변경)')
u = rep(u, 'NEW=' + OLDNEW, 'NEW=' + NEW)
a = u.index('DIFFN="$(printf \'%s\\n\' ')
b = u.index('\n', u.index('# git diff --name-only OLD NEW — 130경로'))
lines = ['DIFFN="$(printf \'%s\\n\' ' + DIFF[0] + ' \\']
lines += ['  ' + p + ' \\' for p in DIFF[1:-1]]
lines += ['  ' + DIFF[-1] + ')"   # git diff --name-only OLD NEW — %d경로(시험 %d·제품 Java 20·V96 1·build.gradle 1·프런트 8). 앞 판 99e6ab10 목록에 시험 전용 %d경로 추가'
          % (N, NTEST, N - 130)]
u = u[:a] + '\n'.join(lines) + u[b:]
u = rep(u, '# V168D4: 새 jar 항목 이름·sha256 목록(jarmanifest, 895줄)의 sha256 — 로컬 bootJar 99e6ab10.',
        '# V168D4: 새 jar 항목 이름·sha256 목록(jarmanifest, 895줄)의 sha256 — 로컬 bootJar 99e6ab10(%s 와 제품·빌드·프런트 입력 동일).' % short)
out['U변수.sh'] = u

u0 = out['U0.sh']
u0 = rep(u0, '[ "$NEW" = ' + OLDNEW + ' ]', '[ "$NEW" = ' + NEW + ' ]')
u0 = rep(u0, '기대 130경로(시험 100·제품 Java 20·V96 1·build.gradle 1·프런트 8)',
         '기대 %d경로(시험 %d·제품 Java 20·V96 1·build.gradle 1·프런트 8)' % (N, NTEST))
out['U0.sh'] = u0

u1 = out['U1.sh']
u1 = rep(u1, '후보 99e6ab10 목록(JARM)과 다름', '후보 %s(제품 입력 99e6ab10) 목록(JARM)과 다름' % short)
out['U1.sh'] = u1

for n, s in out.items():
    t = s.replace('원본 구조 V167D4(v167-race-20261115/d4', '').replace('99e6ab10 + 시험 전용', '').replace('입력은 99e6ab10 과', '') \
         .replace('로컬 bootJar 99e6ab10(', '').replace('88f06345·99e6ab10 이 같은', '').replace('제품 입력 99e6ab10)', '').replace('앞 판 99e6ab10 목록', '')
    for old in ('V167D4', 'maint-v167', 'v1.167 통합 후보', OLDNEW, '130경로', '99e6ab10'):
        if old in t:
            raise SystemExit('원본 표기 남음: %s in %s' % (old, n))
DST.mkdir(parents=True)
for n, s in out.items():
    (DST / n).write_text(s, encoding='utf-8', newline='\n')
print('완료', len(out), '블록', N, '경로')
