#!/usr/bin/env bash
# 대역 원문 복원·대조(총괄 v1.168 §3-6): <이름>-nojar.tar.gz 를 풀고, jar 사본 자리에 해시가 같은 원본 jar 를 복사한 뒤 <이름>-list.sha256 으로 전부 대조한다.
# 사용: restore.sh <이름 standin-v167-r1|standin-v168-r1> <출력 폴더> <원본 jar...>   (원본 jar 는 해시로 짝지음)
set -eu; N=$1; D=$2; shift 2; H=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$D"; tar xzf "$H/$N-nojar.tar.gz" -C "$D"
declare -A SRC; for j in "$@"; do SRC[$(sha256sum < "$j" | cut -c1-64)]=$j; done
while read -r h p; do [ -n "${SRC[$h]:-}" ] || { echo "원본 jar 없음: $h $p"; exit 4; }; mkdir -p "$D/$(dirname "$p")"; cp "${SRC[$h]}" "$D/$p"; done < "$H/$N-jar-refs.txt"
(cd "$D" && sha256sum -c --quiet "$H/$N-list.sha256") && echo "대조 $(wc -l < "$H/$N-list.sha256")/$(wc -l < "$H/$N-list.sha256") 일치"
