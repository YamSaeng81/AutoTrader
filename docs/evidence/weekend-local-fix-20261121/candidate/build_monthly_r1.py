"""W4 보완 w4-3(총괄 v1.213 §3 R1·§4-1). 원본 build_monthly.py(w4-2, 1b7739b3)는 고치지 않고 해시 확인 뒤 그 함수를 그대로 쓴다.

바뀐 계약 — 주문 원천을 세 가지로 구분한다(원본은 셋 다 '거래 없음'으로 바꿀 수 있었다):
  · orders 참조 null(주문 원천 없음)  → 세션은 산출하되 주문에 의존하는 값(실현·수수료·매수대금·왕복·승패·거래 상태·미실현 증감·
    항등식)을 미확정으로 바꾼다. 경계 포착으로 확정된 월손익과 묶음 합은 그대로 둔다. '거래0'으로 세지 않는다. 가짜 주문을 넣지 않는다.
  · 참조 파일에 orders 키 없음 / orders 가 목록이 아님 → 그 세션만 구조화해 거절(ORDERS_KEY_MISSING / ORDERS_TYPE_INVALID).
  · 명시적 orders: [] → 거래0(확정) 그대로.
세션 거절(위 키/타입 결함 또는 변환기 계약 위반)이 있으면 그 세션이 속했을 묶음의 월손익은 확정으로 내지 않는다(확정 부분 합 보존).
고정 변환기 v4·성적표 v5 원문은 바꾸지 않는다. 성적표 v5 의 Markdown 은 원문 함수로 만든 뒤 주문 원천 없는 세션 행만 표시를 고친다.
입력·출력·종료값은 원본과 같다(0 핵심 확정 · 10 미확정 칸 · 20 일부 세션 거절/실패 · 30 무효 · 40 오류).
"""
import argparse, copy, os, sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import orig  # noqa: E402

TOOL, VERSION = 'build_monthly_r1', 'w4-3'
NO_SRC = '주문 원천 없음'
ORDER_FIELDS_NONE = ('realized_in_period', 'realized_known_part', 'fees_paid', 'fees_known_part', 'buy_funds', 'buy_funds_known_part',
                     'round_trips_closed', 'wins', 'losses', 'round_trips_closed_known_part', 'wins_known_part', 'losses_known_part',
                     'unrealized_change', 'holding_hours_median')
IDENTITY_FLAG = '실현+미실현 증감 ≠ 월손익 — 입력 원가/평가/거래 누락 확인'


def precheck_orders(man, R):
    """세션별 주문 참조를 분류한다. 읽기 실패는 원본 Inputs 가 매니페스트 무효로 처리하도록 남긴다."""
    base = os.path.dirname(os.path.abspath(man['__path__']))
    status, rejected = {}, []
    for i, s in enumerate(man.get('sessions') or []):
        k = f"{s.get('kind')}#{s.get('id')}"
        ref = s.get('orders')
        if ref is None:
            status[k] = 'missing'
            continue
        if not (isinstance(ref, dict) and isinstance(ref.get('file'), str)):
            continue   # 원본 Inputs 가 {file} 형식 위반으로 무효 처리
        v = R.load_file(os.path.normpath(os.path.join(base, ref['file'])))
        if isinstance(v, dict) and '__load_error__' in v:
            continue   # 원본 규칙(실수형·키 중복 등) 그대로
        if not isinstance(v, dict) or 'orders' not in v:
            rejected.append({'index': i, 'session': k, 'error': 'ORDERS_KEY_MISSING',
                             'message': f"{ref['file']}: 객체의 orders 키 없음 — 거래 없음으로 바꾸지 않고 세션 거절"})
        elif not isinstance(v['orders'], list):
            rejected.append({'index': i, 'session': k, 'error': 'ORDERS_TYPE_INVALID',
                             'message': f"{ref['file']}: orders 가 목록 아님({type(v['orders']).__name__}) — 세션 거절"})
        else:
            status[k] = 'empty' if not v['orders'] else 'listed'
    return status, rejected


