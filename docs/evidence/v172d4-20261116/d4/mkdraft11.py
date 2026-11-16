# V172D4 생성기(총괄 v1.172 / 423차 §3 R1~R3) — V171D4 원문(1644851b:docs/evidence/v171d4-20261116/d4/blocks-d4)에서
# 지정한 부분만 바꾼다. 바꾸는 곳마다 출현 횟수를 확인하고, 다르면 멈춘다. 끝에 옛 판 표기·옛 보존 태그 조회 형태·
# P0 검사 앞의 차단 조회·latest 무조건 덮어쓰기가 남으면 멈춘다. imgchain.py·JARM·관문·조회문은 바꾸지 않는다.
# 사용: python -I mkdraft11.py <V171D4 원문 폴더> <출력 폴더>
import hashlib, pathlib, re, sys

src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
SRCSUM = {  # 1644851b 블롭 해시(v171d4-20261116/d4/blocks-d4.sha256 · runner EXP 와 같은 값)
    "U변수": "ede1ac55cc49c4b2eef49686e9915132dfa2a25f1c4dc904f4a129fda965a7bc",
    "U0": "6fd5b7f4fc0614c5b8ae979d3cfc80fbf00976bbac76141d08b8313a3db87ef1",
    "U1": "d9f038555445b410c45b104c1eec61bda1fcf62d69f90e60315bfba8dd33973e",
    "U1되돌림": "e5ff25c7e97298b3736bdd06a0aac873a853a6c614f904dfc42f319c5eb3d7da",
    "U2": "ae0b2d774ec09c1059df5955fdc169d54cc9409cf100e2e3656c43e1ca1ba905",
    "U3": "e50069bd22aecfb1b8a510067afba49dec82e10b18bc4e0590df4f9b14028093",
    "UF2": "18cfcd27e805aa0ea3c6861228bbf48576e0a1de656cbe6d1646ea73dd74a8ce",
    "U4": "7a4b4cf26478e2931ec92918941612537b3413c07365478d08bd0de738a2e30a",
    "U되돌림": "84ecc373a8a3cb29bf44785f95ce46526570caa4d26ccb8be10f7f6baffe766c",
    "U뒤관측": "9afe1f91358a07bd54ec7701ef0fc817bb62c158b6926c5e82fc586db05b98b0",
}
B = {}
for k, h in SRCSUM.items():
    b = (src / (k + ".sh")).read_bytes()
    if hashlib.sha256(b).hexdigest() != h or b"\r" in b:
        sys.exit("원문 해시 다름 또는 CR 포함: " + k)
    B[k] = b.decode("utf-8")


def rep(k, old, new, n=1):
    c = B[k].count(old)
    if c != n:
        sys.exit("%s: 바꿀 문자열 %d회(기대 %d): %s" % (k, c, n, old[:80]))
    B[k] = B[k].replace(old, new)


# ── 판 표지(모든 블록) ──
for k in ["U0", "U1", "U1되돌림", "U2", "U3", "U4", "UF2", "U되돌림", "U뒤관측"]:
    rep(k, "v1.171 통합 후보 초안 D4", "v1.172 통합 후보 초안 D4", 1 if k == "U0" else 2)
    if k != "U0":
        rep(k, '[ "${UB:-}" = V171D4 ]', '[ "${UB:-}" = V172D4 ]')
rep("U0", '[ "$M" = "$HOME/maint-v171" ] && [ "${UB:-}" = V171D4 ]', '[ "$M" = "$HOME/maint-v172" ] && [ "${UB:-}" = V172D4 ]')

# ── U변수: 판 표지·same3 세 갈래·tagq/tagkeep/namecls ──
rep("U변수", 'M=~/maint-v171; mkdir -p "$M"; OPS=~/ops-scripts; UB=V171D4   # v1.171 판(',
    'M=~/maint-v172; mkdir -p "$M"; OPS=~/ops-scripts; UB=V172D4   # v1.172 판(V171D4 + 보존 태그 조회 세 갈래·복구 전 이름 출처 판정·P0 검사 우선, 총괄 v1.172 §3 R1~R3, 생성기 mkdraft11.py) ← v1.171 판(')
