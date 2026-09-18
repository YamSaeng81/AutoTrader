#!/usr/bin/env bash
#
# 캔들 백필 — 감시 범위 104종 중 56종이 캔들 0건이다 (2026-09-18)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 무엇을 발견했나
#
#   09-18 실측. 최근 30일 100회 이상 평가된 코인(= 함대가 실제로 감시하는 범위)이 **104종**인데:
#
#     캔들 0건      56종   ← NEAR·KAITO·SHIB·BCH·INJ·FIL·SAND·JUP·CRO ... 절반이 넘는다
#     밀린 것       48종   ← 30종이 08-30, 즉 19일 정지
#     최신           2종   ← ETH·XRP (고정 격자)
#
#   **함대가 감시하는 코인의 절반 이상을 백테스트·WF 가 검증할 수 없다.**
#   09-18 의 "통과 0건" 기준선은 이 46%(48/104) 안에서, 그마저 밀린 데이터로 잰 것이다.
#
# ■ 왜 이렇게 됐나 — `CandleDataFreshnessScheduler` 의 두 구멍 (같은 날 수정)
#
#   ① 갱신 대상이 8종 하드코딩이라 동적 워치리스트가 통째로 빠졌다.
#   ② 갭이 7일을 넘으면 건너뛰었다. 갭은 매일 커지기만 하므로 **한 번 밀리면 영원히
#      돌아오지 못한다** — 그 8종 중 6종이 이미 그 상태였다.
#   ③ 실패가 `log.warn` 뿐이라 아무도 몰랐다. 09-14 에 같은 증상을 고치고도 재발했다.
#
#   스케줄러는 고쳤지만(대상을 strategy_log 에서 산출 · 긴 갭은 잘라서 요청 · 텔레그램 알림),
#   **이미 벌어진 갭은 스스로 메우지 못한다** — 이 스크립트가 그 1회성 복구다.
#
# ■ 단계가 나뉜 이유 — 요청 사슬 길이
#
#   `UpbitCandleCollector.fetchCandles` 의 수집 루프는 **all-or-nothing** 이다. 사슬이 길면
#   중간 1회 실패로 전부 버려진다(09-08 실측: M15 822회 → 6/6 실패 / H1 206회 → 8/8 성공).
#   그래서 **짧은 갭부터** 확실히 메우고, 긴 전체 수집은 연 단위로 쪼개 뒤에 돌린다.
#
#   STEP 은 따로 실행할 수 있다. 1 단계만 돌려도 오늘의 기준선 재측정은 가능해진다.
#
#     STEP=1 bash scripts/backfill_candles_0918.sh   # 밀린 48종 갭 메우기 (권장: 먼저)
#     STEP=2 bash scripts/backfill_candles_0918.sh   # 캔들 0건 56종 신규 수집 (오래 걸림)
#     bash scripts/backfill_candles_0918.sh          # 1 → 2 순차
#
# ■ STEP 2 의 범위를 2024-01-01 로 잡은 이유
#
#   56종 × 4.5년을 한 번에 당기면 사슬이 너무 길어져 통째로 실패한다. 그리고 이 코인들은
#   대부분 신규 상장이라 그 이전 데이터가 없다. WF 를 windowCount=3 으로 돌릴 수 있는
#   최소선(약 1.5년)을 넘기는 선에서 2024-01-01 로 끊는다. 더 필요하면 연도를 나눠 다시 돌릴 것
#   — `saveAll` 이 PK(time, coin_pair, timeframe) upsert 라 겹쳐 수집해도 안전하다.
#
# ■ 끝나고 할 일
#
#   1. `bash scripts/backfill_candles_0918.sh --status` 로 커버리지 확인
#   2. 캔들이 채워진 뒤에야 Wave 3 착수가 의미 있다 — L(MTF) 수정 후 재측정이 필요한데,
#      캔들이 밀려 있으면 **재측정 자체가 불가능**하다.

set -uo pipefail

API="http://localhost:8080/api/v1"
TODAY=$(date -u +%Y-%m-%d)

# ── 밀린 48종. 08-25 부터 당긴다(며칠 겹쳐 안전하게 — upsert 라 중복 무해). ──
STALE='["KRW-XLM","KRW-SUI","KRW-WLD","KRW-GRVT","KRW-RE","KRW-STX","KRW-SLX","KRW-WLFI",
"KRW-PUMP","KRW-NCT","KRW-KAT","KRW-CFG","KRW-TRAC","KRW-GAS","KRW-META2","KRW-MLK",
"KRW-MET2","KRW-BEAM","KRW-FOLD","KRW-LSK","KRW-LIT","KRW-NEWT","KRW-ZRO","KRW-ONDO",
"KRW-TRUMP","KRW-ENA","KRW-ARB","KRW-FLOCK","KRW-UNI","KRW-ONT","KRW-AXL","KRW-ICX",
"KRW-LA","KRW-DOS","KRW-MIRA","KRW-CRV","KRW-0G","KRW-ZKC","KRW-SOL","KRW-BTC","KRW-ADA",
"KRW-LINK","KRW-DOGE","KRW-AVAX","KRW-PROM","KRW-EUL","KRW-XRP","KRW-ETH"]'

