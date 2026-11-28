"""Read seven scan fields and three named non-secret universe env keys only."""
import base64
import hashlib
import json
import pathlib
import subprocess

ROOT=pathlib.Path(__file__).parent
OUT=ROOT/'result.json'
if OUT.exists(): raise SystemExit('EXISTING_RESULT_NO_RETRY')
remote=r'''import datetime,json,pathlib,subprocess
app=pathlib.Path.home()/"crypto_trader/AutoTrader"
def run(args,data=None):
 p=subprocess.run(args,cwd=app,input=data,capture_output=True)
 if p.returncode or p.stderr:raise SystemExit('READ_FAILED_OR_STDERR')
 return p.stdout
head=run(['git','rev-parse','HEAD']).decode().strip()
runtime=run(['docker','inspect','--format','{{.Image}}|{{.State.StartedAt}}','autotrader-backend-1']).decode().strip()
if head!='1b719645ef907cb1aa45ffa01438fcfda7566758' or runtime!='sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89|2026-11-28T03:10:38.948790758Z':raise SystemExit('PIN_MISMATCH')
sql="""BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='15s';SET LOCAL lock_timeout='2s';
DO $$BEGIN IF (SELECT count(*) FROM risk_config)<>1 THEN RAISE EXCEPTION 'RISK_ROW_COUNT'; END IF;END$$;
SELECT row_to_json(x) FROM (SELECT scan_max_candidate_size,scan_min_atr_pct,scan_max_spread_pct,scan_min_trade_value_krw,scan_max_atr_pct,scan_require_uptrend,scan_exclude_crashing FROM risk_config) x;
ROLLBACK;"""
before=datetime.datetime.now(datetime.timezone.utc).isoformat()
fields=json.loads(run(['docker','compose','-f','docker-compose.prod.yml','exec','-T','-e','PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=2000','db','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','trader','-d','crypto_auto_trader'],sql.encode()))
after=datetime.datetime.now(datetime.timezone.utc).isoformat()
env=json.loads(run(['docker','inspect','--format','{{json .Config.Env}}','autotrader-backend-1']))
keys=('SHARED_UNIVERSE_ENABLED','SHARED_UNIVERSE_TIMEFRAME','SHARED_UNIVERSE_REFRESH_MIN')
selected={}
for key in keys:
 values=[line.split('=',1)[1] for line in env if line.startswith(key+'=')]
 if len(values)>1:raise SystemExit('DUPLICATE_ENV_KEY')
 selected[key]={'present':bool(values),'value':values[0] if values else None}
print(json.dumps({'status':'READ_OK','product':head,'backend_runtime':runtime,'db_bracket_utc':{'before':before,'after':after},'scan_fields':fields,'selected_env':selected,'scope':'seven risk fields, three non-secret named env keys; no sessions, holdings, orders, credentials or other environment content returned','runtime_binding_verified':False,'writes':0}))
'''
b=base64.b64encode(remote.encode()).decode()
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod',"printf '%s' '"+b+"' | base64 -d | python3 -"],capture_output=True)
OUT.write_bytes(p.stdout)
(ROOT/'transport.json').write_text(json.dumps({'rc':p.returncode,'stderr_bytes':len(p.stderr),'stderr_sha256':hashlib.sha256(p.stderr).hexdigest()})+'\n')
if p.returncode or p.stderr:raise SystemExit('READ_UNCONFIRMED_NO_RETRY')
print(p.stdout.decode())
