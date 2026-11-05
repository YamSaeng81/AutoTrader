"""Review fixed Git objects, preserved XML, R blocks and existing local JARs.

No Gradle, server, network, database, or deployment commands are run.
"""
from pathlib import Path
import collections
import hashlib
import io
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SCRATCH = ROOT / 'core-engine/build/review-k23-6287c59'
SCRATCH.mkdir(parents=True, exist_ok=True)
REF = '6287c59'
BASE = 'fe01207f3f60e97e308925157f84a1bb2da04388'
N15 = 'd6f7e18924bbef785c5628200a8101a807e4948a'
N16 = 'ac29ecf11da9529fd3d83a6b28ae8756c4e89c9a'
INTEGRATION = '51dcee530721c0ab23f3f6f875a15b9416b751a3'
P = 'web-api/src/main/java/com/cryptoautotrader/api/service/'
T = 'web-api/src/test/java/com/cryptoautotrader/api/service/'
DEV = ROOT / 'docs/evidence/k23-prep-51dcee5'
BASH = Path('C:/Program Files/Git/bin/bash.exe')


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def src(ref, path):
    return git('show', f'{ref}:{path}').decode('utf-8')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def manifest(name, count):
    folder = ROOT / 'docs/evidence' / name
    rows = []
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        want, filename = line.split(' ', 1)
        filename = filename.strip().lstrip('*')
        p = folder / filename
        assert sha(p.read_bytes()) == want, filename
        assert sha(git('show', f'{REF}:{p.relative_to(ROOT).as_posix()}')) == want, filename
        rows.append({'path': filename, 'sha256': want})
    assert len(rows) == count, (name, len(rows))
    return {'raw_and_git_objects_match': len(rows)}


def suites(folder):
    result = []
    for p in sorted(folder.glob('TEST-*.xml')):
        x = ET.parse(p).getroot()
        failures = [{'test': t.attrib['name'], 'message': f.attrib.get('message', '')}
                    for t in x.findall('testcase') for f in t.findall('failure')]
        result.append({'class': x.find('testcase').attrib['classname'], **{k: int(x.attrib.get(k, 0))
                       for k in ('tests', 'failures', 'errors', 'skipped')}, 'failure_details': failures})
    assert result, folder
    return {'classes': len(result), **{k: sum(s[k] for s in result)
            for k in ('tests', 'failures', 'errors', 'skipped')}, 'suites': result}


manifests = {name: manifest(name, n) for name, n in [
    ('n15-final-boundary-d6f7e18', 31), ('k23-prep-51dcee5', 117)]}
n15folder = ROOT / 'docs/evidence/n15-final-boundary-d6f7e18'
results = {'n15_before': suites(n15folder / 'before'),
           'n15_after': suites(n15folder / 'after'),
           'integration': suites(DEV / 'tests/integ-run2'),
           'sma20_alone': suites(DEV / 'tests/integ-dailysma20-alone')}
for i in range(1, 4):
    results[f'baseline_run{i}'] = suites(DEV / f'tests/baseline-fe01207/run{i}')
for key, values in [('n15_before', (6, 5)), ('n15_after', (99, 0)),
                    ('integration', (173, 1)), ('sma20_alone', (24, 0)),
                    ('baseline_run1', (49, 0)), ('baseline_run2', (49, 1)),
                    ('baseline_run3', (49, 1))]:
    s = results[key]
    assert (s['tests'], s['failures']) == values
    assert s['errors'] == s['skipped'] == 0
for k in ('integration', 'baseline_run2', 'baseline_run3'):
    failing = [s for s in results[k]['suites'] if s['failures']]
    assert len(failing) == 1 and failing[0]['class'].endswith('DailySma20LiveOrderPathTest')
    assert '회차 마감 뒤 도착' in failing[0]['failure_details'][0]['message']
assert results['n15_after']['classes'] == 16
assert results['integration']['classes'] == 25
other = [s for s in results['integration']['suites'] if not s['class'].endswith('DailySma20LiveOrderPathTest')]
assert sum(s['tests'] for s in other) == 149 and all(s['failures'] == 0 for s in other)
for name, count in [('N15EntryExpiryTest', 6), ('N15FinalApprovalBoundaryTest', 6), ('N16InvalidPriceReproTest', 13)]:
    s = next(s for s in results['integration']['suites'] if s['class'].endswith('.' + name))
    assert s['tests'] == count and s['failures'] == 0

