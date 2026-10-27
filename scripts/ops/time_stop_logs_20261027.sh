#!/usr/bin/env bash
# TIME_STOP 전송 실패(10-26 17:00Z) 전후 15분 호스트 보존 로그 조회 (2026-10-27, 읽기 전용) — 운영 상태를 바꾸지 않는다.
# 실행(저장소 폴더에서): git show <SHA>:scripts/ops/time_stop_logs_20261027.sh | bash -s -- logs/backend
# selected=0 은 '사건 없음' 의 증거가 아니다 — RANGE 와 읽기 실패·누락 파일을 함께 본다. 텔레그램 봇 토큰은 출력에서 가린다.
set -u
set -o pipefail
export LC_ALL=C
LOG_DIR="${1:-logs/backend}"
read_files=0
read_failed=0
for stem in system trade; do
  found=0
  for path in "$LOG_DIR/$stem.2026-10-26.log" \
              "$LOG_DIR/$stem.2026-10-26.log.gz" \
              "$LOG_DIR/$stem.log"; do
    [ -e "$path" ] || continue
    found=1
    if [ ! -r "$path" ]; then
      printf 'UNREADABLE: %s\n' "$path" >&2
      read_failed=1
      continue
    fi
    read_files=$((read_files + 1))
    printf '\nFILE: %s\n' "$path"
    if ! gzip -cdf -- "$path" | awk -v src="$path" '
      $1 ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/ {
        if (first == "") first = $1 " " $2
        last = $1 " " $2
      }
      $1 == "2026-10-26" && $2 >= "16:45:00" && $2 < "17:15:00" &&
        /Telegram|TIME_STOP|시간 초과 청산|매도|청산|정산|체결/ {
        print src ":" $0
        n++
      }
      END {
        printf "RANGE: %s | first=%s | last=%s | selected=%d\n", src, first, last, n
      }' | sed -E 's#(https://api.telegram.org/bot)[^/[:space:]]+#\1[REDACTED]#g'; then
      printf 'READ_FAILED: %s\n' "$path" >&2
      read_failed=1
    fi
  done
  if [ "$found" -eq 0 ]; then
    printf 'UNAVAILABLE: %s logs not found under %s\n' "$stem" "$LOG_DIR" >&2
    read_failed=1
  fi
done
[ "$read_files" -gt 0 ] || read_failed=1
printf '\nUTC window: 2026-10-26 16:45:00 <= t < 17:15:00\n'
printf 'KST window: 2026-10-27 01:45:00 <= t < 02:15:00\n'
printf 'selected=0 is not proof of no event; check RANGE and missing/unreadable files.\n'
[ "$read_failed" -eq 0 ] || exit 2

