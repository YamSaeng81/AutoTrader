"""Read selected trial telemetry only; raw API/account payloads remain on server."""
import base64
import datetime
import hashlib
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).parent
remote = r'''import datetime,decimal,hashlib,json,os,pathlib
r=pathlib.Path.home()/"work-batches/live-smoke-20261128-exp3"
def read(name):
 p=r/name
 return json.loads(p.read_text()) if p.is_file() else None
phase=read("driver-output/phase.json")
state=read("observer-output/state.json")
final=read("observer-output/final.json")
snapshot=(final or {}).get("state") or (state or {}).get("snapshot") or {}
selected={k:snapshot.get(k) for k in ("id","status","contract_ok","open_positions","active_orders","cleanup_pending")}
selected["buys"]=[{k:b.get(k) for k in ("id","state","funds","fee","pending")} for b in snapshot.get("buys",[])]
broker=(final or {}).get("broker")
zero=None if not broker else decimal.Decimal(str(broker["btc_balance"]))==0 and decimal.Decimal(str(broker["btc_locked"]))==0
preflight=read("driver-output/preflight.json")
cash_unchanged=None if not broker or not preflight else decimal.Decimal(str(broker["krw_balance"]))==decimal.Decimal(str(preflight["chance"]["krw_available"]))
hashes={}
for name in ("driver-output/final.json","observer-output/final.json","observer-output/gate.txt","observer-output/gate-verdict.txt","observer-output/stop-response.json","observer-output/broker-after.json"):
 p=r/name
 if p.is_file():
  b=p.read_bytes();hashes[name]={"sha256":hashlib.sha256(b).hexdigest(),"bytes":len(b)}
armed=read("observer-output/armed.json")
alive=None
if armed:
 try: os.kill(armed["pid"],0);alive=True
 except ProcessLookupError: alive=False
finished=None if not final else datetime.datetime.fromtimestamp((r/"observer-output/final.json").stat().st_mtime,datetime.timezone.utc).isoformat()
print(json.dumps({"at":datetime.datetime.now(datetime.timezone.utc).isoformat(),"phase":phase,"selected_trial_state":selected,"observer_alive":alive,"driver_error":read("driver-output/error.json"),"observer_error":read("observer-output/error.json"),"final":None if not final else {k:final.get(k) for k in ("status","reason","gate","sid")},"final_file_written_at":finished,"broker_btc_zero":zero,"broker_krw_unchanged":cash_unchanged,"raw_payload_exported":False,"hashes":hashes},default=str))
'''
encoded = base64.b64encode(remote.encode()).decode()
result = subprocess.run([r"C:\Windows\System32\OpenSSH\ssh.exe", "-F", r"C:\Users\SangJoon_Lee\.ssh\config", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10", "-o", "ConnectionAttempts=1", "-T", "cat-prod", "printf '%s' '" + encoded + "' | base64 -d | python3 -"], capture_output=True)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%H%M%S%f")
out = ROOT / ("telemetry-" + stamp + ".json")
(ROOT / ("transport-" + stamp + ".json")).write_text(json.dumps({"rc":result.returncode,"stderr_bytes":len(result.stderr),"stderr_sha256":hashlib.sha256(result.stderr).hexdigest()}) + "\n")
if result.returncode or result.stderr:
    raise SystemExit("TELEMETRY_READ_FAILED")
out.write_bytes(result.stdout)
data=json.loads(result.stdout)
print(json.dumps({k:v for k,v in data.items() if k != "hashes"}))
