# 대역 공통 — 모든 가짜 명령이 불린 기록을 남긴다. 실제 docker·git·ssh·네트워크를 부르지 않는다.
: "${SIM:?SIM 없음 — sim.sh 로만 실행}"
ST=$SIM/state
calllog() { printf '%s\t%s\t%.300s\n' "$(cat "$ST/clock")" "$1" "$(printf '%s ' "${@:2}" | tr '\n' ' ')" >> "$SIM/calls.log"; }
now() { cat "$ST/clock"; }
forbid() { printf 'FORBIDDEN\t%s\t%.300s\n' "$1" "$(printf '%s ' "${@:2}" | tr '\n' ' ')" >> "$SIM/forbidden.log"; echo "대역 금지 명령: $1" >&2; exit 99; }
