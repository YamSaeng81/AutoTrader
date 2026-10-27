#!/usr/bin/env bash
# preserve_logs_p0.sh 시험 (2026-10-27) — 리눅스(GNU coreutils)에서만, 가짜 로그로. 실행: bash scripts/ops/test_preserve_logs_p0.sh
# 운영 서버·운영 로그를 쓰지 않는다. /tmp 아래 임시 폴더만 만든다.
set -u
export LC_ALL=C
HERE=$(cd "$(dirname "$0")" && pwd -P)
SCRIPT="$HERE/preserve_logs_p0.sh"
TOKEN="SECRET_TOKEN_DO_NOT_PRINT"
pass=0; fail=0
check() { if [ "$2" = 0 ]; then pass=$((pass + 1)); echo "PASS $1"; else fail=$((fail + 1)); echo "FAIL $1 ${3:-}"; fi; }
is() { [ "$1" = "$2" ] && echo 0 || echo 1; }
has() { printf '%s' "$1" | grep -qF -- "$2" && echo 0 || echo 1; }
hasnt() { printf '%s' "$1" | grep -qF -- "$2" && echo 1 || echo 0; }

fresh() {   # 가짜 저장소: logs/backend 에 현재 파일 + 회전 파일 + 섞인 파일, backups/
  W=$(mktemp -d /tmp/p0test.XXXXXX)
  mkdir -p "$W/repo/logs/backend" "$W/repo/backups" "$W/home"
  L="$W/repo/logs/backend"
  for f in system.log strategy.log trade.log system.2026-10-01.log system.2026-10-02.log \
           strategy.2026-10-01.log trade.2026-10-26.log.gz; do
    printf '2026-10-01 07:50:16.000 ERROR x https://api.telegram.org/bot%s/sendMessage %s\n' "$TOKEN" "$f" > "$L/$f"
  done
  printf 'x\n' > "$L/system.2026-10-01.log.tmp"; printf 'x\n' > "$L/notes.txt"
  touch -d '2026-10-02 00:00:01' "$L/system.2026-10-01.log"
}
run() { (cd "$W/repo" && HOME="$W/home" bash "$SCRIPT" "$@" 2>&1); }

# 1. plan — 목록·크기·10-01 확인, 아무것도 만들지 않음, 내용 미출력
fresh
out=$(run plan logs/backend); rc=$?
check "plan 종료 0" "$(is $rc 0)" "$out"
check "plan: 회전 파일 4개만" "$(has "$out" 'FILES: 4개')" "$out"
check "plan: 현재 파일(system.log)·섞인 파일 제외" "$( printf '%s' "$out" | grep -qE '^  (system\.log|notes\.txt|system\.2026-10-01\.log\.tmp) ' && echo 1 || echo 0)" "$out"
check "plan: 10-01 파일 있음 확인" "$(has "$out" '10-01 파일(system.2026-10-01.log): 있음')" "$out"
check "plan: 디스크 여유·필요량 출력" "$(has "$out" '여유')" "$out"
check "plan: 로그 내용·토큰 미출력" "$(hasnt "$out" "$TOKEN")" "$out"
check "plan: 보존 폴더를 만들지 않음" "$( [ ! -e "$W/home/preserve" ] && echo 0 || echo 1)"