assert git('rev-parse', N15 + '^').decode().strip() == git('rev-parse', '6f6a522').decode().strip()
for parent in (BASE, N15, N16):
    subprocess.run(['git', 'merge-base', '--is-ancestor', parent, INTEGRATION], cwd=ROOT, check=True)
paths = git('diff', '--name-only', BASE, INTEGRATION).decode().splitlines()
assert len(paths) == 11 and all(p.endswith('.java') for p in paths)
assert sum('/src/main/' in p for p in paths) == 8
unchanged_from_candidate = []
for p in paths:
    if p in (P + 'LiveTradingService.java', P + 'DynamicTradingService.java'):
        s = src(INTEGRATION, p)
        assert 'MarkPriceBook.isPrice(price)' in s and 'entryExpiresAt' in s
        for a, b in [(BASE, N15), (BASE, N16)]:
            patch = git('diff', '--unified=0', a, b, '--', p).decode()
            additions = [l[1:] for l in patch.splitlines() if l.startswith('+') and not l.startswith('+++')]
            assert all(line in s.splitlines() for line in additions), p
    else:
        owner = N16 if p.endswith(('MarkPriceBook.java', 'N16InvalidPriceReproTest.java')) else N15
        assert git('show', f'{owner}:{p}') == git('show', f'{INTEGRATION}:{p}')
        unchanged_from_candidate.append(p)
assert len(unchanged_from_candidate) == 9
for test in ('DailySma20LiveOrderPathTest', 'BtcGateUnknownEntryTest'):
    assert src(BASE, T + test + '.java') == src(INTEGRATION, T + test + '.java')
test15 = T + 'N15FinalApprovalBoundaryTest.java'
assert git('show', f'{N15}:{test15}') == (n15folder / 'tests/N15FinalApprovalBoundaryTest.java').read_bytes()

reservation = src(INTEGRATION, P + 'CapitalReservationService.java')
reserve = reservation[reservation.index('public Decision reserveForBuy(String kind, Long sessionId, BigDecimal amount, Long selfPositionId, Instant'):]
reserve = reserve[:reserve.index('public void releaseWithinTransaction')]
assert reserve.count('entryExpiry(entryExpiresAt, "차감 직전")') == 2
assert reserve.index('em.refresh(session,') < reserve.rindex('entryExpiry(entryExpiresAt, "차감 직전")') < reserve.rindex('session.setAvailableKrw(after)')
assert reserve.index('checkAccountHeadroom(') < reserve.index('entryExpiry(entryExpiresAt, "차감 직전")') < reserve.index('session.setAvailableKrw(after)')
engine = src(INTEGRATION, P + 'OrderExecutionEngine.java')
prepare = engine[engine.index('private Long prepareIntent('):]
assert prepare.index('approveNewEntry(request, sessionKind)') < prepare.index('entryExpiry(request.getEntryExpiresAt(), "의도 직전")') < prepare.index('OrderEntity order =')
assert prepare.index('throw new IntentExpired(') < prepare.index('newClientOrderId()') < prepare.index('registerDispatching(')
catch = engine[engine.index('} catch (IntentExpired expired) {'):]
assert catch.index('releaseWithinTransaction(') < catch.index('throw new ReservationRejected(CapitalReservationService.ENTRY_EXPIRED')
assert src(INTEGRATION, P + 'MarkPriceBook.java') == src(N16, P + 'MarkPriceBook.java')
for method in ('claimOwned', 'claimForSubmission', 'callExchangeAndRecord'):
    # Preserved diffs were also read; expiry changes do not alter committed-order execution methods.
    assert not re.search(r'^[-+]    (?:private|public).*\b' + method + r'\(', git('diff', BASE, INTEGRATION, '--', P + 'OrderExecutionEngine.java').decode(), re.M)

rounds = src(INTEGRATION, P + 'EntryPriorityRounds.java')
daily = src(BASE, T + 'DailySma20LiveOrderPathTest.java')
btc = src(BASE, T + 'BtcGateUnknownEntryTest.java')
assert 'private final Map<RoundKey, Instant> closed = new HashMap<>()' in rounds
assert 'closed.containsKey(c.round()) || now.isAfter(deadline(c.round()))' in rounds
assert 'closed.put(e.getKey(), now)' in rounds
assert 'LAST_ROUND.updateAndGet' in daily and 'truncatedTo(ChronoUnit.SECONDS)' in daily
assert 'truncatedTo(ChronoUnit.SECONDS)' in btc and 'lastOpen.minusSeconds(5)' in btc

