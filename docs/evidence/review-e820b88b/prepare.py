from pathlib import Path
import subprocess,hashlib,json,io,zipfile,xml.etree.ElementTree as ET
OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[2]
MAIN='e820b88b'
SHA='72bbde46cd9bac1441605e9ad89ff00960fde5d9'
def git(*a): return subprocess.check_output(['git',*a],cwd=ROOT)
def sha(b):return hashlib.sha256(b).hexdigest()
checks={}; xml={}
for folder in ['aligned-v148-20261111','ab-create-v3-20261111','dynamic-jar-81b0e7a7','aligned-v148-review-864f070d','remaining-review-5624bd05']:
    prefix='docs/evidence/'+folder+'/'
    rows=[]
    for row in git('show',MAIN+':'+prefix+'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not row.strip():continue
        h,p=row.split(maxsplit=1);p=p.lstrip('*')
        r=subprocess.run(['git','show',MAIN+':'+prefix+p],cwd=ROOT,capture_output=True)
        b=r.stdout if r.returncode==0 else None
        local=sha((ROOT/prefix/p).read_bytes()) if (ROOT/prefix/p).exists() else None
        rows.append({'path':p,'expected':h,'blob':sha(b) if b is not None else None,'local':local,'ok':b is not None and sha(b)==h==local})
        if b is None: print('MISSING BLOB',folder,p,'local_match',h==local,flush=True)
        if p.endswith('.xml'):
            e=ET.fromstring(b);xml[folder+'/'+p]={k:int(e.get(k,'0')) for k in ['tests','failures','errors','skipped']}
    checks[folder]=rows
    print(folder,sum(x['ok'] for x in rows),len(rows),flush=True)
(OUT/'audit.json').write_text(json.dumps({'submission':git('rev-parse',MAIN).decode().strip(),'candidate':SHA,'checks':checks,'xml':xml},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
snap=OUT/'snapshot'
assert not snap.exists()
snap.mkdir()
zipfile.ZipFile(io.BytesIO(git('archive','--format=zip',SHA,'build.gradle','settings.gradle','gradlew','gradlew.bat','gradle','core-engine','strategy-lib','exchange-adapter','web-api'))).extractall(snap)
tool=OUT/'tools'
tool.mkdir()
paths=git('ls-tree','-r','--name-only',MAIN,'docs/evidence/ab-create-v3-20261111').decode().splitlines()
for p in paths:
    dest=tool/Path(p).relative_to('docs/evidence/ab-create-v3-20261111')
    dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(git('show',MAIN+':'+p))
print('snapshot/tool fixed',SHA,flush=True)
