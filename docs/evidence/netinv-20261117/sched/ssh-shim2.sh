#!/bin/bash
# 2판(2026-11-17): 접속 단계 실패(종료 255)만 재시도한다. 시도마다 표준 출력을 임시 파일에 받고, 255 가 아닌 시도의 출력만 내보낸다
# (1판은 일부 출력 뒤 끊기면 재시도 출력이 이어 붙을 수 있었다). 표준 입력은 쓰지 않는 호출(</dev/null)에만 쓴다. 원격 명령은 그대로.
t=$(mktemp)
for i in 1 2 3 4 5 6 7 8 9 10; do
  /usr/bin/ssh -o ConnectTimeout=15 "$@" > "$t"; rc=$?
  if [ $rc -ne 255 ]; then cat "$t"; rm -f "$t"; exit $rc; fi
  echo "[shim2] 종료 255 재시도 $i ($(wc -c < "$t") 바이트 버림)" >&2; sleep 3
done
rm -f "$t"; exit 255
