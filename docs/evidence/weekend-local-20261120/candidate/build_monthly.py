"""W4 월 성적표 실행 연결기(총괄 v1.212 §2 W4). 로컬 파일 입력 전용 — SSH·psql·DB·HTTP·가격 수집 없음.

고정 원천을 해시로 확인해 그대로 부른다: 변환기 v4 convert.convert()(f63a857f) → 성적표 v5 scorecard.build()/render_md()(b95acebd).
기존 도구를 복사·수정하지 않는다. 등록표 v3 검증은 P7 v8 load_registry()(156c3828).

입력: --manifest M (version 'monthly-manifest-1', 파일 경로는 매니페스트 기준 상대)
  A) 개별 원천 묶기
     period{from,to,axis}·deadline_M·month_bounds   기간 계약(변환기 그대로)
     registry{file}|null       등록표 v3 — capital_events 를 registry_events 로 쓴다. null/무효면 순입금 미확정(0 으로 채우지 않음)
     clock{file}|null          {same_source:[{axes,basis}]} 시계 관계 선언. null 이면 관계 없음 — 다른 축 비교는 미확정
     benchmarks{file}|null     {btc_price_start, btc_price_end, source} 명시 가격만. 없으면 미확인
     sessions[]               {kind,id, meta{file}, boundary_start{file}|null, boundary_end{file}|null, orders{file}|null}
                              meta = {kind,id,mode,strategy_type,timeframe,created_at,initial_capital?, status_end?, provenance?}
                              boundary_* = 포착 {captured_at, axis, snapshot_id, data, gap_evidence}. 조립기 DB 부분 묶음(현재 행)은
                              경계 포착으로 받지 않는다(그 값만 미확정). orders = {orders:[...]} 주문 원천(포착 포함)
     account{file}|null       계좌 포착 {start,end}(변환기 계약)
  B) 준비된 원천: prepared_source{file} — 이미 scorecard-source-3 인 JSON. 아직 없는 열은 추정하지 않는다.
출력(--out 새 폴더, 이미 있으면 멈춤): source.json, scorecard-input.json, conversion-report.json, scorecard.json, scorecard.md,
  source_map.json(값별 파일·sha256·필드·시각 축·확정/미확정 사유), result.json
세션 하나가 변환 계약을 어기면 그 세션만 '변환 실패'로 남기고 나머지 세션은 계속한다.
종료값: 0 핵심 금액 칸(CORE_FIELDS·묶음·계좌) 전부 확정 — 일별 곡선·비교 가격 등 원천 없는 열은 not_provided_columns 로 따로 표시
  · 10 산출했지만 미확정 칸 있음 · 20 일부 세션 변환 실패(나머지 산출) · 30 매니페스트/기간 계약 무효 · 40 오류
"""
import argparse, copy, hashlib, json, os, sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pins  # noqa: E402

TOOL, VERSION, MANIFEST = 'build_monthly', 'w4-2', 'monthly-manifest-1'
EXIT = {'BUILT_CORE_CONFIRMED': 0, 'BUILT_WITH_UNCONFIRMED': 10, 'PARTIAL': 20, 'INVALID': 30, 'ERROR': 40}
CORE_FIELDS = ('month_pnl', 'realized_in_period', 'fees_paid', 'buy_funds', 'round_trips_closed', 'wins', 'losses')
UNK = '미확정'
NOT_PROVIDED = {'daily_equity': '일별 곡선 원천 미제공 — 낙폭 미확정(조립기 현재 행으로 만들지 않음)',
                'market_regime': '시장 국면 원천 미제공'}


class Invalid(ValueError):
    pass


class Inputs:
    """매니페스트 기준으로 파일을 읽고 sha256 을 기록한다. 실수형·키 중복은 P7 load_file 규칙으로 거절."""

    def __init__(self, base):
        self.base, self.files = base, {}

    def read(self, ref, what):
        if ref is None:
            return None, None
        if not (isinstance(ref, dict) and set(ref) == {'file'} and isinstance(ref['file'], str)):
            raise Invalid(f'{what}: {{file}} 형식')
        p = os.path.normpath(os.path.join(self.base, ref['file']))
        R = pins.load('p7_v8')
        v = R.load_file(p)
        if isinstance(v, dict) and '__load_error__' in v:
            raise Invalid(f"{what}: {ref['file']} {v['__load_error__'][0]}")
        self.files[ref['file']] = hashlib.sha256(open(p, 'rb').read()).hexdigest()
        return v, ref['file']


