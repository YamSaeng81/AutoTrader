#!/usr/bin/env bash
# 정기 점검 자동화 (10:00·22:00 KST) — 수집·비교·보고만 한다. 운영 상태를 바꾸는 명령은 없다.
#
# 무엇을 하나: 앱 컨테이너 상태·운영 SHA, DB 읽기 전용 조회(daily_checks.sql), 점검 구간의 ERROR 로그 분류, 66세션 경보,
#             텔레그램 전송 이력을 모아 기대값과 비교하고, 결과 파일을 남긴 뒤 텔레그램으로 요약을 보낸다.
# 판정: 🟢 "점검 항목 정상"(아래 항목에 한정) / 🔴 이상 / ⚪ 판정 불가(수집 실패 — 정상으로 치지 않는다).
# 🔴 자동화는 수집·비교·보고를 대신할 뿐이다. 결과 확인과 정지 집행은 운영자 책임 그대로다(§6.16, 리뷰 2026-10-22).
#    보고가 점검 시각 뒤에 오지 않으면 운영자가 서버 상태를 직접 확인한다(아래 "보고 미수신").
#
# 사용:
#   scripts/ops/daily_check.sh            # 실행·보고
#   DRY_RUN=1 scripts/ops/daily_check.sh  # 보고를 보내지 않고 화면에만
# 설치·crontab: docs/OPS_DAILY_CHECK.md §3 — 설치본은 ~/ops-scripts 에 두고 REPO_DIR·SQL_FILE 를 지정한다(서버 체크아웃은 배포 SHA 에 고정).
#
# 종료 코드: 0 점검 항목 정상·보고 전송 / 1 이상 또는 판정 불가·보고 전송 / 2 보고 전송 실패 / 3 이미 실행 중
#
# 시험용 바꿔 끼우기(운영에서는 쓰지 않는다): PSQL_CMD · LOGS_FILE · APP_STATE · ALERTS_CMD · TG_API · SQL_TIMEOUT · TZ_EXPECT ·
#   SLEEP_CMD · REPORT_LIMIT · LOCK_MODE(mkdir — flock 없는 환경의 대체 잠금을 시험할 때)
set -u
# 🔴 2026-10-27: 로케일을 C(바이트 비교)로 고정한다. 서버 대화형 셸(C.UTF-8)에서 GNU grep 3.7 이 공유 풀 기동 줄을
#    P_POOL 로 잡지 못해(0) 미분류 🔴 로 셌다 — cron(로케일 없음 = C)에서는 1. 글자 단위로는 재현되지 않아 원인은 미규명이고,
#    패턴은 UTF-8 바이트 그대로 쓰므로 C 에서 같은 뜻이다. 보고 자르기는 파이썬이 UTF-8 로 직접 센다(아래 7).
export LC_ALL=C

