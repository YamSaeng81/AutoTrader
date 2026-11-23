#!/usr/bin/env bash
# A3 관측 수집 실행 원문 a3-preread-2 (총괄 v1.229 §3 R1·R2). **검토용 원문 — 미실행. 별도 승인 전 실행 금지. 가짜 SSH·서버 별칭 구동 시험도 하지 않았다.**
# 성격: '허용된 관측값 수집안'이다. A3 미배정 현금 확정·'충분' 판정 경로가 아니다(v1.229 §2).
# r4(a3-preread-1, 324d05d9) 대비:
#   R1 대상은 **LIVE#203 한 개로 고정**(--kind LIVE --sid 203 외 거절). --reg-id 는 대상을 묶지 못하므로 뺐다.
#      승인 날짜·창·대상을 원격 인자로 넘기고, 원격이 서버 KST 로 SQL·계좌 호출 직전에 다시 검사한다(PC 검사는 사전 거절용일 뿐).
#   R2 ssh 뒤 처리는 해시 고정한 a3-finish.sh(파일 전용)로 넘긴다. 반출 검사 실패·토큰 형태 발견·원격 실패·필수 기록 실패에서
#      본문 출력·확정·후속 소비가 없다. 원격 반출 변환은 a3-proj.py(파일 전용, 해시 고정)로 분리했다.
# 사용:
#   bash a3-preread.sh --date <승인 날짜 YYYY-MM-DD(KST)> --window <HH:MM-HH:MM(KST)> --kind LIVE --sid 203 --alloc-src <운영자 자금 이벤트 기록 파일(로컬)>
#                      --auth-file <서버의 인증 헤더 파일 경로(~/… 또는 /…)> --with-orders <true|false> --out <로컬 보존 폴더(a3pre-YYYYMMDD-HHMM, 없어야 함)>
# 하나라도 비거나 형식이 틀리거나 승인 대상과 다르면 원격 호출 0회로 거절(종료 2).
# 인증: 운영자가 서버에 미리 둔 헤더 파일(한 줄 'Authorization: Bearer …', 권한 600)의 **경로만** 받는다. 토큰을 만들거나 읽거나 옮기지 않는다.
#       서버 curl 의 `-H @파일` 지원은 미확인 — 별도 운영 탐색 없이 승인 본문의 사전 조건으로 둔다(지원 안 되면 원격 종료 6, 재시도 없음).
# 보호값: PGOPTIONS statement_timeout=15s·lock_timeout=2s, REPEATABLE READ READ ONLY + ROLLBACK, ON_ERROR_STOP, curl --max-time 20, ssh BatchMode.
# 종료값: 0 확정 · 2 시작 전/원격 거절 · 3 장부 SQL 실패 · 4 기록/반출 검사 실패 · 5 SQL 표준오류 · 6 계좌 불통과 · 7 앱 시계 불통과
#         · 8 SQL 범위 거절 · 9 반출 줄 토큰 형태 · 255 등 ssh 값 그대로
set -u
SELF="$(cd "$(dirname "$0")" && pwd)"
APPROVED_KIND=LIVE; APPROVED_SID=203
SQL="$SELF/a3-pre.sql";       SQL_SHA=69e1f277c6612758313a7927f10427d979eb07f1a5b9e6875a8d05310e29b31d
PROJ="$SELF/a3-proj.py";      PROJ_SHA=24802e60a4da2c9ed35f11a85d7e40a1d167465522d0259de748c7121754662e
REM="$SELF/a3-remote.sh";     REM_SHA=2cb807428c70e6878c989aff357f6725efbf654d8ba39bb858e4a9dca3ecec3e
FIN="$SELF/a3-finish.sh";     FIN_SHA=fb2aac5e9567c09e14ff43a536eb9f37820cb7bf240841d4ce9284eaa7df0f06
deny() { echo "🔴 거절(종료 2): $* — 원격 호출 0회"; exit 2; }
hashok() { [ "$(sha256sum < "$1" | cut -d' ' -f1)" = "$2" ]; }
hashok "$SQL" "$SQL_SHA" && hashok "$PROJ" "$PROJ_SHA" && hashok "$REM" "$REM_SHA" && hashok "$FIN" "$FIN_SHA" \
  || deny "고정 해시 불일치(a3-pre.sql·a3-proj.py·a3-remote.sh·a3-finish.sh)"
