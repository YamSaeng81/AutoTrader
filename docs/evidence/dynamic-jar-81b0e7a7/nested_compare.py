"""bootJar 안 모듈 jar(BOOT-INF/lib/*-0.1.0.jar)의 내부 항목 CRC 대조 — 바깥 CRC 차이가 내용 차이인지 묶음 메타(시각)인지 가린다."""
import io, json, sys, zipfile
def inner(p, name):
    with zipfile.ZipFile(p) as z:
        data = z.read(name)
    with zipfile.ZipFile(io.BytesIO(data)) as zz:
        return {i.filename: (i.CRC, i.file_size) for i in zz.infolist() if not i.is_dir()}, {i.filename: i.date_time for i in zz.infolist()}
out = {}
for name in sys.argv[3:]:
    (o, ot), (n, nt) = inner(sys.argv[1], name), inner(sys.argv[2], name)
    out[name] = {"entries": [len(o), len(n)], "content_changed": sorted(k for k in set(o) | set(n) if o.get(k) != n.get(k)),
                 "timestamp_only_differs": sorted(set(ot.items()) ^ set(nt.items())) != [] }
print(json.dumps(out, ensure_ascii=False, indent=1))
