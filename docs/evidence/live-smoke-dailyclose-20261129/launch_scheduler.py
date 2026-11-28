"""Stage one immutable package and start its finite server scheduler once."""
import base64
import hashlib
import json
import pathlib
import shlex
import subprocess

ROOT=pathlib.Path(__file__).parent
E=ROOT.parent
files={n:ROOT/n for n in ('scheduler.py','driver.py','preflight.py','observer.py')}
files.update({n:E/'live-smoke-draft-v3-20261128'/n for n in ('smoke.sh','safe_api.py','kst.py')})
files.update({n:E/'one-entry-gate-v4-20261125'/n for n in ('gate_check.py','recovery-gate-v4.sql')})
package={n:{'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'b64':base64.b64encode(p.read_bytes()).decode()} for n,p in files.items()}
out=ROOT/'launch-result.json'
if out.exists(): raise SystemExit('EXISTING_RESULT_NO_RETRY')
remote=r'''import base64,datetime,hashlib,json,pathlib,subprocess,sys,time
package=json.load(sys.stdin)
app=pathlib.Path.home()/"crypto_trader/AutoTrader"
def cmd(args):
 p=subprocess.run(args,cwd=app,capture_output=True)
 if p.returncode or p.stderr: raise SystemExit('PIN_READ_FAILED')
 return p.stdout.decode().strip()
if datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date().isoformat()!='2026-11-28': raise SystemExit('REGISTRATION_DATE_MISMATCH')
if cmd(['git','rev-parse','HEAD'])!='1b719645ef907cb1aa45ffa01438fcfda7566758': raise SystemExit('HEAD_MISMATCH')
if cmd(['git','status','--porcelain','--untracked-files=no']): raise SystemExit('DIRTY_TRACKED_FILES')
if cmd(['docker','inspect','--format','{{.Image}}|{{.State.StartedAt}}','autotrader-backend-1'])!='sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89|2026-11-28T03:10:38.948790758Z': raise SystemExit('INSTANCE_MISMATCH')
r=pathlib.Path.home()/'work-batches/live-smoke-20261129-dailyclose'
if r.exists(): raise SystemExit('EXISTING_PATH_NO_RETRY')
data={}
for name,value in package.items():
 if pathlib.Path(name).name!=name: raise SystemExit('BAD_PATH')
 b=base64.b64decode(value['b64'],validate=True)
 if hashlib.sha256(b).hexdigest()!=value['sha256']: raise SystemExit('BAD_HASH')
 data[name]=b
for folder,sid in [('live-smoke-20261128-exp1',204),('live-smoke-20261128-exp3',205)]:
 f=json.loads((r.parent/folder/'observer-output/final.json').read_text())
 if f.get('sid')!=sid or f.get('status')!='COMPLETE' or f.get('gate')!='PASS' or f['state']['status']!='STOPPED' or f['state']['buys']: raise SystemExit('PRIOR_RESULT_MISMATCH')
r.mkdir(mode=0o700)
for name,b in data.items():
 with (r/name).open('xb') as f:f.write(b)
manifest={n:hashlib.sha256((r/n).read_bytes()).hexdigest() for n in data}
(r/'manifest.json').write_text(json.dumps(manifest))
with (r/'scheduler.log').open('xb') as log:
 child=subprocess.Popen([sys.executable,'-u',str(r/'scheduler.py')],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
(r/'scheduler-pid.txt').write_text(str(child.pid)+'\n')
time.sleep(2)
state=json.loads((r/'scheduler-state.json').read_text()) if (r/'scheduler-state.json').is_file() else None
print(json.dumps({'directory':str(r),'pid':child.pid,'child_rc':child.poll(),'state':state,'manifest':manifest,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
'''
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod','python3 -c '+shlex.quote(remote)],input=json.dumps(package).encode(),capture_output=True)
out.write_bytes(p.stdout)
(ROOT/'launch-transport.json').write_text(json.dumps({'rc':p.returncode,'stderr_bytes':len(p.stderr),'stderr_sha256':hashlib.sha256(p.stderr).hexdigest()})+'\n')
if p.returncode or p.stderr: raise SystemExit('LAUNCH_UNCONFIRMED_NO_RETRY')
result=json.loads(p.stdout)
if result['manifest']!={n:v['sha256'] for n,v in package.items()}: raise SystemExit('MANIFEST_MISMATCH')
print(json.dumps({k:v for k,v in result.items() if k!='manifest'}))
