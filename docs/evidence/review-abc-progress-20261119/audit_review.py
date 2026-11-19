"""Bounded interim review. Reads Git blobs and saved results only; runs no app/tests."""
from pathlib import Path
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
FOLLOW = ROOT / 'docs/evidence/dec-costbasis-followup-20261119'
INTEG = ROOT / 'docs/evidence/dec-integ-abc-20261119'
checks = []
facts = {}

def git(*args, data=None):
    return subprocess.check_output(['git', *args], input=data, cwd=ROOT)

def check(name, passed, actual=None):
    checks.append({'check': name, 'pass': bool(passed), 'actual': actual})

def blob(sha, path):
    return git('show', sha + ':' + path)

def patch_id(sha):
    patch = git('show', '--pretty=format:', '--no-ext-diff', sha)
    return git('patch-id', '--stable', data=patch).decode().split()[0]

def saved_json(path, total, passed):
    raw = path.read_bytes()
    obj = json.loads(raw)
    rows = obj['checks']
    good = sum(x['pass'] is True for x in rows)
    check(str(path.relative_to(ROOT)), (len(rows), good) == (total, passed),
          {'total': len(rows), 'passed': good, 'sha256': hashlib.sha256(raw).hexdigest()})
    return obj

def xml_summary(folder, expected):
    files = sorted(folder.glob('TEST-*.xml'))
    result = {'files': len(files), 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for path in files:
        node = ET.parse(path).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            result[key] += int(node.get(key, '0'))
    check(str(folder.relative_to(ROOT)) + ' XML summary', result == expected, result)

facts['anchors'] = {name: git('rev-parse', name).decode().strip()
                    for name in ('HEAD', 'origin/main', '32d5dafd', 'b408a223', 'aff357e2', '60df3437')}
saved_json(FOLLOW / 'base-fail/cost_basis_followup_checks.json', 47, 35)
saved_json(FOLLOW / 'fix1/cost_basis_followup_checks.json', 47, 47)
saved_json(FOLLOW / 'affected-pg/cost_basis_checks.json', 48, 48)
saved_json(FOLLOW / 'affected-pg/cost_basis_boundary_checks.json', 59, 59)
cross = saved_json(INTEG / 'cross-r2/abc_cross_checks.json', 19, 19)
facts['buy_alert_observation'] = cross.get('obs_real_buy_fill_alert_events')
check('saved cross observation: BUY alert events zero', facts['buy_alert_observation'] == 0)
xml_summary(FOLLOW / 'affected-h2', {'files': 39, 'tests': 257, 'failures': 0, 'errors': 0, 'skipped': 0})
xml_summary(FOLLOW / 'affected-pg', {'files': 6, 'tests': 37, 'failures': 0, 'errors': 0, 'skipped': 0})
xml_summary(INTEG / 'cross-r2', {'files': 1, 'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0})

changes = git('diff', '--name-only', '32d5dafd', 'b408a223').decode().splitlines()
facts['followup_paths'] = changes
check('follow-up: five product Java and two test Java files, no other inputs',
      len(changes) == 7 and sum('/src/main/java/' in x for x in changes) == 5
      and sum('/src/test/java/' in x for x in changes) == 2)
check('saved product diff equals fixed Git diff',
      git('diff', '32d5dafd', 'b408a223', '--', 'web-api/src/main') == (FOLLOW / 'product.diff').read_bytes())
repo = 'web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java'
repo_text = blob('b408a223', repo).decode()
check('N1 explicitly checks BUY quantity is non-NULL and zero',
      'nb.filled_quantity IS NOT NULL AND nb.filled_quantity = 0' in repo_text
      and 'COALESCE(nb.filled_quantity, 0) = 0' not in repo_text)
check('pre-deploy extraction source is fixed b408a223',
      blob('b408a223', repo) == (FOLLOW / 'predeploy-v3/OrderRepository.b408a223.java').read_bytes())

rows = [line.split() for line in (INTEG / 'integration-commits.txt').read_text().splitlines() if line.strip()]
check('integration map has 15 rows', len(rows) == 15)
originals, mapped = [], []
for original, integrated, same, recorded in rows:
    originals.append(original)
    mapped.append(integrated)
    original_id, integrated_id = patch_id(original), patch_id(integrated)
    check('patch-id ' + original[:8] + ' -> ' + integrated[:8],
          same == 'same' and original_id == integrated_id == recorded)
check('mapping uses 15 unique source and destination commits', len(set(originals)) == len(set(mapped)) == 15)
chain = git('rev-list', '--reverse', 'aff357e2..60df3437').decode().splitlines()
check('integration chain equals 15 mapped commits plus cross-test commit',
      chain[:-1] == mapped and len(chain) == 16 and chain[-1] == facts['anchors']['60df3437'])
src_paths = git('diff', '--name-only', '60df3437^', '60df3437').decode().splitlines()
check('60df3437 adds cross-test only', src_paths == ['web-api/src/test/java/com/cryptoautotrader/api/service/AbcCrossPgTest.java'])

gate = FOLLOW / 'rollback-gate-v4-draft/rehearsal'
old = (gate / 'gate4_rej-v3.txt').read_text(encoding='utf-8')
new = (gate / 'gate4_rej-v4.txt').read_text(encoding='utf-8')
check('saved old gate permits marker row; new gate refuses', 'VERDICT ROLLBACK_GATE_OK' in old and 'VERDICT STOP_INCOMPATIBLE' in new)
settle = 'web-api/src/main/java/com/cryptoautotrader/api/service/OrderSettlementService.java'
settle_text = blob('60df3437', settle).decode()
check('atomic settlement source unchanged from deployed 0e1d2a02', blob('60df3437', settle) == blob('0e1d2a02', settle))
check('atomic BUY settlement has no trade buffer invocation',
      'applyBuyFillAccounting' in settle_text and 'bufferTradeEvent' not in settle_text)
facts['limits'] = [
    'Interim files are uncommitted and no manifest was available; final committed-byte verification is pending.',
    'Saved XML/JSON were parsed; PostgreSQL, Spring, full regression, builds and production were not run by reviewer.',
    'Full regression on 60df3437 was reported running; no completion or pass is inferred.',
    'Pre-deploy v3 evidence includes syntax and one widened-window NULL probe, not full product-value parity.',
    'BUY buffer omission is supported by saved cross observation and unchanged settlement source; historic production events and V83 introduction were not independently verified.'
]
(HERE / 'observations.json').write_text(json.dumps(facts, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
result = {'passed': sum(x['pass'] for x in checks), 'failed': sum(not x['pass'] for x in checks), 'checks': checks}
(HERE / 'checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: result[k] for k in ('passed', 'failed')}))
raise SystemExit(1 if result['failed'] else 0)
