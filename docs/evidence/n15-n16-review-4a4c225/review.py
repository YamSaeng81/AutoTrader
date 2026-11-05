"""Read-only review of preserved evidence and fixed candidate; no app test or network."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = "4a4c225"
BASE = "fe01207f3f60e97e308925157f84a1bb2da04388"
CANDIDATE = "3385f70902999210dafe7774570fdc69d1a1fe82"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def source(ref, path):
    return git("show", f"{ref}:{path}").decode("utf-8")


def digest(b):
    return hashlib.sha256(b).hexdigest()


def manifest(name, expected):
    folder = ROOT / "docs/evidence" / name
    rows = []
    for line in (folder / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        wanted, path = line.split(" ", 1)
        path = path.strip().lstrip("*")
        full = folder / path
        raw = full.read_bytes()
        blob = git("show", f"{REF}:{full.relative_to(ROOT).as_posix()}")
        assert digest(raw) == digest(blob) == wanted, path
        rows.append({"path": path, "sha256": wanted})
    assert len(rows) == expected
    return rows


def suite(path):
    root = ET.parse(path).getroot()
    return {"file": path.relative_to(ROOT).as_posix(), "tests": int(root.attrib["tests"]),
            "failures": int(root.attrib["failures"]), "errors": int(root.attrib["errors"]),
            "skipped": int(root.attrib.get("skipped", "0"))}


checksums = {"n15": manifest("n15-entry-delay-fe01207", 7),
             "n16": manifest("n16-invalid-price-fe01207", 20)}
n15 = ROOT / "docs/evidence/n15-entry-delay-fe01207"
n16 = ROOT / "docs/evidence/n16-invalid-price-fe01207"
n15_xml = [suite(p) for p in sorted((n15 / "run2").glob("TEST-*.xml"))]
n16_before = [suite(p) for p in sorted((n16 / "before").glob("TEST-*.xml"))]
n16_after = [suite(p) for p in sorted((n16 / "after").glob("TEST-*.xml"))]
assert len(n15_xml) == 1 and n15_xml[0]["tests"] == 3 and n15_xml[0]["failures"] == 0
assert len(n16_before) == 1 and n16_before[0]["tests"] == 9 and n16_before[0]["failures"] == 7
assert len(n16_after) == 10 and sum(s["tests"] for s in n16_after) == 76
assert all(s["failures"] == s["errors"] == s["skipped"] == 0 for s in n16_after)

assert git("merge-base", BASE, CANDIDATE).decode().strip() == BASE
assert git("rev-parse", f"{CANDIDATE}^").decode().strip() == BASE
paths = git("diff", "--name-only", BASE, CANDIDATE).decode().splitlines()
prefix = "web-api/src/main/java/com/cryptoautotrader/api/service/"
main_paths = [p for p in paths if "/src/main/" in p]
assert main_paths == [prefix + n + ".java" for n in
                      ["DynamicTradingService", "LiveTradingService", "MarkPriceBook"]]
assert len(paths) == 4 and paths[-1].endswith("/N16InvalidPriceReproTest.java")
test_blob = git("show", f"{CANDIDATE}:{paths[-1]}")
assert test_blob == (n16 / "tests/N16InvalidPriceReproTest.java").read_bytes()
diff = git("diff", BASE, CANDIDATE, "--", *main_paths)
assert diff == (n16 / "diff-fe01207-3385f70-main.txt").read_bytes()

for engine in ["LiveTradingService", "DynamicTradingService"]:
    code = source(CANDIDATE, prefix + engine + ".java")
    method = code[code.index("private void doOnRealtimePriceEvent("):]
    guard = method.index("if (!MarkPriceBook.isPrice(price))")
    assert guard < method.index("updatePriceHistory(") if engine == "LiveTradingService" else guard < method.index("rtCheckLastMs.put(")
    assert "return;" in method[guard:guard + 380]

rounds = source(BASE, prefix + "EntryPriorityRounds.java")
prereg = source(REF, "docs/LIVE_ENTRY_PREREG.md")
assert "최대 5분" in prereg
assert "reason = c.approve().get();" in rounds
assert "now.isAfter(deadline(c.round()))" in rounds
assert "Clock" not in rounds
mark = source(CANDIDATE, prefix + "MarkPriceBook.java")
assert "price != null && price.signum() > 0" in mark
assert "neu.at().isBefore(old.at())" in mark
assert "Duration.between(m.at(), now).getSeconds() > maxAgeSeconds" in mark

result = {
    "reviewed_commit": git("rev-parse", REF).decode().strip(),
    "base": BASE, "candidate": CANDIDATE, "manifests": checksums,
    "n15_xml": n15_xml, "n16_before_xml": n16_before, "n16_after_xml": n16_after,
    "candidate_changed_files": paths,
    "candidate_test_and_diff_match_preserved_evidence": True,
    "app_tests_rerun": False, "server_db_or_external_access": False,
    "limits": [
        "N15 processDue uses a future argument while engine clocks remain wall clock; not a real 61-minute wait.",
        "N15 slow-head case demonstrates propagation for 1.5 seconds, not crossing a proposed expiry boundary.",
        "N16 76 includes 9 N16 cases; two passing cases preserve current future-time and EPOCH behavior.",
        "Production malformed input frequency and current holdings were not newly queried."
    ]
}
OUT.mkdir(parents=True, exist_ok=True)
(OUT / "review.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"evidence_git_and_worktree": [7, 20], "n15_preserved_xml": "3/3",
                  "n16_before_failures": "7/9", "n16_after_xml": "76/76 (includes 9 N16)",
                  "production_files": len(main_paths), "test_rerun": False}, ensure_ascii=False))
