"""ab_create v4.2.4 승인 날짜·C·T0 관문 시험 — 소켓·DB·토큰 없음(Client·MetaDB 를 대역으로 바꾸고 시각을 주입).
총괄 v1.181 §4 범위만: 정상·다음C·다음날·요청 도중 이탈 + 승인값 입력 음성. 같은 입력을 v4.2(13a82299 원문)에 넣은 대조도 남긴다.
사용: python -X utf8 test/test_v424_window.py <v4.2 ab_create.py 경로>   (끝 줄 "통과 N/N", 실패가 있으면 종료 1)
"""
import importlib.util
import json
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEW = HERE.parent / "create" / "ab_create.py"
OLD = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else None
PROTOCOL = HERE.parent / "tool" / "protocol.json"
KST = timezone(timedelta(hours=9))


def load(path: Path, name: str):
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    sys.path.pop(0)
    return m


class World:
    """대역이 공유하는 상태: POST 기록·만든 행."""
    def __init__(self, mod, times):
        self.mod, self.times, self.posts, self.rows, self.meta_calls = mod, list(times), [], [], 0
        self.next_id = 1001

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
            started = (w.last + timedelta(seconds=1, microseconds=123456)).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
            w.posts.append(sid)
            d = {"id": sid, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
                 "initialCapital": body["initialCapital"], "strategyParams": body["strategyParams"], "status": "RUNNING",
                 "startedAt": started}
            w.rows.append({"id": sid, "status": "RUNNING", "strategyName": body["strategyType"], "coinPair": body["coinPair"],
                           "timeframe": body["timeframe"], "initialCapital": body["initialCapital"],
                           "strategyParams": body["strategyParams"], "startedAt": started})
            return 200, {"success": True, "data": d}, None

    class FakeMeta:
        def __init__(self, db_cmd):
            pass

        def preflight(self):
            w.meta_calls += 1
            return {"rows": [], "max_id": 1000, "running_total": 10, "sha256": "0" * 64, "db_now": "2026-11-18T05:06:30Z"}, None

        def after(self, max_id):
            w.meta_calls += 1
            return {"rows": list(w.rows), "max_id": max_id, "running_total": 10 + len(w.rows), "sha256": "1" * 64,
                    "db_now": "2026-11-18T05:06:50Z"}, None

    mod.Client, mod.MetaDB = FakeClient, FakeMeta


def k(y, mo, d, h, mi, s=0):
    return datetime(y, mo, d, h, mi, s, tzinfo=KST)


APPROVED = ["--approved-date", "2026-11-18", "--approved-c", "2026-11-18T14:00:00+09:00", "--approved-t0", "2026-11-18T14:15:00+09:00"]


def run_case(mod, times, extra, approved=APPROVED, execute=True):
    w = World(mod, times)
    install_fakes(mod, w)
    tmp = Path(tempfile.mkdtemp(prefix="v424-"))
    tok = tmp / "tok"
    tok.write_text("not-a-real-token\n", encoding="utf-8")
    out = tmp / "out"
    argv = ["--base-url", "http://stand-in.invalid", "--token-file", str(tok), "--protocol", str(PROTOCOL), "--out-dir", str(out),
            "--code-sha", "0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d", "--db-cmd", '["psql-stand-in"]', "--db-source", "대역"]
    argv += approved + extra + (["--execute"] if execute else [])
    rc = mod.run(argv, clock=w.clock)
    j = out / "journal.jsonl"
    kinds = [json.loads(x)["kind"] for x in j.read_text(encoding="utf-8").splitlines()] if j.exists() else []
    draft = out / "creation_log.draft.json"
    d = json.loads(draft.read_text(encoding="utf-8")) if draft.exists() else None
    return rc, w, kinds, d, out


results = []


def check(name, cond, info=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + (f" — {info}" if info else ""))


new = load(NEW, "ab_create_v424")
arms8 = [k(2026, 11, 18, 14, 6, 31 + i) for i in range(8)]

# 정상
rc, w, kinds, d, _ = run_case(new, [k(2026, 11, 18, 14, 6, 30)] + arms8, [])
check("P1 정상 11-18 14:06:30·C14:00 → 0, POST 8", rc == 0 and len(w.posts) == 8, f"rc={rc} posts={len(w.posts)}")
check("P1a 초안 T0 = 승인 T0", d is not None and d["t0"] == "2026-11-18T14:15:00+09:00", d and d["t0"])
check("P1b 기록부 preflight 에 승인값", kinds[:1] == ["preflight"] and kinds.count("intent") == 8)

# 다음 C(같은 날 14:21:30 → 경계 14:15)
rc, w, kinds, d, _ = run_case(new, [k(2026, 11, 18, 14, 21, 30)] + arms8, [])
check("N1 다음C 14:21:30 → 2, POST 0, DB 0", rc == 2 and not w.posts and w.meta_calls == 0, f"rc={rc} posts={len(w.posts)} meta={w.meta_calls}")
# 다음 날 같은 시각
rc, w, kinds, d, _ = run_case(new, [k(2026, 11, 19, 14, 6, 30)] + arms8, [])
check("N2 다음날 11-19 14:06:30 → 2, POST 0, DB 0", rc == 2 and not w.posts and w.meta_calls == 0, f"rc={rc} posts={len(w.posts)} meta={w.meta_calls}")
# 승인 창 앞(14:05:59) — 같은 C 지만 창 밖
rc, w, kinds, d, _ = run_case(new, [k(2026, 11, 18, 14, 5, 59)] + arms8, [])
check("N2b 승인 C 의 창 앞 14:05:59 → 2, POST 0", rc == 2 and not w.posts)

