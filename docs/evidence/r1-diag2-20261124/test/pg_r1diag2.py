"""r1-diag-2 격리 로컬 PG 1묶음(총괄 v1.234 §3-2). 운영·SSH·생산 별칭·운영 인증·실제 DB 파일·제품 시험·회귀·jar 없음.

전제: dec-read-gate-v3-20261120/setup_pg.py(98b3eab0)로 만든 --network none·tmpfs 일회용 컨테이너와 d81c3f2f V1~V96 템플릿 DB 'base'.
base 를 복제한 DB 하나에 합성 입력(fixture.sql)을 넣는다. setup 의 flyway 최소 표(version, success)는 이 복제 DB 안에서만
Flyway 실제 열 구성(installed_rank·installed_on timestamp 등)으로 바꿔 넣는다(81·87·94 설치 시각을 손계산용으로 고정).
같은 묶음 3건:
  normal     : PGOPTIONS 15s/2s 로 SQL 원문 1회 → psql rc0·stderr 없음·실제 결과가 손계산 기대 행 집합과 정확히 일치·shape_check2 rc0
  guard_14s  : statement_timeout=14s 로 같은 SQL 1회 → psql rc3·stdout 빈·stderr 에 R1D_GUARD(보호값 불일치로 중단)
  uq_short   : normal 실제 출력에서 DYNAMIC UQ 마지막 숫자 한 칸을 뺀 파일 → shape_check2 rc7·SHAPE_MISMATCH·traceback 없음
SQL 결과는 파일 모델이 만들지 않는다(model.py 사용 안 함). 기대값은 아래 EXPECT 에 손으로 적었다.
사용: python -I pg_r1diag2.py --container <이름> --out <회차 폴더(없어야 함)>
종료값: 3건 모두 기대대로 0, 아니면 1(원문 보존). 실행 환경 전제 실패 2.
"""
import argparse, hashlib, json, os, subprocess, sys
from datetime import datetime, timezone

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SQL = os.path.join(ROOT, 'sql', 'r1-diag-2.sql')
CHK = os.path.join(ROOT, 'check', 'shape_check2.py')
SETUP_SHA = '98b3eab0b28be0b9b389a4cc9ac64c03e2f22e87b6d8f23e2649ee6931d2208d'
SETUP = os.path.join(os.path.dirname(ROOT), 'dec-read-gate-v3-20261120', 'setup_pg.py')
PGO_OK = 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s'
PGO_BAD = 'PGOPTIONS=-c statement_timeout=14s -c lock_timeout=2s'
N = chr(92) + 'N'
DB = 'c_r1d2'


def ago(d):
    return f"now() - interval '{d} days'"


