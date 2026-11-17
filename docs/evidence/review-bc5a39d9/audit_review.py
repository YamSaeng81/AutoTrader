"""432차 고정 근거와 격리된 분기 점검. 서버/DB/HTTP/컨테이너/실제 SSH 변경 호출 없음."""
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
REV = "bc5a39d9f03f768b09c291cd3153d08efb6e8740"
ORIGIN = "13a8229981eecf8e7d35381bcc9a5e95d04ad6f5"
E = "docs/evidence/"
checks, manifests, probes = [], [], []


def check(name, result):
    checks.append({"name": name, "passed": bool(result)})


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def blob(path):
    return git("show", REV + ":" + path)


for folder, listing, expected in [
    ("sshadmin-20261117", "SHA256SUMS", 38),
    ("create-plan-20261117", "SHA256SUMS", 12),
    ("review-13a82299", "SHA256SUMS", 4),
    ("ab-create-v423-20261117", "bundle/SHA256SUMS.bundle", 16),
]:
    base = E + folder + "/"
    raw = blob(base + listing)
    check(base + listing + ": working bytes", raw == (ROOT / base / listing).read_bytes())
    rows = raw.decode("utf-8-sig").splitlines()
    check(base + listing + ": count", len(rows) == expected)
    for row in rows:
        m = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", row)
        assert m, row
        sha, rel = m.groups()
        p = base + rel
        data = blob(p)
        check(p + ": blob sha and working bytes", hashlib.sha256(data).hexdigest() == sha and data == (ROOT / p).read_bytes())
    manifests.append({"folder": folder, "list": listing, "matched": len(rows)})
check("fixed local HEAD", git("rev-parse", "HEAD").decode().strip() == REV)
check("unpublished origin/main ref", git("rev-parse", "origin/main").decode().strip() == ORIGIN)
check("no tracked production/build input changes", not git("diff", "--name-only", ORIGIN, REV, "--", ".", ":!docs").strip())

sshdir = E + "sshadmin-20261117/"
admin = blob(sshdir + "admin_read.sh").decode()
change = blob(sshdir + "change/ssh_change.sh").decode()
verify = blob(sshdir + "change/verify_local.sh").decode()
probe = blob(sshdir + "probe/probe_methods.sh").decode()
for path in ("admin_read.sh", "change/ssh_change.sh", "change/verify_local.sh", "probe/probe_methods.sh"):
    run = subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc", "-n"],
                         input=blob(sshdir + path), capture_output=True, timeout=10)
    check(path + ": syntax only", run.returncode == 0)
console = blob(sshdir + "test/run1/console.txt").decode()
check("saved OpenSSH container result 40/40", len(re.findall(r"^PASS ", console, re.M)) == 40 and "PASS=40 FAIL=0" in console)
check("saved exact OpenSSH package", "1:8.9p1-3ubuntu0.18" in console)
match = blob(sshdir + "test/run1/m3.txt").decode()
check("saved Match refusal without reload", "rc=4" not in match and "reload 안 함" in match and "기대값 불일치" in match)
check("probe offers two methods", "Permission denied (publickey,password)" in blob(sshdir + "probe/probe-stderr.txt").decode())
check("probe disabled key/password prompt/reuse", all(k in probe for k in ("PubkeyAuthentication=no", "NumberOfPasswordPrompts=0", "ControlPath=none", "BatchMode=yes")))
check("admin captures sshd exit before grep", admin.index('rc "sshd_T.$n" "$r"') < admin.index('grep -Ei "^($KEYS)'))
check("admin host assumes address without collecting UseDNS", "host=$A" in admin and "usedns" not in admin.lower().split("keys=")[1].split("# 설정 파일")[0])
check("verify disables connection reuse and limits identities", all(k in verify for k in ("ControlMaster=no", "ControlPath=none", "IdentitiesOnly=yes", "-F /dev/null")))

# 분기 원문을 추출하고 경로는 이 근거 폴더의 임시 파일로 제한한다.
# sshd/systemctl/sleep만 무해한 함수로 대체하며 실제 /etc, /root, 서비스에는 접근하지 않는다.
def branch(name, next_name):
    body = change.split("\n" + name + ")", 1)[1].split("\n" + next_name + ")", 1)[0]
    return body.rsplit(";;", 1)[0].replace("/usr/sbin/sshd", "review_sshd")


