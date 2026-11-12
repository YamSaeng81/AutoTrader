from pathlib import Path
import subprocess
O=Path(__file__).resolve().parent;S=O/'B01';J=Path('C:/Program Files/Java/jdk-17/bin');L=O.parent/'review-aligned-9491166f/lib';C=O/'classes'
T=S/'web-api/src/test/java/com/cryptoautotrader/api/service/RealNextBarClockTest.java'
s=(O/'D-original.java.txt').read_bytes().decode('utf8').replace('\r\n','\n')
s=s.replace('        List<Candle> cs = uptrend(lastOpen);','        lastOpen = lastOpen.truncatedTo(ChronoUnit.HOURS);\n        useCloseHour(lastOpen);\n        List<Candle> cs = uptrend(lastOpen);')
s=s.replace('        assertThat(liveOrders(l, "BUY")).as("LIVE 매수 접수").hasSize(1);','        System.out.println("[ROOT D DIAG] LIVE logs=" + strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("LIVE",l.getId()).stream().map(x->x.getSignal()+":"+x.getReason()).toList());\n        System.out.println("[ROOT D DIAG] PAPER logs=" + strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER",p.getId()).stream().map(x->x.getSignal()+":"+x.getReason()).toList());\n        assertThat(liveOrders(l, "BUY")).as("LIVE 매수 접수").hasSize(1);')
T.write_bytes(s.encode('utf8'));(O/'D-diagnostic.java.txt').write_bytes(T.read_bytes())
cp=';'.join([str(C),str(L/'*'),str(S/'web-api/src/test/resources'),str(S/'web-api/src/main/resources')])
with (O/'javac-D-diagnostic.txt').open('wb') as f:r=subprocess.run([str(J/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(C),str(T)],stdout=f,stderr=subprocess.STDOUT)
assert r.returncode==0
with (O/'D-diagnostic.txt').open('wb') as f:r=subprocess.run([str(J/'java.exe'),'-Xmx768m','-Dfile.encoding=UTF-8','-cp',cp,'ReviewLauncher','com.cryptoautotrader.api.service.RealNextBarClockTest'],cwd=S/'web-api',stdout=f,stderr=subprocess.STDOUT,timeout=180)
print('diagnostic exit',r.returncode)
for line in (O/'D-diagnostic.txt').read_text(encoding='utf8',errors='replace').splitlines():
 if '[ROOT D DIAG]' in line or '[D]' in line: print(line)
