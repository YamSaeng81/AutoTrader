# U1-되돌림(D4) — U1 이 '… U1-되돌림' 으로 멈췄거나, U1 통과 뒤 운영자가 U2 를 하지 않기로 했을 때만 쓴다(자동 실행 없음). 재기동하지 않는다.
# 대상은 HEAD=NEW 뿐이다(이미 OLD 이면 체크아웃 없이 latest·원문만 맞춘다). 그 밖 HEAD·추적 변경·실행 이미지/cron 불일치·프로세스 조회 실패는 바꾸지 않고 멈춘다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V161D4 ] || stop "U 변수가 v1.161 통합 후보 초안 D4 판이 아님 — F → G → v1.161 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
[ ! -e "$M/ok-U2" ] && [ ! -e "$M/t-deploy.utc" ] || stop "U2(재기동)를 시작했다 — U1-되돌림이 아니라 U되돌림"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #27 이미지(IMGOLD)가 아님"
[ -e "$M/start_66.py.before" ] || stop "U0 보존본이 없다 — 보고"
date '+%F %T %Z' | tee "$M/t-u1rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-u1rollback.txt"
H=$(head -1 "$M/git-pre-u1rollback.txt")
[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 후보·b5cd1d0 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"
[ "$(sed -n 2p "$M/git-pre-u1rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "실행 중 backend 가 image-before 가 아님 — 재기동이 있었을 수 있다. 바꾸지 않고 보고"
crontab -l > "$M/crontab-u1rollback.txt" || stop "crontab 조회 실패 — 바꾸지 않고 보고"
[ "$(h256 "$M/crontab-u1rollback.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 바꾸지 않고 보고"
FI=0; fresh_idle u1rb || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다(아직 바꾼 것 없음) — 끝난 뒤 다시(끊지 않는다)"; stop "프로세스 조회 실패(아직 바꾼 것 없음) — 회차 없음으로 보지 않는다. pgrep-u1rb.* 를 보고"; }
if [ "$H" = "$NEW" ]; then git checkout --quiet $OLD; else echo "HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다"; fi
docker tag "$IMG0" $REPO:latest
if [ -s "$M/image-fe-before.txt" ] && [ -s "$M/fe-ref-before.txt" ]; then FEREF=$(cat "$M/fe-ref-before.txt"); docker tag "$(cat "$M/image-fe-before.txt")" "$FEREF"; [ "$(docker image inspect --format '{{.Id}}' "$FEREF")" = "$(cat "$M/image-fe-before.txt")" ] || stop "frontend latest 원복 실패 — 보고"; [ "$(docker inspect --format '{{.Image}}' "$($DC ps -q frontend)")" = "$(cat "$M/image-fe-before.txt")" ] || stop "실행 중 frontend 가 바뀌었다 — 보고"; fi
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; docker image inspect --format '{{.Id}}' $REPO:latest; } | tee "$M/git-u1rollback.txt"
[ "$(head -1 "$M/git-u1rollback.txt")" = "$OLD" ] && [ "$(sed -n 2p "$M/git-u1rollback.txt")" = "(끝)" ] && [ "$(sed -n 3p "$M/git-u1rollback.txt")" = "$IMG0" ] || stop "되돌림 결과가 b5cd1d0·latest=image-before 가 아님 — 보고"
[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 시험 파일이 없다 — 보고"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "실행 중 backend 가 바뀌었다 — 보고"
rm -f "$M/ok-U1"; echo "↩ U1-되돌림 끝 — HEAD b5cd1d0·latest = image-before, 재기동 없음. 상태 파일(streak·snap·log)은 되감지 않았다"
)
