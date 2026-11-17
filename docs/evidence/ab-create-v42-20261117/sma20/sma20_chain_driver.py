"""SMA20 단건 도구(sma20_create·sma20_complete)를 실제 로컬 Spring 서버(HTTP)·실제 psql(격리 v152-pg)로 잇는 구동기 (2026-11-17, 총괄 v1.178 §4-2).
Java 시험 Sma20ChainPgTest(시험 브랜치)가 단계마다 부른다. 운영 서버·운영 DB·실제 토큰 없음(시험 프로필 로컬 값 test-api-token).

단계:
  create  --port P --code-sha SHA [--sub NAME]   실제 시각으로 창 [지금−1분, 지금+5분]·T0(창 시작 뒤 첫 00:00 UTC)를 정해 POST 1회.
                                                 창 규칙(09:00~09:59 KST 아님·T0−30분 전 끝)에 안 맞으면 sma20_create 가 보내지 않는다(rc2).
  check   --tag T [--psql-db X]                  sma20_check.sql 을 psql 로 → T.db_check.out → sma20_complete → T.sma20_creation_log.json
  seed-other --other-db X                        생성된 행을 다른 격리 DB 에 같은 ID 로 복사하고 started_at 만 +1초(다른 DB 같은 ID 음성의 전제)
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import sma20_create as sc   # noqa: E402
import sma20_complete as cp   # noqa: E402

assert sc.ab.CREATOR_VERSION == "v4.2" and Path(sc.__file__).resolve().parent == HERE
CONTAINER = "v152-pg"
BODY = HERE / "body.json"


def psql(db: str, args: list[str], stdin: bytes | None = None) -> tuple[int, str, str]:
    p = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "trader", "-d", db, "-X", "-q", "-v", "ON_ERROR_STOP=1"] + args,
                       input=stdin, capture_output=True)
    return p.returncode, p.stdout.decode("utf-8"), p.stderr.decode("utf-8")


def stage_create(a) -> int:
    real = datetime.now(timezone.utc).replace(microsecond=0)
    ws, we = real - timedelta(minutes=1), real + timedelta(minutes=5)
    t0 = sc.first_t0_after(ws)
    o = Path(a.out) / (a.sub or "create")
    o.mkdir(parents=True, exist_ok=True)
    tok = Path(a.out) / "local-test-token.txt"
    tok.write_text("test-api-token", encoding="utf-8")
    args = ["--base-url", f"http://127.0.0.1:{a.port}", "--token-file", str(tok), "--body", str(BODY), "--out-dir", str(o),
            "--code-sha", a.code_sha, "--window-start", ws.isoformat(), "--window-end", we.isoformat(), "--t0", t0.isoformat(),
            "--db-cmd", json.dumps(["docker", "exec", "-i", CONTAINER, "psql", "-U", "trader", "-d", a.db]),
            "--db-source", f"로컬 격리 컨테이너 {CONTAINER} / DB {a.db} (docker exec psql, 생성기 메타 조회)", "--execute"]
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = sc.run(args)
    print(json.dumps({"stage": "create", "sub": a.sub or "create", "rc": rc, "real_now": real.isoformat(), "window": [ws.isoformat(), we.isoformat()],
                      "t0": t0.isoformat(), "tool_out": buf.getvalue().strip()}, ensure_ascii=False))
    return rc


def draft(a) -> dict:
    return json.loads((Path(a.out) / "create" / "sma20_creation.draft.json").read_text(encoding="utf-8"))


def stage_check(a) -> int:
    o = Path(a.out)
    d = draft(a)
    db = a.psql_db or a.db
    rc, out, err = psql(db, ["--csv", "-v", f"id={d['session_id']}", "-v", f"t0={d['t0']}", "-f", "-"], (HERE / "sma20_check.sql").read_bytes())
    (o / f"{a.tag}.db_check.out").write_bytes(out.encode("utf-8"))
    (o / f"{a.tag}.psql.stderr.txt").write_text(err, encoding="utf-8")
    if rc != 0:
        print(json.dumps({"stage": "check", "tag": a.tag, "psql_rc": rc, "stderr": err}, ensure_ascii=False))
        return 10
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        crc = cp.main(["--draft", str(o / "create" / "sma20_creation.draft.json"), "--body", str(BODY), "--db-output", str(o / f"{a.tag}.db_check.out"),
                       "--check-db-source", f"로컬 격리 컨테이너 {CONTAINER} / DB {db} (docker exec psql)", "--out", str(o / f"{a.tag}.sma20_creation_log.json")])
    (o / f"{a.tag}.complete.txt").write_text(buf.getvalue(), encoding="utf-8")
    print(json.dumps({"stage": "check", "tag": a.tag, "psql_db": db, "complete_rc": crc, "complete_out": buf.getvalue().strip()}, ensure_ascii=False))
    return crc


def stage_seed_other(a) -> int:
    sid = draft(a)["session_id"]
    rc1, rows, e1 = psql(a.db, ["-c", f"COPY (SELECT * FROM paper_trading.virtual_balance WHERE id = {int(sid)}) TO STDOUT"])
    if rc1 != 0 or len(rows.splitlines()) != 1:
        print(json.dumps({"stage": "seed-other", "rc": 10, "why": "원본 행 1개가 아니다", "stderr": e1}, ensure_ascii=False))
        return 10
    rc2, _, e2 = psql(a.other_db, ["-c", f"DELETE FROM paper_trading.virtual_balance WHERE id = {int(sid)}",
                                   "-c", "COPY paper_trading.virtual_balance FROM STDIN",
                                   "-c", f"UPDATE paper_trading.virtual_balance SET started_at = started_at + interval '1 second' WHERE id = {int(sid)}"],
                      rows.encode("utf-8"))
    print(json.dumps({"stage": "seed-other", "rc": 0 if rc2 == 0 else 10, "other_db": a.other_db, "id": sid, "stderr": e2}, ensure_ascii=False))
    return 0 if rc2 == 0 else 10


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["create", "check", "seed-other"])
    ap.add_argument("--out", required=True)
    ap.add_argument("--db", required=True)
    ap.add_argument("--port")
    ap.add_argument("--code-sha")
    ap.add_argument("--sub")
    ap.add_argument("--tag", default="ok")
    ap.add_argument("--psql-db")
    ap.add_argument("--other-db")
    a = ap.parse_args(argv)
    return {"create": stage_create, "check": stage_check, "seed-other": stage_seed_other}[a.stage](a)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
