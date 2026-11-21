"""Read committed metadata-draft evidence, original manifests, and archive without extracting or rerunning."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = 'a569939b414bd4698fab678ee7a7effcb3b72a6f'
PREFIX = 'docs/evidence/parallel-monitor-draft-20261121/'


def blob(rel, rev=BASE):
    return subprocess.run(['git', 'show', rev + ':' + rel], cwd=ROOT, capture_output=True, check=True).stdout


if (HERE / 'checks.json').exists():
    raise SystemExit('New audit output required; do not overwrite')
checks, rows, counts = [], [], {}


def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})


for folder in ('parallel-monitor-draft-20261121', 'weekend-local-20261120', 'weekend-local-fix-20261121'):
    base = 'docs/evidence/' + folder + '/'
    start = len(rows)
    for line in blob(base + 'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not line.strip():
            continue
        h, p = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line).groups()
        rows.append((posixpath.normpath(base + p), h.lower()))
    counts[folder] = len(rows) - start
b = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
                   input=''.join(BASE + ':' + p + '\n' for p, _ in rows).encode(), capture_output=True, check=True).stdout
cur, bad, working_bad = 0, [], []
for path, h in rows:
    end = b.index(b'\n', cur)
    header = b[cur:end].split()
    if header[-1] == b'missing':
        bad.append(path)
        cur = end + 1
        continue
    n = int(header[-1])
    data = b[end + 1:end + 1 + n]
    cur = end + n + 2
    if hashlib.sha256(data).hexdigest() != h:
        bad.append(path)
    p = ROOT / path
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != h:
        working_bad.append(path)
check('Committed and working manifests', not bad and not working_bad,
      {'entries': len(rows), 'folders': counts, 'committed_mismatches': bad, 'working_mismatches': working_bad})

final = json.loads(blob(PREFIX + 'runs/pm-run2/results.json'))
check('Saved final 13 cases', len(final['cases']) == 13 and all(c['ok'] is True for c in final['cases']))
with tarfile.open(fileobj=io.BytesIO(blob(PREFIX + 'runs/pm-run1.tar.gz')), mode='r:gz') as t:
    ms = t.getmembers()
    unsafe = [m.name for m in ms if m.issym() or m.islnk() or m.name.startswith('/') or '..' in PurePosixPath(m.name).parts]
    sums = [m for m in ms if m.isfile() and m.name.endswith('/results.json')]
    old = json.load(t.extractfile(sums[0])) if len(sums) == 1 else {}
    check('Archived first 13 cases', not unsafe and len(old.get('cases', [])) == 13
          and all(c['ok'] is True for c in old.get('cases', [])),
          {'files': sum(m.isfile() for m in ms), 'unsafe_names': unsafe, 'extracted_to_disk': False})
by = {c['case']: c for c in final['cases']}
check('Same-count wrong ID detected', by['P2a_same_count_id_swapped']['w1'] == 'MISMATCH'
      and by['P2a_same_count_id_swapped']['w2'] == 'RISK_PRESENT')
check('Registered DYNAMIC PAPER caught by W1', by['P2b_registered_dynamic_is_paper']['w1'] == 'MISMATCH'
      and by['P2b_registered_dynamic_is_paper']['w2'] == 'NO_RISK_ITEMS')
check('Ownerless source not consumed', by['P3a_ownerless_order_position']['conv'] == 'COLLECTION_NOT_PASSED'
      and by['P3a_ownerless_order_position']['checks']['outputs_only_when_converted'])
check('Empty success differs from empty failure', by['P5e_explicit_success_empty']['conv'] == 'CONVERTED'
      and by['P5e_explicit_success_empty']['w1'] == 'MISMATCH'
      and by['P5b_failed_empty_output']['conv'] == 'INVALID')
check('Missing P0 not counted as zero', by['P5f_aux_p0_not_provided']['conv'] == 'COLLECTION_NOT_PASSED')

lock = json.loads(blob(PREFIX + 'source-lock.json'))
source_bad, verified = [], 0
for key, v in lock['sources'].items():
    if isinstance(v, dict) and 'path' in v and 'sha256' in v:
        rev = v.get('git_revision', BASE)
        if hashlib.sha256(blob(v['path'], rev)).hexdigest() != v['sha256']:
            source_bad.append(key)
        verified += 1
check('Locked sources', not source_bad, {'verified': verified, 'mismatches': source_bad})

sql = blob(PREFIX + 'sql/state.sql').decode('utf-8')
body = '\n'.join(l for l in sql.splitlines() if not l.lstrip().startswith('--'))
check('SQL read-only and protected schema excluded', 'REPEATABLE READ READ ONLY' in body and 'ROLLBACK;' in body
      and not re.search(r'\b(INSERT|UPDATE|DELETE|ALTER|DROP|CREATE)\b|paper_trading\.', body, re.I))
check('LIVE actual mode selected', "'LIVE', id, session_type" in body and "'DYNAMIC', id, trading_mode" in body)
v46 = blob('web-api/src/main/resources/db/migration/V46__add_session_type_to_live_trading_session.sql', 'd81c3f2f').decode('utf-8')
v12 = blob('web-api/src/main/resources/db/migration/V12__create_live_trading_session.sql', 'd81c3f2f').decode('utf-8')
check('LIVE mode and timestamp schema', 'session_type VARCHAR(10)' in v46 and re.search(r'created_at\s+TIMESTAMPTZ', v12))

# Resolve the known Korean filename from the fixed folder; do not synthesize shell commands.
paths = subprocess.run(['git', 'ls-tree', '-r', '--name-only', '-z', BASE, 'docs/evidence/d81d4r-20261120/d4/blocks-d81'],
                       cwd=ROOT, capture_output=True, check=True).stdout.decode('utf-8').split('\0')
hold_path = next(p for p in paths if p.endswith('/U' + chr(0xbcc0) + chr(0xc218) + '.sh'))
hold = blob(hold_path).decode('utf-8')
check('Existing P0 criterion located', 'Connection is not available, request timed out' in hold
      and 'holdobs()' in hold,
      {'path': hold_path, 'sha256': hashlib.sha256(blob(hold_path)).hexdigest(),
       'criterion': 'Connection is not available, request timed out'})
check('Registry version remains registry-3', lock.get('registry_version') == 'registry-3')

out = {'baseline': BASE, 'checks': checks, 'passed': sum(c['ok'] for c in checks), 'total': len(checks),
       'sql_executed': False, 'candidate_tests_rerun': False, 'production_access': False,
       'settings_content_read_or_changed': False}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'passed': out['passed'], 'total': out['total'], 'manifest_entries': len(rows),
                  'failed': [c['name'] for c in checks if not c['ok']]}))
raise SystemExit(any(not c['ok'] for c in checks))
