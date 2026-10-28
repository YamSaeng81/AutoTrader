#!/bin/bash
# =============================================================================
# DB 복구 확인 (리뷰 22차, 2026-10-28) — 서버 밖 사본 1개를 격리된 로컬 컨테이너에 복구해 본다.
#
# 목적: 기존 66세션 자료 보호. 백업 파일이 실제로 복구되는지만 확인한다.
#       확인 범위 = 복원 오류 없음 · 스키마(필수 테이블·마이그레이션 이력) · 66세션 식별자 · 주요 테이블 행 수.
#       🔴 중간 성과(손익·수익률·잔액)는 계산하지 않는다.
#
# 사용법 (운영 서버가 아니라 서버 밖 사본이 있는 PC 에서):
#   scripts/db-restore-drill.sh <backup.sql.gz> <기대 SHA-256> <이미지> <기대 TimescaleDB 버전>
#   예) scripts/db-restore-drill.sh d:/private/backup_20261028_044929.sql.gz 3f…e1 \
#          timescale/timescaledb:2.17.2-pg15 2.17.2
#   이미지·버전은 운영 DB 에서 읽은 값(§3-6 ⓐ)과 같아야 한다. 다르면 시작 전에 실패한다.
#
# 예전 스크립트(Tier 4 §17)에서 고친 것:
#   - 고정 포트(15432) 공개 → 포트를 열지 않는다(docker exec 로만 접근). 컨테이너 이름도 실행마다 다르다.
#   - 오래된 테이블 목록(V73 에서 삭제된 trade_log 등) → 66세션 장부 3개 + 캔들 + 이력으로 교체.
#   - 복원 오류 무시(psql 기본값, 2>/dev/null || echo 0) → ON_ERROR_STOP, 오류 1건이면 즉시 실패.
#   - 버전 미고정(latest) → 이미지·TimescaleDB 버전을 인자로 받아 확인. timescaledb_pre_restore/post_restore 사용.
#   - 운영 비밀번호(.env) 사용 → 임시 컨테이너용 비밀번호를 그때그때 만든다(출력하지 않는다).
#   - ./backups 에 기록 → 아무 파일도 쓰지 않는다. 결과는 표준 출력으로만.
# =============================================================================
set -euo pipefail

