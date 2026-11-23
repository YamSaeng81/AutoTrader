#!/usr/bin/env bash
# D81A2 시험 전용 가짜 원격 프로그램(v1.225 §3). 파일 입력/출력만 한다:
#   받은 원격 명령 문자열을 파일로 보존하고 호출 기록을 D81_FAKE_LOG 에 덧붙인 뒤 고정 응답 1줄을 낸다.
#   원격 접속·컨테이너·HTTP·하위 셸 실행을 하지 않는다(받은 명령 문자열을 실행하지 않는다).
# 사용: fake-ssh.sh --identify   → 고유 식별 응답 1줄
#       fake-ssh.sh <host> <원격 명령 문자열> < 표준 입력
set -u
ID="D81A2-FAKE-REMOTE v1 file-io-only 3c9e7b14"
if [ "${1:-}" = --identify ]; then printf '%s\n' "$ID"; exit 0; fi
LOG=${D81_FAKE_LOG:?D81_FAKE_LOG 없음}
host=${1:-}; cmd=${2:-}
n=1; [ -f "$LOG" ] && n=$(( $(wc -l < "$LOG") + 1 ))
printf '%s' "$cmd" > "$LOG.cmd.$n"
printf 'call %s host=%s argc=%s cmd_sha256=%s\n' "$n" "$host" "$#" "$(printf '%s' "$cmd" | sha256sum | cut -d' ' -f1)" >> "$LOG"
[ "$host" = d81-test.invalid ] || { echo "fake: 시험 대상이 아닌 host 거절" >&2; exit 98; }
echo "fake: 합성 원격 응답(명령 미실행) — ok-U0 모사 아님"
exit 0