for label, which, third, changed_main in [
    ("rollback_normal", "rollback", False, False),
    ("rollback_third_conf", "rollback", True, False),
    ("rollback_changed_main", "rollback", False, True),
    ("rollback_key_third_content", "rollback-key", True, False),
    ("reload_inactive_service", "reload", False, False),
]:
    with tempfile.TemporaryDirectory(prefix="branch-", dir=HERE) as temp:
        base = Path(temp).resolve()
        assert base.is_relative_to(HERE.resolve())
        backup = base / "backup"
        backup.mkdir()
        for name, value in (("sshd_config", "baseline-main\n"), ("authorized_keys", "baseline-key-fixture\n")):
            (backup / name).write_text(value, encoding="utf-8", newline="\n")
        (backup / "SHA256SUMS").write_text("".join(hashlib.sha256((backup / n).read_bytes()).hexdigest() + " *" + n + "\n" for n in ("sshd_config", "authorized_keys")), encoding="utf-8", newline="\n")
        dst, main, key = base / "hardening.conf", base / "sshd_config", base / "authorized_keys"
        expected = "this-change-conf\n"
        dst.write_text("third-party-conf\n" if third else expected, encoding="utf-8", newline="\n")
        main.write_text("changed-main\n" if changed_main else "baseline-main\n", encoding="utf-8", newline="\n")
        key.write_text("unrelated-added-key-fixture\n" if third else "baseline-key-fixture\n", encoding="utf-8", newline="\n")
        prior = {p.name: p.read_text() for p in (dst, main, key)}
        script = "set -u\n"
        for n, value in {"BK": backup.as_posix(), "DST": dst.as_posix(), "MAIN": main.as_posix(), "AK": key.as_posix(), "MARK": (base / "reload.txt").as_posix(), "STEP": which,
                         "CONF_SHA": hashlib.sha256(expected.encode()).hexdigest()}.items():
            script += f"{n}='{value}'\n"
        script += '''say() { printf '%s\\n' "$*"; }
die() { say "STOP $1 $2"; exit "$1"; }
sha() { sha256sum < "$1" | cut -d' ' -f1; }
bk_ok() { [ -d "$BK" ] && (cd "$BK" && sha256sum --quiet -c SHA256SUMS) || die 7 backup; }
review_sshd() { return 0; }
check_all() { return 0; }
sleep() { return 0; }
systemctl() {
  case "$*" in
    "reload ssh.service") printf called >> "$MARK"; return 0;;
    "is-active ssh.service") printf 'inactive\\n'; return 3;;
    "show -p MainPID --value ssh.service") printf '123\\n'; return 0;;
    *) return 99;;
  esac
}
'''
        if which == "rollback":
            body = branch("rollback", "rollback-key")
        elif which == "reload":
            body = branch("reload", "rollback")
        else:
            body = change.split("\nrollback-key)", 1)[1].rsplit(";;", 1)[0]
        script += body + "\n"
        run = subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc"], input=script.encode(), capture_output=True, timeout=10)
        observed = {"case": label, "exit": run.returncode, "config_exists": dst.exists(), "reload_called": (base / "reload.txt").exists(),
                    "key_restored_to_backup": key.read_bytes() == (backup / "authorized_keys").read_bytes(),
                    "main_changed_from_backup": main.read_bytes() != (backup / "sshd_config").read_bytes(),
                    "prior": prior, "stdout": run.stdout.decode(), "stderr": run.stderr.decode()}
        # 성공 수에는 현재 공백이 예상대로 재현됐다는 대조도 포함한다.
        if which == "rollback":
            ok = run.returncode == 0 and not dst.exists() and observed["reload_called"]
        elif which == "rollback-key":
            ok = run.returncode == 0 and observed["key_restored_to_backup"]
        else:
            ok = run.returncode == 0 and observed["reload_called"] and "is-active=inactive" in observed["stdout"]
        check("current branch observation " + label, ok)
        probes.append(observed)

# 시간 함수만 호출: 네트워크·생성 요청·DB 조회 없음.
bundle = ROOT / E / "ab-create-v423-20261117"
sys.path.insert(0, str(bundle / "create"))
spec = importlib.util.spec_from_file_location("review_ab", bundle / "create/ab_create.py")
ab = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = ab
spec.loader.exec_module(ab)
windows = []
for value in ("2026-11-18T14:06:30+09:00", "2026-11-18T14:21:30+09:00", "2026-11-19T14:06:30+09:00"):
    c, inside = ab.m15_window(datetime.fromisoformat(value))
    row = {"called_at": value, "inside_generic_window": inside, "boundary": c.isoformat(), "t0": (c + timedelta(minutes=15)).isoformat()}
    windows.append(row)
    check("generic window accepts " + value, inside)
check("later call can derive a different T0", len({r["t0"] for r in windows}) == 3)

result = {"fixed_commit": REV, "scope": "fixed blobs, saved outputs, temporary-file branches and pure time function; no production/DB/HTTP/SSH daemon/container",
          "manifests": manifests, "passed": sum(c["passed"] for c in checks), "failed": sum(not c["passed"] for c in checks),
          "checks": checks, "branch_probes": probes, "window_probes": windows}
(HERE / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(json.dumps({k: result[k] for k in ("fixed_commit", "passed", "failed", "manifests")}, ensure_ascii=False))
raise SystemExit(bool(result["failed"]))
