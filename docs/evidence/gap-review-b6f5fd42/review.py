"""Review stored 11-09 evidence and exercise only cmd_freshness with fake boundaries."""
import ast
import collections
import contextlib
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import types

ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
SCRATCH=ROOT/'core-engine/build/gap-review-b6f5fd42'
SCRATCH.mkdir(parents=True,exist_ok=True)
HEAD=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip()
assert HEAD.startswith('b6f5fd42')
BASE='1899b49f633f8aefdc66b15c42debdaba8b3852c'
src=ROOT/'docs/evidence/v1136-gap-1109/start_66.1899b49.py'
raw=src.read_bytes()
norm=lambda b:b.replace(b'\r\n',b'\n')
expected=subprocess.check_output(['git','show',f'{BASE}:scripts/prospective/start_66.py'],cwd=ROOT)
assert norm(raw)==norm(expected)
result={'head':HEAD,'source':BASE,'source_sha256_lf':hashlib.sha256(norm(raw)).hexdigest()}
result['manifests']={}
for folder in ('v1136-gap-1109','review-94317640'):
    directory=ROOT/'docs/evidence'/folder
    entries=[]
    for line in (directory/'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        m=re.fullmatch(r'([0-9a-f]{64}) [ *](.+)',line)
        assert m,(folder,line)
        expected_hash,name=m.groups()
        path=directory/name
        assert hashlib.sha256(path.read_bytes()).hexdigest()==expected_hash,path
        entries.append((path.relative_to(ROOT).as_posix(),expected_hash))
    data=subprocess.check_output(['git','cat-file','--batch'],cwd=ROOT,input=''.join(f'{HEAD}:{p}\n' for p,_ in entries).encode('utf-8'))
    pos=0
    for path,want in entries:
        end=data.index(b'\n',pos); header=data[pos:end].split()
        assert len(header)==3 and header[1]==b'blob',(path,header)
        size=int(header[2]); assert hashlib.sha256(data[end+1:end+1+size]).hexdigest()==want,path
        pos=end+2+size
    assert pos==len(data)
    result['manifests'][folder]=len(entries)

folder=ROOT/'docs/evidence/v1136-gap-1109'
cron=(folder/'syslog-cron.txt').read_text(encoding='utf-8')
result['cron_launches_kst']={h:len(re.findall(rf'Nov  9 {h}:19:01 .*CMD .*start_66.py freshness',cron)) for h in ('02','03','04','05')}
assert all(n==1 for n in result['cron_launches_kst'].values())
rows=[json.loads(line) for line in (folder/'freshness_log-1108T14-22.jsonl').read_text(encoding='utf-8').splitlines()]
result['stored_rows']=[]
for row in rows:
    errors=collections.Counter(str(v.get('exErr')) for v in row['rows'].values())
    retained=[coin for coin,v in row['rows'].items() if v.get('exErr') in ('net','404') and v.get('exTo')]
    assert not retained,(row['at'],retained)
    result['stored_rows'].append({'at':row['at'],'rechecked':len(row.get('rechecked',[])),
        'green':row['green'],'unknown':row['unknown'],'red':row['red'],'ex_errors':dict(errors),'retained_error_value':retained})

# Compile selected original function definitions only: no module top-level environment load,
# URL open, DNS, sockets, SSH, application API or actual waiting can run.
tree=ast.parse(raw.decode('utf-8-sig'))
selected=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in ('cmd_freshness','_parse_iso')]
assert len(selected)==2
compiled=compile(ast.fix_missing_locations(ast.Module(body=selected,type_ignores=[])),str(src),'exec')
start=datetime(2026,11,8,19,19,1,tzinfo=timezone.utc)
old=datetime(2026,11,8,16,0,tzinfo=timezone.utc)
new=old+timedelta(hours=1)

def case(name,first,second,second_db,expected_verdict):
    directory=SCRATCH/name; directory.mkdir(exist_ok=False)
    clock={'now':start}
    class FakeDateTime(datetime):
        @classmethod
        def now(cls,tz=None):return clock['now']
    def sleep(seconds):clock['now']+=timedelta(seconds=seconds)
    db_calls=[]; exchange_calls=[]
    def db():
        db_calls.append(True)
        value=old if len(db_calls)==1 else second_db
        return {'KRW-A':{'to':value.isoformat(),'count':1}}
    def exchange(pair):
        exchange_calls.append(pair)
        return first if len(exchange_calls)==1 else second
    forbidden=lambda *args,**kwargs:(_ for _ in ()).throw(AssertionError('external boundary called'))
    namespace={'datetime':FakeDateTime,'timezone':timezone,'json':json,'os':__import__('os'),
        'time':types.SimpleNamespace(sleep=sleep),'COINS':['A'],'UPBIT_GAP_S':1,
        'need_env':lambda:None,'_db_latest':db,'_upbit_latest_h1':exchange,
        '_streak_reset':lambda *args:None,'_streak_update':lambda *args:None,
        'FRESH_LOG':str(directory/'freshness_log.jsonl'),'FRESH':str(directory/'fresh.json'),
        'call':forbidden,'urlopen':forbidden}
    exec(compiled,namespace)
    output=io.StringIO()
    with contextlib.redirect_stdout(output):code=namespace['cmd_freshness'](save=True)
    saved=json.loads((directory/'freshness_log.jsonl').read_text(encoding='utf-8'))
    actual='unknown' if saved['unknown'] else 'red' if saved['red'] else 'green'
    record={'name':name,'expected':expected_verdict,'actual':actual,'contract_matches':actual==expected_verdict,
        'exit':code,'entry':saved,'first_lookup_at':start.isoformat(),'finished_at':clock['now'].isoformat(),
        'db_calls':len(db_calls),'exchange_calls':len(exchange_calls)}
    (OUT/f'{name}.txt').write_text(output.getvalue(),encoding='utf-8')
    return record

cases=[
    case('recheck-net', (new,None),(None,'net'),new,'unknown'),
    case('recheck-404', (new,None),(None,'404'),new,'red'),
    case('recheck-success', (new,None),(new,None),new,'green'),
    case('no-recheck-success', (old,None),(old,None),old,'green'),
    case('initial-net-recovered', (None,'net'),(new,None),new,'green'),
    case('both-net', (None,'net'),(None,'net'),old,'unknown'),
    case('both-404', (None,'404'),(None,'404'),old,'red'),
]
assert [c['contract_matches'] for c in cases]==[False,False,True,True,True,True,True]
assert cases[0]['entry']['rows']['KRW-A']['exErr']=='net' and cases[0]['entry']['green']==1
assert cases[1]['entry']['rows']['KRW-A']['exErr']=='404' and cases[1]['entry']['green']==1
assert cases[3]['entry']['at']==start.isoformat() and cases[3]['finished_at']!=start.isoformat()
assert cases[0]['entry']['at']==cases[0]['finished_at']
result['local_cases']=cases
result['network_calls']=0
result['prior_incident_contains_new_case']=False
result['deployment_authorized']=False
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifests':result['manifests'],'cron_launches_kst':result['cron_launches_kst'],
    'new_local_failures':[c['name'] for c in cases if not c['contract_matches']],
    'controls_pass':5,'incident_saved_rows_no_retained_errors':True,'network_calls':0},ensure_ascii=False))
