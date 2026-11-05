# #25(342f461 §92 UB=92) U 블록 → N20 다음 배포 초안(미승인, UB=N20D1). 바꾸는 곳은 모두 지정한 횟수만큼 정확히 맞아야 한다.
import sys, pathlib
SRC = pathlib.Path('D:/tmp/k25run/blocks'); DST = pathlib.Path('D:/tmp/n20c/blocks-draft')
NEWC = '1899b49f633f8aefdc66b15c42debdaba8b3852c'; EE = 'ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8'
E24 = 'sha256:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6'


def rep(s, a, b, n=1):
    c = s.count(a)
    if c != n:
        sys.exit('맞는 수 %d ≠ %d: %r' % (c, n, a[:90]))
    return s.replace(a, b)


UBOLD = '[ "${UB:-}" = 92 ] || stop "U 변수가 v1.124(§5-9 92 블록) 판이 아님 — F → G → §5-9 92 U 변수"'
UBNEW = '[ "${UB:-}" = N20D1 ] || stop "U 변수가 N20 초안 D1 판이 아님 — F → G → N20 초안 U 변수"'
N20OBS = open('D:/tmp/n20c/n20obs.sh', encoding='utf-8').read()
out = {}

s = (SRC / 'U변수.sh').read_text(encoding='utf-8')
L = s.splitlines(keepends=True)
s = rep(s, L[0], 'M=~/maint-n20; mkdir -p "$M"; OPS=~/ops-scripts; UB=N20D1   # N20 다음 배포 초안 D1(미승인·실행 금지) 판 표지 — U 블록 전부가 확인한다. 후보 1899b49(출처 candidate/n20-held-mark-ee509cf). #25 기록 폴더 ~/maint-n19·#24 ~/maint-n17·#23 ~/maint-n15n16 은 읽기만 하고 덮어쓰지 않는다\n')
s = rep(s, 'OLD=7a944723c2959898226c2ad80f6b079ab5678296; NEW=' + EE, 'OLD=' + EE + '; NEW=' + NEWC)
s = rep(s, L[2], 'IMGOLD=미정   # #25 배포 이미지(~/maint-n19/image-new.txt) — #25 결과 수용 뒤 실제 값으로 채운다. sha256: 로 시작하지 않으면 U0 가 멈춘다\n'
        'IMGPREV6=' + E24 + '   # #24 이미지 — 이전 복구 태그 autotrader-backend:7a94472 가 가리킨다(#25 U1 이 만듦, 보존, 바꾸지 않는다)\n')
s = rep(s, "web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java \\\n  web-api/src/test/java/com/cryptoautotrader/api/service/N19RestFallbackItemDefenseTest.java)\"",
        "web-api/src/main/java/com/cryptoautotrader/api/service/HeldMarkRefresher.java \\\n  web-api/src/test/java/com/cryptoautotrader/api/service/N20HeldMarkItemIsolationTest.java)\"")
i = s.index('RARX="$(printf'); j = s.index('\n', s.index("LiveTradingService.class')\"", i))
s = s[:i] + ("RARX=\"$(printf '%s\\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher$Book.class' \\\n"
             "  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher.class')\"   # 실제 로컬 bootJar 대조(N20 초안) — ee509cf·1899b49 jar 에서 새로 셈($Book 은 줄 번호만 다름)") + s[j:]
s = s.rstrip('\n') + '\n' + N20OBS
out['U변수.sh'] = s

s = (SRC / 'U0.sh').read_text(encoding='utf-8')
s = rep(s, '[ "$M" = "$HOME/maint-n19" ] && [ "${UB:-}" = 92 ] && [ "$NEW" = ' + EE + ' ]',
        '[ "$M" = "$HOME/maint-n20" ] && [ "${UB:-}" = N20D1 ] && [ "$NEW" = ' + NEWC + ' ]')
s = rep(s, '&& type n19obs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 92 U 변수"',
        '&& type n19obs > /dev/null 2>&1 && type n20obs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → N20 초안 U 변수"')
s = rep(s, '[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 시작했다',
        'case "$IMGOLD" in sha256:*) ;; *) stop "IMGOLD 미정 — #25 결과 수용 뒤 실제 #25 이미지로 채운 판이 아님(초안 D1 은 실행하지 않는다)" ;; esac\n'
        '[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 시작했다')
s = rep(s, '"HEAD 가 7a94472 가 아니거나', '"HEAD 가 ee509cf 가 아니거나')
s = rep(s, '"후보 ee509cf 없음"', '"후보 1899b49 없음"')
s = rep(s, '"7a94472 가 후보의 조상이 아님"', '"ee509cf 가 후보의 조상이 아님"')
s = rep(s, '"diff 가 정확히 N19 2개 파일이 아님', '"diff 가 정확히 N20 2개 파일이 아님')
s = rep(s, '[ "$IMGOLD" = "$(cat ~/maint-n17/image-new.txt)" ] || stop "실행 이미지가 #24 배포 이미지(e51b512a…)와 다름"',
        '[ "$IMGOLD" = "$(cat ~/maint-n19/image-new.txt)" ] || stop "실행 이미지가 #25 배포 이미지(~/maint-n19/image-new.txt)와 다름"')
