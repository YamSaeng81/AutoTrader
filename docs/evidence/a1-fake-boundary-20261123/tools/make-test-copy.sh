#!/usr/bin/env bash
# 생산용 초안에서 시험 사본을 기계적으로 만든다(v1.225 §3). 바뀌는 곳은 정확히 세 군데뿐이다:
#   ① 1행 뒤 시험 사본 표지 1줄  ② `set -u` 뒤 test-guard.inc(가짜 해시 고정)  ③ 원격 호출 1줄 → 가짜 절대 경로 호출
# 사용: make-test-copy.sh <생산용 초안> <가짜 파일> <출력 시험 사본>
set -eu
SRC=$1; FK=$2; OUT=$3
HERE="$(cd "$(dirname "$0")" && pwd)"
FSHA=$(sha256sum < "$FK" | cut -d' ' -f1)
OLD='  ssh -o BatchMode=yes cat-prod "$4" < "$3" 2> "$2" | tee "$1" > /dev/null'
NEW='  "$FAKE" "$TEST_HOST" "$4" < "$3" 2> "$2" | tee "$1" > /dev/null'
[ "$(grep -cxF -- "$OLD" "$SRC")" = 1 ] || { echo "원격 호출 줄이 정확히 1개가 아님" >&2; exit 1; }
[ "$(grep -cx 'set -u' "$SRC")" = 1 ] || { echo "set -u 줄이 정확히 1개가 아님" >&2; exit 1; }
awk -v old="$OLD" -v new="$NEW" -v g="$HERE/test-guard.inc" -v sha="$FSHA" '
  NR==1 { print; print "# ⚠ 시험 사본(make-test-copy.sh 생성) — 운영에 쓰지 않는다. 원격 호출은 검증된 가짜 절대 경로로만 한다."; next }
  $0=="set -u" { print; while ((getline l < g) > 0) { gsub(/@@FAKE_SHA@@/, sha, l); print l }; next }
  $0==old { print new; next }
  { print }' "$SRC" > "$OUT"
chmod +x "$OUT"
# 검사: 주석이 아닌 줄에 원격 도구 이름·운영 별칭이 남지 않았는가
if grep -vE '^[[:space:]]*#' "$OUT" | grep -nE '(^|[^A-Za-z_])(ssh|scp|sftp|curl|wget|nc|telnet)([^A-Za-z_]|$)|cat-prod'; then
  echo "시험 사본에 원격 도구/운영 별칭이 남음" >&2; exit 1
fi
# docker 는 원격 명령 문자열(가짜에 인자 데이터로만 전달) 안에만 있어야 한다: AP= 줄과 설치 상태 remote 인자 줄 2개
[ "$(grep -vE '^[[:space:]]*#' "$OUT" | grep -c 'docker')" = 2 ] || { echo "docker 문자열 위치가 예상(2줄)과 다름" >&2; exit 1; }
echo "make-test-copy: $(basename "$SRC") → $OUT (가짜 해시 $FSHA)"
