from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
E = Path(__file__).resolve().parent
REF = '1aa77ebed1140a705cbbc997cb007f8b899159d3'
BASE = ROOT / 'docs/evidence'


def sha(b):
    return hashlib.sha256(b).hexdigest()


def git(p):
    return subprocess.check_output(['git','show',f'{REF}:{p}'],cwd=ROOT)


def read(folder, name):
    return (BASE/folder/name).read_text(encoding='utf-8')


checks = {'source':REF,'manifests':{}}
for folder in ['n03-1104-2200','n13-repro-6995c52','n13-plan-6995c52','n13-hikari-initial','k21-result-review-4d8bd2f','n13-next-scope']:
    rows = []
    for line in read(folder,'SHA256SUMS').splitlines():
        h,n = re.fullmatch(r'([a-f0-9]{64}) [ *](.+)',line).groups()
        p=BASE/folder/n
        assert sha(p.read_bytes()) == sha(git(p.relative_to(ROOT).as_posix())) == h,n
        rows.append({'file':n,'sha256':h,'raw_and_Git_match':True})
    checks['manifests'][folder]=rows
assert [len(v) for v in checks['manifests'].values()] == [9,5,5,7,8,4]

n03='n03-1104-2200'
report,msg,last,raw=[read(n03,n) for n in ['20261104-2200.txt','20261104-2200.txt.msg','last_send','20261104-2200.txt.raw']]
assert '\n'.join(report.splitlines()[:-1]).strip() == msg.strip()
assert report.splitlines()[-1] == last.strip() == '2026-11-04 22:00:03 KST 보고 전송 성공 · 🔴 이상 1건'
assert [l for l in report.splitlines() if l.startswith('🔴 ')] == ['🔴 점검 구간 안에 backend 기동(11-04 16:39) — 계획된 재기동인지 확인']
assert '운영 6995c52 · 점검 스크립트 d491fd1609b7' in report
assert 'BTC H1 입력(관측 11-04 22:00:01 KST): B1 true · 최신 완료 봉 11-04 21:00 · 필요 11-04 20:00(2분 유예) · B3 최근 20일 마감 봉 20/20' in report
assert 'SID 203: CREATED · 잔액 20200.00 · 보유 0 · 미결 0' in report
assert '미분류 0' in report and '⚪' not in report
db=raw.split('--- DB (종료 0)\n')[1].split('---',1)[0]
kv=[l.split('|') for l in db.splitlines() if '|' in l and not l.startswith('END|')]
assert all(len(r)==2 for r in kv) and len({r[0] for r in kv})==len(kv)==32
obs=read(n03,'observations.txt')
rounds=re.findall(r'^(2026-11-04T\S+) 🟢21 ⚪0 🔴1 🔴코인=KRW-BLAST ⚪코인=- 재확인=-$',obs,re.M)
assert len(rounds)==5
assert [t[11:16] for t in rounds] == ['08:19','09:19','10:19','11:19','12:19']
http=re.findall(r'^(\d\d:\d\d:\d\d\.\d{3}) WARN CryptoPanicSource HTTP 403$',obs,re.M)
assert len(http)==11 and len(set(http))==11
assert 'leak detection triggered 35 · unleaked 34 · Connection is not available 0' in obs
install=read(n03,'install_state.txt')
assert install.count('f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b')==2
assert '2135e95314edeea68f6b9dfc31a1c0b0034f84b9d3cbfd52be7ee82afab9cc60' in install
assert '653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8' in install
error=read(n03,'error_lines.txt')
assert len(error.splitlines())==3 and all(c in error for c in ['KRW-BLAST','KRW-BONK','KRW-STORJ'])
assert sha(error.encode()) == '8b0d5f8fcff868c2918ad60f9159f0f1ef01454aa5e0195aaf845384326079e2'
checks['N03']={'accepted':True,'server_send':'success, phone receipt unknown','raw_DB_keys':32,'postdeploy_rounds_utc':rounds,
               'cryptopanic_HTTP403':http,'old_missing_hour_repair':'unconfirmed',
               'log_coverage':'current container 16:39 onward, not full nominal10:00-22:00',
               'remote_original_4_hash_match':'developer report; individual remote4 hashes not preserved separately',
               'N12_and_postdeploy_loading_gap':'remain closed within observation scope'}

plans=[]
for chunk in read('n13-plan-6995c52','plan-out.txt').split('== findPendingHoldFor')[1:]:
    lines=[l.strip().removesuffix('+').rstrip() for l in chunk.splitlines()]
    p=json.loads('\n'.join(lines[lines.index('['):lines.index('(1 row)')]))[0]
    def walk(n):
        yield n
        for c in n.get('Plans',[]):yield from walk(c)
    nodes=list(walk(p['Plan']))
    anti=[n for n in nodes if n.get('Join Type')=='Anti']
    assert len(anti)==1 and anti[0]['Node Type']=='Merge Join'
    assert 'coin_pair' in anti[0]['Merge Cond'] and 'created_at' not in anti[0]['Merge Cond']
    assert 'date_trunc' in anti[0]['Join Filter']
    assert any(n['Node Type']=='Seq Scan' and n.get('Alias')=='e' for n in nodes)
    plans.append({'method':'findPendingHoldFor'+chunk.splitlines()[0].split(' / ')[0],'json':p})
