#!/usr/bin/env bash
# P0 — 만료 전 회전 로그의 서버 내부 보존 사본 + SHA-256 대조 (2026-10-27, docs/RAW_DATA_PRESERVATION_DESIGN.md §9 P0·§11)
# 승인 범위: 서버 안 새 보존 폴더로 복사 + 무결성 확인뿐. 서버 밖 전송·cron·DB 추출 없음. 원본은 건드리지 않는다(읽기만).
# 실행(저장소 폴더 ~/crypto_trader/AutoTrader 에서, 검토한 SHA 로):
#   git show <SHA>:scripts/ops/preserve_logs_p0.sh | bash -s -- plan logs/backend
#   git show <SHA>:scripts/ops/preserve_logs_p0.sh | P0_SHA=<SHA> bash -s -- copy logs/backend "$HOME/preserve/p0-logs-<날짜>"
# 대상: 회전이 끝난 날짜 파일 {system,strategy,trade}.YYYY-MM-DD.log(.gz) 만 — 쓰는 중인 system.log 등은 넣지 않는다.
# 🔴 로그 내용을 읽어 출력하지 않는다(토큰 포함 가능). 화면에는 파일 이름·크기·대조 결과만. 해시 원문은 보존 폴더의 SHA256SUMS 에만.
# 종료 코드: 0 정상 · 2 사전 확인 실패(아무것도 복사하지 않음) · 3 해시 불일치 · 4 복사 실패
set -u
set -o pipefail
export LC_ALL=C
umask 077

MODE="${1:-}"
LOG_DIR="${2:-logs/backend}"
DEST="${3:-}"
PATTERN='^(system|strategy|trade)\.[0-9]{4}-[0-9]{2}-[0-9]{2}\.log(\.gz)?$'
KEY="system.2026-10-01.log"

die() { printf '🔴 %s\n' "$1" >&2; exit "${2:-2}"; }
utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
kst() { TZ=Asia/Seoul date +%Y-%m-%dT%H:%M:%S+09:00; }
free_bytes() { df -Pk "$1" | awk 'NR == 2 { print $4 * 1024 }'; }

case "$MODE" in plan|copy) ;; *) die "사용법: plan <로그 폴더> | copy <로그 폴더> <새 보존 폴더>";; esac
[ -d "$LOG_DIR" ] || die "로그 폴더 없음: $LOG_DIR"
SRC=$(cd "$LOG_DIR" && pwd -P) || die "로그 폴더에 들어갈 수 없음: $LOG_DIR"

# ── 대상 목록 — 이름으로만 고른다(내용을 열지 않는다) ──
FILES=()
while IFS= read -r f; do
  [ -f "$SRC/$f" ] && FILES+=("$f")
done < <(ls -1A "$SRC" | grep -E "$PATTERN" | sort)
[ "${#FILES[@]}" -gt 0 ] || die "회전이 끝난 로그 파일이 없다: $SRC"

total=0
unreadable=()
for f in "${FILES[@]}"; do
  if [ ! -r "$SRC/$f" ]; then unreadable+=("$f"); continue; fi
  total=$(( total + $(stat -c %s "$SRC/$f") ))
done
if [ -e "$SRC/$KEY" ]; then
  key_state="있음 ($(stat -c %s "$SRC/$KEY") bytes)"
else
  key_state="없음 — 이미 지워졌거나 만들어지지 않았다"