rep("U변수", "블록이 ~/maint-v171/imgchain.py 로 쓰고", "블록이 ~/maint-v172/imgchain.py 로 쓰고")
OLD_SAME3 = '''same3() {   # same3 <이미지 참조> <대응 기록 파일> — 참조의 대상·manifest·config 가 기록과 같은지(조회 실패는 다름)
  local r
  [ -s "$2" ] && r=$(rchain "$1") || return 1
  [ "$(echo "$r" | cut -d' ' -f1-3)" = "$(ok3 "$2")" ]
}
'''
NEW_SAME3 = r'''# ── V172D4(총괄 v1.172 §3 R1·R2) — 조회 실패를 다른 상태(태그 없음·원판 아님)로 추정하지 않는다 ──
same3() {   # same3 <이미지 참조> <대응 기록 파일> → 0 같음 · 1 대응 다름(조회는 성공) · 2 조회 불명(기록 없음·이름/API/save 조회 실패)
  local r
  [ -s "$2" ] || { echo "🔴 대응 기록 없음 $2" >&2; return 2; }
  r=$(rchain "$1") || { echo "🔴 $1 대응 조회 불명" >&2; return 2; }
  [ "$(echo "$r" | cut -d' ' -f1-3)" = "$(ok3 "$2")" ] || { echo "🔴 $1 대응이 기록($2)과 다름" >&2; return 1; }
}
tagq() {   # tagq <태그 참조> → 0 있음(API 200·Id) · 1 명시적 부재(API 404·본문 {"message":"No such image: <참조>"}) · 2 조회 불명(소켓·권한·daemon·API·형식)
  # 운영 응답 형식(읽기 2026-11-16 12:58 KST, docs/evidence/v172d4-20261116/state-1258): 없음 404 위 본문 · 있음 200 {"Id":"sha256:…" · 소켓 실패 curl 종료 7
  local o c
  o=$(curl -s -w '\n%{http_code}' --unix-socket /var/run/docker.sock "http://localhost/$DAPI/images/$1/json") || { c=$?; echo "🔴 $1 태그 조회 불명(curl 종료 $c)" >&2; return 2; }
  c=${o##*$'\n'}; o=${o%$'\n'*}; o=${o%$'\n'}
  if [ "$c" = 200 ] && [[ "$o" =~ ^\{\"Id\":\"sha256:[0-9a-f]{64}\" ]]; then return 0; fi
  if [ "$c" = 404 ] && [ "$o" = "{\"message\":\"No such image: $1\"}" ]; then return 1; fi
  echo "🔴 $1 태그 조회 불명(HTTP $c ${o:0:120})" >&2; return 2
}
tagkeep() {   # tagkeep <보존 태그> <대응 기록 파일> → 0 있음·기록과 같음 · 1 명시적 부재 · 2 다른 대상을 가리킴 · 3 조회 불명
  local q=0 s=0
  tagq "$1" || q=$?
  case "$q" in
    0) same3 "$1" "$2" || s=$?; case "$s" in 0) return 0 ;; 1) return 2 ;; *) return 3 ;; esac ;;
    1) return 1 ;;
    *) return 3 ;;
  esac
}
namecls() {   # namecls <이미지 이름> <원판 대응 기록> <이번 빌드 대응 기록(없을 수 있음)> → 표준 출력 원판|새판|제3. 조회 불명은 출력 없이 종료 2
  # 새판 = U1 이 그 빌드 직후 대응을 확인해 기록한 대상·manifest·config 와 같을 때만. 빌드 실패로 기록이 없는데 이름이 바뀌었으면 제3(출처 미확인)
  local r
  [ -s "$2" ] || { echo "🔴 원판 대응 기록 없음 $2" >&2; return 2; }
  r=$(rchain "$1") || { echo "🔴 $1 대응 조회 불명 — 원판·새판으로 추정하지 않는다" >&2; return 2; }
  r=$(echo "$r" | cut -d' ' -f1-3)
  if [ "$r" = "$(ok3 "$2")" ]; then echo 원판
  elif [ -s "$3" ] && [ "$r" = "$(ok3 "$3")" ]; then echo 새판
  else echo "🔴 $1 대상 ${r:7:12}… 은 원판·이번 빌드 기록 어느 것도 아님(제3의 대상 또는 출처 미확인)" >&2; echo 제3; fi
}
'''
rep("U변수", OLD_SAME3, NEW_SAME3)


