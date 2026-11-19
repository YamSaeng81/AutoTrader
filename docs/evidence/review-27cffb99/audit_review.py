"""Read fixed Git blobs and saved artifacts; never run the application or tests."""
from pathlib import Path
from collections import Counter
import hashlib
import io
import json
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHA = '27cffb99985557c371d0ad1b7a03c7d560188776'
FINAL = 'd81c3f2f91c0239aacb0e1176dde9b898f4d7466'
P = 'docs/evidence/dec-final-abc-20261119/'
checks, observations = [], {}

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def check(name, passed, actual=None):
    checks.append({'check': name, 'pass': bool(passed), 'actual': actual})

def blob(sha, path):
    return git('show', sha + ':' + path)

def tree(sha, prefix):
    return git('-c', 'core.quotepath=false', 'ls-tree', '-r', '--name-only', sha, '--', prefix).decode().splitlines()

def batch(paths):
    result = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
        input=''.join(SHA + ':' + p + '\n' for p in paths).encode(),
        stdout=subprocess.PIPE, check=True).stdout
    stream, out = io.BytesIO(result), {}
    for path in paths:
        header = stream.readline().decode().split()
        assert len(header) == 3 and header[1] == 'blob', (path, header)
        out[path] = stream.read(int(header[2]))
        assert stream.read(1) == b'\n'
    assert not stream.read()
    return out

changed = git('diff-tree', '--no-commit-id', '--name-only', '-r', SHA).decode().splitlines()
paths = tree(SHA, P) + tree(SHA, 'docs/evidence/review-e97ca31b/')
saved = batch(paths)
bad_local = [p for p, b in saved.items() if not (ROOT / p).is_file() or (ROOT / p).read_bytes() != b]
check('submitted paths and evidence byte equality', len(changed) == 354 and len(saved) == 351 and not bad_local,
      {'changed': len(changed), 'evidence': len(saved), 'mismatch': bad_local})
newline_only = [p for p in bad_local if (ROOT / p).read_bytes().replace(b'\r\n', b'\n') == saved[p]]
check('302 differing committed files are CRLF-to-LF conversion only', len(bad_local) == 302 and newline_only == bad_local,
      {'differing': len(bad_local), 'newline_only': len(newline_only)})
check('permission settings not submitted', '.claude/settings.local.json' not in changed)
missing_manifest_files = []
for prefix, count in [(P, 355), ('docs/evidence/review-e97ca31b/', 5)]:
    lines = saved[prefix + 'SHA256SUMS'].decode('utf-8-sig').splitlines()
    rows = [re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line) for line in lines]
    assert all(rows)
    missing = [m for m in rows if prefix + m[2].removeprefix('./') not in saved]
    bad = [m[2] for m in rows if prefix + m[2].removeprefix('./') in saved and
        hashlib.sha256(saved[prefix + m[2].removeprefix('./')]).hexdigest() != m[1]]
    check(prefix + ' available committed checksums', len(rows) == count and not bad,
        {'listed': len(rows), 'committed': len(rows) - len(missing), 'mismatch': bad})
    if prefix == P:
        check('355 original local files still match submitted manifest', len(rows) == 355 and all(
            hashlib.sha256((ROOT / (prefix + m[2].removeprefix('./'))).read_bytes()).hexdigest() == m[1] for m in rows))
    for m in missing:
        path = prefix + m[2].removeprefix('./')
        local = ROOT / path
        missing_manifest_files.append({'path': path, 'local_exists': local.is_file(),
            'local_hash_matches': local.is_file() and hashlib.sha256(local.read_bytes()).hexdigest() == m[1]})
check('all manifest-listed evidence committed', not missing_manifest_files, missing_manifest_files)
check('11 missing logs remain locally with original bytes', len(missing_manifest_files) == 11 and
    all(x['local_exists'] and x['local_hash_matches'] for x in missing_manifest_files))
observations['missing_committed_logs'] = missing_manifest_files

def data(rel):
    if P + rel in saved:
        return saved[P + rel]
    # Inspect missing local logs explicitly; never describe these as committed blobs.
    assert any(x['path'] == P + rel for x in missing_manifest_files)
    return (ROOT / (P + rel)).read_bytes()

def src(sha, filename):
    return blob(sha, 'web-api/src/test/java/com/cryptoautotrader/api/service/' + filename + '.java').decode()

def diff_names(a, b, *filters):
    return git('diff', '--name-only', a, b, '--', *filters).decode().splitlines()

commits = data('integration-commits.txt').decode()
pairs = re.findall(r'^([0-9a-f]{8}) -> ([0-9a-f]{8}) ', commits, re.M)
mapping = []
for old, new in pairs:
    def patch_id(c):
        return subprocess.check_output(['git', 'patch-id', '--stable'], cwd=ROOT,
            input=git('show', '--format=', c)).decode().split()[0]
    mapping.append({'old': old, 'new': new, 'equal': patch_id(old) == patch_id(new)})
