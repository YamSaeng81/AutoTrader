#!/usr/bin/env bash
# 새 모의 9세션 생성 실행 틀 v4.2.1 (총괄 v1.178 §4-4 + 11-17 추가 지시: 출력 덮어쓰기·기록 실패·종료값 가림 거절)
# 🔴 실행 금지: 총괄 승인·운영자 동반 뒤, 단계(STEP)마다 한 번 부르고 결과를 보고한 뒤 다음으로.
# 서버(운영) 안에서 이 묶음 사본(ab-create-v42-20261117/) 위치로 부른다. 자동 재시도·자동 다음 단계·자동 되돌림 없음. 어느 단계든 0 이 아니면 멈춘다.
#
#   STEP=0 REPO_DIR=<서버 저장소> TOKEN_FILE=<운영자 600 파일 경로> bash bundle/run_bundle.sh   # 출처·준비 확인(읽기만)
#   STEP=1 ...                                                                                 # 사전 확인 SQL + 판정(읽기만)
#   STEP=2 ...                                                                                 # A/B 8 생성(창: M15 경계 C 의 +6~+12분, T0 = C+15)
#   STEP=3 ...                                                                                 # A/B 확인 SQL + 완성(읽기만, 첫 평가 뒤)
#   STEP=4 SMA20_WS=<ISO> SMA20_WE=<ISO> SMA20_T0=<ISO> ...                                    # SMA20 1 생성(창·T0 는 승인값 그대로)
#   STEP=5 ...                                                                                 # SMA20 확인 SQL + 완성(읽기만, T0 전)
#
# 비밀값: 토큰은 TOKEN_FILE 경로로만 넘긴다(내용을 읽거나 출력하지 않는다 — 도구가 헤더에만 싣는다). DB 는 서버 안 docker compose exec psql
# (컨테이너 안 접속, 비밀번호 인자·환경 변수 없음). 출력 폴더에 토큰·비밀번호가 없음을 STEP 끝마다 grep 으로 확인한다(값은 출력하지 않음).
#
# 기록 규칙(v4.2.1):
#   - 덮어쓰기 거절: 단계가 쓸 파일·폴더가 하나라도 이미 있으면 아무것도 부르지 않고 종료 5. 같은 단계 재실행은 새 OUT 이 아니면 불가.
#   - 순서: STEP k(≥1)는 k-1 단계의 rc 기록(OUT/k-1.rc)이 정확히 0 일 때만 부른다. 없거나 0 이 아니면 종료 6(부분 생성 뒤 다음 단계 금지).
#   - 기록 실패: steps.log 추가·tee 기록·rc 파일 쓰기 중 하나라도 실패하면 종료 8. 도구가 0 이어도 8 로 끝난다(성공으로 보이지 않게).
#   - 종료값 가림 금지: 파이프의 도구 rc(PIPESTATUS[0])와 tee rc(PIPESTATUS[1])를 따로 본다. 도구 rc 가 0 이 아니면 그 값 그대로 끝나고,
#     기록도 실패했으면 steps.log 가 가능한 한 둘 다 남긴다. 최종 종료값은 OUT/STEP.rc 에도 쓴다.
set -u
: "${STEP:?STEP 필요}" "${REPO_DIR:?REPO_DIR 필요}" "${TOKEN_FILE:?TOKEN_FILE 경로 필요}"
B=$(cd "$(dirname "$0")/.." && pwd)
CODE_SHA=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d
BASE=http://localhost:8080        # 검토한 backend 직접 경로(RUNBOOK 의 localhost:8080). 프록시·frontend·로그인 경유 금지 — 3xx 는 도구가 불명으로 멈춘다
DC=(docker compose -f "$REPO_DIR/docker-compose.prod.yml" exec -T db psql -X -U trader -d crypto_auto_trader)
DBCMD=$(printf '["docker","compose","-f","%s","exec","-T","db","psql","-U","trader","-d","crypto_auto_trader"]' "$REPO_DIR/docker-compose.prod.yml")
DBSRC="운영 서버 docker compose exec db psql (crypto_auto_trader, 컨테이너 안 접속)"
OUT="${OUT_DIR:-$B/run-live}"   # 기본 run-live. 운영자가 새 폴더를 줄 수 있다(덮어쓰기는 어느 쪽이든 거절)
TOOL_RC=0
PY=python3

case "$STEP" in 0|1|2|3|4|5) ;; *) echo "STEP 은 0~5"; exit 2 ;; esac
mkdir -p "$OUT" || { echo "출력 폴더 만들기 실패 — 멈춤" >&2; exit 8; }