def tagcase(var, ref, rec, what):
    return ('%s=0; tagkeep %s "%s" || %s=$?   # V172D4 R1: 0 있음·같음 / 1 명시적 부재(API 404) / 2 다른 대상 / 3 조회 불명\n'
            'case "$%s" in 0) echo "보존 태그 %s 있음 — %s 대응과 같음" ;; 1) echo "보존 태그 %s 없음(API 404 확인) — U1 이 만든다" ;; '
            '2) stop "보존 태그 %s 이 이미 다른 이미지를 가리킨다 — 보고" ;; '
            '*) stop "보존 태그 %s 조회 불명(권한·daemon·API·형식) — 없음으로 보지 않는다. 보고" ;; esac') % (
        var, ref, rec, var, var, ref, what, ref, ref, ref)


# ── U0: R1 보존 태그 세 갈래, 앞 시도의 이번 빌드 기록 정리 ──
rep("U0", 'if docker image inspect $REPO:b5cd1d0 > /dev/null 2>&1; then same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "보존 태그 $REPO:b5cd1d0 이 이미 다른 이미지를 가리킨다 — 보고"; fi',
    tagcase("TKB", "$REPO:b5cd1d0", "$M/image-before-chain.txt", "#27 backend"))
rep("U0", 'if docker image inspect autotrader-frontend:b5cd1d0 > /dev/null 2>&1; then same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "보존 태그 autotrader-frontend:b5cd1d0 이 이미 다른 이미지를 가리킨다 — 보고"; fi',
    tagcase("TKF", "autotrader-frontend:b5cd1d0", "$M/image-fe-before-chain.txt", "U0 frontend"))
rep("U0", 'touch "$M/ok-U0"; echo "✅ U0 통과',
    'rm -f "$M/image-new.txt" "$M/image-new-chain.txt" "$M/image-fe-new.txt" "$M/image-fe-new-chain.txt"   # V172D4 R2: 이번 빌드판 기록은 이 U0 뒤의 U1 만 쓴다(앞 시도 기록을 이번 빌드로 오인하지 않게, 모든 확인 통과 뒤에만 지운다)\n'
    'touch "$M/ok-U0"; echo "✅ U0 통과')

