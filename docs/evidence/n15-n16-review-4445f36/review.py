"""Read preserved evidence and fixed Git candidates. Does not run Gradle or contact a server."""
from pathlib import Path
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '4445f36192752636f5c47186c6e2fff3aab2ffcf'
BASE = 'fe01207f3f60e97e308925157f84a1bb2da04388'
N15 = '6f6a5220f7a19f381fa210a9c4a530e262a4fb04'
N16 = 'ac29ecf11da9529fd3d83a6b28ae8756c4e89c9a'
P = 'web-api/src/main/java/com/cryptoautotrader/api/service/'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def source(ref, path):
    return git('show', f'{ref}:{path}').decode('utf-8')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def manifest(name, count):
    directory = ROOT / 'docs/evidence' / name
    rows = []
    for line in (directory / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        wanted, path = line.split(' ', 1)
        path = path.strip().lstrip('*')
        file = directory / path
        assert digest(file.read_bytes()) == wanted, path
        assert digest(git('show', f'{REF}:{file.relative_to(ROOT).as_posix()}')) == wanted, path
        rows.append({'path': path, 'sha256': wanted})
    assert len(rows) == count
    return rows


def suites(name, phase):
    directory = ROOT / 'docs/evidence' / name / phase
    out = []
    for p in sorted(directory.glob('TEST-*.xml')):
        xml = ET.parse(p).getroot()
        out.append({'file': p.name, **{k: int(xml.attrib.get(k, 0)) for k in ['tests', 'failures', 'errors', 'skipped']}})
    return out


manifests = {'n16': manifest('n16-future-time-ac29ecf', 23), 'n15': manifest('n15-expiry-6f6a522', 31)}
tests = {}
for name, count, classes, before_fail in [('n16-future-time-ac29ecf', 80, 10, 4), ('n15-expiry-6f6a522', 93, 15, 6)]:
    before, after = suites(name, 'before'), suites(name, 'after')
    assert len(before) == 1 and before[0]['failures'] == before_fail
    assert before[0]['tests'] == (13 if count == 80 else 6)
    assert len(after) == classes and sum(s['tests'] for s in after) == count
    assert all(s['failures'] == s['errors'] == s['skipped'] == 0 for s in after)
    tests[name] = {'before': before, 'after': after}

assert git('rev-parse', N15 + '^').decode().strip() == BASE
assert git('rev-parse', N16 + '^').decode().strip() == '3385f70902999210dafe7774570fdc69d1a1fe82'
paths15 = git('diff', '--name-only', BASE, N15).decode().splitlines()
paths16 = git('diff', '--name-only', BASE, N16).decode().splitlines()
assert len(paths15) == 8 and len([p for p in paths15 if '/src/main/' in p]) == 7
assert len(paths16) == 4 and len([p for p in paths16 if '/src/main/' in p]) == 3
assert not any('migration' in p or 'application' in p for p in paths15 + paths16)
for candidate, folder, test in [(N15, 'n15-expiry-6f6a522', 'N15EntryExpiryTest'), (N16, 'n16-future-time-ac29ecf', 'N16InvalidPriceReproTest')]:
    file = f'web-api/src/test/java/com/cryptoautotrader/api/service/{test}.java'
    assert git('show', f'{candidate}:{file}') == (ROOT / 'docs/evidence' / folder / 'tests' / (test + '.java')).read_bytes()
    paths = [p for p in (paths15 if candidate == N15 else paths16) if '/src/main/' in p]
    expected = 'diff-fe01207-6f6a522-main.txt' if candidate == N15 else 'diff-fe01207-ac29ecf-main.txt'
    assert git('diff', BASE, candidate, '--', *paths) == (ROOT / 'docs/evidence' / folder / expected).read_bytes()

mark = source(N16, P + 'MarkPriceBook.java')
assert 'new Mark(price, earlier(at, received), received, source, null, at)' in mark
assert 'new Mark(bids.get(0)[0], earlier(at, received), received, REST_ORDERBOOK_DEPTH, List.copyOf(bids), at)' in mark
assert 'neu.at().isBefore(old.at())' in mark
assert 'return receivedAt != null ? receivedAt : Instant.now();' in mark
assert 'return observedAt.isAfter(receivedAt) ? receivedAt : observedAt;' in mark
assert 'Duration.between(m.at(), now).getSeconds() > maxAgeSeconds' in mark
assert source(N16, P + 'HeldMarkRefresher.java') == source(BASE, P + 'HeldMarkRefresher.java')
assert source(N16, P + 'PoolLossGuard.java') == source(BASE, P + 'PoolLossGuard.java')
assert source(N16, P + 'PositionLimitGuard.java') == source(BASE, P + 'PositionLimitGuard.java')

reservation = source(N15, P + 'CapitalReservationService.java')
method = reservation[reservation.index('public Decision reserveForBuy(String kind, Long sessionId, BigDecimal amount, Long selfPositionId, Instant'):]
method = method[:method.index('public void releaseWithinTransaction')]
assert method.count('EntryPriorityRounds.isExpired(') == 1
check = method.index('EntryPriorityRounds.isExpired(')
assert check < method.index('poolLossGuard.checkForEntry(') < method.index('session.setAvailableKrw(after)')
assert check < method.index('em.refresh(session, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)')
engine = source(N15, P + 'OrderExecutionEngine.java')
assert engine.count('request.getEntryExpiresAt()') == 1
assert 'entryClock' not in engine and 'ENTRY_EXPIRED' not in engine
rounds = source(N15, P + 'EntryPriorityRounds.java')
assert 'Instant at = clock.now();' in rounds and 'if (isExpired(expiry(c.round()), at))' in rounds
assert 'return deadline(round).plus(window(round.timeframe()));' in rounds
assert 'expiresAt != null && !now.isBefore(expiresAt)' in rounds
assert 'c.expired().accept(reason)' in rounds

anchors = {}
for name, text, needles in [
    ('CapitalReservationService', reservation, ['lockAccount();', 'if (EntryPriorityRounds.isExpired(entryExpiresAt, now))', 'Decision loss = poolLossGuard.checkForEntry', 'em.refresh(session, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)', 'session.setAvailableKrw(after);']),
    ('OrderExecutionEngine', engine, ['Long orderId = prepareIntent(request);', 'if (!approveNewEntry(request, sessionKind))', 'OrderEntity order = OrderEntity.builder()'])]:
    anchors[name] = {needle: [i for i, line in enumerate(text.splitlines(), 1) if needle in line] for needle in needles}
reproduction = json.loads((OUT / 'reservation-review.json').read_text(encoding='utf-8'))
assert reproduction['java_exit'] == reproduction['javac_exit'] == 0
result = {
    'reviewed_main': REF, 'base': BASE, 'n15': N15, 'n16': N16,
    'manifests_worktree_and_git': manifests, 'preserved_xml': tests,
    'changed_files_n15': paths15, 'changed_files_n16': paths16,
    'preserved_test_sources_and_production_diffs_match_git': True,
    'candidate_source_anchors': anchors,
    'n16_ruling': 'Accept local candidate; runtime not changed and N16 not completed.',
    'n15_ruling': 'Changes requested: post-check blocking guards and session locks can cross E before debit/intent.',
    'fresh_reproduction': reproduction,
    'limits': ['80 and 93 include N16 13 and N15 6 respectively; overlapping classes must not be summed as unique tests.',
               '7/7 review reproduction expectations include three defects; not seven passing safety tests.',
               'Focused reproduction compiles exact candidate methods with mocked dependencies, not an integrated DB/order commit.',
               'No server, operational DB, external query, Gradle regression or bootJar build performed.']
}
(OUT / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifests': [23, 31], 'n16_xml': '80/80 (includes 13)', 'n15_xml': '93/93 (includes 6)',
                  'n16': 'local candidate accepted', 'n15': '3 reproduced post-check expiry gaps'}, ensure_ascii=False))
