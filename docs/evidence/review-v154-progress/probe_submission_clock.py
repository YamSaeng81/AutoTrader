from pathlib import Path
import subprocess, difflib, json
O=Path(__file__).resolve().parent;S=O/'B01';J=Path('C:/Program Files/Java/jdk-17/bin');L=O.parent/'review-aligned-9491166f/lib';C=O/'classes'
files=[]; diff=[]
for name in ['LiveTradingService.java','PaperTradingService.java']:
 p=S/'web-api/src/main/java/com/cryptoautotrader/api/service'/name
 old=p.read_bytes().decode('utf8').replace('\r\n','\n')
 start=old.index('String notQueued = entryPriorityRounds.submit(')
 end=old.index('Instant.now());',start)
 new=old[:end]+'(entryClock != null ? entryClock.now() : Instant.now()));'+old[end+len('Instant.now());'):]
 assert new!=old
 diff.extend(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='before/'+name,tofile='probe-only/'+name))
 p.write_bytes(new.encode('utf8'));files.append(str(p))
(O/'submission-clock-probe.diff').write_text(''.join(diff),encoding='utf8')
cp=';'.join([str(C),str(L/'*'),str(S/'web-api/src/test/resources'),str(S/'web-api/src/main/resources')])
with (O/'javac-submission-clock.txt').open('wb') as f:r=subprocess.run([str(J/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(C)]+files,stdout=f,stderr=subprocess.STDOUT)
assert r.returncode==0
with (O/'D-submission-clock.txt').open('wb') as f:r=subprocess.run([str(J/'java.exe'),'-Xmx768m','-Dfile.encoding=UTF-8','-cp',cp,'ReviewLauncher','com.cryptoautotrader.api.service.RealNextBarClockTest'],cwd=S/'web-api',stdout=f,stderr=subprocess.STDOUT,timeout=180)
print('probe-only submission clock exit',r.returncode)
for line in (O/'D-submission-clock.txt').read_text(encoding='utf8',errors='replace').splitlines():
 if '[ROOT D DIAG]' in line or '[D]' in line: print(line)
(O/'submission-clock-result.json').write_text(json.dumps({'exit_code':r.returncode,'scope':'scratch-only LIVE and PAPER submission timestamps, not submitted candidate','fixture':'same hour-aligned D with diagnostics'},indent=2)+'\n')
assert r.returncode==0
