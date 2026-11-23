"""R1 범위 관문·R3 원천 열 격리 PG 1묶음 4사례(총괄 v1.229 §4). 운영·제품 PG·애플리케이션·외부 연결 없음.

전제: dec-read-gate-v3-20261120/setup_pg.py(98b3eab0)로 만든 --network none·tmpfs 일회용 컨테이너와 d81c3f2f V1~V96 템플릿 DB 'base'.
사례마다 base 에서 새 DB 를 복제하고 합성 픽스처를 넣은 뒤, 읽기안과 같은 형식(표준 입력 -f -, PGOPTIONS 보호값, -At -F TAB,
-v ON_ERROR_STOP=1 -v sid -v with_orders=true)으로 read/a3-pre.sql 을 1회 실행한다.
사례(4):
  A normal_real203   REAL#203(+범위 밖 REAL 204·PAPER 205·DYNAMIC REAL 900·소유 없는 열린 주문) sid=203 → SCOPE OK, 범위 밖 금액 0행,
                     R3: BUY 원천 없는 포지션 22 = NO_BUY_SOURCE·원가 '-', 20개 10,005원 매수 뒤 10개 매도한 포지션 21 = 총원가 10005·잔여 5002.5
  B target_paper     LIVE#203 이 PAPER, sid=203 → SESSION_NOT_REAL, 범위 절 0행
  C target_missing   LIVE#203 세션 행 없음(203 을 가리키는 주문/포지션 행은 남김), sid=203 → SESSION_NOT_FOUND, 범위 절 0행
  D other_real_id    REAL#203·REAL#204 모두 있음, sid=204 → SID_NOT_APPROVED, 범위 절 0행
사용: python -I pg_scope.py --container <이름> --out <기록 폴더(없어야 함)>
종료값: 4사례 모두 기대대로 0, 아니면 1(원문 보존). 실행 환경 전제 실패 2.
"""
import argparse, hashlib, json, os, subprocess, sys
from decimal import Decimal

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
SQL = os.path.join(os.path.dirname(HERE), 'read', 'a3-pre.sql')
PGOPTIONS = 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s'

OUT_AMT = ['731911', '642817', '553729', '464631', '375543', '286457', '197371', '108283', '99173']   # 범위 밖에만 쓰는 금액
S = "INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, session_type) VALUES "
P = "INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at) VALUES "
O = ('INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, filled_quantity, '
     'applied_executed_funds, applied_paid_fee, settlement_final, fill_accounting_pending, sell_applied_at, reserved_krw, created_at, filled_at) VALUES ')


