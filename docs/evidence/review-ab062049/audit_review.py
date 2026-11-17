"""429차 고정 제출의 로컬 대조. 운영/DB/네트워크/제품 시험 실행 없음."""
import hashlib
import importlib.util
import io
import json
import re
import socket
import subprocess
import sys
import urllib.request
import urllib.response
import xml.etree.ElementTree as ET
from email.message import Message
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SOURCE = "ab0620493408173e536ccddc43d3ab7b2b41a584"
checks = []


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, check=True).stdout


def blob(path):
    return git("show", f"{SOURCE}:{path}")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check(name, passed, detail=None):
    checks.append({"name": name, "ok": bool(passed), "detail": detail})


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


check("HEAD", git("rev-parse", "HEAD").decode().strip() == SOURCE)
check("origin/main", git("rev-parse", "origin/main").decode().strip() == SOURCE)
changed = git("diff", "--name-only", "26a12f3e", SOURCE).decode().splitlines()
check("제출 변경은 docs 경로", all(p.startswith("docs/") for p in changed), len(changed))
for folder, expected in {"ab-create-v41-20261116": 104, "n03-1116-2200": 65,
                         "morning-20261117": 9, "review-26a12f3e": 4}.items():
    prefix = f"docs/evidence/{folder}/"
    entries = [re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line).groups()
               for line in blob(prefix + "SHA256SUMS").decode().splitlines() if line.strip()]
    check(folder + " 목록 개수", len(entries) == expected, len(entries))
    for expected_sha, relative in entries:
        path = prefix + relative.removeprefix("./")
        data = blob(path)
        check(path, sha(data) == expected_sha and (ROOT / path).read_bytes() == data)

P = "docs/evidence/ab-create-v41-20261116/"
V4 = "docs/evidence/ab-create-v4-20261111/"
for path in ("create/post_creation_check.sql", "create/extract_positions.sql", "tool/protocol.json"):
    check("v4 원문 연결 " + path, blob(P + path) == blob(V4 + path))
creator = load_module("review_creator_v41", P + "create/ab_create.py")
complete = load_module("ab_complete", P + "create/ab_complete.py")
arms = load_module("review_arms_v41", P + "tool/ab_arms.py")
protocol_raw = blob(P + "tool/protocol.json")
protocol = json.loads(protocol_raw)
for path in ("create/meta_preflight.sql", "create/meta_after.sql"):
    sql = blob(P + path).decode()
    check(path + " 읽기 전용·비열람 제외", "REPEATABLE READ READ ONLY" in sql and
          "id NOT BETWEEN 522 AND 587" in sql and "ROLLBACK;" in sql)
for name, expected in (("preflight.out", [588]), ("after586.out", [588, 600, 601])):
    text = blob(P + "runs/meta-sql-pg/" + name).decode()
    result, error = creator.parse_meta(0, text)
    check("보존 PG 출력 해석 " + name, error is None and [r["id"] for r in result["rows"]] == expected)
for log, count in (("test_ab_create_v41.run2.txt", 31), ("test_ab_v41.run2.txt", 26)):
    text = blob(P + "runs/" + log).decode()
    check("보존 도구 시험 " + log, f"Ran {count} tests" in text and text.strip().endswith("OK"))

for run in ("run-a-seed", "run-b-main"):
    base = P + "runs/pgchain/" + run + "/"
    xml = ET.fromstring(blob(base + "TEST-com.cryptoautotrader.api.service.AbChainV4PgTest.xml"))
    check(run + " 실제 HTTP·격리 PG 시험 XML", xml.get("tests") == "1" and xml.get("failures") == "0" and xml.get("errors") == "0")
    draft_bytes = blob(base + "create/creation_log.draft.json")
    draft = json.loads(draft_bytes)
    db_text = blob(base + "ok.db_check.out").decode()
    saved = json.loads(blob(base + "ok.creation_log.json"))
    replayed = complete.complete(draft, db_text, protocol, sha(protocol_raw), draft_sha=sha(draft_bytes),
                                 db_source=saved["db_check"]["db_source"])
    check(run + " 완성기 순수 함수 재현", replayed == saved)
    rows = arms.build_rows(saved, protocol, sha(protocol_raw))
    arms.check_against_db_output(saved, db_text)
    check(run + " arms CSV 바이트 재현", arms.to_csv_bytes(rows) == blob(base + "arms.csv"))
    journal = [json.loads(line) for line in blob(base + "create/journal.jsonl").decode().splitlines()]
    check(run + " 전송 의도·결과 각8", sum(r["kind"] == "intent" for r in journal) == 8 and
          sum(r["kind"] == "result" and r.get("class") == "OK" for r in journal) == 8)
    check(run + " DB 시각 출처·정밀도", draft["tool_version"] == "v4.1" and draft["reconcile_ok"] and
          all("api_stored_started_at" not in s and creator.precision_contract_ok(s["post_started_at_raw"], s["db_meta_started_at"])
              for s in draft["sessions"]))
    for case in ("one_row_1us", "fp_missing", "fp_multiple", "trade_before_t0", "stored_ratio") + (("other_db",) if run == "run-b-main" else ()):
        output = blob(base + "neg_" + case + ".db_check.out").decode()
        try:
            complete.complete(draft, output, protocol, sha(protocol_raw), db_source="보존 음성 입력")
            rejected = False
        except complete.Rejected:
            rejected = True
        check(run + " 음성 입력 거절 " + case, rejected)

