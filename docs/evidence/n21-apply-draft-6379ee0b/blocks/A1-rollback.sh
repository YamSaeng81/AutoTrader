# A1-되돌림 — 운영자가 판단했을 때만 쓴다(자동 실행 없음). 체크아웃을 OLD 로 되돌리고 원문을 대조한다. 빌드·재기동 없음
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${NB:-}" = 21 ] || stop "N21 변수(vars.sh)를 먼저 넣는다"
[ -e "$M/start_66.py.before" ] || stop "A0 보존본이 없다 — 보고"
if pgrep -af "start_66.py freshness" > "$M/pgrep-rb.txt"; then cat "$M/pgrep-rb.txt"; stop "freshness 회차가 돌고 있다 — 끝난 뒤 다시(끊지 않는다)"; fi
date '+%F %T %Z' | tee "$M/t-rollback.txt"
git status --porcelain --untracked-files=no > "$M/status-pre-rollback.txt"
[ ! -s "$M/status-pre-rollback.txt" ] || { cat "$M/status-pre-rollback.txt"; stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"; }
git checkout --quiet "$OLD"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-rollback.txt"
[ "$(head -1 "$M/git-rollback.txt")" = "$OLD" ] && [ "$(sed -n 2p "$M/git-rollback.txt")" = "(끝)" ] || stop "되돌림 결과가 1899b49 가 아니거나 변경 있음 — 보고"
[ "$(sha256sum < "$F" | cut -d' ' -f1)" = "$HOLD" ] && cmp -s "$F" "$M/start_66.py.before" || stop "start_66.py 가 A0 보존본과 다름 — 보고"
[ ! -e "$T" ] || stop "$T 가 남아 있다 — 보고"
rm -f "$M/ok-A1"; echo "↩ 되돌림 끝 — 1899b49. 상태 파일(streak·snap·log)은 건드리지 않았다"
)
