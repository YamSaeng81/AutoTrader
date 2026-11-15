"""Read preserved Git objects, test reports, and jar contents for this review."""
from pathlib import Path
import ast
import hashlib
import io
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SUBMISSION = "7e77e34a"
BASE = ROOT / "docs/evidence/v167-race-20261115"


def git(*args, data=None):
    return subprocess.check_output(["git", *args], cwd=ROOT, input=data, stderr=subprocess.DEVNULL)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def checksum_rows(path):
    return [(line[:64], line[64:].strip().lstrip("*"))
            for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]


def manifest(folder, source_base=None, committed=True):
    expected = checksum_rows(folder / "SHA256SUMS") if source_base is None else checksum_rows(folder)
    base = folder if source_base is None else source_base
    work_bad, blob_bad = [], []
    raw, offset = b"", 0
    if committed:
        request = "".join(SUBMISSION + ":" + (base/name).relative_to(ROOT).as_posix() + "\n" for _, name in expected)
        raw = git("cat-file", "--batch", data=request.encode())
    for expected_hash, name in expected:
        path = base / name
        if not path.is_file() or digest(path.read_bytes()) != expected_hash:
            work_bad.append(name)
        if committed:
            end = raw.index(b"\n", offset)
            header, offset = raw[offset:end].split(), end+1
            if header[-1] == b"missing":
                blob_bad.append(name + ":missing")
            else:
                size = int(header[-1])
                if digest(raw[offset:offset+size]) != expected_hash:
                    blob_bad.append(name)
                offset += size+1
    return dict(count=len(expected), worktree_mismatch=work_bad, blob_mismatch=blob_bad)


def junit(folder):
    totals, failures, modules = dict(tests=0, failures=0, errors=0, skipped=0), [], {}
    for path in folder.rglob("TEST-*.xml"):
        tree = ET.parse(path).getroot()
        relative = path.relative_to(folder).as_posix()
        module = next((name for name in ["core-engine", "exchange-adapter", "strategy-lib", "web-api"]
                       if name in relative.split("/")), "web-api")
        row = modules.setdefault(module, dict(tests=0, failures=0, errors=0, skipped=0))
        for key in totals:
            number = int(tree.get(key, 0))
            totals[key] += number
            row[key] += number
        for case in tree.findall("testcase"):
            for failure in case.findall("failure") + case.findall("error"):
                failures.append(dict(xml=relative, case=case.get("name"), message=failure.get("message", "")[:220]))
    for row in [totals, *modules.values()]:
        row["passed"] = row["tests"]-row["failures"]-row["errors"]-row["skipped"]
    return dict(totals=totals, modules=modules, failures=failures,
        metadata={p.name: p.read_text(encoding="utf-8-sig") for p in folder.iterdir()
                  if p.name in ["meta.txt", "time.txt", "cmd.txt", "git-status.txt"]},
        guards={p.relative_to(folder).as_posix(): p.read_text(encoding="utf-8-sig").splitlines()[:7]
                for p in folder.rglob("NET-GUARD-summary.txt")})


def jar_entries(path):
    own = re.compile(r"BOOT-INF/lib/(core-engine|strategy-lib|exchange-adapter)[^/]*\.jar$")
    result, owners = {}, set()
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names))
        for name in names:
            if name.endswith("/"):
                continue
            data = archive.read(name)
            if own.match(name):
                key = re.sub(r"-[0-9.]+(-SNAPSHOT)?\.jar$", ".jar", name)
                assert key not in owners
                owners.add(key)
                with zipfile.ZipFile(io.BytesIO(data)) as inner:
                    inside = inner.namelist()
                    assert len(inside) == len(set(inside))
                    for sub in inside:
                        if not sub.endswith("/"):
                            result[key+"!"+sub] = digest(inner.read(sub))
            else:
                result[name] = digest(data)
    return result


def document_checks():
    paths = [ROOT/"docs"/name for name in ["START_HERE.md", "LIMITED_LIVE_TEST_PLAN.md", "CURRENT_HANDOFF.md",
        "LIVE_READINESS.md", "NOVEMBER_LIVE_UPGRADE.md", "PROGRESS.md", "README.md"]]
    paths.append(OUT/"README.md")
    lines, broken, foreign, count = {}, [], [], 0
    for path in paths:
        value = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()
        lines[relative] = len(value.splitlines())
        for no, line in enumerate(value.splitlines(), 1):
            if re.search(r"[\u3001\u3002\u3040-\u30ff\u4e00-\u9fff]", line):
                foreign.append([relative, no])
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", value):
            if target.startswith(("https://", "http://", "#")):
                continue
            count += 1
            if not (path.parent/target.split("#", 1)[0].strip("<>")).exists():
                broken.append([relative, target])
    def night(value):
        return value.replace("\r\n", "\n").split("### 야간 작업 원칙", 1)[1].split("### 보완 뒤에도", 1)[0]
    before = git("show", SUBMISSION+":docs/LIMITED_LIVE_TEST_PLAN.md").decode()
    return dict(lines=lines, links=count, broken_links=broken, foreign_characters=foreign,
        night_rule_unchanged=night(before)==night(paths[1].read_text(encoding="utf-8")),
        recent_progress_items=len(re.findall(r"^### \d+차", paths[5].read_text(encoding="utf-8"), re.M)))


