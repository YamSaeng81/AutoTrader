#!/usr/bin/env bash
# N21 단독 적용안 v2 블록의 로컬 대역 — 서버·docker·cron·네트워크를 쓰지 않는다.
# 실행: bash standin/run.sh <저장소 경로> <출력 폴더>
# 대역: HOME=임시 폴더, ~/crypto_trader/AutoTrader = 저장소의 로컬 복제(1899b49 체크아웃, origin = 로컬 저장소 → fetch 는 네트워크를 쓰지 않음).
#       docker·crontab·pgrep 은 PATH 앞의 가짜 명령, date +%M 만 가짜 분(FAKE_MIN).
#       가짜 pgrep 은 고정 프로세스 목록(procs.txt, 명령줄 한 줄씩)에 블록의 FPAT 를 grep -E 로 적용한다. 목록에는 블록 원문을 담은 bash -c 명령줄이 들어 있다.
#       pgrep.rc 가 있으면 그 종료값(2·127 등 조회 실패)을 그대로 낸다.
#       git 은 checkout 호출만 calls.txt 에 적고 실제 git 으로 넘긴다.
# 대역 치환 2개: CRONH = 가짜 crontab 원문의 해시, SYSLOG = 임시 폴더의 가짜 시스템 기록(실제 /var/log/syslog 는 서버에만 있다).
# 판정: 사례마다 블록 종료값 + 통과/멈춤 이유(문구) + 실제 checkout 호출 수(누적)를 함께 단언한다.
set -u
REPO=$(cd "$1" && pwd); OUT=$2; mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd); B="$HERE/../blocks"
IMG=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1
OLD=1899b49f633f8aefdc66b15c42debdaba8b3852c; MID=99bd4eaadb0ff3037518c3cacbf6bb98de91bf4e; NEW=6379ee0b2a7527b8136d7fba27e83881980d8555
CRON_TXT='19 * * * * cd ~/crypto_trader/AutoTrader && /usr/bin/python3 scripts/prospective/start_66.py freshness --save >> scripts/prospective/freshness_runs.log 2>&1'
CRONH_FAKE=$(printf '%s\n' "$CRON_TXT" | sha256sum | cut -d' ' -f1)
CRONLINE='Nov 10 12:19:01 yhdeabba CRON[2297465]: (manicstr) CMD (cd ~/crypto_trader/AutoTrader && /usr/bin/python3 scripts/prospective/start_66.py freshness --save >> scripts/prospective/freshness_runs.log 2>&1)'
WATCHLINE='Nov 10 12:25:01 yhdeabba CRON[2300091]: (manicstr) CMD (cd ~/crypto_trader/AutoTrader && /usr/bin/python3 scripts/prospective/start_66.py watch --notify >> scripts/prospective/watch_runs.log 2>&1)'
RUNPY='4242 /usr/bin/python3 scripts/prospective/start_66.py freshness --save'
RUNSH='4241 /bin/sh -c cd ~/crypto_trader/AutoTrader && /usr/bin/python3 scripts/prospective/start_66.py freshness --save >> scripts/prospective/freshness_runs.log 2>&1'
PASS=0; FAILN=0

mkenv() {   # → 새 HOME
  local H A; H=$(mktemp -d); mkdir -p "$H/bin" "$H/crypto_trader"
  A="$H/crypto_trader/AutoTrader"
  git -c core.autocrlf=false clone -q --shared --no-checkout "$REPO" "$A"
  git -C "$A" -c core.autocrlf=false -c advice.detachedHead=false checkout -q "$OLD"
  git -C "$A" config core.autocrlf false
  printf '%s\n' "$CRON_TXT" > "$H/cron.txt"; echo "$IMG" > "$H/img.txt"; : > "$H/calls.txt"
  printf 'Nov 10 11:19:01 yhdeabba CRON[1]: (manicstr) CMD (이전 회차 — 기준 위치 앞)\n%s\n' "$WATCHLINE" > "$H/syslog"
  echo '{"at": "2026-11-10T00:19:30+00:00", "green": 22, "unknown": 0, "red": 0, "redCoins": [], "unknownCoins": [], "rechecked": [], "rows": {"KRW-BTC": {"dbTo": "x", "exTo": "x", "exErr": null, "count": 500, "rechecked": false}}}' > "$A/scripts/prospective/freshness_log.jsonl"
  printf 'freshness 저장(이전 회차)\n' > "$A/scripts/prospective/freshness_runs.log"
  # 고정 프로세스 목록: 블록 원문을 담은 bash -c(한 줄로 펼침), 다른 하위 명령, python 이 아닌 명령
  { printf '9001 bash -c '; cat "$B/vars.sh" "$B/A0.sh" "$B/A1.sh" "$B/A1-rollback.sh" "$B/A2.sh" | tr '\n' ' '; echo
    echo '9002 /usr/bin/python3 scripts/prospective/start_66.py alerts --days 1 --no-mark'
    echo '9003 less scripts/prospective/start_66.py freshness 메모'; } > "$H/procs.txt"
  cat > "$H/bin/docker" <<EOS
#!/usr/bin/env bash
case "\$1" in compose) echo cid-backend ;; inspect) cat "$H/img.txt" ;; *) echo "가짜 docker: \$*" >&2; exit 9 ;; esac
EOS
  cat > "$H/bin/crontab" <<EOS
