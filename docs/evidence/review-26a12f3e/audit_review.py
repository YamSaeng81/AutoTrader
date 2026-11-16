"""428차 제출의 로컬 Git·보존 파일 대조. 서버·네트워크·제품 시험 실행 없음."""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SOURCE = "26a12f3ecb4254f003b15ea30d0cf9aecf1de925"
PRODUCT = "0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d"
checks = []


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], check=True, capture_output=True).stdout


def check(name, passed, detail=None):
    checks.append({"name": name, "ok": bool(passed), "detail": detail})


def digest(data):
    return hashlib.sha256(data).hexdigest()


def blob(path, revision=SOURCE):
    return git("show", f"{revision}:{path}")


check("HEAD", git("rev-parse", "HEAD").decode().strip() == SOURCE)
check("origin/main", git("rev-parse", "origin/main").decode().strip() == SOURCE)
changed = git("diff", "--name-only", "7fea1b89", SOURCE).decode().splitlines()
check("제출 변경은 docs 경로", all(path.startswith("docs/") for path in changed), len(changed))
folders = {"n05-final-0e1d2a02": 5, "screen-0e1d2a02": 37,
           "create-bundle-0e1d2a02": 6, "review-7fea1b89": 5}
for folder, expected in folders.items():
    prefix = f"docs/evidence/{folder}/"
    manifest = blob(prefix + "SHA256SUMS").decode("utf-8")
    entries = []
    for line in manifest.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        if match is None:
            raise ValueError(f"목록 형식 오류: {folder}")
        entries.append(match.groups())
    check(f"{folder} 목록 개수", len(entries) == expected, len(entries))
    for sha, relative in entries:
        path = prefix + relative.removeprefix("./")
        committed = blob(path)
        check(path, digest(committed) == sha and (ROOT / path).read_bytes() == committed)

n05 = ROOT / "docs/evidence/n05-final-0e1d2a02"
mapped = subprocess.run([sys.executable, "-B", "-I", str(n05 / "n05map.py"), str(ROOT)],
                        capture_output=True)
saved = blob("docs/evidence/n05-final-0e1d2a02/runs/n05map-0e1d2a02.txt").decode("utf-8")
mapped_text = mapped.stdout.decode("utf-8")
check("N05 Git 대조 재실행", mapped.returncode == 0 and "위치 39개 중 불일치 0" in mapped_text)
check("N05 대조 표 재현", mapped_text.strip() == saved.removesuffix("rc=0\n").strip())
files = ["LiveTradingService", "DynamicTradingService", "PaperTradingService", "PaperTradingController",
         "RealtimeProtectionDispatcher", "RiskManagementService", "SessionBalanceUpdater",
         "DynamicSessionBalanceUpdater", "OrderExecutionEngine"]
tree = git("ls-tree", "-r", "--name-only", PRODUCT).decode().splitlines()
for name in files:
    paths = [p for p in tree if p.startswith("web-api/src/main/java/") and p.endswith("/" + name + ".java")]
    (path,) = paths
    same = blob(path, "88f06345") == blob(path, PRODUCT)
    check(f"N05 파일 {name}", same == (name != "OrderExecutionEngine"))

screen_prefix = "docs/evidence/screen-0e1d2a02/"
for run in ("prod-render-1", "prod-render-2"):
    base = screen_prefix + "runs/" + run + "/"
    summary = json.loads(blob(base + "summary.json"))
    requests = json.loads(blob(base + "requests.json"))
    responses = json.loads(blob(base + "responses.json"))
    allowed = [r for r in requests if r["decision"] == "allow"]
    blocked = [r for r in requests if r["decision"] == "block"]
    check(run + " 기록 수", len(allowed) == summary["requests"]["allowed"] and
          len(blocked) == summary["requests"]["blocked"], summary["requests"])
    check(run + " 로그인·페이지 오류", summary["loggedIn"] and not summary["pageErrors"])
    check(run + " 변경 요청", all(r["method"] == "GET" or
          (r["method"] == "POST" and r["path"] == "/api/auth/login") for r in allowed))
    violations = []
    for request in allowed:
        path = request["path"]
        match = re.search(r"/paper-trading/sessions/(\d+)(?:/|$)", path)
        query_id = re.search(r"[?&]sessionId=(\d+)(?:&|$)", request["query"])
        if path == "/api/proxy/api/v1/paper-trading/sessions" or (match and 522 <= int(match[1]) <= 587):
            violations.append(path)
        if "/logs/strategy" in path and (not query_id or 522 <= int(query_id[1]) <= 587):
            violations.append(path + request["query"])
    check(run + " 비열람 요청 제외", not violations, violations)
    if run == "prod-render-2":
        check("2차 7개 화면", len(summary["shots"]) == 7 and all(not s["errorText"] for s in summary["shots"]))
        check("2차 API 22개 모두 200", len(responses) == 22 and all(r["status"] == 200 for r in responses))
        check("표시 대상 518", summary["paperSession"] == 518)

front_diff = git("diff", "--name-only", "5f9ef2ad", PRODUCT, "--", "crypto-trader-frontend").decode().splitlines()
check("v153 렌더링의 화면 소스 동일", front_diff == ["crypto-trader-frontend/.dockerignore"], front_diff)
bundle = "docs/evidence/create-bundle-0e1d2a02/"
plan = json.loads(blob(bundle + "ab-plan/plan.json"))
historical_plan = "docs/evidence/v157-20261112/runs/ab4-main/out/create/plan.json"
check("A/B 계획 v157 바이트 동일", blob(bundle + "ab-plan/plan.json") == blob(historical_plan))
check("A/B 요청 8개", len(plan["bodies"]) == 8)
body = json.loads(blob(bundle + "sma20/body.json"))
check("SMA20 요청", body == {"strategyType": "DAILY_SMA20", "coinPair": "KRW-BTC", "timeframe": "H1",
      "initialCapital": 20200, "investRatio": 0.995, "stopLossPct": 5, "maxHoldHours": 0,
      "strategyParams": {"paperMaxInvestment": 20000, "paperExecutionMode": "LIVE_ALIGNED", "forwardTag": "SMA20-FWD-1"},
      "enableTelegram": False})
creator = blob("docs/evidence/ab-create-v4-20261111/create/ab_create.py").decode("utf-8")
check("v4 전체 목록 GET 두 곳", creator.count('client.call("GET", ENDPOINT)') == 2)
sql = blob(bundle + "preflight/preflight.sql").decode("utf-8")
check("사전 읽기 SQL", "REPEATABLE READ READ ONLY" in sql and "id NOT BETWEEN 522 AND 587" in sql and "ROLLBACK;" in sql)
check("기존 사전 BTC 질의는 개수·최소·최대만", "min(time)" in sql and "max(time)" in sql and "lag(" not in sql.lower())

result = {"source": SOURCE, "product": PRODUCT, "scope": "로컬 제출·Git·요청 기록 대조, 제품 시험·운영 접속 없음",
          "passed": sum(c["ok"] for c in checks), "failed": sum(not c["ok"] for c in checks), "checks": checks}
(OUT / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: result[k] for k in ("source", "passed", "failed")}, ensure_ascii=False))
for item in checks:
    if not item["ok"]:
        print(json.dumps(item, ensure_ascii=False))
sys.exit(bool(result["failed"]))