def assemble(man, io, smap):
    """매니페스트 → scorecard-source-3. 없는 값은 None 그대로(변환기가 미확정 처리)."""
    if 'prepared_source' in man:
        src, f = io.read(man['prepared_source'], 'prepared_source')
        smap.append({'value': '전체 원천', 'file': f, 'sha256': io.files[f], 'field': '/', 'status': '준비된 원천 그대로',
                     'note': '없는 열은 추정하지 않음'})
        return src
    for k in ('period', 'deadline_M', 'sessions'):
        if k not in man:
            raise Invalid(f'매니페스트 {k} 누락')
    src = {'version': 'scorecard-source-3', 'period': man['period'], 'deadline_M': man['deadline_M'],
           'month_bounds': man.get('month_bounds', []), 'sessions': []}
    reg, rf = io.read(man.get('registry'), 'registry')
    if reg is None:
        src['registry_events'] = None
        smap.append({'value': '자금 이벤트', 'file': None, 'status': UNK, 'why': '등록표 미제공 — 순입금 미확정'})
    else:
        R = pins.load('p7_v8')
        res = R.Result()
        _, st = R.load_registry(reg, res)
        if st != 'valid' or reg.get('registry_version') != R.REGISTRY_V3:
            src['registry_events'] = None
            smap.append({'value': '자금 이벤트', 'file': rf, 'sha256': io.files[rf], 'status': UNK,
                         'why': f'등록표 v3 검증 실패({st}) — 이벤트를 쓰지 않음', 'codes': sorted({a['code'] for a in res.anomalies + res.holds})})
        else:
            src['registry_events'] = copy.deepcopy(reg['capital_events'])
            for i, e in enumerate(reg['capital_events']):
                smap.append({'value': f"자금 이벤트 {e['ref']}", 'file': rf, 'sha256': io.files[rf], 'field': f'/capital_events/{i}',
                             'axis': e['axis'], 'status': '입력'})
    clk, cf = io.read(man.get('clock'), 'clock')
    src['clock'] = clk if clk is not None else {'same_source': []}
    smap.append({'value': '시계 관계', 'file': cf, 'sha256': io.files.get(cf), 'field': '/same_source',
                 'status': '선언 있음' if clk and clk.get('same_source') else '관계 없음 — 다른 축 비교 미확정'})
    bm, bf = io.read(man.get('benchmarks'), 'benchmarks')
    if bm is not None:
        src['benchmarks'] = {k: bm.get(k) for k in ('btc_price_start', 'btc_price_end')}
        smap.append({'value': 'BTC 보유 비교 가격', 'file': bf, 'sha256': io.files[bf], 'field': '/btc_price_start,/btc_price_end',
                     'status': '입력', 'source': bm.get('source')})
    else:
        smap.append({'value': 'BTC 보유 비교 가격', 'file': None, 'status': '미확인', 'why': '가격 원천 미제공 — 수집하지 않음'})
    for i, s in enumerate(man['sessions']):
        meta, mf = io.read(s.get('meta'), f'sessions[{i}].meta')
        if meta is None:
            raise Invalid(f'sessions[{i}].meta 필수')
        if (meta.get('kind'), meta.get('id')) != (s.get('kind'), s.get('id')):
            raise Invalid(f'sessions[{i}]: 매니페스트 종류/ID 와 meta 파일 불일치')
        k = f"{s['kind']}#{s['id']}"
        x = {kk: meta[kk] for kk in ('kind', 'id', 'mode', 'strategy_type', 'timeframe', 'created_at', 'initial_capital',
                                     'status_end', 'provenance') if kk in meta}
        smap.append({'value': f'{k} 세션 정보', 'file': mf, 'sha256': io.files[mf], 'field': '/', 'axis': 'app(created_at)', 'status': '입력'})
        x['boundary'] = {}
        for side in ('start', 'end'):
            b, bf2 = io.read(s.get(f'boundary_{side}'), f'sessions[{i}].boundary_{side}')
            if isinstance(b, dict) and b.get('kind') == 'db-extract-partial-1':
                smap.append({'value': f'{k} 경계 {side}', 'file': bf2, 'sha256': io.files[bf2], 'status': UNK,
                             'why': '조립기 DB 부분 묶음(현재 행)은 경계 포착이 아님 — 월말/월초 값으로 쓰지 않음'})
                b = None
            elif b is not None:
                smap.append({'value': f'{k} 경계 {side}', 'file': bf2, 'sha256': io.files[bf2], 'field': '/',
                             'axis': b.get('axis'), 'captured_at': b.get('captured_at'), 'status': '변환기 판정 참조'})
            else:
                smap.append({'value': f'{k} 경계 {side}', 'file': None, 'status': UNK, 'why': '경계 포착 미제공'})
            x['boundary'][side] = b
        od, of = io.read(s.get('orders'), f'sessions[{i}].orders')
        x['orders'] = (od or {}).get('orders', []) if od is not None else []
        smap.append({'value': f'{k} 주문 원천', 'file': of, 'sha256': io.files.get(of), 'field': '/orders',
                     'status': '입력' if od is not None else '미제공(거래0 으로 취급하지 않도록 아래 주문별 표시 확인)'})
        if od is None:
            x['_orders_missing'] = True
        src['sessions'].append(x)
    acc, af = io.read(man.get('account'), 'account')
    if acc is not None:
        src['account'] = acc
        smap.append({'value': '계좌 포착', 'file': af, 'sha256': io.files[af], 'field': '/', 'axis': 'exchange', 'status': '변환기 판정 참조'})
    return src


