# 제한 실거래 실행 절차 — F0~F6

> 현재 상태와 남은 작업은 [현재 계획](LIMITED_LIVE_TEST_PLAN.md)을 먼저 읽는다. 이 문서는 명령 원문이다. **최종 원본 S는 미확정**이며, 이 문서 분리는 새로운 실행·배포 승인이 아니다.

2026-11-12에 종전 계획서 §5-8의 F0~F6을 분리했다. **15개 bash 블록 본문은 바이트 단위로 같다.** 운영 HEAD·이미지·경로·START_DATE·SID·한도와 명령을 바꾸지 않았다. 기존 F1·N06 해시와 실제 운영 핀은 별도 변경 판정 전 유지한다.

## 문서와 추출 도구의 연결

- 아래 F 이름·마커는 기존대로 유지한다. F 변수부터 필요한 블록을 정해진 순서로 사용한다.
- `test_start_gate.py`·`test_sid_ledger.py`와 cron 시험의 F2-1은 이 문서에서 추출한다.
- 로컬 리허설의 `RT_PLAN_DOC`는 이 문서를 가리킨다. 기존 `LIMITED_LIVE_TEST_PLAN.md` 경로를 준 호출도 같은 폴더의 이 문서로 연결한다.
- 과거 SID 생성 요청 본문과 장부 cron 추가 시험은 [원문 보존본](LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md)에서만 읽는다. **이미 생성된 SID 203을 다시 만드는 명령이 아니다.**
- 옛 §5-8은 아래 F 절차, 옛 §6-2/§6-3은 이 문서 끝의 중단·종료 조건을 뜻한다. 과거 구현 설명·개발 결과·배포 절차는 보존본에서 확인한다.

