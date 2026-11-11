"""v1.150 §4 — A/B 생성·확인·완성·추출·집계를 실제 로컬 서버(HTTP)와 실제 psql 로 잇는 구동기 (2026-11-11).

AbChainPgTest(Java, 격리 로컬 PG + 임의 포트의 실제 Spring 서버)가 단계마다 이 스크립트를 부른다. 도구는 ab-create-v3-20261111 의 커밋 원문을
그대로 import 한다(바꾸지 않음). 운영 서버·운영 DB·실제 토큰 없음 — 토큰은 시험 프로필의 로컬 값(test-api-token)이다.

단계:
  create  --port P --out O --code-sha SHA        ab_create.run(…, clock=…) — 실제 HTTP POST 8회. 시계: 실제 시각이 창 [C+6, C+12] 안이면 실제 시각
                                                  그대로, C+12 를 지난 13·14분이면 실제 시각에서 (분−12)만큼 뺀 흐르는 시계(서버 started_at ≥ 시작 시각,
                                                  < T0 = C+15 유지). C+6 전이면 보내지 않고 종료 2(실제 창을 기다려 다시 실행).
                                                  (1회차는 C+7 고정 시계 — 서버 실제 started_at 이 시작 시각보다 앞서 사후 목록 대조 0건, 종료 3. 구동기 결함.)
  check   --out O --tag NAME                     post_creation_check.sql 을 컨테이너 psql(--csv, ON_ERROR_STOP)로 끝까지 실행 → NAME.db_check.out,
                                                  ab_complete.main 으로 NAME.creation_log.json. 종료 코드 = 완성기 종료 코드.
  extract --out O --tag NAME                     extract_positions.sql → NAME.positions.csv, 완성본으로 arms.csv·marks.csv 를 만들고 집계기 실행
                                                  → NAME.aggregate.json(거절이면 NAME.aggregate.rejected.txt, 종료 3).
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
V3 = HERE.parents[1] / "ab-create-v3-20261111"
sys.path.insert(0, str(V3 / "create"))
sys.path.insert(0, str(V3 / "tool"))

import ab_aggregate as ag   # noqa: E402
import ab_complete as cp    # noqa: E402
import ab_create            # noqa: E402

PROTOCOL = V3 / "tool" / "protocol.json"
CONTAINER = "v150-pg"
DB = None   # --db 로 받는다(3회차: "ab_chain" 고정이라 새 DB ab_chain2 가 아닌 이전 DB 를 읽음 — 구동기 결함)


def psql(sql_file: Path, variables: dict[str, str]) -> tuple[int, str, str]:
    assert DB, "--db 필요"
    cmd = ["docker", "exec", "-i", CONTAINER, "psql", "-U", "trader", "-d", DB, "-X", "-q", "--csv", "-v", "ON_ERROR_STOP=1"]
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


def draft(a) -> dict:
    return json.loads((Path(a.out) / "create" / "creation_log.draft.json").read_text(encoding="utf-8"))


def stage_check(a) -> int:
    d = draft(a)
    ids = ",".join(s["session_id"] for s in d["sessions"])
    rc, out, err = psql(V3 / "create" / "post_creation_check.sql", {"ids": ids, "t0": d["t0"]})
    o = Path(a.out)
    (o / f"{a.tag}.db_check.out").write_text(out, encoding="utf-8")
    (o / f"{a.tag}.psql.stderr.txt").write_text(err, encoding="utf-8")
    if rc != 0:
        print(json.dumps({"stage": "check", "tag": a.tag, "psql_rc": rc, "stderr": err}, ensure_ascii=False))
        return 10
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        crc = cp.main(["--draft", str(o / "create" / "creation_log.draft.json"), "--db-output", str(o / f"{a.tag}.db_check.out"),
                       "--protocol", str(PROTOCOL), "--out", str(o / f"{a.tag}.creation_log.json")])
    (o / f"{a.tag}.complete.txt").write_text(buf.getvalue(), encoding="utf-8")
    print(json.dumps({"stage": "check", "tag": a.tag, "psql_rc": rc, "complete_rc": crc, "complete_out": buf.getvalue().strip()},
                     ensure_ascii=False))
    return crc


def stage_extract(a) -> int:
    o = Path(a.out)
    log = json.loads((o / "ok.creation_log.json").read_text(encoding="utf-8"))
    ids = ",".join(s["session_id"] for s in log["sessions"])
    rc, out, err = psql(V3 / "create" / "extract_positions.sql", {"ids": ids})
    (o / f"{a.tag}.positions.csv").write_text(out, encoding="utf-8")
    if rc != 0:
        print(json.dumps({"stage": "extract", "psql_rc": rc, "stderr": err}, ensure_ascii=False))
        return 10
    t0 = cp._ts(log["t0"])
    end = t0 + timedelta(days=42)
    protocol = json.loads(PROTOCOL.read_text(encoding="utf-8"))
    arms = []
    for s in log["sessions"]:
        st = s["stored"]
        row = {k: st[k] for k in ag.STORED_KEYS}
        # 완성본의 params_json 은 객체다 — arms.csv 는 JSON 문자열(test_chain.arms_from_log 과 같은 변환). 2회차: 이 변환이 없어 집계기 TypeError.
        row["params_json"] = json.dumps(row["params_json"]) if isinstance(row["params_json"], dict) else row["params_json"]
        arms.append({"pair_id": s["pair_id"], "arm": s["arm"], "session_id": s["session_id"], **row,
                     "ruleset_hash": s["ruleset_hash"], "code_sha": log["code_sha"], "start_ts": t0.isoformat(), "end_ts": end.isoformat()})
    marks = [{"coin": coin, "ts": (t0 + timedelta(days=i)).isoformat(), "price": price}
             for coin, price in (("KRW-BTC", "100"), ("KRW-ETH", "100")) for i in range(43)]
    with open(o / "arms.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(arms[0].keys()))
        w.writeheader()
        w.writerows(arms)
    with open(o / "marks.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["coin", "ts", "price"])
        w.writeheader()
        w.writerows(marks)
    positions = list(csv.DictReader(io.StringIO(out)))
    try:
        res = ag.aggregate(arms, positions, marks, [], creation_log=log)
        (o / f"{a.tag}.aggregate.json").write_text(json.dumps(res, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
        print(json.dumps({"stage": "extract", "tag": a.tag, "rows": len(positions), "statuses": sorted({p["status"] for p in positions}),
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
    a = ap.parse_args(argv)
    global DB
    DB = a.db
    return {"create": stage_create, "check": stage_check, "extract": stage_extract}[a.stage](a)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