N = "docs/evidence/n03-1116-2200/"
server_shas = dict((name, digest) for digest, name in re.findall(r"^([0-9a-f]{64})  (.+)$", blob(N + "server-files.txt").decode(), re.M))
for file in ("20261116-2200.txt", "20261116-2200.txt.msg", "20261116-2200.txt.raw", "last_send"):
    check("보고 서버 해시 " + file, sha(blob(N + file)) == server_shas[file])
raw = blob(N + "20261116-2200.txt.raw").decode()
check("22:00 보고 DB 값", all(s in raw for s in ("flyway_version|96", "real_open_positions|0", "real_unresolved_orders|0",
      "sid_status|CREATED", "sid_available_krw|20200.00", "btc_h1_ok|true", "btc_h1_daily|20")))
for folder, path in (("n03-1116-2200", "install_state.txt"), ("morning-20261117", "state.txt")):
    state = blob(f"docs/evidence/{folder}/{path}").decode()
    check(folder + " 이미지·기동·HEAD 보존", all(s in state for s in ("0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d",
        "2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f", "RestartCount=0")))
network = blob("docs/evidence/morning-20261117/network-lines.txt").decode().splitlines()
check("아침 통신 ERROR9·WARN1", sum(" ERROR " in s for s in network) == 9 and sum(" WARN " in s for s in network) == 1)
summary = blob("docs/evidence/morning-20261117/logs-since-2200.txt").decode()
check("아침 로그 요약 값", all(s in summary for s in ("로그 줄 18410", "ERROR 21", "Hikari 누수 0 · 획득 실패 0", "OutOfMemory 0 · Started 0")))
observations = blob(N + "observations.txt").decode()
check("6차 observations는 접속 실패 원문", "HTTP 비200" not in observations and "재시도 10" in observations)
attempt4 = blob(N + "attempt4/observations.txt").decode()
check("4차 observations에 유효 관측 존재", "뉴스 수집 완료 50 · 미완료 15" in attempt4 and "획득 실패 0" in attempt4)

# 소켓을 차단한 상태에서 urllib의 실제 리다이렉트 처리만 가짜 HTTP 응답으로 확인한다.
def deny_socket(*args, **kwargs):
    raise RuntimeError("이 검토는 네트워크 연결을 허용하지 않는다")


class FakeHTTP(urllib.request.BaseHandler):
    handler_order = 100

    def __init__(self, response_code):
        self.response_code = response_code
        self.requests = []

    def http_open(self, request):
        self.requests.append({"method": request.get_method(), "url": request.full_url})
        headers = Message()
        if len(self.requests) == 1 and self.response_code != 200:
            headers["Location"] = request.full_url
            code, payload = self.response_code, b""
        else:
            code = 200
            payload = b'{"success":true,"data":[{"id":560,"profit":"unread-marker"}]}'
        response = urllib.response.addinfourl(io.BytesIO(payload), headers, request.full_url, code)
        response.msg = "fake"
        return response


redirects = []
original_urlopen = urllib.request.urlopen
original_connect, original_connect_ex = socket.socket.connect, socket.socket.connect_ex
socket.socket.connect = socket.socket.connect_ex = deny_socket
try:
    for status in (301, 302, 303, 307, 308):
        fake = FakeHTTP(status)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), fake)
        urllib.request.urlopen = opener.open
        result = creator.Client("http://offline.invalid", "local-fake-token", 1).call("POST", creator.ENDPOINT, {})
        received = isinstance((result[1] or {}).get("data"), list)
        check(f"리다이렉트 {status} 관측 재현", [r["method"] for r in fake.requests] ==
              (["POST", "GET"] if status in (301, 302, 303) else ["POST"]) and received == (status in (301, 302, 303)))
        redirects.append({"response": status, "requests": fake.requests, "returned_code": result[0], "list_payload_received": received})
    try:
        creator.Client("http://offline.invalid", "local-fake-token", 1).call("GET", creator.ENDPOINT)
        rejected = False
    except RuntimeError:
        rejected = True
    check("직접 GET 거절 대조군", rejected)
finally:
    urllib.request.urlopen = original_urlopen
    socket.socket.connect, socket.socket.connect_ex = original_connect, original_connect_ex

result = {"source": SOURCE, "scope": "로컬 Git·보존 출력·순수 도구 함수·소켓 없는 리다이렉트 재현. 제품 시험/운영 접속 없음",
          "passed": sum(c["ok"] for c in checks), "failed": sum(not c["ok"] for c in checks), "checks": checks,
          "finding": "v4.1 직접 GET 거절과 달리 urllib 자동 301/302/303 처리에서 전체 목록 GET·응답 수신이 발생한다. 실제 운영 발생은 미확인.",
          "redirect_probe": redirects}
(OUT / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: result[k] for k in ("source", "passed", "failed")}, ensure_ascii=False))
for item in checks:
    if not item["ok"]:
        print(json.dumps(item, ensure_ascii=False))
sys.exit(bool(result["failed"]))
