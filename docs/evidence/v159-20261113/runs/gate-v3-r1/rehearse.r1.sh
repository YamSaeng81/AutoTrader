#!/usr/bin/env bash
# 총괄 v1.159 §5 — 관문 3판 로컬 PG 예행 (2026-11-13). 2판 rehearse.sh 의 장치(템플릿 복사·관문 전후 표 내용 해시)를 그대로 쓰고
# 3판 판정 사례를 더했다. 로컬 격리 컨테이너 v152-pg 의 이 작업용 DB 만 쓴다(운영 접속 없음).
# 템플릿 h153 = V1..V94+V96 (4a4a04a3 와 마이그레이션 트리 해시 7bca8a02… 같음).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
C=v152-pg; U=trader; TPL=h153
OUTROOT="${1:?출력 폴더}"
mkdir -p "$OUTROOT"
q() { docker exec -i "$C" psql -U "$U" -d "$1" -v ON_ERROR_STOP=1 -Atq; }
PASS=0; FAIL=0; NAMES=""

docker exec "$C" psql -U "$U" -d s5 -Atqc "ALTER DATABASE $TPL WITH IS_TEMPLATE true ALLOW_CONNECTIONS false" >/dev/null
docker exec "$C" psql -U "$U" -d s5 -Atqc "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname = '$TPL'" >/dev/null

fingerprint() {  # 관문이 읽는 표 전체의 내용 해시(쓰기 없음 확인)
  echo "SELECT md5(coalesce(string_agg(t, '' ORDER BY t), '')) FROM (
    SELECT v::text AS t FROM paper_trading.virtual_balance v UNION ALL SELECT p::text FROM paper_trading.position p
    UNION ALL SELECT o::text FROM paper_trading.\"order\" o UNION ALL SELECT l::text FROM live_trading_session l
    UNION ALL SELECT d::text FROM dynamic_session d UNION ALL SELECT p::text FROM position p UNION ALL SELECT o::text FROM \"order\" o
    UNION ALL SELECT c::text FROM position_cleanup c UNION ALL SELECT s::text FROM order_settlement_discrepancy s) x;" | q "$1"
}

scenario() {   # $1 이름, $2 기대 판정, $3 기대 종료 코드, $4 상태 SQL, [$5 EXCH_RECON 파일]
  local name="$1" expect="$2" erc="$3" sql="$4" recon="${5:-}" db="g159_$1"
  NAMES="$NAMES $name"
  docker exec "$C" psql -U "$U" -d s5 -Atqc "DROP DATABASE IF EXISTS $db" >/dev/null
  docker exec "$C" psql -U "$U" -d s5 -Atqc "CREATE DATABASE $db TEMPLATE $TPL" >/dev/null
  printf '%s\n' "$sql" | q "$db" > "$OUTROOT/$name.setup.txt" 2>&1 || { echo "[$name] 상태 준비 실패"; FAIL=$((FAIL+1)); return; }
  local before after out rc
  before="$(fingerprint "$db")"
  out="$(EXCH_RECON="$recon" PSQL="docker exec -i $C psql -U $U -d $db -At" bash "$HERE/rollback_gate.sh" "$OUTROOT/$name.ledger" 2>&1)"; rc=$?
  after="$(fingerprint "$db")"
  printf '%s\nexit=%s\nfingerprint before=%s after=%s\n' "$out" "$rc" "$before" "$after" > "$OUTROOT/$name.out.txt"
  local verdict; verdict="$(printf '%s\n' "$out" | sed -n 's/^VERDICT \([A-Z_]*\).*/\1/p' | tail -1)"
  local ledger_ok=1
  if [ "$expect" = "ROLLBACK_GATE_OK" ]; then
    [ -s "$OUTROOT/$name.ledger/LEDGER_COMPLETE" ] && ( cd "$OUTROOT/$name.ledger" && sha256sum -c SHA256SUMS >/dev/null 2>&1 ) || ledger_ok=0
  fi
  if [ "$verdict" = "$expect" ] && [ "$rc" = "$erc" ] && [ "$before" = "$after" ] && [ "$ledger_ok" = 1 ]; then
    PASS=$((PASS+1)); echo "[$name] 통과: $verdict exit=$rc 표 변화 없음"
  else
    FAIL=$((FAIL+1)); echo "[$name] 실패: 판정 $verdict(기대 $expect) exit=$rc(기대 $erc) 전 $before 후 $after 장부 $ledger_ok"
  fi
}

