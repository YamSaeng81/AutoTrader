#!/bin/bash
# D3 실행 틀(총괄 v1.140 §108 지시 6) — 출처 9aa617d, 후보 b5cd1d0, 판 표지 UB=O13N21D3.
# 사용: run.sh <블록이름> <deploy|rollback> [시도 꼬리]
#   블록: U0 U1 U1되돌림 U2 U3 U4 U5 U되돌림 U뒤관측 (U변수·F·G 는 단독 실행하지 않고 앞에 붙는다)
#   예: run.sh U3 deploy → out/out-U3-deploy.txt,  run.sh U5 deploy 2 → out/out-U5-deploy-2.txt
# 블록은 extract.py 가 9aa617d 에서 꺼낸 blocks/ 만 쓴다(실행 전 blocks.json 해시를 다시 확인, 다르면 실행 안 함).
# 출력 파일이 이미 있으면 덮어쓰지 않고 종료 2. 원격 종료값은 요약으로 가리지 않고 그대로 돌려준다.
set -u
D=${D3RUN:-/d/tmp/d3run}; B=$D/blocks; SSH=${D3SSH:-ssh}
n=${1:?블록 이름}; mode=${2:?deploy 또는 rollback}
case "$mode" in deploy|rollback) ;; *) echo "🔴 실행 틀 거절: 두 번째 인자는 deploy 또는 rollback (받은 값 $mode)"; exit 2 ;; esac
case "$n" in U0|U1|U1되돌림|U2|U3|U4|U5|U되돌림|U뒤관측) ;; *) echo "🔴 실행 틀 거절: 실행 블록 이름이 아님 $n"; exit 2 ;; esac
[ -f "$B/$n.sh" ] || { echo "🔴 실행 틀 거절: 블록 $n 없음"; exit 2; }
o=$D/out/out-$n-$mode${3:+-$3}.txt
[ ! -e "$o" ] || { echo "🔴 실행 틀 거절(종료 2): 이미 있는 출력 $o — 블록을 돌리지 않았다. 시도 꼬리를 붙여 다시"; exit 2; }
PYTHONIOENCODING=utf-8 python -I - "$B" <<'PYEOF' || { echo "🔴 실행 틀 거절(종료 2): 블록 해시가 blocks.json 과 다름"; exit 2; }
import hashlib, json, pathlib, sys
b = pathlib.Path(sys.argv[1]); j = json.loads((b / 'blocks.json').read_text(encoding='utf-8'))
ks = [k for k, v in j.items() if isinstance(v, dict)]
bad = [k for k in ks if not j[k]['expected_match'] or hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest() != j[k]['sha256']]
sys.exit(1 if bad or len(ks) != 12 else 0)
PYEOF
mkdir -p "$D/out"
s="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/$n.sh")"
echo "[로컬 시작] $(TZ=KST-9 date '+%F %T +0900') 블록=$n 구분=$mode 출처=$(sed -n 's/.*"source": "\(.*\)".*/\1/p' "$B/blocks.json")" | tee "$o"
$SSH -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(TZ=KST-9 date '+%F %T +0900') rc=$rc" | tee -a "$o"
exit "$rc"
