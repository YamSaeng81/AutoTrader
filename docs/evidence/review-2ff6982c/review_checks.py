"""459 fixed-blob and preserved-result review; no test suite, DB, or network."""
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import posixpath
import re
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
EV = ROOT / 'docs/evidence'
SHA = '2ff6982c634f4ad639d25b174bb990d3a4250b81'
checks = []

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *args], cwd=ROOT)

def digest(b):
    return hashlib.sha256(b).hexdigest()

def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})

check('HEAD and saved origin/main', all(git('rev-parse', x).decode().strip() == SHA for x in ('HEAD', 'origin/main')),
      'Saved refs only; no new remote query')
paths = git('diff', '--name-only', 'a926727c', SHA).decode().splitlines()
check('Evidence and three management docs only', all(p.startswith('docs/evidence/') or p in
      ('docs/CURRENT_HANDOFF.md', 'docs/LIMITED_LIVE_TEST_PLAN.md', 'docs/PROGRESS.md') for p in paths),
      {'paths': len(paths), 'settings_included': '.claude/settings.local.json' in paths})
for folder, expected_count in (('dec-collect-assembler-commit-20261120', 36), ('review-a926727c', 18)):
    base, bad, count = 'docs/evidence/' + folder + '/', [], 0
    for line in git('show', SHA + ':' + base + 'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not line.strip():
            continue
        m = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line)
        if not m:
            bad.append({'format': line})
            continue
        h, rel = m.groups()
        p = posixpath.normpath(base + rel)
        b = git('show', SHA + ':' + p)
        count += 1
        if digest(b) != h or (ROOT / p).read_bytes() != b:
            bad.append(p)
    check('Manifest ' + folder, count == expected_count and not bad, {'entries': count, 'bad': bad})
oldp = EV / 'dec-collect-assembler-20261120/assemble.py'
newp = EV / 'dec-collect-assembler-commit-20261120/assemble.py'
old, new = ast.parse(oldp.read_text(encoding='utf-8')), ast.parse(newp.read_text(encoding='utf-8'))
for name in ('sha', 'wall', 'load_clock', 'clock_check'):
    a, b = [next(x for x in t.body if isinstance(x, ast.FunctionDef) and x.name == name) for t in (old, new)]
    check('Unchanged function ' + name, ast.dump(a) == ast.dump(b))
def main_without_finish(t):
    n = next(x for x in t.body if isinstance(x, ast.FunctionDef) and x.name == 'main')
    n.body = [x for x in n.body if not (isinstance(x, ast.FunctionDef) and x.name == 'finish')]
    return ast.dump(n)
check('Assembly main unchanged outside finish', main_without_finish(old) == main_without_finish(new))
spec = importlib.util.spec_from_file_location('review_459', newp)
asm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asm)
saved = json.loads((newp.parent / 'run1/results.json').read_text(encoding='utf-8'))
check('Preserved first-run 3/3 and source hash', saved['passed'] == saved['total'] == 3
      and all(x['pass'] for x in saved['cases'].values()) and saved['assembler_sha256'] == digest(newp.read_bytes()))
verifiers = {}
for case, want in (('normal', True), ('sums_write_fail', False), ('final_rename_fail', False)):
    out = newp.parent / 'run1' / case
    ok, body = asm.verify_committed(str(out))
    verifiers[case] = {'ok': ok, 'reason': None if ok else body}
    check('Read-only verifier ' + case, ok is want and ((out / 'bundle.json').exists() is want)
          and ((out / 'bundle.json.uncommitted').exists() is not want), verifiers[case])
normal = saved['cases']['normal']
check('Normal nine original/parsed files unchanged', len(normal['same_bytes']) == 9 and not normal['diff_bytes']
      and normal['bundle_equal_except_commit'] and all(
          (newp.parent / 'run1/normal' / n).read_bytes() == (oldp.parent / 'run1/normal' / n).read_bytes()
          for n in normal['same_bytes']))
check('Both failure records remain uncommitted', all(saved['cases'][c]['rc'] == 42
      and saved['cases'][c]['cli_verify'][0] == 44 and saved['cases'][c]['direct_bundle_json'] == 'absent'
      for c in ('sums_write_fail', 'final_rename_fail')))
pins = {'read.sql': '9818db5452f66f058c97322ac9aaf73097036997777d10a0af9f6dde05bdae03',
        'parse.py': '0f6fe827c9da513445ce9c52300b8688f8f4f162a99026b90d05943c10341908'}
check('SQL and parser accepted hashes unchanged', all(digest((EV / 'dec-read-gate-v3-20261120' / n).read_bytes()) == h
      for n, h in pins.items()), pins)
attrs = git('check-attr', 'text', 'eol', '--', str(newp.relative_to(ROOT)).replace('\\', '/'),
            'docs/evidence/dec-collect-assembler-commit-20261120/run1/normal/psql_rc.txt').decode().splitlines()
out = {'sha': SHA, 'passed': sum(c['ok'] for c in checks), 'failed': sum(not c['ok'] for c in checks),
       'assembler_sha256': digest(newp.read_bytes()), 'manifest_entries': 54, 'checks': checks,
       'attribute_observation': attrs, 'verifier_results': verifiers}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: out[k] for k in ('passed', 'failed', 'manifest_entries', 'assembler_sha256')}))
raise SystemExit(0 if out['failed'] == 0 else 1)
