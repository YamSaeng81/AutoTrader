#!/usr/bin/env bash
#
# Walk Forward 전면 재검증 (2026-09-08) — 손절 로직 수정 반영
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 왜 재검증하나
#   `ExitRuleChecker.updateTrailingStops` 의 손실 구간 SL 조임을 2026-09-08 제거했다.
#   그런데 **BacktestEngine 도 같은 함수를 쓴다**(설계 의도 — 백테스트와 운영이 같은
#   청산 규칙을 써야 한다). 즉 기존 WF 350건은 전부 다음 규칙으로 계산된 것이다:
#
#     저가 < 진입가 → SL 을 저점 × (1 − 0.3%) 로 상향  = 사실상 0.3% 손절
#
#   설정값 stop_loss_pct=5.00 이 실제로는 0.43% 로 걸렸고, 운영 페이퍼에서 청산 534건
#   중 85.4% 가 평균 0.9시간 만에 휩쏘 손절됐다. **그 전제로 통과/탈락시킨 조합 판정은
#   전부 무효**다. WalkForwardValidationGate 가 실자본 배정을 이 판정으로 막고 있으므로
#   방치하면 잘못된 근거로 전략을 계속 차단/허용하게 된다.
#
# ■ 두 번째 문제 — WF 가 M15 를 한 번도 검증한 적이 없다
#   backtest_run 350건이 **전부 H1** 이다. 그런데 실제 운영은:
#
#     고정코인 PAPER  40세션 … 전부 M15
#     동적 세션       12세션 … H1 6 / M15 6
#
#   운영 함대의 대부분이 검증된 적 없는 타임프레임으로 돌고 있다. 이번에 함께 채운다.
#
# ■ 세 번째 문제 — M15 캔들이 없거나 5개월 낡았다 (먼저 고쳐야 한다)
#   2026-09-08 기준 운영 8코인의 candle_data 실측:
#
#     coin       H1 최종      M15 행수    M15 최종
#     KRW-ADA    2026-08-30   148,358     2026-03-29
#     KRW-AVAX   2026-08-30         0     —
#     KRW-BTC    2026-08-30   148,463     2026-03-30
#     KRW-DOGE   2026-08-30   148,364     2026-03-29
#     KRW-EUL    2026-08-30         0     —
#     KRW-LINK   2026-08-30         0     —
#     KRW-PROM   2026-09-01         0     —
#     KRW-SOL    2026-08-31   148,366     2026-03-29
#
#   **M15 는 4코인이 0건, 나머지 4코인도 5개월 낡았다.** 이대로 WF 를 돌리면 절반이
#   조용히 누락된다 — 2026-08-24 에 실제로 겪은 사고다(48조합 중 25개 누락,
#   scripts/backfill_candle_data.sh 주석 참조). 그래서 Step 1 이 백필이다.
#
# ■ 순서 (Step 1 완료를 확인한 뒤 Step 2 를 돌릴 것)
#   Step 1  캔들 백필 — M15 8코인 전체 + H1 갭
#   Step 2  WF 재검증 — 5전략 × 8코인 × {H1, M15}
#   Step 3  게이트 판정 재확인
#
#   Step 1 은 Upbit 레이트리밋 때문에 시간이 걸린다(M15 는 H1 의 4배 분량).
#   완료되면 코인마다 텔레그램 알림이 온다. STEP=2 로 나눠 실행하는 것을 권장한다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>
#   bash scripts/revalidate_walk_forward_0908.sh          # 전체 안내 + Step 1
#   STEP=1 bash scripts/revalidate_walk_forward_0908.sh   # 캔들 백필만
#   STEP=2 bash scripts/revalidate_walk_forward_0908.sh   # WF 재검증만 (백필 완료 후)
#   STEP=3 bash scripts/revalidate_walk_forward_0908.sh   # 판정 확인만

set -uo pipefail

API="http://localhost:8080/api/v1"
STEP="${STEP:-1}"

OPS_COINS='["KRW-SOL","KRW-BTC","KRW-DOGE","KRW-LINK","KRW-ADA","KRW-AVAX","KRW-PROM","KRW-EUL"]'
OPS_STRATS='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_MOMENTUM_ICHIMOKU_V2","COMPOSITE_MTF_BTC","COMPOSITE_MTF_CONFIRMED"]'

# ── 토큰 ─────────────────────────────────────────────────────────────────────
if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다."
  echo "  export API_AUTH_TOKEN=... 후 다시 실행하거나, .env 가 있는 디렉터리에서 실행하세요."
  exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

