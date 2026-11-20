"""성적표 원천 계약 v2 — 포착 파일(scorecard-source-2) → 성적표 v3 입력(scorecard-input-2) 변환 최소 사본.
총괄 v1.202 §2-1~3·§3-1. 파일 입력 → 파일 산출. 운영·DB·네트워크 접속 없음. 451차 asof_examples.py 원문은 보존한다.

사용: python -I convert.py --source <포착.json> --out <폴더> [--scorecard <scorecard v3 경로>]
  → <폴더>/scorecard-input.json, conversion-report.json (그리고 --scorecard 를 주면 <폴더>/scorecard/ 에 v3 산출)
종료값: 0 = 변환(미확정 칸이 있어도 0), 2 = 계약 위반(산출 없음), 그 밖 = v3 종료값을 그대로 전달.

규칙(C1~C7)
  C1 경계 B(월초 from·월말 T)의 값은 '포착'으로만 얻는다. 포착 시각이 B와 같으면 그대로, 아니면 [B, 포착] 공백 동안 값이 변하지 않았다는
     근거(no_change) 또는 정확한 복원 원천(exact_restore)이 같은 시각 축으로 공백 전체를 덮을 때만 확정한다. 그 밖은 미확정이며
     현재값·0으로 채우지 않는다. T 값은 관측 마감 M 뒤 포착이면 근거가 있어도 받지 않는다.
     → 1월1일10:00 회차는 자동으로 12월말 값이 아니다(S3).
  C2 세션 스냅숏(가용 KRW·포지션·예약)은 한 포착(snapshot_id) 안의 값만 함께 쓴다. 계좌 조회는 DB와 동시라고 가정하지 않는다(별도 포착·축).
  C3 월 거래 귀속 = 앱 관측/장부 반영 시각(BUY filled_at, SELL sell_applied_at)이 [from, T) 안. 그 밖 매도는 이 달 실현이 아니다(S1).
     이월 매수(관측 < from)는 '이월 매수 원가' 항목으로 따로 낸다. 거래소 체결 시각을 추정하지 않는다.
  C4 주문 값(누적 수량·대금·수수료)의 T 시점 값: [T, M] 포착이 settlement_final·정산 대기 아님·reported_observed_at < T 이면 확정
     (마지막 관측이 T 앞이므로 포착까지 그 값이 그대로다). 아니면 그 주문의 정산 시각을 null 로 넘겨 v3가 미확정으로 낸다.
  C5 포착 사이 값 차이는 정정 기록(이전/현재 포착 참조·알려진 차분·관측 구간)으로만 남긴다. 구간이 한 달 안이면 그 달,
     달을 걸치면 '귀속 미확정'이며 어느 달 확정 손익에도 넣지 않는다(S2). 다음 달·최신 관측 달로 보내지 않는다.
  C6 기간 중 생성 세션: started_in_period=true, 기초0, 기초 포착을 주면 거절(S4). 배정은 등록표 ALLOCATE 이벤트로만 넣는다.
     initial_capital 은 최초 배정액의 대조 원천이며 이벤트가 없을 때 대신 쓰지 않는다(없으면 순입금 미확정, S5).
  C7 등록표 자금 이벤트는 고유 ref·양수 유한·종류·통화 KRW·적용/기록 시각·세션 연결을 검사하고 중복 적용하지 않는다.
"""
import argparse, json, os, subprocess, sys
from datetime import datetime
from decimal import Decimal as D, InvalidOperation

AXES = ('app', 'db', 'exchange', 'collector')
UNK = '미확정'


class Bad(ValueError):
    pass


def ts(v, path, none=False):
    if v is None:
        if none:
            return None
        raise Bad(f'{path}: 시각 없음')
    try:
        t = datetime.fromisoformat(str(v).replace('Z', '+00:00'))
    except ValueError:
        raise Bad(f'{path}: 시각 아님')
    if t.tzinfo is None:
        raise Bad(f'{path}: 시간대 없음')
    return t


def num(v, path, pos=False):
    if isinstance(v, bool) or not isinstance(v, (str, int)):
        raise Bad(f'{path}: 문자열/정수 숫자만')
    try:
        d = D(str(v))
    except InvalidOperation:
        raise Bad(f'{path}: 숫자 아님')
    if not d.is_finite():
        raise Bad(f'{path}: 유한수 아님')
    if pos and d <= 0:
        raise Bad(f'{path}: 양수만')
    return d


