#!/usr/bin/env bash
# v1.245 §3 — 게이트 v4 검증 r3 1묶음. 실제 LIVE Java 경로·r2 합성 29건은 다시 돌리지 않는다. 운영·거래소·SSH 호출 없음.
# ① 보존된 oe2_r2 실제 세션(sid 23)을 읽기 전용 트랜잭션으로 v3·v4에 넣는다 ② 새 DB oe2_gate_r3(oe2_r2 스키마만 복사, V96)에 경계 8건 → v4.
# 사용: run-r3.sh r3   결과 = 이 폴더/runs/r3. 경로나 DB가 이미 있으면 거절(덮어쓰기·삭제 없음).
set -u
dir=/d/tmp/one-entry-d81c3f2f; want=1b719645ef907cb1aa45ffa01438fcfda7566758; prod=c43e1f1af1daab9c23e737101ed297826a70e4a5
ev="$(cd "$(dirname "$0")" && pwd)"; G3="$ev/../one-entry-gate-v3-20261125"; G4="$ev/../one-entry-gate-v4-20261125"
[ "${1:-}" = r3 ] || { echo "회차는 r3만"; exit 1; }
R=$ev/runs/r3; [ -e "$R" ] && { echo "있음 $R"; exit 1; }
[ "$(git -C $dir rev-parse HEAD)" = "$want" ] || { echo "HEAD 불일치"; exit 1; }
[ -z "$(git -C $dir status --porcelain)" ] || { echo "작업 트리 변경"; exit 1; }
[ "$(git -C $dir diff --name-only $prod HEAD)" = "web-api/src/test/java/com/cryptoautotrader/api/service/LiveOneEntrySmokePathPgTest.java" ] || { echo "제품/시험 차이 범위 다름"; exit 1; }
P() { docker exec -i one-entry-pg psql -X -q -U trader "$@"; }
PRO() { docker exec -i -e PGOPTIONS='-c default_transaction_read_only=on' one-entry-pg psql -X -q -U trader "$@"; }
db=oe2_gate_r3
[ -n "$(P -At -d postgres -c "select 1 from pg_database where datname='oe2_r2'")" ] || { echo "oe2_r2 없음 — 보고"; exit 1; }
[ -z "$(P -At -d postgres -c "select 1 from pg_database where datname='$db'")" ] || { echo "DB 있음 $db"; exit 1; }
mkdir -p "$R/gate"
{ echo "HEAD $(git -C $dir rev-parse HEAD)"; echo "product $prod (diff = 시험 1파일)"; date -Iseconds
  sha256sum "$G3/recovery-gate-v3.sql" "$G3/gate_check.py" "$G4/recovery-gate-v4.sql" "$G4/gate_check.py" "$ev/r3-fixtures.sql" "$ev/r3-expected.txt" "$0"
  P -At -d postgres -c "select version()"; } > "$R/head.txt"
