"""Director v1.188: local uncommitted evidence, pure calculations and file CLI only.
No production/network/DB/Java/Docker/build/commit/push operations.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SRC = ROOT / 'docs/evidence/dec-reconcile-v3-20261118'
P7 = SRC / 'p7v3'
checks = []


def check(name, ok, detail=None):
    checks.append(dict(check=name, passed=bool(ok), detail=detail))


def manifest(folder, expected):
    names = []
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        h, n = line.split(maxsplit=1)
        n = n.lstrip('*')
        b = (folder / n).read_bytes()
        check('sha256:' + str((folder / n).relative_to(ROOT)), hashlib.sha256(b).hexdigest() == h)
        names.append(n)
    check('manifest count:' + folder.name, len(names) == expected, len(names))
    return names


names = manifest(SRC, 207)
actual = {str(p.relative_to(SRC)).replace('\\', '/') for p in SRC.rglob('*') if p.is_file()}
check('submitted manifest coverage', actual == set(names) | {'SHA256SUMS'}, sorted(actual - set(names) - {'SHA256SUMS'}))
manifest(ROOT / 'docs/evidence/review-c2a09f9c', 6)
# Verify the original v2 from committed blobs as well as its working bytes.
old = 'docs/evidence/dec-reconcile-20261118'
for line in (ROOT / old / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
    h, n = line.split(maxsplit=1)
    n = n.lstrip('*')
    b = subprocess.check_output(['git', 'show', f'c2a09f9c:{old}/{n}'], cwd=ROOT)
    check('preserved v2:' + n, hashlib.sha256(b).hexdigest() == h and b == (ROOT / old / n).read_bytes())

spec = importlib.util.spec_from_file_location('reviewed_p7v3', P7 / 'reconcile.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
records = {}
for path in sorted((P7 / 'run-v3/cases').glob('*.json')):
    d = json.loads(path.read_text(encoding='utf-8'))
    records[path.stem] = d
    if 'input' not in d:
        check('saved CLI:' + path.stem, d['rc'] == m.EXIT[d['result']['overall']])
        continue
    inputs = copy.deepcopy(d['input'])
    orig = m.order_effects
    if path.stem == 'N11_internal_error_is_error':
        def injected(_):
            raise RuntimeError('\uc2dc\ud5d8\uc6a9 \ub0b4\ubd80 \uc608\uc678')
        m.order_effects = injected
    try:
        got = m.evaluate(inputs['registry'], inputs['base'], inputs['now'])
    finally:
        m.order_effects = orig
    check('pure saved result:' + path.stem, got == d['result'])
    if 'source_sha256' in d:
        original = ROOT / 'docs/evidence' / d['source']
        check('source input:' + path.stem, hashlib.sha256(original.read_bytes()).hexdigest() == d['source_sha256'])
check('94 saved cases / 87 pure / 7 CLI', len(records) == 94 and sum('input' in d for d in records.values()) == 87)
for name, passed, failed in [('run-v2-red', 40, 54), ('run-v3', 94, 0)]:
    lines = (P7 / name / 'cases.out.txt').read_text(encoding='utf-8').splitlines()
    check('saved summary:' + name, sum(l.startswith('PASS |') for l in lines) == passed and sum(l.startswith('FAIL |') for l in lines) == failed)
mutants = (P7 / 'mutation.out.txt').read_text(encoding='utf-8').splitlines()
check('16 saved mutation detections', len(mutants) == 16 and all(re.match(r'V\d\d_.* rc=1 ', l) for l in mutants))

probes = []


def probe(name, source, edit, expected):
    box = copy.deepcopy(records[source]['input'])
    edit(box)
    result = m.evaluate(box['registry'], box['base'], box['now'])
    probes.append(dict(name=name, source=source, input=box, result=result))
    check('observed probe:' + name, expected(result), result['overall'])


probe('C0_unchanged_A1', 'A1_eth_hold', lambda _: None, lambda r: r['overall'] == 'OK')
probe('C1_unchanged_B1', 'B1_preexisting_btc', lambda _: None, lambda r: r['overall'] == 'OK')


def missing_coverage(box):
    for side in ('exchange_before', 'exchange_after'):
        box['now'][side].pop('done_query', None)


probe('U1_no_new_db_fill_and_missing_exchange_coverage', 'B1_preexisting_btc', missing_coverage,
      lambda r: r['overall'] == 'OK' and r['exchange_coverage']['query'] is None)


def unknown_state(box):
    box['now']['db']['orders'][0]['state'] = 'FILLED_TYPO'


probe('U2_unknown_order_state_with_fill', 'A1_eth_hold', unknown_state, lambda r: r['overall'] == 'OK')

# Exercise the real CLI with a bad encoding, rather than injecting evaluate().
with tempfile.TemporaryDirectory(prefix='p7v3-review-') as td:
    tmp = Path(td)
    src = records['A1_eth_hold']['input']
    for k in ('registry', 'base', 'now'):
        (tmp / (k + '.json')).write_text(json.dumps(src[k]), encoding='utf-8')
    bad = tmp / 'bad-utf8.json'
    bad.write_bytes(b'\xff\xfeinvalid-utf8')
    command = [sys.executable, '-B', '-X', 'utf8', str(P7 / 'reconcile.py'), '--registry', str(bad),
               '--base', str(tmp / 'base.json'), '--now', str(tmp / 'now.json')]
    r = subprocess.run(command, capture_output=True, text=True, encoding='utf-8')
    try:
        json.loads(r.stdout)
        has_json = True
    except json.JSONDecodeError:
        has_json = False
    probes.append(dict(name='U3_invalid_utf8_registry_file', input_hex=bad.read_bytes().hex(),
                       rc=r.returncode, stdout=r.stdout, stderr=r.stderr, structured_json=has_json))
    check('observed probe:U3_invalid_utf8_registry_file', r.returncode == 1 and not has_json and 'UnicodeDecodeError' in r.stderr)

summary = dict(total=len(checks), passed=sum(c['passed'] for c in checks), failed=sum(not c['passed'] for c in checks))
anchors = {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest()
           for p in (SRC / 'SHA256SUMS', P7 / 'reconcile.py', P7 / 'cases.py', P7 / 'mutate.py')}
for name, value in [('checks.json', dict(summary=summary, input_anchors=anchors, checks=checks)), ('counterexamples.json', probes)]:
    with (OUT / name).open('w', encoding='utf-8', newline='\n') as f:
        json.dump(value, f, ensure_ascii=False, indent=1)
        f.write('\n')
print(json.dumps(summary))
sys.exit(bool(summary['failed']))
