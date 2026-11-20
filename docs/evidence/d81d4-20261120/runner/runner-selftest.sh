#!/bin/bash
# D81D4 실행 틀 시험(가짜 ssh, 블록을 실행하지 않음). 사용: runner-selftest.sh <새 폴더> <run.sh> <U 9블록 폴더> <F·G 폴더>
# blocks.json 은 extract.py 출력 형식을 흉내 낸다(출처 자리 40자 0·1). 실제 추출은 커밋 뒤 extract.py 로 따로 확인한다.
set -u; T=$1; R=$2; [ ! -e "$T" ] || { echo "이미 있음 $T"; exit 2; }
mkdir -p "$T/blocks-d81" && cd "$T" && cp "$3"/*.sh blocks-d81/ && cp "$4/F.sh" "$4/G.sh" blocks-d81/
PYTHONIOENCODING=utf-8 python -I - <<'PY'
import hashlib, json, pathlib
b = pathlib.Path('blocks-d81'); res = {'source': '0' * 40, 'fg': '1' * 40}
for k in ['F', 'G', 'U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'U4', 'U되돌림', 'U뒤관측']:
    res[k] = {'sha256': hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest(), 'expected_match': True}
(b / 'blocks.json').write_text(json.dumps(res, ensure_ascii=False), encoding='utf-8')
PY
cat > fakessh.sh <<'FS'
#!/bin/bash
echo x >> "$D4RUN/ssh-calls.txt"; printf '%s' "${@: -1}" > "$D4RUN/last-remote.txt"
echo "(가짜 ssh) 원격 명령 $(printf '%s' "${@: -1}" | wc -c)바이트 · 앞 인자 ${*:1:3}"; exit ${FAKE_RC:-0}
FS
chmod +x fakessh.sh; : > ssh-calls.txt; export D4RUN=$T D4SSH=$T/fakessh.sh; P=0; F=0
t() { local want=$1 desc=$2; shift 2; local b a rc; b=$(wc -l < ssh-calls.txt); "$@" > last-out.txt 2>&1; rc=$?; a=$(wc -l < ssh-calls.txt)
  if [ "$rc" = "${want%%/*}" ] && [ "$((a-b))" = "${want#*/}" ]; then P=$((P+1)); echo "PASS $desc — 종료 $rc · ssh 호출 $((a-b))"; else F=$((F+1)); echo "FAIL $desc — 종료 $rc · ssh 호출 $((a-b)) (기대 $want)"; tail -2 last-out.txt; fi; }
t 0/1 "U0 정상 rc0" env FAKE_RC=0 bash "$R" U0 deploy
r=$(cat last-remote.txt); eval "set -- ${r#bash -c }"; s="$(cat blocks-d81/F.sh blocks-d81/G.sh blocks-d81/U변수.sh blocks-d81/U0.sh)"
if [ "$1" = "$s" ] && [ "${1: -1}" = ")" ]; then P=$((P+1)); echo "PASS 원격 원문 = F+G+U변수+U0, 마지막 문자 ')'"; else F=$((F+1)); echo "FAIL 원격 원문 U0"; fi
t 2/0 "같은 출력 이름 다시 → 덮어쓰지 않음" env FAKE_RC=0 bash "$R" U0 deploy
t 1/1 "원격 rc1 그대로(U0 시도 2)" env FAKE_RC=1 bash "$R" U0 deploy 2
t 255/1 "ssh 실패 rc255 그대로(U1)" env FAKE_RC=255 bash "$R" U1 deploy
for b in U2 U3 U4 U뒤관측; do t 0/1 "$b deploy" env FAKE_RC=0 bash "$R" $b deploy; done
r=$(cat last-remote.txt); eval "set -- ${r#bash -c }"; s="$(cat blocks-d81/F.sh blocks-d81/G.sh blocks-d81/U변수.sh blocks-d81/U뒤관측.sh)"
if [ "$1" = "$s" ]; then P=$((P+1)); echo "PASS 원격 원문 = F+G+U변수+U뒤관측"; else F=$((F+1)); echo "FAIL 원격 원문 U뒤관측"; fi
for b in U1되돌림 U되돌림 U3; do t 0/1 "$b rollback" env FAKE_RC=0 bash "$R" $b rollback; done
for b in U0 U1 U2 U4 U뒤관측; do t 2/0 "$b rollback 거절" bash "$R" $b rollback; done
for b in U1되돌림 U되돌림 UF2 U2확인; do t 2/0 "$b deploy 거절" bash "$R" $b deploy; done
t 2/0 "모드 잘못" bash "$R" U3 deplo
cp blocks-d81/U2.sh u2.bak; printf ' ' >> blocks-d81/U2.sh; t 2/0 "U2 1바이트 변조" bash "$R" U2 deploy 3; cp u2.bak blocks-d81/U2.sh
cp blocks-d81/U변수.sh uv.bak; printf ' ' >> blocks-d81/U변수.sh; t 2/0 "U변수 1바이트 변조(다른 블록 실행도 거절)" bash "$R" U3 deploy 3; cp uv.bak blocks-d81/U변수.sh
cp blocks-d81/F.sh f.bak; printf ' ' >> blocks-d81/F.sh; t 2/0 "F 1바이트 변조" bash "$R" U3 deploy 4; cp f.bak blocks-d81/F.sh
echo '(' > blocks-d81/UF2.sh; t 2/0 "옛 UF2 블록이 섞임" bash "$R" U3 deploy 5; rm blocks-d81/UF2.sh
cp blocks-d81/blocks.json bj.bak; sed -i 's/"fg": "1111111111111111111111111111111111111111"/"fg": "111"/' blocks-d81/blocks.json; t 2/0 "F·G 출처 40자 아님" bash "$R" U3 deploy 6; cp bj.bak blocks-d81/blocks.json
cp blocks-d81/U2.sh u2.bak; cp blocks-d81/U3.sh blocks-d81/U2.sh; t 2/0 "U2 자리에 다른 원문" bash "$R" U3 deploy 7; cp u2.bak blocks-d81/U2.sh
t 0/1 "복구 뒤 정상" env FAKE_RC=0 bash "$R" U3 deploy 8
echo "== 종료값 기록"; cat out/exit-codes.txt; echo "== 결과: PASS $P · FAIL $F"
