"""Read-only review of submitted Git blobs, manifests, and preserved JUnit XML."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
BASE = ROOT / "docs/evidence/g1-submit-boundary-20261113"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def manifest(folder):
    rows, missing, bad, blob_missing, blob_normalized, blob_other = [], [], [], [], [], []
    for line in (folder / "SHA256SUMS").read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        expected, name = line[:64], line[64:].strip().lstrip("*")
        path = folder / name
        rows.append(name)
        if not path.is_file():
            missing.append(name)
            continue
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            bad.append(name)
        try:
            data = git("show", "HEAD:" + path.relative_to(ROOT).as_posix())
        except subprocess.CalledProcessError:
            blob_missing.append(name)
            continue
        if hashlib.sha256(data).hexdigest() != expected:
            if data.replace(b"\r\n", b"\n") == path.read_bytes().replace(b"\r\n", b"\n"):
                blob_normalized.append(name)
            else:
                blob_other.append(name)
    return dict(count=len(rows), missing=missing, worktree_mismatch=bad,
                blob_exact_count=len(rows)-len(blob_missing)-len(blob_normalized)-len(blob_other),
                blob_missing=blob_missing, blob_line_ending_only=blob_normalized, blob_other_mismatch=blob_other)


def junit(run):
    folder = BASE / "runs" / run
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files, cases, failures, skips, logs = list(folder.rglob("TEST-*.xml")), [], [], [], []
    for path in files:
        tree = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(tree.attrib.get(key, 0))
        logs.append("\n".join((node.text or "") for node in tree.findall("system-out")))
        for case in tree.findall("testcase"):
            cases.append(case.attrib)
            if case.find("skipped") is not None:
                skips.append(case.attrib)
            for failure in case.findall("failure") + case.findall("error"):
                failures.append(dict(case=case.attrib, message=failure.attrib.get("message", "")))
    logs = "\n".join(logs)
    metadata = {}
    for name in ["meta.txt", "summary.txt", "time.txt", "rc.txt"]:
        path = folder / name
        if path.is_file():
            metadata[name] = path.read_text(encoding="utf-8-sig")
    return dict(xml_files=len(files), **counts,
                passed=counts["tests"]-counts["skipped"]-counts["failures"]-counts["errors"],
                failure_cases=failures, skipped_cases=skips, metadata=metadata,
                null_pointer_mentions=logs.count("NullPointerException"),
                ws_connected_mentions=logs.count("Upbit WebSocket 연결 성공"),
                ws_subscription_mentions=logs.count("구독 메시지 전송"))


def documents():
    paths = [ROOT / "docs" / name for name in [
        "START_HERE.md", "LIMITED_LIVE_TEST_PLAN.md", "CURRENT_HANDOFF.md",
        "LIVE_READINESS.md", "NOVEMBER_LIVE_UPGRADE.md", "PROGRESS.md", "README.md"]]
    paths.append(OUT / "README.md")
    broken, foreign, counts, links = [], [], {}, 0
    for path in paths:
        content = path.read_text(encoding="utf-8")
        counts[path.relative_to(ROOT).as_posix()] = len(content.splitlines())
        for no, line in enumerate(content.splitlines(), 1):
            if re.search(r"[\u3001\u3002\u3040-\u30ff\u4e00-\u9fff]", line):
                foreign.append([path.relative_to(ROOT).as_posix(), no])
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", content):
            if target.startswith(("#", "https://", "http://")):
                continue
            target = target.split("#", 1)[0].strip("<>")
            links += 1
            if not (path.parent / target).exists():
                broken.append([path.relative_to(ROOT).as_posix(), target])
    plan = paths[1].read_text(encoding="utf-8")
    before = git("show", "HEAD:docs/LIMITED_LIVE_TEST_PLAN.md").decode()
    def night(text):
        return text.replace("\r\n", "\n").split("### 야간 작업 원칙", 1)[1].split("### 보완 뒤에도", 1)[0]
    progress = paths[5].read_text(encoding="utf-8")
    return dict(line_counts=counts, checked_links=links, broken_links=broken,
                foreign_characters=foreign, night_rule_unchanged=night(before)==night(plan),
                recent_progress_items=len(re.findall(r"^### \d+차", progress, re.M)))


result = dict(
    reviewed_main=git("rev-parse", "HEAD").decode().strip(),
    refs={ref: git("rev-parse", ref).decode().strip() for ref in [
        "origin/main", "investigate/g1-submit-boundary", "origin/investigate/g1-submit-boundary",
        "integration/v158", "origin/integration/v158"]},
    manifests={}, runs={}, documents=documents(),
    candidate_paths=git("diff", "--name-only", "88f06345", "3f9cc926").decode().splitlines(),
    investigation_commits=git("log", "--format=%H %s", "88f06345..3f9cc926").decode().splitlines(),
)
for name in ["g1-submit-boundary-20261113", "review-9fd6c856", "v164-wait20-20261113", "n05-links-88f06345"]:
    result["manifests"][name] = manifest(ROOT / "docs/evidence" / name)
for run in ["final-rel19-nogate", "final-rel19-gate50", "final-n15pg-off", "final-n15pg-500", "aligned-v96pg-88f06345", "diag-r1"]:
    result["runs"][run] = junit(run)
(OUT / "checks.json").write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
print(json.dumps(dict(manifests={key: {field: (len(v) if isinstance(v, list) else v) for field, v in value.items()}
                                for key, value in result["manifests"].items()},
                      runs={key: {field: value[field] for field in ["xml_files", "tests", "passed", "failures", "errors", "skipped", "null_pointer_mentions"]}
                            for key, value in result["runs"].items()},
                      candidate_path_count=len(result["candidate_paths"])), indent=2))
assert all(not (row["missing"] or row["worktree_mismatch"]) for row in result["manifests"].values())
assert all(path.startswith("web-api/src/test/") for path in result["candidate_paths"])
assert result["refs"]["origin/main"] == result["reviewed_main"]
assert result["refs"]["investigate/g1-submit-boundary"] == result["refs"]["origin/investigate/g1-submit-boundary"]
assert result["refs"]["integration/v158"] == result["refs"]["origin/integration/v158"]
assert not result["documents"]["broken_links"]
assert not result["documents"]["foreign_characters"]
assert result["documents"]["night_rule_unchanged"]
assert result["documents"]["recent_progress_items"] == 5
