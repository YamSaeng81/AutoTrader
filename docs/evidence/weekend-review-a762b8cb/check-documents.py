"""v1.135 document/evidence final check; no historical checker or app execution."""
import difflib
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
before = json.loads((ROOT / 'core-engine/build/weekend-review-a762b8cb/before-docs.json').read_text(encoding='utf-8'))


def sha(b):
    return hashlib.sha256(b).hexdigest()


def bash_hashes(text):
    return [sha(m.encode()) for m in re.findall(r'^```bash\r?\n(.*?)^```\s*$', text, re.M | re.S)]


changed = subprocess.check_output(['git', 'diff', '--name-only', '-z'], cwd=ROOT).decode().split('\0')
assert sorted(p for p in changed if p) == sorted(before)
preserved = []
for path, old in before.items():
    text = (ROOT / path).read_text(encoding='utf-8')
    if path == 'docs/PROGRESS.md':
        old = old.replace('> **마지막 갱신**:', '> **이전 갱신**:', 1)
    matches = difflib.SequenceMatcher(a=old.splitlines(keepends=True), b=text.splitlines(keepends=True), autojunk=False)
    assert not [op for op in matches.get_opcodes() if op[0] in ('replace', 'delete')], path
    preserved.append(path)
plan = (ROOT / 'docs/LIMITED_LIVE_TEST_PLAN.md').read_text(encoding='utf-8')
ops = (ROOT / 'docs/OPS_DAILY_CHECK.md').read_text(encoding='utf-8')
assert bash_hashes(plan) == bash_hashes(before['docs/LIMITED_LIVE_TEST_PLAN.md'])
assert bash_hashes(ops) == bash_hashes(before['docs/OPS_DAILY_CHECK.md'])
assert len(bash_hashes(plan)) == 149 and len(bash_hashes(ops)) == 7
approved = json.loads((ROOT / 'docs/evidence/k26-result-review-1aaea01/n06-blocks.json').read_text(encoding='utf-8'))
assert all(bash_hashes(plan)[item['index_zero_based']] == item['new_sha256'] for item in approved['blocks'])
assert plan.count('**103. 총괄 주말 진행 검토') == 1
assert '340차, 총괄v1.135' in (ROOT / 'docs/PROGRESS.md').read_text(encoding='utf-8')
prereg = 'docs/LIVE_ENTRY_PREREG.md'
assert (ROOT / prereg).read_bytes() == subprocess.check_output(['git', 'show', f'HEAD:{prereg}'], cwd=ROOT)
check = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
assert check.returncode == 0, check.stdout
result = {'version': '1.135', 'section': 103, 'progress': 340,
    'previous_document_content_preserved': preserved,
    'unchanged_plan_bash': 149, 'unchanged_ops_bash': 7, 'approved_f15_hashes_unchanged': 15,
    'prereg_unchanged': True, 'git_diff_check': 0, 'product_changes_by_reviewer': False,
    'server_operations_by_reviewer': False, 'new_deployment_authorized': False,
    'committed_by_reviewer': False}
(OUT / 'doc-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
for target in re.findall(r'\]\(([^)]+)\)', (OUT / 'README.md').read_text(encoding='utf-8')):
    assert (OUT / target).exists(), target
files = sorted(p for p in OUT.rglob('*') if p.is_file() and p.name != 'SHA256SUMS')
assert len(files) == 12, [p.relative_to(OUT).as_posix() for p in files]
manifest = ''.join(f'{sha(p.read_bytes())} *{p.relative_to(OUT).as_posix()}\n' for p in files)
(OUT / 'SHA256SUMS').write_text(manifest, encoding='utf-8', newline='\n')
for p in files:
    ignored = subprocess.run(['git', 'check-ignore', '--quiet', str(p.relative_to(ROOT))], cwd=ROOT, capture_output=True)
    assert ignored.returncode == 1, p
print(json.dumps(result, ensure_ascii=False, indent=2))
print(f'New review SHA256SUMS: {len(files)}/{len(files)} bytes match; all paths unignored.')