probe=$(api "$API/backtest/jobs")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다: $(echo "$probe" | head -c 200)"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요."; exit 1 ;;
esac
echo "✓ 인증 확인"

TODAY=$(date -u +%Y-%m-%d)

# ── 배포 확인 — 수정이 안 들어간 빌드로 재검증하면 의미가 없다 ────────────────
printf '\n\033[1m▶ 선행 확인\033[0m\n'
echo "  이 재검증은 손절 수정이 **배포된 빌드**에서 돌아야 의미가 있습니다."
echo "  운영 DB 로 확인: 2026-09-08 09:26 KST 이후 진입 포지션의 sl_gap 이 −5% 근처여야 합니다."
echo "    SELECT round((stop_loss_price/entry_price-1)*100,3) FROM paper_trading.position"
echo "    WHERE opened_at >= timestamptz '2026-09-08 00:20+00' ORDER BY opened_at DESC LIMIT 3;"

case "$STEP" in
  1)
    printf '\n\033[1m▶ Step 1: 캔들 백필 (갭만 채운다)\033[0m\n'
    echo "  GET /data/summary 로 코인별 보유 구간을 읽어, **없는 구간만** 요청합니다."
    echo "  (saveAll 이 PK(time,coin_pair,timeframe) upsert 라 겹쳐 받아도 안전합니다.)"

    # ── 왜 전 구간을 다시 받지 않는가 (2026-09-08 1차 실패에서 배운 것) ──────
    #
    # 1차 시도(8코인 × 2022~2026 단일 호출)에서 M15 는 6/8 실패, H1 은 8/8 성공했다.
    # 경계는 타임프레임이 아니라 **코인당 요청 사슬 길이**였다:
    #
    #   H1  2022~2026   206회 → 8/8 성공
    #   M15 KRW-PROM     13회 → 성공 (2026-08-12 상장이라 구간이 짧다)
    #   M15 KRW-EUL      21회 → 성공 (2026-07-26 상장)
    #   M15 2022~2026   822회 → 6/6 실패
    #
    # UpbitCandleCollector.fetchCandles 의 수집 루프는 all-or-nothing 이다 — 822회 중
    # 1회만 실패해도 RuntimeException 이 터지며 그때까지 모은 캔들을 전부 버린다
    # (UpbitCandleCollector:81). 운영 함대 52세션이 같은 Upbit 초당 10회 예산을 나눠 쓰고
    # 있어(동적 8세션만으로 264 req/분 ≈ 4.4 req/s) 90초짜리 연속 호출은 버티지 못한다.
    #
    # M15 가 안 되는 것이 아니다 — 09-08 시점에 AVAX·EUL·PROM 은 M15 가 2026-09-07 까지
    # 정상으로 들어와 있다. 요청을 짧게 끊으면 된다.
    #
    # 그래서 이 스텝은 (a) 보유 구간을 먼저 조회해 **갭만** 요청하고,
    # (b) 남은 갭도 연 단위로 쪼개 호출당 ~206회(=성공이 확인된 H1 과 동일 규모)를 넘지 않게 한다.
    # 이미 받은 구간을 다시 요청하면 822회짜리 호출이 되살아나 또 실패한다.

    printf '\n\033[1m▶ 현재 보유 현황\033[0m\n'
    PLAN=$(api "$API/data/summary" | COINS="$OPS_COINS" TODAY="$TODAY" python3 -c 'import json, os, sys
from datetime import date

coins = json.loads(os.environ["COINS"])
today = date.fromisoformat(os.environ["TODAY"])
START = date(2022, 1, 1)
PER_DAY = {"M15": 96, "H1": 24}
FRESH_TOLERANCE_DAYS = 2   # 최근 N일 이내면 최신으로 본다 (매 실행마다 1일짜리 꼬리 호출 방지)
HEAD_TOLERANCE_DAYS = 30   # 앞구간이 이만큼 넘게 비어야 요청한다

rows = json.load(sys.stdin)["data"]
have = {}
for r in rows:
    if r.get("coinPair") in coins:
        have[(r["coinPair"], r["timeframe"])] = (
            date.fromisoformat(str(r["from"])[:10]),
            date.fromisoformat(str(r["to"])[:10]),
        )

# 상장일 추정 — 어떤 타임프레임이든 가장 이른 캔들이 그 코인의 하한이다.
# 이게 없으면 EUL(2026-07-26 상장)·PROM(2026-08-12)에 2022 구간을 매번 요청하게 되고,
# 빈 응답이라 영영 채워지지 않아 스크립트가 멱등해지지 않는다.
floor = {}
for (coin, _tf), (mn, _mx) in have.items():
    floor[coin] = min(floor.get(coin, mn), mn)

def emit(tf, coin, a, b):
    """[a, b) 를 연 단위로 쪼개 PLAN 줄로 낸다 — 호출당 요청 수를 상한 아래로 유지."""
    y = a.year
    while a < b:
        end = min(b, date(y + 1, 1, 1))
        days = (end - a).days
        if days > 0:
            print("PLAN=%s|%s|%s|%s|%d" % (tf, coin, a, end, (days * PER_DAY[tf] + 199) // 200))
        a = end
        y += 1

for tf in ("M15", "H1"):
    for coin in coins:
        base = floor.get(coin, START)
        rng = have.get((coin, tf))
        if rng is None:
            print("ROW=  %-4s %-10s 보유 없음  → %s ~ 오늘" % (tf, coin, base), file=sys.stderr)
            emit(tf, coin, base, today)
            continue
        mn, mx = rng
        gaps = []
        if (mn - base).days > HEAD_TOLERANCE_DAYS:
            gaps.append((base, mn))
        if (today - mx).days > FRESH_TOLERANCE_DAYS:
            gaps.append((mx, today))
        if not gaps:
            note = "완결" if base >= mn else "완결 (상장 %s)" % base
            print("ROW=  %-4s %-10s %s ~ %s  %s" % (tf, coin, mn, mx, note), file=sys.stderr)
            continue
        desc = " + ".join("%s~%s" % (a, b) for a, b in gaps)
        print("ROW=  %-4s %-10s %s ~ %s  갭: %s" % (tf, coin, mn, mx, desc), file=sys.stderr)
        for a, b in gaps:
            emit(tf, coin, a, b)
')
    echo "$PLAN" | sed -n 's/^ROW=//p'

    PLAN_LINES=$(echo "$PLAN" | sed -n 's/^PLAN=//p')
    if [ -z "$PLAN_LINES" ]; then
      echo
      echo "▶ 채울 갭이 없습니다. Step 2 로 진행하세요."
      exit 0
    fi

    printf '\n\033[1m▶ 수집 계획 (연 단위 분할)\033[0m\n'
    echo "$PLAN_LINES" | awk -F'|' '{printf "  %-4s %-10s %s ~ %s  (%s회)\n", $1,$2,$3,$4,$5}'
    total=$(echo "$PLAN_LINES" | awk -F'|' '{s+=$5} END {print s}')
    calls=$(echo "$PLAN_LINES" | wc -l)
    printf '\n  총 %s개 호출 / 누적 %s회 요청 — 호출당 상한 ~206회(성공 확인된 H1 과 동일 규모)\n' "$calls" "$total"

    printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
    read -r _

    ok=0; fail=0
    while IFS='|' read -r tf coin from to reqs; do
      [ -z "$tf" ] && continue
      printf '  %-4s %-10s %s ~ %s (%s회) … ' "$tf" "$coin" "$from" "$to" "$reqs"
      resp=$(api -X POST "$API/data/collect/batch" -H 'Content-Type: application/json' \
        -d "{\"coinPairs\": [\"$coin\"], \"timeframe\": \"$tf\", \"startDate\": \"$from\", \"endDate\": \"$to\"}")
      case "$resp" in
        *'"success":true'*) echo "요청 접수"; ok=$((ok+1)) ;;
        *)                  echo "거부: $(echo "$resp" | head -c 120)"; fail=$((fail+1)) ;;
      esac
      # 수집은 비동기라 접수는 즉시 끝난다. 운영 함대와 레이트리밋 예산을 나눠 쓰므로
      # 호출 사이를 벌려 동시 수집이 겹치지 않게 한다.
      sleep 45
    done <<EOF
$PLAN_LINES
EOF

    printf '\n\033[1m▶ 접수: 성공 %s / 거부 %s\033[0m\n' "$ok" "$fail"

    cat <<'NOTE'

▶ 수집은 백그라운드입니다. 코인마다 텔레그램 알림이 옵니다.
  이 스크립트는 **다시 돌려도 안전**합니다 — 매번 보유 구간을 다시 읽어 남은 갭만 요청합니다.
  실패한 구간이 있으면 그대로 재실행하세요. 계획이 비면 "채울 갭이 없습니다" 로 끝납니다.

  DB 로 직접 확인하려면:

    SELECT coin_pair, timeframe, count(*) AS n,
           min(time)::date AS mn, max(time)::date AS mx
    FROM candle_data
    WHERE coin_pair IN ('KRW-ADA','KRW-AVAX','KRW-BTC','KRW-DOGE',
                        'KRW-EUL','KRW-LINK','KRW-PROM','KRW-SOL')
    GROUP BY 1,2 ORDER BY 2,1;

  ※ KRW-EUL(2026-07-26 상장)·KRW-PROM(2026-08-12 상장)은 그 이전 구간이 애초에 없습니다.
    계획에서 앞구간이 잡히더라도 빈 응답이라 무해하며, 한 번 돌면 이후로는 제외됩니다.

  ※ 반복 실패 시 실제 예외 확인 (텔레그램의 "캔들 데이터 수집 실패" 는 래핑 메시지):

    docker compose -f docker-compose.prod.yml logs --since 3h backend \
      | grep -E "캔들 수집 실패|\[Batch\].*실패" -A5

  다음: STEP=2 bash scripts/revalidate_walk_forward_0908.sh
NOTE
    ;;
  2)
    printf '\n\033[1m▶ Step 2: WF 재검증 (5전략 × 8코인 × 2타임프레임 = 80조합)\033[0m\n'
    echo "  기존 350건은 0.3% 손절 기준이라 폐기 대상입니다. 새 결과로 판정을 다시 냅니다."
    printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
    read -r _

    for tf in H1 M15; do
      echo
      echo "▶ $tf — 5전략 × 8코인 = 40조합"
      api -X POST "$API/backtest/walk-forward-batch-async" -H 'Content-Type: application/json' \
        -d "{
          \"coinPairs\": $OPS_COINS,
          \"strategyTypes\": $OPS_STRATS,
          \"timeframe\": \"$tf\",
          \"startDate\": \"2022-01-01\",
          \"endDate\": \"$TODAY\",
          \"inSampleRatio\": 0.7,
          \"windowCount\": 5
        }"
      echo
    done

    cat <<'NOTE'