def convert_isolated(C, src):
    """세션별로 변환해 한 세션의 계약 위반이 다른 세션을 막지 않게 한다. 기간 계약 위반은 전체 무효."""
    base = {k: v for k, v in src.items() if k not in ('sessions', 'account')}
    try:
        _, rep0 = C.convert(dict(copy.deepcopy(base), sessions=[]))
    except (C.Bad, KeyError, TypeError, ValueError) as e:
        raise Invalid(f'기간/공통 계약: {e}')
    sessions, reports, failed, missing_orders = [], [], [], []
    for x in src['sessions']:
        x = copy.deepcopy(x)
        if x.pop('_orders_missing', False):
            missing_orders.append(f"{x.get('kind')}#{x.get('id')}")
        try:
            inp, rep = C.convert(dict(copy.deepcopy(base), sessions=[x]))
        except (C.Bad, KeyError, TypeError, ValueError) as e:
            failed.append({'session': f"{x.get('kind')}#{x.get('id')}", 'error': type(e).__name__, 'message': str(e)})
            continue
        sessions += inp['sessions']
        reports += rep['sessions']
    inp = {'version': 'scorecard-input-3', 'period': {'from': src['period']['from'], 'to': src['period']['to']}, 'sessions': sessions}
    if src.get('benchmarks') is not None:
        inp['benchmarks'] = src['benchmarks']
    rep = dict(rep0, sessions=reports)
    if src.get('account') is not None:
        try:
            ai, ar = C.convert(dict(copy.deepcopy(base), sessions=[], account=src['account']))
            inp['account'] = ai.get('account')
            rep['account'] = ar.get('account')
        except (C.Bad, KeyError, TypeError, ValueError) as e:
            failed.append({'session': '계좌', 'error': type(e).__name__, 'message': str(e)})
    return inp, rep, failed, missing_orders


