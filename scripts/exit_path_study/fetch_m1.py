# -*- coding: utf-8 -*-
"""
시작봉 해소용 M1 캔들 수집 — 업비트 공개 시세 API (인증 불필요, 읽기 전용)

🔴 **필요한 구간만 좁혀서 받는다.**
    전 코인·전 기간이 아니라, 시작봉 불명이 실제로 발생한 (코인 × 시작봉) 217개 구간
    = 약 6,945개 캔들. 요청 1건당 최대 60캔들이므로 요청 217건이면 끝난다.

⚠️ 업비트는 **거래가 없던 분의 캔들을 생략**한다. 빠진 분은 체결이 없었다는 뜻이므로
   그 분에는 어떤 가격도 접촉하지 않았다고 본다 (고·저는 체결 기준이다).
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import path as P  # noqa: E402
from run_study import (CACHE, H_GRID, STP_R, TGT_R, load, make_entries,  # noqa: E402
                       pick_tf)

API = "https://api.upbit.com/v1/candles/minutes/1"
THROTTLE_S = 0.12          # 업비트 시세 API 제한(약 10 req/s) 아래로
RETRY = 3


def needed_windows():
    """시작봉 불명이 1칸이라도 발생한 (코인, 시작봉 시각, 주기) 집합."""
    pos, series = load()
    dyn = pos[pos.session_kind == "DYN_PAPER"].reset_index(drop=True)
    entries, _ = make_entries(dyn)
    need = {}
    for H in H_GRID:
        dt = np.timedelta64(H, "h")
        for coin, t_in, entry in entries:
            tf, s = pick_tf(series, coin, t_in, t_in + dt, H)
            if s is None:
                continue
            times, o, h, l, c = s
            i0, i1, j = P.window(times, t_in, t_in + dt)
            if i1 <= i0 or j < 0:
                continue
            tgt = entry * (1 + TGT_R / 100.0)
            stp = entry * (1 - STP_R / 100.0)
            if ((h[j] >= tgt) | (l[j] <= stp)).any():
                need[(coin, times[j])] = tf
    return need


def fetch(market: str, to_iso: str, count: int):
    url = f"{API}?market={market}&count={count}&to={to_iso}"
    for k in range(RETRY):
        try:
            time.sleep(THROTTLE_S)
            req = urllib.request.Request(url, headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(1.0 + k)
                continue
            if e.code == 404:
                return []          # 상장폐지 등
            raise
        except Exception:
            if k == RETRY - 1:
                raise
            time.sleep(0.5 + k)
    return []


def main() -> int:
    need = needed_windows()
    print(f"필요한 구간 {len(need)}개  "
          f"(M15 {sum(1 for v in need.values() if v=='M15')} · "
          f"H1 {sum(1 for v in need.values() if v=='H1')})")

    rows, empty, fail = [], 0, 0
    for i, ((coin, bar_start), tf) in enumerate(sorted(need.items(), key=lambda x: str(x[0]))):
        dur = np.timedelta64(60 if tf == "H1" else 15, "m")
        bar_end = bar_start + dur
        to_iso = pd.Timestamp(bar_end).strftime("%Y-%m-%dT%H:%M:%SZ")
        try:
            data = fetch(coin, to_iso, int(dur / np.timedelta64(1, "m")))
        except Exception as e:
            fail += 1
            print(f"  ✗ {coin} {bar_start} — {e!r}")
            continue
        if not data:
            empty += 1
        for d in data:
            rows.append((coin, d["candle_date_time_utc"], d["opening_price"],
                         d["high_price"], d["low_price"], d["trade_price"]))
        if (i + 1) % 50 == 0:
            print(f"  {i+1}/{len(need)}  누적 캔들 {len(rows):,}", flush=True)

    df = pd.DataFrame(rows, columns=["coin_pair", "time", "open", "high", "low", "close"])
    df["time"] = pd.to_datetime(df["time"], utc=True)
    df = df.drop_duplicates(["coin_pair", "time"]).sort_values(["coin_pair", "time"])
    df.to_csv(CACHE / "candles_M1.csv.gz", index=False, compression="gzip")
    print(f"\n✓ M1 {len(df):,}캔들 / {df.coin_pair.nunique()}코인 저장")
    print(f"  빈 응답 {empty}건 · 실패 {fail}건")
    exp = sum(60 if v == "H1" else 15 for v in need.values())
    print(f"  기대 {exp:,} 대비 {len(df)/exp*100:.1f}%  "
          f"(업비트는 무거래 분봉을 생략한다)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
