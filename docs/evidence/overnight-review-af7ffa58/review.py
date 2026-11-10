"""Read-only audit of submitted files; local synthetic checks only. No network or DB."""
import copy
import hashlib
import importlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
OUT = ROOT / 'core-engine/build/overnight-review-af7ffa58'
OUT.mkdir(parents=True, exist_ok=True)

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, stderr=subprocess.DEVNULL)

def sha(b):
    return hashlib.sha256(b).hexdigest()

report = {'head': git('rev-parse','HEAD').decode().strip(), 'candidate': git('rev-parse','af7ffa58').decode().strip(), 'checksums': {}, 'synthetic': []}
folders = ['same-path-20261110','dynamic-diff-20261110','dynamic-restore-20261110',
           'exit-ab-protocol-20261110','shared-loop-20261110','scorecard-v2-memo-20261110',
           'overnight-gaps-20261110','sma20p-d4-af7ffa58','n03-1110-2200','morning-1111']
for folder in folders:
    base = ROOT/'docs/evidence'/folder
    results = []
    for line in (base/'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        m = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)',line.strip())
        if not m: continue
        expected, name = m.groups()
        path = base/name
        local = sha(path.read_bytes())
        rel = path.relative_to(ROOT).as_posix()
        blob = None
        if folder != 'morning-1111': blob = sha(git('show', 'HEAD:'+rel))
        results.append({'file': name, 'local_ok': local==expected.lower(), 'blob_ok': blob==expected.lower() if blob else None})
    report['checksums'][folder] = {'count':len(results), 'local_bad':[r for r in results if not r['local_ok']], 'blob_bad':[r for r in results if r['blob_ok'] is False], 'committed':folder!='morning-1111'}
report['candidate_paths'] = git('diff','--name-only','b5cd1d0','af7ffa58').decode().splitlines()
report['product_paths'] = [p for p in report['candidate_paths'] if '/src/main/' in p]
report['test_paths'] = [p for p in report['candidate_paths'] if '/src/test/' in p]
report['blocks'] = {}
for f in ['docs/LIMITED_LIVE_TEST_PLAN.md','docs/OPS_DAILY_CHECK.md']:
    raw = (ROOT/f).read_bytes()
    blocks = re.findall(rb'```bash[^\r\n]*\r?\n(.*?)```',raw,re.S)
    report['blocks'][f] = [sha(x.replace(b'\r\n',b'\n')) for x in blocks]
sys.path.insert(0,str(ROOT/'docs/evidence/exit-ab-protocol-20261110/tool'))
ag = importlib.import_module('ab_aggregate')
fixture = importlib.import_module('test_ab_aggregate')
for name, mutation in [
    ('control', lambda rows: None),
    ('all_four_pairs_same_strategy_coin',lambda rows:[r.update(strategy='COMPOSITE_MEANREV_BB',coin='KRW-BTC') for r in rows]),
    ('all_capital_200000',lambda rows:[r.update(initial_capital='200000') for r in rows]),
    ('all_timeframe_H1',lambda rows:[r.update(timeframe='H1') for r in rows]),
    ('different_sha_between_pairs',lambda rows:[r.update(code_sha='different') for r in rows if r['pair_id']=='P4']),
]:
    rows = fixture.arms()
    mutation(rows)
    try:
        pairs=ag.check_config(rows)
        result='ACCEPTED'
    except ag.ConfigRejected:
        result='REJECTED'
    report['synthetic'].append({'case':name,'result':result})
(OUT/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({**report,'blocks':{k:len(v) for k,v in report['blocks'].items()}},ensure_ascii=False,indent=2))
