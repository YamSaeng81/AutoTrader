SID=203; M="D:/tmp/w3-evidence/run3/f"; mkdir -p "$M"
PSQL="docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader"
export RT_SHIFT='56 days 1 minute'
EGS_LOCAL='1|0|1|true|-|-|0'   # 로컬: 실자금 LIVE 1 · DYNAMIC 0 · 기준 행 1 · 위험 한도 3.0/15.0 · 과거 비상 정지 없음
START_DATE=2026-12-01   # 시작 예정일(KST). 연기하면 이 줄을 문서에서 고쳐 커밋한 뒤 그 값으로 쓴다
NEW=1899b49f633f8aefdc66b15c42debdaba8b3852c   # v1.132: #26(11-06 13:36 KST) 결과 수용. 이전 값 ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8(#25). v1.129: #25(11-06 10:32 KST) 결과 수용. 이전 값 7a944723c2959898226c2ad80f6b079ab5678296(#24). v1.123: #24(11-05 19:41 KST) 결과 수용. 이전 값 1103f48505a1457029f6555547a87aa986535738(#23). v1.116: 개입 #23(2026-11-05 15:29 KST) 운영 결과 수용 뒤 기대 HEAD. 이전 값 fe01207f3f60e97e308925157f84a1bb2da04388(#22). 이전 값 6995c52259e3de4c7c922a32f9fb2aa9ddd49f00(#21). 이전 값 b1d7664db73222aec5d86f2e9137debef0ff4df3(#20). 이전 값 ea4490099f0fa6b614fe71102534b0ba84c0eb7f(#19). 이전 값 5bb0544926a694832e75234670bb8f99dfe33bb0(#18), cad393956e131c257195fcd08fcac18c83802788(#17), f0872e2547c918a7b5146f027d325bd441ddf0d7(#16), 82d15d42fa22bb7d0f24a8243ebc888a2a7f071c
sc() { $PSQL -At -F ' | ' -c "
  SELECT count(*) AS total,
         count(*) FILTER (WHERE status = 'RUNNING' AND id NOT IN (576,577,578)) AS running_63,
         count(*) FILTER (WHERE status = 'STOPPED' AND id IN (576,577,578))     AS stopped_3,
         coalesce(string_agg(id || ':' || status, ' ' ORDER BY id) FILTER (WHERE NOT (
             (id IN (576,577,578) AND status = 'STOPPED') OR
             (id NOT IN (576,577,578) AND status = 'RUNNING'))), '-') AS mismatch
    FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587" ; }
svc() { for s in backend db redis db-backup frontend; do docker inspect --format "$s {{.Image}} {{.State.StartedAt}}" "$($DC ps -q $s)"; done ; }
EG="SELECT (SELECT count(*) FROM live_trading_session WHERE session_type = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')) || '|' ||
           (SELECT count(*) FROM dynamic_session WHERE trading_mode = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')) || '|' ||
           (SELECT count(*) FROM capital_stage_baseline) || '|' ||
           (SELECT (max_daily_loss_pct = 3.0 AND max_portfolio_drawdown_pct = 15.0)::text FROM risk_config ORDER BY id DESC LIMIT 1) || '|' ||
           (SELECT coalesce(string_agg(id::text, ',' ORDER BY id), '-') FROM live_trading_session WHERE session_type = 'REAL' AND status = 'EMERGENCY_STOPPED') || '|' ||
           (SELECT coalesce(string_agg(id::text, ',' ORDER BY id), '-') FROM dynamic_session WHERE trading_mode = 'REAL' AND status = 'EMERGENCY_STOPPED') || '|' ||
           ((SELECT count(*) FROM live_trading_session WHERE session_type = 'REAL' AND status NOT IN ('RUNNING','PAUSED','CREATED','EMERGENCY_STOPPED','STOPPED','DELETED')) +
            (SELECT count(*) FROM dynamic_session WHERE trading_mode = 'REAL' AND status NOT IN ('RUNNING','PAUSED','CREATED','EMERGENCY_STOPPED','STOPPED','DELETED')))"
EGS='1|0|1|true|157,161,166,167,168,183,184,185,186,187,188,189,190,191,192,193,194,195,196,200|92|0'
LIVE1="SELECT coalesce(string_agg(id || ':' || status, ',' ORDER BY id), '-') FROM live_trading_session WHERE session_type = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')"
BASE="SELECT stage || '|' || to_char(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '|' || total_cap_krw FROM capital_stage_baseline"
BASEX='STAGE_1|2026-10-30 04:16:57.997762|100000.00'
KEEP="SELECT session_type = 'REAL' AND strategy_type = 'DAILY_SMA20' AND coin_pair = 'KRW-BTC' AND timeframe = 'H1'
  AND initial_capital = 20200 AND invest_ratio = 0.995 AND max_investment = 20000 AND stop_loss_pct = 5.0
  AND max_hold_hours = 0 AND coalesce(strategy_params::text, '{}') = '{}'
  AND to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') = '2026-11-06 09:10:44.690486' FROM live_trading_session WHERE id = $SID"
# v1.56 (N08): BTC H1 입력 — B1 필요 완료 봉(운영 CandleFreshnessGuard 와 같은 2분 유예) 충족 | 최신 완료 봉 | 필요 봉 | B3 최근 20일 마감 봉(23:00 UTC 시작) 개수/20
BTCIN="WITH h AS (SELECT max(time) FILTER (WHERE time + interval '1 hour' <= now()) AS done, date_trunc('hour', now() - interval '2 minutes') - interval '1 hour' AS need FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1'),
  d AS (SELECT (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '1 hour' - make_interval(days => k)) AT TIME ZONE 'UTC' AS t FROM generate_series(0, 19) k)
SELECT coalesce(h.done >= h.need, false) || '|' || coalesce(to_char(h.done AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI'), '-') || '|' || to_char(h.need AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI')
  || '|' || (SELECT count(c.close) FROM d JOIN market_data_cache c ON c.coin_pair = 'KRW-BTC' AND c.timeframe = 'H1' AND c.time = d.t) || '/20' FROM h"
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
F1_DAY=$(TZ=Asia/Seoul date +%F)
[ ! -e "$M/f1-failed-$F1_DAY" ] || stop "오늘 F1 실패 기록이 있다 — 오늘은 시작하지 않고 보고"
trap 'rc=$?; if [ "$rc" -ne 0 ]; then printf "F1 실패 — %s · 종료 %s\n" "$F1_DAY" "$rc" > "$M/f1-failed-$F1_DAY"; fi' EXIT
F1_PREVIOUS="$M/f1-attempts/$(date -u +%Y%m%dT%H%M%SZ)-${BASHPID:-$$}"
mkdir -p "$F1_PREVIOUS"
for f in "$M"/*-f1.* "$M/ok-F1" "$M/operator-confirm.txt"; do
  [ ! -f "$f" ] || cp -p "$f" "$F1_PREVIOUS/"
done
rm -f "$M/ok-F1" "$M/operator-confirm.txt"             # 이전 통과·확인으로 새 실패를 우회하지 않는다(사본은 위 폴더 보존)
date '+%F %T %Z' | tee "$M/t-f1.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-f1.utc"
IMGF1=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1   # v1.132: #26 배포 이미지. 이전 기대 ~/maint-n19/image-new.txt(#25, 200f2f57…). v1.129: #25 배포 이미지. 이전 기대 ~/maint-n17/image-new.txt(#24, e51b512a…). v1.123: #24 배포 이미지. 이전 기대 ~/maint-n15n16/image-new.txt(#23, 9818254c…). v1.116: #23 배포 이미지. 이전 기대 ~/maint-n13/image-new.txt(#22, 9cb5d84f…). 이전 기대 ~/maint-n12/image-new.txt(#21, 67294e80…). 이전 기대 ~/maint-n11/image-new.txt(#20, fd3ac351…). 이전 기대 ~/maint-n08/image-new.txt(#19, 61804654…). 이전 기대 ~/maint-n04/image-new.txt(#18, 39875d5f…), ~/maint-rounding/image-new.txt(#17, a747a8e6…)
# N18 제안: 정기 점검 설치판 대조(읽기 전용) — 누락·읽기 실패·불일치면 위 trap 의 당일 실패 표지로 끝나고 ok-F1 을 만들지 않는다. 설치·복구·chmod·수동 점검 실행으로 고치지 않는다
INSX="$(printf 'INSTALLED_SHA c669ccdc6e248ce8e55e9a7ac7405197d266fa18\ndaily_check.sh ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc\ndaily_checks.sql 653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8')"
[ "$($PSQL -At -c "$EG" | tee "$M/eg-f1.txt")" = "$EGS_LOCAL" ] || stop "EG 가 EGS_LOCAL 과 다름 — eg-f1.txt 를 보고"
[ "$($PSQL -At -c "$LIVE1" | tee "$M/live1-f1.txt")" = "$SID:CREATED" ] || stop "실행 가능 실자금 세션이 203:CREATED 하나가 아님"
[ "$($PSQL -At -c "$KEEP" | tee "$M/keep-f1.txt")" = "t" ] || stop "SID 203 저장값이 생성 때와 다름"
$PSQL -x -c "SELECT id, status, initial_capital, available_krw, invest_ratio, max_investment, stop_loss_pct, max_hold_hours, strategy_params, started_at, created_at FROM live_trading_session WHERE id = $SID;" | tee "$M/session-f1.txt"
[ "$($PSQL -At -c "SELECT available_krw = 20200 AND started_at IS NULL FROM live_trading_session WHERE id = $SID")" = "t" ] || stop "available_krw 20200·started_at 비어 있음이 아님"
$PSQL -At -c "$BASE" | tee "$M/base-f1.txt"
$PSQL -At -F ' ' <<'SQL' | tee "$M/zero-f1.txt"
SELECT 'real_open_positions', count(*) FROM position p
  LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
  LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id
 WHERE p.status IN ('OPEN','CLOSING') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL');
SELECT 'real_unresolved_orders', count(*) FROM "order" o
  LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
  LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
 WHERE o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL');
SELECT 'cleanup_pending', count(*) FROM position_cleanup WHERE done_at IS NULL;
SELECT 'sid_orders', count(*) FROM "order" WHERE session_kind = 'LIVE' AND session_id = 203;
SELECT 'sid_positions', count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = 203;
SQL
[ "$(awk '{s+=$2} END {print s+0}' "$M/zero-f1.txt")" = "0" ] || stop "활성 주문·열린 포지션·정리 대기·SID 203 주문/포지션 중 0 이 아닌 것 있음"
$PSQL -At -c "$BTCIN" | tee "$M/btc-f1.txt"
[ "$(cut -d'|' -f1 "$M/btc-f1.txt")" = true ] && [ "$(cut -d'|' -f4 "$M/btc-f1.txt")" = 20/20 ] || stop "BTC H1 입력 미충족 — 2분 유예 필요 완료 봉 또는 최근 20일 마감 봉 20/20 아님(btc-f1.txt 를 보고)"
curl -s http://127.0.0.1:18081/api/v1/trading/health/exchange | tee "$M/exchange-f1.json"; echo
python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["data"]; print("거래소", d["status"], d["latencyMs"], "ms"); sys.exit(0 if d["status"] == "UP" else 1)' "$M/exchange-f1.json" || stop "거래소 연결 UP 아님"
TOKEN=rt-token
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18081/api/v1/account/summary > "$M/account-f1.json"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["data"]; a=float(d["availableKrw"]); l=float(d["lockedKrw"]); b=[(h["balance"], h["locked"]) for h in d.get("holdings", []) if h["currency"] == "BTC"]; print("KRW 주문 가능", a, "· 묶인 KRW", l, "· BTC", b or "없음"); sys.exit(0 if a >= 20200 and l == 0 else 1)' "$M/account-f1.json" || stop "KRW 주문 가능 20,200원 미만이거나 묶인 KRW 있음(업비트 미체결) 또는 조회 실패"
touch "$M/ok-F1"; echo "✅ F1 기계 점검 통과 — 경보 네 갈래와 휴대폰 수신을 확인하고 운영자 확인 파일을 쓴 뒤 08:50 F2"
)
