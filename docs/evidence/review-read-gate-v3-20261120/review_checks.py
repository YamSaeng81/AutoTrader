"""v1.206: audit preserved files; no SQL, Docker, network, app, build or regression run."""
import hashlib
import json
from pathlib import Path
import re
import runpy
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
V3 = ROOT / 'docs/evidence/dec-read-gate-v3-20261120'
V2 = ROOT / 'docs/evidence/dec-read-gate-v2-20261120'
HEAD = '43d729dbe6341ba306489b89762b56d71cb505ba'
STAGE = {'S', 'O', 'P', 'D', 'CNT'}
checks = []


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *args], cwd=ROOT)


def check(name, passed, detail=None):
    checks.append(dict(name=name, pass_=bool(passed), detail=detail))


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def manifest(folder):
    entries, bad = [], []
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        want, rel = line.split(None, 1)
        p = folder / rel.strip().lstrip('*')
        entries.append(p.resolve())
        if sha(p.read_bytes()) != want:
            bad.append(rel)
    check(folder.name + '_manifest', not bad, dict(entries=len(entries), bad=bad))
    return set(entries)


def rows(raw):
    return [line.split('\t') for line in raw.decode('utf-8').splitlines() if line]


check('HEAD_and_saved_origin_no_network', git('rev-parse', 'HEAD').decode().strip() == HEAD
      == git('rev-parse', 'origin/main').decode().strip())
listed = manifest(V3)
actual = {p.resolve() for p in V3.rglob('*') if p.is_file() and p.name != 'SHA256SUMS'}
attrs = (V3 / '.gitattributes').resolve()
check('manifest_coverage', actual == listed or actual - listed == {attrs} and not listed - actual,
      dict(listed=len(listed), actual=len(actual), extra=[p.name for p in actual - listed]))
preservation = dict(folder_attributes_exists=attrs.exists(),
                    effective_attributes=git('check-attr', 'text', 'eol', '--',
                        (V3 / 'read.sql').relative_to(ROOT).as_posix(),
                        (V3 / 'run1/A_V3/stdout.tsv').relative_to(ROOT).as_posix()).decode())
manifest(V2)
manifest(ROOT / 'docs/evidence/review-read-gate-v2-20261120')
manifest(ROOT / 'docs/evidence/review-43d729db')
pins = {name: sha((V3 / name).read_bytes()) for name in ('read.sql', 'parse.py', 'tests_pg.py', 'setup_pg.py')}
log = (V3 / 'run1/tests_pg.out.txt').read_text(encoding='utf-8')
check('source_pins_in_preserved_log', all(pins[n] in log for n in ('read.sql', 'parse.py'))
      and all(sha((V2 / n).read_bytes()) in log for n in ('read.sql', 'parse.py', 'tests_pg.py')), pins)
lines = log.splitlines()
check('original_failure_retained', sum(x.startswith('PASS |') for x in lines) == 3
      and sum(x.startswith('FAIL |') for x in lines) == 1 and re.findall(r'\d+', lines[-1]) == ['4', '3', '1'])
setup = (V3 / 'run1/setup.txt').read_text(encoding='utf-8')
check('PG15_network_tmpfs_migrations_recorded', 'server=PostgreSQL 15.13 ' in setup
      and 'network/tmpfs=none {"/var/lib/postgresql/data":""}' in setup
      and 'migrations count=95 min=1 max=96' in setup, setup.splitlines())
sql2 = (V2 / 'read.sql').read_text(encoding='utf-8')
sql3 = (V3 / 'read.sql').read_text(encoding='utf-8')
check('registered_monetary_phase_unchanged', sql2[sql2.index("SELECT 'S', 'LIVE'"):] == sql3[sql3.index("SELECT 'S', 'LIVE'"):])
check('R1_R2_registration_and_session_gate_unchanged', sql2[sql2.index("SELECT 'ID_R'"):sql2.index('WITH c AS')]
      == sql3[sql3.index("SELECT 'ID_R'"):sql3.index('WITH c AS')])
check('unregistered_SQL_gate_unconditional', sql3.count("WHERE owner IN ('X','U')") == 2
      and sql3.count("(owner IN ('NO_SESSION_ID','NO_SESSION_ROW','UNKNOWN_KIND','UNCLASSIFIED','UNREG'))") == 2)
check('read_only_single_snapshot', sql3.count('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;') == 1
      and sql3.rstrip().endswith('ROLLBACK;') and sql3.count('\\if :gate_go') == 1)
driver = (V3 / 'tests_pg.py').read_text(encoding='utf-8')
check('driver_four_unique_calls_no_overwrite', driver.count('= run(a.container, a.out,') == 4
      and 'if os.path.exists(d):' in driver and driver.index('if os.path.exists(d):') < driver.index('t2.run_case(cont')
      and not any(x in driver for x in ("'stdout.tsv'), 'wb'", "'stdout.tsv'), 'w'")))
