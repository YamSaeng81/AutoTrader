# v1.159 통합 후보 초안 D4(V159D4, v159-20261113/d4/blocks-d4) → v1.160 통합 후보 초안 D4(V160D4). 총괄 v1.160(400차) §4·§5.
# 🔴 미승인·서버 실행 금지 초안. 원본 V159D4 는 덮어쓰지 않는다(출력 폴더는 없어야 한다).
# 바꾸는 것(값만): 판 표지 V159D4→V160D4·기록 폴더 ~/maint-v159→~/maint-v160·판 문구 v1.159→v1.160 통합 후보,
#                  NEW 802b691a→88f06345(시험 관측 커밋, 제품·빌드 입력은 802b691a 와 같다),
#                  DIFFN 100→102경로(시험 70→72: SchedulerProfileCleanupTest 변경·OrderFlowObservation 추가),
#                  GS 미정→6385e1d5…(총괄 v1.160 §5 지정), U변수 1행 주석(v159 생성기가 남긴 원본 이름 표기 오류 정정),
#                  jar 출처 주석에 88f06345 동일 입력 표기.
# 그대로: FEIMGOLD=미정(다음 승인된 읽기에서 기입), 블록 구조·순서·멈춤 조건·RARX/JARN(백엔드 입력 같음)·관문/조회문 해시 변수.
# 바꾸는 곳마다 횟수가 정확히 맞아야 한다(아니면 멈춘다). 원본 표기가 남으면 멈춘다.
# 사용: python -I -X utf8 mkdraft6.py <V159D4 blocks-d4> <출력 폴더> <NEW 40자리> <DIFFN 102 파일> <GS 40자리> <옛 DIFFN 100 파일>
import pathlib, re, sys

SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
NEW, GS = sys.argv[3], sys.argv[5]
DIFF = [l for l in pathlib.Path(sys.argv[4]).read_text(encoding='utf-8').splitlines() if l]
ODIFF = [l for l in pathlib.Path(sys.argv[6]).read_text(encoding='utf-8').splitlines() if l]
OLDNEW = '802b691a6606821d6db838bf5aca0f4cf468205a'
assert re.fullmatch(r'[0-9a-f]{40}', NEW) and re.fullmatch(r'[0-9a-f]{40}', GS), (NEW, GS)
assert len(DIFF) == 102 and len(ODIFF) == 100 and set(ODIFF) < set(DIFF), (len(DIFF), len(ODIFF))
added = sorted(set(DIFF) - set(ODIFF))
assert added == ['web-api/src/test/java/com/cryptoautotrader/api/service/SchedulerProfileCleanupTest.java',
                 'web-api/src/test/java/com/cryptoautotrader/api/support/OrderFlowObservation.java'], added
n7 = NEW[:7]
NL, BS = chr(10), chr(92)
assert not DST.exists(), DST


def rep(s, x, y, n=1):
    c = s.count(x)
    if c != n:
        raise SystemExit('바꿀 곳 수 다름: %r (기대 %d, 실제 %d)' % (x[:80], n, c))
    return s.replace(x, y)


def diffn_text(paths, note):
    return 'DIFFN="$(printf ' + "'%s" + BS + "n' " + (' ' + BS + NL + '  ').join(paths) + ')"   ' + note


OLDNOTE = '# git diff --name-only OLD NEW — 100경로(시험 70·제품 Java 20·V96 1·build.gradle 1·프런트 8)'
NEWNOTE = '# git diff --name-only OLD NEW — 102경로(시험 72·제품 Java 20·V96 1·build.gradle 1·프런트 8)'

