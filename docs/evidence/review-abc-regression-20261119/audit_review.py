"""Read saved regression artifacts and fixed Git blobs; do not run product tests."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUN = ROOT / 'docs/evidence/dec-integ-abc-20261119/full-60df3437'
checks, observations = [], {}

def check(name, passed, actual=None):
    checks.append({'check': name, 'pass': bool(passed), 'actual': actual})

def blob(sha, path):
    return subprocess.check_output(['git', 'show', sha + ':' + path], cwd=ROOT)

def inventory():
    return {p.relative_to(RUN).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(RUN.rglob('*')) if p.is_file()}

before = inventory()
expected = {'core-engine': (47, 298, 0, 0, 7), 'exchange-adapter': (2, 11, 0, 0, 0),
            'strategy-lib': (17, 113, 0, 0, 0), 'web-api': (222, 1444, 1, 0, 86)}
totals = [0, 0, 0, 0]
failures = []
copy_record = (RUN / 'xml-copy-check.txt').read_text(encoding='utf-8')
for module, exp in expected.items():
    files = sorted((RUN / 'xml' / module).glob('TEST-*.xml'))
    sums = [0, 0, 0, 0]
    for path in files:
        node = ET.parse(path).getroot()
        sums = [a + int(node.get(k, '0')) for a, k in zip(sums, ('tests', 'failures', 'errors', 'skipped'))]
        for case in node.iter('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                failures.append({'file': path.relative_to(RUN).as_posix(), 'class': case.get('classname'),
                                 'test': case.get('name'), 'message': failure.get('message', '')})
    actual = (len(files), *sums)
    check(module + ' XML counts', actual == exp, actual)
    check(module + ' saved copy record',
          f'{module} generated={exp[0]} kept={exp[0]} hash_mismatch=0' in copy_record)
    totals = [a + b for a, b in zip(totals, sums)]
check('aggregate', totals == [1866, 1, 0, 93], totals)
check('single failed class', len(failures) == 1 and failures[0]['class'].endswith('DailySma20LiveOrderPathTest'))
check('failure identifies unknown-cost entry rejection', len(failures) == 1 and
      all(s in failures[0]['message'] for s in ('연속 손실 판정 불가', 'posId=226', '확정 비용', 'Expected size: 1 but was: 0')))
time = (RUN / 'time.txt').read_text(encoding='utf-8')
check('fixed SHA and exit', 'HEAD 60df343750c330e90e6023ebbb115ce1ad140384 dirty=0' in time and
      'rc=1 end 2026-11-19 14:59:52' in time and 'start 2026-11-19 14:38:59' in time)
check('saved working tree status is empty', not (RUN / 'git-status.txt').read_bytes().strip())
guard = (RUN / 'xml/web-api/NET-GUARD-summary.txt').read_text(encoding='utf-8')
check('web-api guard installed with zero blocked attempts',
      'installed_during_run=true' in guard and 'blocked_external_attempts=0' in guard)
testpath = 'web-api/src/test/java/com/cryptoautotrader/api/service/DailySma20LiveOrderPathTest.java'
test = blob('60df3437', testpath).decode('utf-8')
method = re.search(r'void reentersAfterClose\(\) \{(.*?)\n    \}', test, re.S).group(1)
check('reentry fixture closes directly without order source',
      'old.setStatus("CLOSED")' in method and 'old.setClosedAt(' in method and
      'positionRepository.save(old)' in method and 'orderRepository' not in method and 'setRealizedPnl' not in method)
helper = re.search(r'private PositionEntity position\(.*?\n    \}', test, re.S).group(0)
check('position helper writes only position', 'positionRepository.save(' in helper and 'orderRepository' not in helper)
check('fixture unchanged on A, C and integration',
      blob('aff357e2', testpath) == blob('b408a223', testpath) == blob('60df3437', testpath))
risk = blob('60df3437', 'web-api/src/main/java/com/cryptoautotrader/api/service/RiskManagementService.java').decode('utf-8')
check('cost branch needs confirmed buy and sell source',
      'buyN == 0 || buyUnknown > 0 || sellMissing > 0 || sellNet == null || buyCost == null' in risk and
      'noTrade ? ClosedPnl.known(BigDecimal.ZERO) : new ClosedPnl(null, true, pos.getId())' in risk)
check('saved artifacts stable during audit', inventory() == before)
observations.update({'totals': {'tests': 1866, 'passed': 1772, 'failed': 1, 'skipped': 93},
                     'failures': failures, 'guard_header': guard.splitlines()[:7],
                     'artifact_count': len(before),
                     'limits': ['No Java/PG/build/network operation executed.',
                                'Generated-to-preserved XML equality uses the saved copy record; original build folders were not rechecked.',
                                'A-only/C-only executions, old-version query and BUY-summary fail-first runs were reported in progress and are not judged here.',
                                'The final development submission manifest and committed blobs are not available yet.']})
for name, value in [('checks.json', {'passed': sum(x['pass'] for x in checks),
                                    'failed': sum(not x['pass'] for x in checks), 'checks': checks}),
                    ('observations.json', observations)]:
    (HERE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(HERE / 'regression-files.sha256').write_text(''.join(f'{digest}  {name}\n' for name, digest in before.items()), encoding='utf-8')
print(json.dumps({'passed': sum(x['pass'] for x in checks), 'failed': sum(not x['pass'] for x in checks),
                  'artifacts': len(before), 'totals': observations['totals']}, ensure_ascii=False))
raise SystemExit(0 if all(x['pass'] for x in checks) else 1)
