#!/usr/bin/env bash
#
# 운영 DB 데이터 정합성 보정 — 2026-09-07
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. psql 로 직접 붙는다.
#
# ■ 무엇을 고치나
#
#   [1] 세션 49 총자산 손상
#       dynamic_session id=49 (COMPOSITE_MEANREV_BB/H1, DELETED) 의
#       total_asset_krw 가 221,876 원으로 기록돼 있다. 화면상 +2,118%.
#       이 세션의 청산은 2건뿐이고 realized_pnl 합계는 -168 원 → 정상값은 9,832 원.
#       오차 +212,043 원. 주문 이력도 3건뿐이라 체결로는 설명되지 않는다.
#
#       DELETED 라 매매에는 영향이 없지만, 전략별/함대 누적 통계에 들어가면
#       전체 수치를 통째로 오염시킨다.
#
#   [2] 페이퍼 주문의 executed_funds 백필
#       executed_funds 는 거래소 응답에서만 채워져서 페이퍼 주문은 전 행 NULL 이었다.
#       그래서 order.quantity 의 이중 의미(시장가 매수=KRW 총액 / 그 외=코인 수량,
#       Upbit price 타입 제약)를 읽는 쪽이 매번 side·order_type 으로 되짚어야 했다.
#       코드는 2026-09-07 부터 채우고, 과거 행은 여기서 메운다.
#         - BUY  : quantity 가 곧 KRW 총액
#         - SELL : price × quantity (수수료 차감 전 매도 대금)
#
# ■ 안전장치
#   - 전부 트랜잭션 1개. 검증 쿼리가 예상과 다르면 ROLLBACK 하고 아무것도 바꾸지 않는다.
#   - DRY_RUN=1 (기본) 이면 무엇이 바뀔지만 보여주고 반드시 롤백한다.
#     실제 반영은 DRY_RUN=0 으로 다시 실행.
#   - 세션 49 는 ID 를 박지 않고 "total_asset 과 초기자본+실현손익의 괴리가 10만원 초과 +
#     보유 포지션 없음" 조건으로 찾는다. 정상 세션은 걸리지 않는다(41/42 세션이 오차 0이고,
#     RUNNING 세션의 ±100원 괴리는 평가손익이라 정상이며 10만원 문턱에 한참 못 미친다).
#
# 사용법:
#   DRY_RUN=1 bash scripts/fix_data_integrity_0907.sh      # 미리보기 (기본)
#   DRY_RUN=0 bash scripts/fix_data_integrity_0907.sh      # 실제 반영
#
# 환경변수: PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD (미설정 시 아래 기본값)
#           COMPOSE_FILE  compose 파일 경로 (기본 docker-compose.prod.yml)
#           DB_SERVICE    compose 의 DB 서비스명 (기본 db)

set -euo pipefail

export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-crypto_auto_trader}"
export PGUSER="${PGUSER:-trader}"
DRY_RUN="${DRY_RUN:-1}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
DB_SERVICE="${DB_SERVICE:-db}"

if [ -z "${PGPASSWORD:-}" ]; then
  read -r -s -p "DB 비밀번호: " PGPASSWORD
  echo
  export PGPASSWORD
fi

# psql 경로 결정 — 운영 호스트에는 psql 이 깔려 있지 않고 DB 가 컨테이너 안에서만 돈다.
# 호스트에 psql 이 있으면 그대로 쓰고, 없으면 compose 의 DB 컨테이너를 경유한다.
# (`exec -T` 로 TTY 를 끄지 않으면 heredoc 이 stdin 으로 전달되지 않는다.)
if command -v psql >/dev/null 2>&1; then
  echo "▶ psql: 호스트 (${PGHOST}:${PGPORT})"
  run_psql() { psql "$@"; }
elif docker compose -f "$COMPOSE_FILE" ps "$DB_SERVICE" >/dev/null 2>&1; then
  echo "▶ psql: ${COMPOSE_FILE} 의 '${DB_SERVICE}' 컨테이너 경유"
  run_psql() {
    docker compose -f "$COMPOSE_FILE" exec -T \
      -e PGPASSWORD="$PGPASSWORD" \
      "$DB_SERVICE" psql -U "$PGUSER" -d "$PGDATABASE" "$@"
  }
else
  echo "❌ psql 을 찾을 수 없고 '${DB_SERVICE}' 컨테이너도 없다." >&2
  echo "   리포 루트에서 실행 중인지, COMPOSE_FILE / DB_SERVICE 가 맞는지 확인할 것." >&2
  exit 1
fi

if [ "$DRY_RUN" = "0" ]; then
  FINISH="COMMIT;"
  echo "▶ 실제 반영 모드 (DRY_RUN=0)"
else
  FINISH="ROLLBACK;"
  echo "▶ 미리보기 모드 (DRY_RUN=1) — 변경사항은 롤백된다. 반영하려면 DRY_RUN=0"
fi

run_psql -v ON_ERROR_STOP=1 <<SQL
\timing off
BEGIN;

