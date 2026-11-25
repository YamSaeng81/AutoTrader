#!/bin/bash
# batch-r2 직접 영향 4경계 파일 검증(총괄 v1.251 §3-1). 기존 83건은 반복하지 않는다.
# 실제 lib.sh·pins.sh(collect_pins·pins_logs)·n03check.py 를 쓰고, pins_core(git/docker/해시 읽기)만 11-24 fixture 값을 내는 합성 함수로 바꾼다.
# 블록은 호출 횟수만 기록하는 대역, 가짜 시계 파일. ssh·curl·docker·git·DB·PATH 치환 없음.
# 사용: edge4.sh <출력 폴더(없어야 함)>
set -u
H=$(cd "$(dirname "$0")" && pwd); BDIR=$H; PY=${STPY:-python3}; OUT=${1:?출력 폴더}
[ ! -e "$OUT" ] || { echo "🔴 출력 폴더가 이미 있다"; exit 2; }; mkdir -p "$OUT" || exit 4
SRCEXP=452ac38430dff30300048026e9478e555e015b8c; FX=$H/fixture-n03-20261124; NP=0; NF=0
ok() { if [ "$1" = "$2" ]; then NP=$((NP+1)); echo "PASS $3 (기대=$1)"; else NF=$((NF+1)); echo "FAIL $3 기대=$1 실제=$2"; fi; }
mkblocks() { local b=$1 x; mkdir -p "$b"; for x in F G U변수 U0 U1 U1되돌림 U2 U3 U4 U되돌림 U뒤관측; do printf '#BLOCK=%s\n' "$x" > "$b/$x.sh"; done
  $PY -I - "$b" "$SRCEXP" <<'PYEOF'
import hashlib, json, pathlib, sys
b = pathlib.Path(sys.argv[1]); j = {'source': sys.argv[2], 'fg': '7c62a7db' + '0' * 32}
for p in sorted(b.glob('*.sh')):
    j[p.stem] = {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'expected_match': True}
(b / 'blocks.json').write_text(json.dumps(j, ensure_ascii=False), encoding='utf-8')
PYEOF
}
explist() { ( cd "$1" && for f in *.sh; do printf '%s %s\n' "$(sha256sum < "$f" | cut -d' ' -f1)" "${f%.sh}"; done ); }
runcase() { local name=$1 setup=$2; local C=$OUT/case-$name n e
  mkdir -p "$C"/{runs,dck,ldg,src}; mkblocks "$C/src/blocks-d81"
  cp "$FX/20261124-1000.txt" "$FX/last_send" "$C/dck/"; cp "$FX/20261124.txt" "$C/ldg/"
  printf 'x\n' > "$C/dck/20261124-1000.txt.msg"; printf 'x\n' > "$C/dck/20261124-1000.txt.raw"; printf 'x\n' | gzip > "$C/dck/20261124-1000.txt.log.gz"
  while read -r n e; do case $n in 20261124.txt) touch -d "@$e" "$C/ldg/$n" ;; *) touch -d "@$e" "$C/dck/$n" ;; esac; done < "$FX/mtimes.txt"
  (
    BASE=$C; LOCK=$C/batch.lock; RUNS=$C/runs; JOB=d81b-u0u1-20261125T000000Z-1; R=$RUNS/$JOB; B=$C/src/blocks-d81; M=$C/maint
    DCK=$C/dck; LDG=$C/ldg; TAG=20261124-1000; LD=20261124
    T_N03=1795742040; T_START=1795768500; T_U0=1795768560; END_EXCL=1795775460; U1AGE=1740
    EXP_LIST=$(explist "$B"); echo $((T_N03 - 600)) > "$C/clock"
    now_epoch() { cat "$C/clock"; }
    sleep_s() { echo $(( $(cat "$C/clock") + $1 )) > "$C/clock"; }
    adv() { echo $(( $(cat "$C/clock") + $1 )) > "$C/clock"; }
    verify_self() { return 0; }
    act_U0() { adv 120; mkdir -p "$M"; touch -d "@$(now_epoch)" "$M/ok-U0"; return 0; }
    act_U1() { adv 300; touch "$M/ok-U1"; return 0; }
    exec_block() { local order blk; order=$(printf '%s\n' "$1" | sed -n 's/^#BLOCK=//p' | tr '\n' ' '); blk=${order% }; blk=${blk##* }; echo "$blk" >> "$C/calls.txt"; "act_$blk"; }
    . "$BDIR/lib.sh"; . "$BDIR/pins.sh"
    pins_core() { grep -v '^logcount ' "$FX/pins.txt"; }     # 실제 읽기 대신 11-24 관측값(정상)
    eval "$setup"
    batch_main
  ) > "$C/out.txt" 2>&1
  local rc=$? fw calls; fw=$(cut -d' ' -f1 "$C/runs/"*/final.txt 2>/dev/null); calls=$(cat "$C/calls.txt" 2>/dev/null | tr '\n' ' '); calls=${calls% }
  echo "$rc|${fw:-없음}|${calls:-없음}|$(cat "$C"/runs/*/n03/collect-rc.txt 2>/dev/null)"
}
main() {
echo "[edge4 시작] $(date -u '+%F %T')Z host=$(hostname) py=$($PY -V 2>&1)"
ok "0|COMPLETE-U1-READY|U0 U1|collect_pins rc=0 stderr=0B" "$(runcase e1-normal '')" "경계1 정상 수집·빈 stderr·정상 N03 → 기존 통과 경로"
grep -q '^N03 PASS 항목 45 실패 0' "$OUT"/case-e1-normal/runs/*/n03/verdict.txt; ok 0 $? "경계1 판정기 45항목 PASS(수집 rc·중복 항목 포함)"
ok "1|STOP-N03|없음|collect_pins rc=24 stderr=0B" "$(runcase e2-rc 'pins_logs() { local k; for k in "${LOGK[@]}"; do echo "logcount 0 $k"; done; return 24; }; LOGK=("Connection leak detection" "Connection is not available" "REST 강제 갱신 시세가 가격이 아님" "REST 폴백 시세 항목 건너뜀" "[시세 보강] 시세 항목 건너뜀" "[시세 보강] 회차 오류" "[시세 보강] 시세 처리 실패" "DOCTYPE" "<html")')" "경계2 pins 내용 정상·수집 rc 비0 → STOP, U0/U1 0"
cmp -s <(grep -v '^$' "$FX/pins.txt") "$(ls -d "$OUT"/case-e2-rc/runs/*)/n03/pins.txt"; ok 0 $? "경계2 pins.txt 가 정상 fixture 와 같은 내용(정상처럼 보임)"
ok "1|STOP-N03|없음|collect_pins rc=0 stderr=38B" "$(runcase e3-stderr 'pins_core() { grep -v "^logcount " "$FX/pins.txt"; echo "warning: 합성 수집 경고 한 줄" >&2; }')" "경계3 수집 rc0·stderr 비어 있지 않음 → STOP, U0/U1 0"
ok "1|STOP-N03|없음|collect_pins rc=22 stderr=" "$(runcase e4-gzip 'printf "not gzip\n" > "$DCK/20261124-1000.txt.log.gz"' | sed 's/stderr=[0-9]*B$/stderr=/')" "경계4 로그 압축 해제 실패 → 0건 통과 없이 STOP, U0/U1 0"
grep -q 'logcount' "$(ls -d "$OUT"/case-e4-gzip/runs/*)/n03/pins.txt"; ok 1 $? "경계4 로그 집계 줄을 만들지 않음(0건으로 바꾸지 않음)"
for c in e2-rc e3-stderr e4-gzip; do grep -q '^N03 BLOCK 수집 실패' "$OUT"/case-$c/runs/*/n03/verdict.txt; ok 0 $? "$c 판정기 미실행·수집 실패 사유 보존"; done
echo "[edge4 끝] $(date -u '+%F %T')Z PASS=$NP FAIL=$NF"; [ "$NF" = 0 ]
}
main 2>&1 | tee "$OUT/result.txt"; exit "${PIPESTATUS[0]}"