# ── 캔들 0건 56종. 감시는 하는데 검증 수단이 없는 코인들. ──
MISSING_1='["KRW-NEAR","KRW-SHIB","KRW-BCH","KRW-INJ","KRW-FIL","KRW-SAND","KRW-JUP",
"KRW-CRO","KRW-IOTA","KRW-STORJ","KRW-GLM","KRW-ETHFI","KRW-POLYX","KRW-ANKR","KRW-WAVES",
"KRW-CVC","KRW-POWR","KRW-SC","KRW-WAXP","KRW-DKA","KRW-ELF","KRW-RAY","KRW-ORCA","KRW-T",
"KRW-G","KRW-HIVE","KRW-BFC","KRW-PUNDIX","KRW-PROS"]'

MISSING_2='["KRW-ONG","KRW-TREE","KRW-CHIP","KRW-CAP","KRW-SOPH","KRW-HOME","KRW-CP",
"KRW-O","KRW-BLAST","KRW-ANIME","KRW-SKR","KRW-ZKP","KRW-PRL","KRW-MANTRA","KRW-PLUME",
"KRW-QUID","KRW-B3","KRW-ELSA","KRW-BIRB","KRW-UP2","KRW-AZTEC","KRW-DRV","KRW-ARX",
"KRW-FF","KRW-ERA","KRW-SAHARA","KRW-XCN"]'

# ── 인증 ─────────────────────────────────────────────────────────────────────
if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다 (.env 또는 환경변수)."
  exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

# ── --status: 커버리지 확인 ──────────────────────────────────────────────────
if [ "${1:-}" = "--status" ]; then
  docker compose -f docker-compose.prod.yml exec -T db \
    psql -U trader -d crypto_auto_trader -c "
  WITH w AS (SELECT coin_pair, count(*) n FROM strategy_log
              WHERE created_at > now() - interval '30 days'
              GROUP BY 1 HAVING count(*) >= 100),
       h AS (SELECT coin_pair, max(time)::date d FROM candle_data
              WHERE timeframe='H1' GROUP BY 1)
  SELECT CASE WHEN h.d IS NULL THEN '캔들 0건'
              WHEN h.d >= CURRENT_DATE - 2 THEN '최신'
              ELSE '밀림(' || (CURRENT_DATE - h.d) || '일)' END AS state,
         count(*) AS coins
    FROM w LEFT JOIN h USING (coin_pair)
   GROUP BY 1 ORDER BY 2 DESC;"
  exit 0
fi

collect() {
  local label="$1" coins="$2" start="$3"
  echo "▶ $label  ($start ~ $TODAY, H1)"
  api -X POST "$API/data/collect/batch" \
    -H 'Content-Type: application/json' \
    -d "{\"coinPairs\": $(echo "$coins" | tr -d '\n'), \"timeframe\": \"H1\",
         \"startDate\": \"$start\", \"endDate\": \"$TODAY\"}"
  echo; echo
}

resp=$(api "$API/data/summary")
case "$resp" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요"; exit 1 ;;
esac
echo "✓ 인증 확인"
echo

STEP="${STEP:-0}"

if [ "$STEP" = "0" ] || [ "$STEP" = "1" ]; then
  echo "═══ STEP 1 — 밀린 48종 갭 메우기 ═══"
  collect "밀린 48종" "$STALE" "2026-08-25"
fi

if [ "$STEP" = "0" ] || [ "$STEP" = "2" ]; then
  echo "═══ STEP 2 — 캔들 0건 56종 신규 수집 (오래 걸립니다) ═══"
  collect "신규 29종 (1/2)" "$MISSING_1" "2024-01-01"
  collect "신규 27종 (2/2)" "$MISSING_2" "2024-01-01"
fi

echo "─────────────────────────────────────────────────────────────────────────"
echo "배치는 백그라운드 실행입니다. 완료 시 코인별 텔레그램 알림이 옵니다."
echo
echo "  커버리지 확인   bash scripts/backfill_candles_0918.sh --status"
echo "  요약            curl -s -H \"\$AUTH\" $API/data/summary"
echo
echo "⚠️ 신규 상장 코인은 상장 이전 구간이 비어 있는 게 정상입니다 — 0건으로 남아도"
echo "   전부 실패한 것은 아닙니다. --status 로 '캔들 0건' 종수가 줄었는지로 판단하세요."
