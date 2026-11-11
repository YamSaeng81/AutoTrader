"""총괄 v1.149 §3 관측 C0·C1·C2·A1·A2 를 v2(커밋 5624bd05 블롭)와 v3(이 폴더)에 같은 입력으로 돌린다 — 전후 대조.

총괄 review.py 와 같은 목 클라이언트(Client 만 바꿈, 실제 HTTP·서버·DB·토큰 0, 가상 시각 2026-12-07 00:08Z)와 같은 변형식을 쓴다.
v2 는 git 블롭을 임시 폴더에 꺼내 import 한다(작업 사본·과거 근거 폴더는 바꾸지 않는다). 실행: 저장소 안에서 python -X utf8 overseer_cases_v2_v3.py
"""
import contextlib
import copy
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
NOW = datetime(2026, 12, 7, 0, 8, tzinfo=timezone.utc)
V2 = "docs/evidence/ab-create-v2-20261111/"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def v2_snapshot() -> Path:
    d = Path(tempfile.mkdtemp(prefix="v2snap-"))
    for rel in ("tool/ab_aggregate.py", "tool/test_ab_aggregate.py", "tool/protocol.json", "create/ab_create.py"):
        blob = subprocess.check_output(["git", "show", "5624bd05:" + V2 + rel], cwd=ROOT)
        (d / rel).parent.mkdir(parents=True, exist_ok=True)
        (d / rel).write_bytes(blob)
    return d


class FakeClient:
    last = None
    scenario = "ok"

    def __init__(self, *a, **kw):
        self.posts = 0
        self.gets = 0
        self.rows = []
        FakeClient.last = self

    def call(self, method, path, body=None):
        if method == "GET":
            self.gets += 1
            if self.gets == 2 and self.scenario == "reconcile_failure":
                return 503, {"success": False}, None
            return 200, {"success": True, "data": copy.deepcopy(self.rows)}, None
        self.posts += 1
        d = {"id": 1000 + self.posts, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
             "initialCapital": body["initialCapital"], "strategyParams": body["strategyParams"], "status": "RUNNING",
             "startedAt": NOW.isoformat()}
        self.rows.append(d)
        return 200, {"success": True, "data": d}, None


def run_create(cr, protocol: Path, scenario: str, prior=None) -> dict:
    out = Path(tempfile.mkdtemp(prefix="case-"))
    token = out / "fake-token.txt"
    token.write_text("LOCAL_REVIEW_FAKE", encoding="utf8")
    if prior:
        cr.append(out / "journal.jsonl", prior)
    FakeClient.scenario = scenario
    FakeClient.last = None
    args = ["--base-url", "http://invalid.invalid", "--token-file", str(token), "--protocol", str(protocol), "--out-dir", str(out),
            "--code-sha", "b" * 40, "--execute"]
    with patch.object(cr, "Client", FakeClient), contextlib.redirect_stdout(io.StringIO()):
        rc = cr.run(args, now=NOW)
    return {"rc": rc, "posts": 0 if FakeClient.last is None else FakeClient.last.posts}


def run_aggregate(ag, fx, mutate) -> dict:
    a = fx.arms()
    lg = fx.creation_log(a)
    mutate(a, lg)
    try:
        out = ag.aggregate(a, fx.positions(), fx.marks(), [], creation_log=lg)
        return {"result": "계산됨", "primary": out["primary_mean_B_minus_A"]}
    except ag.ConfigRejected as e:
        return {"result": "거절", "reason": str(e)}


def a1(a, lg):
    lg["db_verified"] = False


def a2(a, lg):
    for x in a:
        x["ruleset_hash"] = ""
    for x in lg["sessions"]:
        x["ruleset_hash"] = ""
    lg["db_verified"] = True


def cases(tag, base: Path) -> list[dict]:
    sys.path.insert(0, str(base / "tool"))
    try:
        ag = load(f"ab_aggregate", base / "tool" / "ab_aggregate.py")
        fx = load(f"test_ab_aggregate", base / "tool" / "test_ab_aggregate.py")
        cr = load(f"ab_create_{tag}", base / "create" / "ab_create.py")
        protocol = base / "tool" / "protocol.json"
        out = [
            {"case": "C0 대조", **run_create(cr, protocol, "ok")},
            {"case": "C1 사후 목록 503", **run_create(cr, protocol, "reconcile_failure")},
            {"case": "C2 결과 없는 의도", **run_create(cr, protocol, "ok", {"kind": "intent", "pair_arm": "P1A",
                                                                            "body_sha256": "unresolved-prior-send"})},
            {"case": "A1 db_verified=false", **run_aggregate(ag, fx, a1)},
            {"case": "A2 빈 지문(서로 같음)", **run_aggregate(ag, fx, a2)},
        ]
    finally:
        sys.path.remove(str(base / "tool"))
        for m in ("ab_aggregate", "test_ab_aggregate"):
            sys.modules.pop(m, None)
    return [{"version": tag, **x} for x in out]


if __name__ == "__main__":
    rows = cases("v2", v2_snapshot()) + cases("v3", HERE)
    print(json.dumps(rows, ensure_ascii=False, indent=2))
