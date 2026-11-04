from pathlib import Path
import hashlib,json,re,subprocess,sys,xml.etree.ElementTree as ET,ast
ROOT=Path(__file__).resolve().parents[3];E=Path(__file__).resolve().parent;BASE=ROOT/'docs/evidence'
REF='46f2c04da90607601eb3ea4050c26a6736ea3724'
def git(ref,p):return subprocess.run(['git','show',f'{ref}:{p}'],cwd=ROOT,capture_output=True)
def sha(b):return hashlib.sha256(b).hexdigest()
def text(p):return p.read_text(encoding='utf-8')
out={'source':REF,'manifests':{}}
for name in ['n13-sql-candidate-fe01207','n13-deploy-prep-fe01207','n14-log-coverage-c669ccd','n13-review-1aa77eb']:
    p=BASE/name;rows=[]
    for l in text(p/'SHA256SUMS').splitlines():
        h,n=re.fullmatch(r'([a-f0-9]{64}) [ *](.+)',l).groups();q=p/n;b=git(REF,q.relative_to(ROOT).as_posix())
        assert sha(q.read_bytes())==h
        if b.returncode==0:assert sha(b.stdout)==h
        rows.append({'path':n,'sha256':h,'raw_matches':True,'in_submission_Git':b.returncode==0})
    out['manifests'][name]=rows
missing=[r['path'] for r in out['manifests']['n13-deploy-prep-fe01207'] if not r['in_submission_Git']]
assert len(missing)==5 and all(n.endswith('.log') for n in missing)
out['missing_fixture_correction']='5 existing original .log fixtures were ignored by Git; folder .gitignore now unignores them, add originals next commit, no rerun needed'
xml=[]
for p in (BASE/'n13-sql-candidate-fe01207/tests/run1-pg/xml').glob('TEST-*.xml'):
    r=ET.parse(p).getroot();a=r.attrib
    assert all(int(a[k])==0 for k in ['failures','errors','skipped'])
    xml.append({k:a[k] for k in ['name','tests','failures','errors','skipped']})
assert sum(int(r['tests']) for r in xml)==16
out['XML']=xml
def assertions(ref):
    src=git(ref,'scripts/ops/test_daily_check.py').stdout.decode();tree=ast.parse(src)
    calls=sorted([n for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id=='check'],key=lambda n:n.lineno)
    return [ast.dump(c,include_attributes=False) for c in calls if not any(isinstance(n,ast.Constant) and isinstance(n.value,str) and n.value.startswith('N14 ') for n in ast.walk(c.args[0]))]
a,b=assertions('d491fd1'),assertions('c669ccd')
assert len(a)>0 and a==b
out['N14_old_test_assertions_unchanged']={'existing_static_check_calls':len(a),'all_existing_check_call_AST_equal':True,'runtime167_includes_loop_cases':True}
perf=[]
for run in ['r3','r4']:
    for p in sorted((BASE/f'n13-sql-candidate-fe01207/runs/{run}').glob('*.json')):
        data=json.loads(text(p));rr=[x for x in data if isinstance(x,dict) and 'variant' in x];summary={}
        for v in sorted({r['variant'] for r in rr}):
            rows=[r for r in rr if r['variant']==v];times=[r['execution_ms'] for r in rows if r.get('execution_ms') is not None]
            summary[v]={'n':len(rows),'success':sum(r.get('rc')==0 for r in rows),'min_execution_ms':min(times) if times else None,'max_execution_ms':max(times) if times else None}
            if v=='c2':
                assert all(r.get('rc')==0 for r in rows)
                for r in rows:
                    if not r.get('plan'):continue
                    def walk(n):
                        yield n
                        for c in n.get('Plans',[]):yield from walk(c)
                    anti=[n for n in walk(r['plan'][0]['Plan']) if n.get('Join Type')=='Anti']
                    assert anti and all('coin_pair' in n.get('Merge Cond','') and 'date_trunc' in n.get('Merge Cond','') for n in anti)
        perf.append({'run':run,'file':p.name,'variants':summary})
