#!/usr/bin/env bash
# A3 사전 조회 실행 원문 a3-preread-1 (총괄 v1.228 §3-2, 월중 부록 §3-3 공용). **검토용 원문 — 미실행. 별도 승인 전 실행 금지. 가짜 SSH 시험도 하지 않았다.**
# 목적: 배포 전 1회, 미배정 현금 범위(a3calc3.py 입력)와 F1 전제(KRW·묶인 KRW·진행 주문·정리 대기)를 같은 원격 호출 안에서 얻는다.
#       월중 원천 회차에서는 with_orders=true 로 등록 SID 의 주문 열(M절)을 같은 트랜잭션에 더한다(별도 승인).
# 개발 PC 에서 실행. 원격 호출은 ssh 1회(BatchMode)뿐이고 그 안에서 a3-remote.sh 가 SQL 1회·계좌 요약 1회·앱 시계 2회를 한다. 자동 재시도 없음.
# 사용:
#   bash a3-preread.sh --date <승인 날짜 YYYY-MM-DD(KST)> --window <HH:MM-HH:MM(KST)> --reg-id <등록 ID> --alloc-src <운영자 자금 이벤트 기록 파일(로컬)>
#                      --auth-file <서버의 인증 헤더 파일 경로(~/… 또는 /…)> --sid <등록 SID> --with-orders <true|false> --out <로컬 보존 폴더(a3pre-YYYYMMDD-HHMM, 없어야 함)>
# 하나라도 비거나 형식이 틀리면 원격 호출 0회로 거절(종료 2). 오늘(KST)이 승인 날짜가 아니거나 지금이 창 밖이어도 거절.
# 인증: 운영자가 서버에 미리 둔 헤더 파일(한 줄 'Authorization: Bearer …', 권한 600)의 **경로만** 받는다. 이 원문은 토큰을 만들거나 읽거나 옮기지 않는다.
# 보호값: PGOPTIONS statement_timeout=15s·lock_timeout=2s, REPEATABLE READ READ ONLY + ROLLBACK, ON_ERROR_STOP, curl --max-time 20.
# 종료값: 0 성공 · 2 시작 전 거절/원격 거절 · 3 장부 SQL 실패 · 4 기록 실패 · 5 SQL 표준오류 · 6 계좌 요약 실패(http≠200 또는 curl 실패) · 255 등 ssh 값 그대로
# 반출(콘솔/PC): a3-remote.sh 의 허용 줄만 — 시계 표본·호출 전후 구간·KRW 세 정수·BTC 수량/현재가·다른 보유 개수·fetchedAt·세션/예약/포지션 행(아래 SQL 열)·개수·서버 해시.
#   계좌 JSON·SQL 결과·표준오류 원문은 서버 ~/a3-pre/<TAG>/ 에만. 로그 수집 없음.
set -u
SELF="$(cd "$(dirname "$0")" && pwd)"
SQL="$SELF/a3-pre.sql";       SQL_SHA=d505d08fa351a0d8d64822d9319c80e7c8210f342a3eed673531a49dd14b520b
REM="$SELF/a3-remote.sh";     REM_SHA=94db4ca967a5f990b2ac7ab0b898098a49072928afb360ff1b2b260fc24501e5
deny() { echo "🔴 거절(종료 2): $* — 원격 호출 0회"; exit 2; }
hashok() { [ "$(sha256sum < "$1" | cut -d' ' -f1)" = "$2" ]; }
hashok "$SQL" "$SQL_SHA" && hashok "$REM" "$REM_SHA" || deny "고정 해시 불일치(a3-pre.sql·a3-remote.sh)"
DATE= WIN= REG= ALLOC= AUTH= SID= WO= OUT=
while [ $# -gt 0 ]; do
  case "$1" in
    --date) DATE=${2:-};; --window) WIN=${2:-};; --reg-id) REG=${2:-};; --alloc-src) ALLOC=${2:-};;
    --auth-file) AUTH=${2:-};; --sid) SID=${2:-};; --with-orders) WO=${2:-};; --out) OUT=${2:-};;
    *) deny "모르는 인자 $1";;
  esac; shift 2 || deny "인자 값 없음"
