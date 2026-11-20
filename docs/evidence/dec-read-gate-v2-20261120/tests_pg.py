"""read.sql v2 + parse.py v2 격리 로컬 PostgreSQL 검증(총괄 v1.204 §3-1, 1회 허용분).
전제: 네트워크 없는 일회용 컨테이너(--network none, 데이터 tmpfs)에 d81c3f2f 마이그레이션 V1~V96 을 적용한 DB 'base'.
      flyway_schema_history 는 (version, success) 최소 표로 대신했다. 제품 PG·운영 DB·애플리케이션은 쓰지 않는다.
사용: python -I tests_pg.py --container <이름> --out <회차 폴더>
사례마다 base 를 템플릿으로 새 DB 를 만들고 공통+사례 픽스처를 넣은 뒤 psql 로 read.sql 을 실행해 stdout·stderr·rc 원문을 보존하고
parse.py 를 돌린다. 거절 사례는 원문에 2단계 레코드(S/O/P/D/CNT)가 0행인지 직접 센다.
"""
import argparse, hashlib, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
V1 = os.path.join(HERE, '..', 'dec-read-plan-20261120', 'read.sql')

COMMON = r"""
INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, created_at)
VALUES (203,'X','KRW-BTC','H1',20200,11000.5,20200,'RUNNING','2026-11-06 19:07:36+09'),
       (100,'X','KRW-ETH','H1',10000,10000,10000,'STOPPED','2026-10-01 09:00:00+09');
INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode, created_at)
VALUES (900,'D','H1',30000,30000,30000,'RUNNING','REAL','2026-11-10 09:00:00+09'),
       (950,'D','H1',777777,777777,777777,'RUNNING','PAPER','2026-11-10 09:00:00+09');
INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at, closed_at)
VALUES (7,'KRW-BTC','LONG',92000000,92000000,0.0001,'OPEN','LIVE',203,9200.09,'2026-11-20 09:00:01+09',NULL),
       (8,'KRW-BTC','LONG',90000000,90000000,0.0001,'CLOSED','LIVE',203,9000,'2026-10-21 09:00:01+09','2026-11-20 08:00:00+09'),
       (20,'KRW-ETH','LONG',5000000,5000000,0.001,'CLOSED','LIVE',100,5000,'2026-10-02 09:00:00+09','2026-10-03 09:00:00+09'),
       (30,'KRW-XRP','LONG',777,777,7,'OPEN','DYNAMIC',950,5439,'2026-11-11 09:00:00+09',NULL);
INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, filled_at,
                    sell_applied_at, settlement_final, fee_source, applied_executed_funds, applied_paid_fee)
VALUES (11,7,'KRW-BTC','BUY','MARKET',0.0001,'FILLED','LIVE',203,'2026-11-20 09:00:00+09','2026-11-20 09:00:01+09',NULL,true,'EXCHANGE',9195.5,4.59),
       (12,8,'KRW-BTC','BUY','MARKET',0.0001,'FILLED','LIVE',203,'2026-10-21 09:00:00+09','2026-10-21 09:00:01+09',NULL,true,'EXCHANGE',9000,4.5),
       (13,8,'KRW-BTC','SELL','MARKET',0.0001,'FILLED','LIVE',203,'2026-11-18 23:59:00+09','2026-11-20 07:59:00+09','2026-11-20 08:00:00+09',true,'EXCHANGE',9100,4.55),
       (14,NULL,'KRW-BTC','BUY','MARKET',0.0001,'SUBMITTED','LIVE',203,'2026-11-20 10:00:00+09',NULL,NULL,false,NULL,NULL,NULL),
       (21,20,'KRW-ETH','BUY','MARKET',0.001,'FILLED','LIVE',100,'2026-10-02 09:00:00+09','2026-10-02 09:00:01+09',NULL,true,'EXCHANGE',5000,2.5),
       (31,30,'KRW-XRP','BUY','MARKET',7,'FILLED','DYNAMIC',950,'2026-11-11 09:00:00+09','2026-11-11 09:00:01+09',NULL,true,'EXCHANGE',777777,388.9),
       (41,NULL,'KRW-SOL','BUY','MARKET',0.1,'FILLED','DYNAMIC',900,'2026-11-12 09:00:00+09','2026-11-12 09:00:01+09',NULL,true,'EXCHANGE',20000,10);
INSERT INTO order_settlement_discrepancy(id, order_id, kind, resolved_at) VALUES (1,11,'FEE_DECREASE',NULL),(2,21,'FEE_DECREASE','2026-10-04 09:00:00+09');
"""
REG_OK = {'registry_version': 'registry-3', 'sessions': [{'kind': 'LIVE', 'id': 203}, {'kind': 'DYNAMIC', 'id': 900}]}
INS_O = 'INSERT INTO "order"(id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, settlement_final) VALUES '
STAGE2 = ('S', 'O', 'P', 'D', 'CNT')
WIN = '2026-11-19T00:00:00Z'   # v1 대조 실행용(v1 의 window_from)

