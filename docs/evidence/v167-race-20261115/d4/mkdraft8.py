# v1.161 최종 후보 D4(V161D4, v161-20261113/d4/blocks-d4) → v1.167 주문 확인 경합 보완 후보 D4 초안(V167D4). 총괄 v1.167(414차) §4-6.
# 🔴 미승인·서버 실행 금지 판. 원본 V161D4 는 덮어쓰지 않는다(출력 폴더는 없어야 한다).
# 바꾸는 것: 판 표지 V161D4→V167D4·기록 폴더 ~/maint-v161→~/maint-v167·판 문구 v1.161→v1.167 통합 후보,
#            NEW 88f06345→99e6ab10(candidate/order-poll-race-b987d7a7), DIFFN 102→130경로(실제 git diff --name-only b5cd1d0 99e6ab10),
#            RARX 주석(이름 목록은 같은 62줄), JARM 추가(U변수 1줄·U0 존재 확인·U1 새 jar 항목 내용 목록 대조).
# 그대로: OLD·GS·RARX 62줄·JARN 881 895·IMGOLD·FEIMGOLD·관문/조회문 해시 변수, 블록 순서·다른 멈춤 조건.
# 바꾸는 곳마다 횟수가 정확히 맞아야 한다(아니면 멈춘다). 원본 표기가 남으면 멈춘다.
# 사용: python -I -X utf8 mkdraft8.py <V161D4 blocks-d4> <출력 폴더> <DIFFN 목록 파일> <jarmanifest.py> <JARM 64자리>
import pathlib, re, sys

SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
DIFF = pathlib.Path(sys.argv[3]).read_text(encoding='utf-8').split()
MAN = pathlib.Path(sys.argv[4]).read_text(encoding='utf-8')
JARM = sys.argv[5]
assert re.fullmatch(r'[0-9a-f]{64}', JARM), JARM
OLDNEW = '88f06345af31888c2c8669ebe8ad00ca02cfd6d8'
NEW = '99e6ab109c8a2b3778d2d5ddb430e06d1f308d6f'
assert len(DIFF) == 130, len(DIFF)
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
    s = s.replace('v1.161 통합 후보', 'v1.167 통합 후보')
    s = s.replace('V161D4', 'V167D4')
    s = s.replace('maint-v161', 'maint-v167')
    out[n] = s

u = out['U변수.sh']
u = rep(u, '(integration/v158 88f0634)', '(candidate/order-poll-race-b987d7a7 99e6ab10, 88f06345 + 주문 확인 경합 보완 19d1cc61)')
u = rep(u, '원본 구조 V160D4(v160-20261113/d4, 그 원본 V159D4·SMA20PD4 sma20p-d4-af7ffa58), 생성기 mkdraft7.py(값만 변경)',
        '원본 구조 V161D4(v161-20261113/d4, 그 원본 V160D4·V159D4·SMA20PD4 sma20p-d4-af7ffa58), 생성기 mkdraft8.py(값 변경 + JARM 대조 추가)')
u = rep(u, 'NEW=' + OLDNEW, 'NEW=' + NEW)
# DIFFN 블록 전체를 실제 목록으로 다시 쓴다
a = u.index('DIFFN="$(printf \'%s\\n\' ')
b = u.index('\n', u.index('# git diff --name-only OLD NEW — 102경로'))
lines = ['DIFFN="$(printf \'%s\\n\' ' + DIFF[0] + ' \\']
lines += ['  ' + p + ' \\' for p in DIFF[1:-1]]
lines += ['  ' + DIFF[-1] + ')"   # git diff --name-only OLD NEW — 130경로(시험 100·제품 Java 20·V96 1·build.gradle 1·프런트 8). 앞 판 88f06345 목록에 시험 전용 28경로 추가']
u = u[:a] + '\n'.join(lines) + u[b:]
u = rep(u, '# 보존 b5cd1d0.jar(a7ef759f…) 대 로컬 bootJar 4a4a04a3=802b691a=88f06345 입력(0834382a…, 88f06345 는 시험 2경로만 다름), 비교기 v2 — 변경 48·추가 14',
        '# 보존 b5cd1d0.jar(a7ef759f…) 대 로컬 bootJar 99e6ab10(f7450f13…), 비교기 v2 — 변경 48·추가 14. 🔴 88f06345 입력 jar(0834382a…)와 이름 목록이 같다 — 내용 구별은 JARM')
u = rep(u, 'JARN="(끝) 881 895"\n',
        'JARN="(끝) 881 895"\n'
        'JARM=' + JARM + '   # V167D4: 새 jar 항목 이름·sha256 목록(jarmanifest, 895줄)의 sha256 — 로컬 bootJar 99e6ab10. 88f06345 입력 jar 는 a91ffaaf…, b5cd1d0 는 a66d2ccd…\n')
out['U변수.sh'] = u

u0 = out['U0.sh']
u0 = rep(u0, '[ "$NEW" = ' + OLDNEW + ' ] && [ -n "${JARN:-}" ]', '[ "$NEW" = ' + NEW + ' ] && [ -n "${JARN:-}" ] && [ -n "${JARM:-}" ]')
u0 = rep(u0, '기대 102경로(시험 72·제품 Java 20·V96 1·build.gradle 1·프런트 8)', '기대 130경로(시험 100·제품 Java 20·V96 1·build.gradle 1·프런트 8)')
out['U0.sh'] = u0

u1 = out['U1.sh']
anchor = 'grep -qxF "$JARN" "$M/jar-diff.txt" || stop "항목 수가 881 → 895 가 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"\n'
add = ('# V167D4: 이름 목록(RARX)은 88f06345 입력 jar 와 같다 — 새 jar 의 항목 내용 목록 해시(JARM)로 후보 입력을 확인한다\n'
       'python3 - "$M/app-new.jar" > "$M/jar-manifest-new.txt" 2>&1 <<\'EOF\' && JMC=0 || JMC=$?\n' + MAN.rstrip('\n') + '\nEOF\n'
       '[ "$JMC" = 0 ] || stop "새 jar 항목 목록 거절(종료 $JMC) — jar-manifest-new.txt 를 보고(재기동 전). U1-되돌림"\n'
       'JMH=$(sha256sum < "$M/jar-manifest-new.txt" | cut -c1-64); echo "JARM $JMH" | tee "$M/jar-manifest-sha.txt"\n'
       '[ "$JMH" = "$JARM" ] || stop "새 jar 항목 내용이 후보 99e6ab10 목록(JARM)과 다름 — 옛 후보 jar 이거나 다른 입력. jar-manifest-new.txt 를 보고(재기동 전). U1-되돌림"\n')
u1 = rep(u1, anchor, anchor + add)
out['U1.sh'] = u1

for n, s in out.items():
    for old in ('V161D4', 'maint-v161', 'v1.161 통합 후보', OLDNEW, '102경로'):
        if old in s.replace('원본 구조 V161D4(v161-20261113/d4', ''):
            raise SystemExit('원본 표기 남음: %s in %s' % (old, n))
DST.mkdir(parents=True)
for n, s in out.items():
    (DST / n).write_text(s, encoding='utf-8', newline='\n')
print('완료', len(out), '블록')
