from pathlib import Path
import subprocess,json,hashlib,re
OUT=Path(__file__).resolve().parent;ROOT=OUT.parents[2]
SHA='19dbad3a5a1cd205841aea96c400f6aa18fd089f'
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def sha(b):return hashlib.sha256(b).hexdigest()
sources=[]
for module in ['strategy-lib','core-engine','exchange-adapter','web-api']:
 for p in (OUT/'snapshot'/module/'src/main').rglob('*'):
  if not p.is_file():continue
  path=p.relative_to(OUT/'snapshot').as_posix();b=git('show',SHA+':'+path);local=p.read_bytes()
  sources.append({'path':path,'blob_sha256':sha(b),'compiled_copy_sha256':sha(local),'exact':b==local,'content_except_CRLF_equal':b.replace(b'\r\n',b'\n')==local.replace(b'\r\n',b'\n')})
assert all(r['content_except_CRLF_equal'] for r in sources)
audit=json.loads((OUT/'audit.json').read_text(encoding='utf8'))
def totals(prefix):
 rows={k:v for k,v in audit['xml'].items() if k.startswith(prefix)}
 return {'files':len(rows),**{k:sum(r[k] for r in rows.values()) for k in ['tests','failures','errors','skipped']}}
summary={
 'submission':git('rev-parse','c4a4d684').decode().strip(),'candidate':SHA,
 'main_sources':sources,'compiled_count':len(sources),'exact_count':sum(r['exact'] for r in sources),
 'submitted_xml':{name:totals(prefix) for name,prefix in {'related':'aligned-v150-20261111/related/TEST-','pg':'aligned-v150-20261111/pg/TEST-','old_binary':'aligned-v150-20261111/pg/oldbin/TEST-','http_psql_chain':'aligned-v150-20261111/pgchain/run-final-19dbad3a/TEST-'}.items()},
 'local_reviewer':{'candidate_tests':8,'candidate_tests_success':8,'first_probe_success':False,'first_probe_reason':'Same-thread settlement attached CLOSING to the reading persistence context; it did not simulate a separate quote handler. Candidate eight tests passed in that run.','corrected_probe_tests':1,'corrected_probe_success':1,'s5':'Separate quote thread commits settlement between balance read and positions read; performance reports completed one trade with old current asset and old realized PnL. Actual Spring/H2, controlled timing, not PG load.','external_orders_messages_calls':0},
 'tools_reviewer':json.loads((OUT/'tools-review.json').read_text(encoding='utf8')),
 'prod_actions':[],'pg_docker_buildjar_rerun':False,
}
(OUT/'review.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({k:v for k,v in summary.items() if k not in ('main_sources','tools_reviewer')},ensure_ascii=False,indent=2))