done
[[ "$DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || deny "승인 날짜 없음/형식"
[ "$(TZ=Asia/Seoul date +%F)" = "$DATE" ] || deny "오늘(KST)이 승인 날짜 $DATE 가 아님"
[[ "$WIN" =~ ^([0-2][0-9]:[0-5][0-9])-([0-2][0-9]:[0-5][0-9])$ ]] || deny "승인 창 없음/형식"
NOW=$(TZ=Asia/Seoul date +%H:%M); [[ ! "$NOW" < "${BASH_REMATCH[1]}" && ! "$NOW" > "${BASH_REMATCH[2]}" ]] || deny "지금 $NOW(KST)이 승인 창 $WIN 밖"
[[ "$REG" =~ ^[A-Za-z0-9._-]{3,64}$ ]] || deny "등록 ID 없음/형식"
[ -n "$ALLOC" ] && [ -f "$ALLOC" ] && [ -s "$ALLOC" ] || deny "배정 원천(운영자 자금 이벤트 기록 파일) 없음 — S 대상 근거 없이 조회하지 않는다"
[[ "$AUTH" =~ ^(~/|/)[A-Za-z0-9._/-]+$ ]] || deny "인증 파일 경로 없음/형식(경로만, 토큰 값 금지)"
[[ "$SID" =~ ^[0-9]{1,9}$ ]] || deny "등록 SID 없음/형식"
[ "$WO" = true ] || [ "$WO" = false ] || deny "with-orders 는 true|false"
case "$(basename "${OUT:-x}")" in a3pre-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]) ;; *) deny "보존 폴더 이름(a3pre-YYYYMMDD-HHMM)";; esac
[ ! -e "$OUT" ] || deny "보존 폴더가 이미 있음 — 같은 승인으로 다시 읽지 않는다"
TAG=$(basename "$OUT")
mkdir "$OUT" || { echo "🔴 기록 실패(종료 4): 보존 폴더 — 원격 호출 0회"; exit 4; }
I="$OUT/INCOMPLETE.txt"
{ printf '미완료 — a3-preread-1 시작 %s(PC UTC)\n' "$(date -u +%FT%T.%6NZ)" > "$I" \
  && printf 'date %s\nwindow %s\nreg_id %s\nalloc_src_sha256 %s\nauth_path %s\nsid %s\nwith_orders %s\nsql_sha256 %s\nremote_sha256 %s\n' \
       "$DATE" "$WIN" "$REG" "$(sha256sum < "$ALLOC" | cut -d' ' -f1)" "$AUTH" "$SID" "$WO" "$SQL_SHA" "$REM_SHA" > "$OUT/args.txt"; } \
  || { echo "🔴 기록 실패(종료 4): 시작 기록 — 원격 호출 0회, 미완료 표지 남김"; exit 4; }
B64=$(base64 -w0 < "$SQL")
PC0=$(date -u +%FT%T.%6NZ)
ssh -o BatchMode=yes cat-prod "bash -s -- '$TAG' '$AUTH' '$WO' '$SID' '$B64' '$SQL_SHA'" < "$REM" > "$OUT/remote.out" 2> "$OUT/ssh.stderr"; RC=$?
PC1=$(date -u +%FT%T.%6NZ)
WR=0
printf 'pc_utc %s %s\nssh_rc %s\nssh_stderr_bytes %s\n' "$PC0" "$PC1" "$RC" "$(wc -c < "$OUT/ssh.stderr")" > "$OUT/run.txt" || WR=1
rm -f "$OUT/ssh.stderr" || WR=1          # ssh 표준오류 원문은 남기지 않는다(바이트 수만)
grep -cE '[0-9]{8,10}:[A-Za-z0-9_-]{30,}|Bearer [A-Za-z0-9._-]{8,}' "$OUT/remote.out" > "$OUT/token-check.txt"; [ "$(cat "$OUT/token-check.txt")" = 0 ] || { echo "🔴 반출 줄에 토큰 형태 — 보고만, 커밋 금지"; [ "$RC" != 0 ] || RC=4; }
(cd "$OUT" && sha256sum args.txt run.txt remote.out token-check.txt > PC-SHA256SUMS) || WR=1
if [ "$WR" = 0 ]; then rm -f "$I" || WR=1; fi
cat "$OUT/remote.out"
if [ "$WR" != 0 ]; then FR=4; [ "$RC" = 0 ] || FR=$RC; echo "🔴 기록 실패 — 원격 종료 $RC · 이 실행 종료 $FR. 미완료 표지 남김, 재실행 금지"; exit "$FR"; fi
echo "a3-preread 끝 — 원격 종료 $RC. 같은 폴더로 다시 읽지 않는다. 판정은 a3calc3.py 입력 작성 뒤(변화 근거·S 대상 칸은 아래 README §3-3 대로 채우지 못하면 보류)."
exit "$RC"
