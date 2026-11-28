"""Preregistered order, natural signals, one BUY per case, cumulative gross-cost cap."""
from decimal import Decimal as D

PRODUCT='1b719645ef907cb1aa45ffa01438fcfda7566758'
IMAGE='sha256:66c4eba80eebdce70746661dd8ac95054d3b0c9c6fbf5b11a96b2f1753315a89'
TOTAL=D('50000')
PRIOR=D('9954.18210375')
RESERVE=D('5600')
STRATEGIES=('COMPOSITE_BREAKOUT','COMPOSITE_MOMENTUM_ICHIMOKU_V2','COMPOSITE_REGIME_ROUTER','SUPERTREND','RSI','BOLLINGER','ATR_BREAKOUT','COMPOSITE_MOMENTUM','COMPOSITE_MOMENTUM_ICHIMOKU','COMPOSITE_MTF_BTC','COMPOSITE_MTF_MOMENTUM')
MAX_ADMITTED=7
EXPERIMENTAL={'SUPERTREND','RSI','BOLLINGER','ATR_BREAKOUT'}
BODY={'coinPair':'KRW-BTC','timeframe':'H1','initialCapital':10000,'investRatio':0.55,'maxInvestment':5500,'stopLossPct':5,'maxHoldHours':0,'strategyParams':{'maxEntries':1}}


def request_body(strategy):
    if strategy not in STRATEGIES:raise ValueError('UNREGISTERED_STRATEGY')
    return dict(BODY,strategyType=strategy,allowExperimentalLive=strategy in EXPERIMENTAL)


def can_reserve(spent,fee):
    spent,fee=D(str(spent)),D(str(fee))
    return spent.is_finite() and fee.is_finite() and spent>=PRIOR and fee>=0 and spent+RESERVE<=TOTAL and D('5500')*(1+fee)<=RESERVE


def buy_cost(orders):
    cost=D(0)
    proof_codes={'WITHDRAWN_STOPPED','WITHDRAWN_OWNER_LOST','WITHDRAWN_DISPATCH_LOST','RISK_REJECTED','CLIENT_UNAVAILABLE','EXCHANGE_REJECTED'}
    for o in orders:
        if o['funds'] is not None and o['fee'] is not None:
            a,b=D(str(o['funds'])),D(str(o['fee']))
            if not a.is_finite() or not b.is_finite() or min(a,b)<0:raise ValueError('COST_INVALID')
            cost+=a+b
        elif o['state']=='FAILED' and o.get('failure_code') in proof_codes and o.get('volume') in (None,0,'0'):
            continue
        elif o['state']=='CANCELLED' and o.get('cancel_confirmed') and o.get('volume') is not None and D(str(o['volume']))==0:
            continue
        else:
            raise ValueError('COST_UNCONFIRMED')
    return cost


def enabled(flags):
    for prefix in ('type','timeframe'):
        if type(flags.get(prefix+'_exists')) is not bool:raise ValueError('ENABLEMENT_SHAPE')
        if flags[prefix+'_exists']:
            value=flags.get(prefix+'_active')
            if type(value) is not bool:raise ValueError('ENABLEMENT_UNKNOWN')
            if not value:return False
    return True