result = dict(reviewed_main=git("rev-parse", SUBMISSION).decode().strip(), manifests={}, runs={}, jars={}, n03={})
for name in ["v167-race-20261115", "review-40bf70b9", "n03-1113-2200", "n03-1114-1000", "n03-1114-2200", "n03-1115-1000"]:
    folder = ROOT / "docs/evidence" / name
    result["manifests"][name] = manifest(folder)
    if name.startswith("n03-"):
        report_names = [n for _, n in checksum_rows(folder/"local-sha256.txt") if n.endswith((".txt", ".msg", ".raw"))]
        remote = dict((n, h) for h, n in checksum_rows(folder/"local-sha256.txt"))
        server = {match.group(2): match.group(1) for line in (folder/"server-files.txt").read_text(encoding="utf-8").splitlines()
                  if (match := re.match(r"^([0-9a-f]{64})\s+\*?(.+)$", line))}
        result["n03"][name] = dict(server_report_hashes={n: digest((folder/n).read_bytes())==remote[n] for n in report_names},
            server_record_matches_local={n: remote[n]==server.get(n) for n in report_names},
            runtime=(folder/"run-time.txt").read_text(encoding="utf-8-sig"),
            last_send=(folder/"last_send").read_text(encoding="utf-8-sig"))
for name in ["r10-h2-base", "r11-pg-base", "r14-rel71-99e6ab10", "pg99-SubmitPollRacePgTest",
             "pg99-SettlementRoundingPgTest", "pgbase-SettlementRoundingPgTest", "full-99e6ab10"]:
    result["runs"][name] = junit(BASE/"runs"/name)
result["existing_pg"] = {p.name: junit(p)["totals"] for p in sorted((BASE/"runs").glob("pg99-*"))}
result["refs"] = {ref: git("rev-parse", ref).decode().strip() for ref in ["HEAD", "origin/main",
    "candidate/order-poll-race-b987d7a7", "origin/candidate/order-poll-race-b987d7a7", "integration/v158"]}
result["product_paths"] = git("diff", "--name-only", "b987d7a7", "99e6ab10", "--", ":(exclude)web-api/src/test/").decode().splitlines()
jar_paths = {"b5cd1d0": Path("D:/tmp/o1o3/jars/b5cd1d0.jar"),
             "88f06345": Path("D:/tmp/v159/jar/4a4a04a3.jar"),
             "99e6ab10": Path("D:/tmp/v167-ev/jars/99e6ab10.jar")}
contents = {}
for name, path in jar_paths.items():
    entries = jar_entries(path)
    listing = "".join(k+"\t"+entries[k]+"\n" for k in sorted(entries)).encode()
    contents[name] = entries
    result["jars"][name] = dict(file_sha256=digest(path.read_bytes()), entries=len(entries), manifest_sha256=digest(listing))
    if name == "99e6ab10":
        assert listing == (BASE/"jars/manifest-99e6ab10.txt").read_bytes()
for old in ["b5cd1d0", "88f06345"]:
    a, b = contents[old], contents["99e6ab10"]
    result["jars"][old]["diff_to_candidate"] = {kind: [k for k in sorted(set(a)|set(b)) if
        ((kind=="added" and k not in a) or (kind=="deleted" and k not in b) or
         (kind=="changed" and k in a and k in b and a[k]!=b[k]))] for kind in ["changed", "added", "deleted"]}
blocks = BASE / "d4/blocks-d4"
result["block_manifest"] = manifest(BASE/"d4/blocks-d4.sha256", source_base=blocks)
u1 = (blocks/"U1.sh").read_text(encoding="utf-8")
embedded = u1.split('python3 - "$M/app-new.jar" > "$M/jar-manifest-new.txt"', 1)[1].split("\n", 1)[1].split("\nEOF", 1)[0]+"\n"
result["jarm_embedded_matches_file"] = embedded==(BASE/"jarmanifest.py").read_text(encoding="utf-8")
def entries_ast(path):
    return ast.dump(next(node for node in ast.parse(path.read_text(encoding="utf-8")).body
                         if isinstance(node, ast.FunctionDef) and node.name=="entries"))
result["entries_function_ast_matches_comparator"] = entries_ast(BASE/"jarmanifest.py")==entries_ast(BASE/"jarcmp_v2.py")
result["rarx_same_as_v161"] = (BASE/"jars/rarx-v161.txt").read_bytes()==(BASE/"jars/rarx-99e6.txt").read_bytes()
result["standin_local_raw"] = manifest(BASE/"d4/standin/runs-r1.sha256", source_base=Path("D:/tmp/v167d4s/sim/runs"), committed=False)
result["bash_syntax"] = {path.name: subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc", "-n", str(path)],
        cwd=ROOT, capture_output=True).returncode for path in sorted(blocks.glob("*.sh"))}
result["documents"] = document_checks()
for key in ["jarm_embedded_matches_file", "entries_function_ast_matches_comparator", "rarx_same_as_v161"]:
    assert result[key]
assert all(not (row["worktree_mismatch"] or row["blob_mismatch"]) for row in result["manifests"].values())
assert all(all(row["server_record_matches_local"].values()) for row in result["n03"].values())
assert all(value==0 for value in result["bash_syntax"].values())
assert not result["documents"]["broken_links"] and not result["documents"]["foreign_characters"]
assert result["documents"]["night_rule_unchanged"] and result["documents"]["recent_progress_items"]==5
(OUT/"checks.json").write_text(json.dumps(result, ensure_ascii=True, indent=2)+"\n", encoding="utf-8")
print(json.dumps(dict(manifests=result["manifests"], runs={n: x["totals"] for n,x in result["runs"].items()},
    jarm={n: {k:v for k,v in x.items() if k!="diff_to_candidate"} for n,x in result["jars"].items()},
    standin_local_raw=result["standin_local_raw"], product_paths=result["product_paths"]), indent=2))
