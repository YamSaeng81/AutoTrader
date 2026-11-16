# V171D4 생성기(총괄 v1.171 / 421차 §2~§4) — V168D4 원문(d5790c1c:docs/evidence/v168-final-20261115/d4/blocks-d4)에서
# 지정한 부분만 바꾼다. 바꾸는 곳마다 출현 횟수를 확인하고, 다르면 멈춘다. 끝에 옛 판 표기(maint-v168·UB=V168D4·7df5758f)가 남으면 멈춘다.
# 사용: python mkdraft10.py <V168D4 원문 폴더> <imgchain.py> <출력 폴더>
import hashlib, pathlib, sys

src, icp, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
SRCSUM = {  # d5790c1c 원문 해시(v168-run-20261116/blocks/blocks.json 과 같은 값)
    "U변수": "9042ae7bc39dd0054d4ca43a5e05677f13fb936f9300abf5ee0d1a6064a87311",
    "U0": "cffb1d94c3ff4d93193a0f8133946627e8cf25bb7edcc40da1f38297dc233787",
    "U1": "6127f4ae8b0cf19d1bd97d3ed1ed5ff762f3377b225456ed82a0da08851ae72b",
    "U1되돌림": "aecdd952e76e99a7a8233221accb23f0ed664032c04ae6a6e8395a2c4b111163",
    "U2": "03a25c9375b81fdc4fa0341fa9f270b8614bec075d7736e74143b7f9a0171948",
    "U3": "fd78948b03e850406a9245bfd8bf48d0139622c71d1c153c97740cac8117c8e6",
    "U4": "9d5919f476f67acc00561ee411abab23e9ca28a77cc4ab25426c77615f43036e",
    "UF2": "5b59952371f84ef2e9379a4f0af2e06c77ebdb5d756c2d810c14d360d078a7cf",
    "U되돌림": "4b0da72e82003a4623c25adc268468f4c37ffe651db137484266aa07f5b58f19",
    "U뒤관측": "1257073da29caf02b62e59e81f5124f8fd9f8458146deae8d90ea79f2a76fbc1",
}
B = {}
for k, h in SRCSUM.items():
    b = (src / (k + ".sh")).read_bytes()
    if hashlib.sha256(b).hexdigest() != h or b"\r" in b:
        sys.exit("원문 해시 다름 또는 CR 포함: " + k)
    B[k] = b.decode("utf-8")
ICPY = icp.read_bytes()
if b"\r" in ICPY or not ICPY.endswith(b"\n") or b"\nICEOF\n" in ICPY:
    sys.exit("imgchain.py 형식 이상")
ICSUM = hashlib.sha256(ICPY).hexdigest()


def rep(k, old, new, n=1):
    c = B[k].count(old)
    if c != n:
        sys.exit("%s: 바꿀 문자열 %d회(기대 %d): %s" % (k, c, n, old[:80]))
    B[k] = B[k].replace(old, new)


# ── 판 표지(모든 블록) ──
for k in ["U0", "U1", "U1되돌림", "U2", "U3", "U4", "UF2", "U되돌림", "U뒤관측"]:
    n = 1 if k == "U0" else 2
    rep(k, "v1.168 통합 후보 초안 D4", "v1.171 통합 후보 초안 D4", n)
    if k != "U0":
        rep(k, '[ "${UB:-}" = V168D4 ]', '[ "${UB:-}" = V171D4 ]')
rep("UF2", "# UF2(V168D4 새 블록)", "# UF2(V168D4 에서 온 블록, V171D4 이미지 대응 보완)")

# ── U변수: 판 표지·JARM·이미지 대응 도구 ──
rep("U변수", 'M=~/maint-v168; mkdir -p "$M"; OPS=~/ops-scripts; UB=V168D4   # v1.168 통합 후보(',
    'M=~/maint-v171; mkdir -p "$M"; OPS=~/ops-scripts; UB=V171D4   # v1.171 판(V168D4 + Git LF JARM·이미지 대상/manifest/config 대응·단계별 복구, 총괄 v1.171 §2~§4) 통합 후보(')
rep("U변수", "생성기 mkdraft9.py(값만 변경)", "생성기 mkdraft9.py(값만 변경) → V171D4 생성기 mkdraft10.py(V168D4 원문 d5790c1c 에서 지정 부분만 변경)")
rep("U변수", "JARM=7df5758fc48231372a9a507b228bafb5bfed1648332a8566e0881d80e2e1176d   # V168D4: 새 jar 항목 이름·sha256 목록(jarmanifest, 895줄)의 sha256 — 로컬 bootJar 99e6ab10(0e1d2a02 와 제품·빌드·프런트 입력 동일).",
    "JARM=b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8   # V171D4(총괄 v1.171 §2): 후보 0e1d2a02 Git 블롭(LF) 기준 새 jar 항목 이름·sha256 목록(895줄, docs/evidence/review-b6afaddd/expected-manifest-git-lf.txt)의 sha256. 종전 V168D4 JARM 은 Windows CRLF 리소스 97개를 담은 값(당시 기록으로 보존). 798항목은 종전과 같다.")
