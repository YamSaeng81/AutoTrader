"""Inspect preserved N20 evidence and execute unchanged production class bodies with local stubs."""
from pathlib import Path
import hashlib,json,re,subprocess,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
TMP=ROOT/'core-engine/build/n20-review-9017d63'
TMP.mkdir(parents=True,exist_ok=True)
REF='9017d63'
OLD='7a944723c2959898226c2ad80f6b079ab5678296'
NEW='ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8'
INV='52114a32b6fb16021dcf977a742cbe6e519fc0e8'
SERVICE='web-api/src/main/java/com/cryptoautotrader/api/service/'
JAVA=Path('C:/Program Files/Java/jdk-17/bin')
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def read(p):return p.read_text(encoding='utf-8')
def sha(b):return hashlib.sha256(b).hexdigest()
def manifest(name):
    p=ROOT/'docs/evidence'/name;n=0
    for row in read(p/'SHA256SUMS').splitlines():
        h,f=row.split(maxsplit=1);f=f.lstrip('*').removeprefix('./');q=p/f
        assert sha(q.read_bytes())==h,q
        assert q.read_bytes()==git('show',REF+':'+q.relative_to(ROOT).as_posix()),q
        n+=1
    return n
counts={n:manifest(n) for n in ['n20-held-mark-repro-ee509cf','k25-accept-342f461']}
p=ROOT/'docs/evidence/n20-held-mark-repro-ee509cf'
sources={}
for name in ['HeldMarkRefresher','MarkPriceBook','RealtimePriceEvent']:
    path=SERVICE+name+'.java'
    before=git('show',OLD+':'+path);after=git('show',NEW+':'+path)
    assert before==after
    sources[name]=after.decode()
    if name!='RealtimePriceEvent':assert (p/'code'/ (name+'.java.ee509cf')).read_bytes().replace(b'\r\n',b'\n')==after
assert git('rev-parse',INV+'^').decode().strip()==NEW
testpath='web-api/src/test/java/com/cryptoautotrader/api/service/N20HeldMarkRefresherReproTest.java'
assert git('diff','--name-only',NEW,INV).decode().splitlines()==[testpath]
snapshot=p/'run-r1/src'/testpath
assert snapshot.read_bytes().replace(b'\r\n',b'\n')==git('show',INV+':'+testpath)
for row in read(p/'run-r1/src.sha256').splitlines():
    h,n=row.split(maxsplit=1);assert sha((p/'run-r1/src'/n.lstrip('*').removeprefix('./')).read_bytes())==h
xml=ET.fromstring(next((p/'run-r1/xml').glob('*.xml')).read_bytes())
assert [int(xml.get(k,0)) for k in ['tests','failures','errors','skipped']]==[20,0,0,0]
assert read(p/'run-r1/exit.txt').strip()=='0'
assert read(p/'run-r1/head.txt').strip()==NEW
stdout=xml.findtext('system-out') or ''
rows=[r for r in stdout.splitlines() if r.startswith('N20행|')]
assert len(rows)==20 and '\n'.join(rows)+'\n'==read(p/'rows.txt')
for kind in ['PRICE_FORGED','PRICE_ABC','MARKET_NUM']:
    deep=next(r for r in rows if r.startswith('N20행|'+kind+'|호가=DEEP|'))
    assert 'byTrade=[N20A]|byBook=[N20B, N20C]|unobserved=[]' in deep
    assert '|실패표시=false|경보=0|' in deep
    failed=next(r for r in rows if r.startswith('N20행|'+kind+'|호가=FAIL|'))
    assert '|unobserved=[N20B, N20C]|' in failed and '|실패표시=true|경보=1|' in failed
assert not any(x in read(snapshot) for x in ['PoolLossGuard','OrderExecutionEngine','sendMarketOrder'])

# Annotation removal / dependency substitution only; no production method body is edited.
def inner(name):
    s=sources[name]
    s=re.sub(r'^package .*?;\n|^import .*?;\n','',s,flags=re.M)
    s=re.sub(r'^\s*@[^\n]+\n','\n',s,flags=re.M)
    s=s.replace('public class '+name+' {','static class '+name+' {',1)
    s=s.replace('org.springframework.jdbc.core.JdbcTemplate','JdbcTemplate')
    s=s.replace('org.springframework.jdbc.core.RowCallbackHandler','RowCallbackHandler')
    if name=='HeldMarkRefresher':s=s.replace('static class HeldMarkRefresher {','static class HeldMarkRefresher {\nstatic final Log log = new Log();',1)
    return s