blocks_text = (DEV / 'blocks/r-blocks.md').read_text(encoding='utf-8')
blocks = re.findall(r'^```bash\n(.*?)^```', blocks_text, re.M | re.S)
assert len(blocks) == 8
doc = src(REF, 'docs/LIMITED_LIVE_TEST_PLAN.md')
allblocks = re.findall(r'^```bash\n(.*?)^```', doc, re.M | re.S)
assert allblocks[-8:] == blocks and len(allblocks) == 109
hash_rows = json.loads((DEV / 'local-check/blocks.json').read_text(encoding='utf-8'))
syntax = []
for i, (block, (name, want)) in enumerate(zip(blocks, hash_rows)):
    assert sha(block.encode()) == want
    p = SCRATCH / f'block-{i}.sh'
    p.write_bytes(block.encode())
    result = subprocess.run([str(BASH), '--noprofile', '--norc', '-n', str(p)], capture_output=True)
    assert result.returncode == 0, result.stderr
    syntax.append({'block': name, 'sha256': want, 'bash_n': 0})

cmp_code = (DEV / 'jars/jarcmp.py').read_text(encoding='utf-8')
assert cmp_code == (ROOT / 'docs/evidence/n13-deploy-prep-fe01207/jarcmp.py').read_text(encoding='utf-8')
assert cmp_code in blocks[2]
jarhome = Path('D:/tmp/n15final/jars')
jars = []
for line in (DEV / 'jars/jar-sha.txt').read_text(encoding='utf-8').splitlines():
    want, filename = line.split()
    p = jarhome / filename
    assert sha(p.read_bytes()) == want
    with zipfile.ZipFile(p) as z:
        duplicates = [n for n, c in collections.Counter(z.namelist()).items() if c > 1]
        nested = {}
        for n in z.namelist():
            if re.match(r'BOOT-INF/lib/(core-engine|strategy-lib|exchange-adapter)[^/]*\.jar$', n):
                with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                    nested[n] = [k for k, c in collections.Counter(inner.namelist()).items() if c > 1]
        assert not duplicates and not any(nested.values())
    jars.append({'file': filename, 'sha256': want, 'outer_duplicate_names': duplicates, 'own_module_duplicate_names': nested})
jar_checks = []
for a, b, expected in [('fe01207.jar', '51dcee5.jar', 'jar-diff.txt'),
                       ('51dcee5.jar', '51dcee5-rerun.jar', 'jar-diff-rebuild.txt')]:
    result = subprocess.run([sys.executable, '-B', '-X', 'utf8', str(DEV / 'jars/jarcmp.py'), str(jarhome/a), str(jarhome/b)],
                            capture_output=True, check=True)
    text = result.stdout.decode().replace('\r\n', '\n')
    assert text == (DEV / 'jars' / expected).read_text(encoding='utf-8')
    jar_checks.append({'left': a, 'right': b, 'output': text})

result = {'reviewed_main': git('rev-parse', REF).decode().strip(), 'base': BASE,
          'N15': N15, 'N16': N16, 'integration_draft': INTEGRATION,
          'manifests': manifests, 'preserved_XML': results,
          'paths': paths, 'nine_files_exact_from_candidates': unchanged_from_candidate,
          'two_overlapping_files_preserve_both_diffs': True,
          'N15_final_boundary_and_same_transaction_refund': 'source and preserved H2 tests reviewed; no new DB execution',
          'test_collision': 'unchanged fixtures use second-resolution keys; shared closed-round retention is normal production behavior; baseline reproduces same failure',
          'total': '172/173, not a completed integration pass; other 24 classes 149/149 and SMA20 23/24',
          'R_blocks': syntax, 'existing_local_jars': jars, 'rerun_of_jar_item_comparison': jar_checks,
          'no_Gradle_bootJar_server_database_network_or_deployment': True}
(OUT / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifests': {k: v['raw_and_git_objects_match'] for k, v in manifests.items()},
                  'N15': '99/99', 'integration': '172/173', 'R_bash_n': 8,
                  'jar_hashes': 3, 'jar_comparisons': 2}, ensure_ascii=False))
