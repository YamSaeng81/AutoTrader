"""Read committed evidence; exercise draft blocks using local functions only.

No SSH, HTTP, DB, application code, build, checkout, cron or Docker execution.
Git is read only in the controller; git/docker/crontab/pgrep inside bash are fakes.
"""
from pathlib import Path
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SCRATCH = ROOT / 'core-engine/build/review-da7977d4'
SCRATCH.mkdir(parents=True, exist_ok=True)
BASH = r'C:\Program Files\Git\bin\bash.exe'
COMMIT = 'da7977d4'
OLD = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
NEW = '6379ee0b2a7527b8136d7fba27e83881980d8555'
O13 = '1ec91046bf2bf0b7af0e6f05c04267a6c78efc21'
F = 'scripts/prospective/start_66.py'
IMG = 'sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def posix(path):
    s = Path(path).resolve().as_posix()
    return '/' + s[0].lower() + s[2:] if s[1:3] == ':/' else s

def quote(value):
    return "'" + value.replace("'", "'\"'\"'") + "'"

def verify_manifest(folder):
    p = ROOT / 'docs/evidence' / folder
    rows = []
    for line in (p / 'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip():
            continue
        digest, name = re.match(r'^([0-9a-f]{64})\s+\*?(.+)$', line).groups()
        local = (p / name).read_bytes()
        blob = git('show', f'{COMMIT}:{(p / name).relative_to(ROOT).as_posix()}')
        assert sha(local) == digest == sha(blob), (folder, name)
        rows.append({'file': name, 'sha256': digest})
    return rows

folders = ['n21-recheck-1899b49', 'n21-apply-draft-6379ee0b',
           'n03-1110-1000', 'gap-review-b6f5fd42', 'n21-review-6379ee0b']
manifests = {f: verify_manifest(f) for f in folders}
assert [len(v) for v in manifests.values()] == [11, 30, 18, 12, 9]
old_review = json.loads((ROOT / 'docs/evidence/n21-review-6379ee0b/review.json').read_text(encoding='utf-8'))
for name, digest in old_review['submitted_file_sha256_at_review'].items():
    assert sha((ROOT / 'docs/evidence/n21-recheck-1899b49' / name).read_bytes()) == digest

def names(revision):
    return git('diff', '--name-only', '-z', OLD, revision).decode().strip('\0').split('\0')

n21_paths, o13_paths = names(NEW), names(O13)
assert len(n21_paths) == 2 and len(o13_paths) == 6
assert not set(n21_paths).intersection(o13_paths)
assert git('show', f'{OLD}:{F}') == git('show', f'{O13}:{F}')
for name in o13_paths:
    if '/main/' in name:
        assert git('show', f'{OLD}:{name}') == git('show', f'{NEW}:{name}')

np = ROOT / 'docs/evidence/n03-1110-1000'
report = (np / '20261110-1000.txt').read_text(encoding='utf-8')
errors = (np / 'error_lines.txt').read_text(encoding='utf-8').splitlines()
installed = git('show', 'c669ccd:scripts/ops/daily_check.sh').decode('utf-8')
patterns = dict(re.findall(r"^\s*(P_[A-Z0-9_]+)='([^'\n]+)'", installed, re.M))
classified = {k: sum(bool(re.search(patterns[k], line)) for line in errors)
              for k in ('P_POOL', 'P_CANDLE', 'P_404', 'P_IO', 'P_NEWS', 'P_BLAST')}
unclassified = [line for line in errors if not any(re.search(p, line) for p in patterns.values())]
assert len(errors) == 12 and classified == {'P_POOL': 0, 'P_CANDLE': 0,
    'P_404': 3, 'P_IO': 6, 'P_NEWS': 0, 'P_BLAST': 3} and not unclassified
for phrase in ['🟢 점검 항목 정상', 'B1 true', '필요 11-10 08:00', '20/20',
               'SID 203: CREATED · 잔액 20200.00', '2026-11-10 10:00:04 KST 보고 전송 성공']:
    assert phrase in report
server_hashes = dict((name, digest) for digest, name in re.findall(
    r'^([0-9a-f]{64})  (\S+)$', (np / 'server-files.txt').read_text(encoding='utf-8'), re.M))
for name in ['20261110-1000.txt', '20261110-1000.txt.msg', '20261110-1000.txt.raw', 'last_send']:
    assert sha((np / name).read_bytes()) == server_hashes[name]
assert report.splitlines()[:-1] == (np / '20261110-1000.txt.msg').read_text(encoding='utf-8').splitlines()
ledger = (np / 'ledger-20261110.txt').read_text(encoding='utf-8')
assert 'CREATED |      20200.00 |            20200 |         0.00' in ledger
prior_ledger = ROOT / 'docs/evidence/n03-1109-1000/ledger-20261109.txt'
assert prior_ledger.exists()
ledger_delta = [(i+1, a, b) for i, (a, b) in enumerate(zip(
    prior_ledger.read_text(encoding='utf-8').splitlines(), ledger.splitlines())) if a != b]
assert [n for n, a, b in ledger_delta] == [6, 27]

blocks = ROOT / 'docs/evidence/n21-apply-draft-6379ee0b/blocks'
syntax = {}
for script in [*sorted(blocks.glob('*.sh')), blocks.parent / 'standin/run.sh']:
    r = subprocess.run([BASH, '--noprofile', '--norc', '-n', posix(script)], capture_output=True)
    assert r.returncode == 0, (script, r.stderr)
    syntax[str(script.relative_to(blocks.parent)).replace('\\', '/')] = sha(script.read_bytes())

# Each run uses the exact submitted block, with no vars.sh execution (its cd/mkdir
# point to a real server home). Equivalent constants point exclusively into a new
# local fixture directory. No HOME/system variable reassignment is used.
def run_case(case, block, *, pgrep_rc=1, cron_mode='current', traceback=False,
             initial_head=NEW, expected_rc=0, expect_checkout=False):
    with tempfile.TemporaryDirectory(prefix='fixture-', dir=SCRATCH) as tmp:
        task = Path(tmp)
        m = task / 'maint'; m.mkdir()
        repo = task / 'repo'; (repo / 'scripts/prospective').mkdir(parents=True)
        f = repo / F
        f.write_bytes(git('show', f'{NEW}:{F}'))
        (task / 'old.py').write_bytes(git('show', f'{OLD}:{F}'))
        (task / 'new.py').write_bytes(f.read_bytes())
        (m / 'start_66.py.before').write_bytes((task / 'old.py').read_bytes())
        (m / 'ok-A0').touch(); (m / 'ok-A1').touch()
        if block == 'A1':
            (m / 'ok-A1').unlink()
        (m / 'log-lines-before.txt').write_text('1\n')
        (m / 't-apply.utc').write_text('2026-11-10T01:30:00Z\n')
        (m / 'crontab-before.txt').write_text('unchanged fixture cron\n')
        row = {'at': '2026-11-10T02:19:30+00:00', 'green': 22, 'unknown': 0,
               'red': 0, 'redCoins': [], 'unknownCoins': [], 'rechecked': [],
               'rows': {f'KRW-TEST{i}': {'count': 500, 'dbTo': '2026-11-10T02:00:00+00:00',
               'exTo': '2026-11-10T02:00:00+00:00', 'exErr': None, 'rechecked': False} for i in range(22)}}
        log = repo / 'scripts/prospective/freshness_log.jsonl'
        log.write_text(json.dumps(row) + '\n' + json.dumps(row) + '\n', encoding='utf-8')
        (repo / 'scripts/prospective/freshness_runs.log').write_text(
            'Traceback (most recent call last):\nNEW_FIXTURE_ERROR\n' if traceback else '', encoding='utf-8')
        setup = f'''
NB=21
OLD={quote(OLD)}; NEW={quote(NEW)}; IMG={quote(IMG)}
M={quote(posix(m))}; F={quote(F)}
T=scripts/prospective/test_freshness_recheck_fail.py
BNEW=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d
HNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b
HOLD=adca8829b9fb6b4c6996dc853fadade58d685d9c0c044135eb6f92665dba337d
DC='docker compose -f docker-compose.prod.yml'
g_head={quote(initial_head)}
git() {{
    case "$1" in
      rev-parse) printf '%s\\n' "$g_head" ;;
      status) return 0 ;;
      hash-object) printf '%s\\n' "$BNEW" ;;
      checkout)
        printf 'checkout %s\\n' "$3" >> {quote(posix(task / 'calls.txt'))}
        g_head="$3"
        if [ "$g_head" = "$OLD" ]; then cp {quote(posix(task / 'old.py'))} "$F"; else cp {quote(posix(task / 'new.py'))} "$F"; fi ;;
      *) printf 'UNEXPECTED git %s\\n' "$*" >&2; return 90 ;;
    esac
}}
docker() {{ case "$1" in compose) echo fixture-backend ;; inspect) echo "$IMG" ;; *) return 90 ;; esac; }}
crontab() {{ [ "$1" = -l ] || return 90; cat "$M/crontab-before.txt"; }}
pgrep() {{
    [ {pgrep_rc} = 0 ] && echo '4242 python3 scripts/prospective/start_66.py freshness --save'
    [ {pgrep_rc} = 2 ] && echo 'pgrep: fixture lookup failure' >&2
    return {pgrep_rc}
}}
date() {{ [ "$*" = '+%M' ] && {{ echo 30; return; }}; command date "$@"; }}
grep() {{
    if [ "${{@: -1}}" = /var/log/syslog ]; then
        {"echo 'Nov 10 11:19:01 host CRON[42]: CMD (python3 scripts/prospective/start_66.py freshness --save)'" if cron_mode == 'current' else 'return 1'}
    else command grep "$@"; fi
}}
python3() {{ {quote(posix(sys.executable))} -B -X utf8 "$@"; }}
ssh() {{ echo FORBIDDEN >&2; return 99; }}
curl() {{ echo FORBIDDEN >&2; return 99; }}
psql() {{ echo FORBIDDEN >&2; return 99; }}
'''
        source = (blocks / f'{block}.sh').read_text(encoding='utf-8')
        runner = task / 'run.sh'; runner.write_text(setup + '\n' + source, encoding='utf-8', newline='\n')
        r = subprocess.run([BASH, '--noprofile', '--norc', posix(runner)], cwd=repo,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           env={**os.environ, 'PYTHONIOENCODING': 'utf-8'}, timeout=30)
        output = r.stdout.decode('utf-8')
        (HERE / f'{case}.txt').write_text(output, encoding='utf-8', newline='\n')
        called = (task / 'calls.txt').read_text() if (task / 'calls.txt').exists() else ''
        assert r.returncode == expected_rc and bool(called) == expect_checkout, (case, r.returncode, output, called)
        assert 'FORBIDDEN' not in output and 'UNEXPECTED' not in output
        return {'case': case, 'block': block, 'pgrep_exit': pgrep_rc,
                'cron_fixture': cron_mode, 'new_traceback': traceback,
                'initial_head': initial_head, 'rc': r.returncode,
                'fake_checkout_calls': called.splitlines(), 'output_sha256': sha(r.stdout)}

cases = [
    run_case('a1-no-process-control', 'A1', initial_head=OLD, expect_checkout=True),
    run_case('a1-running-control', 'A1', initial_head=OLD, pgrep_rc=0, expected_rc=1),
    run_case('a1-pgrep-error', 'A1', initial_head=OLD, pgrep_rc=2, expect_checkout=True),
    run_case('a2-current-cron-control', 'A2'),
    run_case('a2-no-cron', 'A2', cron_mode='none'),
    run_case('a2-new-traceback', 'A2', traceback=True),
    run_case('rollback-unexpected-head', 'A1-rollback', initial_head='a'*40, expect_checkout=True),
]
result = {'head_reviewed': git('rev-parse', 'HEAD').decode().strip(),
          'manifests': {k: {'count': len(v), 'files': v} for k,v in manifests.items()},
          'old_n21_submitted_11_unchanged': True,
          'paths': {'n21': n21_paths, 'o1o3': o13_paths, 'intersection': []},
          'n03': {'error_lines': len(errors), 'classification': classified,
                  'unclassified': len(unclassified), 'server_file_hashes_checked': 4,
                  'ledger_changed_line_numbers': [x[0] for x in ledger_delta],
                  'scope': 'stored report, ledger and evidence only; no new server read'},
          'syntax': syntax, 'draft_boundary_cases': cases,
          'original_standin_read_not_rerun': '21/21 uses substring only, ignores per-case exit status; s01-a2 passes despite missing syslog and freshness_runs.log',
          'limits': ['No actual process discovery or race test on Linux', 'No application/PG/HTTP/SSH/build/checkout',
                     'No N21 product test rerun; prior acceptance retained',
                     'No combined candidate constructed; paths are disjoint, final candidate still requires evidence']}
(HERE / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'manifests': {k: len(v) for k,v in manifests.items()}, 'syntax': len(syntax),
                  'new_local_cases': len(cases), 'classified': classified, 'disjoint_paths': [2,6]}, ensure_ascii=False))
