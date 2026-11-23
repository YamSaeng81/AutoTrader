#!/bin/bash
# 사용: run-a2.sh <키트 절대경로> <회차 폴더 절대경로> <python 디렉터리>
set -u
KIT=$1; RUN=$2; PYD=$3
mkdir -p "$RUN"; : > "$KIT/detect-calls.txt"; : > "$KIT/psql-calls.txt"
printf 'case\tseg\trc\tokU0\tsentinel_console\tfail_line\thash_match\tfile_kept\n' > "$RUN/results.tsv"
for spec in fix:ok fix:partial fix:empty fix:odir fix:vdir fix:fmt fix:r1 orig:ok orig:partial; do
  seg=${spec%%:*}; c=${spec#*:}; M="$RUN/$seg-$c/maint"; mkdir -p "$M"; echo 'SELECT 1;' > "$M/predeploy-read.sql"
  [ "$c" = odir ] && mkdir "$M/predeploy-read.txt"; [ "$c" = vdir ] && mkdir "$M/predeploy-verdict.txt"
  env -i HOME="$RUN" PATH="$KIT/detect:/usr/bin:/bin:$PYD" M="$M" PSQL="$KIT/fake-psql.sh" A2T_CASE="$c" A2T_KIT="$KIT" LANG=C.UTF-8 PYTHONUTF8=1 \
    /usr/bin/bash --noprofile --norc "$KIT/seg-$seg.sh" > "$RUN/$seg-$c/console.txt" 2>&1; rc=$?
  con="$RUN/$seg-$c/console.txt"; ok=$([ -e "$M/ok-U0" ] && echo yes || echo no)
  sen=$(grep -cE '987654\.321|987000\.111|555444\.333|SENTINEL_ERR' "$con")
  fl=$(grep -c '조회 실패(종료' "$con"); hm=-; fk=-
  if [ -f "$M/predeploy-read.txt" ]; then fk=yes; h=$(sha256sum < "$M/predeploy-read.txt" | cut -d' ' -f1); [ "$fl" -gt 0 ] && { grep -q "sha256 $h" "$con" && hm=yes || hm=no; }; else fk=no; fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$c" "$seg" "$rc" "$ok" "$sen" "$fl" "$hm" "$fk" >> "$RUN/results.tsv"
done
cp "$KIT/detect-calls.txt" "$KIT/psql-calls.txt" "$RUN/"
