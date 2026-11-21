#!/usr/bin/env bash
# 병행 감시 실제 읽기 1회 실행안 pm-read-2 (총괄 v1.216 §3-2, G2·G3 보완). **미실행 초안 — 별도 승인 전 실행 금지.** pm-read-1(pm-read.sh 7f07c470)은 보존한다.
# 개발 PC 에서 실행한다. 서버에는 아무것도 쓰지 않는다(SSH 표준 출력만 로컬로 받는다). DB 는 READ ONLY 트랜잭션 + ROLLBACK, PGOPTIONS 보호값.
#   bash pm-read-2.sh read <보존폴더 경로(이름 pm-read-YYYYMMDD-HHMM, 없어야 함)> <SINCE: 보고 명목 시각의 UTC 예 2026-11-22T13:00:00Z>
#   bash pm-read-2.sh post <보존폴더 경로> [n03-values.json]
# 기록·종료값 규칙(D81D4R 실행 틀 run.sh 084cd60d 의 검토된 계약을 따른다):
#   필수 기록 = 미완료 표지 INCOMPLETE.txt(맨 처음) · 각 원격 출력/표준오류 · run-time · 해시 목록 · read-status.txt(완료 줄, 맨 마지막).
#   모든 기록이 성공한 뒤에만 미완료 표지를 지운다. 기록 실패 → 종료 4(원래 실패값이 비영이면 그 값 유지), 표지 남김, 자동 재시도 없음.
#   미완료 표지가 남은 폴더는 post 도 거절한다(종료 2). 같은 폴더로 다시 읽지 않는다.
# read 종료값: 0 READ_OK · 6 READ_LOG_FAILED(상태 읽기는 정상, 로그 수집 실패 → post 에서 P0 검사 미완료) · 3 STOP_PIN(HEAD·이미지·상태·재시작·Flyway·서버 시각 불일치/조회 불명)
#              · 5 STOP_SQL_STDERR · 원격/SQL 실패는 그 rc 그대로(예 255 SSH, 1 psql) · 4 기록 실패 · 2 시작 전 거절
# post 종료값: 0 P0 없음+변환 CONVERTED · 10 P0_PRESENT(변환 결과와 함께 보고) · 20 수집 불통과(P0 검사 미완료 포함) · 30 INVALID · 40 ERROR · 4 기록 실패 · 2 거절
# 시각 축: SINCE·UNTIL 은 **서버 UTC**(UNTIL 은 서버 date -u 결과). PC 시각은 run-time.txt 에 따로 남기고 구간 근거로 쓰지 않는다.
# 원문 로그: local-only/ 에만 보존(.gitignore). 공개 제출에는 local-only-SHA256SUMS(해시)·구간·rc·판정 요약만.
set -u
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
FU="$(dirname "$SELF_DIR")"; EVI="$(dirname "$FU")"
SQL="$FU/sql/state-3.sql";                 SQL_SHA=6d71df61a59d144afe9cfa1affc250c6e219c6195612aa97d2580fa8de1bec6b
CONV="$FU/candidate/convert_state_4.py";   CONV_SHA=1416564e5df04cc7b59b5e4eaafce50a096c02a7ce333dd661ffb8d8be8edfcd
AUXW="$FU/candidate/make_aux2.py";         AUXW_SHA=bde58ccb40af3a18d65d624b98edb5065486fee4f082d366291e16a2f5c50cbd
P0T="$EVI/parallel-monitor-check-20261121/candidate/p0_input.py"; P0T_SHA=3fdd580a2008af692e9448ed0766f1a1a058ae3aaf17128352911f8d403d1f66
UV="$EVI/d81d4r-20261120/d4/blocks-d81/U변수.sh";               UV_SHA=efb96108113142132da3685669c1a034e7a3050bc891bbacbecd2891137b19fd
EXP_HEAD=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d
EXP_IMG=sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f
EXP_STATUS=running; EXP_RESTART=0; EXP_FLYWAY=96
PGO="-c statement_timeout=15s -c lock_timeout=2s"
AP="cd ~/crypto_trader/AutoTrader && docker compose -f docker-compose.prod.yml"
deny() { echo "🔴 거절(종료 2): $* — 원격 호출 0회"; exit 2; }
hashok() { [ "$(sha256sum < "$1" | cut -d' ' -f1)" = "$2" ]; }
hashok "$SQL" "$SQL_SHA" && hashok "$CONV" "$CONV_SHA" && hashok "$AUXW" "$AUXW_SHA" && hashok "$P0T" "$P0T_SHA" && hashok "$UV" "$UV_SHA" \
  || deny "고정 해시 불일치(SQL·변환기·aux 작성기·P0 도구·U변수 중 하나)"
