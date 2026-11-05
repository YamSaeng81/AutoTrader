"""Local source, preserved evidence, jar and deployment-source review. No Gradle/server/DB."""
from pathlib import Path
import hashlib, io, json, re, subprocess, xml.etree.ElementTree as ET, zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
TMP = ROOT / 'core-engine/build/review-n17-a1e38e7'
TMP.mkdir(parents=True, exist_ok=True)
REF = 'a1e38e7'
OLD = '1103f48505a1457029f6555547a87aa986535738'
NEW = '7a944723c2959898226c2ad80f6b079ab5678296'
SERVICE = 'web-api/src/main/java/com/cryptoautotrader/api/service/'
JAVA = Path('C:/Program Files/Java/jdk-17/bin')
BASH = 'C:/Program Files/Git/bin/bash.exe'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def sha(b):
    return hashlib.sha256(b).hexdigest()

def src(ref, name):
    return git('show', ref + ':' + SERVICE + name + '.java').decode('utf-8')

def method(s, signature):
    start = s.index(signature)
    pos = s.index('{', start) + 1
    depth = 1
    while depth:
        depth += (s[pos] == '{') - (s[pos] == '}')
        pos += 1
    return s[start:pos]

def manifest(folder):
    count = 0
    for row in (ROOT / folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        if not row.strip():
            continue
        expected, name = row.split(maxsplit=1)
        path = folder + '/' + name.lstrip('*')
        data = (ROOT / path).read_bytes()
        assert sha(data) == expected and data == git('show', REF + ':' + path), path
        count += 1
    return count

manifests = {n: manifest('docs/evidence/' + n) for n in [
    'n17-rest-price-1103f48', 'n06-handover-list-draft', 'n05-review-0bf874f']}
assert git('rev-parse', NEW + '^').decode().strip() == OLD
paths = git('diff', '--name-only', OLD, NEW).decode().splitlines()
assert len(paths) == 4 and all(n.endswith('.java') for n in paths)
vals = json.loads((ROOT/'docs/evidence/n17-rest-price-1103f48/blocks/vals.json').read_text(encoding='utf-8'))
assert paths == vals['DIFF']

test_root = ROOT / 'docs/evidence/n17-rest-price-1103f48/tests'
xml_results = {}
for folder, classes, expected in [('r1-failfirst-1103f48', 1, [24,10,0,0]), ('r2-fix-related', 8, [86,0,0,0])]:
    xmls = list((test_root/folder/'xml').glob('*.xml'))
    totals = [0,0,0,0]
    for p in xmls:
        assert p.read_bytes() == git('show', REF + ':' + p.relative_to(ROOT).as_posix())
        x = ET.fromstring(p.read_bytes())
        for i, k in enumerate(['tests','failures','errors','skipped']):
            totals[i] += int(x.attrib.get(k, 0))
    assert len(xmls) == classes and totals == expected, (folder, totals)
    xml_results[folder] = {'classes': classes, 'tests_failures_errors_skipped': totals}

# git diff omitted the untracked new test. Runtime source is byte-verifiable;
# the test is reviewed at the fixed commit and matched by XML cases, not claimed
# to be an independently preserved run2 source snapshot.
patch = (test_root/'r2-fix-related/worktree.diff').read_bytes().replace(b'\r\n', b'\n')
diff = git('diff', '--no-ext-diff', OLD, NEW, '--', 'web-api/src/main')
assert patch == diff

plan = git('show', REF + ':docs/LIMITED_LIVE_TEST_PLAN.md').decode()
blocks = re.findall(r'^```bash\n(.*?)^```', plan, re.M|re.S)
tmd = (ROOT/'docs/evidence/n17-rest-price-1103f48/blocks/t-blocks.md').read_text(encoding='utf-8')
tblocks = re.findall(r'^```bash\n(.*?)^```', tmd, re.M|re.S)
assert len(blocks) == 125 and len(tblocks) == 8 and blocks[-8:] == tblocks
syntax = []
for i, b in enumerate(tblocks):
    path = TMP / ('t%d.sh' % i)
    path.write_text(b, encoding='utf-8')
    p = subprocess.run([BASH, '--noprofile', '--norc', '-n', path.as_posix()], capture_output=True)
    assert p.returncode == 0, p.stderr
    syntax.append({'block': i, 'sha256': sha(b.encode()), 'bash_n': 0})

# Compare actual preserved jars using the exact T1 comparison code; no new build.
cmp_code = tblocks[2].split("<<'EOF' | tee \"$M/jar-diff.txt\"\n",1)[1].split('\nEOF',1)[0]
cmp_path = TMP/'jarcmp.py'
cmp_path.write_text(cmp_code, encoding='utf-8')
jars = [Path('D:/tmp/n17/jars/1103f48.jar'), Path('D:/tmp/n17/jars/7a94472.jar')]
expected_hash = ['f7ee2ef4bf822a6af4a6597892cb64739a7523be0aa1091c5c44f6f875ceacbe',
                 'ddb64359f330419a0a6e233e4c09a2f0f7f1568784858bf344bf607ef10422fe']
for p, e in zip(jars, expected_hash):
    assert sha(p.read_bytes()) == e
cmp = subprocess.run([__import__('sys').executable, '-X','utf8', str(cmp_path), *map(str,jars)], capture_output=True)
assert cmp.returncode == 0
jar_out = cmp.stdout.decode('utf-8').replace('\r\n','\n')
assert jar_out == (ROOT/'docs/evidence/n17-rest-price-1103f48/jars/diff-1103f48-7a94472.txt').read_text(encoding='utf-8')
(OUT/'jar-compare.txt').write_text(jar_out, encoding='utf-8')
assert len(jar_out.splitlines()) == 7 and jar_out.splitlines()[-1] == '(끝) 881 881'

# Unchanged old safety functions and exact T1 comparison code, not just a diff summary.
rmd = git('show', REF+':docs/evidence/k23-final-1103f48/blocks/r-blocks.md').decode()
rblocks = re.findall(r'^```bash\n(.*?)^```', rmd, re.M|re.S)
for sig in ['btcok()', 'btcgate()', 'newsobs()', 'httpobs()', 'holdobs()', 'entryobs()']:
    assert method(tblocks[0], sig) == method(rblocks[0], sig), sig
assert cmp_code == rblocks[2].split("<<'EOF' | tee \"$M/jar-diff.txt\"\n",1)[1].split('\nEOF',1)[0]
assert '|| stop "jar 차이가 N17 6줄' in tblocks[2]
assert "grep -qx '(끝) 881 881'" in tblocks[2]
assert 'docker tag "$IMG0" $REPO:1103f48' in tblocks[2]
assert 'backend' in tblocks[3] and '--no-build --no-deps backend' in tblocks[3]

# Compile unchanged actual methods with dependency stubs, before and after.
java_runs = {}
for ref, label in [(OLD,'before'),(NEW,'candidate')]:
    s = {name: src(ref,name) for name in ['LiveTradingService','DynamicTradingService','MarkPriceBook','ForceRefreshResult','RealtimePriceEvent']}
    live, dyn, book = s['LiveTradingService'], s['DynamicTradingService'], s['MarkPriceBook']
    force = [method(live,'ForceRefreshResult forceRefreshPrice('), method(dyn,'ForceRefreshResult forceRefreshPrice(')]
    rest = method(live,'private static Instant restTradeTime(')
    assert rest == method(dyn,'private static Instant restTradeTime(')
    stale = method(book,'public boolean isStale(')
    price = method(book,'public static boolean isPrice(')
    cstart = dyn.index('String cause = !subscribed')
    cause = dyn[cstart:dyn.index('\n\n',cstart)].strip()
    bools = '\n'.join(line.strip() for line in dyn.splitlines() if line.strip().startswith(('boolean invalidPrice = refresh.outcome()', 'boolean fetched = refresh.outcome()')))
    tail = 'String tail = invalidPrice ? "" : " 60초 폴링은 계속 돌고 있으므로 손절 자체가 멈춘 것은 아닙니다.";' if label=='candidate' else 'String tail = "";'
    for name in ['ForceRefreshResult','RealtimePriceEvent']:
        (TMP/(name+'.java')).write_text(re.sub(r'^package [^;]+;\s*','',s[name]),encoding='utf-8')
    support = '''
static class Client { List<Map<String,Object>> rows=new ArrayList<>(); boolean fail;
  List<Map<String,Object>> getTicker(String coin) { if(fail) throw new RuntimeException("stub"); return rows; } }
static class Publisher { int count; void publishEvent(RealtimePriceEvent e) { count++; } }
static class Logger { void warn(String s,Object... v){} void error(String s,Object... v){} }
static class MarkPriceBook { long maxAgeSeconds=120; long maxAgeSeconds(){return maxAgeSeconds;}
%s
%s
}
static class Probe { Client upbitRestClient=new Client(); Publisher eventPublisher=new Publisher();
 MarkPriceBook markPriceBook=new MarkPriceBook(); Logger log=new Logger();
%s
}
static class Live extends Probe { %s }
static class Dynamic extends Probe { %s }
''' % (stale,price,rest.replace('private static','static'),force[0],force[1])
    main = '''
static void one(String kind,String input,Object value,int age,String expected,int events) {
 Probe p=kind.equals("LIVE")?new Live():new Dynamic();
 if(input.equals("exception"))p.upbitRestClient.fail=true;
 else if(!input.equals("empty")) { Map<String,Object> row=new HashMap<>();row.put("market","KRW-BTC");row.put("trade_price",value);
 row.put("trade_timestamp",Instant.now().minusSeconds(age).toEpochMilli());p.upbitRestClient.rows.add(row); }
 ForceRefreshResult r=p instanceof Live l?l.forceRefreshPrice("KRW-BTC"):((Dynamic)p).forceRefreshPrice("KRW-BTC");
 if(!r.outcome().name().equals(expected)||p.eventPublisher.count!=events)throw new AssertionError(kind+input+": "+r);
 System.out.println(kind+"|"+input+"|"+r.outcome()+"|events="+p.eventPublisher.count);
}
static void reason(String outcome,int streak,boolean expectedHuman,boolean badPrice) {
 ForceRefreshResult refresh=ForceRefreshResult.failed();
 for(ForceRefreshResult.Outcome o:ForceRefreshResult.Outcome.values()) if(o.name().equals(outcome))refresh=new ForceRefreshResult(o,Instant.now());
 boolean subscribed=true;MarkPriceBook markPriceBook=new MarkPriceBook();
 %s
 boolean needsHuman=!fetched||!subscribed||streak>=5;
 %s
 %s
 if(needsHuman!=expectedHuman)throw new AssertionError("needsHuman "+outcome);
 if(badPrice&&(!cause.contains("가격으로 볼 수 없는 값")||cause.contains("이벤트 경로")||!tail.isEmpty()))throw new AssertionError(cause+tail);
 System.out.println("cause|"+outcome+"|streak="+streak+"|human="+needsHuman+"|"+cause);
}
public static void main(String[] args) {
 boolean fix=%s;
 for(String k:List.of("LIVE","DYNAMIC")) {
 one(k,"zero","0",1,fix?"INVALID_PRICE":"PUBLISHED",fix?0:1);
 one(k,"negative","-5",1,fix?"INVALID_PRICE":"PUBLISHED",fix?0:1);
 one(k,"old_zero","0",121,fix?"INVALID_PRICE":"STALE",fix?0:1);
 one(k,"positive","299",1,"PUBLISHED",1);one(k,"old_positive","280",121,"STALE",1);
 one(k,"missing",null,1,"FAILED",0);one(k,"malformed","NOT_PRICE",1,"FAILED",0);
 one(k,"empty",null,1,"FAILED",0);one(k,"exception",null,1,"FAILED",0);
 }
 reason(fix?"INVALID_PRICE":"PUBLISHED",1,fix,fix);
 reason("FAILED",1,true,false);reason("PUBLISHED",1,false,false);reason("PUBLISHED",5,true,false);
 Instant t=Instant.parse("2026-11-05T00:00:00Z");MarkPriceBook b=new MarkPriceBook();
 if(b.isStale(t,t.plusMillis(120999))||!b.isStale(t,t.plusSeconds(121)))throw new AssertionError("age");
 System.out.println("age|120.999=fresh|121=stale");
}
''' % (bools,cause,tail,'true' if label=='candidate' else 'false')
    (TMP/'N17Review.java').write_text('import java.math.*;import java.time.*;import java.util.*;\npublic class N17Review {\n'+support+main+'\n}',encoding='utf-8')
    compile = subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',str(TMP),*[str(TMP/(n+'.java')) for n in ['ForceRefreshResult','RealtimePriceEvent','N17Review']]],capture_output=True)
    (OUT/('javac-'+label+'.txt')).write_bytes(compile.stdout+compile.stderr)
    assert compile.returncode==0,compile.stderr.decode(errors='replace')
    run = subprocess.run([str(JAVA/'java.exe'),'-Dfile.encoding=UTF-8','-cp',str(TMP),'N17Review'],capture_output=True)
    (OUT/('method-'+label+'.txt')).write_bytes(run.stdout+run.stderr)
    assert run.returncode==0,run.stderr.decode(errors='replace')
    assert len(run.stdout.decode().splitlines())==23
    java_runs[label]={'compile':0,'run':0,'force_inputs':18,'cause_controls':4,'age_control':1}

assert method(src(OLD,'LiveTradingService'),'public void pollRestTickerFallback(') == method(src(NEW,'LiveTradingService'),'public void pollRestTickerFallback(')
assert 'MarkPriceBook.isPrice' not in method(src(NEW,'LiveTradingService'),'public void pollRestTickerFallback(')
fallback = method(src(NEW,'LiveTradingService'),'public void pollRestTickerFallback(')
fallback_java = '''import java.math.*;import java.time.*;import java.util.*;
public class FallbackReview {
 static final long WS_FALLBACK_THRESHOLD_SEC=30; boolean restFallbackActive;
 static class Health { boolean isWsUnhealthy(long age){return true;} }
 static class Subs { List<String> getSubscribedCoins(){return List.of("KRW-A","KRW-B");} }
 static class Client { List<Map<String,Object>> rows;List<Map<String,Object>> getTicker(String m){return rows;} }
 static class Pub {int count;void publishEvent(RealtimePriceEvent e){count++;} }
 static class Log {void info(String s,Object...v){} void warn(String s,Object...v){} void error(String s,Object...v){} }
 Health exchangeHealthMonitor=new Health();Subs wsSubscriptionManager=new Subs();Client upbitRestClient=new Client();
 Pub eventPublisher=new Pub();Log log=new Log();
 %s
 %s
 static Map<String,Object> row(String price){return row("KRW-A",price);}
 static Map<String,Object> row(String market,String price){return Map.of("market",market,"trade_price",price,"trade_timestamp",Instant.now().toEpochMilli());}
 static void one(String label,List<Map<String,Object>> rows,int expected) {
  FallbackReview p=new FallbackReview();p.upbitRestClient.rows=rows;p.pollRestTickerFallback();
  if(p.eventPublisher.count!=expected)throw new AssertionError(label);
  System.out.println(label+"|published="+p.eventPublisher.count);
 }
 public static void main(String[]args){
  one("zero_negative_and_positive_all_published",List.of(row("0"),row("-5"),row("299")),3);
  one("malformed_first_hides_later_valid",List.of(row("NOT_PRICE"),row("KRW-B","299")),0);
  one("valid_then_malformed_hides_last_valid",List.of(row("299"),row("NOT_PRICE"),row("KRW-B","300")),1);
  one("positive_controls",List.of(row("299"),row("300")),2);
 }
}
''' % (method(src(NEW,'LiveTradingService'),'private static Instant restTradeTime('),fallback)
(TMP/'FallbackReview.java').write_text(fallback_java,encoding='utf-8')
fc = subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-cp',str(TMP),'-d',str(TMP),str(TMP/'FallbackReview.java')],capture_output=True)
assert fc.returncode == 0,fc.stderr.decode(errors='replace')
fr = subprocess.run([str(JAVA/'java.exe'),'-Dfile.encoding=UTF-8','-cp',str(TMP),'FallbackReview'],capture_output=True)
(OUT/'fallback-original.txt').write_bytes(fr.stdout+fr.stderr)
assert fr.returncode == 0
result = {'source':git('rev-parse',REF).decode().strip(),'candidate':NEW,'parent_operating':OLD,
          'manifests':manifests,'candidate_paths':paths,'preserved_XML_not_rerun':xml_results,
          'runtime_run_patch_matches_candidate':True,
          'test_run_source_snapshot_not_preserved_in_untracked_git_diff':True,
          'local_original_method_execution':java_runs,
          'deployment_blocks':syntax,'T1_actual_preserved_jar_compare':[6,881,881],
          'jar_file_hashes':dict(zip([p.name for p in jars],expected_hash)),
          'old_safety_functions_and_comparator_unchanged':True,
          'global_REST_fallback_unchanged_without_price_guard':True,
          'original_global_fallback_local_cases': {'cases':4,'compile':fc.returncode,'run':fr.returncode,
            'zero_negative_published':True,'malformed_row_aborts_later_valid_rows':True,
            'method_sha256':sha(fallback.encode()),'no_consumers_orders_or_watchdog_executed':True},
          'no_application_test_reexecution_Gradle_server_DB_network':True}
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifests':manifests,'XML':xml_results,'method_inputs':36,'cause_controls':8,
 'age_controls':2,'syntax':len(syntax),'jar':[6,881,881]},ensure_ascii=False))
