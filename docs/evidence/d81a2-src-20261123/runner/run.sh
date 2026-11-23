#!/bin/bash
# D81A2 실행 틀(총괄 v1.227 §3-1) — d81d4r-20261120/runner/run.sh(cd8bd76f)에서 U0 고정 해시(736351db→91ea9342, A2 104행 실패 반출 보정)와 판 표지 문자열만 바꿨다. 기록 계약·전송 줄·기록 ~/maint-d81 는 그대로.
# 기록 계약(v1.199 §2): 필수 기록 = 미완료 표지 out/INCOMPLETE.txt · 출력 시작 줄 · 원격 출력 · 끝 줄 · out/exit-codes.txt 한 줄(완료 표지, 마지막).
#   - ssh 전 필수 기록 실패 → ssh 0회, 종료 4. 미완료 표지가 이미 있으면 어떤 블록도 ssh 0회, 종료 2(뒤 단계 직접 호출 거절).
#   - ssh 뒤 기록 실패 → 원격 0이어도 종료 4(로컬 성공 아님) · 원격 비영이면 그 값 유지. 원격·기록 값을 따로 출력. 미완료 표지는 남긴다 — 원격 상태 미확인/부분, 확인 전 재실행 금지. 자동 재시도 없음.
#   - 모든 기록 성공 뒤에만 exit-codes 완료 줄을 쓰고, 그다음 미완료 표지를 지운다(지우기 실패도 종료 4·표지 남음).
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
EXP = {  # F·G 는 7c62a7db, U 9블록은 mkd81r.py 생성(U변수·U0 만 474d1033 판과 다름, U0 는 다시 A2 보정본 91ea9342)(blocks-d81.sha256)
    'F': '26d392695c47fecb2321e850b07418679c1326c2a7eec13c02002056f96f4c24',
    'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822',
    'U변수': 'efb96108113142132da3685669c1a034e7a3050bc891bbacbecd2891137b19fd',
    'U0': '91ea93428ab4dc4f515d2497d388b4ccd75d4dcb48a1be1089468a3a433f1457',
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
I=$D/out/INCOMPLETE.txt
[ ! -e "$I" ] || { echo "🔴 실행 틀 거절(종료 2): 미완료 표지 $I 가 있다 — 앞 실행의 원격 상태 미확인/부분. 확인·보고 전에는 어떤 블록도 호출하지 않는다(ssh 0회)"; exit 2; }
mkdir -p "$D/out" || { echo "🔴 기록 실패(종료 4): out 폴더를 만들지 못함 — ssh 0회"; exit 4; }
rec() { local f=$1; shift; printf '%s\n' "$*" | tee -a "$f" > /dev/null; }   # 기록 한 줄. tee·쓰기 실패면 비영
s="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/$n.sh")"
st="[로컬 시작] $(date '+%F %T') KST(이 PC 시계) 블록=$n 구분=$mode${3:+ 시도=$3} 출처=${SRC:0:8} 판=D81A2"
rec "$I" "$st 출력=$(basename "$o")" || { rm -f "$I"; echo "🔴 기록 실패(종료 4): 미완료 표지를 쓰지 못함 — ssh 0회, 원격 변경 없음"; exit 4; }
rec "$o" "$st" || { rm -f "$I"; echo "🔴 기록 실패(종료 4): 출력 시작 줄을 쓰지 못함 — ssh 0회, 원격 변경 없음"; exit 4; }
echo "$st"
$SSH -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
ps=("${PIPESTATUS[@]}"); rc=${ps[0]}; wr=0; [ "${ps[1]}" = 0 ] || wr=1
en="[로컬 끝] $(date '+%F %T') KST rc=$rc"
rec "$o" "$en" || wr=1
echo "$en"
[ "$wr" = 0 ] && rec "$D/out/exit-codes.txt" "$(date '+%F %T') $n $mode${3:+ $3} rc=$rc $(basename "$o")" || wr=1
[ "$wr" = 0 ] && rm -f "$I" && [ ! -e "$I" ] || wr=1
if [ "$wr" != 0 ]; then
  fr=4; [ "$rc" = 0 ] || fr=$rc
  echo "🔴 기록 실패 — 원격 종료값 rc=$rc · 로컬 기록 실패=1 · 이 틀 종료=$fr. 원격 상태 미확인/부분: 확인·보고 전 재실행 금지(자동 재시도 없음). 미완료 표지 $I 를 남겼다"
  exit "$fr"
fi
exit "$rc"
