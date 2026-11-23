#!/bin/bash
# A2 보정 시험 전용 가짜 psql(파일 대역). 표준입력(SQL)은 버리고 A2T_CASE 에 따라 고정 출력·종료값을 낸다. 네트워크·DB 호출 없음.
cat > /dev/null
echo "$A2T_CASE" >> "$A2T_KIT/psql-calls.txt"
R12='LIVE | 0
DYNAMIC | 0
LIVE | -1234.5
DYNAMIC | 0'
ROW='7 | 1 | 0 | 987654.321 | 0.01 | 1 | 987000.111 | 0.01 | 0 | 0 | LIVE | 203 | 2026-11-24 | KRW-BTC | x | 1'
case "$A2T_CASE" in
  ok|vdir) printf '%s\n%s\n' "$R12" "$ROW"; exit 0;;
  partial) printf '%s\n%s\n' "$R12" "$ROW"; echo 'ERROR:  SENTINEL_ERR near 555444.333' >&2; exit 3;;
  empty) echo 'psql: error: SENTINEL_ERR connection 555444.333' >&2; exit 2;;
  odir) printf '%s\n' "$R12"; exit 0;;
  fmt) printf '%s\n%s\n' "$R12" '7 | 1 | 0 | 987654.321 | 0.01'; exit 0;;
  r1) printf 'LIVE | 1\nDYNAMIC | 0\nLIVE | -1234.5\nDYNAMIC | 0\n%s\n' "$ROW"; exit 0;;
  *) echo "알 수 없는 시험 사례" >&2; exit 97;;
esac