KEY="'{\"paperExecutionMode\":\"LIVE_ALIGNED\",\"paperMaxInvestment\":20000}'::jsonb"
OLDKEY="'{}'::jsonb"
VB="INSERT INTO paper_trading.virtual_balance (id, total_krw, available_krw, initial_capital, strategy_name, coin_pair, timeframe, status, started_at, strategy_params) VALUES"
PO="INSERT INTO paper_trading.position (session_id, session_kind, coin_pair, side, entry_price, avg_price, size, invested_krw, status, opened_at, closed_at, exit_reason, exit_decided_at, realized_pnl) VALUES"
LS="INSERT INTO live_trading_session (id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status) VALUES"
RP="INSERT INTO position (id, coin_pair, side, entry_price, avg_price, size, session_kind, session_id, status) VALUES"
RO="INSERT INTO \"order\" (coin_pair, side, order_type, quantity, state, session_kind, session_id, position_id, filled_quantity, applied_executed_funds, applied_paid_fee, reported_executed_funds, reported_paid_fee, fill_accounting_pending) VALUES"

# 기존 66 과 같은 키 없는 세션 + 정지된 SID 203 형태(CREATED)를 모든 사례에 깔아 둔다(새 모드로 세지 않아야 한다).
BASE="$VB (501, 100000, 80000, 100000, 'MEANREV_BB', 'KRW-BTC', 'M15', 'RUNNING', now(), $OLDKEY);
$PO (501, 'PAPER', 'KRW-BTC', 'BUY', 100, 100, 200, 20000, 'OPEN', now(), NULL, NULL, NULL, NULL);
$LS (203, 'DAILY_SMA20', 'KRW-BTC', 'D1', 20200, 20200, 20200, 'CREATED');"
POSTOK="$VB (901, 20150, 20150, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (901, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'CLOSED', now(), now(), 'FORCED_STOP', now(), -49.88);"
# 매수 20,000 요청 · 반영 19,990 + 수수료 9.995 · 0.0016 BTC 체결, 열린 포지션 0.0016 — 잔액 기대 = 20200 − (20000 − round(20000 − 19990 − 9.995, 2)) = 200.01
LIVEHELD="$LS (204, 'DAILY_SMA20', 'KRW-BTC', 'D1', 20200, 200.01, 20200, 'RUNNING');
$RP (7001, 'KRW-BTC', 'LONG', 12493750, 12493750, 0.0016, 'LIVE', 204, 'OPEN');
$RO ('KRW-BTC', 'BUY', 'PRICE', 20000, 'FILLED', 'LIVE', 204, 7001, 0.0016, 19990, 9.995, 19990, 9.995, false);"
RECON="$OUTROOT/exch-recon-sample.txt"
printf '거래소 BTC 증감 0.0016 · 주문 순체결량 0.0016 · 열린 포지션 수량 0.0016 (예행용 견본 — 운영 출력 아님)\n' > "$RECON"