def axis(v, path):
    if v not in AXES:
        raise Bad(f'{path}: 시각 축 {list(AXES)} 중 하나 필수(받은 값 {v!r})')
    return v


def month_of(t, bounds):
    """bounds: 정렬된 월 경계 목록. t 가 속한 [b_i, b_{i+1}) 의 인덱스, 범위 밖이면 None."""
    for i in range(len(bounds) - 1):
        if bounds[i] <= t < bounds[i + 1]:
            return i
    return None


# ── C1·C2 경계 값 ────────────────────────────────────────────
def boundary_value(v, B, name, path, M=None):
    """경계 B 의 값. (data | None, 보고). v = {captured_at, axis, snapshot_id, data, gap_evidence|null}."""
    if v is None:
        return None, {'value': name, 'status': UNK, 'why': f'{name} 포착 없음 — 현재값·0 으로 채우지 않음'}
    if not isinstance(v, dict):
        raise Bad(f'{path}: 객체 아님')
    at = ts(v.get('captured_at'), f'{path}.captured_at')
    ax = axis(v.get('axis'), f'{path}.axis')
    sid = v.get('snapshot_id')
    if not (isinstance(sid, str) and sid.strip()):
        raise Bad(f'{path}.snapshot_id: 한 포착 식별자 필수(같은 스냅숏 값만 함께 씀)')
    if not isinstance(v.get('data'), dict):
        raise Bad(f'{path}.data: 객체 필수')
    rep = {'value': name, 'captured_at': v['captured_at'], 'axis': ax, 'snapshot_id': sid,
           'boundary': B.isoformat(), 'gap_seconds': abs((at - B).total_seconds())}
    if M is not None and at > M:
        rep.update(status=UNK, why=f'{name} 포착이 관측 마감 M({M.isoformat()}) 뒤')
        return None, rep
    if at == B:
        rep.update(status='확정', why='포착 시각 = 경계')
        return v['data'], rep
    ev = v.get('gap_evidence')
    lo, hi = min(at, B), max(at, B)
    if ev is None:
        rep.update(status=UNK, why=f'{name} 경계와 포착 사이 {rep["gap_seconds"]:.0f}초 공백 — 불변 근거·복원 원천 없음')
        return None, rep
    if ev.get('kind') not in ('no_change', 'exact_restore'):
        raise Bad(f'{path}.gap_evidence.kind: no_change/exact_restore 만')
    if not (isinstance(ev.get('ref'), str) and ev['ref'].strip()):
        raise Bad(f'{path}.gap_evidence.ref: 근거 참조 필수')
    eax = axis(ev.get('axis'), f'{path}.gap_evidence.axis')
    ef, et = ts(ev.get('from'), f'{path}.gap_evidence.from'), ts(ev.get('to'), f'{path}.gap_evidence.to')
    rep['gap_evidence'] = {'kind': ev['kind'], 'ref': ev['ref'], 'axis': eax}
    if eax != ax:
        rep.update(status=UNK, why=f'{name} 근거 축 {eax} ≠ 포착 축 {ax} — 시계 관계 없이 공백을 덮었다고 보지 않음')
        return None, rep
    if not (ef <= lo and et >= hi):
        rep.update(status=UNK, why=f'{name} 근거 구간이 경계~포착 공백 전체를 덮지 않음')
        return None, rep
    rep.update(status='확정', why=f"{name} 공백을 {ev['kind']} 근거 {ev['ref']} 가 덮음")
    return v['data'], rep


