# N20 D2(로컬 수용, v1.129) → #26 최종안(§5-9 98 블록, UB=98). 총괄 v1.129 §97 지시:
# IMGOLD 를 실제 #25 이미지 전체 ID 로 채우고, 판 표지·초안 안내 문구만 바꾼다. 그 밖의 로직은 D2 그대로.
# 바꾸는 곳은 모두 지정한 횟수만큼 정확히 맞아야 한다(아니면 멈춘다).
import sys, pathlib
SRC = pathlib.Path('D:/tmp/n20c/blocks-d2'); DST = pathlib.Path('D:/tmp/n20c/blocks-k26')
IMG25 = 'sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6'


def rep(s, a, b, n=1):
    c = s.count(a)
    if c != n:
        sys.exit('맞는 수 %d ≠ %d: %r' % (c, n, a[:90]))
    return s.replace(a, b)


UBOLD = '[ "${UB:-}" = N20D2 ] || stop "U 변수가 N20 초안 D2 판이 아님 — F → G → N20 초안 U 변수"'
UBNEW = '[ "${UB:-}" = 98 ] || stop "U 변수가 v1.130(§5-9 98 블록) #26 최종안 판이 아님 — F → G → §5-9 98 U 변수"'
out = {}

s = (SRC / 'U변수.sh').read_text(encoding='utf-8')
s = rep(s, 'UB=N20D2   # N20 다음 배포 초안 D2(미승인·실행 금지) 판 표지 — U 블록 전부가 확인한다.',
        'UB=98   # v1.130(§5-9 98 블록) #26 최종안 판 표지 — U 블록 전부가 확인한다. 총괄 최종 출처 검토·실행 판정 전에는 실행하지 않는다.')
s = rep(s, 'IMGOLD=미정   # #25 배포 이미지(~/maint-n19/image-new.txt) — #25 결과 수용 뒤 실제 값으로 채운다. sha256: 로 시작하지 않으면 U0 가 멈춘다',
        'IMGOLD=' + IMG25 + '   # #25 배포 이미지(~/maint-n19/image-new.txt, v1.129 수용 값) — U0 가 실행 이미지·기록 파일과 같은지 확인한다. sha256: 로 시작하지 않으면 U0 가 멈춘다')
s = rep(s, '# 실제 로컬 bootJar 대조(N20 초안) —', '# 실제 로컬 bootJar 대조(N20 후보 1899b49) —')
s = rep(s, '# N20 줄 관측(초안 D2 — 1899b49 고정 문구).', '# N20 줄 관측(§5-9 98 — 1899b49 고정 문구).')
out['U변수.sh'] = s

s = (SRC / 'U0.sh').read_text(encoding='utf-8')
s = rep(s, '[ "${UB:-}" = N20D2 ] && [ "$NEW" =', '[ "${UB:-}" = 98 ] && [ "$NEW" =')
s = rep(s, 'stop "변수 순서 — F 변수 → G 변수 → N20 초안 U 변수"', 'stop "변수 순서 — F 변수 → G 변수 → §5-9 98 U 변수"')
s = rep(s, 'stop "IMGOLD 미정 — #25 결과 수용 뒤 실제 #25 이미지로 채운 판이 아님(초안 D1·D2 는 실행하지 않는다)"',
        'stop "IMGOLD 가 sha256: 이미지 ID 가 아님 — §5-9 98 U 변수 원문이 아님(초안 D1·D2 는 실행하지 않는다)"')
out['U0.sh'] = s

for n in ('U1', 'U2', 'U3', 'U4', 'U뒤관측', 'U되돌림'):
    out[n + '.sh'] = rep((SRC / (n + '.sh')).read_text(encoding='utf-8'), UBOLD, UBNEW)

DST.mkdir(exist_ok=False)
for k, v in out.items():
    if 'N20D2' in v or '초안 D2' in v.replace('초안 D1·D2', ''):
        sys.exit('남은 D2 표기: ' + k)
    (DST / k).write_bytes(v.encode('utf-8'))
print('완료', len(out), '블록 →', DST)