FIXTURE = (
    # Flyway 실제 열 구성(복제 DB 안에서만). V81 = 45일 전, V87 = 38일 전, V94 = 20일 전. 실패한 97, 버전 없는 반복 이관 1건.
    "CREATE TABLE fw_tmp AS SELECT version FROM flyway_schema_history;\n"
    "DROP TABLE flyway_schema_history;\n"
    "CREATE TABLE flyway_schema_history(installed_rank int PRIMARY KEY, version varchar(50), description varchar(200) NOT NULL,"
    " type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum int, installed_by varchar(100) NOT NULL,"
    " installed_on timestamp NOT NULL DEFAULT now(), execution_time int NOT NULL, success boolean NOT NULL);\n"
    "INSERT INTO flyway_schema_history SELECT row_number() OVER (ORDER BY version::int), version, 'x', 'SQL', 'V' || version || '__x.sql', 0, 'postgres',"
    " CASE version WHEN '81' THEN (now() AT TIME ZONE 'UTC') - interval '45 days' WHEN '87' THEN (now() AT TIME ZONE 'UTC') - interval '38 days'"
    " WHEN '94' THEN (now() AT TIME ZONE 'UTC') - interval '20 days' ELSE (now() AT TIME ZONE 'UTC') - interval '100 days' + version::int * interval '10 minutes' END,"
    " 1, true FROM fw_tmp;\n"
    "INSERT INTO flyway_schema_history VALUES (200, '97', 'fail', 'SQL', 'V97__fail.sql', 0, 'postgres', now() AT TIME ZONE 'UTC', 1, false),"
    " (201, NULL, 'rep', 'SQL', 'R__rep.sql', 0, 'postgres', now() AT TIME ZONE 'UTC', 1, true);\n"
    "DROP TABLE fw_tmp;\n"
    "INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, trading_mode) VALUES"
    " (1,'X','H1',1,1,1,'REAL'), (2,'X','H1',1,1,1,'PAPER'), (3,'X','H1',1,1,1,'WEIRD');\n"
    "INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, session_type) VALUES"
    " (10,'X','KRW-BTC','H1',1,1,1,'REAL'), (11,'X','KRW-BTC','H1',1,1,1,'PAPER');\n"
    "INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, opened_at, closed_at, exit_reason) VALUES"
    f" (1,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',1,{ago(50)},{ago(2.5)},'SL'),"          # A③×2 + P② 겹침, V81·V87 전 개시
    f" (2,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',1,{ago(40)},{ago(10.5)},'TP'),"         # A① 매수 수수료 NULL(P 아님), V87 전만
    f" (4,'KRW-BTC','LONG',1,1,2,'CLOSED','DYNAMIC',2,{ago(10)},{ago(5.5)},'tp manual edit')," # P② 부분 확정, 세션 PAPER(혼입 모사), 코드 형식 밖
    f" (5,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',99,{ago(30)},{ago(25.5)},'PHANTOM'),"   # P④ 주문 없음, 세션 행 없음
    f" (6,'KRW-BTC','LONG',1,1,0,'CLOSED','DYNAMIC',3,{ago(4)},{ago(3.5)},'ENTRY_REJECTED'),"  # 무거래 확정(제외)
    f" (16,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',3,{ago(4)},{ago(3.5)},'ENTRY_REJECTED')," # P④ size≠0, 세션 모드 OTHER
    f" (7,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',1,{ago(9)},{ago(8.5)},'TIME_STOP'),"    # BUY FAILED 체결 0(제외)
    f" (8,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',1,NULL,{ago(12.5)},'CANCELLED'),"      # P④ 취소 미확정, opened_at NULL
    f" (9,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',1,{ago(16)},{ago(15.5)},NULL),"        # P④ 체결 수량 NULL·상태 FILLED·미반영 매도
    f" (10,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',1,{ago(40)},{ago(35.5)},'PHANTOM'),"  # 30일 창 밖(제외)
    f" (11,'KRW-BTC','LONG',1,1,1,'CLOSED','DYN_PAPER',2,{ago(2)},{ago(1.5)},'PHANTOM'),"  # DYN_PAPER(제외)
    f" (12,'KRW-BTC','LONG',1,1,1,'OPEN','DYNAMIC',1,{ago(1)},NULL,NULL),"                 # OPEN(제외)
    f" (13,'KRW-BTC','LONG',1,1,1,'CLOSED','DYNAMIC',NULL,{ago(3)},{ago(2.5)},'PHANTOM')," # session_id NULL(제외)
    f" (21,'KRW-BTC','LONG',1,1,1,'CLOSED','LIVE',10,{ago(5)},{ago(4.5)},'TP'),"           # LIVE 정상(제외)
    f" (22,'KRW-BTC','LONG',1,1,1,'CLOSED','LIVE',11,{ago(7)},{ago(6.5)},'TP'),"           # LIVE A③ 수량 0 + P② 겹침, 세션 PAPER
    f" (23,'KRW-BTC','LONG',1,1,0,'CLOSED','LIVE',10,{ago(1)},{ago(0.5)},'ENTRY_REJECTED');\n"  # 무거래 확정(제외)
    'INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, filled_quantity,'
    ' applied_executed_funds, applied_paid_fee, sell_applied_at, fee_source, cancel_confirmed_at) VALUES'
    " (101,1,'KRW-BTC','BUY','MARKET',1,'FILLED','DYNAMIC',1,1,100,1,NULL,NULL,NULL),"
    f" (102,1,'KRW-BTC','SELL','MARKET',1,'FILLED','DYNAMIC',1,1,NULL,1,{ago(2.5)},NULL,NULL),"
    f" (103,1,'KRW-BTC','SELL','MARKET',1,'FILLED','DYNAMIC',1,1,NULL,NULL,{ago(1.5)},NULL,NULL),"
    " (201,2,'KRW-BTC','BUY','MARKET',1,'FILLED','DYNAMIC',1,1,100,NULL,NULL,NULL,NULL),"
    f" (202,2,'KRW-BTC','SELL','MARKET',1,'FILLED','DYNAMIC',1,1,100,1,{ago(10.5)},NULL,NULL),"
    f" (301,NULL,'KRW-BTC','SELL','MARKET',1,'FILLED','DYNAMIC',1,1,100,1,{ago(20.5)},NULL,NULL),"
    " (302,NULL,'KRW-BTC','SELL','MARKET',1,'FILLED','DYNAMIC',1,1,100,1,now() + interval '1 hour',NULL,NULL),"  # 시계 차이 모사(D30P)
    " (401,4,'KRW-BTC','BUY','MARKET',2,'FILLED','DYNAMIC',2,2,200,2,NULL,NULL,NULL),"
    f" (402,4,'KRW-BTC','SELL','MARKET',1,'FILLED','DYNAMIC',2,1,100,1,{ago(5.5)},NULL,NULL),"
    " (701,7,'KRW-BTC','BUY','MARKET',1,'FAILED','DYNAMIC',1,0,NULL,NULL,NULL,NULL,NULL),"
    " (801,8,'KRW-BTC','BUY','MARKET',1,'CANCELLED','DYNAMIC',1,0,NULL,NULL,NULL,'UPBIT',NULL),"
    " (901,9,'KRW-BTC','BUY','MARKET',1,'FILLED','DYNAMIC',1,NULL,NULL,NULL,NULL,NULL,NULL),"
    " (902,9,'KRW-BTC','SELL','MARKET',1,'PENDING','DYNAMIC',1,NULL,NULL,NULL,NULL,NULL,NULL),"
    f" (1001,10,'KRW-BTC','SELL','MARKET',1,'FILLED','DYNAMIC',1,1,NULL,NULL,{ago(35.5)},NULL,NULL),"
    f" (1101,11,'KRW-BTC','SELL','MARKET',1,'FILLED','DYN_PAPER',2,1,NULL,NULL,{ago(1.5)},NULL,NULL),"
    f" (1201,NULL,'KRW-BTC','SELL','MARKET',1,'FILLED','DYNAMIC',NULL,1,NULL,NULL,{ago(1.5)},NULL,NULL),"
    " (1202,12,'KRW-BTC','BUY','MARKET',1,'FILLED','DYNAMIC',1,1,100,1,NULL,NULL,NULL),"
    " (2101,21,'KRW-BTC','BUY','MARKET',1,'FILLED','LIVE',10,1,100,1,NULL,NULL,NULL),"
    f" (2102,21,'KRW-BTC','SELL','MARKET',1,'FILLED','LIVE',10,1,100,1,{ago(4.5)},NULL,NULL),"
    " (2201,22,'KRW-BTC','BUY','MARKET',1,'FILLED','LIVE',11,1,100,1,NULL,NULL,NULL),"
    f" (2202,22,'KRW-BTC','SELL','MARKET',1,'FILLED','LIVE',11,0,0,0,{ago(6.5)},NULL,NULL);\n"
)

