# U5. N21 감시 스크립트 뒤 확인 — 읽기 전용. U1 체크아웃 뒤 첫 :19 회차가 끝난 다음 쓴다(U2~U4 와 따로, 회차가 길면 끝날 때까지).
# 회차를 손으로 돌리거나 실패를 유도하지 않는다. 통과 조건(구간 = U1 이 체크아웃 직전에 기록한 위치 뒤): cron 시작 줄 ≥ 1, 새 관측 행 ≥ 1,
# 새 Traceback 0, JSON 칸 같음, N21 상태 0. 기록을 못 읽거나 근거가 하나라도 없으면 '미확인'으로 멈춘다. 재조회 실패 경로(net·404)는 자연 발생 전까지 미관측이다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = O13N21D3 ] || stop "U 변수가 O1·O3+N21 초안 D3 판이 아님 — F → G → O1·O3+N21 초안 D3 U 변수(초안은 실행하지 않는다)"
[ -e "$M/ok-U1" ] && [ -e "$M/mark-apply.txt" ] || stop "U1 미통과(또는 U1-되돌림 함)"
[ ! -e "$M/t-rollback.utc" ] || stop "U되돌림을 했다 — U5 는 하지 않는다"
FI=0; fresh_idle u5 || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다 — 끝난 뒤 다시(끊지 않는다)"; stop "프로세스 조회 실패 — 회차 없음으로 보지 않는다. pgrep-u5.* 를 보고"; }
date '+%F %T %Z' | tee "$M/t-u5.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-u5.txt"
[ "$(head -1 "$M/git-u5.txt")" = "$NEW" ] && [ "$(sed -n 2p "$M/git-u5.txt")" = "(끝)" ] || stop "HEAD 가 통합 후보가 아니거나 변경 있음"
[ "$(h256 "$NF")" = "$NHNEW" ] || stop "start_66.py 가 통합 후보 원문과 다름"
NA=0; n21after apply > "$M/n21after-u5.txt" 2>&1 || NA=$?
cat "$M/n21after-u5.txt"
[ "$NA" = 0 ] || stop "N21 적용 뒤 근거 미확인 또는 이상(종료 $NA) — n21after-u5.txt 를 보고(회차를 손으로 돌리지 않는다)"
crontab -l > "$M/crontab-u5.txt" || stop "crontab 조회 실패"
[ "$(h256 "$M/crontab-u5.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 보고"
if [ -e "$M/ok-U2" ]; then EXP=$(cat "$M/image-new.txt"); else EXP=$(cat "$M/image-before.txt"); fi
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$EXP" ] || stop "실행 이미지가 이 단계의 기대(image-before 또는 image-new)와 다름 — 보고"
echo "✅ U5 — 적용 뒤 cron 시작 줄·새 관측 행·새 Traceback 0 확인(시작 줄과 행의 1:1 짝은 미확정). 재조회 실패 경로(net·404)는 자연 발생 전까지 미관측이다"
)
