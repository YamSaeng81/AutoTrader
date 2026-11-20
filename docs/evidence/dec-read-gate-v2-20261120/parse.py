"""read.sql v2 원문 출력 파서(총괄 v1.204 §3-1) — 파일 입력만. 운영 출력은 없다.
사용: python -I parse.py --raw <stdout 원문> --psql-rc <정수> --registry <등록표 json> --out <db 조각.json>
종료값: 0 = 2단계까지 읽음, 10 = 게이트 STOP(금액 열람 없음, 등록 대조·등록 밖 목록만), 20 = psql rc≠0,
        30 = 형식 결함 또는 SQL 게이트와 파서 재계산 불일치.
파서는 SQL 게이트의 추가 방어다. SQL 이 GO 를 냈어도 파서 재계산이 STOP 사유를 찾으면 30 으로 거절하지만,
그때는 이미 2단계 금액이 출력된 뒤이므로 선행 차단은 SQL 게이트가 맡는다(v1.204 R1).
"""
import argparse, hashlib, json, re, sys
from datetime import datetime

COLS = {'META': 10, 'ID_R': 6, 'ID_S': 5, 'ID_O': 7, 'ID_P': 7, 'GATE_N': 2, 'GATE': 2,
        'S': 12, 'O': 24, 'P': 13, 'D': 5, 'CNT': 3, 'END': 2}
ORDER = list(COLS)
STAGE2 = ('S', 'O', 'P', 'D', 'CNT')
OWNERS = {'NO_SESSION_ID', 'NO_SESSION_ROW', 'UNKNOWN_KIND', 'UNCLASSIFIED', 'UNREG'}
REG_ST = {'REG_OK', 'REG_MISSING', 'REG_MODE'}
NUM = re.compile(r'^-?\d+(\.\d+)?$')
INT = re.compile(r'^-?\d+$')
ONAMES = ['id', 'session_kind', 'session_id', 'position_id', 'coin_pair', 'side', 'order_type', 'quantity', 'reserved_krw',
          'client_order_id', 'state', 'filled_quantity', 'exchange_order_id', 'created_at', 'filled_at', 'sell_applied_at',
          'reported_executed_funds', 'reported_paid_fee', 'applied_executed_funds', 'applied_paid_fee', 'settlement_final',
          'fill_accounting_pending', 'reported_observed_at']
PNAMES = ['id', 'session_kind', 'session_id', 'coin_pair', 'size', 'status', 'invested_krw', 'position_fee', 'ruleset_hash',
          'opened_at', 'closing_at', 'closed_at']


class Bad(ValueError):
    pass


def v(x):
    return None if x == '\\N' else x


def ts(x, p):
    if x is None:
        return None
    try:
        t = datetime.fromisoformat(x.replace(' ', 'T', 1))
    except ValueError:
        raise Bad(f'{p}: 시각 형식 {x!r}')
    if t.tzinfo is None:
        raise Bad(f'{p}: 시간대 없음')
    return x


def nm(x, p):
    if x is not None and not NUM.match(x):
        raise Bad(f'{p}: 숫자 형식 {x!r}')
    return x


def it(x, p, null=False):
    if x is None and null:
        return None
    if x is None or not INT.match(x):
        raise Bad(f'{p}: 정수 형식 {x!r}')
    return int(x)


def bl(x, p):
    if x not in ('t', 'f'):
        raise Bad(f'{p}: 불리언 형식 {x!r}')
    return x == 't'


def pg_arr(s, p):
    if s is None or not re.match(r'^\{(-?\d+(,-?\d+)*)?\}$', s):
        raise Bad(f'{p}: 배열 형식 {s!r}')
    return sorted(int(x) for x in s[1:-1].split(',') if x)


