#!/usr/bin/env bash
#
# 실자본 매매 전면 중단 (2026-08-18)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 인증
#   ApiTokenAuthFilter — 로그인 없이 API_AUTH_TOKEN 환경변수와 일치하는 고정 Bearer 토큰.
#   docker-compose.prod.yml 이 ${API_AUTH_TOKEN} 을 읽으므로 같은 디렉터리의 .env 에 있다.
#   (첫 실행 시 이 헤더가 없어 전 요청이 UNAUTHORIZED 로 실패했다.)
#
# ■ 무엇을 하나 / 안 하나
#   [O] 실자본 6세션 정지 — DYN 46·48·50·52(REAL) + LIVE 198·199
#   [X] DYN_PAPER 신규 생성 — 하지 않는다.
#       paper_trading.virtual_balance 에 이미 42세션(7전략 × 6코인, H1)이 08-07 부터
#       가동 중이다. 애초 계획했던 9세션은 이것과 완전히 중복이며 표본도 더 좁다.
#   [X] DYN_PAPER 47·49·51·53 정지 — 하지 않는다.
#       DYNAMIC 엔진(워치리스트 스캔) 경로의 유일한 페이퍼 관측이라 남긴다.
#       42세션은 단일코인 고정이라 스캔 로직을 검증하지 못한다.
#
# ■ 정지 후 API 부하
#   DYNAMIC 은 세션마다 워치리스트를 각자 조회한다(공유 캐시 없음) — 33 요청/분/세션.
#   REAL 4종을 내리면 297 → 약 165 요청/분(한도 420의 39%)으로 떨어진다.

set -uo pipefail
API="http://localhost:8080/api/v1"

# ── 토큰 ─────────────────────────────────────────────────────────────────────
if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  # .env 의 API_AUTH_TOKEN 만 뽑아 온다 (set -a 로 전체를 먹이면 다른 변수까지 덮어쓴다)
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다."
  echo "  export API_AUTH_TOKEN=... 후 다시 실행하거나, .env 가 있는 디렉터리에서 실행하세요."
  exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"

api() { curl -s -H "$AUTH" "$@"; }

# 인증 선확인 — 실패하면 아무것도 건드리지 않고 멈춘다
probe=$(api "$API/dynamic-sessions")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다: $(echo "$probe" | head -c 200)"; exit 1 ;;
esac
echo "✓ 인증 확인"

STOP_DYN="46 48 50 52"      # REAL 만. PAPER 47·49·51·53 은 유지
STOP_LIVE="198 199"

# ── 0. PRECHECK ──────────────────────────────────────────────────────────────
echo
echo "=== PRECHECK: 열린 포지션 (있으면 정지 시 시장가 청산됨) ==="
for id in $STOP_DYN; do
  echo "  DYN $id : $(api "$API/dynamic-sessions/$id/positions" | head -c 200)"
done
for id in $STOP_LIVE; do
  echo "  LIVE $id: $(api "$API/trading/sessions/$id/positions" | head -c 200)"
done
echo
if [ "${ASSUME_YES:-0}" != "1" ]; then
  read -p "위 목록이 비어 있으면 Enter, 포지션이 있으면 Ctrl-C: "
fi

# ── 1. 실자본 정지 ───────────────────────────────────────────────────────────
echo
echo "=== 1. 동적 세션 REAL 4종 정지 ==="
for id in $STOP_DYN; do
  echo "  stop DYN $id  -> $(api -X POST "$API/dynamic-sessions/$id/stop" | head -c 250)"
done

echo
echo "=== 2. LIVE 실자본 2종 정지 ==="
for id in $STOP_LIVE; do
  echo "  stop LIVE $id -> $(api -X POST "$API/trading/sessions/$id/stop" | head -c 250)"
done

# ── 2. 확인 ──────────────────────────────────────────────────────────────────
echo
echo "=== 3. 결과 ==="
api "$API/dynamic-sessions" | head -c 4000; echo
echo
echo "기대 상태:"
echo "  STOPPED = DYN 46·48·50·52, LIVE 198·199   → 실자본 노출 0"
echo "  RUNNING = DYN_PAPER 47·49·51·53 + virtual_balance 42세션 (전부 모의)"
