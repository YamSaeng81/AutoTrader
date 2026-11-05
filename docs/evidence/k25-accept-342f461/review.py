"""Preserved morning evidence and N19v2 source/jar review. No server/Gradle."""
from pathlib import Path
import ast,hashlib,json,re,subprocess,sys,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
TMP=ROOT/'core-engine/build/k25-accept-342f461'
TMP.mkdir(parents=True,exist_ok=True)
REF='342f461'
OLD='7a944723c2959898226c2ad80f6b079ab5678296'
PRIOR='f6868a587a921dbc8b4985793582bc764b0896b1'
NEW='ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8'
SERVICE='web-api/src/main/java/com/cryptoautotrader/api/service/'
BASH='C:/Program Files/Git/bin/bash.exe'
JAVA=Path('C:/Program Files/Java/jdk-17/bin')

def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def text(p):return p.read_text(encoding='utf-8')
def sha(b):return hashlib.sha256(b).hexdigest()
def blocks(s):return re.findall(r'^```bash\n(.*?)^```',s,re.M|re.S)
def method(s,sig):
    start=s.index(sig);pos=s.index('{',start)+1;depth=1
    while depth:
        depth+=(s[pos]=='{')-(s[pos]=='}');pos+=1
    return s[start:pos]
def manifest(name):
    folder=ROOT/'docs/evidence'/name;count=0
    for row in text(folder/'SHA256SUMS').splitlines():
        if not row.strip():continue
        h,n=row.split(maxsplit=1);p=folder/n.lstrip('*')
        b=p.read_bytes()
        assert sha(b)==h,p
        assert b==git('show',REF+':'+p.relative_to(ROOT).as_posix()),p
        count+=1
    return count

counts={n:manifest(n) for n in ['morning-1106','n19v2-rest-fallback-ee509cf','night-review-303079b']}
morning=ROOT/'docs/evidence/morning-1106'
errors=[x for x in text(morning/'error-lines-0750.txt').splitlines() if ' ERROR ' in x]
assert len(errors)==7
assert sum('CryptoPanicSource' in x and 'Connection reset' in x for x in errors)==1
blast=[x for x in errors if 'KRW-BLAST' in x]
assert len(blast)==6
for prefix in ['19:10:01','19:10:46']:
    group=[x for x in blast if prefix in x]
    assert len(group)==3
    assert sum('status=404' in x and 'UpbitRestClient' in x for x in group)==1
    assert sum('404' in x and 'UpbitCandleCollector' in x for x in group)==1
    assert sum('DataCollectionService' in x for x in group)==1
window=text(morning/'news-2200-window.txt')
assert '13:00:29.675 [scheduler-2] ERROR' in window
assert '13:00:29.675 [scheduler-2] WARN' in window
assert 'sourceId=cryptopanic 결과=FAILED 종류=IOException' in window
assert '13:00:29.730 [scheduler-2] INFO' in window and 'sourceId=upbit_notice 전체=30건 신규=0건' in window
state=text(morning/'morning-0750.txt')
for s in ['서비스 5개 T4(20:12)와 같음','Created=2026-11-05T10:41:44.782771166Z','StartedAt=2026-11-05T10:41:45.989275157Z RestartCount=0 Status=running',
          'HEAD '+OLD,'세션 상태 T4 와 같음','SID 203·기준 행·EG·0 확인 통과','Flyway 94','true|11-06 06:00|11-06 06:00|20/20',
          '설치판 c669ccd 그대로','장부 설치 그대로','crontab T0 와 같음','과거 장부 파일 그대로',
          'ERROR 전체 7 · 세 마켓 404 밖 5','연결 획득 실패(Connection is not available) 0 · 누수 경고 0 · unleaked 0','rc=0']:
    assert s in state,s

p=ROOT/'docs/evidence/n19v2-rest-fallback-ee509cf'
assert git('rev-parse',NEW+'^').decode().strip()==PRIOR
assert git('rev-parse',PRIOR+'^').decode().strip()==OLD
paths=git('diff','--name-only',OLD,NEW).decode().splitlines()
vals=json.loads(text(p/'blocks/vals.json'))
assert paths==vals['DIFF'] and len(paths)==2
xml={}
for run,expected,classes in [('r3-failfirst-f6868a5',[23,5,0,0],1),('r4-fix-related',[73,0,0,0],6)]:
    d=p/'tests'/run;totals=[0,0,0,0];xmls=list((d/'xml').glob('*.xml'))
    for f in xmls:
        e=ET.fromstring(f.read_bytes())
        for i,k in enumerate(['tests','failures','errors','skipped']):totals[i]+=int(e.attrib.get(k,0))
    assert len(xmls)==classes and totals==expected
    xml[run]={'classes':classes,'tests_failures_errors_skipped':totals}
    for row in text(d/'src.sha256').splitlines():
        h,n=row.split(maxsplit=1);n=n.lstrip('*').removeprefix('./')
        data=(d/'src'/n).read_bytes();assert sha(data)==h
        ref=NEW if run=='r4-fix-related' or '/test/' in n else PRIOR
        assert data.replace(b'\r\n',b'\n')==git('show',ref+':'+n)
