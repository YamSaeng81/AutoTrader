#!/bin/bash
# 실제 서버 collect_pins 형식 확인 1회(총괄 v1.251 §3-2). batch.sh 를 source/실행하지 않고 pins.sh 만 불러 collect_pins 한 번.
# 대상 회차 고정 20261125-1000(이미 생성된 log.gz) — 없으면 중단, 다른 날짜로 재시도하지 않는다. 출력 폴더가 이미 있으면 중단.
# 반출: pins.txt(해시·상태 4필드·집계 수)·pins.txt.err·collect-rc.txt·format.txt 뿐. 풀어 둔 로그는 collect_pins 가 지운다.
set -u
BASE=$HOME/work-batches/d81b-20261127-exp1; BDIR=$BASE/batch-r2; O=$BASE/pins-format-20261125-1000
DCK=$HOME/daily-check; TAG=20261125-1000
[ ! -e "$O" ] || { echo "🔴 $O 가 이미 있다 — 중단"; exit 9; }
[ -f "$DCK/$TAG.txt.log.gz" ] || { echo "🔴 대상 $TAG.txt.log.gz 없음 — 중단(다른 회차로 재시도 안 함)"; exit 8; }
mkdir "$O" || exit 4
. "$BDIR/pins.sh" || { echo "🔴 pins.sh 읽기 실패"; exit 2; }
echo "[형식 확인 시작] $(date -u '+%F %T')Z 대상=$DCK/$TAG.txt.log.gz $(stat -c '%s %y' "$DCK/$TAG.txt.log.gz")"
rc=0; collect_pins "$O/pins.txt" || rc=$?
echo "collect_pins rc=$rc stderr=$(stat -c %s "$O/pins.txt.err")B" > "$O/collect-rc.txt"
ls "$O"; [ ! -e "$O/pins.txt.tmp.log" ] && [ ! -e "$O/pins.txt.tmp.cron" ] && echo "임시 로그·cron 사본 지움" || echo "🔴 임시 파일 남음"
cat "$O/collect-rc.txt"; echo "== pins.txt"; cat "$O/pins.txt"; echo "== pins.txt.err"; cat "$O/pins.txt.err"
fr=0; python3 -I "$BDIR/pinscheck.py" "$O" > "$O/format.txt" 2>&1 || fr=$?
echo "== format (rc=$fr)"; cat "$O/format.txt"
( cd "$O" && sha256sum -- * > SHA256SUMS ) && echo "SHA256SUMS $(sha256sum < "$O/SHA256SUMS" | cut -d' ' -f1)"
echo "[형식 확인 끝] $(date -u '+%F %T')Z collect=$rc format=$fr"
[ "$rc" = 0 ] && [ "$fr" = 0 ]
