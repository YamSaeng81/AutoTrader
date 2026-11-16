# docker image save 스트림(OCI 레이아웃 tar)을 읽어 index→manifest→config 연결과 각 원문 sha256을 확인한다. 디스크에 쓰지 않는다.
import hashlib, json, sys, tarfile
small = {}; names = []
with tarfile.open(fileobj=sys.stdin.buffer, mode='r|') as t:
    for m in t:
        names.append(m.name)
        if not m.isfile(): continue
        if m.name in ('index.json', 'manifest.json', 'oci-layout') or (m.name.startswith('blobs/sha256/') and m.size < 65536):
            small[m.name] = t.extractfile(m).read()
bad = [n for n, b in small.items() if n.startswith('blobs/sha256/') and hashlib.sha256(b).hexdigest() != n.split('/')[-1]]
print('blob 원문 해시 불일치', len(bad), bad)
idx = json.loads(small['index.json'])
def blob(d): return json.loads(small['blobs/sha256/' + d.split(':')[1]])
def walk(desc, depth=0):
    mt = desc.get('mediaType'); d = desc['digest']; p = desc.get('platform'); a = desc.get('annotations', {})
    print('  ' * depth + f'{mt} {d} platform={p} ref={a.get("io.containerd.image.name") or a.get("org.opencontainers.image.ref.name") or ""}')
    if 'blobs/sha256/' + d.split(':')[1] not in small: print('  ' * depth + '  (본문 없음/큼)'); return
    j = blob(d)
    if 'manifests' in j:
        for c in j['manifests']: walk(c, depth + 1)
    elif 'config' in j:
        c = j['config']; ok = ('blobs/sha256/' + c['digest'].split(':')[1]) in small
        print('  ' * depth + f'  config {c["digest"]} size={c["size"]} 원문있음={ok}')
        if ok:
            cj = blob(c['digest']); print('  ' * depth + f'  config os={cj.get("os")} arch={cj.get("architecture")} created={cj.get("created")}')
for d in idx['manifests']: walk(d)
if 'manifest.json' in small: print('manifest.json', small['manifest.json'].decode()[:400])
