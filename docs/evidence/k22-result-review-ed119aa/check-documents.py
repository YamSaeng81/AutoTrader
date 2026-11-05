from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = 'ed119aaa6089f363a091d2a7caba115dfc69cbf6'
PLAN = 'docs/LIMITED_LIVE_TEST_PLAN.md'
OPS = 'docs/OPS_DAILY_CHECK.md'
HEAD = 'fe01207f3f60e97e308925157f84a1bb2da04388'
IMAGE = 'sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7'


def sha(s):
    return hashlib.sha256(s.encode('utf-8')).hexdigest()


def git_text(path):
    return subprocess.check_output(['git', 'show', f'{REF}:{path}'], cwd=ROOT).decode('utf-8')


def blocks(s):
    return re.findall(r'^```bash\n(.*?)^```', s.replace('\r\n', '\n'), re.M | re.S)


old = blocks(git_text(PLAN))
new_text = (ROOT / PLAN).read_text(encoding='utf-8')
new = blocks(new_text)
assert len(old) == len(new) == 101
names = json.loads((ROOT / 'docs/evidence/k21-result-review-4d8bd2f/n06-blocks.json').read_text(encoding='utf-8'))['blocks']
rows = []
indices = []
for record in names:
    candidates = [i for i, block in enumerate(old) if sha(block) == record['new_sha256']]
    assert len(candidates) == 1, (record['block'], candidates)
    i = candidates[0]
    indices.append(i)
    rows.append({'block': record['block'], 'old_sha256': sha(old[i]), 'new_sha256': sha(new[i]), 'changed': old[i] != new[i]})
assert len(rows) == 15
assert [r['block'] for r in rows if r['changed']] == ['F 변수', 'F1 첫 블록']
assert [i for i, (a, b) in enumerate(zip(old, new)) if a != b] == indices[:2]
assert f'NEW={HEAD}' in new[indices[0]]
assert f'IMGF1={IMAGE}' in new[indices[1]]
assert 'cat ~/maint-n13/image-new.txt' in new[indices[1]]
ops_before = blocks(git_text(OPS))
ops_after = blocks((ROOT / OPS).read_text(encoding='utf-8'))
assert len(ops_before) == len(ops_after) == 7 and ops_before == ops_after
syntax = []
for i in indices[:2]:
    result = subprocess.run([r'C:\Program Files\Git\bin\bash.exe', '--noprofile', '--norc', '-n'],
                            input=new[i].encode('utf-8'), cwd=ROOT, capture_output=True)
    assert result.returncode == 0, result.stderr.decode('utf-8', errors='replace')
    syntax.append({'block_index_zero_based': i, 'sha256': sha(new[i]), 'bash_n_exit': result.returncode})
diff = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
assert diff.returncode == 0, diff.stdout.decode('utf-8', errors='replace')
paths = subprocess.check_output(['git', 'diff', '--name-only'], cwd=ROOT).decode('utf-8').splitlines()
assert sorted(paths) == sorted(['docs/LIMITED_LIVE_TEST_PLAN.md', 'docs/LIVE_READINESS.md',
                                'docs/NOVEMBER_LIVE_UPGRADE.md', 'docs/OPS_DAILY_CHECK.md', 'docs/PROGRESS.md'])
assert '**72.' in new_text and 'v1.104' in new_text.splitlines()[2]
out = {'source_before': REF, 'new_head': HEAD, 'new_image': IMAGE, 'image_record': '~/maint-n13/image-new.txt',
       'blocks': rows, 'final_S': 'unfixed until 2026-11-30'}
(OUT / 'n06-blocks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
doc_check = {'plan_bash_total': 101, 'changed_blocks': [r['block'] for r in rows if r['changed']],
             'other_plan_bash_unchanged': 99, 'other_fixed_F_blocks_unchanged': 13,
             'ops_bash_total': 7, 'ops_bash_unchanged': True, 'bash_n_changed_only': syntax,
             'git_diff_check': 0, 'tracked_files_changed': paths,
             'no_commit_or_server_or_app_test_execution': True}
(OUT / 'doc-check.json').write_text(json.dumps(doc_check, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'fixed_F': 15, 'changed': 2, 'unchanged': 13, 'other_bash_unchanged': 99,
                  'OPS_unchanged': 7, 'syntax_checks': 2, 'diff_check': 0,
                  'new_F_hashes': [r['new_sha256'] for r in rows[:2]]}, ensure_ascii=False))