stub=r'''
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
public class N20Review {
static final String A="KRW-N20A", B="KRW-N20B", C="KRW-N20C";
static final String RAW="N20_RAW_SECRET\nERROR FORGED";
static final Instant NOW=Instant.parse("2026-11-06T00:00:00Z");
static final BigDecimal QTY=new BigDecimal("10");
static class Log {
 final List<String> lines=new ArrayList<>();
 void add(String level,String fmt,Object...args) {
   for(Object a:args) {int i=fmt.indexOf("{}"); if(i>=0)fmt=fmt.substring(0,i)+String.valueOf(a)+fmt.substring(i+2);}
   lines.add(level+" "+fmt);
 }
 void info(String f,Object...a){add("INFO",f,a);} void warn(String f,Object...a){add("WARN",f,a);}
 void error(String f,Object...a){add("ERROR",f,a);} void debug(String f,Object...a){add("DEBUG",f,a);}
}
record Row(String c,BigDecimal q){String getString(int n){return c;} BigDecimal getBigDecimal(int n){return q;}}
interface RowCallbackHandler{void processRow(Row r);}
static class JdbcTemplate{
 final Map<String,BigDecimal> held=new TreeMap<>();
 void query(String sql,RowCallbackHandler cb){held.forEach((c,q)->cb.processRow(new Row(c,q)));}
 @SuppressWarnings("unchecked") <T>T queryForObject(String sql,Class<T> t,Object...a){return (T)held.get((String)a[0]);}
}
static class TelegramNotificationService{final List<String> messages=new ArrayList<>();void sendCustomNotification(String m){messages.add(m);}}
static class UpbitRestClient{
 List<Map<String,Object>> ticker;String mode;boolean transport;
 final List<String> booksCalled=new ArrayList<>();
 List<Map<String,Object>> getTicker(String markets)throws Exception{if(transport)throw new Exception(RAW);return ticker;}
 List<Map<String,Object>> getOrderbook(String coin)throws Exception{
   booksCalled.add(coin);if(mode.equals("FAIL"))throw new Exception(RAW);
   return List.of(Map.of("timestamp",NOW.toEpochMilli(),"orderbook_units",List.of(Map.of("bid_price",new BigDecimal("99"),"bid_size",new BigDecimal(mode.equals("SHALLOW")?"3":"50")))));
 }
}
static Map<String,Object> ticker(Object c,Object p){Map<String,Object> m=new LinkedHashMap<>();m.put("market",c);m.put("trade_price",p);m.put("trade_timestamp",NOW.minusSeconds(5).toEpochMilli());return m;}
static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
static void run(String kind,String mode,int ageC,int ageB){
 JdbcTemplate jdbc=new JdbcTemplate();for(String c:List.of(A,B,C))jdbc.held.put(c,QTY);
 MarkPriceBook marks=new MarkPriceBook();marks.jdbc=jdbc;
 HeldMarkRefresher r=new HeldMarkRefresher(marks,jdbc);UpbitRestClient rest=new UpbitRestClient();rest.mode=mode;r.setUpbitRestClient(rest);
 TelegramNotificationService tg=new TelegramNotificationService();r.telegram=tg;HeldMarkRefresher.log.lines.clear();
 if(ageC>0)marks.observe(C,new BigDecimal("290"),NOW.minusSeconds(ageC),NOW.minusSeconds(ageC),MarkPriceBook.WS_TRADE);
 if(ageB>0)marks.observe(B,new BigDecimal("190"),NOW.minusSeconds(ageB),NOW.minusSeconds(ageB),MarkPriceBook.WS_TRADE);
 Object market=kind.equals("MARKET_NUM")?12345:B;
 Object price=switch(kind){case "PRICE_ABC"->"abc";case "ZERO"->BigDecimal.ZERO;default->new BigDecimal("200");};
 rest.ticker=List.of(ticker(A,new BigDecimal("100")),ticker(market,price),ticker(C,new BigDecimal("300")));
 rest.transport=kind.equals("TRANSPORT");HeldMarkRefresher.Result result=r.refreshNow(NOW);
 boolean cut=kind.equals("PRICE_ABC")||kind.equals("MARKET_NUM");
 if(cut)ok(!result.byTrade().contains(C),"normal C must be skipped in original");
 if(cut&&mode.equals("DEEP")){ok(result.byBook().contains(C),"C replaced with book");ok(result.unobserved().isEmpty(),"valid book price");ok(tg.messages.isEmpty(),"no failure alert");ok(HeldMarkRefresher.log.lines.size()==1,"only INFO, no cut record");}
 if(cut&&!mode.equals("DEEP"))ok(result.unobserved().contains(C)==(ageC==0||ageC>120),"C cached freshness");
 if(kind.equals("CONTROL")){ok(result.byTrade().equals(List.of(A,B,C)),"normal trade control");ok(rest.booksCalled.isEmpty(),"no orderbook");}
 if(kind.equals("ZERO")){ok(result.byTrade().contains(B),"old valid mark is wrongly labelled as this trade");ok(marks.get(B).source().equals(MarkPriceBook.WS_TRADE),"invalid price did not overwrite");ok(marks.freshPrice(B,NOW).compareTo(new BigDecimal("190"))==0,"valid old mark retained");}
 boolean rawTg=tg.messages.stream().anyMatch(m->m.contains(RAW));
 if(kind.equals("TRANSPORT"))ok(rawTg,"whole exception message propagated into alert");
 System.out.println(kind+"/"+mode+" Ccache="+ageC+" Bcache="+ageB+" byTrade="+result.byTrade()+" byBook="+result.byBook()+" unobserved="+result.unobserved()+" Csource="+(marks.get(C)==null?"-":marks.get(C).source())+" alerts="+tg.messages.size()+" raw_exception_in_alert="+rawTg);
}
public static void main(String[]args){
 run("CONTROL","DEEP",0,0);run("PRICE_ABC","DEEP",0,0);run("MARKET_NUM","DEEP",0,0);
 run("PRICE_ABC","SHALLOW",0,0);run("MARKET_NUM","FAIL",0,0);
 run("PRICE_ABC","FAIL",90,0);run("PRICE_ABC","FAIL",130,0);
 run("ZERO","DEEP",0,90);run("TRANSPORT","FAIL",0,0);
}
'''
program=stub+'\n'.join(inner(n) for n in ['HeldMarkRefresher','MarkPriceBook','RealtimePriceEvent'])+'\n}\n'
(TMP/'N20Review.java').write_text(program,encoding='utf-8')
r=subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8',str(TMP/'N20Review.java')],capture_output=True)
assert r.returncode==0,r.stderr.decode(errors='replace')
r=subprocess.run([str(JAVA/'java.exe'),'-Dfile.encoding=UTF-8','-cp',str(TMP),'N20Review'],capture_output=True)
assert r.returncode==0,r.stderr.decode(errors='replace')
assert len(r.stdout.decode().splitlines())==9
(OUT/'method-run.txt').write_bytes(r.stdout)
(OUT/'method-harness.java.txt').write_text(program,encoding='utf-8')
result={'review_ref':git('rev-parse',REF).decode().strip(),'candidate_not_deployed':NEW,'investigation_commit':INV,
 'manifests_worktree_and_git':counts,'preserved_JUnit':[20,0,0,0],'preserved_rows':20,'source_snapshot_sha256':sha(snapshot.read_bytes()),
 'production_blobs_unchanged_7a_to_ee':{n:git('rev-parse',NEW+':'+SERVICE+n+'.java').decode().strip() for n in sources},
 'root_original_class_body_stub_inputs':9,'new_boundaries':['zero-price with valid 90-second cached mark is mislabelled byTrade','controlled REST exception text reaches error/state/alert'],
 'not_executed':['server','network','DB','Spring/Gradle','pool guard','reservation/order/sell','jar build','deployment']}
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifests':counts,'XML_and_rows':20,'root_stub_inputs':9}))