check('seven imported patches equal original patches', len(mapping) == 7 and all(x['equal'] for x in mapping), mapping)
prod = diff_names('60df3437', FINAL, '*/src/main/*')
check('ABC to final product scope', prod == ['web-api/src/main/java/com/cryptoautotrader/api/service/OrderSettlementService.java'], prod)
check('BUY candidate to final only three test files', diff_names('596ed4b0', FINAL) == [
    'web-api/src/test/java/com/cryptoautotrader/api/service/' + n + '.java'
    for n in ['DailySma20LiveOrderPathTest', 'LiveLossWindowPgTest', 'RealLossGlobalPgTest']])
for n, sha, kind in [('DailySma20LiveOrderPathTest', '09fa2d1c', 'test'),
                     ('AbcCrossPgTest', '596ed4b0', 'test'), ('RealBuySummaryPgTest', '596ed4b0', 'test'),
                     ('OrderSettlementService', '596ed4b0', 'main')]:
    path = f'web-api/src/{kind}/java/com/cryptoautotrader/api/service/{n}.java'
    check(n + ' final blob equals accepted source', blob(FINAL, path) == blob(sha, path))
check('0401 to final only RealLossGlobal test', diff_names('0401d0e9', FINAL) == [
    'web-api/src/test/java/com/cryptoautotrader/api/service/RealLossGlobalPgTest.java'])
check('no frontend change', not diff_names('0e1d2a02', FINAL, 'frontend'))

fixture_diff = git('diff', '60df3437', FINAL, '--',
    'web-api/src/test/java/com/cryptoautotrader/api/service/LiveLossWindowPgTest.java',
    'web-api/src/test/java/com/cryptoautotrader/api/service/RealLossGlobalPgTest.java')
check('saved fixture diff equals fixed source diff', fixture_diff == data('fixture-lw-rl.diff').replace(b'\r\n', b'\n'))
removed = [x for x in fixture_diff.decode().splitlines() if x.startswith('-') and not x.startswith('---')]
check('fixture edits remove no assertions', not any('assertThat' in x or '.isEqual' in x for x in removed), removed)
for n in ['LiveLossWindowPgTest', 'RealLossGlobalPgTest']:
    s = src(FINAL, n)
    check(n + ' confirmed BUY identity and explicit fee zero', all(x in s for x in [
        '.side("BUY")', '.state("FILLED")', '.positionId(p.getId())',
        'buy.setReportedPaidFee(BigDecimal.ZERO)', 'buy.setAppliedPaidFee(BigDecimal.ZERO)',
        'buy.setFilledAt(p.getOpenedAt())', 'buy.setAppliedExecutedFunds(', 'orderRepository.save(buy)']))
lw = src(FINAL, 'LiveLossWindowPgTest')
old_lw = src('60df3437', 'LiveLossWindowPgTest')
check('LW manual/non-LIVE control unchanged', 'void scope()' in lw and 'void scope()' in old_lw and
      lw.split('void scope()', 1)[1].split('@Test', 1)[0] == old_lw.split('void scope()', 1)[1].split('@Test', 1)[0])
check('RL both refusal reasons exclude unknown cost', src(FINAL, 'RealLossGlobalPgTest').count('.doesNotContain("판정 불가")') == 2)

def xml_stats(prefix):
    files = [p for p in saved if p.startswith(P + prefix) and '/TEST-' in p and p.endswith('.xml')]
    totals = [0, 0, 0, 0]
    for p in files:
        node = ET.fromstring(saved[p])
        totals = [a + int(node.get(k, 0)) for a, k in zip(totals, ['tests', 'failures', 'errors', 'skipped'])]
    return {'files': len(files), 'totals': totals}

runs = [
    ('lw-0401d0e9/', [7, 0, 0, 0]),
    ('direct-0401d0e9/h2-DailySma20LiveOrderPathTest/', [24, 0, 0, 0]),
    ('direct-0401d0e9/pg-PhantomKeepsConfirmedPnlPgTest/', [4, 0, 0, 0]),
    ('direct-0401d0e9/pg-ClosingIntentRollbackPgTest/', [2, 0, 0, 0]),
    ('direct-0401d0e9/pg-N15PgLockWaitExpiryTest/', [13, 0, 0, 0]),
    ('direct-0401d0e9/pg-RealLossGlobalPgTest/', [3, 0, 0, 0]),
    ('direct2-d81c3f2f/pg-RealLossGlobalPgTest/', [3, 0, 0, 0]),
    ('direct2-d81c3f2f/pg-RealBuySummaryPgTest/', [1, 0, 0, 0]),
]
observations['direct_results'] = {}
for path, expected in runs:
    stats = xml_stats(path)
    check(path + ' saved XML and exit', stats['files'] == 1 and stats['totals'] == expected and data(path + 'rc.txt').strip() == b'rc=0', stats)
    observations['direct_results'][path] = stats
