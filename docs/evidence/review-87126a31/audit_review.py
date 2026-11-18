"""Director v1.189: fixed Git blobs, saved file inputs and local CLI only.
No network, DB, Docker, Java tests, builds, commit or push.
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
from datetime import datetime
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
E = ROOT / 'docs/evidence'
HEAD = '87126a3138d2c74030952f8c0f93cc1330ad2585'
BASE = '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d'
MODE = '18f3a7956ae28b59a6f4235889fe60cb00c399fa'
COOL = '893364406e4cbbf2ca129680af18d8995e7c90a9'
MERGED = 'ac52715eb5c1d84daa1be7cf231f4ec97e00c149'
checks = []
anchors = {}


def check(name, ok, detail=None):
    checks.append({'check': name, 'passed': bool(ok), 'detail': detail})


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def blob_batch(ref, paths):
    requests = b''.join((ref + ':' + p + '\n').encode() for p in paths)
    data = subprocess.check_output(['git', 'cat-file', '--batch'], input=requests, cwd=ROOT)
    pos = 0
    result = {}
    for path in paths:
        end = data.index(b'\n', pos)
        header = data[pos:end].split()
        assert header[1] == b'blob', (path, header)
        size = int(header[2])
        pos = end + 1
        result[path] = data[pos:pos + size]
        pos += size + 1
    assert pos == len(data)
    return result


def manifest(name, count):
    folder = E / name
    text = (folder / 'SHA256SUMS').read_text('utf-8')
    rows = [line.split(maxsplit=1) for line in text.splitlines()]
    names = [n.lstrip('*') for _, n in rows]
    check('manifest count:' + name, len(rows) == count and len(set(names)) == count, len(rows))
    for (h, _), n in zip(rows, names):
        check('file hash:' + name + '/' + n, digest((folder / n).read_bytes()) == h)
    rel = folder.relative_to(ROOT).as_posix()
    paths = git('ls-tree', '-rz', '--name-only', HEAD, '--', rel).decode().split('\0')
    paths = [p for p in paths if p]
    expected = {rel + '/' + n for n in names} | {rel + '/SHA256SUMS', rel + '/.gitattributes'}
    check('committed manifest coverage:' + name, set(paths) == expected)
    for p, b in blob_batch(HEAD, paths).items():
        check('fixed blob:' + p, b == (ROOT / p).read_bytes())
    anchors[name + '/SHA256SUMS'] = digest((folder / 'SHA256SUMS').read_bytes())


check('HEAD fixed', git('rev-parse', 'HEAD').decode().strip() == HEAD)
check('saved origin/main', git('rev-parse', 'origin/main').decode().strip() == HEAD)
for n, count in [('dec-reconcile-v4-20261118', 241), ('ab-create-v424-20261119', 28),
                 ('dec-pg-cashflow-20261118', 53), ('dec-modecd-20261118', 16),
                 ('review-p7v3-20261118', 5)]:
    manifest(n, count)

v4 = E / 'dec-reconcile-v4-20261118/p7v4'
impl = v4 / 'reconcile.py'
anchors['p7v4/reconcile.py'] = digest(impl.read_bytes())
m = load_module('director_p7v4', impl)
records = {}
pure = cli = 0
for p in sorted((v4 / 'run-v4/cases').glob('*.json')):
    d = json.loads(p.read_text('utf-8'))
    records[p.stem] = d
    if 'input' not in d:
        cli += 1
        check('saved file CLI:' + p.stem, d['rc'] == m.EXIT[d['result']['overall']])
        continue
    pure += 1
    orig = m.order_effects
    if p.stem == 'N11_internal_error_is_error':
        # Use the exact saved message; it is part of the structured result.
        message = d['result']['message']
        def injected(_):
            raise RuntimeError(message)
        m.order_effects = injected
    try:
        x = copy.deepcopy(d['input'])
        r = m.evaluate(x['registry'], x['base'], x['now'])
    finally:
        m.order_effects = orig
    check('saved input result:' + p.stem, r == d['result'])
check('v4 case counts', (pure, cli) == (99, 10), [pure, cli])
for p in (E / 'dec-reconcile-v3-20261118/p7v3/run-v3/cases').glob('*.json'):
    old = json.loads(p.read_text('utf-8'))
    check('old 94 verdict:' + p.stem, old['result']['overall'] == records[p.stem]['result']['overall'])
mut = (v4 / 'mutation.out.txt').read_text('utf-8').splitlines()
check('saved affected mutants', len(mut) == 11 and all('rc=1' in x for x in mut))
check('saved red summary', '109 \ud1b5\uacfc 97 \uc2e4\ud328 12' in (v4 / 'run-v3-red/cases.out.txt').read_text('utf-8'))
check('saved v4 summary', '109 \ud1b5\uacfc 109 \uc2e4\ud328 0' in (v4 / 'run-v4/cases.out.txt').read_text('utf-8'))

# Recheck malformed UTF-8 without touching submitted files or making sockets.
cli_results = []
with tempfile.TemporaryDirectory(prefix='p7-review-') as tmp:
    td = Path(tmp)
    control = records['A1_eth_hold']['input']
    paths = {}
    for key, arg in [('registry', 'registry'), ('base', 'base'), ('now', 'now')]:
        paths[arg] = td / (key + '.json')
        paths[arg].write_text(json.dumps(control[key]), 'utf-8')
    for bad_key, want, code in [('registry', 20, 'REGISTRY_INVALID'), ('now', 10, 'OBSERVATION_INVALID')]:
        bad = td / ('bad-' + bad_key + '.json')
        bad.write_bytes(bytes.fromhex('fffe696e76616c69642d75746638'))
        args = [sys.executable, '-B', '-X', 'utf8', str(impl)]
        for key, path in paths.items():
            args += ['--' + key, str(bad if key == bad_key else path)]
        run = subprocess.run(args, capture_output=True)
        r = json.loads(run.stdout)
        codes = {x['code'] for x in r['anomalies'] + r['holds']}
        check('UTF8 real CLI:' + bad_key, run.returncode == want and code in codes and not run.stderr
              and b'invalid-utf8' not in run.stdout)
        cli_results.append({'input': bad_key, 'rc': run.returncode, 'result': r, 'stderr': run.stderr.decode()})

pg = E / 'dec-pg-cashflow-20261118'
a = load_module('director_assemble', pg / 'assemble.py')
paired = {}
for name, b, n, axis, want, need, forbid, edit, why in a.PAIRS:
    d = json.loads((pg / 'run1/pairs' / (name + '.json')).read_text('utf-8'))
    paired[name] = d
    bs, bh = a.load(str(pg / 'run1/snaps'), b)
    ns, nh = a.load(str(pg / 'run1/snaps'), n)
    kind = 'LIVE' if name.startswith('L') else 'DYNAMIC'
    drop = {(s['kind'], s['id']) for s in ns['db']['sessions'] if s['kind'] == kind} if edit == 'DROP' else ()
    inp = {'registry': a.registry(bs, ns, drop, 'app' if edit == 'APPAXIS' else 'db'),
           'base': a.obs(bs, axis), 'now': a.obs(ns, axis)}
    if edit == 'STALE':
        a.stale_exchange(bs)(inp)
    elif callable(edit):
        edit(inp)
    check('PG pair snapshot lineage:' + name, inp == d['input'] and bh == d['base_snapshot']['sha256']
          and nh == d['now_snapshot']['sha256'])
    x = copy.deepcopy(inp)
    r = m.evaluate(x['registry'], x['base'], x['now'])
    check('PG pair recalculation:' + name, r == d['result'])
check('PG saved pairs count', len(paired) == 30)
for p in (pg / 'run1/gradle').glob('*.xml'):
    x = ET.parse(p).getroot()
    check('saved PG XML', (x.get('tests'), x.get('failures'), x.get('errors'), x.get('skipped')) == ('1', '0', '0', '0'))
clock = json.loads((pg / 'run1/pairs/clock.json').read_text('utf-8'))
offsets, rtts = [], []
for p in (pg / 'run1/snaps').glob('*.json'):
    d = json.loads(p.read_text('utf-8'))
    offs = [(a.ts(s['at_db']) - a.ts(s['at'])).total_seconds() * 1000 for s in d['steps']]
    offsets.extend(offs)
    rtts.extend(s['rtt_ms'] for s in d['steps'])
    check('clock saved source:' + p.stem, clock[p.stem] == {
        'offset_ms_min': round(min(offs), 3), 'offset_ms_max': round(max(offs), 3),
        'rtt_ms_max': max(s['rtt_ms'] for s in d['steps']), 'sha256': digest(p.read_bytes())})
check('clock sample count', len(offsets) == 75)
check('clock observed varying offset', min(offsets) < 1400 < max(offsets), [min(offsets), max(offsets), max(rtts)])

# Same raw snapshot, only the declared comparison axis changes the verdict.
probes = {'malformed_utf8': cli_results, 'clock_cases': {}}
for prefix in ('L', 'D'):
    keys = [prefix + '01_new_allocation', prefix + '01b_new_allocation_db_axis_limit', prefix + '09_collector_clock_axis']
    probes['clock_cases'][prefix] = {key: paired[key] for key in keys}
    check('saved clock false anomaly:' + prefix, [paired[k]['result']['overall'] for k in keys] == ['OK', 'ANOMALY', 'HOLD'])

new = E / 'ab-create-v424-20261119'
old = E / 'ab-create-v424-20261118'
bundle = new / 'bundle/SHA256SUMS.bundle'
check('same bundle manifest', bundle.read_bytes() == (old / 'bundle/SHA256SUMS.bundle').read_bytes())
for line in bundle.read_text('utf-8').splitlines():
    h, path = line.split(maxsplit=1)
    path = path.lstrip('*')
    check('unchanged bundle body:' + path, digest((new / path).read_bytes()) == h
          and (new / path).read_bytes() == (old / path).read_bytes())
for stem in ('steps', 'deploy'):
    text_new = (new / (stem + '-20261119.sh')).read_text('utf-8')
    text_old = (old / (stem + '-20261118.sh')).read_text('utf-8')
    norm = text_new.replace('20261119', '20261118').replace('2026-11-19', '2026-11-18').replace('2026-11-20', '2026-11-19')
    norm = norm.replace('db0bc080e7c62e6628b5b55736c4e5a764aafa02014f01c10e429f75c2cf1d55',
                        'bdf0a76448f1fd40f3561c0166acd9129003d69b6fbfa62dec8c5465b4acdff0')
    # Header comments name the new director edition. Executable lines must be identical after approved substitutions.
    code = lambda x: '\n'.join(line for line in x.splitlines() if not line.lstrip().startswith('#'))
    check('date only executable diff:' + stem, code(norm) == code(text_old))
    run = subprocess.run(['C:/Program Files/Git/bin/bash.exe', '--noprofile', '--norc', '-n', str(new / (stem + '-20261119.sh'))], capture_output=True)
    check('date shell syntax:' + stem, run.returncode == 0)

check('integration parent', git('show', '-s', '--format=%P', MERGED).decode().strip() == MODE)
patchid = lambda sha: subprocess.check_output(['git', 'patch-id', '--stable'], input=git('show', '--pretty=format:', sha), cwd=ROOT).split()[0]
check('integration original patch', patchid(MERGED) == patchid(COOL))
check('integration saved diff', git('diff', BASE, MERGED) == (E / 'dec-modecd-20261118/candidate-ac52715e.diff').read_bytes())
totals = [0, 0, 0, 0]
xmls = list((E / 'dec-modecd-20261118/xml').glob('*.xml'))
for p in xmls:
    x = ET.parse(p).getroot()
    vals = [int(x.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')]
    totals = [a + b for a, b in zip(totals, vals)]
check('integration XML totals', len(xmls) == 12 and totals == [101, 0, 0, 0], totals)

result = {'fixed_head': HEAD, 'candidate': MERGED, 'input_anchors': anchors,
          'scope': 'Fixed blobs and pure saved-input/CLI audit; no new Java or PG execution',
          'passed': sum(c['passed'] for c in checks), 'failed': sum(not c['passed'] for c in checks), 'checks': checks}
for filename, data in [('checks.json', result), ('observations.json', probes)]:
    with (OUT / filename).open('w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, ensure_ascii=False, indent=1, default=str)
        f.write('\n')
print(json.dumps({'passed': result['passed'], 'failed': result['failed'], 'failures': [c for c in checks if not c['passed']]}, ensure_ascii=False))
sys.exit(1 if result['failed'] else 0)
