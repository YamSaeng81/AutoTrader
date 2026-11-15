"""Verify fixed submitted artifacts without running product or test workloads."""
from pathlib import Path
import ast
import hashlib
import json
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SUBMISSION = "d5790c1c"
BASE = ROOT / "docs/evidence/v168-final-20261115"

helper_source = subprocess.check_output(["git", "show", SUBMISSION+":docs/evidence/review-7e77e34a/audit_review.py"], cwd=ROOT).decode()
helper_tree = ast.parse(helper_source)
helpers = ast.Module(body=[node for node in helper_tree.body
    if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef))], type_ignores=[])
scope = dict(ROOT=ROOT, OUT=OUT, SUBMISSION=SUBMISSION)
exec(compile(helpers, "fixed-review-helpers", "exec"), scope)
git, digest, manifest, junit, checksum_rows, jar_entries = [scope[name]
    for name in ["git", "digest", "manifest", "junit", "checksum_rows", "jar_entries"]]

result = dict(reviewed_main=git("rev-parse", SUBMISSION).decode().strip(), manifests={}, runs={})
for name in ["v168-final-20261115", "am-1116-0800", "n03-1115-2200", "review-7e77e34a"]:
    result["manifests"][name] = manifest(ROOT/"docs/evidence"/name)
for name in ["full-0e1d2a02", "full-diag-be7e8e4f-trace", "h01-h2", "h02-pg", "h03-neg-h2",
             "t02-fixture-group", "t03-boundary-probe", "t05-roundkeys",
             "t06-pg-AlignedV96PgBehaviorTest", "t06-pg-BalanceListSnapshotPgTest", "t06-pg-PerformanceSnapshotS5PgTest"]:
    result["runs"][name] = junit(BASE/"runs"/name)
result["refs"] = {name: git("rev-parse", name).decode().strip() for name in [
    "HEAD", "origin/main", "candidate/order-poll-race-b987d7a7", "origin/candidate/order-poll-race-b987d7a7"]}
result["morning_handoff"] = dict(commit=git("rev-parse", "9a0b47dc").decode().strip(),
    paths=git("diff", "--name-only", SUBMISSION, "9a0b47dc").decode().splitlines())
assert result["morning_handoff"]["paths"] == ["docs/CURRENT_HANDOFF.md"]
result["candidate_test_paths"] = git("diff", "--name-only", "99e6ab10", "0e1d2a02").decode().splitlines()
result["non_test_trees"] = {}
result["all_modules_non_test_trees"] = {}
for ref in ["99e6ab10", "0e1d2a02"]:
    rows = [line for line in git("ls-tree", "-r", ref).decode().splitlines() if "\tweb-api/src/test/" not in line]
    result["non_test_trees"][ref] = dict(count=len(rows), sha256=digest(("\n".join(rows)+"\n").encode()))
    product_rows = [line for line in rows if "/src/test/" not in line.split("\t", 1)[1]]
    result["all_modules_non_test_trees"][ref] = dict(count=len(product_rows),
        sha256=digest(("\n".join(product_rows)+"\n").encode()))

jar_paths = [Path("D:/tmp/v168-ev/jars/0e1d2a02.jar"), Path("D:/tmp/v167-ev/jars/99e6ab10.jar"),
    Path("D:/tmp/o1o3/jars/b5cd1d0.jar"), Path("D:/tmp/v159/jar/4a4a04a3.jar")]
result["jars"], jar_hashes = {}, set()
for path in jar_paths:
    file_hash = digest(path.read_bytes())
    jar_hashes.add(file_hash)
    contents = jar_entries(path)
    listing = "".join(key+"\t"+contents[key]+"\n" for key in sorted(contents)).encode()
    result["jars"][path.name] = dict(sha256=file_hash, entries=len(contents), manifest_sha256=digest(listing))

blocks = BASE/"d4/blocks-d4"
result["blocks"] = manifest(BASE/"d4/blocks-d4.sha256", source_base=blocks)
result["bash_syntax"] = {path.name: subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc", "-n", str(path)],
    cwd=ROOT, capture_output=True).returncode for path in sorted(blocks.glob("*.sh"))}
diff_names = git("diff", "--name-only", "b5cd1d0", "0e1d2a02").decode()
result["diffn"] = dict(count=len(diff_names.splitlines()),
    preserved_exact=diff_names.encode()==(BASE/"d4/diffn-b5cd1d0-0e1d2a02.txt").read_bytes())
def bash_blocks(ref, name):
    data = git("show", ref+":"+name).decode()
    return re.findall(r"```bash\r?\n(.*?)```", data, re.S)
