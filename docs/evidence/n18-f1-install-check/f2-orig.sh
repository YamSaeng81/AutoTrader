(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ ! -e "$M/f1-failed-$(TZ=Asia/Seoul date +%F)" ] || stop "오늘 F1 실패 기록이 있다 — 오늘은 시작하지 않고 보고"
[ -e "$M/ok-F1" ] || stop "F1 미통과"
[ -e "$M/t-f1.txt" ] && [ "$M/ok-F1" -nt "$M/t-f1.txt" ] || stop "F1 통과가 최신 시도 뒤의 결과가 아님 — 시작하지 않고 보고"
[ $(( $(date +%s) - $(stat -c %Y "$M/ok-F1") )) -le 1800 ] || stop "F1 통과가 30분보다 오래됨 — F1 부터 다시"
[ -s "$M/operator-confirm.txt" ] && ! grep -q '<' "$M/operator-confirm.txt" && [ "$M/operator-confirm.txt" -nt "$M/ok-F1" ] || stop "운영자 확인 파일 없음·자리표시 남음·F1 보다 오래됨"
! grep -q '다른 것 있음' "$M/operator-confirm.txt" || stop "경보에 기존 반복 밖의 것 있음 — 시작하지 않고 보고"
[ "$(TZ=Asia/Seoul date +%F)" = "$START_DATE" ] || stop "오늘 $(TZ=Asia/Seoul date +%F) KST 는 시작 예정일 $START_DATE 이 아님 — 시작하지 않는다"
HM=$(TZ=Asia/Seoul date +%H%M); [ $((10#$HM)) -ge 850 ] && [ $((10#$HM)) -le 855 ] || stop "지금 $HM KST — 08:50~08:55 밖이라 시작하지 않는다(다음 대응 가능한 날 08:50)"
[ ! -e "$M/start-sent" ] || stop "이미 시작 요청을 보냈다 — 다시 보내지 않는다. 아래 확인 조회부터"
[ "$($PSQL -At -c "$EG")" = "$EGS" ] || stop "EG 가 EGS 와 다름"
[ "$($PSQL -At -c "$LIVE1")" = "$SID:CREATED" ] || stop "203:CREATED 가 아님"
TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)
date '+%F %T %Z' | tee "$M/t-start.txt"; touch "$M/start-sent"
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/trading/sessions/$SID/start | tee "$M/start.json"; echo
)
