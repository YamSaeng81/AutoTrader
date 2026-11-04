"""Local-only verification of committed b577f67 deployment-procedure fixes."""
from pathlib import Path
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "core-engine/build/review-k20-b577f67"
OUT.mkdir(parents=True, exist_ok=True)
SOURCE = "b577f673e700e166c949b225272dc959be071ed7"
OLD = "350efd14205526f9f8eb91b683349ce9e10ab48c"
PYTHON = sys.executable
BASH = r"C:\Program Files\Git\bin\bash.exe"
def gitfile(sha, path):
    return subprocess.check_output(["git", "show", sha + ":" + path], cwd=ROOT)
def sha(data):
    return hashlib.sha256(data).hexdigest()
def blocks(text):
    return re.findall(r"```bash\n(.*?)\n```", text.replace("\r\n", "\n"), re.S)
plan = gitfile(SOURCE, "docs/LIMITED_LIVE_TEST_PLAN.md").decode()
prior = gitfile(OLD, "docs/LIMITED_LIVE_TEST_PLAN.md").decode()
bb, oldbb = blocks(plan), blocks(prior)
assert len(bb) == 76 and len(oldbb) == 71 and bb[:71] == oldbb
new = bb[-5:]
names = ["kvars", "k1", "k3", "k4", "knews"]
current = dict(zip(names, new))
oldblocks = blocks(prior.split("**K 변수**", 1)[1])[:8]
for name, idx in [("k0", 1), ("k2", 3), ("krollback", 7)]:
    current[name] = oldblocks[idx]
expected = json.loads((ROOT / "docs/evidence/k20-fix-k1-newsobs/block-hashes.json").read_text())
syntax = []
for name, body in current.items():
    data = (body + "\n").encode()
    assert sha(data) == expected[name], name
    path = OUT / (name + ".sh")
    path.write_bytes(data)
    r = subprocess.run([BASH, "--noprofile", "--norc", "-n", path.as_posix()], capture_output=True)
    assert r.returncode == 0, (name, r.stderr)
    syntax.append({"block": name, "sha256": sha(data), "bash_n_exit": r.returncode})
manifests = []
for folder, count in [("k20-fix-k1-newsobs", 28), ("k20-review-350efd1", 14)]:
    path = ROOT / "docs/evidence" / folder
    rows = []
    for line in (path / "SHA256SUMS").read_text().splitlines():
        expected_sha, name = line.split(maxsplit=1)
        name = name.lstrip("*")
        local = (path / name).read_bytes()
        committed = gitfile(SOURCE, (path / name).relative_to(ROOT).as_posix())
        assert sha(local) == expected_sha == sha(committed), name
        rows.append({"path": name, "sha256": expected_sha, "working_and_commit_match": True})
    assert len(rows) == count
    manifests.append({"folder": folder, "count": len(rows), "files": rows})