assert len(plans)==2
out=read('n13-plan-6995c52','plan-out.txt')
assert out.startswith('BEGIN\n') and out.rstrip().endswith('ROLLBACK\n== end')
assert (BASE/'n13-plan-6995c52/plan-stderr.txt').stat().st_size==0
scope=json.loads(read('n13-next-scope','source-and-scope.json'))
assert scope['sql_sha256']=='79d12e371ff7ba3a80fb2b5dc2715b2ff684f4ffac0b30473478ea0e89179c91'
checks['plan']={'accepted':'bounded planning read, not timing proof','SQL_same_hash':True,
                'transport':'Bash stdin redirection instead of PowerShell pipeline accepted; identical SQL scope',
                'estimated_rows':2521187,'statistics_last_autoanalyze':'2026-10-17T13:57:41.206443Z',
                'observation':'coin-only Merge Anti Join, time Join Filter, inner e Seq Scan',
                'local_performance_measurement_next':True,'production_actual_execution_or_DDL_next':False}
(E/'plans.json').write_text(json.dumps(plans,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

test=read('n13-repro-6995c52','HoldBaselineConnectionReproTest.java')
summary=read('n13-repro-6995c52','junit-summary.txt')
assert 'tests="6" skipped="0" failures="0" errors="0"' in summary
assert len(re.findall(r'Assertions\.assert',test))==1
events=read('n13-repro-6995c52','events.txt')
sections=re.split(r'^== ',events,flags=re.M)[1:]
assert len(sections)==6
phases=[]
for sec in sections:
    name=sec.splitlines()[0]
    number=int(name[0])
    rows=[]
    for line in sec.splitlines()[1:]:
        m=re.match(r'\s*(\d+)ms\s+(\S+)\s+tx=(true|false)\s+이스레드보유=(\d+) 풀사용중=(\d+)\s+(.*)',line)
        if m and ('질의 대기 시작' in m[6] or '가격 조회 대기 시작' in m[6]):
            tx=m[3]=='true'; held=int(m[4]); what=m[6]
            if number in [1,2,3,4]:
                assert not tx
                assert held == (1 if '질의 대기 시작' in what else 0),line
            if number==5:assert tx and held==1,line
            rows.append({'thread_kind':m[2],'transaction_active':tx,'held_by_thread':held,'pool_active':int(m[5]),'phase':what})
    if number>0:assert rows
    assert '-- 끝: 풀사용중=0' in sec
    phases.append({'case':name,'phase_records':rows})
assert '호출 예외로 끝남: JpaSystemException' in sections[4]
assert '후속 회차 끝 — 평가행 4h=2 24h=2' in sections[2] and '후속 회차 끝 — 평가행 4h=2 24h=2' in sections[4]
assert 'catchup 뒤 평가행 4h=2 24h=2' in sections[3]
checks['reproduction']={'accepted_as':'connection boundary observation, not PostgreSQL timing or candidate equivalence',
                        'developer_JUnit_summary':'6 completed,0 failures,0 errors',
                        'behavior_assertions':'source has proxy assertion only; cases1-5 record behavior rather than asserting; root checked submitted events directly',
                        'full_XML':'not in submitted evidence; no repeat required solely for this',
                        'phases_checked':phases,'H2_interval_adapter':'test-only; no production/native PostgreSQL plan proof',
                        'next_candidate_tests':'explicit PostgreSQL SQL-result equivalence and connection/row-outcome assertions'}

script=git('scripts/ops/daily_check.sh').decode()
assert 'logs --no-color --since' in script
assert 'APP_STARTED' in script and 'SINCE_ISO' in script
checks['N14']={'known_gap':'container recreation inside requested log window, ERROR total excludes earlier container',
               'scope':'local minimal monitor coverage candidate after N13, no archive merge or installation',
               'use_created_not_started_alone':'restart may retain same-container logs; inspect container Created to identify recreation gap',
               'classification':'known missing log prefix => WHITE, keep independent RED and continue DB/alerts checks'}
(E/'review.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'manifests':{k:len(v) for k,v in checks['manifests'].items()},'N03':'accepted','rounds':5,'HTTP403':11,
                  'plan':'2 planning JSONs parsed','repro':'6 event sections checked, behavior not JUnit assertions',
                  'N13':'query-only candidate + local PostgreSQL performance authorized; no prod DDL/ANALYZE',
                  'N14':'coverage candidate assigned'},ensure_ascii=False))