# 2. copy — 새 폴더(700)·파일(600), 해시 대조, 기록, 원본 그대로
before=$(cd "$W/repo/logs/backend" && sha256sum * | sort; stat -c '%n %Y %s' * | sort)
out=$(run copy logs/backend "$W/home/preserve/p0-logs-test"); rc=$?
D="$W/home/preserve/p0-logs-test"
check "copy 종료 0" "$(is $rc 0)" "$out"
check "copy: OK 4 / 4 · 불일치 0" "$(has "$out" 'OK 4 / 4 · 불일치 0 · 복사 실패 0 · SHA256SUMS 재검사 OK')" "$out"
check "copy: 폴더 700" "$(is "$(stat -c %a "$D")" 700)"
check "copy: 파일 600" "$(is "$(stat -c %a "$D"/*.log* "$D/SHA256SUMS" "$D/RECORD.txt" "$D/RESULTS.tsv" | sort -u)" 600)"
check "copy: 사본 = 원본 (sha256sum -c)" "$( (cd "$D" && sha256sum --quiet -c SHA256SUMS) >/dev/null 2>&1 && echo 0 || echo 1)"
check "copy: 현재 파일은 복사 안 함" "$( [ ! -e "$D/system.log" ] && [ ! -e "$D/notes.txt" ] && echo 0 || echo 1)"
check "copy: 수정 시각 보존(cp -p)" "$(is "$(stat -c %Y "$D/system.2026-10-01.log")" "$(stat -c %Y "$W/repo/logs/backend/system.2026-10-01.log")")"
after=$(cd "$W/repo/logs/backend" && sha256sum * | sort; stat -c '%n %Y %s' * | sort)
check "copy: 원본 그대로(내용·시각·크기)" "$(is "$before" "$after")"
rec=$(cat "$D/RECORD.txt")
check "기록: 수행자·시각(UTC·KST)·10-01·대조 결과" "$( printf '%s' "$rec" | grep -q '^수행자: ' && printf '%s' "$rec" | grep -q '+09:00' && printf '%s' "$rec" | grep -q '10-01 파일 있음' && printf '%s' "$rec" | grep -q 'OK 4 · 불일치 0' && echo 0 || echo 1)" "$rec"
check "기록: 검토 SHA 미기재 표시(P0_SHA 없을 때)" "$(has "$rec" '검토 SHA: 미기재')"
check "copy: 로그 내용·토큰 미출력" "$(hasnt "$out" "$TOKEN")"
check "기록 파일에도 토큰 없음" "$( grep -qF "$TOKEN" "$D/RECORD.txt" "$D/RESULTS.tsv" "$D/SHA256SUMS" && echo 1 || echo 0)"
check "RESULTS.tsv: 파일마다 원본·사본 해시와 OK" "$(is "$(tail -n +2 "$D/RESULTS.tsv" | awk -F'\t' '$3 == $4 && $5 == "OK"' | wc -l)" 4)"

# 3. 같은 폴더로 다시 → 거절(새 폴더만)
out=$(run copy logs/backend "$W/home/preserve/p0-logs-test"); rc=$?
check "이미 있는 폴더 → 종료 2" "$(is $rc 2)" "$out"
# 4. 정리 경로 안 → 거절
out=$(run copy logs/backend "$W/repo/logs/backend/keep"); rc=$?
check "로그 폴더 안 → 종료 2, 만들지 않음" "$( [ $rc = 2 ] && [ ! -e "$W/repo/logs/backend/keep" ] && echo 0 || echo 1)" "$out"
out=$(run copy logs/backend "$W/repo/backups/keep"); rc=$?
check "backups 안 → 종료 2, 만들지 않음" "$( [ $rc = 2 ] && [ ! -e "$W/repo/backups/keep" ] && echo 0 || echo 1)" "$out"
P0_SHA=abc1234 bash -c true
out=$(cd "$W/repo" && HOME="$W/home" P0_SHA=abc1234 bash "$SCRIPT" copy logs/backend "$W/home/preserve/p0-sha" 2>&1); rc=$?
check "P0_SHA 를 주면 기록에 남는다" "$(has "$(cat "$W/home/preserve/p0-sha/RECORD.txt")" '검토 SHA: abc1234')"
rm -rf "$W"

# 5. 10-01 파일 없음 → 보고하고 나머지는 복사
fresh; rm "$W/repo/logs/backend/system.2026-10-01.log"
out=$(run plan logs/backend)
check "10-01 없음 → plan 이 '없음' 보고" "$(has "$out" '10-01 파일(system.2026-10-01.log): 없음')" "$out"
out=$(run copy logs/backend "$W/home/preserve/p"); rc=$?
check "10-01 없음 → 나머지 3개 복사, 종료 0" "$( [ $rc = 0 ] && printf '%s' "$out" | grep -q 'OK 3 / 3' && echo 0 || echo 1)" "$out"
rm -rf "$W"

# 6. 디스크 여유 부족 → 복사 전 중단 (겉보기 크기가 큰 희소 파일)
fresh
avail=$(df -Pk "$W" | awk 'NR == 2 { print $4 * 1024 }')     # 여유 공간 크기의 희소 파일 → 필요량(× 2)이 여유를 넘는다
truncate -s "$avail" "$W/repo/logs/backend/trade.2026-09-30.log" || echo "희소 파일을 만들지 못했다"
out=$(run copy logs/backend "$W/home/preserve/p"); rc=$?
check "여유 부족 → 종료 2, 폴더 안 만듦" "$( [ $rc = 2 ] && [ ! -e "$W/home/preserve/p" ] && echo 0 || echo 1)" "$out"
rm -rf "$W"

# 7. 읽을 수 없는 파일 → plan 보고, copy 거절
fresh; chmod 000 "$W/repo/logs/backend/system.2026-10-02.log"
if [ "$(id -u)" != 0 ]; then
  out=$(run plan logs/backend)
  check "읽기 불가 → plan 보고(이름만)" "$(has "$out" '읽기 불가: 1개 — system.2026-10-02.log')" "$out"
  out=$(run copy logs/backend "$W/home/preserve/p"); rc=$?
  check "읽기 불가 → copy 종료 2, 폴더 안 만듦" "$( [ $rc = 2 ] && [ ! -e "$W/home/preserve/p" ] && echo 0 || echo 1)" "$out"
fi
chmod 600 "$W/repo/logs/backend/system.2026-10-02.log"; rm -rf "$W"

# 8. 해시 불일치 → 종료 3 (복사본을 바꾸는 가짜 cp)
fresh; mkdir "$W/bin"
cat > "$W/bin/cp" <<'EOF'
#!/usr/bin/env bash
/bin/cp "$@" && for last; do :; done && printf 'tampered\n' >> "$last"
EOF
chmod +x "$W/bin/cp"
out=$(cd "$W/repo" && HOME="$W/home" PATH="$W/bin:$PATH" bash "$SCRIPT" copy logs/backend "$W/home/preserve/p" 2>&1); rc=$?
check "사본이 원본과 다르면 종료 3·불일치 보고" "$( [ $rc = 3 ] && printf '%s' "$out" | grep -q '불일치 4' && echo 0 || echo 1)" "$out"
check "불일치 출력에도 토큰 없음" "$(hasnt "$out" "$TOKEN")"
rm -rf "$W"

# 9. 복사 실패 → 종료 4
fresh; mkdir "$W/bin"
cat > "$W/bin/cp" <<'EOF'
#!/usr/bin/env bash
case "$*" in *strategy.2026-10-01.log*) exit 1;; esac
exec /bin/cp "$@"
EOF
chmod +x "$W/bin/cp"
out=$(cd "$W/repo" && HOME="$W/home" PATH="$W/bin:$PATH" bash "$SCRIPT" copy logs/backend "$W/home/preserve/p" 2>&1); rc=$?
check "복사 실패 → 종료 4·실패 파일 보고" "$( [ $rc = 4 ] && printf '%s' "$out" | grep -q '복사 실패: strategy.2026-10-01.log' && echo 0 || echo 1)" "$out"
rm -rf "$W"

# 10. 잘못된 사용
out=$(bash "$SCRIPT" 2>&1); rc=$?
check "인자 없음 → 종료 2" "$(is $rc 2)"
fresh
out=$(run copy logs/backend); rc=$?
check "copy 에 폴더 없음 → 종료 2" "$(is $rc 2)" "$out"
rm -rf "$W"

echo
echo "$pass/$((pass + fail)) 통과"
[ "$fail" -eq 0 ]
