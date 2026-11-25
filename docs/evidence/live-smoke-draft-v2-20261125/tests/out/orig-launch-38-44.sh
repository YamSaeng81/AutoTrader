if [ "$STEP" = gate ]; then
  sed -n '/^GATE-OUT-BEGIN$/,/^GATE-OUT-END$/p' "$L/gate.out" | sed '1d;$d' > "$L/gate-psql.txt"
  sed -n 's/^GATE-RC=//p' "$L/gate.out" > "$L/gate-rc.txt"
  printf 'sid=%s\nsession_type=REAL\nstrategy_type=DAILY_SMA20\ncoin_pair=KRW-BTC\ntimeframe=H1\ninitial_capital=10000\ninvest_ratio=0.995\nmax_investment=9950\nmax_entries=1\n' "$SID" > "$L/registration.txt"
  python -I "$here/../one-entry-gate-v4-20261125/gate_check.py" "$L/gate-psql.txt" "$L/gate-rc.txt" "$L/registration.txt" | tee "$L/gate-verdict.txt"
fi
exit "$rc"
