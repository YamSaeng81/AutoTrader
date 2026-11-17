"""430차 고정 블롭·보존 결과 및 함수만 검사. 서버/DB/HTTP/제품 시험은 호출하지 않는다."""
import hashlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tempfile
import urllib.error
import xml.etree.ElementTree as ET
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
REV = "c3dca38571ba8545fb23cf4c671381755f4d6557"
EVID = "docs/evidence/"
DEV = ROOT / EVID / "ab-create-v42-20261117"
checks, manifests, probes = [], [], []


def check(name, condition):
    checks.append({"name": name, "passed": bool(condition)})


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def blob(path):
    return git("show", REV + ":" + path)


specs = [
    ("ab-create-v42-20261117", "SHA256SUMS", 281),
    ("ab-create-v42-20261117", "bundle/SHA256SUMS.bundle", 16),
    ("n03-1117-1000", "SHA256SUMS", 22),
    ("netinv-20261117", "SHA256SUMS", 18),
    ("bundle-record-review-20261117", "SHA256SUMS", 5),
    ("bundle-record-review-20261117/v422", "SHA256SUMS", 5),
    ("review-ab062049", "SHA256SUMS", 4),
]
for folder, listing, expected in specs:
    base = EVID + folder + "/"
    raw = blob(base + listing)
    check(folder + "/" + listing + ": working list bytes", raw == (ROOT / base / listing).read_bytes())
    lines = raw.decode("utf-8-sig").splitlines()
    check(folder + "/" + listing + ": count", len(lines) == expected)
    for line in lines:
        m = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        assert m, line
        sha, rel = m.groups()
        p = base + rel
        committed = blob(p)
        check(p + ": blob sha and working bytes",
              hashlib.sha256(committed).hexdigest() == sha and (ROOT / p).read_bytes() == committed)
    manifests.append({"folder": folder, "list": listing, "entries": len(lines)})

check("HEAD fixed submission", git("rev-parse", "HEAD").decode().strip() == REV)
check("local origin/main fixed submission", git("rev-parse", "origin/main").decode().strip() == REV)
check("SMA test SHA has no outside-test differences", not git(
    "diff", "--name-only", "0e1d2a02", "a11d414e", "--", ".", ":!web-api/src/test").strip())
check("AB plan bytes unchanged", (DEV / "bundle/plan-ab/plan.json").read_bytes() ==
      (ROOT / EVID / "create-bundle-0e1d2a02/ab-plan/plan.json").read_bytes())
check("SMA body bytes unchanged", (DEV / "sma20/body.json").read_bytes() ==
      (ROOT / EVID / "create-bundle-0e1d2a02/sma20/body.json").read_bytes())

sys.path.insert(0, str(DEV / "create"))
import ab_create as ab
import http_post as hp


class UnreadBody(io.BytesIO):
    def read(self, *a, **k):
        raise AssertionError("3xx body was read")


class FakeOpener:
    def __init__(self, code, location):
        self.code, self.location, self.calls, self.fp = code, location, 0, UnreadBody(b"fixture")

    def open(self, request, timeout):
        self.calls += 1
        assert request.get_method() == "POST"
        raise urllib.error.HTTPError(request.full_url, self.code, "fixture", {"Location": self.location}, self.fp)


for code in (301, 302, 303, 307, 308):
    for target in ("/same", "/api/paper-trading", "../relative", "http://example.invalid/other"):
        opener = FakeOpener(code, target)
        result = hp.post_once(opener, "http://example.invalid/create", "non-secret-review-fixture", {}, 1)
        cls, _ = ab.classify(*result, {}, set())
        check(f"redirect {code} {target}: one fake POST, no body, UNKNOWN",
              opener.calls == 1 and result == (code, None, hp.REDIRECT) and cls == "UNKNOWN" and opener.fp.closed)
        check(f"redirect {code} {target}: handler refuses second request",
              hp._NoRedirect().redirect_request(None, None, code, "fixture", {}, target) is None)

spec = importlib.util.spec_from_file_location("judge_review", DEV / "bundle/judge_preflight.py")
judge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(judge)
cases = [("bundle/judge-runs/normal_nodup.out", 0), ("bundle/judge-runs/broken.out", 3)]
cases += [("preflight/runs-pg/" + n + ".out", 2) for n in
          ("normal", "missing_gap3", "stale_h1_0502_00", "cap_over_n10", "enablement")]
for rel, rc in cases:
    actual, _ = judge.judge((DEV / rel).read_text(encoding="utf-8"), DEV / "tool/protocol.json", DEV / "sma20/body.json")
    check("saved preflight replay " + rel, actual == rc)

chain = DEV / "runs/sma20chain/run1"
xml = ET.fromstring((chain / "TEST-com.cryptoautotrader.api.service.Sma20ChainPgTest.xml").read_bytes())
check("SMA local chain XML 1/0/0/0", all(xml.attrib[k] == v for k, v in
      {"tests": "1", "failures": "0", "errors": "0", "skipped": "0"}.items()))
