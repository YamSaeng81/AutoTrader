#!/usr/bin/env python3
"""지나간 구간의 **봉 전수 대조** — 적재 지속성을 사후에 판정한다 (2026-10-01 신설).

🔴 왜 이것이 필요한가
`freshness` 는 매시간 **한 점**만 본다. 72시간을 그렇게 모으는 이유는 도구가 지나간 시간을
판정할 수 없었기 때문인데, 판정 기준을 **거래소 봉 목록**으로 고친 뒤에는 그 전제가 바뀐다 —
그 구간의 봉은 DB 와 거래소 양쪽에 남아 있으므로 **봉 단위로 전수 대조**할 수 있다.
표본이 22코인 × 72점에서 22코인 × 72봉 전부로 늘어나므로 이 대조는 **더 엄격하다.**

⚠️ 이 도구가 말하지 않는 것 — **적시성**이다.
지금 DB 에 봉이 있다는 것은 "언젠가 들어왔다"는 뜻이고, 제때 들어왔다는 뜻은 아니다
(`shouldFetchFullRange` 가 걸리면 과거 구간을 다시 받을 수 있다). 적시성은 같은 구간의
`freshness_log.jsonl` 로 보완한다 — 매 회차의 `to` 가 현재/직전 봉이었다면 그 시각에 1봉 이상
뒤처진 적이 없다는 뜻이다. **두 증거를 함께 써야 72시간 연속 적재의 근거가 된다.**

사용법 (DB 덤프는 비밀번호가 셸을 벗어나지 않도록 사용자가 직접 뽑는다):

  docker exec -e PGPASSWORD="$(grep -m1 '^DB_PASSWORD=' .env | cut -d= -f2-)" \
    autotrader-db-1 psql -U trader -d crypto_auto_trader -At -F'|' -c \
    "SELECT coin_pair, to_char(time,'YYYY-MM-DD\"T\"HH24:MI:SSZ') FROM market_data_cache
     WHERE timeframe='H1' AND time >= '2026-09-28T06:00:00Z'
       AND time < '2026-10-01T06:00:00Z' ORDER BY coin_pair, time" > /tmp/db_bars.txt

  python3 scripts/prospective/bar_audit.py /tmp/db_bars.txt \
      --from 2026-09-28T06:00:00Z --to 2026-10-01T06:00:00Z
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

sys.path.insert(0, __file__.rsplit("/", 1)[0] if "/" in __file__ else ".")

COINS = ["IOTA", "WAVES", "CRO", "ONG", "SC", "POLYX", "NEAR", "WAXP", "BCH", "CVC",
         "POWR", "T", "ANKR", "DKA", "GLM", "HIVE", "PUNDIX", "ELF", "BLAST", "JUP",
         "G", "INJ"]


def _iso(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def _exchange_bars(pair, t_from, t_to):
    """거래소가 가진 그 구간의 H1 봉 시각 집합. 실패는 None 으로 구분한다."""
    url = ("https://api.upbit.com/v1/candles/minutes/60?market=%s&count=200&to=%s"
           % (urllib.parse.quote(pair), t_to.strftime("%Y-%m-%dT%H:%M:%SZ")))
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=15) as r:
            arr = json.loads(r.read().decode("utf-8"))
    except Exception as e:
        print("   x %s 거래소 조회 실패: %s" % (pair, e))
        return None
    out = set()
    for c in arr:
        t = _iso(c["candle_date_time_utc"] + "Z") if "Z" not in c["candle_date_time_utc"] \
            else _iso(c["candle_date_time_utc"])
        t = t.replace(tzinfo=timezone.utc)
        if t_from <= t < t_to:
            out.add(t)
    return out


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = argv[1]
    t_to = _iso(_arg(argv, "--to") or datetime.now(timezone.utc)
                .replace(minute=0, second=0, microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ"))
    t_from = _iso(_arg(argv, "--from") or (t_to - timedelta(hours=72))
                  .strftime("%Y-%m-%dT%H:%M:%SZ"))
    hours = int((t_to - t_from).total_seconds() // 3600)

    db = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or "|" not in line:
                continue
            pair, ts = line.split("|", 1)
            t = _iso(ts.strip())
            if t.tzinfo is None:
                t = t.replace(tzinfo=timezone.utc)
            if t_from <= t < t_to:
                db.setdefault(pair.strip(), set()).add(t)

    print("구간 %s ~ %s (%d시간)\n" % (t_from.isoformat(), t_to.isoformat(), hours))
    print("%-12s %8s %8s %8s %8s %s"
          % ("코인", "거래소", "DB", "결손", "여분", "판정"))
    print("-" * 78)
    bad, unknown = [], []
    detail = {}
    for c in COINS:
        pair = "KRW-" + c
        ex = _exchange_bars(pair, t_from, t_to)
        time.sleep(0.12)
        if ex is None:
            print("%-12s %8s %8s %8s %8s %s"
                  % (pair, "-", len(db.get(pair, ())), "-", "-", "⚪ 판정 불가"))
            unknown.append(pair)
            continue
        ours = db.get(pair, set())
        missing = sorted(ex - ours)      # 🔴 거래소에 있는데 우리가 없다 = 적재 결손
        extra = sorted(ours - ex)        # 📌 우리만 있다 — 봉이 사라지는 일은 없어야 한다
        verdict = "🟢" if not missing and not extra else (
            "🔴 결손 %d봉" % len(missing) if missing else "📌 여분 %d봉" % len(extra))
        if missing:
            bad.append(pair)
            detail[pair] = [t.strftime("%m-%dT%H") for t in missing[:12]]
        print("%-12s %8d %8d %8d %8d %s"
              % (pair, len(ex), len(ours), len(missing), len(extra), verdict))

    print("-" * 78)
    print("🔴 결손 있는 코인 %d / ⚪ 판정 불가 %d / 전체 %d" % (len(bad), len(unknown), len(COINS)))
    for pair, ts in detail.items():
        print("   %s 결손 봉: %s" % (pair, " ".join(ts)))
    if not bad and not unknown:
        print("\n🟢 **%d시간 구간에서 거래소 봉 전부가 DB 에 있다** — 적재 결손 0." % hours)
        print("   ⚠️ 이것은 적시성을 말하지 않는다. 같은 구간 `freshness_log.jsonl` 의 매 회차가")
        print("      1봉 이상 뒤처지지 않았음을 함께 확인해야 한다.")
    return 0 if not (bad or unknown) else 1


def _arg(argv, name):
    if name in argv:
        i = argv.index(name)
        if i + 1 < len(argv):
            return argv[i + 1]
    return None


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
