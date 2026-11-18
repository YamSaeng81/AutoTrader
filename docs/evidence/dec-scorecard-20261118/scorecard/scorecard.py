"""월 성적표 최소 시제품(총괄 v1.187 §3-5·v1.189 §3-2). 파일 입력 → 파일 산출. 네트워크·DB 접속 없음.

사용: python -I scorecard.py --input <입력.json> --out <폴더>   → <폴더>/scorecard.json, scorecard.md
종료값: 0 = 산출(미확정 칸이 있어도 0), 2 = 입력 계약 위반(산출 없음).

원칙(12월 준비 §3)
  - 월손익 = 기말 평가 − 기초 평가 − 순입금. 하나라도 모르면 0 으로 채우지 않고 '미확정'과 이유를 낸다.
  - CLOSING 포지션·가격 없는 보유는 0원이 아니라 미확정이다.
  - 수수료 이중 차감 금지: DYNAMIC 매도 realized_pnl = (매도대금 − 매도수수료) − 수량×평균가이고 평균가에 매수 수수료가 들어 있다
    (0e1d2a02 DynamicTradingService finalize 매도). position_fee 는 왕복 수수료 합계라 손익에서 다시 빼지 않는다. 수수료는 따로 보여 주기만 한다.
  - 주문 원천(대금·수수료)으로 다시 계산한 실현손익과 저장 realized_pnl 이 다르면 고르지 않고 불일치로 표시한다.
  - 손실·거래0·중단 세션을 모두 포함한다. 합계는 모든 세션이 확정일 때만 내고, 아니면 확정 부분 합과 미확정 세션 목록을 따로 낸다.
  - 낙폭은 일별 표본이 있을 때만 '일별 표본 낙폭(장중 최대 낙폭 아님)'으로 계산한다. 없는 일별 자료를 복원하지 않는다.
  - 코드 SHA·규칙 지문·전략/주기/모드·시장 상황은 입력 출처를 그대로 싣고, 없으면 '미확인'으로 둔다. 구간 중 지문이 바뀌면 분리 필요로 표시한다.
  - 비교 기준: 현금(0)·같은 기초 자본의 BTC 보유. BTC 가격이 없으면 미확인.
금액은 Decimal 로 계산하고 원 단위 반올림은 표시에서만 한다.
"""
import argparse, json, os, sys
from datetime import datetime
from decimal import Decimal as D, InvalidOperation

UNK = '미확정'
NA = '미확인'
TOL = D('0.01')   # 저장 실현손익과 주문 원천 재계산의 표시 허용 차(원) — 넘으면 불일치로 표시(판정을 통과시키는 유예가 아님)


class Bad(ValueError):
    pass


def dec(v, path, allow_none=False):
    if v is None:
        if allow_none:
            return None
        raise Bad(f'{path}: 값 없음')
    if isinstance(v, bool) or not isinstance(v, (str, int)):
        raise Bad(f'{path}: 문자열/정수 숫자만(실수형 금지)')
    try:
        d = D(str(v))
    except InvalidOperation:
        raise Bad(f'{path}: 숫자 아님')
    if not d.is_finite():
        raise Bad(f'{path}: 유한수 아님')
    return d


def tstamp(v, path):
    try:
        t = datetime.fromisoformat(str(v).replace('Z', '+00:00'))
    except ValueError:
        raise Bad(f'{path}: 시각 아님')
    if t.tzinfo is None:
        raise Bad(f'{path}: 시간대 없음')
    return t


def s(x):
    return None if x is None else str(x)


def won(x):
    return UNK if x is None else f'{x.quantize(D("1")):,}'


# ── 평가 ──────────────────────────────────────────────────────
def snapshot_value(snap, path, why):
    """세션 시점 평가 = 가용 KRW + 보유 평가. 모르면 None 과 이유."""
    if snap is None:
        why.append(f'{path} 없음')
        return None
    total = dec(snap.get('available_krw'), f'{path}.available_krw')
    ok = True
    for i, p in enumerate(snap.get('positions') or []):
        st = p.get('status')
        size = dec(p.get('size'), f'{path}.positions[{i}].size')
        if st == 'CLOSING':
            why.append(f"{path} 포지션 {p.get('id')} CLOSING — 0원 아님, 매도 확정 전 미확정")
            ok = False
            continue
        if st != 'OPEN':
            raise Bad(f'{path}.positions[{i}].status: OPEN/CLOSING 만(받은 값 {st!r})')
        price = dec(p.get('price'), f'{path}.positions[{i}].price', allow_none=True)
        if price is None:
            why.append(f"{path} 포지션 {p.get('id')} {p.get('coin')} 평가 가격 없음")
            ok = False
            continue
        total += size * price
    return total if ok else None


