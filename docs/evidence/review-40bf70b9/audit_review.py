"""Inspect fixed Git objects and preserved results without executing product code."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SUBMISSION = "40bf70b9"
BASE = ROOT / "docs/evidence/v166-isolation-20261115"
OEE = "web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java"


def git(*args, data=None):
    return subprocess.check_output(["git", *args], cwd=ROOT, input=data, stderr=subprocess.DEVNULL)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def manifest(name):
    folder = ROOT / "docs/evidence" / name
    expected = []
    for line in (folder / "SHA256SUMS").read_text(encoding="utf-8-sig").splitlines():
        if line.strip():
            expected.append((line[:64], folder / line[64:].strip().lstrip("*")))
    request = "".join(SUBMISSION + ":" + path.relative_to(ROOT).as_posix() + "\n" for _, path in expected)
    raw = git("cat-file", "--batch", data=request.encode())
    offset, work_bad, blob_bad = 0, [], []
    for digest, path in expected:
        end = raw.index(b"\n", offset)
        header = raw[offset:end].split()
        offset = end + 1
        if header[-1] == b"missing":
            blob_bad.append(path.relative_to(ROOT).as_posix() + ":missing")
        else:
            size = int(header[-1])
            if sha(raw[offset:offset + size]) != digest:
                blob_bad.append(path.relative_to(ROOT).as_posix())
            offset += size + 1
        if not path.is_file() or sha(path.read_bytes()) != digest:
            work_bad.append(path.relative_to(ROOT).as_posix())
    return dict(count=len(expected), worktree_mismatch=work_bad, blob_mismatch=blob_bad)


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    token = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|[{}]', re.S)
    for match in token.finditer(source, opening):
        if match.group() == "{":
            depth += 1
        elif match.group() == "}":
            depth -= 1
            if depth == 0:
                return source[start:match.end()]
    raise ValueError(signature)


def run_summary(name):
    folder = BASE / "runs" / name
    modules, failures, xmls = {}, [], list(folder.rglob("TEST-*.xml"))
    for path in xmls:
        tree = ET.parse(path).getroot()
        relative = path.relative_to(folder).as_posix()
        module = next((part for part in ["core-engine", "exchange-adapter", "strategy-lib", "web-api"]
                       if part in relative.split("/")), "web-api")
        counts = modules.setdefault(module, dict(tests=0, failures=0, errors=0, skipped=0))
        for key in counts:
            counts[key] += int(tree.get(key, 0))
        for case in tree.findall("testcase"):
            for fail in case.findall("failure") + case.findall("error"):
                failures.append(dict(xml=relative, case=case.get("name"), message=fail.get("message", "")[:180]))
    for counts in modules.values():
        counts["passed"] = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
    metadata = {}
    for path in folder.rglob("meta.txt"):
        metadata[path.relative_to(folder).as_posix()] = path.read_text(encoding="utf-8-sig")
    guards = {}
    for path in folder.rglob("NET-GUARD-summary.txt"):
        guards[path.relative_to(folder).as_posix()] = path.read_text(encoding="utf-8-sig").splitlines()[:7]
    return dict(xml_files=len(xmls), modules=modules, failures=failures, metadata=metadata, network_guards=guards)


def document_checks():
    paths = [ROOT / "docs" / name for name in ["START_HERE.md", "LIMITED_LIVE_TEST_PLAN.md", "CURRENT_HANDOFF.md",
        "LIVE_READINESS.md", "NOVEMBER_LIVE_UPGRADE.md", "PROGRESS.md", "README.md"]]
    paths.append(OUT / "README.md")
    bad_links, foreign, sizes, count = [], [], {}, 0
    for path in paths:
        content = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()
        sizes[relative] = len(content.splitlines())
        for no, line in enumerate(content.splitlines(), 1):
            if re.search(r"[\u3001\u3002\u3040-\u30ff\u4e00-\u9fff]", line):
                foreign.append([relative, no])
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", content):
            if target.startswith(("https://", "http://", "#")):
                continue
            count += 1
            if not (path.parent / target.split("#", 1)[0].strip("<>")).exists():
                bad_links.append([relative, target])
    def night(value):
        return value.replace("\r\n", "\n").split("### 야간 작업 원칙", 1)[1].split("### 보완 뒤에도", 1)[0]
    before = git("show", SUBMISSION + ":docs/LIMITED_LIVE_TEST_PLAN.md").decode()
    return dict(lines=sizes, links=count, broken_links=bad_links, foreign_characters=foreign,
        night_rule_unchanged=night(before)==night(paths[1].read_text(encoding="utf-8")),
        recent_progress_items=len(re.findall(r"^### \d+차", paths[5].read_text(encoding="utf-8"), re.M)))


result = dict(reviewed_main=git("rev-parse", SUBMISSION).decode().strip(), manifests={}, runs={}, methods={})
result["refs"] = {ref: git("rev-parse", ref).decode().strip() for ref in [
    "HEAD", "origin/main", "integration/v158", "test/v166-isolation-3f9cc926", "origin/test/v166-isolation-3f9cc926"]}
for name in ["v166-isolation-20261115", "g1-submit-boundary-20261113", "review-6f27b35d"]:
    result["manifests"][name] = manifest(name)
for name in ["guard-neg-6cls", "iso-6cls", "n15pg-off-b2976905", "n15pg-fix2-500",
             "rel19-gateoff-b2976905", "rel19-gate50-b2976905", "int-full-b2976905", "affected46-b987d7a7"]:
    result["runs"][name] = run_summary(name)
sources = {ref: git("show", ref + ":" + OEE).decode() for ref in ["b5cd1d0", "88f06345", "b987d7a7"]}
for signature in ["public void pollActiveOrders()", "private Boolean resolveUnresolvedSubmissionInTx(Long orderId)",
                  "private void transitionState(OrderEntity order, String newState, String failedReason)",
                  "private boolean claimOwned(Long orderId, OrderRequest request)"]:
    fragments = {ref: method(source, signature) for ref, source in sources.items()}
    result["methods"][signature] = dict(sha256={ref: sha(value.encode()) for ref, value in fragments.items()},
        identical=len(set(fragments.values())) == 1,
        first_lines={ref: sources[ref][:sources[ref].index(signature)].count("\n")+1 for ref in sources})
repo = git("show", "88f06345:web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java").decode()
entity = git("show", "88f06345:web-api/src/main/java/com/cryptoautotrader/api/entity/OrderEntity.java").decode()
result["source_flags"] = dict(order_entity_version_annotation="@Version" in entity,
    existing_attach_found_submission="int attachFoundSubmission(" in repo,
    claim_clears_dispatching="finally {\n            DISPATCHING.remove(orderId);" in sources["88f06345"])
result["test_paths_since_88"] = git("diff", "--name-only", "88f06345", "b987d7a7").decode().splitlines()
result["test_paths_since_3f"] = git("diff", "--name-only", "3f9cc926", "b987d7a7").decode().splitlines()
timeline = []
for path in (BASE / "runs/affected46-b987d7a7").rglob("TEST-*SchedulerProfileCleanupTest.xml"):
    for line in path.read_text(encoding="utf-8").splitlines():
        if any(mark in line for mark in ["01:03:02.909", "01:03:02.911", "주문 ID=359", "거래로그 ID=579", "거래로그 ID=580"]):
            timeline.append(line[:1200])
result["scheduler_excerpt"] = timeline[:30]
result["documents"] = document_checks()
assert all(not (item["worktree_mismatch"] or item["blob_mismatch"]) for item in result["manifests"].values())
assert all(path.startswith("web-api/src/test/") for path in result["test_paths_since_88"])
assert not result["documents"]["broken_links"]
assert not result["documents"]["foreign_characters"]
assert result["documents"]["night_rule_unchanged"]
assert result["documents"]["recent_progress_items"] == 5
(OUT / "checks.json").write_text(json.dumps(result, ensure_ascii=True, indent=2)+"\n", encoding="utf-8")
print(json.dumps(dict(manifests=result["manifests"], methods=result["methods"], source_flags=result["source_flags"],
                     runs={name: row["modules"] for name, row in result["runs"].items()},
                     test_path_count=len(result["test_paths_since_88"])), indent=2))