ANCHOR = "FEIMGOLD=sha256:d825a9f5b00d3dac93ee154f32f91b5a1474248fefaa3651f0d0c3b0cc2f7f66   # 실행 중 frontend 이미지 ID"
rep("U변수", ANCHOR, "FEIMGOLD=sha256:d825a9f5b00d3dac93ee154f32f91b5a1474248fefaa3651f0d0c3b0cc2f7f66   # 실행 중 frontend 컨테이너의 .Image 기록값(V171D4: 이미지 참조로 쓰지 않는다 — 아래 FETOLD)")
ICBLOCK = r'''# ── V171D4 이미지 대응(총괄 v1.171 §3) — 운영 읽기 2026-11-16 10:52~11:00 KST(docs/evidence/v171d4-20261116/state-1052) ──
# 서버 Docker 29.9.2 는 containerd 이미지 저장소다. 이미지 참조의 대상 ID(index)·linux/amd64 manifest·config 는 서로 다른 값이다.
# 새로 만든 컨테이너의 .Image 는 대상 ID(backend 743dfeb… = 52e12428)다. 09-22 에 만든 frontend 9a63d15… 의 .Image(d825a9f5)는
# 지금 어떤 이미지 객체(대상 837db515·config 80ce7550)와도 같지 않다. 그래서 실행 컨테이너 대응은 ① 컨테이너 ImageManifestDescriptor
# = 참조 대상의 linux/amd64 manifest ② daemon 이 그 manifest 의 사용 컨테이너로 그 컨테이너를 표시 ③ save 산출물의 manifest→config
# 원문 해시 ④ .Image 가 허용값(옛 컨테이너는 기록값, 새 컨테이너는 대상 ID 또는 config)인지로 확인한다. 태그 이름·Compose 라벨만으로 정하지 않는다.
IMGOLDM=sha256:ca440b8403c4a65ce39c3a49f0e480c5ca5e9630380cdbcfe4daf6405fc6c628; IMGOLDC=sha256:0344757e24e4567d56ff735560eb8564eab131933613b6deee663ead91e43e9b   # #27 backend 대상 52e12428 의 linux/amd64 manifest·config(save 원문 해시 확인)
FETOLD=sha256:837db515e0eb8b9cb4bcf8f03ed3b98adf9f8cdb176d13777949c792d10b5017; FEMOLD=sha256:fa5c9ba849c3ee1e48540004105ea18df6d8d25df1bf490a6eeac425413e3ad6; FECOLD=sha256:80ce755066587ea4eadad00e823b9f4aaa5e9696897aa3a4ebcf12cf6d11d946   # 실행 중 frontend: 이미지 이름 autotrader-frontend 의 대상 ID·linux/amd64 manifest·config
DAPI=v1.56   # 서버 Docker Engine API 판(29.9.2)
ICSUM=@ICSUM@   # 아래 imgchain.py 원문 sha256 — 블록이 ~/maint-v171/imgchain.py 로 쓰고 쓸 때마다 대조한다
IFS= read -r -d '' ICPY <<'ICEOF' || true
@ICPY@ICEOF
icpy() { [ -e "$M/imgchain.py" ] || printf '%s' "$ICPY" > "$M/imgchain.py"; [ "$(h256 "$M/imgchain.py")" = "$ICSUM" ] || { echo "🔴 $M/imgchain.py 원문 해시가 ICSUM 과 다름" >&2; return 9; } ; }
rchain() {   # rchain <이미지 참조> → "대상ID manifest config 사용컨테이너(쉼표, 없으면 -)" 한 줄. 하나라도 확인 못 하면 출력 없이 0 아닌 종료
  local t j a c
  icpy || return 9
  t=$(docker image inspect --format '{{.Id}}' "$1") || return 4
  j=$(curl -sf --unix-socket /var/run/docker.sock "http://localhost/$DAPI/images/$t/json?manifests=1") || { echo "🔴 이미지 API 조회 실패 $1" >&2; return 5; }
  a=$(printf '%s' "$j" | python3 -I "$M/imgchain.py" api "$t") || return 6
  c=$(docker image save --platform linux/amd64 "$t" | python3 -I "$M/imgchain.py" save "${a%% *}") || return 7
  echo "$t ${a%% *} $c ${a#* }"
}
cchain() {   # cchain <서비스> → "컨테이너ID .Image manifest"
  local id
  icpy || return 9
  id=$($DC ps -q "$1") && [ -n "$id" ] || { echo "🔴 $1 컨테이너 조회 실패" >&2; return 4; }
  docker inspect --format '{{.Id}} {{.Image}} {{json .ImageManifestDescriptor}}' "$id" | python3 -I "$M/imgchain.py" ctr
}
isimg() {   # isimg <서비스> "<허용 .Image 값들>" <대상ID> <manifest> <config> — 실행 컨테이너가 그 이미지인지
  [ $# = 5 ] || { echo "🔴 isimg 인자 수 $#(기록 파일 없음?)" >&2; return 1; }
  local c r
  c=$(cchain "$1") && r=$(rchain "$3") || return 1
  set -- "$1" "$2" "$3" "$4" "$5" $c $r
  [ $# = 12 ] || { echo "🔴 $1: 대응 조회 결과 형식 이상" >&2; return 1; }
  [ "$9" = "$3" ] && [ "${10}" = "$4" ] && [ "${11}" = "$5" ] && [ "$8" = "$4" ] || { echo "🔴 $1: manifest·config 대응 다름(컨테이너 manifest ${8:7:12} / 기대 ${4:7:12}·${5:7:12}, 조회 ${10:7:12}·${11:7:12})" >&2; return 1; }
  case ",${12}," in *",$6,"*) ;; *) echo "🔴 $1: daemon 의 manifest 사용 컨테이너 목록에 실행 컨테이너 ${6:0:12} 없음" >&2; return 1 ;; esac
  case " $2 " in *" $7 "*) ;; *) echo "🔴 $1: 컨테이너 .Image ${7:7:12} 가 허용값이 아님" >&2; return 1 ;; esac
}
ok3() { cut -d' ' -f1-3 "$1" ; }   # 대응 기록 파일 → "대상 manifest config"
okimg() { echo "$(cut -d' ' -f1 "$1") $(cut -d' ' -f3 "$1")" ; }   # 새로 만든 컨테이너의 .Image 허용값 = 대상 ID 또는 config
same3() {   # same3 <이미지 참조> <대응 기록 파일> — 참조의 대상·manifest·config 가 기록과 같은지(조회 실패는 다름)
  local r
  [ -s "$2" ] && r=$(rchain "$1") || return 1
  [ "$(echo "$r" | cut -d' ' -f1-3)" = "$(ok3 "$2")" ]
}
'''
ICBLOCK = ICBLOCK.replace("@ICSUM@", ICSUM).replace("@ICPY@", ICPY.decode("utf-8"))
rep("U변수", "\nGS=6385e1d5f64eb09a95791ba253a30d2b21341082", "\n" + ICBLOCK + "GS=6385e1d5f64eb09a95791ba253a30d2b21341082")

