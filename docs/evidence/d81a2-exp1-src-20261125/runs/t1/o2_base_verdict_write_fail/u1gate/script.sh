(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = D81D4 ] || stop "U 변수가 v1.198 backend 전용 D81D4 판이 아님 — F → G → D81D4 U 변수(초안은 실행하지 않는다)"
[ -e "$M/ok-U0" ] || stop "U0 미통과"
[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 통과했다 — 바로 U2"
[ $(( $(date +%s) - $(stat -c %Y "$M/ok-U0") )) -le 1800 ] || stop "U0 가 30분보다 오래됨 — U0 부터(아직 바꾼 것 없음, U1-되돌림 하지 않는다)"
echo U1_GATE_PASS
)