#!/usr/bin/env bash
[ "\$1" = -l ] && cat "$H/cron.txt" || { echo "가짜 crontab: 쓰기 거절" >&2; exit 9; }
EOS
  cat > "$H/bin/pgrep" <<EOS
#!/usr/bin/env bash
# 가짜 pgrep -af <패턴> — procs.txt 의 각 명령줄('PID 명령줄')에서 명령줄 부분에 패턴을 적용한다
if [ -e "$H/pgrep.rc" ]; then echo "pgrep: 대역 조회 실패" >&2; exit \$(cat "$H/pgrep.rc"); fi
[ "\$1" = -af ] || { echo "가짜 pgrep: 모르는 인자 \$*" >&2; exit 2; }
hit=0
while IFS= read -r l; do printf '%s\n' "\${l#* }" | grep -qE -- "\$2" && { printf '%s\n' "\$l" | cut -c1-120; hit=1; }; done < "$H/procs.txt"
[ \$hit = 1 ] && exit 0 || exit 1
EOS
  cat > "$H/bin/date" <<EOS
#!/usr/bin/env bash
[ "\$*" = "+%M" ] && [ -n "\${FAKE_MIN:-}" ] && { echo "\$FAKE_MIN"; exit 0; }
exec /usr/bin/date "\$@"
EOS
  chmod +x "$H/bin/"*
  echo "$H"
}

blk() {   # $1 HOME, $2 블록 — vars.sh 를 넣은 셸에서 블록 하나를 실행. 종료값 = 블록 종료값
  local H=$1 b=$2
  local s="source '$B/vars.sh'; CRONH=$CRONH_FAKE; SYSLOG='$H/syslog'"
  s="$s; git() { [ \"\$1\" = checkout ] && echo \"checkout \$*\" >> '$H/calls.txt'; command git \"\$@\"; }; source '$B/$b.sh'"
  HOME="$H" PATH="$H/bin:$PATH" FAKE_MIN="${FAKE_MIN:-30}" bash -c "$s" 2>&1
}

# 사례 하나: $1 이름, $2 HOME, $3 블록, $4 기대 종료값, $5 기대 문구, $6 기대 누적 checkout 수
case_() {
  local name=$1 H=$2 b=$3 wrc=$4 want=$5 wco=$6 out rc co
  out=$(blk "$H" "$b"); rc=$?
  co=$(grep -c '^checkout' "$H/calls.txt")
  { printf '%s\n' "$out"; echo "[대역] 블록 $b · 종료 $rc · 누적 checkout $co · HEAD $(git -C "$H/crypto_trader/AutoTrader" rev-parse HEAD)"; } > "$OUT/$name.out"
  if [ "$rc" = "$wrc" ] && printf '%s' "$out" | grep -qF -- "$want" && [ "$co" = "$wco" ]; then
    PASS=$((PASS+1)); echo "PASS $name — 종료 $rc · '$want' · checkout $co"
  else FAILN=$((FAILN+1)); echo "FAIL $name — 종료 $rc(기대 $wrc) · 문구 '$want' · checkout $co(기대 $wco)"; fi
}
head_is() {  # $1 이름, $2 HOME, $3 기대 HEAD
  local h; h=$(git -C "$2/crypto_trader/AutoTrader" rev-parse HEAD)
  if [ "$h" = "$3" ]; then PASS=$((PASS+1)); echo "PASS $1 — HEAD ${3:0:8}"; else FAILN=$((FAILN+1)); echo "FAIL $1 — HEAD $h(기대 $3)"; fi
}
cycle() {  # $1 HOME, $2 exErr, $3 exTo, $4 실행 기록 덧붙임 — 적용 뒤 :19 회차 하나를 흉내(시작 줄·행·실행 기록)
  local A="$1/crypto_trader/AutoTrader/scripts/prospective"
  [ "${NOCRON:-}" = 1 ] || printf '%s\n' "$CRONLINE" >> "$1/syslog"
  echo "{\"at\": \"2099-01-01T00:19:30+00:00\", \"green\": 21, \"unknown\": 1, \"red\": 0, \"redCoins\": [], \"unknownCoins\": [\"KRW-BTC\"], \"rechecked\": [\"KRW-BTC\"], \"rows\": {\"KRW-BTC\": {\"dbTo\": \"x\", \"exTo\": $3, \"exErr\": $2, \"count\": 500, \"rechecked\": true}}}" >> "$A/freshness_log.jsonl"
  printf '%b' "${4:-freshness 저장\n}" >> "$A/freshness_runs.log"
}
applied() {  # A0·A1 을 통과시킨 HOME
  local H; H=$(mkenv); blk "$H" A0 > /dev/null; blk "$H" A1 > /dev/null; echo "$H"
}

