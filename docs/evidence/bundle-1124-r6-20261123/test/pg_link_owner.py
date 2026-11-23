"""R6-1 소유 연결 1사례 — 보완 전(r5 a3-pre-2)/후(r6 a3-pre-3) 비교(총괄 v1.230 §2 R6-1). 운영·제품 PG·애플리케이션·외부 연결 없음.

전제: dec-read-gate-v3-20261120/setup_pg.py(98b3eab0)로 만든 --network none·tmpfs 일회용 컨테이너와 d81c3f2f V1~V96 템플릿 DB 'base'.
base 에서 DB 하나를 복제해 합성 픽스처를 넣고, 같은 DB 에 r5 SQL 과 r6 SQL 을 읽기안 형식(표준 입력 -f -, PGOPTIONS 보호값,
-At -F TAB, -v ON_ERROR_STOP=1 -v sid=203 -v with_orders=true)으로 각 1회 실행한다(둘 다 READ ONLY + ROLLBACK).
사례(1) link_foreign_buy:
  LIVE#203(REAL) 포지션 21(OPEN, 잔량 20)에 정상 매수 20개/원가 10,005원(10,000+5)과,
  **LIVE#204(REAL) 소유** 매수 20개/원가 90,000원(89,990+10)이 같은 position_id=21 로 연결돼 있다(정적 반례의 합성 재현).
  기대 r5: POS 21 매수 원천 합계 40개/100,005원(범위 밖 금액 혼입) → 구판 결함 확인.
  기대 r6: SCOPE LINK_OWNER_MISMATCH, LINKX 1, 범위 절 0행, 외부 금액(89990·90000·100005)과 정상 원가(10005) 모두 미반출,
           ALLOCATED/잔여 원가 없음.
사용: python -I pg_link_owner.py --container <이름> --out <기록 폴더(없어야 함)>
종료값: 기대대로 0, 아니면 1(원문 보존). 실행 환경 전제 실패 2.
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
SQL_NEW = os.path.join(os.path.dirname(HERE), 'read', 'a3-pre.sql')
SQL_OLD = os.path.join(os.path.dirname(os.path.dirname(HERE)), 'bundle-1124-r5-20261123', 'read', 'a3-pre.sql')
OLD_SHA = '69e1f277c6612758313a7927f10427d979eb07f1a5b9e6875a8d05310e29b31d'
PGOPTIONS = 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s'
SCOPED = ('TSES', 'OBUY', 'CNT', 'XCNT', 'POS', 'MAXTS', 'MORD')
FOREIGN_AMT = ('89990', '90000', '100005')

FIXTURE = (
    "INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, session_type) VALUES "
    "(203,'DAILY_SMA20','KRW-BTC','H1',20200,10195,20200,'CREATED','REAL'),"
    "(204,'DAILY_SMA20','KRW-BTC','H1',100000,10000,100000,'RUNNING','REAL');\n"
    "INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at) VALUES "
    "(21,'KRW-BTC','LONG',500,500.25,20,'OPEN','LIVE',203,10005,'2026-11-20 00:00:01+00');\n"
    'INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, filled_quantity, '
    'applied_executed_funds, applied_paid_fee, settlement_final, fill_accounting_pending, sell_applied_at, reserved_krw, created_at, filled_at) VALUES '
    "(101,21,'KRW-BTC','BUY','MARKET',20,'FILLED','LIVE',203,20,10000,5,true,false,NULL,NULL,'2026-11-20 00:00:00+00','2026-11-20 00:00:01+00'),"
    "(201,21,'KRW-BTC','BUY','MARKET',20,'FILLED','LIVE',204,20,89990,10,true,false,NULL,NULL,'2026-11-20 00:00:00+00','2026-11-20 00:00:02+00');\n"
)


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def wb(p, b):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def psql(cont, db, args, stdin=None, env=()):
    e = [x for k in env for x in ('-e', k)]
    return sh(['docker', 'exec', '-i', *e, cont, 'psql', '-X', '-q', '-U', 'postgres', '-d', db, *args], input=stdin)


def run_sql(cont, db, sqlb, d):
    p = psql(cont, db, ['-At', '-F', '\t', '-v', 'ON_ERROR_STOP=1', '-v', 'sid=203', '-v', 'with_orders=true', '-f', '-'],
             stdin=sqlb, env=(PGOPTIONS,))
    wb(os.path.join(d, 'ledger.tsv'), p.stdout)
    wb(os.path.join(d, 'ledger.stderr'), p.stderr)
    wb(os.path.join(d, 'ledger.rc'), f'{p.returncode}\n')
    out = p.stdout.decode('utf-8')
    return p, out, [ln.split('\t') for ln in out.split('\n') if ln]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--container', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    net = sh(['docker', 'inspect', '-f', '{{.HostConfig.NetworkMode}}', a.container]).stdout.decode().strip()
    if net != 'none':
        print('🔴 컨테이너가 네트워크 없는 격리가 아님:', net)
        return 2
    old_b, new_b = open(SQL_OLD, 'rb').read(), open(SQL_NEW, 'rb').read()
    if hashlib.sha256(old_b).hexdigest() != OLD_SHA:
        print('🔴 r5 원문 해시 불일치')
        return 2
    os.makedirs(a.out, exist_ok=False)
    db = 'c_link'
    r = psql(a.container, 'postgres', ['-v', 'ON_ERROR_STOP=1', '-c', f'CREATE DATABASE {db} TEMPLATE base'])
    if r.returncode:
        print('🔴 DB 복제 실패', r.stderr)
        return 2
    wb(os.path.join(a.out, 'fixture.sql'), FIXTURE)
    r = psql(a.container, db, ['-v', 'ON_ERROR_STOP=1', '-f', '-'], stdin=FIXTURE.encode())
    wb(os.path.join(a.out, 'fixture.rc'), f'{r.returncode}\n{r.stderr.decode("utf-8", "replace")}')
    if r.returncode:
        print('🔴 픽스처 실패', r.stderr.decode('utf-8', 'replace'))
        return 2

    # 보완 전(r5): 범위 밖 원천 혼입 확인
    po, oo, ro = run_sql(a.container, db, old_b, os.path.join(a.out, 'before_r5'))
    pos = [x for x in ro if x[0] == 'POS']
    p21 = pos[0] if len(pos) == 1 else None
    before = {
        'rc0': po.returncode == 0, 'stderr_empty': po.stderr == b'',
        'scope_ok': [x for x in ro if x[0] == 'SCOPE'] == [['SCOPE', 'OK', 'LIVE', '203']],
        'pos21_buy_n_2': p21 is not None and p21[1] == '21' and p21[5] == '2',
        'pos21_buy_qty_40_mixed': p21 is not None and float(p21[6]) == 40,
        'pos21_buy_cost_100005_mixed': p21 is not None and float(p21[7]) == 100005,
        'foreign_amount_in_output': '100005' in oo,
    }
    # 보완 후(r6): 소유 불일치 거절, 금액 없는 사유/개수만
    pn, on, rn = run_sql(a.container, db, new_b, os.path.join(a.out, 'after_r6'))
    tags = [x[0] for x in rn]
    after = {
        'rc0': pn.returncode == 0, 'stderr_empty': pn.stderr == b'',
        'scope_link_owner_mismatch': [x for x in rn if x[0] == 'SCOPE'] == [['SCOPE', 'LINK_OWNER_MISMATCH', 'LIVE', '203']],
        'linkx_count_1': [x for x in rn if x[0] == 'LINKX'] == [['LINKX', '1']],
        'only_meta_scope_linkx_end': tags == ['META', 'SCOPE', 'LINKX', 'END'],
        'scoped_sections_zero_rows': not any(t in SCOPED for t in tags),
        'foreign_amounts_absent': not any(x in on for x in FOREIGN_AMT),
        'normal_cost_not_confirmed': '10005' not in on and 'ALLOCATED' not in on,
        'meta_version_a3_pre_3': bool(rn) and rn[0][:2] == ['META', 'a3-pre-3'],
    }
    ok = all(before.values()) and all(after.values())
    show = sh(['docker', 'exec', '-e', 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s', a.container, 'psql', '-X', '-At', '-U', 'postgres',
               '-d', db, '-c', 'SHOW statement_timeout', '-c', 'SHOW lock_timeout']).stdout.decode().split()
    summary = {'case': 'link_foreign_buy', 'ok': ok, 'container_network': net, 'pgoptions_show': show,
               'sql_old_sha256': OLD_SHA, 'sql_new_sha256': hashlib.sha256(new_b).hexdigest(),
               'test_sha256': hashlib.sha256(open(os.path.abspath(__file__), 'rb').read()).hexdigest(),
               'before_r5': before, 'before_r5_pos21': p21, 'after_r6': after,
               'after_rows_by_tag': {t: tags.count(t) for t in sorted(set(tags))}}
    wb(os.path.join(a.out, 'summary.json'), json.dumps(summary, ensure_ascii=False, indent=1) + '\n')
    print(('PASS' if ok else 'FAIL'), 'before', {k: v for k, v in before.items() if not v}, 'after', {k: v for k, v in after.items() if not v}, show)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
