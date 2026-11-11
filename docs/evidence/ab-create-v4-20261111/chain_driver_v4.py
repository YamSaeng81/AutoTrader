"""v1.152 A·C — A/B 생성(v4)·확인·완성(v4)·arms.csv 변환(ab_arms)·추출·집계를 실제 로컬 서버(HTTP)와 실제 psql 로 잇는 구동기 (2026-11-11).

v1.150 pgchain/chain_driver.py(원문 보존)에서 바꾼 점:
  - 생성기·완성기는 ab-create-v4-20261111/create 의 v4, arms.csv 는 tool/ab_arms.py 로만 만든다(구동기 안의 손 변환 제거).
    집계기는 ab-create-v3-20261111/tool/ab_aggregate.py 원문 그대로.
  - check 에 --db-source(연결 출처 기록)를 넘기고, --psql-db 로 확인 SQL 을 **다른 실제 DB** 에 돌리는 음성 경로를 둔다(다른 DB 의 같은 ID).
  - 컨테이너는 v152-pg.
AbChainV4PgTest(Java, 격리 로컬 PG + 임의 포트의 실제 Spring 서버)가 단계마다 부른다. 운영 서버·운영 DB·실제 토큰 없음(시험 프로필 로컬 값).

단계:
  create  --port P --code-sha SHA     ab_create v4 — 실제 HTTP POST 8회(시계 규칙은 v1.150 구동기와 같음)
  check   --tag NAME [--psql-db DB]   post_creation_check.sql 을 컨테이너 psql 로 끝까지 → NAME.db_check.out → ab_complete v4 → NAME.creation_log.json
  extract --tag NAME                  extract_positions.sql → NAME.positions.csv, ab_arms.py(ok 완성본 + ok DB 출력) → arms.csv(+meta), 집계 → NAME.aggregate.json
"""
from __future__ import annotations

import argparse
import contextlib
import csv
import io
import json
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
V3 = HERE.parent / "ab-create-v3-20261111"
sys.path.insert(0, str(HERE / "create"))
sys.path.insert(0, str(HERE / "tool"))
sys.path.insert(0, str(V3 / "tool"))

import ab_aggregate as ag   # noqa: E402  (v3 원문)
import ab_arms              # noqa: E402  (v4)
import ab_complete as cp    # noqa: E402  (v4)
import ab_create            # noqa: E402  (v4)

PROTOCOL = HERE / "tool" / "protocol.json"
CONTAINER = "v152-pg"
DB = None


def psql(sql_file: Path, variables: dict[str, str], db: str) -> tuple[int, str, str]:
    cmd = ["docker", "exec", "-i", CONTAINER, "psql", "-U", "trader", "-d", db, "-X", "-q", "--csv", "-v", "ON_ERROR_STOP=1"]
    for k, v in variables.items():
        cmd += ["-v", f"{k}={v}"]
    cmd += ["-f", "-"]
    p = subprocess.run(cmd, input=sql_file.read_bytes(), capture_output=True)
    return p.returncode, p.stdout.decode("utf-8"), p.stderr.decode("utf-8")


