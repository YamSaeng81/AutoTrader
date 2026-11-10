"""Read fixed Git blobs; run exact local tests with only selected original AST nodes.

No start_66 module top-level execution or .env loading. Network/process boundaries
are denied while the test bodies run. All state writes are temporary local files.
Run from repository root: python -B -X utf8 docs/evidence/n21-review-6379ee0b/review.py
"""
import ast
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import types
import urllib.request
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SUBMITTED = ROOT / 'docs/evidence/n21-recheck-1899b49'
BASE = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
FIRST = '99bd4eaadb0ff3037518c3cacbf6bb98de91bf4e'
NEW = '6379ee0b2a7527b8136d7fba27e83881980d8555'
SCRIPT = 'scripts/prospective/start_66.py'
TEST = 'scripts/prospective/test_freshness_recheck_fail.py'
SPACING = 'scripts/prospective/test_freshness_spacing.py'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def lf(data):
    return data.replace(b'\r\n', b'\n')

blobs = {rev: git('show', rev + ':' + SCRIPT) for rev in (BASE, FIRST, NEW)}
test = git('show', NEW + ':' + TEST)
spacing = git('show', NEW + ':' + SPACING)
assert git('rev-parse', FIRST + '^').decode().strip() == BASE
assert git('rev-parse', NEW + '^').decode().strip() == FIRST
assert git('diff', '--name-only', BASE, FIRST).decode().splitlines() == [TEST]
assert git('diff', '--name-only', FIRST, NEW).decode().splitlines() == [SCRIPT]
assert git('show', FIRST + ':' + TEST) == test
assert blobs[FIRST] == blobs[BASE]
assert git('show', BASE + ':' + SPACING) == spacing
assert git('merge-base', BASE, NEW).decode().strip() == BASE
local_remote = git('rev-parse', 'refs/remotes/origin/candidate/n21-recheck-1899b49').decode().strip()
assert local_remote == NEW  # Local tracking ref only; no remote/network verification.

provenance = {}
for name, data in [('start_66.1899b49.py', blobs[BASE]),
                   ('start_66.candidate.py', blobs[NEW]),
                   ('test_freshness_recheck_fail.py', test),
                   ('test_freshness_spacing.py', spacing),
                   ('candidate.diff', git('diff', BASE, NEW))]:
    raw = (SUBMITTED / name).read_bytes()
    assert lf(raw) == lf(data), name
    provenance[name] = {'submitted_sha256': digest(raw), 'git_sha256': digest(data),
                        'lf_equal': True, 'byte_equal': raw == data}

tree_base = ast.parse(blobs[BASE].decode())
tree_new = ast.parse(blobs[NEW].decode())
defs = lambda t: {n.name: ast.dump(n, include_attributes=False) for n in t.body
                  if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))}
db, dn = defs(tree_base), defs(tree_new)
assert db.keys() == dn.keys()
assert [n for n in db if db[n] != dn[n]] == ['cmd_freshness']
# Prove the candidate equals exactly the approved assignment replacement.
old = b'            if ex2:\n                state[pair]["exTo"] = ex2\n'
new = b'            state[pair]["exTo"] = ex2\n'
without_comments = lambda s: '\n'.join(l for l in s.splitlines() if not l.lstrip().startswith('#'))
assert blobs[BASE].count(old) == 1
assert without_comments(blobs[BASE].replace(old, new).decode()) == without_comments(blobs[NEW].decode())

CALLS = []
def deny(*args, **kwargs):
    CALLS.append('denied external boundary')
    raise AssertionError('No network or child process is authorized in this review')

