#!/usr/bin/env bash
# R 변수 블록 로컬 확인(v1.113, RV=81 · 후보 1103f48) — 서버 명령 아님. k23-prep-51dcee5/local-check/check_r.sh 에서 기대값만 바꾸고 판 표지·IMGPREV3·src/main 동일 확인을 더했다. HOME 을 임시 폴더로 바꿔 ~/maint-n15n16 이 이 PC 의 실제 HOME 에 생기지 않게 한다.
# 인자: 통합 초안 작업 사본(git), jar 비교 출력 파일
set -u
D="$(cd "$(dirname "$0")" && pwd)"; REPO_I="$1"; JARDIFF="$2"
export HOME="$D/fakehome"; rm -rf "$HOME"; mkdir -p "$HOME"
python3() { python -I -X utf8 "$@"; }
# shellcheck disable=SC1091
source "$D/blocks/R변수.sh"
pass=0; fail=0
ok() { if [ "$1" = 0 ]; then echo "PASS $2"; pass=$((pass+1)); else echo "FAIL $2"; fail=$((fail+1)); fi; }
[ "$M" = "$HOME/maint-n15n16" ] && [ "$RV" = 81 ]; ok $? "판 표지 RV=81·M=~/maint-n15n16"
[ "$OLD" = fe01207f3f60e97e308925157f84a1bb2da04388 ] && [ "$NEW" = 1103f48505a1457029f6555547a87aa986535738 ]; ok $? "OLD fe01207·NEW 1103f48"
[ "$IMGOLD" = sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7 ]; ok $? "IMGOLD = #22 이미지 9cb5d84f…"
[ "$IMGPREV" = sha256:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9 ] && [ "$IMGPREV2" = sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6 ] && [ "$IMGPREV3" = sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07 ]; ok $? "이전 복구 태그 세 이미지(ea44900·b1d7664·6995c52)"
[ "$DIFFN" = "$(git -C "$REPO_I" diff --name-only "$OLD" "$NEW")" ]; [ "$(printf '%s
' "$DIFFN" | wc -l)" = 12 ]; ok $? "DIFFN = git diff --name-only fe01207 1103f48(12개)"
[ -z "$(git -C "$REPO_I" diff --name-only "$OLD" "$NEW" | grep -vE '\.java$')" ]; ok $? "변경 경로가 모두 .java(마이그레이션·설정·의존성 없음)"
[ "$(grep -v '^(끝)' "$JARDIFF")" = "$RARX" ]; [ "$(printf '%s
' "$RARX" | wc -l)" = 29 ]; ok $? "RARX = 실제 jar 비교 출력 fe01207→1103f48(끝 줄 제외, 29줄)"
grep -qx '(끝) 879 881' "$JARDIFF"; ok $? "jar 비교 끝 줄 (끝) 879 881"
[ "$LS" = 8bfe8a9fa896f400b3fb6681fef021c99d6ba43b ] && [ "$CK" = c669ccdc6e248ce8e55e9a7ac7405197d266fa18 ] && [ "$CKSH" = ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc ] && [ "$CRON" = f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b ]; ok $? "장부·점검(c669ccd)·cron 기준값 그대로"
[ -z "$(git -C "$REPO_I" diff --name-only 51dcee530721c0ab23f3f6f875a15b9416b751a3 "$NEW" -- web-api/src/main)" ] && [ "$(git -C "$REPO_I" diff --name-only 51dcee530721c0ab23f3f6f875a15b9416b751a3 "$NEW")" = web-api/src/test/java/com/cryptoautotrader/api/service/DailySma20LiveOrderPathTest.java ]; ok $? "51dcee5→1103f48 은 시험 파일 1개뿐(운영 src/main 0개)"
grep -q '(#22 P1 이 만듦' "$D/blocks/R변수.sh" && ! grep -q '#22 R1' "$D/blocks/R변수.sh"; ok $? "IMGPREV3 주석 실제 단계명 #22 P1"
# 1차 실행(check_r-run1-checkdesign-fail.out)은 entryobs 함수 머리 주석 "(v1.111)"(함수 도입 판, 본문 무변경)까지 잡아 실패했다 — 확인 설계 오류. 그 한 줄만 뺀다.
! grep -h '51dcee5\|RV:-}" = 79\|v1.111' "$D"/blocks/*.sh | grep -vx '# N15·N16 줄 관측(v1.111). 인자: 앱 로그. 출력은 로그 머리 시각·엔진 종류·건수만(코인·세션 id·가격·사유 본문은 내지 않는다).' | grep -q .; ok $? "블록에 옛 §79 후보·판 표지 없음(entryobs 도입 판 주석 1줄 제외)"
type holdobs >/dev/null 2>&1 && type httpobs >/dev/null 2>&1 && type newsobs >/dev/null 2>&1 && type entryobs >/dev/null 2>&1; ok $? "관측 함수 넷 정의"

L="$D/entryobs-cases"; P='backend-1  | '
pre() { sed "s/^/$P/" "$@"; }
printf '%s\n' "${P}07:40:00.000 [main] INFO  c.c.a.CryptoAutoTraderApplication - Started CryptoAutoTraderApplication" \
  "${P}07:45:00.000 [scheduling-1] INFO  c.c.api.service.EntryPriorityRounds - [진입 우선순위] 1위 KRW-BTC LIVE#7 → 진입" > "$L/none.log"
{ cat "$L/none.log"; pre "$L/src-cb-live.txt" "$L/src-cb-dyn.txt" "$L/src-rj-live.txt" "$L/src-rj-dyn.txt" "$L/src-intent-dyn.txt"; } > "$L/n15.log"
{ cat "$L/none.log"; pre "$L/src-bad-live.txt" "$L/src-bad-dyn.txt"; } > "$L/n16.log"
{ cat "$L/n15.log"; echo "${P}14:30:00.000 [scheduling-1] ERROR c.c.api.service.EntryPriorityRounds - [진입 우선순위] 만료 사유 기록 실패: RoundKey[timeframe=H1] LIVE#9 KRW-XRP"; } > "$L/fail.log"
# 원문 줄의 CRLF·연속 줄(스택) 섞임
{ cat "$L/n16.log" | sed 's/$/\r/'; echo "${P}	at com.cryptoautotrader.api.service.LiveTradingService.onPrice(LiveTradingService.java:1)"; } > "$L/crlf.log"

entryobs "$L/none.log" > "$L/none.out" 2>&1; rc=$?
[ $rc = 0 ] && grep -q '^N15: 미관측' "$L/none.out" && grep -q '^N16: 미관측' "$L/none.out" && grep -q '콜백 전 LIVE 0·DYNAMIC 0' "$L/none.out"; ok $? "entryobs 없음 — 종료 0·N15/N16 미관측(→ 진입 줄은 세지 않음)"
entryobs "$L/n15.log" > "$L/n15.out" 2>&1; rc=$?
[ $rc = 0 ] && grep -q '콜백 전 LIVE 1·DYNAMIC 1 · 예약·의도 경계 거절(ENTRY_EXPIRED) LIVE 1·DYNAMIC 1(그중 의도 직전 LIVE 0·DYNAMIC 1) · 만료 사유 기록 실패 0' "$L/n15.out" && ! grep -q '^N15: 미관측' "$L/n15.out" && grep -q '^N16: 미관측' "$L/n15.out"; ok $? "entryobs N15 실제 줄 5개 — 범주·엔진별 건수"
entryobs "$L/n16.log" > "$L/n16.out" 2>&1; rc=$?
[ $rc = 0 ] && grep -q 'N16 가격이 아닌 실시간 시세 무시 — LIVE 1·DYNAMIC 1' "$L/n16.out" && grep -q '^N15: 미관측' "$L/n16.out"; ok $? "entryobs N16 실제 줄 2개 — LIVE·DYNAMIC 구분"
entryobs "$L/fail.log" > "$L/fail.out" 2>&1; rc=$?
[ $rc = 1 ] && grep -q '만료 사유 기록 실패 1' "$L/fail.out" && grep -q '🔴 만료 사유 기록 실패 14:30:00.000' "$L/fail.out"; ok $? "entryobs 만료 사유 기록 실패 — 종료 1"
entryobs "$L/crlf.log" > "$L/crlf.out" 2>&1; rc=$?
[ $rc = 0 ] && grep -q 'LIVE 1·DYNAMIC 1' "$L/crlf.out"; ok $? "entryobs CRLF·머리 없는 연속 줄 — 같은 건수"
entryobs > "$L/noarg.out" 2>&1; rc=$?; [ $rc = 3 ] && grep -q '인자는 1개' "$L/noarg.out"; ok $? "entryobs 인자 없음 — ⚪ 종료 3"
! grep -h 'KRW-\|#[0-9]\|sessionId=\|만료 2026\|관측 2026\|WS_TRADE' "$L"/*.out > /dev/null; ok $? "출력에 코인·세션 id·가격·사유 본문 없음"
echo "== $pass 통과 / $fail 실패"
[ $fail = 0 ]
