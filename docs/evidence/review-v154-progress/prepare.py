from pathlib import Path
import hashlib, io, json, subprocess, zipfile, xml.etree.ElementTree as ET
O=Path(__file__).resolve().parent; R=O.parents[2]
def git(*args): return subprocess.check_output(['git',*args],cwd=R)
def sha(b): return hashlib.sha256(b).hexdigest()
refs={n:git('rev-parse',s).decode().strip() for n,s in {'main':'24a7018e','F':'9d92a0f7','loss':'0b23b44e','B01':'8e81d0b4','integration':'9bce7dc1','production':'b5cd1d0'}.items()}
for n in ['B01']:
    dst=O/n; assert not dst.exists(); dst.mkdir()
    zipfile.ZipFile(io.BytesIO(git('archive','--format=zip',refs[n],'core-engine','strategy-lib','exchange-adapter','web-api'))).extractall(dst)
for n,p in [('F','web-api/src/main/java/com/cryptoautotrader/api/service/RealtimeProtectionDispatcher.java'),('loss','web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java')]:
    (O/Path(p).name).write_bytes(git('show',refs[n]+':'+p))
inputs={}; xml={}
for rel in ['docs/evidence/v153-20261112','docs/evidence/n03-1112-1000']:
    for p in sorted((R/rel).rglob('*')):
        if not p.is_file(): continue
        key=p.relative_to(R).as_posix(); inputs[key]=sha(p.read_bytes())
        if p.suffix=='.xml':
            try:
                e=ET.parse(p).getroot(); xml[key]={k:int(e.get(k,'0')) for k in ['tests','failures','errors','skipped']}
                xml[key]['failures_detail']=[{'class':t.get('classname'),'test':t.get('name'),'message':f.get('message')} for t in e.findall('testcase') for f in list(t.findall('failure'))+list(t.findall('error'))]
            except Exception as e: xml[key]={'parse_error':type(e).__name__}
audit={'refs':refs,'inputs':inputs,'xml':xml}
(O/'audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({'refs':refs,'input_files':len(inputs),'xml_files':len(xml)},indent=2))
