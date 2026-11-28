"""Serial production strategy trials, fixed gross-cost budget, no direct broker orders."""
import datetime
import hashlib
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import time
from decimal import Decimal as D
import plan
import observer as obs
import safe_api
import preflight_guard

ROOT=pathlib.Path(__file__).resolve().parent
KST=datetime.timezone(datetime.timedelta(hours=9))


def record(name,value):
    obs.record(ROOT/name,value)


def api(method,path,body=None):
    values=[x.split('=',1)[1].strip() for x in (obs.APP/'.env').read_text().splitlines() if x.startswith('API_AUTH_TOKEN=')]
    if len(values)!=1:raise RuntimeError('API_AUTH_NOT_CONFIGURED')
    return safe_api.request(method,path,json.dumps(body) if body is not None else '',values[0])['data']


def send_once(folder,name,path,body=None):
    with (folder/('sent-'+name)).open('x') as f:
        f.write(datetime.datetime.now(KST).isoformat());f.flush();os.fsync(f.fileno())
    value=api('POST',path,body)
    obs.record(folder/(name+'-response.json'),value)
    return value


def verify_response(value,body):
    expected={'strategyType':body['strategyType'],'coinPair':'KRW-BTC','timeframe':'H1','status':'CREATED','sessionType':'REAL','initialCapital':10000,'investRatio':D('0.55'),'maxInvestment':5500,'stopLossPct':5,'maxHoldHours':0}
    for key,want in expected.items():
        actual=value.get(key)
        if isinstance(want,(D,int)):
            if isinstance(actual,bool) or actual is None or D(str(actual))!=D(str(want)):raise RuntimeError('CREATE_RESPONSE_MISMATCH')
        elif actual!=want:raise RuntimeError('CREATE_RESPONSE_MISMATCH')
    params=value.get('strategyParams')
    if params!={'maxEntries':1} or type(params.get('maxEntries')) is not int:raise RuntimeError('PARAMS_RESPONSE_MISMATCH')


