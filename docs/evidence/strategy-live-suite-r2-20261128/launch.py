"""Start one pinned server suite, never re-run an existing path."""
import base64
import hashlib
import json
import pathlib
import shlex
import subprocess

ROOT=pathlib.Path(__file__).parent
names=('batch.py','plan.py','observer.py','preflight.py','preflight_guard.py','safe_api.py','gate_check.py','recovery-gate-v4.sql')
package={n:{'sha256':hashlib.sha256((ROOT/n).read_bytes()).hexdigest(),'b64':base64.b64encode((ROOT/n).read_bytes()).decode()} for n in names}
out=ROOT/'launch-result.json'
if out.exists():raise SystemExit('EXISTING_LAUNCH_RESULT_NO_RETRY')
remote=r'''import base64,datetime,hashlib,json,pathlib,subprocess,sys,time
p=json.load(sys.stdin)
r=pathlib.Path.home()/'work-batches/strategy-live-suite-r2-20261128'
if r.exists():raise SystemExit('EXISTING_PATH_NO_RETRY')
if datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date().isoformat()!='2026-11-28':raise SystemExit('DATE_MISMATCH')
data={}
for n,v in p.items():
 if pathlib.Path(n).name!=n:raise SystemExit('BAD_PATH')
 b=base64.b64decode(v['b64'],validate=True)
 if hashlib.sha256(b).hexdigest()!=v['sha256']:raise SystemExit('BAD_HASH')
 data[n]=b
r.mkdir(mode=0o700)
for n,b in data.items():
 with (r/n).open('xb') as f:f.write(b)
manifest={n:hashlib.sha256((r/n).read_bytes()).hexdigest() for n in data}
(r/'manifest.json').write_text(json.dumps(manifest))
# Read-only SQL parse/source probe on an already completed known trial before CREATE.
probe="import json,observer,plan;observer.CASE={'strategy':plan.STRATEGIES[0]};s=observer.snapshot(205);assert s['id']==205 and s['status']=='STOPPED' and not s['buys'] and not s['contract_ok'];print(json.dumps({'sql_probe':'PASS','target':205,'writes':0}))"
q=subprocess.run([sys.executable,'-B','-c',probe],cwd=r,capture_output=True)
(r/'sql-probe.json').write_bytes(q.stdout)
if q.returncode or q.stderr:raise SystemExit('SQL_PROBE_FAILED_NO_CREATE')
with (r/'batch.log').open('xb') as log:
 child=subprocess.Popen([sys.executable,'-u',str(r/'batch.py')],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
(r/'pid.txt').write_text(str(child.pid)+'\n')
time.sleep(2)
def load(n):return json.loads((r/n).read_text()) if (r/n).is_file() else None
print(json.dumps({'directory':str(r),'pid':child.pid,'child_rc':child.poll(),'source_hashes':manifest,'sql_probe':json.loads(q.stdout),'phase':load('phase.json'),'budget':load('budget.json'),'error':load('error.json'),'at':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
'''
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod','python3 -c '+shlex.quote(remote)],input=json.dumps(package).encode(),capture_output=True)
out.write_bytes(p.stdout)
(ROOT/'launch-transport.json').write_text(json.dumps({'rc':p.returncode,'stderr_bytes':len(p.stderr),'stderr_sha256':hashlib.sha256(p.stderr).hexdigest()})+'\n')
if p.returncode or p.stderr:raise SystemExit('LAUNCH_UNCONFIRMED_NO_RETRY')
result=json.loads(p.stdout)
if result['source_hashes']!={n:v['sha256'] for n,v in package.items()}:raise SystemExit('HASH_MISMATCH')
print(json.dumps({k:v for k,v in result.items() if k!='source_hashes'}))
