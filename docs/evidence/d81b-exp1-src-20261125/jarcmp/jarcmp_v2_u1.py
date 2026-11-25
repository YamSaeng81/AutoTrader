import hashlib, io, re, sys, zipfile
# jar 비교기 v2(총괄 v1.135 H2) — v1(k23-prep-51dcee5/jars/jarcmp.py)과 같은 항목 비교에 '중복 명시 거절'을 더했다.
# 바깥 jar 이름 중복·자체 모듈 jar 내부 이름 중복·버전만 다른 자체 모듈 jar 가 같은 이름으로 겹치는 경우를 옛/새 양쪽 모두에서 찾는다.
# 같은 바이트의 중복도 거절한다. 양쪽에 같은 중복이 있어도 상쇄하지 않는다. 하나라도 있으면 차이를 내지 않고 종료 3.
# 시각·압축 방식만 다른 재포장은 내용 해시가 같으므로 차이 0 이다. 출력 형식(변경/추가/삭제 줄·'(끝) a b')은 v1 과 같다.
OWN = re.compile(r"BOOT-INF/lib/(core-engine|strategy-lib|exchange-adapter)[^/]*\.jar$")
def entries(path, bad):
    try:
        z = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as e:
        bad.append("%s: 바깥 jar 를 열 수 없음(%s)" % (path, type(e).__name__)); return {}
    out, names = {}, z.namelist()
    for n in sorted(set(x for x in names if names.count(x) > 1)):
        bad.append("%s: 바깥 항목 이름 중복 %s ×%d" % (path, n, names.count(n)))
    owner = {}
    for n in dict.fromkeys(names):
        if n.endswith("/"):
            continue
        if OWN.match(n):
            k0 = re.sub(r"-[0-9.]+(-SNAPSHOT)?\.jar$", ".jar", n)
            if k0 in owner:
                bad.append("%s: 자체 모듈 jar 가 같은 이름으로 겹침 %s · %s" % (path, owner[k0], n)); continue
            owner[k0] = n
            try:
                inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
            except zipfile.BadZipFile:
                bad.append("%s: 자체 모듈 jar 를 열 수 없음 %s" % (path, n)); continue
            ins = inner.namelist()
            for m in sorted(set(x for x in ins if ins.count(x) > 1)):
                bad.append("%s: 자체 모듈 내부 이름 중복 %s!%s ×%d" % (path, n, m, ins.count(m)))
            for m in dict.fromkeys(ins):
                if not m.endswith("/"):
                    out[k0 + "!" + m] = hashlib.sha256(inner.read(m)).hexdigest()
        else:
            out[n] = hashlib.sha256(z.read(n)).hexdigest()
    return out
bad = []
a, b = entries(sys.argv[1], bad), entries(sys.argv[2], bad)
if bad:
    for x in bad:
        print("🔴 jar 비교 거절: " + x)
    print("(거절) 중복·형식 이상 %d건 — 차이를 내지 않는다" % len(bad))
    sys.exit(3)
for k in sorted(set(a) | set(b)):
    if a.get(k) != b.get(k):
        print(("추가 " if k not in a else "삭제 " if k not in b else "변경 ") + k)
print("(끝)", len(a), len(b))