log() {   # steps.log 에 먼저 쓰고(실패하면 8) 화면에도 보인다. tee 를 쓰지 않는다(쓰기 실패가 가려지지 않게).
  local line; line="$(date -u +%FT%TZ) STEP=$STEP $*"
  printf '%s\n' "$line" >> "$OUT/steps.log" || {   # 기록 실패: 도구가 이미 0 이 아닌 값을 냈으면 그 값, 아니면 8
    printf '%s [steps.log 기록 실패]\n' "$line" >&2; [ "${TOOL_RC:-0}" -ne 0 ] && exit "$TOOL_RC"; exit 8; }
  printf '%s\n' "$line"
}
finish() {   # 최종 rc 를 rc 파일과 steps.log 에 남기고 그 값으로 끝낸다. rc 파일을 못 쓰면 8.
  local r=$1
  printf '%s\n' "$r" > "$OUT/$STEP.rc" || { log "rc 파일 기록 실패(원래 rc=$r)"; [ "$r" -ne 0 ] && exit "$r"; exit 8; }
  log "끝 rc=$r"; exit "$r"
}
fresh() {   # 이 단계가 쓸 경로가 하나라도 있으면 아무것도 부르지 않고 5
  local p bad=0
  for p in "$@"; do [ -e "$OUT/$p" ] && { log "이미 있음: $p"; bad=1; }; done
  [ $bad -eq 0 ] || { log "🔴 출력 덮어쓰기 거절 — 아무것도 부르지 않음"; exit 5; }
}
prev_ok() {   # 앞 단계 rc 기록이 정확히 0
  local k=$((STEP - 1)) v
  [ "$STEP" -eq 0 ] && return 0
  v=$(cat "$OUT/$k.rc" 2>/dev/null) || v="없음"
  [ "$v" = "0" ] || { log "🔴 앞 단계 STEP=$k rc=$v — 이 단계 부르지 않음"; exit 6; }
}
recorded() {   # recorded <도구 rc> <tee rc> <이름> — 기록 실패를 도구 rc 와 따로 남긴다
  local tr=$1 er=$2 name=$3
  if [ "$er" -ne 0 ]; then log "🔴 $name 기록(tee) 실패 rc=$er · 도구 rc=$tr"; [ "$tr" -ne 0 ] && finish "$tr"; finish 8; fi
}
nosecret() {   # 토큰 파일 내용이 출력 폴더에 없는지(값은 보이지 않게 개수만)
  local n; n=$(grep -rlF -f "$TOKEN_FILE" "$OUT" 2>/dev/null | wc -l); log "토큰 문자열 포함 파일 수=$n"; [ "$n" -eq 0 ] || { log "🔴 토큰 노출 — 멈춤"; finish 9; }
}

prev_ok
case "$STEP" in
0)  # 출처·준비(읽기만)
    fresh 0.rc 0-sha.txt 0-python.txt 0-backend.txt
    (cd "$B" && sha256sum -c bundle/SHA256SUMS.bundle) > "$OUT/0-sha.txt" 2>&1; r=$?; log "묶음 해시 rc=$r"; [ $r -eq 0 ] || finish 2
    h=$(git -C "$REPO_DIR" rev-parse HEAD); log "서버 HEAD=$h"; [ "$h" = "$CODE_SHA" ] || { log "HEAD 가 $CODE_SHA 아님 — 멈춤"; finish 2; }
    $PY --version > "$OUT/0-python.txt" 2>&1 || { log "python 확인 실패"; finish 2; }; log "python=$(cat "$OUT/0-python.txt")"
    m=$(stat -c %a "$TOKEN_FILE" 2>/dev/null); log "토큰 파일 권한=$m(내용 읽지 않음)"; case "$m" in 600|400) ;; *) log "권한 600/400 아님 — 멈춤"; finish 2;; esac
    cid=$(docker compose -f "$REPO_DIR/docker-compose.prod.yml" ps -q backend) && [ -n "$cid" ] || { log "backend 컨테이너 확인 실패"; finish 2; }
    docker inspect -f '{{.Config.Image}} {{.State.StartedAt}} {{.RestartCount}}' "$cid" | tee "$OUT/0-backend.txt"; ps=("${PIPESTATUS[@]}"); TOOL_RC=${ps[0]}
    recorded "${ps[0]}" "${ps[1]}" "backend 확인"; [ "${ps[0]}" -eq 0 ] || finish 2
    finish 0
    ;;
1)  # 사전 확인(읽기만) — 522~587 은 개수만
    fresh 1.rc 1-preflight.out 1-preflight.err 1-judge.txt
    "${DC[@]}" -q --csv -v ON_ERROR_STOP=1 -v at= -v n_new=9 -v cap=120 -f - < "$B/preflight/preflight_v2.sql" > "$OUT/1-preflight.out" 2> "$OUT/1-preflight.err"
    r=$?; TOOL_RC=$r; log "preflight psql rc=$r"; [ $r -eq 0 ] || finish 2
    $PY -X utf8 "$B/bundle/judge_preflight.py" --preflight-output "$OUT/1-preflight.out" | tee "$OUT/1-judge.txt"; ps=("${PIPESTATUS[@]}"); TOOL_RC=${ps[0]}
    log "판정 rc=${ps[0]}"; recorded "${ps[0]}" "${ps[1]}" "판정"; finish "${ps[0]}"
    ;;
