#!/usr/bin/env bash
#
# 캔들 백필 2차 — H1 실패분 재시도 + M15 커버리지 확장 (2026-09-20)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 09-18 백필이 남긴 두 구멍
#
#   ① **H1 18종이 여전히 0건이다.** 목록에서 빠진 게 아니라 `backfill_candles_0918.sh`
#      STEP 2 에 넣었는데 **한 건도 안 들어왔다.** BCH·SHIB·FIL·SAND·JUP·INJ 는 상장 이력이
#      충분한 코인이므로 "데이터가 없어서"가 아니다.
#
#   ② **M15 는 감시 103종 중 15종뿐이다.** 그나마 FLOCK·AXL·CFG·NEWT 4종은 2026-03-29 에
#      멈춰 있다. 그런데 **운영에 M15 세션이 돌고 있다**(동적 86 · MTF_CONFIRMED/M15).
#      09-18 스크립트를 H1 만 돌게 짠 것은 "알트는 M15 캔들이 없으니" 였는데, 그건 원인과
#      결과를 뒤집은 판단이었다 — 없어서 안 쓴 게 아니라 **안 채워서 없는** 것이다.
#
# ■ 왜 09-18 STEP 2 가 실패했나 — 그리고 이번엔 무엇이 다른가
#
#   `UpbitCandleCollector.fetchCandles` 의 수집 루프는 **all-or-nothing** 이다. 사슬 중
#   한 번만 실패해도 그 요청의 결과를 통째로 버린다. 09-08 실측이 경계선을 보여 준다:
#
#       M15 822 요청/코인  →  6/6 실패
#       H1  206 요청/코인  →  8/8 성공
#
#   09-18 은 한 번의 API 호출에 **29종을 한꺼번에** 넘겼다. 코인당 사슬은 짧아도 호출 하나가
#   붙들고 있는 시간이 길어져 통째로 날아갔다. 이번 스크립트는 반대로 간다:
#
#       · **코인 하나씩** 호출한다 — 한 코인이 실패해도 나머지는 산다
#       · **기간을 슬라이스로 쪼갠다** — 한 요청의 사슬을 200 요청 아래로 유지한다
#         (H1 200일 ≈ 4,800캔들 ≈ 48요청 / M15 120일 ≈ 11,520캔들 ≈ 116요청)
#       · 호출 사이에 쉰다 — 운영 함대와 Upbit 예산을 나눠 쓴다
#
#   `saveAll` 이 PK(time, coin_pair, timeframe) upsert 라 **겹쳐 수집해도 안전하다.**
#   중간에 끊겨도 그냥 다시 돌리면 된다.
#
# ■ 선행 조건
#
#   백엔드 재빌드가 **필요하다** — 09-20 에 `CandleDataFreshnessScheduler` 의 기아 결함을
#   고쳤다(상한에 걸리면 목록 뒤쪽과 M15 가 영원히 평가되지 않던 문제). 이 스크립트 자체는
#   기존 수집 API 만 쓰므로 재빌드 전에 돌려도 동작하지만, **내일 새벽 자동 갱신이 제대로
#   돌려면 재빌드가 먼저다.** 스크립트로 갭을 줄여 두면 자동 갱신이 상한에 덜 걸린다.
#
# ■ 사용법 — 운영 서버에서, 리포 루트에서
#
#     bash scripts/backfill_candles_0920.sh --status   # 현재 커버리지
#     STEP=1 bash scripts/backfill_candles_0920.sh     # H1 18종 재시도
#     STEP=2 bash scripts/backfill_candles_0920.sh     # M15 확장
#     bash scripts/backfill_candles_0920.sh            # 1 → 2 순차
#
#   소요 시간(호출 사이 20초 기준, 2026-09-20 계산):
#     STEP 1   18코인 × 5슬라이스 = 90호출  ≈ 30분
#     STEP 2   4×6 + 24×4 = 약 120호출     ≈ 40분
#   합쳐 1시간 남짓이다. SSH 가 끊겨도 죽지 않게 tmux 안에서 돌리거나 `nohup ... &` 를 쓸 것.
#   SLEEP_BETWEEN=10 으로 줄이면 절반이 되지만, 운영 함대와 레이트리밋을 다툰다.

set -uo pipefail

API="http://localhost:8080/api/v1"
TODAY=$(date -u +%Y-%m-%d)

# 호출 사이 대기(초) — 운영 함대와 레이트리밋 예산을 나눠 쓴다.
SLEEP_BETWEEN="${SLEEP_BETWEEN:-20}"

# ── STEP 1 대상: H1 이 아직 0건인 18종 (09-20 실측) ─────────────────────────
H1_COINS="KRW-ONG KRW-TREE KRW-SOPH KRW-BLAST KRW-HOME KRW-ZKP KRW-BCH KRW-SHIB
KRW-FIL KRW-SAND KRW-SC KRW-JUP KRW-WAXP KRW-PUNDIX KRW-PROS KRW-CVC KRW-STORJ KRW-INJ"

# H1 슬라이스 — 200일씩. 2024-01-01 부터 오늘까지 약 3.7배.
H1_START="2024-01-01"
H1_SLICE_DAYS=200

