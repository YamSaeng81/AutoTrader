"""11-19 제안값의 날짜·창·T0 좁은 시험(총괄 v1.188 §4) — 소켓·DB·토큰 없음. 생성기·SMA20 도구는 11-18 판과 같은 바이트다.
승인값은 손으로 다시 쓰지 않고 steps-20261119.sh 의 상수를 읽어 그대로 넣는다(파일 값과 시험 값이 갈라지지 않게).
사용: python -X utf8 test/test_20261119_dates.py   (끝 줄 "통과 N/N", 실패가 있으면 종료 1)
"""
import importlib.util
import json
import re
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
PROTOCOL = ROOT / "tool" / "protocol.json"
KST = timezone(timedelta(hours=9))


def load(path: Path, name: str):
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    sys.path.pop(0)
    return m


def steps_consts(path: Path) -> dict:
    c = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        mt = re.match(r"^(AB_DATE|AB_C|AB_T0|SMA20_WS|SMA20_WE|SMA20_T0)=(\S+)$", line)
        if mt:
            c[mt.group(1)] = mt.group(2)
    return c


NEWC = steps_consts(ROOT / "steps-20261119.sh")
OLDC = {"AB_DATE": "2026-11-18", "AB_C": "2026-11-18T14:00:00+09:00", "AB_T0": "2026-11-18T14:15:00+09:00"}   # 만료된 11-18 판


class World:
    def __init__(self, times):
        self.times, self.posts, self.rows, self.meta_calls, self.next_id, self.last = list(times), [], [], 0, 1001, None

    def clock(self):
        if not self.times:
            raise AssertionError("시각 목록이 모자람")
        self.last = self.times.pop(0)
        return self.last


def install_fakes(mod, w: World):
    class FakeClient:
        def __init__(self, base, token, timeout):
            pass

        def call(self, method, path, body=None):
            assert method == "POST" and path == mod.ENDPOINT
            sid = w.next_id
            w.next_id += 1
            started = (w.last + timedelta(seconds=1)).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
            w.posts.append(sid)
            row = {"id": sid, "status": "RUNNING", "strategyName": body["strategyType"], "coinPair": body["coinPair"],
                   "timeframe": body["timeframe"], "initialCapital": body["initialCapital"],
                   "strategyParams": body["strategyParams"], "startedAt": started}
            w.rows.append(row)
            return 200, {"success": True, "data": dict(row, strategyName=body["strategyType"])}, None

    class FakeMeta:
        def __init__(self, db_cmd):
            pass

        def preflight(self):
            w.meta_calls += 1
            return {"rows": [], "max_id": 1000, "running_total": 10, "sha256": "0" * 64,
                    "db_now": w.last.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}, None

        def after(self, max_id):
            w.meta_calls += 1
            return {"rows": list(w.rows), "max_id": max_id, "running_total": 10 + len(w.rows), "sha256": "1" * 64,
                    "db_now": w.last.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}, None

    mod.Client, mod.MetaDB = FakeClient, FakeMeta


def k(y, mo, d, h, mi, s=0):
    return datetime(y, mo, d, h, mi, s, tzinfo=KST)


def approved(c):
    return ["--approved-date", c["AB_DATE"], "--approved-c", c["AB_C"], "--approved-t0", c["AB_T0"]]


def run_ab(mod, start, appr):
    arms = [start + timedelta(seconds=1 + i) for i in range(8)]
    w = World([start] + arms)
    install_fakes(mod, w)
    tmp = Path(tempfile.mkdtemp(prefix="d1119-"))
    tok = tmp / "tok"
    tok.write_text("not-a-real-token\n", encoding="utf-8")
    out = tmp / "out"
    argv = ["--base-url", "http://stand-in.invalid", "--token-file", str(tok), "--protocol", str(PROTOCOL), "--out-dir", str(out),
            "--code-sha", "0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d", "--db-cmd", '["psql-stand-in"]', "--db-source", "대역",
            "--execute"] + appr
    rc = mod.run(argv, clock=w.clock)
    draft = out / "creation_log.draft.json"
    d = json.loads(draft.read_text(encoding="utf-8")) if draft.exists() else None
    return rc, w, d