rbs = json.loads(data('direct2-d81c3f2f/pg-RealBuySummaryPgTest/real_buy_summary_checks.json'))
check('clean final BUY summary 23 assertions', len(rbs['checks']) == 23 and not rbs['failures'] and all(x['pass'] for x in rbs['checks']))
check('direct final HEAD is clean', data('direct2-d81c3f2f/head.txt').strip() == ('HEAD ' + FINAL).encode())
def output(prefix):
    xml = next(saved[p] for p in saved if p.startswith(P + prefix) and '/TEST-' in p and p.endswith('.xml'))
    return ET.fromstring(xml).findtext('system-out') or ''
before_rl = [l for l in output('direct-0401d0e9/pg-RealLossGlobalPgTest/').splitlines() if '[RL157]' in l]
after_rl = [l for l in output('direct2-d81c3f2f/pg-RealLossGlobalPgTest/').splitlines() if '[RL157]' in l]
check('RL previous passing tests used unknown-cost refusals', len(before_rl) == 3 and all('판정 불가' in x for x in before_rl[:2]), before_rl)
check('RL fixed tests observe 4% and 2% loss limits', len(after_rl) == 3 and '4.00% > 1.50%' in after_rl[0] and
      '2.00% > 1.50%' in after_rl[1] and '2001.0000' in after_rl[1], after_rl)
observations['rl_before_after'] = {'before': before_rl, 'after': after_rl}
reuse = ['RealLossThreeFormulaPgTest', 'PartialSellRealizedPnlPgTest', 'ClosingIntentAtomicityPgTest',
         'SettlementRoundingPgTest', 'CostBasisRiskPgTest', 'CostBasisBoundaryPgTest',
         'CostBasisFollowupPgTest', 'AbcCrossPgTest']
check('reused PG test blobs equal final source', all(src('596ed4b0', n) == src(FINAL, n) for n in reuse), reuse)
check('reused application/build/config inputs equal BUY candidate', not [p for p in diff_names('596ed4b0', FINAL)
    if not p.startswith('web-api/src/test/')])
for n in ['CostBasisRiskPgTest', 'CostBasisBoundaryPgTest', 'CostBasisFollowupPgTest']:
    check(n + ' negative cost cases unchanged', src('b408a223', n) == src(FINAL, n))

full = {}
for mod, expected, n in [('core-engine', [298, 0, 0, 7], 47), ('exchange-adapter', [11, 0, 0, 0], 2),
    ('strategy-lib', [113, 0, 0, 0], 17), ('web-api', [1445, 0, 0, 87], 223)]:
    stats = xml_stats('full-d81c3f2f/xml/' + mod + '/')
    check(mod + ' full XML counts', stats == {'files': n, 'totals': expected}, stats)
    full[mod] = stats
check('full regression fixed clean HEAD and successful exit', ('HEAD ' + FINAL + ' dirty=0') in data('full-d81c3f2f/time.txt').decode() and
      b'rc=0 end 2026-11-19 16:08:03' in data('full-d81c3f2f/time.txt') and not data('full-d81c3f2f/git-status.txt').strip())
check('full log reports successful build', b'BUILD SUCCESSFUL' in data('full-d81c3f2f/gradle.log'))
copy = data('full-d81c3f2f/xml-copy-check.txt').decode()
check('copy record lists 289 XML without mismatch', all(f'{m} generated={s["files"]} kept={s["files"]} hash_mismatch=0' in copy for m, s in full.items()))
net = data('full-d81c3f2f/xml/web-api/NET-GUARD-summary.txt').decode()
check('web-api guard installed and zero blocked attempts', all(x in net for x in ['installed_during_run=true', 'classes_checked=194', 'blocked_external_attempts=0']))
observations['full_results'] = full

manifest = data('jar-d81c3f2f/jar-manifest-d81c3f2f.lf.txt')
jarm = hashlib.sha256(manifest).hexdigest()
check('898 entry content manifest and JARM', len(manifest.splitlines()) == 898 and jarm == '33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509', jarm)
def parse_manifest(b):
    rows = [x.split('\t') for x in b.decode().splitlines()]
    assert len(rows) == len({x[0] for x in rows}) and all(re.fullmatch('[0-9a-f]{64}', x[1]) for x in rows)
    return dict(rows)
new = parse_manifest(manifest)
baseline_archive = blob(SHA, 'docs/evidence/v171d4-20261116/standin/standin-runs-r1r2.tar.gz')
with tarfile.open(fileobj=io.BytesIO(baseline_archive), mode='r:gz') as archive:
    old_bytes = archive.extractfile('s1-c01/home/maint-v171/jar-manifest-new.txt').read()