check("SMA local chain rc", (chain / "gradle.rc").read_text().strip() == "0")
check("T0 six-case XML", any(
    ET.fromstring(p.read_bytes()).attrib.get("tests") == "6" and
    ET.fromstring(p.read_bytes()).attrib.get("failures") == "0"
    for p in (DEV / "javatests/t0-path/run2").glob("TEST-*.xml")))
before = json.loads((DEV / "chart/before-0e1d2a02/dark.json").read_text(encoding="utf-8"))
after = json.loads((DEV / "chart/after-candidate/dark.json").read_text(encoding="utf-8"))
check("chart stored dark measurement changed", before != after)

net = ROOT / EVID / "netinv-20261117"
supp = (net / "ssh-supp.txt").read_text(encoding="utf-8")
check("SSH MaxStartups raw line", "149646 connections dropped" in supp and "00:48:31" in supp)
check("SSH accepted publickey count", "21 Accepted publickey" in supp)
check("SSH failed minute sum", sum(int(x) for x in re.findall(r"^\s+(\d+) T\d\d:\d\d$", supp, re.M)) == 20754)
check("SSH source count is masked prefix", "20708 from 143.198.x.x" in supp)
supp_script = (net / "supp.sh").read_text(encoding="utf-8")
check("SSH mask happens before grouping", supp_script.index("sed -E") < supp_script.index("출발지(앞 두 자리)별 Failed"))
check("SSH mask merges distinct fixture addresses", re.sub(r"([0-9]{1,3}\.[0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}",
      r"\1.x.x", "143.198.10.1 143.198.20.2") == "143.198.x.x 143.198.x.x")
claude = (net / "w4-claude-0500kst.txt").read_text(encoding="utf-8")
check("Claude failure present, recovery absent in saved window", "REPORT_NARRATION" in claude and "전송 성공" not in claude and "요청 실패" in claude)

# 보존 함수만 분리한다. 실제 도구/네트워크/자격 증명은 사용하지 않는다.
body = blob(EVID + "ab-create-v42-20261117/bundle/run_bundle.sh").decode("utf-8")
functions = body[body.index("log() {"):body.index('\nprev_ok\ncase "$STEP" in')]
for label, injection, missing, expected in [
    ("normal_no_match", "", False, (0, 0, 1)),
    ("missing_pattern_file", "", True, (0, 0, 1)),
    ("grep_read_error", "grep() { return 2; }", False, (0, 0, 1)),
    ("fixture_match", "", False, (9, 6, 0)),
]:
    with tempfile.TemporaryDirectory(prefix="secret-check-", dir=HERE) as temp:
        out = Path(temp).resolve()
        assert out.is_relative_to(HERE.resolve())
        token = out.parent / (out.name + ".pattern")
        # 패턴 파일은 검색 대상 밖에 두고 고정 비밀 아닌 문자열만 쓴다.
        try:
            if not missing:
                token.write_text("non-secret-review-fixture", encoding="utf-8")
            (out / "record.txt").write_text("non-secret-review-fixture" if label == "fixture_match" else "ordinary output", encoding="utf-8")
            script = f"set -u\nOUT='{out.as_posix()}'\nTOKEN_FILE='{token.as_posix()}'\nSTEP=2\nTOOL_RC=0\n" + functions
            script += "\n" + injection + "\n( nosecret; finish 0 )\nfirst=$?\nSTEP=3\nTOOL_RC=0\n( prev_ok; printf allowed > \"$OUT/guard.txt\" )\nnext=$?\nbuiltin printf '__FIRST__=%s\\n__NEXT__=%s\\n' \"$first\" \"$next\"\n"
            run = subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc"], input=script.encode(), capture_output=True, timeout=15)
            stdout = run.stdout.decode("utf-8")
            values = dict(l.split("=", 1) for l in stdout.splitlines() if l.startswith("__"))
            state = (int(values["__FIRST__"]), int(values["__NEXT__"]), int((out / "guard.txt").exists()))
            check("secret scan function observation " + label, state == expected)
            probes.append({"case": label, "first_exit": state[0], "next_guard_exit": state[1], "guard_marker": state[2],
                           "final_rc": (out / "2.rc").read_text() if (out / "2.rc").exists() else None,
                           "stdout": stdout, "stderr": run.stderr.decode("utf-8")})
        finally:
            if token.exists():
                assert token.parent == HERE
                token.unlink()

result = {"fixed_commit": REV, "scope": "Git blobs, saved evidence and pure/fake/function checks; no product suite, DB, HTTP or production access",
          "manifests": manifests, "checks": checks, "passed": sum(c["passed"] for c in checks),
          "failed": sum(not c["passed"] for c in checks), "secret_scan_probes": probes}
(HERE / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(json.dumps({"passed": result["passed"], "failed": result["failed"], "manifests": manifests,
                  "secret_scan": [{k: c[k] for k in ("case", "first_exit", "next_guard_exit", "guard_marker")} for c in probes]}, ensure_ascii=False))
raise SystemExit(bool(result["failed"]))
