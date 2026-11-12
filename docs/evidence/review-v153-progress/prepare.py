from pathlib import Path
import io, zipfile, subprocess, json, hashlib, xml.etree.ElementTree as ET
OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[2]
def git(*a): return subprocess.check_output(['git',*a],cwd=ROOT)
report={k:git('rev-parse',v).decode().strip() for k,v in {'main':'24a7018e','aligned':'5f9ef2ad','integration':'c7137896','protection':'ec7d33ef','E':'b46302d9','production':'b5cd1d0'}.items()}
for tag,sha in [('candidate',report['protection'])]:
    dst=OUT/tag
    assert not dst.exists()
    dst.mkdir()
    zipfile.ZipFile(io.BytesIO(git('archive','--format=zip',sha,'core-engine','strategy-lib','exchange-adapter','web-api'))).extractall(dst)
report['xml']={}
for p in sorted((ROOT/'docs/evidence/v153-20261112').rglob('*.xml')):
    e=ET.parse(p).getroot()
    report['xml'][p.relative_to(ROOT).as_posix()]={k:int(e.get(k,'0')) for k in ['tests','failures','errors','skipped']}
report['inputs']={}
for p in sorted((ROOT/'docs/evidence/v153-20261112').rglob('*')):
    if p.is_file(): report['inputs'][p.relative_to(ROOT).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
(OUT/'audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({k:v for k,v in report.items() if k not in ['xml','inputs']},indent=2))
print('xml',len(report['xml']),'input files',len(report['inputs']))
