"""start_66.py freshness 의 업비트 요청 간격 시험 (2026-10-23) — 로컬에서만, 네트워크·운영 API 를 쓰지 않는다.
실행: python scripts/prospective/test_freshness_spacing.py

리뷰 2026-10-23: 1차 조회만 1초로 바뀌고 **재조회 경로는 0.5초**로 남아 있었다. 거래소 호출을 가짜 시계 위에서 기록해
1차·재조회를 이어서 **연속한 두 업비트 요청 사이가 모두 1초 이상**인지 본다. 재조회 경로가 실제로 탔는지도 함께 확인한다.
🔴 범위: 이 스크립트가 보내는 요청 간격뿐이다 — 서버가 받는 시각·앱과의 합산은 UpbitRequestLimiterTest·정기 점검 429 감시가 본다.
"""
import json
import os
import shutil
import sys
import tempfile
import types
from datetime import datetime, timedelta, timezone

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import start_66 as s66  # noqa: E402

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)))


T0 = datetime(2026, 10, 24, 4, 19, 1, tzinfo=timezone.utc)   # cron :19 시작
EX_TO = datetime(2026, 10, 24, 4, 0, tzinfo=timezone.utc)
BEHIND = ["KRW-" + c for c in s66.COINS[:4]]                  # 1차에서 뒤처진 4종 → 재조회


def run(behind_net=False):
    """가짜 시계로 cmd_freshness 를 한 번 돌리고 (업비트 호출 시각들, 단계 표시, JSON 한 줄) 을 돌려준다."""
    clock = {"t": 0.0}
    calls = []          # (가짜 시각, 'first'|'recheck', pair)
    phase = {"p": "first"}

    def fake_sleep(sec):
        if sec >= 60:   # 70초 대기 = 재조회 단계로 넘어간다
            phase["p"] = "recheck"
        clock["t"] += sec

    class FakeDT(datetime):
        @classmethod
        def now(cls, tz=None):
            return T0 + timedelta(seconds=clock["t"])

    def fake_upbit(pair):
        calls.append((clock["t"], phase["p"], pair))
        clock["t"] += 0.05          # 응답 지연
        if behind_net and pair in BEHIND and phase["p"] == "first":
            return None, "net"
        return EX_TO, None

    def fake_db():
        lag = phase["p"] == "first"
        return {"KRW-" + c: {"coinPair": "KRW-" + c, "timeframe": "H1", "count": 500,
                             "to": ((EX_TO - timedelta(hours=1)) if (lag and "KRW-" + c in BEHIND) else EX_TO).isoformat()}
                for c in s66.COINS}

    work = tempfile.mkdtemp()
    saved = {k: getattr(s66, k) for k in ("time", "datetime", "need_env", "_db_latest", "_upbit_latest_h1",
                                          "FRESH_LOG", "STREAK", "FRESH")}
    try:
        s66.time = types.SimpleNamespace(sleep=fake_sleep)
        s66.datetime = FakeDT
        s66.need_env = lambda: None
        s66._db_latest = fake_db
        s66._upbit_latest_h1 = fake_upbit
        s66.FRESH_LOG = os.path.join(work, "freshness_log.jsonl")
        s66.STREAK = os.path.join(work, "freshness_streak.json")
        s66.FRESH = os.path.join(work, "freshness_snap.json")
        rc = s66.cmd_freshness(save=False)
        row = json.loads(open(s66.FRESH_LOG, encoding="utf-8").read().splitlines()[-1])
    finally:
        for k, v in saved.items():
            setattr(s66, k, v)
        shutil.rmtree(work, ignore_errors=True)
    return rc, calls, row


def gaps(calls):
    return [(b[0] - a[0], a, b) for a, b in zip(calls, calls[1:])]


import io, contextlib  # noqa: E402,E401
with contextlib.redirect_stdout(io.StringIO()):
    rc, calls, row = run()

first = [c for c in calls if c[1] == "first"]
re_ = [c for c in calls if c[1] == "recheck"]
check("재조회 경로를 탔다 — 1차 22건 + 재조회 %d건(뒤처진 4종)" % len(re_),
      len(first) == len(s66.COINS) and [c[2] for c in re_] == BEHIND, (len(first), [c[2] for c in re_]))
g_re = [g for g, a, b in gaps(re_)]
check("재조회 연속 요청 사이가 모두 1초 이상 (최소 %.2fs)" % min(g_re), min(g_re) >= 1.0, g_re)
g_all = [g for g, a, b in gaps(calls)]
check("1차·재조회 전체 연속 요청 사이가 모두 1초 이상 (최소 %.2fs)" % min(g_all), min(g_all) >= 1.0,
      [(round(g, 2), a[1], b[1]) for g, a, b in gaps(calls) if g < 1.0])
check("재조회 뒤 따라붙어 green 22 · 재확인 목록 4종", rc == 0 and row["green"] == 22 and row["rechecked"] == BEHIND, row)

# 리뷰 ④: JSON at 은 시작 시각이 아니라 그 회차의 관측(끝 무렵) 시각이다 — 재조회가 있으면 시작 + 22초 + 70초 + 재조회만큼 뒤
at = datetime.fromisoformat(row["at"])
check("JSON at 은 시작(:19:01) 이 아니라 재조회 뒤 관측 시각이다 (at %s, 시작 + %.0f초)" % (at.strftime("%H:%M:%S"), (at - T0).total_seconds()),
      (at - T0).total_seconds() > 70, row["at"])

with contextlib.redirect_stdout(io.StringIO()):
    rc2, calls2, row2 = run(behind_net=True)
g2 = [g for g, a, b in gaps([c for c in calls2 if c[1] == "recheck"])]
check("거래소 조회 실패(net)로 재조회한 경우도 1초 이상 (최소 %.2fs)" % min(g2),
      min(g2) >= 1.0 and row2["rechecked"] == BEHIND, (g2, row2["rechecked"]))

print("\nfreshness 간격 시험 %d/%d 통과" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