# 손계산 기대(META·REF·END 제외 전 행). 짧은 튜플은 9열로 \N 을 채워 비교한다(ORIG 는 3열 그대로).
E = []
for k, o in (('LIVE', 2), ('DYNAMIC', 11)):
    E.append(('ORIG', k, str(o)))
E += [('SPLIT', 'LIVE', '1', '1', '2'), ('SPLIT', 'DYNAMIC', '5', '6', '11')]
AM = {'LIVE': {'PAPER': 1}, 'DYNAMIC': {'REAL': 5}}
PM = {'LIVE': {'PAPER': 1}, 'DYNAMIC': {'REAL': 3, 'PAPER': 1, 'NO_ROW': 1, 'OTHER': 1}}
AX = {'LIVE': {'FIELDS_ONLY': 1}, 'DYNAMIC': {'FIELDS_ONLY': 2, 'COST_ONLY': 3}}
AF = {'LIVE': {'F_QTY_NULL_OR_LE0': 1},
      'DYNAMIC': {'F_FUNDS_NULL': 2, 'F_FEE_NULL': 1, 'C_NO_POSITION': 2, 'C_BUY_COST_NULL': 1}}
PX = {'LIVE': {'QTY_SHORT': 1}, 'DYNAMIC': {'QTY_SHORT': 2, 'NO_SOURCE': 4}}
PF = {'LIVE': {'Q_PARTIAL_SETTLED': 1},
      'DYNAMIC': {'Q_NO_SETTLED_SELL': 1, 'Q_PARTIAL_SETTLED': 1, 'Q_APPLIED_SELL_FUNDS_NULL': 1, 'N_NO_ORDERS': 2,
                  'N_ER_SIZE_NOT_ZERO': 1, 'N_NO_BUY': 2, 'N_BUY_QTY_NULL': 1, 'N_BUY_NOT_FAILED_OR_CANCELLED': 1,
                  'N_BUY_CANCEL_UNCONFIRMED': 1, 'N_UNAPPLIED_SELL': 1}}
