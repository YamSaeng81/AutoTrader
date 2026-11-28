"""Selected suite status, own decisions/costs and narrow enablement diagnostics."""
import base64
import datetime
import hashlib
import json
import pathlib
import subprocess

ROOT=pathlib.Path(__file__).parent
remote=r'''import datetime,hashlib,json,pathlib,subprocess,sys
r=pathlib.Path.home()/'work-batches/strategy-live-night-20261128'
sys.path.insert(0,str(r));import observer,plan
def load(p):return json.loads(p.read_text()) if p.is_file() else None
cases=[]
for d in sorted(r.glob('[0-9][0-9]-*')):
 cfg=load(d/'case.json');f=load(d/'observer-output/final.json');s=load(d/'observer-output/state.json')
 snapshot=(f or {}).get('state') or (s or {}).get('snapshot') or {}
 cases.append({'case':cfg,'create_sent':(d/'sent-create').exists(),'create_recorded':(d/'create-response.json').exists(),'start_sent':(d/'sent-start').exists(),'state':{k:snapshot.get(k) for k in ('id','status','open_positions','active_orders','cleanup_pending','decisions')},'buys':snapshot.get('buys'),'final':None if not f else {k:f.get(k) for k in ('status','strategy','sid','reason','gate','buy_total_cost','order_net_pnl','cash_order_match')},'observer_error':load(d/'observer-output/error.json')})
sql="""BEGIN READ ONLY; SET LOCAL statement_timeout='15s';SET LOCAL lock_timeout='2s';
SELECT json_build_object('new_live_states',(SELECT coalesce(json_agg(json_build_object('id',id,'strategy',strategy_type,'status',status)),'[]'::json) FROM live_trading_session WHERE id>205),
'type_enablement',(SELECT coalesce(json_agg(json_build_object('strategy',strategy_name,'active',is_active)),'[]'::json) FROM strategy_type_enabled WHERE strategy_name IN('COMPOSITE_BREAKOUT','COMPOSITE_MOMENTUM_ICHIMOKU_V2','COMPOSITE_REGIME_ROUTER','SUPERTREND','RSI','BOLLINGER','ATR_BREAKOUT','COMPOSITE_MOMENTUM','COMPOSITE_MOMENTUM_ICHIMOKU','COMPOSITE_MTF_BTC','COMPOSITE_MTF_MOMENTUM')),
'timeframe_enablement',(SELECT coalesce(json_agg(json_build_object('strategy',strategy_name,'timeframe',timeframe,'active',is_active)),'[]'::json) FROM strategy_timeframe_enabled WHERE timeframe='H1' AND strategy_name IN('COMPOSITE_BREAKOUT','COMPOSITE_MOMENTUM_ICHIMOKU_V2','COMPOSITE_REGIME_ROUTER','SUPERTREND','RSI','BOLLINGER','ATR_BREAKOUT','COMPOSITE_MOMENTUM','COMPOSITE_MOMENTUM_ICHIMOKU','COMPOSITE_MTF_BTC','COMPOSITE_MTF_MOMENTUM')));ROLLBACK;"""
diag=json.loads(observer.sql_query(sql))
logs=subprocess.run(['docker','logs','--since','2026-11-28T09:54:40Z','autotrader-backend-1'],capture_output=True)
if logs.returncode:raise SystemExit('LOG_READ_FAILED')
lines=(logs.stdout+logs.stderr).decode('utf-8').splitlines()
errors=[x for x in lines if 'GlobalExceptionHandler' in x]
categories={name:sum(1 for x in errors if word in x) for name,word in [('disabled','\ube44\ud65c\uc131\ud654'),('allocation','\uc790\ubcf8 \ucd08\uacfc'),('unsupported_strategy','\uc9c0\uc6d0\ud558\uc9c0 \uc54a\ub294 \uc804\ub7b5'),('session_conflict','\uc138\uc158 \uc0c1\ud0dc'),('validation','\uac80\uc99d') ]}
hashes={}
for d in r.glob('[0-9][0-9]-*'):
 for n in ('observer-output/final.json','observer-output/gate.txt','observer-output/gate-verdict.txt'):
  p=d/n
  if p.is_file():
   b=p.read_bytes();hashes[str(p.relative_to(r))]={'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b)}
print(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':load(r/'phase.json'),'budget':load(r/'budget.json'),'results':load(r/'results.json'),'final':load(r/'final.json'),'error':load(r/'error.json'),'cases':cases,'diagnostics':diag,'exception_log_categories':categories,'raw_payload_exported':False,'hashes':hashes},default=str))
'''
b=base64.b64encode(remote.encode()).decode()
p=subprocess.run([r'C:\Windows\System32\OpenSSH\ssh.exe','-F',r'C:\Users\SangJoon_Lee\.ssh\config','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-T','cat-prod',"printf '%s' '"+b+"' | base64 -d | python3 -"],capture_output=True)
name='status-'+datetime.datetime.now(datetime.timezone.utc).strftime('%H%M%S%f')
(ROOT/(name+'-transport.json')).write_text(json.dumps({'rc':p.returncode,'stderr_bytes':len(p.stderr),'stderr_sha256':hashlib.sha256(p.stderr).hexdigest()})+'\n')
if p.returncode or p.stderr:raise SystemExit('READ_FAILED_NO_RETRY')
(ROOT/(name+'.json')).write_bytes(p.stdout)
data=json.loads(p.stdout);print(json.dumps({k:v for k,v in data.items() if k!='hashes'}))