def module(source):
    chosen = {'_parse_iso', 'cmd_freshness', '_alerts_report', '_streak_reset',
              '_streak_update', '_runs_since'}
    constants = {'COINS', 'UPBIT_GAP_S', 'MAX_GAP_MIN', 'REQUIRED_HOURS'}
    tree = ast.parse(source.decode())
    nodes = []
    for n in tree.body:
        if isinstance(n, ast.FunctionDef) and n.name in chosen:
            nodes.append(n)
        elif isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in constants for t in n.targets):
            nodes.append(n)
    m = types.ModuleType('start_66')
    m.__dict__.update(json=json, os=os, datetime=datetime, timedelta=timedelta, timezone=timezone,
                      time=types.SimpleNamespace(sleep=deny), need_env=deny,
                      _db_latest=deny, _upbit_latest_h1=deny,
                      FRESH_LOG='', FRESH='', STREAK='')
    exec(compile(ast.Module(body=nodes, type_ignores=[]), '<original fixed Git AST>', 'exec'), m.__dict__)
    return m

class Capture(io.StringIO):
    def reconfigure(self, **kwargs):
        pass

def exact_test(source, test_source, file_name, output_name):
    m = module(source)
    previous = sys.modules.get('start_66')
    sys.modules['start_66'] = m
    env = {'__name__': '__main__', '__file__': str(SUBMITTED / file_name)}
    capture = Capture()
    rc = 0
    try:
        with contextlib.redirect_stdout(capture):
            try:
                exec(compile(test_source, str(SUBMITTED / file_name), 'exec'), env)
            except SystemExit as e:
                rc = e.code
    finally:
        if previous is None:
            del sys.modules['start_66']
        else:
            sys.modules['start_66'] = previous
    text = capture.getvalue()
    (OUT / output_name).write_text(text, encoding='utf-8', newline='\n')
    return {'rc': rc, 'pass': sum(l.startswith('PASS ') for l in text.splitlines()),
            'fail': sum(l.startswith('FAIL ') for l in text.splitlines()),
            'output_sha256': digest(text.encode()),
            'matches_submitted_lf': lf((SUBMITTED / output_name).read_bytes()) == text.encode()}

real_state_cases = []
def streak_sequence(error):
    """Use actual original streak helpers, not the submitted reset/update stubs."""
    m = module(blobs[NEW])
    start = datetime(2026, 11, 8, 19, 19, 1, tzinfo=timezone.utc)
    old = datetime(2026, 11, 8, 16, tzinfo=timezone.utc)
    fresh = old + timedelta(hours=1)
    clock = {'seconds': 0, 'retry': False, 'recovered': False}
    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return start + timedelta(seconds=clock['seconds'])
    def sleep(seconds):
        clock['seconds'] += seconds
        if seconds == 70:
            clock['retry'] = True
    def db():
        to = fresh if clock['retry'] or clock['recovered'] else old
        return {'KRW-A': {'to': to.isoformat(), 'count': 500}}
    def exchange(pair):
        if clock['recovered']:
            return fresh, None
        return (None, error) if clock['retry'] else (fresh, None)
    m.COINS = ['A']
    m.datetime = Clock
    m.need_env = lambda: None
    m.time = types.SimpleNamespace(sleep=sleep)
    m._db_latest, m._upbit_latest_h1 = db, exchange
    with tempfile.TemporaryDirectory(prefix='n21-root-') as temp:
        m.FRESH_LOG, m.FRESH, m.STREAK = [str(Path(temp) / n) for n in ('log.jsonl', 'snap.json', 'streak.json')]
        Path(m.STREAK).write_text(json.dumps({'startedAt': (start - timedelta(hours=10)).isoformat(),
            'lastAt': (start - timedelta(minutes=30)).isoformat()}))
        with contextlib.redirect_stdout(Capture()):
            fail_rc = m.cmd_freshness(save=True)
        broken = json.loads(Path(m.STREAK).read_text(encoding='utf-8'))
        row = json.loads(Path(m.FRESH_LOG).read_text(encoding='utf-8').splitlines()[-1])
        assert fail_rc == 1 and broken['startedAt'] is None and broken['lastAt'] is None and broken['brokenAt']
        assert row['rows']['KRW-A']['exTo'] is None and row['rows']['KRW-A']['exErr'] == error
        assert (row['unknown'], row['red']) == ((1, 0) if error == 'net' else (0, 1))
        real_state_cases.append({'case': error + ' actual persisted reset', 'pass': True})
        clock.update(seconds=3600, retry=False, recovered=True)
        with contextlib.redirect_stdout(Capture()):
            good_rc = m.cmd_freshness(save=True)
        recovered = json.loads(Path(m.STREAK).read_text(encoding='utf-8'))
        assert good_rc == 0 and recovered['startedAt'] == recovered['lastAt']
        assert recovered['startedAt'] == Clock.now().replace(second=1).isoformat()  # now taken before first loop
        real_state_cases.append({'case': error + ' recovery starts new streak', 'pass': True})
        lines, bad, count = m._alerts_report(start - timedelta(minutes=1), Clock.now(), 'local sequence')
        assert count == 2 and bad == ['판정 불가 1회' if error == 'net' else '적재 뒤처짐 1회']
        assert any('현재' in l and '🟢 1 / ⚪ 0 / 🔴 0' in l for l in lines)
        real_state_cases.append({'case': error + ' history alert retained after recovery', 'pass': True})