s = rep(s, "[ \"$(docker image inspect --format '{{.Id}}' $REPO:latest)\" = \"$IMG0\" ]",
        "[ \"$(docker image inspect --format '{{.Id}}' $REPO:7a94472)\" = \"$IMGPREV6\" ] || stop \"이전 복구 태그 $REPO:7a94472 이 #24 이미지(e51b512a…)가 아님 — 보고\"\n"
        "[ \"$(docker image inspect --format '{{.Id}}' $REPO:latest)\" = \"$IMG0\" ]")
s = rep(s, '— 7a94472 실행 중 로그, 기록만)', '— ee509cf 실행 중 로그, 기록만)')
s = rep(s, 'N17 줄(7a94472 실행 중 로그, 기록만)', 'N17 줄(ee509cf 실행 중 로그, 기록만)')
s = rep(s, 'N19 줄(7a94472 에는 이 줄이 없으므로 미관측이 기대, 기록만)"; cat "$M/n19-before.txt"\n',
        'N19 줄(ee509cf 실행 중 로그, 자연 발생만, 기록만)"; cat "$M/n19-before.txt"\n'
        'n20obs "$M/backend-before.log" > "$M/n20-before.txt" || stop "n20obs 인자 오류 — n20-before.txt 를 보고"\n'
        'echo "== 배포 전 기준선: N20 줄(ee509cf 에는 이 문구가 없으므로 미관측이 기대, 기록만)"; cat "$M/n20-before.txt"\n')
out['U0.sh'] = s

s = (SRC / 'U1.sh').read_text(encoding='utf-8')
s = rep(s, '$REPO:7a94472', '$REPO:ee509cf', 5)
s = rep(s, '"jar 차이가 N19 3줄이 아님', '"jar 차이가 N20 2줄이 아님')
out['U1.sh'] = s

out['U2.sh'] = (SRC / 'U2.sh').read_text(encoding='utf-8')

s = (SRC / 'U3.sh').read_text(encoding='utf-8')
a = 'n19obs "$M/app-$T.log" | tee "$M/n19obs-$T.txt" || stop "n19obs 인자 오류 — 위 출력을 보고"\n'
s = rep(s, a, a + 'n20obs "$M/app-$T.log" | tee "$M/n20obs-$T.txt" || stop "n20obs 인자 오류 — 위 출력을 보고"\n')
s = rep(s, '— 7a94472 복귀 확인.', '— ee509cf 복귀 확인.')
out['U3.sh'] = s

s = (SRC / 'U4.sh').read_text(encoding='utf-8')
a = 'n19obs "$M/app-after30.log" | tee "$M/n19obs-after30.txt" || stop "n19obs 인자 오류 — 위 출력을 보고"\n'
s = rep(s, a, a + 'echo "== N20 줄(기동 뒤 30분 로그 · 자연 발생만)"\n'
        'n20obs "$M/app-after30.log" | tee "$M/n20obs-after30.txt" || stop "n20obs 인자 오류 — 위 출력을 보고"\n')
a = '(WS 를 끊거나 이상 응답을 주입하지 않는다)" || true\n'
s = rep(s, a, a + 'grep -q "^N20: 미관측" "$M/n20obs-after30.txt" && echo "⚪ N20 보유 시세 보강 항목 건너뜀·처리 실패 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(이상 응답·예외를 주입하지 않는다)" || true\n')
out['U4.sh'] = s

s = (SRC / 'U되돌림.sh').read_text(encoding='utf-8')
s = rep(s, '"image-before 가 #24 이미지(e51b512a…)가 아님"', '"image-before 가 #25 이미지(IMGOLD)가 아님"')
s = rep(s, '"✅ 7a94472 (e51b512a…) 로 복귀', '"✅ ee509cf (#25 이미지) 로 복귀')
s = rep(s, 'N15/N16·N17·N19 줄은 기록만)', 'N15/N16·N17·N19·N20 줄은 기록만)')
out['U되돌림.sh'] = s

s = (SRC / 'U뒤관측.sh').read_text(encoding='utf-8')
a = 'n19obs "$M/app-news-$TS.log" | tee "$M/n19obs-$TS.txt" || echo "⚪ n19obs 인자 오류 — 위 출력을 보고"\n'
s = rep(s, a, a + 'echo "== 기동 뒤 N20 줄(자연 발생만)"\n'
        'n20obs "$M/app-news-$TS.log" | tee "$M/n20obs-$TS.txt" || echo "⚪ n20obs 인자 오류 — 위 출력을 보고"\n')
out['U뒤관측.sh'] = s

for n in ['U1.sh', 'U2.sh', 'U3.sh', 'U4.sh', 'U되돌림.sh', 'U뒤관측.sh']:
    out[n] = rep(out[n], UBOLD, UBNEW)
DST.mkdir(exist_ok=True)
for n, s in out.items():
    (DST / n).write_text(s, encoding='utf-8', newline='\n')
print('ok', sorted(out))
