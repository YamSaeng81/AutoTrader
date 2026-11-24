"""r1-diag-4 격리 로컬 PG 1묶음(총괄 v1.239 §3-1). 운영·SSH·생산 별칭·운영 인증·실제 DB 파일·제품 시험·회귀·jar 없음.

전제: dec-read-gate-v3-20261120/setup_pg.py(98b3eab0)로 만든 --network none·tmpfs 일회용 컨테이너와 d81c3f2f V1~V96 템플릿 DB 'base'.
사례마다 base 를 복제한 DB 하나에 합성 입력을 넣는다(Flyway 표는 복제 DB 안에서만 실제 열 구성으로 바꾼다 — pg_r1diag2 d98f3f85 와 같은 식).
같은 묶음:
  normal       KNOWN 3(상한 1µs 전 포함)·OTHER 5(상한 정각·PAPER 세션·QTY_SHORT·11-10 개설·A pid NULL) / LIVE A1·P2, 옛 고아, 세션 상태·모드 혼합
  empty        R1 대상 0(무거래 확정 행만) → EXP 세 칸 \\N
  minute_edge  KNOWN 마지막 경계가 정각 분, OTHER 경계가 분 직전(.999999) — 세션 TimeZone=Asia/Seoul 로 실행
  new_ap       직전 db_now 와 같은 시각(NEW 아님)·30초 뒤 A·1분 뒤 P·1µs 뒤 LIVE P
  running_real REAL 실행 중 세션·열린/정리 중 포지션·미해결 제출·활성 주문·정리 대기, 소유 불명 4종·PAPER/DYN_PAPER·옛 고아·상태 불명
  guard_14s    statement_timeout=14s → psql rc3·stdout 빈·R1D_GUARD
  query_fail   position_cleanup 이 없는 DB → psql rc3·부분 stdout → 검사기 rc7
  mutations    normal 실제 출력 변형 7종 → 검사기 rc7·traceback 없음
SQL 결과는 파일 모델이 만들지 않는다. 기대값은 아래 사례 표에 손으로 적었다(EXP 시각만 t + 30일을 파이썬으로 더한다).
사용: python -I pg_r1diag4.py --container <이름> --out <회차 폴더(없어야 함)>
종료값: 전부 기대대로 0, 아니면 1(원문 보존). 실행 환경 전제 실패 2.
"""
import argparse, hashlib, json, os, subprocess, sys
from datetime import datetime, timedelta, timezone

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SQL = os.path.join(ROOT, 'sql', 'r1-diag-4.sql')
CHK = os.path.join(ROOT, 'check', 'shape_check4.py')
SETUP_SHA = '98b3eab0b28be0b9b389a4cc9ac64c03e2f22e87b6d8f23e2649ee6931d2208d'
SETUP = os.path.join(os.path.dirname(ROOT), 'dec-read-gate-v3-20261120', 'setup_pg.py')
PGO_OK = 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s'
PGO_SEOUL = 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s -c TimeZone=Asia/Seoul'
PGO_BAD = 'PGOPTIONS=-c statement_timeout=14s -c lock_timeout=2s'
N = chr(92) + 'N'
PRIOR_NOW = datetime(2026, 11, 24, 4, 49, 14, 512077)

FLYWAY = (
    "CREATE TABLE fw_tmp AS SELECT version FROM flyway_schema_history;\n"
    "DROP TABLE flyway_schema_history;\n"
    "CREATE TABLE flyway_schema_history(installed_rank int PRIMARY KEY, version varchar(50), description varchar(200) NOT NULL,"
    " type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum int, installed_by varchar(100) NOT NULL,"
    " installed_on timestamp NOT NULL DEFAULT now(), execution_time int NOT NULL, success boolean NOT NULL);\n"
    "INSERT INTO flyway_schema_history SELECT row_number() OVER (ORDER BY version::int), version, 'x', 'SQL', 'V' || version || '__x.sql', 0,"
    " 'postgres', (now() AT TIME ZONE 'UTC') - interval '100 days' + version::int * interval '10 minutes', 1, true FROM fw_tmp;\n"
    "INSERT INTO flyway_schema_history VALUES (200, '97', 'fail', 'SQL', 'V97__fail.sql', 0, 'postgres', now() AT TIME ZONE 'UTC', 1, false),"
    " (201, NULL, 'rep', 'SQL', 'R__rep.sql', 0, 'postgres', now() AT TIME ZONE 'UTC', 1, true);\n"
    "DROP TABLE fw_tmp;\n"
)


