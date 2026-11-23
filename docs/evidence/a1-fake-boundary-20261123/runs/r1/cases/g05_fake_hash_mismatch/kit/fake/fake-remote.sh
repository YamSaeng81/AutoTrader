#!/usr/bin/env bash
# A1 시험 전용 가짜 원격 프로그램 (v1.225 §3). 파일 입력/출력만 한다:
#   고정 응답 파일을 읽어 표준 출력/표준 오류로 내고, 호출 기록을 A1_FAKE_LOG 에 덧붙인다.
#   원격 접속·컨테이너·HTTP·하위 셸 원격 명령을 실행하지 않는다(그런 도구 이름을 이 파일에 두지 않는다).
# 사용: fake-remote.sh --identify            → 고유 식별 응답 1줄
#       fake-remote.sh <host> <원격 명령 문자열> < 표준 입력
# 환경: A1_FAKE_MODE(합성 응답 종류) · A1_FAKE_LOG(호출 기록 파일) · A1_FAKE_OUT(recfail 모드에서만, 실행기 보존폴더)
set -u
ID="A1-FAKE-REMOTE v1 file-io-only 5f0c2a61"
if [ "${1:-}" = --identify ]; then printf '%s\n' "$ID"; exit 0; fi
LOG=${A1_FAKE_LOG:?A1_FAKE_LOG 없음}; M=${A1_FAKE_MODE:?A1_FAKE_MODE 없음}
FX="$(cd "$(dirname "$0")" && pwd)/fixtures"
host=${1:-}; cmd=${2:-}
case "$cmd" in
  *server_utc*) kind=install ;;
  *ON_ERROR_STOP=1*) kind=sql ;;
  *) kind=unexpected ;;
esac
n=1; [ -f "$LOG" ] && n=$(( $(wc -l < "$LOG") + 1 ))
printf 'call %s host=%s kind=%s mode=%s\n' "$n" "$host" "$kind" "$M" >> "$LOG"
cat > "$LOG.stdin.$n"            # 받은 표준 입력 보존(SQL 호출이면 고정 SQL 해시 대조용)
[ "$host" = a1-test.invalid ] || { echo "fake: 시험 대상이 아닌 host 거절" >&2; exit 98; }
[ "$kind" != unexpected ] || { echo "fake: 예상하지 않은 명령" >&2; exit 99; }
if [ "$M" = ssh255 ]; then echo "fake: 합성 연결 실패(종료 255 모사)" >&2; exit 255; fi
if [ "$kind" = install ]; then
  printf 'server_utc %s\n' "$(date -u +%FT%TZ)"
  if [ "$M" = pinbad ]; then echo "HEAD ffffffffffffffffffffffffffffffffffffffff"; else echo "HEAD 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d"; fi
  cat "$FX/install-backend.txt"
  [ "$M" = install_stderr ] && echo "fake: 합성 경고(설치 상태 rc0 + 표준오류)" >&2
  [ "$M" = install_recfail ] && mkdir "${A1_FAKE_OUT:?}/rc.txt"   # 다음 rc 기록이 실패하도록 같은 이름의 폴더를 만든다
  exit 0
fi
# kind=sql
case "$M" in
  empty)      exit 0 ;;
  noguard)    echo "(합성) <stdin>:41: ERROR:  A1_GUARD 보호값 불일치 statement_timeout=0 lock_timeout=0 (합성)" >&2; exit 3 ;;
  sql_stderr) cat "$FX/a1-ok.tsv"; echo "WARNING:  합성 경고(SQL rc0 + 표준오류)" >&2; exit 0 ;;
  sumbad)     sed 's/^CNT\t6\t/CNT\t7\t/' "$FX/a1-ok.tsv"; exit 0 ;;
  flywaybad)  sed '1s/\t96\trepeatable read/\t95\trepeatable read/' "$FX/a1-ok.tsv"; exit 0 ;;
  *)          cat "$FX/a1-ok.tsv"; exit 0 ;;
esac
# 변경
