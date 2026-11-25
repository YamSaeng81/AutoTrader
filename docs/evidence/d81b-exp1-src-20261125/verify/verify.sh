#!/bin/bash
# D81B-EXP1 최소 파일 검증(총괄 v1.247 §2-6). 서버·ssh·PATH 치환 없음. 원문에서 필요한 핀 검사 줄만 잘라 로컬 변수·임시 폴더로 실행한다.
# 이 결과는 U0 전체 실행이나 서버 동작 검증이 아니다.
# 사용: bash verify.sh pins|jar|hash <작업 폴더(없는 경로)> [출처 커밋 40자(hash 단계)]
set -u
R='D:/Claude Code/projects/crypto-auto-trader'; N="$R/docs/evidence/d81b-exp1-src-20261125"; B="$N/d4/blocks-d81"
OLDSRC=794480ea1041714c6f531d32c8051f9982f01609; OB=docs/evidence/d81a2-exp1-src-20261125/d4/blocks-d81
phase=${1:?pins|jar|hash}; W=${2:?작업 폴더}
[ ! -e "$W" ] || { echo "작업 폴더 $W 가 이미 있다 — 멈춤"; exit 2; }
mkdir -p "$W"; fail=0
ok() { echo "  [통과] $*"; }; ng() { echo "  [실패] $*"; fail=1; }
uvars() {   # U변수에서 핀 검사에 필요한 정의만(1·2·3행, JARN·JARM 줄, RARX 정의 구간)
  sed -n '1,3p' "$1"; grep -E '^(JARN|JARM)=' "$1"; awk '/^RARX=/{f=1} f{print} f&&/\)"   # /{exit}' "$1"; }
if [ "$phase" = pins ]; then
  git -C "$R" show "$OLDSRC:$OB/U변수.sh" > "$W/old-U변수.sh"
  STUB='ICSUM=x; BTCIN=x; for f in v96h pf3ok oldtag isimg same3 okimg fresh_idle n21mark n21after sid203 newsobs httpobs holdobs entryobs n17obs n19obs n20obs; do eval "$f() { :; }"; done'
  U0P=$(sed -n '3,7p' "$B/U0.sh")   # stop 정의 · 핀 검사 · IMGOLD 형식 · 경로 존재 거절 · mkdir
  run() {   # $1 사례 이름, $2 U변수 파일, $3 덧쓰기 변수(옛 값 혼입)
    local h="$W/home-$1"; mkdir "$h"
    ( export HOME="$h"; set -euo pipefail; eval "$STUB"; eval "$(uvars "$2")"; eval "${3:-}"; eval "$U0P"; echo "핀 통과 M=$M" ) > "$W/pins-$1.txt" 2>&1; local rc=$?
    local made; made=$(cd "$h" && ls -A | tr '\n' ' ')
    echo "  사례 $1 rc=$rc 만든 경로=[${made}] | $(tail -1 "$W/pins-$1.txt" | cut -c1-120)"; RC=$rc; MADE=$made; }
  echo "== 1. 새 변수 + U0 핀(경로·NEW·JARM·JARN)"
  run new "$B/U변수.sh"; [ "$RC" = 0 ] && [ "$MADE" = "maint-d81b-20261127-exp1 " ] && ok "새 변수 핀 통과, 새 기록 경로만 생성" || ng "새 변수"
  echo "== 2. 옛 d81 값 하나씩 혼입 — 거절·후속 mkdir 없음"
  run mixNEW "$B/U변수.sh" 'NEW=d81c3f2f91c0239aacb0e1176dde9b898f4d7466'
  [ "$RC" = 1 ] && [ -z "$MADE" ] && ok "옛 NEW 거절" || ng "옛 NEW"
  run mixJARM "$B/U변수.sh" 'JARM=33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509'
  [ "$RC" = 1 ] && [ -z "$MADE" ] && ok "옛 JARM 거절" || ng "옛 JARM"
  run mixJARN "$B/U변수.sh" 'JARN="(끝) 895 898"'
  [ "$RC" = 1 ] && [ -z "$MADE" ] && ok "옛 JARN 거절" || ng "옛 JARN"
  run mixM "$B/U변수.sh" 'M=~/maint-d81-20261127-exp1'
  [ "$RC" = 1 ] && [ -z "$MADE" ] && ok "옛 기록 경로 거절" || ng "옛 경로"
  run oldU "$W/old-U변수.sh"
  [ "$RC" = 1 ] && [ -z "$MADE" ] && ok "794480ea U변수 통째 거절" || ng "옛 U변수 전체"