AGE = {('LIVE', 'A'): {'D21_29': 1}, ('LIVE', 'P'): {'D21_29': 1},
       ('DYNAMIC', 'A'): {'D07_13': 1, 'D14_20': 1, 'D21_29': 2, 'D30P': 1},
       ('DYNAMIC', 'P'): {'D00_06': 1, 'D14_20': 2, 'D21_29': 3}}
for tag, tab, keys in (('AM', AM, ('REAL', 'PAPER', 'NO_ROW', 'OTHER')), ('PM', PM, ('REAL', 'PAPER', 'NO_ROW', 'OTHER')),
                       ('AX', AX, ('FIELDS_ONLY', 'COST_ONLY', 'FIELDS_AND_COST')),
                       ('AF', AF, ('F_FUNDS_NULL', 'F_FEE_NULL', 'F_QTY_NULL_OR_LE0', 'C_NO_POSITION', 'C_NO_FILLED_BUY',
                                   'C_BUY_COST_NULL', 'C_BUY_QTY_NULL')),
                       ('PX', PX, ('QTY_SHORT', 'NO_SOURCE', 'BOTH')),
                       ('PF', PF, ('Q_NO_SETTLED_SELL', 'Q_PARTIAL_SETTLED', 'Q_APPLIED_SELL_FUNDS_NULL', 'Q_UNAPPLIED_SELL',
                                   'Q_NO_SELL_ORDER', 'N_NO_ORDERS', 'N_ER_SIZE_NOT_ZERO', 'N_NO_BUY', 'N_BUY_QTY_NULL',
                                   'N_BUY_NOT_FAILED_OR_CANCELLED', 'N_BUY_CANCEL_UNCONFIRMED', 'N_UNAPPLIED_SELL'))):
    for k in ('LIVE', 'DYNAMIC'):
        for x in keys:
            E.append((tag, k, x, str(tab[k].get(x, 0))))
E += [('PE', 'LIVE', 'TP', '1')]
E += [('PE', 'DYNAMIC', g, '1') for g in ('SL', '(nonconforming)', 'PHANTOM', 'ENTRY_REJECTED', 'CANCELLED', '(null)')]
#        kind       a  a_dist a_null p  both union items
E += [('UQ', 'LIVE', '1', '1', '0', '1', '1', '1', '1'), ('UQ', 'DYNAMIC', '5', '2', '2', '6', '1', '7', '9')]
for (k, br), tab in AGE.items():
    for b in ('D00_06', 'D07_13', 'D14_20', 'D21_29', 'D30P'):
        E.append(('AGE', k, br, b, str(tab.get(b, 0))))
#        kind  branch before_v81 before_v87 opened_null
E += [('LEG', 'LIVE', 'A', '0', '0', '0'), ('LEG', 'LIVE', 'P', '0', '0', '0'),
      ('LEG', 'DYNAMIC', 'A', '2', '3', '0'), ('LEG', 'DYNAMIC', 'P', '1', '1', '1')]