# ── 2판 사례(새 모드)를 3판 판정 이름으로 ──
scenario s01_pre_clean "ROLLBACK_GATE_OK" 0 "$BASE"
scenario s02_post_ok "ROLLBACK_GATE_OK" 0 "$BASE
$POSTOK"
scenario s03_nm_running "STOP_RUNNING" 3 "$BASE
$VB (902, 20200, 200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'RUNNING', now(), $KEY);
$PO (902, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'OPEN', now(), NULL, NULL, NULL, NULL);"
scenario s04_nm_closing "HOLD_PENDING" 2 "$BASE
$VB (903, 20188.5, 200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (903, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'CLOSING', now(), NULL, 'FORCED_STOP', now(), NULL);"
scenario s05_closing_no_decided "STOP_UNKNOWN" 3 "$BASE
$VB (904, 20188.5, 200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (904, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'CLOSING', now(), NULL, 'FORCED_STOP', NULL, NULL);"
scenario s06_odd_status "STOP_UNKNOWN" 3 "$BASE
$VB (905, 20200, 20200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'PAUSED', now(), $KEY);"
scenario s07_stopped_open "STOP_MISMATCH" 3 "$BASE
$VB (906, 20200, 200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (906, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'OPEN', now(), NULL, NULL, NULL, NULL);"
scenario s08_no_v96_created "STOP_UNKNOWN" 3 "$BASE
$VB (907, 20150, 20150, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
ALTER TABLE paper_trading.position DROP COLUMN exit_decided_at;"
mkdir -p "$OUTROOT/s09_ledger_exists.ledger"
scenario s09_ledger_exists "LEDGER_REFUSED" 4 "$BASE
$POSTOK"
# ── 3판 새 사례: "생성 전"이어도 실계좌 진행·미정산·정리 대기·대조가 막는다 ──
scenario s10_pre_intent_ready "HOLD_PENDING" 2 "$BASE
$LS (205, 'DAILY_SMA20', 'KRW-BTC', 'D1', 20200, 200, 20200, 'STOPPED');
$RP (7002, 'KRW-BTC', 'LONG', 12500000, 12500000, 0.0016, 'LIVE', 205, 'CLOSING');
$RO ('KRW-BTC', 'SELL', 'MARKET', 0.0016, 'INTENT_READY', 'LIVE', 205, 7002, NULL, NULL, NULL, NULL, NULL, false);"
scenario s11_pre_acct_pending "HOLD_PENDING" 2 "$BASE
$RO ('KRW-BTC', 'SELL', 'MARKET', 0.0016, 'FILLED', 'LIVE', 203, NULL, 0.0016, 19900, 9.95, 19900, 9.95, true);"
scenario s12_pre_cleanup "HOLD_PENDING" 2 "$BASE
INSERT INTO position_cleanup (position_id, session_kind, session_id, reason, pending_at) VALUES (7003, 'DYNAMIC', 9, '시험 정리 대기', now());"
scenario s13_pre_discrepancy "HOLD_PENDING" 2 "$BASE
$RO ('KRW-BTC', 'SELL', 'MARKET', 0.0016, 'FILLED', 'LIVE', 203, NULL, 0.0016, 19900, 9.95, 19900, 9.95, false);
INSERT INTO order_settlement_discrepancy (order_id, kind, previous_value, received_value) SELECT max(id), 'FUNDS', 19900, 19899 FROM \"order\";"
scenario s14_pre_report_ne_applied "STOP_MISMATCH" 3 "$BASE
$RO ('KRW-BTC', 'SELL', 'MARKET', 0.0016, 'FILLED', 'LIVE', 203, NULL, 0.0016, 19900, 9.95, 19899, 9.95, false);"
scenario s15_live_held_no_recon "STOP_UNKNOWN" 3 "$BASE
$LIVEHELD"
scenario s16_live_held_recon_ok "ROLLBACK_GATE_OK" 0 "$BASE
$LIVEHELD" "$RECON"
scenario s17_live_qty_mismatch "STOP_MISMATCH" 3 "$BASE
$LIVEHELD
UPDATE position SET size = 0.0015 WHERE id = 7001;" "$RECON"
scenario s18_live_balance_mismatch "STOP_MISMATCH" 3 "$BASE
$LIVEHELD
UPDATE live_trading_session SET available_krw = 200 WHERE id = 204;" "$RECON"
scenario s19_dynamic_real_closing "HOLD_PENDING" 2 "$BASE
INSERT INTO dynamic_session (id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode) VALUES (9, 'COMPOSITE_MOMENTUM_ICHIMOKU_V2', 'H1', 100000, 80000, 100000, 'STOPPED', 'REAL');
$RP (7004, 'KRW-ETH', 'LONG', 4000000, 4000000, 0.005, 'DYNAMIC', 9, 'CLOSING');"

echo "예행 결과: 통과 $PASS / 실패 $FAIL"
for d in $NAMES; do docker exec "$C" psql -U "$U" -d s5 -Atqc "DROP DATABASE IF EXISTS g159_$d" >/dev/null; done
[ "$FAIL" = 0 ]