# (이름, 사례 픽스처, 등록표, 기대 rc, 추가 검사)
CASES = [
    ('A 정상 GO — 과거 생성·창 안 정산 매도(13)와 과거 매수(12)·CLOSED 포지션(8) 포함, PAPER 950·등록 밖 STOPPED 100 금액 없음', '', REG_OK, 0,
     lambda o, raw: {x['id'] for x in o['orders']} == {11, 12, 13, 14, 41} and {p['id'] for p in o['positions']} == {7, 8}
     and [d['order_id'] for d in o['unresolved_discrepancies']] == [11]
     and '777777' not in raw and '\t950\t' not in raw
     and {(u['rec'], u['id'], u['stop']) for u in o['unregistered_ids']} == {('S', 100, False), ('O', 21, False), ('P', 20, False)}),
    ('B NULL 소유자 주문(LIVE·FILLED·과거) — SQL 게이트 STOP', INS_O + "(50,'KRW-BTC','BUY','MARKET',1,'FILLED','LIVE',NULL,'2026-01-01 09:00:00+09',true);",
     REG_OK, 10, lambda o, raw: any(u['id'] == 50 and u['owner'] == 'NO_SESSION_ID' for u in o['unregistered_ids'])),
    ('C 소유 행 없음(DYNAMIC 999) — STOP', INS_O + "(51,'KRW-BTC','BUY','MARKET',1,'CANCELLED','DYNAMIC',999,'2026-01-01 09:00:00+09',true);",
     REG_OK, 10, lambda o, raw: any(u['id'] == 51 and u['owner'] == 'NO_SESSION_ROW' for u in o['unregistered_ids'])),
    ('D 알 수 없는 kind(PAPER 문자열) — STOP', INS_O + "(52,'KRW-BTC','BUY','MARKET',1,'FILLED','PAPER',203,'2026-01-01 09:00:00+09',true);",
     REG_OK, 10, lambda o, raw: any(u['id'] == 52 and u['owner'] == 'UNKNOWN_KIND' for u in o['unregistered_ids'])),
    ('E 등록표에 없는 실제 ID(LIVE 204 미존재) — REG_MISSING STOP', '',
     {'registry_version': 't', 'sessions': [{'kind': 'LIVE', 'id': 203}, {'kind': 'LIVE', 'id': 204}, {'kind': 'DYNAMIC', 'id': 900}]}, 10,
     lambda o, raw: [r['reg'] for r in o['registry_check']] == ['REG_MISSING']),
    ('F 등록된 DYNAMIC PAPER(950) — REG_MODE STOP, 950 금액 비열람', '',
     {'registry_version': 't', 'sessions': [{'kind': 'LIVE', 'id': 203}, {'kind': 'DYNAMIC', 'id': 900}, {'kind': 'DYNAMIC', 'id': 950}]}, 10,
     lambda o, raw: [(r['id'], r['reg'], r['mode']) for r in o['registry_check']] == [(950, 'REG_MODE', 'PAPER')] and '777777' not in raw),
    ('G 등록 밖 RUNNING 실자금 세션(DYNAMIC 901 REAL) — STOP', "INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status) VALUES (901,'D','H1',1,1,1,'RUNNING');",
     REG_OK, 10, lambda o, raw: any(u['rec'] == 'S' and u['id'] == 901 and u['stop'] for u in o['unregistered_ids'])),
    ('H 등록 밖 STOPPED 세션의 진행 중 주문(과거 생성) — STOP', INS_O + "(53,'KRW-ETH','BUY','MARKET',1,'SUBMITTED','LIVE',100,'2026-10-05 09:00:00+09',false);",
     REG_OK, 10, lambda o, raw: any(u['id'] == 53 and u['owner'] == 'UNREG' and u['stop'] for u in o['unregistered_ids'])),
    ('I 소유 없는 포지션(session_id NULL·CLOSED) — STOP', "INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id) VALUES (60,'KRW-BTC','LONG',1,1,1,'CLOSED','LIVE',NULL);",
     REG_OK, 10, lambda o, raw: any(u['rec'] == 'P' and u['id'] == 60 and u['owner'] == 'NO_SESSION_ID' for u in o['unregistered_ids'])),
    ('J 빈 등록표 — STOP', '', {'registry_version': 't', 'sessions': []}, 10, lambda o, raw: o['gate_bad'] >= 1),
]


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def psql(cont, db, args, stdin=None):
    return sh(['docker', 'exec', '-i', cont, 'psql', '-X', '-q', '-U', 'postgres', '-d', db] + args, input=stdin)


