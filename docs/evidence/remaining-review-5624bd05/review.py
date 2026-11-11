from pathlib import Path
import subprocess,hashlib,json,xml.etree.ElementTree as ET,importlib.util,sys,copy,contextlib,io
from datetime import datetime,timedelta,timezone
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[3];OUT=Path(__file__).resolve().parent
HEAD='5624bd05'; SNAP=OUT/'snapshot'
def git(*a):return subprocess.check_output(['git',*a],cwd=ROOT)
def sha(b):return hashlib.sha256(b).hexdigest()
audit={'source_main':git('rev-parse',HEAD).decode().strip(),'folders':{},'xml':{}}
for name in ['rounds-active-20261111','ab-create-v2-20261111','dynamic-isolation-20261111']:
    prefix='docs/evidence/'+name+'/'; checks=[]
    for row in git('show',HEAD+':'+prefix+'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not row.strip():continue
        h,p=row.split(maxsplit=1);p=p.lstrip('*');b=git('show',HEAD+':'+prefix+p)
        checks.append({'path':p,'sha256':h,'blob':sha(b),'local':sha((ROOT/prefix/p).read_bytes()),'ok':sha(b)==h==sha((ROOT/prefix/p).read_bytes())})
        dst=SNAP/name/p;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(b)
        if p.endswith('.xml'):
            e=ET.fromstring(b);audit['xml'][name+'/'+p]={k:int(e.get(k,'0')) for k in ['tests','failures','errors','skipped']}
    audit['folders'][name]=checks
(OUT/'audit.json').write_text(json.dumps(audit,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
def module(name,p):
    spec=importlib.util.spec_from_file_location(name,p);mod=importlib.util.module_from_spec(spec);sys.modules[name]=mod;spec.loader.exec_module(mod);return mod
TOOL=SNAP/'ab-create-v2-20261111/tool'; CREATE=SNAP/'ab-create-v2-20261111/create'
ag=module('ab_aggregate',TOOL/'ab_aggregate.py'); fx=module('test_ab_aggregate',TOOL/'test_ab_aggregate.py'); cr=module('ab_create',CREATE/'ab_create.py')
results=[]
protocol,psha=cr.load_protocol(TOOL/'protocol.json')
NOW=datetime(2026,12,7,0,8,tzinfo=timezone.utc)
class FakeClient:
    last=None;scenario='ok'
    def __init__(self,*a,**kw):self.posts=0;self.gets=0;self.rows=[];FakeClient.last=self
    def call(self,method,path,body=None):
        if method=='GET':
            self.gets+=1
            if self.gets==2 and self.scenario=='reconcile_failure':return 503,{'success':False},None
            return 200,{'success':True,'data':copy.deepcopy(self.rows)},None
        assert method=='POST';self.posts+=1
        d={'id':1000+self.posts,'strategyName':body['strategyType'],'coinPair':body['coinPair'],'timeframe':body['timeframe'],'initialCapital':body['initialCapital'],'strategyParams':body['strategyParams'],'status':'RUNNING','startedAt':NOW.isoformat()}
        self.rows.append(d);return 200,{'success':True,'data':d},None
def run_create(name,scenario,prior=None):
    out=OUT/name;out.mkdir(exist_ok=False)
    token=OUT/'fake-token.txt';token.write_text('LOCAL_REVIEW_FAKE',encoding='utf8')
    if prior: cr.append(out/'journal.jsonl',prior)
    FakeClient.scenario=scenario
    args=['--base-url','http://invalid.invalid','--token-file',str(token),'--protocol',str(TOOL/'protocol.json'),'--out-dir',str(out),'--code-sha','b'*40,'--execute']
    buf=io.StringIO()
    with patch.object(cr,'Client',FakeClient),contextlib.redirect_stdout(buf):rc=cr.run(args,now=NOW)
    (out/'stdout.txt').write_text(buf.getvalue(),encoding='utf8')
    recs=cr.journal_lines(out/'journal.jsonl');reconcile=[x for x in recs if x['kind']=='reconcile'][-1]
    r={'id':name,'rc':rc,'posts':FakeClient.last.posts,'reconcile':reconcile};results.append(r);print(json.dumps(r,ensure_ascii=False))
    return r
control=run_create('C0-control','ok');assert control['rc']==0 and control['posts']==8
r=run_create('C1-reconcile-failed','reconcile_failure');assert r['rc']==0 and r['reconcile']['status']=='목록 조회 실패'
r=run_create('C2-intent-without-result','ok',{'kind':'intent','pair_arm':'P1A','body_sha256':'unresolved-prior-send'});assert r['posts']==8 and r['rc']==0
a=fx.arms();lg=fx.creation_log(a);lg['db_verified']=False
out=ag.aggregate(a,fx.positions(),fx.marks(),[],creation_log=lg)
r={'id':'A1-unverified-log','db_verified':False,'status':out['primary_status'],'primary':out['primary_mean_B_minus_A']};results.append(r);print(json.dumps(r,ensure_ascii=False));assert out['primary_mean_B_minus_A'] is not None
a=fx.arms();lg=fx.creation_log(a)
for x in a:x['ruleset_hash']=''
for x in lg['sessions']:x['ruleset_hash']=''
lg['db_verified']=True
out=ag.aggregate(a,fx.positions(),fx.marks(),[],creation_log=lg)
r={'id':'A2-empty-fingerprints','status':out['primary_status'],'primary':out['primary_mean_B_minus_A']};results.append(r);print(json.dumps(r,ensure_ascii=False));assert out['primary_mean_B_minus_A'] is not None
(OUT/'results.json').write_text(json.dumps(results,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
print('manifests',{k:(sum(x['ok'] for x in v),len(v)) for k,v in audit['folders'].items()})
for prefix in ['rounds-active-20261111/related-r2/','dynamic-isolation-20261111/related/']:
    vs=[v for k,v in audit['xml'].items() if k.startswith(prefix)];print(prefix,len(vs),{k:sum(v[k] for v in vs) for k in ['tests','failures','errors','skipped']})