# ── U0 ──
rep("U0", '[ "$M" = "$HOME/maint-v168" ] && [ "${UB:-}" = V168D4 ] && [ "$NEW" = 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d ] && [ -n "${JARN:-}" ] && [ -n "${JARM:-}" ]',
    '[ "$M" = "$HOME/maint-v171" ] && [ "${UB:-}" = V171D4 ] && [ "$NEW" = 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d ] && [ -n "${JARN:-}" ] && [ "${JARM:-}" = b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8 ] && [ -n "${ICSUM:-}" ] && type isimg > /dev/null 2>&1 && type same3 > /dev/null 2>&1 && type okimg > /dev/null 2>&1')
rep("U0", '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:latest)" = "$IMG0" ] || stop "$REPO:latest 가 실행 이미지와 다름"\n',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:latest)" = "$IMG0" ] || stop "$REPO:latest 가 실행 이미지와 다름"\n'
    'rm -f "$M/fe-stage.txt"   # V171D4: U1 frontend 단계 기록은 이 판의 U1 이 새로 쓴다\n'
    'R0=$(rchain "$IMG0") || stop "backend 실행 이미지의 대상·manifest·config 조회 실패 — 대응 불명, 보고"; echo "$R0" | tee "$M/image-before-chain.txt"\n'
    '[ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] || stop "#27 backend 이미지의 manifest·config 가 고정값(ca440b84…·0344757e…)과 다름 — 보고"\n'
    'isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 컨테이너가 #27 이미지 manifest·config 와 연결되지 않음 — 보고"\n'
    'same3 $REPO:latest "$M/image-before-chain.txt" || stop "$REPO:latest 의 대상·manifest·config 가 실행 이미지와 다름 — 보고"\n'
    'if docker image inspect $REPO:b5cd1d0 > /dev/null 2>&1; then same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "보존 태그 $REPO:b5cd1d0 이 이미 다른 이미지를 가리킨다 — 보고"; fi\n')
rep("U0", 'docker inspect --format \'{{.Config.Image}}\' "$($DC ps -q frontend)" | tee "$M/fe-ref-before.txt"\n[ -s "$M/fe-ref-before.txt" ] || stop "frontend 이미지 이름을 읽지 못함"\n',
    'docker inspect --format \'{{.Config.Image}}\' "$($DC ps -q frontend)" | tee "$M/fe-ref-before.txt"\n'
    '[ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "frontend 이미지 이름이 autotrader-frontend 가 아님 — 보고"\n'
    'RF=$(rchain autotrader-frontend) || stop "frontend 이미지 이름의 대상·manifest·config 조회 실패 — 대응 불명, 보고"; echo "$RF" | tee "$M/image-fe-before-chain.txt"\n'
    '[ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] || stop "frontend 이미지 이름이 고정 대상·manifest·config(837db515…·fa5c9ba8…·80ce7550…)와 다름 — 보고"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 컨테이너가 이미지 이름의 manifest·config 와 연결되지 않음 — 보고"\n'
    'if docker image inspect autotrader-frontend:b5cd1d0 > /dev/null 2>&1; then same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "보존 태그 autotrader-frontend:b5cd1d0 이 이미 다른 이미지를 가리킨다 — 보고"; fi\n')