def fixture(t203):
    """t203: 'REAL' | 'PAPER' | None(세션 행 없음). 203 소유 주문/포지션 행은 모든 사례에 넣는다."""
    s = []
    if t203:
        s.append(S + f"(203,'DAILY_SMA20','KRW-BTC','H1',20200,15195,20200,'CREATED','{t203}');")
    s.append(S + "(204,'DAILY_SMA20','KRW-ETH','H1',731911,642817,731911,'RUNNING','REAL'),"
                 "(205,'DAILY_SMA20','KRW-XRP','H1',553729,553729,553729,'RUNNING','PAPER');")
    s.append("INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, status, trading_mode) "
             "VALUES (900,'COMPOSITE_BREAKOUT','H1',464631,464631,464631,'RUNNING','REAL');")
    s.append(P + "(21,'KRW-BTC','LONG',500,500.25,10,'OPEN','LIVE',203,10005,'2026-11-20 00:00:01+00'),"
                 "(22,'KRW-ETH','LONG',1000,1000,0.5,'OPEN','LIVE',203,NULL,'2026-11-20 00:00:02+00'),"
                 "(31,'KRW-ETH','LONG',375543,375543,1,'OPEN','LIVE',204,375543,'2026-11-20 00:00:03+00'),"
                 "(41,'KRW-XRP','LONG',197371,197371,1,'OPEN','LIVE',205,197371,'2026-11-20 00:00:04+00'),"
                 "(51,'KRW-SOL','LONG',108283,108283,1,'OPEN','DYNAMIC',900,108283,'2026-11-20 00:00:05+00');")
    s.append(O + "(101,21,'KRW-BTC','BUY','MARKET',20,'FILLED','LIVE',203,20,10000,5,true,false,NULL,NULL,'2026-11-20 00:00:00+00','2026-11-20 00:00:01+00'),"
                 "(102,21,'KRW-BTC','SELL','MARKET',10,'FILLED','LIVE',203,10,6000,3,true,false,'2026-11-21 00:00:01+00',NULL,'2026-11-21 00:00:00+00','2026-11-21 00:00:01+00'),"
                 "(103,NULL,'KRW-BTC','BUY','MARKET',0,'SUBMITTED','LIVE',203,0,NULL,NULL,false,false,NULL,5000,'2026-11-22 00:00:00+00',NULL),"
                 "(201,31,'KRW-ETH','BUY','MARKET',1,'FILLED','LIVE',204,1,375543,0,true,false,NULL,NULL,'2026-11-20 00:00:00+00','2026-11-20 00:00:03+00'),"
                 "(202,NULL,'KRW-ETH','BUY','MARKET',0,'SUBMITTED','LIVE',204,0,NULL,NULL,false,false,NULL,286457,'2026-11-22 00:00:00+00',NULL),"
                 "(301,41,'KRW-XRP','BUY','MARKET',1,'FILLED','LIVE',205,1,197371,0,true,false,NULL,NULL,'2026-11-20 00:00:00+00','2026-11-20 00:00:04+00'),"
                 "(401,51,'KRW-SOL','BUY','MARKET',1,'FILLED','DYNAMIC',900,1,108283,0,true,false,NULL,NULL,'2026-11-20 00:00:00+00','2026-11-20 00:00:05+00'),"
                 "(501,NULL,'KRW-ADA','BUY','MARKET',0,'SUBMITTED','LIVE',NULL,0,NULL,NULL,false,false,NULL,99173,'2026-11-22 00:00:00+00',NULL);")
    s.append("INSERT INTO position_cleanup(position_id, session_kind, session_id, reason, pending_at) VALUES (31,'LIVE',204,'SYNTH','2026-11-22 00:00:00+00');")
    s.append("INSERT INTO order_settlement_discrepancy(order_id, kind, resolved_at) VALUES (201,'FEE_DECREASE',NULL);")
    return '\n'.join(s) + '\n'


CASES = [('A_normal_real203', 'REAL', '203', 'OK'), ('B_target_paper', 'PAPER', '203', 'SESSION_NOT_REAL'),
         ('C_target_missing', None, '203', 'SESSION_NOT_FOUND'), ('D_other_real_id', 'REAL', '204', 'SID_NOT_APPROVED')]
