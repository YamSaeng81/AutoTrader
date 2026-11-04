import hashlib, io, re, sys, zipfile
OWN = re.compile(r"BOOT-INF/lib/(core-engine|strategy-lib|exchange-adapter)[^/]*\.jar$")
def classes(path):
    z, out = zipfile.ZipFile(path), {}
    for n in z.namelist():
        if n.startswith("BOOT-INF/classes/") and not n.endswith("/"):
            out[n] = hashlib.sha256(z.read(n)).hexdigest()
        elif n.startswith("BOOT-INF/lib/") and n.endswith(".jar"):
            if OWN.match(n):
                inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
                for m in inner.namelist():
                    if not m.endswith("/"):
                        out[re.sub(r"-[0-9.]+(-SNAPSHOT)?\.jar$", ".jar", n) + "!" + m] = hashlib.sha256(inner.read(m)).hexdigest()
            else:
                out[n] = hashlib.sha256(z.read(n)).hexdigest()
    return out
a, b = classes(sys.argv[1]), classes(sys.argv[2])
for k in sorted(set(a) | set(b)):
    if a.get(k) != b.get(k):
        print(("추가 " if k not in a else "삭제 " if k not in b else "변경 ") + k)
print("(끝)", len(a), len(b))
