import copy
import json
import pathlib
from decimal import Decimal as D
import plan
import observer
import batch

results=[]
def check(name,condition):results.append({'case':name,'pass':bool(condition)})
check('all_seven_maximum_reservations_under_cap',plan.PRIOR+plan.MAX_ADMITTED*plan.RESERVE<=plan.TOTAL)
check('exact_cap',plan.can_reserve('44400','0.0005'))
check('cap_plus_one_cent',not plan.can_reserve('44400.01','0.0005'))
check('fee_over_reserve',not plan.can_reserve(plan.PRIOR,'0.02'))
check('nan_fee',not plan.can_reserve(plan.PRIOR,'NaN'))
check('prior_not_omitted',not plan.can_reserve('0','0.0005'))
check('fixed_order_eleven_unique',len(set(plan.STRATEGIES))==11)
check('deprecated_blocked_absent',not set(plan.STRATEGIES)&{'TEST_TIMED','MACD','STOCHASTIC_RSI','MACD_STOCH_BB','COMPOSITE_BREAKOUT_ICHIMOKU'})
for strategy in plan.STRATEGIES:
    b=plan.request_body(strategy)
    check('request_'+strategy,b['maxInvestment']==5500 and b['initialCapital']==10000 and b['strategyParams']=={'maxEntries':1} and b['allowExperimentalLive']==(strategy in plan.EXPERIMENTAL))
base={'contract_ok':True,'status':'RUNNING','buys':[],'active_orders':0,'last_signal':'HOLD','last_decision_epoch':100,'started_epoch':95}
check('hold_wait_twenty_seconds',observer.decide(base,1000,119) is None)
check('hold_completes_no_buy_case',observer.decide(base,1000,120)=='NO_BUY_DECISION')
buy=copy.deepcopy(base);buy['last_signal']='BUY'
check('buy_signal_not_early_stopped',observer.decide(buy,1000,120) is None)
unknown=copy.deepcopy(base);unknown['buys']=[{'state':'UNKNOWN','funds':None,'fee':None,'pending':True}]
check('unknown_stops',observer.decide(unknown,1000,120)=='ORDER_UNKNOWN')
fill=copy.deepcopy(base);fill['buys']=[{'state':'FILLED','funds':'5500','fee':'2.75','pending':False}]
check('settled_buy_stops',observer.decide(fill,1000,120)=='BUY_SETTLED')
two=copy.deepcopy(fill);two['buys']*=2
check('two_buy_violation',observer.decide(two,1000,120)=='BUY_LIMIT_VIOLATION')
cost=copy.deepcopy(fill);cost['buys'][0]['funds']='5600'
check('case_cost_violation',observer.decide(cost,1000,120)=='COST_LIMIT_VIOLATION')
check('source_cost',plan.buy_cost(fill['buys'])==D('5502.75'))
try:plan.buy_cost([{'state':'FILLED','funds':'5500','fee':None}]);bad=False
except ValueError:bad=True
check('unknown_fee_not_zero',bad)
check('proven_failed_zero',plan.buy_cost([{'state':'FAILED','funds':None,'fee':None,'failure_code':'RISK_REJECTED','volume':None}])==0)
try:plan.buy_cost([{'state':'FAILED','funds':None,'fee':None,'failure_code':'OTHER','volume':None}]);bad=False
except ValueError:bad=True
check('unproven_failure_unknown',bad)
b=plan.request_body(plan.STRATEGIES[0]);response=dict(b,status='CREATED',sessionType='REAL')
try:batch.verify_response(response,b);good=True
except RuntimeError:good=False
check('response_contract_valid',good)
for field,value in [('strategyType','RSI'),('sessionType','PAPER'),('maxInvestment',20000),('strategyParams',{'maxEntries':'1'})]:
    r=dict(response);r[field]=value
    try:batch.verify_response(r,b);bad=False
    except RuntimeError:bad=True
    check('reject_response_'+field,bad)
check('absent_enablement_is_allowed',plan.enabled({'type_exists':False,'timeframe_exists':False}))
check('disabled_strategy_is_skipped',not plan.enabled({'type_exists':True,'type_active':False,'timeframe_exists':False}))
check('disabled_timeframe_is_skipped',not plan.enabled({'type_exists':False,'timeframe_exists':True,'timeframe_active':False}))
try:plan.enabled({'type_exists':True,'type_active':None,'timeframe_exists':False});bad=False
except ValueError:bad=True
check('unknown_flag_not_allowed',bad)
out=pathlib.Path(__file__).parent/'test-results.json'
if out.exists():raise SystemExit('EXISTING_RESULT')
out.write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
print(sum(x['pass'] for x in results),'/',len(results),'boundaries; no SSH, DB, network or order calls')
raise SystemExit(0 if all(x['pass'] for x in results) else 1)