blocks = {p.name: p.read_text(encoding='utf-8') for p in SRC.glob('*.sh')}
names = ['U0.sh', 'U1.sh', 'U1되돌림.sh', 'U2.sh', 'U3.sh', 'U4.sh', 'UF2.sh', 'U되돌림.sh', 'U뒤관측.sh', 'U변수.sh']
assert sorted(blocks) == sorted(names), sorted(blocks)
# 판 표지·판 문구 출현 횟수(원본에서 센 값) — 다르면 원본이 바뀐 것이다
UBN = {'U0.sh': 1, 'U1.sh': 1, 'U1되돌림.sh': 1, 'U2.sh': 1, 'U3.sh': 1, 'U4.sh': 1, 'UF2.sh': 2, 'U되돌림.sh': 1, 'U뒤관측.sh': 1, 'U변수.sh': 2}
PANN = {'U0.sh': 1, 'U1.sh': 2, 'U1되돌림.sh': 2, 'U2.sh': 2, 'U3.sh': 2, 'U4.sh': 2, 'UF2.sh': 2, 'U되돌림.sh': 2, 'U뒤관측.sh': 2, 'U변수.sh': 1}
out = {}
for name, s in blocks.items():
    if name == 'U변수.sh':
        lines = s.split(NL)
        assert lines[0].startswith('M=~/maint-v159; mkdir -p "$M"; OPS=~/ops-scripts; UB=V159D4   # v1.159 통합 후보(integration/v158 802b691)'), lines[0][:120]
        lines[0] = ('M=~/maint-v160; mkdir -p "$M"; OPS=~/ops-scripts; UB=V160D4   # v1.160 통합 후보(integration/v158 %s) 배포 초안 D4(미승인·실행 금지) 판 표지 — '
                    'U 블록 전부가 확인한다. 원본 구조 V159D4(v159-20261113/d4, 그 원본 SMA20PD4 sma20p-d4-af7ffa58), 생성기 mkdraft6.py(값만 변경). '
                    'backend+frontend 같은 릴리스, DDL V96(새 backend 기동 때 Flyway)' % n7)
        s = NL.join(lines)
        s = rep(s, 'NEW=' + OLDNEW, 'NEW=' + NEW)
        old_block = diffn_text(ODIFF, OLDNOTE)
        s = rep(s, old_block, diffn_text(DIFF, NEWNOTE))   # 원본 목록을 같은 형식으로 다시 만든 것이 원문과 정확히 같아야 바뀐다
        s = rep(s, '로컬 bootJar 4a4a04a3=802b691a 입력(0834382a…)', '로컬 bootJar 4a4a04a3=802b691a=88f06345 입력(0834382a…, 88f06345 는 시험 2경로만 다름)')
        s = rep(s, 'GS=미정   # 관문 3판·손실 조회문 v2 를 담은 main 고정 커밋 — 근거 커밋 뒤 총괄 승인 판에 기입. 미정이면 U0·U되돌림이 멈춘다',
                'GS=' + GS + '   # 관문 3판·손실 조회문 v2 를 담은 main 고정 커밋(총괄 v1.160 §5 지정). 꺼낸 원문 해시가 GATESQL·GATESH·PFSUM 과 다르면 U0·U되돌림이 멈춘다')
        s = rep(s, 'UB=V159D4', 'UB=V160D4', 0)   # 원본의 판 표지 두 번은 모두 1행이었다 — 재작성 뒤 0이어야 한다
        s = rep(s, 'v1.159 통합 후보', 'v1.160 통합 후보', 0)
    else:
        s = rep(s, 'V159D4', 'V160D4', UBN[name])
        s = rep(s, 'v1.159 통합 후보', 'v1.160 통합 후보', PANN[name])
        if name == 'U0.sh':
            s = rep(s, '"$M" = "$HOME/maint-v159"', '"$M" = "$HOME/maint-v160"')
            s = rep(s, '[ "$NEW" = ' + OLDNEW + ' ]', '[ "$NEW" = ' + NEW + ' ]')
            s = rep(s, '기대 100경로(시험 70·', '기대 102경로(시험 72·')
    for bad in ('V159D4' if name != 'U변수.sh' else 'UB=V159D4', 'maint-v159', OLDNEW if name != 'U변수.sh' else 'NEW=' + OLDNEW,
                '100경로', '시험 70·', 'v1.159 통합 후보', 'GS=미정'):
        if bad in s:
            raise SystemExit('원본 표기가 남음: %s %s' % (name, bad))
    out[name] = s

DST.mkdir(parents=True)
for name, s in out.items():
    (DST / name).write_bytes(s.encode('utf-8'))
print('생성', DST, len(out), '블록 · NEW', NEW, '· GS', GS, '· DIFFN', len(DIFF), '· 추가 경로', added)
