"""B WF 입력 ⓒ — KRW-BTC H1 캔들을 업비트 공개 시세에서 받는다 (2026-10-28, LIVE_READINESS §1 #4, 리뷰 3차 승인).
2026-10-29 리뷰 38차: 시장 인수 추가(TSMOM 의 KRW-ETH). 기본값은 KRW-BTC 라 기존 실행은 그대로다. 기록에 중복·최초/최종·빠진 시간 목록을 더했다.

실행: python scripts/supertrend_filter/fetch_btc_h1.py [출력 CSV] [시작 UTC] [끝 UTC] [시장]
기본: d:/tmp/stval_btc/btc_h1.csv · 2023-12-31T15:00:00 ~ 2026-09-22T14:00:00 (21코인 로컬 CSV 구간의 합집합, 양 끝 포함)
형식: 로컬 CSV(stval.dir)와 같다 — 시각(UTC, 'Z' 없음),시가,고가,저가,종가,거래량. 시간 오름차순.
옆에 .meta.txt 로 출처·조회 시각·구간·행 수·빠진 시간 수·SHA-256 을 남긴다.
🔴 운영 서버·DB 를 건드리지 않는다. 받은 행이 구간 양 끝을 덮지 못하면 실패로 끝낸다(BTC 가드를 끈 채 실행하지 않는다).
"""
import hashlib
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone

sys.stdout.reconfigure(encoding="utf-8")
out = sys.argv[1] if len(sys.argv) > 1 else "d:/tmp/stval_btc/btc_h1.csv"
start = datetime.fromisoformat(sys.argv[2] if len(sys.argv) > 2 else "2023-12-31T15:00:00").replace(tzinfo=timezone.utc)
end = datetime.fromisoformat(sys.argv[3] if len(sys.argv) > 3 else "2026-09-22T14:00:00").replace(tzinfo=timezone.utc)
market = sys.argv[4] if len(sys.argv) > 4 else "KRW-BTC"
URL = "https://api.upbit.com/v1/candles/minutes/60?market=" + market + "&count=200&to={}"

rows = {}
dups = 0                                            # 응답 사이에 같은 시각이 다시 온 횟수 — 덮어쓰지 않고 센다
to = end + timedelta(hours=1)                       # to 는 제외 경계 — 끝 봉을 포함하려고 한 시간 뒤부터
fetched_at = datetime.now(timezone.utc)
while True:
    url = URL.format(to.strftime("%Y-%m-%dT%H:%M:%SZ"))
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=25) as r:
                data = json.load(r)
            break
        except Exception as e:                       # 429·일시 오류 — 잠시 쉬고 다시
            print(f"재시도 {attempt + 1}: {e}")
            time.sleep(1 + attempt)
    else:
        sys.exit("🔴 조회 실패 — 자료 미확보")
    if not data:
        break
    for c in data:
        t = datetime.fromisoformat(c["candle_date_time_utc"]).replace(tzinfo=timezone.utc)
        if start <= t <= end:
            if t in rows:
                dups += 1
                continue
            rows[t] = (c["opening_price"], c["high_price"], c["low_price"], c["trade_price"], c["candle_acc_trade_volume"])
    oldest = datetime.fromisoformat(data[-1]["candle_date_time_utc"]).replace(tzinfo=timezone.utc)
    if oldest <= start:
        break
    to = oldest
    time.sleep(0.12)

if start not in rows or end not in rows:
    sys.exit(f"🔴 구간 양 끝을 덮지 못했다: 시작 {start in rows} · 끝 {end in rows} — 자료 미확보")
ts = sorted(rows)
missing = int((end - start) / timedelta(hours=1)) + 1 - len(ts)
have = set(ts)
gaps = [start + timedelta(hours=i) for i in range(int((end - start) / timedelta(hours=1)) + 1)
        if start + timedelta(hours=i) not in have]  # 빈 시간은 봉을 만들지 않고 목록만 남긴다
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8", newline="\n") as f:
    for t in ts:
        f.write(t.strftime("%Y-%m-%dT%H:%M:%S") + "," + ",".join(str(v) for v in rows[t]) + "\n")
h = hashlib.sha256(open(out, "rb").read()).hexdigest()
meta = "\n".join([
    f"출처: 업비트 공개 시세 API GET /v1/candles/minutes/60?market={market} (인증 없음)",
    f"조회 시각(UTC): {fetched_at.isoformat(timespec='seconds')}",
    f"구간(UTC, 양 끝 포함): {start:%Y-%m-%dT%H:%M:%S} ~ {end:%Y-%m-%dT%H:%M:%S}",
    f"행 수: {len(ts)} · 최초 {ts[0]:%Y-%m-%dT%H:%M:%S} · 최종 {ts[-1]:%Y-%m-%dT%H:%M:%S} · 중복(응답 간 같은 시각): {dups}",
    f"빠진 시간(거래 없는 봉 포함): {missing}",
    "빠진 시각: " + (" ".join(f"{g:%Y-%m-%dT%H}" for g in gaps) or "없음"),
    f"SHA-256({os.path.basename(out)}): {h}",
])
open(out + ".meta.txt", "w", encoding="utf-8").write(meta + "\n")
print(meta)