patches = [(socket, 'getaddrinfo'), (socket, 'create_connection'), (socket.socket, 'connect'),
           (socket.socket, 'connect_ex'), (urllib.request, 'urlopen'),
           (subprocess, 'Popen'), (os, 'system')]
originals = [(obj, name, getattr(obj, name)) for obj, name in patches]
try:
    for obj, name, _ in originals:
        setattr(obj, name, deny)
    runs = {
        'base19': exact_test(blobs[BASE], test, Path(TEST).name, 'before-1899b49.txt'),
        'candidate19': exact_test(blobs[NEW], test, Path(TEST).name, 'after.txt'),
        'base_spacing': exact_test(blobs[BASE], spacing, Path(SPACING).name, 'spacing-before-1899b49.txt'),
        'candidate_spacing': exact_test(blobs[NEW], spacing, Path(SPACING).name, 'spacing-after.txt'),
    }
    assert (runs['base19']['pass'], runs['base19']['fail'], runs['base19']['rc']) == (10, 9, 1)
    assert (runs['candidate19']['pass'], runs['candidate19']['fail'], runs['candidate19']['rc']) == (19, 0, 0)
    assert all(runs[k]['pass'] == 6 and runs[k]['fail'] == 0 and runs[k]['rc'] == 0 for k in ('base_spacing', 'candidate_spacing'))
    assert all(r['matches_submitted_lf'] for r in runs.values())
    streak_sequence('net')
    streak_sequence('404')
finally:
    for obj, name, original in originals:
        setattr(obj, name, original)
assert not CALLS

files = {f.name: digest(f.read_bytes()) for f in sorted(SUBMITTED.iterdir()) if f.is_file()}
result = {'base': BASE, 'test_first': FIRST, 'candidate': NEW,
          'local_remote_tracking_ref': local_remote, 'remote_queried': False,
          'submitted_manifest_present': (SUBMITTED / 'SHA256SUMS').exists(),
          'submitted_file_sha256_at_review': files, 'provenance': provenance,
          'source_semantics_change': 'one exTo assignment; all other functions unchanged',
          'tests': runs, 'actual_persisted_streak': real_state_cases,
          'denied_boundary_calls': len(CALLS),
          'not_run': ['server/DB/HTTP', 'actual module top-level/.env', 'Gradle/PG/rehearsal/jar/Docker', 'git commit/push'],
          'scope': 'local AST function verification; submitted test bodies unchanged; not an actual module import'}
(OUT / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'tests': runs, 'actual_persisted_checks': len(real_state_cases),
                  'external_boundary_calls': len(CALLS), 'provenance_files': len(provenance)}, ensure_ascii=False))
