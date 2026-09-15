#!/bin/bash
# =============================================================================
# DB 복원 드릴 스크립트 — 20260415_analy.md Tier 4 §17
#
# 목적: 백업 파일로부터 새 Postgres 컨테이너에 복원하고 스키마를 검증한다.
#       "복원 해본 적 없는 백업은 백업이 아니다" — 월 1회 실행 권장.
#
# 사용법:
#   ./scripts/db-restore-drill.sh                      # 최신 백업 파일 자동 선택
#   ./scripts/db-restore-drill.sh backups/backup_X.sql.gz  # 특정 파일 지정
#
# 전제 조건:
#   - Docker가 실행 중이어야 한다.
#   - backups/ 디렉토리에 *.sql.gz 파일이 1건 이상 있어야 한다.
#   - DB_PASSWORD 환경변수 또는 .env 파일이 필요하다.
# =============================================================================
set -euo pipefail

# ── 설정 ──────────────────────────────────────────────────────────────────────
RESTORE_CONTAINER="crypto-restore-drill"
RESTORE_DB="crypto_auto_trader_restore"
RESTORE_PORT="15432"
DB_USER="trader"
BACKUP_DIR="./backups"
REQUIRED_TABLES=(
    "live_trading_session"
    "position"
    "order_details"
    "trade_log"
    "backtest_run"
    "candle_data"
    "execution_drift_log"
)

# ── 색상 출력 ─────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

# ── DB_PASSWORD 로드 ──────────────────────────────────────────────────────────
if [ -z "${DB_PASSWORD:-}" ]; then
    if [ -f ".env" ]; then
        export $(grep -v '^#' .env | xargs)
    fi
fi
if [ -z "${DB_PASSWORD:-}" ]; then
    fail "DB_PASSWORD 환경변수가 설정되지 않았습니다. .env 파일 또는 export DB_PASSWORD=... 필요."
fi

# ── 백업 파일 선택 ────────────────────────────────────────────────────────────
if [ $# -ge 1 ]; then
    BACKUP_FILE="$1"
else
    BACKUP_FILE=$(ls -t "${BACKUP_DIR}"/*.sql.gz 2>/dev/null | head -1)
fi

if [ -z "${BACKUP_FILE:-}" ] || [ ! -f "${BACKUP_FILE}" ]; then
    fail "백업 파일을 찾을 수 없습니다: ${BACKUP_FILE:-'(없음)'}. backups/ 디렉토리를 확인하세요."
fi
ok "복원 대상 백업 파일: ${BACKUP_FILE}"

# ── 기존 드릴 컨테이너 정리 ──────────────────────────────────────────────────
echo "기존 드릴 컨테이너 정리 중..."
docker rm -f "${RESTORE_CONTAINER}" 2>/dev/null || true

# ── 새 Postgres 컨테이너 시작 ─────────────────────────────────────────────────
echo "복원용 Postgres 컨테이너 시작 중..."
docker run -d \
    --name "${RESTORE_CONTAINER}" \
    -e POSTGRES_USER="${DB_USER}" \
    -e POSTGRES_PASSWORD="${DB_PASSWORD}" \
    -e POSTGRES_DB="${RESTORE_DB}" \
    -p "${RESTORE_PORT}:5432" \
    timescale/timescaledb:latest-pg15

# ── Postgres 준비 대기 ────────────────────────────────────────────────────────
echo "Postgres 준비 대기 중..."
for i in $(seq 1 30); do
    if docker exec "${RESTORE_CONTAINER}" pg_isready -U "${DB_USER}" -q 2>/dev/null; then
        ok "Postgres 준비 완료 (${i}초)"
        break
    fi
    if [ "$i" -eq 30 ]; then
        docker rm -f "${RESTORE_CONTAINER}" 2>/dev/null || true
        fail "Postgres 30초 내 준비 실패"
    fi
    sleep 1
done

# ── 복원 실행 ────────────────────────────────────────────────────────────────
echo "백업 복원 중 (${BACKUP_FILE})..."
if gunzip -c "${BACKUP_FILE}" | docker exec -i "${RESTORE_CONTAINER}" \
        psql -U "${DB_USER}" -d "${RESTORE_DB}" -q 2>&1; then
    ok "복원 완료"
else
    docker rm -f "${RESTORE_CONTAINER}" 2>/dev/null || true
    fail "복원 실패 — psql 오류 발생"
fi

# ── 스키마 검증 ──────────────────────────────────────────────────────────────
echo "스키마 검증 중..."
FAILED_TABLES=()
for TABLE in "${REQUIRED_TABLES[@]}"; do
    COUNT=$(docker exec "${RESTORE_CONTAINER}" psql -U "${DB_USER}" -d "${RESTORE_DB}" -tAc \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='${TABLE}';" 2>/dev/null || echo "0")
    if [ "${COUNT:-0}" -gt 0 ]; then
        ok "  테이블 존재: ${TABLE}"
    else
        warn "  테이블 없음: ${TABLE}"
        FAILED_TABLES+=("${TABLE}")
    fi
done

# ── 행 수 요약 ───────────────────────────────────────────────────────────────
echo ""
echo "=== 주요 테이블 행 수 ==="
for TABLE in live_trading_session position backtest_run candle_data; do
    ROWS=$(docker exec "${RESTORE_CONTAINER}" psql -U "${DB_USER}" -d "${RESTORE_DB}" -tAc \
        "SELECT COUNT(*) FROM ${TABLE};" 2>/dev/null || echo "조회 실패")
    echo "  ${TABLE}: ${ROWS} 행"
done

# ── 컨테이너 정리 ────────────────────────────────────────────────────────────
docker rm -f "${RESTORE_CONTAINER}" 2>/dev/null || true
ok "드릴 컨테이너 정리 완료"

# ── 결과 요약 ────────────────────────────────────────────────────────────────
echo ""
if [ ${#FAILED_TABLES[@]} -eq 0 ]; then
    ok "복원 드릴 성공 — 모든 필수 테이블 확인됨"
    echo "드릴 실행일: $(date '+%Y-%m-%d %H:%M:%S')" >> "${BACKUP_DIR}/drill-log.txt"
    echo "사용 파일: ${BACKUP_FILE}" >> "${BACKUP_DIR}/drill-log.txt"
    echo "결과: SUCCESS" >> "${BACKUP_DIR}/drill-log.txt"
    echo "---" >> "${BACKUP_DIR}/drill-log.txt"
    exit 0
else
    fail "복원 드릴 실패 — 누락 테이블: ${FAILED_TABLES[*]}"
fi
