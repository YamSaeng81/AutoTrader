#!/bin/bash
# =============================================================================
# 보안 점검 스크립트 — 20260415_analy.md Tier 4 §18
#
# 목적:
#   1. Git 히스토리에 실제 API 키 유출 여부 확인
#   2. Actuator 엔드포인트 외부 노출 범위 검증
#   3. CORS 설정 확인
#   4. Upbit IP 화이트리스팅 권고 안내
#
# 사용법: ./scripts/security-check.sh [서버URL]
#   예: ./scripts/security-check.sh http://localhost:8080
# =============================================================================
set -euo pipefail

SERVER_URL="${1:-http://localhost:8080}"
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }

echo "============================================================"
echo " CryptoAutoTrader 보안 점검 (§18)"
echo " 점검 시각: $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"
echo ""

# ── 1. Git 히스토리 키 유출 점검 ─────────────────────────────────────────────
echo "[ 1/4 ] Git 히스토리 API 키 유출 점검"
REAL_KEY_PATTERNS=(
    "UPBIT_ACCESS_KEY=[A-Za-z0-9+/]{20,}"
    "UPBIT_SECRET_KEY=[A-Za-z0-9+/]{20,}"
    "upbit.access-key=[A-Za-z0-9+/]{20,}"
    "upbit.secret-key=[A-Za-z0-9+/]{20,}"
)
LEAKED=false
for PATTERN in "${REAL_KEY_PATTERNS[@]}"; do
    MATCHES=$(git log --all -p 2>/dev/null | grep -E "${PATTERN}" | \
              grep -v "your_access_key\|your_secret_key\|example\|placeholder\|<\|>" || true)
    if [ -n "${MATCHES}" ]; then
        fail "  Git 히스토리에 실제 키가 포함된 커밋 발견!"
        echo "${MATCHES}"
        LEAKED=true
    fi
done
if [ "${LEAKED}" = false ]; then
    ok "  Git 히스토리에 실제 API 키 없음"
fi

# ── 2. .env 파일 Git 추적 여부 ───────────────────────────────────────────────
echo ""
echo "[ 2/4 ] .env 파일 Git 추적 여부"
if git ls-files --error-unmatch .env 2>/dev/null; then
    fail "  .env 파일이 Git에 추적되고 있습니다! git rm --cached .env 실행 필요"
else
    ok "  .env 파일은 Git에서 추적되지 않음 (.gitignore 정상)"
fi

# ── 3. Actuator 엔드포인트 노출 범위 ─────────────────────────────────────────
echo ""
echo "[ 3/4 ] Actuator 엔드포인트 외부 노출 점검 (대상: ${SERVER_URL})"
SENSITIVE_ENDPOINTS=("/actuator/env" "/actuator/beans" "/actuator/mappings" "/actuator/configprops")
SAFE_ENDPOINTS=("/actuator/health" "/actuator/prometheus")

for EP in "${SENSITIVE_ENDPOINTS[@]}"; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "${SERVER_URL}${EP}" 2>/dev/null || echo "000")
    if [ "${STATUS}" = "200" ]; then
        fail "  민감 엔드포인트 노출: ${EP} (HTTP ${STATUS}) — 즉시 차단 필요"
    elif [ "${STATUS}" = "000" ]; then
        warn "  연결 불가: ${EP} (서버 미실행 또는 타임아웃)"
    else
        ok "  차단 확인: ${EP} (HTTP ${STATUS})"
    fi
done

for EP in "${SAFE_ENDPOINTS[@]}"; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "${SERVER_URL}${EP}" 2>/dev/null || echo "000")
    if [ "${STATUS}" = "200" ]; then
        ok "  정상 노출: ${EP} (HTTP ${STATUS})"
    elif [ "${STATUS}" = "000" ]; then
        warn "  연결 불가: ${EP} (서버 미실행)"
    else
        warn "  예상 외 응답: ${EP} (HTTP ${STATUS})"
    fi
done

# ── 4. Upbit IP 화이트리스팅 권고 ────────────────────────────────────────────
echo ""
echo "[ 4/4 ] Upbit IP 화이트리스팅 권고 사항"
echo ""
SERVER_IP=$(curl -s --max-time 3 https://ifconfig.me 2>/dev/null || echo "조회 실패")
warn "  현재 서버 외부 IP: ${SERVER_IP}"
echo "  → Upbit 보안 설정(https://upbit.com/service_center/open_api_management)에서"
echo "     위 IP를 허용 목록에 추가하세요."
echo "  → API 키 만료 주기: 90일마다 재발급 권장 (정책 확인 후 적용)"
echo "  → 현재 적용된 환경변수: UPBIT_ACCESS_KEY / UPBIT_SECRET_KEY"
echo "     (application.yml: 빈 문자열 기본값 — prod 환경변수 필수)"

echo ""
echo "============================================================"
echo " 점검 완료"
echo "============================================================"
