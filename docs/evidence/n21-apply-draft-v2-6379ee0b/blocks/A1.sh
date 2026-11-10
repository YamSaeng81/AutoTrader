# A1. 적용 — 서버 체크아웃만 NEW 로 옮긴다. 빌드·재기동·cron·DB·설정 변경 없음.
# 실패 메시지에 'A1-되돌림' 이 있으면 운영자가 판단해 A1-되돌림을 쓴다(자동 되돌림 없음). '아직 바꾼 것 없음' 이면 되돌리지 않는다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${NB:-}" = 21v2 ] && type fresh_idle > /dev/null 2>&1 && type n21mark > /dev/null 2>&1 || stop "N21 v2 변수(vars.sh)를 먼저 넣는다"
[ -e "$M/ok-A0" ] || stop "A0 미통과"
[ ! -e "$M/ok-A1" ] || stop "A1 을 이미 통과했다 — A2 로"
[ $(( $(date +%s) - $(stat -c %Y "$M/ok-A0") )) -le 1800 ] || stop "A0 가 30분보다 오래됨 — A0 부터(아직 바꾼 것 없음)"
MIN=$((10#$(date +%M)))
[ "$MIN" -ge 25 ] || [ "$MIN" -lt 14 ] || stop "매시 :14~:24 — :25 이후 A0 부터(아직 바꾼 것 없음)"
[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "HEAD·작업 트리가 A0 때와 다름(아직 바꾼 것 없음) — 보고"
FI=0; fresh_idle a1 || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다(아직 바꾼 것 없음) — 끝난 뒤 A0 부터(끊지 않는다)"; stop "프로세스 조회 실패(아직 바꾼 것 없음) — 회차 없음으로 보지 않는다. pgrep-a1.* 를 보고"; }
n21mark apply || stop "기준 위치를 기록하지 못했다(아직 바꾼 것 없음) — 보고"
date '+%F %T %Z' | tee "$M/t-apply.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-apply.utc"
git checkout --quiet "$NEW"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-apply.txt"
[ "$(head -1 "$M/git-apply.txt")" = "$NEW" ] && [ "$(sed -n 2p "$M/git-apply.txt")" = "(끝)" ] || stop "체크아웃 결과가 NEW 가 아니거나 변경 있음 — A1-되돌림"
[ "$(git hash-object "$F")" = "$BNEW" ] && [ "$(sha256sum < "$F" | cut -d' ' -f1)" = "$HNEW" ] || stop "start_66.py 가 후보 원문(6305e512…·68d441ce…)과 다름 — A1-되돌림"
grep -c 'state\[pair\]\["exTo"\] = ex2$' "$F" | tee "$M/fix-line.txt"
[ "$(cat "$M/fix-line.txt")" = 1 ] || stop "수정 줄이 1개가 아님 — A1-되돌림"
python3 -c 'import ast,sys; ast.parse(open(sys.argv[1], encoding="utf-8").read())' "$F" > "$M/syntax.txt" 2>&1 || { cat "$M/syntax.txt"; stop "문법 확인 실패 — A1-되돌림"; }   # 캐시 파일을 쓰지 않는 문법 확인
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG" ] || stop "backend 이미지가 바뀌었다 — 즉시 보고(이 블록은 컨테이너를 건드리지 않는다)"
crontab -l | cmp -s - "$M/crontab-before.txt" || stop "cron 이 A0 때와 다름 — 보고"
touch "$M/ok-A1"; echo "✅ A1 통과 — 다음 :19 회차가 끝난 뒤 A2"
)
