"""Apply the explicitly approved one-row maintenance record once; no credentials exported."""
import base64
import hashlib
import json
import pathlib
import shlex
import subprocess

ROOT = pathlib.Path(__file__).parent
sql = (ROOT / "register-window.sql").read_bytes()
if hashlib.sha256(sql).hexdigest() != "2988eba0b3a78f83524b9854df985a73ddfd417811c689414ed9bb04cfcea663":
    raise SystemExit("SQL_HASH_MISMATCH")
out = ROOT / "registration-result.json"
if out.exists():
    raise SystemExit("Existing attempt; not repeated")
remote = '''import datetime,json,pathlib,subprocess,sys
app=pathlib.Path.home()/"crypto_trader/AutoTrader"
def checked(args):
 p=subprocess.run(args,cwd=app,capture_output=True)
 if p.returncode or p.stderr: raise SystemExit("PIN_READ_FAILED")
 return p.stdout.decode().strip()
if checked(["git","rev-parse","HEAD"])!="1b719645ef907cb1aa45ffa01438fcfda7566758": raise SystemExit("HEAD_MISMATCH")
if checked(["git","status","--porcelain","--untracked-files=no"]): raise SystemExit("TRACKED_FILES_CHANGED")
if checked(["docker","inspect","--format","{{.Image}}|{{.State.StartedAt}}","autotrader-backend-1"])!="sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89|2026-11-28T03:10:38.948790758Z": raise SystemExit("INSTANCE_MISMATCH")
sql=sys.stdin.buffer.read()
p=subprocess.run(["docker","compose","-f","docker-compose.prod.yml","exec","-T","-e","PGOPTIONS=-c statement_timeout=15000 -c lock_timeout=2000","db","psql","-X","-qAt","-F","|","-v","ON_ERROR_STOP=1","-U","trader","-d","crypto_auto_trader"],input=sql,capture_output=True,cwd=app)
print(json.dumps({"at":datetime.datetime.now(datetime.timezone.utc).isoformat(),"psql_rc":p.returncode,"stderr_bytes":len(p.stderr),"returned_metadata":p.stdout.decode().strip(),"sql_sha256":__import__("hashlib").sha256(sql).hexdigest()}))
raise SystemExit(p.returncode or bool(p.stderr))
'''
p = subprocess.run([r"C:\Windows\System32\OpenSSH\ssh.exe", "-F", r"C:\Users\SangJoon_Lee\.ssh\config", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15", "-o", "ConnectionAttempts=1", "-T", "cat-prod", "python3 -c " + shlex.quote(remote)], input=sql, capture_output=True)
out.write_bytes(p.stdout)
(ROOT / "registration-transport.json").write_text(json.dumps({"ssh_rc":p.returncode,"stderr_bytes":len(p.stderr),"stderr_sha256":hashlib.sha256(p.stderr).hexdigest()},indent=2)+"\n",encoding="utf-8",newline="")
print("SSH rc",p.returncode,"stderr bytes",len(p.stderr))
if p.stdout:
    print(p.stdout.decode(errors="replace"))
raise SystemExit(p.returncode or bool(p.stderr))