**분리 후 남은 연결 보완:** F5-3 원문은 아직 `git show "$SHA":docs/LIMITED_LIVE_TEST_PLAN.md`에서 F3를 추출한다. 분리 이후 SHA의 현재 계획에는 F3가 없어 추출 결과가 0바이트이며, `awk`는 종료 0을 반환한다. **이 상태의 F5-3을 최종 S의 실행 사본으로 사용하지 않는다.** 최종 고정 전에 RUNBOOK 경로에서 추출하도록 새 출처를 검토하고, F3 비어 있음·기대 해시 불일치 시 실행 전에 멈추는 검사를 확인한다. 아래 고정 명령 본문과 과거 해시는 이번 점검에서 바꾸지 않았다. [추가 문서 점검](evidence/document-followup-review-20261112/README.md) 참조. → **v1.159(398차) 보완: 새 출처 [F5-3v](#f5-3v)를 F5-3 뒤에 추가했다.** 같은 커밋의 RUNBOOK에서 F3를 꺼내고, 꺼내기 실패·0바이트·기대 해시(`bb86fb39…`) 불일치면 F3·완료 조회를 실행하지 않고 멈춘다. 기존 15블록은 바이트 그대로이며, 대역 시험은 `scripts/ops/test_f53v_source.py`다. 최종 S의 기대 해시는 S 고정 때 다시 확인한다.

## F 절차

**F0. 비용 조건 — ✅ 충족 (2026-10-30, 운영자 직접 진술 수용; 서버 명령 없음)**
- **운영자 원문(이번 대화)**: "F0 비용은 다 무료야. 전기 통신 모든게. 그렇게 처리할수있게해줘 더이상 묻지않게"
- **기록·판정**: 서버·전기·통신을 포함한 이번 제한 실거래 시험의 운영비를 **월 0원**으로 기록한다. 운영자의 명시적 확인을 근거로 월 100,000원 한도 이내이며 **F0 충족**으로 수용한다. 진술 시점은 이번 대화이고 문서 반영일은 2026-10-30이다.
- **근거 수준**: 운영자 직접 진술이다. 청구·사용량 화면, 무료 혜택 종료일, 유료 전환 후 예상액을 확인한 것으로 기록하지 않는다. 기존 화면 확인 요구는 이번 운영자 결정으로 대체하며, **동일 비용 확인·증빙·종료일 질문을 반복하지 않는다.**
- **이후 처리**: 새 비용 발생 또는 유료 전환 사실이 전달되면 그 변경분만 기록하고 월 100,000원 한도를 적용한다. 실매매 수수료·슬리피지 측정은 기존 규칙을 따른다.
- **시작 일정 확인**: 리뷰 회신 문안에 "12월 2일"로 적힌 데 대해 운영자가 "1일이 맞아"라고 확인했다(운영자 원문, 2026-10-30). 기존 **2026-12-01(화) 08:50 KST** 유지. `START_DATE=2026-12-01`과 F1~F4 명령은 변경하지 않는다. 시작에는 당일 F1과 경보·휴대폰 수신 확인이 필요하다.

**F 변수** — 🔴 12-01 새 셸마다 먼저 (#15 폴더 `MS` 는 읽기만)
```bash
cd ~/crypto_trader/AutoTrader
DC="docker compose -f docker-compose.prod.yml"; SID=203
START_DATE=2026-12-01   # 시작 예정일(KST). 연기하면 이 줄을 문서에서 고쳐 커밋한 뒤 그 값으로 쓴다
M=~/start-sid203; MS=~/maint-20261030-stage1; mkdir -p "$M"
NEW=b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88   # v1.141: O1·O3+N21 운영 결과 수용. 이전 값 1899b49f633f8aefdc66b15c42debdaba8b3852c(#26). v1.132: #26(11-06 13:36 KST) 결과 수용. 이전 값 ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8(#25). v1.129: #25(11-06 10:32 KST) 결과 수용. 이전 값 7a944723c2959898226c2ad80f6b079ab5678296(#24). v1.123: #24(11-05 19:41 KST) 결과 수용. 이전 값 1103f48505a1457029f6555547a87aa986535738(#23). v1.116: 개입 #23(2026-11-05 15:29 KST) 운영 결과 수용 뒤 기대 HEAD. 이전 값 fe01207f3f60e97e308925157f84a1bb2da04388(#22). 이전 값 6995c52259e3de4c7c922a32f9fb2aa9ddd49f00(#21). 이전 값 b1d7664db73222aec5d86f2e9137debef0ff4df3(#20). 이전 값 ea4490099f0fa6b614fe71102534b0ba84c0eb7f(#19). 이전 값 5bb0544926a694832e75234670bb8f99dfe33bb0(#18), cad393956e131c257195fcd08fcac18c83802788(#17), f0872e2547c918a7b5146f027d325bd441ddf0d7(#16), 82d15d42fa22bb7d0f24a8243ebc888a2a7f071c
PSQL="$DC exec -T db psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader"
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
  AND to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') = '2026-10-30 04:16:58.044684' FROM live_trading_session WHERE id = $SID"
# v1.56 (N08): BTC H1 입력 — B1 필요 완료 봉(운영 CandleFreshnessGuard 와 같은 2분 유예) 충족 | 최신 완료 봉 | 필요 봉 | B3 최근 20일 마감 봉(23:00 UTC 시작) 개수/20
BTCIN="WITH h AS (SELECT max(time) FILTER (WHERE time + interval '1 hour' <= now()) AS done, date_trunc('hour', now() - interval '2 minutes') - interval '1 hour' AS need FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1'),
  d AS (SELECT (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '1 hour' - make_interval(days => k)) AT TIME ZONE 'UTC' AS t FROM generate_series(0, 19) k)
SELECT coalesce(h.done >= h.need, false) || '|' || coalesce(to_char(h.done AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI'), '-') || '|' || to_char(h.need AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI')
  || '|' || (SELECT count(c.close) FROM d JOIN market_data_cache c ON c.coin_pair = 'KRW-BTC' AND c.timeframe = 'H1' AND c.time = d.t) || '/20' FROM h"
```

**F1. 직전 점검 — 12-01 08:30 KST 부터 (읽기 전용 + 텔레그램 시험 1회)**
```bash
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
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-f1.txt"
[ "$(head -1 "$M/git-f1.txt")" = "$NEW" ] && [ "$(sed -n 2p "$M/git-f1.txt")" = "(끝)" ] || stop "HEAD 가 b5cd1d0 가 아니거나 추적 파일 변경 있음 — 보고"
svc | tee "$M/svc-f1.txt"
IMGF1=sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055   # v1.141: O1·O3+N21 배포 이미지. 이전 기대 ~/maint-n20/image-new.txt(#26, 426a09a2…). v1.132: #26 배포 이미지. 이전 기대 ~/maint-n19/image-new.txt(#25, 200f2f57…). v1.129: #25 배포 이미지. 이전 기대 ~/maint-n17/image-new.txt(#24, e51b512a…). v1.123: #24 배포 이미지. 이전 기대 ~/maint-n15n16/image-new.txt(#23, 9818254c…). v1.116: #23 배포 이미지. 이전 기대 ~/maint-n13/image-new.txt(#22, 9cb5d84f…). 이전 기대 ~/maint-n12/image-new.txt(#21, 67294e80…). 이전 기대 ~/maint-n11/image-new.txt(#20, fd3ac351…). 이전 기대 ~/maint-n08/image-new.txt(#19, 61804654…). 이전 기대 ~/maint-n04/image-new.txt(#18, 39875d5f…), ~/maint-rounding/image-new.txt(#17, a747a8e6…)
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMGF1" ] && [ "$(cat ~/maint-o13n21/image-new.txt)" = "$IMGF1" ] || stop "backend 이미지가 O1·O3+N21 배포 이미지(52e12428…)와 다름 — 보고"
[ "$(cat ~/ops-scripts/SID_LEDGER_SHA)" = 8bfe8a9fa896f400b3fb6681fef021c99d6ba43b ] && [ "$(sha256sum < ~/ops-scripts/sid_ledger.sql | cut -d' ' -f1)" = 02a8585021fb2a1a52d9b3b56b60e957cb71b9ca3c11bf819595df15cf334f1c ] || stop "설치 장부가 8bfe8a9 판(02a85850…)이 아님 — 보고"
# N18 제안: 정기 점검 설치판 대조(읽기 전용) — 누락·읽기 실패·불일치면 위 trap 의 당일 실패 표지로 끝나고 ok-F1 을 만들지 않는다. 설치·복구·chmod·수동 점검 실행으로 고치지 않는다
INSX="$(printf 'INSTALLED_SHA c669ccdc6e248ce8e55e9a7ac7405197d266fa18\ndaily_check.sh ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc\ndaily_checks.sql 653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8')"
{ echo "INSTALLED_SHA $(cat ~/ops-scripts/INSTALLED_SHA 2>/dev/null || echo 읽기실패)"; for f in daily_check.sh daily_checks.sql; do echo "$f $({ sha256sum < ~/ops-scripts/$f | cut -d' ' -f1; } 2>/dev/null || echo 읽기실패)"; done; } | tee "$M/install-f1.txt"
[ "$(cat "$M/install-f1.txt")" = "$INSX" ] || stop "정기 점검 설치판이 c669ccd(셸 ff0a5d7d…·SQL 653b22ba…)와 다르거나 읽을 수 없음 — install-f1.txt 를 보고"
$DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort | tee "$M/pool-f1.txt"
[ "$(cat "$M/pool-f1.txt")" = "$(printf 'TRADING_CAPITALPOOL_MAXCONCURRENTPOSITIONS=3\nTRADING_CAPITALPOOL_PERPOSITIONCAPKRW=20000\nTRADING_CAPITALPOOL_STAGE=STAGE_1\nTRADING_CAPITALPOOL_TOTALCAPKRW=100000' | sort)" ] || stop "공유 풀 등록값이 STAGE_1 · 100000 · 20000 · 3 과 다름 — pool-f1.txt 를 보고"
[ "$($PSQL -At -c "$EG" | tee "$M/eg-f1.txt")" = "$EGS" ] || stop "EG 가 EGS 와 다름 — eg-f1.txt 를 보고"
[ "$($PSQL -At -c "$LIVE1" | tee "$M/live1-f1.txt")" = "$SID:CREATED" ] || stop "실행 가능 실자금 세션이 203:CREATED 하나가 아님"
[ "$($PSQL -At -c "$KEEP" | tee "$M/keep-f1.txt")" = "t" ] || stop "SID 203 저장값이 생성 때와 다름"
$PSQL -x -c "SELECT id, status, initial_capital, available_krw, invest_ratio, max_investment, stop_loss_pct, max_hold_hours, strategy_params, started_at, created_at FROM live_trading_session WHERE id = $SID;" | tee "$M/session-f1.txt"
[ "$($PSQL -At -c "SELECT available_krw = 20200 AND started_at IS NULL FROM live_trading_session WHERE id = $SID")" = "t" ] || stop "available_krw 20200·started_at 비어 있음이 아님"
[ "$($PSQL -At -c "$BASE" | tee "$M/base-f1.txt")" = "$BASEX" ] || stop "기준 행이 생성 때와 다름"
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
sc | tee "$M/s-f1.txt"; [ "$(cat "$M/s-f1.txt")" = "66 | 63 | 3 | -" ] || stop "검증 66세션 상태가 기대와 다름"
curl -s http://localhost:8080/api/v1/trading/health/exchange | tee "$M/exchange-f1.json"; echo
python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["data"]; print("거래소", d["status"], d["latencyMs"], "ms"); sys.exit(0 if d["status"] == "UP" else 1)' "$M/exchange-f1.json" || stop "거래소 연결 UP 아님"
TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/account/summary > "$M/account-f1.json"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["data"]; a=float(d["availableKrw"]); l=float(d["lockedKrw"]); b=[(h["balance"], h["locked"]) for h in d.get("holdings", []) if h["currency"] == "BTC"]; print("KRW 주문 가능", a, "· 묶인 KRW", l, "· BTC", b or "없음"); sys.exit(0 if a >= 20200 and l == 0 else 1)' "$M/account-f1.json" || stop "KRW 주문 가능 20,200원 미만이거나 묶인 KRW 있음(업비트 미체결) 또는 조회 실패"
python3 scripts/prospective/start_66.py alerts --days 1 --no-mark > "$M/alerts-f1.txt" 2>&1 && AE=0 || AE=$?
echo "경보 종료코드 $AE"
{ [ "$AE" -le 1 ] && grep -q '^🔴 🔴 은 경보이고 확정이 아니다' "$M/alerts-f1.txt" && ! grep -q 'Traceback' "$M/alerts-f1.txt"; } || stop "경보 조회가 끝까지 돌지 않음 — alerts-f1.txt 를 보고"
echo "== 경보 (지금)"; cat "$M/alerts-f1.txt"
echo "== 경보 (#15 정비 전 기준선)"; cat "$MS/alerts-before.txt"
{ date '+%F %T %Z'; curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/settings/telegram/test; } | tee "$M/telegram-f1.txt"
[ "$(tail -1 "$M/telegram-f1.txt")" = "200" ] || stop "텔레그램 시험 200 아님"
touch "$M/ok-F1"; echo "✅ F1 기계 점검 통과 — 경보 네 갈래와 휴대폰 수신을 확인하고 운영자 확인 파일을 쓴 뒤 08:50 F2"
)
```
- 기대: `git-f1` = `1899b49f…` 다음 `(끝)` · 이미지 = #26 배포 이미지 전체 ID `sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1`(실행 이미지와 `~/maint-n20/image-new.txt` 가 둘 다 이 값) · 설치 장부 `SID_LEDGER_SHA` `8bfe8a9f…`·sha256 `02a85850…` · 정기 점검 `INSTALLED_SHA=c669ccd…`·셸 `ff0a5d7d…`·SQL `653b22ba…`(install-f1.txt) · 공유 풀 네 값이 정확히 `STAGE_1` · `100000` · `20000` · `3` · `EG` = `EGS` · `203:CREATED` · 저장값 `t` · `available_krw` 20200·`started_at` 비어 있음 · 기준 행 = `BASEX` · 0 다섯 줄 · BTC 입력 `true|12-01 07:00|12-01 07:00|20/20`(08:30 무렵 실행 기준, v1.56) · `sc` `66 | 63 | 3 | -` · 거래소 `UP` · KRW 주문 가능 ≥ 20,200·묶인 KRW 0 · 경보 조회 완료 · 텔레그램 `200`. 멈추면 F2 로 가지 않고 그 출력을 보고한다 — 그날은 시작하지 않는다.
- 새 시도는 이전 F1 파일·통과 표지·운영자 확인을 `f1-attempts/`에 보존하고 기존 통과·확인을 무효화한다. 실패하면 날짜별 `f1-failed-YYYY-MM-DD`를 남기며 그날 F1·F2는 모두 거절한다. 이 표지를 지워 재시도하지 않는다. F2는 통과 표지가 최신 시도(`t-f1.txt`) 뒤에 만들어졌는지도 확인한다.
- 경보는 `MS/alerts-before.txt` 와 네 갈래(기존 반복·새 경보·⚪·실행 누락)로 대조한다. KRW-BLAST 반복 밖의 🔴·⚪·실행 누락이 있으면 시작하지 않고 보고한다.
- 운영자 확인 파일 — 경보 대조와 휴대폰 수신(이번 시험 메시지의 말풍선 시각)을 본 뒤에만, 빈칸을 직접 채워 만든다:
```bash
printf '경보 네 갈래: %s\n휴대폰 수신 시각: %s\n' "<기존 반복만 / 다른 것 있음 — 내용>" "<HH:MM>" | tee "$M/operator-confirm.txt"
```

**F2. 시작 1회 — 12-01 08:50 KST (F1 통과 · 확인 파일 작성 뒤)**
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ ! -e "$M/f1-failed-$(TZ=Asia/Seoul date +%F)" ] || stop "오늘 F1 실패 기록이 있다 — 오늘은 시작하지 않고 보고"
[ -e "$M/ok-F1" ] || stop "F1 미통과"
[ -e "$M/t-f1.txt" ] && [ "$M/ok-F1" -nt "$M/t-f1.txt" ] || stop "F1 통과가 최신 시도 뒤의 결과가 아님 — 시작하지 않고 보고"
[ $(( $(date +%s) - $(stat -c %Y "$M/ok-F1") )) -le 1800 ] || stop "F1 통과가 30분보다 오래됨 — F1 부터 다시"
[ -s "$M/operator-confirm.txt" ] && ! grep -q '<' "$M/operator-confirm.txt" && [ "$M/operator-confirm.txt" -nt "$M/ok-F1" ] || stop "운영자 확인 파일 없음·자리표시 남음·F1 보다 오래됨"
! grep -q '다른 것 있음' "$M/operator-confirm.txt" || stop "경보에 기존 반복 밖의 것 있음 — 시작하지 않고 보고"
[ "$(TZ=Asia/Seoul date +%F)" = "$START_DATE" ] || stop "오늘 $(TZ=Asia/Seoul date +%F) KST 는 시작 예정일 $START_DATE 이 아님 — 시작하지 않는다"
HM=$(TZ=Asia/Seoul date +%H%M); [ $((10#$HM)) -ge 850 ] && [ $((10#$HM)) -le 855 ] || stop "지금 $HM KST — 08:50~08:55 밖이라 시작하지 않는다(다음 대응 가능한 날 08:50)"
[ ! -e "$M/start-sent" ] || stop "이미 시작 요청을 보냈다 — 다시 보내지 않는다. 아래 확인 조회부터"
[ "$($PSQL -At -c "$EG")" = "$EGS" ] || stop "EG 가 EGS 와 다름"
[ "$($PSQL -At -c "$LIVE1")" = "$SID:CREATED" ] || stop "203:CREATED 가 아님"
TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)
date '+%F %T %Z' | tee "$M/t-start.txt"; touch "$M/start-sent"
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/trading/sessions/$SID/start | tee "$M/start.json"; echo
)
```
- 🔴 **한 번만.** `start-sent` 표지가 두 번째 요청을 막는다. 응답이 없거나 애매해도 다시 부르지 말고 아래 확인부터 한다. 거절 문구가 오면 그대로 보고한다(다시 보내지 않는다).
- 시작 확인(1분 뒤, 읽기 전용 — 기대와 다르면 몇 분 뒤 이 조회만 다시):
```bash
$PSQL -x -c "SELECT id, status, available_krw, started_at, started_at AT TIME ZONE 'Asia/Seoul' AS started_kst, (started_at + interval '56 days') AT TIME ZONE 'Asia/Seoul' AS end_kst FROM live_trading_session WHERE id = $SID;" | tee "$M/start-check.txt"
$PSQL -At -c "$KEEP" | tee -a "$M/start-check.txt"                                  # t
$PSQL -At -c "$LIVE1" | tee -a "$M/start-check.txt"                                 # 203:RUNNING
$PSQL -At -c "$EG" | tee -a "$M/start-check.txt"; $PSQL -At -c "$BASE" | tee -a "$M/start-check.txt"   # EGS · BASEX
sc | tee -a "$M/start-check.txt"                                                    # 66 | 63 | 3 | -
```
- 기대: `status` `RUNNING` · `started_at` 08:50 KST 무렵(이 값이 56일 기산점) · `end_kst` = `started_at` + 56일 · 저장값 `t` · `203:RUNNING` · `EG` = `EGS` · 기준 행 = `BASEX`(다시 만들지 않음) · `sc` 같음. `CREATED` 그대로면 다시 부르지 말고 응답과 이 출력을 보고한다.

**F2-1. 정기 점검 정상 상태 전환 `CREATED → RUNNING` — F2 시작 확인 직후, 10:00 KST 첫 정기 점검 전 (v1.41, 리뷰 2026-11-01 3번)**
F2 시작 확인 출력이 `status` `RUNNING` · `203:RUNNING` 일 때만 한다. `CREATED` 그대로거나 F2 가 멈췄으면 하지 않는다(정기 점검은 `CREATED` 그대로가 맞다). 블록은 `OPS_DAILY_CHECK.md` §3-1 을 손으로 옮기지 않고 리뷰가 확인한 커밋에서 그대로 꺼낸다(F5-3 과 같은 방식). 새 셸이면 위 **F 변수**를 먼저 넣는다.
```bash
SHA=<리뷰가 확인한 커밋 SHA>
git fetch origin && git show "$SHA":docs/OPS_DAILY_CHECK.md | awk '/^### 3-1\. /{f=1; next} f && /^```bash$/{g=1; next} g && /^```$/{exit} g' > "$M/cron-switch.sh"
head -2 "$M/cron-switch.sh"; tail -2 "$M/cron-switch.sh"; sha256sum "$M/cron-switch.sh"   # 첫 줄 '(' · 둘째 줄 set -euo pipefail · 끝 두 줄 SID_OK_STATUS="$TO" … DRY_RUN=1 … / ')'
grep -c '^FROM=CREATED; TO=RUNNING' "$M/cron-switch.sh"                                   # 1
bash "$M/cron-switch.sh" 2>&1 | tee "$M/cron-switch.txt"
crontab -l | grep -c 'SID_OK_STATUS=RUNNING '; crontab -l | grep -c 'SID_OK_STATUS=CREATED '; crontab -l | grep -c 'sid_ledger.sql'   # 1 · 0 · 1
```
- 기대: `✅ cron 전환 CREATED → RUNNING · 보존본 … · 적용본 …` 한 줄 · 이어지는 `DRY_RUN` 결과에 `SID 203: RUNNING` 줄이 있고 `SID 203 상태` 🔴 줄이 없다(KRW-BLAST 반복 같은 기존 🟡·🔴 는 따로 본다) · 마지막 세 숫자 `1` `0` `1`(장부 기록 줄은 그대로).
- `🔴 멈춤` 이면 cron 은 바뀌지 않았다 — `cron-switch.txt` 와 `crontab -l` 출력을 그대로 보낸다. 블록을 고쳐 다시 돌리지 않는다. `🔴 적용 뒤 대조 실패` 면 안내대로 보존본으로 되돌리고 같은 것을 보낸다.
- 10:00 KST 첫 자동 보고의 `SID 203:` 줄이 `RUNNING` 이고 `SID 203 상태` 🔴 가 없으면 전환이 적용된 것이다. 시작 뒤 수동 점검은 늘 `SID_OK_STATUS=RUNNING` 을 적는다(`OPS_DAILY_CHECK.md` §4).

**F3. 첫 일봉 판단 확인 — 12-01 09:15 KST (읽기 전용)**
```bash
S=$($PSQL -At -c "SELECT started_at FROM live_trading_session WHERE id = $SID")
TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)
$PSQL -v s="$S" <<'SQL' 2>&1 | tee "$M/f3.txt"
BEGIN TRANSACTION READ ONLY;
\echo '== G1 SID 203 판단 기록(시작 뒤 전부)'
SELECT created_at AT TIME ZONE 'Asia/Seoul' AS kst, signal, signal_price, was_executed, blocked_reason, reason FROM strategy_log WHERE session_type = 'LIVE' AND session_id = 203 AND created_at >= :'s'::timestamptz ORDER BY id;
\echo '== G2 SID 203 주문'
SELECT id, position_id, side, order_type, state, quantity, filled_quantity, reported_executed_funds, applied_executed_funds, reported_paid_fee, applied_paid_fee, fee_source, settlement_final, fill_accounting_pending, signal_reason, created_at AT TIME ZONE 'Asia/Seoul' AS kst FROM "order" WHERE session_kind = 'LIVE' AND session_id = 203 ORDER BY id;
\echo '== G3 SID 203 포지션'
SELECT id, coin_pair, status, size, entry_price, avg_price, invested_krw, position_fee, realized_pnl, exit_reason, opened_at AT TIME ZONE 'Asia/Seoul' AS opened_kst, closed_at AT TIME ZONE 'Asia/Seoul' AS closed_kst FROM position WHERE session_kind = 'LIVE' AND session_id = 203 ORDER BY id;
\echo '== G4 SID 203 세션 잔액'
SELECT status, available_krw, total_asset_krw FROM live_trading_session WHERE id = 203;
\echo '== G5 정리 대기 — 기대 0'
SELECT count(*) FROM position_cleanup WHERE done_at IS NULL;
\echo '== G6 SID 203 미해결 정산 불일치 — 기대 0행'
SELECT d.id, d.order_id, d.kind, d.previous_value, d.received_value, d.previous_state, d.received_state, d.detected_at AT TIME ZONE 'Asia/Seoul' AS kst FROM order_settlement_discrepancy d JOIN "order" o ON o.id = d.order_id WHERE o.session_kind = 'LIVE' AND o.session_id = 203 AND d.resolved_at IS NULL ORDER BY d.id;
ROLLBACK;
SQL
# 장부(체결 있는 주문의 반영값만, 없는 값은 0 으로 채우지 않는다): 세션 상태 · 세션 잔액 · 원시 장부 = 20200 − Σ매수(반영 대금 + 반영 수수료) + Σ매도(반영 대금 − 반영 수수료)
#   · 잔액 기대(주문별 두 자리, v1.33) = 20200 − Σ매수(요청액 − round(요청액 − 반영 대금 − 반영 수수료, 2)) + Σ매도 round(반영 대금 − 반영 수수료, 2) · 주문 순체결량 · 열린 포지션 수량 합계
LEDGER=$($PSQL -At -F '|' -c "
SELECT l.status, l.available_krw,
       20200 - coalesce(sum(o.applied_executed_funds + o.applied_paid_fee) FILTER (WHERE o.side = 'BUY' AND o.filled_quantity > 0), 0)
             + coalesce(sum(o.applied_executed_funds - o.applied_paid_fee) FILTER (WHERE o.side = 'SELL' AND o.filled_quantity > 0), 0),
       20200 - coalesce(sum(o.quantity - round(o.quantity - o.applied_executed_funds - o.applied_paid_fee, 2)) FILTER (WHERE o.side = 'BUY' AND o.filled_quantity > 0), 0)
             + coalesce(sum(round(o.applied_executed_funds - o.applied_paid_fee, 2)) FILTER (WHERE o.side = 'SELL' AND o.filled_quantity > 0), 0),
       coalesce(sum(o.filled_quantity) FILTER (WHERE o.side = 'BUY'), 0) - coalesce(sum(o.filled_quantity) FILTER (WHERE o.side = 'SELL'), 0),
       (SELECT coalesce(sum(size), 0) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status IN ('OPEN','CLOSING'))
  FROM live_trading_session l LEFT JOIN \"order\" o ON o.session_kind = 'LIVE' AND o.session_id = l.id
 WHERE l.id = $SID GROUP BY l.status, l.available_krw")
# 개수: 판단 기록 · 체결 매수 · 체결 매도 · 미종결 주문 · 회계 반영 대기 · 체결 주문의 보고값·반영값 없음 · 보고값≠반영값 · settlement_final 거짓(참고) · 열린 포지션 · 정상 사유 밖 청산 · 정리 대기 · 미해결 정산 불일치
CNT=$($PSQL -At -F '|' -c "
SELECT (SELECT count(*) FROM strategy_log WHERE session_type = 'LIVE' AND session_id = $SID AND created_at >= (SELECT started_at FROM live_trading_session WHERE id = $SID)),
       count(*) FILTER (WHERE side = 'BUY' AND coalesce(filled_quantity, 0) > 0),
       count(*) FILTER (WHERE side = 'SELL' AND coalesce(filled_quantity, 0) > 0),
       count(*) FILTER (WHERE state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')),
       count(*) FILTER (WHERE fill_accounting_pending),
       count(*) FILTER (WHERE coalesce(filled_quantity, 0) > 0 AND (applied_executed_funds IS NULL OR applied_paid_fee IS NULL OR reported_executed_funds IS NULL OR reported_paid_fee IS NULL)),
       count(*) FILTER (WHERE coalesce(filled_quantity, 0) > 0 AND (reported_executed_funds <> applied_executed_funds OR reported_paid_fee <> applied_paid_fee)),
       count(*) FILTER (WHERE coalesce(filled_quantity, 0) > 0 AND NOT settlement_final),
       (SELECT count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status IN ('OPEN','CLOSING')),
       (SELECT count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status = 'CLOSED' AND coalesce(exit_reason, '-') NOT IN ('STOP_LOSS','TAKE_PROFIT','TRAILING_STOP')),
       (SELECT count(*) FROM position_cleanup WHERE done_at IS NULL),
       (SELECT count(*) FROM order_settlement_discrepancy d JOIN \"order\" o ON o.id = d.order_id WHERE o.session_kind = 'LIVE' AND o.session_id = $SID AND d.resolved_at IS NULL)
  FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = $SID")
# v1.57 (N08 검토): 입력 검사는 RUNNING 첫날 F3 에만 적용한다. F5-3 종료 대조에서는 조회하지 않는다.
# 입력 조회가 실패해도 기존 장부·계좌 대조를 보존한다. 실패한 조회의 부분 출력은 사용하지 않는다.
BTC3=; INP=
if [ "${LEDGER%%|*}" = RUNNING ]; then
BTC3=$($PSQL -At -c "$BTCIN") || BTC3=
INP=$($PSQL -At -F '|' -c "
SELECT count(*) FILTER (WHERE coalesce(reason, '') NOT LIKE '일봉 마감 봉 아님%' AND coalesce(reason, '') NOT LIKE '닫힌 캔들 미갱신%' AND NOT (
         coalesce(reason, '') LIKE '데이터 부족%' OR coalesce(reason, '') LIKE '일봉 종가 누락%' OR coalesce(reason, '') LIKE '전략 요구 캔들 미달%'
         OR coalesce(reason, '') LIKE 'BTC_MARKET_GUARD 판정 불가%' OR coalesce(blocked_reason, '') LIKE '%판정 불가%')),
       count(*) FILTER (WHERE coalesce(reason, '') LIKE '데이터 부족%' OR coalesce(reason, '') LIKE '일봉 종가 누락%' OR coalesce(reason, '') LIKE '전략 요구 캔들 미달%'
         OR coalesce(reason, '') LIKE 'BTC_MARKET_GUARD 판정 불가%' OR coalesce(blocked_reason, '') LIKE '%판정 불가%')
  FROM strategy_log WHERE session_type = 'LIVE' AND session_id = $SID AND created_at >= (SELECT started_at FROM live_trading_session WHERE id = $SID)") || INP=
fi
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/account/summary > "$M/account-f3.json"
python3 - "$M/account-f1.json" "$M/account-f3.json" "$LEDGER" "$CNT" "$BTC3" "$INP" <<'PY' 2>&1 | tee -a "$M/f3.txt"
import json, sys
from decimal import Decimal as D
def acct(p):  # 업비트 KRW(주문 가능 + 묶임, 원 단위 반올림) · BTC(보유 + 묶임)
    d = json.load(open(p))["data"]
    btc = sum((D(str(h["balance"])) + D(str(h["locked"])) for h in d.get("holdings", []) if h["currency"] == "BTC"), D(0))
    return D(str(d["availableKrw"])) + D(str(d["lockedKrw"])), btc
try:
    k1, b1 = acct(sys.argv[1]); k3, b3 = acct(sys.argv[2])
    st, avail, raw, expect, net, posq = sys.argv[3].split("|"); avail, raw, expect, net, posq = D(avail), D(raw), D(expect), D(net), D(posq)
    logs, buys, sells, unres, pend, miss, diff, unfinal, opn, badexit, cleanup, disc = (int(x) for x in sys.argv[4].split("|"))
except Exception as e:  # 장부·개수·계좌 조회 중 하나라도 실패하면 정상 판정을 내지 않는다
    print(f"❔ 판정: 조회 실패({type(e).__name__}) — 정상 판정 아님. F3 를 다시 조회, 그대로면 §6-2 대응·보고")
    sys.exit(1)
print("== 대조 (시작 전 기준 = F1 의 account-f1.json)")
print("세션 상태", st)
print("업비트 KRW", k1, "→", k3, "· BTC", b1, "→", b3)
if miss or pend:
    print("세션 잔액", avail, "· 장부 기대 계산 불가(반영값 없음 또는 회계 반영 대기)")
else:
    print("세션 잔액", avail, "· 잔액 기대(주문별 두 자리)", expect, "· 차이", avail - expect, "· 원시 장부", raw, "· 원시 차이(참고)", avail - raw)
print("거래소 KRW 증감", k3 - k1, "· 세션 증감", avail - 20200, "· 차이", (k3 - k1) - (avail - 20200), "(±1원 = 반올림)")
print("거래소 BTC 증감", b3 - b1, "· 주문 순체결량", net, "· 열린 포지션 수량", posq)
print("판단 기록", logs, "· 체결 매수", buys, "· 체결 매도", sells, "· 미종결 주문", unres, "· 회계 반영 대기", pend,
      "· 반영값·보고값 없음", miss, "· 보고≠반영", diff, "· settlement_final 거짓(참고)", unfinal,
      "· 열린 포지션", opn, "· 정상 사유 밖 청산", badexit, "· 정리 대기", cleanup, "· 미해결 정산 불일치", disc)
match = (b3 - b1) == net == posq and abs((k3 - k1) - (avail - 20200)) <= 1
if st != "RUNNING":
    print(f"⛔ 판정: 세션 상태 {st} — 정상 운영 아님. 이 출력 전체를 별도 보고(§6-2 완료 확인). 재시작·재요청 없음")
elif logs == 0 or unres or cleanup or badexit or buys > 1 or diff or disc:
    print("🔴 판정: 판단 기록 없음·미종결 주문·정리 대기·정상 사유 밖 청산·중복 매수·보고값≠반영값·미해결 정산 불일치(G6) 중 하나 — §6-2 대응·보고")
elif miss or pend:
    print("❔ 판정: 확인 불가 — 체결 주문의 보고값·반영값이 비었거나 회계 반영 대기. G2 줄로 확인하고 F3 를 다시 조회, 그대로면 §6-2 대응·보고")
elif avail != expect or not match:
    print("🔴 판정: 세션 잔액·장부·거래소 증감·포지션 수량 중 설명되지 않는 차이 — §6-2 대응·보고")
elif buys == 0 and sells == 0 and opn == 0:
    print("✅ 판정: 매수 없음 — 현금 대기 (현재 조회 기준 일치)")
elif buys == 1 and sells == 0 and opn == 1:
    print("✅ 판정: 매수 1건 보유 중 (현재 조회 기준 일치 — 정산 최종 확정을 뜻하지 않음)")
elif buys == 1 and sells == 1 and opn == 0:
    print("✅ 판정: 매수 뒤 정상 사유(손절·익절·추적) 청산 (현재 조회 기준 일치 — 정산 최종 확정을 뜻하지 않음)")
else:
    print("🔴 판정: 위 세 경우 밖의 조합 — §6-2 대응·보고")
print("== 입력 (정산 판정과 별개, v1.57)")
if st != "RUNNING":
    print(f"입력 판정: 적용 제외 — 세션 {st}. 종료 확인은 위 장부·계좌 대조와 F5-3 조건으로 판단")
else:
    try:
        in_ok, in_done, in_need, in_b3 = sys.argv[5].split("|")
        dec, short = (int(x) for x in sys.argv[6].split("|"))
        if in_ok not in ("true", "false") or dec < 0 or short < 0:
            raise ValueError("입력 조회 형식")
    except Exception as e:
        print(f"❔ 입력 판정: 조회 실패({type(e).__name__}) — 입력 정상 판정 아님. 위 정산 대조와 별도로 G1·조회 오류를 보고, F3 재조회")
        sys.exit(1)
    print("현재 BTC H1 최신 완료 봉", in_done, "· 필요(2분 유예)", in_need, "· 충족", in_ok, "· 최근 20일 마감 봉", in_b3)
    print("시작 뒤 누적 일봉 판단", dec, "· 시작 뒤 누적 입력 부족·판정 불가 기록", short, "(과거 기록 포함, 현재 미회복 확정 아님)")
    if in_ok != "true" or in_b3 != "20/20":
        print("🔴 입력 판정: 현재 BTC 입력 미충족 — 정상 전략 HOLD 로 쓰지 않는다. G1·이 출력을 보고(데이터 부족을 매수로 바꾸거나 기준을 완화하지 않는다)")
    elif dec == 0:
        print("🔴 입력 판정: 일봉 판단 없음 — 현재 BTC 입력 충족만으로 정상 전략 HOLD 로 쓰지 않는다. G1·이 출력을 보고")
    elif short:
        print("🔴 입력 판정: 시작 뒤 입력 부족·판정 불가 기록 있음 — 현재 BTC 입력은 충족. 과거 사유·회복 여부를 G1 과 대조해 보고, 정상 전략 HOLD 로 확정하지 않는다")
    else:
        print("✅ 입력 판정: 현재 입력 충족·일봉 판단 있음 — HOLD 면 정상 전략 HOLD, blocked_reason 이 있으면 관문 차단")
PY
```
- 판정은 주문 건수가 아니라 **세션 상태 · 중복 매수 · 매도 사유 · 실제 체결량 · 포지션 수량 · 수수료 · 잔액** 대조로 한다. 시작 전 기준은 F1 의 `account-f1.json`(08:30 업비트 KRW·BTC)이고, 거래소는 그 **증감분**으로 비교한다(시작 전 BTC 보유가 있어도 같다).
  - ~~세션 잔액 = 20200 − Σ매수(반영 대금 + 반영 수수료) + Σ매도(반영 대금 − 반영 수수료)~~ → **v1.33(리뷰 2026-10-31 반올림 결정): 세션 잔액 = 잔액 기대(주문별 두 자리) = 20200 − Σ매수(요청액 − round(요청액 − 반영 대금 − 반영 수수료, 2)) + Σ매도 round(반영 대금 − 반영 수수료, 2), 차이 0**(종전 ±0.01 허용을 없앴다). 원시 장부(8자리 반영값 그대로 합)와 그 차이는 참고로 출력한다. 체결 있는 주문의 `applied_*` 만 쓰고, **없는 값을 0 이나 다른 열로 채우지 않는다** — 없으면 "확인 불가" 다.
  - 체결 있는 주문은 보고값(`reported_*`)과 반영값(`applied_*`)이 같아야 한다.
  - 거래소 KRW 증감 = 세션 증감(±1원, 계좌 조회가 원 단위로 반올림한다).
  - **거래소 BTC 증감 = 주문 순체결량(Σ매수 − Σ매도 체결량) = 열린 포지션(`OPEN`·`CLOSING`) 수량 합계**, 셋 다 정확히 같다.
  - SID 203 주문의 미해결 정산 불일치(G6, `resolved_at` 없음)는 0건이어야 한다. 보고값·반영값이 같아도 1건 이상이면 🔴 다.
  - `settlement_final` 거짓은 참고로만 출력한다. 운영 코드는 정상 정산 뒤에도 이 값을 자동으로 참으로 만들지 않으므로 판정에 쓰지 않는다.
- ✅ 정상(셋 중 하나, 세션 `RUNNING` · 위 대조 모두 일치 · 회계 반영 대기 0 · 미종결 0 · 정리 대기 0 · 미해결 정산 불일치 0 · 판단 기록 1개 이상). ✅ 는 **"현재 조회 기준 일치"** 이고 정산 최종 확정을 뜻하지 않는다.
  - **매수 없음**: 09:00 회차 HOLD 이거나 BUY 가 관문에서 막힘(`blocked_reason`). 현금으로 기다린다(조치 없음).
  - **매수 1건 보유**: 체결 있는 매수 1건(전량 `FILLED` 이거나 부분 체결 뒤 취소 — 실제 체결분이 정산됐으면 정상) · 포지션 1개 `OPEN`.
  - **매수 뒤 정상 청산**: 매수 1건 + 매도 1건 · 포지션 `CLOSED` · `exit_reason` 이 `STOP_LOSS`·`TAKE_PROFIT`·`TRAILING_STOP` 중 하나.
- ❔ 확인 불가(체결 주문의 보고값·반영값 없음, 또는 `fill_accounting_pending`): G2 의 해당 주문 줄로 어느 값이 비었는지 보고, F3 를 다시 조회한다. 그대로면 §6-2 대응을 따르고 보고한다. "조회 실패" ❔(장부·개수·계좌 값을 못 읽음)도 같다 — 정상으로 보지 않는다.
- ⛔ 세션 상태가 `RUNNING` 이 아님(`EMERGENCY_STOPPED`·`STOPPED`·`PAUSED` 등): 정상 운영으로 보지 않는다. 출력 전체를 별도로 보고하고 §6-2 완료 확인을 따른다.
- 🔴 그 밖(체결 매수 2건 이상, `UNKNOWN`·미종결 주문, 판단 기록 없음, 정상 사유 밖 청산, 보고값≠반영값, 미해결 정산 불일치(G6), 설명되지 않는 잔액·거래소·포지션 수량 차이)은 §6-2 대응 순서를 따르고 출력을 그대로 보고한다.
- **입력 판정(v1.56 추가·v1.57 종료 연결 보완, N08)** — 정산 판정 줄 아래에 따로 낸다. 입력 조회·파싱 실패는 `❔ 입력 판정`으로 보고하며 기존 장부·계좌 대조 출력을 지우거나 정상으로 바꾸지 않는다. 입력 조회 종료값이 0이 아니면 부분 출력도 쓰지 않는다.
  - `RUNNING` 첫날 F3 에만 입력 검사를 적용한다. `STOPPED`·`EMERGENCY_STOPPED` 등 다른 상태에서는 입력을 조회하지 않고 `적용 제외`로 출력한다. 종료 뒤 수집 중단·첫 일봉 전 비상정지는 입력 경보 사유로 쓰지 않으며, F5-3의 정산·거래소 대조는 그대로 수행한다.
  - 🔴 현재 입력 미충족: B1 필요 완료 봉(09:15 실행이면 당일 08:00 봉) 미충족 또는 B3 20/20 아님. 일봉 판단 0도 따로 🔴로 보고한다(최신성 판정 불가면 판단 기록 없이 건너뛴다 — 코드 `CandleFreshnessGuard`·`LiveTradingService`). 이때 "매수 없음 ✅" 을 정상 HOLD 로 쓰지 않는다.
  - 시작 뒤 `데이터 부족`·`일봉 종가 누락`·`전략 요구 캔들 미달`·`BTC_MARKET_GUARD 판정 불가` 사유나 `blocked_reason` 의 `판정 불가`가 있으면 🔴로 보고하되 **과거 기록을 포함한 누적 건수**임을 표시한다. 현재 B1/B3가 충족되면 그 사실도 따로 출력하며 과거 기록만으로 현재 미회복을 확정하지 않는다.
  - ✅ 입력 충족: 위가 모두 아니고 시작 뒤 일봉 판단이 1개 이상. HOLD 면 정상 전략 HOLD, `blocked_reason`(EMA200·RANGE·BLACK_SWAN·BTC 발동 등)이 있으면 관문 차단이다. F3 는 첫날 확인이며 일상 점검으로 확대하지 않는다.
  - `일봉 마감 봉 아님`·`닫힌 캔들 미갱신` 은 판단으로 세지 않는다(08:50~09:00 회차는 07:00 봉이라 `일봉 마감 봉 아님` 이 정상).
- 어느 경우든 재시작·재요청하지 않는다.

**F3-2. 일상 운영 — 장부 읽기 (시작 뒤 매일, v1.41 리뷰 2026-11-01 3번 · v1.42 보완 — 조회 실패 시 판정 없음, 보유 중 정산 불일치 🔴)**
09:30 장부 파일이 자동으로 생겨도 비용 중단 조건(§6-2 왕복 실측 비용 > 0.40%)은 집행되지 않는다. 사람이 읽고 판정해야 한다. 아래 때마다 장부를 새로 조회해 읽는다(읽기 전용 — `BEGIN TRANSACTION READ ONLY` … `ROLLBACK`).
- **매일 10:00·22:00 KST 정기 점검 보고를 확인한 뒤.** 10:00 회차는 09:00 일봉 판단·체결 뒤 첫 확인이다.
- **청산을 확인한 뒤.** 정기 점검 보고의 `구간 주문 … 매도` 가 1 이상이거나 텔레그램 손절·청산 알림을 받으면 그때 바로 한다. 다음 09:00 재진입 판단 전에 끝낸다.
- 09:30 cron 의 날짜 파일(`~/sid203-ledger/YYYYMMDD.txt`)은 그날 원본 보존용이다. 덮어쓰지 않고, 이 읽기에 쓰지 않는다. 읽기 결과는 따로 `~/sid203-read/` 에 시각별로 남긴다(`set -C`, 같은 분에 두 번 돌리면 두 번째가 실패한다).
- v1.42(리뷰 2026-11-01 F3-2 보완 1): 조회가 실패하면(같은 분 재실행으로 `set -C` 가 막은 경우 포함) 파서를 돌리지 않고 ❔ 로 끝낸다. 있던 파일은 보존하고 읽지 않는다. ~~v1.41: 조회 종료값과 관계없이 파서가 `$F` 를 읽었다 — 같은 분 재실행이면 이전 파일로 ✅·종료 0 이 나왔다(리뷰 재현).~~
- v1.42(보완 2): 장부 조회에 이어 같은 입력으로 **M1 정산 불일치 검사**(읽기 전용)를 돌린다. 반영이 끝난 체결 주문의 보고값≠반영값(대금·수수료)과 SID 203 주문의 미해결 정산 불일치 기록을 포지션 OPEN·CLOSED 와 관계없이 센다. 장부 파일(`sid_ledger.sql`)은 바꾸지 않는다. ~~v1.41: 보유 중(`왕복 미완료`)이면 보고 10,040원·반영 10,000원이어도 잔액·수량이 반영값 기준으로 맞아 ✅ 가 나왔다(리뷰 재현).~~
```bash
R=~/sid203-read; mkdir -p "$R"; F="$R/$(TZ=Asia/Seoul date +%Y%m%d-%H%M).txt"; PRE=$([ -e "$F" ] && echo 있음 || echo 없음)
( set -C -o pipefail; cd ~/crypto_trader/AutoTrader && { cat ~/ops-scripts/sid_ledger.sql && cat <<'SQL'
BEGIN TRANSACTION READ ONLY;
\echo '== M1 정산 불일치 — 반영 끝난 체결 주문의 보고값≠반영값(대금·수수료) · SID 주문의 미해결 정산 불일치 기록 (포지션 OPEN·CLOSED 무관)'
WITH _m AS (
  SELECT '주문 ' || o.id || ' ' || o.side || ' 보고 ' || coalesce(o.reported_executed_funds::text, '미확인') || '/' || coalesce(o.reported_paid_fee::text, '미확인')
         || ' 반영 ' || coalesce(o.applied_executed_funds::text, '미확인') || '/' || coalesce(o.applied_paid_fee::text, '미확인') AS t, 1 AS a, 0 AS b
    FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :sid AND o.filled_quantity > 0 AND NOT coalesce(o.fill_accounting_pending, false)
     AND (o.reported_executed_funds <> o.applied_executed_funds OR o.reported_paid_fee <> o.applied_paid_fee)
  UNION ALL
  SELECT '주문 ' || o.id || ' 미해결 기록 ' || x.id || ' ' || x.kind, 0, 1
    FROM order_settlement_discrepancy x JOIN "order" o ON o.id = x.order_id
   WHERE o.session_kind = 'LIVE' AND o.session_id = :sid AND x.resolved_at IS NULL)
SELECT coalesce(sum(a), 0) AS rep_ne_app, coalesce(sum(b), 0) AS disc_open, coalesce(string_agg(t, '; ' ORDER BY t), '-') AS detail FROM _m;
ROLLBACK;
\echo '== 정산 검사 끝'
SQL
} | docker compose -f docker-compose.prod.yml exec -T db psql -X -A -F '|' -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -v sid=203 -v init=20200 -f - > "$F" 2>&1 ); RC=$?; echo "조회 종료 $RC"
if [ "$RC" -ne 0 ]; then
  if [ "$PRE" = 있음 ]; then echo "❔ 판정: 조회하지 않음 — 같은 분의 파일이 이미 있다(set -C). 그 파일은 보존하고 이번 판정에 쓰지 않는다. 1분 뒤 이 블록을 다시: $F"
  else echo "❔ 판정: 장부 조회 실패(종료 $RC) — 판정하지 않는다(파서 실행 안 함). 파일 전체를 보고: $F"; fi
  false                                           # ❔ 도 종료값 0 이 아니게(셸을 닫지 않도록 exit 대신)
else python3 - "$F" <<'PY'
import sys
from decimal import Decimal as D
p = sys.argv[1]
txt = open(p, encoding="utf-8", errors="replace").read()
sec, R, hdr = None, {}, None
for line in txt.splitlines():                     # psql -A -F '|' — 구역마다 첫 줄이 열 이름
    if line[:4] in ("== L", "== M") and line[4:5].isdigit():
        sec, hdr = line[3:5], None
        R[sec] = []
    elif line.startswith("== "):                  # 끝 표시 — 구역을 닫는다
        sec = None
    elif sec and "|" in line:
        if hdr is None:
            hdr = line.split("|")
        else:
            R[sec].append(dict(zip(hdr, line.split("|"))))
if "\n== 끝\n" not in txt or not txt.rstrip().endswith("== 정산 검사 끝") or any(k not in R for k in ("L0", "L3", "L4", "M1")) \
        or len(R["L0"]) != 1 or len(R["L4"]) != 1 or len(R["M1"]) != 1:
    print("❔ 판정: 장부 조회가 끝까지 돌지 않음(끝 표시·L0·L3·L4·M1 중 빠짐) — 정상 판정 아님. 파일 전체를 보고:", p)
    sys.exit(1)
l0, l4, m1 = R["L0"][0], R["L4"][0], R["M1"][0]
print("== 장부 읽기", p, "· 조회", l0["queried_kst"], "· 세션", l0["status"], "· init_ok", l0["init_ok"])
over, unmeas = [], []
for r in R["L3"]:
    print("L3 포지션 %s %s %s · %s · 비용 %s%% · > 0.40 %s" % (r["pos"], r["status"], r["exit_reason"], r["rt_status"], r["cost_pct"], r["cost_gt_040"]))
    if r["cost_gt_040"] == "t":
        over.append(r["pos"])
    elif r["rt_status"].startswith("측정 불가"):
        unmeas.append("포지션 %s %s" % (r["pos"], r["rt_status"]))
print("L4 세션 잔액 %s · 잔액 기대 %s · balance_diff %s · 확인 불가 주문 %s · 순체결량 %s · 열린 포지션 수량 %s"
      % (l4["available_krw"], l4["balance_expected"], l4["balance_diff"], l4["unk_orders"], l4["net_btc"], l4["open_size"]))
try:
    diff = D(l4["balance_diff"])
except Exception:
    diff = None
qty_ok = D(l4["net_btc"]) == D(l4["open_size"])
mis = int(m1["rep_ne_app"]) + int(m1["disc_open"])
print("M1 보고≠반영(반영 끝난 주문) %s · 미해결 정산 불일치 기록 %s · %s" % (m1["rep_ne_app"], m1["disc_open"], m1["detail"]))
if over:
    v = "🔴 판정: §6-2 중단 조건 — 왕복 실측 비용 > 0.40%(포지션 " + ", ".join(over) + "). 전량 정지 대상(F6). 이 출력 전체를 바로 보고"
elif l0["status"] != "RUNNING":
    v = "⛔ 판정: 세션 상태 %s — 정상 운영 아님. 이 출력 전체를 별도 보고(§6-2 완료 확인). 재시작·재요청 없음" % l0["status"]
elif mis:
    v = ("🔴 판정: 정산 — 보고≠반영 %s건 · 미해결 정산 불일치 기록 %s건(포지션 OPEN·CLOSED 무관). §6-2 정산 불일치 대응(조회부터)·보고"
         % (m1["rep_ne_app"], m1["disc_open"]))
elif l4["unk_orders"] != "0" or diff is None:
    v = "❔ 판정: 확인 불가 — 체결 주문의 보고값·반영값 없음 또는 회계 반영 대기(%s건). 몇 분 뒤 이 읽기만 다시, 그대로면 §6-2 정산 대응·보고" % l4["unk_orders"]
elif diff != 0 or not qty_ok or l0["init_ok"] != "t":
    v = ("🔴 판정: 정산 — balance_diff %s · 순체결량 %s / 열린 포지션 수량 %s · init_ok %s 중 기대와 다름. §6-2 정산 불일치 대응(조회부터)·보고"
         % (l4["balance_diff"], l4["net_btc"], l4["open_size"], l0["init_ok"]))
elif unmeas:
    v = "❔ 판정: 측정 불가 — " + " · ".join(unmeas) + ". 0.40% 대조를 하지 않는다(숫자를 채우지 않는다). 사유 그대로 보고"
else:
    v = ("✅ 판정: 현재 조회 기준 일치 — 완료 왕복 %d건 모두 측정·0.40%% 이하 · 왕복 미완료 %d건 · balance_diff 0 · 정산 불일치 0"
         % (sum(1 for r in R["L3"] if r["rt_status"] == "측정"), sum(1 for r in R["L3"] if r["rt_status"] == "왕복 미완료")))
print(v)
sys.exit(0 if v.startswith("✅") else 1)
PY
fi
```
- 판정은 마지막 줄 하나다. 여러 개에 해당하면 위 순서(중단 조건 → 세션 상태 → **정산 불일치(M1)** → 확인 불가 → 정산(잔액·수량·init) → 측정 불가)에서 앞의 것이 나온다. 아래 줄들(L3 포지션별·L4·M1)은 그대로 보고에 붙인다. v1.42: M1 을 확인 불가 앞에 둔다 — 반영이 끝난 주문의 보고≠반영과 미해결 기록은 기다려서 풀리지 않으므로, 다른 주문의 반영 대기에 가려지지 않게 한다.
- ✅ **현재 조회 기준 일치**: 보고만 하고 조치는 없다. 왕복이 없으면(시작 첫날 매수 없음) `완료 왕복 0건`. `왕복 미완료` 는 보유 중이라 비용을 아직 재지 않는다는 뜻이다. v1.42: 정산 불일치(M1) 0 도 ✅ 의 조건이다.
- 🔴 **중단 조건(왕복 실측 비용 > 0.40%)**: §6-2 "하나라도 걸리면 전량 정지"에 해당한다. 이 출력 전체를 바로 보고하고 **F6 중도 정지**로 간다(사유 = 이 판정 줄, 근거 = 이 읽기 파일 `$F`). 비교 값은 L3 `cost_pct`(사전 등록 정의, 반올림 전 값으로 판정 — `0.40000000` 은 해당 아님)뿐이다. 실제 수수료(`fees`·`fee_pct_actual`)로 다시 계산하지 않는다.
- ❔ **측정 불가(사유)**: 0.40% 대조를 하지 않는다. 빠진 값을 0 이나 다른 열로 채워 숫자를 만들지 않는다. 사유 문구(진입·청산 기준가 없음, 정산 확인 불가, 정산 불일치, 매수/매도 수량 불일치) 그대로 보고한다.
- 🔴 **정산(`balance_diff` ≠ 0 · 순체결량 ≠ 열린 포지션 수량 · `init_ok` 거짓)**: §6-2 정산 불일치 대응 순서(1. 업비트 미체결·보유량과 내부 기록 대조 — 조회만)를 따르고 보고한다. 허용 오차는 없다(v1.33). 해결되지 않으면 §6-2 2번대로 다음 일봉 마감을 기다리지 않고 **F6 중도 정지**로 간다.
- 🔴 **정산 — 보고≠반영·미해결 정산 불일치 기록(M1, v1.42)**: 위 🔴 정산과 같은 대응이다. 포지션이 열려 있어도(`왕복 미완료`) 해당한다. M1 줄의 `detail`(주문 번호·보고값/반영값·기록 번호·종류)을 그대로 보고한다. 보고값·반영값이 없거나 회계 반영 대기인 주문은 M1 에 넣지 않고 ❔ 확인 불가로 남는다. v1.44(리뷰 2026-11-01): **누락만 있으면 ❔, 비교 가능한 값에 확정 불일치가 있으면 🔴** 다. 한 주문에서 값 하나가 없어도 비교 가능한 다른 값이 다르면(예: 보고 대금 없음 · 보고 수수료 5 · 반영 수수료 4) M1 에 넣어 🔴 를 유지한다. 상세의 빈 값은 문자 `미확인` 으로 적는다(숫자 0 으로 대체하지 않는다). ~~v1.42: 상세 문자열을 값 그대로 이어 붙여, 값 하나가 없으면 그 주문 줄이 상세에서 빠졌다.~~
- ❔ **확인 불가**: 몇 분 뒤 이 블록만 다시 돌린다(같은 분이면 `set -C` 로 실패하니 1분 뒤). 그대로면 §6-2 대응·보고. ⛔ 는 F3 의 ⛔ 와 같다.
- `조회 종료` 가 0 이 아니면 파서를 돌리지 않고 `❔ 판정: 조회하지 않음`(같은 분 파일이 이미 있음 — 1분 뒤 다시) 또는 `❔ 판정: 장부 조회 실패`(파일 전체를 보낸다)로 끝난다(v1.42). `❔ 판정: 장부 조회가 끝까지 돌지 않음` 도 정상으로 보지 않는다. 파일 전체를 보낸다.

**F4. 종료 시각 고정** — 종료 = F2 확인의 `end_kst`(실제 `started_at` + 56일, 예정대로면 2027-01-26 08:50 KST 무렵). 운영자가 시작 당일 종료 점검 일정을 등록한다. 종료 때 정지 API(`POST …/sessions/$SID/stop`) 한 번 → §6-2 의 완료 확인(STOPPED · 활성 주문 0 · 정리 대기 0 · 업비트 BTC 와 기록 일치) → §6-3 보고. 증액·추가 입금·자동 연장은 없다. 실행 명령은 아래 F5(v1.28). (v1.29 정정, 리뷰 2026-10-31 보완 4: 그 전에 비상정지됐으면 상태는 `EMERGENCY_STOPPED` 그대로 두고 같은 완료 확인을 한다 — 완료 상태를 `STOPPED` 하나로 요구하지 않는다.)

**F5. 56일 종료 실행 (v1.28, 리뷰 2026-10-31 3번)** — 실제 종료 시각 확인 → 정지 요청 1회 → 청산·정산 완료 확인 → 최종 비용·순손익 보고. 격리 리허설 4차 ⑥(정지 1회 → 청산 매도 → 정리 완료 → F3 대조 ⛔ STOPPED·차이 0)과 같은 순서·같은 F3 블록을 쓴다. 새 셸이면 위 **F 변수**를 먼저 넣고, 이어서:
```bash
E=~/end-sid203; mkdir -p "$E"; cp -n "$M/account-f1.json" "$E/"     # 시작 전 기준(F1)을 종료 폴더로 — 첫날 F3 기록($M)은 덮어쓰지 않는다
ls ~/ops-scripts/sid_ledger.sql                                     # §5-9 6번 설치본(없으면 F5-4 전에 설치)
```
**F5-1. 실제 종료 시각 확인 — 종료 예정일 08:30 KST 무렵 (읽기 전용)**
```bash
$PSQL -x -c "SELECT id, status, started_at AT TIME ZONE 'Asia/Seoul' AS started_kst, (started_at + interval '56 days') AT TIME ZONE 'Asia/Seoul' AS end_kst,
  now() AT TIME ZONE 'Asia/Seoul' AS now_kst, now() >= started_at + interval '56 days' AS due FROM live_trading_session WHERE id = $SID;" | tee "$E/end-check.txt"
grep end_kst "$M/start-check.txt" "$E/end-check.txt"                # 두 줄의 값이 같아야 한다(F2 확인 기록 = 지금 계산)
```
- 기대: `status` `RUNNING` · `end_kst` = F2 확인 기록의 `end_kst`(실제 `started_at` + 56일) · `due` 는 그 시각 전 `f`, 뒤 `t`.
- `end_kst` 가 F2 기록과 다르면 멈추고 두 출력을 보고한다(`started_at` 이 바뀌었다는 뜻 — 정지하지 않는다).
- `status` 가 이미 `STOPPED`·`EMERGENCY_STOPPED`(§6-2 중단 발동 등)면 F5-2 를 건너뛰고 F5-3·F5-4 만 한다. 🔴 그 상태를 **그대로 보존**한다(v1.29) — `EMERGENCY_STOPPED` 를 `STOPPED` 로 바꾸려고 정지·시작·재설정 요청을 하지 않는다(정지 API 는 `RUNNING` 만 받는다). 무엇이 멈췄는지는 F5-4 L5 로 출처별로 적는다.

**F5-2. 정지 요청 1회 — `end_kst` 이후, 다음 09:00 KST 일봉 판단 전 (확인 파일 뒤)**
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ ! -e "$E/stop-sent" ] || stop "이미 정지 요청을 보냈다 — 다시 보내지 않는다. F5-3 확인부터"
[ "$($PSQL -At -c "SELECT now() >= started_at + interval '56 days' FROM live_trading_session WHERE id = $SID")" = "t" ] || stop "아직 실제 시작 + 56일 전 — 정지하지 않는다"
[ "$($PSQL -At -c "SELECT status FROM live_trading_session WHERE id = $SID")" = "RUNNING" ] || stop "RUNNING 이 아님 — 정지 요청 없이 F5-3 확인·보고"
TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/account/summary > "$E/account-before-stop.json"
date '+%F %T %Z' | tee "$E/t-stop.txt"; touch "$E/stop-sent"
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/trading/sessions/$SID/stop | tee "$E/stop.json"; echo
)
```
- 🔴 **한 번만.** `stop-sent` 표지가 두 번째 요청을 막는다. 응답이 없거나 애매해도 다시 부르지 말고 F5-3 조회부터 한다. 거절 문구가 오면 그대로 보고한다.
- 정지는 ⓐ 미종결 매수 취소 요청 ⓑ 장부 수량이 있는 OPEN 포지션 시장가 매도 ⓒ 세션 STOPPED 를 함께 한다(§6-2 실제 기능). 🔴 업비트에서 직접 매도하지 않는다.
- 09:00 회차 전에 못 보냈으면(늦음) 알게 된 즉시 같은 블록 1회 — 늦은 시각과 사유를 기록한다. 그 사이의 매매도 정지가 청산한다.
- 56일 전 §6-2 정지는 이 블록이 아니라 아래 **F6** 로 한다(이 블록은 56일 전이면 멈춘다). 같은 정지 표지(`$E/stop-sent`)를 써서 F6 뒤에는 이 블록이 다시 보내지 않는다.

**F5-3. 청산·정산 완료 확인 — 정지 1~3분 뒤 (읽기 전용, 기대와 다르면 몇 분 뒤 이 조회만 다시)**
```bash
SHA=<리뷰가 확인한 커밋 SHA>                                       # F3 블록을 이 커밋의 문서에서 그대로 꺼낸다(손으로 옮기지 않는다)
git fetch origin && git show "$SHA":docs/LIMITED_LIVE_TEST_PLAN.md | awk '/^\*\*F3\. /{f=1; next} f && /^```bash$/{g=1; next} g && /^```$/{exit} g' > "$E/f3-block.sh"
head -1 "$E/f3-block.sh"; tail -1 "$E/f3-block.sh"; sha256sum "$E/f3-block.sh"   # 첫 줄 S=$($PSQL …, 끝 줄 PY
( M=$E; . "$E/f3-block.sh" )                                        # 결과는 $E/f3.txt·$E/account-f3.json — 첫날 $M/f3.txt 는 그대로
$PSQL -At -F ' | ' -c "SELECT *, CASE WHEN status IN ('STOPPED','EMERGENCY_STOPPED') AND active + open_pos + cleanup + unresolved = 0
    THEN 'DB 정리 조건 충족 — 정산·거래소 대조 별도 확인 필요' ELSE '미완료' END AS verdict FROM (
  SELECT status, available_krw, stopped_at AT TIME ZONE 'Asia/Seoul' AS stopped_kst,
  (SELECT count(*) FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = $SID AND state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')) AS active,
  (SELECT count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status IN ('OPEN','CLOSING')) AS open_pos,
  (SELECT count(*) FROM position_cleanup WHERE done_at IS NULL) AS cleanup,
  (SELECT count(*) FROM order_settlement_discrepancy WHERE resolved_at IS NULL) AS unresolved
  FROM live_trading_session WHERE id = $SID) x" | tee "$E/stop-check.txt"
```
- 🔴 최종 상태는 **`STOPPED` 또는 `EMERGENCY_STOPPED`** 다(v1.29, 리뷰 2026-10-31 보완 4). F5-2 로 정지했으면 `STOPPED`, 그 전에 비상정지됐으면 `EMERGENCY_STOPPED` **그대로** — 어느 쪽이든 아래 완료 확인은 같다. F5-2 를 보냈는데 `EMERGENCY_STOPPED` 면(정지 직전 다른 중단 발동) 그대로 두고 F5-4 L5 로 사유를 적는다.
- 기대(리허설 4차 ⑥ 과 같은 모양): F3 정산 상태 판정은 `⛔ 판정: 세션 상태 <최종 상태>`, 입력 판정은 `적용 제외`다(v1.57). 종료에서는 이 조합이 기대이며, 마지막 줄이 ⛔여야 한다고 검사하지 않는다. 대조 줄은 `거래소 BTC 증감 0 · 주문 순체결량 0 · 열린 포지션 수량 0`(시작 전보다 BTC 가 늘거나 줄지 않음) · `거래소 KRW 증감 … 세션 증감 … 차이` ±1원 이내 · 미종결 0 · 회계 반영 대기 0 · 반영값·보고값 없음 0 · 보고≠반영 0 · 정리 대기 0 · 미해결 정산 불일치 0. `stop-check.txt` = `<최종 상태> | <잔액> | <정지 시각> | 0 | 0 | 0 | 0 | DB 정리 조건 충족 — 정산·거래소 대조 별도 확인 필요`(v1.29 — 종전 기대 `STOPPED | … | 0 | 0 | 0 | 0` 은 비상정지 종료를 미완료로 봤다. ~~v1.29 끝 문구 `완료 조건 충족 — 상태 <최종 상태> 그대로`~~ → v1.30 정정(리뷰 2026-10-31 장부 보완 2): 이 조회는 상태·활성 주문·열린 포지션·정리 대기·미해결 불일치만 보고 정산 반영 대기·거래소 잔액 차이는 보지 않는다 — 확인 범위대로 좁혔다. 최종 상태는 첫 칸 그대로).
- 🔴 끝 칸 `DB 정리 조건 충족` 은 **완료가 아니다**. 회계 반영 대기 0·반영값·보고값 없음 0·보고≠반영 0·거래소 BTC·KRW 대조는 위 F3 블록 출력으로 따로 확인한다.
- 🔴 **`STOPPED`·`EMERGENCY_STOPPED` 만으로 청산·정산 완료라고 적지 않는다**(§6-2 5번). 위가 모두 맞을 때만 "청산·정산 완료 확인"으로 기록한다.
- 미종결·열린 포지션·정리 대기가 남으면 몇 분 뒤 이 조회만 다시. 그대로면 §6-2 대응(불명 주문은 식별자로 업비트 조회, 직접 매도는 활성 주문 0·정리 대기 0 확인 뒤 남은 차이에만) 후 보고한다.

<a id="f5-3v"></a>**F5-3v. 청산·정산 완료 확인 — 새 출처(v1.159, 398차): 분리 이후 커밋에서는 F5-3 대신 이 블록**
분리(2026-11-12) 이후 커밋의 현재 계획에는 F3가 없어, 위 F5-3의 추출 줄은 0바이트를 만들고도 종료 0을 낸다. 이 블록은 같은 커밋의 **RUNBOOK**에서 F3를 꺼내고, 꺼내기 실패·빈 결과·기대 해시 불일치면 F3와 완료 조회를 **실행하지 않고** 멈춘다. F3 추출식·F3 실행 줄·완료 조회문은 위 F5-3과 같은 글자다. 위 F5-3 블록과 과거 해시는 바꾸지 않았다(분리 전 고정 커밋에는 옛 계획에 F3가 남아 있다). 기대 해시는 분리 전후 같은 F3 본문의 값이며, 최종 S 고정 때 리뷰가 그 커밋에서 다시 확인한다.
```bash
SHA=<리뷰가 확인한 커밋 SHA>                                       # 분리 이후 커밋 — F3 는 이 커밋의 RUNBOOK 에서 꺼낸다
F3H=bb86fb397da00523f564a8f02a1960c98bb1ac03887629fe74372990929fa2bb  # 기대 F3 SHA256(분리 전후 같은 본문)
rm -f "$E/f3-block.sh" "$E/f3-src-ok"
( set -uo pipefail
  stop() { echo "🔴 멈춤: $*"; exit 1; }
  git fetch origin || stop "git fetch 실패 — F3·완료 조회 미실행"
  git show "$SHA":docs/LIMITED_LIVE_TEST_RUNBOOK.md > "$E/f3-src-runbook.md" || stop "$SHA 에서 RUNBOOK 을 읽지 못함 — F3·완료 조회 미실행"
  awk '/^\*\*F3\. /{f=1; next} f && /^```bash$/{g=1; next} g && /^```$/{exit} g' "$E/f3-src-runbook.md" > "$E/f3-block.sh" || stop "F3 추출 실패 — F3·완료 조회 미실행"
  [ -s "$E/f3-block.sh" ] || stop "F3 추출 결과 0바이트 — F3·완료 조회 미실행"
  h=$(sha256sum "$E/f3-block.sh" | cut -d' ' -f1); echo "F3 추출 $(wc -c < "$E/f3-block.sh")바이트 sha256 $h"
  [ "$h" = "$F3H" ] || stop "F3 해시 불일치(기대 $F3H) — F3·완료 조회 미실행"
  head -1 "$E/f3-block.sh"; tail -1 "$E/f3-block.sh"                # 첫 줄 S=$($PSQL …, 끝 줄 PY
  touch "$E/f3-src-ok" )
if [ -e "$E/f3-src-ok" ]; then
( M=$E; . "$E/f3-block.sh" )                                        # 결과는 $E/f3.txt·$E/account-f3.json — 첫날 $M/f3.txt 는 그대로
$PSQL -At -F ' | ' -c "SELECT *, CASE WHEN status IN ('STOPPED','EMERGENCY_STOPPED') AND active + open_pos + cleanup + unresolved = 0
    THEN 'DB 정리 조건 충족 — 정산·거래소 대조 별도 확인 필요' ELSE '미완료' END AS verdict FROM (
  SELECT status, available_krw, stopped_at AT TIME ZONE 'Asia/Seoul' AS stopped_kst,
  (SELECT count(*) FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = $SID AND state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')) AS active,
  (SELECT count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status IN ('OPEN','CLOSING')) AS open_pos,
  (SELECT count(*) FROM position_cleanup WHERE done_at IS NULL) AS cleanup,
  (SELECT count(*) FROM order_settlement_discrepancy WHERE resolved_at IS NULL) AS unresolved
  FROM live_trading_session WHERE id = $SID) x" | tee "$E/stop-check.txt"
else echo "🔴 F3 출처 확인 실패 — 위 멈춤 사유를 그대로 보고한다(F3 를 손으로 옮기지 않는다)"; fi
```
- 기대·판정·다시 조회 조건은 위 F5-3 과 같다. `F3 추출 … sha256 bb86fb39…` 줄이 먼저 나와야 하며, 🔴 멈춤 줄이 나오면 F3 결과·`stop-check.txt` 가 없는 것이 정상이다 — 다른 커밋·파일에서 F3 를 꺼내 대신하지 않는다.
- 추출 원문 `$E/f3-src-runbook.md`·`$E/f3-block.sh` 를 종료 근거와 함께 보존한다.

**F5-4. 최종 비용·순손익 보고 — F5-3 완료 확인 뒤 (읽기 전용)**
```bash
$PSQL -v sid=$SID -v init=20200 -f - < ~/ops-scripts/sid_ledger.sql | tee "$E/ledger-final.txt"
sha256sum ~/ops-scripts/sid_ledger.sql | tee -a "$E/ledger-final.txt"
```
- ~~종전(v1.28): 둘째 줄에 `circuit_breaker_*` 조회를 두고 ④ 를 "발동 없으면 '실제 발동 없음'" 으로 적었다.~~ → v1.29 정정(리뷰 2026-10-31 보완 4): 서킷브레이커 칸만으로 모든 중단 기능이 발동하지 않았다고 쓸 수 없다. 중단 기록은 장부 L5 에 출처별로 있다.
- §6-3 보고 다섯 가지를 이 출력에서 옮긴다:
  - ① 날짜별 신호·주문: L1 신호·관문 차단 사유·주문·체결, L2.
  - ② 체결 비용: L3 `rt_status`·`cost_pct`(사전 등록 정의 = 불리 진입 + 불리 청산 + 0.10)·`cost_gt_040`(§6-2 > 0.40%). `측정 불가(…)`·`왕복 미완료` 는 그 문구 그대로 적고 0.40% 대조를 하지 않는다. 실제 수수료는 `fees`·`fee_pct_actual`, 주문별은 L2 로 따로 적는다.
  - ③ 정산: ~~L4 장부 현금 = 세션 잔액~~ → v1.34(리뷰 2026-10-31): L4 세션 잔액(`available_krw`) = 잔액 기대(`balance_expected`), `balance_diff` 0. **원시 장부 현금(`ledger_cash`, 8자리)과 저장 잔액은 다른 값으로 둘 다 적는다** — 예: 원시 21,789.201원 · 저장 잔액 21,789.21원(`raw_diff` 0.009). "원시 = 저장"으로 쓰지 않는다. F5-3 거래소 대조.
  - ④ 중단 기능: L5 를 **출처별로** 옮긴다 — 서킷브레이커(횟수·사유·시각), 포지션 한도 도달(자동 정지 여부), 공유 풀 손실 한도(진입 차단 — 정지 아님), 세션 최종 상태와 그 설명. 기록 0 인 출처만 "그 기능의 발동 기록 없음"으로 적는다. 거래소 장애 일괄 비상정지·수동 비상정지는 DB 에 사유 칸이 없다 — L5 가 "서킷브레이커 기록 없음" 비상정지면 앱 로그(`비상 정지`)·텔레그램에서 사유를 찾아 적는다. 사람 판단 정지(§6-2 의 세 조건·왕복 비용 > 0.40%)는 운영자 기록으로 적는다. 아무 중단도 없었을 때만 §6-3 대로 "실제 발동 없음".
  - ⑤ 손익: ~~L1 마지막 날 `net_pnl` = 최종 잔액 − 20,200, BTC 0 일 때 L4 와 같다~~ → v1.34: L1 마지막 날 `net_pnl` 은 **원시 현금 기준**(`ledger_cash` − 20,200, BTC 0 일 때)이고, 잔액 기준 손익은 `available_krw` − 20,200 이다. 둘을 따로 적고 차이(`raw_diff`)를 함께 적는다 — `realized_pnl` 합계는 참고.
- `L0 init_ok` 가 `t` 가 아니거나, L1 에 "확인 불가" 가 남거나, ~~L4 `ledger_cash` ≠ `available_krw`~~ → **v1.33: L4 `balance_diff` ≠ 0(잔액 기대 `balance_expected` ≠ `available_krw`)** 이면 최종 손익을 확정하지 않고 출력 전체를 보고한다. `raw_diff`(원시 장부 현금 − 잔액)는 반올림이라 참고로 적는다.
- 정기 점검의 `SID_OK_STATUS` 를 최종 상태(`STOPPED` 또는 `EMERGENCY_STOPPED`)로 바꾸는 것은 이 보고에 대한 리뷰 판단 뒤 — `OPS_DAILY_CHECK.md` §3-1 블록의 첫 줄만 `FROM=RUNNING; TO=<최종 상태>`. 증액·추가 입금·자동 연장·재시작은 없다.

**F6. 중도 정지 — 56일 전 §6-2 정지 (v1.42, 리뷰 2026-11-01 중도 정지 절차)**
언제: F3-2 가 🔴 §6-2 중단 조건을 내거나, §6-2 의 다른 중단 조건(손실 한도·구현 불일치·해결되지 않은 정산 불일치·청산이 필요한 주문 상태 불명)을 운영자가 확인했을 때. **56일 대기 조건을 적용하지 않는다.** 그 밖은 F5 와 같다 — 같은 폴더 `$E`, 같은 정지 표지 `$E/stop-sent`, 정지 요청 1회, 정지 뒤 F5-3(청산·정산·거래소 대조) → F5-4(최종 보고). 새 셸이면 위 **F 변수**를 먼저 넣는다. 아래 두 줄의 `<…>` 를 채운 뒤 블록 전체를 붙인다.
```bash
E=~/end-sid203; mkdir -p "$E"; cp -n "$M/account-f1.json" "$E/"     # F5 와 같은 종료 폴더·시작 전 기준
REASON='<정지 사유 한 줄 — 예: §6-2 왕복 실측 비용 > 0.40%(포지션 2, cost_pct 0.41230000)>'
BASIS='<판정 근거 파일 — 예: /home/<사용자>/sid203-read/20261215-1003.txt>'
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ ! -e "$E/stop-sent" ] || stop "이미 정지 요청을 보냈다 — 다시 보내지 않는다. F5-3 확인부터"
[ "$SID" = 203 ] || stop "SID 가 203 이 아님($SID) — 정지 요청 없음"
case "$REASON$BASIS" in *'<'*) stop "사유·근거의 <…> 를 채우지 않았다 — 정지 요청 없음" ;; esac
[ -n "$REASON" ] || stop "사유가 비었다 — 정지 요청 없음"
[ -s "$BASIS" ] || stop "판정 근거 파일이 없다($BASIS) — 정지 요청 없음"
ST=$($PSQL -At -c "SELECT status FROM live_trading_session WHERE id = $SID AND session_type = 'REAL' AND coin_pair = 'KRW-BTC'")
{ echo "== 중도 정지 판단 $(TZ=Asia/Seoul date '+%F %T KST') · 56일 대기 조건 적용 안 함"; echo "사유: $REASON"; echo "근거: $BASIS"
  sha256sum "$BASIS"; echo "조회 상태: SID $SID ${ST:-없음}"; } | tee -a "$E/midstop-reason.txt"
case "$ST" in
  RUNNING) ;;
  STOPPED|EMERGENCY_STOPPED) echo "⛔ 이미 $ST — F6 정지 요청 없음(상태 보존). 실제 중단 출처는 F5-4 L5 로 적는다. F5-3 완료 확인으로" | tee -a "$E/midstop-reason.txt"; exit 0 ;;
  *) stop "SID $SID 상태 '${ST:-없음}' — RUNNING 이 아님. 정지 요청 없이 이 출력 보고" ;;
esac
TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/account/summary > "$E/account-before-stop.json"
date '+%F %T %Z' | tee "$E/t-stop.txt"; touch "$E/stop-sent"
echo "F6 정지 요청 보냄 $(cat "$E/t-stop.txt") — 응답이 정지 수락이면 F5-4 ④ 사람 판단 정지(§6-2)" | tee -a "$E/midstop-reason.txt"
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/trading/sessions/$SID/stop | tee "$E/stop.json"; echo
)
```
- 기대(`RUNNING` 일 때): `== 중도 정지 판단 …` · `사유:` · `근거:` · 근거 파일 sha256 · `조회 상태: SID 203 RUNNING` 이 화면과 `$E/midstop-reason.txt` 에 남고(덧붙여 쓴다 — 지우지 않는다), 정지 시각(`t-stop.txt`)과 정지 응답(`stop.json`)이 나온다.
- 🔴 **한 번만.** `stop-sent` 표지는 F5-2 와 같은 파일이다 — F6 를 다시 붙여도, 56일 종료 때 F5-2 를 붙여도 다시 보내지 않는다. 응답이 없거나 애매하면 다시 부르지 말고 아래 상태 조회부터 한다. 거절 문구가 오면 그대로 보고한다.
- 이미 `STOPPED`·`EMERGENCY_STOPPED` 면 정지 요청 없이 `⛔ 이미 … — F6 정지 요청 없음` 으로 끝난다. 그 상태를 그대로 보존한다(F5-1 과 같다 — `EMERGENCY_STOPPED` 를 `STOPPED` 로 바꾸려고 정지·시작·재설정을 하지 않는다). 이어서 F5-3 으로 간다.
- `🔴 멈춤` 이면 정지 요청은 나가지 않았다. 화면 출력을 그대로 보고한다. `CREATED` 등 다른 상태면 고치지 않고 보고만 한다.
- 정지가 하는 일은 F5-2 와 같다(ⓐ 미종결 매수 취소 요청 ⓑ 장부 수량이 있는 OPEN 포지션 시장가 매도 ⓒ 세션 STOPPED). 🔴 업비트에서 직접 매도하지 않는다.
- **정지 뒤**: 1~3분 뒤 상태부터 조회 → **F5-3**(F3 블록의 거래소 BTC·KRW 대조 + `stop-check.txt`) → **F5-4**(최종 비용·순손익). F5-1·F5-2 는 하지 않는다. F5-4 ④ 중단 기능 기록(v1.44, 리뷰 2026-11-01):
  - `midstop-reason.txt` 에 `F6 정지 요청 보냄` 줄이 있고 정지가 받아들여졌으면(`stop.json`·상태 `STOPPED`) "사람 판단 정지(§6-2)"로 적는다. 사유·시각·근거는 `midstop-reason.txt`·`t-stop.txt` 에서 옮긴다.
  - `⛔ 이미 … — F6 정지 요청 없음` 이면 "사람 판단 정지"로 적지 않는다. **실제 중단 출처와 시각**(F5-4 L5 — 서킷브레이커·포지션 한도 자동 정지·비상정지와 그 시각, 정지 시각은 위 상태 조회의 `stopped_at`, 사유가 DB 에 없으면 앱 로그·텔레그램)과 **"F6 정지 요청 없음 — 이미 <상태>"** 를 함께 적는다. F6 의 사유·근거는 그때 운영자가 본 판단으로만 남긴다.
  - 요청을 보냈는데 거절됐거나(예: 직전에 비상정지) 응답이 불명확하면, 상태 조회 결과와 L5 로 실제 출처를 확인해 그대로 적는다. 요청을 보냈다는 사실만으로 사람 판단 정지라고 적지 않는다.
  - ~~v1.42: 정지 뒤 늘 "사람 판단 정지(§6-2)"로 적었다 — 이미 정지돼 F6 요청이 없던 경우도 포함됐다.~~ 증액·재시작·재개는 없다(§6-2 "한도를 늘려 재개하지 않는다").
```bash
$PSQL -At -F ' | ' -c "SELECT status, stopped_at AT TIME ZONE 'Asia/Seoul' FROM live_trading_session WHERE id = $SID" | tee -a "$E/midstop-reason.txt"   # STOPPED 또는 EMERGENCY_STOPPED | 정지 시각
```
- 정기 점검 `SID_OK_STATUS` 를 최종 상태로 바꾸는 것은 F5-4 와 같이 이 보고에 대한 리뷰 판단 뒤다.

## 6. 중단·종료 조건

기존 대응 순서와 한도를 유지한다. 시작 전 확인과 날짜·상태·수신 조건은 위 F0~F3을 따른다.

### 6-2. 중단 — 하나라도 걸리면 전량 정지

- `LIVE_ENTRY_PREREG.md` §6 중단 조건 전부를 그대로 적용한다.
  - 누적 −15,000원
  - 24시간 −3,000원
  - `CAPITAL_LOSS` −15%
  - `MAX_DRAWDOWN` −20%
  - `CB_REPEAT` ≥ 3
  - 왕복 실측 비용 > 0.40%
- 시험 목적에 맞춰 세 가지를 더한다(리뷰 채택). 확인 결과를 보고 판단하는 사람 판단 정지다. 새 자동 장치나 새 도구를 만들지 않는다. **대응 순서를 구분한다(리뷰 지시)**:
  - **구현 불일치** — 같은 캔들로 계산기를 다시 돌린 신호와 실제 주문이 다르다.
    - 판단 전에 **결합 규칙(§2-3)으로 설명되는 차이를 먼저 대조한다**: 정상적인 관문 차단(EMA200·RANGE·BLACK_SWAN·BTC 급락·회차 승인 시점 재확인), 조기 청산(손절·익절·추적·시간 청산), 전략 SELL 차단, 부분 체결, 수량 반올림.
    - 이것들로 설명되지 않는 차이만 구현 불일치로 보고 정지한다.
  - **정산 불일치** — 업비트 잔액·보유 수량과 내부 기록이 수수료·반올림 범위를 넘어 다르다.
  - **주문 상태 불명** — 주문 상태를 확인할 수 없는 주문이 생긴다(`UNKNOWN_ORDER_STATE_DESIGN.md`).
  - ~~🔴 정산 불일치·주문 상태 불명은 이 순서로 대응한다: ① 신규 주문을 먼저 막는다(세션 정지) → ② 미체결 주문과 업비트 실제 보유량을 확인한다 → ③ 그 뒤에 청산한다.~~ → **정정 (리뷰, 2026-10-30)**: 세션 정지(`stopSession`)는 "청산 없는 차단"이 아니다. 이 순서는 실제 기능과 맞지 않았다. 아래로 바꾼다.
  - **실제 기능** (코드·시험으로 확인, §5-7)
    - **청산 없이 신규 주문만 막는 기능은 없다.** 정지는 ⓐ 미종결 매수에 취소 요청(응답을 잃은 주문은 식별자로) ⓑ 장부 수량이 있는 OPEN 포지션 시장가 매도 ⓒ 세션 STOPPED 를 **함께** 한다. 진행 중 매도는 취소하지 않는다.
    - **불명 주문이 있으면 중복 주문은 자동으로 막힌다.** 같은 세션의 신규 매수는 주문 엔진이 거절한다(미해결 제출 > 0). 매도가 불명이면 포지션이 CLOSING 이라 손절·전략 SELL·정지가 다시 팔지 않는다. 불명 주문은 5초 주기로 **식별자 조회만** 하고 다시 내지 않는다.
    - **불명 주문이 남은 채 정지해도 그 주문을 다시 내지 않는다.** 매수 불명: 식별자 취소 요청만, 수량 0 포지션은 매도하지 않고 정리 대기로 열어 두며 자금을 복원하지 않는다. 매도 불명: 새 매도도 취소도 하지 않는다.
  - **대응 순서 (주문 상태 불명)**
    1. 자동 차단을 확인한다(조회만): `GET /api/v1/trading/sessions/{id}/orders` 에서 `UNKNOWN` 주문, 그 뒤 새 매수가 없음.
    2. 불명 주문을 업비트에서 **UUID(있으면) 또는 identifier(`clientOrderId`)로 조회**한다(업비트 주문 조회 API `GET /v1/order` 의 `uuid`·`identifier`, 또는 웹·앱 주문 내역). 그 시각의 체결 내역과 BTC 보유량도 확인한다.
    3. 청산이 필요하면 **세션 정지 API 하나로만** 청산한다(`POST /api/v1/trading/sessions/{id}/stop`). 🔴 **업비트에서 직접 매도하지 않는다** — 시스템이 늦은 체결을 반영해 정리 대기 포지션을 팔 수 있어, 겹치면 이중 매도다. 직접 매도는 아래 5번 확인 뒤에만 한다.
    4. 정지 뒤에도 남은 불명 주문·정리 대기는 사람이 근거를 확인하고 종결한다(30분 이상 된 불명 매수는 기존 수동 조정 경로 — 회계만, 주문 상태는 UNKNOWN 유지).
    5. 🔴 **STOPPED 만으로 청산·정산 완료라고 기록하지 않는다.** 완료는 세션 STOPPED · 활성 주문 0(불명 포함) · 정리 대기 0 · 업비트 BTC 보유량과 내부 기록 일치를 모두 확인한 뒤에만 적는다. 업비트 직접 매도는 활성 주문 0·정리 대기 0 을 확인한 뒤 남은 차이에만 한다.
  - **대응 순서 (정산 불일치, 불명 주문 없음)**: 신규 매수는 일봉 마감(09:00 KST) 회차에서만 나간다. ~~승인 ≤ 5분~~은 잘못된 기존 안내다. N15 만료 방어는 운영 b5cd1d0에 포함돼 있다. H1은 5분 수집 뒤 5분 실행 유예·09:10부터 새 예약/의도 금지이며, 확정된 의도의 전송·체결 시각을 보장하는 기한은 아니다(원문 보존본 §74~§84). 정산 불일치 대응을 이 기한까지 늦춘다는 뜻은 아니다.
    1. 업비트 미체결 주문·보유량과 내부 기록을 대조한다(조회만).
    2. 🔴 **해결되지 않으면 다음 일봉 마감까지 기다리며 운영하지 않는다 — 바로 정지 절차로 들어간다(리뷰, 2026-10-30).** 정지는 장부 수량을 매도한다(청산 포함).
    3. 정지 뒤 남은 차이(장부보다 많은 실제 보유 등)는 **세션 STOPPED · 활성 주문 0 · 정리 대기 0을 확인한 뒤에만** 사람이 정리한다(직접 매도 포함). STOPPED 만으로 청산·정산 완료라고 기록하지 않는다(위 5번과 같다).
  - **최소 수정 대상**: 위 순서로 "확인 전 중복 주문 없음"은 현재 기능으로 충족된다고 판단해 **수정 대상으로 보고하지 않는다.** 청산 없는 차단 기능은 없다. 정산 불일치에서 마감 회차의 신규 매수를 막으려면 정지(청산 포함)밖에 없다. → **리뷰 판정 (2026-10-30): 이 한계는 이번 BTC 1개·매수 상한 2만 원 시험에서 수용. 별도 차단 기능 개발은 하지 않는다.** 이 시험 밖으로 넓혀 적용하지 않는다.
- 중단 시 시점·사유·그때까지의 손익을 기록한다. 한도를 늘려 재개하지 않는다.

### 6-3. 종료

- 활성화 뒤 **8주(56일)** 또는 중단 중 먼저 오는 때에 끝낸다. 자동 연장은 없다.
- 끝날 때 보유가 있으면 전량 매도해 정산한다.
- **보고할 것**
  - ① 구현 일치: 날짜별 신호·주문 대조
  - ② 체결 비용: 체결마다 실측
  - ③ 정산: 시작·종료 잔액과 체결 합계 대조
  - ④ 중단 기능: 발동 여부. 발동이 없으면 "실제 발동 없음"으로 적는다
  - ⑤ 손익: 기록만
- 손익으로 증액·연장·2단계를 판단하지 않는다.
- 정해 두는 한계 두 가지:
  - 이 시험은 포지션 1개라, 1단계 비용 판정(왕복 30건·판정 가능 코인 ≥ 8)을 채우지 못한다. 2단계 진입 근거가 되지 않는다.
  - 중단선이 실제로 발동하지 않으면 자동 중단 경로는 이 시험에서 확인되지 않는다. 그 경우 근거는 기존 시험과 #8 직전 확인뿐이다.

