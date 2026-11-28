import base64
import hashlib
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).parent
EVIDENCE = ROOT.parent
files = {
    "observer.py": ROOT / "observer.py",
    "safe_api.py": EVIDENCE / "live-smoke-draft-v3-20261128/safe_api.py",
    "gate_check.py": EVIDENCE / "one-entry-gate-v4-20261125/gate_check.py",
    "recovery-gate-v4.sql": EVIDENCE / "one-entry-gate-v4-20261125/recovery-gate-v4.sql",
}
package = {name: {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "b64": base64.b64encode(path.read_bytes()).decode()} for name, path in files.items()}
manifest = {name: data["sha256"] for name, data in package.items()}
(ROOT / "package-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="")
remote = '''import base64,hashlib,json,pathlib,sys
p=json.load(sys.stdin)
root=pathlib.Path.home()/"work-batches/live-smoke-20261128-exp1"
if root.exists(): raise SystemExit("EXISTING_PATH_NO_RETRY")
values={}
for name,item in p.items():
 if pathlib.Path(name).name!=name: raise SystemExit("BAD_PATH")
 data=base64.b64decode(item["b64"],validate=True)
 if hashlib.sha256(data).hexdigest()!=item["sha256"]: raise SystemExit("BAD_HASH")
 values[name]=data
root.mkdir()
for name,data in values.items():
 with (root/name).open("xb") as f: f.write(data)
manifest={n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in values}
(root/"manifest.json").write_text(json.dumps(manifest,sort_keys=True)+"\\n")
print(json.dumps({"status":"STAGED_ONLY","directory":str(root),"files":manifest}))
'''
command = "python3 -c " + __import__("shlex").quote(remote)
if (ROOT / "stage-result.json").exists():
    raise SystemExit("Existing stage result; not repeated")
p = subprocess.run([r"C:\Windows\System32\OpenSSH\ssh.exe", "-F", r"C:\Users\SangJoon_Lee\.ssh\config", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15", "-T", "cat-prod", command], input=json.dumps(package).encode(), capture_output=True)
(ROOT / "stage-result.json").write_bytes(p.stdout)
print("stage rc", p.returncode, "stderr bytes", len(p.stderr))
if p.returncode or p.stderr:
    raise SystemExit(1)
result = json.loads(p.stdout)
if result["files"] != manifest:
    raise SystemExit("REMOTE_HASH_MISMATCH")
print("Staged only;", len(manifest), "source hashes match; no process started")
