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
[ "$(head -1 "$M/git-f1.txt")" = "$NEW" ] && [ "$(sed -n 2p "$M/git-f1.txt")" = "(끝)" ] || stop "HEAD 가 1103f48 이 아니거나 추적 파일 변경 있음 — 보고"
svc | tee "$M/svc-f1.txt"
IMGF1=sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62   # v1.116: #23 배포 이미지. 이전 기대 ~/maint-n13/image-new.txt(#22, 9cb5d84f…). 이전 기대 ~/maint-n12/image-new.txt(#21, 67294e80…). 이전 기대 ~/maint-n11/image-new.txt(#20, fd3ac351…). 이전 기대 ~/maint-n08/image-new.txt(#19, 61804654…). 이전 기대 ~/maint-n04/image-new.txt(#18, 39875d5f…), ~/maint-rounding/image-new.txt(#17, a747a8e6…)
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMGF1" ] && [ "$(cat ~/maint-n15n16/image-new.txt)" = "$IMGF1" ] || stop "backend 이미지가 #23 배포 이미지(9818254c…)와 다름 — 보고"
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
