"""Explicit external execution test. Never writes app DB or fabricates strategy trades.

One market BUY (consideration+fee<=KRW10000), one market SELL of that fill only.
Secrets remain in server memory, raw broker replies remain in private server directory.
"""
import base64
import datetime
import decimal
import hashlib
import hmac
import importlib.util
import json
import os
import pathlib
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT=pathlib.Path(__file__).resolve().parent
APP=pathlib.Path.home()/"crypto_trader/AutoTrader"
D=decimal.Decimal
BUY_ID="codex-smoke-20261128-immediate-buy"
SELL_ID="codex-smoke-20261128-immediate-sell"
AMOUNT=D('9950')


def number(value):
    if isinstance(value,bool) or value is None: raise ValueError('INVALID_NUMBER')
    n=D(str(value))
    if not n.is_finite() or n<0: raise ValueError('INVALID_NUMBER')
    return n


def jwt_token(access,secret,params,nonce):
    payload={'access_key':access,'nonce':nonce}
    query=urllib.parse.unquote(urllib.parse.urlencode(params,doseq=True))
    if query:
        payload.update(query_hash=hashlib.sha512(query.encode()).hexdigest(),query_hash_alg='SHA512')
    enc=lambda v:base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).rstrip(b'=')
    message=enc({'alg':'HS512','typ':'JWT'})+b'.'+enc(payload)
    signature=base64.urlsafe_b64encode(hmac.new(secret.encode(),message,hashlib.sha512).digest()).rstrip(b'=')
    return (message+b'.'+signature).decode()


def record(name,value):
    path=ROOT/name
    tmp=path.with_suffix(path.suffix+'.uncommitted')
    with tmp.open('x',encoding='utf-8') as f:
        json.dump(value,f,default=str,ensure_ascii=True);f.write('\n');f.flush();os.fsync(f.fileno())
    os.replace(tmp,path)


def shell(args,data=None):
    p=subprocess.run(args,cwd=APP,input=data,capture_output=True)
    if p.returncode or p.stderr: raise RuntimeError('COMMAND_FAILED_OR_STDERR')
    return p.stdout


def effective_keys(env):
    selected=[]
    for key in ('UPBIT_ACCESS_KEY','UPBIT_SECRET_KEY'):
        values=[line.split('=',1)[1] for line in env if line.startswith(key+'=')]
        if len(values)!=1 or not values[0]:raise RuntimeError('BROKER_AUTH_NOT_CONFIGURED')
        selected.append(values[0])
    return tuple(selected)


class Broker:
    def __init__(self):
        raw=shell(['docker','inspect','--format','{{json .Config.Env}}','autotrader-backend-1'])
        self.access,self.secret=effective_keys(json.loads(raw))
        self.response_prefix=uuid.uuid4().hex
        self.counter=0

    def request(self,method,path,params):
        allowed_get=path in ('/v1/orders/chance','/v1/order')
        if method=='GET' and not allowed_get:raise ValueError('PATH_REJECTED')
        if method=='POST' and path!='/v1/orders':raise ValueError('PATH_REJECTED')
        if method not in ('GET','POST'):raise ValueError('METHOD_REJECTED')
        if path=='/v1/orders/chance' and params!={'market':'KRW-BTC'}:raise ValueError('MARKET_REJECTED')
        if path=='/v1/order' and params.get('identifier') not in (BUY_ID,SELL_ID):raise ValueError('IDENTIFIER_REJECTED')
        if method=='POST':
            expected_buy={'market':'KRW-BTC','side':'bid','price':'9950','ord_type':'price','identifier':BUY_ID}
            valid_sell=(params.get('market')=='KRW-BTC' and params.get('side')=='ask' and params.get('ord_type')=='market' and params.get('identifier')==SELL_ID and set(params)=={'market','side','volume','ord_type','identifier'} and number(params['volume'])>0)
            if params!=expected_buy and not valid_sell:raise ValueError('ORDER_BODY_REJECTED')
        token=jwt_token(self.access,self.secret,params,str(uuid.uuid4()))
        url='https://api.upbit.com'+path
        if method=='GET':url+='?'+urllib.parse.urlencode(params)
        body=json.dumps(params,separators=(',',':')).encode() if method=='POST' else None
        req=urllib.request.Request(url,data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method=method)
        self.counter+=1
        try:
            with urllib.request.urlopen(req,timeout=12) as response:raw=response.read()
        except urllib.error.HTTPError as exc:
            # Store only a category; never print broker error bodies or credentials.
            raise RuntimeError('BROKER_HTTP_'+str(exc.code)) from None
        data=json.loads(raw,parse_float=D)
        if not isinstance(data,dict) or 'error' in data:raise RuntimeError('BROKER_RESPONSE_REJECTED')
        record('broker-response-'+self.response_prefix+'-'+str(self.counter)+'.json',data)
        return data


