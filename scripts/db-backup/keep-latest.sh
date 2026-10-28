#!/bin/bash
# =============================================================================
# 최신 완료 백업 1개를 회전 삭제 대상 밖에 보존 (리뷰 22·23차, LIVE_READINESS §3-6 ⓑ)
#
# 사용법 (운영 서버, ~/crypto_trader/AutoTrader 에서):
#   bash keep-latest.sh                 # backups/ 의 가장 최근 파일
#   bash keep-latest.sh <파일>          # 특정 파일 (최신 파일이 검사에 실패했을 때 그 앞 파일)
# 환경 변수: BACKUP_SRC_DIR(기본 backups), KEEP_DIR(기본 ~/crypto_trader/backup_keep)
#
# 압축 검사 · 덤프 끝 표시 · 복사 · 해시 대조 중 하나라도 실패하면 즉시 중단하고 종료 코드 1.
# 그때는 보존 폴더에 그 파일을 남기지 않고, SHA256SUMS 에도 기록하지 않는다.
# 🔴 ./backups 안의 하위 폴더도 회전 삭제(find /backups …)에 걸리므로 보존 폴더는 프로젝트 밖에 둔다.
# =============================================================================
set -euo pipefail

SRC_DIR="${BACKUP_SRC_DIR:-backups}"
KEEP="${KEEP_DIR:-$HOME/crypto_trader/backup_keep}"

stop() { echo "[중단] $*" >&2; exit 1; }

if [ $# -ge 1 ]; then
    F="$1"
else
    F=$(ls -t "$SRC_DIR"/backup_*.sql.gz 2>/dev/null | head -n 1) || true
fi
[ -n "${F:-}" ] && [ -f "$F" ] || stop "백업 파일 없음: ${F:-(없음)}"
NAME=$(basename "$F")
ls -l "$F"

gzip -t "$F" 2>/dev/null || stop "압축 검사 실패: $NAME — 그 앞 파일로 다시 하세요"
echo "압축 검사 통과"
gunzip -c "$F" | tail -n 5 | grep -q "PostgreSQL database dump complete" \
    || stop "덤프 끝 표시 없음: $NAME — 그 앞 파일로 다시 하세요"
echo "덤프 끝 표시 있음"

SRC_SHA=$(sha256sum "$F" | cut -d' ' -f1)
[ -n "$SRC_SHA" ] || stop "원본 해시 계산 실패"

mkdir -p "$KEEP" || stop "보존 폴더 생성 실패: $KEEP"
chmod 700 "$KEEP" || stop "보존 폴더 권한 설정 실패"
[ ! -e "$KEEP/$NAME" ] || stop "이미 보존돼 있음: $KEEP/$NAME (덮어쓰지 않는다)"

TMP="$KEEP/.${NAME}.copying"
if ! cp -p "$F" "$TMP"; then rm -f "$TMP"; stop "복사 실패"; fi
COPY_SHA=$(sha256sum "$TMP" | cut -d' ' -f1) || { rm -f "$TMP"; stop "사본 해시 계산 실패"; }
if [ "$COPY_SHA" != "$SRC_SHA" ]; then rm -f "$TMP"; stop "사본 해시 불일치: 원본 $SRC_SHA / 사본 $COPY_SHA"; fi
if ! mv "$TMP" "$KEEP/$NAME"; then rm -f "$TMP"; stop "사본 확정(mv) 실패"; fi
chmod 600 "$KEEP/$NAME" || stop "사본 권한 설정 실패"
echo "$SRC_SHA  $NAME" >> "$KEEP/SHA256SUMS" || stop "SHA256SUMS 기록 실패"

echo "보존 완료: $KEEP/$NAME"
echo "크기: $(wc -c < "$KEEP/$NAME" | tr -d ' ') 바이트"
echo "SHA-256: $SRC_SHA"
