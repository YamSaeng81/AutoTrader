import base64
import hashlib
import json
import pathlib
import shlex
import subprocess

DEST = pathlib.Path(__file__).parent.parent / "live-smoke-results-20261128"
if DEST.exists():
    raise SystemExit("Existing results; not overwritten")
remote = '''import base64,hashlib,json,pathlib
home=pathlib.Path.home()/"work-batches"
records={}; missing=[]
paths=["run-config.json","driver-output/preflight.json","driver-output/phase.json","driver-output/error.json","driver-output/final.json","driver-output/session.json","driver-output/p0.out","driver-output/create.out","driver-output/verify.out","driver-output/start.out","driver-output/observer-armed.json","observer-output/armed.json","observer-output/state.json","observer-output/operator-stop-reason.json","observer-output/operator-stop-response.json","observer-output/stop-reason.json","observer-output/gate.txt","observer-output/gate-rc.txt","observer-output/registration.txt","observer-output/gate-verdict.txt","observer-output/broker-after.json","observer-output/final.json","observer-output/error.json"]
for attempt in ("exp1","exp2"):
 root=home/("live-smoke-20261128-"+attempt)
 for name in paths:
  p=root/name; key=attempt+"/"+name
  if not p.is_file(): missing.append(key);continue
  data=p.read_bytes();records[key]={"sha256":hashlib.sha256(data).hexdigest(),"b64":base64.b64encode(data).decode()}
print(json.dumps({"records":records,"not_present":missing}))
'''
p = subprocess.run([r"C:\Windows\System32\OpenSSH\ssh.exe", "-F", r"C:\Users\SangJoon_Lee\.ssh\config", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10", "-o", "ConnectionAttempts=1", "-T", "cat-prod", "python3 -c " + shlex.quote(remote)], capture_output=True)
if p.returncode or p.stderr:
    raise SystemExit(f"Read failed rc={p.returncode} stderr_bytes={len(p.stderr)}; no retry")
result = json.loads(p.stdout)
decoded = {}
for name, item in result["records"].items():
    if ".." in pathlib.PurePosixPath(name).parts or not name.startswith(("exp1/", "exp2/")):
        raise SystemExit("Invalid path")
    data = base64.b64decode(item["b64"], validate=True)
    if hashlib.sha256(data).hexdigest() != item["sha256"]:
        raise SystemExit("Hash mismatch")
    decoded[name] = data
DEST.mkdir()
(DEST / ".gitattributes").write_text("* -text\n", encoding="utf-8", newline="")
for name, data in decoded.items():
    path = DEST / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
(DEST / "remote-hashes.json").write_text(json.dumps({k:v["sha256"] for k,v in result["records"].items()},indent=2)+"\n",encoding="utf-8",newline="")
(DEST / "not-present.json").write_text(json.dumps(result["not_present"],indent=2)+"\n",encoding="utf-8",newline="")
print(f"Preserved {len(decoded)} files; all remote/local hashes match")
