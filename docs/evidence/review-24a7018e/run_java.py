from pathlib import Path
import subprocess, json, hashlib, sys
OUT=Path(__file__).resolve().parent; SNAP=OUT/'snapshot'
LIB=OUT.parent/'review-aligned-9491166f/lib'; JDK=Path('C:/Program Files/Java/jdk-17/bin')
isolation=len(sys.argv)>1
sources=[]
for mod in ['strategy-lib','core-engine','exchange-adapter','web-api']:
    sources+=list((SNAP/mod/'src/main/java').rglob('*.java'))
if isolation:
    sources=[p for p in sources if p.name!='AsyncConfig.java']
    sources.append(OUT/'isolation/web-api/src/main/java/com/cryptoautotrader/api/config/AsyncConfig.java')
sources+=list((SNAP/'web-api/src/test/java/com/cryptoautotrader/api/support').glob('*.java'))
sources.append(SNAP/'web-api/src/test/java/com/cryptoautotrader/api/service/AbstractSma20SameInputFixture.java')
tests=['RealNextBarClockTest','SharedMarketDataExecutorSaturationTest'] if not isolation else ['MarketDataRejectIsolationTest','RejectPolicyWitnessTest']
if len(sys.argv)>2:tests=['RejectPolicyWitnessTest']
for name in tests:
    sources.append((OUT/name).with_suffix('.java') if name=='RejectPolicyWitnessTest' else (OUT/'isolation' if isolation else SNAP)/('web-api/src/test/java/com/cryptoautotrader/api/service/'+name+'.java'))
runner=OUT/'ReviewLauncher.java'
runner.write_text('import org.junit.platform.launcher.core.*;import org.junit.platform.launcher.listeners.*;import static org.junit.platform.engine.discovery.DiscoverySelectors.*;public class ReviewLauncher{public static void main(String[]args){var l=new SummaryGeneratingListener();var b=LauncherDiscoveryRequestBuilder.request();for(String s:args)b.selectors(selectClass("com.cryptoautotrader.api.service."+s));var x=LauncherFactory.create();x.registerTestExecutionListeners(l);x.execute(b.build());l.getSummary().printTo(new java.io.PrintWriter(System.out,true));l.getSummary().printFailuresTo(new java.io.PrintWriter(System.out,true));System.exit(l.getSummary().getTestsFailedCount()+l.getSummary().getContainersFailedCount()>0?1:0);}}',encoding='utf8')
sources.append(runner)
tag='isolation' if isolation else 'candidate';classes=OUT/('classes-'+tag);classes.mkdir(exist_ok=True)
args=['-encoding','UTF-8','-cp',';'.join(str(p) for p in LIB.glob('*.jar')),'-d',str(classes)]+[str(p) for p in sources]
argfile=OUT/('javac-'+tag+'.args');argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args),encoding='utf8')
with (OUT/('javac-'+tag+'.txt')).open('wb') as f:
    r=subprocess.run([str(JDK/'javac.exe'),'@'+str(argfile)],stdout=f,stderr=subprocess.STDOUT)
print('javac',tag,r.returncode,flush=True)
if r.returncode:raise SystemExit(r.returncode)
(OUT/'libraries.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in LIB.glob('*.jar')},indent=2)+'\n')
cp=';'.join([str(classes),str(LIB/'*'),str(SNAP/'web-api/src/test/resources'),str(SNAP/'web-api/src/main/resources')])
with (OUT/('spring-'+tag+'.txt')).open('wb') as f:
    r=subprocess.run([str(JDK/'java.exe'),'-Xmx1g','-Dfile.encoding=UTF-8','-cp',cp,'ReviewLauncher',*tests],cwd=SNAP/'web-api',stdout=f,stderr=subprocess.STDOUT,timeout=180)
print('tests',tag,r.returncode,flush=True)
raise SystemExit(r.returncode)