DATE= WIN= KIND= SID= ALLOC= AUTH= WO= OUT=
while [ $# -gt 0 ]; do
  case "$1" in
    --date) DATE=${2:-};; --window) WIN=${2:-};; --kind) KIND=${2:-};; --sid) SID=${2:-};; --alloc-src) ALLOC=${2:-};;
    --auth-file) AUTH=${2:-};; --with-orders) WO=${2:-};; --out) OUT=${2:-};;
    *) deny "모르는 인자 $1";;
  esac; shift 2 || deny "인자 값 없음"
done
[ "$KIND" = "$APPROVED_KIND" ] && [ "$SID" = "$APPROVED_SID" ] || deny "승인 대상(LIVE#203)과 다른 대상 $KIND#$SID"
[[ "$DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || deny "승인 날짜 없음/형식"
[[ "$WIN" =~ ^([0-2][0-9]:[0-5][0-9])-([0-2][0-9]:[0-5][0-9])$ ]] && [[ ! "${BASH_REMATCH[1]}" > "${BASH_REMATCH[2]}" ]] || deny "승인 창 없음/형식/자정 넘김"
A=${BASH_REMATCH[1]} B=${BASH_REMATCH[2]}
NOW=$(TZ=Asia/Seoul date +%F_%H:%M) || deny "PC 시계 읽기 실패"
[ "${NOW%_*}" = "$DATE" ] || deny "오늘(PC KST)이 승인 날짜 $DATE 가 아님"
[[ ! "${NOW#*_}" < "$A" && ! "${NOW#*_}" > "$B" ]] || deny "지금 ${NOW#*_}(PC KST)이 승인 창 $WIN 밖"
[ -n "$ALLOC" ] && [ -f "$ALLOC" ] && [ -s "$ALLOC" ] || deny "배정 원천(운영자 자금 이벤트 기록 파일) 없음 — S 대상 근거 없이 조회하지 않는다"
[[ "$AUTH" =~ ^(~/|/)[A-Za-z0-9._/-]+$ ]] || deny "인증 파일 경로 없음/형식(경로만, 토큰 값 금지)"
[ "$WO" = true ] || [ "$WO" = false ] || deny "with-orders 는 true|false"
case "$(basename "${OUT:-x}")" in a3pre-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]) ;; *) deny "보존 폴더 이름(a3pre-YYYYMMDD-HHMM)";; esac
[ ! -e "$OUT" ] || deny "보존 폴더가 이미 있음 — 같은 승인으로 다시 읽지 않는다"
TAG=$(basename "$OUT")
mkdir "$OUT" || { echo "🔴 기록 실패(종료 4): 보존 폴더 — 원격 호출 0회"; exit 4; }
I="$OUT/INCOMPLETE.txt"
{ printf '미완료 — a3-preread-2 시작 %s(PC UTC)\n' "$(date -u +%FT%T.%6NZ)" > "$I" \
  && printf 'date %s\nwindow %s\nkind %s\nsid %s\nalloc_src_sha256 %s\nauth_path %s\nwith_orders %s\nsql_sha256 %s\nproj_sha256 %s\nremote_sha256 %s\nfinish_sha256 %s\n' \
       "$DATE" "$WIN" "$KIND" "$SID" "$(sha256sum < "$ALLOC" | cut -d' ' -f1)" "$AUTH" "$WO" "$SQL_SHA" "$PROJ_SHA" "$REM_SHA" "$FIN_SHA" > "$OUT/args.txt"; } \
  || { echo "🔴 기록 실패(종료 4): 시작 기록 — 원격 호출 0회, 미완료 표지 남김"; exit 4; }
SQLB=$(base64 -w0 < "$SQL") && PROJB=$(base64 -w0 < "$PROJ") || { echo "🔴 기록 실패(종료 4): 원문 인코딩 — 원격 호출 0회"; exit 4; }
PC0=$(date -u +%FT%T.%6NZ)
ssh -o BatchMode=yes cat-prod "bash -s -- '$TAG' '$AUTH' '$WO' '$SID' '$KIND' '$DATE' '$WIN' '$SQLB' '$SQL_SHA' '$PROJB' '$PROJ_SHA'" \
  < "$REM" > "$OUT/remote.out" 2> "$OUT/ssh.stderr"; RC=$?
PC1=$(date -u +%FT%T.%6NZ)
WR=0
printf 'pc_utc %s %s\nssh_rc %s\nssh_stderr_bytes %s\n' "$PC0" "$PC1" "$RC" "$(wc -c < "$OUT/ssh.stderr")" > "$OUT/run.txt" || WR=1
rm -f "$OUT/ssh.stderr" || WR=1          # ssh 표준오류 원문은 남기지 않는다(바이트 수만)
exec bash "$FIN" "$OUT" "$RC" "$WR"      # 반출 검사·기록·본문 출력·확정은 a3-finish.sh 만 한다
