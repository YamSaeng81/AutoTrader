# -*- coding: utf-8 -*-
"""
운영 DB 에서 포지션과 캔들을 내려받아 캐시한다 (읽기 전용).

docs/EXIT_PATH_STUDY_PREREG.md v3 §3

⚠️ 비밀번호는 **환경변수 PGPASSWORD 로만** 받는다. 이 파일에도, 캐시에도 남기지 않는다.

    PGPASSWORD=... python scripts/exit_path_study/fetch_data.py
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

import pandas as pd
import psycopg2

CACHE = Path(__file__).resolve().parent / "cache"
H_MAX_HOURS = 72          # 최장 관찰기간 — 캔들을 이만큼 더 받아야 한다
PAD_HOURS = H_MAX_HOURS + 6


def connect():
    pw = os.environ.get("PGPASSWORD")
    if not pw:
        print("✗ PGPASSWORD 환경변수가 없습니다.")
        raise SystemExit(1)
    return psycopg2.connect(
        host=os.environ.get("PGHOST", "yhpapa.iptime.org"),
        port=int(os.environ.get("PGPORT", "8432")),
        dbname=os.environ.get("PGDATABASE", "crypto_auto_trader"),
        user=os.environ.get("PGUSER", "trader"),
        password=pw, connect_timeout=20)


POS_SQL = """
    SELECT id, coin_pair, side, session_kind, entry_price, size, invested_krw,
           realized_pnl, position_fee, opened_at, closed_at, exit_reason,
           stop_loss_price, take_profit_price
      FROM position
     WHERE status = 'CLOSED' AND closed_at IS NOT NULL
       AND entry_price IS NOT NULL AND entry_price > 0
       AND session_kind IN ('DYN_PAPER', 'LIVE', 'DYNAMIC')
     ORDER BY opened_at
"""

CANDLE_SQL = """
    SELECT coin_pair, time, open, high, low, close
      FROM candle_data
     WHERE timeframe = %s
       AND coin_pair = ANY(%s)
       AND time >= %s AND time <= %s
     ORDER BY coin_pair, time
"""


def main() -> int:
    CACHE.mkdir(exist_ok=True)
    conn = connect()
    print("✓ 접속 성공")

    pos = pd.read_sql(POS_SQL, conn)
    pos["opened_at"] = pd.to_datetime(pos["opened_at"], utc=True)
    pos["closed_at"] = pd.to_datetime(pos["closed_at"], utc=True)
    for c in ("entry_price", "size", "invested_krw", "realized_pnl",
              "position_fee", "stop_loss_price", "take_profit_price"):
        pos[c] = pd.to_numeric(pos[c], errors="coerce")
    pos.to_csv(CACHE / "positions.csv.gz", index=False, compression="gzip")

    print(f"\n포지션 {len(pos):,}건")
    print(pos.groupby(["session_kind", "side"]).size().to_string())
    print("\nexit_reason 분포 (DYN_PAPER)")
    d = pos[pos.session_kind == "DYN_PAPER"]
    print(d["exit_reason"].fillna("(NULL)").value_counts().to_string())

    lo = pos["opened_at"].min()
    hi = pos["closed_at"].max() + pd.Timedelta(hours=PAD_HOURS)
    coins = sorted(pos["coin_pair"].unique().tolist())
    print(f"\n캔들 범위 {lo:%Y-%m-%d %H:%M} ~ {hi:%Y-%m-%d %H:%M}  코인 {len(coins)}")

    for tf in ("H1", "M15"):
        df = pd.read_sql(CANDLE_SQL, conn, params=(tf, coins, lo.to_pydatetime(),
                                                   hi.to_pydatetime()))
        df["time"] = pd.to_datetime(df["time"], utc=True)
        for c in ("open", "high", "low", "close"):
            df[c] = pd.to_numeric(df[c])
        df.to_csv(CACHE / f"candles_{tf}.csv.gz", index=False, compression="gzip")
        print(f"  {tf}: {len(df):,}행 / {df['coin_pair'].nunique()}코인")

    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
