#!/usr/bin/env bash
# 관문4판 대역용 가짜 psql — 표준 입력이 gate.sql 이면 FAKE_GATE 줄을, COPY 이면 CSV 1줄을 낸다. DB 접속 없음.
IN="$(cat)"
case "$IN" in
  *"COPY ("*) [ -n "${FAKE_COPY_FAIL:-}" ] && { echo "ERROR: fake copy failure" >&2; exit 1; }; printf 'id\n1\n';;
  *) [ -n "${FAKE_QUERY_FAIL:-}" ] && { echo "psql: error: connection refused (fake)" >&2; exit 2; }
     printf '%s\nDECIDED|closing_without_decided_at=0\n' "$FAKE_GATE";;
esac
