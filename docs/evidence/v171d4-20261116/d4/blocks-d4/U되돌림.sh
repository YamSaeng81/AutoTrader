(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V171D4 ] || stop "U 변수가 v1.171 통합 후보 초안 D4 판이 아님 — F → G → v1.171 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #27 이미지(IMGOLD)가 아님"
[ -s "$M/image-before-chain.txt" ] && [ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] || stop "U0 backend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"
[ -s "$M/image-fe-before-chain.txt" ] && [ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] && [ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "U0 frontend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"
[ -e "$M/start_66.py.before" ] || stop "U0 보존본이 없다 — 보고"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-rollback.txt"
H=$(head -1 "$M/git-pre-rollback.txt")
[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 후보·b5cd1d0 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"
[ "$(sed -n 2p "$M/git-pre-rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"
echo "== V171D4 — 바꾸기 전 실행 컨테이너 대응 판정(backend·frontend)과 보존 태그 확인"
if isimg backend "$(okimg "$M/image-before-chain.txt")" $(ok3 "$M/image-before-chain.txt") 2>> "$M/rb-probe.txt"; then BR=구판
elif [ -s "$M/image-new-chain.txt" ] && isimg backend "$(okimg "$M/image-new-chain.txt")" $(ok3 "$M/image-new-chain.txt") 2>> "$M/rb-probe.txt"; then BR=새판
else cat "$M/rb-probe.txt" 2>/dev/null | tail -4; stop "실행 backend 가 image-before·image-new 대응 어느 것도 아니거나 조회 실패 — 다른 작업이 있었을 수 있다. 바꾸지 않고 보고"; fi
if isimg frontend "$FEIMGOLD $(okimg "$M/image-fe-before-chain.txt")" $(ok3 "$M/image-fe-before-chain.txt") 2>> "$M/rb-probe.txt"; then FR=구판
elif [ -s "$M/image-fe-new-chain.txt" ] && isimg frontend "$(okimg "$M/image-fe-new-chain.txt")" $(ok3 "$M/image-fe-new-chain.txt") 2>> "$M/rb-probe.txt"; then FR=새판
else cat "$M/rb-probe.txt" 2>/dev/null | tail -4; stop "실행 frontend 가 U0·U1 대응 어느 것도 아니거나 조회 실패(제3의 이미지일 수 있다) — 바꾸지 않고 보고"; fi
same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"
same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"
FN=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" 2>> "$M/rb-probe.txt" || FN=1
if [ "$FN" = 1 ]; then FL1=$(docker image inspect --format '{{.Id}}' autotrader-frontend) || stop "frontend 이미지 이름 조회 실패 — 바꾸지 않고 보고"; [ -s "$M/image-fe-new.txt" ] && [ "$FL1" = "$(cat "$M/image-fe-new.txt")" ] || stop "frontend 이미지 이름이 U0·U1 이미지 어느 것도 아님($FL1) — 바꾸지 않고 보고"; fi
echo "실행 판정 backend=$BR frontend=$FR · frontend 이미지 이름 $([ "$FN" = 1 ] && echo U1새판 || echo U0) · 단계 $(cat "$M/fe-stage.txt" 2>/dev/null || echo 없음)" | tee "$M/rb-identity.txt"
crontab -l > "$M/crontab-rollback.txt" || stop "crontab 조회 실패 — 바꾸지 않고 보고"
[ "$(h256 "$M/crontab-rollback.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 바꾸지 않고 보고"
FI=0; fresh_idle rb || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다(아직 바꾼 것 없음) — 끝난 뒤 다시(끊지 않는다)"; stop "프로세스 조회 실패(아직 바꾼 것 없음) — 회차 없음으로 보지 않는다. pgrep-rb.* 를 보고"; }
echo "== 상태 기반 복구 관문 3판 — 판정만(자동 되돌림 없음). 실계좌 보유·RUNNING 이 있으면 EXCH_RECON 에 승인된 읽기 블록의 대조 출력 파일을 준다"
gsok || stop "GS(관문 고정 커밋)가 미정이거나 없음 — 되돌리지 않고 보고"
git show "$GS:$GTP/gate.sql" > "$M/gate.sql" && git show "$GS:$GTP/rollback_gate.sh" > "$M/rollback_gate.sh" || stop "관문 원문을 꺼내지 못함 — 되돌리지 않고 보고"
[ "$(h256 "$M/gate.sql")" = "$GATESQL" ] && [ "$(h256 "$M/rollback_gate.sh")" = "$GATESH" ] || stop "관문 원문 해시가 다름 — 되돌리지 않고 보고"
GR=0; EXCH_RECON="${EXCH_RECON:-}" PSQL="$PSQL -At" bash "$M/rollback_gate.sh" "$M/rollback-ledger" > "$M/gate-out.txt" 2>&1 || GR=$?
cat "$M/gate-out.txt"
[ "$GR" = 0 ] && [ "$(grep '^VERDICT ' "$M/gate-out.txt" | tail -1 | cut -d' ' -f2)" = ROLLBACK_GATE_OK ] || stop "관문이 ROLLBACK_GATE_OK 가 아님(종료 $GR) — 구판으로 되돌리지 않는다. 현재 바이너리로 정지·유효 가격 대기·정산/정리를 유지하고 gate-out.txt 를 보고(0원 정산·행 삭제·마이그레이션 삭제 금지)"
date '+%F %T %Z' | tee "$M/t-rollback.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-rollback.utc"
if [ "$FR" = 새판 ]; then
  docker tag "$FETOLD" autotrader-frontend   # 대응 확인한 보존 대상 ID 로 이미지 이름을 되돌린다
  same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름 원복 확인 실패 — 보고"
  $DC up -d --no-build --no-deps --force-recreate frontend
  sleep 10
  isimg frontend "$(okimg "$M/image-fe-before-chain.txt")" $(ok3 "$M/image-fe-before-chain.txt") || stop "frontend 가 image-fe-before 대상·manifest·config 와 연결되지 않음 또는 조회 실패"
  echo "frontend 복구 — 이미지 이름·실행 컨테이너 모두 U0 대상(837db515)" | tee "$M/fe-restored.txt"
elif [ "$FN" = 1 ]; then
  docker tag "$FETOLD" autotrader-frontend
  same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름 원복 확인 실패 — 보고"
  echo "frontend 실행은 구판 그대로 — 이미지 이름만 U0 대상으로 되돌렸다(재생성 없음)" | tee "$M/fe-restored.txt"
else echo "frontend 는 구판 그대로 — 바꾸지 않는다" | tee "$M/fe-restored.txt"; fi
if [ "$H" = "$NEW" ]; then git checkout --quiet $OLD; else echo "HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다"; fi
[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "체크아웃 실패"
[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 시험 파일이 없다 — 보고"
docker tag "$IMGOLD" $REPO:latest
same3 $REPO:latest "$M/image-before-chain.txt" || stop "backend latest 원복 확인 실패 — 보고"
$DC up -d --no-build --no-deps --force-recreate backend
sleep 15
isimg backend "$(okimg "$M/image-before-chain.txt")" $(ok3 "$M/image-before-chain.txt") || stop "이미지가 image-before 대상·manifest·config 와 다름 또는 조회 실패"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "공유 풀 등록값 다름"
svc | tee "$M/svc-rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-rollback.txt"
echo "✅ b5cd1d0 (#27 이미지) 로 복귀 — 5분 뒤 U3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17·N19·N20 줄은 기록만)"
)
