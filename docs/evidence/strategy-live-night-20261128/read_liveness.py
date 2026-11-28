"""Verify exact process command paths and observer heartbeat without payload export."""
import base64
import datetime
import json
import pathlib
import subprocess

ROOT=pathlib.Path(__file__).parent
remote=r'''import datetime,json,pathlib
r=pathlib.Path.home()/'work-batches/strategy-live-night-20261128'
def alive(pid,expected):
 try:return str(expected).encode() in (pathlib.Path('/proc')/str(pid)/'cmdline').read_bytes()
 except FileNotFoundError:return False
pid=int((r/'pid.txt').read_text())
phase=json.loads((r/'phase.json').read_text())
sid=phase['sid'];case=phase['case'];strategy=phase['strategy']
folder=r/('%02d-%s'%(case,strategy));opid=phase.get('observer_pid')
state=folder/'observer-output/state.json'
stamp=datetime.datetime.fromtimestamp(state.stat().st_mtime,datetime.timezone.utc).isoformat() if state.is_file() else None
print(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'batch_pid':pid,'batch_process_matches':alive(pid,r/'batch.py'),'observer_pid':opid,'observer_process_matches':False if opid is None else alive(opid,folder/'observer.py'),'sid':sid,'observer_state_written_at':stamp}))
'''
b=base64.b64encode(remote.encode()).decode()
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod',"printf '%s' '"+b+"' | base64 -d | python3 -"],capture_output=True)
if p.returncode or p.stderr:raise SystemExit('LIVENESS_READ_FAILED')
out=ROOT/('liveness-'+datetime.datetime.now(datetime.timezone.utc).strftime('%H%M%S%f')+'.json')
out.write_bytes(p.stdout)
print(p.stdout.decode())
