"""병행 감시 상태 파일 변환기 후보 pm-conv-4 (총괄 v1.216 §3-1 G1 SQL 판 맞춤 사본). 로컬 파일 입력 전용 — SSH·psql·DB·HTTP·Telegram 호출 없음.
pm-conv-3(parallel-monitor-check-20261121/candidate/convert_state_3.py a131c531)과의 차이는 세 가지뿐이다:
  1) SQL 판 표지 pm-state-3(followup/sql/state-3.sql)만 받는다.
  2) owner KIND_MODE_MISMATCH 를 ORDER_/POSITION_OWNER_KIND_MODE_MISMATCH 로 이름 그대로 남긴다(pm-conv-3 은 UNCLASSIFIED 로 냈다 — 둘 다 수집 불통과).
  3) source_note·sources 의 SQL 판 문자열을 실제 SQL_VERSION 으로 쓴다(pm-conv-3 은 'pm-state-1' 문자열이 남아 있었다).
aux(pm-aux-2)·형식 검사·판정식·종료값은 pm-conv-3 과 같다. 아래 설명의 aux 항목은 pm-aux-2 로 읽는다.

입력
  --sql / --sql-sha256   검토용 SQL 초안(../sql/state.sql)과 고정 해시. 다르면 변환하지 않는다.
  --tsv / --rc / --stderr  그 SQL 을 psql 로 실행한 표준 출력·종료값 파일·표준 오류 파일(별도 승인된 실행에서 보존한 것).
  --aux                  설치본 N03 결과에서 따로 보존한 P0·미분류 ERROR·66 개수/상태(aux_version 'pm-aux-1').
                         원천 파일 경로·sha256·rc 를 함께 받아 확인한다. 이 변환기는 N03 원문을 해석하지 않는다.
  --label / --synthetic  관측 표지와 합성 여부(true/false, 필수)
  --out-dir              결과 폴더(이미 있으면 멈춤)

출력
  result.json 은 항상 낸다. 변환 성공(CONVERTED)일 때만 W1 입력 f1-obs.json(f1-obs-1)과 W2 입력 parallel-in.json(parallel-in-1)을 낸다.
  실패·부분 출력·수집 불통과는 소비 가능한 관측을 내지 않는다.

검사
  - 실행: rc 파일이 정확히 0, 표준 오류가 비어 있음. 아니면 QUERY_FAILED(행이 있어도 부분 출력으로 본다).
  - 형식: META 가 첫 줄·END 가 끝 줄, 태그별 열 수가 정확해야 함(금액 열이 섞이면 열 수가 달라 거절), 필드 형식, CNT 와 실제 행 수 일치,
          U·CNT 정확히 1줄. 0바이트 출력이나 META/END 없는 출력은 '성공 빈 목록'이 아니라 OUTPUT_EMPTY/OUTPUT_TRUNCATED 이다.
          명시적 성공 빈 목록은 META·U·CNT(0…)·END 가 모두 있는 출력뿐이다.
  - 수집 불통과(제외하거나 빈 목록으로 바꾸지 않음): 소유자 없는 주문/포지션(owner≠OK), 알 수 없는 세션 상태·주문 상태·포지션 상태·side,
          LIVE session_type≠'REAL', DYNAMIC trading_mode 가 REAL/PAPER 가 아님, aux 항목 미제공(null).
    알려진 상태 집합은 W1(registry_f1.KNOWN_STATUS)·W2(parallel_report.ORDER_STATES/POS_STATUS)를 해시 확인 후 그대로 쓴다.
  - 오류 메시지에는 태그·줄 번호·열 번호·필드 이름만 쓰고 값은 쓰지 않는다(식별자·상태 표지는 결과 목록에 남긴다).
  - 시각: META db_now~END db_clock 을 db 축 구간으로 적을 뿐 계좌·거래소 시계 관계를 선언하지 않는다.

종료값: CONVERTED 0 · COLLECTION_NOT_PASSED 20 · INVALID 30 · ERROR 40
"""
import argparse, hashlib, json, os, re, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.normpath(os.path.join(HERE, '..', '..', 'weekend-local-fix-20261121', 'candidate'))
ORIG_LOADER_SHA = '3d7a1dccfa57359bccef2e71abc3132390ae10d04a8b668bb6d3e7a0a8161b0c'   # source-lock.json original_loader

