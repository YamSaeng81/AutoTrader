#!/usr/bin/env bash
# 총괄 v1.153 H — 복구 관문 로컬 PG 예행 (2026-11-12). 로컬 격리 컨테이너 v152-pg 의 이 작업용 DB 만 쓴다(운영 접속 없음).
# 사례마다 템플릿 DB(h153 = 후보 마이그레이션 V1..V94+V96 적용)를 복사해 상태를 만들고 관문을 돌린다. 관문 전후 표 내용 해시가 같아야 한다(읽기 전용).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
C=v152-pg; U=trader; TPL=h153
OUTROOT="${1:?출력 폴더}"
mkdir -p "$OUTROOT"
q() { docker exec -i "$C" psql -U "$U" -d "$1" -v ON_ERROR_STOP=1 -Atq; }
PASS=0; FAIL=0

# 템플릿 준비: TimescaleDB 배경 작업자가 템플릿 DB 에 붙어 있으면 복사가 거절된다(1회차, 원문 보존).
# 템플릿을 접속 불가로 두고 그 DB 의 배경 작업자만 끝낸다(이 작업용 DB 한정).
docker exec "$C" psql -U "$U" -d s5 -Atqc "ALTER DATABASE $TPL WITH IS_TEMPLATE true ALLOW_CONNECTIONS false" >/dev/null
docker exec "$C" psql -U "$U" -d s5 -Atqc "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname = '$TPL'" >/dev/null

fingerprint() {  # 세 표 내용 해시
  echo "SELECT md5(coalesce(string_agg(t::text, '' ORDER BY t::text), '')) FROM (SELECT v::text AS t FROM paper_trading.virtual_balance v UNION ALL SELECT p::text FROM paper_trading.position p UNION ALL SELECT o::text FROM paper_trading.\"order\" o) x;" | q "$1"
}

scenario() {   # $1 이름, $2 기대 판정, $3 기대 종료 코드, $4 상태 SQL
  local name="$1" expect="$2" erc="$3" sql="$4" db="h153_$1"
  docker exec "$C" psql -U "$U" -d s5 -Atqc "DROP DATABASE IF EXISTS $db" >/dev/null
  docker exec "$C" psql -U "$U" -d s5 -Atqc "CREATE DATABASE $db TEMPLATE $TPL" >/dev/null
  printf '%s\n' "$sql" | q "$db" > "$OUTROOT/$name.setup.txt" 2>&1 || { echo "[$name] 상태 준비 실패"; FAIL=$((FAIL+1)); return; }
  local before after out rc
  before="$(fingerprint "$db")"
  out="$(PSQL="docker exec -i $C psql -U $U -d $db -At" bash "$HERE/rollback_gate.sh" "$OUTROOT/$name.ledger" 2>&1)"; rc=$?
  after="$(fingerprint "$db")"
  printf '%s\nexit=%s\nfingerprint before=%s after=%s\n' "$out" "$rc" "$before" "$after" > "$OUTROOT/$name.out.txt"
  local verdict; verdict="$(printf '%s\n' "$out" | sed -n 's/^VERDICT \([A-Z_]*\).*/\1/p' | tail -1)"
  if [ "$verdict" = "$expect" ] && [ "$rc" = "$erc" ] && [ "$before" = "$after" ]; then
    PASS=$((PASS+1)); echo "[$name] 통과: $verdict exit=$rc 표 변화 없음"
  else
    FAIL=$((FAIL+1)); echo "[$name] 실패: 판정 $verdict(기대 $expect) exit=$rc(기대 $erc) 전 $before 후 $after"
  fi
}

KEY="'{\"paperExecutionMode\":\"LIVE_ALIGNED\",\"paperMaxInvestment\":20000}'::jsonb"
OLDKEY="'{}'::jsonb"
VB="INSERT INTO paper_trading.virtual_balance (id, total_krw, available_krw, initial_capital, strategy_name, coin_pair, timeframe, status, started_at, strategy_params) VALUES"
PO="INSERT INTO paper_trading.position (session_id, session_kind, coin_pair, side, entry_price, avg_price, size, invested_krw, status, opened_at, closed_at, exit_reason, exit_decided_at, realized_pnl) VALUES"

# 기존 66 과 같은 키 없는 세션은 모든 사례에 깔아 둔다(세지 않아야 한다).
BASE="$VB (501, 100000, 80000, 100000, 'MEANREV_BB', 'KRW-BTC', 'M15', 'RUNNING', now(), $OLDKEY);
$PO (501, 'PAPER', 'KRW-BTC', 'BUY', 100, 100, 200, 20000, 'OPEN', now(), NULL, NULL, NULL, NULL);"

scenario s1_pre "PRE_CREATION_OK" 0 "$BASE"
scenario s2_post_ok "POST_CREATION_OK" 0 "$BASE
$VB (901, 20150, 20150, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (901, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'CLOSED', now(), now(), 'FORCED_STOP', now(), -49.88);"
scenario s3_running "STOP_RUNNING" 3 "$BASE
$VB (902, 20200, 200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'RUNNING', now(), $KEY);
$PO (902, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'OPEN', now(), NULL, NULL, NULL, NULL);"
scenario s4_pending "HOLD_PENDING" 2 "$BASE
$VB (903, 20188.5, 200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (903, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'CLOSING', now(), NULL, 'FORCED_STOP', now(), NULL);"
scenario s5_closing_no_decided "STOP_UNKNOWN" 3 "$BASE
$VB (904, 20188.5, 200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (904, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'CLOSING', now(), NULL, 'FORCED_STOP', NULL, NULL);"
scenario s6_odd_status "STOP_UNKNOWN" 3 "$BASE
$VB (905, 20200, 20200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'PAUSED', now(), $KEY);"
scenario s7_stopped_open "STOP_MISMATCH" 3 "$BASE
$VB (906, 20200, 200, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (906, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'OPEN', now(), NULL, NULL, NULL, NULL);"
scenario s8_no_v96_created "STOP_UNKNOWN" 3 "$BASE
$VB (907, 20150, 20150, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
ALTER TABLE paper_trading.position DROP COLUMN exit_decided_at;"
scenario s9_no_v96_pre "PRE_CREATION_OK" 0 "$BASE
ALTER TABLE paper_trading.position DROP COLUMN exit_decided_at;"
# s10: s2 와 같은 상태에 같은 장부 폴더로 두 번째 — 덮어쓰지 않는다.
mkdir -p "$OUTROOT/s10_ledger_exists.ledger"
scenario s10_ledger_exists "LEDGER_REFUSED" 4 "$BASE
$VB (901, 20150, 20150, 20200, 'DAILY_SMA20', 'KRW-BTC', 'H1', 'STOPPED', now(), $KEY);
$PO (901, 'PAPER', 'KRW-BTC', 'BUY', 12500000, 12500000, 0.0016, 20000, 'CLOSED', now(), now(), 'FORCED_STOP', now(), -49.88);"

echo "예행 결과: 통과 $PASS / 실패 $FAIL"
for d in s1_pre s2_post_ok s3_running s4_pending s5_closing_no_decided s6_odd_status s7_stopped_open s8_no_v96_created s9_no_v96_pre s10_ledger_exists; do
  docker exec "$C" psql -U "$U" -d s5 -Atqc "DROP DATABASE IF EXISTS h153_$d" >/dev/null
done
[ "$FAIL" -eq 0 ]