REPO_DIR="${REPO_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
OUT_DIR="${OUT_DIR:-$HOME/daily-check}"
WINDOW_HOURS="${WINDOW_HOURS:-12}"
SQL_TIMEOUT="${SQL_TIMEOUT:-60}"
LOG_TIMEOUT="${LOG_TIMEOUT:-60}"
ALERTS_TIMEOUT="${ALERTS_TIMEOUT:-180}"
EXPECT_S66="${EXPECT_S66:-66}"
# 검증 도중 거래를 끝낸 것으로 지정된 세션·거래지원 종료가 확인된 마켓 (리뷰 2026-10-27, SUPERTREND_VALIDATION_PREREG.md 📌 KRW-BLAST).
# 분모에서 빼는 것이 아니다 — 원래 66개는 그대로 세고, 이 세션만 STOPPED 를 정상으로 본다. 추가는 리뷰 판단을 받은 뒤에만.
S66_ENDED_IDS="${S66_ENDED_IDS-576 577 578}"
ENDED_MARKETS="${ENDED_MARKETS-KRW-BLAST}"
TZ_EXPECT="${TZ_EXPECT:-+0900}"
# 실거래 시험 세션(리뷰 2026-10-31) — 정상 매수·보유·청산·재진입은 허용하고 중복·미결 지연·정산 이상만 🔴. 다른 실자금 세션은 종전대로 0 기대.
# SID_OK_STATUS: 정상으로 보는 상태 — 시작 전 CREATED, 운영 중 RUNNING. 56일 종료로 정지한 뒤에는 리뷰 판단을 받아 STOPPED 로 바꾼다.
SID_ID="${SID_ID:-203}"
SID_OK_STATUS="${SID_OK_STATUS-CREATED RUNNING}"
STALE_MIN="${STALE_MIN:-15}"
TG_API="${TG_API:-https://api.telegram.org}"
SLEEP_CMD="${SLEEP_CMD:-sleep}"
REPORT_LIMIT="${REPORT_LIMIT:-3500}"      # 텔레그램 한 메시지 상한 4096자 안 — 문자 기준
PY=$(command -v python3 || command -v python || true)
# 설치 SHA — 설치 때 스크립트 옆에 INSTALLED_SHA 를 둔다(docs/OPS_DAILY_CHECK.md §3). 없으면 저장소판.
SCRIPT_SHA=$(cat "$(dirname "$0")/INSTALLED_SHA" 2>/dev/null | head -c 12)
SCRIPT_SHA="${SCRIPT_SHA:-저장소판}"
DC="docker compose -f $REPO_DIR/docker-compose.prod.yml"
PSQL_CMD="${PSQL_CMD:-$DC exec -T db psql -X -U trader -d crypto_auto_trader}"
# 재시도 대상(중요 경보) 유형 — 이 유형의 전송 실패는 🔴. TelegramNotificationService.RETRY_TYPES 와 같게 둔다.
CRITICAL_TG_TYPES="STOP_LOSS SESSION_STOP RISK_LIMIT CLEANUP_NEEDS_HUMAN CLEANUP_INCOMPLETE EXCHANGE_DOWN CANDLE_REFRESH_REPORT"

mkdir -p "$OUT_DIR"

# ── 중복 실행 방지 ──
# 서버: flock(원자적, 프로세스가 끝나면 커널이 푼다). flock 이 없는 환경(로컬 시험)만 mkdir 잠금으로 대체한다 —
# mkdir 은 원자적이고, PID 를 아직 못 쓴 잠금(빈 pid)은 "실행 중" 으로 본다(리뷰 2026-10-22: 그 틈에 지우고 함께 실행하던 경합).
# 버려진 잠금은 이름 바꾸기(mv — 원자적)로 한 실행만 넘겨받는다.
busy() { echo "$(date '+%F %T %Z') 이미 실행 중($1) — 이번 실행은 건너뛴다"; exit 3; }
if command -v flock >/dev/null 2>&1 && [ "${LOCK_MODE:-}" != "mkdir" ]; then
  exec 9>"$OUT_DIR/.lock.flock"
  flock -n 9 || busy "flock"
else
  LOCK="$OUT_DIR/.lock.d"
  if ! mkdir "$LOCK" 2>/dev/null; then
    old=$(cat "$LOCK/pid" 2>/dev/null || true)
    if [ -z "$old" ]; then
      age=$(( $(date +%s) - $(stat -c %Y "$LOCK" 2>/dev/null || date +%s) ))
      [ "$age" -lt 600 ] && busy "잠금 생성 직후 — pid 기록 전"
    elif kill -0 "$old" 2>/dev/null; then
      busy "pid $old"
    fi
    mv "$LOCK" "$LOCK.stale.$$" 2>/dev/null || busy "다른 실행이 버려진 잠금을 넘겨받는 중"
    rm -rf "$LOCK.stale.$$"
    mkdir "$LOCK" 2>/dev/null || busy "잠금 경합"
  fi
  echo $$ > "$LOCK/pid"
  trap 'rm -rf "$LOCK"' EXIT
fi

NOW_KST=$(TZ=KST-9 date '+%Y-%m-%d %H:%M %Z')
SINCE_ISO=$(date -u -d "-${WINDOW_HOURS} hours" '+%Y-%m-%dT%H:%M:%SZ')
SINCE_KST=$(TZ=KST-9 date -d "$SINCE_ISO" '+%m-%d %H:%M')
STAMP=$(TZ=KST-9 date '+%Y%m%d-%H%M')
REPORT="$OUT_DIR/$STAMP.txt"

RED=(); NOTE=(); UNKNOWN=(); DETAIL=()
red()     { RED+=("$1"); }
note()    { NOTE+=("$1"); }
unknown() { UNKNOWN+=("$1"); }
detail()  { DETAIL+=("$1"); }

