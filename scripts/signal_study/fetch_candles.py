# -*- coding: utf-8 -*-
"""
운영 DB 에서 H1 캔들을 내려받아 로컬에 캐시한다 (읽기 전용).

docs/SIGNAL_STUDY_PREREG.md v5 §2 의 코인·기간을 그대로 쓴다.

⚠️ 비밀번호는 **환경변수 PGPASSWORD 로만** 받는다. 이 파일에도, 캐시에도 남기지 않는다.

사용:
    PGPASSWORD=... python scripts/signal_study/fetch_candles.py
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

COINS = ["KRW-ADA", "KRW-ARB", "KRW-AVAX", "KRW-BTC", "KRW-DOGE", "KRW-ETH",
         "KRW-LINK", "KRW-ONDO", "KRW-SOL", "KRW-STX", "KRW-SUI", "KRW-XLM", "KRW-XRP"]
START = "2023-01-01"
END = "2026-09-19"
TIMEFRAME = "H1"

CACHE = Path(__file__).resolve().parent / "cache"


def main() -> int:
    pw = os.environ.get("PGPASSWORD")
    if not pw:
        print("✗ PGPASSWORD 환경변수가 없습니다.")
        return 1

    CACHE.mkdir(exist_ok=True)

    conn = psycopg2.connect(
        host=os.environ.get("PGHOST", "yhpapa.iptime.org"),
        port=int(os.environ.get("PGPORT", "8432")),
        dbname=os.environ.get("PGDATABASE", "crypto_auto_trader"),
        user=os.environ.get("PGUSER", "trader"),
        password=pw,
        connect_timeout=20,
    )
    print("✓ 접속 성공")

    # 시각은 UTC 로 받아 그대로 쓴다. KST 변환은 이 실험에 불필요하다
    # (구간 연속성 판정은 '간격'만 보고, 절대 시각은 블록 분할에만 쓴다).
    sql = """
        SELECT coin_pair, time, open, high, low, close, volume
          FROM candle_data
         WHERE timeframe = %s
           AND coin_pair = ANY(%s)
           AND time >= %s::timestamptz
           AND time <  (%s::date + 1)::timestamptz
         ORDER BY coin_pair, time
    """
    df = pd.read_sql(sql, conn, params=(TIMEFRAME, COINS, START, END))
    conn.close()

    if df.empty:
        print("✗ 조회 결과가 비었습니다.")
        return 1

    df["time"] = pd.to_datetime(df["time"], utc=True)
    for c in ("open", "high", "low", "close", "volume"):
        df[c] = pd.to_numeric(df[c], errors="coerce")

    # parquet 엔진(pyarrow/fastparquet)이 없는 환경이라 CSV 로 둔다.
    # 40만 행 규모라 용량·속도 모두 문제되지 않는다.
    out = CACHE / "candles_h1.csv.gz"
    df.to_csv(out, index=False, compression="gzip")

    print(f"✓ 저장 {out}  ({len(df):,} 행)")
    print()
    print(f"{'코인':<10} {'행':>8}  {'시작':<12} {'끝':<12}  {'결측추정':>8}")
    print("-" * 60)
    for coin, g in df.groupby("coin_pair", sort=True):
        span_h = int((g["time"].max() - g["time"].min()).total_seconds() // 3600) + 1
        miss = span_h - len(g)
        print(f"{coin:<10} {len(g):>8,}  {str(g['time'].min().date()):<12} "
              f"{str(g['time'].max().date()):<12}  {miss:>8,}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
