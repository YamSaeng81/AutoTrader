"""Validate v1.132 local pin changes and seal review files; no server or deployment execution."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '1aaea01d2c53a5f77e4017f5f53dc1e844c482bb'
NAMES = ['LIMITED_LIVE_TEST_PLAN.md', 'LIVE_READINESS.md', 'NOVEMBER_LIVE_UPGRADE.md',
         'OPS_DAILY_CHECK.md', 'PROGRESS.md']
NEW = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
IMAGE = 'sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1'
BASH = 'C:/Program Files/Git/bin/bash.exe'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def old(name):
    return git('show', REF + ':docs/' + name).decode().replace('\r\n', '\n')


def cur(name):
    return (ROOT / 'docs' / name).read_text(encoding='utf-8')


def blocks(text):
    return re.findall(r'^```bash\n(.*?)^```', text, re.M | re.S)


def sha(data):
    return hashlib.sha256(data).hexdigest()


before = blocks(old(NAMES[0]))
after = blocks(cur(NAMES[0]))
assert len(before) == len(after) == 149
assert [i for i, (x, y) in enumerate(zip(before, after)) if x != y] == [12, 13]
prior = json.loads((ROOT / 'docs/evidence/k25-result-review-b7f90ce/n06-blocks.json').read_text(encoding='utf-8'))
rows = []
for row in prior['blocks']:
    idx = row['index_zero_based']
    h0, h1 = sha(before[idx].encode()), sha(after[idx].encode())
    assert h0 == row['new_sha256'], idx
    rows.append({'block': row['block'], 'index_zero_based': idx,
                 'old_sha256': h0, 'new_sha256': h1, 'changed': h0 != h1})
assert len(rows) == 15 and sum(row['changed'] for row in rows) == 2
pins = {'source_before': REF, 'version': '1.132', 'operating_head': NEW,
        'operating_image': IMAGE, 'image_record': '~/maint-n20/image-new.txt', 'blocks': rows,
        'final_S': 'unfixed until 2026-11-30', 'N18_installation_check_in_F1': True}
(OUT / 'n06-blocks.json').write_text(json.dumps(pins, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
assert re.search(r'^NEW=' + NEW + r' ', after[12], re.M)
assert re.search(r'^IMGF1=' + IMAGE + r' ', after[13], re.M)
assert '$(cat ~/maint-n20/image-new.txt)' in after[13]
assert 'HEAD 가 1899b49 가 아니거나' in after[13]
assert '"$(head -1 "$M/git-f1.txt")" = "$NEW"' in after[13]
assert 'backend 이미지가 #26 배포 이미지(426a09a2…)' in after[13]
assert all(token in after[13] for token in ['INSTALLED_SHA', 'ff0a5d7d', '653b22ba'])
scratch = ROOT / 'core-engine/build/k26-result-review-1aaea01'
for idx, prefixes in [(12, ['NEW=']),
                      (13, ['[ "$(head -1 "$M/git-f1.txt")', 'IMGF1=', '[ "$(docker inspect --format'])]:
    x, y = before[idx].splitlines(), after[idx].splitlines()
    assert len(x) == len(y)
    changed = [(a, b) for a, b in zip(x, y) if a != b]
    assert len(changed) == len(prefixes)
    assert all(any(a.startswith(p) and b.startswith(p) for p in prefixes) for a, b in changed)
    p = scratch / ('F-check-' + str(idx) + '.sh')
    p.write_text(after[idx], encoding='utf-8', newline='\n')
    result = subprocess.run([BASH, '--noprofile', '--norc', '-n', str(p)], capture_output=True)
    assert result.returncode == 0, result.stderr

assert blocks(cur('OPS_DAILY_CHECK.md')) == blocks(old('OPS_DAILY_CHECK.md'))
assert len(blocks(cur('OPS_DAILY_CHECK.md'))) == 7
assert cur('LIVE_ENTRY_PREREG.md') == old('LIVE_ENTRY_PREREG.md')
plan = cur(NAMES[0])
assert plan.splitlines()[2].startswith('> **v1.132 ')
assert plan.count('**100.') == 1 and plan.index('**100.') < plan.index('## 6. 시작·중단·종료 조건')
assert '`1899b49f…` 다음 `(끝)`' in plan
assert '> **마지막 갱신**: 2026-11-06 (327차,' in cur('PROGRESS.md')
assert '> **이전 갱신**: 2026-11-06 (326차,' in cur('PROGRESS.md')


def row(text, key):
    return next(line for line in text.splitlines() if line.startswith('| ' + key + ' |'))


nov, nov_old = cur('NOVEMBER_LIVE_UPGRADE.md'), old('NOVEMBER_LIVE_UPGRADE.md')
assert NEW in row(nov, '운영 backend') and IMAGE in row(nov, '운영 이미지')
assert '| N20 | 완료 · 운영 반영 확인 |' in nov
for key in ['N06', 'N20']:
    assert row(nov, key).count('|') == row(nov_old, key).count('|')
for key in ['N15', 'N16', 'N17', 'N18', 'N19', 'DB', '정기 점검', '장부', '시험 세션', '시작·종료']:
    assert row(nov, key) == row(nov_old, key), key
changed_paths = git('diff', '--name-only', '-z').decode().strip('\0').split('\0')
assert sorted(changed_paths) == sorted('docs/' + name for name in NAMES), changed_paths
r = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
assert r.returncode == 0, r.stdout + r.stderr

review = json.loads((OUT / 'review.json').read_text(encoding='utf-8'))
assert review['committed_blob_matches']['k26-result-1899b49'] == 102
assert len(review['missing_in_result_commit']) == 5
for relative in review['missing_in_result_commit']:
    p = OUT / 'preserved-out' / Path(relative).name
    assert p.read_bytes() == (ROOT / 'docs/evidence/k26-result-1899b49' / relative).read_bytes()
    r = subprocess.run(['git', 'check-ignore', '-q', str(p)], cwd=ROOT, capture_output=True)
    assert r.returncode == 1, (p, r.returncode)

result = {'version': '1.132', 'section': 100, 'progress': 327, 'plan_blocks': 149,
          'changed_indices': [12, 13], 'F_changed': 2, 'F_unchanged': 13,
          'other_plan_blocks_unchanged': 147, 'OPS_blocks_unchanged': 7, 'F_syntax': 2,
          'operating_head': NEW, 'operating_image': IMAGE, 'image_record': '~/maint-n20/image-new.txt',
          'N20_runtime_accepted': True, 'natural_trigger': 'unobserved', 'final_S': 'unfixed',
          'missing_original_out_files_preserved': 5, 'supplement_not_ignored': True, 'git_diff_check': 0}
(OUT / 'doc-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
files = sorted((p for p in OUT.rglob('*') if p.is_file() and p.name != 'SHA256SUMS'),
               key=lambda p: p.relative_to(OUT).as_posix())
manifest = [sha(p.read_bytes()) + '  ' + p.relative_to(OUT).as_posix() for p in files]
(OUT / 'SHA256SUMS').write_text('\n'.join(manifest) + '\n', encoding='utf-8')
print(json.dumps({'F_changed': 2, 'F_unchanged': 13, 'other_plan_unchanged': 147,
                  'OPS_unchanged': 7, 'preserved_missing': 5, 'review_files': len(files), 'diff_check': 0}))
