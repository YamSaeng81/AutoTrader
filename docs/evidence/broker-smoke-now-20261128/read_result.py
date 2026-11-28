"""Return only this trial's totals/verdicts and server file hashes."""
import base64
import datetime
import hashlib
import json
import pathlib
import subprocess

ROOT=pathlib.Path(__file__).parent
remote=r'''import datetime,hashlib,json,pathlib
r=pathlib.Path.home()/'work-batches/broker-smoke-now-20261128'
def load(n):
 p=r/n
 return json.loads(p.read_text()) if p.is_file() else None
hashes={}
for p in r.iterdir():
 if p.is_file():
  b=p.read_bytes();hashes[p.name]={'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b)}
schedule=r.parent/'live-smoke-20261129-dailyclose'
print(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':load('phase.json'),'final':load('final.json'),'error':load('error.json'),'cleanup_error':load('cleanup-unconfirmed.json'),'fallback_sell':load('fallback-sell-totals.json'),'buy_totals':load('buy-totals.json'),'sell_totals':load('sell-totals.json'),'buy_post_intent':(r/'sent-buy').exists(),'sell_post_intent':(r/'sent-sell').exists(),'tomorrow_scheduler_final':json.loads((schedule/'scheduler-final.json').read_text()),'raw_payload_exported':False,'hashes':hashes}))
'''
b=base64.b64encode(remote.encode()).decode()
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod',"printf '%s' '"+b+"' | base64 -d | python3 -"],capture_output=True)
name='result-'+datetime.datetime.now(datetime.timezone.utc).strftime('%H%M%S%f')
(ROOT/(name+'-transport.json')).write_text(json.dumps({'rc':p.returncode,'stderr_bytes':len(p.stderr),'stderr_sha256':hashlib.sha256(p.stderr).hexdigest()})+'\n')
if p.returncode or p.stderr:raise SystemExit('READ_UNCONFIRMED')
(ROOT/(name+'.json')).write_bytes(p.stdout)
data=json.loads(p.stdout)
print(json.dumps({k:v for k,v in data.items() if k!='hashes'}))
