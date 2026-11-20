"""Fixed-blob audit and focused file-contract checks; no DB, network, or test runner."""
import ast
import copy
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
SHA = '73c23938f94a862af58f7b24afa50c764ca3bb67'
checks, probes = [], {}

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *args], cwd=ROOT)

def digest(b):
    return hashlib.sha256(b).hexdigest()

def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})

def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m

check('HEAD and saved origin/main', all(git('rev-parse', x).decode().strip() == SHA
      for x in ('HEAD', 'origin/main')), 'Saved refs; no remote query')
paths = git('diff', '--name-only', SHA + '^', SHA).decode().splitlines()
check('Submission only evidence and three management docs', all(p.startswith('docs/evidence/') or p in
      ('docs/LIMITED_LIVE_TEST_PLAN.md', 'docs/CURRENT_HANDOFF.md', 'docs/PROGRESS.md') for p in paths),
      {'changed_paths': len(paths), 'settings_included': '.claude/settings.local.json' in paths})
manifests = []
for folder in ('dec-f3-v9-20261120', 'dec-collect-contract-20261120', 'review-170395d3'):
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
        work = (ROOT / path).read_bytes()
        n += 1
        if digest(blob) != expected or work != blob:
            bad.append(path)
    manifests.append({'folder': folder, 'entries': n, 'mismatches': bad})
    check('Manifest ' + folder, not bad, manifests[-1])

f8p = EV / 'dec-registry-p7v8-20261120/p7v8/f3.py'
f9p = EV / 'dec-f3-v9-20261120/f3v9/f3.py'
old, new = ast.parse(f8p.read_text(encoding='utf-8')), ast.parse(f9p.read_text(encoding='utf-8'))
# Remove the documented two guards and module docstring, then compare executable ASTs.
old.body = old.body[1:]
new.body = new.body[1:]
shape = next(n for n in new.body if isinstance(n, ast.FunctionDef) and n.name == 'shape')
shape.body = [n for n in shape.body if not (isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'cov' for t in n.targets))
              and not (isinstance(n, ast.If) and any(isinstance(x, ast.Name) and x.id == 'cov' for x in ast.walk(n.test)))]
judge = next(n for n in new.body if isinstance(n, ast.FunctionDef) and n.name == 'judge')
judge.body = [n for n in judge.body if not (isinstance(n, ast.If) and isinstance(n.test, ast.Name) and n.test.id == 'why')]
check('Executable F3 changes limited to two guards', ast.dump(old) == ast.dump(new))
f8, f9 = load(f8p, 'review_f8'), load(f9p, 'review_f9')
saved = json.loads((EV / 'dec-f3-v9-20261120/probe-v9.out.json').read_text(encoding='utf-8'))
check('Preserved CLI results 13/13', saved['all_ok'] and saved['passed'] == saved['total'] == 13
      and len(saved['cases']) == 13 and all(x['ok'] and x['rc'] == x['want_rc'] for x in saved['cases'])
      and [x['rc'] for x in saved['cases']].count(0) == 1
      and [x['rc'] for x in saved['cases']].count(1) == 9
      and [x['rc'] for x in saved['cases']].count(2) == 3)
cases = load(EV / 'dec-registry-p7v8-20261120/p7v8/cases_v8.py', 'review_cases')
p7 = cases.V8
normal = cases.ev(p7, cases.P0)
check('Normal F3 unchanged', f8.judge(normal) == f9.judge(normal) == (True, []))
probes['f3_guards'] = []
for key in ('code', 'exchange_coverage', 'account', 'account_sum', 'exchange_coverage.window'):
    value = copy.deepcopy(normal)
    if '.' in key:
        value['exchange_coverage']['window'] = ['x']
    else:
        value[key] = ['x']
    try:
        f8.judge(value)
        before = 'no exception'
    except AttributeError:
        before = 'AttributeError'
    after = f9.judge(value)
    probes['f3_guards'].append({'field': key, 'before': before, 'after': after})
check('Five formerly exceptional types return nonpass with reasons', all(x['before'] == 'AttributeError'
      and x['after'][0] is False and x['after'][1] for x in probes['f3_guards']))

pins = {'read.sql': '9818db5452f66f058c97322ac9aaf73097036997777d10a0af9f6dde05bdae03',
        'parse.py': '0f6fe827c9da513445ce9c52300b8688f8f4f162a99026b90d05943c10341908'}
for name, expected in pins.items():
    path = 'docs/evidence/dec-read-gate-v3-20261120/' + name
    check('Unchanged accepted ' + name, digest(git('show', SHA + ':' + path)) == expected
          and (ROOT / path).read_bytes() == git('show', SHA + ':' + path), expected)

# Contract translation probes: C0..C6 are orchestration, not P7's five collection stages.
meta = copy.deepcopy(cases.P0['base']['meta'])
t = meta['steps'][0].get('at') or meta['steps'][0]['start']
meta['steps'] = [{'step': 'C' + str(i), 'start': t, 'end': t} for i in range(7)]
probs = []
p7.v_meta(probs, 'meta', meta)
probes['orchestration_steps_as_p7'] = {'problems': probs, 'required_steps': p7.STEPS}
check('Literal C0..C6 is rejected as P7 steps', bool(probs) and 'db_extract' in str(probs))
sample = {'pair': ['db', 'collector'], 'c0': '2026-11-20T00:00:00Z',
          'd': '2026-11-20T00:00:01Z', 'c1': '2026-11-20T00:00:02Z', 'mono_rtt_ms': '2000'}
probs = []
p7.v_clock(probs, 'clock', {'version': p7.CLOCK_VERSION, 'samples': [sample], 'same_source': []})
probes['sample_without_step'] = probs
check('Contract sample needs mandatory step', bool(probs))
sample['step'] = 'db_extract'
probs = []
p7.v_clock(probs, 'clock', {'version': p7.CLOCK_VERSION, 'samples': [sample], 'same_source': []})
check('Named db_extract sample has valid shape', not probs, probs)
ob = copy.deepcopy(cases.P0['base'])
ob['meta']['steps'] = [{'step': s, 'start': sample['c0'], 'end': sample['c1']} for s in p7.STEPS]
ob['meta']['clock'] = {'version': p7.CLOCK_VERSION, 'samples': [sample], 'same_source': []}
ctx = p7.build_clock('one-sample', ob)
prior = p7.convert(p7.tstamp('2026-11-20T00:00:00.500000Z'), 'db', 'collector', [ctx])
at_d = p7.convert(p7.tstamp(sample['d']), 'db', 'collector', [ctx])
probes['single_sample_coverage'] = {'db_point_before_sample': prior,
    'sample_point_interval': [x.isoformat() for x in at_d] if at_d else None,
    'report': ctx.report}
check('Single sample does not extrapolate to earlier DB point', prior is None and at_d is not None)

out = {'sha': SHA, 'passed': sum(c['ok'] for c in checks), 'failed': sum(not c['ok'] for c in checks),
       'manifest_entries': sum(m['entries'] for m in manifests), 'f3_v9_sha256': digest(f9p.read_bytes()), 'checks': checks}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(HERE / 'contract-probes.json').write_text(json.dumps(probes, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: out[k] for k in ('passed', 'failed', 'manifest_entries', 'f3_v9_sha256')}))
raise SystemExit(0 if out['failed'] == 0 else 1)