def T(s):
    return f"timestamptz '{s}+00'"


def dyn(rows):
    return ("INSERT INTO dynamic_session(id, strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, trading_mode, status) VALUES "
            + ', '.join(f"({i},'X','H1',1,1,1,'{m}','{s}')" for i, m, s in rows) + ";\n")


def live(rows):
    return ("INSERT INTO live_trading_session(id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw,"
            " session_type, status) VALUES "
            + ', '.join(f"({i},'X','KRW-BTC','H1',1,1,1,'{m}','{s}')" for i, m, s in rows) + ";\n")


def pos(rows):
    # (id, status, kind, sid, size, opened, closed, exit_reason) — 시각은 SQL 식 문자열
    q = lambda x: 'NULL' if x is None else (x if x.startswith(('timestamptz', 'now()')) else f"'{x}'")
    return ("INSERT INTO position(id, coin_pair, side, entry_price, avg_price, size, status, session_kind, session_id, opened_at, closed_at, exit_reason)"
            " VALUES " + ', '.join(
                f"({i},'KRW-BTC','LONG',1,1,{sz},'{st}',{q(k)},{'NULL' if s is None else s},{q(o)},{q(c)},{q(er)})"
                for i, st, k, s, sz, o, c, er in rows) + ";\n")


def ords(rows):
    # (id, pid, side, state, kind, sid, filled, funds, fee, sell_applied)
    q = lambda x: 'NULL' if x is None else (x if isinstance(x, (int, float)) or str(x).startswith(('timestamptz', 'now()')) else f"'{x}'")
    return ('INSERT INTO "order"(id, position_id, coin_pair, side, order_type, quantity, state, session_kind, session_id, filled_quantity,'
            ' applied_executed_funds, applied_paid_fee, sell_applied_at) VALUES ' + ', '.join(
                f"({i},{q(p)},'KRW-BTC','{sd}','MARKET',1,'{st}',{q(k)},{q(s)},{q(f)},{q(fu)},{q(fe)},{q(sa)})"
                for i, p, sd, st, k, s, f, fu, fe, sa in rows) + ";\n")


def cleanup(rows):
    return ("INSERT INTO position_cleanup(position_id, session_kind, session_id, reason, pending_at, done_at) VALUES "
            + ', '.join(f"({p},'{k}',{s},'STOP',now() - interval '1 hour',{'now()' if d else 'NULL'})" for p, k, s, d in rows) + ";\n")