out['performance']=perf
# Compare retained actual jars with the submitted full-content comparator; never rebuild.
jars=[Path('D:/Claude Code/projects/cat-n12/web-api/build/libs/web-api-0.1.0.jar'),Path('D:/Claude Code/projects/cat-n13/web-api/build/libs/web-api-0.1.0.jar')]
assert all(p.is_file() for p in jars)
jarhash=[sha(p.read_bytes()) for p in jars]
recorded=[l.split()[0] for l in text(BASE/'n13-deploy-prep-fe01207/jar-sha.txt').splitlines()]
assert jarhash==recorded
r=subprocess.run([sys.executable,'-I','-X','utf8',str(BASE/'n13-deploy-prep-fe01207/jarcmp.py'),*[str(p) for p in jars]],capture_output=True)
expected='변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/StrategyLogRepository.class\n(끝) 879 879'
assert r.returncode==0 and r.stdout.decode().replace('\r\n','\n').strip()==expected
(E/'jar-comparison.txt').write_bytes(r.stdout)
out['jars']={'sha256':jarhash,'diff':expected,'rebuilt':False}
# Pure parser cases: original five fixtures plus stack/header/no-header/HTTP controls.
old=text(BASE/'n13-deploy-prep-fe01207/holdobs.sh').split("<<'PYEOF'\n",1)[1].split('\nPYEOF',1)[0]
(E/'holdobs-submitted.py').write_bytes(old.encode())
cases={}
for n,rc in [('none',0),('hold',3),('hold-noreturn',3),('other',3),('timeout',1)]:
    cases['original-'+n]=((BASE/f'n13-deploy-prep-fe01207/local-check/holdobs-cases/{n}.log').read_bytes(),rc)
head='backend-1  | 08:00:00.001 [scheduler-7] ERROR o.s.s.s.TaskUtils - Unexpected error occurred in scheduled task\n'
to='backend-1  | Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms. PRIVATE-REVIEW-46\n'
cases.update({'stack':((head+to).encode(),1),'stack-two-lines':((head+to+to).encode(),1),
              'orphan-stack':(to.encode(),1),'HTTP-timeout-control':((head+'backend-1  | java.net.http.HttpTimeoutException: request timed out\n').encode(),0),
              'CRLF-stack':((head+to).replace('\n','\r\n').encode(),1),
              'header-control':((head.rstrip()+' HikariPool-1 - Connection is not available, request timed out after 30000ms.\n').encode(),1),
              'blank':(b'\n',0)})
cd=E/'cases';cd.mkdir(exist_ok=True);results=[]
for n,(raw,expected_rc) in cases.items():
    f=cd/(n+'.txt');f.write_bytes(raw);item={'name':n,'expected_fixed':expected_rc}
    for tag,py in [('submitted',E/'holdobs-submitted.py'),('fixed',E/'holdobs-fixed.py')]:
        r=subprocess.run([sys.executable,'-I','-X','utf8',str(py),str(f)],capture_output=True)
        (cd/(n+'.'+tag+'.out')).write_bytes(r.stdout)
        assert not r.stderr and 'PRIVATE-REVIEW-46' not in r.stdout.decode() and 'scheduler-7' not in r.stdout.decode()
        item[tag+'_exit']=r.returncode
        if tag=='fixed':assert r.returncode==expected_rc,n
    results.append(item)
r=subprocess.run([sys.executable,'-I','-X','utf8',str(E/'holdobs-fixed.py')],capture_output=True);assert r.returncode==3
results.append({'name':'noargs','fixed_exit':3})
assert next(r for r in results if r['name']=='stack')['submitted_exit']==0
out['holdobs_cases']=results
# Check only the changed install continuation/errexit behavior, not production mutation.
inst=text(E/'install-fixed.sh');guard=inst.split('\nINSTALL_RC=$?\n',1)[1]
dry='SID_OK_STATUS=CREATED REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql DRY_RUN=1 ~/ops-scripts/daily_check.sh'
guard=guard.replace(dry,"printf 'DRY_EXECUTED\\n'")
igs=[]
for n,body,rc,want in [('ok',"printf 'INSTALL_OK\\n'",0,True),('stop',"exit 1",1,False),('unchecked-failure',"false\nprintf 'AFTER_FALSE\\n'",1,False)]:
    script='(\nset -euo pipefail\n'+body+'\n)\nINSTALL_RC=$?\n'+guard
    r=subprocess.run([r'C:\Program Files\Git\bin\bash.exe','--noprofile','--norc'],input=script.encode(),capture_output=True)
    assert r.returncode==rc and (b'DRY_EXECUTED' in r.stdout)==want and b'AFTER_FALSE' not in r.stdout
    igs.append({'case':n,'exit':r.returncode,'dry_ran':want,'errexit_preserved':True})
out['install_guard_cases']=igs
out['runtime_read_attempt']={'result':'ssh cat-prod alias not configured in this review session; DNS lookup failed before server command delivery','server_or_DB_read_completed':False}
out['ruling']={'N13':'C2 local candidate accepted, no index addition required now, operating improvement pending actual deployment',
               'N14':'candidate accepted, install first and verify first automatic report',
               'procedure':'root corrected stack-timeout detection/install continuation/new monitor expectation/DB timezone gate; use newly committed corrected source, not46f2c04',
               'production_change':'none in this review'}
(E/'review.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifest_raw_counts':{k:len(v) for k,v in out['manifests'].items()},'missing_Git_fixtures':5,'XML_tests':16,'old_N14_assertions':len(a),
                  'retained_jars':'one repository class,879->879','holdobs_cases':len(results),'install_guard_cases':3,'N13':'accepted','N14':'accepted','server_read':'not completed'},ensure_ascii=False))
