"""read.sql 원문 출력 파서 검토안(총괄 v1.203 §3-3) — 파일 입력만. 실행된 적 있는 운영 출력은 없다(가상 출력으로만 시험).
사용: python -I parse.py --raw <stdout 원문> --psql-rc <정수> --registry <등록표 v3 json> --out <db 조각.json>
종료값: 0 = 2단계까지 읽음(형식 정상), 10 = 게이트 STOP(등록 밖 ID — 금액 열람 없음), 20 = psql 실패(rc≠0),
        30 = 형식 결함(열 수·레코드 순서·숫자/시각 형식·META/GATE/END 누락·등록 밖 행이 2단계에 섞임).
원문은 바꾸지 않는다. 결과 조각에는 원문 sha256 을 적는다. 숫자는 문자열 그대로(실수 변환 없음), \\N 은 null.
"""
import argparse, hashlib, json, re, sys
from datetime import datetime

COLS = {'META': 7, 'ID_S': 4, 'ID_O': 6, 'ID_P': 5, 'GATE': 2, 'S': 12, 'O': 24, 'P': 13, 'D': 5, 'END': 2}
ORDER = ['META', 'ID_S', 'ID_O', 'ID_P', 'GATE', 'S', 'O', 'P', 'D', 'END']   # 레코드 종류가 나오는 순서
NUM = re.compile(r'^-?\d+(\.\d+)?$')


class Bad(ValueError):
    pass


def v(x):
    return None if x == '\\N' else x


def tsv(x, p):
    if x is None:
        return None
    try:
        t = datetime.fromisoformat(x.replace(' ', 'T', 1))
    except ValueError:
        raise Bad(f'{p}: 시각 형식')
    if t.tzinfo is None:
        raise Bad(f'{p}: 시간대 없음')
    return x


def nm(x, p):
    if x is not None and not NUM.match(x):
        raise Bad(f'{p}: 숫자 형식')
    return x


def parse(text, reg):
    keys = {(s['kind'], s['id']) for s in reg['sessions']}
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
    for k in ('META', 'GATE', 'END'):
        if len(rows[k]) != 1:
            raise Bad(f'{k} 정확히 1개 아님')
    meta = rows['META'][0]
    tsv(meta[1], 'META.now'), tsv(meta[2], 'META.clock')
    if meta[5] != 'repeatable read':
        raise Bad('격리 수준 repeatable read 아님')
    unreg = [r for r in rows['ID_O'] if r[4] != 'OK' or (r[1], int(r[2])) not in keys] + \
            [r for r in rows['ID_P'] if (r[1], int(r[2])) not in keys] + \
            [r for r in rows['ID_S'] if (r[0], int(r[1])) not in keys and r[2] != 'STOPPED']
    gate = rows['GATE'][0][0]
    if gate == 'STOP':
        if rows['S'] or rows['O'] or rows['P'] or rows['D']:
            raise Bad('STOP 인데 2단계 행이 있음')
        return 10, {'gate': 'STOP', 'unregistered_ids': unreg}
    if gate != 'GO' or unreg:
        raise Bad(f'GATE {gate} 와 식별 목록 불일치(등록 밖 {len(unreg)})')
    out = {'observed_at': meta[1], 'clock_read_at': meta[2], 'snapshot': meta[3], 'flyway_version': int(meta[4]),
           'end_clock': tsv(rows['END'][0][0], 'END'), 'sessions': [], 'orders': [], 'positions': [], 'unresolved_discrepancies': []}
    for r in rows['S']:
        if (r[0], int(r[1])) not in keys:
            raise Bad('2단계 세션이 등록 밖')
        out['sessions'].append({'kind': r[0], 'id': int(r[1]), 'mode': r[2], 'strategy_type': r[3], 'timeframe': r[4], 'coin_pair': r[5],
                                'initial_capital': nm(r[6], 'S.initial_capital'), 'available_krw': nm(r[7], 'S.available_krw'),
                                'status': r[8], 'created_at': tsv(r[9], 'S.created_at'), 'stopped_at': tsv(r[10], 'S.stopped_at')})
    for r in rows['O']:
        if (r[1], int(r[2])) not in keys:
            raise Bad('2단계 주문이 등록 밖')
        names = ['id', 'session_kind', 'session_id', 'position_id', 'coin_pair', 'side', 'order_type', 'quantity', 'reserved_krw',
                 'client_order_id', 'state', 'filled_quantity', 'exchange_order_id', 'created_at', 'filled_at', 'sell_applied_at',
                 'reported_executed_funds', 'reported_paid_fee', 'applied_executed_funds', 'applied_paid_fee', 'settlement_final',
                 'fill_accounting_pending', 'reported_observed_at']
        o = dict(zip(names, r))
        for k in ('quantity', 'reserved_krw', 'filled_quantity', 'reported_executed_funds', 'reported_paid_fee', 'applied_executed_funds', 'applied_paid_fee'):
            nm(o[k], f'O.{k}')
        for k in ('created_at', 'filled_at', 'sell_applied_at', 'reported_observed_at'):
            tsv(o[k], f'O.{k}')
        for k in ('settlement_final', 'fill_accounting_pending'):
            if o[k] not in ('t', 'f', None):
                raise Bad(f'O.{k} 불리언 형식')
            o[k] = None if o[k] is None else o[k] == 't'
        for k in ('id', 'session_id', 'position_id'):
            o[k] = None if o[k] is None else int(o[k])
        out['orders'].append(o)
    for r in rows['P']:
        if (r[1], int(r[2])) not in keys:
            raise Bad('2단계 포지션이 등록 밖')
        out['positions'].append({'id': int(r[0]), 'session_kind': r[1], 'session_id': int(r[2]), 'coin_pair': r[3], 'size': nm(r[4], 'P.size'),
                                 'status': r[5], 'invested_krw': nm(r[6], 'P.invested_krw'), 'position_fee': nm(r[7], 'P.position_fee'),
                                 'ruleset_hash': r[8], 'opened_at': tsv(r[9], 'P.opened_at')})
    for r in rows['D']:
        out['unresolved_discrepancies'].append({'id': int(r[0]), 'order_id': int(r[1]), 'kind': r[2], 'detected_at': r[3]})
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
    meta = {'raw_sha256': hashlib.sha256(raw).hexdigest(), 'raw_bytes': len(raw), 'psql_rc': a.psql_rc}
    if a.psql_rc != 0:
        rc, body = 20, {'error': 'psql 실패 — 원문·stderr 보존, 부분 출력을 값으로 쓰지 않음'}
    else:
        try:
            rc, body = parse(raw.decode('utf-8'), json.load(open(a.registry, encoding='utf-8')))
        except (Bad, UnicodeDecodeError, ValueError, IndexError, TypeError) as e:
            rc, body = 30, {'error': f'{type(e).__name__}: {e}'}
    res = dict(meta, rc=rc, **body)
    with open(a.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    print(json.dumps({'rc': rc, 'raw_sha256': meta['raw_sha256']}, ensure_ascii=False))
    return rc


if __name__ == '__main__':
    sys.exit(main())
