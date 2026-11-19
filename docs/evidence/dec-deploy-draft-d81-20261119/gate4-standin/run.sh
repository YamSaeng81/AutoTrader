#!/usr/bin/env bash
# 관문4판(dec-costbasis-followup-20261119/rollback-gate-v4-draft, 원문 무변경) 판정 순서 대역. 사용: run.sh <작업 폴더(공백 없는 경로)>
set -u
W="${1:?}"; HERE="$(cd "$(dirname "$0")" && pwd)"
GATE="$HERE/../../dec-costbasis-followup-20261119/rollback-gate-v4-draft/rollback_gate.sh"
mkdir -p "$W"; cp "$HERE/fake_psql.sh" "$W/fake_psql.sh"
export PSQL="bash $W/fake_psql.sh"
base() { echo "GATE|v96=1|created=0|running=0|odd_session=0|open=0|closing=0|odd_position=0|stopped_with_open=0|unresolved_orders=${UO:-0}|acct_pending=${AP:-0}|applied_missing=0|reported_ne_applied=0|cleanup=${CL:-0}|discrepancy=${DS:-0}|real_closing=${RC_:-0}|real_running=0|real_held=0|qty_mismatch=0|balance_mismatch=0${TAIL-|exit_reason_unknown_to_old=${UK:-0}}"; }
case_() { # 이름 기대판정 기대rc 장부경로
  local name="$1" want="$2" wrc="$3" out="$4"
  out_txt="$(bash "$GATE" "$out" 2>&1)"; rc=$?
  v="$(printf '%s\n' "$out_txt" | grep '^VERDICT' | awk '{print $2}')"
  led=$([ -e "$out/LEDGER_COMPLETE" ] && echo complete || ([ -e "$out" ] && echo partial || echo none))
  ok=$([ "$v" = "$want" ] && [ "$rc" = "$wrc" ] && echo PASS || echo FAIL)
  printf '%s\t%s\twant=%s/%s\tgot=%s/%s\tledger=%s\n' "$ok" "$name" "$want" "$wrc" "$v" "$rc" "$led"
  printf '%s\n' "$out_txt" > "$HERE/out-$name.txt"
}
n=0
t() { n=$((n+1)); export FAKE_GATE="$(base)"; case_ "$@"; unset FAKE_QUERY_FAIL FAKE_COPY_FAIL; UO=0 AP=0 CL=0 DS=0 RC_=0 UK=0; unset TAIL; }
UK=1;                       t S01_entry_rejected_present      STOP_INCOMPATIBLE 3 "$W/l01"
UK=1 UO=2 AP=1;             t S02_present_plus_open_orders    STOP_INCOMPATIBLE 3 "$W/l02"
UK=1 CL=1 DS=1 RC_=1;       t S03_present_plus_cleanup_settle STOP_INCOMPATIBLE 3 "$W/l03"
UK=1; mkdir -p "$W/l04";    t S04_present_ledger_path_exists  STOP_INCOMPATIBLE 3 "$W/l04"
UK=0;                       t S05_absent_clean                ROLLBACK_GATE_OK  0 "$W/l05"
UK=0 UO=1;                  t S06_absent_open_order           HOLD_PENDING      2 "$W/l06"
UK=0 AP=1 CL=1;             t S07_absent_acct_cleanup         HOLD_PENDING      2 "$W/l07"
export FAKE_QUERY_FAIL=1;   t S08_query_failure               STOP_UNKNOWN      3 "$W/l08"
TAIL="";                    t S09_v3_line_no_new_key          STOP_UNKNOWN      3 "$W/l09"
UK=0; export FAKE_COPY_FAIL=1; t S10_ledger_write_failure     LEDGER_REFUSED    4 "$W/l10"
UK=0; mkdir -p "$W/l11";    t S11_absent_ledger_path_exists   LEDGER_REFUSED    4 "$W/l11"
