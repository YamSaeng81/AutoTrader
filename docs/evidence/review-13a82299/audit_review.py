"""431차 고정 Git 블롭·보존 근거·분리 함수 점검. 서버/DB/HTTP/제품 시험 호출 없음."""
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
REV = "13a8229981eecf8e7d35381bcc9a5e95d04ad6f5"
E = "docs/evidence/"
NEW = E + "ab-create-v423-20261117/"
OLD = E + "ab-create-v42-20261117/"
checks, manifests, probes = [], [], []


def check(name, value):
    checks.append({"name": name, "passed": bool(value)})


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def blob(path):
    return git("show", REV + ":" + path)


def listing(path):
    rows = []
    for line in blob(path).decode("utf-8-sig").splitlines():
        m = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        assert m, line
        rows.append(m.groups())
    return rows


for folder, manifest, expected in [
    ("sshsec-20261117", "SHA256SUMS", 7),
    ("ab-create-v423-20261117", "SHA256SUMS", 50),
    ("ab-create-v423-20261117", "bundle/SHA256SUMS.bundle", 16),
    ("review-c3dca385", "SHA256SUMS", 4),
]:
    base = E + folder + "/"
    path = base + manifest
    check(path + ": working bytes", blob(path) == (ROOT / path).read_bytes())
    rows = listing(path)
    check(path + ": count", len(rows) == expected)
    for sha, rel in rows:
        p = base + rel
        raw = blob(p)
        check(p + ": committed sha and working bytes",
              hashlib.sha256(raw).hexdigest() == sha and (ROOT / p).read_bytes() == raw)
    manifests.append({"folder": folder, "list": manifest, "matched": len(rows)})

check("fixed local HEAD", git("rev-parse", "HEAD").decode().strip() == REV)
check("local origin/main ref (no fetch)", git("rev-parse", "origin/main").decode().strip() == REV)
new_rows = {rel: sha for sha, rel in listing(NEW + "bundle/SHA256SUMS.bundle")}
old_rows = {rel: sha for sha, rel in listing(OLD + "bundle/SHA256SUMS.bundle")}
check("16 paths unchanged", new_rows.keys() == old_rows.keys())
check("only executable member changed is runner",
      [p for p in new_rows if new_rows[p] != old_rows[p]] == ["bundle/run_bundle.sh"])
for rel in new_rows:
    if rel != "bundle/run_bundle.sh":
        check("unchanged executable member " + rel, blob(NEW + rel) == blob(OLD + rel))

old = blob(OLD + "bundle/run_bundle.sh").decode("utf-8")
new = blob(NEW + "bundle/run_bundle.sh").decode("utf-8")
tail = '\nprev_ok\ncase "$STEP" in'
check("step dispatch and calls unchanged", old[old.index(tail):] == new[new.index(tail):])
check("other recording/guard functions unchanged", old[old.index("log() {"):old.index("nosecret() {")] ==
      new[new.index("log() {"):new.index("nosecret() {")])
check("fixed new runner sha", hashlib.sha256(new.encode()).hexdigest() ==
      "bad10c08b928d5af8dc26086e062d8ef07bd31b039a49e42fb01593df246150b")
syntax = subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc", "-n"],
                        input=new.encode(), capture_output=True, timeout=10)
check("runner bash syntax only", syntax.returncode == 0)

linux = blob(NEW + "runs/linux-wsl-test.txt").decode("utf-8")
windows = blob(NEW + "runs/win-gitbash-test.txt").decode("utf-8")
baseline = blob(NEW + "runs/old-660e2332-with-v423-tests.txt").decode("utf-8")
check("saved Linux 49/49, uid1000 and rc0", "통과 49/49" in linux and "user_uid=1000" in linux and "test_rc=0" in linux)
check("saved Windows 43/43", "통과 43/43" in windows)
check("saved old baseline 29/43", "통과 29/43" in baseline)
for case in ("L04", "L05"):
    record = blob(NEW + "runs/linux-keep/" + case + ".txt").decode("utf-8")
    check("saved Linux actual read error " + case,
          "첫 호출 종료값=10" in record and "다음 단계 직접 호출 종료값=6" in record and "토큰 검사 미완료" in record)

