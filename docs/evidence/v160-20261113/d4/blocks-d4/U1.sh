(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V160D4 ] || stop "U 변수가 v1.160 통합 후보 초안 D4 판이 아님 — F → G → v1.160 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
[ -e "$M/ok-U0" ] || stop "U0 미통과"
[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 통과했다 — 바로 U2"
[ $(( $(date +%s) - $(stat -c %Y "$M/ok-U0") )) -le 1800 ] || stop "U0 가 30분보다 오래됨 — U0 부터(아직 바꾼 것 없음, U1-되돌림 하지 않는다)"
MIN=$((10#$(date +%M)))
[ "$MIN" -ge 25 ] || [ "$MIN" -lt 14 ] || stop "매시 :14~:24 는 freshness 회차 직전·직후다 — :25 이후 U0 부터(아직 바꾼 것 없음, U1-되돌림 하지 않는다)"
[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "HEAD·작업 트리가 U0 때와 다름(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"
FI=0; fresh_idle u1 || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 끝난 뒤 U0 부터(끊지 않는다)"; stop "프로세스 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 회차 없음으로 보지 않는다. pgrep-u1.* 를 보고"; }
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-apply.utc"
IMG0=$(cat "$M/image-before.txt")
if docker image inspect $REPO:b5cd1d0 > /dev/null 2>&1; then [ "$(docker image inspect --format '{{.Id}}' $REPO:b5cd1d0)" = "$IMG0" ] || stop "$REPO:b5cd1d0 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi
docker tag "$IMG0" $REPO:b5cd1d0
[ "$(docker image inspect --format '{{.Id}}' $REPO:b5cd1d0)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"
git checkout --quiet $NEW
[ "$(git rev-parse HEAD)" = "$NEW" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "체크아웃 결과가 NEW 가 아니거나 변경 있음 — U1-되돌림"
[ "$(h256 "$NF")" = "$NHNEW" ] && [ -e "$NT" ] || stop "start_66.py 가 운영·후보 같은 원문(68d441ce…)과 다르거나 시험 파일 없음 — U1-되돌림"
python3 -c 'import ast,sys; ast.parse(open(sys.argv[1], encoding="utf-8").read())' "$NF" > "$M/syntax.txt" 2>&1 || { cat "$M/syntax.txt"; stop "start_66.py 문법 확인 실패 — U1-되돌림"; }   # 캐시 파일을 쓰지 않는 문법 확인
date '+%F %T %Z' | tee "$M/t-build.txt"
$DC build backend > "$M/build.log" 2>&1 || stop "빌드 실패 — build.log 를 보고. U1-되돌림"
IMG1=$(docker image inspect --format '{{.Id}}' $REPO:latest); echo "$IMG1" | tee "$M/image-new.txt"
[ "$IMG1" != "$IMG0" ] || stop "새 이미지가 만들어지지 않음 — U1-되돌림"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "🔴 실행 중 backend 가 바뀌었다 — 보고"
docker cp "$($DC ps -q backend)":/app/app.jar "$M/app-old.jar"
CID=$(docker create $REPO:latest); docker cp "$CID":/app/app.jar "$M/app-new.jar"; docker rm "$CID" > /dev/null
sha256sum "$M/app-old.jar" "$M/app-new.jar" | tee "$M/jar-sha.txt"
python3 - "$M/app-old.jar" "$M/app-new.jar" > "$M/jar-diff.txt" 2>&1 <<'EOF' && JC=0 || JC=$?
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
EOF
cat "$M/jar-diff.txt"
[ "$JC" = 0 ] || stop "jar 비교기 v2 거절(종료 $JC) — 중복 항목·형식 이상. jar-diff.txt 를 보고(재기동 전). U1-되돌림"
[ "$(grep -v '^(끝)' "$M/jar-diff.txt")" = "$RARX" ] || stop "jar 차이가 기대 62줄(변경 48·추가 14, V96 SQL 포함)이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"
grep -qxF "$JARN" "$M/jar-diff.txt" || stop "항목 수가 881 → 895 가 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"
echo "== frontend(같은 릴리스) — 기존 이미지 보존 태그·새 이미지 빌드(재기동 없음)"
FE0=$(cat "$M/image-fe-before.txt"); FEREF=$(cat "$M/fe-ref-before.txt"); FER=${FEREF%%:*}
if docker image inspect $FER:b5cd1d0 > /dev/null 2>&1; then [ "$(docker image inspect --format '{{.Id}}' $FER:b5cd1d0)" = "$FE0" ] || stop "$FER:b5cd1d0 태그가 이미 다른 이미지를 가리킨다 — U1-되돌림"; fi
docker tag "$FE0" $FER:b5cd1d0
[ "$(docker image inspect --format '{{.Id}}' $FER:b5cd1d0)" = "$FE0" ] || stop "frontend 기존 이미지 보존 태그 실패 — U1-되돌림"
$DC build frontend > "$M/build-fe.log" 2>&1 || stop "frontend 빌드 실패 — build-fe.log 를 보고. U1-되돌림"
FE1=$(docker image inspect --format '{{.Id}}' "$FEREF"); echo "$FE1" | tee "$M/image-fe-new.txt"
[ "$FE1" != "$FE0" ] || stop "새 frontend 이미지가 만들어지지 않음 — U1-되돌림"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q frontend)")" = "$FE0" ] || stop "🔴 실행 중 frontend 가 바뀌었다 — 보고"
touch "$M/ok-U1"; echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2(backend 만 재기동, frontend 는 U3 뒤 UF2)"
)
