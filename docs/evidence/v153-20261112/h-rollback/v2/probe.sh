#!/usr/bin/env bash
# 총괄 v1.154 §4 — 관문 2판 기록 실패 검사(가짜 psql·sha256sum 만, DB/Docker/네트워크 없음) (2026-11-12).
# 사용: probe.sh <관문 스크립트> <새 출력 폴더>. 사례마다 기대 판정·종료 코드와 장부 상태를 대조한다.
set -u
GATE="${1:?관문 스크립트}"; ROOT="${2:?새 출력 폴더}"
[ -e "$ROOT" ] && { echo "출력 폴더가 이미 있음: $ROOT"; exit 9; }
mkdir -p "$ROOT"

# 가짜 psql: FAKE_PSQL 모드로 집계/COPY 결과를 정한다.
fake_psql() {
  local input; input="$(cat)"
  case "$input" in
    *"AS gate"*)
      [ "${FAKE_PSQL:-}" = "agg_fail" ] && { echo "FAKE aggregate failure" >&2; return 2; }
      printf '%s\n' 'GATE|v96=1|created=1|running=0|odd_session=0|open=0|closing=0|odd_position=0|stopped_with_open=0' 'DECIDED|closing_without_decided_at=0' ;;
    *"position WHERE"*)
      case "${FAKE_PSQL:-}" in
        csv_fail) echo "FAKE copy failure" >&2; return 1 ;;
        csv_stderr) printf 'id,status\n7,CLOSED\n'; echo "WARNING: fake notice" >&2 ;;
        csv_empty) : ;;
        *) printf 'id,status\n7,CLOSED\n' ;;
      esac ;;
    *) printf 'id,status\n1,STOPPED\n' ;;
  esac
}
# 가짜 sha256sum: FAKE_SHA 모드. 그 밖에는 진짜 명령.
sha256sum() {
  if [ "${1:-}" = "-c" ]; then command sha256sum "$@"; return; fi
  case "${FAKE_SHA:-}" in
    fail) echo "FAKE checksum failure" >&2; return 17 ;;
    empty_ok) return 0 ;;
    bogus) command sha256sum "$@" | awk 'NR==1{c=substr($0,1,1); $0=((c=="0")?"1":"0") substr($0,2)} {print}'; return 0 ;;
    *) command sha256sum "$@" ;;
  esac
}
export -f fake_psql sha256sum
export PSQL=fake_psql

PASS=0; FAIL=0
case_run() {  # $1 이름, $2 기대 판정, $3 기대 rc, $4 기대 장부 상태(complete|failed|none|untouched), 환경 변수…
  local name="$1" ev="$2" erc="$3" est="$4"; shift 4
  local out="$ROOT/$name.ledger"
  [ "$name" = "existing_path" ] && { mkdir -p "$out"; echo keep > "$out/old.txt"; }
  [ "$name" = "write_fail" ] && { echo "file, not dir" > "$ROOT/$name.parent"; out="$ROOT/$name.parent/ledger"; }
  local txt; txt="$(env "$@" bash "$GATE" "$out" 2>&1)"; local rc=$?
  local v; v="$(printf '%s\n' "$txt" | sed -n 's/^VERDICT \([A-Z_]*\).*/\1/p' | tail -1)"
  local st=none
  if [ -d "$out" ]; then
    if [ -f "$out/LEDGER_COMPLETE" ]; then st=complete; elif [ -f "$out/LEDGER_FAILED" ]; then st=failed;
    elif [ -f "$out/old.txt" ] && [ "$(ls "$out")" = "old.txt" ]; then st=untouched; else st=other; fi
  fi
  local okword=yes; printf '%s\n' "$txt" | grep -q '이후 종전 U되돌림' || okword=no
  { echo "== $name ($*)"; printf '%s\n' "$txt"; echo "exit=$rc ledger=$st okword=$okword"; [ -d "$out" ] && ls -la "$out"; } > "$ROOT/$name.txt" 2>&1
  local good=1
  [ "$v" = "$ev" ] && [ "$rc" = "$erc" ] && [ "$st" = "$est" ] || good=0
  [ "$ev" != "POST_CREATION_OK" ] && [ "$okword" = "yes" ] && good=0
  if [ $good = 1 ]; then PASS=$((PASS+1)); echo "[$name] 통과: $v exit=$rc 장부=$st 복구문구=$okword";
  else FAIL=$((FAIL+1)); echo "[$name] 실패: $v(기대 $ev) exit=$rc(기대 $erc) 장부=$st(기대 $est) 복구문구=$okword"; fi
}
case_run normal          POST_CREATION_OK 0 complete FAKE_PSQL=ok
case_run agg_fail        STOP_UNKNOWN     3 none     FAKE_PSQL=agg_fail
case_run csv_fail        LEDGER_REFUSED   4 failed   FAKE_PSQL=csv_fail
case_run csv_stderr      LEDGER_REFUSED   4 failed   FAKE_PSQL=csv_stderr
case_run csv_empty       LEDGER_REFUSED   4 failed   FAKE_PSQL=csv_empty
case_run write_fail      LEDGER_REFUSED   4 none     FAKE_PSQL=ok
case_run sha_fail        LEDGER_REFUSED   4 failed   FAKE_SHA=fail
case_run sha_empty_ok    LEDGER_REFUSED   4 failed   FAKE_SHA=empty_ok
case_run sha_mismatch    LEDGER_REFUSED   4 failed   FAKE_SHA=bogus
case_run existing_path   LEDGER_REFUSED   4 untouched FAKE_PSQL=ok
echo "검사 결과: 통과 $PASS / 실패 $FAIL"
[ "$FAIL" -eq 0 ]