EXPECT = sorted(tuple(r) if r[0] == 'ORIG' else tuple(r) + (N,) * (9 - len(r)) for r in E)


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def sha(b):
    return hashlib.sha256(b).hexdigest()


def wb(p, b):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb') as f:
        f.write(b if isinstance(b, bytes) else b.encode('utf-8'))


def utc():
    return datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.%fZ')


def psql(cont, db, args, stdin=None, env=()):
    e = [x for k in env for x in ('-e', k)]
    return sh(['docker', 'exec', '-i', *e, cont, 'psql', '-X', '-q', '-U', 'postgres', '-d', db, *args], input=stdin)


def run_sql(cont, sqlb, d, pgo):
    t0 = utc()
    p = psql(cont, DB, ['-v', 'ON_ERROR_STOP=1', '-f', '-'], stdin=sqlb, env=(pgo,))
    t1 = utc()
    wb(os.path.join(d, 'sql-input.sha256'), sha(sqlb) + '\n')
    wb(os.path.join(d, 'out.tsv'), p.stdout)
    wb(os.path.join(d, 'out.stderr'), p.stderr)
    wb(os.path.join(d, 'psql.rc'), f'{p.returncode}\n')
    wb(os.path.join(d, 'pgoptions.txt'), pgo + '\n')
    wb(os.path.join(d, 'collected.txt'), f'pc-start {t0}\npc-end {t1}\n')
    return p


