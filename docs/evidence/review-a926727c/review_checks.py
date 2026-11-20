"""Fixed-blob review and one new write-failure probe; no DB/network/product tests."""
import ast
import builtins
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import posixpath
import re
import subprocess
import sys
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
EV = ROOT / 'docs/evidence'
SHA = 'a926727cbf0c7acc9007450a0dcbad315ae171d6'
checks = []

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *args], cwd=ROOT)

def sha(b):
    return hashlib.sha256(b).hexdigest()

def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})

check('HEAD and saved origin/main', all(git('rev-parse', x).decode().strip() == SHA
      for x in ('HEAD', 'origin/main')), 'Saved refs; no remote query')
paths = git('diff', '--name-only', SHA + '^', SHA).decode().splitlines()
check('Submission only evidence and three management docs', all(p.startswith('docs/evidence/') or p in
      ('docs/LIMITED_LIVE_TEST_PLAN.md', 'docs/CURRENT_HANDOFF.md', 'docs/PROGRESS.md') for p in paths),
      {'changed_paths': len(paths), 'settings_included': '.claude/settings.local.json' in paths})
manifest_paths = set()
for folder in ('dec-collect-assembler-20261120', 'dec-collect-contract-supp-20261120', 'review-73c23938'):
    base = 'docs/evidence/' + folder + '/'
    lines = git('show', SHA + ':' + base + 'SHA256SUMS').decode('utf-8-sig').splitlines()
    bad, n = [], 0
    for line in lines:
        if not line.strip():
            continue
        m = re.fullmatch(r'([0-9a-fA-F]{64})\s+\*?(.+)', line)
        if not m:
            bad.append({'bad_format': line})
            continue
        expected, rel = m.groups()
        path = posixpath.normpath(base + rel)
        blob = git('show', SHA + ':' + path)
        n += 1
        manifest_paths.add(path)
        if sha(blob) != expected or (ROOT / path).read_bytes() != blob:
            bad.append(path)
    check('Manifest ' + folder, not bad, {'entries': n, 'mismatches': bad})
check('Unique manifest files match reported 108', len(manifest_paths) == 108, len(manifest_paths))

asm_dir = EV / 'dec-collect-assembler-20261120'
asm_path = asm_dir / 'assemble.py'
tree = ast.parse(asm_path.read_text(encoding='utf-8'))
imports = {x.name for n in ast.walk(tree) if isinstance(n, ast.Import) for x in n.names}
calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)
         and isinstance(n.func.value, ast.Name) and n.func.value.id == 'subprocess']
check('No network imports and only fixed parser subprocess', not (imports & {'socket', 'requests', 'urllib', 'http'})
      and len(calls) == 1 and calls[0].func.attr == 'run',
      {'subprocess_calls': len(calls), 'command': ast.unparse(calls[0].args[0])})
for name, expected in {'read.sql': '9818db5452f66f058c97322ac9aaf73097036997777d10a0af9f6dde05bdae03',
                       'parse.py': '0f6fe827c9da513445ce9c52300b8688f8f4f162a99026b90d05943c10341908'}.items():
    path = 'docs/evidence/dec-read-gate-v3-20261120/' + name
    check('Unchanged accepted ' + name, sha(git('show', SHA + ':' + path)) == expected
          and (ROOT / path).read_bytes() == git('show', SHA + ':' + path), expected)
report = json.loads((asm_dir / 'run1/results.json').read_text(encoding='utf-8'))
want = {'normal': 0, 'stop': 10, 'psql_fail_partial': 20, 'parse_format_fail': 30,
        'clock_shift': 13, 'clock_absent': 41, 'existing_out': 40}
check('Preserved seven cases, no repeat', report['pass'] == report['total'] == 7 and set(report['cases']) == set(want)
      and all(report['cases'][k]['pass'] and report['cases'][k]['rc'] == rc for k, rc in want.items()))
