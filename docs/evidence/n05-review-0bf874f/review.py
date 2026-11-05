"""Review committed N05 links and reproduce K9 from unchanged operating methods.

Only local javac/java with dependency stubs; no Gradle, Spring, network or DB.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SCRATCH = ROOT / 'core-engine/build/review-n05-0bf874f'
SCRATCH.mkdir(parents=True, exist_ok=True)
REF = '0bf874f'
OP = '1103f48505a1457029f6555547a87aa986535738'
SERVICE = 'web-api/src/main/java/com/cryptoautotrader/api/service/'

def git(ref, name):
    return subprocess.check_output(['git', 'show', ref + ':' + name], cwd=ROOT)

def source(name):
    return git(OP, SERVICE + name + '.java').decode('utf-8')

def sha(data):
    return hashlib.sha256(data).hexdigest()

def method(text, signature):
    start = text.index(signature)
    begin = text.index('{', start)
    depth = 1
    end = begin + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

def manifest(folder):
    count = 0
    for row in (ROOT / folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        if not row.strip():
            continue
        expected, name = row.split(maxsplit=1)
        name = name.lstrip('*')
        path = folder + '/' + name
        local = (ROOT / path).read_bytes()
        assert sha(local) == expected and local == git(REF, path), path
        count += 1
    return count

assert manifest('docs/evidence/n05-k23-links-1103f48') == 1
assert manifest('docs/evidence/k23-result-review-28e2183') == 7
evidence = 'docs/evidence/k23-final-1103f48/tests/integ25-1103f48/xml'
xmls = [p for p in (ROOT / evidence).glob('*.xml')]
totals = [0, 0, 0, 0]
test_names = set()
for p in xmls:
    assert p.read_bytes() == git(REF, evidence + '/' + p.name)
    suite = ET.fromstring(p.read_bytes())
    for i, key in enumerate(('tests', 'failures', 'errors', 'skipped')):
        totals[i] += int(suite.attrib.get(key, 0))
    test_names.add(p.name.removeprefix('TEST-com.cryptoautotrader.api.service.').removesuffix('.xml'))
assert len(xmls) == 25 and totals == [173, 0, 0, 0]
required = ['N15EntryExpiryTest', 'N15FinalApprovalBoundaryTest', 'EntryPriorityRoundsTest',
            'IntentPrecommitAndOnceOnlyTest', 'SubmitResponseUnknownTest', 'StopVersusInFlightExitRaceTest',
            'N16InvalidPriceReproTest', 'StaleRealtimeExitTest', 'SlWatchdogFreshnessTest',
            'LiveTradingSlWatchdogRecoveryTest', 'DynamicTradingSlWatchdogRecoveryTest',
            'CandleGapExitTest', 'PoolLossLimitTest']
assert set(required) <= test_names

s = {name: source(name) for name in ['LiveTradingService', 'DynamicTradingService', 'MarkPriceBook',
    'ForceRefreshResult', 'RealtimePriceEvent', 'EntryPriorityRounds', 'CapitalReservationService',
    'OrderExecutionEngine', 'PoolLossGuard']}
live, dyn, book, engine = (s[n] for n in ['LiveTradingService', 'DynamicTradingService', 'MarkPriceBook', 'OrderExecutionEngine'])
checks = {}
checks['K1_expiry_before_callback'] = s['EntryPriorityRounds'].index('if (isExpired(expiry(c.round()), at))') < s['EntryPriorityRounds'].index('reason = c.approve().get();')
for kind, text in [('LIVE', live), ('DYNAMIC', dyn)]:
    handler = method(text, 'private void doOnRealtimePriceEvent(')
    checks[kind + '_invalid_before_throttle_record'] = handler.index('!MarkPriceBook.isPrice(price)') < handler.index('recordSlCheck(')
    checks[kind + '_only_one_expiry_setter'] = text.count('order.setEntryExpiresAt(entryExpiresAt)') == 1
    request = method(text, 'ForceRefreshResult forceRefreshPrice(')
    checks[kind + '_K9_no_price_validation'] = 'isPrice(' not in request and request.index('eventPublisher.publishEvent(') < request.index('markPriceBook.isStale(')
    (OUT / (kind.lower() + '-force-original.java.txt')).write_text(request + '\n', encoding='utf-8')
checks['K3_stop_approval_before_final_expiry'] = engine.index('approveNewEntry(request, sessionKind)') < engine.index('entryExpiry(request.getEntryExpiresAt(), "의도 직전")')
checks['K5_same_transaction_refund'] = 'catch (IntentExpired expired)' in engine and 'releaseWithinTransaction' in engine
checks['K10_trade_and_book_clamped'] = book.count('earlier(at, received)') == 2
assert all(checks.values()), checks

force_live = method(live, 'ForceRefreshResult forceRefreshPrice(')
force_dyn = method(dyn, 'ForceRefreshResult forceRefreshPrice(')
rest_time = method(live, 'private static Instant restTradeTime(')
assert rest_time == method(dyn, 'private static Instant restTradeTime(')
stale = method(book, 'public boolean isStale(')
price_check = method(book, 'public static boolean isPrice(')
cause_start = dyn.index('String cause = !subscribed')
cause_end = dyn.index('\n\n', cause_start)
cause = dyn[cause_start:cause_end].strip()
(OUT / 'dynamic-cause-original.java.txt').write_text(cause + '\n', encoding='utf-8')

support = '''
static class FakeClient {
    List<Map<String,Object>> rows = new ArrayList<>();
    List<Map<String,Object>> getTicker(String coin) { return rows; }
}
static class FakePublisher {
    List<RealtimePriceEvent> events = new ArrayList<>();
    void publishEvent(RealtimePriceEvent event) { events.add(event); }
}
static class FakeLogger { void error(String format, Object... args) {} }
static class MarkPriceBook {
    long maxAgeSeconds = 120;
    long maxAgeSeconds() { return maxAgeSeconds; }
    %s
    %s
}
static class Probe {
    FakeClient upbitRestClient = new FakeClient();
    FakePublisher eventPublisher = new FakePublisher();
    MarkPriceBook markPriceBook = new MarkPriceBook();
    FakeLogger log = new FakeLogger();
    %s
}
static class LiveProbe extends Probe { %s }
static class DynamicProbe extends Probe { %s }
''' % (stale, price_check, rest_time.replace('private static', 'static'), force_live, force_dyn)
main = '''
static void run(String kind, String label, Object price, int ageSeconds, String expected, int events) {
    Probe probe = kind.equals("LIVE") ? new LiveProbe() : new DynamicProbe();
    Map<String,Object> row = new HashMap<>();
    row.put("market", "KRW-BTC"); row.put("trade_price", price);
    row.put("trade_timestamp", Instant.now().minusSeconds(ageSeconds).toEpochMilli());
    probe.upbitRestClient.rows.add(row);
    ForceRefreshResult r = probe instanceof LiveProbe p ? p.forceRefreshPrice("KRW-BTC")
        : ((DynamicProbe)probe).forceRefreshPrice("KRW-BTC");
    if (!r.outcome().name().equals(expected) || probe.eventPublisher.events.size() != events)
        throw new AssertionError(kind + " " + label);
    System.out.println(kind + "|" + label + "|" + r.outcome() + "|published=" + probe.eventPublisher.events.size());
}
static String dynamicCause(ForceRefreshResult refresh, boolean subscribed, int streak) {
    boolean fetched = refresh.outcome() != ForceRefreshResult.Outcome.FAILED;
    MarkPriceBook markPriceBook = new MarkPriceBook();
    %s
    return cause;
}
public static void main(String[] args) {
    for (String kind : List.of("LIVE", "DYNAMIC")) {
        run(kind, "fresh_zero", "0", 1, "PUBLISHED", 1);
        run(kind, "fresh_negative", "-1", 1, "PUBLISHED", 1);
        run(kind, "fresh_positive", "299", 1, "PUBLISHED", 1);
        run(kind, "stale_positive", "280", 121, "STALE", 1);
        run(kind, "missing_price", null, 1, "FAILED", 0);
        run(kind, "malformed_price", "NOT_PRICE", 1, "FAILED", 0);
    }
    String result = dynamicCause(new ForceRefreshResult(ForceRefreshResult.Outcome.PUBLISHED, Instant.now()), true, 5);
    if (!result.contains("실시간 이벤트 경로가 죽었을 수 있습니다")) throw new AssertionError(result);
    System.out.println("DYNAMIC|fresh_invalid_after_5_unchecked_cycles|" + result);
    MarkPriceBook book = new MarkPriceBook();
    Instant t = Instant.parse("2026-11-05T00:00:00Z");
    if (book.isStale(t, t.plusMillis(120999)) || !book.isStale(t, t.plusSeconds(121))) throw new AssertionError("age");
    System.out.println("age_control|120.999=fresh|121=stale");
}
''' % cause

java = 'import java.math.BigDecimal;\nimport java.time.*;\nimport java.util.*;\n' + \
       'public class N05Review {\n' + support + main + '\n}\n'
for name in ['ForceRefreshResult', 'RealtimePriceEvent']:
    raw = s[name]
    raw = re.sub(r'^package [^;]+;\s*', '', raw)
    (SCRATCH / (name + '.java')).write_text(raw, encoding='utf-8')
(SCRATCH / 'N05Review.java').write_text(java, encoding='utf-8')
bin_dir = Path('C:/Program Files/Java/jdk-17/bin')
comp = subprocess.run([str(bin_dir/'javac.exe'), '-encoding', 'UTF-8', '-d', str(SCRATCH),
    str(SCRATCH/'ForceRefreshResult.java'), str(SCRATCH/'RealtimePriceEvent.java'), str(SCRATCH/'N05Review.java')],
    capture_output=True)
(OUT / 'javac.txt').write_bytes(comp.stdout + comp.stderr)
assert comp.returncode == 0, comp.stderr.decode('utf-8', errors='replace')
run = subprocess.run([str(bin_dir/'java.exe'), '-Dfile.encoding=UTF-8', '-cp', str(SCRATCH), 'N05Review'], capture_output=True)
(OUT / 'reproduce.txt').write_bytes(run.stdout + run.stderr)
assert run.returncode == 0, run.stderr.decode('utf-8', errors='replace')
assert len(run.stdout.decode('utf-8').splitlines()) == 14
result = {'submitted_ref': subprocess.check_output(['git','rev-parse',REF], cwd=ROOT).decode().strip(),
          'operating_source': OP, 'submitted_manifest': 1, 'previous_review_manifest': 7,
          'preserved_XML_not_rerun': {'classes': 25, 'tests_failures_errors_skipped': totals},
          'source_checks': checks, 'source_hashes': {name: sha(text.encode()) for name,text in s.items()},
          'force_method_hashes': {'LIVE': sha(force_live.encode()), 'DYNAMIC': sha(force_dyn.encode())},
          'local_reproduction': {'compile_exit': comp.returncode, 'run_exit': run.returncode,
             'force_input_cases': 12, 'dynamic_cause_control': 1, 'age_boundary_control': 1,
             'original_methods_unchanged': True, 'dependencies_stubbed': True,
             'Spring_async_dispatch_consumers_sales_not_executed': True},
          'no_Gradle_build_app_tests_network_server_DB': True,
          'N05_static_links_accepted_not_full_rehearsal': True,
          'K9_followup': 'N17 local reproduction and minimal candidate authorized; no deployment approval'}
(OUT/'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'manifest':[1,7], 'preserved_XML':[25,173], 'local_force_cases':12,
                  'cause_and_age_controls':2, 'javac_exit':comp.returncode, 'run_exit':run.returncode}, ensure_ascii=False))