# ── U1 ── 변경 전에 두 서비스 대응 확인 → 두 보존 태그 → (기존) 체크아웃·backend 빌드 → frontend 빌드(단계 기록)
rep("U1", 'IMG0=$(cat "$M/image-before.txt")\n'
    'if docker image inspect $REPO:b5cd1d0 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $REPO:b5cd1d0)" = "$IMG0" ] || stop "$REPO:b5cd1d0 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi\n'
    'docker tag "$IMG0" $REPO:b5cd1d0\n'
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:b5cd1d0)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"\n',
    'IMG0=$(cat "$M/image-before.txt")\n'
    'echo "== V171D4 — 바꾸기 전 대응 확인(backend·frontend 실행 컨테이너·이미지 이름·보존 태그)"\n'
    '[ "$IMG0" = "$IMGOLD" ] && [ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] && [ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] && [ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "U0 대응 기록이 없거나 고정값과 다름(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"\n'
    'isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"\n'
    'same3 $REPO:latest "$M/image-before-chain.txt" || stop "$REPO:latest 가 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"\n'
    'same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름이 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"\n'
    'if docker image inspect $REPO:b5cd1d0 > /dev/null 2>&1; then same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "$REPO:b5cd1d0 태그가 이미 다른 이미지를 가리키거나 조회 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi\n'
    'if docker image inspect autotrader-frontend:b5cd1d0 > /dev/null 2>&1; then same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "autotrader-frontend:b5cd1d0 태그가 이미 다른 이미지를 가리키거나 조회 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi\n'
    'docker tag "$IMGOLD" $REPO:b5cd1d0\n'
    'same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "기존 backend 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"\n'
    'docker tag "$FETOLD" autotrader-frontend:b5cd1d0\n'
    'same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "기존 frontend 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"\n'
    'echo 보존태그 > "$M/fe-stage.txt"   # frontend 단계: 보존태그 → 빌드시작(이미지 이름이 바뀔 수 있음) → 빌드끝(새 이미지 기록)\n')
rep("U1", 'IMG1=$(docker image inspect --format \'{{.Id}}\' $REPO:latest); echo "$IMG1" | tee "$M/image-new.txt"\n'
    '[ "$IMG1" != "$IMG0" ] || stop "새 이미지가 만들어지지 않음 — U1-되돌림"\n'
    '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)")" = "$IMG0" ] || stop "🔴 실행 중 backend 가 바뀌었다 — 보고"\n'
    'docker cp "$($DC ps -q backend)":/app/app.jar "$M/app-old.jar"\n'
    'CID=$(docker create $REPO:latest); docker cp "$CID":/app/app.jar "$M/app-new.jar"; docker rm "$CID" > /dev/null\n',
    'IMG1=$(docker image inspect --format \'{{.Id}}\' $REPO:latest) || stop "새 backend 이미지 조회 실패 — U1-되돌림"; echo "$IMG1" | tee "$M/image-new.txt"\n'
    '[ "$IMG1" != "$IMG0" ] || stop "새 이미지가 만들어지지 않음 — U1-되돌림"\n'
    'R1=$(rchain "$IMG1") || stop "새 backend 이미지의 대상·manifest·config 조회 실패 — U1-되돌림"; echo "$R1" | tee "$M/image-new-chain.txt"\n'
    '[ "$(cut -d\' \' -f2 "$M/image-new-chain.txt")" != "$IMGOLDM" ] || stop "새 backend manifest 가 #27 과 같음 — U1-되돌림"\n'
    'same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 빌드 중 바뀌었거나 조회 실패 — U1-되돌림(보고)"\n'
    'isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "🔴 실행 중 backend 가 바뀌었다 또는 조회 실패 — 보고"\n'
    'docker cp "$($DC ps -q backend)":/app/app.jar "$M/app-old.jar"\n'
    'CID=$(docker create "$IMG1"); docker cp "$CID":/app/app.jar "$M/app-new.jar"; docker rm "$CID" > /dev/null\n')
rep("U1", '[ "$JMH" = "$JARM" ] || stop "새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름 — 옛 후보 jar 이거나 다른 입력. jar-manifest-new.txt 를 보고(재기동 전). U1-되돌림"\n',
    '[ "$JMH" = "$JARM" ] || stop "새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름 — 옛 후보 jar 이거나 다른 입력. jar-manifest-new.txt 를 보고(재기동 전). U1-되돌림"\n')  # 그대로(위치 확인용)