f_new = bash_blocks(SUBMISSION, "docs/LIMITED_LIVE_TEST_RUNBOOK.md")[0]
f_old = bash_blocks("c8520c55", "docs/LIMITED_LIVE_TEST_RUNBOOK.md")[0]
g_hash = "4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822"
g_matches = {}
for ref in [SUBMISSION, "c8520c55"]:
    data = bash_blocks(ref, "docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md")
    g_matches[ref] = [digest(value.encode()) for value in data if digest(value.replace("\r\n", "\n").encode())==g_hash]
result["fg"] = dict(f_exact=f_new==f_old, f_sha256=digest(f_new.encode()), g_matches=g_matches)

result["standin_archives"] = {}
for version in ["167", "168"]:
    prefix = BASE/"standin-archive"/('standin-v'+version+'-r1')
    expected = {name.removeprefix("./"): value for value,name in checksum_rows(Path(str(prefix)+"-list.sha256"))}
    actual = {}
    with tarfile.open(str(prefix)+"-nojar.tar.gz", "r|gz") as archive:
        for member in archive:
            if member.isfile():
                actual[member.name.removeprefix("./")] = digest(archive.extractfile(member).read())
    refs = checksum_rows(Path(str(prefix)+"-jar-refs.txt"))
    for value,name in refs:
        assert value in jar_hashes
        actual[name.removeprefix("./")] = value
    result["standin_archives"][version] = dict(expected=len(expected), actual=len(actual),
        mismatch=[name for name,value in expected.items() if actual.get(name)!=value],
        extra=sorted(set(actual)-set(expected)), jar_references=len(refs))

probe_runs, xml_totals = {}, dict(tests=0, failures=0, errors=0, skipped=0)
with tarfile.open(BASE/"probe/probe-xml-overnight-1116.tar.gz", "r|gz") as archive:
    for member in archive:
        if member.isfile() and "/TEST-" in member.name and member.name.endswith(".xml"):
            run = re.search(r"f-\d{4}-\d{6}", member.name).group()
            tree = ET.parse(archive.extractfile(member)).getroot()
            counts = probe_runs.setdefault(run, dict(tests=0, failures=0, errors=0, skipped=0, xml_files=0))
            counts["xml_files"] += 1
            for key in xml_totals:
                value = int(tree.get(key, 0))
                counts[key] += value
                xml_totals[key] += value
tsv = {}
for run in probe_runs:
    path = BASE/"probe/runs"/run/"probe-times.tsv"
    lines = path.read_text(encoding="utf-8").splitlines()
    tsv[run] = dict(rows=len(lines), failed=sum(line.split("\t")[-1]!="OK" for line in lines))
diagnostics = 0
with tarfile.open(BASE/"probe/probe-gradlelog-overnight-1116.tar.gz", "r|gz") as archive:
    for member in archive:
        if member.isfile():
            diagnostics += archive.extractfile(member).read().count(b"[N15PROBE]")
result["overnight_probe"] = dict(run_count=len(probe_runs), xml_totals=xml_totals,
    xml_runs=probe_runs, tsv_runs=tsv, diagnostic_occurrences=diagnostics,
    matching_counts=all(probe_runs[run]["tests"]==tsv[run]["rows"] for run in probe_runs))
result["documents"] = scope["document_checks"]()
assert all(not (row["worktree_mismatch"] or row["blob_mismatch"]) for row in result["manifests"].values())
assert all(name.startswith("web-api/src/test/") for name in result["candidate_test_paths"])
assert result["non_test_trees"]["99e6ab10"]==result["non_test_trees"]["0e1d2a02"]
assert result["all_modules_non_test_trees"]["99e6ab10"] == result["all_modules_non_test_trees"]["0e1d2a02"]
assert result["fg"]["f_exact"] and all(len(x)==1 for x in g_matches.values())
assert result["diffn"]["preserved_exact"] and all(code==0 for code in result["bash_syntax"].values())
assert all(not (row["mismatch"] or row["extra"]) for row in result["standin_archives"].values())
assert not result["documents"]["broken_links"] and not result["documents"]["foreign_characters"]
assert result["documents"]["night_rule_unchanged"] and result["documents"]["recent_progress_items"] == 5
assert result["overnight_probe"]["run_count"] == 108 and xml_totals == dict(tests=14688, failures=0, errors=0, skipped=0)
assert result["overnight_probe"]["matching_counts"] and diagnostics == 0
(OUT/"checks.json").write_text(json.dumps(result, ensure_ascii=True, indent=2)+"\n", encoding="utf-8")
print(json.dumps(dict(manifests=result["manifests"], runs={k:v["totals"] for k,v in result["runs"].items()},
    non_test_trees=result["non_test_trees"], fg=result["fg"], diffn=result["diffn"],
    standin_archives=result["standin_archives"],
    overnight_probe={key:value for key,value in result["overnight_probe"].items() if key not in ["xml_runs", "tsv_runs"]}), indent=2))
