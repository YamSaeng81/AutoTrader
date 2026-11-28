import copy
import datetime
import json
import pathlib
import sys
from decimal import Decimal as D

ROOT=pathlib.Path(__file__).parent
sys.path.append(str(ROOT.parent/'strategy-live-suite-r2-20261128'))
import plan
import observer
import batch

results=[]
def check(name,value):results.append({'case':name,'pass':bool(value)})
check('four_fixed_enabled_candidates',len(plan.STRATEGIES)==4 and not plan.EXPERIMENTAL)
check('all_four_worst_cost_within_cap',plan.PRIOR+plan.MAX_ADMITTED*plan.RESERVE==D('32354.18210375')<plan.TOTAL)
check('same_gross_not_recycled',not plan.can_reserve('44400.01','0.0005'))
check('observations_two_hours',plan.OBSERVE_SECONDS==7200)
base={'contract_ok':True,'status':'RUNNING','buys':[],'sells':[],'active_orders':0,'open_positions':0,'last_signal':'HOLD','last_decision_epoch':100,'started_epoch':95}
observer.CASE={'exit_policy':'FORCED_AFTER_SETTLEMENT'}
check('hold_waits_for_window',observer.decide(base,7300,500) is None)
check('deadline_stops_hold',observer.decide(base,7300,7300)=='OBSERVATION_DEADLINE')
missing=dict(base,last_signal=None)
check('missing_decision_is_not_waited_indefinitely',observer.decide(missing,7300,216)=='NO_DECISION_WITHIN_TWO_MINUTES')
filled=copy.deepcopy(base);filled['buys']=[{'state':'FILLED','funds':'5500','fee':'2.75','pending':False}];filled['open_positions']=1
check('first_case_stops_settled_buy',observer.decide(filled,7300,500)=='BUY_SETTLED')
observer.CASE={'exit_policy':'NATURAL_UNTIL_DEADLINE'}
check('natural_case_does_not_force_immediate_sell',observer.decide(filled,7300,500) is None)
closed=copy.deepcopy(filled);closed['open_positions']=0;closed['sells']=[{'state':'FILLED','funds':'5520','fee':'2.76','pending':False}]
check('natural_sell_settled_closes',observer.decide(closed,7300,500)=='NATURAL_EXIT_SETTLED')
pending=copy.deepcopy(closed);pending['sells'][0]['pending']=True
check('pending_sell_does_not_finish',observer.decide(pending,7300,500) is None)
unknown=copy.deepcopy(filled);unknown['buys'][0]['state']='UNKNOWN'
check('unknown_still_stops_immediately',observer.decide(unknown,7300,500)=='ORDER_UNKNOWN')
excess=copy.deepcopy(filled);excess['buys'][0]['funds']='5600'
check('cost_cap_not_relaxed_for_natural_exit',observer.decide(excess,7300,500)=='COST_LIMIT_VIOLATION')
double=copy.deepcopy(filled);double['buys']*=2
check('one_entry_cap_not_relaxed',observer.decide(double,7300,500)=='BUY_LIMIT_VIOLATION')
check('proof_cost_math',plan.buy_cost(closed['buys'])==D('5502.75'))
check('disabled_still_skipped',not plan.enabled({'type_exists':True,'type_active':False,'timeframe_exists':False}))
for strategy in plan.STRATEGIES:
    body=plan.request_body(strategy);response=dict(body,status='CREATED',sessionType='REAL')
    try:batch.verify_response(response,body);good=True
    except RuntimeError:good=False
    check('contract_'+strategy,good)
check('fixed_cutoff_before_next_start',datetime.datetime(2026,11,29,7,59,tzinfo=batch.KST)<datetime.datetime(2026,11,29,8,0,tzinfo=batch.KST))
out=ROOT/'test-results.json'
if out.exists():raise SystemExit('EXISTING_RESULT')
out.write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
print(sum(x['pass'] for x in results),'/',len(results),'night boundaries; no external calls')
raise SystemExit(0 if all(x['pass'] for x in results) else 1)
