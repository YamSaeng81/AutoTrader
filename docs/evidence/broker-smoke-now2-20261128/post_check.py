"""State counts only after maintenance registration; no performance or account values."""
import base64
import hashlib
import json
import pathlib
import subprocess

ROOT=pathlib.Path(__file__).parent
out=ROOT/'health.json'
if out.exists():raise SystemExit('EXISTING_RESULT_NO_RETRY')
remote=r'''import datetime,json,pathlib,subprocess
app=pathlib.Path.home()/"crypto_trader/AutoTrader"
sql="""BEGIN READ ONLY;SET LOCAL statement_timeout='15s';SET LOCAL lock_timeout='2s';
SELECT json_build_object(
 'fixed1_untouched',(SELECT status='CREATED' AND started_at IS NULL AND available_krw=20200 AND NOT EXISTS(SELECT 1 FROM "order" WHERE session_kind='LIVE' AND session_id=203) AND NOT EXISTS(SELECT 1 FROM position WHERE session_kind='LIVE' AND session_id=203) FROM live_trading_session WHERE id=203),
 'trial_states',(SELECT json_agg(json_build_object('id',id,'status',status) ORDER BY id) FROM live_trading_session WHERE id IN(204,205)),
 'real_running_or_paused',(SELECT count(*) FROM live_trading_session WHERE session_type='REAL' AND status IN('RUNNING','PAUSED'))+(SELECT count(*) FROM dynamic_session WHERE trading_mode='REAL' AND status IN('RUNNING','PAUSED')),
 'paper66_total',(SELECT count(*) FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587),
 'paper66_running',(SELECT count(*) FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587 AND status='RUNNING'),
 'paper66_stopped',(SELECT count(*) FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587 AND status='STOPPED'),
 'cleanup_pending',(SELECT count(*) FROM position_cleanup WHERE done_at IS NULL),
 'maintenance_id2_present',EXISTS(SELECT 1 FROM exchange_maintenance_window WHERE id=2 AND starts_at=TIMESTAMPTZ '2026-11-08T17:00Z' AND ends_at=TIMESTAMPTZ '2026-11-08T22:00Z'));
ROLLBACK;"""
p=subprocess.run(['docker','compose','-f','docker-compose.prod.yml','exec','-T','-e','PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=2000','db','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','trader','-d','crypto_auto_trader'],cwd=app,input=sql.encode(),capture_output=True)
if p.returncode or p.stderr:raise SystemExit('HEALTH_READ_FAILED')
print(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'state_counts':json.loads(p.stdout),'paper_performance_read':False,'writes':0}))
'''
b=base64.b64encode(remote.encode()).decode()
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod',"printf '%s' '"+b+"' | base64 -d | python3 -"],capture_output=True)
out.write_bytes(p.stdout)
(ROOT/'health-transport.json').write_text(json.dumps({'rc':p.returncode,'stderr_bytes':len(p.stderr),'stderr_sha256':hashlib.sha256(p.stderr).hexdigest()})+'\n')
if p.returncode or p.stderr:raise SystemExit('READ_UNCONFIRMED_NO_RETRY')
print(p.stdout.decode())