def run_chk(path, d):
    c = sh([sys.executable, '-I', CHK, path])
    wb(os.path.join(d, 'check.stdout'), c.stdout)
    wb(os.path.join(d, 'check.stderr'), c.stderr)
    wb(os.path.join(d, 'check.rc'), f'{c.returncode}\n')
    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--container', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    net = sh(['docker', 'inspect', '-f', '{{.HostConfig.NetworkMode}}', a.container]).stdout.decode().strip()
    if net != 'none':
        print('🔴 컨테이너가 네트워크 없는 격리가 아님:', net)
        return 2
    if sha(open(SETUP, 'rb').read()) != SETUP_SHA:
        print('🔴 setup_pg.py 해시 불일치')
        return 2
    sqlb, chkb = open(SQL, 'rb').read(), open(CHK, 'rb').read()
    os.makedirs(a.out, exist_ok=False)
    r = psql(a.container, 'postgres', ['-v', 'ON_ERROR_STOP=1', '-c', f'CREATE DATABASE {DB} TEMPLATE base'])
    if r.returncode:
        print('🔴 DB 복제 실패', r.stderr)
        return 2
    wb(os.path.join(a.out, 'fixture.sql'), FIXTURE)
    r = psql(a.container, DB, ['-v', 'ON_ERROR_STOP=1', '-f', '-'], stdin=FIXTURE.encode())
    wb(os.path.join(a.out, 'fixture.rc'), f'{r.returncode}\n{r.stderr.decode("utf-8", "replace")}')
    if r.returncode:
        print('🔴 픽스처 실패', r.stderr.decode('utf-8', 'replace'))
        return 2
    wb(os.path.join(a.out, 'expected.tsv'), ''.join('\t'.join(x) + '\n' for x in EXPECT))
    cnt_sql = 'SELECT (SELECT count(*) FROM "order") || \' \' || (SELECT count(*) FROM position) || \' \' || (SELECT count(*) FROM flyway_schema_history)'
    before = psql(a.container, DB, ['-At', '-c', cnt_sql]).stdout.decode().strip()

    # 1) 정상
    d1 = os.path.join(a.out, 'normal')
    p1 = run_sql(a.container, sqlb, d1, PGO_OK)
    out = p1.stdout.decode('utf-8', 'replace')
    rows = [ln.split('\t') for ln in out.split('\n') if ln]
    body = sorted(tuple(x) for x in rows if x[0] not in ('META', 'REF', 'END'))
    wb(os.path.join(d1, 'actual-body.tsv'), ''.join('\t'.join(x) + '\n' for x in body))
    missing = [x for x in EXPECT if x not in body]
    extra = [x for x in body if x not in EXPECT]
    meta = rows[0] if rows else []
    refs = [x for x in rows if x[0] == 'REF']
    c1 = run_chk(os.path.join(d1, 'out.tsv'), d1)
    after = psql(a.container, DB, ['-At', '-c', cnt_sql]).stdout.decode().strip()
    normal = {
        'psql_rc0': p1.returncode == 0,
        'stderr_empty': p1.stderr == b'',
        'meta_r1_diag_2_flyway96_rr_15s_2s': len(meta) == 10 and meta[1] == 'r1-diag-2' and meta[5] == '96'
                                             and meta[6] == 'repeatable read' and meta[8] == '15s' and meta[9] == '2s',
        'ref_81_87_94': [x[1] for x in refs] == ['81', '87', '94'],
        'end_last': bool(rows) and rows[-1][0] == 'END' and len(rows[-1]) == 2,
        'body_equals_expected': not missing and not extra and len(body) == len(EXPECT),
        'uq_9_columns_with_union_items': [x for x in rows if x[0] == 'UQ' and x[1] == 'DYNAMIC']
                                         == [['UQ', 'DYNAMIC', '5', '2', '2', '6', '1', '7', '9']],
        'nonconforming_text_not_exported': 'manual edit' not in out,
        'check_rc0_shape_ok': c1.returncode == 0 and c1.stdout.decode().strip() == 'SHAPE_OK' and c1.stderr == b'',
        'db_rows_unchanged': before == after and before != '',
    }
    wb(os.path.join(d1, 'diff.json'), json.dumps({'missing': missing, 'extra': extra}, ensure_ascii=False, indent=1))

    # 2) 보호값 불일치 → SQL 중단
    d2 = os.path.join(a.out, 'guard_14s')
    p2 = run_sql(a.container, sqlb, d2, PGO_BAD)
    guard = {
        'psql_rc3': p2.returncode == 3,
        'stdout_empty': p2.stdout == b'',
        'stderr_r1d_guard': b'R1D_GUARD' in p2.stderr,
    }

    # 3) 실제 정상 출력에서 UQ 숫자 한 칸을 뺀 파일 → 검사기 rc7
    d3 = os.path.join(a.out, 'uq_short')
    lines = p1.stdout.decode('utf-8').split('\n')
    cut = 0
    for i, ln in enumerate(lines):
        f = ln.split('\t')
        if f[:2] == ['UQ', 'DYNAMIC']:
            lines[i] = '\t'.join(f[:-1])
            cut += 1
    shortp = os.path.join(d3, 'uq-short.tsv')
    wb(shortp, '\n'.join(lines))
    c3 = run_chk(shortp, d3)
    so = c3.stdout.decode('utf-8', 'replace')
    uqs = {
        'one_cell_removed': cut == 1,
        'check_rc7': c3.returncode == 7,
        'shape_mismatch_column_count': so.startswith('SHAPE_MISMATCH') and '열 수 8 ≠ 9' in so,
        'no_traceback': b'Traceback' not in c3.stderr and c3.stderr == b'',
    }

    ok = all(normal.values()) and all(guard.values()) and all(uqs.values())
    show = sh(['docker', 'exec', '-e', PGO_OK, a.container, 'psql', '-X', '-At', '-U', 'postgres', '-d', DB,
               '-c', 'SHOW statement_timeout', '-c', 'SHOW lock_timeout']).stdout.decode().split()
    ver = sh(['docker', 'exec', a.container, 'psql', '-X', '-At', '-U', 'postgres', '-c', 'select version()']).stdout.decode().strip()
    summary = {'ok': ok, 'normal': normal, 'guard_14s': guard, 'uq_short': uqs,
               'container_network': net, 'server': ver, 'pgoptions_show': show,
               'db_counts_before_after': [before, after], 'expected_rows': len(EXPECT), 'actual_body_rows': len(body),
               'meta': meta[:2] + meta[5:] if meta else [], 'db_now': meta[2] if len(meta) > 2 else None,
               'sql_sha256': sha(sqlb), 'check_sha256': sha(chkb),
               'test_sha256': sha(open(os.path.abspath(__file__), 'rb').read()), 'setup_sha256': SETUP_SHA,
               'production_calls': 0, 'ssh_calls': 0}
    wb(os.path.join(a.out, 'summary.json'), json.dumps(summary, ensure_ascii=False, indent=1) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))
    sys.exit(main())
