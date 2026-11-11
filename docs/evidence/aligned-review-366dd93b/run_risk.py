from pathlib import Path
import subprocess,json
out=Path(__file__).resolve().parent; root=out.parents[2]
jdk=Path('C:/Program Files/Java/jdk-17/bin'); cp=';'.join([str(out/'classes')]+[str(p) for p in (out/'lib').glob('*.jar')])
def compile(src,dest,label):
    dest.mkdir(exist_ok=True);args=['-encoding','UTF-8','-cp',cp,'-d',str(dest),str(src)]
    argfile=out/(label+'.args');argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args))
    r=subprocess.run([str(jdk/'javac.exe'),'@'+str(argfile)],capture_output=True)
    (out/(label+'.log')).write_bytes(r.stdout+r.stderr); assert r.returncode==0,(label,r.returncode)
compile(out/'RiskReview949.java',out/'classes','risk-javac')
baseline=out/'risk-base';baseline.mkdir(exist_ok=True)
src=baseline/'RiskManagementService.java';src.write_bytes(subprocess.check_output(['git','show','af7ffa58:web-api/src/main/java/com/cryptoautotrader/api/service/RiskManagementService.java'],cwd=root))
compile(src,baseline/'classes','risk-base-javac')
for variant in ['candidate','base']:
    path=(str(baseline/'classes')+';' if variant=='base' else '')+cp
    argfile=out/('risk-'+variant+'.args');args=['-Dfile.encoding=UTF-8','-cp',path,'RiskReview949',variant]
    argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args))
    r=subprocess.run([str(jdk/'java.exe'),'@'+str(argfile)],capture_output=True)
    (out/('risk-'+variant+'.log')).write_bytes(r.stdout+r.stderr)
    print(r.stdout.decode('utf8'),end='');assert r.returncode==0,variant