def mark_orders_missing(card, keys):
    for c in card['sessions']:
        if c['session'] not in keys:
            continue
        for f in ORDER_FIELDS_NONE:
            c[f] = None
        c['trade_state'] = f'미확정({NO_SRC})'
        c['identity_check'] = f'미확정({NO_SRC})'
        c['fees_status'] = '미확정'
        c['fees_unconfirmed_reasons'] = [NO_SRC]
        c['buy_funds_unconfirmed_reasons'] = [NO_SRC]
        c['round_trips_unconfirmed'] = [NO_SRC]
        c['value_unconfirmed'] = list(c.get('value_unconfirmed') or []) + [f"{c['session']} {NO_SRC} — 거래 값 미확정(월손익은 경계 포착 기준)"]
        c['flags'] = [f for f in c['flags'] if f != IDENTITY_FLAG] + [f'{NO_SRC} — 실현·수수료·왕복·승패 미확정, 거래0 아님']
        c['orders_source'] = '미제공'


def groups_of(meta, S):
    """거절 세션이 성적표에 있었다면 속했을 묶음 키(성적표 v5 build 와 같은 규칙). 돈 종류를 모르면 None."""
    try:
        mc = S.money_class(meta, 'x')
    except S.Bad:
        return None
    code = ((meta.get('provenance') or {}).get('code_sha')) or S.NA
    return [('money', mc), ('kind', f"{mc}|{meta.get('kind')}"), ('mode', f"{mc}|{meta.get('mode')}"),
            ('strategy_timeframe', f"{mc}|{meta.get('strategy_type') or S.NA}/{meta.get('timeframe') or S.NA}"),
            ('code_version', f"{mc}|{code}")]


def mark_excluded(card, excluded, S):
    """거절/변환 실패 세션이 빠진 묶음을 확정 합으로 내지 않는다. 확정 부분 합은 남긴다."""
    by = {(a['group'], a['name']): a for a in card['aggregates']}
    for k, meta in excluded:
        gs = groups_of(meta, S) if meta else None
        targets = [by[g] for g in gs if g in by] if gs else list(card['aggregates'])
        if gs:
            for g in gs:
                if g not in by:
                    a = {'group': g[0], 'name': g[1], 'sessions': [], 'month_pnl': None, 'status': '미확정', 'known_part_sum': '0',
                         'unconfirmed_sessions': [], 'note': None}
                    card['aggregates'].append(a)
                    by[g] = a
                    targets.append(a)
        for a in targets:
            a['month_pnl'], a['status'] = None, '미확정'
            if k not in a['unconfirmed_sessions']:
                a['unconfirmed_sessions'].append(k)
            a['note'] = '거절/변환 실패 세션 제외 — 확정 부분 합은 전체 손익이 아님'
    real = next((a for a in card['aggregates'] if a['group'] == 'money' and a['name'] == S.REAL), None)
    if excluded and (real is None or real['month_pnl'] is None):
        card['account_minus_real_sessions'] = f'{S.NA}(계좌 또는 실자금 세션 합계 미확정·없음)'
    card['included']['excluded'] = len(excluded)


