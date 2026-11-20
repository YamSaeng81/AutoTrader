#!/bin/bash
# D81D4 실행 틀(총괄 v1.198 §3-6) — v172u2c-20261116/runner/run.sh 에서 블록 폴더(blocks-d81)·허용 블록·고정 해시 11개만 바꿨다. 판 표지 UB=D81D4, 기록 ~/maint-d81.
# 이 틀의 존재는 실행 승인이 아니다. 운영 배포·관문 설치·실제 세션 생성은 별도 승인 뒤에만 쓴다.
# 사용: run.sh <블록이름> <deploy|rollback> [시도 꼬리]
#   deploy: U0 U1 U2 U3 U4 U뒤관측 · rollback: U1되돌림 U되돌림 U3(되돌림 뒤 관측)
#   출력: out/out-<블록>-<구분>[-꼬리].txt, out/exit-codes.txt 이어 쓰기. 출력 파일이 이미 있으면 덮어쓰지 않고 종료 2.
# 원격 스크립트 = F·G·U변수·블록 원문을 붙인 것이며, 마지막 실행문은 검증된 원문 블록 ( … ) 이다(뒤에 echo 를 붙이지 않는다).
# 로컬에서 ssh 의 종료값을 즉시 PIPESTATUS 로 잡아 출력 파일·out/exit-codes.txt 에 기록한 뒤 그 값으로 exit 한다.
# 블록 11개 해시가 아래 고정값·blocks.json(extract.py 출력)과 다르면 종료 2.
set -u
D=${D4RUN:-/d/tmp/d81run}; B=$D/blocks-d81; SSH=${D4SSH:-ssh}
n=${1:?블록 이름}; mode=${2:?deploy 또는 rollback}
case "$mode:$n" in deploy:U0|deploy:U1|deploy:U2|deploy:U3|deploy:U4|deploy:U뒤관측|rollback:U1되돌림|rollback:U되돌림|rollback:U3) ;;
  *) echo "🔴 실행 틀 거절: 블록·구분 조합이 아님 $n/$mode (deploy: U0 U1 U2 U3 U4 U뒤관측 · rollback: U1되돌림 U되돌림 U3)"; exit 2 ;; esac
[ -f "$B/$n.sh" ] || { echo "🔴 실행 틀 거절: 블록 $n 없음"; exit 2; }
o=$D/out/out-$n-$mode${3:+-$3}.txt
[ ! -e "$o" ] || { echo "🔴 실행 틀 거절(종료 2): 이미 있는 출력 $o — 블록을 돌리지 않았다. 시도 꼬리를 붙여 다시"; exit 2; }
PYTHONIOENCODING=utf-8 python -I - "$B" <<'PYEOF' || { echo "🔴 실행 틀 거절(종료 2): 블록 해시가 고정값·blocks.json 과 다름"; exit 2; }
import hashlib, json, pathlib, sys
EXP = {  # F·G 는 7c62a7db, U 9블록은 mkd81.py 생성(blocks-d81.sha256)
    'F': '26d392695c47fecb2321e850b07418679c1326c2a7eec13c02002056f96f4c24',
    'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822',
    'U변수': '3d2c434b310a1f1ae3d1433540580234abf69df6f24178d1432720992a288fa4',
    'U0': 'c5206db76289d9463edaa00758d5d1eab5c5c536d041fbe79fc6230735635e5a',
    'U1': '8d0ce7906fd04124c2c9e251614867e0389ab7a96e8ccb5fae2efae3b79b2af3',
    'U1되돌림': 'a272ab5862a97539233f9425d4efda2da077e26fe6828a0211cd99c2c5827c8b',
    'U2': 'caac178a9b7cae104584b91107c19ee5594eb0437d01b74e16935fe29803644f',
    'U3': 'cc414408aa11133279ad491d6815ac0f9ed521ce0ea87cc46e7a4ec4b204e573',
    'U4': '979c5d85addf7f58c92cfe31aa1759438db790995d642fb530600e0676d3b740',
    'U되돌림': '65df00d5d52430077357d9207b188de5e063f96374c33fe0c32591dca95bfe83',
    'U뒤관측': 'c748a791e87e31ad557977ce3b8c3ab3c0a82436aa4d68ffee93c07bd3d9fd31',
}
b = pathlib.Path(sys.argv[1]); j = json.loads((b / 'blocks.json').read_text(encoding='utf-8'))
ks = [k for k, v in j.items() if isinstance(v, dict)]
bad = [k for k in ks if not j[k]['expected_match'] or hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest() != EXP.get(k) or j[k]['sha256'] != EXP.get(k)]
extra = [p.name for p in b.glob('*.sh') if p.stem not in EXP]
sys.exit(1 if bad or extra or len(ks) != 11 or sorted(ks) != sorted(EXP) or len(str(j.get('source'))) != 40 or len(str(j.get('fg'))) != 40 else 0)
PYEOF
SRC=$(PYTHONIOENCODING=utf-8 python -I -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["source"])' "$B/blocks.json")
mkdir -p "$D/out"
s="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/$n.sh")"
echo "[로컬 시작] $(date '+%F %T') KST(이 PC 시계) 블록=$n 구분=$mode${3:+ 시도=$3} 출처=${SRC:0:8} 판=D81D4" | tee "$o"
$SSH -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T') KST rc=$rc" | tee -a "$o"
echo "$(date '+%F %T') $n $mode${3:+ $3} rc=$rc $(basename "$o")" >> "$D/out/exit-codes.txt"
exit "$rc"