# ── 0. 실행 환경 — 서버 시간대(cron 시각이 KST 10:00·22:00 이어야 한다) ──
TZ_NOW=$(date '+%z')
[ -z "$TZ_EXPECT" ] || [ "$TZ_NOW" = "$TZ_EXPECT" ] || note "서버 시간대 $TZ_NOW (기대 $TZ_EXPECT) — cron 시각이 KST 10:00·22:00 인지 확인"

[ -f "$REPO_DIR/docker-compose.prod.yml" ] || unknown "REPO_DIR($REPO_DIR)에 docker-compose.prod.yml 이 없다 — 설치 설정 확인"

# ── 1. 운영 SHA ──
SHA=$(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo "?")
DIRTY=$(git -C "$REPO_DIR" status --porcelain --untracked-files=no 2>/dev/null | wc -l | tr -d ' ')
[ "$SHA" = "?" ] && unknown "운영 SHA 확인 실패"
[ "${DIRTY:-0}" != "0" ] && red "서버 작업 트리에 추적 파일 변경 ${DIRTY}건"

# ── 2. 앱 컨테이너 — 앱이 내려가 있어도 나머지 점검·보고는 계속한다 ──
if [ -n "${APP_STATE:-}" ]; then
  STATE_LINE="$APP_STATE"
else
  CID=$($DC ps -q backend 2>/dev/null | head -1)
  if [ -z "$CID" ]; then
    STATE_LINE="absent|0|-|-"
  else
    STATE_LINE=$(docker inspect --format '{{.State.Status}}|{{.RestartCount}}|{{.State.StartedAt}}|{{.Image}}' "$CID" 2>/dev/null || echo "inspect-failed|0|-|-")
  fi
fi
IFS='|' read -r APP_STATUS APP_RESTARTS APP_STARTED APP_IMAGE <<< "$STATE_LINE"
APP_IMAGE_SHORT=$(echo "$APP_IMAGE" | sed 's/^sha256://' | cut -c1-12)
case "$APP_STATUS" in
  running) ;;
  absent) red "backend 컨테이너 없음(앱이 내려가 있다)" ;;
  inspect-failed) unknown "backend 상태 조회 실패" ;;
  *) red "backend 상태 $APP_STATUS" ;;
esac
[ "${APP_RESTARTS:-0}" != "0" ] && red "backend 재시작 횟수 $APP_RESTARTS"
if [ "$APP_STATUS" = "running" ] && [ "$APP_STARTED" != "-" ]; then
  started_epoch=$(date -d "$APP_STARTED" +%s 2>/dev/null || echo 0)
  since_epoch=$(date -d "$SINCE_ISO" +%s)
  if [ "$started_epoch" -ge "$since_epoch" ]; then
    red "점검 구간 안에 backend 기동($(TZ=KST-9 date -d "$APP_STARTED" '+%m-%d %H:%M')) — 계획된 재기동인지 확인"
  fi
fi
detail "앱: $APP_STATUS · 재시작 ${APP_RESTARTS:-?} · 기동 $APP_STARTED · 이미지 ${APP_IMAGE_SHORT:--}"

# ── 3. DB 조회 — 시간 초과·오류·출력 누락은 판정 불가 ──
SQL_FILE="${SQL_FILE:-$REPO_DIR/scripts/ops/daily_checks.sql}"   # 설치본은 저장소 밖(~/ops-scripts) — 서버 체크아웃은 배포 SHA 에 고정돼 있다
SQL_OUT=$(timeout "$SQL_TIMEOUT" $PSQL_CMD -At -F'|' -v since="$SINCE_ISO" -v sid="$SID_ID" -v stale_min="$STALE_MIN" -f - < "$SQL_FILE" 2>&1); SQL_RC=$?
declare -A KV=()
TG_FAIL_LINES=()
if [ $SQL_RC -eq 124 ]; then
  unknown "DB 조회 시간 초과(${SQL_TIMEOUT}초)"
elif [ $SQL_RC -ne 0 ]; then
  unknown "DB 조회 실패(종료 $SQL_RC): $(echo "$SQL_OUT" | grep -m1 -iE 'error|fatal|could not|no such' | cut -c1-160)"
elif ! echo "$SQL_OUT" | grep -qx 'END|ok'; then
  unknown "DB 조회 출력 누락(끝 표시 END|ok 없음)"
