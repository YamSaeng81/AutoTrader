#!/bin/bash
# v1.169 §3-3 — 기존 ~/maint-v161 전체를 다른 이름으로 보존하고 원본 해시를 대조한다. 새 이름이 이미 있거나 maint-v167/v168 이 있으면 아무것도 바꾸지 않고 멈춘다.
set -u
L=${1:?로컬 출력 폴더}; mkdir -p "$L"; [ ! -e "$L/keep-v161.txt" ] || { echo "이미 있는 출력"; exit 2; }
ssh -o BatchMode=yes cat-prod 'set -u; K=~/maint-v161-pre-v168-20261116
echo "서버 $(date "+%F %T %Z")"
echo "== 보존 전 목록"; ls -ld ~/maint-v16* ~/maint-v161-* 2>&1
for d in ~/maint-v167 ~/maint-v168; do [ ! -e "$d" ] || { echo "🔴 멈춤: $d 가 이미 있다 — 덮어쓰거나 임의 재개하지 않는다"; exit 3; }; done
[ -d ~/maint-v161 ] || { echo "🔴 멈춤: ~/maint-v161 없음"; exit 3; }
[ ! -e "$K" ] || { echo "🔴 멈춤: $K 가 이미 있다"; exit 3; }
echo "== 원본 숨김·하위 폴더 확인"; ls -la ~/maint-v161 | head -50
(cd ~/maint-v161 && find . -type f | sort | xargs -d "\n" sha256sum) > /tmp/v161-orig.sha
cp -a ~/maint-v161 "$K" || { echo "🔴 복사 실패"; exit 4; }
(cd "$K" && find . -type f | sort | xargs -d "\n" sha256sum) > /tmp/v161-keep.sha
echo "== 원본 파일 $(wc -l < /tmp/v161-orig.sha)개 · 사본 $(wc -l < /tmp/v161-keep.sha)개"
cmp /tmp/v161-orig.sha /tmp/v161-keep.sha && echo "✅ 원본·사본 sha256 목록 같음" || { echo "🔴 원본·사본 다름"; diff /tmp/v161-orig.sha /tmp/v161-keep.sha; exit 5; }
echo "== 원본 sha256 목록"; cat /tmp/v161-orig.sha; rm -f /tmp/v161-orig.sha /tmp/v161-keep.sha
echo "== 보존 후 목록"; ls -ld ~/maint-v16* ~/maint-v161-* 2>&1' </dev/null > "$L/keep-v161.txt" 2>&1
rc=$?; echo "rc=$rc" >> "$L/keep-v161.txt"; cat "$L/keep-v161.txt"; exit $rc
