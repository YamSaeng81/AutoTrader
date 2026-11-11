from pathlib import Path
import subprocess, hashlib, json, io, zipfile, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SHA = '9491166f93dc0e9ba5c83108853720ea6bfa5e0f'
def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)
def sha(b): return hashlib.sha256(b).hexdigest()
result = {'head':git('rev-parse','HEAD').decode().strip(), 'candidate':SHA, 'parents':git('show','-s','--format=%P',SHA).decode().strip(), 'evidence':{}, 'xml':{}}
for folder in ['aligned-gaps-20261111','stop-race-wait-20261111']:
    prefix = 'docs/evidence/'+folder
    manifest = git('show','HEAD:'+prefix+'/SHA256SUMS').decode('utf-8-sig')
    checks=[]
    for row in manifest.splitlines():
        if not row.strip(): continue
        expected, rel = row.split(maxsplit=1)
        rel=rel.lstrip('*')
        path=prefix+'/'+rel
        local=(ROOT/path).read_bytes()
        blob=git('show','HEAD:'+path)
        checks.append({'path':rel,'expected':expected,'local':sha(local),'blob':sha(blob),'ok':sha(local)==sha(blob)==expected})
    result['evidence'][folder]=checks
for p in (ROOT/'docs/evidence/aligned-gaps-20261111').rglob('*.xml'):
    try:
        e=ET.fromstring(p.read_bytes())
        result['xml'][str(p.relative_to(ROOT))]={k:e.get(k) for k in ['tests','failures','errors','skipped']}
    except ET.ParseError: pass
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
snap=OUT/'snapshot'
assert not snap.exists(), 'snapshot already exists; do not overwrite'
paths=['build.gradle','settings.gradle','gradlew','gradlew.bat','gradle','core-engine','strategy-lib','exchange-adapter','web-api']
data=git('archive','--format=zip',SHA,*paths)
z=zipfile.ZipFile(io.BytesIO(data))
snap.mkdir()
for name in z.namelist():
    dest=(snap/name).resolve()
    assert dest.is_relative_to(snap.resolve())
z.extractall(snap)
print(json.dumps({'head':result['head'],'candidate':SHA,'checks':{k:[sum(vv['ok'] for vv in v),len(v)] for k,v in result['evidence'].items()},'snapshot':str(snap),'source_files':len(z.namelist())},ensure_ascii=False))
