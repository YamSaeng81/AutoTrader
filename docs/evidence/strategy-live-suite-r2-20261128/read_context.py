"""Only registered trial decision provenance and own LIVE evaluation log lines."""
import base64
import datetime
import json
import pathlib
import subprocess

ROOT=pathlib.Path(__file__).parent
remote=r'''import datetime,json,pathlib,re,subprocess,sys
r=pathlib.Path.home()/'work-batches/strategy-live-suite-r2-20261128'
sys.path.insert(0,str(r));import observer
ids=[]
for folder in r.glob('[0-9][0-9]-*'):
 p=folder/'create-response.json'
 if p.is_file():
  s=json.loads(p.read_text());sid=s['id']
  if type(sid) is not int or sid<=205 or s['sessionType']!='REAL':raise SystemExit('TARGET_REJECTED')
  ids.append(sid)
if not ids:raise SystemExit('NO_REGISTERED_TARGETS')
sql="""BEGIN READ ONLY;SET LOCAL statement_timeout='15s';SET LOCAL lock_timeout='2s';
SELECT coalesce(json_agg(x),'[]'::json) FROM (SELECT l.session_id,l.created_at,l.strategy_name,l.signal,left(l.reason,600) AS reason,l.was_executed,left(l.blocked_reason,300) AS blocked_reason,l.indicators_json,l.ruleset_hash,
(l.ruleset_hash=left(encode(sha256(convert_to(s.params_text,'UTF8')),'hex'),12)) AS fingerprint_matches_snapshot,
(s.params_text LIKE '%maxEntries=1%') AS entry_limit_in_fingerprint
FROM strategy_log l LEFT JOIN ruleset_snapshot s ON s.ruleset_hash=l.ruleset_hash
WHERE l.session_type='LIVE' AND l.session_id IN ("""+','.join(map(str,ids))+""") ORDER BY l.id LIMIT 50) x;ROLLBACK;"""
decisions=json.loads(observer.sql_query(sql))
logs=subprocess.run(['docker','logs','--since','2026-11-28T09:59:00Z','autotrader-backend-1'],capture_output=True)
if logs.returncode:raise SystemExit('LOG_READ_FAILED')
pattern=re.compile(r'(?:sessionId=|id=)('+ '|'.join(map(str,ids))+r')(?=[^0-9]|$)')
lines=[line for line in (logs.stdout+logs.stderr).decode('utf-8').splitlines() if 'LiveTradingService' in line and pattern.search(line)]
safe=[]
for line in lines[-12:]:
 if re.search(r'(?i)Bearer |access_key|secret_key|Authorization:|API_AUTH_TOKEN',line):raise SystemExit('SENSITIVE_PATTERN_NO_EXPORT')
 safe.append(line[:700])
print(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'registered_live_ids':ids,'decisions':decisions,'own_live_log_lines':safe,'other_session_or_paper_performance_read':False}))
'''
b=base64.b64encode(remote.encode()).decode()
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod',"printf '%s' '"+b+"' | base64 -d | python3 -"],capture_output=True)
if p.returncode or p.stderr:raise SystemExit('CONTEXT_READ_FAILED rc='+str(p.returncode)+' stderr_bytes='+str(len(p.stderr)))
out=ROOT/('context-'+datetime.datetime.now(datetime.timezone.utc).strftime('%H%M%S%f')+'.json')
out.write_bytes(p.stdout)
print(p.stdout.decode())
