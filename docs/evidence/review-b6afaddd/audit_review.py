"""Review committed artifacts and Git blobs without running deployment blocks."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SOURCE = "b6afaddd"
CANDIDATE = "0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d"
BASH = "C:/Program Files/Git/bin/bash.exe"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def committed_manifest(folder):
    base = ROOT / "docs/evidence" / folder
    rows = []
    for line in (base / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        expected, name = line[:64], line[64:].strip().lstrip("*")
        path = base / name
        rel = path.relative_to(ROOT).as_posix()
        assert sha(path.read_bytes()) == expected, rel
        assert sha(git("show", SOURCE + ":" + rel)) == expected, rel
        rows.append(name)
    return {"count": len(rows), "working_and_committed_mismatches": 0}


def parse_entries(data):
    rows = [line.split("\t") for line in data.decode("utf-8").splitlines()]
    assert all(len(row) == 2 and re.fullmatch("[a-f0-9]{64}", row[1]) for row in rows)
    names = [row[0] for row in rows]
    assert names == sorted(names) and len(set(names)) == len(names)
    return dict(rows)


result = {"source": git("rev-parse", SOURCE).decode().strip(), "candidate": CANDIDATE}
result["manifests"] = {name: committed_manifest(name) for name in [
    "review-ffd3cb0e", "n03-1116-1000", "v168-run-20261116",
]}
base = ROOT / "docs/evidence/v168-run-20261116"
old = parse_entries((ROOT / "docs/evidence/v168-final-20261115/jars/manifest-0e1d2a02.lf.txt").read_bytes())
server_bytes = (base / "server-maint-v168/jar-manifest-new.txt").read_bytes()
server = parse_entries(server_bytes)
assert set(old) == set(server) and len(server) == 895
different = [name for name in old if old[name] != server[name]]
resource_root = "BOOT-INF/classes/"
expected_resources = set(git("ls-tree", "-r", "--name-only", CANDIDATE,
    "web-api/src/main/resources").decode().splitlines())
expected_resources = {resource_root + name.removeprefix("web-api/src/main/resources/")
                      for name in expected_resources}
assert set(different) == expected_resources and len(different) == 97
expected_lf = dict(old)
resource_checks = []
for name in different:
    path = "web-api/src/main/resources/" + name.removeprefix(resource_root)
    blob = git("show", CANDIDATE + ":" + path)
    assert b"\r" not in blob, path
    assert sha(blob) == server[name], path
    assert sha(blob.replace(b"\n", b"\r\n")) == old[name], path
    expected_lf[name] = sha(blob)
    resource_checks.append({"entry": name, "git_lf_sha256": expected_lf[name],
                            "old_crlf_sha256": old[name]})
expected_bytes = "".join(name + "\t" + expected_lf[name] + "\n" for name in sorted(expected_lf)).encode()
assert expected_bytes == server_bytes
expected_hash = sha(expected_bytes)
assert expected_hash == "b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8"
(OUT / "expected-manifest-git-lf.txt").write_bytes(expected_bytes)
result["jar"] = {"entries": len(server), "unchanged_entries": len(server) - len(different),
    "resource_changes": len(different), "sql": sum(name.endswith(".sql") for name in different),
    "resource_checks": resource_checks, "independently_derived_lf_jarm": expected_hash,
    "old_jarm": sha((ROOT / "docs/evidence/v168-final-20261115/jars/manifest-0e1d2a02.lf.txt").read_bytes()),
    "derived_manifest_equals_server_bytes": True}
result["blocks"] = {}
for path in sorted((base / "blocks").glob("*.sh")):
    name = path.name
    if name in {"F.sh", "G.sh"}:
        source_path = "docs/LIMITED_LIVE_TEST_RUNBOOK.md" if name == "F.sh" else "docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md"
        document = git("show", "d5790c1c:" + source_path).decode().replace("\r\n", "\n")
        bodies = re.findall(r"^```bash\n(.*?)^```\s*$", document, re.M | re.S)
        matched = [body.encode() for body in bodies if sha(body.encode()) == sha(path.read_bytes())]
        assert len(matched) == 1, name
        source_bytes = matched[0]
    else:
        source_bytes = git("show", "d5790c1c:docs/evidence/v168-final-20261115/d4/blocks-d4/" + name)
        assert path.read_bytes() == source_bytes, name
    syntax = subprocess.run([BASH, "--noprofile", "--norc", "-n", str(path)], capture_output=True)
    assert syntax.returncode == 0, name
    result["blocks"][name] = {"sha256": sha(path.read_bytes()), "syntax_exit": 0,
                              "fixed_source_match": source_bytes == path.read_bytes()}
result["outputs"] = {}
for path in sorted((base / "out").glob("*.txt")):
    text = path.read_text(encoding="utf-8")
    result["outputs"][path.name] = {
        "sha256": sha(path.read_bytes()), "lines": len(text.splitlines()),
        "end_lines": [line for line in text.splitlines() if "[로컬 끝]" in line],
        "failure_lines": [line for line in text.splitlines() if "멈춤:" in line or "No such image" in line],
    }
result["submitted_file_names"] = [p.relative_to(base).as_posix() for p in sorted(base.rglob("*")) if p.is_file()]
result["raw_docker_diagnostics_note"] = "README reports 10:29 state and 837db515/fa5c9ba8 mapping; full Docker inspect/version/image-tree raw outputs are absent from the submitted file set."
result["old_block_image_comparisons"] = {}
for name in ["U0.sh", "U1.sh", "U1되돌림.sh", "U2.sh", "UF2.sh", "U되돌림.sh"]:
    lines = (base / "blocks" / name).read_text(encoding="utf-8").splitlines()
    result["old_block_image_comparisons"][name] = [
        {"line": i + 1, "text": line} for i, line in enumerate(lines)
        if "docker tag" in line or "docker image inspect" in line or "{{.Image}}" in line
    ]
assert git("rev-parse", "candidate/order-poll-race-b987d7a7").decode().strip() == CANDIDATE
documents = [ROOT / "docs" / name for name in [
    "START_HERE.md", "LIMITED_LIVE_TEST_PLAN.md", "CURRENT_HANDOFF.md",
    "LIVE_READINESS.md", "NOVEMBER_LIVE_UPGRADE.md", "PROGRESS.md", "README.md",
]] + [OUT / "README.md"]
result["documents"] = {}
for path in documents:
    text = path.read_text(encoding="utf-8")
    assert not re.search(r"[\u3001\u3002\u3040-\u30ff\u4e00-\u9fff]", text), str(path)
    broken = []
    for target in re.findall(r"\]\(([^)]+)\)", text):
        target = target.split("#", 1)[0].strip("<>")
        if target and not re.match(r"[a-zA-Z]+://", target) and not (path.parent / target).exists():
            broken.append(target)
    assert not broken, (str(path), broken)
    result["documents"][path.relative_to(ROOT).as_posix()] = {"lines": len(text.splitlines()), "broken_links": []}
progress = (ROOT / "docs/PROGRESS.md").read_text(encoding="utf-8")
assert re.findall(r"^### (\d+)차", progress, re.M) == ["421", "420", "419", "418", "417"]
plan_path = "docs/LIMITED_LIVE_TEST_PLAN.md"
previous_plan = git("show", SOURCE + ":" + plan_path).decode("utf-8")
current_plan = (ROOT / plan_path).read_text(encoding="utf-8")
night = r"### 야간 작업 원칙.*?(?=### 보완 뒤에도)"
assert re.search(night, previous_plan.replace("\r\n", "\n"), re.S).group() == re.search(night, current_plan, re.S).group()
result["night_policy_unchanged"] = True
(OUT / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
rows = []
for path in sorted(OUT.rglob("*")):
    if path.is_file() and path.name != "SHA256SUMS":
        rows.append(sha(path.read_bytes()) + "  " + path.relative_to(OUT).as_posix() + "\n")
(OUT / "SHA256SUMS").write_text("".join(rows), encoding="utf-8", newline="\n")
print(json.dumps({"manifests": result["manifests"], "entries": len(server),
                  "unchanged": len(server) - len(different), "git_lf_resources": len(different),
                  "jarm": expected_hash, "syntax": len(result["blocks"]),
                  "output_names": list(result["outputs"]), "review_manifest": len(rows),
                  "documents": result["documents"]}, ensure_ascii=False))
