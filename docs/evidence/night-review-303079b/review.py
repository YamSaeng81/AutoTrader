"""Local review of preserved night evidence. No server, DB, Gradle or jar build."""
from pathlib import Path
import hashlib, json, re, subprocess, sys, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
TMP = ROOT / 'core-engine/build/night-review-303079b'
TMP.mkdir(parents=True, exist_ok=True)
REF = '303079b'
NEW = '7a944723c2959898226c2ad80f6b079ab5678296'
CAND = 'f6868a587a921dbc8b4985793582bc764b0896b1'
IMAGE = 'sha256:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6'
SERVICE = 'web-api/src/main/java/com/cryptoautotrader/api/service/'
BASH = 'C:/Program Files/Git/bin/bash.exe'
JAVA = Path('C:/Program Files/Java/jdk-17/bin')

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def sha(b):
    return hashlib.sha256(b).hexdigest()

def text(p):
    return p.read_text(encoding='utf-8')

def blocks(s):
    return re.findall(r'^```bash\n(.*?)^```', s, re.M | re.S)

def method(s, signature):
    start = s.index(signature)
    pos = s.index('{', start) + 1
    depth = 1
    while depth:
        depth += (s[pos] == '{') - (s[pos] == '}')
        pos += 1
    return s[start:pos]

def manifest(name):
    folder = ROOT / 'docs/evidence' / name
    count = 0
    for row in text(folder/'SHA256SUMS').splitlines():
        if not row.strip():
            continue
        h, n = row.split(maxsplit=1)
        p = folder / n.lstrip('*')
        data = p.read_bytes()
        assert sha(data) == h, p
        assert data == git('show', REF + ':' + p.relative_to(ROOT).as_posix()), p
        count += 1
    return count

counts = {n: manifest(n) for n in [
    'k24-result-7a94472', 'n03-1105-2200', 'n18-f1-install-check',
    'n19-rest-fallback-7a94472', 'n06-handover-list-night',
    'n17-review-a1e38e7', 'overnight-20261105-scope']}

# Verify the exact original deployment source and the server record copies.
k = ROOT / 'docs/evidence/k24-result-7a94472'
m = k/'maint-n17'
source = blocks(git('show','a1e38e7:docs/LIMITED_LIVE_TEST_PLAN.md').decode())
bm = json.loads(text(k/'blocks/blocks.json'))
for name, val in bm.items():
    if name.startswith('_'):
        continue
    assert source[val['index']].encode() == (k/'blocks'/(name+'.sh')).read_bytes()
    assert sha(source[val['index']].encode()) == val['sha256']
server = {}
for row in text(k/'out/server-sha256.txt').splitlines():
    h, n = row.split(maxsplit=1)
    server[n.removeprefix('./')] = h
copies = list(p for p in m.iterdir() if p.is_file())
assert len(copies) == 71 and len(server) == 77
for p in copies:
    assert sha(p.read_bytes()) == server[p.name], p
assert text(m/'image-new.txt').strip() == IMAGE
assert text(m/'git-deploy.txt').splitlines()[0] == NEW
for stage in ['before','deploy','after30']:
    assert text(m/('s-'+stage+'.txt')).strip() == '66 | 63 | 3 | -'
    assert (m/('q-'+stage+'.txt')).read_bytes() == (m/'q-before.txt').read_bytes()
svc = [text(m/('svc-'+s+'.txt')).splitlines() for s in ['before','deploy','after30']]
assert svc[0][1:] == svc[1][1:] == svc[2][1:] and svc[1] == svc[2]
assert svc[1][0] == 'backend ' + IMAGE + ' 2026-11-05T10:41:45.989275157Z'
assert server['app-old.jar'] == 'a6f4357e8a8b53085541896c90c7c7add4632897dff062e6fdd130b07fa60e7e'
assert server['app-new.jar'] == 'bb67b521da94c6975243f9ff65f449ee6f0abd305ae829fadeb5bcadb0b849ea'
jd = text(m/'jar-diff.txt')
assert jd == text(ROOT/'docs/evidence/n17-rest-price-1103f48/jars/diff-1103f48-7a94472.txt')
assert len(jd.splitlines()) == 7 and jd.splitlines()[-1] == '(끝) 881 881'
for stage in ['T0','T1','T2','T3','T4']:
    s = text(k/('out-'+stage+'.txt'))
    assert 'rc=0' in s and '✅ '+stage in s