# ── U1: R1 두 보존 태그를 모두 판정한 뒤에만 쓴다, 있으면 다시 쓰지 않는다 ──
OLD_U1TAG = '''if docker image inspect $REPO:b5cd1d0 > /dev/null 2>&1; then same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "$REPO:b5cd1d0 태그가 이미 다른 이미지를 가리키거나 조회 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi
if docker image inspect autotrader-frontend:b5cd1d0 > /dev/null 2>&1; then same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "autotrader-frontend:b5cd1d0 태그가 이미 다른 이미지를 가리키거나 조회 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"; fi
docker tag "$IMGOLD" $REPO:b5cd1d0
same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "기존 backend 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"
docker tag "$FETOLD" autotrader-frontend:b5cd1d0
'''
NEW_U1TAG = '''BK=0; tagkeep $REPO:b5cd1d0 "$M/image-before-chain.txt" || BK=$?   # V172D4 R1: 두 보존 태그를 모두 판정한 뒤에만 쓴다
FK=0; tagkeep autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || FK=$?
echo "보존 태그 판정 backend=$BK frontend=$FK (0 있음·같음 / 1 명시적 부재 / 2 다른 대상 / 3 조회 불명)" | tee "$M/tag-keep-u1.txt"
case "$BK" in 0|1) ;; 2) stop "$REPO:b5cd1d0 태그가 이미 다른 이미지를 가리킨다(HEAD·latest·태그 그대로, U1-되돌림 하지 않는다)" ;; *) stop "$REPO:b5cd1d0 태그 조회 불명 — 없음으로 보지 않는다(HEAD·latest·태그 그대로, U1-되돌림 하지 않는다)" ;; esac
case "$FK" in 0|1) ;; 2) stop "autotrader-frontend:b5cd1d0 태그가 이미 다른 이미지를 가리킨다(HEAD·latest·태그 그대로, U1-되돌림 하지 않는다)" ;; *) stop "autotrader-frontend:b5cd1d0 태그 조회 불명 — 없음으로 보지 않는다(HEAD·latest·태그 그대로, U1-되돌림 하지 않는다)" ;; esac
[ "$BK" = 0 ] || docker tag "$IMGOLD" $REPO:b5cd1d0   # 명시적 부재일 때만 만든다
same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "기존 backend 이미지 보존 태그 실패(HEAD·latest 그대로, U1-되돌림 하지 않는다)"
[ "$FK" = 0 ] || docker tag "$FETOLD" autotrader-frontend:b5cd1d0
'''
rep("U1", OLD_U1TAG, NEW_U1TAG)

# ── U1되돌림: R2 두 이름의 출처(원판·이번 빌드판·제3·불명)를 판정한 뒤에만 바꾼다 ──
rep("U1되돌림", "# U1-되돌림(V171D4) —", "# U1-되돌림(V172D4 — V171D4 에 총괄 v1.172 R2 보완) —")
OLD_RB1 = '''BL=0; same3 $REPO:latest "$M/image-before-chain.txt" 2>> "$M/u1rb-probe.txt" || BL=1
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
'''
NEW_RB1 = '''echo "== V172D4 R2 — 두 이름의 출처 판정(원판·이번 빌드판·제3·조회 불명). 두 서비스 판정을 끝내기 전에는 HEAD·태그를 바꾸지 않는다"
BN=$(namecls $REPO:latest "$M/image-before-chain.txt" "$M/image-new-chain.txt" 2>> "$M/u1rb-probe.txt") || { tail -3 "$M/u1rb-probe.txt"; stop "backend latest 대응 조회 불명 — 원판·새판으로 추정하지 않는다. 바꾸지 않고 보고"; }
FN=$(namecls autotrader-frontend "$M/image-fe-before-chain.txt" "$M/image-fe-new-chain.txt" 2>> "$M/u1rb-probe.txt") || { tail -3 "$M/u1rb-probe.txt"; stop "frontend 이미지 이름 대응 조회 불명 — 원판·새판으로 추정하지 않는다. 바꾸지 않고 보고"; }
echo "이름 출처 판정 backend latest=$BN · frontend=$FN · frontend 단계 $FS" | tee "$M/u1rb-names.txt"
[ "$BN" != 제3 ] || { tail -3 "$M/u1rb-probe.txt"; stop "backend latest 가 U0 원판·U1 이번 빌드 기록(image-new-chain) 어느 것도 아님 — 출처 미확인, 바꾸지 않고 보고"; }
[ "$FN" != 제3 ] || { tail -3 "$M/u1rb-probe.txt"; stop "frontend 이미지 이름이 U0 원판·U1 이번 빌드 기록(image-fe-new-chain) 어느 것도 아님 — 빌드시작 뒤 기록 없는 변경이면 출처 미확인. 바꾸지 않고 보고"; }
case "$FN:$FS" in 원판:*|새판:빌드시작|새판:빌드끝) ;; *) stop "frontend 이미지 이름이 이번 빌드판인데 단계 기록이 빌드 전($FS) — 바꾸지 않고 보고" ;; esac
BL=0; [ "$BN" = 원판 ] || BL=1
FL=0; [ "$FN" = 원판 ] || FL=1
if [ "$BL" = 1 ]; then same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"; fi
if [ "$FL" = 1 ]; then same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "frontend 보존 태그가 U0 대응과 다름 또는 조회 실패 — 바꾸지 않고 보고"; fi
'''
rep("U1되돌림", OLD_RB1, NEW_RB1)