fi
first=${FILES[0]}
last=${FILES[$(( ${#FILES[@]} - 1 ))]}

summary() {
  printf 'SOURCE: %s\n' "$SRC"
  printf 'FILES: %d개 · 총 %d bytes (%s)\n' "${#FILES[@]}" "$total" "$(numfmt --to=iec "$total" 2>/dev/null || echo "$total")"
  for stem in system strategy trade; do
    n=$(printf '%s\n' "${FILES[@]}" | grep -c "^$stem\.")
    d=$(printf '%s\n' "${FILES[@]}" | grep "^$stem\." | sed -E 's/^[a-z]+\.([0-9-]{10})\.log.*/\1/' | sort | sed -n '1p;$p' | paste -sd'~' -)
    printf '  %-8s %3d개  %s\n' "$stem" "$n" "${d:--}"
  done
  printf '10-01 파일(%s): %s\n' "$KEY" "$key_state"
  printf '읽기 불가: %d개%s\n' "${#unreadable[@]}" "$( [ "${#unreadable[@]}" -gt 0 ] && printf ' — %s' "${unreadable[*]}")"
}

if [ "$MODE" = "plan" ]; then
  summary
  printf '목록:\n'
  for f in "${FILES[@]}"; do printf '  %s  %s bytes\n' "$f" "$(stat -c %s "$SRC/$f" 2>/dev/null || echo '?')"; done
  base="${DEST:-$HOME/preserve}"
  probe="$base"; while [ ! -d "$probe" ]; do probe=$(dirname "$probe"); done
  printf '보존 위치 후보: %s (여유 확인 경로 %s) · 여유 %s bytes · 필요(총크기 × 2) %s bytes\n' \
    "$base" "$probe" "$(free_bytes "$probe")" "$(( total * 2 ))"
  printf 'plan 은 아무것도 복사하지 않았다.\n'
  exit 0
fi

# ── copy ──
[ "${#unreadable[@]}" -eq 0 ] || die "읽을 수 없는 파일이 있다 — 복사하지 않는다(위 plan 참고)"
[ -n "$DEST" ] || die "새 보존 폴더 경로를 준다(예: \$HOME/preserve/p0-logs-20261028)"
[ ! -e "$DEST" ] || die "보존 폴더가 이미 있다 — 새 폴더만 쓴다: $DEST"
parent=$(dirname "$DEST")
if [ ! -d "$parent" ]; then
  mkdir -p "$parent" || die "상위 폴더를 만들 수 없음: $parent"
  chmod 700 "$parent"
fi
PARENT=$(cd "$parent" && pwd -P)
DEST_ABS="$PARENT/$(basename "$DEST")"
REPO=$(pwd -P)
case "$DEST_ABS/" in
  "$SRC"/*) die "보존 폴더가 로그 폴더 안이다 — logback 정리 경로 밖이어야 한다: $DEST_ABS";;
  "$REPO/backups"/*|"$REPO/logs"/*) die "보존 폴더가 기존 정리 경로(backups·logs) 안이다: $DEST_ABS";;
esac
free_before=$(free_bytes "$PARENT")
[ "$free_before" -ge $(( total * 2 )) ] || die "디스크 여유 부족 — 여유 $free_before < 필요 $(( total * 2 )) bytes"

started_utc=$(utc); started_kst=$(kst)
mkdir "$DEST_ABS" || die "보존 폴더를 만들 수 없음: $DEST_ABS"
chmod 700 "$DEST_ABS"
summary

: > "$DEST_ABS/SHA256SUMS"
RESULTS="$DEST_ABS/RESULTS.tsv"
printf 'file\tbytes\tsource_sha256\tcopy_sha256\tmatch\n' > "$RESULTS"
ok=0; bad=(); failed=()
for f in "${FILES[@]}"; do
  if ! cp -p -- "$SRC/$f" "$DEST_ABS/$f"; then failed+=("$f"); continue; fi
  chmod 600 "$DEST_ABS/$f"
  s=$(sha256sum -- "$SRC/$f" | cut -d' ' -f1)
  c=$(sha256sum -- "$DEST_ABS/$f" | cut -d' ' -f1)
  printf '%s  %s\n' "$c" "$f" >> "$DEST_ABS/SHA256SUMS"
  if [ "$s" = "$c" ]; then m=OK; ok=$(( ok + 1 )); else m=MISMATCH; bad+=("$f"); fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$f" "$(stat -c %s "$DEST_ABS/$f")" "$s" "$c" "$m" >> "$RESULTS"
done
# 사본 폴더 자체 재검사 — SHA256SUMS 로
recheck=$( (cd "$DEST_ABS" && sha256sum --quiet -c SHA256SUMS) >/dev/null 2>&1 && echo OK || echo FAIL )
finished_utc=$(utc); finished_kst=$(kst)
free_after=$(free_bytes "$PARENT")
sums_sha=$(sha256sum "$DEST_ABS/SHA256SUMS" | cut -d' ' -f1)

{
  printf 'P0 회전 로그 보존 기록\n'
  printf '수행자: %s (uid %s) @ %s\n' "$(id -un)" "$(id -u)" "$(hostname)"
  printf '검토 SHA: %s\n' "${P0_SHA:-미기재}"
  printf '시작: %s (%s)\n종료: %s (%s)\n' "$started_utc" "$started_kst" "$finished_utc" "$finished_kst"
  printf '원본: %s (그대로 둠)\n사본: %s (폴더 700, 파일 600)\n' "$SRC" "$DEST_ABS"
  printf '파일: %d개 · 총 %d bytes · 10-01 파일 %s\n' "${#FILES[@]}" "$total" "$key_state"
  printf '대조: OK %d · 불일치 %d · 복사 실패 %d · SHA256SUMS 재검사 %s\n' "$ok" "${#bad[@]}" "${#failed[@]}" "$recheck"
  printf 'SHA256SUMS 의 sha256: %s\n' "$sums_sha"
  printf '디스크 여유(사본 위치): 전 %s · 후 %s bytes\n' "$free_before" "$free_after"
  printf '파일별 결과: RESULTS.tsv\n'
} > "$DEST_ABS/RECORD.txt"
chmod 600 "$DEST_ABS/SHA256SUMS" "$RESULTS" "$DEST_ABS/RECORD.txt"

printf '\n시작 %s (%s) · 종료 %s (%s) · 수행자 %s\n' "$started_utc" "$started_kst" "$finished_utc" "$finished_kst" "$(id -un)"
printf '사본: %s · 권한 폴더 %s\n' "$DEST_ABS" "$(stat -c %a "$DEST_ABS")"
printf '대조: OK %d / %d · 불일치 %d · 복사 실패 %d · SHA256SUMS 재검사 %s\n' "$ok" "${#FILES[@]}" "${#bad[@]}" "${#failed[@]}" "$recheck"
[ "${#bad[@]}" -eq 0 ] || printf '  불일치: %s\n' "${bad[*]}"
[ "${#failed[@]}" -eq 0 ] || printf '  복사 실패: %s\n' "${failed[*]}"
printf 'SHA256SUMS 의 sha256: %s\n' "$sums_sha"
printf '기록: %s/RECORD.txt · 파일별: RESULTS.tsv\n' "$DEST_ABS"
[ "${#failed[@]}" -eq 0 ] || exit 4
[ "${#bad[@]}" -eq 0 ] && [ "$recheck" = OK ] || exit 3
exit 0
