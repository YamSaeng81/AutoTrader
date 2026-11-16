#!/bin/bash
# V171D4 실행 틀 시험(가짜 ssh, 블록을 실행하지 않음). 사용: runner-selftest.sh <새 폴더> <run.sh> <V171D4 blocks-d4> <F·G 폴더> <옛 V168D4 U변수>
set -u; T=$1; R=$2; OLDUV=$5; [ ! -e "$T" ] || { echo "이미 있음 $T"; exit 2; }
mkdir -p "$T/blocks" && cd "$T" && cp "$3"/*.sh blocks/ && cp "$4/F.sh" "$4/G.sh" blocks/
PYTHONIOENCODING=utf-8 python - <<'PY'
import hashlib, json, pathlib
b = pathlib.Path('blocks'); res = {'source': '0' * 40}
for k in ['F', 'G', 'U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'UF2', 'U4', 'U되돌림', 'U뒤관측']:
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
t 0/1 "정상 rc0" env FAKE_RC=0 bash $R U0 deploy
r=$(cat last-remote.txt); eval "set -- ${r#bash -c }"; s="$(cat blocks/F.sh blocks/G.sh blocks/U변수.sh blocks/U0.sh)"
if [ "$1" = "$s" ] && [ "${1: -1}" = ")" ]; then P=$((P+1)); echo "PASS 원격 원문 = F+G+U변수+U0, 마지막 문자 ')'"; else F=$((F+1)); echo "FAIL 원격 원문"; fi
t 2/0 "같은 출력 이름 다시 → 덮어쓰지 않음" env FAKE_RC=0 bash $R U0 deploy
t 1/1 "원격 rc1 그대로" env FAKE_RC=1 bash $R U1 deploy
t 255/1 "ssh 실패 rc255 그대로" env FAKE_RC=255 bash $R U2 deploy
t 0/1 "시도 꼬리 2" env FAKE_RC=0 bash $R U0 deploy 2
t 2/0 "모드 잘못" bash $R U0 deplo
t 2/0 "블록 이름 잘못" bash $R U9 deploy
cp blocks/U1.sh U1.bak; printf ' ' >> blocks/U1.sh; t 2/0 "블록 1바이트 변조" bash $R U1 deploy 3; cp U1.bak blocks/U1.sh
cp blocks/blocks.json bj.bak; sed -i 's/"0000000000000000000000000000000000000000"/"000"/' blocks/blocks.json; t 2/0 "출처 40자 아님" bash $R U1 deploy 4; cp bj.bak blocks/blocks.json
cp blocks/U변수.sh uv.bak; cp "$OLDUV" blocks/U변수.sh; t 2/0 "옛 V168D4 U변수 섞임" bash $R U1 deploy 5; cp uv.bak blocks/U변수.sh
t 0/1 "복구 뒤 정상" env FAKE_RC=0 bash $R U1 deploy 6
echo "== 종료값 기록"; cat out/exit-codes.txt; echo "== 결과: PASS $P · FAIL $F"