# ── U되돌림: R2 backend latest 도 판정, 이번 빌드판일 때만 되돌린다 ──
OLD_RB = '''FN=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" 2>> "$M/rb-probe.txt" || FN=1
if [ "$FN" = 1 ]; then FL1=$(docker image inspect --format '{{.Id}}' autotrader-frontend) || stop "frontend 이미지 이름 조회 실패 — 바꾸지 않고 보고"; [ -s "$M/image-fe-new.txt" ] && [ "$FL1" = "$(cat "$M/image-fe-new.txt")" ] || stop "frontend 이미지 이름이 U0·U1 이미지 어느 것도 아님($FL1) — 바꾸지 않고 보고"; fi
echo "실행 판정 backend=$BR frontend=$FR · frontend 이미지 이름 $([ "$FN" = 1 ] && echo U1새판 || echo U0) · 단계 $(cat "$M/fe-stage.txt" 2>/dev/null || echo 없음)" | tee "$M/rb-identity.txt"
'''
NEW_RB = '''echo "== V172D4 R2 — 두 이름의 출처 판정(원판·이번 빌드판·제3·조회 불명). 두 서비스 판정을 끝내기 전에는 HEAD·태그·컨테이너를 바꾸지 않는다"
BN=$(namecls $REPO:latest "$M/image-before-chain.txt" "$M/image-new-chain.txt" 2>> "$M/rb-probe.txt") || { tail -3 "$M/rb-probe.txt"; stop "backend latest 대응 조회 불명 — 원판·새판으로 추정하지 않는다. 바꾸지 않고 보고"; }
FNM=$(namecls autotrader-frontend "$M/image-fe-before-chain.txt" "$M/image-fe-new-chain.txt" 2>> "$M/rb-probe.txt") || { tail -3 "$M/rb-probe.txt"; stop "frontend 이미지 이름 대응 조회 불명 — 원판·새판으로 추정하지 않는다. 바꾸지 않고 보고"; }
[ "$BN" != 제3 ] || { tail -3 "$M/rb-probe.txt"; stop "backend latest 가 U0 원판·U1 이번 빌드판 어느 것도 아님(제3의 대상) — 바꾸지 않고 보고"; }
[ "$FNM" != 제3 ] || { tail -3 "$M/rb-probe.txt"; stop "frontend 이미지 이름이 U0 원판·U1 이번 빌드판 어느 것도 아님(제3의 대상) — 바꾸지 않고 보고"; }
FN=0; [ "$FNM" = 원판 ] || FN=1
echo "실행 판정 backend=$BR frontend=$FR · frontend 이미지 이름 $([ "$FN" = 1 ] && echo U1새판 || echo U0) · 단계 $(cat "$M/fe-stage.txt" 2>/dev/null || echo 없음) · backend latest $BN" | tee "$M/rb-identity.txt"
'''
rep("U되돌림", OLD_RB, NEW_RB)
rep("U되돌림", 'docker tag "$IMGOLD" $REPO:latest\n',
    'if [ "$BN" = 새판 ]; then docker tag "$IMGOLD" $REPO:latest; fi   # V172D4 R2: 이번 빌드판으로 확인된 latest 만 되돌린다(원판이면 그대로)\n')


