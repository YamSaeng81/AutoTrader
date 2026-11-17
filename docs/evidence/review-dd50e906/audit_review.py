"""433차 고정 블롭·보존 출력·격리 분기 검토. 운영/DB/소켓/컨테이너 호출 없음."""
import hashlib
import importlib.util
import json
import re
import shlex
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
REV = "dd50e9068b4362852629c42de5f9027a82d1d6ce"
BASE = "13a8229981eecf8e7d35381bcc9a5e95d04ad6f5"
BASH = "C:/Program Files/Git/bin/bash.exe"
E = "docs/evidence/"
checks, manifests, probes = [], [], []


def check(name, ok):
    checks.append({"name": name, "passed": bool(ok)})


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def blob(path):
    return git("show", REV + ":" + path)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def put(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def shell(script):
    return subprocess.run([BASH, "--noprofile", "--norc"], input=script.encode(), capture_output=True, timeout=20)


for folder, listing in [("sshfix-20261118", "SHA256SUMS"), ("ab-create-v424-20261118", "SHA256SUMS"),
                        ("n03-1117-2200", "SHA256SUMS"), ("review-bc5a39d9", "SHA256SUMS"),
                        ("ab-create-v424-20261118", "bundle/SHA256SUMS.bundle"),
                        ("ab-create-v423-20261117", "bundle/SHA256SUMS.bundle")]:
    base = E + folder + "/"
    raw = blob(base + listing)
    check(base + listing + " working bytes", raw == (ROOT / base / listing).read_bytes())
    rows = raw.decode("utf-8-sig").splitlines()
    for row in rows:
        m = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", row)
        assert m, row
        digest, rel = m.groups()
        p = base + rel
        data = blob(p)
        check(p + " committed digest and working bytes", sha(data) == digest and data == (ROOT / p).read_bytes())
    manifests.append({"folder": folder, "list": listing, "matched": len(rows)})
check("local HEAD and local origin/main ref", all(git("rev-parse", ref).decode().strip() == REV for ref in ("HEAD", "origin/main")))
check("docs-only tracked scope since frozen base", not git("diff", "--name-only", BASE, REV, "--", ".", ":!docs").strip())

new = E + "ab-create-v424-20261118/"
old = E + "ab-create-v423-20261117/"
changed = []
for line in blob(new + "bundle/SHA256SUMS.bundle").decode().splitlines():
    rel = re.fullmatch(r"[0-9a-f]{64} [ *](.+)", line).group(1)
    if blob(new + rel) != blob(old + rel):
        changed.append(rel)
check("only two of sixteen bundle files changed", sorted(changed) == ["bundle/run_bundle.sh", "create/ab_create.py"])
check("frozen old creator unchanged in Git", git("show", BASE + ":" + old + "create/ab_create.py") == blob(old + "create/ab_create.py"))
for p in ("change/00-cat-hardening.conf", "change/verify_local.sh"):
    check("SSH unchanged " + p, blob(E + "sshfix-20261118/" + p) == blob(E + "sshadmin-20261117/" + p))
for p in (E + "sshfix-20261118/change/ssh_change.sh", E + "sshfix-20261118/admin_read_add.sh",
          new + "bundle/run_bundle.sh", new + "steps-20261118.sh", new + "deploy-20261118.sh"):
    r = subprocess.run([BASH, "--noprofile", "--norc", "-n"], input=blob(p), capture_output=True, timeout=10)
    check(p + " syntax only", r.returncode == 0)
ssh = blob(E + "sshfix-20261118/change/ssh_change.sh").decode()
console = blob(E + "sshfix-20261118/test/run1/console.txt").decode()
check("saved SSH 52/52 and version", "PASS=52 FAIL=0" in console and "1:8.9p1-3ubuntu0.18" in console)
v1 = blob(E + "sshfix-20261118/test/v1-same-tests.txt").decode()
check("saved v1 same checks 29/52", "PASS=29 FAIL=23" in v1)
for p, count in (("test/test_v424_window.out.txt", 21), ("test/test_v424_bundle.gitbash.txt", 6),
                 ("test/test_steps_guard.out.txt", 5), ("test/test_deploy_standin.out.txt", 6)):
    output = blob(new + p).decode()
    check("saved " + p, f"통과 {count}/{count}" in output)

# 실제 분기·함수 원문을 그대로 읽고 /etc·/root 대신 이 폴더의 임시 경로에만 연결한다.
# sshd/systemctl/sleep은 무해한 함수이며 데몬·네트워크·운영 경로에 접근하지 않는다.
helpers = ssh.split("# 읽기 실패를 빈 값", 1)[1].split("T=$(mktemp", 1)[0]
helpers = "# 읽기 실패를 빈 값" + helpers
helpers += "# 이 작업의 산출물 기록" + ssh.split("# 이 작업의 산출물 기록", 1)[1].split('case "$STEP" in\nprecheck)', 1)[0]
helpers = helpers.replace("/usr/sbin/sshd", "review_sshd")
conf = blob(E + "sshfix-20261118/change/00-cat-hardening.conf").decode()
for name in ("normal", "third_conf", "changed_main", "extra_dropin", "missing_state", "third_key", "inactive", "unknown"):
    with tempfile.TemporaryDirectory(prefix="ssh-", dir=HERE) as temp:
        w = Path(temp).resolve()
        assert w.is_relative_to(HERE.resolve())
        bk, dd = w / "backup", w / "dropins"
        st = bk / "state"
        st.mkdir(parents=True)
        dd.mkdir()
        put(bk / "sshd_config", "main fixture\n")
        put(bk / "authorized_keys", "old key fixture\n")
        put(bk / "sshd_config.d/50-cloud.conf", "cloud fixture\n")
        backup_files = ["sshd_config", "authorized_keys", "sshd_config.d/50-cloud.conf"]
        put(bk / "SHA256SUMS", "".join(sha((bk / f).read_bytes()) + "  ./" + f + "\n" for f in backup_files))
        dsha = sha(("50-cloud.conf " + sha(b"cloud fixture\n") + "\n").encode())
        for key, value in {"bk.main": sha(b"main fixture\n"), "bk.ak": sha(b"old key fixture\n"), "bk.dlist": dsha,
                           "staged.dst": sha(conf.encode()), "ak.added": sha(b"old key fixture\nnew key fixture\n")}.items():
            put(st / key, value + "\n")
        put(w / "main", "changed main\n" if name == "changed_main" else "main fixture\n")
        put(dd / "50-cloud.conf", "cloud fixture\n")
        dst = dd / "00-cat-hardening.conf"
        put(dst, "third conf\n" if name == "third_conf" else conf)
        put(w / "keys", "third key\n" if name == "third_key" else "old key fixture\nnew key fixture\n")
        if name == "extra_dropin":
            put(dd / "10-third.conf", "third dropin\n")
        if name == "missing_state":
            (st / "staged.dst").unlink()
        step = "rollback-key" if name == "third_key" else "rollback"
        body = ssh.split("\n" + step + ")", 1)[1]
        body = body.split("\nrollback-key)", 1)[0] if step == "rollback" else body
        body = body.rsplit(";;", 1)[0].replace("/usr/sbin/sshd", "review_sshd")
        script = "set -u\n"
        for k, value in {"BK": bk.as_posix(), "ST": st.as_posix(), "DDIR": dd.as_posix(), "DST": dst.as_posix(),
                         "MAIN": (w / "main").as_posix(), "AK": (w / "keys").as_posix(), "T": w.as_posix(),
                         "CONF_NAME": dst.name, "CONF_SHA": sha(conf.encode()), "SVC": "ssh.service", "STEP": step,
                         "MARK": (w / "reload").as_posix(), "MODE": name}.items():
            script += k + "=" + shlex.quote(value) + "\n"
        script += '''say() { printf '%s\\n' "$*"; }
die() { say "STOP $1 $2"; exit "$1"; }
review_sshd() { return 0; }
sleep() { return 0; }
systemctl() {
  case "$*" in
    "reload ssh.service") printf 'reload\\n' >> "$MARK"; return 0;;
    "is-active ssh.service") case "$MODE" in inactive) echo inactive; return 3;; unknown) return 1;; *) echo active; return 0;; esac;;
    "show -p MainPID --value ssh.service") echo 123; return 0;;
    *) return 99;;
  esac
}
'''
        before = {str(p.relative_to(w)): sha(p.read_bytes()) for p in w.rglob("*") if p.is_file()}
        r = shell(script + helpers + "\n" + body)
        reloads = (w / "reload").read_text().count("reload") if (w / "reload").exists() else 0
        after = {str(p.relative_to(w)): sha(p.read_bytes()) for p in w.rglob("*") if p.is_file()}
        want = 0 if name == "normal" else 6 if name == "inactive" else 9 if name == "unknown" else 7
        rejected = want == 7
        check("isolated exact SSH branch " + name, r.returncode == want and (before == after and reloads == 0 if rejected else not dst.exists() and reloads == 1))
        probes.append({"case": name, "exit": r.returncode, "expected": want, "reloads": reloads, "unchanged": before == after,
                       "stdout": r.stdout.decode(), "stderr": r.stderr.decode()})

# 승인 시각 함수만 호출. 생성기 실행·토큰·DB·전송 없음.
sys.path.insert(0, str(ROOT / new / "create"))
spec = importlib.util.spec_from_file_location("review_433_ab", ROOT / new / "create/ab_create.py")
ab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ab)
_, c, t0 = ab.parse_approval("2026-11-18", "2026-11-18T14:00:00+09:00", "2026-11-18T14:15:00+09:00")
for when, want in (("2026-11-18T14:06:00+09:00", True), ("2026-11-18T14:12:00+09:00", True),
                   ("2026-11-18T14:05:59+09:00", False), ("2026-11-18T14:12:01+09:00", False),
                   ("2026-11-18T14:21:30+09:00", False), ("2026-11-19T14:06:30+09:00", False)):
    check("approval function " + when, ab.approved_window_ok(datetime.fromisoformat(when), c)[1] == want)

