from pathlib import Path
import sys,unittest,json,contextlib,copy,io,zipfile,hashlib,subprocess,importlib.util
OUT=Path(__file__).resolve().parent
sys.path[:0]=[str(OUT/'tools/create'),str(OUT/'tools/tool')]
import test_ab_aggregate,test_ab_create,test_chain
suite=unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromModule(test_ab_aggregate),unittest.defaultTestLoader.loadTestsFromTestCase(test_ab_create.CreateMock),unittest.defaultTestLoader.loadTestsFromModule(test_chain)])
with (OUT/'python-tests.txt').open('w',encoding='utf8') as f,contextlib.redirect_stdout(f):
 r=unittest.TextTestRunner(stream=f,verbosity=2).run(suite)
print('in-memory tools tests',r.testsRun,'success',r.wasSuccessful())
def entries(path):
 own=('core-engine-','strategy-lib-','exchange-adapter-');out={};dups=[]
 with zipfile.ZipFile(path) as z:
  names=[x.filename for x in z.infolist() if not x.is_dir()]
  if len(names)!=len(set(names)):dups.append('outer')
  for i in z.infolist():
   if i.is_dir():continue
   if i.filename.startswith('BOOT-INF/lib/') and Path(i.filename).name.startswith(own):
    with zipfile.ZipFile(io.BytesIO(z.read(i))) as inner:
     ns=[x.filename for x in inner.infolist() if not x.is_dir()]
     if len(ns)!=len(set(ns)):dups.append(i.filename)
     for j in inner.infolist():
      if not j.is_dir():out[i.filename+'!'+j.filename]=hashlib.sha256(inner.read(j)).hexdigest()
   else:out[i.filename]=hashlib.sha256(z.read(i)).hexdigest()
 return out,dups
old=Path('D:/tmp/o13n21-1899b49/web-api/build/libs/web-api-0.1.0.jar');new=Path('D:/tmp/dyn-81b0e7a7/web-api/build/libs/web-api-0.1.0.jar')
a,ad=entries(old);b,bd=entries(new)
result={'old_sha256':hashlib.sha256(old.read_bytes()).hexdigest(),'new_sha256':hashlib.sha256(new.read_bytes()).hexdigest(),'expanded_counts':[len(a),len(b)],'duplicate_names':[ad,bd],'changed':[k for k in sorted(set(a)|set(b)) if a.get(k)!=b.get(k)]}
# Tiny synthetic jar duplicate-name negative control against submitted comparator, no product jar edit.
import warnings
for name,duplicate in [('control.jar',False),('duplicate.jar',True)]:
 with warnings.catch_warnings():
  warnings.simplefilter('ignore')
  with zipfile.ZipFile(OUT/name,'w') as z:
   z.writestr('application.yml',b'fixed')
   if duplicate:z.writestr('application.yml',b'fixed')
ROOT=OUT.parents[2]
script=subprocess.check_output(['git','show','e820b88b:docs/evidence/dynamic-jar-81b0e7a7/jar_compare.py'],cwd=ROOT)
(OUT/'submitted_jar_compare.py').write_bytes(script)
p=subprocess.run([sys.executable,'-B','-X','utf8',str(OUT/'submitted_jar_compare.py'),str(OUT/'control.jar'),str(OUT/'duplicate.jar')],capture_output=True,text=True,encoding='utf8')
result['duplicate_control_submitted_output']=json.loads(p.stdout);assert result['duplicate_control_submitted_output']['changed']==[]
(OUT/'jar-review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('expanded jar counts',result['expanded_counts'],'changed',len(result['changed']),'submitted ignores duplicate-name control')
raise SystemExit(0 if r.wasSuccessful() else 1)
