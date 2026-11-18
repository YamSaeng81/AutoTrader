"""434차 고정 출처와 배치 기록 분기만 검토. 실제 archive/SSH/DB/POST 없음."""
import hashlib
import json
import re
import shlex
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
REV = "e0119f0a4eff0432ddc47e13393b3cbf4e84bfd1"
PREV = "dd50e9068b4362852629c42de5f9027a82d1d6ce"
B = "docs/evidence/ab-create-v424-20261118/"
BASH = "C:/Program Files/Git/bin/bash.exe"
checks, manifests, probes = [], [], []


def check(name, ok):
    checks.append({"name": name, "passed": bool(ok)})


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def blob(path, rev=REV):
    return git("show", rev + ":" + path)


for base, listing in ((B, "SHA256SUMS"), (B, "bundle/SHA256SUMS.bundle"),
                       ("docs/evidence/review-dd50e906/", "SHA256SUMS")):
    raw = blob(base + listing)
    check(base + listing + " working bytes", raw == (ROOT / base / listing).read_bytes())
    rows = raw.decode("utf-8-sig").splitlines()
    for row in rows:
        m = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", row)
        assert m, row
        digest, rel = m.groups()
        data = blob(base + rel)
        check(base + rel + " committed digest and working bytes", hashlib.sha256(data).hexdigest() == digest and data == (ROOT / base / rel).read_bytes())
    manifests.append({"folder": base, "list": listing, "matched": len(rows)})
check("HEAD and local origin/main refs", all(git("rev-parse", ref).decode().strip() == REV for ref in ("HEAD", "origin/main")))
check("no product/build changes", not git("diff", "--name-only", PREV, REV, "--", ".", ":!docs").strip())
check("sixteen-file bundle unchanged", blob(B + "bundle/SHA256SUMS.bundle") == blob(B + "bundle/SHA256SUMS.bundle", PREV))
for row in blob(B + "bundle/SHA256SUMS.bundle").decode().splitlines():
    rel = re.fullmatch(r"[0-9a-f]{64} [ *](.+)", row).group(1)
    check("unchanged frozen input " + rel, blob(B + rel) == blob(B + rel, PREV))
check("dated step file unchanged", blob(B + "steps-20261118.sh") == blob(B + "steps-20261118.sh", PREV))
deploy = blob(B + "deploy-20261118.sh").decode()
check("new deploy digest", hashlib.sha256(deploy.encode()).hexdigest() == "57fefb0f2065aa501c785845f732dbaca5d03502e5c1ee47e19dce61fb3c7d53")
syntax = subprocess.run([BASH, "--noprofile", "--norc", "-n"], input=deploy.encode(), capture_output=True, timeout=10)
check("bash syntax only", syntax.returncode == 0)
for rel, n in (("test/test_deploy_logfail.wsl.txt", 8), ("test/test_deploy_standin.wsl.txt", 6)):
    saved = blob(B + rel).decode()
    check("saved WSL output " + rel, f"통과 {n}/{n}" in saved and "rc=0" in saved)

# Fixed source below LOGF: all paths are a private workspace fixture, and every git/archive,
# extraction, hash command and SSH call is a shell function. No production command is invoked.
cases = [("normal", 0, 0, 0, 0, 0, 2), ("first_log", 1, 0, 0, 0, 8, 0),
         ("after_first_ssh", 5, 0, 0, 0, 8, 1), ("remote_hash_output", 7, 0, 0, 0, 8, 2),
         ("final_log", 8, 0, 0, 0, 8, 2), ("archive_failure_and_log", 1, 1, 0, 0, 3, 0),
         ("first_ssh_failure_and_log", 5, 0, 255, 0, 255, 1),
         ("remote_tar_failure_and_log", 5, 0, 12, 0, 6, 1),
         ("existing_remote_and_log", 5, 0, 10, 0, 5, 1),
         ("remote_verify_failure_and_log", 6, 0, 0, 1, 7, 2),
         ("local_hash_failure_and_log", 3, 0, 0, 0, 4, 0)]
for name, fail_from, archive_rc, first_rc, second_rc, want_rc, want_calls in cases:
    with tempfile.TemporaryDirectory(prefix="branch-", dir=HERE) as temp:
        w = Path(temp).resolve()
        assert w.is_relative_to(HERE.resolve())
        (w / B).mkdir(parents=True)
        script = "set -u\ncd " + shlex.quote(w.as_posix()) + "\n"
        for key, value in {"P": B.rstrip("/"), "T": w.as_posix(), "C": "fixture", "RN": "fixture", "BUNDLE_LIST_SHA": "list-fixture",
                           "STEPS_SHA": "steps-fixture", "FAIL_FROM": str(fail_from), "GA": str(archive_rc), "SR1": str(first_rc),
                           "SR2": str(second_rc), "HASH_RC": "1" if name == "local_hash_failure_and_log" else "0"}.items():
            script += key + "=" + shlex.quote(value) + "\n"
        script += '''git() { printf archive; return "$GA"; }
tar() { command mkdir -p "$T/x/$P/bundle"; printf list > "$T/x/$P/bundle/SHA256SUMS.bundle"; printf step > "$T/x/$P/steps-20261118.sh"; return 0; }
sha256sum() { case "$*" in *--strict*) return "$HASH_RC";; esac; if [ "$(cat)" = step ]; then echo 'steps-fixture -'; else echo 'list-fixture -'; fi; }
ssh() { local n; printf 'ssh\\n' >> "$T/calls"; n=$(wc -l < "$T/calls"); if [ "$n" = 1 ]; then return "$SR1"; fi; printf 'list-fixture -\\nsteps-fixture -\\n'; return "$SR2"; }
tee() { local n; printf 'tee\\n' >> "$T/teecount"; n=$(wc -l < "$T/teecount"); if [ "$FAIL_FROM" -gt 0 ] && [ "$n" -ge "$FAIL_FROM" ]; then cat >/dev/null; return 28; fi; command tee "$@"; }
'''
        script += "LOGF=$P/" + deploy.split("LOGF=$P/", 1)[1]
        run = subprocess.run([BASH, "--noprofile", "--norc"], input=script.encode(), capture_output=True, timeout=20)
        calls = (w / "calls").read_text().count("ssh") if (w / "calls").exists() else 0
        logfile = w / B / "deploy-20261118-run.log"
        log = logfile.read_text(encoding="utf-8") if logfile.exists() else ""
        check("isolated exact deployment branch " + name, run.returncode == want_rc and calls == want_calls and ("배치 완료" in log) == (name == "normal"))
        if name in ("after_first_ssh", "remote_hash_output", "final_log"):
            check("remote partial warning " + name, "원격 상태 미확인/부분" in run.stderr.decode())
        probes.append({"case": name, "exit": run.returncode, "expected_exit": want_rc, "fake_ssh_calls": calls,
                       "log_lines": len(log.splitlines()), "stdout": run.stdout.decode(), "stderr": run.stderr.decode()})

result = {"fixed_commit": REV, "scope": "Git blobs, saved WSL outputs, script flow below LOGF with private paths and harmless command functions; no SSH/DB/HTTP/archive/container",
          "manifests": manifests, "passed": sum(x["passed"] for x in checks), "failed": sum(not x["passed"] for x in checks),
          "checks": checks, "probes": probes}
(HERE / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(json.dumps({k: result[k] for k in ("fixed_commit", "passed", "failed", "manifests")}, ensure_ascii=False))
raise SystemExit(bool(result["failed"]))