assert 'rc=1' in text(k/'out-T3-early-1.txt')
assert '기동 5분 전' in text(k/'out-T3-early-1.txt')
assert '🟢 21 / ⚪ 0 / 🔴 1' in text(k/'out-alerts-supp.txt')
assert '사전 승인을 T2에 쓰지 않았다' in text(m/'overnight-approval.txt')
assert text(k/'out/operator-confirm.txt').strip() == '확인 수신 19:41:40'
assert not any('rollback' in p.name for p in copies)

# N03: actual preserved report, raw DB contract and server hashes, no re-send.
n = ROOT/'docs/evidence/n03-1105-2200'
report, msg, send, raw = [text(n/x) for x in ['20261105-2200.txt','20261105-2200.txt.msg','last_send','20261105-2200.txt.raw']]
assert report.rstrip().splitlines()[-1] == send.strip()
assert '\n'.join(report.rstrip().splitlines()[:-1]) == msg.rstrip()
server_files = {}
for row in text(n/'server-files.txt').splitlines():
    mat = re.match(r'^([a-f0-9]{64})  (\S+)$',row)
    if mat:
        server_files[mat[2]] = mat[1]
for name in ['20261105-2200.txt','20261105-2200.txt.msg','20261105-2200.txt.raw','last_send']:
    assert sha((n/name).read_bytes()) == server_files[name]
red = [s for s in report.splitlines() if s.startswith('🔴')]
white = [s for s in report.splitlines() if s.startswith('⚪')]
assert len(red) == len(white) == 1
assert 'backend 기동(11-05 19:41)' in red[0] and 'ERROR 로그 범위 미확보' in white[0]
db = dict(re.findall(r'^(\w+)\|([^\n]*)$', raw, re.M))
assert [db[x] for x in ['sid_status','sid_available_krw','btc_h1_ok','btc_h1_done','btc_h1_need','btc_h1_daily']] == ['CREATED','20200.00','true','11-05 21:00','11-05 20:00','20']
assert len(re.findall(r'^🔴 .*1종: KRW-BLAST$',raw,re.M)) == 12
errors = text(n/'error_lines.txt').splitlines()
assert len(errors) == 3 and all('404' in x for x in errors)
assert all(any(coin in e for e in errors) for coin in ['BLAST','BONK','STORJ'])
assert IMAGE in text(n/'install_state.txt')
assert 'Created=2026-11-05T10:41:44.782771166Z' in text(n/'install_state.txt')
assert 'Hikari 누수 경고 0 · unleaked 0 · 획득 실패 0' in text(n/'observations.txt')

# N18 preserved local results and exact four-line insertion. No full rehearsal.
p18 = ROOT/'docs/evidence/n18-f1-install-check'
old_f1 = source[13]
proposal = text(p18/'f1-prop.sh')
addition = text(p18/'add.txt')
assert proposal.replace(addition,'',1) == old_f1
assert addition in proposal and proposal.index(addition) < proposal.index('/telegram/test')
summary = text(p18/'runs/r1/summary.txt').splitlines()
assert len(summary) == 18
for row in summary:
    if row.startswith('orig ') or row.startswith('prop normal '):
        assert 'f1_rc=0' in row and 'new_ok=1' in row and 'telegram=1 start=0' in row
    else:
        for required in ['f1_rc=1','f2_rc=1','new_ok=0','old_ok_left=0','failed_mark=1','telegram=0 start=0','오늘 F1 실패 기록']:
            assert required in row

# N19 fixed source, snapshots and preserved XML, not re-running Spring tests.
p19 = ROOT/'docs/evidence/n19-rest-fallback-7a94472'
assert git('rev-parse', CAND+'^').decode().strip() == NEW
paths = git('diff','--name-only',NEW,CAND).decode().splitlines()
assert len(paths) == 2 and all(x.endswith('.java') for x in paths)
xml_results = {}
for run, expected, classes in [('r1-failfirst-7a94472',[17,10,0,0],1),('r2-fix-related',[67,0,0,0],6)]:
    totals = [0,0,0,0]
    xmls = list((p19/'tests'/run/'xml').glob('*.xml'))
    for p in xmls:
        e = ET.fromstring(p.read_bytes())
        for i, key in enumerate(['tests','failures','errors','skipped']):
            totals[i] += int(e.attrib.get(key,0))
    assert len(xmls) == classes and totals == expected
    xml_results[run] = {'classes':classes,'tests_failures_errors_skipped':totals}
    for hrow in text(p19/'tests'/run/'src.sha256').splitlines():
        h, file = hrow.split(maxsplit=1)
        file = file.lstrip('*').removeprefix('./')
        data = (p19/'tests'/run/'src'/file).read_bytes()
        assert sha(data) == h
        ref = CAND if run == 'r2-fix-related' or '/test/' in file else NEW
        assert data.replace(b'\r\n',b'\n') == git('show',ref+':'+file)

