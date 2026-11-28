"""Cancel scheduled test, stage a private finite external trial and start it once."""
import base64
import hashlib
import json
import pathlib
import shlex
import subprocess

ROOT=pathlib.Path(__file__).parent
paths={'broker_trial.py':ROOT/'broker_trial.py'}
paths.update({n:ROOT.parent/'live-smoke-exec3-20261128'/n for n in ('preflight.py','driver.py')})
package={n:{'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'b64':base64.b64encode(p.read_bytes()).decode()} for n,p in paths.items()}
out=ROOT/'launch-result.json'
if out.exists():raise SystemExit('EXISTING_RESULT_NO_RETRY')
remote=r'''import base64,datetime,hashlib,json,pathlib,subprocess,sys,time
package=json.load(sys.stdin)
base=pathlib.Path.home()/'work-batches'
root=base/'broker-smoke-now-20261128'
if root.exists():raise SystemExit('EXISTING_TRIAL_PATH_NO_RETRY')
if datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date().isoformat()!='2026-11-28':raise SystemExit('DATE_MISMATCH')
files={}
for n,item in package.items():
 if pathlib.Path(n).name!=n:raise SystemExit('BAD_PATH')
 b=base64.b64decode(item['b64'],validate=True)
 if hashlib.sha256(b).hexdigest()!=item['sha256']:raise SystemExit('HASH_MISMATCH')
 files[n]=b
schedule=base/'live-smoke-20261129-dailyclose'
state=json.loads((schedule/'scheduler-state.json').read_text())
if state.get('status')!='WAITING':raise SystemExit('SCHEDULE_NOT_WAITING')
with (schedule/'CANCEL').open('x') as f:f.write('User requested immediate execution test; cancel future trial to avoid duplicate budget\n')
deadline=time.monotonic()+40
while not (schedule/'scheduler-final.json').is_file():
 if time.monotonic()>=deadline:raise SystemExit('CANCELLATION_UNCONFIRMED_NO_BUY')
 time.sleep(1)
final=json.loads((schedule/'scheduler-final.json').read_text())
if final!={'status':'CANCELLED','trial_started':False}:raise SystemExit('CANCELLATION_NOT_CONFIRMED')
root.mkdir(mode=0o700)
for n,b in files.items():
 with (root/n).open('xb') as f:f.write(b)
manifest={n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in files}
(root/'manifest.json').write_text(json.dumps(manifest))
with (root/'trial.log').open('xb') as log:
 child=subprocess.Popen([sys.executable,'-u',str(root/'broker_trial.py')],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
(root/'pid.txt').write_text(str(child.pid)+'\n')
time.sleep(2)
print(json.dumps({'directory':str(root),'pid':child.pid,'child_rc':child.poll(),'tomorrow_trial_cancelled':final,'source_hashes':manifest,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
'''
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod','python3 -c '+shlex.quote(remote)],input=json.dumps(package).encode(),capture_output=True)
out.write_bytes(p.stdout)
(ROOT/'launch-transport.json').write_text(json.dumps({'rc':p.returncode,'stderr_bytes':len(p.stderr),'stderr_sha256':hashlib.sha256(p.stderr).hexdigest()})+'\n')
if p.returncode or p.stderr:raise SystemExit('LAUNCH_UNCONFIRMED_NO_RETRY')
result=json.loads(p.stdout)
if result['source_hashes']!={n:v['sha256'] for n,v in package.items()}:raise SystemExit('MANIFEST_MISMATCH')
print(json.dumps({k:v for k,v in result.items() if k!='source_hashes'}))
