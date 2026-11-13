#!/bin/bash
# V161D4 실행 틀(총괄 v1.162 §3·§4) — 출처 c8520c55, 후보 88f06345, 판 표지 UB=V161D4. 직전 실제 사용 틀 o13n21-d3-result-b5cd1d0/blocks/run.sh 를 블록 이름만 바꿔 옮겼다.
# 사용: run.sh <블록이름> <deploy|rollback> [시도 꼬리]
#   블록: U0 U1 U1되돌림 U2 U3 UF2 U4 U되돌림 U뒤관측 (U변수·F·G 는 단독 실행하지 않고 앞에 붙는다)
#   예: run.sh U3 deploy → out/out-U3-deploy.txt,  run.sh U3 deploy 2 → out/out-U3-deploy-2.txt
# 원격 스크립트 = F·G·U변수·블록 원문을 붙인 것이며, 마지막 실행문은 검증된 원문 블록 ( … ) 이다(뒤에 echo 를 붙이지 않는다).
# 로컬에서 ssh 의 종료값을 즉시 PIPESTATUS 로 잡아 출력 파일·out/exit-codes.txt 에 기록한 뒤 그 값으로 exit 한다.
# 출력 파일이 이미 있으면 덮어쓰지 않고 종료 2. 블록 해시가 blocks.json 과 다르면 종료 2.
set -u
D=${D4RUN:-/d/tmp/v161run}; B=$D/blocks; SSH=${D4SSH:-ssh}
n=${1:?블록 이름}; mode=${2:?deploy 또는 rollback}
case "$mode" in deploy|rollback) ;; *) echo "🔴 실행 틀 거절: 두 번째 인자는 deploy 또는 rollback (받은 값 $mode)"; exit 2 ;; esac
case "$n" in U0|U1|U1되돌림|U2|U3|UF2|U4|U되돌림|U뒤관측) ;; *) echo "🔴 실행 틀 거절: 실행 블록 이름이 아님 $n"; exit 2 ;; esac
[ -f "$B/$n.sh" ] || { echo "🔴 실행 틀 거절: 블록 $n 없음"; exit 2; }
o=$D/out/out-$n-$mode${3:+-$3}.txt
[ ! -e "$o" ] || { echo "🔴 실행 틀 거절(종료 2): 이미 있는 출력 $o — 블록을 돌리지 않았다. 시도 꼬리를 붙여 다시"; exit 2; }
PYTHONIOENCODING=utf-8 python -I - "$B" <<'PYEOF' || { echo "🔴 실행 틀 거절(종료 2): 블록 해시가 blocks.json 과 다름"; exit 2; }
import hashlib, json, pathlib, sys
b = pathlib.Path(sys.argv[1]); j = json.loads((b / 'blocks.json').read_text(encoding='utf-8'))
ks = [k for k, v in j.items() if isinstance(v, dict)]
bad = [k for k in ks if not j[k]['expected_match'] or hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest() != j[k]['sha256']]
sys.exit(1 if bad or len(ks) != 12 or j.get('source') != 'c8520c55902563bf32b3578bb8156cc2fe4e80f1' else 0)
PYEOF
mkdir -p "$D/out"
s="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/$n.sh")"
echo "[로컬 시작] $(date '+%F %T') KST(이 PC 시계) 블록=$n 구분=$mode${3:+ 시도=$3} 출처=c8520c55" | tee "$o"
$SSH -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T') KST rc=$rc" | tee -a "$o"
echo "$(date '+%F %T') $n $mode${3:+ $3} rc=$rc $(basename "$o")" >> "$D/out/exit-codes.txt"
exit "$rc"