def order_totals(order,side):
    ident=BUY_ID if side=='bid' else SELL_ID
    if order.get('market')!='KRW-BTC' or order.get('side')!=side or order.get('identifier')!=ident or not order.get('uuid'):
        raise RuntimeError('ORDER_IDENTITY_MISMATCH')
    if order.get('state') not in ('done','cancel'):raise RuntimeError('ORDER_NOT_TERMINAL')
    trades=order.get('trades')
    if not isinstance(trades,list) or order.get('trades_count')!=len(trades):raise RuntimeError('TRADE_SOURCE_INCOMPLETE')
    volume=sum((number(t['volume']) for t in trades),D(0))
    funds=sum((number(t['funds']) for t in trades),D(0))
    if volume!=number(order['executed_volume']):raise RuntimeError('VOLUME_SOURCE_MISMATCH')
    return {'volume':volume,'funds':funds,'fee':number(order['paid_fee']),'state':order['state'],'uuid':order['uuid'],'identifier':ident}


def wait_order(broker,ident):
    end=time.monotonic()+75
    while True:
        try:
            order=broker.request('GET','/v1/order',{'identifier':ident})
            if order.get('state') in ('done','cancel'):return order
        except RuntimeError as exc:
            if str(exc)!='BROKER_HTTP_404':raise
        if time.monotonic()>=end:raise RuntimeError('ORDER_COMPLETION_UNCONFIRMED')
        time.sleep(2)


