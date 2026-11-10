# A0. 사전 확인 — 읽기 전용 + git fetch(작업 트리는 바꾸지 않음). 실패하면 아무것도 바꾸지 않은 상태로 멈춘다
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${NB:-}" = 21 ] || stop "N21 변수(vars.sh)를 먼저 넣는다"
[ ! -e "$M/ok-A1" ] || stop "A1 을 이미 통과했다 — A2 로"
rm -f "$M/ok-A0"
date '+%F %T %Z' | tee "$M/t-a0.txt"
MIN=$((10#$(date +%M)))
[ "$MIN" -ge 25 ] || [ "$MIN" -lt 14 ] || stop "매시 :14~:24 는 freshness 회차 직전·직후다 — :25 이후 다시"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-before.txt"
[ "$(head -1 "$M/git-before.txt")" = "$OLD" ] && [ "$(sed -n 2p "$M/git-before.txt")" = "(끝)" ] || stop "HEAD 가 1899b49 가 아니거나 추적 파일 변경 있음"
[ "$(git rev-parse "HEAD:$F")" = "$BOLD" ] && [ "$(git hash-object "$F")" = "$BOLD" ] && [ "$(sha256sum < "$F" | cut -d' ' -f1)" = "$HOLD" ] || stop "start_66.py 가 1899b49 원문(460031d3…)과 다름"
[ ! -e "$T" ] || stop "$T 가 이미 있다(추적 안 된 파일) — 체크아웃이 덮어쓰지 않도록 보고"
docker inspect --format '{{.Image}}' "$($DC ps -q backend)" | tee "$M/image-before.txt"
[ "$(cat "$M/image-before.txt")" = "$IMG" ] || stop "backend 이미지가 426a09a2… 가 아님 — 보고"
crontab -l > "$M/crontab-before.txt" || stop "crontab 조회 실패"
[ "$(sha256sum < "$M/crontab-before.txt" | cut -d' ' -f1)" = "$CRONH" ] || stop "crontab 이 f2dce280… 과 다름 — 보고"
if pgrep -af "start_66.py freshness" > "$M/pgrep-a0.txt"; then cat "$M/pgrep-a0.txt"; stop "freshness 회차가 아직 돌고 있다 — 끝날 때까지 기다린다(끊지 않는다)"; fi
git fetch origin
git cat-file -e "$NEW^{commit}" || stop "후보 6379ee0b 없음"
[ "$(git rev-parse "$NEW^")" = "$MID" ] && [ "$(git rev-parse "$MID^")" = "$OLD" ] || stop "후보 계보가 1899b49 → 99bd4eaa → 6379ee0b 가 아님"
git diff --name-status "$OLD" "$NEW" | tee "$M/diff-names.txt"
[ "$(cat "$M/diff-names.txt")" = "$(printf 'M\t%s\nA\t%s' "$F" "$T")" ] || stop "바뀌는 파일이 start_66.py·새 시험 둘이 아님"
[ "$(git rev-parse "$NEW:$F")" = "$BNEW" ] || stop "후보 start_66.py 블롭이 6305e512… 가 아님"
git diff "$OLD" "$NEW" -- "$F" > "$M/start_66.diff"
cp -p "$F" "$M/start_66.py.before"
for s in freshness_streak.json freshness_snap.json; do [ ! -f "scripts/prospective/$s" ] || cp -p "scripts/prospective/$s" "$M/$s.before"; done
wc -l < scripts/prospective/freshness_log.jsonl > "$M/log-lines-before.txt"
tail -1 scripts/prospective/freshness_log.jsonl > "$M/log-last-before.json"
touch "$M/ok-A0"; echo "✅ A0 통과 — :14 전, 30분 안에 A1"
)