# ── C7 등록표 자금 이벤트 ───────────────────────────────────
def check_events(evs, path):
    if evs is None:
        return None
    if not isinstance(evs, list):
        raise Bad(f'{path}: 목록 필수')
    seen, out = set(), []
    for i, e in enumerate(evs):
        p = f'{path}[{i}]'
        ref = e.get('ref')
        if not (isinstance(ref, str) and ref.strip()):
            raise Bad(f'{p}.ref: 고유 참조 필수')
        if ref in seen:
            raise Bad(f'{p}.ref {ref!r}: 중복 — 같은 이벤트를 두 번 적용하지 않음')
        seen.add(ref)
        if e.get('type') not in ('ALLOCATE', 'RECOVER', 'DEPOSIT', 'WITHDRAW'):
            raise Bad(f"{p}.type: ALLOCATE/RECOVER/DEPOSIT/WITHDRAW 만(받은 값 {e.get('type')!r})")
        if e.get('currency') != 'KRW':
            raise Bad(f'{p}.currency: KRW 만(성적표 순입금)')
        amt = num(e.get('amount'), f'{p}.amount', pos=True)
        ap = ts(e.get('applied_at'), f'{p}.applied_at')
        rc = ts(e.get('recorded_at'), f'{p}.recorded_at')
        if rc < ap:
            raise Bad(f'{p}: 기록 시각이 적용 시각보다 앞섬')
        axis(e.get('axis'), f'{p}.axis')
        s = e.get('session')
        if e['type'] in ('ALLOCATE', 'RECOVER'):
            if not (isinstance(s, list) and len(s) == 2):
                raise Bad(f'{p}.session: 배정/회수는 [kind, id] 세션 연결 필수')
        elif s is not None:
            raise Bad(f'{p}.session: 계좌 입출금에는 세션 연결을 두지 않음')
        out.append(dict(e, _amt=amt, _at=ap))
    return out


# ── 세션 ─────────────────────────────────────────────────────
def session_input(x, per, T, M, events, report):
    k = f"{x.get('kind')}#{x.get('id')}"
    created = ts(x.get('created_at'), f'{k}.created_at')
    new = per[0] <= created < T
    if created >= T:
        raise Bad(f'{k}: 기간 뒤 생성 세션은 이 달 성적표 대상 아님')
    bnd = x.get('boundary') or {}
    srep = {'session': k, 'started_in_period': new, 'values': [], 'orders': [], 'corrections': [], 'carry_in_buys': []}
    out = {kk: x.get(kk) for kk in ('kind', 'id', 'mode', 'strategy_type', 'timeframe', 'status_end', 'market_regime', 'provenance')
           if x.get(kk) is not None}
    out['created_at'] = x['created_at']
    if new:
        if bnd.get('start') is not None:
            raise Bad(f'{k}: 기간 안 새 세션에 기초 포착을 줌 — 기초는 0, 배정은 순입금(S4)')
        out['started_in_period'] = True
    else:
        data, rep = boundary_value(bnd.get('start'), per[0], f'{k} 기초', f'{k}.boundary.start')
        srep['values'].append(rep)
        if data is not None:
            out['start'] = data
    data, rep = boundary_value(bnd.get('end'), T, f'{k} 기말', f'{k}.boundary.end', M)
    srep['values'].append(rep)
    if data is not None:
        out['end'] = data
    # 순입금: 등록표 ALLOCATE/RECOVER 이벤트만(C6·C7)
    if events is None:
        out['flows'] = None
        srep['flows'] = {'status': UNK, 'why': '등록표 자금 이벤트 없음 — initial_capital 로 대신하지 않음'}
    else:
        mine = [e for e in events if e['type'] in ('ALLOCATE', 'RECOVER') and e['session'] == [x['kind'], x['id']]]
        inper = [e for e in mine if per[0] <= e['_at'] < T]
        for e in mine:
            if e['_at'] < created:
                raise Bad(f"{k}: 이벤트 {e['ref']} 적용이 세션 생성 전")
        out['flows'] = [{'kind': e['type'], 'amount_krw': str(e['_amt']), 'at': e['applied_at']} for e in sorted(inper, key=lambda e: e['_at'])]
        srep['flows'] = {'status': '확정', 'refs': [e['ref'] for e in inper]}
        if new:
            first = sorted((e for e in mine if e['type'] == 'ALLOCATE'), key=lambda e: e['_at'])
            ic = x.get('initial_capital')
            if not first:
                out['flows'] = None
                srep['flows'] = {'status': UNK, 'why': '새 세션인데 최초 배정 이벤트 없음 — initial_capital 로 만들지 않음(S5)'}
            elif ic is not None and num(ic, f'{k}.initial_capital') != first[0]['_amt']:
                raise Bad(f"{k}: 최초 배정 {first[0]['_amt']} ≠ initial_capital {ic} — 대조 실패")
    out['trades'] = trades_of(x, per, T, M, srep, k)
    report['sessions'].append(srep)
    return out


