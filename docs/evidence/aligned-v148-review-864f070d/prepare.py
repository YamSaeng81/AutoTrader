from pathlib import Path
import subprocess, hashlib, json, io, zipfile, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
SHA='864f070dbd723513595ba4b8c6d021851785aa97'
MAIN='ae3883bc'
def git(*a): return subprocess.check_output(['git',*a],cwd=ROOT)
def sha(b): return hashlib.sha256(b).hexdigest()
checks=[]
prefix='docs/evidence/aligned-v147-20261111/'
for row in git('show',MAIN+':'+prefix+'SHA256SUMS').decode('utf-8-sig').splitlines():
    if not row.strip(): continue
    h,p=row.split(maxsplit=1); p=p.lstrip('*')
    b=git('show',MAIN+':'+prefix+p)
    checks.append({'path':p,'expected':h,'blob':sha(b),'local':sha((ROOT/prefix/p).read_bytes()),'ok':sha(b)==h==sha((ROOT/prefix/p).read_bytes())})
xml={}
for p in (ROOT/prefix).rglob('*.xml'):
    e=ET.fromstring(p.read_bytes()); xml[p.relative_to(ROOT/prefix).as_posix()]={k:int(e.get(k,'0')) for k in ['tests','failures','errors','skipped']}
result={'candidate':SHA,'submission':git('rev-parse',MAIN).decode().strip(),'parents':git('show','-s','--format=%P',SHA).decode().strip(),'checks':checks,'xml':xml}
(OUT/'audit.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
snap=OUT/'snapshot'; assert not snap.exists()
z=zipfile.ZipFile(io.BytesIO(git('archive','--format=zip',SHA,'build.gradle','settings.gradle','gradlew','gradlew.bat','gradle','core-engine','strategy-lib','exchange-adapter','web-api')))
snap.mkdir(); z.extractall(snap)
for grp in ['related-r3','runs/r2']:
    found=[v for k,v in xml.items() if k.startswith(grp+'/')]
    print(grp,len(found),{k:sum(x[k] for x in found) for k in ['tests','failures','errors','skipped']})
print('checks',sum(x['ok'] for x in checks),len(checks))