# ── U3·U4·U뒤관측: R3 기동 구간 로그를 먼저 보존하고 P0 를 검사한다 ──
def p0first(tag, log, hq):
    return ('echo "== V172D4 R3 — P0 먼저: 기동 구간 로그 보존 → 연결 획득 시간 초과 검사(이미지 대응·뉴스 DB 조회는 그 뒤)"\n'
            '$DC logs --no-color --since "$S" backend > "%s" || stop "🔴 P0 검사 미완료 — 기동 구간 로그를 확보하지 못함(로그 조회 실패). 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"\n'
            '[ -s "%s" ] || stop "🔴 P0 검사 미완료 — 기동 구간 로그가 비었다. 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"\n'
            'holdobs "%s" > "$M/holdobs-%s-p0.txt" && %s=0 || %s=$?\n'
            '[ "$%s" != 1 ] || { cat "$M/holdobs-%s-p0.txt"; stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)"; }\n'
            '[ "$%s" = 0 ] || [ "$%s" = 3 ] || { cat "$M/holdobs-%s-p0.txt"; stop "🔴 P0 검사 미완료 — holdobs 종료 $%s. 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"; }\n') % (
        log, log, log, tag, hq, hq, hq, tag, hq, hq, tag, hq)


rep("U3", '''$DC ps backend
IMGOK=0; isimg backend "$(okimg "$EXPF")" $(ok3 "$EXPF") || IMGOK=$?   # V171D4: 판정은 P0 검사 뒤
''', p0first("$T", "$M/app-$T-p0.log", "HP0") + '''$DC ps backend
IMGOK=0; isimg backend "$(okimg "$EXPF")" $(ok3 "$EXPF") || IMGOK=$?   # V171D4: 판정은 P0 검사 뒤
''')
rep("U3", '$DC logs --no-color --since "$S" backend > "$M/app-$T.log"\n',
    '$DC logs --no-color --since "$S" backend > "$M/app-$T.log" || stop "기동 구간 로그 다시 읽기 실패(P0 1차 검사는 통과) — 보고"\n')
rep("U4", '''date '+%F %T %Z' | tee "$M/t-after30.txt"
$DC ps backend
''', '''date '+%F %T %Z' | tee "$M/t-after30.txt"
''' + p0first("after30", "$M/app-after30-p0.log", "HP0") + '''$DC ps backend
''')
rep("U4", '$DC logs --no-color --since "$S" backend > "$M/app-after30.log"\n',
    '$DC logs --no-color --since "$S" backend > "$M/app-after30.log" || stop "기동 뒤 30분 로그 다시 읽기 실패(P0 1차 검사는 통과) — 보고"\n')
rep("U뒤관측", '''S=$(cat "$M/t-deploy.utc"); TS=$(date '+%Y%m%d-%H%M')
''', '''S=$(cat "$M/t-deploy.utc"); TS=$(date '+%Y%m%d-%H%M')
''' + p0first("$TS", "$M/app-$TS-p0.log", "HP0"))

# ── 추가 보완(총괄 v1.172 추가 검토 메모 review-1644851b/postlog) — 두 번째 로그 검사도 P0·판정 실패면 즉시 멈춘다 ──
HQX = 'stop "🔴 P0 검사 미완료 — 두 번째 로그 holdobs 종료 $HQ(판정 실패). 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"'
for k in ["U3", "U4"]:
    rep(k, '[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)"\n',
        '[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)"\n'
        '[ "$HQ" = 0 ] || [ "$HQ" = 3 ] || ' + HQX + '\n')
