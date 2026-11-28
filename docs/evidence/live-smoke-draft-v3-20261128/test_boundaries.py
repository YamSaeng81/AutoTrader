"""No SSH/curl/DB. HTTP uses an in-memory opener; shell tests define api/q functions."""
import importlib.util
import json
import os
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("safe_api", ROOT / "safe_api.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

class Response:
    def __init__(self, status, data):
        self.status, self.data = status, data
    def __enter__(self):
        return self
    def __exit__(self, *args):
        pass
    def read(self):
        return self.data

def main():
    results = []
    for name, status, data, expected in [
        ("http_valid", 200, b'{"success":true,"data":{"status":"CREATED"}}', True),
        ("http_500", 500, b'service failure', False),
        ("invalid_json", 200, b'{', False),
        ("application_failure", 200, b'{"success":false,"data":{}}', False),
        ("application_error_in_data", 200, b'{"success":true,"data":{"error":"failure"}}', False),
        ("missing_data", 200, b'{"success":true}', False),
        ("nonobject", 200, b'[]', False),
    ]:
        try:
            api.request("POST", "/api/v1/trading/sessions", "{}", "synthetic", lambda *a, **k: Response(status, data))
            actual = True
        except (ValueError, TypeError):
            actual = False
        results.append({"case": name, "pass": actual == expected})
    touched = []
    try:
        api.request("POST", "/api/v1/settings/upbit/test-order", "{}", "synthetic", lambda *a, **k: touched.append(True))
        rejected = False
    except ValueError:
        rejected = True
    results.append({"case": "reject_unrelated_path_before_opener", "pass": rejected and not touched})
    source = (ROOT / "smoke.sh").read_text(encoding="utf-8")
    functions = source[source.index("stop() {"):source.index("for k in APPROVED_DATE")]
    shell_cases = [
        ("start_running", "RUNNING|t", 0, False, True),
        ("start_not_running", "CREATED|f", 0, False, False),
        ("start_http_failure", "RUNNING|t", 1, False, False),
        ("start_marker_missing_parent", "RUNNING|t", 0, True, False),
    ]
    with tempfile.TemporaryDirectory(prefix="smoke-boundary-") as temp:
        base = pathlib.Path(temp)
        for name, state, api_rc, missing, expected in shell_cases:
            dest = base / name
            if not missing:
                dest.mkdir()
            marker = base / (name + "-api-called")
            bash_dest = str(dest).replace("\\", "/")
            bash_marker = str(marker).replace("\\", "/")
            script = "set -euo pipefail\n" + functions + f'''
M='{bash_dest}'; SID=901
kst_ok() {{ :; }}
api() {{ printf 'called' > '{bash_marker}'; printf '{{"success":true,"data":{{"id":901}}}}'; return {api_rc}; }}
q() {{ printf '%s\\n' '{state}'; }}
step_start
'''
            result = subprocess.run([r"C:\Program Files\Git\bin\bash.exe", "-c", script], capture_output=True)
            expected_call = not missing
            results.append({"case": name, "pass": (result.returncode == 0) == expected and marker.exists() == expected_call, "rc": result.returncode})
    report = {"cases": results, "passed": sum(r["pass"] for r in results), "total": len(results), "network_calls": 0}
    out = ROOT / "boundary-results.json"
    if out.exists():
        raise SystemExit("Existing results; preserve before a new run")
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="")
    print(json.dumps(report))
    raise SystemExit(0 if report["passed"] == report["total"] else 1)

if __name__ == "__main__":
    main()
