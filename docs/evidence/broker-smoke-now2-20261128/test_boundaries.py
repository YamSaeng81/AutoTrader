import base64
import importlib.util
import json
import pathlib

root=pathlib.Path(__file__).parent
spec=importlib.util.spec_from_file_location('broker_trial',root/'broker_trial.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
results=[]
token=m.jwt_token('FAKE_ACCESS','FAKE_SECRET',{'market':'KRW-BTC','identifier':m.BUY_ID},'fixed-nonce')
parts=token.split('.')
payload=json.loads(base64.urlsafe_b64decode(parts[1]+'='*(-len(parts[1])%4)))
results.append({'case':'jwt_query_binding','pass':payload['query_hash_alg']=='SHA512' and payload['nonce']=='fixed-nonce' and len(payload['query_hash'])==128})
base={'market':'KRW-BTC','side':'bid','identifier':m.BUY_ID,'uuid':'FAKE_UUID','state':'done','executed_volume':'0.0001','paid_fee':'4.975','trades_count':1,'trades':[{'volume':'0.0001','funds':'9950'}]}
results.append({'case':'terminal_source_totals','pass':m.order_totals(base,'bid')['funds']+m.order_totals(base,'bid')['fee']==m.D('9954.975')})
cancel=dict(base,state='cancel')
results.append({'case':'cancel_with_fill_is_not_zero','pass':m.order_totals(cancel,'bid')['volume']==m.D('0.0001')})
for name,key,value in [('wrong_market','market','KRW-ETH'),('wrong_id','identifier','OTHER'),('wrong_side','side','ask'),('not_terminal','state','wait'),('incomplete_trades','trades_count',2),('volume_mismatch','executed_volume','0.0002'),('fee_missing','paid_fee',None),('fee_nan','paid_fee','NaN'),('fee_negative','paid_fee','-1')]:
    data=dict(base);data[key]=value
    try:m.order_totals(data,'bid');passed=False
    except (RuntimeError,ValueError):passed=True
    results.append({'case':name,'pass':passed})
for name,fee,expected in [('fee_within_budget','0.0005',True),('fee_over_budget','0.01',False)]:
    results.append({'case':name,'pass':(m.AMOUNT*(1+m.number(fee))<=m.D('10000'))==expected})
results.append({'case':'effective_container_auth_only','pass':m.effective_keys(['UPBIT_ACCESS_KEY=FAKE_A','UPBIT_SECRET_KEY=FAKE_S','OTHER_SECRET=IGNORED'])==('FAKE_A','FAKE_S')})
for name,env in [('missing_effective_key',['UPBIT_ACCESS_KEY=FAKE']),('duplicate_effective_key',['UPBIT_ACCESS_KEY=FAKE','UPBIT_ACCESS_KEY=OTHER','UPBIT_SECRET_KEY=FAKE'])]:
    try:m.effective_keys(env);passed=False
    except RuntimeError:passed=True
    results.append({'case':name,'pass':passed})
out=root/'test-results.json'
if out.exists():raise SystemExit('EXISTING_RESULT')
out.write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
print(sum(r['pass'] for r in results),'/',len(results),'boundaries; network/SSH/DB/order calls 0')
raise SystemExit(0 if all(r['pass'] for r in results) else 1)
