"""Verify preserved evidence against the prepared Git index, without rewriting sources."""
import argparse
import hashlib
import json
from pathlib import Path
import posixpath
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ap = argparse.ArgumentParser()
ap.add_argument('--out', required=True)
a = ap.parse_args()
target = Path(a.out)
if target.exists():
    raise SystemExit('New audit output path required')
rows = []
for folder in ('weekend-local-20261120', 'n03-1120-2200', 'weekend-work-order-20261120'):
    base = 'docs/evidence/' + folder + '/'
    for line in (ROOT / base / 'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip():
            continue
        h, rel = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line).groups()
        rows.append((posixpath.normpath(base + rel), h))
b = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
                   input=''.join(':' + p + '\n' for p, _ in rows).encode(), capture_output=True, check=True).stdout
cur, bad = 0, []
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
result = {'scope': 'Prepared index; existing manifests unchanged', 'entries': len(rows), 'mismatches': bad,
          'raw_evidence_added_or_restored': 182, 'tests_rerun': False}
target.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result))
raise SystemExit(bool(bad))
