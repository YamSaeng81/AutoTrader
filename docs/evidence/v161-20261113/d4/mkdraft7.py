# v1.160 통합 후보 초안 D4(V160D4, v160-20261113/d4/blocks-d4) → v1.161 최종 후보 D4(V161D4). 총괄 v1.161(402차) §4-2.
# 🔴 미승인·서버 실행 금지 판. 원본 V160D4 는 덮어쓰지 않는다(출력 폴더는 없어야 한다).
# 바꾸는 것(값만): 판 표지 V160D4→V161D4·기록 폴더 ~/maint-v160→~/maint-v161·판 문구 v1.160 통합 후보→v1.161 통합 후보,
#                  FEIMGOLD 미정→실제 운영 읽기 값(n03-1113-1000/frontend-image.txt, 2026-11-13 13:39:36 KST),
#                  U변수 1행 주석(원본·생성기 이름).
# 그대로: NEW 88f06345·GS 6385e1d5·DIFFN 102경로·RARX/JARN·IMGOLD·관문/조회문 해시 변수, 블록 구조·순서·멈춤 조건.
# 바꾸는 곳마다 횟수가 정확히 맞아야 한다(아니면 멈춘다). 원본 표기가 남으면 멈춘다.
# 사용: python -I -X utf8 mkdraft7.py <V160D4 blocks-d4> <출력 폴더> <FEIMGOLD sha256:64자리> <frontend 컨테이너 64자리> <조회 시각 문구>
import pathlib, re, sys

SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
FE, FEC, FET = sys.argv[3], sys.argv[4], sys.argv[5]
assert re.fullmatch(r'sha256:[0-9a-f]{64}', FE), FE
assert re.fullmatch(r'[0-9a-f]{64}', FEC), FEC
NEW = '88f06345af31888c2c8669ebe8ad00ca02cfd6d8'
GS = '6385e1d5f64eb09a95791ba253a30d2b21341082'
NL = chr(10)
assert not DST.exists(), DST


def rep(s, x, y, n=1):
    c = s.count(x)
    if c != n:
        raise SystemExit('바꿀 곳 수 다름: %r (기대 %d, 실제 %d)' % (x[:80], n, c))
    return s.replace(x, y)


blocks = {p.name: p.read_text(encoding='utf-8') for p in SRC.glob('*.sh')}
names = ['U0.sh', 'U1.sh', 'U1되돌림.sh', 'U2.sh', 'U3.sh', 'U4.sh', 'UF2.sh', 'U되돌림.sh', 'U뒤관측.sh', 'U변수.sh']
assert sorted(blocks) == sorted(names), sorted(blocks)
# 원본(V160D4)에서 센 출현 횟수 — mkdraft6 의 UBN·PANN 과 같다. 다르면 원본이 바뀐 것이다
UBN = {'U0.sh': 1, 'U1.sh': 1, 'U1되돌림.sh': 1, 'U2.sh': 1, 'U3.sh': 1, 'U4.sh': 1, 'UF2.sh': 2, 'U되돌림.sh': 1, 'U뒤관측.sh': 1}
PANN = {'U0.sh': 1, 'U1.sh': 2, 'U1되돌림.sh': 2, 'U2.sh': 2, 'U3.sh': 2, 'U4.sh': 2, 'UF2.sh': 2, 'U되돌림.sh': 2, 'U뒤관측.sh': 2}
FEOLD = ('FEIMGOLD=미정   # 실행 중 frontend 이미지 ID — 추정하지 않는다. U0 직전 읽기 전용 확인(docker inspect frontend)값을 총괄 승인 판에 기입. '
         '미정이면 U0 가 멈춘다')
FENEW = ('FEIMGOLD=%s   # 실행 중 frontend 이미지 ID — 운영 읽기 %s(N03 11-13 10:00 회차 읽기, 컨테이너 %s, '
         'docs/evidence/n03-1113-1000/frontend-image.txt). U0 가 실행 중 frontend 와 다시 대조하고 다르면 멈춘다' % (FE, FET, FEC[:12]))
out = {}
for name, s in blocks.items():
    if name == 'U변수.sh':
        lines = s.split(NL)
        assert lines[0].startswith('M=~/maint-v160; mkdir -p "$M"; OPS=~/ops-scripts; UB=V160D4   # v1.160 통합 후보(integration/v158 88f0634)'), lines[0][:120]
        lines[0] = ('M=~/maint-v161; mkdir -p "$M"; OPS=~/ops-scripts; UB=V161D4   # v1.161 통합 후보(integration/v158 88f0634) 배포 D4(미승인·실행 금지) 판 표지 — '
                    'U 블록 전부가 확인한다. 원본 구조 V160D4(v160-20261113/d4, 그 원본 V159D4·SMA20PD4 sma20p-d4-af7ffa58), 생성기 mkdraft7.py(값만 변경). '
                    'backend+frontend 같은 릴리스, DDL V96(새 backend 기동 때 Flyway)')
        s = NL.join(lines)
        s = rep(s, FEOLD, FENEW)
        s = rep(s, 'NEW=' + NEW, 'NEW=' + NEW)   # 그대로인지 확인
        s = rep(s, 'GS=' + GS + '   #', 'GS=' + GS + '   #')
        for bad in ('UB=V160D4', 'maint-v160', 'v1.160 통합 후보', 'FEIMGOLD=미정'):
            if bad in s:
                raise SystemExit('원본 표기가 남음: %s %s' % (name, bad))
    else:
        s = rep(s, 'V160D4', 'V161D4', UBN[name])
        s = rep(s, 'v1.160 통합 후보', 'v1.161 통합 후보', PANN[name])
        if name == 'U0.sh':
            s = rep(s, '"$M" = "$HOME/maint-v160"', '"$M" = "$HOME/maint-v161"')
            s = rep(s, '[ "$NEW" = ' + NEW + ' ]', '[ "$NEW" = ' + NEW + ' ]')
        for bad in ('V160D4', 'maint-v160', 'v1.160 통합 후보'):
            if bad in s:
                raise SystemExit('원본 표기가 남음: %s %s' % (name, bad))
    out[name] = s

DST.mkdir(parents=True)
for name, s in out.items():
    (DST / name).write_bytes(s.encode('utf-8'))
print('생성', DST, len(out), '블록 · FEIMGOLD', FE, '· 컨테이너', FEC[:12], '· NEW', NEW, '· GS', GS)
