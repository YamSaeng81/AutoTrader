#!/usr/bin/env bash
root_fake_psql() {
  local input
  input="$(cat)"
  case "$input" in
    *"AS gate"*) printf '%s\n' 'GATE|v96=1|created=1|running=0|odd_session=0|open=0|closing=0|odd_position=0|stopped_with_open=0' 'DECIDED|closing_without_decided_at=0' ;;
    *) printf '%s\n' 'id,status' '1,STOPPED' ;;
  esac
}
sha256sum() { printf 'ROOT deliberate checksum failure\n' >&2; return 17; }
export -f root_fake_psql sha256sum
export PSQL=root_fake_psql
bash "$1" "$2"
