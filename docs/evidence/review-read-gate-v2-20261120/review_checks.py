"""v1.205: inspect submitted working files and preserved outputs only. No SQL, parser suite, Docker, network or app execution."""
import hashlib
import json
import pathlib
import posixpath
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / 'docs/evidence/dec-read-gate-v2-20261120'
HEAD = '43d729dbe6341ba306489b89762b56d71cb505ba'
checks = []


def h(b):
    return hashlib.sha256(b).hexdigest()


def git(*a):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *a], cwd=ROOT)


def check(name, ok, detail=None):
    checks.append(dict(name=name, pass_=bool(ok), detail=detail))


def manifest(folder, committed=False):
    lines = (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines()
    bad = []
    paths = []
    for row in lines:
        want, rel = row.split(None, 1)
        rel = rel.strip().lstrip('*')
        p = folder / rel
        paths.append(p.resolve())
        b = p.read_bytes()
        if h(b) != want:
            bad.append(rel)
        if committed:
            path = posixpath.normpath(p.relative_to(ROOT).as_posix())
            if git('show', HEAD + ':' + path) != b:
                bad.append('commit:' + rel)
    check(folder.name + '_manifest', not bad, dict(total=len(lines), bad=bad))
    return paths


check('HEAD_and_saved_origin_unchanged_no_network', git('rev-parse', 'HEAD').decode().strip() == HEAD == git('rev-parse', 'origin/main').decode().strip())
listed = manifest(SRC)
actual = {p.resolve() for p in SRC.rglob('*') if p.is_file() and p.name != 'SHA256SUMS'}
extra = actual - set(listed)
attrs = (SRC / '.gitattributes').resolve()
check('submitted_manifest_covers_evidence', extra == {attrs} and not (set(listed) - actual),
      dict(listed=len(listed), actual=len(actual), separately_checked=[p.name for p in extra]))
check('gitattributes_preserves_bytes', attrs.read_bytes().strip() == b'* -text', h(attrs.read_bytes()))
manifest(ROOT / 'docs/evidence/review-43d729db')
for rel in ('read.sql', 'parse.py'):
    p = 'docs/evidence/dec-read-plan-20261120/' + rel
    check('original_' + rel + '_unchanged', (ROOT / p).read_bytes() == git('show', HEAD + ':' + p))
pins = {name: h((SRC / name).read_bytes()) for name in ('read.sql', 'parse.py', 'tests_pg.py')}
runlog = (SRC / 'run2/tests_pg.out.txt').read_text(encoding='utf-8')
check('source_pins_in_runlog', all(pins[n] in runlog for n in ('read.sql', 'parse.py')), pins)
check('preserved_summary_11_of_11', runlog.splitlines()[-1] == '합계 11 통과 11 실패 0')
check('preserved_PG_version_and_migration_count', 'server=PostgreSQL 15.13 ' in runlog and 'base migrations(count|max)=95|96' in runlog)

cases = []
for tag in 'ABCDEFGHIJK':
    d = SRC / 'run2' / tag
    raw = (d / 'stdout.tsv').read_bytes()
    rows = [line.split('\t') for line in raw.decode('utf-8').splitlines() if line]
    p = json.loads((d / 'parsed.json').read_text(encoding='utf-8'))
    prc = int((d / 'psql_rc.txt').read_text())
    rc = int((d / 'parse_rc.txt').read_text())
    gates = [r[1] for r in rows if r[0] == 'GATE']
    s2 = [r for r in rows if r[0] in ('S', 'O', 'P', 'D', 'CNT')]
    expected_rc = 0 if tag == 'A' else 20 if tag == 'K' else 10
    ok = (rc == expected_rc == p['rc'] and p['raw_sha256'] == h(raw)
          and p['raw_bytes'] == len(raw) and p['psql_rc'] == prc
          and (prc == 3 and not s2 if tag == 'K' else prc == 0)
          and (gates == ['GO'] if tag == 'A' else not gates if tag == 'K' else gates == ['STOP'] and not s2))
    reg = (d / 'registry.json').read_bytes()
    if tag != 'K':
        meta = next(r for r in rows if r[0] == 'META')
        ok = ok and meta[7] == h(reg)
    if tag == 'A':
        ok = ok and {x['id'] for x in p['orders']} == {11, 12, 13, 14, 41} and {x['id'] for x in p['positions']} == {7, 8}
        ok = ok and '777777' not in raw.decode() and not any(r[0] == 'S' and r[3] in ('100', '950') for r in rows)
    detail = dict(case=tag, psql_rc=prc, parser_rc=rc, gate=gates, stage2_rows=len(s2), raw_sha256=h(raw))
    cases.append(detail)
    check('preserved_case_' + tag, ok, detail)

for tag, leaked in (('V1B', False), ('V1F', True)):
    d = SRC / 'run2' / tag
    text = (d / 'stdout.tsv').read_text(encoding='utf-8')
    rows = [line.split('\t') for line in text.splitlines()]
    check('preserved_old_' + tag, int((d / 'psql_rc.txt').read_text()) == 0 and ['GATE', 'GO'] in rows
          and ('777777' in text) == leaked and (tag != 'V1B' or not any(r[0] == 'ID_O' and r[1] == '50' for r in rows)))
setup = (SRC / 'run1/setup-error.txt').read_text(encoding='utf-8')
check('setup_failure_preserved_before_read', 'CREATE DATABASE c_a TEMPLATE base' in setup and 'accessed by other users' in setup)
driver = (SRC / 'tests_pg.py').read_text(encoding='utf-8')
first = driver.index("run_case(a.container, a.out, 'K'")
second = driver.index("live_ids=abc", first)
check('K_extra_normal_execution_and_overwrite_in_driver', first < second and driver.count("open(os.path.join(d2, 'stdout.tsv'), 'wb')") == 1)
sql = (SRC / 'read.sql').read_text(encoding='utf-8')
check('read_only_and_no_time_filter', 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;' in sql and 'ROLLBACK;' in sql
      and "o.created_at >=" not in sql and "position WHERE status <>" not in sql)
range_probe = dict(basis='static SQL predicate model, not an executed PG case',
    source_sha256=pins['read.sql'], owner='UNREG', session_status='STOPPED', state='FILLED',
    settlement_final=True, fill_accounting_pending=False, fee_source='EXCHANGE', unresolved_discrepancy=False,
    linked_position_status='CLOSED', old_timestamps='before the observation base',
    new_timestamps='after the observation base, completed before current capture',
    original_case='run2/A order21, position20, LIVE100',
    sql_stop_old=False, sql_stop_new=False,
    reason='UNREG gate uses only active/unsettled/open_disc/open_pos. Event timestamps and baseline identity/version evidence are not inputs. Metadata lists alone do not prove that a terminal order predates the observation interval.')
(OUT / 'range-probe.json').write_text(json.dumps(range_probe, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
result = dict(basis='uncommitted working-file hashes; not a fixed execution commit', head=HEAD, pins=pins,
              pass_count=sum(x['pass_'] for x in checks), fail_count=sum(not x['pass_'] for x in checks), checks=checks)
(OUT / 'checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(OUT / 'case-audit.json').write_text(json.dumps(dict(cases=cases, driver_note='K first runs a normal read through run_case; its outputs are overwritten by the invalid-array execution. Extra normal output is unavailable; excluded from success evidence. No rerun requested.'), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(checks=len(checks), failed=[x['name'] for x in checks if not x['pass_']], pins=pins), ensure_ascii=False))
