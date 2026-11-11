from pathlib import Path
import subprocess,hashlib,json,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[3]; OUT=Path(__file__).resolve().parent
def git(*a): return subprocess.check_output(['git',*a],cwd=ROOT)
def sha(b): return hashlib.sha256(b).hexdigest()
HEAD=git('rev-parse','HEAD').decode().strip()
result={'head_observed_after_tests':HEAD,'candidate':'864f070dbd723513595ba4b8c6d021851785aa97','followup_candidate':'fa4363f6dc4c873bf559c4483e473c06dfe85504','extra_evidence':{},'source_byte_checks':[]}
for name in ['aligned2-followups-20261111','n03-1111-1000']:
    prefix='docs/evidence/'+name+'/'
    rows=[]
    for line in git('show',HEAD+':'+prefix+'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not line.strip(): continue
        h,p=line.split(maxsplit=1);p=p.lstrip('*')
        b=git('show',HEAD+':'+prefix+p)
        rows.append({'path':p,'expected':h,'blob':sha(b),'local':sha((ROOT/prefix/p).read_bytes()),'ok':sha(b)==h==sha((ROOT/prefix/p).read_bytes())})
    result['extra_evidence'][name]=rows
for name in git('ls-tree','-r','--name-only',result['candidate'],'strategy-lib/src/main','core-engine/src/main','exchange-adapter/src/main','web-api/src/main').decode().splitlines():
    raw=git('show',result['candidate']+':'+name); actual=(OUT/'snapshot'/name).read_bytes()
    result['source_byte_checks'].append({'path':name,'blob_sha256':sha(raw),'compiled_source_sha256':sha(actual),'byte_equal':raw==actual,'ok':raw.replace(b'\r\n',b'\n')==actual.replace(b'\r\n',b'\n')})
result['followup_product_diff']=git('diff','--stat',result['candidate'],result['followup_candidate'],'--','strategy-lib/src/main','core-engine/src/main','exchange-adapter/src/main','web-api/src/main').decode()
result['root_execution']={'method':'JDK17 javac with previously cached dependencies, JUnit Launcher, real Spring/H2 context, fixed candidate product sources, mock exchange/order/Telegram/ws','source_provenance':'git archive with core.autocrlf=true: 442/443 product main files differ from blob only by CRLF; normalized content matches443/443; hashes record compiled bytes and blob bytes separately','tests':20,'success':20,'failures':0,'skipped':0,'witness_tests':6,'submitted_fix_tests':12,'submitted_real_order_tests':2,'scope':'witness assertions verify observed gaps, not acceptance criteria; no PostgreSQL/server/external call/bootJar/deploy'}
result['findings']=[
 {'id':'W1','priority':'P1','observed':'pending TIME_STOP settled at pre-decision H1 candle despite a newer fresh mark quote, in scheduled runStrategy','fill':'12482505.00000000','old_close':'12495000','fresh_quote':'13744500.0','decision':'2026-11-11T05:40:07.003106300Z','candle_end':'2026-11-11T05:39:06Z'},
 {'id':'W2/W3','priority':'P1','observed':'single and bulk history deletion erase STOPPED+CLOSING position and session without SELL settlement'},
 {'id':'W4','priority':'P1','observed':'pending CLOSING omitted from getOpenPositions and unrealized performance (500 -> 0, closed trade count 0)'},
 {'id':'W5','priority':'P1','observed':'injected afterCommit Telegram buffer exception leaves committed buy (position1,available200) with approval outcome error'},
 {'id':'W6','priority':'P1','observed':'known unrealized MDD peak gap persists: 30000peak/25000current triggered before memory reset, pass after; not a real process restart'}]
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('extra manifests',{k:(sum(x['ok'] for x in v),len(v)) for k,v in result['extra_evidence'].items()})
print('product files',sum(x['ok'] for x in result['source_byte_checks']),len(result['source_byte_checks']),'followup product diff',repr(result['followup_product_diff']))
