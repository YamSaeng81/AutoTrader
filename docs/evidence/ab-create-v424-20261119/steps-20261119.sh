#!/usr/bin/env bash
# 11-19 생성 제안값을 고정한 서버 단계 실행 파일(총괄 v1.188 §4, 11-18 판 bdf0a764 의 날짜·경로 차분). 🔴 실제 생성 승인 전 실행 금지. 승인이 11-19 12:00 KST 까지 없으면 이 파일은 폐기하고
# 새 날짜 값으로 새 파일을 만들어 다시 제출한다(값을 손으로 고쳐 쓰지 않는다).
# 서버 manicstr 셸에서, 배치된 폴더(~/ab-run-20261119) 안에서 단계마다 한 번:  bash steps-20261119.sh <0~5>
# 이 파일은 묶음 해시·서버 KST 날짜를 확인한 뒤 run_bundle.sh v4.2.4 를 승인값 그대로 부른다. 자동 재시도·다음 단계 자동 진행 없음.
# 종료값: run_bundle.sh 의 값 그대로 · 20 인자 이상 · 21 폴더/묶음 해시 불일치 · 22 서버 KST 날짜가 승인 날짜 아님 · 23 토큰 파일 없음(STEP 0 전 운영자 준비)
set -u
export LC_ALL=C
# ---- 승인 요청값(이 판의 고정값) ----
AB_DATE=2026-11-19
AB_C=2026-11-19T14:00:00+09:00
AB_T0=2026-11-19T14:15:00+09:00
SMA20_WS=2026-11-19T15:00:00+09:00
SMA20_WE=2026-11-19T15:10:00+09:00
SMA20_T0=2026-11-20T09:00:00+09:00
R=/home/manicstr/ab-run-20261119
OUT=$R/run-20261119
REPO=/home/manicstr/crypto_trader/AutoTrader
TOK=/home/manicstr/.cat-token-20261119
BUNDLE_LIST_SHA=e28b1293bca3dbabbb885be44ce57ff03e81b7fd21af8b5d0a2e7a5e476c3880   # bundle/SHA256SUMS.bundle (16개 목록)
# -----------------------------------
S=${1:-}
case "$S" in 0|1|2|3|4|5) ;; *) echo "STOP 20: 단계는 0~5"; exit 20 ;; esac
[ "$(pwd -P)" = "$R" ] || { echo "STOP 21: $R 안에서 부른다(지금 $(pwd -P))"; exit 21; }
h=$(sha256sum < bundle/SHA256SUMS.bundle | cut -d' ' -f1); [ "$h" = "$BUNDLE_LIST_SHA" ] || { echo "STOP 21: 묶음 목록 해시 다름($h)"; exit 21; }
sha256sum --quiet --strict -c bundle/SHA256SUMS.bundle; r=$?; echo "묶음 16개 해시 rc=$r"; [ $r -eq 0 ] || { echo "STOP 21: 묶음 해시 불일치"; exit 21; }
d=$(TZ=Asia/Seoul date +%F); echo "서버 KST $(TZ=Asia/Seoul date '+%F %T') · 승인 날짜 $AB_DATE"
[ "$d" = "$AB_DATE" ] || { echo "STOP 22: 서버 KST 날짜($d)가 승인 날짜가 아님 — 보내지 않음"; exit 22; }
[ -f "$TOK" ] || { echo "STOP 23: 토큰 파일 없음(운영자 준비 전)"; exit 23; }
echo "단계 $S 시작 $(date -u +%FT%TZ)"
case "$S" in
  2) env STEP=2 REPO_DIR="$REPO" TOKEN_FILE="$TOK" OUT_DIR="$OUT" AB_DATE="$AB_DATE" AB_C="$AB_C" AB_T0="$AB_T0" bash bundle/run_bundle.sh ;;
  4) env STEP=4 REPO_DIR="$REPO" TOKEN_FILE="$TOK" OUT_DIR="$OUT" SMA20_WS="$SMA20_WS" SMA20_WE="$SMA20_WE" SMA20_T0="$SMA20_T0" bash bundle/run_bundle.sh ;;
  *) env STEP="$S" REPO_DIR="$REPO" TOKEN_FILE="$TOK" OUT_DIR="$OUT" bash bundle/run_bundle.sh ;;
esac
r=$?; echo "단계 $S 끝 $(date -u +%FT%TZ) rc=$r"; exit $r
