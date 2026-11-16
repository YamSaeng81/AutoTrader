#!/bin/bash
# V172D4 U2확인 실행 틀 시험(가짜 ssh, 블록을 실행하지 않음). 사용: runner-selftest.sh <새 폴더> <run.sh> <V172D4 blocks-d4> <F·G 폴더> <U2확인.sh>
set -u; T=$1; R=$2; [ ! -e "$T" ] || { echo "이미 있음 $T"; exit 2; }
mkdir -p "$T/blocks-u2c" && cd "$T" && cp "$3"/*.sh blocks-u2c/ && cp "$4/F.sh" "$4/G.sh" "$5" blocks-u2c/
PYTHONIOENCODING=utf-8 python - <<'PY'
import hashlib, json, pathlib
b = pathlib.Path('blocks-u2c'); res = {'source': '0' * 40, 'base': '1' * 40}
for k in ['F', 'G', 'U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'UF2', 'U4', 'U되돌림', 'U뒤관측', 'U2확인']:
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
t 0/1 "U2확인 정상 rc0" env FAKE_RC=0 bash $R U2확인 deploy
r=$(cat last-remote.txt); eval "set -- ${r#bash -c }"; s="$(cat blocks-u2c/F.sh blocks-u2c/G.sh blocks-u2c/U변수.sh blocks-u2c/U2확인.sh)"
if [ "$1" = "$s" ] && [ "${1: -1}" = ")" ]; then P=$((P+1)); echo "PASS 원격 원문 = F+G+U변수+U2확인, 마지막 문자 ')'"; else F=$((F+1)); echo "FAIL 원격 원문"; fi
t 2/0 "같은 출력 이름 다시 → 덮어쓰지 않음" env FAKE_RC=0 bash $R U2확인 deploy
t 1/1 "원격 rc1 그대로(U2확인 시도 2)" env FAKE_RC=1 bash $R U2확인 deploy 2
t 255/1 "ssh 실패 rc255 그대로(U3)" env FAKE_RC=255 bash $R U3 deploy
t 0/1 "U3 시도 꼬리 2" env FAKE_RC=0 bash $R U3 deploy 2
r=$(cat last-remote.txt); eval "set -- ${r#bash -c }"; s="$(cat blocks-u2c/F.sh blocks-u2c/G.sh blocks-u2c/U변수.sh blocks-u2c/U3.sh)"
if [ "$1" = "$s" ]; then P=$((P+1)); echo "PASS 원격 원문 = F+G+U변수+U3(ce6df2fa 원문)"; else F=$((F+1)); echo "FAIL 원격 원문 U3"; fi
t 2/0 "U2확인 rollback 거절" bash $R U2확인 rollback
for b in U0 U1 U2 U1되돌림; do t 2/0 "$b 은 이 틀로 실행하지 않음" bash $R $b deploy; done
t 2/0 "모드 잘못" bash $R U3 deplo
cp blocks-u2c/U2확인.sh u2c.bak; printf ' ' >> blocks-u2c/U2확인.sh; t 2/0 "U2확인 1바이트 변조" bash $R U2확인 deploy 3; cp u2c.bak blocks-u2c/U2확인.sh
cp blocks-u2c/U3.sh u3.bak; printf ' ' >> blocks-u2c/U3.sh; t 2/0 "U3 1바이트 변조" bash $R U3 deploy 3; cp u3.bak blocks-u2c/U3.sh
cp blocks-u2c/blocks.json bj.bak; sed -i 's/"1111111111111111111111111111111111111111"/"111"/' blocks-u2c/blocks.json; t 2/0 "기준 출처 40자 아님" bash $R U3 deploy 4; cp bj.bak blocks-u2c/blocks.json
cp blocks-u2c/U2.sh u2.bak; cp blocks-u2c/U2확인.sh blocks-u2c/U2.sh; t 2/0 "U2 자리에 다른 원문" bash $R U3 deploy 5; cp u2.bak blocks-u2c/U2.sh
t 0/1 "복구 뒤 정상" env FAKE_RC=0 bash $R U4 deploy
echo "== 종료값 기록"; cat out/exit-codes.txt; echo "== 결과: PASS $P · FAIL $F"
