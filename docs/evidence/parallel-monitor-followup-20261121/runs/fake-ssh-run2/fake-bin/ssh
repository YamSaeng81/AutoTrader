#!/usr/bin/env bash
# 가짜 ssh(총괄 v1.216 §3-2 시험 전용) — 네트워크 연결 없음. 원격 명령 문자열을 기록하고, 시나리오(FAKE_SCEN)에 맞는 고정 출력을 낸다.
# 환경: FAKE_SCEN 시나리오 · FAKE_DIR 기록 폴더 · FAKE_TSV 상태 SQL 고정 출력 · FAKE_LOGSRC backend 로그 고정 출력 · FAKE_L 보존폴더 경로
set -u
[ "$#" = 4 ] && [ "$1" = -o ] && [ "$2" = BatchMode=yes ] && [ "$3" = cat-prod ] || { echo "fake-ssh: 예상 밖 인자" >&2; exit 97; }
cmd=$4
n=$(( $(cat "$FAKE_DIR/calls.txt" 2>/dev/null | wc -l) + 1 ))
case "$cmd" in
  *"git rev-parse HEAD"*) kind=install ;;
  *" psql "*) kind=sql ;;
  "date -u +%FT%TZ") kind=until ;;
  *"logs --no-color"*) kind=logs ;;
  *) kind=unknown ;;
esac
printf '%s\t%s\t%s\n' "$n" "$kind" "$cmd" >> "$FAKE_DIR/calls.txt"
cat > "$FAKE_DIR/stdin.$n"          # 표준 입력 보존(SQL 은 -f - 로 들어온다)
[ "$kind" != unknown ] || { echo "fake-ssh: 알 수 없는 원격 명령" >&2; exit 98; }
S=$FAKE_SCEN
case "$kind" in
  install)
    [ "$S" = ssh_fail ] && { echo "ssh: connect to host cat-prod port 22: Connection refused" >&2; exit 255; }
    h=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d; img=sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f
    [ "$S" = head_mismatch ] && h=1111111111111111111111111111111111111111
    [ "$S" = image_mismatch ] && img=sha256:9999999999999999999999999999999999999999999999999999999999999999
    echo "server_utc 2026-11-22T13:25:00Z"; echo "HEAD $h"
    echo "backend Image=$img Status=running RestartCount=0 StartedAt=2026-11-16T05:16:14.129686133Z" ;;
  sql)
    [ "$S" = sql_fail ] && { echo "ERROR:  canceling statement due to statement timeout" >&2; exit 1; }
    if [ "$S" = flyway_mismatch ]; then sed '1s/\t96\trepeatable read$/\t95\trepeatable read/' "$FAKE_TSV"; else cat "$FAKE_TSV"; fi ;;
  until) echo "2026-11-22T13:25:05Z" ;;
  logs)
    [ "$S" = last_write_fail ] && mkdir "$FAKE_L/read-status.txt"   # 마지막 필수 기록(완료 줄) 대상이 쓰기 불가가 되도록
    [ "$S" = log_fail ] && { echo "Error response from daemon: fake log failure" >&2; exit 1; }
    cat "$FAKE_LOGSRC" ;;
esac
exit 0