SCOPED = ('TSES', 'OBUY', 'CNT', 'XCNT', 'POS', 'MAXTS', 'MORD')


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def wb(p, b):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def psql(cont, db, args, stdin=None, env=()):
    e = [x for k in env for x in ('-e', k)]
    return sh(['docker', 'exec', '-i', *e, cont, 'psql', '-X', '-q', '-U', 'postgres', '-d', db, *args], input=stdin)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--container', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    net = sh(['docker', 'inspect', '-f', '{{.HostConfig.NetworkMode}}', a.container]).stdout.decode().strip()
    if net != 'none':
        print('🔴 컨테이너가 네트워크 없는 격리가 아님:', net)
        return 2
    os.makedirs(a.out, exist_ok=False)
    sqlb = open(SQL, 'rb').read()
    sql_sha = hashlib.sha256(sqlb).hexdigest()
    results, ok_all = [], True
    for name, t203, sid, scope_exp in CASES:
        d = os.path.join(a.out, name)
        db = 'c_' + name.split('_')[0].lower()
        r = psql(a.container, 'postgres', ['-v', 'ON_ERROR_STOP=1', '-c', f'CREATE DATABASE {db} TEMPLATE base'])
        if r.returncode:
            print('🔴 DB 복제 실패', r.stderr)
            return 2
        fx = fixture(t203)
        wb(os.path.join(d, 'fixture.sql'), fx)
        r = psql(a.container, db, ['-v', 'ON_ERROR_STOP=1', '-f', '-'], stdin=fx.encode())
        wb(os.path.join(d, 'fixture.rc'), f'{r.returncode}\n{r.stderr.decode("utf-8", "replace")}')
        if r.returncode:
            print('🔴 픽스처 실패', name, r.stderr.decode('utf-8', 'replace'))
            return 2
        p = psql(a.container, db, ['-At', '-F', '\t', '-v', 'ON_ERROR_STOP=1', '-v', f'sid={sid}', '-v', 'with_orders=true', '-f', '-'],
                 stdin=sqlb, env=(PGOPTIONS,))
        wb(os.path.join(d, 'ledger.tsv'), p.stdout)
        wb(os.path.join(d, 'ledger.stderr'), p.stderr)
        wb(os.path.join(d, 'ledger.rc'), f'{p.returncode}\n')
        out = p.stdout.decode('utf-8')
        rows = [ln.split('\t') for ln in out.split('\n') if ln]
        tags = [r[0] for r in rows]
        scope = [r for r in rows if r[0] == 'SCOPE']
        amounts_leaked = [x for x in OUT_AMT if x in out]
        checks = {'rc0': p.returncode == 0, 'stderr_empty': p.stderr == b'',
                  'scope': scope == [['SCOPE', scope_exp, 'LIVE', sid]],
                  'meta_first_end_last': bool(tags) and tags[0] == 'META' and tags[-1] == 'END',
                  'out_of_scope_amounts_absent': not amounts_leaked}
        if scope_exp == 'OK':
            by = {t: [r for r in rows if r[0] == t] for t in SCOPED}
            pos = {r[1]: r for r in by['POS']}
            p21, p22 = pos.get('21'), pos.get('22')
            checks.update({
                'tses_only_203': [r[2] for r in by['TSES']] == ['203'] and by['TSES'][0][3] == 'REAL',
                'obuy_only_103': [r[1] for r in by['OBUY']] == ['103'],
                'cnt_target': by['CNT'] == [['CNT', '1', '0', '2']],
                'xcnt_counts_only': by['XCNT'] == [['XCNT', '2', '1', '2', '1', '1']],
                'pos_only_target': sorted(pos) == ['21', '22'],
                'mord_only_target': [r[1] for r in by['MORD']] == ['101', '102', '103'],
                'maxts_one_row': len(by['MAXTS']) == 1,
                # R3: 21 = 20개 10,005원 매수 → 10개 매도 → 잔량 10, 남은 원가 5002.5(총원가 10005 를 넘기지 않는다)
                'r3_p21_sources': p21 is not None and Decimal(p21[5]) == 1 and Decimal(p21[6]) == 20 and Decimal(p21[7]) == 10005
                                  and p21[8:11] == ['0', '0', '1'] and Decimal(p21[11]) == 10 and p21[12:15] == ['0', '0', '0'],
                'r3_p21_allocated': p21 is not None and p21[15] == 'ALLOCATED' and Decimal(p21[16]) == Decimal('5002.5')
                                    and Decimal(p21[4]) == 10,
                # R3: 22 = BUY 원천 0 → complete 로 만들지 않는다
                'r3_p22_no_buy_source': p22 is not None and p22[5] == '0' and p22[6] == '-' and p22[7] == '-'
                                        and p22[15] == 'NO_BUY_SOURCE' and p22[16] == '-',
            })
        else:
            checks['scoped_sections_zero_rows'] = not any(t in SCOPED for t in tags)
            checks['only_meta_scope_end'] = tags == ['META', 'SCOPE', 'END']
            checks['target_amounts_absent'] = not any(x in out for x in ('10005', '5002.5', '20200', '15195'))
        ok = all(checks.values())
        ok_all &= ok
        res = {'case': name, 'sid': sid, 'target_session_type': t203, 'ok': ok, 'checks': checks, 'amounts_leaked': amounts_leaked,
               'rows_by_tag': {t: tags.count(t) for t in sorted(set(tags))}}
        wb(os.path.join(d, 'result.json'), json.dumps(res, ensure_ascii=False, indent=1) + '\n')
        results.append(res)
        print(('PASS ' if ok else 'FAIL ') + name, '' if ok else {k: v for k, v in checks.items() if not v})
    show = sh(['docker', 'exec', '-e', 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s', a.container, 'psql', '-X', '-At', '-U', 'postgres',
               '-d', 'c_a', '-c', 'SHOW statement_timeout', '-c', 'SHOW lock_timeout']).stdout.decode().split()
    summary = {'sql_sha256': sql_sha, 'container_network': net, 'pgoptions_show': show,
               'passed': sum(r['ok'] for r in results), 'total': len(results), 'cases': {r['case']: r['ok'] for r in results}}
    wb(os.path.join(a.out, 'summary.json'), json.dumps(summary, ensure_ascii=False, indent=1) + '\n')
    print(f"{summary['passed']}/{summary['total']}", show)
    return 0 if ok_all else 1


if __name__ == '__main__':
    sys.exit(main())
