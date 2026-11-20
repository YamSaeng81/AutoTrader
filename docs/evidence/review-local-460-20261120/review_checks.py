"""Review uncommitted 460 files and their fixed predecessors; no DB/network/test runner."""
import hashlib
import json
from pathlib import Path
import posixpath
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
EV = ROOT / 'docs/evidence'
HEAD = '2ff6982c634f4ad639d25b174bb990d3a4250b81'
checks, reviewed = [], {}

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *args], cwd=ROOT)

def sha(b):
    return hashlib.sha256(b).hexdigest()

def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})

check('Baseline HEAD and saved origin/main', all(git('rev-parse', x).decode().strip() == HEAD
      for x in ('HEAD', 'origin/main')), 'No new remote query or write')
for folder in ('dec-usage-resume-20261120', 'dec-read-approval-bundle-20261120',
               'dec-parallel-start-acceptance-20261120', 'review-2ff6982c'):
    base, bad, n = 'docs/evidence/' + folder + '/', [], 0
    for line in (ROOT / base / 'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip():
            continue
        m = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line)
        if not m:
            bad.append({'format': line})
            continue
        h, rel = m.groups()
        p = posixpath.normpath(base + rel)
        got = sha((ROOT / p).read_bytes())
        reviewed[p] = got
        n += 1
        if got != h:
            bad.append(p)
    check('Local manifest ' + folder, not bad, {'entries': n, 'mismatches': bad, 'committed': False})
base = 'docs/evidence/dec-collect-assembler-commit-20261120/'
bad, n = [], 0
for line in git('show', HEAD + ':' + base + 'SHA256SUMS').decode().splitlines():
    h, rel = re.fullmatch(r'([a-f0-9]{64})\s+\*?(.+)', line).groups()
    p = posixpath.normpath(base + rel)
    blob = git('show', HEAD + ':' + p)
    n += 1
    if sha(blob) != h or (ROOT / p).read_bytes() != blob:
        bad.append(p)
check('459 original 36 blobs unchanged', n == 36 and not bad, {'entries': n, 'bad': bad})
attr = EV / 'dec-collect-assembler-commit-20261120/.gitattributes'
check('459 preservation attribute added locally', attr.read_text(encoding='utf-8').strip() == '* -text',
      {'sha256': sha(attr.read_bytes()), 'committed': False})
registry = EV / 'dec-registry-p7v8-20261120/p7v8/reconcile.py'
code = registry.read_text(encoding='utf-8')
check('P7 accepted pin and registry-3 source', sha(registry.read_bytes()) ==
      '156c38289dba5836db3a61a3b005e9d3b873e8142b3d0146f693080f51f9effa'
      and "REGISTRY_V3 = 'registry-3'" in code and all(x in code for x in ('cash_base', 'holdings_base', 'capital_events')))
readme = (EV / 'dec-read-approval-bundle-20261120/README.md').read_text(encoding='utf-8')
accept = (EV / 'dec-parallel-start-acceptance-20261120/README.md').read_text(encoding='utf-8')
check('Stale registry fields identified in submitted draft', 'unallocated_krw_base' in readme
      and 'non_session_holdings' in readme and 'v2' in readme)
check('Unapproved no-fix start alternative identified', '\ubbf8\uc218\uc815 \uc2dc' in accept)
deploy = (EV / 'dec-deploy-draft-d81-20261119/README.md').read_text(encoding='utf-8')
check('Wrong rollback pin identified against accepted d81 draft', 'b5cd1d0' in accept and '52e12428' in accept
      and '837db515' in accept and '0e1d2a02 / backend 2c5d8427 / frontend 9d7f123a / DB V96' in deploy)
check('Existing fixed LIVE SID203 does not require recreation', '**\uae30\uc874 SID203**' in
      (EV / 'dec-ops-plan-20261119/README.md').read_text(encoding='utf-8'))
usage = (EV / 'dec-usage-resume-20261120/README.md').read_text(encoding='utf-8')
check('Usage observation explicitly remains unconfirmed', '\uc0ac\uc6a9\ub960 \ubbf8\ud655\uc778' in usage
      and 'd95c7a84' in usage)
out = {'review_basis': 'Uncommitted local files at recorded hashes; not a commit acceptance',
       'head': HEAD, 'passed': sum(c['ok'] for c in checks), 'failed': sum(not c['ok'] for c in checks),
       'reviewed_local_hashes': reviewed, 'checks': checks,
       'denial_evidence': 'Developer report only; rejected command, classifier reason, and live approval UI not supplied',
       'scheduler_evidence': 'Developer reports d95c7a84 for 2026-11-20 22:14 KST; reviewer cannot query that live Claude session'}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: out[k] for k in ('passed', 'failed', 'head')}))
raise SystemExit(0 if out['failed'] == 0 else 1)
