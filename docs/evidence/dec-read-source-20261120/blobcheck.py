import hashlib, posixpath, subprocess, sys
ROOT = sys.argv[1]
sys.stdout.reconfigure(encoding='utf-8')
def git(*a): return subprocess.check_output(['git', '-c', 'core.quotepath=false', *a], cwd=ROOT)
folders = ['dec-read-gate-v3-20261120', 'dec-read-gate-v2-20261120', 'review-43d729db', 'review-read-gate-v2-20261120',
           'review-read-gate-v3-20261120', 'dec-scorecard-source-v4-20261120', 'dec-registry-p7v8-20261120', 'dec-read-source-20261120']
lines, bad_all = [], 0
staged = set(git('diff', '--cached', '--name-only').decode().splitlines())
for f in folders:
    base = 'docs/evidence/' + f + '/'
    rows = git('show', ':' + base + 'SHA256SUMS').decode().splitlines()
    bad = []
    for r in rows:
        h, p = r.split(None, 1)
        path = posixpath.normpath(base + p.strip().lstrip('*'))
        if hashlib.sha256(git('show', ':' + path)).hexdigest() != h:
            bad.append(path)
    attr = git('check-attr', 'text', '--', base + 'SHA256SUMS').decode().strip().rsplit(': ', 1)[1]
    bad_all += len(bad)
    lines.append(f'{f}: 목록 {len(rows)} 일치 {len(rows)-len(bad)} 불일치 {bad} text={attr}')
lines.append(f'스테이징 파일 {len(staged)}, 권한 설정 파일 포함={".claude/settings.local.json" in staged}')
lines.append(f'합계 불일치 {bad_all}')
print('\n'.join(lines))
sys.exit(1 if bad_all else 0)