def arr(reg, kind):
    return '{' + ','.join(str(s['id']) for s in reg['sessions'] if s['kind'] == kind) + '}'


def run_case(cont, out, tag, delta, reg, sql_path, extra_vars=()):
    d = os.path.join(out, tag)
    os.makedirs(d, exist_ok=True)
    db = 'c_' + tag.lower()
    for q in (f'DROP DATABASE IF EXISTS {db}', f'CREATE DATABASE {db} TEMPLATE base'):
        r = psql(cont, 'postgres', ['-v', 'ON_ERROR_STOP=1', '-c', q])
        if r.returncode:
            raise SystemExit(f'{tag}: {q} 실패 {r.stderr!r}')
    fx = (COMMON + delta).encode('utf-8')
    open(os.path.join(d, 'fixture.sql'), 'wb').write(fx)
    r = psql(cont, db, ['-v', 'ON_ERROR_STOP=1'], stdin=fx)
    if r.returncode:
        raise SystemExit(f'{tag}: 픽스처 실패 {r.stderr!r}')
    regb = json.dumps(reg, ensure_ascii=False, sort_keys=True).encode('utf-8')
    open(os.path.join(d, 'registry.json'), 'wb').write(regb)
    vars_ = ['-v', f"live_ids={arr(reg, 'LIVE')}", '-v', f"dyn_ids={arr(reg, 'DYNAMIC')}",
             '-v', f'reg_sha256={hashlib.sha256(regb).hexdigest()}'] + list(extra_vars)
    open(os.path.join(d, 'vars.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(vars_[1::2]) + '\n')
    sql = open(sql_path, 'rb').read()
    p = psql(cont, db, ['-v', 'ON_ERROR_STOP=1'] + vars_, stdin=sql)
    open(os.path.join(d, 'stdout.tsv'), 'wb').write(p.stdout)
    open(os.path.join(d, 'stderr.txt'), 'wb').write(p.stderr)
    open(os.path.join(d, 'psql_rc.txt'), 'w').write(f'{p.returncode}\n')
    stage2 = sum(1 for ln in p.stdout.decode('utf-8', 'replace').split('\n') if ln.split('\t')[0] in STAGE2)
    return d, p.returncode, stage2, p.stdout.decode('utf-8', 'replace')


def parse_run(d, rc):
    q = sh([sys.executable, '-I', os.path.join(HERE, 'parse.py'), '--raw', os.path.join(d, 'stdout.tsv'), '--psql-rc', str(rc),
            '--registry', os.path.join(d, 'registry.json'), '--out', os.path.join(d, 'parsed.json')])
    open(os.path.join(d, 'parse_rc.txt'), 'w').write(f'{q.returncode}\n')
    return q.returncode, json.load(open(os.path.join(d, 'parsed.json'), encoding='utf-8'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--container', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    h = lambda p: hashlib.sha256(open(p, 'rb').read()).hexdigest()
    ver = sh(['docker', 'exec', a.container, 'psql', '-U', 'postgres', '-tAc', 'select version()']).stdout.decode().strip()
    lines = [f'read.sql sha256={h(os.path.join(HERE, "read.sql"))}', f'parse.py sha256={h(os.path.join(HERE, "parse.py"))}',
             f'server={ver}', 'base = ALLOW_CONNECTIONS false 템플릿(사례 DB 에서 마이그레이션 수를 읽음)']
    ok = n = 0
    for i, (name, delta, reg, want, chk) in enumerate(CASES):
        tag = name.split()[0]
        d, prc, st2, raw = run_case(a.container, a.out, tag, delta, reg, os.path.join(HERE, 'read.sql'))
        rc, o = parse_run(d, prc)
        if i == 0:
            lines.append('base migrations(count|max)=' + psql(a.container, 'c_a', ['-tAc', 'select count(*), max(version::int) from flyway_schema_history']).stdout.decode().strip())
        good = prc == 0 and rc == want and (want == 0 or st2 == 0) and chk(o, raw)
        n += 1; ok += good
        lines.append(f"{'PASS' if good else 'FAIL'} | {name} | psql_rc={prc} parse_rc={rc} gate={o.get('gate')} gate_bad={o.get('gate_bad')} 2단계행={st2} err={o.get('error')}")
    # K: psql 실패(잘못된 배열 변수) — 2단계 0행, 파서 20
    d, prc, st2, raw = run_case(a.container, a.out, 'K', '', REG_OK, os.path.join(HERE, 'read.sql'))
    d2 = os.path.join(a.out, 'K')
    p = psql(a.container, 'c_k', ['-v', 'ON_ERROR_STOP=1', '-v', 'live_ids=abc', '-v', 'dyn_ids={}', '-v', 'reg_sha256=x'],
             stdin=open(os.path.join(HERE, 'read.sql'), 'rb').read())
    open(os.path.join(d2, 'stdout.tsv'), 'wb').write(p.stdout)
    open(os.path.join(d2, 'stderr.txt'), 'wb').write(p.stderr)
    open(os.path.join(d2, 'psql_rc.txt'), 'w').write(f'{p.returncode}\n')
    open(os.path.join(d2, 'vars.txt'), 'w', encoding='utf-8', newline='\n').write('live_ids=abc\ndyn_ids={}\nreg_sha256=x\n')
    st2 = sum(1 for ln in p.stdout.decode('utf-8', 'replace').split('\n') if ln.split('\t')[0] in STAGE2)
    rc, o = parse_run(d2, p.returncode)
    good = p.returncode != 0 and rc == 20 and st2 == 0
    n += 1; ok += good
    lines.append(f"{'PASS' if good else 'FAIL'} | K psql 실패(live_ids=abc) — 부분 출력 미사용 | psql_rc={p.returncode} parse_rc={rc} 2단계행={st2}")
    # 대조: v1 read.sql 은 B(NULL 소유자)·F(등록 PAPER)에서 GO 로 금액을 읽는다(결함 재현, 기대 = v1 GO).
    for name, delta, reg in (('V1B', CASES[1][1], REG_OK), ('V1F', '', CASES[5][2])):
        d, prc, st2, raw = run_case(a.container, a.out, name, delta, reg, V1, ['-v', f'window_from={WIN}'])
        gate = next((ln.split('\t')[1] for ln in raw.split('\n') if ln.startswith('GATE\t')), None)
        lines.append(f'대조 | {name} v1 read.sql sha256={h(V1)} | psql_rc={prc} gate={gate} 2단계행={st2} '
                     f"PAPER950금액노출={'777777' in raw}")
    lines.append(f'합계 {n} 통과 {ok} 실패 {n - ok}')
    open(os.path.join(a.out, 'tests_pg.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 0 if ok == n else 1


if __name__ == '__main__':
    sys.exit(main())
