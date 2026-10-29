"""TSMOM 공식 점검 공백 — 거래소 재조회(V93 조건 (나)) (2026-10-29, 리뷰 39차, docs/RESEARCH_PROPOSAL_TS_MOMENTUM.md §6-2 ⑪).

실행: python -I scripts/tsmom/maintenance_recheck_h1.py [입력 CSV] [저장 폴더]
기본: scripts/tsmom/maintenance_windows_tsmom.csv · d:/tmp/stval_maint/recheck

각 공백 묶음(입력 CSV 의 gap_first_utc ~ gap_last_utc)마다 KRW-BTC·KRW-ETH H1 을 앞뒤 3시간씩 넓혀 업비트 공개 시세
(/v1/candles/minutes/60, 인증 없음)로 다시 조회한다. 요청 URL·응답 머리말·응답 본문·조회 시각(UTC)을 그대로 저장하고,
응답의 봉 시작 시각(candle_date_time_utc)만 읽어 각 칸의 있음/없음을 적는다. 🔴 가격 칸은 읽지 않는다. 가짜 봉을 만들지 않는다.
"""
import csv
import hashlib
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone

sys.stdout.reconfigure(encoding="utf-8")
H, PAD = timedelta(hours=1), timedelta(hours=3)
src = sys.argv[1] if len(sys.argv) > 1 else "scripts/tsmom/maintenance_windows_tsmom.csv"
out = sys.argv[2] if len(sys.argv) > 2 else "d:/tmp/stval_maint/recheck"
os.makedirs(out, exist_ok=True)


def utc(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


summary = []
for row in csv.DictReader(open(src, encoding="utf-8")):
    lo, hi = utc(row["gap_first_utc"]) - PAD, utc(row["gap_last_utc"]) + PAD
    count = int((hi - lo) / H) + 1
    for market in ("KRW-BTC", "KRW-ETH"):
        url = (f"https://api.upbit.com/v1/candles/minutes/60?market={market}"
               f"&to={(hi + H).strftime('%Y-%m-%dT%H:%M:%SZ')}&count={count}")
        asked = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=20) as r:
            body, status, headers = r.read(), r.status, dict(r.headers)
        got = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
        stem = f"{out}/{row['id']}_{market}"
        open(stem + ".body.json", "wb").write(body)
        json.dump({"url": url, "requested_at_utc": asked, "received_at_utc": got, "status": status, "headers": headers,
                   "body_sha256": hashlib.sha256(body).hexdigest()}, open(stem + ".request.json", "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
        have = {utc(c["candle_date_time_utc"] + "Z") for c in json.loads(body)}
        slots = [lo + i * H for i in range(count)]
        absent = [t.strftime("%Y-%m-%dT%H") for t in slots if t not in have]
        summary.append({"id": row["id"], "market": market, "requested_at_utc": asked, "status": status,
                        "body_sha256": hashlib.sha256(body).hexdigest(), "range_utc": f"{lo:%Y-%m-%dT%H}~{hi:%Y-%m-%dT%H}",
                        "absent": absent})
        print(f"{row['id']} {market} {lo:%Y-%m-%dT%H}~{hi:%Y-%m-%dT%H} 응답 {status} · 없는 칸 {' '.join(absent) or '-'}")
        time.sleep(0.2)
json.dump(summary, open(f"{out}/summary.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
