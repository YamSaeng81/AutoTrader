# A2. 뒤 확인 — 읽기 전용. A1 뒤 첫 :19 회차가 끝난 다음(회차가 길면 끝날 때까지) 쓴다. 회차를 손으로 돌리거나 실패를 유도하지 않는다.
# 통과 조건(구간 = A1 이 체크아웃 직전에 기록한 위치 뒤): cron 시작 줄 ≥ 1, 새 관측 행 ≥ 1, 새 Traceback 0, JSON 칸 같음, N21 상태 0.
# 기록을 못 읽거나 근거가 하나라도 없으면 '미확인'으로 멈춘다(종료 1). 재조회 실패 경로(net·404)는 자연 발생 전까지 미관측이다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${NB:-}" = 21v2 ] && type fresh_idle > /dev/null 2>&1 && type n21after > /dev/null 2>&1 || stop "N21 v2 변수(vars.sh)를 먼저 넣는다"
[ -e "$M/ok-A1" ] && [ -e "$M/mark-apply.txt" ] || stop "A1 미통과"
FI=0; fresh_idle a2 || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "회차가 아직 돈다 — 끝난 뒤 다시"; stop "프로세스 조회 실패 — pgrep-a2.* 를 보고"; }
date '+%F %T %Z' | tee "$M/t-a2.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-a2.txt"
[ "$(head -1 "$M/git-a2.txt")" = "$NEW" ] && [ "$(sed -n 2p "$M/git-a2.txt")" = "(끝)" ] || stop "HEAD 가 6379ee0b 가 아니거나 변경 있음"
[ "$(sha256sum < "$F" | cut -d' ' -f1)" = "$HNEW" ] || stop "start_66.py 가 후보 원문과 다름"
NA=0; n21after apply > "$M/n21after-a2.txt" 2>&1 || NA=$?
cat "$M/n21after-a2.txt"
[ "$NA" = 0 ] || stop "적용 뒤 근거 미확인 또는 이상(종료 $NA) — n21after-a2.txt 를 보고(회차를 손으로 돌리지 않는다)"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG" ] || stop "backend 이미지가 바뀌었다 — 보고"
crontab -l | cmp -s - "$M/crontab-before.txt" || stop "cron 이 A0 때와 다름 — 보고"
echo "✅ A2 — 적용 뒤 cron 시작 줄·새 관측 행·새 Traceback 0 확인(시작 줄과 행의 1:1 짝은 미확정). 재조회 실패 경로(net·404)는 자연 발생 전까지 미관측이다"
)