# Verify actual retained jars using the existing full-item comparator.
ublocks = blocks(text(p19/'blocks/u-blocks.md'))
assert len(ublocks) == 8
syntax = []
for i,b in enumerate(ublocks):
    p = TMP/('u%d.sh'%i)
    p.write_text(b,encoding='utf-8')
    r = subprocess.run([BASH,'--noprofile','--norc','-n',p.as_posix()],capture_output=True)
    assert r.returncode == 0, r.stderr
    syntax.append(sha(b.encode()))
cmp_code = ublocks[2].split("<<'EOF' | tee \"$M/jar-diff.txt\"\n",1)[1].split('\nEOF',1)[0]
cmp_path = TMP/'jarcmp.py'
cmp_path.write_text(cmp_code,encoding='utf-8')
jars = [Path('D:/tmp/n17/jars/7a94472.jar'),Path('D:/tmp/n19/jars/f6868a5.jar')]
hashes = ['ddb64359f330419a0a6e233e4c09a2f0f7f1568784858bf344bf607ef10422fe','7c3a06550043a392d489d579c289c473b015010b9b8c177704807bf78747096a']
for jar,h in zip(jars,hashes):
    assert sha(jar.read_bytes()) == h
r = subprocess.run([sys.executable,'-B','-X','utf8',str(cmp_path),*map(str,jars)],capture_output=True)
assert r.returncode == 0
jar_out = r.stdout.decode().replace('\r\n','\n')
assert jar_out == text(p19/'jars/diff-7a94472-f6868a5.txt')
(OUT/'jar-compare.txt').write_text(jar_out,encoding='utf-8')

