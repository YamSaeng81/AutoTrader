"""실행 틀의 finish/prev_ok 함수만 로컬 Bash에서 대조. 도구/서버/DB/네트워크 호출 없음."""
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE_SHA = "1c4bb697cefd17a7705a6c128ed361ba8723764ba88ef686fa35f1dc65985005"
source = (HERE / "run_bundle.v421.reviewed.sh").read_bytes()
assert hashlib.sha256(source).hexdigest() == SOURCE_SHA
text = source.decode("utf-8")
functions = text[text.index("log() {"):text.index('\nprev_ok\ncase "$STEP" in')]
with tempfile.TemporaryDirectory(prefix="record-probe-", dir=HERE) as temp:
    output = Path(temp).resolve()
    assert output.is_relative_to(HERE.resolve())
    # 마지막 로그 쓰기 시점의 실패를 디렉터리로 주입한다. 함수 원문은 그대로이며 finish만 호출한다.
    script = "set -u\nOUT='" + output.as_posix() + "'\nSTEP=2\nTOOL_RC=0\n" + functions + """
mkdir "$OUT/steps.log"
( finish 0 )
r=$?
printf 'finish_exit=%s rc_file=%s\n' "$r" "$(cat "$OUT/2.rc")"
STEP=3
( prev_ok; printf 'next-step-allowed\n' )
printf 'next_stage_guard_exit=%s\n' "$?"
"""
    run = subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc"],
                         input=script.encode("utf-8"), capture_output=True)
    stdout = run.stdout.decode("utf-8")
    observed = "finish_exit=8 rc_file=0" in stdout and "next-step-allowed" in stdout and "next_stage_guard_exit=0" in stdout
    result = {"source_sha256": SOURCE_SHA, "scope": "보존 실행 틀의 함수만 호출. 실제 단계/도구/서버/DB/네트워크는 호출하지 않음",
              "failure_injection": "finish의 마지막 steps.log 쓰기를 실패시킴(steps.log를 디렉터리로 둠)",
              "stdout": stdout, "observed_stale_success": observed,
              "stderr_log_write_failure": "steps.log 기록 실패" in run.stderr.decode("utf-8")}
(HERE / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False))
raise SystemExit(0 if observed else 1)
