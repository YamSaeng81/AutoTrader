(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = 98 ] || stop "U 변수가 v1.130(§5-9 98 블록) #26 최종안 판이 아님 — F → G → §5-9 98 U 변수"
[ -e "$M/ok-U0" ] || stop "U0 미통과"
[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 통과했다 — 바로 U2"
[ $(( $(date +%s) - $(stat -c %Y "$M/ok-U0") )) -le 1800 ] || stop "U0 가 30분보다 오래됨 — U0 부터(아직 바꾼 것 없음, U1-되돌림 하지 않는다)"
IMG0=$(cat "$M/image-before.txt")
if docker image inspect $REPO:ee509cf > /dev/null 2>&1; then [ "$(docker image inspect --format '{{.Id}}' $REPO:ee509cf)" = "$IMG0" ] || stop "$REPO:ee509cf 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi
docker tag "$IMG0" $REPO:ee509cf
[ "$(docker image inspect --format '{{.Id}}' $REPO:ee509cf)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"
git checkout --quiet $NEW
[ "$(git rev-parse HEAD)" = "$NEW" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "체크아웃 결과가 NEW 가 아니거나 변경 있음 — U1-되돌림"
date '+%F %T %Z' | tee "$M/t-build.txt"
$DC build backend > "$M/build.log" 2>&1 || stop "빌드 실패 — build.log 를 보고. U1-되돌림"
IMG1=$(docker image inspect --format '{{.Id}}' $REPO:latest); echo "$IMG1" | tee "$M/image-new.txt"
[ "$IMG1" != "$IMG0" ] || stop "새 이미지가 만들어지지 않음 — U1-되돌림"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "🔴 실행 중 backend 가 바뀌었다 — 보고"
docker cp "$($DC ps -q backend)":/app/app.jar "$M/app-old.jar"
CID=$(docker create $REPO:latest); docker cp "$CID":/app/app.jar "$M/app-new.jar"; docker rm "$CID" > /dev/null
sha256sum "$M/app-old.jar" "$M/app-new.jar" | tee "$M/jar-sha.txt"
python3 - "$M/app-old.jar" "$M/app-new.jar" <<'EOF' | tee "$M/jar-diff.txt"
import hashlib, io, re, sys, zipfile
OWN = re.compile(r"BOOT-INF/lib/(core-engine|strategy-lib|exchange-adapter)[^/]*\.jar$")
def entries(path):
    z, out = zipfile.ZipFile(path), {}
    if len(set(z.namelist())) != len(z.namelist()):
        out["(중복 항목 이름 있음)"] = path
    for n in z.namelist():
        if n.endswith("/"):
            continue
        if OWN.match(n):
            inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
            for m in inner.namelist():
                if not m.endswith("/"):
                    out[re.sub(r"-[0-9.]+(-SNAPSHOT)?\.jar$", ".jar", n) + "!" + m] = hashlib.sha256(inner.read(m)).hexdigest()
        else:
            out[n] = hashlib.sha256(z.read(n)).hexdigest()
    return out
a, b = entries(sys.argv[1]), entries(sys.argv[2])
for k in sorted(set(a) | set(b)):
    if a.get(k) != b.get(k):
        print(("추가 " if k not in a else "삭제 " if k not in b else "변경 ") + k)
print("(끝)", len(a), len(b))
EOF
[ "$(grep -v '^(끝)' "$M/jar-diff.txt")" = "$RARX" ] || stop "jar 차이가 N20 2줄이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"
grep -qx '(끝) 881 881' "$M/jar-diff.txt" || stop "항목 수가 881 → 881 가 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"
touch "$M/ok-U1"; echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2"
)
