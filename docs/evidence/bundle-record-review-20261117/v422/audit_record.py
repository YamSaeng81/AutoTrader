"""v4.2.2의 보존 함수만 검사한다. 전체 실행 틀·도구·서버·DB·네트워크는 호출하지 않는다."""
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DEV = ROOT / "docs/evidence/ab-create-v42-20261117"
SOURCE_SHA = "660e2332433c50aadcf68b4e92b03d1ae2509e76a34f610ba0217e000c5653c7"
source = (DEV / "bundle/run_bundle.sh").read_bytes()
assert hashlib.sha256(source).hexdigest() == SOURCE_SHA
snapshot = HERE / "run_bundle.v422.reviewed.sh"
if snapshot.exists():
    assert snapshot.read_bytes() == source
else:
    snapshot.write_bytes(source)
body = source.decode("utf-8")
functions = body[body.index("log() {"):body.index('\nprev_ok\ncase "$STEP" in')]
checks = []


def check(name, passed):
    checks.append({"name": name, "passed": bool(passed)})


cases = [
    ("normal", 0, "", 0, 0, "0\n", None),
    ("last_log_failure", 0, 'mkdir "$OUT/steps.log"', 8, 8, None, None),
    ("rc_content_write_failure", 0,
     "printf() { if [ \"$STEP\" = 2 ] && [ \"$1\" = '%s\\n' ] && [ \"${2:-}\" = 0 ]; then return 1; fi; builtin printf \"$@\"; }",
     8, 6, None, ""),
    ("rc_rename_failure", 0, "mv() { return 1; }", 8, 6, None, "0\n"),
    ("tool3_last_log_failure", 3, 'mkdir "$OUT/steps.log"', 3, 8, None, None),
    ("tool3_rc_content_write_failure", 3,
     "printf() { if [ \"$STEP\" = 2 ] && [ \"$1\" = '%s\\n' ] && [ \"${2:-}\" = 3 ]; then return 1; fi; builtin printf \"$@\"; }",
     3, 6, None, ""),
    ("killed_before_rename", 0, 'mv() { kill -KILL "$BASHPID"; }', 137, 6, None, "0\n"),
]
results = []
for name, value, injection, expected_first, expected_next, expected_rc, expected_tmp in cases:
    with tempfile.TemporaryDirectory(prefix="function-probe-", dir=HERE) as temp:
        output = Path(temp).resolve()
        assert output.is_relative_to(HERE.resolve())
        script = "set -u\nOUT='" + output.as_posix() + "'\nSTEP=2\nTOOL_RC=0\n" + functions
        script += "\n" + injection + f"\n( finish {value} )\nfirst=$?\n"
        # 이 호출은 다음 단계의 선행 조건 함수와 무해한 호출 표지만 검사한다.
        script += '''STEP=3
TOOL_RC=0
( prev_ok; printf 'guard-allowed\\n' > "$OUT/guard-call.txt" )
next=$?
builtin printf '__FIRST__=%s\\n__NEXT__=%s\\n' "$first" "$next"
'''
        run = subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc"],
                             input=script.encode("utf-8"), capture_output=True, timeout=15)
        stdout = run.stdout.decode("utf-8")
        stderr = run.stderr.decode("utf-8")
        statuses = dict(line.split("=", 1) for line in stdout.splitlines() if line.startswith("__"))
        rc_file = (output / "2.rc").read_text(encoding="utf-8") if (output / "2.rc").is_file() else None
        tmp_file = (output / "2.rc.tmp").read_text(encoding="utf-8") if (output / "2.rc.tmp").is_file() else None
        guard_called = (output / "guard-call.txt").is_file()
        check(name + ": first exit", statuses.get("__FIRST__") == str(expected_first))
        check(name + ": next guard exit", statuses.get("__NEXT__") == str(expected_next))
        check(name + ": final marker", rc_file == expected_rc)
        check(name + ": temporary marker", tmp_file == expected_tmp)
        check(name + ": guard call", guard_called == (name == "normal"))
        results.append({"case": name, "first_exit": int(statuses["__FIRST__"]),
                        "next_guard_exit": int(statuses["__NEXT__"]), "rc_file": rc_file,
                        "temporary_file": tmp_file, "guard_probe_calls": int(guard_called),
                        "stdout": stdout, "stderr": stderr})

log_path = DEV / "runs/bundle-rules/test_run_bundle.v422.run2.txt"
stored_log = log_path.read_text(encoding="utf-8")
check("stored run2: 29 assertion lines", sum(x.startswith("통과  ") for x in stored_log.splitlines()) == 29)
check("stored run2: summary and exit", "통과 29/29\nrc=0" in stored_log)
check("stored run2: no failing assertion", not any(x.startswith("실패  ") for x in stored_log.splitlines()))
case_expect = {"T20": (8, 8), "T21": (8, 6), "T22": (3, 8), "T23": (3, 6), "T24": (137, 6), "T25": (8, 8)}
reviewed_files = {}
for relative in ["bundle/run_bundle.sh", "bundle/test_run_bundle.sh",
                 "runs/bundle-rules/test_run_bundle.v422.run1.txt", "runs/bundle-rules/test_run_bundle.v422.run2.txt"]:
    reviewed_files[relative] = hashlib.sha256((DEV / relative).read_bytes()).hexdigest()
for key, (first, next_rc) in case_expect.items():
    relative = f"runs/bundle-rules/v422-cases.run2/{key}.txt"
    raw = (DEV / relative).read_bytes()
    saved = raw.decode("utf-8")
    reviewed_files[relative] = hashlib.sha256(raw).hexdigest()
    check(key + ": stored first exit", f"첫 호출 종료값={first}\n" in saved)
    check(key + ": stored next exit", f"다음 단계 직접 호출 종료값={next_rc}\n" in saved)
    check(key + ": no next completion", "ab_complete rc=" not in saved and "확인 psql rc=" not in saved)

result = {"source_sha256": SOURCE_SHA,
          "scope": "보존 함수만 호출. guard_probe_calls는 실제 도구가 아닌 다음 선행 조건 통과 표지다. 개발 전체 실행 틀 시험은 보존 결과만 대조했다.",
          "checks": checks, "passed": sum(x["passed"] for x in checks),
          "failed": sum(not x["passed"] for x in checks),
          "function_cases": results, "reviewed_developer_file_hashes": reviewed_files}
(HERE / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(json.dumps({"passed": result["passed"], "failed": result["failed"],
                  "cases": [{k: x[k] for k in ("case", "first_exit", "next_guard_exit", "guard_probe_calls")} for x in results]}, ensure_ascii=False))
raise SystemExit(1 if result["failed"] else 0)