# v01 정상: A0 → A1 → (회차 전) A2 미확인 → 회차 → A2 → 되돌림. 프로세스 목록의 블록 원문 bash -c 는 회차로 잡히지 않는다
H=$(mkenv)
case_ v01-a0 "$H" A0 0 "✅ A0 통과" 0
case_ v01-a1 "$H" A1 0 "✅ A1 통과" 1
head_is v01-head-new "$H" "$NEW"
case_ v01-a2-before-cycle "$H" A2 1 "미확인: 적용 뒤 cron 시작 줄이 없다" 1
cycle "$H" '"net"' null
case_ v01-a2 "$H" A2 0 "✅ A2" 1
case_ v01-rollback "$H" A1-rollback 0 "↩ 되돌림 끝" 2
head_is v01-head-old "$H" "$OLD"; rm -rf "$H"

# 프로세스 조회 0/1/그 밖
H=$(mkenv); printf '%s\n%s\n' "$RUNSH" "$RUNPY" >> "$H/procs.txt"
case_ v02-a0-running "$H" A0 1 "회차가 아직 돌고 있다" 0; rm -rf "$H"
H=$(mkenv); echo 2 > "$H/pgrep.rc"
case_ v03-a0-pgrep-error "$H" A0 1 "프로세스 조회 실패 — 회차 없음으로 보지 않는다" 0; rm -rf "$H"
H=$(mkenv); blk "$H" A0 > /dev/null; printf '%s\n' "$RUNPY" >> "$H/procs.txt"
case_ v04-a1-running "$H" A1 1 "회차가 돌고 있다(아직 바꾼 것 없음)" 0
head_is v04-head "$H" "$OLD"; rm -rf "$H"
H=$(mkenv); blk "$H" A0 > /dev/null; echo 2 > "$H/pgrep.rc"
case_ v05-a1-pgrep-error "$H" A1 1 "프로세스 조회 실패(아직 바꾼 것 없음)" 0
head_is v05-head "$H" "$OLD"; rm -rf "$H"
H=$(mkenv); blk "$H" A0 > /dev/null; echo 127 > "$H/pgrep.rc"
case_ v06-a1-pgrep-missing "$H" A1 1 "프로세스 조회 실패(아직 바꾼 것 없음)" 0; rm -rf "$H"
H=$(mkenv); blk "$H" A0 > /dev/null; printf '%s\n' "$RUNSH" >> "$H/procs.txt"
case_ v07-a1-sh-shell-only "$H" A1 0 "✅ A1 통과" 1; rm -rf "$H"   # python 이 끝나고 sh 껍데기만 남은 순간은 회차로 잡지 않는다(패턴 경계 확인)

# 시각·기록 경계
H=$(mkenv); blk "$H" A0 > /dev/null
FAKE_MIN=19 case_ v08-a1-window "$H" A1 1 "매시 :14~:24" 0; rm -rf "$H"
H=$(mkenv); rm "$H/syslog"
case_ v09-a0-no-syslog "$H" A0 1 "읽을 수 없다 — 적용하지 않고 보고" 0; rm -rf "$H"
H=$(mkenv); blk "$H" A0 > /dev/null; rm "$H/crypto_trader/AutoTrader/scripts/prospective/freshness_runs.log"
case_ v10-a1-no-runlog "$H" A1 1 "기준 위치를 기록하지 못했다(아직 바꾼 것 없음)" 0; rm -rf "$H"