old = parse_manifest(old_bytes)
check('preserved production LF baseline content hash', len(old) == 895 and hashlib.sha256(old_bytes).hexdigest() ==
      'b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8')
changed_entries = sorted(n for n in old.keys() & new.keys() if old[n] != new[n])
added = sorted(new.keys() - old.keys())
deleted = sorted(old.keys() - new.keys())
check('independent jar manifest comparison 35 changed 3 added', len(changed_entries) == 35 and len(added) == 3 and not deleted,
      {'changed': changed_entries, 'added': added, 'deleted': deleted})
check('all changed jar entries are application classes', all(n.startswith('BOOT-INF/classes/') and n.endswith('.class') for n in changed_entries + added))
source_paths = diff_names('0e1d2a02', FINAL, '*/src/main/*')
class_sources = {n.removeprefix('BOOT-INF/classes/').split('$')[0].removesuffix('.class') + '.java' for n in changed_entries + added}
check('38 class changes map to 14 product Java files', len(source_paths) == 14 and
      class_sources == {n.split('/src/main/java/')[1] for n in source_paths})
u1 = blob('1644851b', 'docs/evidence/v171d4-20261116/d4/blocks-d4/U1.sh')
code = data('jar-d81c3f2f/jarm_u1.py').replace(b'\r\n', b'\n').strip()
check('manifest calculation reuses fixed U1 code', code in u1)
actual_jar = Path('D:/tmp/final-lf-d81c3f2f/web-api/build/libs/web-api-0.1.0.jar')
if actual_jar.is_file():
    raw = actual_jar.read_bytes()
    check('saved local jar file hash', hashlib.sha256(raw).hexdigest() == data('jar-d81c3f2f/jar-sha.txt').decode().split()[0])
    contents, duplicates = {}, []
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        duplicates += [n for n, c in Counter(z.namelist()).items() if c > 1]
        resources = 0
        for n in z.namelist():
            if n.endswith('/'):
                continue
            if re.fullmatch(r'BOOT-INF/lib/(core-engine|strategy-lib|exchange-adapter)[^/]*\.jar', n):
                key = re.sub(r'-[0-9.]+(-SNAPSHOT)?\.jar$', '.jar', n)
                with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                    duplicates += [key + '!' + a for a, c in Counter(inner.namelist()).items() if c > 1]
                    for item in inner.namelist():
                        if not item.endswith('/'):
                            contents[key + '!' + item] = hashlib.sha256(inner.read(item)).hexdigest()
            else:
                contents[n] = hashlib.sha256(z.read(n)).hexdigest()
                if n.startswith('BOOT-INF/classes/') and n.endswith(('.yml', '.xml', '.sql')):
                    resources += 1
                    path = 'web-api/src/main/resources/' + n.removeprefix('BOOT-INF/classes/')
                    check('jar LF resource equals Git blob: ' + path, b'\r\n' not in z.read(n) and z.read(n) == blob(FINAL, path))
    check('actual jar entries equal committed content manifest', not duplicates and contents == new,
          {'entries': len(contents), 'duplicate_names': duplicates, 'LF_resources': resources})
    observations['actual_jar_read'] = str(actual_jar)
else:
    observations['actual_jar_read'] = 'Unavailable at review time; saved manifest only.'
observations.update({'submission': SHA, 'candidate': FINAL, 'committed_evidence_files': len(saved),
    'manifest_checks': 360, 'newline_converted_committed_files': newline_only,
    'jar_changed': changed_entries, 'jar_added': added,
    'limits': ['No application tests, PostgreSQL, Docker, build, SSH, remote query or production commands were run.',
        'Intermediate 0401 direct runs are linked by identical product, test and build inputs; they are not claimed to have executed at d81.',
        'The original full-run build-result directories were not rechecked; the saved copy record and committed XML were reviewed.',
        'No ABC 60df jar content manifest was submitted. Only source delta and production LF content comparison establish scope.',
        'A local jar was read if still present; it was not rebuilt and does not prove a future server build.',
        'PG exclusions and prior results are limited to the C/N2 consumer scope, not every skipped PG test.',
        'Remote equality is based on saved refs; no remote query was performed.']})
(HERE / 'checks.json').write_text(json.dumps({'passed': sum(c['pass'] for c in checks), 'failed': sum(not c['pass'] for c in checks),
    'checks': checks}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(HERE / 'observations.json').write_text(json.dumps(observations, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'passed': sum(c['pass'] for c in checks), 'failed': sum(not c['pass'] for c in checks),
    'evidence_files': len(saved), 'manifests': 360}, ensure_ascii=False))
raise SystemExit(0 if all(c['pass'] for c in checks) else 1)
