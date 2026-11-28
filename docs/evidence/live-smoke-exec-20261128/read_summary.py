"""Return only bounded verdicts/booleans and file hashes; no account/response payloads."""
import base64
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).parent
OUT = ROOT / "completion-summary.json"
if OUT.exists():
    raise SystemExit("Existing summary")
remote = '''import datetime,decimal,hashlib,json,pathlib
base=pathlib.Path.home()/"work-batches"
r=base/"live-smoke-20261128-exp1"; r2=base/"live-smoke-20261128-exp2"
final=json.loads((r/"observer-output/final.json").read_text())
before=json.loads((r/"driver-output/preflight.json").read_text())
state=final["state"];broker=final["broker"]
gate=(r/"observer-output/gate-verdict.txt").read_text().splitlines()[0]
second=json.loads((r2/"driver-output/error.json").read_text())
hashes={}
for parent,name in [(r,"driver-output/final.json"),(r,"driver-output/create.out"),(r,"driver-output/verify.out"),(r,"driver-output/start.out"),(r,"observer-output/operator-stop-response.json"),(r,"observer-output/gate.txt"),(r,"observer-output/gate-verdict.txt"),(r,"observer-output/broker-after.json"),(r2,"driver-output/preflight.json"),(r2,"driver-output/error.json")]:
 p=parent/name
 if p.exists():
  b=p.read_bytes();hashes[parent.name+"/"+name]={"sha256":hashlib.sha256(b).hexdigest(),"bytes":len(b)}
D=decimal.Decimal
print(json.dumps({"at":datetime.datetime.now(datetime.timezone.utc).isoformat(),"sid":204,"session_status":state["status"],"buy_count":len(state["buys"]),"open_positions":state["open_positions"],"active_orders":state["active_orders"],"cleanup_pending":state["cleanup_pending"],"gate":gate,"broker_btc_zero":D(str(broker["btc_balance"]))==0 and D(str(broker["btc_locked"]))==0,"broker_krw_unchanged":D(str(broker["krw_balance"]))==D(before["chance"]["krw_available"]),"operator_stop_response_recorded":(r/"observer-output/operator-stop-response.json").is_file(),"second_attempt_reason":second["reason"],"second_attempt_created_session":(r2/"driver-output/session.json").exists(),"raw_payload_exported":False,"hashes":hashes}))
'''
b = base64.b64encode(remote.encode()).decode()
p = subprocess.run([r"C:\Windows\System32\OpenSSH\ssh.exe", "-F", r"C:\Users\SangJoon_Lee\.ssh\config", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10", "-o", "ConnectionAttempts=1", "-T", "cat-prod", "printf '%s' '" + b + "' | base64 -d | python3 -"], capture_output=True)
if p.returncode or p.stderr:
    raise SystemExit(f"Read failed rc={p.returncode} stderr_bytes={len(p.stderr)}")
OUT.write_bytes(p.stdout)
summary = json.loads(p.stdout)
print(json.dumps({k:v for k,v in summary.items() if k != "hashes"}))
