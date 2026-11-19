#!/usr/bin/env bash
# 2차: N3 구판 재관측(P3 실제 빈 호출) + 관문 3·4판 대조, 매수 요약 기점 실패(R3 보정) → 보완 적용 실행. 출력 덮어쓰기 금지
set -u
R=/d/tmp/abc-ev/runs
EV="/d/Claude Code/projects/crypto-auto-trader/docs/evidence"
unset RACE_PG_URL SR_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL OB_PG_URL
run() { local name=$1 dir=$2 filt=$3; shift 3
  local out=$R/$name; [ -e "$out" ] && { echo "있음 $out"; return; }; mkdir -p "$out"
  ( cd "$dir" && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > "$out/head.txt"
    env "$@" COSTB_OUT="$out" ./gradlew :web-api:cleanTest :web-api:test --tests "$filt" > "$out/gradle.log" 2>&1; echo "rc=$?" > "$out/rc.txt" )
  mkdir -p "$out/xml"; cp "$dir"/web-api/build/test-results/test/TEST-*.xml "$out/xml/" 2>/dev/null
  echo "$name $(cat $out/rc.txt) xml=$(ls $out/xml | wc -l)"; }
pg() { docker exec cat-rt-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS $1" -c "CREATE DATABASE $1" >/dev/null; }
snap() { docker exec cat-rt-pg psql -U trader -d $1 -At -c "SELECT id, session_kind, session_id, exit_reason, status, size, md5(p::text) FROM position p WHERE exit_reason NOT IN ('STOP_LOSS','TAKE_PROFIT','TRAILING_STOP','TIME_STOP','STRATEGY_SIGNAL','BLACK_SWAN','FORCED_STOP','UNKNOWN') ORDER BY id"; }
for b in 0e1d2a02 b5cd1d0d; do
  db=n3old2_$b; name=n3old2-$b
  pg $db; run $name /d/tmp/n3old-$b '*OldBinaryNewEnumPgTest' OB_PG_URL=jdbc:postgresql://127.0.0.1:15432/$db
  out=$R/$name; snap $db > $out/rows_before_gate.txt
  for v in v3 v4; do
    if [ $v = v3 ]; then G="$EV/v159-20261113/rollback-gate-v3"; else G="$EV/dec-costbasis-followup-20261119/rollback-gate-v4-draft"; fi
    PSQL="docker exec -i cat-rt-pg psql -U trader -d $db -At" bash "$G/rollback_gate.sh" "$out/ledger-$v" > "$out/gate-$v.txt" 2>&1; echo "rc=$?" >> "$out/gate-$v.txt"
    echo "$name gate-$v: $(grep VERDICT $out/gate-$v.txt | cut -c1-40) $(tail -1 $out/gate-$v.txt)"
  done
  snap $db > $out/rows_after_gate.txt
  cmp -s $out/rows_before_gate.txt $out/rows_after_gate.txt && echo "$name 새 값 행 전후 동일($(wc -l < $out/rows_before_gate.txt)행)" || echo "$name 새 값 행 변경됨!"
done
pg rbs_base2; run rbs-base-c8cb48a0 /d/tmp/rbs-60df3437 '*RealBuySummaryPgTest' SR_PG_URL=jdbc:postgresql://127.0.0.1:15432/rbs_base2
( cd /d/tmp/rbs-60df3437 && git stash pop -q && git diff --stat )
pg rbs_fix1; run rbs-fix1-c8cb48a0-dirty /d/tmp/rbs-60df3437 '*RealBuySummaryPgTest' SR_PG_URL=jdbc:postgresql://127.0.0.1:15432/rbs_fix1