def run_case(index,strategy,spent,manifest):
    body=plan.request_body(strategy)
    folder=ROOT/('%02d-%s'%(index,strategy))
    folder.mkdir(mode=0o700)
    obs.record(folder/'case.json',{'strategy':strategy,'request':body,'budget_reserved':str(plan.RESERVE),'known_gross_cost_before':str(spent)})
    p=subprocess.run([sys.executable,'-I',str(ROOT/'preflight.py')],capture_output=True)
    if p.returncode or p.stderr:raise RuntimeError('PREFLIGHT_FAILED')
    data=json.loads(p.stdout)
    obs.record(folder/'preflight.json',data)
    if not preflight_guard.validate_preflight(data):raise RuntimeError('PREFLIGHT_REJECTED')
    if not plan.can_reserve(spent,data['chance']['bid_fee']) or min(D(data['chance']['bid_min_total']),D(data['chance']['ask_min_total']))<0 or max(D(data['chance']['bid_min_total']),D(data['chance']['ask_min_total']))>D('5500'):
        raise RuntimeError('BUDGET_OR_MINIMUM_REJECTED')
    record('budget.json',{'cap':str(plan.TOTAL),'spent_confirmed':str(spent),'reserved_unresolved':str(plan.RESERVE),'case':index})
    for name in ('observer.py','plan.py','safe_api.py','gate_check.py','recovery-gate-v4.sql'):
        source=(ROOT/name).read_bytes()
        if hashlib.sha256(source).hexdigest()!=manifest[name]:raise RuntimeError('SOURCE_HASH_MISMATCH')
        with (folder/name).open('xb') as f:f.write(source)
    session=send_once(folder,'create','/api/v1/trading/sessions',body)
    sid=session.get('id')
    if type(sid) is not int or sid<=205:raise RuntimeError('NEW_TARGET_ID_REJECTED')
    record('phase.json',{'phase':'VERIFY','case':index,'strategy':strategy,'sid':sid})
    verify_response(session,body)
    obs.CASE={'strategy':strategy}
    initial=obs.snapshot(sid)
    if initial.get('contract_ok') is not True or initial['status']!='CREATED' or initial['buys'] or initial['sells']:raise RuntimeError('DB_CONTRACT_MISMATCH')
    deadline=time.time()+600
    cfg={'sid':sid,'case':{'strategy':strategy},'operator_ready':True,'external_freeze':True,'excluded_existing_currency':'APENFT','expected_sha':plan.PRODUCT,'expected_image':plan.IMAGE,'deadline_epoch':deadline,'output':str(folder/'observer-output')}
    obs.record(folder/'observer-config.json',cfg)
    with (folder/'observer.log').open('xb') as log:
        child=subprocess.Popen([sys.executable,'-u',str(folder/'observer.py'),str(folder/'observer-config.json')],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    until=time.monotonic()+20
    armed=folder/'observer-output/armed.json'
    while not armed.is_file():
        if child.poll() is not None or time.monotonic()>=until:raise RuntimeError('OBSERVER_NOT_ARMED_NO_START')
        time.sleep(0.2)
    if json.loads(armed.read_text())['sid']!=sid or child.poll() is not None:raise RuntimeError('ARMED_TARGET_MISMATCH')
    try:
        started=send_once(folder,'start',f'/api/v1/trading/sessions/{sid}/start')
        if started.get('id')!=sid or started.get('status')!='RUNNING':raise RuntimeError('START_NOT_CONFIRMED')
    except Exception:
        (folder/'cancel-start.flag').open('x').close()
        raise
    record('phase.json',{'phase':'OBSERVING','case':index,'strategy':strategy,'sid':sid,'observer_pid':child.pid,'deadline_epoch':deadline})
    rc=child.wait(timeout=max(1,deadline-time.time())+420)
    if rc or not (folder/'observer-output/final.json').is_file():raise RuntimeError('CASE_UNCONFIRMED_STOP_BATCH')
    final=json.loads((folder/'observer-output/final.json').read_text())
    if final.get('status')!='COMPLETE' or final.get('gate')!='PASS' or not final.get('cash_order_match'):raise RuntimeError('FINAL_NOT_CONFIRMED')
    cost=D(str(final['buy_total_cost']))
    if not cost.is_finite() or cost<0 or cost>plan.RESERVE or spent+cost>plan.TOTAL:raise RuntimeError('CUMULATIVE_BUDGET_BREACH')
    return {'index':index,'strategy':strategy,'sid':sid,'reason':final['reason'],'buy_count':len(final['state']['buys']),'sell_count':len(final['state']['sells']),'buy_total_cost':str(cost),'net_pnl':str(final['order_net_pnl']),'gate':final['gate'],'cash_order_match':True,'decisions':final['state']['decisions']}


def main():
    os.umask(0o077)
    if datetime.datetime.now(KST).date().isoformat()!='2026-11-28':raise RuntimeError('DATE_MISMATCH')
    manifest=json.loads((ROOT/'manifest.json').read_text())
    for n,h in manifest.items():
        if pathlib.Path(n).name!=n or hashlib.sha256((ROOT/n).read_bytes()).hexdigest()!=h:raise RuntimeError('SOURCE_HASH_MISMATCH')
    with (ROOT/'run-once').open('x') as f:f.write(str(os.getpid()))
    prior_path=ROOT.parent/'broker-smoke-now2-20261128/final.json'
    prior=json.loads(prior_path.read_text())
    if prior.get('status')!='COMPLETE' or not prior.get('btc_zero') or D(str(prior['buy_total_cost']))!=plan.PRIOR:raise RuntimeError('PRIOR_COST_UNCONFIRMED')
    spent=plan.PRIOR
    results=[]
    for index,strategy in enumerate(plan.STRATEGIES,1):
        if (ROOT/'CANCEL').exists():raise RuntimeError('OPERATOR_CANCELLED')
        if time.time()-(ROOT/'run-once').stat().st_mtime>7200:raise RuntimeError('SUITE_TIME_LIMIT')
        result=run_case(index,strategy,spent,manifest)
        spent+=D(result['buy_total_cost']);results.append(result)
        record('results.json',results)
        record('budget.json',{'cap':str(plan.TOTAL),'spent_confirmed':str(spent),'reserved_unresolved':'0','completed_cases':len(results)})
    record('final.json',{'status':'COMPLETE','cases':results,'prior_external_buy_cost':str(plan.PRIOR),'total_gross_buy_cost_including_prior':str(spent),'cap':str(plan.TOTAL),'at':datetime.datetime.now(KST).isoformat(),'strategy_profitability_proven':False})


if __name__=='__main__':
    try:main()
    except Exception as exc:
        record('error.json',{'status':'UNCONFIRMED_STOPPED_BATCH','category':type(exc).__name__,'reason':str(exc) if str(exc).isupper() else 'DETAIL_WITHHELD'})
        raise SystemExit(1)