# ── STEP 2 대상: M15 이 없는 감시 코인 중 **평가 상위 24종** ────────────────
#    103종 전부를 M15 로 채우면 H1 의 4배 분량이라 하루로 안 끝난다. 실제로 매매가
#    일어나는 상위부터 채우고, 나머지는 필요할 때 늘린다.
M15_COINS="KRW-ONDO KRW-TRUMP KRW-ENA KRW-SUI KRW-WLD KRW-LA KRW-GRVT KRW-DOS
KRW-RE KRW-CHIP KRW-STX KRW-ARB KRW-NEAR KRW-UNI KRW-SLX KRW-MIRA KRW-CRV KRW-CP
KRW-O KRW-WLFI KRW-HIVE KRW-ONT KRW-PUMP KRW-NCT"

# M15 는 밀도가 H1 의 4배라 기간을 1년으로 줄이고 슬라이스도 짧게 끊는다.
# WF 를 windowCount=3 으로 돌리기엔 1년이면 충분하다.
M15_START="2025-09-20"
M15_SLICE_DAYS=120

# ── 2026-03-29 에 멈춘 M15 4종 — 갭만 메운다 ────────────────────────────────
M15_STALE="KRW-FLOCK KRW-AXL KRW-CFG KRW-NEWT"
M15_STALE_START="2026-03-25"

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

# ── --status ─────────────────────────────────────────────────────────────────
if [ "${1:-}" = "--status" ]; then
  docker compose -f docker-compose.prod.yml exec -T db \
    psql -U trader -d crypto_auto_trader -c "
  WITH w AS (SELECT coin_pair, count(*) n FROM strategy_log
              WHERE created_at > now() - interval '30 days'
              GROUP BY 1 HAVING count(*) >= 100),
       c AS (SELECT coin_pair, timeframe, max(time)::date d FROM candle_data GROUP BY 1,2)
  SELECT tf.timeframe,
         count(*) FILTER (WHERE c.d IS NULL)                        AS \"캔들0건\",
         count(*) FILTER (WHERE c.d >= CURRENT_DATE - 2)            AS \"최신\",
         count(*) FILTER (WHERE c.d IS NOT NULL AND c.d < CURRENT_DATE - 2) AS \"밀림\"
    FROM w CROSS JOIN (VALUES ('H1'),('M15')) AS tf(timeframe)
    LEFT JOIN c ON c.coin_pair = w.coin_pair AND c.timeframe = tf.timeframe
   GROUP BY 1 ORDER BY 1;"
  exit 0
fi

resp=$(api "$API/data/summary")
case "$resp" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요"; exit 1 ;;
esac
echo "✓ 인증 확인"

# 하루씩 더하기 — GNU date 기준.
add_days() { date -u -d "$1 + $2 days" +%Y-%m-%d; }

# 코인 하나를 슬라이스로 쪼개 수집한다. 한 코인이 실패해도 나머지는 계속 간다.
collect_sliced() {
  local coin="$1" tf="$2" start="$3" slice="$4"
  local cursor="$start" chunk_end
  while [ "$cursor" \< "$TODAY" ]; do
    chunk_end=$(add_days "$cursor" "$slice")
    [ "$chunk_end" \> "$TODAY" ] && chunk_end="$TODAY"
    printf '   %-12s %-4s %s ~ %s  ' "$coin" "$tf" "$cursor" "$chunk_end"
    api -X POST "$API/data/collect/batch" \
      -H 'Content-Type: application/json' \
      -d "{\"coinPairs\": [\"$coin\"], \"timeframe\": \"$tf\",
           \"startDate\": \"$cursor\", \"endDate\": \"$chunk_end\"}" \
      | head -c 120
    echo
    cursor="$chunk_end"
    sleep "$SLEEP_BETWEEN"
  done
}

STEP="${STEP:-0}"

if [ "$STEP" = "0" ] || [ "$STEP" = "1" ]; then
  echo
  echo "═══ STEP 1 — H1 18종 재시도 ($H1_START ~ $TODAY, ${H1_SLICE_DAYS}일 슬라이스) ═══"
  for coin in $H1_COINS; do
    collect_sliced "$coin" "H1" "$H1_START" "$H1_SLICE_DAYS"
  done
fi

if [ "$STEP" = "0" ] || [ "$STEP" = "2" ]; then
  echo
  echo "═══ STEP 2a — M15 밀린 4종 갭 메우기 ═══"
  for coin in $M15_STALE; do
    collect_sliced "$coin" "M15" "$M15_STALE_START" "$M15_SLICE_DAYS"
  done

  echo
  echo "═══ STEP 2b — M15 신규 24종 ($M15_START ~ $TODAY, ${M15_SLICE_DAYS}일 슬라이스) ═══"
  for coin in $M15_COINS; do
    collect_sliced "$coin" "M15" "$M15_START" "$M15_SLICE_DAYS"
  done
fi

echo
echo "─────────────────────────────────────────────────────────────────────────"
echo "완료. 커버리지 확인:"
echo "  bash scripts/backfill_candles_0920.sh --status"
echo
echo "⚠️ 수집은 비동기라 위 응답이 '요청 접수'일 뿐입니다. --status 로 실제 반영을 확인하세요."
echo "⚠️ 신규 상장 코인은 상장 이전 구간이 비는 게 정상입니다 — 0건이 줄었는지로 판단하세요."
echo "⚠️ 중간에 끊겨도 그대로 다시 돌리면 됩니다(upsert 라 중복 무해)."