rep("U1", 'echo "== frontend(같은 릴리스) — 기존 이미지 보존 태그·새 이미지 빌드(재기동 없음)"\n'
    'FE0=$(cat "$M/image-fe-before.txt"); FEREF=$(cat "$M/fe-ref-before.txt"); FER=${FEREF%%:*}\n'
    'if docker image inspect $FER:b5cd1d0 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $FER:b5cd1d0)" = "$FE0" ] || stop "$FER:b5cd1d0 태그가 이미 다른 이미지를 가리킨다 — U1-되돌림"; fi\n'
    'docker tag "$FE0" $FER:b5cd1d0\n'
    '[ "$(docker image inspect --format \'{{.Id}}\' $FER:b5cd1d0)" = "$FE0" ] || stop "frontend 기존 이미지 보존 태그 실패 — U1-되돌림"\n'
    '$DC build frontend > "$M/build-fe.log" 2>&1 || stop "frontend 빌드 실패 — build-fe.log 를 보고. U1-되돌림"\n'
    'FE1=$(docker image inspect --format \'{{.Id}}\' "$FEREF"); echo "$FE1" | tee "$M/image-fe-new.txt"\n'
    '[ "$FE1" != "$FE0" ] || stop "새 frontend 이미지가 만들어지지 않음 — U1-되돌림"\n'
    '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$FE0" ] || stop "🔴 실행 중 frontend 가 바뀌었다 — 보고"\n',
    'echo "== frontend(같은 릴리스) — 보존 태그 확인·새 이미지 빌드(재기동 없음)"\n'
    'same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U1 시작 때와 다름 또는 조회 실패 — U1-되돌림(보고)"\n'
    'echo 빌드시작 > "$M/fe-stage.txt"\n'
    '$DC build frontend > "$M/build-fe.log" 2>&1 || stop "frontend 빌드 실패 — build-fe.log 를 보고. U1-되돌림(이미지 이름은 보존 태그 대상으로 되돌린다)"\n'
    'FE1=$(docker image inspect --format \'{{.Id}}\' autotrader-frontend) || stop "새 frontend 이미지 조회 실패 — U1-되돌림"; echo "$FE1" | tee "$M/image-fe-new.txt"\n'
    '[ "$FE1" != "$FETOLD" ] || stop "새 frontend 이미지가 만들어지지 않음 — U1-되돌림"\n'
    'RF1=$(rchain "$FE1") || stop "새 frontend 이미지의 대상·manifest·config 조회 실패 — U1-되돌림"; echo "$RF1" | tee "$M/image-fe-new-chain.txt"\n'
    '[ "$(cut -d\' \' -f2 "$M/image-fe-new-chain.txt")" != "$FEMOLD" ] || stop "새 frontend manifest 가 구판과 같음 — U1-되돌림"\n'
    'echo 빌드끝 > "$M/fe-stage.txt"\n'
    'same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 빌드 중 바뀌었거나 조회 실패 — U1-되돌림(보고)"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "🔴 실행 중 frontend 가 바뀌었다 또는 조회 실패 — 보고"\n')