def map_values(smap, rep, card, failed, missing_orders, src_by_key):
    """변환 보고·성적표 카드의 상태를 값별 지도에 붙인다."""
    for sr in rep.get('sessions', []):
        k = sr['session']
        for v in sr.get('values', []):
            smap.append({'value': v.get('value'), 'session': k, 'axis': v.get('axis'), 'captured_at': v.get('captured_at'),
                         'status': v.get('status'), 'why': v.get('why'), 'from': '변환기 v4 보고'})
        if 'creation' in sr:
            smap.append({'value': f'{k} 생성 시각 기간 소속', 'session': k, 'axis': 'app', 'status': sr['creation']['status'],
                         'why': sr['creation']['why'], 'from': '변환기 v4 보고'})
        smap.append({'value': f'{k} 순입금', 'session': k, 'status': sr['flows']['status'],
                     'why': sr['flows'].get('why'), 'refs': sr['flows'].get('refs'), 'from': '변환기 v4 보고'})
        ofile = src_by_key.get(k)
        for o in sr.get('orders', []):
            smap.append({'value': f"{k} 주문 {o['order']} {o['side']}", 'session': k, 'file': ofile, 'axis': o['axis'],
                         'observed_at': o['observed_at'], 'month': o['month'], 'status': o['value'], 'why': o['why'],
                         'from': '변환기 v4 보고'})
        for c in sr.get('carry_in_buys', []):
            smap.append({'value': f"{k} 이월 매수 원가 주문 {c['order']}", 'session': k, 'status': c['status'],
                         'cost_krw': c['cost_krw'], 'from': '변환기 v4 보고'})
        for c in sr.get('corrections', []):
            smap.append({'value': f"{k} 정정 주문 {c['order']}", 'session': k, 'status': c['attribution'], 'why': c['note'],
                         'from': '변환기 v4 보고'})
    for f in failed:
        smap.append({'value': f"{f['session']} 전체", 'status': '변환 실패', 'why': f"{f['error']}: {f['message']}"})
    for k in missing_orders:
        smap.append({'value': f'{k} 주문 원천', 'status': UNK, 'why': '주문 원천 파일 미제공 — 거래0 표시는 원천 없음 때문일 수 있음'})
    for k, why in NOT_PROVIDED.items():
        smap.append({'value': k, 'status': UNK if k == 'daily_equity' else '미확인', 'why': why})
    for c in card.get('sessions', []):
        smap.append({'value': f"{c['session']} 월손익", 'session': c['session'], 'status': c['month_pnl_status'],
                     'why': c['unconfirmed_reasons'], 'from': '성적표 v5'})


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
    try:
        out['pins'] = {k: pins.verify(k)[1] for k in ('source_converter_v4', 'scorecard_v5', 'p7_v8')}
        C, S = pins.load('source_converter_v4'), pins.load('scorecard_v5')
        R = pins.load('p7_v8')
        man = R.load_file(a.manifest)
        if not isinstance(man, dict) or man.get('version') != MANIFEST or '__load_error__' in man:
            raise Invalid(f'매니페스트 version {MANIFEST!r} 아님/읽기 실패')
        out['synthetic_input'] = man.get('synthetic')
        io, smap = Inputs(os.path.dirname(os.path.abspath(a.manifest))), []
        src = assemble(man, io, smap)
        src_by_key = {}
        for s in man.get('sessions', []):
            if s.get('orders'):
                src_by_key[f"{s['kind']}#{s['id']}"] = s['orders']['file']
        inp, rep, failed, missing_orders = convert_isolated(C, src)
        try:
            card = S.build(copy.deepcopy(inp))
        except S.Bad as e:
            raise Invalid(f'성적표 입력 계약: {e}')
        map_values(smap, rep, card, failed, missing_orders, src_by_key)
        for fn, obj in (('source.json', {k: v for k, v in src.items()}), ('scorecard-input.json', inp),
                        ('conversion-report.json', rep), ('scorecard.json', card)):
            pins.dump(obj, os.path.join(a.out, fn))
        with open(os.path.join(a.out, 'scorecard.md'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(S.render_md(card))
        pins.dump({'version': 'source-map-1', 'files_sha256': io.files, 'values': smap}, os.path.join(a.out, 'source_map.json'))
        # w4-2: 월손익만이 아니라 핵심 금액 칸 전체가 확정일 때만 BUILT_CORE_CONFIRMED (run w4-run1 에서 실현·수수료 미확정을 놓친 결함 수정)
        core = {c['session']: sorted(f for f in CORE_FIELDS if c[f] is None) + (['value_unconfirmed'] if c['value_unconfirmed'] else [])
                for c in card['sessions']}
        core = {k: v for k, v in core.items() if v}
        agg_unk = [f"{a2['group']}:{a2['name']}" for a2 in card['aggregates'] if a2['status'] == UNK]
        acc_unk = 'account' in src and card['account'].get('month_pnl') is None
        out.update(sessions_built=[c['session'] for c in card['sessions']], sessions_failed=failed,
                   unconfirmed_core_fields=core, unconfirmed_aggregates=agg_unk, account_unconfirmed=acc_unk,
                   orders_source_missing=missing_orders,
                   not_provided_columns={'daily_equity': NOT_PROVIDED['daily_equity'],
                                         'benchmark_btc_hold': '가격 원천 미제공' if src.get('benchmarks') is None else '제공'},
                   verdict='PARTIAL' if failed else ('BUILT_WITH_UNCONFIRMED' if core or agg_unk or acc_unk or missing_orders
                                                     else 'BUILT_CORE_CONFIRMED'))
    except Invalid as e:
        out.update(verdict='INVALID', message=str(e))
    except pins.PinError as e:
        out.update(verdict='ERROR', error='PinError', message=str(e))
    except Exception as e:  # noqa: BLE001
        out.update(verdict='ERROR', error=type(e).__name__, message='예상 밖 예외(입력 값 미출력)')
    out['exit'] = EXIT[out['verdict']]
    pins.dump(out, os.path.join(a.out, 'result.json'))
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