def fix_md(md, keys):
    out = []
    for line in md.split('\n'):
        if any(line.startswith(f'| {k} |') for k in keys):
            line = line.replace('미확정(알려진 0)', f'미확정({NO_SRC})')
        out.append(line)
    return '\n'.join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if os.path.exists(a.out):
        sys.stderr.write('출력 폴더가 이미 있음 — 덮어쓰지 않음\n')
        return 41
    os.makedirs(a.out)
    out = {'tool': TOOL, 'version': VERSION}
    B = None
    try:
        B = orig.load('build_monthly')
        P = B.pins
        out['orig_sha256'] = {'build_monthly': orig.verify('build_monthly'), 'pins': orig.verify('pins')}
        out['pins'] = {k: P.verify(k)[1] for k in ('source_converter_v4', 'scorecard_v5', 'p7_v8')}
        C, S, R = P.load('source_converter_v4'), P.load('scorecard_v5'), P.load('p7_v8')
        man = R.load_file(a.manifest)
        if not isinstance(man, dict) or man.get('version') != B.MANIFEST or '__load_error__' in man:
            raise B.Invalid(f'매니페스트 version {B.MANIFEST!r} 아님/읽기 실패')
        out['synthetic_input'] = man.get('synthetic')
        man['__path__'] = a.manifest
        ostat, rejected = ({}, []) if 'prepared_source' in man else precheck_orders(man, R)
        man.pop('__path__')
        io, smap = B.Inputs(os.path.dirname(os.path.abspath(a.manifest))), []
        drop = {r['index'] for r in rejected}
        rej_meta = []
        for r in rejected:
            meta, mf = io.read(man['sessions'][r['index']].get('meta'), f"sessions[{r['index']}].meta")
            rej_meta.append((r['session'], meta))
            smap.append({'value': f"{r['session']} 주문 원천", 'file': man['sessions'][r['index']]['orders']['file'], 'status': '거절',
                         'why': r['message'], 'code': r['error']})
        man2 = dict(man, sessions=[s for i, s in enumerate(man.get('sessions') or []) if i not in drop]) if rejected else man
        src = B.assemble(man2, io, smap)
        src_by_key = {f"{s['kind']}#{s['id']}": s['orders']['file'] for s in man2.get('sessions', []) if s.get('orders')}
        inp, rep, failed, missing_orders = B.convert_isolated(C, src)
        try:
            card = S.build(copy.deepcopy(inp))
        except S.Bad as e:
            raise B.Invalid(f'성적표 입력 계약: {e}')
        built = {c['session'] for c in card['sessions']}
        mark_orders_missing(card, set(missing_orders) & built)
        card['included']['zero_trade'] = sum(c['trade_state'] == '거래0' for c in card['sessions'])
        card['included']['orders_source_missing'] = len(set(missing_orders) & built)
        metas = {f"{s['kind']}#{s['id']}": s for s in src['sessions']}
        excluded = rej_meta + [(f['session'], metas.get(f['session'])) for f in failed if f['session'] != '계좌']
        mark_excluded(card, excluded, S)
        allfail = [{'session': r['session'], 'error': r['error'], 'message': r['message']} for r in rejected] + failed
        B.map_values(smap, rep, card, allfail, missing_orders, src_by_key)
        for fn, obj in (('source.json', src), ('scorecard-input.json', inp), ('conversion-report.json', rep), ('scorecard.json', card)):
            P.dump(obj, os.path.join(a.out, fn))
        with open(os.path.join(a.out, 'scorecard.md'), 'w', encoding='utf-8', newline='\n') as f:
            # 성적표 v5 render_md 는 수수료 미확정 때 알려진 부분 합을 숫자로 읽으므로, 표시용 사본에만 0 을 넣고 그 칸을 '주문 원천 없음'으로 바꾼다
            view = copy.deepcopy(card)
            for c in view['sessions']:
                if c.get('orders_source') == '미제공':
                    c['fees_known_part'] = '0'
            f.write(fix_md(S.render_md(view), set(missing_orders) & built))
        P.dump({'version': 'source-map-1', 'files_sha256': io.files, 'values': smap}, os.path.join(a.out, 'source_map.json'))
        core = {c['session']: sorted(f for f in B.CORE_FIELDS if c[f] is None) + (['value_unconfirmed'] if c['value_unconfirmed'] else [])
                for c in card['sessions']}
        core = {k: v for k, v in core.items() if v}
        agg_unk = [f"{a2['group']}:{a2['name']}" for a2 in card['aggregates'] if a2['status'] == '미확정']
        acc_unk = 'account' in src and card['account'].get('month_pnl') is None
        out.update(sessions_built=[c['session'] for c in card['sessions']], sessions_failed=allfail,
                   unconfirmed_core_fields=core, unconfirmed_aggregates=agg_unk, account_unconfirmed=acc_unk,
                   orders_source=dict(ostat, **{r['session']: 'rejected' for r in rejected}),
                   orders_source_missing=missing_orders,
                   not_provided_columns={'daily_equity': B.NOT_PROVIDED['daily_equity'],
                                         'benchmark_btc_hold': '가격 원천 미제공' if src.get('benchmarks') is None else '제공'},
                   verdict='PARTIAL' if allfail else ('BUILT_WITH_UNCONFIRMED' if core or agg_unk or acc_unk or missing_orders
                                                      else 'BUILT_CORE_CONFIRMED'))
    except orig.OrigError as e:
        out.update(verdict='ERROR', error='OrigError', message=str(e))
    except Exception as e:  # noqa: BLE001
        if B is not None and isinstance(e, B.Invalid):
            out.update(verdict='INVALID', message=str(e))
        elif B is not None and isinstance(e, B.pins.PinError):
            out.update(verdict='ERROR', error='PinError', message=str(e))
        else:
            out.update(verdict='ERROR', error=type(e).__name__, message='예상 밖 예외(입력 값 미출력)')
    out['exit'] = {'BUILT_CORE_CONFIRMED': 0, 'BUILT_WITH_UNCONFIRMED': 10, 'PARTIAL': 20, 'INVALID': 30, 'ERROR': 40}[out['verdict']]
    dump = B.pins.dump if B is not None else None
    if dump:
        dump(out, os.path.join(a.out, 'result.json'))
    else:
        import json
        with open(os.path.join(a.out, 'result.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
