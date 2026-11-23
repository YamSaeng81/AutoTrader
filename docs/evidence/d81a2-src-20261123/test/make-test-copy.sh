#!/usr/bin/env bash
# 생산용 D81A2 run.sh 에서 시험 사본을 기계적으로 만든다(v1.225 §3). 바뀌는 곳은 정확히 네 군데뿐이다:
#   ① 1행 뒤 시험 사본 표지 1줄  ② `set -u` 뒤 guard.inc(가짜 해시 고정)
#   ③ D=… 줄: 기본 경로·원격 도구 변수 제거(D4RUN 필수)  ④ 원격 호출 1줄 → 가짜 절대 경로 호출
# 사용: make-test-copy.sh <생산용 run.sh> <가짜 파일> <출력 시험 사본>
set -eu
SRC=$1; FK=$2; OUT=$3
HERE="$(cd "$(dirname "$0")" && pwd)"
FSHA=$(sha256sum < "$FK" | cut -d' ' -f1)
OLD3='D=${D4RUN:-/d/tmp/d81run}; B=$D/blocks-d81; SSH=${D4SSH:-ssh}'
NEW3='D=${D4RUN:?시험 사본은 D4RUN 필수}; B=$D/blocks-d81'
OLD4='$SSH -o BatchMode=yes cat-prod "bash -c $(printf '"'"'%q'"'"' "$s")" < /dev/null 2>&1 | tee -a "$o"'
NEW4='"$FAKE" "$TEST_HOST" "bash -c $(printf '"'"'%q'"'"' "$s")" < /dev/null 2>&1 | tee -a "$o"'
for L in "$OLD3" "$OLD4" 'set -u'; do
  [ "$(grep -cxF -- "$L" "$SRC")" = 1 ] || { echo "바꿀 줄이 정확히 1개가 아님: $L" >&2; exit 1; }
done
awk -v o3="$OLD3" -v n3="$NEW3" -v o4="$OLD4" -v n4="$NEW4" -v g="$HERE/guard.inc" -v sha="$FSHA" '
  NR==1 { print; print "# ⚠ 시험 사본(make-test-copy.sh 생성) — 운영에 쓰지 않는다. 원격 호출은 검증된 가짜 절대 경로로만 한다."; next }
  $0=="set -u" { print; while ((getline l < g) > 0) { gsub(/@@FAKE_SHA@@/, sha, l); print l }; next }
  $0==o3 { print n3; next }
  $0==o4 { print n4; next }
  { print }' "$SRC" > "$OUT"
chmod +x "$OUT"
# 검사: 주석이 아닌 줄에서 따옴표 안 문구를 지운 뒤 원격 도구 이름·운영 별칭·원격 도구 변수가 남지 않았는가
if grep -vE '^[[:space:]]*#' "$OUT" | sed -E "s/\"[^\"]*\"//g; s/'[^']*'//g" \
   | grep -nE '(^|[^A-Za-z_$])(ssh|scp|sftp|curl|wget|nc|telnet|docker)([^A-Za-z_]|$)|cat-prod|\$SSH|\$\{SSH'; then
  echo "시험 사본에 원격 도구/운영 별칭이 남음" >&2; exit 1
fi
grep -qF 'cat-prod' "$OUT" && { echo "시험 사본에 운영 별칭 문자열이 남음" >&2; exit 1; }
echo "make-test-copy: $(basename "$SRC") → $OUT (가짜 해시 $FSHA)"
