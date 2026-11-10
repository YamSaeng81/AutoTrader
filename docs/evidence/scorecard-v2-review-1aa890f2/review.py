"""Review preserved scorecard material and the candidate helper; no service calls."""
import csv
import hashlib
import io
import json
import re
import statistics
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import datetime
from decimal import Decimal as D
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
WORK = ROOT / 'core-engine/build/scorecard-v2-review-1aa890f2'
WORK.mkdir(parents=True, exist_ok=True)
B = ROOT / 'docs/evidence/scorecard-v2-20261110'
OLD = ROOT / 'docs/evidence/paper-scorecard-20261110'
HEAD = '1aa890f2fdf0ba4df2f70453f62e5c4425c23722'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def sha(b):
    return hashlib.sha256(b).hexdigest()

def manifest(base, expected_n):
    result = []
    for line in (base / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        want, rel = re.split(r' [ *]', line, maxsplit=1)
        rel = rel.strip()
        p = base / rel
        blob = git('show', HEAD + ':' + p.relative_to(ROOT).as_posix())
        result.append(dict(path=rel, expected=want, local=sha(p.read_bytes()), blob=sha(blob)))
    assert len(result) == expected_n, len(result)
    assert all(x['expected'] == x['local'] == x['blob'] for x in result)
    return result

submitted = manifest(B, 22)
previous = manifest(ROOT / 'docs/evidence/paper-scorecard-review-ca762315', 7)
run = subprocess.run([sys.executable, '-I', '-B', '-X', 'utf8', str(B/'score_v2.py'),
                      str(OLD/'raw/raw.txt'), str(OLD/'raw/raw2.txt'), str(B/'raw/raw4.txt'),
                      str(B/'raw/raw5.txt'), str(WORK/'replay')], cwd=ROOT, capture_output=True)
(WORK/'replay-stdout.txt').write_bytes(run.stdout)
(WORK/'replay-stderr.txt').write_bytes(run.stderr)
assert run.returncode == 0 and not run.stderr, run.stderr
replay = []
for p in sorted((B/'out').iterdir()):
    q = WORK/'replay-stdout.txt' if p.name == 'run-stdout.txt' else WORK/'replay'/p.name
    replay.append(dict(path=p.name, equal=p.read_bytes() == q.read_bytes()))
assert len(replay) == 4 and all(x['equal'] for x in replay), replay

def sections(p):
    sec, cur = {}, None
    for line in p.read_text(encoding='utf-8').splitlines(keepends=True):
        if line.startswith('@@'):
            cur = line[2:].strip()
            sec[cur] = []
        elif cur:
            sec[cur].append(line)
    return {k: list(csv.DictReader(io.StringIO(''.join(v)))) for k,v in sec.items()}

T, T2, T4, T5 = [sections(p) for p in (OLD/'raw/raw.txt', OLD/'raw/raw2.txt', B/'raw/raw4.txt', B/'raw/raw5.txt')]
snap = {r['ruleset_hash']:r for r in T4['ruleset_snapshot']}
rh = {(r['src'],r['id']):r['ruleset_hash'] for r in T2['ruleset']}
KEY = 'exit.slTightenOnLoss=false'
ps = T['paper_positions']
def invested(p):
    return D(p['invested_krw']) if p['invested_krw'] else D(p['size'])*D(p['avg_price'])
assert not any(522 <= int(p['session_id']) <= 587 for p in ps)
assert not any(r['session_type']=='PAPER' and 522 <= int(r['session_id']) <= 587 for r in T5['gate_hold'])
cohorts = defaultdict(list)
for p in ps:
    h = rh.get(('PAPER',p['id']))
    cls = 'missing' if not h else 'post' if KEY in snap[h]['params_text'].split('\n') else 'pre'
    cohorts[cls].append(p)
cohort_summary = {}
for cls, rows in cohorts.items():
    widths = [(D(p['entry_price'])-D(p['stop_loss_price']))/D(p['entry_price'])*100 for p in rows if p['stop_loss_price']]
    inv = sum((invested(p) for p in rows),D(0))
    net = sum((D(p['realized_pnl']) for p in rows),D(0))
    cohort_summary[cls] = dict(n=len(rows), median_stored_sl_pct=str(statistics.median(widths)),
                               ret_on_repeated_invested_pct=str(net/inv*100),
                               exit_counts=dict(Counter(p['exit_reason'] for p in rows)))
assert {k:v['n'] for k,v in cohort_summary.items()} == {'missing':58,'pre':755,'post':247}

bins = defaultdict(list)
for p in ps:
    bins[(p['coin_pair'],p['opened_at'][:16],p['closed_at'][:16])].append(p)
spreads = [max(D(p['realized_pnl'])/invested(p)*100 for p in v)
           - min(D(p['realized_pnl'])/invested(p)*100 for p in v)
           for v in bins.values() if len(v)>1]
cluster = dict(minute_groups=len(bins), multi_groups=len(spreads),
               exact_return_different=sum(v!=0 for v in spreads), max_spread_pp=str(max(spreads)),
               over_0_001pp=sum(v>D('0.001') for v in spreads))
assert (cluster['minute_groups'],cluster['multi_groups'],cluster['exact_return_different'],cluster['over_0_001pp']) == (660,218,163,0)

suites = []
for p in sorted((B/'candidate/pass').glob('TEST-*.xml')):
    a = ET.parse(p).getroot().attrib
    suites.append(dict(path=p.name, tests=int(a['tests']), failures=int(a['failures']), errors=int(a['errors']), skipped=int(a['skipped'])))
assert sum(s['tests'] for s in suites)==22
assert all(s['failures']==s['errors']==s['skipped']==0 for s in suites)
mut = ET.parse(B/'candidate/mutation/TEST-DailySma20PaperForwardPathTest.xml').getroot().attrib
assert int(mut['tests'])==6 and int(mut['failures'])==1

# All source checks are against the operating/candidate commits, not main's unshipped V95.
source_paths = {
 'helper':'web-api/src/main/java/com/cryptoautotrader/api/service/PaperMaxInvestment.java',
 'paper':'web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java',
 'live':'web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java',
 'checker':'core-engine/src/main/java/com/cryptoautotrader/core/risk/ExitRuleChecker.java',
 'fingerprint':'core-engine/src/main/java/com/cryptoautotrader/core/risk/RulesetFingerprint.java',
}
sources = {}
for name,path in source_paths.items():
    ref = '2aa2a498' if name in ('helper','paper') else 'b5cd1d0'
    data = git('show',ref+':'+path)
    sources[name] = dict(ref=ref,path=path,sha256=sha(data))
    (WORK/(name+'.java')).write_bytes(data)
paper = (WORK/'paper.java').read_text(encoding='utf-8')
live = (WORK/'live.java').read_text(encoding='utf-8')
assert 'btcMarketGuard.triggered()' in paper and 'btcMarketGuard.blocksEntry()' in live
assert 'min(maxInvestment)' in paper

# Compile only the candidate's actual pure cap helper, then demonstrate present-null handling.
java_dir = WORK/'helper-smoke'
java_dir.mkdir(exist_ok=True)
(java_dir/'PaperMaxInvestment.java').write_bytes(git('show','2aa2a498:'+source_paths['helper']))
smoke = '''import java.math.BigDecimal;
import java.util.*;
import com.cryptoautotrader.api.service.PaperMaxInvestment;
public class CapSmoke {
 public static void main(String[] args) {
  Map<String,Object> p = new HashMap<>();
  p.put("paperMaxInvestment", null);
  System.out.println("present-null cap="+PaperMaxInvestment.from(p));
  System.out.println("present-null capital100000="+PaperMaxInvestment.capitalViolation(new BigDecimal("100000"),p));
  p.put("paperMaxInvestment", 20000);
  System.out.println("valid cap="+PaperMaxInvestment.from(p));
  System.out.println("valid capital20200="+PaperMaxInvestment.capitalViolation(new BigDecimal("20200"),p));
  p.put("paperMaxInvestment", "abc");
  System.out.println("invalid rejected="+(PaperMaxInvestment.capitalViolation(new BigDecimal("100000"),p)!=null));
 }
}
'''
(java_dir/'CapSmoke.java').write_text(smoke,encoding='utf-8')
javac = Path('C:/Program Files/Java/jdk-17/bin/javac.exe')
java = javac.with_name('java.exe')
compile_run = subprocess.run([str(javac),'-encoding','UTF-8','-d',str(java_dir),str(java_dir/'PaperMaxInvestment.java'),str(java_dir/'CapSmoke.java')],capture_output=True)
(WORK/'helper-compile.txt').write_bytes(compile_run.stdout+compile_run.stderr)
assert compile_run.returncode==0,compile_run.stderr
smoke_run = subprocess.run([str(java),'-cp',str(java_dir),'CapSmoke'],capture_output=True)
assert smoke_run.returncode==0
(WORK/'helper-smoke.txt').write_bytes(smoke_run.stdout)
assert b'present-null cap=null' in smoke_run.stdout and b'present-null capital100000=null' in smoke_run.stdout

report = json.loads((B/'out/scorecard_v2.json').read_text(encoding='utf-8'))
result = dict(submitted_commit=HEAD, submitted_manifest=submitted, previous_manifest=previous, replay=replay,
              sl_cohorts=cohort_summary, clusters=cluster, submitted_junit=suites,
              mutation=dict(tests=int(mut['tests']),failures=int(mut['failures'])), sources=sources,
              helper_smoke=smoke_run.stdout.decode().splitlines(),
              bootstrap=report['weekly_block_bootstrap'], gate_counts=report['gate_vs_executed'],
              snapshot_times={k:v['snapshot_ts'] for k,v in [('positions',T['meta'][0]),('hold_bars',T4['meta4'][0]),('gate_holds',T5['meta5'][0])]},
              findings=['PAPER cap is supported, but decision/expiry/price-observation parity is not established',
                        'Present-null cap is treated as absent',
                        'Gate HOLD counts and executed closed positions are not matched order counts',
                        'A/B proposal lacks a single family-level estimand and terminal mark treatment; eight pair-wise selections are not one test',
                        'Six calendar weeks with no-trade weeks retained are a pilot, not eight independent replications'],
              runtime_changes=False, full_product_tests_rerun=False, main_source_modified=False)
(WORK/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(manifest='22/22 + 7/7',replay='4/4',sl=cohort_summary,clusters=cluster,
                      submitted_tests='22/22; mutation 1/6 fails',helper_smoke=smoke_run.stdout.decode().splitlines(),
                      snapshot_times=result['snapshot_times']),ensure_ascii=False,indent=2))