def trade_metrics(trades, period, path):
    """주문 원천 실현손익(기간 안 매도분)·수수료·왕복·보유시간·저장값 대조."""
    realized, fees, buy_funds = D(0), D(0), D(0)
    rounds, wins, losses, holds, pnl_list, mism = 0, 0, 0, [], [], []
    for i, t in enumerate(trades):
        b = t['buy']
        bq = dec(b['qty'], f'{path}[{i}].buy.qty')
        bf = dec(b['funds'], f'{path}[{i}].buy.funds')
        bfee = dec(b['fee'], f'{path}[{i}].buy.fee')
        bat = tstamp(b['at'], f'{path}[{i}].buy.at')
        if bq <= 0:
            raise Bad(f'{path}[{i}].buy.qty ≤ 0')
        avg = (bf + bfee) / bq   # 평균가에 매수 수수료 포함(제품과 같은 정의)
        if period[0] <= bat < period[1]:
            fees += bfee
            buy_funds += bf
        sold, pos_pnl = D(0), D(0)
        last_sell = None
        for j, sl in enumerate(t.get('sells') or []):
            sat = tstamp(sl['at'], f'{path}[{i}].sells[{j}].at')
            q = dec(sl['qty'], f'{path}[{i}].sells[{j}].qty')
            f = dec(sl['funds'], f'{path}[{i}].sells[{j}].funds')
            fee = dec(sl['fee'], f'{path}[{i}].sells[{j}].fee')
            sold += q
            if not (period[0] <= sat < period[1]):
                continue
            r = (f - fee) - q * avg
            realized += r
            pos_pnl += r
            fees += fee
            last_sell = sat
            stored = dec(sl.get('stored_realized_pnl'), f'{path}[{i}].sells[{j}].stored_realized_pnl', allow_none=True)
            if stored is not None and abs(stored - r) > TOL:
                mism.append({'trade': i, 'sell': j, 'stored': s(stored), 'from_orders': s(r), 'diff': s(stored - r)})
        if sold > bq:
            raise Bad(f'{path}[{i}]: 매도 수량 합 > 매수 수량')
        if sold == bq and last_sell is not None:
            rounds += 1
            pnl_list.append(pos_pnl)
            wins += pos_pnl > 0
            losses += pos_pnl < 0
            holds.append((last_sell - bat).total_seconds() / 3600)
    holds.sort()
    med = None if not holds else (holds[len(holds) // 2] if len(holds) % 2 else (holds[len(holds) // 2 - 1] + holds[len(holds) // 2]) / 2)
    return {'realized_from_orders': realized, 'fees_paid': fees, 'buy_funds': buy_funds, 'round_trips': rounds,
            'wins': wins, 'losses': losses, 'holding_hours_median': None if med is None else round(med, 2),
            'round_trip_pnl': [s(x) for x in pnl_list], 'stored_realized_mismatch': mism}


def drawdown(daily, path):
    if not daily or len(daily) < 2:
        return {'value_krw': None, 'pct': None, 'label': f'{NA}(일별 평가 표본 없음 — 복원하지 않음)'}
    peak, worst, worst_pct = None, D(0), D(0)
    for i, d in enumerate(daily):
        e = dec(d['equity'], f'{path}[{i}].equity')
        peak = e if peak is None or e > peak else peak
        dd = e - peak
        if dd < worst:
            worst = dd
            worst_pct = (dd / peak * 100) if peak > 0 else D(0)
    return {'value_krw': s(worst), 'pct': s(worst_pct.quantize(D('0.01'))), 'samples': len(daily),
            'label': '일별 표본 낙폭(장중 최대 낙폭 아님)'}


def session_card(x, period, bench):
    k = f"{x['kind']}#{x['id']}"
    why = []
    start = snapshot_value(x.get('start'), f'{k} 기초', why) if x.get('start') is not None else None
    if x.get('start') is None:
        if x.get('started_in_period'):
            start = D(0)   # 기간 안 새 세션 — 기초 0, 배정은 순입금으로 들어온다
        else:
            why.append(f'{k} 기초 평가 없음(기간 전부터 운용)')
    end = snapshot_value(x.get('end'), f'{k} 기말', why)
    flows = D(0)
    flows_known = x.get('flows') is not None
    for i, f in enumerate(x.get('flows') or []):
        flows += dec(f['amount_krw'], f'{k}.flows[{i}].amount_krw')
    if not flows_known:
        why.append(f'{k} 순입금(배정·회수) 이력 없음')
    pnl = end - start - flows if (start is not None and end is not None and flows_known) else None
    tm = trade_metrics(x.get('trades') or [], period, f'{k}.trades')
    unreal = None if pnl is None else pnl - tm['realized_from_orders']
    prov = x.get('provenance') or {}
    hashes = prov.get('ruleset_hashes_seen') or ([prov['ruleset_hash']] if prov.get('ruleset_hash') else [])
    flags = []
    if len(set(hashes)) > 1:
        flags.append('구간 중 규칙 지문 변경 — 지문별 새 구간으로 분리해야 함(합산 금지)')
    if tm['stored_realized_mismatch']:
        flags.append('저장 실현손익 ≠ 주문 원천 재계산 — 어느 쪽도 고르지 않음')
    regimes = x.get('market_regime')
    base_cap = start if start not in (None, D(0)) else (flows if flows > 0 else None)
    btc = None
    if bench.get('btc_start') is not None and bench.get('btc_end') is not None and base_cap is not None:
        btc = base_cap * (bench['btc_end'] / bench['btc_start'] - 1)
    return {
        'session': k, 'mode': x.get('mode') or NA, 'strategy': x.get('strategy_type') or NA, 'timeframe': x.get('timeframe') or NA,
        'status_end': x.get('status_end') or NA,
        'trade_state': '거래0' if not x.get('trades') else f"왕복 {tm['round_trips']}",
        'start_equity': s(start), 'end_equity': s(end), 'net_flows': s(flows) if flows_known else None,
        'month_pnl': s(pnl), 'month_pnl_status': '확정' if pnl is not None else UNK, 'unconfirmed_reasons': why,
        'month_return_pct': None if (pnl is None or base_cap in (None, D(0))) else s((pnl / base_cap * 100).quantize(D('0.01'))),
        'realized_from_orders': s(tm['realized_from_orders']), 'unrealized_derived': s(unreal),
        'fees_paid': s(tm['fees_paid']), 'fee_note': '손익에 이미 반영(매도 수수료는 대금에서, 매수 수수료는 평균가에) — 다시 빼지 않음',
        'buy_funds': s(tm['buy_funds']), 'round_trips': tm['round_trips'], 'wins': tm['wins'], 'losses': tm['losses'],
        'holding_hours_median': tm['holding_hours_median'], 'round_trip_pnl': tm['round_trip_pnl'],
        'stored_realized_mismatch': tm['stored_realized_mismatch'],
        'drawdown': drawdown(x.get('daily_equity'), f'{k}.daily_equity'),
        'provenance': {'code_sha': prov.get('code_sha') or NA, 'code_sha_source': prov.get('code_sha_source') or NA,
                       'ruleset_hashes': hashes or [NA], 'registry_ref': prov.get('registry_ref') or NA},
        'market_regime': regimes if regimes else NA,
        'benchmark': {'cash_pnl': '0', 'btc_hold_pnl': s(btc) if btc is not None else NA},
        'flags': flags,
    }


def account_card(a):
    if a is None:
        return {'month_pnl': None, 'status': f'{NA}(계좌 기초/기말 없음)'}
    why = []

    def value(snap, name):
        if snap is None:
            why.append(f'계좌 {name} 없음')
            return None
        t = dec(snap.get('krw'), f'account.{name}.krw')
        for c, q in (snap.get('coins') or {}).items():
            p = (snap.get('prices') or {}).get(c)
            if p is None:
                why.append(f'계좌 {name} {c} 가격 없음')
                return None
            t += dec(q, f'account.{name}.coins.{c}') * dec(p, f'account.{name}.prices.{c}')
        return t
    st, en = value(a.get('start'), '기초'), value(a.get('end'), '기말')
    nd = dec(a.get('net_deposits_krw'), 'account.net_deposits_krw', allow_none=True)
    if nd is None:
        why.append('계좌 순입금 이력 없음')
    pnl = en - st - nd if None not in (st, en, nd) else None
    return {'start': s(st), 'end': s(en), 'net_deposits': s(nd), 'month_pnl': s(pnl), 'status': '확정' if pnl is not None else UNK,
            'reasons': why}


def build(inp):
    if not isinstance(inp, dict) or inp.get('version') != 'scorecard-input-1':
        raise Bad('version scorecard-input-1 아님')
    per = inp['period']
    period = (tstamp(per['from'], 'period.from'), tstamp(per['to'], 'period.to'))
    if not period[0] < period[1]:
        raise Bad('period.from ≥ period.to')
    b = inp.get('benchmarks') or {}
    bench = {'btc_start': dec(b.get('btc_price_start'), 'benchmarks.btc_price_start', True),
             'btc_end': dec(b.get('btc_price_end'), 'benchmarks.btc_price_end', True)}
    seen = set()
    cards = []
    for x in inp['sessions']:
        key = (x['kind'], x['id'])
        if key in seen:
            raise Bad(f'세션 중복 {key}')
        seen.add(key)
        cards.append(session_card(x, period, bench))
    groups = {}
    for c in cards:
        for gk in (('engine', c['session'].split('#')[0]), ('strategy', c['strategy']), ('all', '전체')):
            g = groups.setdefault(gk, {'sessions': [], 'known_sum': D(0), 'unconfirmed': []})
            g['sessions'].append(c['session'])
            if c['month_pnl'] is None:
                g['unconfirmed'].append(c['session'])
            else:
                g['known_sum'] += D(c['month_pnl'])
    agg = []
    for (gt, name), g in sorted(groups.items()):
        agg.append({'group': gt, 'name': name, 'sessions': g['sessions'],
                    'month_pnl': s(g['known_sum']) if not g['unconfirmed'] else None,
                    'status': '확정' if not g['unconfirmed'] else UNK,
                    'known_part_sum': s(g['known_sum']), 'unconfirmed_sessions': g['unconfirmed'],
                    'note': None if not g['unconfirmed'] else '확정 부분 합은 전체 손익이 아님'})
    acc = account_card(inp.get('account'))
    allg = next(a for a in agg if a['group'] == 'all')
    gap = None
    if acc.get('month_pnl') is not None and allg['month_pnl'] is not None:
        gap = s(D(acc['month_pnl']) - D(allg['month_pnl']))
    return {'version': 'scorecard-1', 'period': {'from': per['from'], 'to': per['to']},
            'sessions': cards, 'aggregates': agg, 'account': acc,
            'account_minus_sessions': gap if gap is not None else f'{NA}(계좌 또는 세션 합계 미확정)',
            'included': {'sessions': len(cards), 'zero_trade': sum(c['trade_state'] == '거래0' for c in cards),
                         'loss': sum(1 for c in cards if c['month_pnl'] is not None and D(c['month_pnl']) < 0),
                         'stopped': sum(c['status_end'] == 'STOPPED' for c in cards), 'unconfirmed': len(allg['unconfirmed_sessions'])},
            'rules': ['월손익=기말−기초−순입금', 'CLOSING·가격 없음은 미확정', '수수료는 손익에 이미 반영 — 표시만',
                      '손실·거래0·중단 포함', '일별 표본 없으면 낙폭 미확인', '없는 출처는 미확인']}


def render_md(r):
    L = [f"# 월 성적표 {r['period']['from']} ~ {r['period']['to']}", '',
         f"포함 세션 {r['included']['sessions']}(거래0 {r['included']['zero_trade']}·손실 {r['included']['loss']}·중단 {r['included']['stopped']}·미확정 {r['included']['unconfirmed']})", '',
         '| 세션 | 모드 | 전략/주기 | 거래 | 월손익 | 실현(주문 원천) | 수수료(반영됨) | 낙폭 | 코드 | 지문 | 표시 |', '|---|---|---|---|---|---|---|---|---|---|---|']
    for c in r['sessions']:
        dd = c['drawdown']
        L.append(f"| {c['session']} | {c['mode']} | {c['strategy']}/{c['timeframe']} | {c['trade_state']} | "
                 f"{won(None if c['month_pnl'] is None else D(c['month_pnl']))} | {won(D(c['realized_from_orders']))} | {won(D(c['fees_paid']))} | "
                 f"{dd['label'] if dd['value_krw'] is None else won(D(dd['value_krw'])) + ' ' + dd['label']} | {c['provenance']['code_sha']} | "
                 f"{','.join(c['provenance']['ruleset_hashes'])} | {'; '.join(c['flags'] + c['unconfirmed_reasons']) or '-'} |")
    L += ['', '| 묶음 | 이름 | 월손익 | 확정 부분 합 | 미확정 세션 |', '|---|---|---|---|---|']
    for a in r['aggregates']:
        L.append(f"| {a['group']} | {a['name']} | {won(None if a['month_pnl'] is None else D(a['month_pnl']))} | {won(D(a['known_part_sum']))} | "
                 f"{','.join(a['unconfirmed_sessions']) or '-'} |")
    acc = r['account']
    L += ['', f"계좌: 월손익 {won(None if acc.get('month_pnl') is None else D(acc['month_pnl']))} ({acc['status']}) · 계좌−세션 합 {r['account_minus_sessions']}",
          '', '규칙: ' + ' / '.join(r['rules'])]
    return '\n'.join(L) + '\n'


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    try:
        inp = json.loads(open(a.input, 'rb').read().decode('utf-8'), parse_float=lambda x: (_ for _ in ()).throw(Bad(f'실수형 {x}')))
        r = build(inp)
    except (Bad, KeyError, TypeError, ValueError) as e:
        print(json.dumps({'error': type(e).__name__, 'message': str(e)}, ensure_ascii=False))
        return 2
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, 'scorecard.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(r, f, ensure_ascii=False, indent=1)
    with open(os.path.join(a.out, 'scorecard.md'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(render_md(r))
    print(json.dumps({'ok': True, 'sessions': r['included']}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
