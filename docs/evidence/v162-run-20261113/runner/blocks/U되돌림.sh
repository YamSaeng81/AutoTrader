(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V161D4 ] || stop "U 변수가 v1.161 통합 후보 초안 D4 판이 아님 — F → G → v1.161 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #27 이미지(IMGOLD)가 아님"
[ -e "$M/start_66.py.before" ] || stop "U0 보존본이 없다 — 보고"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-rollback.txt"
H=$(head -1 "$M/git-pre-rollback.txt")
[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 후보·b5cd1d0 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"
[ "$(sed -n 2p "$M/git-pre-rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"
IMGR=$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)") || stop "실행 이미지 조회 실패 — 바꾸지 않고 보고"
[ "$IMGR" = "$IMG0" ] || { [ -s "$M/image-new.txt" ] && [ "$IMGR" = "$(cat "$M/image-new.txt")" ]; } || stop "실행 이미지가 image-before·image-new 어느 것도 아님 — 다른 작업이 있었을 수 있다. 바꾸지 않고 보고"
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
if [ -s "$M/image-fe-new.txt" ] && [ "$(docker inspect --format '{{.Image}}' "$($DC ps -q frontend)")" = "$(cat "$M/image-fe-new.txt")" ]; then
  FE0=$(cat "$M/image-fe-before.txt"); docker tag "$FE0" "$(cat "$M/fe-ref-before.txt")"
  $DC up -d --no-build --no-deps --force-recreate frontend
  sleep 10
  [ "$(docker inspect --format '{{.Image}}' "$($DC ps -q frontend)")" = "$FE0" ] || stop "frontend 이미지가 image-fe-before 와 다름"
else echo "frontend 는 구판 그대로 — 바꾸지 않는다"; fi
if [ "$H" = "$NEW" ]; then git checkout --quiet $OLD; else echo "HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다"; fi
[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "체크아웃 실패"
[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 시험 파일이 없다 — 보고"
docker tag "$IMG0" $REPO:latest
$DC up -d --no-build --no-deps --force-recreate backend
sleep 15
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "이미지가 image-before 와 다름"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "공유 풀 등록값 다름"
svc | tee "$M/svc-rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-rollback.txt"
echo "✅ b5cd1d0 (#27 이미지) 로 복귀 — 5분 뒤 U3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17·N19·N20 줄은 기록만)"
)
