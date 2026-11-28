import base64
import datetime
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).parent
remote = '''import datetime,json,pathlib
r=pathlib.Path.home()/"work-batches/live-smoke-20261128-exp2"
out={"at":datetime.datetime.now(datetime.timezone.utc).isoformat()}
for name in ["driver-output/phase.json","driver-output/error.json","driver-output/final.json","driver-output/observer-armed.json","observer-output/armed.json","observer-output/state.json","observer-output/error.json","observer-output/final.json","observer-output/stop-reason.json","observer-output/broker-after.json"]:
 p=r/name
 if p.exists(): out[name]=json.loads(p.read_text())
print(json.dumps(out))
'''
encoded = base64.b64encode(remote.encode()).decode()
p = subprocess.run([r"C:\Windows\System32\OpenSSH\ssh.exe", "-F", r"C:\Users\SangJoon_Lee\.ssh\config", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10", "-o", "ConnectionAttempts=1", "-T", "cat-prod", "printf '%s' '" + encoded + "' | base64 -d | python3 -"], capture_output=True)
name = "status-" + datetime.datetime.now(datetime.timezone.utc).strftime("%H%M%S")
(ROOT / (name + ".json")).write_bytes(p.stdout)
(ROOT / (name + "-transport.json")).write_text(json.dumps({"rc": p.returncode, "stderr_bytes": len(p.stderr)}) + "\n")
print("read rc", p.returncode, "stderr bytes", len(p.stderr))
if p.returncode or p.stderr:
    raise SystemExit(1)
data = json.loads(p.stdout)
phase = data.get("driver-output/phase.json", {})
state = data.get("observer-output/state.json", {}).get("snapshot", {})
print(json.dumps({"at":data["at"],"phase":phase.get("phase"),"sid":phase.get("sid"),"session_status":state.get("status"),"buys":state.get("buys"),"open_positions":state.get("open_positions"),"active_orders":state.get("active_orders"),"driver_error":data.get("driver-output/error.json"),"observer_error":data.get("observer-output/error.json"),"final":data.get("driver-output/final.json")}))