2)  # A/B 8 — 창·요청 직전 재확인·불명 중단·재전송 금지는 도구가 한다
    fresh 2.rc 2-ab-create.txt ab-create
    $PY -X utf8 "$B/create/ab_create.py" --base-url "$BASE" --token-file "$TOKEN_FILE" --protocol "$B/tool/protocol.json" --out-dir "$OUT/ab-create" \
        --code-sha "$CODE_SHA" --db-cmd "$DBCMD" --db-source "$DBSRC" --session-cap 120 --execute | tee "$OUT/2-ab-create.txt"; ps=("${PIPESTATUS[@]}"); TOOL_RC=${ps[0]}
    log "ab_create rc=${ps[0]} (0=8개 OK·DB 메타 대조 성공, 2=보낸 것 없음, 3=멈춤/부분/불명, 4=기록부·잠금)"; nosecret
    recorded "${ps[0]}" "${ps[1]}" "ab_create"; finish "${ps[0]}"
    ;;
3)  # A/B 확인(읽기만) — 첫 평가(60초 주기) 뒤
    fresh 3.rc 3-ab.db_check.out 3-ab.psql.err 3-ab-complete.txt ab.creation_log.json
    D="$OUT/ab-create/creation_log.draft.json"
    ids=$($PY -c "import json;print(','.join(s['session_id'] for s in json.load(open('$D'))['sessions']))") && [ -n "$ids" ] || { log "초안 ID 읽기 실패"; finish 2; }
    t0=$($PY -c "import json;print(json.load(open('$D'))['t0'])") && [ -n "$t0" ] || { log "초안 T0 읽기 실패"; finish 2; }
    "${DC[@]}" -q --csv -v ON_ERROR_STOP=1 -v ids="$ids" -v t0="$t0" -f - < "$B/create/post_creation_check.sql" > "$OUT/3-ab.db_check.out" 2> "$OUT/3-ab.psql.err"
    r=$?; TOOL_RC=$r; log "확인 psql rc=$r"; [ $r -eq 0 ] || finish 2
    $PY -X utf8 "$B/create/ab_complete.py" --draft "$D" --db-output "$OUT/3-ab.db_check.out" --protocol "$B/tool/protocol.json" \
        --db-source "$DBSRC" --out "$OUT/ab.creation_log.json" | tee "$OUT/3-ab-complete.txt"; ps=("${PIPESTATUS[@]}"); TOOL_RC=${ps[0]}
    log "ab_complete rc=${ps[0]}"; recorded "${ps[0]}" "${ps[1]}" "ab_complete"; finish "${ps[0]}"
    ;;
4)  # SMA20 1 — 창·T0 는 승인값을 그대로 넘긴다(도구가 T0 = 창 시작 뒤 첫 09:00 KST 인지·09시대·T0−30분을 본다)
    : "${SMA20_WS:?}" "${SMA20_WE:?}" "${SMA20_T0:?}"
    fresh 4.rc 4-sma20-create.txt sma20-create
    $PY -X utf8 "$B/sma20/sma20_create.py" --base-url "$BASE" --token-file "$TOKEN_FILE" --body "$B/sma20/body.json" --out-dir "$OUT/sma20-create" \
        --code-sha "$CODE_SHA" --window-start "$SMA20_WS" --window-end "$SMA20_WE" --t0 "$SMA20_T0" --db-cmd "$DBCMD" --db-source "$DBSRC" \
        --session-cap 120 --execute | tee "$OUT/4-sma20-create.txt"; ps=("${PIPESTATUS[@]}"); TOOL_RC=${ps[0]}
    log "sma20_create rc=${ps[0]}"; nosecret; recorded "${ps[0]}" "${ps[1]}" "sma20_create"; finish "${ps[0]}"
    ;;
5)  # SMA20 확인(읽기만) — T0 전에 끝낸다(완성기가 db_now < T0 를 본다)
    fresh 5.rc 5-sma20.db_check.out 5-sma20.psql.err 5-sma20-complete.txt sma20.creation_log.json
    D="$OUT/sma20-create/sma20_creation.draft.json"
    sid=$($PY -c "import json;print(int(json.load(open('$D'))['session_id']))") && [ -n "$sid" ] || { log "초안 ID 읽기 실패"; finish 2; }
    t0=$($PY -c "import json;print(json.load(open('$D'))['t0'])") && [ -n "$t0" ] || { log "초안 T0 읽기 실패"; finish 2; }
    "${DC[@]}" -q --csv -v ON_ERROR_STOP=1 -v id="$sid" -v t0="$t0" -f - < "$B/sma20/sma20_check.sql" > "$OUT/5-sma20.db_check.out" 2> "$OUT/5-sma20.psql.err"
    r=$?; TOOL_RC=$r; log "확인 psql rc=$r"; [ $r -eq 0 ] || finish 2
    $PY -X utf8 "$B/sma20/sma20_complete.py" --draft "$D" --body "$B/sma20/body.json" --db-output "$OUT/5-sma20.db_check.out" \
        --check-db-source "$DBSRC" --out "$OUT/sma20.creation_log.json" | tee "$OUT/5-sma20-complete.txt"; ps=("${PIPESTATUS[@]}"); TOOL_RC=${ps[0]}
    log "sma20_complete rc=${ps[0]}"; recorded "${ps[0]}" "${ps[1]}" "sma20_complete"; finish "${ps[0]}"
    ;;
esac