# 배치 새 파일의 log() 원문과 이후 종료값 흐름: git/archive/ssh는 모두 함수 대역.
# 실패한 기록 때문에 배치가 계속되는지 한 번만 확인한다. 실제 git archive/ssh는 호출하지 않는다.
deploy = blob(new + "deploy-20261118.sh").decode()
for fail_record in (False, True):
    with tempfile.TemporaryDirectory(prefix="deploy-", dir=HERE) as temp:
        w = Path(temp).resolve()
        assert w.is_relative_to(HERE.resolve())
        (w / "docs/evidence/ab-create-v424-20261118").mkdir(parents=True)
        prep = "set -u\n" + "cd " + shlex.quote(w.as_posix()) + "\n"
        prep += "MARK=" + shlex.quote((w / "calls").as_posix()) + "\n"
        prep += "T=" + shlex.quote(w.as_posix()) + "\n"
        prep += "P=docs/evidence/ab-create-v424-20261118\nC=fixture\nRN=fixture\nBUNDLE_LIST_SHA=list-fixture\nSTEPS_SHA=steps-fixture\n"
        prep += '''git() { printf 'archive' ; return 0; }
mkdir() { command mkdir "$@"; }
ssh() { printf 'ssh\\n' >> "$MARK"; case "$*" in *--strict*) printf 'list-fixture -\\nsteps-fixture -\\n';; esac; return 0; }
'''
        # Command substitutions run in subshells: choose hashes by the input filename, not mutable shell state.
        prep += '''sha256sum() { case "$*" in *--strict*) return 0;; esac; if [ "$(cat)" = step ]; then echo 'steps-fixture -'; else echo 'list-fixture -'; fi; }
tar() { command mkdir -p "$T/x/$P/bundle"; printf list > "$T/x/$P/bundle/SHA256SUMS.bundle"; printf step > "$T/x/$P/steps-20261118.sh"; return 0; }
'''
        if fail_record:
            prep += "tee() { cat >/dev/null; return 28; }\n"
        body = deploy.split("LOGF=$P/", 1)[1]
        r = shell(prep + "LOGF=$P/" + body)
        calls = (w / "calls").read_text().count("ssh") if (w / "calls").exists() else 0
        check("deploy observation " + ("record failure still succeeds" if fail_record else "control"), r.returncode == 0 and calls == 2)
        probes.append({"case": "deploy_record_failure" if fail_record else "deploy_control", "exit": r.returncode,
                       "fake_ssh_calls": calls, "log_exists": (w / "docs/evidence/ab-create-v424-20261118/deploy-20261118-run.log").exists(),
                       "stdout": r.stdout.decode(), "stderr": r.stderr.decode()})

