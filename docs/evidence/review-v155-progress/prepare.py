from pathlib import Path
import subprocess, hashlib, json, io, zipfile, xml.etree.ElementTree as ET
O=Path(__file__).resolve().parent; R=O.parents[2]
def git(*a): return subprocess.check_output(['git',*a],cwd=R)
def sha(b): return hashlib.sha256(b).hexdigest()
refs={k:git('rev-parse',v).decode().strip() for k,v in {'main':'HEAD','integration':'ee02b346','B01integration':'c6b14e18','preB01':'9bce7dc1','production':'b5cd1d0'}.items()}
S=O/'source'; assert not S.exists(); S.mkdir()
zipfile.ZipFile(io.BytesIO(git('archive','--format=zip',refs['integration'],'core-engine','strategy-lib','exchange-adapter','web-api'))).extractall(S)
out={}; paths={}
for n in ['int155b-full','int155-full','int155b-diag-N15-O1O3','c6b14e18-diag-N15-now','9bce7dc1-diag-N15-now','int155-pg']:
    base=Path('D:/tmp/v153-runs/v155')/n
    rows=[]; files={}
    for p in sorted(base.rglob('*')):
        if not p.is_file(): continue
        files[p.relative_to(base).as_posix()]=sha(p.read_bytes())
        if p.suffix!='.xml':continue
        try:
            e=ET.parse(p).getroot()
            rows.append({'file':str(p),'class':e.get('name'),'time':e.get('timestamp'),**{k:int(e.get(k,'0')) for k in ['tests','failures','errors','skipped']},'failures_detail':[{'test':t.get('name'),'message':f.get('message')} for t in e.findall('testcase') for f in list(t.findall('failure'))+list(t.findall('error'))]})
        except ET.ParseError:pass
    out[n]={'xml':rows,'files':files}
    totals={k:sum(r[k] for r in rows) for k in ['tests','failures','errors','skipped']}
    print(n,len(files),len(rows),totals)
    for row in rows:
        if row['failures'] or row['errors']:print(json.dumps(row,ensure_ascii=False))
(O/'audit.json').write_text(json.dumps({'refs':refs,'runs':out},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
for n in ['N15EntryExpiryTest','N15FinalApprovalBoundaryTest','N15FinalBoundaryPgTest','O1O3LogContractTest','AbstractSma20SameInputFixture']:
    p=S/'web-api/src/test/java/com/cryptoautotrader/api/service'/f'{n}.java'
    if p.exists():
        print('\nSOURCE',n)
        ls=p.read_text(encoding='utf8').splitlines()
        for i,s in enumerate(ls):
            if any(x in s for x in ['Instant base','Instant.now','minusSeconds(','fake.set(','wireClock(','EntryClock','@TestPropertySource','@DirtiesContext','RoundKey(', 'LAST_ROUND','lastOpen =']):
                print(i+1, '\n'.join(ls[max(0,i-1):i+2]))