if [ $# -ne 4 ]; then
    echo "사용법: $0 <backup.sql.gz> <기대 SHA-256> <이미지> <기대 TimescaleDB 버전>" >&2
    exit 2
fi
BACKUP_FILE="$1"; EXPECT_SHA="$2"; IMAGE="$3"; EXPECT_TSDB="$4"
HERE="$(cd "$(dirname "$0")" && pwd)"
CONFIG="${HERE}/forward_aggregate/forward_config.json"
CONTAINER="restore-check-$(date +%Y%m%d%H%M%S)-$$"
DB="restore_check"

ok()   { echo "[OK] $*"; }
fail() { echo "[FAIL] $*" >&2; exit 1; }
cleanup() { docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true; }
trap cleanup EXIT

q() { docker exec "${CONTAINER}" psql -X -v ON_ERROR_STOP=1 -U postgres -d "${DB}" -tAc "$1"; }
# 조회 결과를 먼저 변수에 받고 성공 여부를 확인한다(리뷰 23차: echo "$(q …)" 는 조회 실패를 빈 값으로 삼켰다).
qset() { local __o; __o=$(q "$2" | tr -d '\r') || fail "조회 실패: $3"; printf -v "$1" '%s' "$__o"; }
qcount() { qset "$1" "$2" "$3"; [[ "${!1}" =~ ^[0-9]+$ ]] || fail "행 수가 숫자가 아님: $3 ('${!1}')"; }
qrun() { q "$1" >/dev/null || fail "실행 실패: $2"; }

# ── 1. 파일 해시·압축 검사 ─────────────────────────────────────────────────────
[ -f "${BACKUP_FILE}" ] || fail "파일 없음: ${BACKUP_FILE}"
ACTUAL_SHA=$(sha256sum "${BACKUP_FILE}" | cut -d' ' -f1)
[ "${ACTUAL_SHA}" = "${EXPECT_SHA,,}" ] || fail "SHA-256 불일치: 실제 ${ACTUAL_SHA}"
ok "SHA-256 일치: ${ACTUAL_SHA} ($(wc -c < "${BACKUP_FILE}" | tr -d ' ') 바이트)"
gzip -t "${BACKUP_FILE}" || fail "압축 검사(gzip -t) 실패"
# 예전 백업 루프는 pg_dump 가 중간에 끊겨도 정상 압축 파일을 남겼다 → 덤프 끝 표시로 완결 여부를 본다
gunzip -c "${BACKUP_FILE}" | tail -n 5 | grep -q "PostgreSQL database dump complete" \
    || fail "덤프 끝 표시 없음 — 중간에 끊긴 덤프"
ok "압축 검사·덤프 끝 표시 통과"

# ── 2. 격리 컨테이너 (포트 공개 없음, 일회용 비밀번호) ─────────────────────────
TMP_PW=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
docker run -d --name "${CONTAINER}" --network none \
    -e POSTGRES_PASSWORD="${TMP_PW}" -e POSTGRES_DB="${DB}" \
    "${IMAGE}" >/dev/null
unset TMP_PW
for i in $(seq 1 120); do
    # 이미지 초기화(확장 설치)가 끝나고 서버가 다시 뜬 뒤에만 쓴다
    if docker logs "${CONTAINER}" 2>&1 | grep -q "PostgreSQL init process complete" \
       && docker exec "${CONTAINER}" pg_isready -U postgres -q 2>/dev/null; then
        break
    fi
    [ "$i" -eq 120 ] && fail "컨테이너 준비 120초 초과"
    sleep 1
done
qset TSDB "SELECT extversion FROM pg_extension WHERE extname='timescaledb'" "TimescaleDB 버전"
qset PGV "SHOW server_version" "PostgreSQL 버전"
[ "${TSDB}" = "${EXPECT_TSDB}" ] || fail "TimescaleDB 버전 불일치: 컨테이너 ${TSDB}, 기대 ${EXPECT_TSDB}"
ok "컨테이너 ${IMAGE} · PostgreSQL ${PGV} · TimescaleDB ${TSDB} (네트워크 없음)"

# ── 3. 복원 — 오류 1건이면 즉시 실패 ──────────────────────────────────────────
# 덤프의 소유자(trader)가 있어야 ALTER … OWNER 가 실패하지 않는다
qrun "CREATE ROLE trader LOGIN" "trader 역할 생성"
qrun "SELECT timescaledb_pre_restore()" "timescaledb_pre_restore"
echo "복원 중… (수 분 걸릴 수 있다)"
RESTORE_LOG=$(mktemp)
if ! gunzip -c "${BACKUP_FILE}" | docker exec -i "${CONTAINER}" \
        psql -X -q -v ON_ERROR_STOP=1 -U postgres -d "${DB}" >/dev/null 2>"${RESTORE_LOG}"; then
    echo "--- 복원 오류 (마지막 20줄) ---" >&2; tail -n 20 "${RESTORE_LOG}" >&2; rm -f "${RESTORE_LOG}"
    fail "복원 실패 — 오류에서 중단(ON_ERROR_STOP)"
fi
if grep -q "ERROR" "${RESTORE_LOG}"; then
    grep "ERROR" "${RESTORE_LOG}" | head -n 20 >&2; rm -f "${RESTORE_LOG}"
    fail "복원 출력에 ERROR"
fi
rm -f "${RESTORE_LOG}"
qrun "SELECT timescaledb_post_restore()" "timescaledb_post_restore"
ok "복원 완료 (오류 0)"

# ── 4. 스키마 ─────────────────────────────────────────────────────────────────
for T in public.flyway_schema_history paper_trading.virtual_balance paper_trading.position \
         'paper_trading."order"' public.market_data_cache; do
    qset EXISTS "SELECT to_regclass('${T}') IS NOT NULL" "테이블 존재 ${T}"
    [ "${EXISTS}" = "t" ] || fail "테이블 없음: ${T}"
done
ok "필수 테이블 5개 존재 (paper_trading 장부 3 · market_data_cache · flyway_schema_history)"
qcount FAILED_MIG "SELECT count(*) FROM flyway_schema_history WHERE NOT success" "실패 마이그레이션 수"
[ "${FAILED_MIG}" = "0" ] || fail "실패한 마이그레이션 ${FAILED_MIG}건"
qset LAST_MIG "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" "최신 마이그레이션"
[ -n "${LAST_MIG}" ] || fail "마이그레이션 이력 비어 있음"
ok "마이그레이션 이력: 최신 ${LAST_MIG}, 실패 0"

# ── 5. 66세션 식별자 (id·코인이 forward_config.json 과 같아야 한다) ───────────
EXPECTED=$(python -c "import json,sys; c=json.load(open(sys.argv[1],encoding='utf-8')); print('\n'.join(f\"{s['id']}|{s['coin']}\" for s in sorted(c['sessions'],key=lambda s:s['id'])))" "${CONFIG}" | tr -d '\r')
[ "$(echo "${EXPECTED}" | grep -c .)" = "66" ] || fail "forward_config.json 세션 목록이 66개가 아님"
qset ACTUAL "SELECT id||'|'||coin_pair FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587 ORDER BY id" "66세션 식별자"
if [ "${EXPECTED}" != "${ACTUAL}" ]; then
    diff <(echo "${EXPECTED}") <(echo "${ACTUAL}") | head -n 20 >&2 || true
    fail "66세션 식별자 불일치 (기대 $(echo "${EXPECTED}" | grep -c .) / 복원 $(echo "${ACTUAL}" | grep -c .))"
fi
ok "66세션 식별자 일치 (id 522~587 · 코인)"

# ── 6. 주요 테이블 행 수 (건수만, 금액·손익 열은 읽지 않는다) ──────────────────
qcount N_VB66 "SELECT count(*) FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587" "virtual_balance(66세션)"
qcount N_POS66 "SELECT count(*) FROM paper_trading.position WHERE session_id BETWEEN 522 AND 587" "position(66세션)"
qcount N_ORD66 "SELECT count(*) FROM paper_trading.\"order\" WHERE session_id BETWEEN 522 AND 587" "order(66세션)"
qcount N_VB "SELECT count(*) FROM paper_trading.virtual_balance" "virtual_balance(전체)"
qcount N_MDC "SELECT count(*) FROM public.market_data_cache" "market_data_cache(전체)"
echo "=== 행 수 ==="
echo "  paper_trading.virtual_balance (66세션): ${N_VB66}"
echo "  paper_trading.position (66세션):        ${N_POS66}"
echo "  paper_trading.order (66세션):           ${N_ORD66}"
echo "  paper_trading.virtual_balance (전체):   ${N_VB}"
echo "  public.market_data_cache (전체):        ${N_MDC}"
ok "복구 확인 성공 — 파일 해시·복원·스키마·66세션 식별자·행 수 (성과 계산 없음)"
