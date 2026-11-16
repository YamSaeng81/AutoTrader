"""v4.1 시험 지원 — 메타 조회 SQL(meta_preflight.sql·meta_after.sql)의 출력 형식(psql --csv + @@ 구획)을 시험 상태에서 만든다.

실제 SQL 의 의미(522~587 행 제외·RUNNING 개수는 전체·max_id 는 전체 최댓값·사후는 max_id 뒤 새 행)를 그대로 흉내 낸다.
실제 SQL 자체는 격리 로컬 PG 에서 따로 확인한다(runs/meta-sql-pg). 외부 연결 없음.
"""
from __future__ import annotations

import csv
import io
import json

import ab_create

PREREG = range(522, 588)


def _db_ts(s: str | None) -> str:
    """POST 원문 → DB 저장 표기(마이크로초 반올림, psql UTC '+00' 표기)."""
    if not s:
        return ""
    ns = ab_create.round_to_micros(ab_create.ts_ns(s))
    from datetime import datetime, timezone
    sec, rem = divmod(ns, 1_000_000_000)
    d = datetime.fromtimestamp(sec, tz=timezone.utc)
    us = rem // 1000
    return d.strftime("%Y-%m-%d %H:%M:%S") + (f".{us:06d}".rstrip("0") if us else "") + "+00"


def meta_text(rows: list[dict], which: str, max_id: int | None = None, db_now: str = "2026-12-07 00:08:00.123456+00") -> str:
    """rows: 상태 목록({id,status,strategyName,coinPair,timeframe,initialCapital,strategyParams,startedAt}). 522~587 포함 가능."""
    running_total = sum(1 for r in rows if r.get("status") == "RUNNING")
    mx = max((r["id"] for r in rows), default=0)
    if which == "preflight":
        sel = [r for r in rows if r.get("status") == "RUNNING" and r["id"] not in PREREG
               and isinstance(r.get("strategyParams"), dict) and "paperExecutionMode" in r["strategyParams"]]
    else:
        sel = [r for r in rows if r["id"] > int(max_id) and r["id"] not in PREREG]
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(ab_create.META_ROW_KEYS)
    for r in sorted(sel, key=lambda x: x["id"]):
        cap = r.get("initialCapital")
        w.writerow([r["id"], r["status"], r["strategyName"], r["coinPair"], r["timeframe"],
                    "" if cap is None else f"{float(cap):.8f}",
                    "" if r.get("strategyParams") is None else json.dumps(r["strategyParams"]),
                    r.get("stored_started_at") or _db_ts(r.get("startedAt"))])
    body = buf.getvalue() if sel else ",".join(ab_create.META_ROW_KEYS) + "\n"
    return (f"@@db_now\ndb_now\n{db_now}\n@@counts\nrunning_total,max_id\n{running_total},{mx}\n@@rows\n" + body + "@@end\n")


class FakeMetaDB:
    """ab_create.MetaDB 대역. rows_fn() 이 현재 상태 행을 준다. hook(n, which, max_id) 가 (rc, text) 를 주면 그 값을 쓴다."""
    calls: list = []
    hook = None
    rows_fn = None

    def __init__(self, db_cmd):
        self.db_cmd = db_cmd

    def _go(self, which, max_id=None):
        FakeMetaDB.calls.append((which, max_id))
        n = len(FakeMetaDB.calls)
        if FakeMetaDB.hook is not None:
            r = FakeMetaDB.hook(n, which, max_id)
            if r is not None:
                return ab_create.parse_meta(*r)
        return ab_create.parse_meta(0, meta_text(FakeMetaDB.rows_fn(), which, max_id))

    def preflight(self):
        return self._go("preflight")

    def after(self, max_id):
        return self._go("after", max_id)

    @classmethod
    def reset(cls, rows_fn):
        cls.calls, cls.hook, cls.rows_fn = [], None, rows_fn
