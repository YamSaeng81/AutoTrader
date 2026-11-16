#!/bin/bash
# V171D4 실행 틀(총괄 v1.171 §4) — v168-run-20261116/runner/run.sh 에서 판·경로·블록 대조만 바꿨다. 판 표지 UB=V171D4, 기록 ~/maint-v171.
# 사용: run.sh <블록이름> <deploy|rollback> [시도 꼬리]
#   블록: U0 U1 U1되돌림 U2 U3 UF2 U4 U되돌림 U뒤관측 (U변수·F·G 는 단독 실행하지 않고 앞에 붙는다)
#   예: run.sh U3 deploy → out/out-U3-deploy.txt,  run.sh U3 deploy 2 → out/out-U3-deploy-2.txt
# 원격 스크립트 = F·G·U변수·블록 원문을 붙인 것이며, 마지막 실행문은 검증된 원문 블록 ( … ) 이다(뒤에 echo 를 붙이지 않는다).
# 로컬에서 ssh 의 종료값을 즉시 PIPESTATUS 로 잡아 출력 파일·out/exit-codes.txt 에 기록한 뒤 그 값으로 exit 한다.
# 출력 파일이 이미 있으면 덮어쓰지 않고 종료 2. 블록 12개 해시가 아래 고정값·blocks.json(extract.py 출력)과 다르면 종료 2.
set -u
D=${D4RUN:-/d/tmp/v171run}; B=$D/blocks; SSH=${D4SSH:-ssh}
n=${1:?블록 이름}; mode=${2:?deploy 또는 rollback}
case "$mode" in deploy|rollback) ;; *) echo "🔴 실행 틀 거절: 두 번째 인자는 deploy 또는 rollback (받은 값 $mode)"; exit 2 ;; esac
case "$n" in U0|U1|U1되돌림|U2|U3|UF2|U4|U되돌림|U뒤관측) ;; *) echo "🔴 실행 틀 거절: 실행 블록 이름이 아님 $n"; exit 2 ;; esac
[ -f "$B/$n.sh" ] || { echo "🔴 실행 틀 거절: 블록 $n 없음"; exit 2; }
o=$D/out/out-$n-$mode${3:+-$3}.txt
[ ! -e "$o" ] || { echo "🔴 실행 틀 거절(종료 2): 이미 있는 출력 $o — 블록을 돌리지 않았다. 시도 꼬리를 붙여 다시"; exit 2; }
PYTHONIOENCODING=utf-8 python -I - "$B" <<'PYEOF' || { echo "🔴 실행 틀 거절(종료 2): 블록 해시가 고정값·blocks.json 과 다름"; exit 2; }
import hashlib, json, pathlib, sys
EXP = {  # F·G 는 V168D4 와 같은 원문, U 블록 10개는 V171D4(mkdraft10.py 출력)
    'F': 'b7e3fc2c1753c2ac8f2c8f99aee56dc05b013cbce2045ca25c30d95dbe0353b6',
    'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822',
    'U변수': 'ede1ac55cc49c4b2eef49686e9915132dfa2a25f1c4dc904f4a129fda965a7bc',
    'U0': '6fd5b7f4fc0614c5b8ae979d3cfc80fbf00976bbac76141d08b8313a3db87ef1',
    'U1': 'd9f038555445b410c45b104c1eec61bda1fcf62d69f90e60315bfba8dd33973e',
    'U1되돌림': 'e5ff25c7e97298b3736bdd06a0aac873a853a6c614f904dfc42f319c5eb3d7da',
    'U2': 'ae0b2d774ec09c1059df5955fdc169d54cc9409cf100e2e3656c43e1ca1ba905',
    'U3': 'e50069bd22aecfb1b8a510067afba49dec82e10b18bc4e0590df4f9b14028093',
    'UF2': '18cfcd27e805aa0ea3c6861228bbf48576e0a1de656cbe6d1646ea73dd74a8ce',
    'U4': '7a4b4cf26478e2931ec92918941612537b3413c07365478d08bd0de738a2e30a',
    'U되돌림': '84ecc373a8a3cb29bf44785f95ce46526570caa4d26ccb8be10f7f6baffe766c',
    'U뒤관측': '9afe1f91358a07bd54ec7701ef0fc817bb62c158b6926c5e82fc586db05b98b0',
}
b = pathlib.Path(sys.argv[1]); j = json.loads((b / 'blocks.json').read_text(encoding='utf-8'))
ks = [k for k, v in j.items() if isinstance(v, dict)]
bad = [k for k in ks if not j[k]['expected_match'] or hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest() != EXP.get(k) or j[k]['sha256'] != EXP.get(k)]
sys.exit(1 if bad or len(ks) != 12 or sorted(ks) != sorted(EXP) or len(str(j.get('source'))) != 40 else 0)
PYEOF
SRC=$(PYTHONIOENCODING=utf-8 python -I -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["source"])' "$B/blocks.json")
mkdir -p "$D/out"
s="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/$n.sh")"
echo "[로컬 시작] $(date '+%F %T') KST(이 PC 시계) 블록=$n 구분=$mode${3:+ 시도=$3} 출처=${SRC:0:8} 판=V171D4" | tee "$o"
$SSH -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T') KST rc=$rc" | tee -a "$o"
echo "$(date '+%F %T') $n $mode${3:+ $3} rc=$rc $(basename "$o")" >> "$D/out/exit-codes.txt"
exit "$rc"
