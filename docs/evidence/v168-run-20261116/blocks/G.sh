M=~/maint-sma20fix; mkdir -p "$M"; REPO=autotrader-backend
OLD=82d15d42fa22bb7d0f24a8243ebc888a2a7f071c; NEW=f0872e2547c918a7b5146f027d325bd441ddf0d7
QS=fa0691e647b61f6df4a85bdf796996f50470185e
qc() { git show $QS:scripts/ops/maint_checks.sql | $PSQL -v maint_start="$(cat "$M/maint_start.txt")" -f - ; }
POOLX="$(printf 'TRADING_CAPITALPOOL_MAXCONCURRENTPOSITIONS=3\nTRADING_CAPITALPOOL_PERPOSITIONCAPKRW=20000\nTRADING_CAPITALPOOL_STAGE=STAGE_1\nTRADING_CAPITALPOOL_TOTALCAPKRW=100000' | sort)"
NOT404='status=404, url=https://api\.upbit\.com/v1/candles/[^ ]*[?&]market=KRW-(BLAST|BONK|STORJ)([^A-Z0-9_-][^ ]*)?, body=.*Code not found'
ZERO="SELECT 'real_open_positions', count(*) FROM position p LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id WHERE p.status IN ('OPEN','CLOSING') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL')
UNION ALL SELECT 'real_unresolved_orders', count(*) FROM \"order\" o LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id WHERE o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL')
UNION ALL SELECT 'cleanup_pending', count(*) FROM position_cleanup WHERE done_at IS NULL
UNION ALL SELECT 'sid_orders', count(*) FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = 203
UNION ALL SELECT 'sid_positions', count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = 203"
ZEROX="$(printf '%s 0\n' real_open_positions real_unresolved_orders cleanup_pending sid_orders sid_positions | sort)"
zero5() { local z; z="$($PSQL -At -F ' ' -c "$ZERO")" || { echo "0 확인 조회 실패(종료코드 $?)" >&2; return 1; }
  [ "$(printf '%s\n' "$z" | sort)" = "$ZEROX" ] || { printf '0 확인 결과가 다섯 항목 모두 0 이 아님:\n%s\n' "$z" >&2; return 1; } ; }
alertsok() { [ "$1" -le 1 ] && grep -q '^🔴 🔴 은 경보이고 확정이 아니다' "$2" && ! grep -q 'Traceback' "$2" ; }
sid203() { [ "$($PSQL -At -c "$EG")" = "$EGS" ] && [ "$($PSQL -At -c "$LIVE1")" = "$SID:CREATED" ] && [ "$($PSQL -At -c "$KEEP")" = "t" ] \
  && [ "$($PSQL -At -c "SELECT available_krw = 20200 AND started_at IS NULL FROM live_trading_session WHERE id = $SID")" = "t" ] \
  && [ "$($PSQL -At -c "$BASE")" = "$BASEX" ] && zero5 ; }
