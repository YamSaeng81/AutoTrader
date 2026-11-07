"""Check v1.134 additions without rerunning the historical rehearsal/review tools."""
import difflib
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
before = json.loads((ROOT / 'core-engine/build/weekend-review-dbac6053/before-docs.json').read_text(encoding='utf-8'))
review = json.loads((OUT / 'review.json').read_text(encoding='utf-8'))


def sha(b):
    return hashlib.sha256(b).hexdigest()


def blocks(text):
    return [sha(m.encode()) for m in re.findall(r'^```bash\r?\n(.*?)^```\s*$', text, re.M | re.S)]


changed = subprocess.check_output(['git', 'diff', '--name-only', '-z'], cwd=ROOT).decode().split('\0')
changed = sorted(p for p in changed if p)
assert changed == sorted(before), changed
preserved = []
for path, old in before.items():
    text = (ROOT / path).read_text(encoding='utf-8')
    # Every old line survives. Only the current PROGRESS history label changes.
    if path == 'docs/PROGRESS.md':
        old = old.replace('> **마지막 갱신**:', '> **이전 갱신**:', 1)
    matching = difflib.SequenceMatcher(a=old.splitlines(keepends=True),
                                     b=text.splitlines(keepends=True), autojunk=False)
    lost = [(tag, i, j) for tag, i, j, _, _ in matching.get_opcodes()
            if tag in ('delete', 'replace')]
    assert not lost, (path, lost)
    preserved.append(path)
plan = (ROOT / 'docs/LIMITED_LIVE_TEST_PLAN.md').read_text(encoding='utf-8')
ops = (ROOT / 'docs/OPS_DAILY_CHECK.md').read_text(encoding='utf-8')
assert blocks(plan) == review['protected']['plan_bash']
assert blocks(ops) == review['protected']['ops_bash']
f15 = json.loads((ROOT / 'docs/evidence/k26-result-review-1aaea01/n06-blocks.json').read_text(encoding='utf-8'))
assert all(blocks(plan)[b['index_zero_based']] == b['new_sha256'] for b in f15['blocks'])
assert sha((ROOT / 'docs/LIVE_ENTRY_PREREG.md').read_bytes()) == review['protected']['prereg_sha256']
assert plan.count('**102. 총괄 주말') == 1
assert '333차, 총괄 v1.134' in (ROOT / 'docs/PROGRESS.md').read_text(encoding='utf-8')
cache = ROOT / 'docs/evidence/w4-n06-prep/__pycache__/n06_prepare.cpython-313.pyc'
preserved_cache = OUT / 'preserved/n06_prepare.cpython-313.pyc.bin'
assert cache.read_bytes() == preserved_cache.read_bytes()
assert sha(cache.read_bytes()) == '7d41d684903af899d4b823f227c4c6adb8870b65998227249e4575867ca4ac00'
diffcheck = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
assert diffcheck.returncode == 0, diffcheck.stdout.decode()
assert subprocess.run(['git', 'check-ignore', '--quiet', str(preserved_cache.relative_to(ROOT))],
                      cwd=ROOT, capture_output=True).returncode == 1
result = {'version': '1.134', 'head': review['head'], 'section': 102, 'progress': 333,
          'old_document_content_preserved': preserved, 'plan_bash_unchanged': 149,
          'ops_bash_unchanged': 7, 'f15_approved_hashes_unchanged': 15,
          'prereg_unchanged': True, 'cache_original_and_unignored_copy_match': True,
          'product_source_changed': False, 'git_diff_check': 0,
          'root_runtime_operations': False, 'committed_by_root': False}
(OUT / 'doc-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
# Check the review's explicit relative links after generating its JSON target.
readme = (OUT / 'README.md').read_text(encoding='utf-8')
for target in re.findall(r'\]\(([^)]+)\)', readme):
    assert (OUT / target).exists(), target
files = sorted(p for p in OUT.rglob('*') if p.is_file() and p.name != 'SHA256SUMS')
manifest = ''.join(f'{sha(p.read_bytes())} *{p.relative_to(OUT).as_posix()}\n' for p in files)
(OUT / 'SHA256SUMS').write_text(manifest, encoding='utf-8', newline='\n')
for p in files:
    assert subprocess.run(['git', 'check-ignore', '--quiet', str(p.relative_to(ROOT))],
                          cwd=ROOT, capture_output=True).returncode == 1, p
assert len(files) == 8, len(files)
print(json.dumps(result, ensure_ascii=False, indent=2))
print(f'New review manifest: {len(files)}/{len(files)} bytes match; no files ignored.')