src={ref:git('show',ref+':'+SERVICE+'LiveTradingService.java').decode() for ref in [PRIOR,NEW]}
sig='public void pollRestTickerFallback('
assert src[PRIOR].replace(method(src[PRIOR],sig),'METHOD')==src[NEW].replace(method(src[NEW],sig),'METHOD')
assert method(src[NEW],sig).count('catch (NumberFormatException e)')==1
assert method(src[NEW],sig).count('catch (Exception e)')==1
assert 'String market = (String)' not in method(src[NEW],sig)

# The U92 blocks in the submitted plan and extraction match exactly.
plan=blocks(git('show',REF+':docs/LIMITED_LIVE_TEST_PLAN.md').decode())
ubs=blocks(text(p/'blocks/u-blocks.md'))
assert len(plan)==141 and len(ubs)==8 and plan[-8:]==ubs
oldubs=blocks(text(ROOT/'docs/evidence/n19-rest-fallback-7a94472/blocks/u-blocks.md'))
for name in ['btcok()','btcgate()','newsobs()','httpobs()','holdobs()','entryobs()','n17obs()']:
    assert method(ubs[0],name)==method(oldubs[0],name),name
syntax=[]
for i,b in enumerate(ubs):
    f=TMP/('u%d.sh'%i);f.write_text(b,encoding='utf-8')
    r=subprocess.run([BASH,'--noprofile','--norc','-n',f.as_posix()],capture_output=True)
    assert r.returncode==0,r.stderr
    syntax.append(sha(b.encode()))
assert 'UB=92' in ubs[0] and NEW in ubs[0] and OLD in ubs[0]
assert 'IMGOLD=sha256:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6' in ubs[0]
assert '~/maint-n17/image-new.txt' in ubs[1]
assert 'docker tag "$IMG0" $REPO:7a94472' in ubs[2]
assert 'U2 직전에' not in ubs[3] # checkpoint is documented outside the code, not fabricated in it.
assert '--no-build --no-deps backend' in ubs[3]
assert '--no-build --no-deps --force-recreate backend' in ubs[7]
assert 'git checkout --quiet $OLD' in ubs[7]
cmp_code=ubs[2].split("<<'EOF' | tee \"$M/jar-diff.txt\"\n",1)[1].split('\nEOF',1)[0]
assert cmp_code==oldubs[2].split("<<'EOF' | tee \"$M/jar-diff.txt\"\n",1)[1].split('\nEOF',1)[0]
cmp_path=TMP/'jarcmp.py';cmp_path.write_text(cmp_code,encoding='utf-8')
jars=[Path('D:/tmp/n17/jars/7a94472.jar'),Path('D:/tmp/n19v2/jars/ee509cf.jar')]
hs=['ddb64359f330419a0a6e233e4c09a2f0f7f1568784858bf344bf607ef10422fe','4f4f42aa058c34a15e438ba9048a9f94c49b2963a8bd72c5eb1ca3d5fbd6f4ec']
for f,h in zip(jars,hs):assert sha(f.read_bytes())==h
r=subprocess.run([sys.executable,'-B','-X','utf8',str(cmp_path),*map(str,jars)],capture_output=True)
assert r.returncode==0
jd=r.stdout.decode().replace('\r\n','\n')
assert jd==text(p/'jars/diff-7a94472-ee509cf.txt')
assert len(jd.splitlines())==4 and jd.splitlines()[-1]=='(끝) 881 881'
(OUT/'jar-compare.txt').write_text(jd,encoding='utf-8')