TOOL, VERSION, SQL_VERSION, AUX_VERSION = 'convert_state', 'pm-conv-4', 'pm-state-3', 'pm-aux-2'
EXIT = {'CONVERTED': 0, 'COLLECTION_NOT_PASSED': 20, 'INVALID': 30, 'ERROR': 40}
COLS = {'META': 6, 'S': 9, 'O': 8, 'P': 7, 'U': 3, 'CNT': 6, 'END': 2}
NUL = '\\N'
RX_ID = re.compile(r'[1-9][0-9]{0,18}\Z')
RX_N = re.compile(r'(0|[1-9][0-9]{0,18})\Z')
RX_TOKEN = re.compile(r'[A-Za-z][A-Za-z0-9_]{0,39}\Z')
RX_COIN = re.compile(r'[A-Z]{2,10}-[A-Z0-9]{1,20}\Z')
RX_TS = re.compile(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+00:00\Z')
OWNER_OK, OWNER_BAD = 'OK', {'NO_SESSION_ID', 'UNKNOWN_KIND', 'NO_SESSION_ROW', 'KIND_MODE_MISMATCH'}
AUX_KEYS = {'aux_version', 'synthetic', 'n03', 'p0'}
P0_TOOL, P0_VERSION = 'p0_input', 'pm-p0-1'
P0_STATUS = {'P0_PRESENT', 'P0_ABSENT_IN_PROVIDED_LOG', 'P0_CHECK_INCOMPLETE'}


class Invalid(Exception):
    pass


def sha_file(p):
    with open(p, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()


def load_fixed():
    """W1/W2 원본을 고정 로더(orig.py, 해시 확인)로 불러 상태 집합을 가져온다. 판정식·집합을 복제하지 않는다."""
    p = os.path.join(FIX, 'orig.py')
    if sha_file(p) != ORIG_LOADER_SHA:
        raise RuntimeError('고정 로더 orig.py 해시 불일치')
    sys.path.insert(0, FIX)
    import orig  # noqa: E402
    w1, w2 = orig.load('registry_f1'), orig.load('parallel_report')
    return {'session_status': set(w1.KNOWN_STATUS), 'order_state': set(w2.ORDER_STATES), 'position_status': set(w2.POS_STATUS),
            'w1_obs_keys': tuple(w1.OBS_KEYS), 'w1_obs_version': w1.OBS_VERSION, 'w2_in_version': w2.IN_VERSION,
            'w1_sha256': orig.verify('registry_f1'), 'w2_sha256': orig.verify('parallel_report')}


def fld(v, rx, where, nullable=False):
    if v == NUL:
        if nullable:
            return None
        raise Invalid(f'{where}: NULL 불가')
    if not rx.match(v):
        raise Invalid(f'{where}: 형식 위반(값 미출력)')
    return v


def parse(raw):
    """psql 출력 → 행 목록. 형식 위반은 Invalid(값 미출력)."""
    if raw == b'':
        raise Invalid('OUTPUT_EMPTY: 0바이트 출력 — 성공 빈 목록이 아님')
    try:
        text = raw.decode('utf-8')
    except UnicodeDecodeError:
        raise Invalid('OUTPUT_ENCODING: UTF-8 아님')
    if not text.endswith('\n'):
        raise Invalid('OUTPUT_TRUNCATED: 마지막 줄 바꿈 없음')
    lines = text[:-1].split('\n')
    rows = []
    for n, ln in enumerate(lines, 1):
        cells = ln.split('\t')
        tag = cells[0]
        if tag not in COLS:
            raise Invalid(f'줄 {n}: 알 수 없는 태그(값 미출력)')
        if len(cells) != COLS[tag]:
            raise Invalid(f'줄 {n} {tag}: 열 수 {len(cells)}≠{COLS[tag]} — 허용 열 밖의 열(금액 등) 혼입 또는 누락, 값 미출력')
        rows.append((n, tag, cells[1:]))
    if rows[0][1] != 'META':
        raise Invalid('OUTPUT_TRUNCATED: 첫 줄이 META 아님')
    if rows[-1][1] != 'END':
        raise Invalid('OUTPUT_TRUNCATED: 끝 줄이 END 아님(부분 출력)')
    for t in ('META', 'U', 'CNT', 'END'):
        k = sum(1 for r in rows if r[1] == t)
        if k != 1:
            raise Invalid(f'{t} 줄 {k}개(정확히 1개여야 함)')
    return rows


def convert(rows, fixed):
    meta = {}
    sessions, orders, positions, fails = [], [], [], []
    u = cnt = end = None
    for n, tag, c in rows:
        w = f'줄 {n} {tag}'
        if tag == 'META':
            if c[0] != SQL_VERSION:
                raise Invalid(f'{w}: SQL 버전이 {SQL_VERSION} 아님')
            meta = {'db_now': fld(c[1], RX_TS, f'{w} db_now'), 'db_clock_start': fld(c[2], RX_TS, f'{w} db_clock'),
                    'flyway_max': int(fld(c[3], RX_N, f'{w} flyway')), 'isolation': c[4]}
            if c[4] != 'repeatable read':
                raise Invalid(f'{w}: 격리 수준이 repeatable read 아님')
        elif tag == 'S':
            kind = c[0]
            if kind not in ('LIVE', 'DYNAMIC'):
                raise Invalid(f'{w}: kind 형식(값 미출력)')
            s = {'kind': kind, 'id': int(fld(c[1], RX_ID, f'{w} id')), 'mode': fld(c[2], RX_TOKEN, f'{w} mode', True),
                 'status': fld(c[3], RX_TOKEN, f'{w} status', True), 'strategy_type': fld(c[4], RX_TOKEN, f'{w} strategy_type', True),
                 'timeframe': fld(c[5], RX_TOKEN, f'{w} timeframe', True), 'coin_pair': fld(c[6], RX_COIN, f'{w} coin_pair', True),
                 'created_at': fld(c[7], RX_TS, f'{w} created_at')}
            if kind == 'DYNAMIC' and s['coin_pair'] is not None:
                raise Invalid(f'{w}: DYNAMIC coin_pair 는 NULL 이어야 함(세션 열 아님)')
            key = {'kind': kind, 'id': s['id']}
            if s['status'] not in fixed['session_status']:
                fails.append({'code': 'SESSION_STATUS_UNKNOWN', **key, 'status': s['status']})
            if kind == 'LIVE' and s['mode'] != 'REAL':
                fails.append({'code': 'LIVE_MODE_UNEXPECTED', **key, 'mode': s['mode'],
                              'note': 'LIVE session_type 이 정확히 REAL 아님 — PAPER 로 보고 실자금 분류에서 빼지 않음'})
            if kind == 'DYNAMIC' and s['mode'] not in ('REAL', 'PAPER'):
                fails.append({'code': 'DYNAMIC_MODE_UNKNOWN', **key, 'mode': s['mode']})
            sessions.append(s)
        elif tag == 'O':
            o = {'order_id': int(fld(c[0], RX_ID, f'{w} order_id')), 'kind': fld(c[1], RX_TOKEN, f'{w} session_kind', True),
                 'session_id': None if c[2] == NUL else int(fld(c[2], RX_ID, f'{w} session_id')),
                 'side': fld(c[3], RX_TOKEN, f'{w} side', True), 'coin_pair': fld(c[4], RX_COIN, f'{w} coin_pair', True),
                 'state': fld(c[5], RX_TOKEN, f'{w} state', True), 'owner': c[6]}
            if o['owner'] != OWNER_OK:
                fails.append({'code': 'ORDER_OWNER_' + (o['owner'] if o['owner'] in OWNER_BAD else 'UNCLASSIFIED'),
                              'order_id': o['order_id'], 'session_kind': o['kind'], 'session_id': o['session_id'], 'state': o['state']})
            else:
                if o['state'] not in fixed['order_state']:
                    fails.append({'code': 'ORDER_STATE_UNKNOWN', 'order_id': o['order_id'], 'state': o['state']})
                if o['side'] not in ('BUY', 'SELL'):
                    fails.append({'code': 'ORDER_SIDE_UNKNOWN', 'order_id': o['order_id'], 'side': o['side']})
                if o['coin_pair'] is None:
                    fails.append({'code': 'ORDER_COIN_NULL', 'order_id': o['order_id']})
            orders.append(o)
        elif tag == 'P':
            p = {'position_id': int(fld(c[0], RX_ID, f'{w} position_id')), 'kind': fld(c[1], RX_TOKEN, f'{w} session_kind', True),
                 'session_id': None if c[2] == NUL else int(fld(c[2], RX_ID, f'{w} session_id')),
                 'coin_pair': fld(c[3], RX_COIN, f'{w} coin_pair', True), 'status': fld(c[4], RX_TOKEN, f'{w} status', True),
                 'owner': c[5]}
            if p['owner'] != OWNER_OK:
                fails.append({'code': 'POSITION_OWNER_' + (p['owner'] if p['owner'] in OWNER_BAD else 'UNCLASSIFIED'),
                              'position_id': p['position_id'], 'session_kind': p['kind'], 'session_id': p['session_id'],
                              'status': p['status']})
            else:
                if p['status'] not in fixed['position_status']:
                    fails.append({'code': 'POSITION_STATUS_UNKNOWN', 'position_id': p['position_id'], 'status': p['status']})
                if p['coin_pair'] is None:
                    fails.append({'code': 'POSITION_COIN_NULL', 'position_id': p['position_id']})
            positions.append(p)
        elif tag == 'U':
            u = {'fill_accounting_pending': int(fld(c[0], RX_N, f'{w} 0')), 'open_discrepancy': int(fld(c[1], RX_N, f'{w} 1'))}
        elif tag == 'CNT':
            cnt = [int(fld(x, RX_N, f'{w} {i}')) for i, x in enumerate(c)]
        elif tag == 'END':
            end = fld(c[0], RX_TS, f'{w} db_clock')
    got = [len(sessions), len(orders), len(positions)]
    if cnt[:3] != got:
        raise Invalid(f'ROW_COUNT_MISMATCH: CNT {cnt[:3]} ≠ 실제 행 {got}(부분 출력/중복)')
    for lst, kf, nm in ((sessions, lambda s: (s['kind'], s['id']), '세션'), (orders, lambda o: o['order_id'], '주문'),
                        (positions, lambda p: p['position_id'], '포지션')):
        keys = [kf(x) for x in lst]
        if len(keys) != len(set(keys)):
            raise Invalid(f'{nm} 식별자 중복')
    return meta, sessions, orders, positions, u, cnt, end, fails


def _src(base, src, what):
    if not isinstance(src, dict) or not isinstance(src.get('file'), str):
        raise Invalid(f'AUX: {what} source 형식')
    sp = os.path.join(base, src['file'])
    if not os.path.isfile(sp) or sha_file(sp) != str(src.get('sha256')).lower():
        raise Invalid(f'AUX_SOURCE_HASH_MISMATCH: {what} 보존 원천 파일 없음 또는 해시 불일치')
    return sp


def check_aux(path):
    """pm-aux-2 → (aux 평면화, {원천 이름: 경로}, fails). N03 원천과 P0 원천을 섞지 않는다."""
    try:
        a = json.loads(open(path, 'rb').read().decode('utf-8'))
    except (OSError, UnicodeDecodeError, ValueError):
        raise Invalid('AUX: 읽기/JSON 실패')
    if not isinstance(a, dict) or set(a) != AUX_KEYS:
        raise Invalid('AUX: 최상위 필드가 pm-aux-2 와 다름(값 미출력)')
    if a['aux_version'] != AUX_VERSION or not isinstance(a['synthetic'], bool):
        raise Invalid('AUX: aux_version/synthetic 형식')
    base = os.path.dirname(os.path.abspath(path))
    n, p = a['n03'], a['p0']
    if not isinstance(n, dict) or set(n) != {'source', 'error_logs', 'paper_522_587'}:
        raise Invalid('AUX: n03 형식(값 미출력)')
    if not isinstance(p, dict) or set(p) != {'source'}:
        raise Invalid('AUX: p0 는 {source} 만(값은 p0_input 결과 파일에서 읽는다)')
    nsp = _src(base, n['source'], 'n03')
    if set(n['source']) != {'file', 'sha256', 'rc'} or n['source']['rc'] != 0 or isinstance(n['source']['rc'], bool):
        raise Invalid('AUX_SOURCE_FAILED: n03 원천 rc 가 0 아님 또는 형식')
    psp = _src(base, p['source'], 'p0')
    if set(p['source']) != {'file', 'sha256'}:
        raise Invalid('AUX: p0 source 는 {file, sha256}')
    try:
        r = json.loads(open(psp, 'rb').read().decode('utf-8'))
    except (UnicodeDecodeError, ValueError):
        raise Invalid('AUX: p0 결과 JSON 실패')
    if not isinstance(r, dict) or r.get('tool') != P0_TOOL or r.get('version') != P0_VERSION:
        raise Invalid('AUX: p0 결과가 p0_input pm-p0-1 아님')
    if r.get('synthetic') is not a['synthetic']:
        raise Invalid('AUX: p0 결과 synthetic 표지 불일치')
    fails = []
    st = r.get('status')
    if st == 'P0_CHECK_INCOMPLETE' or st not in P0_STATUS:
        fails.append({'code': 'AUX_P0_CHECK_INCOMPLETE', 'field': 'p0', 'status': st if st in P0_STATUS else '<형식 아님>',
                      'reasons': r.get('incomplete_reasons') if isinstance(r.get('incomplete_reasons'), list) else None,
                      'note': 'P0 검사 미완료 — 빈 P0 목록·0·정상으로 바꾸지 않음'})
        p0_events = None
    else:
        p0_events = r.get('p0_events')
        if not isinstance(p0_events, list) or any(not isinstance(x, dict) or set(x) != {'code', 'label'} for x in p0_events)                 or (st == 'P0_PRESENT') != bool(p0_events) or (st == 'P0_ABSENT_IN_PROVIDED_LOG' and r.get('consumable_as_empty_p0') is not True):
            raise Invalid('AUX: p0 결과 상태와 목록이 맞지 않음(값 미출력)')
    for k in ('error_logs', 'paper_522_587'):
        if n[k] is None:
            fails.append({'code': 'AUX_NOT_PROVIDED', 'field': k, 'note': '없는 값을 0·빈 목록·정상으로 채우지 않음'})
    if n['error_logs'] is not None and (not isinstance(n['error_logs'], list)
                                        or any(not isinstance(x, dict) or set(x) != {'label', 'count'} for x in n['error_logs'])):
        raise Invalid('AUX: error_logs 형식(값 미출력)')
    pp = n['paper_522_587']
    if pp is not None and (not isinstance(pp, dict) or set(pp) != {'total', 'running', 'not_running'}):
        raise Invalid('AUX: paper_522_587 형식(값 미출력)')
    flat = {'synthetic': a['synthetic'], 'p0_events': p0_events, 'error_logs': n['error_logs'], 'paper_522_587': pp,
            'p0_status': st, 'p0_range': r.get('range'), 'p0_log_sha256': (r.get('input') or {}).get('log_sha256')}
    return flat, {'n03': nsp, 'p0': psp}, fails


def run(a):
    out = {'tool': TOOL, 'version': VERSION, 'sql_version': SQL_VERSION, 'label': a.label, 'synthetic': a.synthetic == 'true',
           'consumable': False, 'not_evidence_of': ['실제 SQL 실행', 'P7 통과', 'F3 통과', 'F1 전체 통과'], 'problems': [],
           'collection_failures': []}
    fixed = load_fixed()
    out['fixed'] = {'w1_registry_f1_sha256': fixed['w1_sha256'], 'w2_parallel_report_sha256': fixed['w2_sha256']}
    sql_sha = sha_file(a.sql)
    out['input'] = {'sql_sha256': sql_sha, 'tsv_sha256': sha_file(a.tsv) if os.path.isfile(a.tsv) else None}
    try:
        if sql_sha != a.sql_sha256.lower():
            raise Invalid('SQL_HASH_MISMATCH: 검토용 SQL 초안 해시가 고정값과 다름')
        rc_txt = open(a.rc, encoding='utf-8').read().strip()
        out['input']['rc'] = rc_txt if re.fullmatch(r'-?[0-9]{1,6}', rc_txt) else '<형식 아님>'
        err = open(a.stderr, 'rb').read()
        out['input']['stderr_bytes'] = len(err)
        if rc_txt != '0':
            raise Invalid('QUERY_FAILED: psql 종료값이 0 아님 — 행이 있어도 부분 출력으로 보고 소비하지 않음')
        if err:
            raise Invalid('QUERY_STDERR: 표준 오류가 비어 있지 않음(경고 포함) — 소비하지 않음')
        rows = parse(open(a.tsv, 'rb').read())
        meta, sessions, orders, positions, u, cnt, end, fails = convert(rows, fixed)
        aux, aux_src, aux_fails = check_aux(a.aux)
        if aux['synthetic'] != out['synthetic']:
            raise Invalid('AUX: synthetic 표지가 --synthetic 과 다름')
    except Invalid as e:
        out['problems'].append(str(e))
        out['verdict'] = 'INVALID'
        return out
    out['input'].update({'aux_sha256': sha_file(a.aux), 'aux_source_sha256': {k: sha_file(v) for k, v in aux_src.items()}})
    out['sources'] = {'state': f'{SQL_VERSION} SQL 출력(db 축 구간)', 'n03': 'N03 원천(미분류 ERROR·66)',
                      'p0': {'status': aux['p0_status'], 'range': aux['p0_range'], 'log_sha256': aux['p0_log_sha256'],
                             'note': 'backend 로그 원천 — db 구간·N03 과 같은 관측이라고 선언하지 않음'}}
    out['window'] = {'axis': 'db', 'from': meta['db_now'], 'to': end, 'flyway_max': meta['flyway_max'],
                     'note': 'db 축 구간 표시일 뿐 — 계좌·거래소 시계 관계를 선언하지 않음'}
    out['rows'] = {'sessions': cnt[0], 'orders': cnt[1], 'positions': cnt[2], 'dyn_paper_orders_not_listed': cnt[3],
                   'dyn_paper_positions_not_listed': cnt[4]}
    out['collection_failures'] = fails + aux_fails
    if out['collection_failures']:
        out['verdict'] = 'COLLECTION_NOT_PASSED'
        return out
    note = (f"{SQL_VERSION} SQL {sql_sha[:8]} 출력 {out['input']['tsv_sha256'][:8]} · db 구간 {meta['db_now']}~{end} · "
            f"aux {out['input']['aux_sha256'][:8]}" + (' · 합성 입력' if out['synthetic'] else ''))
    f1 = {'observation_version': fixed['w1_obs_version'], 'synthetic': out['synthetic'], 'source_note': note,
          'sessions': [{k: s[k] for k in fixed['w1_obs_keys']} for s in sessions]}
    w2 = {'input_version': fixed['w2_in_version'], 'synthetic': out['synthetic'], 'source_note': note, 'observed_label': a.label,
          'sessions': [{k: s[k] for k in ('kind', 'id', 'mode', 'status')} for s in sessions],
          'orders': [{k: o[k] for k in ('kind', 'session_id', 'order_id', 'side', 'coin_pair', 'state')} for o in orders],
          'positions': [{k: p[k] for k in ('kind', 'session_id', 'position_id', 'coin_pair', 'status')} for p in positions],
          'settlement_unresolved': u['fill_accounting_pending'] + u['open_discrepancy'],
          'p0_events': aux['p0_events'], 'error_logs': aux['error_logs'], 'paper_522_587': aux['paper_522_587']}
    out['verdict'], out['consumable'] = 'CONVERTED', True
    out['settlement_unresolved_parts'] = u
    return out, f1, w2


def dump(obj, p):
    with open(p, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write('\n')


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    for k in ('sql', 'sql-sha256', 'tsv', 'rc', 'stderr', 'aux', 'label', 'out-dir'):
        ap.add_argument(f'--{k}', required=True)
    ap.add_argument('--synthetic', required=True, choices=('true', 'false'))
    a = ap.parse_args(argv)
    os.makedirs(a.out_dir, exist_ok=False)
    try:
        r = run(a)
        out, f1, w2 = r if isinstance(r, tuple) else (r, None, None)
        if f1 is not None:
            dump(f1, os.path.join(a.out_dir, 'f1-obs.json'))
            dump(w2, os.path.join(a.out_dir, 'parallel-in.json'))
            out['outputs'] = {n: sha_file(os.path.join(a.out_dir, n)) for n in ('f1-obs.json', 'parallel-in.json')}
    except Exception as e:  # noqa: BLE001 — 무출력 대신 구조화된 ERROR(입력 값 미출력)
        out = {'tool': TOOL, 'version': VERSION, 'verdict': 'ERROR', 'consumable': False, 'error': type(e).__name__,
               'message': '예상 밖 예외(입력 값은 출력하지 않음)'}
    out['exit'] = EXIT[out['verdict']]
    dump(out, os.path.join(a.out_dir, 'result.json'))
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
