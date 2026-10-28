#!/bin/sh
# =============================================================================
# db-backup 컨테이너 백업 루프 (리뷰 22차, 2026-10-28)
#
# 고친 결함: 예전 루프는 `pg_dump | gzip > 파일 && echo 백업 완료` 였다. 파이프의 종료 코드는
# gzip 의 것이라 pg_dump 가 실패해도 "백업 완료" 가 찍히고 빈 파일(20 바이트)이 남았다(2026-10-21 실제 발생).
#
# 지금 규칙:
#   - 덤프는 임시 이름(*.sql.gz.part)에 쓴다. 회전 삭제 대상(*.sql.gz)과 겹치지 않는다.
#   - pg_dump 종료 코드 0 · gzip 종료 코드 0 · gzip -t 통과 · 덤프 끝 표시 · 최소 크기 이상 → 그때만 *.sql.gz 로 이름을 바꿔 확정한다.
#   - 하나라도 실패하면 임시 파일을 지우고 "백업 실패" 를 표준 오류에 남기고, 텔레그램 경보를 보낸다.
#     실패한 회차에는 회전 삭제를 하지 않는다(정상 백업이 지워지지 않게). 1시간 뒤 다시 시도한다.
#   - 성공하면 /backups/.last_success 를 갱신한다. 컨테이너 healthcheck 가 이 파일의 나이를 본다.
#   - 시작 직후 DB 가 아직 준비되지 않았으면 최대 10분 기다린다(재시작 직후 실패 방지).
#
# 환경 변수: PGPASSWORD(필수), BACKUP_DB_HOST(기본 db), BACKUP_DB_USER(기본 trader),
#            BACKUP_DB_NAME(기본 crypto_auto_trader), BACKUP_DIR(기본 /backups),
#            BACKUP_KEEP_DAYS(기본 7), BACKUP_INTERVAL(기본 86400), BACKUP_RETRY(기본 3600),
#            BACKUP_MIN_BYTES(기본 1048576), TELEGRAM_BOT_TOKEN·TELEGRAM_CHAT_ID(없으면 경보 생략),
#            BACKUP_ONCE=1 이면 한 회차만 돌고 그 결과로 종료한다(로컬 시험용).
# 🔴 비밀 값(PGPASSWORD·TELEGRAM_BOT_TOKEN)은 출력하지 않는다.
# =============================================================================
set -u

HOST="${BACKUP_DB_HOST:-db}"
USER_="${BACKUP_DB_USER:-trader}"
DB="${BACKUP_DB_NAME:-crypto_auto_trader}"
DIR="${BACKUP_DIR:-/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
INTERVAL="${BACKUP_INTERVAL:-86400}"
RETRY="${BACKUP_RETRY:-3600}"
MIN_BYTES="${BACKUP_MIN_BYTES:-1048576}"

log()  { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }
err()  { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" >&2; }

# 텔레그램 경보. 토큰이 들어간 주소가 로그에 남지 않도록 wget 출력은 모두 버린다.
alert() {
    if [ -z "${TELEGRAM_BOT_TOKEN:-}" ] || [ -z "${TELEGRAM_CHAT_ID:-}" ]; then
        err "경보 생략: 텔레그램 설정 없음"
        return 0
    fi
    if wget -q -T 20 -O /dev/null \
            --post-data "chat_id=${TELEGRAM_CHAT_ID}&text=[db-backup] $1" \
            "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" >/dev/null 2>&1; then
        log "경보 전송"
    else
        err "경보 전송 실패"
    fi
}

wait_db() {
    i=0
    while [ "$i" -lt 60 ]; do
        pg_isready -h "$HOST" -U "$USER_" -d "$DB" -q && return 0
        i=$((i + 1)); sleep 10
    done
    return 1
}

# 한 회차. 성공하면 0, 실패하면 1.
backup_once() {
    stamp=$(date +%Y%m%d_%H%M%S)
    final="$DIR/backup_${stamp}.sql.gz"
    part="${final}.part"
    rcfile="$DIR/.dump_rc.$$"

    if ! wait_db; then
        err "백업 실패: DB 준비 대기 10분 초과 ($HOST)"
        alert "백업 실패: DB 준비 대기 10분 초과"
        return 1
    fi

    # pg_dump 의 종료 코드를 파일로 받아 gzip 의 것과 따로 본다(파이프 종료 코드는 gzip 것뿐이다).
    { pg_dump -h "$HOST" -U "$USER_" "$DB"; echo $? > "$rcfile"; } | gzip > "$part"
    gz_rc=$?
    dump_rc=$(cat "$rcfile" 2>/dev/null || echo "없음"); rm -f "$rcfile"

    reason=""
    if [ "$dump_rc" != "0" ]; then reason="pg_dump 종료 코드 $dump_rc"
    elif [ "$gz_rc" -ne 0 ]; then reason="gzip 종료 코드 $gz_rc"
    elif ! gzip -t "$part" 2>/dev/null; then reason="압축 검사(gzip -t) 실패"
    elif ! gunzip -c "$part" | tail -n 5 | grep -q "PostgreSQL database dump complete"; then reason="덤프 끝 표시 없음"
    else
        size=$(wc -c < "$part" | tr -d ' ')
        [ "$size" -lt "$MIN_BYTES" ] && reason="크기 ${size} 바이트 < 최소 ${MIN_BYTES}"
    fi

    if [ -n "$reason" ]; then
        rm -f "$part"
        err "백업 실패: $reason (확정 파일 없음, 회전 삭제 건너뜀)"
        alert "백업 실패: $reason"
        return 1
    fi

    mv "$part" "$final"
    date -u +%Y-%m-%dT%H:%M:%SZ > "$DIR/.last_success"
    log "백업 완료: $final (${size} 바이트, 덤프·압축·검사 통과)"
    # 회전 삭제는 이번 회차가 성공했을 때만, 확정 파일(*.sql.gz)만 대상으로 한다.
    find "$DIR" -maxdepth 1 -name 'backup_*.sql.gz' -mtime +"$KEEP_DAYS" -print -delete | sed 's/^/회전 삭제: /'
    return 0
}

mkdir -p "$DIR"
# 지난 실행이 중간에 끊겨 남은 임시 파일 정리
find "$DIR" -maxdepth 1 -name '*.sql.gz.part' -delete

while true; do
    if backup_once; then
        [ "${BACKUP_ONCE:-0}" = "1" ] && exit 0
        sleep "$INTERVAL"
    else
        [ "${BACKUP_ONCE:-0}" = "1" ] && exit 1
        sleep "$RETRY"
    fi
done
