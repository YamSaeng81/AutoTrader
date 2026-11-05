#!/bin/bash
# 사용: runall.sh <실행이름> — 원문 F1·제안 F1 각각 9사례
set -u; cd "$(dirname "$0")"; R=runs/$1; [ ! -e "$R" ] || { echo "이미 있는 실행 폴더 $R — 덮어쓰지 않는다"; exit 2; }; mkdir -p "$R/src"
cp harness.sh runall.sh f1-orig.sh f1-prop.sh f2-orig.sh F-orig.sh add.txt "$R/src/"; cp -r fixtures "$R/src/"
( cd "$R/src" && sha256sum harness.sh runall.sh f1-orig.sh f1-prop.sh f2-orig.sh F-orig.sh add.txt fixtures/* | sed 's/ \*/  /' ) > "$R/inputs.sha256"
date '+시작 %F %T' > "$R/time.txt"
for v in orig prop; do for c in normal marker_bad marker_old shell_bad sql_bad marker_missing shell_missing sql_missing sql_unreadable; do
  ./harness.sh f1-$v.sh $c "$R/$v-$c" | sed "s/^/$v /"
done; done | tee "$R/summary.txt"
echo "끝 $(date "+%F %T")" >> "$R/time.txt"