def parse(text, reg, reg_sha):
    sess = reg.get('sessions')
    if not isinstance(sess, list):
        raise Bad('등록표 sessions 없음')
    keys = set()
    for s in sess:
        if s.get('kind') not in ('LIVE', 'DYNAMIC') or not isinstance(s.get('id'), int) or isinstance(s.get('id'), bool):
            raise Bad(f'등록표 항목 형식 {s!r}')
        if (s['kind'], s['id']) in keys:
            raise Bad('등록표 중복')
        keys.add((s['kind'], s['id']))
    rows, last = {k: [] for k in COLS}, -1
    for n, line in enumerate(text.split('\n'), 1):
        if not line:
            continue
        f = [v(x) for x in line.split('\t')]
        k = f[0]
        if k not in COLS or len(f) != COLS[k]:
            raise Bad(f'{n}행: 레코드 {k!r} 열 수 {len(f)}')
        if ORDER.index(k) < last:
            raise Bad(f'{n}행: 레코드 순서')
        last = ORDER.index(k)
        rows[k].append(f[1:])
    for k in ('META', 'GATE_N', 'GATE', 'END'):
        if len(rows[k]) != 1:
            raise Bad(f'{k} 정확히 1개 아님')
    meta = rows['META'][0]
    if meta[0] != 'v2':
        raise Bad('META 판 v2 아님')
    ts(meta[1], 'META.now'), ts(meta[2], 'META.clock'), ts(rows['END'][0][0], 'END')
    if meta[5] != 'repeatable read':
        raise Bad('격리 수준 repeatable read 아님')
    if meta[6] != reg_sha:
        raise Bad('META 등록표 sha256 이 파일과 다름')
    if pg_arr(meta[7], 'META.live_ids') != sorted(i for k, i in keys if k == 'LIVE') or \
       pg_arr(meta[8], 'META.dyn_ids') != sorted(i for k, i in keys if k == 'DYNAMIC'):
        raise Bad('META id 배열이 등록표와 다름')

    # 등록 대조
    seen, reg_bad = set(), []
    for r in rows['ID_R']:
        key = (r[0], it(r[1], 'ID_R.id'))
        if key not in keys or key in seen or r[2] not in REG_ST:
            raise Bad(f'ID_R 행 {r!r}')
        seen.add(key)
        if r[2] != 'REG_OK':
            reg_bad.append({'kind': r[0], 'id': key[1], 'reg': r[2], 'mode': r[3], 'status': r[4]})
        elif r[3] != 'REAL':
            raise Bad(f'REG_OK 인데 모드 {r[3]!r}')
    if seen != keys:
        raise Bad('ID_R 이 등록표 전체를 덮지 않음')

    # 등록 밖 목록과 STOP 사유 재계산
    unreg, stop_n = [], len(reg_bad) + (1 if not keys else 0)
    for r in rows['ID_S']:
        key = (r[0], it(r[1], 'ID_S.id'))
        if key in keys:
            raise Bad('ID_S 에 등록 세션')
        flag = bl(r[3], 'ID_S.flag')
        if flag != (r[2] != 'STOPPED'):
            raise Bad('ID_S 플래그와 상태 불일치')
        stop_n += flag
        unreg.append({'rec': 'S', 'kind': r[0], 'id': key[1], 'status': r[2], 'stop': flag})
    for rec in ('ID_O', 'ID_P'):
        for r in rows[rec]:
            oid = it(r[0], f'{rec}.id')
            sid = it(r[2], f'{rec}.session_id', null=True)
            if r[4] not in OWNERS:
                raise Bad(f'{rec} 소유 분류 {r[4]!r}')
            flag = bl(r[5], f'{rec}.flag')
            if r[4] != 'UNREG' and not flag:
                raise Bad(f'{rec} 소유 이상인데 STOP 플래그 없음')
            if r[4] == 'UNREG' and (r[1], sid) in keys:
                raise Bad(f'{rec} 등록 세션인데 UNREG')
            if rec == 'ID_P' and r[4] == 'UNREG' and flag != (r[3] != 'CLOSED'):
                raise Bad('ID_P 플래그와 상태 불일치')
            stop_n += flag
            unreg.append({'rec': rec[-1], 'id': oid, 'kind': r[1], 'session_id': sid, 'state': r[3], 'owner': r[4], 'stop': flag})
    gate_n = it(rows['GATE_N'][0][0], 'GATE_N')
    gate = rows['GATE'][0][0]
    if gate_n != stop_n:
        raise Bad(f'SQL 게이트 건수 {gate_n} ≠ 파서 재계산 {stop_n}')
    if gate not in ('GO', 'STOP') or (gate == 'GO') != (gate_n == 0):
        raise Bad(f'GATE {gate} 와 건수 {gate_n} 불일치')
    base = {'registry_sha256': reg_sha, 'observed_at': meta[1], 'clock_read_at': meta[2], 'snapshot': meta[3],
            'flyway_version': it(meta[4], 'META.flyway'), 'end_clock': rows['END'][0][0],
            'registry_check': reg_bad, 'unregistered_ids': unreg, 'gate_bad': gate_n}
    if gate == 'STOP':
        if any(rows[k] for k in STAGE2):
            raise Bad('STOP 인데 2단계 행이 있음')
        return 10, dict(base, gate='STOP')

    # 2단계
    if len(rows['CNT']) != 1:
        raise Bad('CNT 정확히 1개 아님')
    out = dict(base, gate='GO', sessions=[], orders=[], positions=[], unresolved_discrepancies=[], cross_links=[])
    for r in rows['S']:
        key = (r[0], it(r[1], 'S.id'))
        if key not in keys or r[2] != 'REAL':
            raise Bad(f'2단계 세션 등록 밖 또는 모드 {r[2]!r}')
        out['sessions'].append({'kind': r[0], 'id': key[1], 'mode': r[2], 'strategy_type': r[3], 'timeframe': r[4], 'coin_pair': r[5],
                                'initial_capital': nm(r[6], 'S.initial_capital'), 'available_krw': nm(r[7], 'S.available_krw'),
                                'status': r[8], 'created_at': ts(r[9], 'S.created_at'), 'stopped_at': ts(r[10], 'S.stopped_at')})
    if {(s['kind'], s['id']) for s in out['sessions']} != keys or len(out['sessions']) != len(keys):
        raise Bad('2단계 세션이 등록표와 다름')
    for r in rows['O']:
        o = dict(zip(ONAMES, r))
        o['id'], o['session_id'] = it(o['id'], 'O.id'), it(o['session_id'], 'O.session_id')
        o['position_id'] = it(o['position_id'], 'O.position_id', null=True)
        if (o['session_kind'], o['session_id']) not in keys:
            raise Bad('2단계 주문이 등록 밖')
        for k in ('quantity', 'reserved_krw', 'filled_quantity', 'reported_executed_funds', 'reported_paid_fee',
                  'applied_executed_funds', 'applied_paid_fee'):
            nm(o[k], f'O.{k}')
        for k in ('created_at', 'filled_at', 'sell_applied_at', 'reported_observed_at'):
            ts(o[k], f'O.{k}')
        for k in ('settlement_final', 'fill_accounting_pending'):
            o[k] = bl(o[k], f'O.{k}')
        out['orders'].append(o)
    for r in rows['P']:
        p = dict(zip(PNAMES, r))
        p['id'], p['session_id'] = it(p['id'], 'P.id'), it(p['session_id'], 'P.session_id')
        if (p['session_kind'], p['session_id']) not in keys:
            raise Bad('2단계 포지션이 등록 밖')
        nm(p['size'], 'P.size'), nm(p['invested_krw'], 'P.invested_krw'), nm(p['position_fee'], 'P.position_fee')
        for k in ('opened_at', 'closing_at', 'closed_at'):
            ts(p[k], f'P.{k}')
        out['positions'].append(p)
    oids = {o['id'] for o in out['orders']}
    for r in rows['D']:
        d = {'id': it(r[0], 'D.id'), 'order_id': it(r[1], 'D.order_id'), 'kind': r[2], 'detected_at': ts(r[3], 'D.detected_at')}
        if d['order_id'] not in oids:
            raise Bad('미해결 차이의 주문이 2단계 주문에 없음')
        out['unresolved_discrepancies'].append(d)
    cnt = rows['CNT'][0]
    if it(cnt[0], 'CNT.o') != len(out['orders']) or it(cnt[1], 'CNT.p') != len(out['positions']):
        raise Bad('CNT 와 실제 행 수 불일치')
    # 등록 주문이 등록 밖 포지션을 가리키면 금액은 이미 읽혔으므로 형식 결함이 아니라 교차 연결로 보존한다.
    pmap = {p['id']: (p['session_kind'], p['session_id']) for p in out['positions']}
    for o in out['orders']:
        if o['position_id'] is not None and pmap.get(o['position_id']) != (o['session_kind'], o['session_id']):
            out['cross_links'].append({'order_id': o['id'], 'position_id': o['position_id'], 'position_owner': pmap.get(o['position_id'])})
    return 0, out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--raw', required=True)
    ap.add_argument('--psql-rc', type=int, required=True)
    ap.add_argument('--registry', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    raw = open(a.raw, 'rb').read()
    regb = open(a.registry, 'rb').read()
    meta = {'raw_sha256': hashlib.sha256(raw).hexdigest(), 'raw_bytes': len(raw), 'psql_rc': a.psql_rc}
    if a.psql_rc != 0:
        rc, body = 20, {'error': 'psql 실패 — 원문·stderr 보존, 부분 출력을 값으로 쓰지 않음'}
    else:
        try:
            rc, body = parse(raw.decode('utf-8'), json.loads(regb.decode('utf-8')), hashlib.sha256(regb).hexdigest())
        except (Bad, UnicodeDecodeError, ValueError, IndexError, TypeError, KeyError, AttributeError) as e:
            rc, body = 30, {'error': f'{type(e).__name__}: {e}'}
    res = dict(meta, rc=rc, **body)
    with open(a.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    print(json.dumps({'rc': rc, 'raw_sha256': meta['raw_sha256'], 'error': body.get('error')}, ensure_ascii=False))
    return rc


if __name__ == '__main__':
    sys.exit(main())