results = []


def check(name, cond, info=""):
    results.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + (f" — {info}" if info else ""))


check("C0 steps-20261119.sh 상수 6개", NEWC == {"AB_DATE": "2026-11-19", "AB_C": "2026-11-19T14:00:00+09:00",
                                               "AB_T0": "2026-11-19T14:15:00+09:00", "SMA20_WS": "2026-11-19T15:00:00+09:00",
                                               "SMA20_WE": "2026-11-19T15:10:00+09:00", "SMA20_T0": "2026-11-20T09:00:00+09:00"}, NEWC)

ab = load(ROOT / "create" / "ab_create.py", "ab_create_1119")
rc, w, d = run_ab(ab, k(2026, 11, 19, 14, 6, 30), approved(NEWC))
check("A1 11-19 14:06:30·새 승인값 → 0, POST 8, 초안 T0=11-19 14:15", rc == 0 and len(w.posts) == 8 and d and d["t0"] == NEWC["AB_T0"],
      f"rc={rc} posts={len(w.posts)} t0={d and d['t0']}")
for name, start, appr in (
        ("A2 만료일 11-18 14:06:30·새 승인값 → 2", k(2026, 11, 18, 14, 6, 30), approved(NEWC)),
        ("A3 다음날 11-20 14:06:30·새 승인값 → 2", k(2026, 11, 20, 14, 6, 30), approved(NEWC)),
        ("A4 다음 C 11-19 14:21:30 → 2", k(2026, 11, 19, 14, 21, 30), approved(NEWC)),
        ("A5 창 앞 11-19 14:05:59 → 2", k(2026, 11, 19, 14, 5, 59), approved(NEWC)),
        ("A6 만료된 11-18 승인값을 11-19 14:06:30 에 재사용 → 2", k(2026, 11, 19, 14, 6, 30), approved(OLDC)),
        ("A7 T0≠C+15(14:30) → 2", k(2026, 11, 19, 14, 6, 30), approved(dict(NEWC, AB_T0="2026-11-19T14:30:00+09:00"))),
        ("A8 승인 날짜 11-20 ≠ C 날짜 → 2", k(2026, 11, 19, 14, 6, 30), approved(dict(NEWC, AB_DATE="2026-11-20")))):
    rc, w, d = run_ab(ab, start, appr)
    check(name + " · POST 0 · DB 0", rc == 2 and not w.posts and w.meta_calls == 0, f"rc={rc} posts={len(w.posts)} meta={w.meta_calls}")

sma = load(ROOT / "sma20" / "sma20_create.py", "sma20_create_1119")
P = lambda s: datetime.fromisoformat(s)
M30 = timedelta(minutes=30)
ws, we, t0 = P(NEWC["SMA20_WS"]), P(NEWC["SMA20_WE"]), P(NEWC["SMA20_T0"])
check("S1 SMA20 창 11-19 15:00~15:10 · T0 11-20 09:00 → 허용", sma.window_problem(ws, we, t0, M30) is None, sma.window_problem(ws, we, t0, M30))
for name, a, b, c in (
        ("S2 T0 를 11-19 09:00 으로(창보다 앞) → 거절", ws, we, P("2026-11-19T09:00:00+09:00")),
        ("S3 T0 를 11-21 09:00 으로 하루 연장 → 거절", ws, we, P("2026-11-21T09:00:00+09:00")),
        ("S4 만료된 11-18 15:00 창 + 새 T0 11-20 → 거절", P("2026-11-18T15:00:00+09:00"), P("2026-11-18T15:10:00+09:00"), t0),
        ("S5 창을 11-20 09:30~09:40 으로(09시대) → 거절", P("2026-11-20T09:30:00+09:00"), P("2026-11-20T09:40:00+09:00"), P("2026-11-21T09:00:00+09:00")),
        ("S6 창 끝이 T0-30분 뒤(11-20 08:40) → 거절", ws, P("2026-11-20T08:40:00+09:00"), t0)):
    pr = sma.window_problem(a, b, c, M30)
    check(name, pr is not None, pr)

n = len(results)
print(f"통과 {sum(results)}/{n}")
sys.exit(0 if all(results) else 1)