# 도중 이탈: 3개 보낸 뒤 14:12:01
times = [k(2026, 11, 18, 14, 6, 30), k(2026, 11, 18, 14, 7), k(2026, 11, 18, 14, 8), k(2026, 11, 18, 14, 9), k(2026, 11, 18, 14, 12, 1)]
rc, w, kinds, d, out = run_case(new, times, [])
check("N3 도중 창 끝 이탈 → 3, POST 3 뒤 중단", rc == 3 and len(w.posts) == 3, f"rc={rc} posts={len(w.posts)}")
check("N3a 기존 기록 보존(intent·result 3, window_closed·reconcile)", kinds.count("intent") == 3 and kinds.count("result") == 3
      and "window_closed" in kinds and kinds[-1] == "reconcile", ",".join(kinds))
# 도중 이탈: 다음 날 같은 분(다른 C 의 창 안) — 경계가 승인 C 가 아니므로 중단
times = [k(2026, 11, 18, 14, 6, 30), k(2026, 11, 18, 14, 7), k(2026, 11, 19, 14, 7)]
rc, w, kinds, d, out = run_case(new, times, [])
check("N3b 도중 다음날 같은 분으로 이동 → 3, POST 1 뒤 중단", rc == 3 and len(w.posts) == 1, f"rc={rc} posts={len(w.posts)}")
# 첫 POST 직전 이탈(사전 DB 조회 중 시간이 지남)
times = [k(2026, 11, 18, 14, 11, 59), k(2026, 11, 18, 14, 12, 5)]
rc, w, kinds, d, out = run_case(new, times, [])
check("N3c 첫 POST 직전 창 지남 → POST 0, 3(기록 보존)", len(w.posts) == 0 and rc == 3 and "window_closed" in kinds, f"rc={rc}")

# 승인값 입력 음성 — 잠금·토큰·DB·POST 전에 2
bad_inputs = {
    "N4 빈 날짜": ["--approved-date", "", "--approved-c", "2026-11-18T14:00:00+09:00", "--approved-t0", "2026-11-18T14:15:00+09:00"],
    "N5 승인값 없음": [],
    "N6 T0 이동(14:30)": ["--approved-date", "2026-11-18", "--approved-c", "2026-11-18T14:00:00+09:00", "--approved-t0", "2026-11-18T14:30:00+09:00"],
    "N7 C 경계 아님(14:05)": ["--approved-date", "2026-11-18", "--approved-c", "2026-11-18T14:05:00+09:00", "--approved-t0", "2026-11-18T14:20:00+09:00"],
    "N8 날짜≠C 날짜": ["--approved-date", "2026-11-18", "--approved-c", "2026-11-19T14:00:00+09:00", "--approved-t0", "2026-11-19T14:15:00+09:00"],
    "N9 시간대 없음": ["--approved-date", "2026-11-18", "--approved-c", "2026-11-18T14:00:00", "--approved-t0", "2026-11-18T14:15:00"],
    "N10 날짜 형식": ["--approved-date", "18-11-2026", "--approved-c", "2026-11-18T14:00:00+09:00", "--approved-t0", "2026-11-18T14:15:00+09:00"],
}
for name, ap in bad_inputs.items():
    rc, w, kinds, d, out = run_case(new, [k(2026, 11, 18, 14, 6, 30)] + arms8, [], approved=ap)
    check(f"{name} → 2, POST 0·DB 0·기록부 없음", rc == 2 and not w.posts and w.meta_calls == 0 and not kinds and not (out / ".ab_create.lock").exists(),
          f"rc={rc}")
# UTC 로 적은 같은 승인값(05:00Z)도 같은 순간이면 받아들인다
ap_utc = ["--approved-date", "2026-11-18", "--approved-c", "2026-11-18T05:00:00+00:00", "--approved-t0", "2026-11-18T05:15:00+00:00"]
rc, w, kinds, d, _ = run_case(new, [k(2026, 11, 18, 14, 6, 30)] + arms8, [], approved=ap_utc)
check("P2 UTC 표기 같은 순간 → 0, POST 8", rc == 0 and len(w.posts) == 8, f"rc={rc}")
# 계획만 + 승인값 → 2, 창 표시
rc, w, kinds, d, out = run_case(new, [], [], execute=False)
check("P3 계획만+승인값 → 2(설계), POST 0", rc == 2 and not w.posts)

# 대조: 같은 다음C·다음날 입력을 v4.2 원문에 — 승인값 인자가 없어 시각만 넣는다
if OLD:
    old = load(OLD, "ab_create_v42")
    rc, w, *_ = run_case(old, [k(2026, 11, 18, 14, 21, 30)] + [k(2026, 11, 18, 14, 21, 31 + i) for i in range(8)], [], approved=[])
    check("C1 대조 v4.2: 다음C 14:21:30 → 0·POST 8(현행 허용 재현)", rc == 0 and len(w.posts) == 8, f"rc={rc} posts={len(w.posts)}")
    rc, w, *_ = run_case(old, [k(2026, 11, 19, 14, 6, 30)] + [k(2026, 11, 19, 14, 6, 31 + i) for i in range(8)], [], approved=[])
    check("C2 대조 v4.2: 다음날 11-19 14:06:30 → 0·POST 8(현행 허용 재현)", rc == 0 and len(w.posts) == 8, f"rc={rc} posts={len(w.posts)}")

n_ok = sum(1 for _, ok in results if ok)
print(f"통과 {n_ok}/{len(results)}")
sys.exit(0 if n_ok == len(results) else 1)