MODE=${1:-}; L=${2:-}
[ -n "$L" ] || deny "보존폴더 인자 없음"
case "$(basename "$L")" in pm-read-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]) ;; *) deny "보존폴더 이름 형식(pm-read-YYYYMMDD-HHMM)";; esac
I="$L/INCOMPLETE.txt"; WR=0
rec() { printf '%s\n' "$2" | tee -a "$1" > /dev/null; }           # 기록 한 줄, 실패면 비영
utc() { date -u -d "$1" +%s 2>/dev/null; }
# remote <출력> <표준오류> <표준입력> <원격 명령> → RRC(원격 rc), 기록 실패면 WR=1
remote() {
  ssh -o BatchMode=yes cat-prod "$4" < "$3" 2> "$2" | tee "$1" > /dev/null
  local ps=("${PIPESTATUS[@]}"); RRC=${ps[0]}
  { [ "${ps[1]}" = 0 ] && [ -f "$2" ]; } || WR=1
  rec "$L/rc.txt" "$(basename "$1") rc=$RRC" || WR=1
}
# finish <상태> <종료값> — 해시 목록 → 완료 줄 → 표지 제거. 실패 시 원래 값 유지(0이면 4)
finish() {
  local st=$1 code=$2
  rec "$L/run-time.txt" "pc-end $(date -u +%FT%TZ)" || WR=1
  # 로그를 받기 전에 멈추면 local-only 가 비어 있다 — 빈 목록을 정상 기록으로 쓴다(fake-ssh-run1 에서 `sha256sum -- *` 실패로 멈춤 5건이 기록 실패가 된 결함 보완)
  if [ -d "$L/local-only" ]; then (cd "$L/local-only" && find . -type f | sed 's|^\./||' | sort | xargs -r sha256sum > ../local-only-SHA256SUMS) || WR=1; fi
  (cd "$L" && find . -path ./local-only -prune -o -type f ! -name read-SHA256SUMS ! -name read-status.txt ! -name INCOMPLETE.txt -print \
     | sed 's|^\./||' | sort | xargs -r sha256sum > read-SHA256SUMS) || WR=1
  [ "$WR" = 0 ] && rec "$L/read-status.txt" "$st exit=$code" || WR=1
  [ "$WR" = 0 ] && rm -f "$I" && [ ! -e "$I" ] || WR=1
  if [ "$WR" != 0 ]; then
    local fr=4; [ "$code" = 0 ] || fr=$code
    echo "🔴 기록 실패 — 원래 결과 $st(종료 $code) · 로컬 기록 실패=1 · 이 실행 종료=$fr. 미완료 표지를 남겼다: 원격 상태 확인·보고 전 재실행 금지(자동 재시도 없음)"
    exit "$fr"
  fi
  echo "read 끝 — $st (종료 $code). 같은 폴더로 다시 읽지 않는다."
  exit "$code"
}

