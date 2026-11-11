from pathlib import Path
import subprocess, zipfile, json, shutil, hashlib
OUT=Path(__file__).resolve().parent
SNAP=OUT/'snapshot'
CACHE=Path('C:/Users/SangJoon_Lee/.gradle/caches/modules-2/files-2.1')
JDK=Path('C:/Program Files/Java/jdk-17/bin')
lib=OUT/'lib'; lib.mkdir(exist_ok=True)
jar=Path('D:/tmp/sma20p-b5cd1d0/web-api/build/libs/web-api-0.1.0.jar')
manifest={}
with zipfile.ZipFile(jar) as z:
    for name in z.namelist():
        if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
            b=z.read(name); (lib/Path(name).name).write_bytes(b); manifest[Path(name).name]=hashlib.sha256(b).hexdigest()
extra=[('org.projectlombok','lombok','1.18.36'),('org.springframework','spring-test','6.1.6'),('org.springframework.boot','spring-boot-test','3.2.5'),('org.springframework.boot','spring-boot-test-autoconfigure','3.2.5'),('org.junit.jupiter','junit-jupiter-api','5.10.2'),('org.junit.jupiter','junit-jupiter-engine','5.10.2'),('org.junit.jupiter','junit-jupiter-params','5.10.2'),('org.junit.platform','junit-platform-launcher','1.10.2'),('org.junit.platform','junit-platform-engine','1.10.2'),('org.junit.platform','junit-platform-commons','1.10.2'),('org.apiguardian','apiguardian-api','1.1.2'),('org.opentest4j','opentest4j','1.3.0'),('org.assertj','assertj-core','3.25.3'),('org.mockito','mockito-core','5.7.0'),('org.mockito','mockito-junit-jupiter','5.7.0'),('org.objenesis','objenesis','3.3'),('net.bytebuddy','byte-buddy-agent','1.14.13'),('com.h2database','h2','2.2.224')]
for group,name,ver in extra:
    paths=list((CACHE/group/name/ver).glob('*/'+name+'-'+ver+'.jar'))
    assert len(paths)==1,(group,name,ver,paths)
    b=paths[0].read_bytes(); (lib/paths[0].name).write_bytes(b);manifest[paths[0].name]=hashlib.sha256(b).hexdigest()
(OUT/'libraries.json').write_text(json.dumps(manifest,indent=2)+'\n')
classes=OUT/'classes'; classes.mkdir(exist_ok=True)
sources=[]
for mod in ['strategy-lib','core-engine','exchange-adapter','web-api']:
    sources+=list((SNAP/mod/'src/main/java').rglob('*.java'))
sources+=list((SNAP/'web-api/src/test/java/com/cryptoautotrader/api/support').glob('*.java'))
sources+=[SNAP/'web-api/src/test/java/com/cryptoautotrader/api/service'/f for f in ['AbstractSma20SameInputFixture.java','AlignedReview949Test.java']]
runner=OUT/'ReviewLauncher.java'
runner.write_text('''import org.junit.platform.launcher.core.*;import org.junit.platform.launcher.listeners.*;import static org.junit.platform.engine.discovery.DiscoverySelectors.*;public class ReviewLauncher{public static void main(String[]args){var l=new SummaryGeneratingListener();var r=LauncherDiscoveryRequestBuilder.request().selectors(selectClass("com.cryptoautotrader.api.service.AlignedReview949Test")).build();var x=LauncherFactory.create();x.registerTestExecutionListeners(l);x.execute(r);l.getSummary().printTo(new java.io.PrintWriter(System.out,true));l.getSummary().printFailuresTo(new java.io.PrintWriter(System.out,true));System.exit(l.getSummary().getTestsFailedCount()+l.getSummary().getContainersFailedCount()>0?1:0);}}''')
sources.append(runner)
run=1
while (OUT/('javac-run%d.log'%run)).exists(): run+=1
args=['-encoding','UTF-8','-cp',';'.join(str(p) for p in lib.glob('*.jar')),'-d',str(classes)]+[str(p) for p in sources]
(OUT/'javac.args').write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args),encoding='utf8')
with (OUT/('javac-run%d.log'%run)).open('wb') as f:
    r=subprocess.run([str(JDK/'javac.exe'),'@'+str(OUT/'javac.args')],stdout=f,stderr=subprocess.STDOUT)
print('javac_rc',r.returncode,flush=True)
if r.returncode: raise SystemExit(r.returncode)
cp=';'.join([str(classes),str(lib/'*'),str(SNAP/'web-api/src/test/resources'),str(SNAP/'web-api/src/main/resources')])
with (OUT/('manual-test-run%d.log'%run)).open('wb') as f:
    r=subprocess.run([str(JDK/'java.exe'),'-Xmx1g','-Dfile.encoding=UTF-8','-cp',cp,'ReviewLauncher'],cwd=SNAP/'web-api',stdout=f,stderr=subprocess.STDOUT,timeout=180)
print('test_rc',r.returncode)
raise SystemExit(r.returncode)
