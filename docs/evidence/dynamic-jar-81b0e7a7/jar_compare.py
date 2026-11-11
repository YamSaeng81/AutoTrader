"""두 bootJar 의 항목별 대조 — 항목 집합·CRC·크기. 중첩 jar(BOOT-INF/lib)는 CRC 로만 본다. 실행: python -X utf8 jar_compare.py OLD NEW"""
import hashlib, json, sys, zipfile
def load(p):
    with zipfile.ZipFile(p) as z:
        return {i.filename: (i.CRC, i.file_size) for i in z.infolist() if not i.is_dir()}
old_p, new_p = sys.argv[1], sys.argv[2]
o, n = load(old_p), load(new_p)
only_old = sorted(set(o) - set(n)); only_new = sorted(set(n) - set(o))
changed = sorted(k for k in set(o) & set(n) if o[k] != n[k])
def sha(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()
print(json.dumps({"old": {"sha256": sha(old_p), "entries": len(o)}, "new": {"sha256": sha(new_p), "entries": len(n)},
                  "only_old": only_old, "only_new": only_new, "changed": changed}, ensure_ascii=False, indent=1))