report = blob(E + "n03-1117-2200/20261117-2200.txt").decode()
check("N03 saved report scope", all(s in report for s in ("🟢", "ERROR(구간): 전체 0", "B3 최근 20일 마감 봉 20/20", "SID 203: CREATED")))
check("N03 saved transmission", "22:00:03 KST 보고 전송 성공" in blob(E + "n03-1117-2200/last_send").decode())
server = blob(E + "n03-1117-2200/server-files.txt").decode()
local = blob(E + "n03-1117-2200/local-sha256.txt").decode()
for match in re.finditer(r"^([0-9a-f]{64})  (.+)$", local, re.M):
    digest, rel = match.groups()
    check("N03 server/local " + rel, digest == sha(blob(E + "n03-1117-2200/" + rel)) and match.group(0) in server)
check("N03 compressed log saved server digest only", "20261117-2200.txt.log.gz" in server and not (ROOT / E / "n03-1117-2200/20261117-2200.txt.log.gz").exists())

result = {"fixed_commit": REV, "scope": "Git blobs, saved outputs, exact branches rebound to private files/functions, pure clock functions. No production/DB/HTTP/SSH/container.",
          "manifests": manifests, "passed": sum(x["passed"] for x in checks), "failed": sum(not x["passed"] for x in checks),
          "checks": checks, "probes": probes}
put(HERE / "checks.json", json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({k: result[k] for k in ("fixed_commit", "passed", "failed", "manifests")}, ensure_ascii=False))
raise SystemExit(bool(result["failed"]))
