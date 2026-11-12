from pathlib import Path
import subprocess, json, hashlib, sys, os
OUT=Path(__file__).resolve().parent; ROOT=OUT.parents[2]
prefix='docs/evidence/ab-create-v3-20261111/tool'
for path in subprocess.check_output(['git','ls-tree','-r','--name-only','24a7018e',prefix],cwd=ROOT).decode().splitlines():
    dest=OUT/'ab-create-v3-20261111/tool'/Path(path).relative_to(prefix)
    dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_bytes(subprocess.check_output(['git','show','24a7018e:'+path],cwd=ROOT))
env=os.environ.copy();env['PYTHONPATH']=str(OUT/'tools/create');env['PYTHONUTF8']='1'
with (OUT/'tools-tests.txt').open('wb') as f:
    r=subprocess.run([sys.executable,'-B','-X','utf8','-m','unittest','-v','test_ab_v4','test_ab_create'],cwd=OUT/'tools/tool',env=env,stdout=f,stderr=subprocess.STDOUT)
print('tools_tests_rc',r.returncode,flush=True)
if r.returncode:raise SystemExit(r.returncode)
sys.path[:0]=[str(OUT/'tools/create'),str(OUT/'tools/tool')]
import ab_complete as cp
import ab_arms as arms
protocol=OUT/'tools/tool/protocol.json'
raw=protocol.read_bytes();proto=json.loads(raw);psha=hashlib.sha256(raw).hexdigest()
rows=[]
for run in ['run-a','run-b-otherdb']:
    p=OUT/'pgchain'/run; d=json.loads((p/'create/creation_log.draft.json').read_bytes())
    good=(p/'ok.db_check.out').read_text(encoding='utf8')
    result=cp.complete(d,good,proto,psha,db_source='immutable local PG evidence; root did not query database')
    csvrows=arms.build_rows(result,proto,psha);arms.check_against_db_output(result,good)
    rows.append({'run':run,'input':'ok','completed':len(result['sessions']),'csv_rows':len(csvrows)})
    for source in sorted(p.glob('neg_*.db_check.out')):
        try:cp.complete(d,source.read_text(encoding='utf8'),proto,psha,db_source='immutable evidence')
        except cp.Rejected as e:rows.append({'run':run,'input':source.name,'rejected':True,'reason':str(e)})
        else:raise AssertionError('negative accepted '+str(source))
(OUT/'tools-replay.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('raw replay normal',sum('completed' in x for x in rows),'negative',sum('rejected' in x for x in rows),flush=True)
