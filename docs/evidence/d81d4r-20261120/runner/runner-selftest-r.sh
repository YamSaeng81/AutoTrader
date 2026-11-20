#!/bin/bash
# D81D4R 실행 틀 영향 시험(총괄 v1.199 §2) — 가짜 ssh 만. 기존 30건은 반복하지 않고 기록 실패·미완료 표지·바뀐 해시만 본다.
# 사용: runner-selftest-r.sh <새 폴더> <run.sh> <U 9블록 폴더> <F·G 폴더> <옛 U변수.sh(474d1033)>
# 주입: PATH 맨 앞 tee 대체가 FAIL_TEE_N 번째 호출만 실패(쓰지 않고 73). run.sh 의 tee 순서 = 1 미완료 표지 · 2 시작 줄 · 3 원격 출력 · 4 끝 줄 · 5 exit-codes.
set -u; T=$1; R=$2; OLDUV=$5; [ ! -e "$T" ] || { echo "이미 있음 $T"; exit 2; }
mkdir -p "$T/base/blocks-d81" "$T/bin" && cp "$3"/*.sh "$T/base/blocks-d81/" && cp "$4/F.sh" "$4/G.sh" "$T/base/blocks-d81/"
( cd "$T/base" && python -I -X utf8 - <<'PY'
import hashlib, json, pathlib
b = pathlib.Path('blocks-d81'); res = {'source': '0' * 40, 'fg': '1' * 40}
for k in ['F', 'G', 'U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'U4', 'U되돌림', 'U뒤관측']:
    res[k] = {'sha256': hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest(), 'expected_match': True}
(b / 'blocks.json').write_text(json.dumps(res, ensure_ascii=False), encoding='utf-8')
PY
)
cat > "$T/bin/fakessh-d81r.sh" <<'FS'
#!/bin/bash
# FAKE-SSH-D81R — 원격 명령을 실행하지 않는다
echo x >> "$D4RUN/ssh-calls.txt"; printf '%s' "${@: -1}" > "$D4RUN/last-remote.txt"
echo "(가짜 ssh) 원격 명령 $(printf '%s' "${@: -1}" | wc -c)바이트"; exit ${FAKE_RC:-0}
FS
cat > "$T/bin/tee" <<'TE'
#!/bin/bash
n=0; [ ! -f "$D4RUN/tee-count" ] || n=$(cat "$D4RUN/tee-count"); n=$((n+1)); printf '%s\n' "$n" > "$D4RUN/tee-count"
if [ "$n" = "${FAIL_TEE_N:-0}" ]; then cat > /dev/null; echo "주입된 tee 쓰기 실패($n)" >&2; exit 73; fi
exec /usr/bin/tee "$@"
TE
chmod +x "$T/bin/"*; FAKE=$T/bin/fakessh-d81r.sh; P=0; F=0
ok() { if [ "$2" = "$3" ]; then P=$((P+1)); echo "PASS $1 — $2"; else F=$((F+1)); echo "FAIL $1 — $2 (기대 $3)"; fi; }
# c <사례> <FAIL_TEE_N> <FAKE_RC> <블록> <구분> [꼬리] — 사례 폴더를 base 에서 새로 만들고(이미 있으면 재사용) 한 번 부른다. 결과 "종료/ssh호출"
c() { local d=$T/$1; [ -d "$d" ] || cp -r "$T/base" "$d"; : >> "$d/ssh-calls.txt"; rm -f "$d/tee-count"; local b a rc
  b=$(wc -l < "$d/ssh-calls.txt")
  [ "$FAKE" = "$T/bin/fakessh-d81r.sh" ] && grep -q '^# FAKE-SSH-D81R' "$FAKE" || { echo "대역 경로 단언 실패 — 중단"; exit 9; }
  D4RUN=$d D4SSH=$FAKE FAIL_TEE_N=$2 FAKE_RC=$3 PATH="$T/bin:$PATH" bash "$R" "${@:4}" > "$d/last-out-$4.txt" 2>&1; rc=$?
  a=$(wc -l < "$d/ssh-calls.txt"); R_=$rc/$((a-b)); }
mk() { [ -e "$T/$1/out/INCOMPLETE.txt" ] && echo 있음 || echo 없음; }
ecn() { [ -f "$T/$1/out/exit-codes.txt" ] && grep -c . "$T/$1/out/exit-codes.txt" || echo 0; }
c 정상 0 0 U0 deploy; ok "정상 U0 종료/ssh·표지·완료줄" "$R_ $(mk 정상) $(ecn 정상)" "0/1 없음 1"
r=$(cat "$T/정상/last-remote.txt"); eval "set -- ${r#bash -c }"; s="$(cat "$T/base/blocks-d81/F.sh" "$T/base/blocks-d81/G.sh" "$T/base/blocks-d81/U변수.sh" "$T/base/blocks-d81/U0.sh")"
ok "원격 원문 = F+G+새 U변수+새 U0, 마지막 ')'" "$([ "$1" = "$s" ] && [ "${1: -1}" = ")" ] && echo 같음 || echo 다름)" 같음
c 정상 0 0 U0 deploy; ok "같은 출력 이름 다시 → 거절" "$R_" 2/0
c 정상 0 0 U1 deploy; ok "정상 뒤 다음 단계 U1" "$R_ $(mk 정상) $(ecn 정상)" "0/1 없음 2"
c 표지실패 1 0 U0 deploy; ok "첫 기록(미완료 표지) 실패 → ssh 0·종료 4" "$R_ $(mk 표지실패) $(ecn 표지실패)" "4/0 없음 0"
c 시작실패 2 0 U0 deploy; ok "출력 시작 줄 실패 → ssh 0·종료 4" "$R_ $(mk 시작실패) $(ecn 시작실패)" "4/0 없음 0"
c 원격출력실패 3 0 U1 deploy; ok "원격 출력 기록 실패·원격 0 → 종료 4·표지 남음·완료줄 없음" "$R_ $(mk 원격출력실패) $(ecn 원격출력실패)" "4/1 있음 0"
ok "  보고 문구(원격 rc·기록 실패 분리·재실행 금지)" "$(grep -c '원격 종료값 rc=0 · 로컬 기록 실패=1 · 이 틀 종료=4.*재실행 금지' "$T/원격출력실패/last-out-U1.txt")" 1
c 원격출력실패 0 0 U2 deploy; ok "표지 남은 뒤 다음 단계 U2 직접 호출 → 거절 ssh 0" "$R_" 2/0
c 원격출력실패 0 0 U1되돌림 rollback; ok "표지 남은 뒤 되돌림 직접 호출도 → 거절 ssh 0" "$R_" 2/0
c 끝줄실패 4 0 U1 deploy; ok "끝 줄 기록 실패·원격 0 → 종료 4·표지 남음" "$R_ $(mk 끝줄실패) $(ecn 끝줄실패)" "4/1 있음 0"
mkdir -p "$T/종료기록디렉터리" && cp -r "$T/base/." "$T/종료기록디렉터리/" && mkdir -p "$T/종료기록디렉터리/out/exit-codes.txt"
c 종료기록디렉터리 0 0 U1 deploy; ok "exit-codes 자리에 디렉터리 → 종료 4·표지 남음" "$R_ $(mk 종료기록디렉터리)" "4/1 있음"
c 종료기록실패 5 0 U1 deploy; ok "exit-codes 쓰기 실패(tee 5) → 종료 4·표지 남음" "$R_ $(mk 종료기록실패) $(ecn 종료기록실패)" "4/1 있음 0"
c 원격7끝줄실패 4 7 U1 deploy; ok "원격 7 + 끝 줄 실패 → 종료 7 유지·표지 남음" "$R_ $(mk 원격7끝줄실패)" "7/1 있음"
ok "  보고 문구 원격 7 분리" "$(grep -c '원격 종료값 rc=7 · 로컬 기록 실패=1 · 이 틀 종료=7' "$T/원격7끝줄실패/last-out-U1.txt")" 1
c 원격255출력실패 3 255 U1 deploy; ok "원격 255 + 원격 출력 기록 실패 → 종료 255 유지·표지 남음" "$R_ $(mk 원격255출력실패)" "255/1 있음"
c 원격7정상기록 0 7 U1 deploy; ok "원격 7·기록 정상 → 종료 7·표지 지움·완료줄 1" "$R_ $(mk 원격7정상기록) $(ecn 원격7정상기록)" "7/1 없음 1"
mkdir -p "$T/표지지우기실패" && cp -r "$T/base/." "$T/표지지우기실패/" && mkdir -p "$T/표지지우기실패/out" && touch "$T/표지지우기실패/out/INCOMPLETE.txt"
c 표지지우기실패 0 0 U0 deploy; ok "앞 실행 표지가 있으면 U0 도 거절" "$R_" 2/0
cp "$T/base/blocks-d81/U변수.sh" "$T/uv.bak"; printf ' ' >> "$T/base/blocks-d81/U변수.sh"; c 변조U변수 0 0 U3 deploy; ok "새 U변수 1바이트 변조 → 거절" "$R_" 2/0; cp "$T/uv.bak" "$T/base/blocks-d81/U변수.sh"
cp "$OLDUV" "$T/base/blocks-d81/U변수.sh"; c 옛U변수 0 0 U3 deploy; ok "474d1033 옛 U변수(옛 pf3ok) → 거절" "$R_" 2/0; cp "$T/uv.bak" "$T/base/blocks-d81/U변수.sh"
c 복구뒤정상 0 0 U3 deploy; ok "복구 뒤 정상" "$R_ $(mk 복구뒤정상)" "0/1 없음"
echo "== 결과: PASS $P · FAIL $F"; [ "$F" = 0 ]
