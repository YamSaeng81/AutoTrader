"""R3 한정 격리 로컬 PG 추가 1묶음(총괄 v1.205 §4-2). v2 의 11사례는 반복하지 않는다.
전제: setup_pg.py 로 만든 네트워크 없는 일회용 컨테이너와 템플릿 DB 'base'. 운영·제품 PG·애플리케이션은 쓰지 않는다.
사용: python -I tests_pg.py --container <이름> --out <회차 폴더>
v1.205 §3 반영: 호출(사례×SQL 판)마다 고유 폴더를 쓰고, 폴더가 이미 있으면 멈춘다(원문 덮어쓰기 없음).

사례:
  R3_V2  v2 read.sql/parse.py, 등록 밖 STOPPED LIVE100 에 기준 뒤 최근 완료된 종결 주문22·CLOSED 포지션21 → v2 는 GO(반례 고정)
  R3_V3  같은 픽스처에 v3 → SQL STOP·파서10·2단계 0행, 주문21·22·포지션20·21 모두 stop
  PAST_V3 v2 A 와 같은 공통 픽스처(과거 종결 주문21·포지션20만) → v3 STOP(기대 변경: v2 A 는 GO 였다)
  A_V3   등록 밖 주문·포지션이 없는 정상 원천 → v3 GO, 2단계 S/O/P/D/CNT 행이 v2 run2/A 원문과 바이트 동일
"""
import argparse, hashlib, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
V2 = os.path.join(HERE, '..', 'dec-read-gate-v2-20261120')
sys.path.insert(0, V2)
import tests_pg as t2  # noqa: E402  v2 의 COMMON·REG_OK·도우미를 그대로 쓴다(사본 아님, 해시로 고정)

STAGE2 = t2.STAGE2
RECENT = ("INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, invested_krw, opened_at, closed_at) "
          "VALUES (21,'KRW-ETH','LONG',5100000,5100000,0.001,'CLOSED','LIVE',100,5100,'2026-11-20 10:20:00+09','2026-11-20 10:25:00+09');\n"
          'INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, created_at, filled_at, '
          'sell_applied_at, settlement_final, fee_source, applied_executed_funds, applied_paid_fee) VALUES '
          "(22,21,'KRW-ETH','BUY','MARKET',0.001,'FILLED','LIVE',100,'2026-11-20 10:20:00+09','2026-11-20 10:20:01+09',NULL,true,'EXCHANGE',5100,2.55);\n")
NO_UNREG = ('DELETE FROM order_settlement_discrepancy WHERE id = 2;\nDELETE FROM "order" WHERE id = 21;\nDELETE FROM position WHERE id = 20;\n')


def run(cont, out, tag, delta, sqlv):
    d = os.path.join(out, tag)
    if os.path.exists(d):
        raise SystemExit(f'{tag}: 폴더가 이미 있음 — 덮어쓰지 않는다')
    src = V2 if sqlv == 'v2' else HERE
    # t2.run_case 는 폴더를 만들고 fixture/registry/vars/stdout/stderr/rc 를 쓴다(이 호출 전용 폴더).
    d, prc, st2, raw = t2.run_case(cont, out, tag, delta, t2.REG_OK, os.path.join(src, 'read.sql'))
    q = subprocess.run([sys.executable, '-I', os.path.join(src, 'parse.py'), '--raw', os.path.join(d, 'stdout.tsv'), '--psql-rc', str(prc),
                        '--registry', os.path.join(d, 'registry.json'), '--out', os.path.join(d, 'parsed.json')], capture_output=True)
    open(os.path.join(d, 'parse_rc.txt'), 'w').write(f'{q.returncode}\n')
    open(os.path.join(d, 'parse_stderr.txt'), 'wb').write(q.stderr)
    o = json.load(open(os.path.join(d, 'parsed.json'), encoding='utf-8'))
    return prc, q.returncode, st2, raw, o


def stage2_lines(raw):
    return [ln for ln in raw.split('\n') if ln.split('\t')[0] in STAGE2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--container', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    h = lambda p: hashlib.sha256(open(p, 'rb').read()).hexdigest()
    lines = [f'v3 read.sql sha256={h(os.path.join(HERE, "read.sql"))}', f'v3 parse.py sha256={h(os.path.join(HERE, "parse.py"))}',
             f'v2 read.sql sha256={h(os.path.join(V2, "read.sql"))}', f'v2 parse.py sha256={h(os.path.join(V2, "parse.py"))}',
             f'v2 tests_pg.py(COMMON 출처) sha256={h(os.path.join(V2, "tests_pg.py"))}']
    ok = n = 0

    def rec(good, msg):
        nonlocal ok, n
        n += 1; ok += bool(good)
        lines.append(f"{'PASS' if good else 'FAIL'} | {msg}")

    stops = lambda o: {(u['rec'], u['id']): u['stop'] for u in o['unregistered_ids']}
    prc, rc, st2, raw, o = run(a.container, a.out, 'R3_V2', RECENT, 'v2')
    rec(prc == 0 and rc == 0 and o['gate'] == 'GO' and st2 > 0 and stops(o).get(('O', 22)) is False and stops(o).get(('P', 21)) is False,
        f'R3_V2 반례 고정: v2 가 기준 뒤 완료된 미등록 주문22·포지션21 을 stop=false 로 두고 GO | psql_rc={prc} parse_rc={rc} gate={o.get("gate")} 2단계행={st2}')
    prc, rc, st2, raw, o = run(a.container, a.out, 'R3_V3', RECENT, 'v3')
    s = stops(o)
    rec(prc == 0 and rc == 10 and st2 == 0 and all(s.get(k) is True for k in (('O', 21), ('O', 22), ('P', 20), ('P', 21))) and o['gate_bad'] == 4,
        f'R3_V3 같은 픽스처 v3: SQL STOP·금액 단계 0행 | psql_rc={prc} parse_rc={rc} gate_bad={o.get("gate_bad")} 2단계행={st2} stop={sorted(s.items())}')
    prc, rc, st2, raw, o = run(a.container, a.out, 'PAST_V3', '', 'v3')
    s = stops(o)
    rec(prc == 0 and rc == 10 and st2 == 0 and s == {('S', 100): False, ('O', 21): True, ('P', 20): True},
        f'PAST_V3 과거 종결 주문21·포지션20(v2 A 는 GO) → v3 STOP(기대 변경) | psql_rc={prc} parse_rc={rc} 2단계행={st2} stop={sorted(s.items())}')
    prc, rc, st2, raw, o = run(a.container, a.out, 'A_V3', NO_UNREG, 'v3')
    ref = open(os.path.join(V2, 'run2', 'A', 'stdout.tsv'), 'rb').read().decode('utf-8')
    same = stage2_lines(raw) == stage2_lines(ref)
    rec(prc == 0 and rc == 0 and o['gate'] == 'GO' and same and {x['id'] for x in o['orders']} == {11, 12, 13, 14, 41}
        and {p['id'] for p in o['positions']} == {7, 8} and [u['rec'] for u in o['unregistered_ids']] == ['S'] and '777777' not in raw,
        f'A_V3 등록 정상 원천: GO·2단계 {len(stage2_lines(raw))}행이 v2 run2/A 원문과 동일={same} | psql_rc={prc} parse_rc={rc}')
    lines.append(f'합계 {n} 통과 {ok} 실패 {n - ok}')
    open(os.path.join(a.out, 'tests_pg.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 0 if ok == n else 1


if __name__ == '__main__':
    sys.exit(main())