def order_value(o, T, M, k):
    """C4. (값 dict | None, 사유)."""
    caps = o.get('captures') or []
    good = []
    for j, c in enumerate(caps):
        at = ts(c.get('captured_at'), f"{k}.order{o.get('id')}.captures[{j}].captured_at")
        if T <= at <= M:
            good.append((at, c))
    if not good:
        return None, '[T, M] 주문 포착 없음 — 현재 누적 행으로 월말 값 복원 안 함'
    at, c = max(good, key=lambda z: z[0])
    ro = ts(c.get('reported_observed_at'), f"{k}.order{o.get('id')}.reported_observed_at", none=True)
    if not (c.get('settlement_final') is True and c.get('fill_accounting_pending') is False and ro is not None and ro < T):
        return None, '월 경계 걸침 — T 이후 관측·정산 미확정(부분 체결 가능)'
    return c, None


def corrections_of(o, bounds, k, srep):
    """C5. 연속 포착 사이 차이 → 정정 기록. 한 달 안 구간만 그 달, 걸치면 귀속 미확정."""
    caps = sorted(((ts(c['captured_at'], f'{k}.cap'), c) for c in o.get('captures') or []), key=lambda z: z[0])
    for (a, ca), (b, cb) in zip(caps, caps[1:]):
        diff = {f: str(num(cb[f], f) - num(ca[f], f)) for f in ('qty', 'funds', 'fee') if num(cb[f], f) != num(ca[f], f)}
        if not diff:
            continue
        ma, mb = month_of(a, bounds), month_of(b, bounds)
        srep['corrections'].append({'order': o.get('id'), 'prev_capture': ca.get('ref'), 'cur_capture': cb.get('ref'),
                                    'known_delta': diff, 'observed_window': [ca['captured_at'], cb['captured_at']],
                                    'attribution': (bounds[ma].isoformat()[:7] if ma is not None and ma == mb else '귀속 미확정'),
                                    'note': '어느 달 확정 손익에도 자동으로 넣지 않음' if not (ma is not None and ma == mb) else '한 달 안 관측 구간'})


def trades_of(x, per, T, M, srep, k):
    bounds = sorted({per[0], T} | {ts(b, 'month_bounds') for b in x.get('_month_bounds', [])})
    groups = {}
    for o in x.get('orders') or []:
        groups.setdefault(o.get('trade_ref'), []).append(o)
    trades = []
    for ref, os_ in groups.items():
        buys = [o for o in os_ if o.get('side') == 'BUY']
        sells = [o for o in os_ if o.get('side') == 'SELL']
        if len(buys) != 1:
            raise Bad(f'{k}.trade {ref}: 매수 1건 필수')
        b = buys[0]
        for o in os_:
            corrections_of(o, bounds, k, srep)

        def leg(o):
            key = 'filled_at' if o['side'] == 'BUY' else 'sell_applied_at'
            at = ts(o.get(key), f"{k}.order{o.get('id')}.{key}", none=True)
            val, why = order_value(o, T, M, k)
            if at is None:
                place = UNK
            elif at < per[0]:
                place = '이전 달'
            elif at >= T:
                place = '다음 달'
            else:
                place = '이 달'
            srep['orders'].append({'order': o.get('id'), 'side': o['side'], 'observed_at': o.get(key), 'axis': 'app',
                                   'month': place, 'value': '확정' if val else UNK, 'why': why})
            return at, val, place, why

        bat, bval, bplace, bwhy = leg(b)
        if bplace == '다음 달':
            continue
        legs = [(s,) + leg(s) for s in sells]
        if bplace == '이전 달' and all(p == '이전 달' for _, _, _, p, _ in legs) and legs:
            sold = sum(num(v['qty'], 'qty') for _, _, v, _, _ in legs if v)
            # 이전 달에 끝난 거래는 이 달 성적표에 넣지 않는다(S1)
            if bval and sold == num(bval['qty'], 'qty'):
                continue
        if bplace == '이전 달':
            srep['carry_in_buys'].append({'order': b.get('id'), 'observed_at': b.get('filled_at'),
                                          'cost_krw': None if bval is None else str(num(bval['funds'], 'f') + num(bval['fee'], 'f')),
                                          'status': '확정' if bval else UNK})
        # 확정 값이 없으면 최근 포착 값을 넘기되 정산 시각을 null 로 둬서 v3 가 월 귀속·수수료를 미확정으로 낸다
        src = bval if bval else (b['captures'][-1] if b.get('captures') else None)
        if src is None:
            raise Bad(f'{k}.trade {ref}: 매수 포착 없음')
        t = {'buy': {'qty': src['qty'], 'funds': src['funds'], 'fee': src['fee'],
                     'settled_at': b['filled_at'] if bval else None}, 'sells': []}
        for s, at, val, place, why in legs:
            if place == '다음 달':
                continue
            c = val or (s['captures'][-1] if s.get('captures') else None)
            if c is None:
                raise Bad(f'{k}.trade {ref}: 매도 포착 없음')
            t['sells'].append({'qty': c['qty'], 'funds': c['funds'], 'fee': c['fee'],
                               'settled_at': s['sell_applied_at'] if val else None,
                               'stored_realized_pnl': s.get('stored_realized_pnl')})
        trades.append(t)
    return trades


