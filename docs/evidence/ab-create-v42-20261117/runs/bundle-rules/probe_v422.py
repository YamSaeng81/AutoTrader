"""총괄 probe_record.py(bundle-record-review-20261117)와 같은 주입을 v4.2.2 run_bundle.sh 함수에 적용. 도구/서버/DB/네트워크 호출 없음."""
import hashlib, json, subprocess, tempfile
from pathlib import Path
HERE = Path(__file__).resolve().parent
src_path = HERE.parent.parent / "bundle" / "run_bundle.sh"
source = src_path.read_bytes()
sha = hashlib.sha256(source).hexdigest()
text = source.decode("utf-8")
functions = text[text.index("log() {"):text.index('\nprev_ok\ncase "$STEP" in')]
with tempfile.TemporaryDirectory(prefix="record-probe-") as temp:
    output = Path(temp).resolve()
    script = "set -u\nOUT='" + output.as_posix() + "'\nSTEP=2\nTOOL_RC=0\n" + functions + """
mkdir "$OUT/steps.log"
( finish 0 )
r=$?
printf 'finish_exit=%s rc_file=%s\n' "$r" "$(cat "$OUT/2.rc" 2>/dev/null || echo 없음)"
STEP=3
( prev_ok; printf 'next-step-allowed\n' )
printf 'next_stage_guard_exit=%s\n' "$?"
"""
    run = subprocess.run(["C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc"], input=script.encode("utf-8"), capture_output=True)
stdout = run.stdout.decode("utf-8")
closed = ("finish_exit=8 rc_file=없음" in stdout) and ("next-step-allowed" not in stdout) and ("next_stage_guard_exit=0" not in stdout)
result = {"source": "bundle/run_bundle.sh", "source_sha256": sha, "same_injection_as": "bundle-record-review-20261117/probe_record.py",
          "stdout": stdout, "stderr": run.stderr.decode("utf-8"), "stale_success_closed": closed}
(HERE / "probe_v422.result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=1))
raise SystemExit(0 if closed else 1)