def stage_create(a) -> int:
    real = datetime.now(timezone.utc)
    c = real.replace(minute=(real.minute // 15) * 15, second=0, microsecond=0)
    r = real - c
    if r < timedelta(minutes=6):
        print(json.dumps({"stage": "create", "rc": 2, "stop": "실제 시각이 C+6 전 — 보내지 않음, 창에서 다시 실행", "real_now": real.isoformat()},
                         ensure_ascii=False))
        return 2
    shift = max(timedelta(0), r - timedelta(minutes=12))
    clock = lambda: datetime.now(timezone.utc) - shift   # noqa: E731
    token = Path(a.out) / "local-test-token.txt"
    token.write_text("test-api-token", encoding="utf-8")
    args = ["--base-url", f"http://127.0.0.1:{a.port}", "--token-file", str(token), "--protocol", str(PROTOCOL),
            "--out-dir", str(Path(a.out) / "create"), "--code-sha", a.code_sha, "--execute"]
    Path(a.out, "create").mkdir(parents=True, exist_ok=True)
    rc = ab_create.run(args, clock=clock)
    print(json.dumps({"stage": "create", "rc": rc, "real_now": real.isoformat(), "clock_shift_s": shift.total_seconds()}, ensure_ascii=False))
    return rc


def stage_check(a) -> int:
    o = Path(a.out)
    d = json.loads((o / "create" / "creation_log.draft.json").read_text(encoding="utf-8"))
    ids = ",".join(s["session_id"] for s in d["sessions"])
    db = a.psql_db or DB
    rc, out, err = psql(HERE / "create" / "post_creation_check.sql", {"ids": ids, "t0": d["t0"]}, db)
    (o / f"{a.tag}.db_check.out").write_bytes(out.encode("utf-8"))
    (o / f"{a.tag}.psql.stderr.txt").write_text(err, encoding="utf-8")
    if rc != 0:
        print(json.dumps({"stage": "check", "tag": a.tag, "psql_rc": rc, "stderr": err}, ensure_ascii=False))
        return 10
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        crc = cp.main(["--draft", str(o / "create" / "creation_log.draft.json"), "--db-output", str(o / f"{a.tag}.db_check.out"),
                       "--protocol", str(PROTOCOL), "--db-source", f"로컬 격리 컨테이너 {CONTAINER} / DB {db} (docker exec psql)",
                       "--out", str(o / f"{a.tag}.creation_log.json")])
    (o / f"{a.tag}.complete.txt").write_text(buf.getvalue(), encoding="utf-8")
    print(json.dumps({"stage": "check", "tag": a.tag, "psql_db": db, "psql_rc": rc, "complete_rc": crc, "complete_out": buf.getvalue().strip()},
                     ensure_ascii=False))
    return crc


def stage_extract(a) -> int:
    o = Path(a.out)
    log = json.loads((o / "ok.creation_log.json").read_text(encoding="utf-8"))
    ids = ",".join(s["session_id"] for s in log["sessions"])
    rc, out, err = psql(HERE / "create" / "extract_positions.sql", {"ids": ids}, DB)
    (o / f"{a.tag}.positions.csv").write_bytes(out.encode("utf-8"))
    if rc != 0:
        print(json.dumps({"stage": "extract", "psql_rc": rc, "stderr": err}, ensure_ascii=False))
        return 10
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        arc = ab_arms.main(["--complete", str(o / "ok.creation_log.json"), "--protocol", str(PROTOCOL),
                            "--db-output", str(o / "ok.db_check.out"), "--out", str(o / "arms.csv")])
    (o / "arms.txt").write_text(buf.getvalue(), encoding="utf-8")
    if arc != 0:
        print(json.dumps({"stage": "extract", "arms_rc": arc, "arms_out": buf.getvalue().strip()}, ensure_ascii=False))
        return 3
    t0 = cp._ts(log["t0"])
    marks = [{"coin": coin, "ts": (t0 + timedelta(days=i)).isoformat(), "price": price}
             for coin, price in (("KRW-BTC", "100"), ("KRW-ETH", "100")) for i in range(43)]
    with open(o / "marks.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["coin", "ts", "price"])
        w.writeheader()
        w.writerows(marks)
    arms = ag.read(o / "arms.csv")
    positions = list(csv.DictReader(io.StringIO(out)))
    try:
        res = ag.aggregate(arms, positions, marks, [], creation_log=log, protocol_path=PROTOCOL)
        (o / f"{a.tag}.aggregate.json").write_text(json.dumps(res, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
        print(json.dumps({"stage": "extract", "tag": a.tag, "arms": buf.getvalue().strip(), "rows": len(positions),
                          "statuses": sorted({p["status"] for p in positions}),
                          "primary": res["primary_mean_B_minus_A"], "primary_status": res["primary_status"]}, ensure_ascii=False))
        return 0
    except ag.ConfigRejected as e:
        (o / f"{a.tag}.aggregate.rejected.txt").write_text(str(e), encoding="utf-8")
        print(json.dumps({"stage": "extract", "tag": a.tag, "rejected": str(e)}, ensure_ascii=False))
        return 3


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["create", "check", "extract"])
    ap.add_argument("--out", required=True)
    ap.add_argument("--port")
    ap.add_argument("--code-sha")
    ap.add_argument("--tag", default="ok")
    ap.add_argument("--db", required=True)
    ap.add_argument("--psql-db")
    a = ap.parse_args(argv)
    global DB
    DB = a.db
    return {"create": stage_create, "check": stage_check, "extract": stage_extract}[a.stage](a)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