# ── U1되돌림: 전면 교체(바꾸기 전 대응 확인 → 단계별 이미지 이름 복구 → 실행 컨테이너 대조 뒤에만 완료) ──
OLD_U1R = B["U1되돌림"]
rep("U1되돌림", OLD_U1R, r'''# U1-되돌림(V171D4) — U1 이 '… U1-되돌림' 으로 멈췄거나, U1 통과 뒤 운영자가 U2 를 하지 않기로 했을 때만 쓴다(자동 실행 없음). 재기동하지 않는다.
# 대상은 HEAD=NEW 뿐이다(이미 OLD 이면 체크아웃 없이 이미지 이름·원문만 맞춘다). 그 밖 HEAD·추적 변경·실행 컨테이너 대응 불일치·cron 불일치·프로세스/이미지 조회 실패는 바꾸지 않고 멈춘다.
# V171D4: 이미지 이름은 U1 의 frontend 단계 기록(fe-stage.txt)과 대응 확인한 보존 태그로 되돌린다. 컨테이너 .Image(가변 latest 의 옛 값)를 복구 출처로 쓰지 않는다.
#   없음/보존태그 = frontend 빌드 전(이미지 이름이 바뀌었으면 제3의 이미지 — 멈춤) · 빌드시작 = 빌드 중/뒤 실패(바뀐 이름은 보존 태그로) · 빌드끝 = 새 이미지 기록됨(이름이 그 새 이미지일 때만 되돌림)
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V171D4 ] || stop "U 변수가 v1.171 통합 후보 초안 D4 판이 아님 — F → G → v1.171 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
[ ! -e "$M/ok-U2" ] && [ ! -e "$M/t-deploy.utc" ] || stop "U2(재기동)를 시작했다 — U1-되돌림이 아니라 U되돌림"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #27 이미지(IMGOLD)가 아님"
[ -s "$M/image-before-chain.txt" ] && [ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] || stop "U0 backend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"
[ -s "$M/image-fe-before-chain.txt" ] && [ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] && [ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "U0 frontend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"
[ -e "$M/start_66.py.before" ] || stop "U0 보존본이 없다 — 보고"
date '+%F %T %Z' | tee "$M/t-u1rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-u1rollback.txt"
H=$(head -1 "$M/git-pre-u1rollback.txt")
[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 후보·b5cd1d0 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"
[ "$(sed -n 2p "$M/git-pre-u1rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"
FS=$(cat "$M/fe-stage.txt" 2>/dev/null || echo 없음); echo "frontend 단계 기록: $FS" | tee "$M/fe-stage-u1rollback.txt"
case "$FS" in 없음|보존태그|빌드시작|빌드끝) ;; *) stop "frontend 단계 기록이 U1 단계가 아님($FS) — 바꾸지 않고 보고" ;; esac
echo "== 바꾸기 전 대응 확인 — 실행 컨테이너 두 개·이미지 이름·보존 태그"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 #27 이미지 대응이 아님 또는 조회 실패 — 재기동이 있었을 수 있다. 바꾸지 않고 보고"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패 — 바꾸지 않고 보고"
BL=0; same3 $REPO:latest "$M/image-before-chain.txt" 2>> "$M/u1rb-probe.txt" || BL=1
if [ "$BL" = 1 ]; then
  L=$(docker image inspect --format '{{.Id}}' $REPO:latest) || stop "backend latest 조회 실패 — 바꾸지 않고 보고"
  [ -s "$M/image-new.txt" ] && [ "$L" = "$(cat "$M/image-new.txt")" ] || stop "backend latest 가 U0 이미지·U1 새 이미지 어느 것도 아님($L) — 바꾸지 않고 보고"
  same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"
fi
FL=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" 2>> "$M/u1rb-probe.txt" || FL=1
if [ "$FL" = 1 ]; then
  case "$FS" in 빌드시작|빌드끝) ;; *) stop "frontend 이미지 이름이 U0 와 다른데 U1 이 frontend 빌드 전 단계($FS) — 제3의 이미지일 수 있다. 바꾸지 않고 보고" ;; esac
  FL1=$(docker image inspect --format '{{.Id}}' autotrader-frontend) || stop "frontend 이미지 이름 조회 실패 — 바꾸지 않고 보고"
  [ "$FS" != 빌드끝 ] || { [ -s "$M/image-fe-new.txt" ] && [ "$FL1" = "$(cat "$M/image-fe-new.txt")" ]; } || stop "frontend 이미지 이름이 U1 새 이미지가 아님($FL1, 제3의 이미지) — 바꾸지 않고 보고"
  echo "$FL1" | tee "$M/fe-latest-pre-u1rollback.txt"
  same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"
fi
echo "되돌릴 이미지 이름: backend latest $([ "$BL" = 1 ] && echo 보존 태그로 || echo 그대로) · frontend $([ "$FL" = 1 ] && echo 보존 태그로 || echo 그대로)" | tee "$M/u1rb-plan.txt"
crontab -l > "$M/crontab-u1rollback.txt" || stop "crontab 조회 실패 — 바꾸지 않고 보고"
[ "$(h256 "$M/crontab-u1rollback.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 바꾸지 않고 보고"
FI=0; fresh_idle u1rb || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다(아직 바꾼 것 없음) — 끝난 뒤 다시(끊지 않는다)"; stop "프로세스 조회 실패(아직 바꾼 것 없음) — 회차 없음으로 보지 않는다. pgrep-u1rb.* 를 보고"; }
if [ "$H" = "$NEW" ]; then git checkout --quiet $OLD; else echo "HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다"; fi
if [ "$BL" = 1 ]; then docker tag "$IMGOLD" $REPO:latest; fi
same3 $REPO:latest "$M/image-before-chain.txt" || stop "backend latest 원복 확인 실패 — 보고"
if [ "$FL" = 1 ]; then docker tag "$FETOLD" autotrader-frontend; fi
same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름 원복 확인 실패 — 보고"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 바뀌었다 또는 조회 실패 — 보고"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 바뀌었다 또는 조회 실패 — 보고"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; echo "backend latest $(ok3 "$M/image-before-chain.txt")"; echo "frontend latest $(ok3 "$M/image-fe-before-chain.txt")"; } | tee "$M/git-u1rollback.txt"
[ "$(head -1 "$M/git-u1rollback.txt")" = "$OLD" ] && [ "$(sed -n 2p "$M/git-u1rollback.txt")" = "(끝)" ] || stop "되돌림 결과가 b5cd1d0 가 아님 — 보고"
[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 시험 파일이 없다 — 보고"
rm -f "$M/ok-U1"; echo "↩ U1-되돌림 끝 — HEAD b5cd1d0·backend latest = #27(52e12428)·frontend 이미지 이름 = U0 대상(837db515), 실행 컨테이너 두 개 대응 그대로, 재기동 없음. 상태 파일(streak·snap·log)은 되감지 않았다"
)
''')

# ── U2 ──
rep("U2", '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:latest)" = "$IMG1" ] || stop "latest 가 image-new 와 다름"\n',
    '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:latest)" = "$IMG1" ] || stop "latest 가 image-new 와 다름"\n'
    'same3 $REPO:latest "$M/image-new-chain.txt" || stop "latest 가 image-new 대상·manifest·config 와 다름 또는 조회 실패(아직 바꾼 것 없음) — 보고"\n'
    'isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 #27 이미지 대응이 아님 또는 조회 실패(아직 바꾼 것 없음) — 보고"\n')
rep("U2", '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)")" = "$IMG1" ] || stop "되돌림 필요: 새 컨테이너 이미지가 image-new 와 다름"\n',
    'isimg backend "$(okimg "$M/image-new-chain.txt")" $(ok3 "$M/image-new-chain.txt") || stop "되돌림 필요: 새 컨테이너가 image-new 대상·manifest·config 와 연결되지 않음 또는 조회 실패"\n')

# ── U3: P0 검사를 가리지 않도록 이미지 대응 판정은 P0 줄 뒤에 멈춘다 ──
rep("U3", 'EXP=$(cat "$M/image-before.txt"); T=rollback;', 'EXPF="$M/image-before-chain.txt"; T=rollback;')
rep("U3", 'EXP=$(cat "$M/image-new.txt"); T=deploy;', 'EXPF="$M/image-new-chain.txt"; T=deploy;')
rep("U3", '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)")" = "$EXP" ] || stop "실행 이미지가 기대와 다름"\n',
    'IMGOK=0; isimg backend "$(okimg "$EXPF")" $(ok3 "$EXPF") || IMGOK=$?   # V171D4: 판정은 P0 검사 뒤\n')
