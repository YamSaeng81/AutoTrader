from pathlib import Path
import sys,json,hashlib,copy,importlib.util,contextlib,io,subprocess
OUT=Path(__file__).resolve().parent;ROOT=OUT.parents[2];MAIN='c4a4d684'
tool=OUT/'tools';sys.path[:0]=[str(tool/'create'),str(tool/'tool')]
import ab_complete as cp
def get(path): return subprocess.check_output(['git','show',MAIN+':'+path],cwd=ROOT)
protocol_raw=(tool/'tool/protocol.json').read_bytes();protocol=json.loads(protocol_raw);psha=hashlib.sha256(protocol_raw).hexdigest()
prefix='docs/evidence/aligned-v150-20261111/pgchain/'
cases=[]
for tag in ['run-final-19dbad3a','run3-wrongdb']:
    draft=json.loads(get(prefix+tag+'/create/creation_log.draft.json'))
    text=get(prefix+tag+'/ok.db_check.out').decode('utf8')
    with contextlib.redirect_stdout(io.StringIO()):result=cp.complete(draft,text,protocol,psha)
    rows=cp.parse_db_output(text);by_id={r['id']:r for r in rows['stored']}
    pairs=[{'id':s['session_id'],'api_started_at':s['created_at'],'db_started_at':by_id[s['session_id']]['started_at'],'same_instant':cp._ts(s['created_at'])==cp._ts(by_id[s['session_id']]['started_at'])} for s in draft['sessions']]
    cases.append({'case':tag,'completed':result['db_verified'],'matched_started_at':sum(p['same_instant'] for p in pairs),'sessions':pairs})
    assert result['db_verified'] is True
    print(tag,'complete=True start_time_matches',sum(p['same_instant'] for p in pairs),'/8')
    print(json.dumps(pairs,ensure_ascii=False),flush=True)
    if tag=='run3-wrongdb': assert sum(p['same_instant'] for p in pairs)==0
root=prefix+'run-final-19dbad3a/'
draft=json.loads(get(root+'create/creation_log.draft.json'));text=get(root+'ok.db_check.out').decode('utf8')
for name,filename,expected in [('missing-fingerprint','neg_fp_missing.db_check.out',True),('multiple-fingerprint','neg_fp_multiple.db_check.out',True),('trade-before-T0','neg_trade_before_t0.db_check.out',True),('stored-mismatch','neg_stored_ratio.db_check.out',True)]:
    rejected=False
    try: cp.complete(copy.deepcopy(draft),get(root+filename).decode('utf8'),protocol,psha)
    except cp.Rejected as e: rejected=True;reason=str(e)
    cases.append({'case':name,'rejected':rejected,'reason':reason if rejected else None});assert rejected is expected
    print(name,'rejected',rejected)
(OUT/'tools-review.json').write_text(json.dumps({'cases':cases,'tool':{'ab_complete_sha256':hashlib.sha256((tool/'create/ab_complete.py').read_bytes()).hexdigest(),'protocol_sha256':psha},'external_calls':0},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
