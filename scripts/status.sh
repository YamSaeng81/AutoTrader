#!/usr/bin/env bash
#
# 지금 무엇을 봐야 하는가 — 상태 점검 (읽기 전용)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 왜 이 스크립트가 있나
#
#   2026-09-09 기준, 이 시스템은 "코드를 고칠 게 없고 데이터가 쌓이기를 기다리는" 상태다.
#   기다리는 동안 맥락을 머릿속에 담아 두는 것이 부담이라, **돌아왔을 때 아무것도 기억하지
#   못해도 다음 행동을 알 수 있도록** 한 화면에 모은다.
#
#   아무것도 바꾸지 않는다. SELECT 만 한다.
#
# ■ 배경 요약 (읽고 나면 아래 숫자가 이해된다)
#
#   2026-09-08  PAPER 전용 손절 버그 수정. 저가가 진입가를 1틱만 밑돌아도 SL 이
#               현재가×0.997 로 끌어올려져 5% 손절이 사실상 0.3% 손절로 동작했다.
#               청산 534건 중 85.4% 가 평균 0.9시간 만에 휩쏘로 털렸다(누적 −1,270만원).
#               → 그래서 09-08 이전의 청산 건수·승률은 **전부 참고가 안 된다.**
#
#   2026-09-08  백테스트 청산 규칙을 실전과 통일(ExitRuleFormula, EXIT_RULES_VERSION=2).
#               그전까지 백테스트만 SL 5% 고정 · TP 10% · time stop 없음이었다.
#
#   2026-09-09  그 규칙으로 WF 80조합 재검증 → **10개만 통과(12.5%)**,
#               OVERFITTING 61/80(76%), H1 은 40조합 중 2개만 생존.
#
#   현재 함대   고정코인 PAPER 40 (5전략 × 8코인, 전부 M15) + 동적 PAPER 12.
#               **LIVE 0 — 실자본 노출 없음.** 게이트도 꺼져 있어 막히는 것도 없다.
#
# ■ 지금 답을 기다리는 질문
#
#   ① 새 청산 규칙이 이전보다 나은가          → 지문 단위 n≥20 (함대 합산)
#   ② **WF 판정이 예측력이 있는가**            → PASS 세션 묶음 vs FAIL 세션 묶음
#
#   ③ 조합 하나하나가 좋은가 → **포기했다.** 조합당 하루 0.075건이라 n=20 에 ~9개월이다.
#      개별 조합 대신 ②처럼 묶어서 본다. 알고 싶은 것도 사실 ② 다 —
#      "백테스트가 현실을 예측하는가".
#
# ■ 사용법
#
#   운영 서버 리포 루트에서:
#       bash scripts/status.sh
#
#   환경변수: PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD (미설정 시 아래 기본값·비번은 프롬프트)
#             COMPOSE_FILE (기본 docker-compose.prod.yml) · DB_SERVICE (기본 db)

set -euo pipefail

export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-crypto_auto_trader}"
export PGUSER="${PGUSER:-trader}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
DB_SERVICE="${DB_SERVICE:-db}"

# 수정 배포 시각 — 이 시점 이후 청산만 "정상 손절" 이다. 그 전 데이터와 절대 섞지 말 것.
FIX_AT="2026-09-08 00:26+00"   # 2026-09-08 09:26 KST

if [ -z "${PGPASSWORD:-}" ]; then
  read -r -s -p "DB 비밀번호: " PGPASSWORD
  echo
  export PGPASSWORD
fi

if command -v psql >/dev/null 2>&1; then
  run_psql() { psql "$@"; }
elif docker compose -f "$COMPOSE_FILE" ps "$DB_SERVICE" >/dev/null 2>&1; then
  run_psql() {
    docker compose -f "$COMPOSE_FILE" exec -T \
      -e PGPASSWORD="$PGPASSWORD" \
      "$DB_SERVICE" psql -U "$PGUSER" -d "$PGDATABASE" "$@"
  }
else
  echo "❌ psql 도 '${DB_SERVICE}' 컨테이너도 찾지 못했다. 리포 루트에서 실행 중인지 확인할 것." >&2
  exit 1
fi

run_psql -v ON_ERROR_STOP=1 -v fix_at="$FIX_AT" <<'SQL'
\timing off
\pset pager off

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════'
\echo '  상태 점검 — 아무것도 바꾸지 않습니다 (SELECT only)'
\echo '═══════════════════════════════════════════════════════════════════════'