\echo ''
\echo '━━━ [1] 총자산 손상 세션 ━━━'
\echo '-- 보정 전'
SELECT s.id, s.strategy_type, s.status,
       s.initial_capital::numeric(14,0)  AS init,
       s.total_asset_krw::numeric(14,0)  AS total_now,
       COALESCE(SUM(p.realized_pnl) FILTER (WHERE p.status='CLOSED'), 0)::numeric(14,0) AS sum_pnl,
       (s.initial_capital + COALESCE(SUM(p.realized_pnl) FILTER (WHERE p.status='CLOSED'), 0))::numeric(14,0) AS total_fixed
FROM dynamic_session s
LEFT JOIN position p ON p.session_id = s.id AND p.session_kind = 'DYN_PAPER'
WHERE s.trading_mode = 'PAPER'
GROUP BY s.id
HAVING ABS(s.total_asset_krw - s.initial_capital
           - COALESCE(SUM(p.realized_pnl) FILTER (WHERE p.status='CLOSED'), 0)) > 100000
ORDER BY s.id;

-- 보유 포지션이 없는(=평가손익으로 설명될 수 없는) 세션만 손댄다.
WITH corrupted AS (
    SELECT s.id,
           (s.initial_capital
            + COALESCE(SUM(p.realized_pnl) FILTER (WHERE p.status='CLOSED'), 0)) AS fixed_total
    FROM dynamic_session s
    LEFT JOIN position p ON p.session_id = s.id AND p.session_kind = 'DYN_PAPER'
    WHERE s.trading_mode = 'PAPER'
    GROUP BY s.id
    HAVING ABS(s.total_asset_krw - s.initial_capital
               - COALESCE(SUM(p.realized_pnl) FILTER (WHERE p.status='CLOSED'), 0)) > 100000
       AND COUNT(*) FILTER (WHERE p.status IN ('OPEN','CLOSING')) = 0
)
UPDATE dynamic_session s
   SET total_asset_krw  = c.fixed_total,
       available_krw    = c.fixed_total,
       mdd_peak_capital = GREATEST(s.initial_capital, c.fixed_total),
       updated_at       = now()
  FROM corrupted c
 WHERE s.id = c.id;

\echo '-- 보정 후 (0행이어야 정상)'
SELECT s.id, s.total_asset_krw::numeric(14,0) AS total_after
FROM dynamic_session s
LEFT JOIN position p ON p.session_id = s.id AND p.session_kind = 'DYN_PAPER'
WHERE s.trading_mode = 'PAPER'
GROUP BY s.id
HAVING ABS(s.total_asset_krw - s.initial_capital
           - COALESCE(SUM(p.realized_pnl) FILTER (WHERE p.status='CLOSED'), 0)) > 100000;

\echo ''
\echo '━━━ [2] executed_funds 백필 ━━━'
\echo '-- 백필 전 NULL 건수'
SELECT session_kind, side, COUNT(*) AS null_rows
FROM "order"
WHERE executed_funds IS NULL AND state = 'FILLED'
  AND session_kind IN ('DYN_PAPER','DYNAMIC')
GROUP BY 1,2 ORDER BY 1,2;

-- 시장가 매수: quantity 가 KRW 총액이다 (Upbit price 타입).
UPDATE "order"
   SET executed_funds = quantity
 WHERE executed_funds IS NULL AND state = 'FILLED'
   AND session_kind IN ('DYN_PAPER','DYNAMIC')
   AND side = 'BUY' AND order_type = 'MARKET'
   AND quantity IS NOT NULL;

-- 매도: quantity 가 코인 수량이므로 price 를 곱한다.
UPDATE "order"
   SET executed_funds = price * quantity
 WHERE executed_funds IS NULL AND state = 'FILLED'
   AND session_kind IN ('DYN_PAPER','DYNAMIC')
   AND side = 'SELL'
   AND price IS NOT NULL AND quantity IS NOT NULL;

\echo '-- 백필 후 남은 NULL (0행이어야 정상)'
SELECT session_kind, side, COUNT(*) AS still_null
FROM "order"
WHERE executed_funds IS NULL AND state = 'FILLED'
  AND session_kind IN ('DYN_PAPER','DYNAMIC')
GROUP BY 1,2 ORDER BY 1,2;

\echo '-- 정합성 확인: BUY 의 executed_funds 가 포지션 투자금과 일치해야 한다 (diff 0)'
SELECT o.id, o.coin_pair,
       o.executed_funds::numeric(14,2) AS ef,
       p.invested_krw::numeric(14,2)   AS invested,
       (o.executed_funds - p.invested_krw)::numeric(14,4) AS diff
FROM "order" o JOIN position p ON p.id = o.position_id
WHERE o.session_kind = 'DYN_PAPER' AND o.side = 'BUY'
ORDER BY o.id DESC LIMIT 5;

$FINISH
SQL

echo ""
if [ "$DRY_RUN" = "0" ]; then
  echo "✅ 반영 완료."
else
  echo "ℹ️  미리보기였다. 위 결과가 맞으면 DRY_RUN=0 으로 다시 실행할 것."
fi
