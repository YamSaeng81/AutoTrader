from pathlib import Path
import subprocess,hashlib,json,io,zipfile,xml.etree.ElementTree as ET
OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[2]
MAIN='c4a4d684'
SHA='19dbad3a5a1cd205841aea96c400f6aa18fd089f'
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT)
def h(b): return hashlib.sha256(b).hexdigest()
checks={}; xml={}
for folder in ['aligned-v150-20261111','review-e820b88b','aligned-v148-20261111']:
    prefix='docs/evidence/'+folder+'/'
    rows=[]
    for row in git('show',MAIN+':'+prefix+'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not row.strip():continue
        expected,path=row.split(maxsplit=1);path=path.lstrip('*')
        result=subprocess.run(['git','show',MAIN+':'+prefix+path],cwd=ROOT,capture_output=True)
        blob=result.stdout if result.returncode==0 else None
        local=(ROOT/prefix/path).read_bytes() if (ROOT/prefix/path).exists() else None
        rows.append({'path':path,'expected':expected,'blob':h(blob) if blob is not None else None,'local':h(local) if local is not None else None,'ok':blob is not None and local is not None and h(blob)==expected==h(local)})
        if path.endswith('.xml') and blob is not None:
            e=ET.fromstring(blob)
            xml[folder+'/'+path]={k:int(e.get(k,'0')) for k in ['tests','failures','errors','skipped']}
    checks[folder]=rows
    print(folder,sum(r['ok'] for r in rows),'/',len(rows),flush=True)
assert all(r['ok'] for rows in checks.values() for r in rows)
(OUT/'audit.json').write_text(json.dumps({'submission':git('rev-parse',MAIN).decode().strip(),'candidate':SHA,'checks':checks,'xml':xml},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
snapshot=OUT/'snapshot';assert not snapshot.exists();snapshot.mkdir()
zipfile.ZipFile(io.BytesIO(git('archive','--format=zip',SHA,'build.gradle','settings.gradle','gradlew','gradlew.bat','gradle','core-engine','strategy-lib','exchange-adapter','web-api','crypto-trader-frontend'))).extractall(snapshot)
tool=OUT/'tools';tool.mkdir()
for path in git('ls-tree','-r','--name-only',MAIN,'docs/evidence/ab-create-v3-20261111').decode().splitlines():
    dest=tool/Path(path).relative_to('docs/evidence/ab-create-v3-20261111');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(git('show',MAIN+':'+path))
print('fixed sources extracted',SHA,flush=True)
