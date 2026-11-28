"""Read only the trial's last three decisions and guard log counts."""
import base64
import datetime
import json
import pathlib
import subprocess

ROOT=pathlib.Path(__file__).parent
remote=r'''import datetime,json,pathlib,subprocess
app=pathlib.Path.home()/"crypto_trader/AutoTrader"
sql="""BEGIN READ ONLY;
SET LOCAL statement_timeout='15s'; SET LOCAL lock_timeout='2s';
SELECT COALESCE(json_agg(x),'[]'::json) FROM (
 SELECT l.created_at,l.signal,left(l.reason,600) AS reason,l.was_executed,
 left(l.blocked_reason,300) AS blocked_reason,l.ruleset_hash,
 (l.ruleset_hash=left(encode(sha256(convert_to(s.params_text,'UTF8')),'hex'),12)) AS fingerprint_matches_snapshot,
 (s.params_text LIKE '%maxEntries=1%') AS entry_limit_in_fingerprint
 FROM strategy_log l LEFT JOIN ruleset_snapshot s ON s.ruleset_hash=l.ruleset_hash
 WHERE l.session_type='LIVE' AND l.session_id=205 ORDER BY l.id DESC LIMIT 3) x;
ROLLBACK;"""
p=subprocess.run(['docker','compose','-f','docker-compose.prod.yml','exec','-T','-e','PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=2000','db','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','trader','-d','crypto_auto_trader'],cwd=app,input=sql.encode(),capture_output=True)
if p.returncode or p.stderr: raise SystemExit('SIGNAL_READ_FAILED')
decisions=json.loads(p.stdout)
logs=subprocess.run(['docker','logs','--since','2026-11-28T05:33:22Z','autotrader-backend-1'],capture_output=True)
if logs.returncode: raise SystemExit('LOG_READ_FAILED')
lines=(logs.stdout+logs.stderr).decode('utf-8',errors='strict').splitlines()
guard=[x for x in lines if 'CandleFreshnessGuard' in x and 'KRW-BTC:H1' in x]
exempt=[x for x in guard if '\uacf5\uc2dd \uc810\uac80 \uacf5\ubc31' in x]
failed=[x for x in guard if 'ERROR' in x]
print(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sid':205,'decisions':decisions,'maintenance_exemption_log_count':len(exempt),'btc_h1_guard_error_count_since_registration':len(failed),'raw_log_exported':False}))
'''
encoded=base64.b64encode(remote.encode()).decode()
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod',"printf '%s' '"+encoded+"' | base64 -d | python3 -"],capture_output=True)
if p.returncode or p.stderr: raise SystemExit('READ_FAILED rc='+str(p.returncode)+' stderr_bytes='+str(len(p.stderr)))
out=ROOT/('signal-'+datetime.datetime.now(datetime.timezone.utc).strftime('%H%M%S%f')+'.json')
out.write_bytes(p.stdout)
print(p.stdout.decode('utf-8'))