# A2 구간별 판정
H=$(applied); NOCRON=1 cycle "$H" null '"x"'
case_ v11-a2-no-cron "$H" A2 1 "미확인: 적용 뒤 cron 시작 줄이 없다" 1; rm -rf "$H"
H=$(applied); cycle "$H" null '"x"' 'Traceback (most recent call last):\n  File "start_66.py"\nValueError: 대역\n'
case_ v12-a2-new-traceback "$H" A2 1 "이상: 적용 뒤 실행 기록에 새 Traceback" 1; rm -rf "$H"
H=$(mkenv); printf 'Traceback (most recent call last):\nOLD_ERROR 적용 전\n' >> "$H/crypto_trader/AutoTrader/scripts/prospective/freshness_runs.log"
blk "$H" A0 > /dev/null; blk "$H" A1 > /dev/null; cycle "$H" null '"x"'
case_ v13-a2-old-traceback-only "$H" A2 0 "✅ A2" 1; rm -rf "$H"
H=$(applied); cycle "$H" null '"x"'; mv "$H/syslog" "$H/syslog.1"; printf '%s\n' "$CRONLINE" > "$H/syslog"
case_ v14-a2-syslog-rotated "$H" A2 1 "미확인: 시스템 기록이 교체·축소됨" 1; rm -rf "$H"
H=$(applied); cycle "$H" null '"x"'; rm "$H/syslog"
case_ v15-a2-syslog-unreadable "$H" A2 1 "미확인: 시스템 기록" 1; rm -rf "$H"
H=$(applied); printf '%s\n' "$CRONLINE" >> "$H/syslog"
case_ v16-a2-no-row "$H" A2 1 "미확인: 적용 뒤 새 관측 행이 없다" 1; rm -rf "$H"
H=$(applied); cycle "$H" '"net"' '"x"'
case_ v17-a2-n21-state "$H" A2 1 "N21 상태 행" 1; rm -rf "$H"
H=$(applied); cycle "$H" null '"x"'; printf '%s\n' "$RUNPY" >> "$H/procs.txt"
case_ v18-a2-running "$H" A2 1 "회차가 아직 돈다" 1; rm -rf "$H"
H=$(applied); cycle "$H" null '"x"'; echo 3 > "$H/pgrep.rc"
case_ v19-a2-pgrep-error "$H" A2 1 "프로세스 조회 실패" 1; rm -rf "$H"
H=$(applied); cycle "$H" null '"x"'; echo sha256:1111 > "$H/img.txt"
case_ v20-a2-image "$H" A2 1 "이미지가 바뀌었다" 1; rm -rf "$H"

# 되돌림 경계
H=$(applied); git -C "$H/crypto_trader/AutoTrader" -c advice.detachedHead=false checkout -q "$MID"
case_ v21-rb-unexpected-head "$H" A1-rollback 1 "어느 것도 아님" 1
head_is v21-head "$H" "$MID"; rm -rf "$H"
H=$(applied); echo 2 > "$H/pgrep.rc"
case_ v22-rb-pgrep-error "$H" A1-rollback 1 "프로세스 조회 실패" 1
head_is v22-head "$H" "$NEW"; rm -rf "$H"
H=$(applied); printf '%s\n' "$RUNPY" >> "$H/procs.txt"
case_ v23-rb-running "$H" A1-rollback 1 "회차가 돌고 있다" 1; rm -rf "$H"
H=$(applied); echo sha256:2222 > "$H/img.txt"
case_ v24-rb-image "$H" A1-rollback 1 "이미지가 426a09a2… 가 아님" 1; rm -rf "$H"
H=$(applied); echo "# 다른 줄" >> "$H/cron.txt"
case_ v25-rb-cron "$H" A1-rollback 1 "cron 이 A0 때와 다름" 1; rm -rf "$H"
H=$(applied); echo "# y" >> "$H/crypto_trader/AutoTrader/scripts/prospective/start_66.py"
case_ v26-rb-dirty "$H" A1-rollback 1 "덮어쓰지 않고 보고" 1; rm -rf "$H"
H=$(mkenv); blk "$H" A0 > /dev/null
case_ v27-rb-already-old "$H" A1-rollback 0 "체크아웃하지 않고 원문만 대조" 0
head_is v27-head "$H" "$OLD"; rm -rf "$H"

echo "대역 단언 $PASS/$((PASS+FAILN)) 통과" | tee "$OUT/summary.txt"
[ "$FAILN" = 0 ]