bad_sums, bad_raw, bad_flags = [], [], []
for case in want:
    out = asm_dir / 'run1' / case
    if case == 'existing_out':
        continue
    bundle = json.loads((out / 'bundle.json').read_text(encoding='utf-8'))
    for name, expected in bundle['inputs'].items():
        if expected is not None and sha((out / name).read_bytes()) != expected:
            bad_raw.append([case, name])
    for line in (out / 'SHA256SUMS').read_text(encoding='ascii').splitlines():
        h, rel = line.split('  ', 1)
        if sha((out / rel).read_bytes()) != h:
            bad_sums.append([case, rel])
    if bundle['is_p7_observation'] or bundle['is_f3_input'] or bundle['account_or_month_end_confirmed']:
        bad_flags.append(case)
    if (bundle['downstream'] == 'db_part_only') != (case == 'normal'):
        bad_flags.append(case)
check('Preserved bundles hashes and blocking flags', not (bad_sums or bad_raw or bad_flags),
      {'hash': bad_sums, 'raw': bad_raw, 'flags': bad_flags})
for key in ('stdout', 'stderr', 'psql-rc', 'registry'):
    filename = {'stdout': 'stdout.tsv', 'stderr': 'stderr.txt', 'psql-rc': 'psql_rc.txt', 'registry': 'registry.json'}[key]
    check('Normal raw source ' + key, (asm_dir / 'run1/normal' / filename).read_bytes() ==
          (EV / 'dec-read-gate-v3-20261120/run1/A_V3' / filename).read_bytes())

spec = importlib.util.spec_from_file_location('review_assembler', asm_path)
asm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asm)
probe = HERE / 'sha-write-failure'
if probe.exists():
    raise RuntimeError('New probe path required; do not overwrite')
ind = asm_dir / 'run1/inputs/normal'
args = []
for key in ('registry', 'stdout', 'stderr', 'psql-rc', 'clock'):
    args += ['--' + key, str(ind / key)]
gate = EV / 'dec-read-gate-v3-20261120'
args += ['--sql', str(gate / 'read.sql'), '--parser', str(gate / 'parse.py'), '--out', str(probe)]
real_open = builtins.open
faults = []
def fail_sums(file, mode='r', *args, **kwargs):
    if Path(file) == probe / 'SHA256SUMS' and 'w' in mode:
        faults.append('SHA256SUMS write denied')
        raise PermissionError('synthetic SHA256SUMS write denial')
    return real_open(file, mode, *args, **kwargs)
stdout = io.StringIO()
with patch('builtins.open', fail_sums), contextlib.redirect_stdout(stdout):
    rc = asm.main(args)
bundle = json.loads((probe / 'bundle.json').read_text(encoding='utf-8'))
finding = {'fault': faults, 'actual_returncode': rc, 'stdout': stdout.getvalue(),
           'bundle_rc': bundle['rc'], 'bundle_downstream': bundle['downstream'],
           'bundle_status': bundle.get('status'), 'sha256sums_exists': (probe / 'SHA256SUMS').exists(),
           'files': sorted(p.name for p in probe.iterdir()),
           'kind': 'File-only record finalization defect; no production action'}
(HERE / 'write-failure.json').write_text(json.dumps(finding, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
check('Write failure reproduced: CLI fails but bundle still advertises success', rc == 42 and faults
      and bundle['rc'] == 0 and bundle['downstream'] == 'db_part_only' and not (probe / 'SHA256SUMS').exists(), finding)
out = {'sha': SHA, 'assembler_sha256': sha(asm_path.read_bytes()),
       'passed': sum(c['ok'] for c in checks), 'failed': sum(not c['ok'] for c in checks),
       'unique_manifest_files': len(manifest_paths), 'checks': checks,
       'note': 'Successful checks include reproduction of a defect, not acceptance of the defect'}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: out[k] for k in ('passed', 'failed', 'unique_manifest_files', 'assembler_sha256')}))
raise SystemExit(0 if out['failed'] == 0 else 1)
