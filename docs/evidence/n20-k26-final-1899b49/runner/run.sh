#!/bin/bash
# #26 실행 틀(총괄 v1.129 §97 지시 4) — 총괄이 #26 실행을 승인하기 전에는 쓰지 않는다.
# 사용: run.sh <블록이름> <deploy|rollback> [시도 꼬리]
#   예: run.sh U3 deploy   → out/out-U3-deploy.txt
#       run.sh U3 rollback → out/out-U3-rollback.txt
# 블록은 승인된 40자리 출처에서 extract.py 로 꺼낸 blocks/ 만 쓴다(blocks/blocks.json 의 해시를 실행 전에 다시 확인).
# 출력 파일이 이미 있으면 덮어쓰지 않고 종료 2 로 멈춘다. 종료값은 요약으로 가리지 않고 그대로 돌려준다.
set -u
D=/d/tmp/k26run; B=$D/blocks
n=${1:?블록 이름}; mode=${2:?deploy 또는 rollback}
case "$mode" in deploy|rollback) ;; *) echo "🔴 실행 틀 거절: 두 번째 인자는 deploy 또는 rollback (받은 값 $mode)"; exit 2 ;; esac
[ -f "$B/$n.sh" ] || { echo "🔴 실행 틀 거절: 블록 $n 없음"; exit 2; }
o=$D/out/out-$n-$mode${3:+-$3}.txt
[ ! -e "$o" ] || { echo "🔴 실행 틀 거절(종료 2): 이미 있는 출력 $o — 블록을 돌리지 않았다. 시도 꼬리를 붙여 다시"; exit 2; }
PYTHONIOENCODING=utf-8 python -I - "$B" <<'PYEOF' || { echo "🔴 실행 틀 거절(종료 2): 블록 해시가 blocks.json 과 다름"; exit 2; }
import hashlib, json, pathlib, sys
b = pathlib.Path(sys.argv[1]); j = json.loads((b / 'blocks.json').read_text(encoding='utf-8'))
bad = [k for k, v in j.items() if isinstance(v, dict) and hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest() != v['sha256']]
sys.exit(1 if bad else 0)
PYEOF
mkdir -p "$D/out"
s="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/$n.sh")"
echo "[로컬 시작] $(date '+%F %T +0900') 블록=$n 구분=$mode 출처=$(sed -n 's/.*"source": "\(.*\)".*/\1/p' "$B/blocks.json")" | tee "$o"
ssh -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T +0900') rc=$rc" | tee -a "$o"
exit "$rc"
