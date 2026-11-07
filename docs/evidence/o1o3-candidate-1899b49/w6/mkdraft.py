# #26 최종안(§5-9 98, UB=98, 커밋된 n20-k26-final-1899b49/blocks-k26) → O1·O3 다음 배포 초안 D1(UB=O13D1).
# 🔴 미승인·서버 실행 금지 초안. 기점·후보·이미지·기록 폴더·diff·jar 기대·보존 태그만 바꾼다. 그 밖의 로직은 #26 그대로.
# 바꾸는 곳은 모두 지정한 횟수만큼 정확히 맞아야 한다(아니면 멈춘다). 사용: python mkdraft.py <blocks-k26 폴더> <출력 폴더(없어야 함)>
import sys, pathlib
SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
B26 = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
NEW = '0d142aa213bb7049fa942e73aa4b652a35e2ed90'
IMG26 = 'sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1'
IMG25 = 'sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6'


def rep(s, a, b, n=1):
    c = s.count(a)
    if c != n:
        sys.exit('맞는 수 %d ≠ %d: %r' % (c, n, a[:90]))
    return s.replace(a, b)


UBOLD = '[ "${UB:-}" = 98 ] || stop "U 변수가 v1.130(§5-9 98 블록) #26 최종안 판이 아님 — F → G → §5-9 98 U 변수"'
UBNEW = '[ "${UB:-}" = O13D1 ] || stop "U 변수가 O1·O3 초안 D1 판이 아님 — F → G → O1·O3 초안 U 변수(초안은 실행하지 않는다)"'
out = {}

s = (SRC / 'U변수.sh').read_text(encoding='utf-8')
s = rep(s, 'M=~/maint-n20; mkdir -p "$M"; OPS=~/ops-scripts; UB=98   # v1.130(§5-9 98 블록) #26 최종안 판 표지 — U 블록 전부가 확인한다. 총괄 최종 출처 검토·실행 판정 전에는 실행하지 않는다. 후보 1899b49(출처 candidate/n20-held-mark-ee509cf). #25 기록 폴더 ~/maint-n19·#24 ~/maint-n17·#23 ~/maint-n15n16 은 읽기만 하고 덮어쓰지 않는다',
        'M=~/maint-o13; mkdir -p "$M"; OPS=~/ops-scripts; UB=O13D1   # O1·O3 다음 배포 초안 D1(미승인·실행 금지) 판 표지 — U 블록 전부가 확인한다. 후보 0d142aa(출처 candidate/o1o3-log-1899b49). #26 기록 폴더 ~/maint-n20·#25 ~/maint-n19·#24 ~/maint-n17·#23 ~/maint-n15n16 은 읽기만 하고 덮어쓰지 않는다')
s = rep(s, 'OLD=ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8; NEW=' + B26, 'OLD=' + B26 + '; NEW=' + NEW)
s = rep(s, 'IMGOLD=' + IMG25 + '   # #25 배포 이미지(~/maint-n19/image-new.txt, v1.129 수용 값) — U0 가 실행 이미지·기록 파일과 같은지 확인한다. sha256: 로 시작하지 않으면 U0 가 멈춘다',
        'IMGOLD=' + IMG26 + '   # #26 배포 이미지(~/maint-n20/image-new.txt, v1.132 수용 값) — U0 가 실행 이미지·기록 파일과 같은지 확인한다. sha256: 로 시작하지 않으면 U0 가 멈춘다\n'
        'IMGPREV7=' + IMG25 + '   # #25 이미지 — 이전 복구 태그 autotrader-backend:ee509cf 가 가리킨다(#26 U1 이 만듦, 보존, 바꾸지 않는다)')
s = rep(s, r'''DIFFN="$(printf '%s\n' web-api/src/main/java/com/cryptoautotrader/api/service/HeldMarkRefresher.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/N20HeldMarkItemIsolationTest.java)"''',
        r'''DIFFN="$(printf '%s\n' web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/N15PgLockWaitExpiryTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/O1O3LogContractTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PoolLossLimitTest.java)"''')
s = rep(s, r'''RARX="$(printf '%s\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher$Book.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher.class')"   # 실제 로컬 bootJar 대조(N20 후보 1899b49) — ee509cf·1899b49 jar 에서 새로 셈($Book 은 줄 번호만 다름)''',
        r'''RARX="$(printf '%s\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$PriceSnapshot.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService.class')"   # 실제 로컬 bootJar 대조(O1·O3 후보 0d142aa) — 1899b49·0d142aa jar 에서 새로 셈($PriceSnapshot 은 줄 번호만 다름)''')
out['U변수.sh'] = s

