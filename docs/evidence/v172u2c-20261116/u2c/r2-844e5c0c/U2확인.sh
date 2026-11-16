(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
# V172D4 U2확인(총괄 v1.174 §2-3~5) — 11-16 14:16 U2 rc1(V96 이력 기대 문자열 96:t: ≠ 실제 연결 결과 96:true:) 뒤, 재기동 없이 U2 의 남은 확인을 마친다.
# 재빌드·재기동·tag·checkout·DDL·DB 쓰기 없음. 원래 U2 의 배포 기록(t-deploy.*)·실패 출력은 바꾸지 않고, 새 기록은 -u2c-<시각> 이름으로 남긴다.
# 모든 확인 뒤에만 U2 의 미실행 꼬리 이름 svc-deploy.txt·git-deploy.txt(U4 원문이 읽는다, 지금 없음을 먼저 확인)를 같은 바이트로 쓰고, 마지막에 ok-U2 를 쓴다.
[ "$M" = "$HOME/maint-v172" ] && [ "${UB:-}" = V172D4 ] && [ "$NEW" = 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d ] || stop "U 변수가 V172D4 판이 아니거나 기록 폴더·NEW 가 다름 — F → G → V172D4 U 변수(ce6df2fa)"
[ -e "$M/ok-U1" ] || stop "U1 미통과"
[ ! -e "$M/ok-U2" ] || stop "U2 를 이미 통과했다 — U2확인 대상 아님(U3)"
[ -s "$M/t-deploy.utc" ] && [ -s "$M/t-deploy.txt" ] || stop "U2 배포 시각 기록 없음 — U2 재기동 전 상태, U2확인 대상 아님. 보고"
[ ! -e "$M/svc-deploy.txt" ] && [ ! -e "$M/git-deploy.txt" ] || stop "원래 U2 의 꼬리 기록(svc-deploy·git-deploy)이 있다 — 11-16 14:16 rc1 상태와 다름. 보고"
for f in t-rollback.utc t-rollback.txt rb-probe.txt rb-identity.txt gate-out.txt fe-stage-u1rollback.txt u1rb-probe.txt git-u1rollback.txt; do [ ! -e "$M/$f" ] || stop "복구를 시작한 기록 $f 이 있다 — 확인하지 않고 보고"; done
for f in ok-UF2 t-fe.utc t-fe.txt svc-pre-fe.txt; do [ ! -e "$M/$f" ] || stop "frontend 교체를 시작한 기록 $f 이 있다 — 확인하지 않고 보고"; done
[ "$(cat "$M/fe-stage.txt" 2>/dev/null)" = 빌드끝 ] && [ -s "$M/image-fe-new-chain.txt" ] && [ -s "$M/image-new-chain.txt" ] || stop "U1 기록(frontend 단계 빌드끝·새 이미지 대응)이 U1 통과 상태가 아님 — 보고"
S=$(cat "$M/t-deploy.utc"); TS=$(date '+%Y%m%d-%H%M%S')
echo "== U2확인 $TS — 원래 U2 배포 기록 $(cat "$M/t-deploy.txt") (UTC $S) 그대로. P0 먼저: 배포 시각 뒤 backend 로그 보존 → 연결 획득 시간 초과 검사"
$DC logs --no-color --since "$S" backend > "$M/app-u2c-$TS.log" || stop "🔴 P0 검사 미완료 — 기동 구간 로그를 확보하지 못함(로그 조회 실패). 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"
[ -s "$M/app-u2c-$TS.log" ] || stop "🔴 P0 검사 미완료 — 기동 구간 로그가 비었다. 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"
holdobs "$M/app-u2c-$TS.log" > "$M/holdobs-u2c-$TS.txt" && HP=0 || HP=$?
[ "$HP" != 1 ] || { cat "$M/holdobs-u2c-$TS.txt"; stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)"; }
[ "$HP" = 0 ] || [ "$HP" = 3 ] || { cat "$M/holdobs-u2c-$TS.txt"; stop "🔴 P0 검사 미완료 — holdobs 종료 $HP. 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"; }
cat "$M/holdobs-u2c-$TS.txt"; [ "$HP" = 0 ] || echo "⚪ 장기 대여 경고(holdobs 3) — 멈추지 않는다. U3 에서 다시 본다(기록만)"
grep -E "Migrating schema|Successfully applied|Started CryptoAutoTrader" "$M/app-u2c-$TS.log" | cut -c1-200 || true
[ "$(git rev-parse HEAD)" = "$NEW" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "HEAD 가 NEW 가 아니거나 추적 파일 변경 있음 — 보고"
same3 $REPO:latest "$M/image-new-chain.txt" || stop "backend latest 가 U1 새 이미지 대상·manifest·config 와 다름 또는 조회 실패 — 보고"
isimg backend "$(okimg "$M/image-new-chain.txt")" $(ok3 "$M/image-new-chain.txt") || stop "실행 backend 가 U1 새 이미지 대응이 아님 또는 조회 실패 — 보고"
BI=$(docker inspect --format '{{.State.StartedAt}} {{.RestartCount}} {{.State.Status}}' "$($DC ps -q backend)") || stop "backend 기동 상태 조회 실패 — 보고"
read -r BSA BRC BSTAT <<< "$BI"
[ "$BRC" = 0 ] && [ "$BSTAT" = running ] || stop "backend 재시작 $BRC · 상태 $BSTAT — 보고"
BD=$(( $(date -d "$BSA" +%s) - $(date -d "$S" +%s) ))
[ "$BD" -ge 0 ] && [ "$BD" -le 120 ] || stop "backend 기동 시각 $BSA 이 U2 배포 기록 $S 뒤 2분 안이 아님(차 ${BD}초) — 그 뒤 다시 기동됐을 수 있다. 보고"
echo "backend 기동 $BSA · 재시작 $BRC · $BSTAT · 배포 기록 뒤 ${BD}초" | tee "$M/backend-start-u2c-$TS.txt"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 frontend 가 U0 구판 대응이 아님 또는 조회 실패 — 보고"
same3 autotrader-frontend "$M/image-fe-new-chain.txt" || stop "frontend 이미지 이름이 U1 새 frontend 대응과 다름 또는 조회 실패 — 보고"
same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 보고"
same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 보고"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 다른 것 있음 — 보고"
[ "$(sc)" = "66 | 63 | 3 | -" ] || stop "검증 66세션 상태 다름 — 보고"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "공유 풀 등록값 다름 — 보고"
v96 || stop "Flyway 최고 버전이 96 이 아님 또는 조회 실패 — 보고"
V96H=$($PSQL -At -c "SELECT string_agg(version || ':' || success || ':' || checksum, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::int >= 95") || stop "V95 이후 이력 조회 실패 — 보고"
echo "V95 이후 이력: ${V96H:-(0행)}" | tee "$M/v96-u2c-$TS.txt"
[ "$V96H" = "96:true:$V96SUM" ] || stop "V95 이후 이력이 V96 한 줄(성공 true·체크섬 $V96SUM)이 아님 — 보고"
svc | tee "$M/svc-u2c-$TS.txt"
[ "$(grep -c . "$M/svc-u2c-$TS.txt")" = 5 ] || stop "서비스 5개 상태 기록이 아님 — 보고"
[ "$(grep '^backend ' "$M/svc-u2c-$TS.txt" | cut -d' ' -f3)" = "$BSA" ] || stop "backend 기동 시각이 확인 중에 바뀌었다 — 보고"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-u2c-$TS.txt") || stop "backend 외 서비스가 바뀜 — 보고"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-u2c-$TS.txt"
[ "$(cat "$M/git-u2c-$TS.txt")" = "$NEW"$'\n(끝)' ] || stop "git 기록이 NEW·추적 변경 없음이 아님 — 보고"
[ ! -e "$M/ok-U2" ] && [ ! -e "$M/svc-deploy.txt" ] && [ ! -e "$M/git-deploy.txt" ] || stop "확인 중에 ok-U2·svc-deploy·git-deploy 가 생겼다 — 보고"
echo "== U2 미실행 꼬리 완성 — 뒤 블록(U4 의 backend 재시작 대조)이 읽는 원래 이름 svc-deploy.txt·git-deploy.txt 를 위 확인 기록과 같은 바이트로 한 번 쓴다"
cp "$M/svc-u2c-$TS.txt" "$M/svc-deploy.txt"; cp "$M/git-u2c-$TS.txt" "$M/git-deploy.txt"
cmp -s "$M/svc-u2c-$TS.txt" "$M/svc-deploy.txt" && cmp -s "$M/git-u2c-$TS.txt" "$M/git-deploy.txt" || stop "svc-deploy·git-deploy 쓰기 확인 실패 — ok-U2 쓰지 않음. 보고"
printf 'V172D4 U2확인 %s · 원래 U2 rc1(11-16 14:16:35) · 배포 기록 %s · backend 기동 %s\n' "$TS" "$(cat "$M/t-deploy.txt")" "$BSA" > "$M/ok-U2"; echo "✅ U2확인 통과 — ok-U2 기록. 바로 U3(기동 5~10분 창을 넘겼으면 지연 편차로 보고, 새 모의 세션을 만들지 않는다)"
)