ssh = blob(E + "sshsec-20261117/out.txt").decode("utf-8")
remote = blob(E + "sshsec-20261117/remote.sh").decode("utf-8")
for key in ("server 2026-11-17 10:40:59 KST", "server 2026-11-17 10:42:07 KST",
            "Failed(출발지 있는 줄)=20754", "고유 출발지 IP 수=28", "20708\t143.198.x.x",
            "143.198 /16 안 고유 IP 수=1, 그 건수=20708", "개수=49", "출발지 수(실패, 전체 IP 서버 안 집계)=22",
            "grep[B.maxstartups] 개수=0 rc=1", "21 manicstr|publickey|ED25519 SHA256:zBMiDG",
            "244 Accepted publickey for manicstr", "rc[F.sudo_sshd_T]=1", "rc[G.ufw]=1"):
    check("SSH saved fact: " + key, key in ssh)
check("SSH IP grouping before mask", "sort | uniq -c | sort -rn | head -5 | awk" in remote and "| mask" in remote)
seven_day = remote[remote.index('echo "D 7일 Accepted'):remote.index('echo "D 로그인 가능한')]
check("7-day grouping does not include fingerprints", 'Accepted [a-z-]+ for [^ ]+' in seven_day and "SHA256" not in seven_day)
check("root-only include content not read", "50-cloud-init.conf grep rc=2" in ssh and "Permission denied" in ssh)

# 실제 생성기 대신 보존된 함수만 사용한다. 임시 경로는 이 새 근거 폴더 안으로 제한한다.
functions = new[new.index("log() {"):new.index(tail)]
cases = [
    ("normal_no_match", "", "present", 0, False, 0),
    ("missing_token", "", "missing", 0, False, 10),
    ("empty_token", "", "empty", 0, False, 10),
    ("grep_error_2", "grep() { return 2; }", "present", 0, False, 10),
    ("grep_error_3", "grep() { return 3; }", "present", 0, False, 10),
    ("grep_1_with_output", "grep() { printf unexpected; return 1; }", "present", 0, False, 10),
    ("fixture_match", "", "present", 0, True, 9),
    ("tool_3_and_scan_error", "grep() { return 2; }", "present", 3, False, 3),
    ("tool_3_no_match", "", "present", 3, False, 3),
    ("tool_3_fixture_match", "", "present", 3, True, 9),
]
for step in (2, 4):
    for label, inject, state, tool_rc, match, expected in cases:
        with tempfile.TemporaryDirectory(prefix="probe-", dir=HERE) as tmp:
            base = Path(tmp).resolve()
            assert base.is_relative_to(HERE)
            out = base / "out"
            out.mkdir()
            token = base / "pattern.txt"
            if state != "missing":
                token.write_text("non-secret-review-fixture" if state == "present" else "", encoding="utf-8")
            (out / "record.txt").write_text("non-secret-review-fixture" if match else "ordinary result", encoding="utf-8")
            script = f"set -u\nOUT='{out.as_posix()}'\nTOKEN_FILE='{token.as_posix()}'\nSTEP={step}\nTOOL_RC={tool_rc}\n"
            script += functions + "\n" + inject + "\n( nosecret; finish \"$TOOL_RC\" )\nfirst=$?\n"
            script += f"STEP={step + 1}\nTOOL_RC=0\n"
            script += '( prev_ok; printf allowed > "$OUT/guard.txt" )\nnext=$?\nbuiltin printf "__FIRST__=%s\\n__NEXT__=%s\\n" "$first" "$next"\n'
            run = subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc"],
                                 input=script.encode(), capture_output=True, timeout=15)
            stdout = run.stdout.decode("utf-8")
            values = dict(l.split("=", 1) for l in stdout.splitlines() if l.startswith("__"))
            actual = (int(values["__FIRST__"]), int(values["__NEXT__"]), int((out / "guard.txt").exists()))
            wanted = (expected, 0 if expected == 0 else 6, int(expected == 0))
            final_rc = (out / f"{step}.rc").read_text().strip()
            check(f"STEP{step} function {label}: exit, next guard, marker, rc",
                  actual == wanted and final_rc == str(expected) and not (out / f"{step}.rc.tmp").exists())
            probes.append({"step": step, "case": label, "exit": actual[0], "next_guard_exit": actual[1],
                           "guard_marker": actual[2], "final_rc": final_rc, "stdout": stdout,
                           "stderr": run.stderr.decode("utf-8")})

result = {"fixed_commit": REV, "scope": "Git blobs, saved evidence and isolated Bash functions only; no product suite/DB/HTTP/production",
          "manifests": manifests, "passed": sum(c["passed"] for c in checks), "failed": sum(not c["passed"] for c in checks),
          "checks": checks, "probes": probes}
(HERE / "checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(json.dumps({k: result[k] for k in ("fixed_commit", "passed", "failed", "manifests")}, ensure_ascii=False))
raise SystemExit(bool(result["failed"]))