s = (SRC / 'U0.sh').read_text(encoding='utf-8')
s = rep(s, '[ "$M" = "$HOME/maint-n20" ] && [ "${UB:-}" = 98 ] && [ "$NEW" = ' + B26 + ' ]',
        '[ "$M" = "$HOME/maint-o13" ] && [ "${UB:-}" = O13D1 ] && [ "$NEW" = ' + NEW + ' ]')
s = rep(s, 'stop "변수 순서 — F 변수 → G 변수 → §5-9 98 U 변수"', 'stop "변수 순서 — F 변수 → G 변수 → O1·O3 초안 U 변수"')
s = rep(s, 'stop "HEAD 가 ee509cf 가 아니거나 추적 파일 변경 있음"', 'stop "HEAD 가 1899b49 가 아니거나 추적 파일 변경 있음"')
s = rep(s, 'stop "후보 1899b49 없음"', 'stop "후보 0d142aa 없음"')
s = rep(s, 'stop "ee509cf 가 후보의 조상이 아님"', 'stop "1899b49 가 후보의 조상이 아님"')
s = rep(s, 'stop "diff 가 정확히 N20 2개 파일이 아님(마이그레이션·설정 포함 금지)"', 'stop "diff 가 정확히 O1·O3 5개 파일이 아님(마이그레이션·설정 포함 금지)"')
s = rep(s, '[ "$IMGOLD" = "$(cat ~/maint-n19/image-new.txt)" ] || stop "실행 이미지가 #25 배포 이미지(~/maint-n19/image-new.txt)와 다름"',
        '[ "$IMGOLD" = "$(cat ~/maint-n20/image-new.txt)" ] || stop "실행 이미지가 #26 배포 이미지(~/maint-n20/image-new.txt)와 다름"')
L6 = '''[ "$(docker image inspect --format '{{.Id}}' $REPO:7a94472)" = "$IMGPREV6" ] || stop "이전 복구 태그 $REPO:7a94472 이 #24 이미지(e51b512a…)가 아님 — 보고"'''
s = rep(s, L6, L6 + '''\n[ "$(docker image inspect --format '{{.Id}}' $REPO:ee509cf)" = "$IMGPREV7" ] || stop "이전 복구 태그 $REPO:ee509cf 이 #25 이미지(200f2f57…)가 아님 — 보고"''')
s = rep(s, '(entryobs 종료 $ED — ee509cf 실행 중 로그, 기록만)', '(entryobs 종료 $ED — 1899b49 실행 중 로그, 기록만)')
s = rep(s, 'N17 줄(ee509cf 실행 중 로그, 기록만)', 'N17 줄(1899b49 실행 중 로그 — 옛 문구도 같은 정규식으로 센다, 기록만)')
s = rep(s, 'N19 줄(ee509cf 실행 중 로그, 자연 발생만, 기록만)', 'N19 줄(1899b49 실행 중 로그, 자연 발생만, 기록만)')
s = rep(s, 'N20 줄(ee509cf 에는 이 문구가 없으므로 미관측이 기대, 기록만)', 'N20 줄(1899b49 실행 중 로그, 자연 발생만, 기록만)')
out['U0.sh'] = s

s = rep((SRC / 'U1.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep(s, '$REPO:ee509cf', '$REPO:1899b49', 5)
s = rep(s, 'stop "jar 차이가 N20 2줄이 아님', 'stop "jar 차이가 O1·O3 3줄이 아님')
out['U1.sh'] = s

for n in ('U2', 'U4', 'U뒤관측'):
    out[n + '.sh'] = rep((SRC / (n + '.sh')).read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep((SRC / 'U3.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
out['U3.sh'] = rep(s, '✅ U3(rollback) 통과 — ee509cf 복귀 확인.', '✅ U3(rollback) 통과 — 1899b49 복귀 확인.')
s = rep((SRC / 'U되돌림.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep(s, 'stop "image-before 가 #25 이미지(IMGOLD)가 아님"', 'stop "image-before 가 #26 이미지(IMGOLD)가 아님"')
out['U되돌림.sh'] = rep(s, '✅ ee509cf (#25 이미지) 로 복귀', '✅ 1899b49 (#26 이미지) 로 복귀')

DST.mkdir(exist_ok=False)
for k, v in out.items():
    for bad in ('UB=98', '= 98 ]', 'maint-n19/image-new', 'HeldMarkRefresher.java'):
        if bad in v:
            sys.exit('남은 #26 표기 %r: %s' % (bad, k))
    (DST / k).write_bytes(v.encode('utf-8'))
print('완료', len(out), '블록 →', DST)
