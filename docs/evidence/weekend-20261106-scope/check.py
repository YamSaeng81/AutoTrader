"""Validate the v1.133 scope additions. Does not execute application or server commands."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
snapshot = ROOT / 'core-engine/build/weekend-20261106-scope/before.json'
rows = json.loads(snapshot.read_text(encoding='utf-8-sig'))
before = {row['path'].replace('\\', '/'): row['content'].replace('\r\n', '\n') for row in rows}


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def blocks(text):
    return re.findall(r'^```bash\n(.*?)^```', text, re.M | re.S)


names = ['LIMITED_LIVE_TEST_PLAN.md', 'LIVE_READINESS.md', 'NOVEMBER_LIVE_UPGRADE.md',
         'OPS_DAILY_CHECK.md', 'PROGRESS.md']
protected = {}
for name, count in [('LIMITED_LIVE_TEST_PLAN.md', 149), ('OPS_DAILY_CHECK.md', 7)]:
    rel = 'docs/' + name
    old, new = blocks(before[rel]), blocks(read(rel))
    assert len(old) == len(new) == count
    assert old == new, name
    protected[name] = [{'index_zero_based': i, 'sha256': sha(block.encode())} for i, block in enumerate(new)]

pins = json.loads(read('docs/evidence/k26-result-review-1aaea01/n06-blocks.json'))
plan_blocks = blocks(read('docs/LIMITED_LIVE_TEST_PLAN.md'))
assert len(pins['blocks']) == 15
for row in pins['blocks']:
    assert sha(plan_blocks[row['index_zero_based']].encode()) == row['new_sha256']
assert read('docs/LIVE_ENTRY_PREREG.md') == before['docs/LIVE_ENTRY_PREREG.md']

for name in names:
    rel = 'docs/' + name
    text = read(rel)
    if name == 'PROGRESS.md':
        text, n = re.subn(r'^> \*\*마지막 갱신\*\*: 2026-11-06 \(328차,.*\n', '', text, flags=re.M)
        assert n == 1
        text = text.replace('> **이전 갱신**: 2026-11-06 (327차,', '> **마지막 갱신**: 2026-11-06 (327차,', 1)
    else:
        text, n = re.subn(r'^> \*\*(?:총괄)?v1\.133[^\n]*\n\n', '', text, flags=re.M)
        assert n == 1, name
        if name == 'LIMITED_LIVE_TEST_PLAN.md':
            text, n = re.subn(r'^\*\*101\. 총괄 주말.*?(?=^## 6\. 시작·중단·종료 조건)', '', text, flags=re.M | re.S)
            assert n == 1
    assert text == before[rel], name

prior = ROOT / 'docs/evidence/k26-result-review-1aaea01'
verified = []
for line in (prior / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
    digest, rel = line.split('  ', 1)
    assert sha((prior / rel).read_bytes()) == digest, rel
    verified.append(rel)
assert len(verified) == 13
missing = ['final-state.txt', 'local-copy.sha256', 'operator-confirm.txt', 'server-list-sha.txt', 'u1-present.txt']
for name in missing:
    assert (prior / 'preserved-out' / name).read_bytes() == (ROOT / 'docs/evidence/k26-result-1899b49/out' / name).read_bytes()

directive = read('docs/WEEKEND_20261106.md')
handoff = read('docs/WEEKEND_20261106_HANDOFF.md')
for token in ['1899b49f633f8aefdc66b15c42debdaba8b3852c',
              'sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1',
              'W1', 'W2', 'W3', 'W4', 'W5', 'W6', '11-10', '13:32~13:36',
              '107/107', '세션 중단', '추가 확인 없이', '서버 실행 금지']:
    assert token in directive, token
assert '개발 과제 실행 결과는 아직 없다' in handoff

head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip()
assert head == '1aaea01d2c53a5f77e4017f5f53dc1e844c482bb'
changed = subprocess.check_output(['git', 'diff', '--name-only', '-z'], cwd=ROOT).decode().strip('\0').split('\0')
assert sorted(changed) == sorted('docs/' + name for name in names), changed
diff = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
assert diff.returncode == 0, diff.stdout + diff.stderr

protected['source'] = 'working copy after v1.132, before v1.133 additions; not HEAD contents'
protected['prereg_sha256_lf'] = sha(before['docs/LIVE_ENTRY_PREREG.md'].encode())
(OUT / 'protected-blocks.json').write_text(json.dumps(protected, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
result = {'version': '1.133', 'section': 101, 'progress': 328, 'head': head,
          'plan_bash_unchanged': 149, 'ops_bash_unchanged': 7, 'F_hashes_unchanged': 15,
          'existing_v132_doc_content_preserved': 5, 'prereg_unchanged': True,
          'v132_evidence_manifest_verified': 13, 'missing_original_copies_match': 5,
          'relative_links_valid': True, 'git_diff_check': 0,
          'application_tests_or_server_execution': False, 'persistent_execution_installed': False,
          'scope_sha256': sha((ROOT / 'docs/WEEKEND_20261106.md').read_bytes()),
          'handoff_initial_sha256': sha((ROOT / 'docs/WEEKEND_20261106_HANDOFF.md').read_bytes())}
(OUT / 'check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
for rel in ['docs/WEEKEND_20261106.md', 'docs/WEEKEND_20261106_HANDOFF.md',
            'docs/evidence/weekend-20261106-scope/README.md']:
    for label, target in re.findall(r'\[([^\]]+)\]\(([^)]+)\)', read(rel)):
        if '://' in target or target.startswith('#'):
            continue
        target = target.split('#', 1)[0]
        assert (ROOT / rel).parent.joinpath(target).resolve().exists(), (rel, label, target)
files = sorted(p for p in OUT.rglob('*') if p.is_file() and p.name != 'SHA256SUMS')
(OUT / 'SHA256SUMS').write_text(''.join(sha(p.read_bytes()) + '  ' + p.relative_to(OUT).as_posix() + '\n' for p in files), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
