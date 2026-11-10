# A1-되돌림 — 운영자가 판단했을 때만 쓴다(자동 실행 없음). 체크아웃을 OLD 로 되돌리고 원문을 대조한다. 빌드·재기동 없음.
# 대상은 HEAD=NEW 뿐이다. HEAD 가 이미 OLD 이면 체크아웃하지 않고 원문만 대조한다.
# 그 밖 HEAD·추적 변경·이미지/cron 불일치·프로세스 조회 실패는 바꾸지 않고 멈춘다. 상태 파일(streak·snap·log)은 되감지 않는다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${NB:-}" = 21v2 ] && type fresh_idle > /dev/null 2>&1 || stop "N21 v2 변수(vars.sh)를 먼저 넣는다"
[ -e "$M/start_66.py.before" ] && [ -e "$M/crontab-before.txt" ] || stop "A0 보존본이 없다 — 보고"
date '+%F %T %Z' | tee "$M/t-rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-rollback.txt"
H=$(head -1 "$M/git-pre-rollback.txt")
[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 6379ee0b·1899b49 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"
[ "$(sed -n 2p "$M/git-pre-rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"
FI=0; fresh_idle rb || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다 — 끝난 뒤 다시(끊지 않는다)"; stop "프로세스 조회 실패 — 회차 없음으로 보지 않는다. 바꾸지 않고 pgrep-rb.* 를 보고"; }
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG" ] || stop "backend 이미지가 426a09a2… 가 아님 — 다른 작업이 있었을 수 있다. 바꾸지 않고 보고"
crontab -l > "$M/crontab-rollback.txt" || stop "crontab 조회 실패 — 바꾸지 않고 보고"
cmp -s "$M/crontab-rollback.txt" "$M/crontab-before.txt" && [ "$(sha256sum < "$M/crontab-rollback.txt" | cut -d' ' -f1)" = "$CRONH" ] || stop "cron 이 A0 때와 다름 — 바꾸지 않고 보고"
if [ "$H" = "$NEW" ]; then git checkout --quiet "$OLD"; else echo "HEAD 가 이미 1899b49 — 체크아웃하지 않고 원문만 대조한다"; fi
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-rollback.txt"
[ "$(head -1 "$M/git-rollback.txt")" = "$OLD" ] && [ "$(sed -n 2p "$M/git-rollback.txt")" = "(끝)" ] || stop "되돌림 결과가 1899b49 가 아니거나 변경 있음 — 보고"
[ "$(sha256sum < "$F" | cut -d' ' -f1)" = "$HOLD" ] && cmp -s "$F" "$M/start_66.py.before" || stop "start_66.py 가 A0 보존본과 다름 — 보고"
[ ! -e "$T" ] || stop "$T 가 남아 있다 — 보고"
rm -f "$M/ok-A1"; echo "↩ 되돌림 끝 — 1899b49. 상태 파일(streak·snap·log)은 건드리지 않았다"
)