elif [ "$phase" = jar ]; then
  OJ=/d/tmp/v171/jars/srv-e25c2a85-app-new.jar; BJ=/d/tmp/final-lf-1b719645/web-api/build/libs/web-api-0.1.0.jar; DJ=/d/tmp/final-lf-d81c3f2f/web-api/build/libs/web-api-0.1.0.jar
  sha256sum "$OJ" "$BJ" "$DJ" | tee "$W/jar-input-sha.txt"
  [ "$(sha256sum < "$OJ" | cut -c1-64)" = 5c53c9e3448b3218735cbef0f3cf7e1332b84069b410460434b2301fbfbfce32 ] && [ "$(sha256sum < "$BJ" | cut -c1-64)" = c631cf4dc5b1b3e733bb775e9964134f871708d96ea2c26dd681f9bfc8ddd952 ] && [ "$(sha256sum < "$DJ" | cut -c1-64)" = eae7e1d25f4a4da27633a75156ed1eeea6659f4f2958254e961cf8c291b87d1f ] || { echo "보존 jar 해시 불일치 — 재구성하지 않고 멈춤"; exit 3; }
  U1P=$(awk '/^python3 - "\$M\/app-old.jar"/{f=1} f{print} f&&/^grep -qxF "\$JARN"/{exit}' "$B/U1.sh"; awk '/^python3 - "\$M\/app-new.jar"/{f=1} f{print} f&&/^\[ "\$JMH" = "\$JARM" \]/{exit}' "$B/U1.sh")
  printf '%s\n' "$U1P" > "$W/u1-cut.sh"
  jrun() {   # $1 사례, $2 새 jar 자리에 놓을 jar
    local m="$W/m-$1"; mkdir "$m"; cp "$OJ" "$m/app-old.jar"; cp "$2" "$m/app-new.jar"
    ( set -euo pipefail; stop() { echo "🔴 멈춤: $*"; exit 1; }
      python3() { python -I -X utf8 "$@" | tr -d '\r'; return "${PIPESTATUS[0]}"; }   # Windows 출력 CR 만 제거(서버 Linux LF 와 같게)
      eval "$(uvars "$B/U변수.sh")"; M="$m"; eval "$U1P"; echo "U1 jar 검사 통과" ) > "$W/jar-$1.txt" 2>&1; RC=$?
    echo "  사례 $1 rc=$RC | $(tail -1 "$W/jar-$1.txt" | cut -c1-140)"; }
  echo "== 3. 보존 jar 를 U1 비교기 원문으로 — 새 RARX·JARN·JARM"
  jrun B "$BJ"; [ "$RC" = 0 ] && ok "운영 0e1d2a02 보존 jar ↔ 1b719645 jar: RARX 42줄·(끝) 895 900·JARM e8f42507 일치" || ng "B jar"
  cmp -s "$W/m-B/jar-diff.txt" "$N/jarcmp/jar-diff-0e1d2a02-vs-1b719645.txt" && ok "U1 출력 jar-diff.txt = 저장 차이 원문(바이트)" || ng "jar-diff 저장본과 다름"
  cmp -s "$W/m-B/jar-manifest-new.txt" "$R/docs/evidence/one-entry-final-20261125/jar-1b719645/jar-manifest-1b719645.lf.txt" && ok "jar-manifest-new.txt = 저장 B 목록(바이트)" || ng "manifest 저장본과 다름"
  jrun D81 "$DJ"; [ "$RC" = 1 ] && grep -q '기대 42줄' "$W/jar-D81.txt" && ok "옛 d81 jar 를 새 jar 자리에 → 차이 목록에서 거절" || ng "d81 jar 거절 안 됨"
  rm -f "$W"/m-*/app-*.jar
