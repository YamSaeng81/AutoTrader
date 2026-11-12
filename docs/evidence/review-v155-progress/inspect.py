from pathlib import Path
import hashlib,json,xml.etree.ElementTree as ET
O=Path(__file__).resolve().parent; A=json.loads((O/'audit.json').read_text(encoding='utf8'))
B=Path('D:/tmp/v153-runs/v155/int155b-full')
checks=[]
for line in (B/'hashes.sha256').read_text(encoding='utf8').splitlines():
 h,n=line.split(None,1); n=n.lstrip('*'); p=B/n
 checks.append({'file':n,'ok':p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==h})
print('HASH_MISMATCH',json.dumps([c for c in checks if not c['ok']],ensure_ascii=False))
print('HASH',len(checks),'XML',sum(c['file'].endswith('.xml') for c in checks))
e=ET.parse(B/'xml/web-api/TEST-com.cryptoautotrader.api.service.O1O3LogContractTest.xml').getroot()
txt=e.findtext('system-out','')+'\n'+e.findtext('system-err','')
(O/'O1O3-system.txt').write_text(txt,encoding='utf8')
ls=txt.splitlines()
for i,l in enumerate(ls):
 if any(x in l for x in ['OLCL','처리 중 예외','ERROR','TaskRejected','Rollback','OLCD']):
  print(l[:650])
rows=A['runs']['int155b-full']['xml'];mods={}
for r in rows:
 mod=Path(r['file']).parent.name
 t=mods.setdefault(mod,{k:0 for k in ['tests','failures','errors','skipped']})
 for k in t:t[k]+=r[k]
(O/'inspection.json').write_text(json.dumps({'checksums':checks,'modules':mods},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps(mods,indent=2))