OLD = "now() - interval '40 days'"
# ── 사례 표: fixture SQL 과 손 기대값. r1 = [(kind, branch, t 'YYYY-MM-DD HH:MM:SS.ffffff', class)] ──
CASES = {}
CASES['normal'] = dict(
    fixture=dyn([(1, 'REAL', 'STOPPED'), (2, 'PAPER', 'STOPPED'), (3, 'REAL', 'EMERGENCY_STOPPED'), (4, 'REAL', 'WEIRD_ST'), (5, 'WEIRD', 'STOPPED')])
    + live([(10, 'REAL', 'CREATED'), (11, 'PAPER', 'STOPPED'), (12, 'REAL', 'DELETED')])
    + pos([(1, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-10-27 09:59:00'), T('2026-10-27 10:00:00'), None),            # KNOWN
           (2, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-10-30 03:15:42'), T('2026-10-30 03:15:42.123456'), None),     # KNOWN
           (3, 'CLOSED', 'DYNAMIC', 3, 0, T('2026-11-01 04:49:00'), T('2026-11-01 04:49:14.512076'), None),     # KNOWN(상한 1µs 전)
           (4, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-11-01 04:49:00'), T('2026-11-01 04:49:14.512077'), None),     # OTHER(상한 정각)
           (5, 'CLOSED', 'DYNAMIC', 2, 0, T('2026-10-29 00:00:00'), T('2026-10-29 00:00:00'), None),            # OTHER(세션 PAPER)
           (6, 'CLOSED', 'DYNAMIC', 1, 1, T('2026-10-28 11:00:00'), T('2026-10-28 12:00:00'), 'TP'),            # OTHER(QTY_SHORT)
           (7, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-11-10 08:00:00'), T('2026-11-10 08:00:00'), None),            # OTHER(KNOWN 상한 뒤)
           (8, 'CLOSED', 'LIVE', 10, 0, T('2026-11-05 00:00:00'), T('2026-11-05 00:00:00'), None),              # LIVE P
           (9, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-10-28 00:00:00'), T('2026-10-28 00:00:00'), 'ENTRY_REJECTED'),# 제외(무거래 확정)
           (10, 'CLOSED', 'DYNAMIC', 1, 0, OLD, OLD, None),                                                    # 제외(창 밖)
           (12, 'CLOSED', 'LIVE', 10, 1, T('2026-11-03 06:00:00'), T('2026-11-03 06:07:08.000001'), 'TP'),      # LIVE P(QTY_SHORT)
           (13, 'CLOSED', 'LIVE', None, 1, OLD, OLD, 'TP')])                                                   # 옛 고아(R1 아님)
    + ords([(601, 6, 'BUY', 'FILLED', 'DYNAMIC', 1, 1, 100, 1, None),
            (701, None, 'SELL', 'FILLED', 'DYNAMIC', 1, 1, 100, 1, T('2026-11-02 00:00:00')),               # DYNAMIC A(pid NULL)
            (1201, 12, 'BUY', 'FILLED', 'LIVE', 10, 1, 100, 1, None),
            (1202, 12, 'SELL', 'FILLED', 'LIVE', 10, 1, None, 1, T('2026-11-03 06:07:08.000001')),           # LIVE A(대금 NULL)
            (1301, None, 'BUY', 'FILLED', 'LIVE', None, 1, 100, 1, None),                                   # 옛 고아 TERMINAL
            (1302, None, 'BUY', 'CANCELLED', 'LIVE', None, 0, None, None, None)]),                          # 옛 고아 TERMINAL
    r1=[('DYNAMIC', 'P', '2026-10-27 10:00:00.000000', 'KNOWN'), ('DYNAMIC', 'P', '2026-10-30 03:15:42.123456', 'KNOWN'),
        ('DYNAMIC', 'P', '2026-11-01 04:49:14.512076', 'KNOWN'), ('DYNAMIC', 'P', '2026-11-01 04:49:14.512077', 'OTHER'),
        ('DYNAMIC', 'P', '2026-10-29 00:00:00.000000', 'OTHER'), ('DYNAMIC', 'P', '2026-10-28 12:00:00.000000', 'OTHER'),
        ('DYNAMIC', 'P', '2026-11-10 08:00:00.000000', 'OTHER'), ('DYNAMIC', 'A', '2026-11-02 00:00:00.000000', 'OTHER'),
        ('LIVE', 'P', '2026-11-05 00:00:00.000000', 'OTHER'), ('LIVE', 'P', '2026-11-03 06:07:08.000001', 'OTHER'),
        ('LIVE', 'A', '2026-11-03 06:07:08.000001', 'OTHER')],
    rs={('LIVE', 'CREATED'): 1, ('LIVE', 'DELETED'): 1, ('DYNAMIC', 'STOPPED'): 1, ('DYNAMIC', 'EMERGENCY_STOPPED'): 1, ('DYNAMIC', 'OTHER'): 1},
    rm={('LIVE', 'REAL'): 2, ('LIVE', 'PAPER'): 1, ('DYNAMIC', 'REAL'): 3, ('DYNAMIC', 'PAPER'): 1, ('DYNAMIC', 'OTHER'): 1},
    rx={}, ow={}, ust={}, orph={('ORDER', 'TERMINAL'): 2, ('POSITION', 'CLOSED'): 1}, pgo=PGO_OK)
CASES['empty'] = dict(
    fixture=dyn([(1, 'REAL', 'STOPPED')])
    + pos([(9, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-11-20 00:00:00'), T('2026-11-20 00:00:00'), 'ENTRY_REJECTED')]),
    r1=[], rs={('DYNAMIC', 'STOPPED'): 1}, rm={('DYNAMIC', 'REAL'): 1}, rx={}, ow={}, ust={}, orph={}, pgo=PGO_OK)
CASES['minute_edge'] = dict(
    fixture=dyn([(1, 'REAL', 'STOPPED')])
    + pos([(1, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-10-28 00:00:00'), T('2026-10-28 00:00:00'), None),
           (2, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-10-31 23:58:00'), T('2026-10-31 23:59:00'), None),
           (3, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-11-05 12:34:00'), T('2026-11-05 12:34:59.999999'), None)]),
    r1=[('DYNAMIC', 'P', '2026-10-28 00:00:00.000000', 'KNOWN'), ('DYNAMIC', 'P', '2026-10-31 23:59:00.000000', 'KNOWN'),
        ('DYNAMIC', 'P', '2026-11-05 12:34:59.999999', 'OTHER')],
    rs={('DYNAMIC', 'STOPPED'): 1}, rm={('DYNAMIC', 'REAL'): 1}, rx={}, ow={}, ust={}, orph={}, pgo=PGO_SEOUL)
CASES['new_ap'] = dict(
    fixture=dyn([(1, 'REAL', 'STOPPED')]) + live([(10, 'REAL', 'STOPPED')])
    + pos([(1, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-10-30 00:00:00'), T('2026-10-30 00:00:00'), None),
           (3, 'CLOSED', 'DYNAMIC', 1, 0, T('2026-11-24 04:50:00'), T('2026-11-24 04:50:14.512077'), None),
           (4, 'CLOSED', 'LIVE', 10, 0, T('2026-11-24 04:49:00'), T('2026-11-24 04:49:14.512078'), None)])
    + ords([(1, None, 'SELL', 'FILLED', 'DYNAMIC', 1, 1, 100, 1, T('2026-11-24 04:49:14.512077')),
            (2, None, 'SELL', 'FILLED', 'DYNAMIC', 1, 1, 100, 1, T('2026-11-24 04:49:44'))]),
    r1=[('DYNAMIC', 'P', '2026-10-30 00:00:00.000000', 'KNOWN'), ('DYNAMIC', 'P', '2026-11-24 04:50:14.512077', 'OTHER'),
        ('LIVE', 'P', '2026-11-24 04:49:14.512078', 'OTHER'), ('DYNAMIC', 'A', '2026-11-24 04:49:14.512077', 'OTHER'),
        ('DYNAMIC', 'A', '2026-11-24 04:49:44.000000', 'OTHER')],
    rs={('DYNAMIC', 'STOPPED'): 1, ('LIVE', 'STOPPED'): 1}, rm={('DYNAMIC', 'REAL'): 1, ('LIVE', 'REAL'): 1},
    rx={}, ow={}, ust={}, orph={}, pgo=PGO_OK)
CASES['running_real'] = dict(
    fixture=dyn([(1, 'REAL', 'RUNNING'), (2, 'REAL', 'PAUSED'), (5, 'WEIRD', 'RUNNING')])
    + live([(10, 'REAL', 'RUNNING'), (11, 'PAPER', 'RUNNING')])
    + pos([(1, 'OPEN', 'LIVE', 10, 1, 'now()', None, None), (2, 'CLOSING', 'LIVE', 10, 1, 'now()', None, None),
           (3, 'OPEN', 'DYNAMIC', 1, 1, 'now()', None, None), (4, 'OPEN', 'LIVE', 11, 1, 'now()', None, None),
           (5, 'OPEN', 'DYN_PAPER', 2, 1, 'now()', None, None), (6, 'OPEN', 'WEIRDK', 1, 1, 'now()', None, None),
           (7, 'OPEN', 'LIVE', 999, 1, 'now()', None, None), (8, 'OPEN', 'LIVE', None, 1, 'now()', None, None),
           (9, 'CLOSING', 'DYNAMIC', 5, 1, 'now()', None, None), (10, 'BOGUS', 'LIVE', 10, 1, 'now()', None, None)])
    + ords([(1, 1, 'BUY', 'UNKNOWN', 'LIVE', 10, None, None, None, None), (2, 2, 'SELL', 'SUBMITTED', 'LIVE', 10, None, None, None, None),
            (3, 3, 'BUY', 'INTENT_READY', 'DYNAMIC', 1, None, None, None, None), (4, None, 'BUY', 'PARTIAL_FILLED', 'DYNAMIC', 2, None, None, None, None),
            (5, 7, 'BUY', 'PENDING', 'LIVE', 999, None, None, None, None), (6, 8, 'BUY', 'PENDING', 'LIVE', None, None, None, None, None),
            (7, 9, 'SELL', 'SUBMITTING', 'DYNAMIC', 5, None, None, None, None), (8, None, 'BUY', 'BOGUS', 'LIVE', 10, None, None, None, None),
            (9, None, 'BUY', 'FILLED', 'LIVE', None, 1, 100, 1, None), (10, None, 'BUY', 'WEIRD', 'LIVE', None, None, None, None, None)])
    + cleanup([(2, 'LIVE', 10, False), (9, 'DYNAMIC', 5, False), (7, 'LIVE', 999, False), (1, 'LIVE', 10, True)]),
    r1=[], rs={('LIVE', 'RUNNING'): 1, ('DYNAMIC', 'RUNNING'): 1, ('DYNAMIC', 'PAUSED'): 1},
    rm={('LIVE', 'REAL'): 1, ('LIVE', 'PAPER'): 1, ('DYNAMIC', 'REAL'): 2, ('DYNAMIC', 'OTHER'): 1},
    rx={('LIVE', 'POS_OPEN'): 1, ('LIVE', 'POS_CLOSING'): 1, ('LIVE', 'ORD_UNRESOLVED_SUBMIT'): 1, ('LIVE', 'ORD_ACTIVE_OTHER'): 1,
        ('LIVE', 'CLEANUP_PENDING'): 1, ('DYNAMIC', 'POS_OPEN'): 1, ('DYNAMIC', 'ORD_UNRESOLVED_SUBMIT'): 1, ('DYNAMIC', 'ORD_ACTIVE_OTHER'): 1},
    ow={('PAPER', 'POS_OPEN'): 1, ('DYN_PAPER', 'POS_OPEN'): 1, ('KIND_OTHER', 'POS_OPEN'): 1,
        ('NO_ROW', 'POS_OPEN'): 1, ('NO_ROW', 'ORD_ACTIVE_OTHER'): 1, ('NO_ROW', 'CLEANUP_PENDING'): 1,
        ('NO_SESSION_ID', 'POS_OPEN'): 1, ('NO_SESSION_ID', 'ORD_ACTIVE_OTHER'): 1,
        ('MODE_OTHER', 'POS_CLOSING'): 1, ('MODE_OTHER', 'ORD_UNRESOLVED_SUBMIT'): 1, ('MODE_OTHER', 'CLEANUP_PENDING'): 1},
    ust={'ORDER': 2, 'POSITION': 1},
    orph={('ORDER', 'ACTIVE'): 1, ('ORDER', 'TERMINAL'): 1, ('ORDER', 'STATE_OTHER'): 1, ('POSITION', 'ACTIVE'): 1}, pgo=PGO_OK)
# 손 기대 TOT(running_real): POS_OPEN 7 · POS_CLOSING 2 · ORD_UNRESOLVED_SUBMIT 3 · ORD_ACTIVE_OTHER 4 · CLEANUP_PENDING 3
TOT_HAND = {'running_real': {'POS_OPEN': 7, 'POS_CLOSING': 2, 'ORD_UNRESOLVED_SUBMIT': 3, 'ORD_ACTIVE_OTHER': 4, 'CLEANUP_PENDING': 3}}

KINDS = ('LIVE', 'DYNAMIC')
STATUSES = ('CREATED', 'RUNNING', 'PAUSED', 'STOPPED', 'EMERGENCY_STOPPED', 'DELETED', 'OTHER')
ITEMS = ('POS_OPEN', 'POS_CLOSING', 'ORD_UNRESOLVED_SUBMIT', 'ORD_ACTIVE_OTHER', 'CLEANUP_PENDING')
OWNERS = ('PAPER', 'DYN_PAPER', 'NO_SESSION_ID', 'KIND_OTHER', 'NO_ROW', 'MODE_OTHER')


def fmt(d):
    return d.strftime('%Y-%m-%dT%H:%M:%S.%f') + '+00:00'


def expect(c):
    E = []
    pad = lambda r: tuple(r) + (N,) * (7 - len(r))
    for k in KINDS:
        rr = [x for x in c['r1'] if x[0] == k]
        a = sum(1 for x in rr if x[1] == 'A'); p = sum(1 for x in rr if x[1] == 'P')
        E.append(('ORIG', k, str(a + p)))
        E.append(pad(('SPLIT', k, str(a), str(p), str(a + p))))
        for cl in ('KNOWN', 'OTHER', 'ALL'):
            sel = [datetime.strptime(x[2], '%Y-%m-%d %H:%M:%S.%f') + timedelta(days=30) for x in rr if cl == 'ALL' or x[3] == cl]
            if not sel:
                E.append(pad(('EXP', k, cl, '0')))
            else:
                last = max(sel)
                E.append(('EXP', k, cl, str(len(sel)), fmt(min(sel)), fmt(last), fmt(last.replace(second=0, microsecond=0) + timedelta(minutes=1))))
        for b in ('A', 'P'):
            E.append(pad(('NEW', k, b, str(sum(1 for x in rr if x[1] == b and datetime.strptime(x[2], '%Y-%m-%d %H:%M:%S.%f') > PRIOR_NOW)))))
        E += [pad(('RS', k, s, str(c['rs'].get((k, s), 0)))) for s in STATUSES]
        E += [pad(('RM', k, m, str(c['rm'].get((k, m), 0)))) for m in ('REAL', 'PAPER', 'OTHER')]
        E += [pad(('RX', k, i, str(c['rx'].get((k, i), 0)))) for i in ITEMS]
    E += [pad(('OW', w, i, str(c['ow'].get((w, i), 0)))) for w in OWNERS for i in ITEMS]
    tot = TOT_HAND.get(c['name'], {})
    E += [pad(('TOT', i, str(tot.get(i, 0)))) for i in ITEMS]
    E += [pad(('UST', t, str(c['ust'].get(t, 0)))) for t in ('ORDER', 'POSITION')]
    E += [pad(('ORPH', t, g, str(c['orph'].get((t, g), 0)))) for t, g in
          (('ORDER', 'ACTIVE'), ('ORDER', 'TERMINAL'), ('ORDER', 'STATE_OTHER'), ('POSITION', 'ACTIVE'), ('POSITION', 'CLOSED'), ('POSITION', 'STATUS_OTHER'))]
    return sorted(E)


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


def run_sql(cont, db, sqlb, d, pgo):
    t0 = utc()
    p = psql(cont, db, ['-v', 'ON_ERROR_STOP=1', '-f', '-'], stdin=sqlb, env=(pgo,))
    t1 = utc()
    wb(os.path.join(d, 'sql-input.sha256'), sha(sqlb) + '\n')
    wb(os.path.join(d, 'out.tsv'), p.stdout)
    wb(os.path.join(d, 'out.stderr'), p.stderr)
    wb(os.path.join(d, 'psql.rc'), f'{p.returncode}\n')
    wb(os.path.join(d, 'pgoptions.txt'), pgo + '\n')
    wb(os.path.join(d, 'collected.txt'), f'pc-start {t0}\npc-end {t1}\n')
    return p


def run_chk(path, d, tag='check'):
    c = sh([sys.executable, '-I', CHK, path])
    wb(os.path.join(d, f'{tag}.stdout'), c.stdout)
    wb(os.path.join(d, f'{tag}.stderr'), c.stderr)
    wb(os.path.join(d, f'{tag}.rc'), f'{c.returncode}\n')
    return c


def mkdb(cont, name, fixture, out):
    r = psql(cont, 'postgres', ['-v', 'ON_ERROR_STOP=1', '-c', f'CREATE DATABASE {name} TEMPLATE base'])
    if r.returncode:
        return r
    wb(os.path.join(out, 'fixture.sql'), FLYWAY + fixture)
    r = psql(cont, name, ['-v', 'ON_ERROR_STOP=1', '-f', '-'], stdin=(FLYWAY + fixture).encode())
    wb(os.path.join(out, 'fixture.rc'), f'{r.returncode}\n{r.stderr.decode("utf-8", "replace")}')
    return r


CNT = 'SELECT (SELECT count(*) FROM "order") || \' \' || (SELECT count(*) FROM position) || \' \' || (SELECT count(*) FROM position_cleanup)'


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
    res = {}
    outs = {}
    for name, c in CASES.items():
        c['name'] = name
        d = os.path.join(a.out, name)
        r = mkdb(a.container, 'c_' + name, c['fixture'], d)
        if r.returncode:
            print('🔴 픽스처 실패', name, r.stderr.decode('utf-8', 'replace'))
            return 2
        E = expect(c)
        wb(os.path.join(d, 'expected.tsv'), ''.join('\t'.join(x) + '\n' for x in E))
        before = psql(a.container, 'c_' + name, ['-At', '-c', CNT]).stdout.decode().strip()
        p = run_sql(a.container, 'c_' + name, sqlb, d, c['pgo'])
        after = psql(a.container, 'c_' + name, ['-At', '-c', CNT]).stdout.decode().strip()
        out = p.stdout.decode('utf-8', 'replace')
        rows = [ln.split('\t') for ln in out.split('\n') if ln]
        body = sorted(tuple(x) for x in rows if x[0] not in ('META', 'PRIOR', 'END'))
        wb(os.path.join(d, 'actual-body.tsv'), ''.join('\t'.join(x) + '\n' for x in body))
        missing = [x for x in E if x not in body]
        extra = [x for x in body if x not in E]
        wb(os.path.join(d, 'diff.json'), json.dumps({'missing': missing, 'extra': extra}, ensure_ascii=False, indent=1))
        meta = rows[0] if rows else []
        ck = run_chk(os.path.join(d, 'out.tsv'), d)
        res[name] = {
            'psql_rc0': p.returncode == 0,
            'stderr_empty': p.stderr == b'',
            'meta_ok': len(meta) == 10 and meta[1] == 'r1-diag-4' and meta[5] == '96' and meta[6] == 'repeatable read'
                       and meta[8] == '15s' and meta[9] == '2s' and meta[7] == ('Asia/Seoul' if c['pgo'] == PGO_SEOUL else 'UTC'),
            'prior_row': [x for x in rows if x[0] == 'PRIOR'] == [['PRIOR', '2026-11-24T04:49:14.512077+00:00',
                                                                   '2026-10-25T04:49:14.512077+00:00', '2026-11-01T04:49:14.512077+00:00']],
            'end_last': bool(rows) and rows[-1][0] == 'END' and len(rows[-1]) == 2,
            'body_equals_expected': not missing and not extra and len(body) == len(E),
            'check_rc0_shape_ok': ck.returncode == 0 and ck.stdout.decode().strip() == 'SHAPE_OK' and ck.stderr == b'',
            'db_rows_unchanged': before == after and before != '',
        }
        outs[name] = p.stdout

    # 보호값 이상
    d = os.path.join(a.out, 'guard_14s')
    p = run_sql(a.container, 'c_normal', sqlb, d, PGO_BAD)
    res['guard_14s'] = {'psql_rc3': p.returncode == 3, 'stdout_empty': p.stdout == b'', 'stderr_r1d_guard': b'R1D_GUARD' in p.stderr}

    # 조회 실패(표 없음) — 부분 출력은 검사기가 거절해야 한다
    d = os.path.join(a.out, 'query_fail')
    r = mkdb(a.container, 'c_fail', dyn([(1, 'REAL', 'STOPPED')]) + 'DROP TABLE position_cleanup;\n', d)
    if r.returncode:
        print('🔴 픽스처 실패 query_fail', r.stderr.decode('utf-8', 'replace'))
        return 2
    p = run_sql(a.container, 'c_fail', sqlb, d, PGO_OK)
    ck = run_chk(os.path.join(d, 'out.tsv'), d)
    res['query_fail'] = {'psql_rc3': p.returncode == 3, 'stderr_relation_missing': b'position_cleanup' in p.stderr and b'does not exist' in p.stderr,
                         'partial_stdout_nonempty': p.stdout != b'', 'no_end_row': b'\nEND\t' not in p.stdout,
                         'check_rc7': ck.returncode == 7 and ck.stdout.decode().startswith('SHAPE_MISMATCH') and ck.stderr == b''}

    # 검사기 변형(normal 실제 출력)
    d = os.path.join(a.out, 'mutations')
    base = outs['normal'].decode('utf-8')
    L = base.split('\n')

    def sub(pred, fn):
        o = []
        for ln in L:
            f = ln.split('\t')
            r2 = fn(f) if ln and pred(f) else f
            if r2 is not None:
                o.append('\t'.join(r2))
        return '\n'.join(o)
    muts = {
        'drop_rx_row': sub(lambda f: f[:3] == ['RX', 'LIVE', 'CLEANUP_PENDING'], lambda f: None),
        'bad_next_minute': sub(lambda f: f[:3] == ['EXP', 'DYNAMIC', 'KNOWN'], lambda f: f[:6] + ['2026-12-01T04:50:00.000000+00:00']),
        'prior_changed': sub(lambda f: f[0] == 'PRIOR', lambda f: f[:1] + ['2026-11-24T04:49:14.000000+00:00'] + f[2:]),
        'known_sum': sub(lambda f: f[:3] == ['EXP', 'DYNAMIC', 'KNOWN'], lambda f: f[:3] + ['2'] + f[4:]),
        'no_end': sub(lambda f: f[0] == 'END', lambda f: None),
        'extra_col': sub(lambda f: f[:3] == ['RS', 'LIVE', 'RUNNING'], lambda f: f + [N]),
        'empty_file': '',
    }
    mres = {}
    for k, txt in muts.items():
        pth = os.path.join(d, k + '.tsv')
        wb(pth, txt)
        ck = run_chk(pth, d, k)
        so = ck.stdout.decode('utf-8', 'replace')
        mres[k] = {'changed': txt != base, 'rc7': ck.returncode == 7, 'shape_mismatch': so.startswith('SHAPE_MISMATCH'),
                   'no_traceback': ck.stderr == b''}
    res['mutations'] = {k: all(v.values()) for k, v in mres.items()}
    wb(os.path.join(d, 'mutations.json'), json.dumps(mres, ensure_ascii=False, indent=1))

    ok = all(all(v.values()) for v in res.values())
    ver = sh(['docker', 'exec', a.container, 'psql', '-X', '-At', '-U', 'postgres', '-c', 'select version()']).stdout.decode().strip()
    summary = {'ok': ok, 'results': res, 'container_network': net, 'server': ver,
               'sql_sha256': sha(sqlb), 'check_sha256': sha(chkb),
               'test_sha256': sha(open(os.path.abspath(__file__), 'rb').read()), 'setup_sha256': SETUP_SHA,
               'production_calls': 0, 'ssh_calls': 0}
    wb(os.path.join(a.out, 'summary.json'), json.dumps(summary, ensure_ascii=False, indent=1) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))
    sys.exit(main())