# Reuse the prior review's Java dependency-stub template, not its execution.
tree=ast.parse(text(ROOT/'docs/evidence/night-review-303079b/review.py'))
templates=[n.value for n in ast.walk(tree) if isinstance(n,ast.Constant) and isinstance(n.value,str) and n.value.startswith('import java.math.*;import java.time.*;import java.util.*;')]
assert len(templates)==1
java_runs={}
for ref,label in [(PRIOR,'before'),(NEW,'candidate')]:
    fixed=label=='candidate'
    template=templates[0].replace('public class N19Review','public class N19v2Review').replace('N19Review p=new N19Review()','N19v2Review p=new N19v2Review()')
    template=template.replace('List.of(row(7,"0"),row("KRW-B","299")),0,1,false)',
        'List.of(row(7,"0"),row("KRW-B","299")),1,0,false)' if fixed else 'List.of(row(7,"0"),row("KRW-B","299")),0,1,false)')
    price=method(git('show',ref+':'+SERVICE+'MarkPriceBook.java').decode(),'public static boolean isPrice(')
    java=template.replace(' PRICE ',price).replace(' REST\n',method(src[ref],'private static Instant restTradeTime(')+'\n').replace(' FALLBACK\n',method(src[ref],sig)+'\n')
    java=java.replace(' ZERO',' 1').replace(' MALFORMED',' 1').replace(' ERR',' 0').replace(' RAW',' false' if fixed else ' true')
    event=git('show',ref+':'+SERVICE+'RealtimePriceEvent.java').decode()
    (TMP/'RealtimePriceEvent.java').write_text(re.sub(r'^package [^;]+;\s*','',event),encoding='utf-8')
    (TMP/'N19v2Review.java').write_text(java,encoding='utf-8')
    c=subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',str(TMP),str(TMP/'RealtimePriceEvent.java'),str(TMP/'N19v2Review.java')],capture_output=True)
    assert c.returncode==0,c.stderr.decode(errors='replace')
    r=subprocess.run([str(JAVA/'java.exe'),'-Dfile.encoding=UTF-8','-cp',str(TMP),'N19v2Review'],capture_output=True)
    (OUT/('method-'+label+'.txt')).write_bytes(r.stdout+r.stderr)
    assert r.returncode==0,r.stderr.decode(errors='replace')
    java_runs[label]={'cases':5,'compile':0,'run':0,'method_sha256':sha(method(src[ref],sig).encode())}

# Execute only n19obs's embedded Python on preserved r4 logs and one synthetic mix.
obs=text(p/'blocks/n19obs.sh').split("<<'PYEOF'\n",1)[1].split('\nPYEOF',1)[0]
obs_path=TMP/'n19obs.py';obs_path.write_text(obs,encoding='utf-8')
xml_log=p/'tests/r4-fix-related/xml/TEST-com.cryptoautotrader.api.service.N19RestFallbackItemDefenseTest.xml'
# As in the submitted check_u.sh, actual application stdout is preserved in XML.
# The Gradle log has no application stdout for these passing tests.
log=TMP/'n19obs-xml-application.log'
log.write_text('\n'.join('backend-1  | '+x for x in text(xml_log).splitlines() if re.match(r'^\d{2}:\d{2}:\d{2}\.\d{3} \[',x))+'\n',encoding='utf-8')
r=subprocess.run([sys.executable,'-B','-X','utf8',str(obs_path),str(log)],capture_output=True)
assert r.returncode==0
o=r.stdout.decode().replace('\r\n','\n')
assert '형식 이상 6·가격 아님 8·코인 형식 2' in o
assert all(x not in o for x in ['N19FORGED','가짜표지','market=','KRW-'])
(OUT/'n19obs-preserved-log.txt').write_text(o,encoding='utf-8')

result={'review_source':git('rev-parse',REF).decode().strip(),'manifests':counts,
    'morning':{'read_time':'2026-11-06 07:50 KST','runtime_unchanged':True,'sid203_CREATED':True,'BTC':['true','06:00','06:00','20/20'],
      'raw_errors':7,'CryptoPanic_reset':1,'BLAST_404_related_errors':6,'helper_outside404_count_5_conservative_misclassification':True,
      'reset_FAILED_IOException_and_next_upbit_success_matched':True,'connection_acquisition_failure':0,'leak_warning':0,
      'original_runtime_logs_not_independently_downloaded_by_reviewer':True},
    'candidate':NEW,'operating_parent':OLD,'immediate_parent':PRIOR,'paths':paths,'preserved_XML_not_rerun':xml,
    'run_source_hashes_match_fixed_candidate_after_LF_normalization':True,'only_fallback_method_changes_since_f6868a5':True,
    'exact_method_stub_execution':java_runs,'untrusted_warning_and_non_string_market_findings_fixed':True,
    'jar_hashes':dict(zip([f.name for f in jars],hs)),'actual_preserved_jar_compare':[3,881,881],
    'fixed_U92_source_matches_plan':True,'U_bash_n_sha256':syntax,'prior_safety_functions_and_comparator_unchanged':True,
    'n19obs_preserved_log_counts':[6,8,2],
    'no_server_DB_network_Gradle_Spring_tests_or_jar_build':True}
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifests':counts,'morning_errors':[1,6],'XML':xml,'method_cases':10,'jar':[3,881,881],'U_syntax':8,'n19obs':[6,8,2]},ensure_ascii=False))