if [ "$MODE" = read ]; then
  SINCE=${3:-}
  [[ "$SINCE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] && [ -n "$(utc "$SINCE")" ] || deny "SINCE 형식(서버 UTC …Z)"
  [ ! -e "$L" ] || deny "보존폴더가 이미 있음 — 같은 승인으로 다시 읽지 않는다"
  mkdir "$L" || { echo "🔴 기록 실패(종료 4): 보존폴더 생성 실패 — 원격 호출 0회"; exit 4; }
  rec "$I" "미완료 — pm-read-2 read 시작 $(date -u +%FT%TZ)(PC) SINCE=$SINCE" || { rm -rf "$L"; echo "🔴 기록 실패(종료 4): 미완료 표지 — 원격 호출 0회"; exit 4; }
  { printf 'local-only/\n' > "$L/.gitignore" && mkdir "$L/local-only" && cp "$SQL" "$L/state-3.sql" \
    && rec "$L/run-time.txt" "pc-start $(date -u +%FT%TZ)" && rec "$L/range-args.txt" "SINCE $SINCE"; } \
    || { echo "🔴 기록 실패(종료 4): 시작 기록 — 원격 호출 0회, 미완료 표지 남김"; exit 4; }
  # 1) 서버 UTC 시각 + 설치 상태(읽기만)
  remote "$L/install_state.txt" "$L/install_state.stderr" /dev/null \
    "echo \"server_utc \$(date -u +%FT%TZ)\"; cd ~/crypto_trader/AutoTrader && echo \"HEAD \$(git rev-parse HEAD)\" && c=\$(docker compose -f docker-compose.prod.yml ps -q backend) && docker inspect --format 'backend Image={{.Image}} Status={{.State.Status}} RestartCount={{.RestartCount}} StartedAt={{.State.StartedAt}}' \$c"
  [ "$RRC" = 0 ] || finish STOP_REMOTE_INSTALL_STATE "$RRC"
  [ "$WR" = 0 ] || finish RECORD_FAILED_INSTALL_STATE 0
  F="$L/install_state.txt"
  SU=$(grep -cE '^server_utc [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' "$F"); HDn=$(grep -cE '^HEAD [0-9a-f]{40}$' "$F")
  BLn=$(grep -cE '^backend Image=[^ ]+ Status=[^ ]+ RestartCount=[0-9]+ StartedAt=[^ ]+$' "$F")
  [ "$SU" = 1 ] && [ "$HDn" = 1 ] && [ "$BLn" = 1 ] || finish STOP_PIN_UNREADABLE 3
  S0=$(sed -n 's/^server_utc //p' "$F"); HD=$(sed -n 's/^HEAD //p' "$F")
  BL=$(grep '^backend ' "$F"); IMG=${BL#*Image=}; IMG=${IMG%% *}; STS=${BL#*Status=}; STS=${STS%% *}
  RST=${BL#*RestartCount=}; RST=${RST%% *}; ST=${BL#*StartedAt=}
  PIN="HEAD $([ "$HD" = "$EXP_HEAD" ] && echo 일치 || echo 불일치) · 이미지 $([ "$IMG" = "$EXP_IMG" ] && echo 일치 || echo 불일치) · 상태 $STS · 재시작 $RST"
  rec "$L/pins.txt" "$PIN" || WR=1
  [ "$HD" = "$EXP_HEAD" ] && [ "$IMG" = "$EXP_IMG" ] && [ "$STS" = "$EXP_STATUS" ] && [ "$RST" = "$EXP_RESTART" ] || finish STOP_PIN_MISMATCH 3
  [ -n "$(utc "$S0")" ] && [ -n "$(utc "$ST")" ] && [ "$(utc "$SINCE")" -lt "$(utc "$S0")" ] || finish STOP_SERVER_TIME 3
  rec "$L/range-args.txt" "SERVER_START $S0" || WR=1
  [ "$WR" = 0 ] || finish RECORD_FAILED_PINS 0
  # 2) 상태 SQL 1회(표준 입력 -f -, PGOPTIONS 보호값 — 격리 PG pg-run1 에서 같은 형식 확인)
  remote "$L/state.tsv" "$L/state.stderr" "$L/state-3.sql" "$AP exec -T -e PGOPTIONS='$PGO' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -"
  SRC_=$RRC; rec "$L/state.rc" "$SRC_" || WR=1
  [ "$SRC_" = 0 ] || finish STOP_SQL_FAILED "$SRC_"
  [ "$WR" = 0 ] || finish RECORD_FAILED_SQL 0
  [ ! -s "$L/state.stderr" ] || finish STOP_SQL_STDERR 5
  FW=$(head -n1 "$L/state.tsv" | awk -F'\t' '$1=="META" && $2=="pm-state-3" {print $5}')
  rec "$L/pins.txt" "Flyway ${FW:-조회 불명}" || WR=1
  [ "$FW" = "$EXP_FLYWAY" ] || finish STOP_PIN_MISMATCH 3
  # 3) 로그 끝 시각(서버 UTC) → P0 원천 로그(local-only)
  remote "$L/server-until.txt" "$L/server-until.stderr" /dev/null 'date -u +%FT%TZ'
  [ "$RRC" = 0 ] || finish STOP_REMOTE_UNTIL "$RRC"
  UNTIL=$(grep -xE '[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z' "$L/server-until.txt")
  [ -n "$UNTIL" ] && [ "$(utc "$UNTIL")" -ge "$(utc "$S0")" ] || finish STOP_SERVER_TIME 3
  rec "$L/range-args.txt" "UNTIL $UNTIL" || WR=1
  [ "$WR" = 0 ] || finish RECORD_FAILED_UNTIL 0
  remote "$L/local-only/backend.log" "$L/local-only/backend.stderr" /dev/null "$AP logs --no-color --since $SINCE --until $UNTIL backend"
  CRC=$RRC; rec "$L/collect.rc" "$CRC" || WR=1
  [ "$WR" = 0 ] || finish RECORD_FAILED_LOG 0
  [ "$CRC" = 0 ] || finish READ_LOG_FAILED 6
  finish READ_OK 0
fi

if [ "$MODE" = post ]; then
  [ -d "$L" ] || deny "보존폴더 없음"
  [ ! -e "$I" ] || deny "미완료 표지가 있다 — read 기록이 불완전(원격 상태 확인·보고 전 소비 금지)"
  RS=$(cut -d' ' -f1 "$L/read-status.txt" 2>/dev/null)
  [ "$RS" = READ_OK ] || [ "$RS" = READ_LOG_FAILED ] || deny "read 상태 ${RS:-없음} — 소비 가능한 read 가 아님"
  [ ! -e "$L/post" ] || deny "post 이미 실행"
  (cd "$L" && sha256sum --quiet -c read-SHA256SUMS && cd local-only && sha256sum --quiet -c ../local-only-SHA256SUMS) > /dev/null 2>&1 || deny "read 원문이 바뀜"
  PI="$L/post-INCOMPLETE.txt"; PD="$L/post"
  rec "$PI" "미완료 — post 시작 $(date -u +%FT%TZ)(PC)" && mkdir "$PD" || { echo "🔴 기록 실패(종료 4): post 시작 기록"; exit 4; }
  SINCE=$(sed -n 's/^SINCE //p' "$L/range-args.txt"); UNTIL=$(sed -n 's/^UNTIL //p' "$L/range-args.txt")
  BL=$(grep '^backend ' "$L/install_state.txt"); ST=${BL#*StartedAt=}
  if [ -n "$(utc "$ST")" ] && [ -n "$(utc "$SINCE")" ] && [ -n "$(utc "$UNTIL")" ] && [ "$(utc "$ST")" -le "$(utc "$SINCE")" ] && [ "$(utc "$SINCE")" -lt "$(utc "$UNTIL")" ]; then
    printf '{"since": "%s", "until": "%s", "axis": "server_utc", "basis": "pm-read-2 read: SINCE 인자(보고 명목 시각 UTC)·UNTIL 서버 date -u, docker compose logs --since/--until; backend StartedAt %s ≤ SINCE"}\n' \
      "$SINCE" "$UNTIL" "$ST" > "$PD/range.json" || WR=1
  else
    rec "$PD/range-not-provided.txt" "backend StartedAt($ST)이 SINCE 뒤이거나 SINCE/UNTIL 서버 시각 근거 부족 — 구간 근거 미제공(P0 검사 미완료)" || WR=1
  fi
  python -I -X utf8 "$P0T" --log "$L/local-only/backend.log" --collect-rc "$L/collect.rc" --range "$PD/range.json" --u-vars "$UV" --u-vars-sha256 "$UV_SHA" \
    --label "pm-read-2 P0" --synthetic false --out-dir "$PD/p0" > "$PD/p0.stdout" 2> "$PD/p0.stderr"; P0RC=$?
  rec "$PD/p0.rc" "$P0RC" || WR=1
  P0S=$(python -I -X utf8 -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['status'])" "$PD/p0/p0.json" 2>/dev/null)
  cp "$PD/p0/p0.json" "$PD/p0-result.json" 2>/dev/null || WR=1
  python -I -X utf8 "$AUXW" "$PD" "${3:-}" "$EVI" > "$PD/aux.stdout" 2>&1; AXRC=$?
  rec "$PD/aux.rc" "$AXRC" || WR=1
  CVRC=40
  if [ "$AXRC" = 0 ]; then
    python -I -X utf8 "$CONV" --sql "$L/state-3.sql" --sql-sha256 "$SQL_SHA" --tsv "$L/state.tsv" --rc "$L/state.rc" --stderr "$L/state.stderr" \
      --aux "$PD/n03-p0-aux.json" --label "pm-read-2 state" --synthetic false --out-dir "$PD/conv" > "$PD/conv.stdout" 2> "$PD/conv.stderr"; CVRC=$?
  fi
  rec "$PD/conv.rc" "$CVRC" || WR=1
  if [ "$P0S" = P0_PRESENT ]; then FINAL=10; elif [ "$P0RC" = 40 ] || [ -z "$P0S" ]; then FINAL=40; else FINAL=$CVRC; fi
  [ "$AXRC" = 0 ] || [ "$FINAL" = 10 ] || FINAL=40
  SUMMARY="P0 ${P0S:-판정 없음}(rc $P0RC) · aux rc $AXRC · 변환 rc $CVRC · 최종 $FINAL"
  (cd "$L" && find post -type f | sort | xargs -r sha256sum > post-SHA256SUMS) || WR=1
  [ "$WR" = 0 ] && rec "$L/post-status.txt" "$SUMMARY" || WR=1
  [ "$WR" = 0 ] && rm -f "$PI" && [ ! -e "$PI" ] || WR=1
  if [ "$WR" != 0 ]; then
    fr=4; [ "$FINAL" = 0 ] || fr=$FINAL
    echo "🔴 기록 실패 — $SUMMARY · 로컬 기록 실패=1 · 종료=$fr. post 미완료 표지를 남겼다(재실행 금지)"; exit "$fr"
  fi
  case "$FINAL" in 0) M="P0 없음(제공 로그 안)·변환 CONVERTED — 운영 준비 아님, W1/W2 는 승인된 실제 등록표 전 미실행";;
    10) M="🔴 P0 발생(DB 연결 획득 시간 초과) — 즉시 보고";; 20) M="🔴 수집 불통과(P0 검사 미완료 포함) — 병행 시작 근거 아님";; *) M="🔴 변환/판정 실패";; esac
  echo "post 끝 — $SUMMARY. $M"
  exit "$FINAL"
fi
deny "첫 인자는 read 또는 post"