def post_once(broker,side,params):
    # A durable intent is written before the only POST. Never retransmit on failure.
    with (ROOT/('sent-'+side)).open('x') as f:
        json.dump({'identifier':params['identifier'],'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'params':params},f)
        f.flush();os.fsync(f.fileno())
    try:
        broker.request('POST','/v1/orders',params)
    except Exception as exc:
        record(side+'-post-unconfirmed.json',{'category':type(exc).__name__,'reason':str(exc) if str(exc).startswith('BROKER_HTTP_') else 'DETAIL_WITHHELD','retransmitted':False})
    return wait_order(broker,params['identifier'])


def sell_fill(broker,buy):
    chance=broker.request('GET','/v1/orders/chance',{'market':'KRW-BTC'})
    if chance['ask_account']['currency']!='BTC' or number(chance['ask_account']['locked'])!=0:raise RuntimeError('BTC_ACCOUNT_CHANGED')
    qty=number(chance['ask_account']['balance'])
    if qty!=buy['volume'] or qty<=0:raise RuntimeError('BTC_HOLDING_NOT_EXACT_TRIAL_FILL')
    params={'market':'KRW-BTC','side':'ask','volume':format(qty,'f'),'ord_type':'market','identifier':SELL_ID}
    return order_totals(post_once(broker,'sell',params),'ask')


def main():
    os.umask(0o077)
    if datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date().isoformat()!='2026-11-28':raise RuntimeError('DATE_MISMATCH')
    manifest=json.loads((ROOT/'manifest.json').read_text())
    for name,h in manifest.items():
        if pathlib.Path(name).name!=name or hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=h:raise RuntimeError('SOURCE_HASH_MISMATCH')
    with (ROOT/'run-once').open('x') as f:f.write(str(os.getpid()))
    spec=importlib.util.spec_from_file_location('preflight',ROOT/'preflight.py');pre=importlib.util.module_from_spec(spec);spec.loader.exec_module(pre)
    v=importlib.util.spec_from_file_location('driver',ROOT/'driver.py');driver=importlib.util.module_from_spec(v);v.loader.exec_module(driver)
    p=subprocess.run([os.sys.executable,'-I',str(ROOT/'preflight.py')],capture_output=True)
    if p.returncode or p.stderr:raise RuntimeError('PREFLIGHT_FAILED')
    before=json.loads(p.stdout)
    if not driver.validate_preflight(before):raise RuntimeError('PREFLIGHT_REJECTED')
    record('preflight.json',before)
    for folder,sid in [('live-smoke-20261128-exp1',204),('live-smoke-20261128-exp3',205)]:
        old=json.loads((ROOT.parent/folder/'observer-output/final.json').read_text())
        if old.get('status')!='COMPLETE' or old.get('sid')!=sid or old.get('gate')!='PASS' or old['state']['buys']:raise RuntimeError('PRIOR_TRIAL_BUDGET_UNCONFIRMED')
    schedule=ROOT.parent/'live-smoke-20261129-dailyclose'
    state=json.loads((schedule/'scheduler-final.json').read_text())
    if state.get('status')!='CANCELLED' or state.get('trial_started') is not False:raise RuntimeError('SCHEDULE_NOT_CANCELLED')
    broker=Broker()
    chance=broker.request('GET','/v1/orders/chance',{'market':'KRW-BTC'})
    if chance['market']['id']!='KRW-BTC' or chance['market']['state']!='active' or chance['bid_account']['currency']!='KRW' or chance['ask_account']['currency']!='BTC':raise RuntimeError('MARKET_MISMATCH')
    if AMOUNT*(1+number(chance['bid_fee']))>D('10000') or number(chance['market']['bid']['min_total'])>AMOUNT or number(chance['market']['ask']['min_total'])>AMOUNT:raise RuntimeError('COST_OR_MINIMUM_LIMIT')
    if number(chance['bid_account']['balance'])<D('30200') or number(chance['bid_account']['locked'])!=0 or number(chance['ask_account']['balance'])!=0 or number(chance['ask_account']['locked'])!=0:raise RuntimeError('ACCOUNT_PRECONDITION_FAILED')
    baseline=number(chance['bid_account']['balance'])
    record('phase.json',{'phase':'BUY_READY','at':datetime.datetime.now(datetime.timezone.utc).isoformat()})
    buy=order_totals(post_once(broker,'buy',{'market':'KRW-BTC','side':'bid','price':'9950','ord_type':'price','identifier':BUY_ID}),'bid')
    record('buy-totals.json',buy)
    if buy['volume']<=0:raise RuntimeError('BUY_NO_FILL')
    sell=sell_fill(broker,buy)
    record('sell-totals.json',sell)
    after=broker.request('GET','/v1/orders/chance',{'market':'KRW-BTC'})
    zero=number(after['ask_account']['balance'])==0 and number(after['ask_account']['locked'])==0
    pnl=sell['funds']-sell['fee']-buy['funds']-buy['fee']
    cash_delta=number(after['bid_account']['balance'])-baseline
    good=zero and number(after['bid_account']['locked'])==0 and buy['volume']==sell['volume'] and buy['funds']+buy['fee']<=D('10000') and cash_delta==pnl
    record('final.json',{'status':'COMPLETE' if good else 'UNCONFIRMED','scope':'external_broker_execution_only_not_app_ledger','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'buy':buy,'sell':sell,'buy_total_cost':buy['funds']+buy['fee'],'net_pnl':pnl,'account_cash_delta':cash_delta,'account_delta_matches_order_sources':cash_delta==pnl,'btc_zero':zero,'krw_locked_zero':number(after['bid_account']['locked'])==0,'app_db_writes':0,'buy_post_count':1,'sell_post_count':1,'tomorrow_trial_cancelled':True})
    if not good:raise RuntimeError('FINAL_RECONCILIATION_UNCONFIRMED')


if __name__=='__main__':
    try:
        main()
    except Exception as exc:
        record('error.json',{'status':'UNCONFIRMED','category':type(exc).__name__,'reason':str(exc) if str(exc).isupper() else 'DETAIL_WITHHELD'})
        # Once a BUY might exist, reconcile its identifier and close only that exact fill.
        # An already sent SELL is never retransmitted.
        if (ROOT/'sent-buy').exists() and not (ROOT/'sent-sell').exists():
            try:
                b=Broker();filled=order_totals(wait_order(b,BUY_ID),'bid')
                if filled['volume']>0:record('fallback-sell-totals.json',sell_fill(b,filled))
            except Exception as clean:
                record('cleanup-unconfirmed.json',{'category':type(clean).__name__,'reason':str(clean) if str(clean).isupper() else 'DETAIL_WITHHELD','retransmit_buy_or_sell':False})
        raise SystemExit(1)
