# W1(주말 지시 §3) 근거 목록 대조기 — 읽기만 한다. 사용: python -I audit.py <저장소> <커밋> <출력 json>
# docs/evidence 아래 모든 SHA256SUMS 를 찾아, 항목마다 (1) 작업 사본 파일 해시 (2) 지정 커밋 블롭 해시를 목록 값과 대조한다.
# 목록에 없는데 폴더에 있는 파일, git ignore 대상 항목도 적는다. 파일을 고치거나 git add 하지 않는다.
import hashlib, json, pathlib, re, subprocess, sys
repo, rev, outp = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
ev = repo / 'docs' / 'evidence'
def git(*a, inp=None):
    return subprocess.run(['git', '-C', str(repo), *a], input=inp, capture_output=True, check=True).stdout
# 커밋 트리의 모든 블롭 sha256 (cat-file --batch)
paths = [p for p in git('ls-tree', '-r', '--name-only', '-z', rev, 'docs/evidence').decode('utf-8').split('\0') if p]
blob = {}
if paths:
    data = git('cat-file', '--batch', inp=''.join('%s:%s\n' % (rev, p) for p in paths).encode('utf-8'))
    i = 0
    for p in paths:
        nl = data.index(b'\n', i); hdr = data[i:nl].split(); size = int(hdr[2])
        blob[p] = hashlib.sha256(data[nl + 1:nl + 1 + size]).hexdigest(); i = nl + 1 + size + 1
res = {'commit': rev, 'manifests': [], 'summary': {}}
tot = {'entries': 0, 'work_ok': 0, 'work_missing': 0, 'work_diff': 0, 'blob_ok': 0, 'blob_missing': 0, 'blob_diff': 0, 'ignored_entries': 0, 'unlisted_files': 0}
for m in sorted(ev.rglob('SHA256SUMS')):
    d = m.parent; rel = d.relative_to(repo).as_posix()
    ent = []
    for ln in m.read_bytes().decode('utf-8').replace('\r', '').split('\n'):
        mm = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', ln)
        if mm: ent.append((mm.group(1), mm.group(2)))
    rows, listed = [], set()
    for h, f in ent:
        f2 = f[2:] if f.startswith('./') else f
        listed.add(f2); p = d / f2; gp = rel + '/' + f2
        w = hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None
        b = blob.get(gp)
        ig = subprocess.run(['git', '-C', str(repo), 'check-ignore', '-q', '--no-index', gp]).returncode == 0
        r = {'file': f2, 'work': 'ok' if w == h else 'missing' if w is None else 'diff', 'blob': 'ok' if b == h else 'missing' if b is None else 'diff', 'ignored': ig}
        tot['entries'] += 1; tot['work_' + r['work']] += 1; tot['blob_' + r['blob']] += 1; tot['ignored_entries'] += ig
        if r['work'] != 'ok' or r['blob'] != 'ok' or ig: rows.append(r)
    un = sorted(q.relative_to(d).as_posix() for q in d.rglob('*') if q.is_file() and q.relative_to(d).as_posix() not in listed and q.name not in ('SHA256SUMS', '.gitattributes'))
    tot['unlisted_files'] += len(un)
    res['manifests'].append({'dir': rel, 'entries': len(ent), 'problems': rows, 'unlisted': un})
res['summary'] = tot
pathlib.Path(outp).write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
print(json.dumps(tot, ensure_ascii=False))
for x in res['manifests']:
    if x['problems']:
        print(x['dir'], x['entries'], x['problems'])