-- WF 판정이 통과시킨 (전략, 코인, 타임프레임) 조합.
-- 게이트와 같은 규칙: 현재 청산 규칙(v2 이상) · OVERFITTING/판정불가 아님 · n≥5 · 기대값>0
CREATE TEMP VIEW wf_latest AS
SELECT DISTINCT ON (strategy_name, coin_pair, timeframe)
       strategy_name, coin_pair, timeframe,
       wf_result_json->>'verdict' AS verdict,
       (wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric AS exp_pct,
       (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int        AS wf_n
FROM backtest_run
WHERE is_walk_forward AND coalesce(exit_rules_version, 0) >= 2
ORDER BY strategy_name, coin_pair, timeframe, created_at DESC;

CREATE TEMP VIEW wf_pass AS
SELECT strategy_name, coin_pair, timeframe
FROM wf_latest
WHERE verdict NOT IN ('OVERFITTING', 'INSUFFICIENT_DATA')
  AND wf_n >= 5 AND exp_pct > 0;

-- 수정 배포 이후 청산된 페이퍼 포지션 + 그 세션이 WF 통과 조합인지
CREATE TEMP VIEW closes AS
SELECT p.closed_at, p.realized_pnl, p.invested_krw, p.ruleset_hash, p.exit_reason,
       v.strategy_name, v.coin_pair, v.timeframe,
       (w.strategy_name IS NOT NULL) AS wf_passed
FROM paper_trading.position p
JOIN paper_trading.virtual_balance v ON v.id = p.session_id
LEFT JOIN wf_pass w
       ON w.strategy_name = v.strategy_name
      AND w.coin_pair     = v.coin_pair
      AND w.timeframe     = v.timeframe
WHERE p.status = 'CLOSED'
  AND p.closed_at >= timestamptz :'fix_at';

\echo ''
\echo '━━━ 0. 배포 확인 — 이게 틀리면 아래 숫자가 전부 무의미하다 ━━━'
\echo '    exit_rules_version 이 2 인 WF 실행이 있어야 하고(기대: H1 40 / M15 40),'
\echo '    sl_gap 은 −5% 근처여야 한다(−0.4% 근처면 손절 버그가 살아 있는 것).'
SELECT timeframe,
       count(*) AS wf_runs,
       count(DISTINCT strategy_name) AS strats,
       count(DISTINCT coin_pair)     AS coins
FROM backtest_run
WHERE is_walk_forward AND exit_rules_version = 2
GROUP BY 1 ORDER BY 1;

SELECT round(avg((stop_loss_price/entry_price - 1) * 100), 3) AS sl_gap_pct_avg,
       count(*) AS positions
FROM paper_trading.position
WHERE opened_at >= timestamptz :'fix_at'
  AND entry_price > 0 AND stop_loss_price IS NOT NULL;

\echo ''
\echo '━━━ 1. 청산 속도 — 수정 배포 이후만 (그 전은 휩쏘라 참고 불가) ━━━'
SELECT closed_at::date AS d, count(*) AS closes
FROM closes GROUP BY 1 ORDER BY 1;

\echo ''
\echo '    일평균과 남은 기간 추정:'
SELECT round(count(*)::numeric / greatest(count(DISTINCT closed_at::date), 1), 2) AS closes_per_day,
       count(*) AS total_closes,
       greatest(20 - count(*), 0) AS remaining_to_20,
       CASE WHEN count(*) = 0 THEN '표본 없음 — 며칠 더'
            ELSE ceil(greatest(20 - count(*), 0)
                 / (count(*)::numeric / greatest(count(DISTINCT closed_at::date), 1)))::text
                 || '일 남음 (① 지문 단위)'
       END AS eta
FROM closes;

\echo ''
\echo '━━━ 2. 질문 ① 새 규칙이 작동하는가 — 지문별 성적 ━━━'
\echo '    지문(ruleset_hash)이 다르면 다른 규칙의 거래다. 절대 합산하지 말 것.'
SELECT ruleset_hash,
       count(*) AS n,
       round(100.0 * count(*) FILTER (WHERE realized_pnl > 0) / count(*), 1) AS winrate,
       round(sum(realized_pnl)) AS sum_pnl,
       CASE WHEN count(*) >= 20 THEN '판정 가능' ELSE '표본 부족 (n≥20 필요)' END AS status
FROM closes
GROUP BY 1 ORDER BY 2 DESC;

\echo ''
\echo '━━━ 3. 질문 ② WF 예측력 — PASS 세션 묶음 vs FAIL 세션 묶음 ━━━'
\echo '    이게 핵심이다. WF 가 통과시킨 조합이 실제로 더 나은가?'
\echo '    양쪽 다 n≥20 이 돼야 비교할 수 있다.'
SELECT CASE WHEN wf_passed THEN 'WF PASS' ELSE 'WF FAIL' END AS grp,
       count(*) AS n,
       round(100.0 * count(*) FILTER (WHERE realized_pnl > 0) / count(*), 1) AS winrate,
       round(sum(realized_pnl))                       AS sum_pnl,
       round(100.0 * sum(realized_pnl)
             / nullif(sum(invested_krw), 0), 3)       AS return_pct
FROM closes
GROUP BY 1 ORDER BY 1;

\echo ''
\echo '    ※ 아직 한쪽이라도 n<20 이면 방향만 보고 결론 내지 말 것.'
\echo '      승률·수익률이 반대로 나와도 표본이 작으면 우연일 수 있다.'

\echo ''
\echo '━━━ 4. 청산 사유 분포 — 손절 버그 재발 감시 ━━━'
\echo '    STOP_LOSS 가 다시 80%대로 치솟거나 평균 보유가 1시간 밑으로 내려가면'
\echo '    09-08 과 같은 일이 재발한 것이다. 즉시 확인할 것.'
SELECT coalesce(exit_reason, '(없음)') AS exit_reason,
       count(*) AS n,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct
FROM closes GROUP BY 1 ORDER BY 2 DESC;

\echo ''
\echo '━━━ 5. WF 통과 조합 (참고) ━━━'
SELECT timeframe, strategy_name, coin_pair, verdict,
       round(exp_pct, 3) AS exp_pct, wf_n
FROM wf_latest
WHERE (strategy_name, coin_pair, timeframe) IN (SELECT * FROM wf_pass)
ORDER BY timeframe, exp_pct DESC;

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════'
SQL

cat <<'NEXT'

▶ 읽는 법 — 위에서 아래 순서로 판단하면 된다

  [0] 배포 확인
      WF 실행이 H1 40 / M15 40 이 아니면 → 재검증이 덜 끝난 것:
          STEP=2 bash scripts/revalidate_walk_forward_0908.sh
      sl_gap 평균이 −5% 근처가 아니면 → 손절 버그 재발. 다른 건 다 제쳐두고 이것부터.

  [1] 청산 속도
      "며칠 남음" 이 나오면 그때 다시 오면 된다. 그 사이 할 일은 없다.
      세션을 늘려도 빨라지지 않는다 — 같은 신호를 같은 시각에 보므로 표본이 상관된다.
      (09-08 에 96→40 으로 줄인 것도 그 중복 때문이었다.)

  [2] 질문 ①  n≥20 이 되면:
      승률과 sum_pnl 을 본다. 09-08 이전은 승률 13% / 누적 −1,270만원이었다.
      그보다 나으면 수정이 실제로 먹혔다는 뜻이다.

  [3] 질문 ②  양쪽 다 n≥20 이 되면 — **여기가 이 프로젝트의 갈림길이다**

      PASS 묶음이 FAIL 묶음보다 뚜렷이 낫다
        → 백테스트에 예측력이 있다. 그때 처음으로 실자본 배정을 논할 근거가 생긴다.
          (REQUIRE_WALK_FORWARD_GATE 를 켜고, LIVE 세션을 통과 조합으로 시작)

      차이가 없거나 반대다
        → 백테스트가 여전히 현실을 모사하지 못한다. 세션을 늘리거나 전략을 더 만드는 것은
          의미가 없고, **무엇이 빠졌는지**를 찾는 쪽이 맞다.
          어느 쪽이든 답이 나온다는 점이 중요하다.

  [4] 손절 사유 분포
      STOP_LOSS 비중이 80%대로 튀면 09-08 재발. 그 외에는 신경 쓸 것 없다.

▶ 지금 코드 쪽에 남은 일

  없음. 알려진 결함 0건, 테스트 500여 개 통과.
  유일한 선택지는 EUL·PROM 10세션 교체다 — 상장이 2026-07-26 / 08-12 라 캔들이 부족해
  **어떤 판정도 나올 수 없는 코인**이 격자의 25% 를 차지한다. 급하지 않다.

▶ 안전 상태

  LIVE 0 세션 · 실자본 0원 · 게이트 비활성.
  며칠 손을 놓아도 잃을 것이 없다. 문제가 생기면 텔레그램이 알린다.

NEXT
