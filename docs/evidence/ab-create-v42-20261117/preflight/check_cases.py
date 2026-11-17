"""preflight_v2.sql 격리 PG 사례 출력 판정. 사례별 기대는 사례를 설계할 때(run_cases.sh 작성 때) 정했으나, 이 판정 파일 자체는 attempt1·normal 출력을 본 뒤 작성했다. 기대와 다르면 실패로 남긴다."""
import csv, io, json, sys
from pathlib import Path

OUT = Path(__file__).resolve().parent / "runs-pg"
N = {"KRW-BTC/H1": "normal", "KRW-BTC/M15": "normal", "KRW-ETH/M15": "normal"}


def parse(name):
    sec, cur = {}, None
    for line in (OUT / f"{name}.out").read_text(encoding="utf-8").splitlines():
        if line.startswith("@@"):
            cur = line[2:]
            sec[cur] = []
        elif cur and line.strip():
            sec[cur].append(line)
    assert "end" in sec, "끝 표지 없음"
    return {k: list(csv.DictReader(io.StringIO("\n".join(v)))) for k, v in sec.items() if k != "end"}


def status(t):
    return {f"{r['coin_pair']}/{r['timeframe']}": r["status"] for r in t["candles"]}


def cand(t, key):
    return next(r for r in t["candles"] if f"{r['coin_pair']}/{r['timeframe']}" == key)


EXPECT = {
    "normal": lambda t: status(t) == N and all(r["enabled"] == "t" for r in t["enablement"]) and t["sessions"][0]["cap_ok"] == "t"
              and [r["id"] for r in t["dup_candidates"]] == ["588"] and cand(t, "KRW-BTC/H1")["b3_daily"] == "20",
    "missing_gap3": lambda t: status(t) == dict(N, **{"KRW-BTC/H1": "missing"}) and cand(t, "KRW-BTC/H1")["missing"] == "3" and cand(t, "KRW-BTC/H1")["b1_ok"] == "t",
    "boundary_gap2": lambda t: status(t) == N and cand(t, "KRW-BTC/H1")["missing"] == "2",
    "missing_b3": lambda t: status(t) == dict(N, **{"KRW-BTC/H1": "missing"}) and cand(t, "KRW-BTC/H1")["b3_daily"] == "19" and cand(t, "KRW-BTC/H1")["missing"] == "1",
    "missing_history": lambda t: status(t) == dict(N, **{"KRW-ETH/M15": "missing"}) and cand(t, "KRW-ETH/M15")["n_window"] == "90" and cand(t, "KRW-ETH/M15")["missing"] == "0",
    "stale_btc_m15": lambda t: status(t) == dict(N, **{"KRW-BTC/M15": "stale"}) and cand(t, "KRW-BTC/M15")["b1_ok"] == "f",
    "grace_h1_0501_59": lambda t: status(t) == N and cand(t, "KRW-BTC/H1")["last_closed"].startswith("2026-11-17 03:00"),
    "stale_h1_0502_00": lambda t: status(t) == dict(N, **{"KRW-BTC/H1": "stale"}) and cand(t, "KRW-BTC/H1")["need_latest_closed"].startswith("2026-11-17 04:00"),
    "none_eth_m15": lambda t: status(t) == dict(N, **{"KRW-ETH/M15": "none"}),
    "enablement": lambda t: {r["strategy_name"]: r["enabled"] for r in t["enablement"]} == {"COMPOSITE_MEANREV_BB": "f", "COMPOSITE_MTF_CONFIRMED": "t", "DAILY_SMA20": "f"},
    "cap_dup": lambda t: t["sessions"][0]["running_total"] == "111" and t["sessions"][0]["cap_ok"] == "t" and [r["id"] for r in t["dup_candidates"]] == ["588", "9001"],
    "cap_over_n10": lambda t: t["sessions"][0]["cap_ok"] == "f",
}
res = {}
for name, f in EXPECT.items():
    rc = (OUT / f"{name}.rc").read_text().strip()
    try:
        ok = rc == "0" and f(parse(name))
    except Exception as e:   # 형식 이상도 실패
        ok = False
        rc += f" ({type(e).__name__})"
    res[name] = {"rc": rc, "ok": bool(ok)}
print(json.dumps(res, ensure_ascii=False, indent=1))
print(f"{sum(v['ok'] for v in res.values())}/{len(res)}")
sys.exit(0 if all(v["ok"] for v in res.values()) else 1)