rep("U3", '[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)"\n',
    '[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)"\n'
    '[ "$IMGOK" = 0 ] || stop "실행 이미지가 기대 대상·manifest·config 와 다름 또는 조회 실패"\n')

# ── UF2 ──
rep("UF2", 'FE0=$(cat "$M/image-fe-before.txt"); FE1=$(cat "$M/image-fe-new.txt"); FEREF=$(cat "$M/fe-ref-before.txt")\n'
    '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$FE0" ] || stop "실행 중 frontend 가 image-fe-before 가 아님 — 보고"\n'
    '[ "$(docker image inspect --format \'{{.Id}}\' "$FEREF")" = "$FE1" ] || stop "frontend 이미지 이름이 image-fe-new 를 가리키지 않음 — 보고"\n'
    '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)")" = "$(cat "$M/image-new.txt")" ] || stop "backend 가 새 이미지가 아님 — 보고"\n',
    '[ "$(cat "$M/fe-stage.txt" 2>/dev/null)" = 빌드끝 ] && [ -s "$M/image-fe-new-chain.txt" ] || stop "U1 frontend 단계 기록이 빌드끝이 아니거나 새 frontend 대응 기록 없음 — 보고"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패 — 보고"\n'
    'same3 autotrader-frontend "$M/image-fe-new-chain.txt" || stop "frontend 이미지 이름이 image-fe-new 대상·manifest·config 를 가리키지 않음 또는 조회 실패 — 보고"\n'
    'same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U0 대응과 다름 또는 조회 실패(복구 출처 없음) — 보고"\n'
    'isimg backend "$(okimg "$M/image-new-chain.txt")" $(ok3 "$M/image-new-chain.txt") || stop "backend 가 새 이미지 대응이 아님 또는 조회 실패 — 보고"\n')
rep("UF2", 'echo "== 여기부터 frontend 재생성 — 이 뒤의 멈춤은 \'frontend 되돌림 필요\'(U되돌림의 frontend 단계)"\n',
    'echo "== 여기부터 frontend 재생성 — 이 뒤의 멈춤은 \'frontend 되돌림 필요\'(U되돌림의 frontend 단계)"\n'
    'echo 교체시작 > "$M/fe-stage.txt"\n')
rep("UF2", '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$FE1" ] || stop "frontend 되돌림 필요: 새 컨테이너 이미지가 image-fe-new 와 다름"\n',
    'isimg frontend "$(okimg "$M/image-fe-new-chain.txt")" $(ok3 "$M/image-fe-new-chain.txt") || stop "frontend 되돌림 필요: 새 컨테이너가 image-fe-new 대상·manifest·config 와 연결되지 않음 또는 조회 실패"\n')
rep("UF2", 'touch "$M/ok-UF2"; echo "✅ UF2 통과', 'echo 교체끝 > "$M/fe-stage.txt"; touch "$M/ok-UF2"; echo "✅ UF2 통과')

# ── U되돌림 ──
rep("U되돌림", 'IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #27 이미지(IMGOLD)가 아님"\n',
    'IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #27 이미지(IMGOLD)가 아님"\n'
    '[ -s "$M/image-before-chain.txt" ] && [ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] || stop "U0 backend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"\n'
    '[ -s "$M/image-fe-before-chain.txt" ] && [ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] && [ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "U0 frontend 대응 기록이 없거나 고정값과 다름 — 바꾸지 않고 보고"\n')
rep("U되돌림", 'IMGR=$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)") || stop "실행 이미지 조회 실패 — 바꾸지 않고 보고"\n'
    '[ "$IMGR" = "$IMG0" ] || { [ -s "$M/image-new.txt" ] && [ "$IMGR" = "$(cat "$M/image-new.txt")" ]; } || stop "실행 이미지가 image-before·image-new 어느 것도 아님 — 다른 작업이 있었을 수 있다. 바꾸지 않고 보고"\n',
    'echo "== V171D4 — 바꾸기 전 실행 컨테이너 대응 판정(backend·frontend)과 보존 태그 확인"\n'
    'if isimg backend "$(okimg "$M/image-before-chain.txt")" $(ok3 "$M/image-before-chain.txt") 2>> "$M/rb-probe.txt"; then BR=구판\n'
    'elif [ -s "$M/image-new-chain.txt" ] && isimg backend "$(okimg "$M/image-new-chain.txt")" $(ok3 "$M/image-new-chain.txt") 2>> "$M/rb-probe.txt"; then BR=새판\n'
    'else cat "$M/rb-probe.txt" 2>/dev/null | tail -4; stop "실행 backend 가 image-before·image-new 대응 어느 것도 아니거나 조회 실패 — 다른 작업이 있었을 수 있다. 바꾸지 않고 보고"; fi\n'
    'if isimg frontend "$FEIMGOLD $(okimg "$M/image-fe-before-chain.txt")" $(ok3 "$M/image-fe-before-chain.txt") 2>> "$M/rb-probe.txt"; then FR=구판\n'
    'elif [ -s "$M/image-fe-new-chain.txt" ] && isimg frontend "$(okimg "$M/image-fe-new-chain.txt")" $(ok3 "$M/image-fe-new-chain.txt") 2>> "$M/rb-probe.txt"; then FR=새판\n'
    'else cat "$M/rb-probe.txt" 2>/dev/null | tail -4; stop "실행 frontend 가 U0·U1 대응 어느 것도 아니거나 조회 실패(제3의 이미지일 수 있다) — 바꾸지 않고 보고"; fi\n'
    'same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"\n'
    'same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"\n'
    'FN=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" 2>> "$M/rb-probe.txt" || FN=1\n'
    'if [ "$FN" = 1 ]; then FL1=$(docker image inspect --format \'{{.Id}}\' autotrader-frontend) || stop "frontend 이미지 이름 조회 실패 — 바꾸지 않고 보고"; [ -s "$M/image-fe-new.txt" ] && [ "$FL1" = "$(cat "$M/image-fe-new.txt")" ] || stop "frontend 이미지 이름이 U0·U1 이미지 어느 것도 아님($FL1) — 바꾸지 않고 보고"; fi\n'
    'echo "실행 판정 backend=$BR frontend=$FR · frontend 이미지 이름 $([ "$FN" = 1 ] && echo U1새판 || echo U0) · 단계 $(cat "$M/fe-stage.txt" 2>/dev/null || echo 없음)" | tee "$M/rb-identity.txt"\n')