rep("U뒤관측", '''echo "== 기동 뒤 HOLD 질의 장기 대여·연결 획득(N13 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-news-$TS.log" | tee "$M/holdobs-$TS.txt" && HQ=0 || HQ=$?
case "$HQ" in 0) ;; 3) echo "⚪ 장기 대여 경고 있음 — 개선 미확인, 위 줄을 보고" ;; *) echo "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고" ;; esac
''', '')
rep("U뒤관측", '$DC logs --no-color --since "$S" backend > "$M/app-news-$TS.log"\n',
    '''$DC logs --no-color --since "$S" backend > "$M/app-news-$TS.log" || stop "🔴 P0 검사 미완료 — 두 번째 로그를 확보하지 못함(로그 조회 실패). 즉시 보고"
[ -s "$M/app-news-$TS.log" ] || stop "🔴 P0 검사 미완료 — 두 번째 로그가 비었다. 즉시 보고"
echo "== 기동 뒤 HOLD 질의 장기 대여·연결 획득(N13, 두 번째 로그 — 첫 검사 뒤 새로 나타난 줄 포함 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-news-$TS.log" | tee "$M/holdobs-$TS.txt" && HQ=0 || HQ=$?
case "$HQ" in 0) ;; 3) echo "⚪ 장기 대여 경고 있음 — 개선 미확인, 위 줄을 보고" ;; 1) stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다(두 번째 로그). 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)" ;; *) ''' + HQX + ''' ;; esac
''')

# ── 끝 확인 ──
for k, s in B.items():
    bad = [x for x in ["maint-v171", "= V171D4 ]", "UB=V171D4", "v1.171 통합 후보 초안 D4", "if docker image inspect"] if x in s]
    if bad:
        sys.exit("%s: 옛 표기·옛 조회 형태 남음 %s" % (k, bad))
for k, logp in [("U3", '"$M/app-$T-p0.log"'), ("U4", '"$M/app-after30-p0.log"'), ("U뒤관측", '"$M/app-$TS-p0.log"')]:
    s = B[k]
    i = s.index("holdobs " + logp)
    for blocker in ['$PSQL -At -c "$NEWSCFG"', "isimg backend", "$DC ps backend"]:
        if blocker in s and s.index(blocker) < i:
            sys.exit("%s: P0 검사 앞에 차단 조회 %s" % (k, blocker))
for k, logp in [("U3", '"$M/app-$T.log"'), ("U4", '"$M/app-after30.log"'), ("U뒤관측", '"$M/app-news-$TS.log"')]:
    s = B[k]
    if s.count("holdobs " + logp) != 1 or s.count("판정 실패). 연결 획득 실패를 0 으로 보지 않는다") != 1:
        sys.exit("%s: 두 번째 로그 holdobs 판정 실패 멈춤이 없음" % k)
    if 'echo "🔴 P0' in s:
        sys.exit("%s: P0 를 출력만 하고 계속하는 줄이 남음" % k)
u = B["U뒤관측"]
if not (u.index('holdobs "$M/app-news-$TS.log"') < u.index("newsobs ")):
    sys.exit("U뒤관측: 두 번째 로그 P0 검사가 다른 관측보다 뒤")
if re.search(r'^docker tag "\$IMGOLD" \$REPO:latest', B["U되돌림"], re.M) or B["U되돌림"].count('docker tag "$IMGOLD" $REPO:latest') != 1:
    sys.exit("U되돌림: backend latest 무조건 덮어쓰기가 남음")
u1 = B["U1"]
if not (u1.index("FK=0; tagkeep") < u1.index('docker tag "$IMGOLD" $REPO:b5cd1d0') < u1.index('docker tag "$FETOLD" autotrader-frontend:b5cd1d0')):
    sys.exit("U1: 두 보존 태그 판정이 첫 태그 쓰기보다 앞이 아님")
for k in ["U1되돌림", "U되돌림"]:
    s = B[k]
    first_change = min(s.index(x) for x in ["git checkout", "docker tag", "$DC up"] if x in s)
    if not (s.index("BN=$(namecls") < first_change and s.index("namecls autotrader-frontend") < first_change):
        sys.exit("%s: 두 이름 판정이 첫 변경보다 앞이 아님" % k)
out.mkdir(parents=True, exist_ok=False)
for k, s in B.items():
    (out / (k + ".sh")).write_bytes(s.encode("utf-8"))
    print("%-8s %s" % (k, hashlib.sha256(s.encode("utf-8")).hexdigest()))
