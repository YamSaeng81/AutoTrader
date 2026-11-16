# V171D4 이미지 대응 확인 도구(총괄 v1.171 §3-2). containerd 이미지 저장소에서 이미지 참조의 대상 ID·linux/amd64 manifest·config 와
# 실행 컨테이너의 연결을 확인한다. 하나라도 확인하지 못하면 표준 오류에 '🔴 이미지 대응 거절'을 쓰고 종료 3(표준 출력 없음).
#   api  <대상ID>     표준 입력 = GET /images/<대상ID>/json?manifests=1 응답 → "manifest 사용컨테이너(쉼표, 없으면 -)"
#   save <manifest>   표준 입력 = docker image save --platform linux/amd64 <대상ID> 스트림(디스크에 쓰지 않음) → "config"
#   ctr               표준 입력 = docker inspect --format '{{.Id}} {{.Image}} {{json .ImageManifestDescriptor}}' 한 줄 → "컨테이너ID .Image manifest"
import hashlib, json, re, sys, tarfile
H = re.compile(r"sha256:[0-9a-f]{64}")
CID = re.compile(r"[0-9a-f]{64}")
MAN = "application/vnd.oci.image.manifest.v1+json"
CFG = "application/vnd.oci.image.config.v1+json"
AMD = {"architecture": "amd64", "os": "linux"}


def die(m):
    sys.stderr.write("🔴 이미지 대응 거절: " + m + "\n")
    sys.exit(3)


def plat(p):
    return isinstance(p, dict) and p == AMD


def api(t):
    if not H.fullmatch(t):
        die("대상 ID 형식이 아님 %r" % t)
    try:
        d = json.loads(sys.stdin.buffer.read())
    except ValueError:
        die("API 응답이 JSON 이 아님")
    if not isinstance(d, dict) or d.get("Id") != t or (d.get("Descriptor") or {}).get("digest") != t:
        die("API 응답의 Id·Descriptor 가 조회한 대상 ID 와 다름")
    ms = [m for m in (d.get("Manifests") or []) if isinstance(m, dict) and m.get("Kind") == "image"]
    if len(ms) != 1:
        die("image 종류 manifest 가 정확히 1개가 아님(%d개)" % len(ms))
    m = ms[0]
    desc = m.get("Descriptor") or {}
    data = m.get("ImageData") or {}
    if not H.fullmatch(str(m.get("ID"))) or desc.get("digest") != m.get("ID"):
        die("manifest ID 와 Descriptor digest 가 다름")
    if desc.get("mediaType") != MAN:
        die("manifest 형식이 OCI manifest 가 아님 %r" % desc.get("mediaType"))
    if not plat(desc.get("platform")) or not plat(data.get("Platform")):
        die("manifest 플랫폼이 linux/amd64 가 아님 %r %r" % (desc.get("platform"), data.get("Platform")))
    if m.get("Available") is not True:
        die("manifest 내용이 저장소에 없음(Available 아님)")
    cs = data.get("Containers") or []
    if not isinstance(cs, list) or any(not CID.fullmatch(str(c)) for c in cs) or len(set(cs)) != len(cs):
        die("사용 컨테이너 목록 형식 이상")
    print(m["ID"], ",".join(cs) if cs else "-")


def save(mdig):
    if not H.fullmatch(mdig):
        die("manifest 형식이 아님 %r" % mdig)
    small = {}
    try:
        with tarfile.open(fileobj=sys.stdin.buffer, mode="r|") as t:
            for e in t:
                if e.isfile() and (e.name == "index.json" or (e.name.startswith("blobs/sha256/") and e.size < (1 << 20))):
                    if e.name in small:
                        die("저장 산출물에 같은 이름 중복 " + e.name)
                    small[e.name] = t.extractfile(e).read()
    except tarfile.TarError as x:
        die("저장 산출물을 읽지 못함(%s)" % type(x).__name__)
    for k, b in small.items():
        if k.startswith("blobs/sha256/") and hashlib.sha256(b).hexdigest() != k[len("blobs/sha256/"):]:
            die("blob 원문 해시가 이름과 다름 " + k)
    if "index.json" not in small:
        die("저장 산출물에 index.json 없음")
    idx = json.loads(small["index.json"])
    ms = idx.get("manifests") or []
    if len(ms) != 1 or ms[0].get("digest") != mdig or ms[0].get("mediaType") != MAN or not plat(ms[0].get("platform")):
        die("저장 산출물 index 가 linux/amd64 manifest %s 하나가 아님" % mdig[:19])
    mb = small.get("blobs/sha256/" + mdig[7:])
    if mb is None or len(mb) != ms[0].get("size"):
        die("manifest 원문 없음 또는 크기 다름")
    mj = json.loads(mb)
    c = mj.get("config") or {}
    if mj.get("mediaType") != MAN or c.get("mediaType") != CFG or not H.fullmatch(str(c.get("digest"))):
        die("manifest 의 config 기술자 형식 이상")
    cb = small.get("blobs/sha256/" + c["digest"][7:])
    if cb is None or len(cb) != c.get("size"):
        die("config 원문 없음 또는 크기 다름")
    cj = json.loads(cb)
    if cj.get("os") != "linux" or cj.get("architecture") != "amd64" or cj.get("variant"):
        die("config 플랫폼이 linux/amd64 가 아님 %r/%r" % (cj.get("os"), cj.get("architecture")))
    print(c["digest"])


def ctr():
    line = sys.stdin.read().strip()
    p = line.split(" ", 2)
    if len(p) != 3 or not CID.fullmatch(p[0]) or not H.fullmatch(p[1]):
        die("컨테이너 조회 줄 형식 이상 %r" % line[:120])
    try:
        d = json.loads(p[2])
    except ValueError:
        die("ImageManifestDescriptor 가 JSON 이 아님")
    if not isinstance(d, dict) or d.get("mediaType") != MAN or not plat(d.get("platform")) or not H.fullmatch(str(d.get("digest"))):
        die("컨테이너 ImageManifestDescriptor 가 linux/amd64 OCI manifest 가 아님 %r" % (d if isinstance(d, dict) else p[2][:80]))
    print(p[0], p[1], d["digest"])


if len(sys.argv) == 3 and sys.argv[1] == "api":
    api(sys.argv[2])
elif len(sys.argv) == 3 and sys.argv[1] == "save":
    save(sys.argv[2])
elif len(sys.argv) == 2 and sys.argv[1] == "ctr":
    ctr()
else:
    die("사용법: api <대상ID> | save <manifest> | ctr")
