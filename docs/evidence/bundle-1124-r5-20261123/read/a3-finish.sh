#!/usr/bin/env bash
# A3 관측 수집 PC 마무리 a3-finish-2 (총괄 v1.229 §3 R2). **파일 전용** — ssh·서버·네트워크 호출 없음. 검토용 원문, 운영 미실행.
# a3-preread.sh 가 ssh 1회 뒤 해시 확인한 이 파일을 부른다: bash a3-finish.sh <보존 폴더> <원격 종료값> <앞 기록 실패 0|1>
# r4(a3-preread-1 뒷부분) 대비:
#   - 반출 검사: grep 종료값으로 '발견 0건(1)'·'발견(0)'·'검사 실패(2 이상)'를 구분한다. 허용 표지·문자 집합 밖 줄도 불통과.
#     불통과면 본문 출력·확정·후속 소비 없이 고정 사유만 낸다. 검사할 값을 지워 통과시키지 않는다(remote.out 은 그대로 보존).
#   - 순서: 반출 검사 → PC-SHA256SUMS → RESULT.txt(마지막 필수 기록) → 본문 출력 → 완료 문구 → 미완료 표지 제거.
#     각 단계의 성공을 확인하며, 미완료 표지 제거가 성공 확정의 마지막 동작이다. 그 뒤에 반드시 성공해야 할 기록은 없다.
#   - 앞 단계 비영 종료값을 보존한다. 앞 단계 성공 + 기록 실패는 종료 4 와 미완료 표지로 끝낸다.
#   - 원격이 실패했으면 본문(데이터 줄)은 내지 않고 고정 사유 줄(STOP·ACCT_FAIL·LEDGER_FAIL)만 낸다.
# 후속 소비 조건: RESULT.txt 첫 줄이 'OK a3-finish-2' 이고 INCOMPLETE.txt 가 **없을 때만** 결과를 a3calc3.py 입력으로 쓴다.
# 종료값: 0 확정 · 원격 비영 값 그대로 · 4 기록/반출 검사 실패 · 9 반출 줄 토큰 형태 발견
set -u
OUT=$1; RC=$2; PREWR=${3:-0}
R="$OUT/remote.out"; I="$OUT/INCOMPLETE.txt"
TOKRE='[0-9]{8,10}:[A-Za-z0-9_-]{30,}|Bearer[[:space:]]+[A-Za-z0-9._~+/=-]{8,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|[Aa]uthorization'
TAGRE='^(SERVER_START|WINDOW_OK|LEDGER_CALL|APP_CLOCK_1|APP_CLOCK_2|ACCOUNT_CALL|ACCT|ACCT_VALUATION|META|SCOPE|TSES|OBUY|CNT|XCNT|POS|MAXTS|MORD|END|SERVER_SHA256SUMS|STDERR_BYTES|SERVER_END|STOP|ACCT_FAIL|LEDGER_FAIL)([ 	]|$)'
deny() {   # 고정 사유만. 본문·remote.out 은 출력하지 않는다. 미완료 표지는 남긴다.
  local code=$1 why=$2
  printf 'FAIL %s %s\n' "$code" "$why" >> "$OUT/FAILED.txt" 2>/dev/null
  echo "🔴 $why(종료 $code) — 본문 출력·확정·후속 소비 없음, 미완료 표지 유지, 재실행 전 보고"
  exit "$code"
}
fr() { if [ "$RC" != 0 ]; then echo "$RC"; else echo "$1"; fi; }   # 앞 단계 비영 값 보존
[[ "$RC" =~ ^[0-9]{1,3}$ ]] || { RC=4; deny 4 REMOTE_RC_INVALID; }
[ -e "$I" ] || deny "$(fr 4)" INCOMPLETE_MARKER_MISSING
# 1) 반출 검사 — 토큰 형태
grep -cE -- "$TOKRE" "$R" > "$OUT/token-check.txt" 2>/dev/null; G=$?
case "$G" in
  1) [ "$(cat "$OUT/token-check.txt" 2>/dev/null)" = 0 ] || deny "$(fr 4)" EXPORT_CHECK_FAILED ;;
  0) deny 9 EXPORT_TOKEN_SHAPE_FOUND ;;
  *) deny "$(fr 4)" EXPORT_CHECK_FAILED ;;
esac
# 1-2) 반출 검사 — 허용 표지·문자 집합(탭·공백·ASCII 표지 문자만). 모르는 줄이 하나라도 있으면 불통과.
grep -cvE -- "$TAGRE" "$R" > "$OUT/tag-check.txt" 2>/dev/null; G=$?
case "$G" in
  1) ;;
  0) deny "$(fr 4)" EXPORT_UNKNOWN_LINE ;;
  *) deny "$(fr 4)" EXPORT_CHECK_FAILED ;;
esac
LC_ALL=C grep -cvE '^[A-Za-z0-9_ 	.:=+-]*$' "$R" > "$OUT/charset-check.txt" 2>/dev/null; G=$?
case "$G" in
  1) ;;
  0) deny "$(fr 4)" EXPORT_CHARSET_INVALID ;;
  *) deny "$(fr 4)" EXPORT_CHECK_FAILED ;;
esac
# 2) 원격 실패: 고정 사유 줄만(검사를 통과한 줄 중에서). 데이터 줄은 내지 않는다.
if [ "$RC" != 0 ]; then
  grep -E '^(STOP|ACCT_FAIL|LEDGER_FAIL) ' "$R"
  (cd "$OUT" && sha256sum args.txt run.txt remote.out token-check.txt > PC-SHA256SUMS) 2>/dev/null
  deny "$RC" REMOTE_FAILED
fi
# 원격 0 이라도 완결 표지가 없으면 확정하지 않는다
grep -qE '^STOP ' "$R"; [ $? = 1 ] || deny 4 REMOTE_OUTPUT_HAS_STOP
for t in 'SERVER_END ' 'SCOPE	OK	LIVE	203' 'ACCT ' 'ACCT_VALUATION ' 'SERVER_SHA256SUMS ' 'END	'; do
  grep -qF -- "$t" "$R" || deny 4 REMOTE_OUTPUT_INCOMPLETE
done
[ "$PREWR" = 0 ] || deny 4 PRE_RECORD_FAILED
# 3) 기록 → 4) 본문 → 5) 확정
(cd "$OUT" && sha256sum args.txt run.txt remote.out token-check.txt > PC-SHA256SUMS) || deny 4 PC_MANIFEST_FAILED
{ printf 'OK a3-finish-2\nremote_rc 0\npc_manifest_sha256 %s\n' "$(sha256sum < "$OUT/PC-SHA256SUMS" | cut -d' ' -f1)" > "$OUT/RESULT.txt"; } 2>/dev/null \
  || deny 4 RESULT_RECORD_FAILED
cat -- "$R" || deny 4 BODY_PRINT_FAILED
echo "a3-preread 끝 — 원격 종료 0. 같은 폴더로 다시 읽지 않는다. 판정은 a3calc3.py 입력 작성 뒤(변화 근거·S 대상 칸은 채우지 못하면 보류)." \
  || deny 4 FINAL_MESSAGE_FAILED
rm -f -- "$I" && [ ! -e "$I" ] || deny 4 INCOMPLETE_REMOVE_FAILED
exit 0
