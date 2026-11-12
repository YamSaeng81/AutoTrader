from pathlib import Path
import subprocess,json,hashlib
O=Path(__file__).resolve().parent;R=O.parents[2];C=O.parent/'review-v154-progress/classes';L=O.parent/'review-aligned-9491166f/lib';J=Path('C:/Program Files/Java/jdk-17/bin')
cp=';'.join(map(str,[O,C,L/'*']))
with (O/'guard-window.txt').open('wb') as f:
 r=subprocess.run([str(J/'java.exe'),'-Dfile.encoding=UTF-8','-cp',cp,'com.cryptoautotrader.api.service.GuardWindowProbe'],stdout=f,stderr=subprocess.STDOUT)
src='web-api/src/main/java/com/cryptoautotrader/api/service/CandleFreshnessGuard.java'
a=subprocess.check_output(['git','show','ee02b346:'+src],cwd=R)
b=subprocess.check_output(['git','show','8e81d0b4:'+src],cwd=R)
assert a==b
(O/'probe-result.json').write_text(json.dumps({'exit':r.returncode,'assertions':5,'scope':'actual unchanged CandleFreshnessGuard.problem and hour-key witness only; no Spring/N15/PG rerun','guard_source_sha256':hashlib.sha256(a).hexdigest(),'source_ee02_equals_8e81':True,'compiled_guard_sha256':hashlib.sha256((C/'com/cryptoautotrader/api/service/CandleFreshnessGuard.class').read_bytes()).hexdigest()},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print((O/'guard-window.txt').read_text(encoding='utf8'))
assert r.returncode==0