# Execute exact fallback methods with stubs. Never print untrusted fixture data.
java_runs = {}
for ref,label in [(NEW,'operating'),(CAND,'candidate')]:
    src = git('show',ref+':'+SERVICE+'LiveTradingService.java').decode()
    event = git('show',ref+':'+SERVICE+'RealtimePriceEvent.java').decode()
    (TMP/'RealtimePriceEvent.java').write_text(re.sub(r'^package [^;]+;\s*','',event),encoding='utf-8')
    price = method(git('show',ref+':'+SERVICE+'MarkPriceBook.java').decode(),'public static boolean isPrice(')
    java = '''import java.math.*;import java.time.*;import java.util.*;
public class N19Review {
 static final long WS_FALLBACK_THRESHOLD_SEC=30; boolean restFallbackActive;
 static class Health {boolean isWsUnhealthy(long age){return true;}}
 static class Subs {List<String> getSubscribedCoins(){return List.of("KRW-A","KRW-B");}}
 static class Client {List<Map<String,Object>> rows;List<Map<String,Object>> getTicker(String m){return rows;}}
 static class Pub {int count;void publishEvent(RealtimePriceEvent e){count++;}}
 static class Log {boolean raw=false,lineBreak=false;int errors,warns;
  void info(String s,Object...v){}
  void warn(String s,Object...v){warns++;for(Object x:v){String t=String.valueOf(x);raw|=t.contains("SYNTHETIC_MARKER");lineBreak|=t.contains("\\n");}}
  void error(String s,Object...v){errors++;}}
 static class MarkPriceBook { PRICE }
 Health exchangeHealthMonitor=new Health(); Subs wsSubscriptionManager=new Subs(); Client upbitRestClient=new Client();
 Pub eventPublisher=new Pub();Log log=new Log();
 REST
 FALLBACK
 static Map<String,Object> row(Object market,String price){return Map.of("market",market,"trade_price",price,"trade_timestamp",Instant.now().toEpochMilli());}
 static void one(String name,List<Map<String,Object>> rows,int pub,int errors,boolean raw){
  N19Review p=new N19Review();p.upbitRestClient.rows=rows;p.pollRestTickerFallback();
  if(p.eventPublisher.count!=pub||p.log.errors!=errors||p.log.raw!=raw)throw new AssertionError(name);
  System.out.println(name+"|published="+pub+"|errors="+errors+"|warns="+p.log.warns+"|untrusted_argument="+p.log.raw+"|newline_argument="+p.log.lineBreak);
 }
 public static void main(String[]args){
  one("positive_control",List.of(row("KRW-A","299"),row("KRW-B","300")),2,0,false);
  one("non_positive_before_good",List.of(row("KRW-A","0"),row("KRW-B","299")), ZERO,0,false);
  one("malformed_price_before_good",List.of(row("KRW-A","abc"),row("KRW-B","299")), MALFORMED, ERR,false);
  one("non_string_market_before_good",List.of(row(7,"0"),row("KRW-B","299")),0,1,false);
  one("untrusted_market_in_invalid_price_warning",List.of(row("SYNTHETIC_MARKER\\nFORGED_LINE","0"),row("KRW-B","299")), ZERO,0, RAW);
 }
}
'''.replace(' PRICE ',price).replace(' REST\n',method(src,'private static Instant restTradeTime(')+'\n').replace(' FALLBACK\n',method(src,'public void pollRestTickerFallback(')+'\n')
    fixed = label == 'candidate'
    java = java.replace(' ZERO',' 1' if fixed else ' 2').replace(' MALFORMED',' 1' if fixed else ' 0').replace(' ERR',' 0' if fixed else ' 1').replace(' RAW',' true' if fixed else ' false')
    (TMP/'N19Review.java').write_text(java,encoding='utf-8')
    c = subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',str(TMP),str(TMP/'RealtimePriceEvent.java'),str(TMP/'N19Review.java')],capture_output=True)
    assert c.returncode == 0, c.stderr.decode(errors='replace')
    r = subprocess.run([str(JAVA/'java.exe'),'-Dfile.encoding=UTF-8','-cp',str(TMP),'N19Review'],capture_output=True)
    (OUT/('method-'+label+'.txt')).write_bytes(r.stdout+r.stderr)
    assert r.returncode == 0,r.stderr.decode(errors='replace')
    java_runs[label] = {'cases':5,'compile':0,'run':0,'method_sha256':sha(method(src,'public void pollRestTickerFallback(').encode())}

result = {'review_ref':git('rev-parse',REF).decode().strip(),'manifests':counts,
    'k24':{'server_records_verified':71,'server_manifest_entries':77,'operating_sha':NEW,'image':IMAGE,
      'jar_changes':6,'jar_items':[881,881],'queries_and_sessions_unchanged':True,'other_services_unchanged':True,
      'early_T3_refused_then_valid_retry':True,'operator_confirmation_developer_record':True,
      'overnight_preapproval_not_used_for_T2':True,'rollback_not_performed':True},
    'n03':{'server_file_hashes':4,'white_log_coverage':1,'red_planned_boot':1,'errors_listed404':3,
      'all_BLAST_observations':12,'server_send':'2026-11-05 22:00:07 KST','phone_receipt':'unconfirmed',
      'coverage_before_19_41_not_secured':True,'post_report_22_00_29_news_error':'developer_report_not_raw_artifact'},
    'n18':{'preserved_old_passes':9,'preserved_proposal_valid':1,'preserved_proposal_rejections':8,
      'exact_insertion_only':True,'trailing_newline_marker_semantics_accepted':True},
    'n19':{'candidate':CAND,'paths':paths,'XML_preserved_not_rerun':xml_results,'run_sources_match_candidate_LF_normalized':True,
      'local_actual_jar_compare':{'changed':3,'items':[881,881]},'U_blocks_syntax':syntax,
      'exact_method_local_execution':java_runs,'untrusted_market_WARN_argument_reproduced':True,
      'non_string_market_aborts_later_good_reproduced_on_both':True,'production_occurrence_not_established':True,
      'candidate_and_U_blocks_not_approved_for_execution':True},
    'no_server_DB_network_Gradle_application_tests_or_jar_build':True}
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifests':counts,'k24_server_copies':71,'n03_server_files':4,'N18_preserved':[9,1,8],
    'N19_XML':xml_results,'N19_original_method_cases':10,'U_bash_n':8},ensure_ascii=False))