▶ 두 배치 모두 백그라운드 실행 중. 진행: GET /api/v1/backtest/jobs
  완료 후 STEP=3 으로 판정을 확인하세요.

  ⚠️ 누락 점검 — 2026-08-24 에 25조합이 조용히 빠진 적이 있습니다. 80건이 다 돌았는지
     반드시 세어 볼 것:

    SELECT timeframe, count(*) AS n, count(DISTINCT strategy_name) AS strats,
           count(DISTINCT coin_pair) AS coins
    FROM backtest_run
    WHERE is_walk_forward AND created_at >= '2026-09-08'
    GROUP BY 1;
NOTE
    ;;

  3)
    printf '\n\033[1m▶ Step 3: 게이트 판정 재확인\033[0m\n'
    api "$API/strategies/walk-forward-gate-status"
    cat <<'NOTE'


▶ 해석 시 주의
  1) 이번 판정은 **0.3% 손절이 아닌 정상 손절** 기준의 첫 판정입니다.
     이전 판정과 뒤집히는 조합이 나오는 것이 정상이며, 그것이 재검증의 목적입니다.

  2) H1 과 M15 를 나눠서 볼 것. 운영 함대는 고정코인 40세션이 전부 M15,
     동적 12세션이 H1 6 / M15 6 입니다. H1 판정으로 M15 함대를 판단할 수 없습니다.

  3) 판정이 나와도 `kill-criteria.auto-stop` 은 계속 OFF 로 둡니다.
     운영 페이퍼 표본(n≥20)이 새 지문으로 쌓인 뒤에 함께 봐야 합니다.

    SELECT p.ruleset_hash, count(*) AS n,
           round(100.0*count(*) FILTER (WHERE p.realized_pnl>0)/count(*),1) AS winrate
    FROM paper_trading.position p
    JOIN paper_trading.virtual_balance v ON v.id = p.session_id
    WHERE p.status='CLOSED' AND p.closed_at >= '2026-09-08'
    GROUP BY 1 ORDER BY 2 DESC;
NOTE
    ;;

  *)
    echo "✗ STEP 은 1·2·3 중 하나여야 합니다 (받은 값: $STEP)"
    exit 1
    ;;
esac