rep("U되돌림", 'if [ -s "$M/image-fe-new.txt" ] && [ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$(cat "$M/image-fe-new.txt")" ]; then\n'
    '  FE0=$(cat "$M/image-fe-before.txt"); docker tag "$FE0" "$(cat "$M/fe-ref-before.txt")"\n'
    '  $DC up -d --no-build --no-deps --force-recreate frontend\n'
    '  sleep 10\n'
    '  [ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$FE0" ] || stop "frontend 이미지가 image-fe-before 와 다름"\n'
    'else echo "frontend 는 구판 그대로 — 바꾸지 않는다"; fi\n',
    'if [ "$FR" = 새판 ]; then\n'
    '  docker tag "$FETOLD" autotrader-frontend   # 대응 확인한 보존 대상 ID 로 이미지 이름을 되돌린다\n'
    '  same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름 원복 확인 실패 — 보고"\n'
    '  $DC up -d --no-build --no-deps --force-recreate frontend\n'
    '  sleep 10\n'
    '  isimg frontend "$(okimg "$M/image-fe-before-chain.txt")" $(ok3 "$M/image-fe-before-chain.txt") || stop "frontend 가 image-fe-before 대상·manifest·config 와 연결되지 않음 또는 조회 실패"\n'
    '  echo "frontend 복구 — 이미지 이름·실행 컨테이너 모두 U0 대상(837db515)" | tee "$M/fe-restored.txt"\n'
    'elif [ "$FN" = 1 ]; then\n'
    '  docker tag "$FETOLD" autotrader-frontend\n'
    '  same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름 원복 확인 실패 — 보고"\n'
    '  echo "frontend 실행은 구판 그대로 — 이미지 이름만 U0 대상으로 되돌렸다(재생성 없음)" | tee "$M/fe-restored.txt"\n'
    'else echo "frontend 는 구판 그대로 — 바꾸지 않는다" | tee "$M/fe-restored.txt"; fi\n')
rep("U되돌림", 'docker tag "$IMG0" $REPO:latest\n$DC up -d --no-build --no-deps --force-recreate backend\nsleep 15\n'
    '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)")" = "$IMG0" ] || stop "이미지가 image-before 와 다름"\n',
    'docker tag "$IMGOLD" $REPO:latest\n'
    'same3 $REPO:latest "$M/image-before-chain.txt" || stop "backend latest 원복 확인 실패 — 보고"\n'
    '$DC up -d --no-build --no-deps --force-recreate backend\nsleep 15\n'
    'isimg backend "$(okimg "$M/image-before-chain.txt")" $(ok3 "$M/image-before-chain.txt") || stop "이미지가 image-before 대상·manifest·config 와 다름 또는 조회 실패"\n')

# ── U뒤관측 ──
rep("U뒤관측", '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)")" = "$(cat "$M/image-new.txt")" ] || stop "실행 이미지가 image-new 와 다름 — 보고"\n',
    'isimg backend "$(okimg "$M/image-new-chain.txt")" $(ok3 "$M/image-new-chain.txt") || stop "실행 이미지가 image-new 대상·manifest·config 와 다름 또는 조회 실패 — 보고"\n')

# ── 남은 옛 표기·직접 비교 확인 ──
for k, s in B.items():
    for bad in ["maint-v168", "UB=V168D4", '= V168D4 ]', "7df5758f", "v1.168 통합 후보 초안"]:
        if bad in s:
            sys.exit("%s: 옛 표기 남음 %s" % (k, bad))
    if "{{.Image}}' \"$($DC ps -q" in s and k not in ("U0",):
        sys.exit("%s: 실행 컨테이너 .Image 직접 비교가 남음" % k)
out.mkdir(parents=True, exist_ok=True)
for k, s in B.items():
    (out / (k + ".sh")).write_bytes(s.encode("utf-8"))
    print(hashlib.sha256(s.encode("utf-8")).hexdigest(), k + ".sh")
print("ICSUM", ICSUM)