def account_input(a, per, T, M, events, report):
    if a is None:
        report['account'] = {'status': UNK, 'why': '계좌 포착 없음'}
        return None
    out, vals = {}, []
    for name, B, key, mm in (('계좌 기초', per[0], 'start', None), ('계좌 기말', T, 'end', M)):
        data, rep = boundary_value(a.get(key), B, name, f'account.{key}', mm)
        if rep.get('axis') not in (None, 'exchange'):
            raise Bad(f'account.{key}.axis: 거래소 조회는 exchange 축(DB 포착과 동시라고 가정하지 않음)')
        vals.append(rep)
        out[key] = data
    if events is None:
        out['deposits'] = None
    else:
        out['deposits'] = [{'at': e['applied_at'], 'amount_krw': str(e['_amt'] if e['type'] == 'DEPOSIT' else -e['_amt'])}
                           for e in events if e['type'] in ('DEPOSIT', 'WITHDRAW') and per[0] <= e['_at'] < T]
    report['account'] = {'values': vals, 'same_db_snapshot': False,
                         'note': '계좌 조회는 DB 포착과 별도 시각·축 — 동시라고 가정하지 않음'}
    return out


def convert(src):
    if not isinstance(src, dict) or src.get('version') != 'scorecard-source-2':
        raise Bad('version scorecard-source-2 아님')
    per = (ts(src['period']['from'], 'period.from'), ts(src['period']['to'], 'period.to'))
    T, M = per[1], ts(src.get('deadline_M'), 'deadline_M')
    if not per[0] < T <= M:
        raise Bad('from < T ≤ M 아님')
    events = check_events(src.get('registry_events'), 'registry_events')
    report = {'version': 'scorecard-source-report-2', 'T': src['period']['to'], 'M': src['deadline_M'], 'sessions': [],
              'rules': ['경계값은 포착+공백 근거로만(C1)', '앱 관측/장부 반영 시각 [from,T) 귀속(C3)',
                        '정정은 관측 구간으로만 귀속, 걸치면 미확정(C5)', '새 세션 기초0+ALLOCATE(C6)']}
    sessions = [session_input(dict(x, _month_bounds=src.get('month_bounds', [])), per, T, M, events, report) for x in src['sessions']]
    inp = {'version': 'scorecard-input-2', 'period': src['period'], 'sessions': sessions}
    if src.get('benchmarks') is not None:
        inp['benchmarks'] = src['benchmarks']
    acc = account_input(src.get('account'), per, T, M, events, report)
    if acc is not None:
        inp['account'] = acc
    return inp, report


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--source', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--scorecard')
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    try:
        src = json.loads(open(a.source, 'rb').read().decode('utf-8'), parse_float=lambda s: (_ for _ in ()).throw(Bad(f'실수형 {s}')))
        inp, rep = convert(src)
    except (Bad, KeyError, TypeError, ValueError) as e:
        print(json.dumps({'error': type(e).__name__, 'message': str(e)}, ensure_ascii=False))
        return 2
    os.makedirs(a.out, exist_ok=True)
    for fn, obj in (('scorecard-input.json', inp), ('conversion-report.json', rep)):
        with open(os.path.join(a.out, fn), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
    print(json.dumps({'ok': True, 'sessions': len(inp['sessions'])}, ensure_ascii=False))
    if a.scorecard:
        p = subprocess.run([sys.executable, '-I', a.scorecard, '--input', os.path.join(a.out, 'scorecard-input.json'),
                            '--out', os.path.join(a.out, 'scorecard')], capture_output=True, text=True, encoding='utf-8')
        print(p.stdout.strip())
        return p.returncode
    return 0


if __name__ == '__main__':
    sys.exit(main())