audit = {"source_sha": SOURCE, "unchanged_existing_bash_blocks": len(oldbb), "total_bash_blocks": len(bb), "blocks": syntax, "manifests": manifests}
(OUT / "submitted-check.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

k1 = current["k1"].split("<<'EOF' | tee \"$M/jar-diff.txt\"\n", 1)[1].split("\nEOF", 1)[0]
news = current["kvars"].split('newsobs() { python3 - "$@" <<\'PYEOF\'\n', 1)[1].split("\nPYEOF", 1)[0]
(OUT / "k1-exact.py").write_text(k1 + "\n", encoding="utf-8")
(OUT / "newsobs-exact.py").write_text(news + "\n", encoding="utf-8")
base = ROOT.parent / "cat-n08/web-api/build/libs/web-api-0.1.0.jar"
candidate = ROOT.parent / "cat-n11/web-api/build/libs/web-api-0.1.0.jar"
hashes = ["f07b455e5f4bfbd8db388ec7b17f15ec2a191f638f9dad6d8d5e40c27409d0d9", "a0cb288509ee08e7f09a22bdb5936f3684bae9a7a53ccdaf9affd164a8415ab5"]
for path, checksum in zip([base, candidate], hashes):
    assert sha(path.read_bytes()) == checksum
def compare(path):
    return subprocess.run([PYTHON, "-X", "utf8", str(OUT / "k1-exact.py"), str(base), str(path)], capture_output=True, encoding="utf-8")
baseline = compare(candidate)
assert baseline.returncode == 0 and baseline.stdout == (ROOT / "docs/evidence/k20-fix-k1-newsobs/jar-diff-k1v2.txt").read_text(encoding="utf-8")
assert baseline.stdout.endswith("(끝) 877 879\n")
(OUT / "jar-baseline.txt").write_text(baseline.stdout, encoding="utf-8")
jar_cases = []
targets = ["META-INF/MANIFEST.MF", "BOOT-INF/classpath.idx", "org/springframework/boot/loader/jar/ManifestInfo.class", None]
for i, target in enumerate(targets, 1):
    mutated = OUT / "mutated-candidate.jar"
    with zipfile.ZipFile(candidate) as src, zipfile.ZipFile(mutated, "w") as dest:
        for info in src.infolist():
            data = src.read(info)
            if info.filename == target:
                if target.endswith("MANIFEST.MF"):
                    data = data.replace(b"Start-Class: com.cryptoautotrader.api.CryptoAutoTraderApplication", b"Start-Class: example.WrongApplication")
                    assert b"example.WrongApplication" in data
                elif target.endswith(".idx"):
                    data += b"\n# review changed index\n"
                else:
                    data = bytes([data[0] ^ 1]) + data[1:]
            if target is None:
                info.date_time = (2026, 11, 4, 0, 0, 0)
                info.compress_type = zipfile.ZIP_STORED
            dest.writestr(info, data)
    r = compare(mutated)
    accepted = r.returncode == 0 and r.stdout == baseline.stdout
    assert accepted == (target is None)
    if target:
        assert "변경 " + target in r.stdout
    name = "jar-case-%d.txt" % i
    (OUT / name).write_text(r.stdout, encoding="utf-8")
    jar_cases.append({"target": target or "zip_metadata_only", "comparator_exit": r.returncode, "fixed_expectation_gate_passed": accepted, "output_file": name})

start = int(dt.datetime(2026, 11, 4, 3, 0, tzinfo=dt.timezone.utc).timestamp())
previous_cases = {r["case"]: r for r in json.loads((ROOT / "docs/evidence/k20-review-350efd1/review.json").read_text(encoding="utf-8"))["news_cases"]}
cases = [("not_due_control", start + 2100, 0), ("due_from_null_no_attempt_no_log", start + 2100, 3), ("due_from_old_no_attempt_no_log", start + 2100, 3), ("attempt_advanced_no_log_control", start + 2100, 2), ("due_from_null_no_attempt_no_log", start + 179, 0), ("due_from_null_no_attempt_no_log", start + 180, 3)]
news_cases = []
for i, (name, obs, expected_exit) in enumerate(cases, 1):
    paths = [OUT / (name + suffix) for suffix in [".log", "-before.txt", "-now.txt"]]
    previous = previous_cases[name]
    data = ["", "tokenpost_rss|RSS|t|%s|%s\n" % (previous["interval_min"], previous["before_last_fetched"]), "tokenpost_rss|RSS|t|%s|%s\n" % (previous["interval_min"], previous["now_last_fetched"])]
    for path, content in zip(paths, data):
        path.write_text(content, encoding="utf-8")
    r = subprocess.run([PYTHON, "-X", "utf8", str(OUT / "newsobs-exact.py"), *map(str, paths), str(start), str(obs)], capture_output=True, encoding="utf-8")
    assert r.returncode == expected_exit, (name, r.returncode, r.stdout, r.stderr)
    if expected_exit == 3:
        assert "⚪" in r.stdout and "확인 필요" in r.stdout and "정상 관측으로 세지 않는다" in r.stdout
    news_cases.append({"case": name, "start_epoch": start, "observation_epoch": obs, "exit": r.returncode, "stdout": r.stdout, "stderr": r.stderr, "inputs_sha256": {p.name: sha(p.read_bytes()) for p in paths}})
review = {"source_sha": SOURCE, "candidate_sha": "b1d7664db73222aec5d86f2e9137debef0ff4df3", "jar_cases": jar_cases, "news_cases": news_cases, "white_continuation_static_check": {"k3_no3_allowed": '[ "$NO" = 0 ] || [ "$NO" = 3 ]' in current["k3"], "k4_no3_allowed": '[ "$NO" = 0 ] || [ "$NO" = 3 ]' in current["k4"], "k4_does_not_claim_news_complete": "뉴스 관측 완료로 보고하지 않는다" in current["k4"]}, "external_http_calls": 0, "db_calls": 0, "ssh_calls": 0, "production_mutations": 0, "app_tests_or_rebuilds": 0}
assert all(review["white_continuation_static_check"].values())
(OUT / "review.json").write_text(json.dumps(review, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"evidence_manifests": [m["count"] for m in manifests], "syntax": len(syntax), "existing_bash_unchanged": len(oldbb), "jar_cases": jar_cases, "news_exits": [r["exit"] for r in news_cases]}, ensure_ascii=False, indent=2))