ident() {  # r2 실제 세션 동일성(읽기 전용)
  PRO -At -d oe2_r2 -F '|' <<'Q'
select 'ro', current_setting('transaction_read_only');
select 'flyway', max(version) from flyway_schema_history where success;
select 'session', id, status, session_type, strategy_params::text, initial_capital, available_krw, total_asset_krw from live_trading_session where id = 23;
select 'position', id, status, size, realized_pnl, exit_reason, (closed_at is not null) from position where session_kind = 'LIVE' and session_id = 23 order by id;
select 'order', id, side, state, position_id, quantity, (sell_applied_at is not null), fill_accounting_pending from "order" where session_kind = 'LIVE' and session_id = 23 order by id;
select 'counts', (select count(*) from live_trading_session), (select count(*) from position), (select count(*) from "order"), (select count(*) from position_cleanup), (select count(*) from order_settlement_discrepancy);
select 'md5', md5(string_agg(x, ',' order by x)) from (select t::text x from live_trading_session t union all select t::text from position t union all select t::text from "order" t union all select t::text from position_cleanup t) z;
Q
}
ident > "$R/r2-identity-before.txt" 2>&1
reg() { printf 'sid=%s\nsession_type=REAL\nstrategy_type=DAILY_SMA20\ncoin_pair=KRW-BTC\ntimeframe=H1\ninitial_capital=10000\ninvest_ratio=0.995\nmax_investment=9950\nmax_entries=1\n' "$1"; }
: > "$R/gate-summary.txt"
gate() {  # 이름 DB sid SQL 판정기 [ro]
  local n=$1 d=$2 s=$3 q=$4 c=$5 x why got rs; reg "$s" > "$R/gate/$n.reg"
  if [ "${6:-}" = ro ]; then PRO -d $d -v ON_ERROR_STOP=1 -A -F '|' -P footer=off -v sid="$s" -f - < "$q" > "$R/gate/$n.out" 2> "$R/gate/$n.err"
  else P -d $d -v ON_ERROR_STOP=1 -A -F '|' -P footer=off -v sid="$s" -f - < "$q" > "$R/gate/$n.out" 2> "$R/gate/$n.err"; fi; echo $? > "$R/gate/$n.rc"
  python -I "$c" "$R/gate/$n.out" "$R/gate/$n.rc" "$R/gate/$n.reg" > "$R/gate/$n.verdict"
  read -r x why < <(awk -v n="$n" '$1==n{print $2, $3}' "$ev/r3-expected.txt")
  got=$(sed -n '1s/^VERDICT //p' "$R/gate/$n.verdict"); rs=$(sed -n '2,$p' "$R/gate/$n.verdict" | tr '\n' ';')
  local ok=ok; [ "$got" = "$x" ] || ok=NG
  if [ "$x" = FAIL ]; then [ "$rs" = "- ${why//~/ };" ] || ok=NG; fi   # FAIL은 사유가 정확히 그 1개여야 한다
  printf '%s %s sid=%s 기대=%s 판정=%s %s\n' "$ok" "$n" "$s" "$x" "$got" "$rs" >> "$R/gate-summary.txt"
}
gate r2_real_v3 oe2_r2 23 "$G3/recovery-gate-v3.sql" "$G3/gate_check.py" ro
gate r2_real_v4 oe2_r2 23 "$G4/recovery-gate-v4.sql" "$G4/gate_check.py" ro
# 새 합성 DB: 게이트가 읽는 표(+FK 대상 strategy_config)만 oe2_r2에서 스키마 복사(pg_dump -s -t, 읽기) + Flyway 이력 데이터.
# 전체 스키마는 timescale 객체 복원 문제가 있어 쓰지 않는다. 게이트 SQL이 읽는 표는 이 5개뿐이다.
TB='-t public.live_trading_session -t public.position -t public."order" -t public.position_cleanup -t public.order_settlement_discrepancy -t public.strategy_config -t public.flyway_schema_history'
{ P -d postgres -c "CREATE DATABASE $db"; echo "create rc=$?"
  docker exec one-entry-pg sh -c "pg_dump -U trader -s $TB oe2_r2" | P -d $db -v ON_ERROR_STOP=1; echo "schema rc=${PIPESTATUS[0]}/${PIPESTATUS[1]}"
  docker exec one-entry-pg pg_dump -U trader -a -t public.flyway_schema_history oe2_r2 | P -d $db -v ON_ERROR_STOP=1; echo "flyway rc=${PIPESTATUS[0]}/${PIPESTATUS[1]}"
  P -At -d $db -c "select count(*) from live_trading_session"; } > "$R/db.txt" 2>&1
P -At -d $db -c "select max(version) from flyway_schema_history where success" > "$R/flyway.txt" 2>&1
P -d $db -v ON_ERROR_STOP=1 -At -F ' ' -f - < "$ev/r3-fixtures.sql" > "$R/manifest.txt" 2> "$R/fixtures.err"; echo "fixtures rc=$?" >> "$R/db.txt"
while read -r n s; do [ -n "$n" ] && gate "$n" $db "$s" "$G4/recovery-gate-v4.sql" "$G4/gate_check.py"; done < "$R/manifest.txt"
ident > "$R/r2-identity-after.txt" 2>&1
cmp -s "$R/r2-identity-before.txt" "$R/r2-identity-after.txt" && echo "r2 동일성 전후 같음" > "$R/r2-identity-cmp.txt" || echo "r2 동일성 전후 다름" > "$R/r2-identity-cmp.txt"
cat "$R/db.txt" "$R/flyway.txt" "$R/r2-identity-cmp.txt"; cat "$R/gate-summary.txt"
echo "게이트 ok $(grep -c '^ok' "$R/gate-summary.txt") / $(wc -l < "$R/gate-summary.txt") (기대 $(grep -vc '^#' "$ev/r3-expected.txt"))"
