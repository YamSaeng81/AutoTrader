# V171D4 대역 — containerd 이미지 저장소 흉내(가짜 docker 가 부른다). 실제 docker 를 부르지 않는다.
# 상태: $ST/img/<hex>(이미지 있음) · $ST/man/<hex>("sha256:M sha256:C") · $ST/blob/<hex>(manifest·config 원문) · $ST/ctr/<서비스>("cid .Image manifest")
#   seed <ST> <blobs.json> <대상ID>   실제 서버 save 원문(index.json·작은 blob)으로 이미지 등록
#   build <ST> <종류> <씨앗>           새 이미지(대상·manifest·config 원문을 만들어 해시로 이름) → 대상 ID 출력
#   api <ST> <대상ID>                 GET /images/<대상>/json?manifests=1 흉내(JSON)
#   save <ST> <대상ID>                docker image save --platform linux/amd64 흉내(tar 바이너리 → 표준 출력)
# 고장 주입(파일): $ST/fault/api-plat-<hex>(값=architecture) · api-nousers-<hex>(사용 컨테이너 목록 비움) · api-two-<hex>(image manifest 2개) · save-cfgos-<hex>(config os 값) · save-badblob-<hex>(manifest 원문 변조)
import base64, hashlib, io, json, os, sys, tarfile

MAN = "application/vnd.oci.image.manifest.v1+json"
IDX = "application/vnd.oci.image.index.v1+json"
CFG = "application/vnd.oci.image.config.v1+json"


def sh(b):
    return hashlib.sha256(b).hexdigest()


def wr(p, b):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(b)


def rd(p):
    with open(p, "rb") as f:
        return f.read()


def fault(st, name):
    p = os.path.join(st, "fault", name)
    return rd(p).decode().strip() if os.path.exists(p) else None


cmd, st = sys.argv[1], sys.argv[2]
if cmd == "seed":
    d = json.load(open(sys.argv[3], encoding="utf-8"))
    t = sys.argv[4]
    idx = json.loads(base64.b64decode(d["index.json"]))
    m = idx["manifests"][0]["digest"]
    for k, v in d.items():
        if k.startswith("blobs/sha256/"):
            wr(os.path.join(st, "blob", k[13:]), base64.b64decode(v))
    c = json.loads(rd(os.path.join(st, "blob", m[7:])))["config"]["digest"]
    wr(os.path.join(st, "man", t[7:]), ("%s %s" % (m, c)).encode())
elif cmd == "build":
    kind, seed = sys.argv[3], sys.argv[4]
    cfg = json.dumps({"architecture": "amd64", "os": "linux", "config": {"Labels": {"standin": kind}}, "rootfs": {"type": "layers", "diff_ids": []}, "standin": seed}).encode()
    layer = ("standin layer " + seed).encode()
    man = json.dumps({"schemaVersion": 2, "mediaType": MAN, "config": {"mediaType": CFG, "digest": "sha256:" + sh(cfg), "size": len(cfg)},
                      "layers": [{"mediaType": "application/vnd.oci.image.layer.v1.tar+gzip", "digest": "sha256:" + sh(layer), "size": len(layer)}]}).encode()
    idx = json.dumps({"schemaVersion": 2, "mediaType": IDX, "manifests": [{"mediaType": MAN, "digest": "sha256:" + sh(man), "size": len(man), "platform": {"architecture": "amd64", "os": "linux"}}], "standin": seed}).encode()
    for b in (cfg, layer, man, idx):
        wr(os.path.join(st, "blob", sh(b)), b)
    t = sh(idx)
    wr(os.path.join(st, "man", t), ("sha256:%s sha256:%s" % (sh(man), sh(cfg))).encode())
    print("sha256:" + t)
elif cmd == "api":
    t = sys.argv[3]
    m, c = rd(os.path.join(st, "man", t[7:])).decode().split()
    users = []
    for s in sorted(os.listdir(os.path.join(st, "ctr"))):
        cid, img, cm = rd(os.path.join(st, "ctr", s)).decode().split()
        if cm == m:
            users.append(cid)
    if fault(st, "api-nousers-" + t[7:]):
        users = []
    arch = fault(st, "api-plat-" + t[7:]) or "amd64"
    ent = {"ID": m, "Descriptor": {"mediaType": MAN, "digest": m, "size": len(rd(os.path.join(st, "blob", m[7:]))), "platform": {"architecture": arch, "os": "linux"}},
           "Available": True, "Size": {"Content": 1, "Total": 1}, "Kind": "image",
           "ImageData": {"Platform": {"architecture": arch, "os": "linux"}, "Size": {"Unpacked": 1}, "Containers": users or None}}
    att = {"ID": "sha256:" + sh(("att" + m).encode()), "Descriptor": {"mediaType": MAN, "digest": "sha256:" + sh(("att" + m).encode()), "size": 837,
           "annotations": {"vnd.docker.reference.digest": m, "vnd.docker.reference.type": "attestation-manifest"}, "platform": {"architecture": "unknown", "os": "unknown"}},
           "Available": True, "Kind": "attestation", "AttestationData": {"For": m}}
    ms = [ent, att]
    if fault(st, "api-two-" + t[7:]):
        e2 = json.loads(json.dumps(ent)); e2["ID"] = e2["Descriptor"]["digest"] = "sha256:" + sh(("arm" + m).encode())
        e2["Descriptor"]["platform"] = e2["ImageData"]["Platform"] = {"architecture": "arm64", "os": "linux"}; ms.append(e2)
    print(json.dumps({"Id": t, "Descriptor": {"mediaType": IDX, "digest": t, "size": 856}, "Manifests": ms}))
elif cmd == "save":
    t = sys.argv[3]
    m, c = rd(os.path.join(st, "man", t[7:])).decode().split()
    mb = rd(os.path.join(st, "blob", m[7:]))
    cb = rd(os.path.join(st, "blob", c[7:]))
    os_ = fault(st, "save-cfgos-" + t[7:])
    if os_:
        j = json.loads(cb); j["os"] = os_; cb = json.dumps(j).encode()
        mj = json.loads(mb); mj["config"]["digest"] = "sha256:" + sh(cb); mj["config"]["size"] = len(cb); mb = json.dumps(mj).encode()
        m2 = "sha256:" + sh(mb)
    else:
        m2 = m
    files = {"oci-layout": b'{"imageLayoutVersion": "1.0.0"}',
             "index.json": json.dumps({"schemaVersion": 2, "mediaType": IDX, "manifests": [{"mediaType": MAN, "digest": m2, "size": len(mb), "platform": {"architecture": "amd64", "os": "linux"}}]}).encode(),
             "blobs/sha256/" + m2[7:]: (mb + b" " if fault(st, "save-badblob-" + t[7:]) else mb),
             "blobs/sha256/" + sh(cb): cb}
    bio = io.BytesIO()
    with tarfile.open(fileobj=bio, mode="w") as tf:
        for name, b in files.items():
            ti = tarfile.TarInfo(name); ti.size = len(b); tf.addfile(ti, io.BytesIO(b))
        big = b"L" * (1 << 20) + (t + "layer").encode()   # 1MB 넘는 레이어(도구가 건너뛰는 크기)
        ti = tarfile.TarInfo("blobs/sha256/" + sh(big)); ti.size = len(big); tf.addfile(ti, io.BytesIO(big))
    sys.stdout.buffer.write(bio.getvalue()); sys.stdout.buffer.flush()
else:
    sys.exit("모르는 명령 " + cmd)
