"""Local-only review of the exact K1/newsobs code from committed 350efd1."""
from pathlib import Path
import datetime as dt
import hashlib
import io
import json
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "core-engine/build/review-k20-350efd1"
OUT.mkdir(parents=True, exist_ok=True)
PYTHON = sys.executable
HEAD = "350efd14205526f9f8eb91b683349ce9e10ab48c"
PLAN = subprocess.check_output(["git", "show", HEAD + ":docs/LIMITED_LIVE_TEST_PLAN.md"], cwd=ROOT).decode().replace("\r\n", "\n")
BLOCKS = re.findall(r"```bash\n(.*?)\n```", PLAN.split("**K 변수**", 1)[1].split("## 6. 시작·중단·종료 조건", 1)[0], re.S)
K1 = BLOCKS[2].split("<<'EOF' | tee \"$M/jar-diff.txt\"\n", 1)[1].split("\nEOF", 1)[0]
NEWS = BLOCKS[0].split('newsobs() { python3 - "$@" <<\'PYEOF\'\n', 1)[1].split("\nPYEOF", 1)[0]
(OUT / "k1-exact.py").write_text(K1 + "\n", encoding="utf-8")
(OUT / "newsobs-exact.py").write_text(NEWS + "\n", encoding="utf-8")

base = ROOT.parent / "cat-n08/web-api/build/libs/web-api-0.1.0.jar"
candidate = ROOT.parent / "cat-n11/web-api/build/libs/web-api-0.1.0.jar"
expected_jar_sha = (ROOT / "docs/evidence/k20-prep-b1d7664/jar-sha.txt").read_text().splitlines()
for path, line in zip((base, candidate), expected_jar_sha):
    assert hashlib.sha256(path.read_bytes()).hexdigest() == line.split()[0]
scope = {}
exec(K1.split("a, b = classes", 1)[0], scope)
def compare(a, b):
    keys = sorted(set(a) | set(b))
    lines = [("추가 " if k not in a else "삭제 " if k not in b else "변경 ") + k for k in keys if a.get(k) != b.get(k)]
    return "\n".join(lines + ["(끝) %d %d" % (len(a), len(b))]) + "\n"
def full(path):
    # Same flattening of internal modules as K1; include every other non-directory entry too.
    out = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith("/"):
                continue
            if scope["OWN"].match(n):
                with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                    for m in inner.namelist():
                        if not m.endswith("/"):
                            out[re.sub(r"-[0-9.]+(-SNAPSHOT)?\.jar$", ".jar", n) + "!" + m] = hashlib.sha256(inner.read(m)).hexdigest()
            else:
                out[n] = hashlib.sha256(z.read(n)).hexdigest()
    return out
a = scope["classes"](base)
baseline = compare(a, scope["classes"](candidate))
assert baseline == (ROOT / "docs/evidence/k20-prep-b1d7664/jar-diff-k1.txt").read_text()
(OUT / "jar-baseline.txt").write_text(baseline, encoding="utf-8")
fullbaseline = compare(full(base), full(candidate))
assert fullbaseline.rstrip() == (ROOT / "docs/evidence/k20-prep-b1d7664/jar-diff-all-entries.txt").read_text().split("\n{'other'", 1)[0].rstrip()
jar_cases = []
with zipfile.ZipFile(candidate) as z:
    loader = next(n for n in z.namelist() if n.startswith("org/springframework/boot/loader/") and n.endswith(".class"))
targets = ["META-INF/MANIFEST.MF", "BOOT-INF/classpath.idx", loader]
for i, target in enumerate(targets, 1):
    mutated = OUT / "mutated-candidate.jar"
    with zipfile.ZipFile(candidate) as source, zipfile.ZipFile(mutated, "w") as dest:
        for info in source.infolist():
            data = source.read(info.filename)
            if info.filename == target:
                if target.endswith("MANIFEST.MF"):
                    data = data.replace(b"Start-Class: com.cryptoautotrader.api.CryptoAutoTraderApplication", b"Start-Class: example.WrongApplication")
                    assert b"example.WrongApplication" in data
                elif target.endswith(".idx"):
                    data = data.replace(b"\n", b"\n# review changed index\n", 1)
                else:
                    data = bytes([data[0] ^ 1]) + data[1:]
            dest.writestr(info, data)
    r = subprocess.run([PYTHON, "-X", "utf8", str(OUT / "k1-exact.py"), str(base), str(mutated)], capture_output=True, encoding="utf-8")
    # The actual K1 shell accepts only the fixed nine lines and exact count.
    passed = r.returncode == 0 and r.stdout == baseline
    actual = compare(full(base), full(mutated))
    assert passed and "변경 " + target in actual
    (OUT / ("jar-case-%d-k1.txt" % i)).write_text(r.stdout, encoding="utf-8")
    (OUT / ("jar-case-%d-full.txt" % i)).write_text(actual, encoding="utf-8")
    jar_cases.append({"target": target, "k1_exit": r.returncode, "k1_gate_passed": passed, "full_comparison_detected": True, "mutated_jar_sha256": hashlib.sha256(mutated.read_bytes()).hexdigest()})

start = int(dt.datetime(2026, 11, 4, 3, 0, tzinfo=dt.timezone.utc).timestamp())
observed = start + 35 * 60
news_cases = []
cases = [
    ("not_due_control", start - 60, 120, start - 60, 0),
    ("due_from_null_no_attempt_no_log", "-", 30, "-", 0),
    ("due_from_old_no_attempt_no_log", start - 7200, 30, start - 7200, 0),
    ("attempt_advanced_no_log_control", start - 7200, 30, start + 180, 2),
]
for name, before_lf, interval, now_lf, expected in cases:
    before = OUT / (name + "-before.txt")
    now = OUT / (name + "-now.txt")
    log = OUT / (name + ".log")
    before.write_text("tokenpost_rss|RSS|t|%d|%s\n" % (interval, before_lf), encoding="utf-8")
    now.write_text("tokenpost_rss|RSS|t|%d|%s\n" % (interval, now_lf), encoding="utf-8")
    log.write_text("", encoding="utf-8")
    r = subprocess.run([PYTHON, "-X", "utf8", str(OUT / "newsobs-exact.py"), str(log), str(before), str(now), str(start)], capture_output=True, encoding="utf-8")
    assert r.returncode == expected
    (OUT / (name + "-stdout.txt")).write_text(r.stdout, encoding="utf-8")
    (OUT / (name + "-stderr.txt")).write_text(r.stderr, encoding="utf-8")
    news_cases.append({"case": name, "start_epoch": start, "observation_epoch": observed, "before_last_fetched": before_lf, "now_last_fetched": now_lf, "interval_min": interval, "exit": r.returncode, "stdout": r.stdout, "stderr": r.stderr})

review = {"source_sha": HEAD, "candidate_sha": "b1d7664db73222aec5d86f2e9137debef0ff4df3", "jar_cases": jar_cases, "news_cases": news_cases, "external_http_calls": 0, "db_calls": 0, "ssh_calls": 0, "production_mutations": 0}
(OUT / "review.json").write_text(json.dumps(review, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(review, ensure_ascii=False, indent=2))
