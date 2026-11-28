"""Second staging attempt only after read-only proof that the first created no folder."""
import base64
import hashlib
import json
import pathlib
import shlex
import subprocess

ROOT = pathlib.Path(__file__).parent
E = ROOT.parent
paths = {name: ROOT / name for name in ("driver.py", "preflight.py", "observer.py")}
paths.update({name: E / "live-smoke-draft-v3-20261128" / name for name in ("smoke.sh", "safe_api.py", "kst.py")})
paths.update({name: E / "one-entry-gate-v4-20261125" / name for name in ("gate_check.py", "recovery-gate-v4.sql")})
package = {name: {"sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "b64": base64.b64encode(p.read_bytes()).decode()} for name, p in paths.items()}
(ROOT / "run-package-manifest.json").write_text(json.dumps({n: v["sha256"] for n, v in package.items()}, indent=2) + "\n", encoding="utf-8", newline="")
remote = '''import base64,datetime,hashlib,json,os,pathlib,subprocess,sys,time
p=json.load(sys.stdin)
root=pathlib.Path.home()/"work-batches/live-smoke-20261128-exp1"
if root.exists(): raise SystemExit("EXISTING_PATH_NO_RETRY")
data={}
for name,item in p.items():
 if pathlib.Path(name).name!=name: raise SystemExit("BAD_PATH")
 b=base64.b64decode(item["b64"],validate=True)
 if hashlib.sha256(b).hexdigest()!=item["sha256"]: raise SystemExit("BAD_HASH")
 data[name]=b
now=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9)))
if now.date().isoformat()!="2026-11-28": raise SystemExit("APPROVAL_DATE_MISMATCH")
root.mkdir()
for name,b in data.items():
 with (root/name).open("xb") as f: f.write(b)
manifest={n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in data}
(root/"manifest.json").write_text(json.dumps(manifest))
cfg={"operator_ready":True,"external_freeze":True,"excluded_existing_currency":"APENFT","product":"1b719645ef907cb1aa45ffa01438fcfda7566758","image":"sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89","run_tag":now.strftime("%Y%m%d-%H%M"),"deadline_epoch":time.time()+1200,"launched_at":now.isoformat()}
(root/"run-config.json").write_text(json.dumps(cfg))
with (root/"launch-once.json").open("x") as f: json.dump({"at":now.isoformat(),"source_hashes":manifest},f)
with (root/"driver.log").open("xb") as log:
 child=subprocess.Popen([sys.executable,"-u",str(root/"driver.py")],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
(root/"driver-pid.txt").write_text(str(child.pid)+"\\n")
time.sleep(3)
print(json.dumps({"directory":str(root),"pid":child.pid,"child_rc":child.poll(),"config":cfg,"manifest":manifest,"phase":json.loads((root/"driver-output/phase.json").read_text()) if (root/"driver-output/phase.json").exists() else None,"error":json.loads((root/"driver-output/error.json").read_text()) if (root/"driver-output/error.json").exists() else None}))
'''
out = ROOT / "start-job-result.json"
if out.exists():
    raise SystemExit("Existing start result; no retry")
result = subprocess.run([r"C:\Windows\System32\OpenSSH\ssh.exe", "-F", r"C:\Users\SangJoon_Lee\.ssh\config", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15", "-o", "ConnectionAttempts=1", "-T", "cat-prod", "python3 -c " + shlex.quote(remote)], input=json.dumps(package).encode(), capture_output=True)
out.write_bytes(result.stdout)
(ROOT / "start-job-transport.json").write_text(json.dumps({"rc": result.returncode, "stderr_bytes": len(result.stderr), "stderr_sha256": hashlib.sha256(result.stderr).hexdigest()}, indent=2) + "\n")
print("SSH rc", result.returncode, "stderr bytes", len(result.stderr))
if result.returncode or result.stderr:
    raise SystemExit(1)
data = json.loads(result.stdout)
if data["manifest"] != {n: v["sha256"] for n, v in package.items()}:
    raise SystemExit("SOURCE_HASH_MISMATCH")
print(json.dumps({k: data[k] for k in ("directory", "pid", "child_rc", "config", "phase", "error")}))
