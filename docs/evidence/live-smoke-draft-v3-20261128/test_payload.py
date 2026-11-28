"""Exercise payload assembly only. Never invokes the SSH part or generated script."""
import json
import pathlib
import shlex
import subprocess

ROOT = pathlib.Path(__file__).parent
results = []
for folder, expected in [(ROOT.parent / "live-smoke-draft-v2-20261125", 1), (ROOT, 0)]:
    source = (folder / "launch.sh").read_text(encoding="utf-8")
    start = source.index("{ printf 'APPROVED_DATE=")
    end = source.index('} | ssh "$SSH_HOST"', start) + 1
    assembly = source[start:end]
    header = "set -u\n" + "here=" + shlex.quote(folder.resolve().as_posix()) + "\n"
    for name, value in {"APPROVED_DATE":"2026-11-28","WIN_START":"1300","WIN_END":"1800","EXPECTED_SHA":"synthetic","EXPECTED_IMAGE":"synthetic","POOL_EXPECTED":"synthetic","ALLOC_BEFORE":"20200","SID":"","RUN_TAG":"20261128-1300","STEP":"p0","OPERATOR_READY":"no","EXTERNAL_FREEZE":"no"}.items():
        header += name + "=" + shlex.quote(value) + "\n"
    run = subprocess.run([r"C:\Program Files\Git\bin\bash.exe", "-c", header + assembly], capture_output=True)
    results.append({"source":folder.name,"case":"assemble_p0_only","rc":run.returncode,"expected":expected,"pass":run.returncode==expected,"payload_bytes":len(run.stdout),"ssh_calls":0})
out = ROOT / "payload-results.json"
if out.exists():
    raise SystemExit("Existing result; not overwritten")
out.write_text(json.dumps(results,indent=2)+"\n",encoding="utf-8",newline="")
print(json.dumps(results))
raise SystemExit(0 if all(r["pass"] for r in results) else 1)
