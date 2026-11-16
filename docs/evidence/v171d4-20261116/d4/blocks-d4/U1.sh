(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V171D4 ] || stop "U 변수가 v1.171 통합 후보 초안 D4 판이 아님 — F → G → v1.171 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
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
echo "== V171D4 — 바꾸기 전 대응 확인(backend·frontend 실행 컨테이너·이미지 이름·보존 태그)"
[ "$IMG0" = "$IMGOLD" ] && [ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] && [ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] && [ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "U0 대응 기록이 없거나 고정값과 다름(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"
same3 $REPO:latest "$M/image-before-chain.txt" || stop "$REPO:latest 가 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"
same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름이 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"
if docker image inspect $REPO:b5cd1d0 > /dev/null 2>&1; then same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "$REPO:b5cd1d0 태그가 이미 다른 이미지를 가리키거나 조회 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi
if docker image inspect autotrader-frontend:b5cd1d0 > /dev/null 2>&1; then same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "autotrader-frontend:b5cd1d0 태그가 이미 다른 이미지를 가리키거나 조회 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi
docker tag "$IMGOLD" $REPO:b5cd1d0
same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "기존 backend 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"
docker tag "$FETOLD" autotrader-frontend:b5cd1d0
same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "기존 frontend 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"
echo 보존태그 > "$M/fe-stage.txt"   # frontend 단계: 보존태그 → 빌드시작(이미지 이름이 바뀔 수 있음) → 빌드끝(새 이미지 기록)
git checkout --quiet $NEW
[ "$(git rev-parse HEAD)" = "$NEW" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "체크아웃 결과가 NEW 가 아니거나 변경 있음 — U1-되돌림"
[ "$(h256 "$NF")" = "$NHNEW" ] && [ -e "$NT" ] || stop "start_66.py 가 운영·후보 같은 원문(68d441ce…)과 다르거나 시험 파일 없음 — U1-되돌림"
python3 -c 'import ast,sys; ast.parse(open(sys.argv[1], encoding="utf-8").read())' "$NF" > "$M/syntax.txt" 2>&1 || { cat "$M/syntax.txt"; stop "start_66.py 문법 확인 실패 — U1-되돌림"; }   # 캐시 파일을 쓰지 않는 문법 확인
date '+%F %T %Z' | tee "$M/t-build.txt"
$DC build backend > "$M/build.log" 2>&1 || stop "빌드 실패 — build.log 를 보고. U1-되돌림"
IMG1=$(docker image inspect --format '{{.Id}}' $REPO:latest) || stop "새 backend 이미지 조회 실패 — U1-되돌림"; echo "$IMG1" | tee "$M/image-new.txt"
[ "$IMG1" != "$IMG0" ] || stop "새 이미지가 만들어지지 않음 — U1-되돌림"
R1=$(rchain "$IMG1") || stop "새 backend 이미지의 대상·manifest·config 조회 실패 — U1-되돌림"; echo "$R1" | tee "$M/image-new-chain.txt"
[ "$(cut -d' ' -f2 "$M/image-new-chain.txt")" != "$IMGOLDM" ] || stop "새 backend manifest 가 #27 과 같음 — U1-되돌림"
same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 빌드 중 바뀌었거나 조회 실패 — U1-되돌림(보고)"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "🔴 실행 중 backend 가 바뀌었다 또는 조회 실패 — 보고"
docker cp "$($DC ps -q backend)":/app/app.jar "$M/app-old.jar"
CID=$(docker create "$IMG1"); docker cp "$CID":/app/app.jar "$M/app-new.jar"; docker rm "$CID" > /dev/null
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
# V168D4: 이름 목록(RARX)은 88f06345 입력 jar 와 같다 — 새 jar 의 항목 내용 목록 해시(JARM)로 후보 입력을 확인한다
python3 - "$M/app-new.jar" > "$M/jar-manifest-new.txt" 2>&1 <<'EOF' && JMC=0 || JMC=$?
# 새 jar 항목 내용 목록(V168D4 U1 JARM) — jar 비교기 v2 의 entries()(중복 거절 포함)를 그대로 쓰고, 항목 이름·sha256 을 이름순으로 출력한다.
# 이름 목록(RARX)만으로는 같은 경로가 바뀐 후보 jar 를 구별하지 못한다(b5cd1d0 대비 88f06345·99e6ab10 이 같은 62줄). 이 목록의 sha256 이 JARM 이다.
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
m = entries(sys.argv[1], bad)
if bad:
    for x in bad:
        print("🔴 jar 목록 거절: " + x)
    sys.exit(3)
for k in sorted(m):
    print(k + "	" + m[k])
EOF
[ "$JMC" = 0 ] || stop "새 jar 항목 목록 거절(종료 $JMC) — jar-manifest-new.txt 를 보고(재기동 전). U1-되돌림"
JMH=$(sha256sum < "$M/jar-manifest-new.txt" | cut -c1-64); echo "JARM $JMH" | tee "$M/jar-manifest-sha.txt"
[ "$JMH" = "$JARM" ] || stop "새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름 — 옛 후보 jar 이거나 다른 입력. jar-manifest-new.txt 를 보고(재기동 전). U1-되돌림"
echo "== frontend(같은 릴리스) — 보존 태그 확인·새 이미지 빌드(재기동 없음)"
same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U1 시작 때와 다름 또는 조회 실패 — U1-되돌림(보고)"
echo 빌드시작 > "$M/fe-stage.txt"
$DC build frontend > "$M/build-fe.log" 2>&1 || stop "frontend 빌드 실패 — build-fe.log 를 보고. U1-되돌림(이미지 이름은 보존 태그 대상으로 되돌린다)"
FE1=$(docker image inspect --format '{{.Id}}' autotrader-frontend) || stop "새 frontend 이미지 조회 실패 — U1-되돌림"; echo "$FE1" | tee "$M/image-fe-new.txt"
[ "$FE1" != "$FETOLD" ] || stop "새 frontend 이미지가 만들어지지 않음 — U1-되돌림"
RF1=$(rchain "$FE1") || stop "새 frontend 이미지의 대상·manifest·config 조회 실패 — U1-되돌림"; echo "$RF1" | tee "$M/image-fe-new-chain.txt"
[ "$(cut -d' ' -f2 "$M/image-fe-new-chain.txt")" != "$FEMOLD" ] || stop "새 frontend manifest 가 구판과 같음 — U1-되돌림"
echo 빌드끝 > "$M/fe-stage.txt"
same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 빌드 중 바뀌었거나 조회 실패 — U1-되돌림(보고)"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "🔴 실행 중 frontend 가 바뀌었다 또는 조회 실패 — 보고"
touch "$M/ok-U1"; echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2(backend 만 재기동, frontend 는 U3 뒤 UF2)"
)