elif [ "$phase" = hash ]; then
  SRC=${3:?출처 커밋 40자}
  echo "== 4. 새 출처 커밋에서 11블록 추출 → 실행 틀 해시 검사(ssh 전 단계만 잘라 실행)"
  ( cd "$W" && python -I -X utf8 "$N/runner/extract.py" "$W/x/blocks-d81" "$SRC" ) > "$W/extract.txt" 2>&1; rc=$?; cat "$W/extract.txt"
  [ "$rc" = 0 ] && [ "$(grep -c ' 같음$' "$W/extract.txt")" = 11 ] && ok "추출 11/11 같음" || ng "추출 rc=$rc"
  awk "/^PYTHONIOENCODING=utf-8 python -I - \"\\\$B\" <<'PYEOF'/{f=1;next} f&&/^PYEOF\$/{exit} f" "$N/runner/run.sh" > "$W/runcheck.py"
  chk() { python -I -X utf8 "$W/runcheck.py" "$1"; }
  chk "$W/x/blocks-d81" && ok "실행 틀 해시 검사: 원본 통과" || ng "원본 해시 검사"
  cp -r "$W/x/blocks-d81" "$W/t1"; printf '# 변조\n' >> "$W/t1/U1.sh"
  chk "$W/t1" && ng "블록 본문 변조 통과됨" || ok "U1 본문 변조 → 해시 거절(전송 단계 전)"
  cp -r "$W/x/blocks-d81" "$W/t2"; printf '# 변조\n' >> "$W/t2/U변수.sh"
  TH=$(sha256sum < "$W/t2/U변수.sh" | cut -c1-64); python -I -X utf8 -c "import json,sys;p=sys.argv[1];j=json.load(open(p,encoding='utf-8'));j['U변수']['sha256']=sys.argv[2];json.dump(j,open(p,'w',encoding='utf-8'),ensure_ascii=False)" "$W/t2/blocks.json" "$TH"
  chk "$W/t2" && ng "blocks.json 까지 맞춘 변조 통과됨" || ok "U변수 변조 + blocks.json 해시 덧쓰기 → 고정값과 달라 거절"
  # 변조 블록을 담은 임시 커밋 객체(로컬, 브랜치·push 없음)로 추출기 거절 확인
  export GIT_INDEX_FILE="$W/idx"; git -C "$R" read-tree "$SRC"
  BL=$( { git -C "$R" show "$SRC:docs/evidence/d81b-exp1-src-20261125/d4/blocks-d81/U0.sh"; printf '# 변조\n'; } | git -C "$R" hash-object -w --stdin)
  git -C "$R" update-index --cacheinfo 100644,"$BL",docs/evidence/d81b-exp1-src-20261125/d4/blocks-d81/U0.sh
  TC=$(git -C "$R" commit-tree "$(git -C "$R" write-tree)" -p "$SRC" -m 'verify tamper (local only)'); unset GIT_INDEX_FILE
  echo "  임시 변조 커밋 $TC(브랜치 없음)"
  ( cd "$W" && python -I -X utf8 "$N/runner/extract.py" "$W/xt" "$TC" ) > "$W/extract-tamper.txt" 2>&1; rc=$?
  grep -E '^U0 ' "$W/extract-tamper.txt"
  [ "$rc" = 1 ] && grep -q '^U0 .*다름' "$W/extract-tamper.txt" && ok "추출기: 변조 U0 커밋 → 종료 1(다름)" || ng "추출기 변조 rc=$rc"
fi
echo "== $phase 결과: $([ $fail = 0 ] && echo 전부 통과 || echo 실패 있음)"
exit $fail