case_audit = []
expected = {'R3_V2': (0, 'GO', 11), 'R3_V3': (10, 'STOP', 0), 'PAST_V3': (10, 'STOP', 0), 'A_V3': (0, 'GO', 11)}
for tag, (want_rc, gate, n) in expected.items():
    d = V3 / 'run1' / tag
    raw = (d / 'stdout.tsv').read_bytes()
    rr = rows(raw)
    parsed = load(d / 'parsed.json')
    registry = (d / 'registry.json').read_bytes()
    stage = [r for r in rr if r[0] in STAGE]
    good = (int((d / 'psql_rc.txt').read_text()) == 0 == parsed['psql_rc']
            and int((d / 'parse_rc.txt').read_text()) == want_rc == parsed['rc']
            and [r[1] for r in rr if r[0] == 'GATE'] == [gate]
            and parsed['raw_sha256'] == sha(raw) and parsed['raw_bytes'] == len(raw)
            and parsed['registry_sha256'] == sha(registry) and len(stage) == n)
    detail = dict(tag=tag, psql_rc=parsed['psql_rc'], parser_rc=parsed['rc'], gate=gate,
                  gate_bad=parsed['gate_bad'], stage2_rows=len(stage), raw_sha256=sha(raw))
    if tag == 'R3_V3':
        stop_ids = {(x['rec'], x['id']) for x in parsed['unregistered_ids'] if x['stop']}
        good = good and stop_ids == {('O', 21), ('O', 22), ('P', 20), ('P', 21)} and parsed['gate_bad'] == 4
    if tag == 'PAST_V3':
        good = good and parsed['gate_bad'] == 2
    if tag == 'A_V3':
        good = good and {x['id'] for x in parsed['orders']} == {11, 12, 13, 14, 41}
        good = good and {x['id'] for x in parsed['positions']} == {7, 8} and b'777777' not in raw
    case_audit.append(detail)
    check('preserved_' + tag, good, detail)
check('counterexample_identical_fixture', (V3 / 'run1/R3_V2/fixture.sql').read_bytes()
      == (V3 / 'run1/R3_V3/fixture.sql').read_bytes())
refraw = (V2 / 'run2/A/stdout.tsv').read_bytes()
newraw = (V3 / 'run1/A_V3/stdout.tsv').read_bytes()
ref = [r for r in rows(refraw) if r[0] in STAGE]
new = [r for r in rows(newraw) if r[0] in STAGE]
diff = [(a, b) for a, b in zip(ref, new) if a != b]
norm = lambda r: r[:4] + ['<insertion_timestamp>'] if r[0] == 'D' else r
same = len(ref) == len(new) == 11 and len(diff) == 1 and diff[0][0][0] == diff[0][1][0] == 'D'
same = same and diff[0][0][:4] == diff[0][1][:4] and [norm(r) for r in ref] == [norm(r) for r in new]
check('normal_control_only_D_detected_at_differs', same, dict(rows=len(new), differing=diff))
comparison = load(V3 / 'run1/a-v3-compare.json')
check('developer_comparison_matches_raw', comparison['v2_A_sha256'] == sha(refraw)
      and comparison['v3_A_V3_sha256'] == sha(newraw) and comparison['stage2_rows'] == [11, 11]
      and comparison['byte_identical_rows'] == 10 and comparison['identical_excluding_D_detected_at'] is True)
names = git('ls-tree', '--name-only', HEAD + ':web-api/src/main/resources/db/migration').decode().splitlines()
mig = next(n for n in names if n.startswith('V82__'))
migb = git('show', HEAD + ':web-api/src/main/resources/db/migration/' + mig)
fixture = (V2 / 'tests_pg.py').read_text(encoding='utf-8')
check('default_insertion_timestamp_explains_difference', b'detected_at' in migb and b'DEFAULT CURRENT_TIMESTAMP' in migb
      and 'INSERT INTO order_settlement_discrepancy(id, order_id, kind, resolved_at)' in fixture,
      dict(migration=mig, migration_sha256=sha(migb)))

# Direct pure-file guard checks only, not a PG run or a parser suite rerun.
sys.dont_write_bytecode = True
parser = runpy.run_path(str(V3 / 'parse.py'), run_name='review_only')
probes = []
for rec in ('ID_O', 'ID_P'):
    raw = (V3 / 'run1/R3_V3/stdout.tsv').read_bytes()
    rr = rows(raw)
    target = next(r for r in rr if r[0] == rec)
    target[6] = 'f'
    changed = '\n'.join('\t'.join(r) for r in rr) + '\n'
    regb = (V3 / 'run1/R3_V3/registry.json').read_bytes()
    try:
        parser['parse'](changed, json.loads(regb), sha(regb))
        rejected = False
    except parser['Bad'] as exc:
        rejected = 'STOP' in str(exc)
    probes.append(dict(record=rec, mutation='clear stop flag', rejected=rejected,
                       basis='direct pure-file parser function, not PostgreSQL'))
    check('file_guard_' + rec + '_false_flag_rejected', rejected)
(OUT / 'case-audit.json').write_text(json.dumps(dict(cases=case_audit, normal_differences=diff, file_probes=probes), indent=2) + '\n', encoding='utf-8')
result = dict(basis='uncommitted hashes and preserved PG outputs; no production or PG execution', head=HEAD,
              pins=pins, preservation=preservation, checks=checks,
              passed=sum(x['pass_'] for x in checks), failed=sum(not x['pass_'] for x in checks))
(OUT / 'checks.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(passed=result['passed'], failed=result['failed'], entries=len(listed), pins=pins)))
sys.exit(0 if not result['failed'] else 1)
