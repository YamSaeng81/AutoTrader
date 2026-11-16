#!/bin/bash
# V172D4 U2확인 실행 틀(총괄 v1.174 §2-3·7) — v172d4-20261116/runner/run.sh 에서 블록 폴더(blocks-u2c)·허용 블록·고정 해시 13개(원래 12 + U2확인)만 바꿨다. 판 표지 UB=V172D4, 기록 ~/maint-v172 그대로.
# 사용: run.sh <블록이름> <deploy|rollback> [시도 꼬리]
#   블록: U2확인(deploy 만) U3 UF2 U4 U되돌림 U뒤관측 — U0·U1·U2·U1되돌림 은 이 틀로 실행하지 않는다(11-16 14:16 U2 rc1 이후 단계만)
#   출력은 앞 틀과 같은 out 폴더(out/out-<블록>-<구분>[-꼬리].txt, out/exit-codes.txt 이어 쓰기)
# 원격 스크립트 = F·G·U변수·블록 원문을 붙인 것이며, 마지막 실행문은 검증된 원문 블록 ( … ) 이다(뒤에 echo 를 붙이지 않는다).
# 로컬에서 ssh 의 종료값을 즉시 PIPESTATUS 로 잡아 출력 파일·out/exit-codes.txt 에 기록한 뒤 그 값으로 exit 한다.
# 출력 파일이 이미 있으면 덮어쓰지 않고 종료 2. 블록 13개 해시가 아래 고정값·blocks.json(extract.py 출력)과 다르면 종료 2.
set -u
D=${D4RUN:-/d/tmp/v172run}; B=$D/blocks-u2c; SSH=${D4SSH:-ssh}
n=${1:?블록 이름}; mode=${2:?deploy 또는 rollback}
case "$mode" in deploy|rollback) ;; *) echo "🔴 실행 틀 거절: 두 번째 인자는 deploy 또는 rollback (받은 값 $mode)"; exit 2 ;; esac
case "$n" in U2확인|U3|UF2|U4|U되돌림|U뒤관측) ;; *) echo "🔴 실행 틀 거절: 이 틀의 실행 블록이 아님 $n (U2확인 U3 UF2 U4 U되돌림 U뒤관측)"; exit 2 ;; esac
[ "$n" != U2확인 ] || [ "$mode" = deploy ] || { echo "🔴 실행 틀 거절: U2확인 은 deploy 만"; exit 2; }
[ -f "$B/$n.sh" ] || { echo "🔴 실행 틀 거절: 블록 $n 없음"; exit 2; }
o=$D/out/out-$n-$mode${3:+-$3}.txt
[ ! -e "$o" ] || { echo "🔴 실행 틀 거절(종료 2): 이미 있는 출력 $o — 블록을 돌리지 않았다. 시도 꼬리를 붙여 다시"; exit 2; }
PYTHONIOENCODING=utf-8 python -I - "$B" <<'PYEOF' || { echo "🔴 실행 틀 거절(종료 2): 블록 해시가 고정값·blocks.json 과 다름"; exit 2; }
import hashlib, json, pathlib, sys
EXP = {  # F·G·U 10블록은 ce6df2fa(V172D4) 그대로, U2확인 은 v172u2c-20261116/u2c(mku2c.py 대조)
    'F': 'b7e3fc2c1753c2ac8f2c8f99aee56dc05b013cbce2045ca25c30d95dbe0353b6',
    'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822',
    'U변수': 'bc96a3a6fda61f8889b5f48a693f665dd6b893ea6a383fa07901dbe5e3f70889',
    'U0': '4f1e8151a4f7bf28ced3cb4910ef378eb184bb9d22961f7954e49c627ec41375',
    'U1': '68d06a12bde2117f074599a1268982bb9e19b1751b4d6e5debb5751def62edd1',
    'U1되돌림': '98e963b96b1fd84a28c3011c983ee8a76125bce1349bec5bc79f93e2db987332',
    'U2': '2feef9e129d07d2e9a1607c16f5cccb17f51eb3c53325dd313e5e7009a2b77e5',
    'U3': '6583c32e4d7ceef7e4575c25496eb25c2db05e43d07544855be8bd5386fd2c46',
    'UF2': 'eff1e389c5441d8b5db6d3cf2bfa1040fec9240bb961040c8b8a495c4f52ecbc',
    'U4': '8661620c7ebbfc99a660cc18590594f5dbf4ae41de93d0e2e4f0f36a1b6dd4c8',
    'U되돌림': '2003195a90c7c3c83f10dfbb1c04330ab7c4587fa1eda46b16812f3cb217b49e',
    'U뒤관측': '9e6e7f94526f11ff6d0598c505e00e39c1408f8f88bfb04c4c6ed962590adc9d',
    'U2확인': '1c9eab3582d8574371501ebb1cc693408bd822f66f752261ad0c8997ddbb7cc2',
}
b = pathlib.Path(sys.argv[1]); j = json.loads((b / 'blocks.json').read_text(encoding='utf-8'))
ks = [k for k, v in j.items() if isinstance(v, dict)]
bad = [k for k in ks if not j[k]['expected_match'] or hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest() != EXP.get(k) or j[k]['sha256'] != EXP.get(k)]
sys.exit(1 if bad or len(ks) != 13 or sorted(ks) != sorted(EXP) or len(str(j.get('source'))) != 40 or len(str(j.get('base'))) != 40 else 0)
PYEOF
SRC=$(PYTHONIOENCODING=utf-8 python -I -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["source"])' "$B/blocks.json")
mkdir -p "$D/out"
s="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/$n.sh")"
echo "[로컬 시작] $(date '+%F %T') KST(이 PC 시계) 블록=$n 구분=$mode${3:+ 시도=$3} 출처=${SRC:0:8} 판=V172D4+U2확인" | tee "$o"
$SSH -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T') KST rc=$rc" | tee -a "$o"
echo "$(date '+%F %T') $n $mode${3:+ $3} rc=$rc $(basename "$o")" >> "$D/out/exit-codes.txt"
exit "$rc"