else
  while IFS='|' read -r k a b; do
    [ -z "$k" ] && continue
    case "$k" in
      tg_fail) TG_FAIL_LINES+=("$a|$b") ;;
      BEGIN|ROLLBACK|END) ;;
      *) KV[$k]="$a" ;;
    esac
  done <<< "$SQL_OUT"
  # 필수 키가 하나라도 빠지면 출력 누락
  for k in flyway_version real_open_positions real_unresolved_orders cleanup_pending quarantine_targets s66_running s66_total \
           s66_not_running s66_coins real_buys_window quarantine_release_rows tg_queue_rejected tg_ok_window tg_last_ok            sid_status sid_available_krw sid_open_positions sid_active_orders sid_stale_orders sid_unknown_orders            sid_dup_buy_positions sid_filled_buys_window sid_orders_window sid_unapplied_fills settlement_unresolved; do
    [ -n "${KV[$k]+x}" ] || unknown "DB 조회 출력 누락($k)"
  done
fi
chk_zero() {  # 키 설명
  local v="${KV[$1]:-}"; [ -z "$v" ] && return
  [ "$v" = "0" ] || red "$2 $v"
}
chk_max() {  # 키 상한 설명
  local v="${KV[$1]:-}"; [ -z "$v" ] && return
  [ "$v" -le "$2" ] 2>/dev/null || red "$3 $v(상한 $2)"
}
if [ ${#KV[@]} -gt 0 ]; then
  chk_zero real_open_positions "SID $SID_ID 밖 실자금 보유(OPEN·CLOSING)"
  chk_zero real_unresolved_orders "SID $SID_ID 밖 실자금 미결 주문"
  chk_zero cleanup_pending "정리 대기"
  chk_zero quarantine_targets "격리 대조 대상"
  chk_zero real_buys_window "점검 구간 SID $SID_ID 밖 실자금 신규 매수"
  chk_zero settlement_unresolved "미해결 정산 불일치"
  # SID 203 — 개수 상한으로 본다: 보유 0~1 · 미결 0~1 · 구간 체결 매수 0~1(일봉 전략). 시작 전(CREATED)에는 주문·보유 모두 0.
  st="${KV[sid_status]:-}"
  if [ -n "$st" ]; then
    [[ " $SID_OK_STATUS " == *" $st "* ]] || red "SID $SID_ID 상태 $st(정상: $SID_OK_STATUS)"
    if [ "$st" = "CREATED" ]; then
      for k in sid_open_positions sid_active_orders sid_orders_window; do
        v="${KV[$k]:-0}"; [ "$v" = "0" ] || [ "$v" = "0 0" ] || red "SID $SID_ID 시작 전(CREATED)인데 $k $v"
      done
    fi
  fi
  chk_max sid_open_positions 1 "SID $SID_ID 열린 포지션"
  chk_max sid_active_orders 1 "SID $SID_ID 동시 미결 주문(중복 주문)"
  chk_zero sid_stale_orders "SID $SID_ID 미결 주문 ${STALE_MIN}분 넘게 지속"
  chk_zero sid_unknown_orders "SID $SID_ID UNKNOWN 주문"
  chk_zero sid_dup_buy_positions "SID $SID_ID 한 포지션에 체결 매수 2건 이상(중복 매수)"
  chk_max sid_filled_buys_window 1 "SID $SID_ID 구간 안 체결 매수(일봉 전략 — 중복 매수 의심)"
  chk_zero sid_unapplied_fills "SID $SID_ID 체결 주문 정산 반영값 없음·반영 대기(${STALE_MIN}분 넘게)"
  chk_zero tg_queue_rejected "텔레그램 큐 진입 실패"
  qr="${KV[quarantine_release_rows]:-}"; [ -n "$qr" ] && [ "$qr" != "0" ] && [ "$qr" != "-1" ] && red "격리 해제 기록 $qr"
  # 검증 세션 — 개수가 아니라 어느 세션인지로 본다: 지정 종료 세션의 STOPPED 만 정상, 그 밖의 RUNNING 아님은 🔴
  S66_LINE="66세션 RUNNING ${KV[s66_running]:-?}"
  if [ -n "${KV[s66_not_running]:-}" ]; then
    [ -n "${KV[s66_total]:-}" ] && [ "${KV[s66_total]}" != "$EXPECT_S66" ] && red "검증 세션 수 ${KV[s66_total]}(원래 $EXPECT_S66)"
    s66_bad=(); s66_ended=()
    for x in ${KV[s66_not_running]}; do
      [ "$x" = "-" ] && continue
      if [[ " $S66_ENDED_IDS " == *" ${x%%:*} "* ]] && [ "${x#*:}" = "STOPPED" ]; then s66_ended+=("${x%%:*}"); else s66_bad+=("$x"); fi
    done
    # 지정 종료 세션은 정지가 끝났다(10-27 13:05 KST) — 다시 RUNNING 이면 '정지 대기' 가 아니라 의도치 않은 재시작이다(리뷰 2026-10-27).
    # RUNNING 아닌 다른 상태(PAUSED 등)는 위 s66_bad 로 이미 🔴
    s66_rerun=()
    for i in $S66_ENDED_IDS; do
      [[ " ${s66_ended[*]} " == *" $i "* ]] && continue
      [[ " ${KV[s66_not_running]} " == *" $i:"* ]] || s66_rerun+=("$i")
    done
    [ ${#s66_bad[@]} -gt 0 ] && red "검증 세션 RUNNING 아님(지정 종료 외): ${s66_bad[*]}"
    [ ${#s66_rerun[@]} -gt 0 ] && red "거래 종료한 지정 세션이 다시 RUNNING(의도치 않은 재시작): ${s66_rerun[*]}"
    S66_LINE="66세션: 원래 ${KV[s66_total]:-?} 중 RUNNING ${KV[s66_running]:-?} · 지정 종료 STOPPED ${#s66_ended[@]}/$(echo $S66_ENDED_IDS | wc -w)${S66_ENDED_IDS:+($S66_ENDED_IDS)}"
  fi
  detail "DB: Flyway ${KV[flyway_version]:-?} · SID $SID_ID 밖 실자금 보유 ${KV[real_open_positions]:-?} · 미결 ${KV[real_unresolved_orders]:-?} · 정리 대기 ${KV[cleanup_pending]:-?} · 격리 대상 ${KV[quarantine_targets]:-?} · $S66_LINE · 구간 SID $SID_ID 밖 실자금 매수 ${KV[real_buys_window]:-?}"
  read -r sb ss <<< "${KV[sid_orders_window]:-? ?}"
  detail "SID $SID_ID: ${KV[sid_status]:-?} · 잔액 ${KV[sid_available_krw]:-?} · 보유 ${KV[sid_open_positions]:-?} · 미결 ${KV[sid_active_orders]:-?} · 구간 주문 매수 ${sb} 매도 ${ss} · 미해결 정산 불일치(전체) ${KV[settlement_unresolved]:-?}"
  detail "텔레그램(구간): 성공 ${KV[tg_ok_window]:-?} · 큐 진입 실패 ${KV[tg_queue_rejected]:-?} · 마지막 성공 ${KV[tg_last_ok]:-?}"
  for line in "${TG_FAIL_LINES[@]}"; do
    t="${line%%|*}"; n="${line##*|}"
    if [[ " $CRITICAL_TG_TYPES " == *" $t "* ]]; then red "텔레그램 중요 경보 전송 실패 $t ${n}건"; else note "텔레그램 전송 실패 $t ${n}건"; fi
  done
fi

# ── 4. ERROR 로그 — 알려진 유형은 로거와 문구로 한정, 그 밖은 🔴 ──
if [ -n "${LOGS_FILE:-}" ]; then
  LOG_OUT=$(cat "$LOGS_FILE" 2>&1); LOG_RC=$?
else
  LOG_OUT=$(timeout "$LOG_TIMEOUT" $DC logs --no-color --since "$SINCE_ISO" backend 2>&1); LOG_RC=$?
fi
if [ $LOG_RC -ne 0 ]; then
  unknown "앱 로그 조회 실패(종료 $LOG_RC)"
else
  ERRS=$(echo "$LOG_OUT" | grep ' ERROR ' || true)
  n_all=$(echo -n "$ERRS" | grep -c '' || true)
  # 의도된 것: 공유 풀 한도 미등록(STAGE_1 비활성 — 기동 때 한 줄)
  P_POOL='CapitalPoolLimits - .*공유 풀 한도가 사전 등록과 맞지 않는다.*한도 설정 누락'
  # 알려진 외부·데이터 사정(🟡 참고 — 건수만): 캔들 최신성 판정 불가 · 상장 폐지 코인 캔들 404 · 업비트/텔레그램/LLM 통신 실패
  P_CANDLE='CandleFreshnessGuard - .*\[캔들 최신성\].*판정 불가'
  P_404='UpbitRestClient - Upbit API 오류: status=404, url=https://api.upbit.com/v1/candles/.*Code not found'
  P_IO='(UpbitCandleCollector - 캔들 수집 실패|MarketDataSyncService - 시장 데이터 동기화 실패|UpbitOrderClient - 계좌 조회 실패|WatchlistFilterService - \[Watchlist\] 필터링 실패|TelegramNotificationService - \[Telegram\] 메시지 전송 중 오류\(응답 미수신\)|ClaudeProvider - \[ClaudeProvider\] 요청 실패)'
  n_pool=$(echo "$ERRS" | grep -cE "$P_POOL" || true)
  n_candle=$(echo "$ERRS" | grep -cE "$P_CANDLE" || true)
  n_404=$(echo "$ERRS" | grep -cE "$P_404" || true)
  n_io=$(echo "$ERRS" | grep -cE "$P_IO" || true)
  OTHER=$(echo "$ERRS" | grep -vE "$P_POOL|$P_CANDLE|$P_404|$P_IO" | grep -v '^$' || true)
  n_other=$(echo -n "$OTHER" | grep -c '' || true)
  detail "ERROR(구간): 전체 ${n_all} · 공유 풀 미등록 ${n_pool} · 캔들 판정 불가 ${n_candle} · 상장 폐지 404 ${n_404} · 통신 실패 ${n_io} · 미분류 ${n_other}"
  [ "${n_candle:-0}" != "0" ] && note "캔들 판정 불가 ERROR ${n_candle}건"
  [ "${n_io:-0}" != "0" ] && note "외부 통신 실패 ERROR ${n_io}건"
  # 캔들 404 를 마켓별로 — 검증 코인은 코인명을 드러낸다(리뷰 2026-10-27: BLAST 상장 폐지가 건수에 묻혔다).
  # 거래지원 종료가 확인된 마켓(ENDED_MARKETS)은 🟡, 확인되지 않은 검증 코인 마켓 소실은 🔴. 검증 밖 마켓은 마켓명만 남긴다.
  if [ "${n_404:-0}" != "0" ]; then
    s66c="${KV[s66_coins]:-}"
    [ -z "$s66c" ] && unknown "검증 코인 목록이 없어 캔들 404 를 가르지 못했다(${n_404}건)"
    other404=()
    while read -r n m; do
      [ -z "$m" ] && continue
      if [ -n "$s66c" ] && [[ " $s66c " == *" $m "* ]]; then
        if [[ " $ENDED_MARKETS " == *" $m "* ]]; then note "검증 코인 $m 캔들 404 ${n}건 — 거래지원 종료 확인된 마켓"
        else red "검증 코인 $m 캔들 404 ${n}건 — 마켓 소실(거래지원 종료 미확인): 업비트 공지를 확인한다"; fi
      else
        other404+=("$m $n")
      fi
    done <<< "$(echo "$ERRS" | grep -E "$P_404" | grep -oE 'market=[A-Z0-9]+-[A-Z0-9]+' | cut -d= -f2 | sort | uniq -c)"
    [ ${#other404[@]} -gt 0 ] && detail "  · 캔들 404 검증 밖 마켓: $(IFS=,; echo "${other404[*]}")"
  fi
  if [ "${n_other:-0}" != "0" ]; then
    red "미분류 ERROR ${n_other}건"
    while IFS= read -r l; do detail "  · $(echo "$l" | sed 's/^backend-1 *| *//' | cut -c1-200)"; done <<< "$(echo "$OTHER" | head -5)"
  fi
fi

# ── 5. 66세션 경보 — start_66.py alerts(구간 지정, 확인 시점 기록 안 함). 앱 API 가 필요하다 ──
if [ -n "${ALERTS_CMD:-}" ]; then
  AL_OUT=$(bash -c "$ALERTS_CMD" 2>&1); AL_RC=$?
else
  AL_OUT=$(cd "$REPO_DIR" && timeout "$ALERTS_TIMEOUT" python3 scripts/prospective/start_66.py alerts --since "$SINCE_ISO" --no-mark 2>&1); AL_RC=$?
fi
AL_LAST=$(echo "$AL_OUT" | grep -E '^(🟢|🔴) ' | grep -v '🔴 🔴' | tail -1)
AL_OK_LINE='🟢 경보 없음 · 실행 누락 없음'
# 종료 코드만 믿지 않는다(리뷰 2026-10-22) — 정상은 정상 결과 줄이 있을 때만, 빈 출력·알 수 없는 출력은 판정 불가
case $AL_RC in
  0) if echo "$AL_OUT" | grep -q '⚪ 이 구간에 관측이 없다'; then unknown "66세션 경보: 구간 관측 없음"
     elif [ "$AL_LAST" != "$AL_OK_LINE" ]; then unknown "66세션 경보: 정상 결과 줄 없음(종료 0, 출력 확인 필요)"; fi ;;
  1) if [[ "$AL_LAST" == "🔴 "* ]]; then
       # 적재 뒤처짐만 있고 그 회차들의 🔴 코인이 전부 거래지원 종료 확인 마켓이면 🟡 — 그 밖(다른 코인·판정 불가·실행 누락)은 🔴 그대로
       al_rows=$(echo "$AL_OUT" | grep -E '^🔴 [0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}  [0-9]+종: ' || true)
       al_other=""
       for c in $(echo "$al_rows" | sed 's/^.*종: //' | tr ' ' '\n' | sort -u); do [[ " $ENDED_MARKETS " == *" $c "* ]] || al_other="$al_other $c"; done
       if [[ "$AL_LAST" =~ ^🔴\ 적재\ 뒤처짐\ [0-9]+회$ ]] && [ -n "$al_rows" ] && [ -z "$al_other" ]; then
         note "66세션 경보: ${AL_LAST#🔴 } — 전부 거래지원 종료 확인 마켓($ENDED_MARKETS), 다른 코인 이상 없음"
       else
         red "66세션 경보(확정 아님 — 확인 필요): ${AL_LAST#🔴 }${al_other:+ · 코인:$al_other}"
       fi
     else unknown "66세션 경보: 결과 줄 없음(종료 1)"; fi ;;
  124) unknown "66세션 경보 조회 시간 초과(${ALERTS_TIMEOUT}초)" ;;
  *) unknown "66세션 경보 조회 실패(종료 $AL_RC): $(echo "$AL_OUT" | tail -1 | cut -c1-120)" ;;
esac
detail "66세션: ${AL_LAST:-(결과 줄 없음)}"

# ── 6. 판정·보고 ──
if [ ${#UNKNOWN[@]} -gt 0 ]; then
  HEAD="⚪ 판정 불가 — 수집 실패 ${#UNKNOWN[@]}건 (정상으로 보지 않는다)"; RC=1
elif [ ${#RED[@]} -gt 0 ]; then
  HEAD="🔴 이상 ${#RED[@]}건"; RC=1
else
  HEAD="🟢 점검 항목 정상"; RC=0
fi
{
  echo "[정기 점검] $HEAD"
  echo "실행 $NOW_KST · 구간 $SINCE_KST ~ (${WINDOW_HOURS}시간) · 운영 $SHA · 점검 스크립트 $SCRIPT_SHA"
  for x in "${UNKNOWN[@]}"; do echo "⚪ $x"; done
  for x in "${RED[@]}"; do echo "🔴 $x"; done
  for x in "${NOTE[@]}"; do echo "🟡 $x"; done
  echo "—"
  for x in "${DETAIL[@]}"; do echo "$x"; done
  [ "$RC" = "0" ] && echo "※ '점검 항목 정상' 은 위 항목에 한정된다. 결과 확인·정지 집행은 운영자 책임."
} > "$REPORT"
{
  echo; echo "===== 원자료 ====="
  echo "--- DB (종료 $SQL_RC)"; echo "$SQL_OUT"
  echo "--- 66세션 (종료 $AL_RC)"; echo "$AL_OUT" | tail -40
} >> "$REPORT.raw"
# 수집한 앱 로그 원문 — 장애 대조용(리뷰 2026-10-22). 압축해 결과 파일과 같은 이름으로 둔다.
printf '%s\n' "${LOG_OUT:-}" | gzip -c > "$REPORT.log.gz" 2>/dev/null || true

if [ "${DRY_RUN:-0}" = "1" ]; then
  cat "$REPORT"; echo "(DRY_RUN — 보고 보내지 않음 · 결과 $REPORT)"; exit $RC
fi

# ── 7. 텔레그램 보고 — 서식 없이(평문), 3회까지(429 는 retry_after, 합계 30초 상한) ──
envval() { grep -m1 "^$1=" "$REPO_DIR/.env" 2>/dev/null | cut -d= -f2- | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }
BOT="${TELEGRAM_BOT_TOKEN:-$(envval TELEGRAM_BOT_TOKEN)}"
CHAT="${TELEGRAM_CHAT_ID:-$(envval TELEGRAM_CHAT_ID)}"
# 보낼 본문 — **문자 단위**로 자른다(리뷰 2026-10-22: head -c 는 바이트라 한글 중간을 자른다). cron 은 로케일이 비어 셸의 ${#…} 도 바이트가 되므로
# 파이썬으로 UTF-8 문자 수를 센다. 파이썬이 없으면 바이트로 자른 뒤 iconv -c 로 끝의 잘린 문자를 버린다. 본문은 명령 인자가 아니라 파일로 curl 에 넘긴다.
MSG="$REPORT.msg"
if [ -n "$PY" ]; then
  "$PY" -c 'import sys
src, dst, limit, tail = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
t = open(src, encoding="utf-8").read().rstrip("\n")
if len(t) > limit:
    t = t[:limit] + "\n" + tail
open(dst, "w", encoding="utf-8", newline="\n").write(t)' "$REPORT" "$MSG" "$REPORT_LIMIT" "…(이하 생략 — 서버 결과 파일 $REPORT)" 2>/dev/null \
    || head -c 3000 "$REPORT" | iconv -c -f UTF-8 -t UTF-8 > "$MSG"
else
  if [ "$(wc -c < "$REPORT")" -gt $((REPORT_LIMIT * 3)) ]; then
    { head -c $((REPORT_LIMIT * 3)) "$REPORT" | iconv -c -f UTF-8 -t UTF-8; printf '\n…(이하 생략 — 서버 결과 파일 %s)' "$REPORT"; } > "$MSG"
  else
    cp "$REPORT" "$MSG"
  fi
fi
SENT=0; waited=0; last=""
if [ -z "$BOT" ] || [ -z "$CHAT" ]; then
  last="토큰·채팅방 설정 없음"
else
  for i in 1 2 3; do
    resp=$(curl -sS --max-time 20 -w '\n%{http_code}' -X POST "$TG_API/bot$BOT/sendMessage" \
             --data-urlencode "chat_id=$CHAT" --data-urlencode "text@$MSG" 2>&1); crc=$?
    code=$(echo "$resp" | tail -1); body=$(echo "$resp" | sed '$d')
    if [ $crc -eq 0 ] && [ "$code" = "200" ] && echo "$body" | grep -qE '"ok": *true'; then SENT=1; break; fi
    last="시도 $i: curl $crc · HTTP $code"
    [ $i -eq 3 ] && break
    w=5
    if [ "$code" = "429" ]; then
      ra=""
      [ -n "$PY" ] && ra=$(printf '%s' "$body" | "$PY" -c 'import sys, json
try:
    v = json.load(sys.stdin).get("parameters", {}).get("retry_after")
    print(int(v)) if v is not None else None
except Exception:
    pass' 2>/dev/null)
      [ -z "$ra" ] && ra=$(printf '%s' "$body" | grep -oE '"retry_after"[[:space:]]*:[[:space:]]*[0-9]+' | grep -oE '[0-9]+$' | head -1)
      [ -n "$ra" ] && w=$ra
    fi
    if [ $((waited + w)) -gt 30 ]; then last="$last · 다음 대기 ${w}초가 대기 상한 30초를 넘어 중단"; break; fi
    WAITS="${WAITS:-}${WAITS:+,}${w}"
    $SLEEP_CMD "$w"; waited=$((waited + w))
  done
fi
[ -n "${WAITS:-}" ] && last="$last · 대기 ${WAITS}초"
if [ $SENT -eq 1 ]; then
  echo "$(date '+%F %T %Z') 보고 전송 성공${WAITS:+ (재시도 대기 ${WAITS}초)} · $HEAD" | tee -a "$REPORT" > "$OUT_DIR/last_send"
  find "$OUT_DIR" -maxdepth 1 -name '2*.txt*' -mtime +60 -delete 2>/dev/null
  exit $RC
fi
echo "$(date '+%F %T %Z') 🔴 보고 전송 실패($last) · $HEAD" | tee -a "$REPORT" > "$OUT_DIR/last_send"
exit 2
