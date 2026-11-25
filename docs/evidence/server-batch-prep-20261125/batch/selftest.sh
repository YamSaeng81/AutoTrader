#!/bin/bash
# 서버 배치 합성 검증(총괄 v1.250 §2 최소 검증). 부작용 없는 고정 함수·파일만 쓴다 — 실제 블록·ssh·curl·docker·PG·git·계좌·.env 를 실행/읽지 않는다. PATH 치환 없음.
# lib.sh 를 source 하고 now_epoch·sleep_s·exec_block·collect_pins·verify_self 를 합성 함수로 정한다(가짜 시계 파일, 블록 대역은 호출 기록만).
# 사용: selftest.sh all <출력 폴더>        — 시각·N03·흐름 사례 1묶음(출력 폴더가 이미 있으면 거절)
#       selftest.sh detach-start <출력 폴더> — 정상 흐름을 setsid nohup 으로 띄우고 바로 끝난다(접속과 독립된 결과 보존 확인용)
#       selftest.sh detach-check <출력 폴더> — 띄운 사례의 final·호출·프로세스 종료를 확인
set -u
H=$(cd "$(dirname "$0")" && pwd); BDIR=$H; PY=${STPY:-python3}
mode=${1:?all|detach-start|detach-check}; OUT=${2:?출력 폴더}
SRCEXP=452ac38430dff30300048026e9478e555e015b8c
FX=$H/fixture-n03-20261124
NP=0; NF=0
ok() { if [ "$1" = "$2" ]; then NP=$((NP+1)); echo "PASS $3 (기대=$1)"; else NF=$((NF+1)); echo "FAIL $3 기대=$1 실제=$2"; fi; }

# ---------- 1. 시각 함수(kst.py) ----------
t_kst() {
  local z out
  for z in UTC Asia/Seoul America/New_York Etc/GMT-14 ""; do
    out=$(TZ=$z $PY -I "$H/kst.py" at 1795705199); ok "1795705199 20261126 2359 2026-11-26T23:59:59+09:00" "$out" "kst 날짜 넘김 직전 TZ=${z:-없음}"
    out=$(TZ=$z $PY -I "$H/kst.py" at 1795705200 | cut -d' ' -f2,3); ok "20261127 0000" "$out" "kst 자정 TZ=${z:-없음}"
  done
  ok "20261127 1734" "$($PY -I "$H/kst.py" at 1795768499 | cut -d' ' -f2,3)" "kst 창 시작 직전(17:34:59)"
  ok "20261127 1735" "$($PY -I "$H/kst.py" at 1795768500 | cut -d' ' -f2,3)" "kst 창 시작 정각"
  ok "20261127 1930" "$($PY -I "$H/kst.py" at 1795775459 | cut -d' ' -f2,3)" "kst 창 끝 분 마지막 초(포함)"
  ok "20261127 1931" "$($PY -I "$H/kst.py" at 1795775460 | cut -d' ' -f2,3)" "kst 창 끝 직후(END_EXCL)"
  local a; for a in "epoch 2026-02-30T10:00" "epoch 2026-11-27T24:00" "epoch 2026-11-27T23:60" "at abc" "at" "nope" "epoch 2026-11-27 10:00"; do
    $PY -I "$H/kst.py" $a > /dev/null 2>&1; ok 2 $? "kst 거절: $a"; done
  ok "" "$($PY -I "$H/kst.py" at abc)" "kst 거절 시 출력 없음"
  local n1 n2; n1=$(date +%s); n2=$($PY -I "$H/kst.py" now | cut -d' ' -f1); [ $(( n2 - n1 )) -ge 0 ] && [ $(( n2 - n1 )) -le 2 ]; ok 0 $? "kst now = 서버 epoch(±2초)"
  # batch.sh 상수 = 의도한 KST 시각
  local k v e
  while read -r k v; do e=$(sed -n "s/^$k=\([0-9]*\).*/\1/p" "$H/batch.sh"); ok "$($PY -I "$H/kst.py" epoch "$v")" "$e" "batch.sh $k = $v KST"; done <<'EOF'
T_N03 2026-11-27T10:14
T_START 2026-11-27T17:35
T_U0 2026-11-27T17:36
END_EXCL 2026-11-27T19:31
EOF
}

# ---------- 2. N03 판정기 ----------
mkfx() { local d=$1 n e; mkdir -p "$d"; cp "$FX/20261124-1000.txt" "$FX/last_send" "$FX/pins.txt" "$d/"; cp "$FX/20261124.txt" "$d/ledger.txt"
  while read -r n e; do case $n in *.msg|*.raw|*.log.gz) continue ;; esac; printf '%s %s %s\n' "$n" "$e" x >> "$d/files.txt"; done < "$FX/mtimes.txt"; }
n03() { $PY -I "$H/n03check.py" "$1" 20261124-1000 > "$1/verdict.txt" 2>&1; echo $?; }
t_n03() { local D=$OUT/n03 c
  mkdir -p "$D"
  mkfx "$D/base"; ok 0 "$(n03 "$D/base")" "N03 기준(11-24 실제 원문) PASS"
  ok "N03 PASS 항목 43 실패 0" "$(tail -1 "$D/base/verdict.txt")" "N03 기준 항목 수"
  mut() { mkfx "$D/$1"; ( cd "$D/$1" && eval "$2" ); ok "$3" "$(n03 "$D/$1")" "N03 $1"; grep '^FAIL' "$D/$1/verdict.txt" | head -2 | sed 's/^/      /'; }
  mut m1-head-red "sed -i '1s/🟢 점검 항목 정상/🔴 이상 1건/' 20261124-1000.txt" 1
  mut m2-sid-balance "sed -i 's/잔액 20200.00/잔액 20100.00/' 20261124-1000.txt" 1
  mut m3-new-red-head "sed -i '3i 🔴 미분류 ERROR 1건' 20261124-1000.txt" 1
  mut m4-ledger-order "sed -i '/^== L2/,/^== L3/s/(0 rows)/(1 row)/' ledger.txt" 1
  mut m5-pin-head "sed -i 's/^HEAD 0e1d/HEAD 1b71/' pins.txt" 1
  mut m6-last-send "sed -i 's/10:00:05/10:00:06/' last_send" 1
  mut m7-stale-report "sed -i 's/^20261124-1000.txt 1795482005/20261124-1000.txt 1795395605/' files.txt" 1
  mut m8-missing-pins "rm pins.txt" 2
  mut m9-error-unclassified "sed -i 's/통신 실패 7 · 미분류 0/통신 실패 6 · 미분류 1/' 20261124-1000.txt" 1
  mut m10-restart "sed -i 's/^backend \(.*\)RestartCount=0/backend \1RestartCount=1/' pins.txt" 1
  mut m11-log-hikari "sed -i 's/^logcount 0 Connection is not available/logcount 2 Connection is not available/' pins.txt" 1
  mut m12-ledger-balance-diff "sed -i 's/|         0.00 |       20200 |/|        -1.00 |       20200 |/' ledger.txt" 1
  mut m13-blast-count "sed -i 's/^66세션: 🔴 적재 뒤처짐 12회/66세션: 🔴 적재 뒤처짐 13회/' 20261124-1000.txt" 1
  mut m14-past-ledger "sed -i 's/^past_ledger 0/past_ledger 1/' pins.txt" 1
}

# ---------- 3. 흐름 사례(가짜 시계·블록 대역) ----------
mkblocks() { local b=$1; mkdir -p "$b"; local x; for x in F G U변수 U0 U1 U1되돌림 U2 U3 U4 U되돌림 U뒤관측; do printf '#BLOCK=%s\n' "$x" > "$b/$x.sh"; done
  $PY -I - "$b" "$SRCEXP" <<'PYEOF'
import hashlib, json, pathlib, sys
b = pathlib.Path(sys.argv[1]); j = {'source': sys.argv[2], 'fg': '7c62a7db' + '0' * 32}
for p in sorted(b.glob('*.sh')):
    j[p.stem] = {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'expected_match': True}
(b / 'blocks.json').write_text(json.dumps(j, ensure_ascii=False), encoding='utf-8')
PYEOF
}
explist() { ( cd "$1" && for f in *.sh; do printf '%s %s\n' "$(sha256sum < "$f" | cut -d' ' -f1)" "${f%.sh}"; done ); }
# 사례 하나: $1 이름 · 나머지는 사례 설정 함수가 정한다. 결과: 종료값·final 첫 낱말·블록 호출 순서·INCOMPLETE 유무
runcase() { local name=$1 setup=$2; local C=$OUT/case-$name
  mkdir -p "$C"/{runs,dck,ldg,src}
  mkblocks "$C/src/blocks-d81"
  local n e; cp "$FX/20261124-1000.txt" "$FX/last_send" "$C/dck/"; cp "$FX/20261124.txt" "$C/ldg/"; printf 'x\n' > "$C/dck/20261124-1000.txt.msg"; printf 'x\n' > "$C/dck/20261124-1000.txt.raw"; printf 'x\n' | gzip > "$C/dck/20261124-1000.txt.log.gz"
  while read -r n e; do case $n in 20261124.txt) touch -d "@$e" "$C/ldg/$n" ;; *) touch -d "@$e" "$C/dck/$n" ;; esac; done < "$FX/mtimes.txt"
  (
    BASE=$C; LOCK=$C/batch.lock; RUNS=$C/runs; JOB=d81b-u0u1-20261125T000000Z-1; R=$RUNS/$JOB; B=$C/src/blocks-d81; M=$C/maint
    DCK=$C/dck; LDG=$C/ldg; TAG=20261124-1000; LD=20261124
    T_N03=1795742040; T_START=1795768500; T_U0=1795768560; END_EXCL=1795775460; U1AGE=1740
    EXP_LIST=$(explist "$B")
    echo $((T_N03 - 7200)) > "$C/clock"
    now_epoch() { cat "$C/clock"; }
    sleep_s() { echo $(( $(cat "$C/clock") + $1 )) > "$C/clock"; echo "sleep $1" >> "$C/sleeps.txt"; }
    adv() { echo $(( $(cat "$C/clock") + $1 )) > "$C/clock"; }
    verify_self() { return 0; }
    collect_pins() { cp "$FX/pins.txt" "$1"; }
    act_U0() { adv 120; mkdir -p "$M"; touch -d "@$(now_epoch)" "$M/ok-U0"; echo "✅ U0 통과(합성)"; return 0; }
    act_U1() { adv 300; touch "$M/ok-U1"; echo "✅ U1 통과(합성)"; return 0; }
    exec_block() { local order blk; order=$(printf '%s\n' "$1" | sed -n 's/^#BLOCK=//p' | tr '\n' ' '); blk=${order% }; blk=${blk##* }
      echo "$blk [$order]" >> "$C/calls.txt"; "act_$blk"; }
    . "$BDIR/lib.sh"
    eval "$setup"
    batch_main
  ) > "$C/out.txt" 2>&1
  local rc=$? fw; fw=$(cut -d' ' -f1 "$C/runs/"*/final.txt 2>/dev/null)
  local calls; calls=$(cut -d' ' -f1 "$C/calls.txt" 2>/dev/null | tr '\n' ' '); calls=${calls% }
  local inc=0; ls "$C/runs/"*/INCOMPLETE.txt > /dev/null 2>&1 && inc=1
  echo "$rc|${fw:-없음}|${calls:-없음}|$inc"
}
t_flow() {
  ok "0|COMPLETE-U1-READY|U0 U1|0" "$(runcase c01-normal '')" "흐름 정상 완료(N03 PASS→U0→U1)"
  local C=$OUT/case-c01-normal R; R=$(ls -d "$C"/runs/*)
  ok "U0 [F G U변수 U0 ]|U1 [F G U변수 U1 ]" "$(tr '\n' '|' < "$C/calls.txt" | sed 's/|$//')" "블록 문자열 = F·G·U변수·블록 순서"
  ok 2 "$(wc -l < "$R/exit-codes.txt")" "exit-codes 2줄"
  ok "COMPLETE-U1-READY" "$(tail -1 "$R/steps.txt" | cut -d' ' -f3)" "final 직전 마지막 상태 = 끝 COMPLETE"
  grep -q '^N03 PASS' "$R/n03/verdict.txt"; ok 0 $? "N03 판정 원문 보존"
  ( cd "$R/n03" && sha256sum --quiet -c SHA256SUMS ); ok 0 $? "N03 보존 폴더 체크섬"
  ok "$(sha256sum < "$FX/20261124-1000.txt" | cut -d' ' -f1)" "$(sha256sum < "$R/n03/20261124-1000.txt" | cut -d' ' -f1)" "N03 보고 사본 = 원본"
  grep -q '대기 N03' "$R/steps.txt" && grep -q '대기 U0' "$R/steps.txt"; ok 0 $? "목표 시각까지 대기 기록"

  # 중복: 잠금을 다른 프로세스가 잡고 있음
  mkdir -p "$OUT/case-c02-duplock"; ( exec 9>>"$OUT/case-c02-duplock/batch.lock"; flock -n 9; sleep 4 ) & local hp=$!; sleep 1
  ok "3|없음|없음|0" "$(runcase c02-duplock '')" "중복 — 잠금 잡힘(시작 안 함)"; wait $hp
  ok "" "$(ls -A "$OUT/case-c02-duplock/runs")" "중복 — 작업 폴더 0"
  ok "3|없음|없음|0" "$(runcase c03-once 'mkdir "$RUNS/old"')" "1회 작업 — 이전 기록 있으면 시작 안 함"
  ok "3|EXPIRED-BEFORE-N03|없음|0" "$(runcase c04-expired 'echo $END_EXCL > "$C/clock"')" "기한 만료(시작 시 이미 지남)"
  ok "3|EXPIRED-BEFORE-U0|없음|0" "$(runcase c05-expired-u0 'echo $((END_EXCL-5)) > "$C/clock"; collect_pins() { cp "$FX/pins.txt" "$1"; adv 10; }')" "N03 뒤 U0 창 지남"
  ok "1|STOP-N03|없음|0" "$(runcase c06-n03-block 'sed -i "1s/🟢 점검 항목 정상/🔴 이상 1건/" "$DCK/20261124-1000.txt"; touch -d @1795482005 "$DCK/20261124-1000.txt"')" "앞 단계 실패 — N03 BLOCK 이면 U0 0"
  ok "1|STOP-N03|없음|0" "$(runcase c06b-n03-missing 'rm "$DCK/20261124-1000.txt"')" "N03 보고 없음 — U0 0"
  ok "2|STOP-HASH|없음|0" "$(runcase c07-hash 'echo "echo 변조" >> "$B/U0.sh"')" "블록 변조 — 실행 0"
  ok "2|STOP-SELF|없음|0" "$(runcase c07b-self 'verify_self() { return 1; }')" "배치 파일 해시 불일치 — 실행 0"
  ok "1|STOP-U0|U0|0" "$(runcase c08-u0-fail 'act_U0() { adv 60; echo "🔴 멈춤: 합성"; return 1; }')" "U0 실패 — U1 0"
  ok "1|STOP-U0|U0|0" "$(runcase c08b-u0-noflag 'act_U0() { adv 60; return 0; }')" "U0 rc0 이지만 ok-U0 없음 — U1 0"
  ok "0|COMPLETE-U1-READY|U0 U1|0" "$(runcase c09-minute 'act_U0() { echo $((T_U0 + 38*60 + 30)) > "$C/clock"; mkdir -p "$M"; touch -d "@$(now_epoch)" "$M/ok-U0"; return 0; }')" ":14~:24 이면 :25 까지 기다린 뒤 U1"
  grep -q 'U1 대기 — 매시 :14~:24 제외(지금 :14)' "$OUT"/case-c09-minute/runs/*/steps.txt; ok 0 $? ":14 대기 기록"
  ok 1795771500 "$(sed -n 's/^\([0-9]*\) 실행 U1.*/\1/p' "$OUT"/case-c09-minute/runs/*/steps.txt)" "U1 시작 = 18:25:00"
  ok "3|STOP-U1-LATE|U0|0" "$(runcase c10-u1-late 'act_U0() { adv 60; mkdir -p "$M"; touch -d "@$(( $(now_epoch) - 1741 ))" "$M/ok-U0"; return 0; }')" "ok-U0 1741초 지남 — U1 0"
  ok "3|EXPIRED-BEFORE-U1|U0|0" "$(runcase c10b-u1-window 'act_U0() { echo $END_EXCL > "$C/clock"; mkdir -p "$M"; touch -d "@$(now_epoch)" "$M/ok-U0"; return 0; }')" "U0 뒤 창 끝 지남 — U1 0"
  ok "4|RECORD-FAIL-U0|U0|1" "$(runcase c11-record 'act_U0() { adv 60; mkdir -p "$M"; touch -d "@$(now_epoch)" "$M/ok-U0"; mkdir "$R/exit-codes.txt"; return 0; }')" "U0 뒤 기록 실패 — INCOMPLETE 남김·U1 0"
  grep -q 'rc=0' "$OUT"/case-c11-record/runs/*/out-U0-deploy.txt; ok 0 $? "기록 실패여도 블록 rc 원문 보존"
  ok "7|RECORD-FAIL-U0|U0|1" "$(runcase c11b-record-rc 'act_U0() { adv 60; mkdir "$R/exit-codes.txt"; return 7; }')" "블록 비영+기록 실패 — 원래 값 7 유지"
  # 재실행 거절: 같은 BASE 로 다시
  local C11=$OUT/case-c11-record
  ( BASE=$C11; LOCK=$C11/batch.lock; RUNS=$C11/runs; R=$RUNS/again; PY=$PY; now_epoch() { cat "$C11/clock"; }; . "$BDIR/lib.sh"; batch_main ) > "$C11/again.txt" 2>&1
  ok 3 $? "기록 실패 뒤 재실행 거절"
  ok "4|RECORD-FAIL-U1|U0 U1|1" "$(runcase c11c-record-u1 'act_U1() { adv 60; touch "$M/ok-U1"; mkdir "$R/x"; mv "$R/exit-codes.txt" "$R/x/"; mkdir "$R/exit-codes.txt"; return 0; }')" "U1 뒤 기록 실패 — RECORD-FAIL-U1·INCOMPLETE 남김"
  ok "1|STOP-U1|U0 U1|0" "$(runcase c12-u1-fail 'act_U1() { adv 60; echo "🔴 멈춤: 합성"; return 1; }')" "U1 실패 — STOP-U1(U2 없음)"
  ok "5|STOP-TIME|없음|0" "$(runcase c13-time 'now_epoch() { echo x; }')" "시각 읽기 실패 — 실행 0"
  ok "4|없음|없음|0" "$(runcase c14-staterec 'st() { return 1; }')" "시작 상태 기록 실패 — 실행 0·final 없음"
  ok 0 "$(cat "$OUT"/case-*/calls.txt 2>/dev/null | grep -c -v -E '^U[01] ')" "모든 사례에서 U0·U1 밖 블록(U2 등) 호출 0"
}

# ---------- 4. 실제 출처 블록 해시 검사(배치의 verify_blocks + batch.sh 고정값) ----------
t_real() { local S; S=$(cd "$H/../src" && pwd)
  ( B=$S/blocks-d81; eval "$(sed -n "/^EXP_LIST='/,/'\$/p" "$H/batch.sh")"; . "$BDIR/lib.sh"; verify_blocks ); ok 0 $? "실제 11블록 = batch.sh 고정값·blocks.json·출처 452ac384"
  local T=$OUT/real-tamper; mkdir -p "$T"; cp -r "$S/blocks-d81" "$T/"; printf '\n' >> "$T/blocks-d81/U1.sh"
  ( B=$T/blocks-d81; eval "$(sed -n "/^EXP_LIST='/,/'\$/p" "$H/batch.sh")"; . "$BDIR/lib.sh"; verify_blocks ); ok 1 $? "실제 블록 1바이트 변조 거절"
  cp "$S/blocks-d81/U1.sh" "$T/blocks-d81/U1.sh"; sed -i 's/452ac38430dff30300048026e9478e555e015b8c/794480ea00000000000000000000000000000000/' "$T/blocks-d81/blocks.json"
  ( B=$T/blocks-d81; eval "$(sed -n "/^EXP_LIST='/,/'\$/p" "$H/batch.sh")"; . "$BDIR/lib.sh"; verify_blocks ); ok 1 $? "blocks.json 출처 다른 커밋 거절"
  ( cd "$H" && sha256sum --quiet -c BATCH.sha256 ); ok 0 $? "배치 파일 BATCH.sha256"
  ( cd "$S" && sha256sum --quiet -c MANIFEST.sha256 ); ok 0 $? "출처 MANIFEST.sha256"
  bash -n "$H/batch.sh" && bash -n "$H/lib.sh" && bash -n "$H/launch.sh" && bash -n "$H/selftest.sh"; ok 0 $? "쉘 구문"
  $PY -I -c 'import ast,sys; [ast.parse(open(f,encoding="utf-8").read()) for f in sys.argv[1:]]' "$H/kst.py" "$H/n03check.py"; ok 0 $? "Python 구문"
}

case $mode in
  unit) mkdir -p "$OUT" && { t_kst; t_n03; echo "[unit] PASS=$NP FAIL=$NF"; } ;;
  all) [ ! -e "$OUT" ] || { echo "🔴 출력 폴더가 이미 있다 — 덮지 않는다"; exit 2; }; mkdir -p "$OUT" || exit 4
       { echo "[selftest 시작] $(date -u '+%F %T')Z host=$(hostname) py=$($PY -V 2>&1)"; t_kst; t_n03; t_flow; t_real
         echo "[selftest 끝] $(date -u '+%F %T')Z PASS=$NP FAIL=$NF"; } 2>&1 | tee "$OUT/result.txt"
       grep -q ' FAIL=0$' "$OUT/result.txt" ;;
  detach-start)
       [ ! -e "$OUT" ] || { echo "🔴 출력 폴더가 이미 있다"; exit 2; }; mkdir -p "$OUT" || exit 4
       echo "launch $(date -u +%s) ssh=${SSH_CONNECTION:+있음} ppid=$PPID" > "$OUT/launch.txt"
       setsid nohup bash "$0" detach-run "$OUT" < /dev/null > "$OUT/nohup.txt" 2>&1 &
       echo "띄움 pid=$! $(date -u '+%F %T')Z — 이 접속을 끝낸다" ;;
  detach-run)
       echo "run-start $(date -u +%s) pid=$$ sid=$(ps -o sid= -p $$ | tr -d ' ') ppid=$PPID" >> "$OUT/launch.txt"
       r=$(runcase d01-detached 'act_U0() { sleep 15; adv 120; mkdir -p "$M"; touch -d "@$(now_epoch)" "$M/ok-U0"; return 0; }; act_U1() { sleep 15; adv 300; touch "$M/ok-U1"; return 0; }')
       echo "run-end $(date -u +%s) 결과=$r" >> "$OUT/launch.txt" ;;
  detach-check)
       cat "$OUT/launch.txt"; ls "$OUT"/case-d01-detached/runs/*/ 2>/dev/null
       ok "0|COMPLETE-U1-READY|U0 U1|0" "$(sed -n 's/^run-end [0-9]* 결과=//p' "$OUT/launch.txt")" "접속 종료 뒤 끝난 배치 결과"
       pgrep -f "selftest.sh detach-run $OUT" > /dev/null; ok 1 $? "배치 프로세스 남지 않음"
       echo "[detach-check] $(date -u '+%F %T')Z PASS=$NP FAIL=$NF"; [ "$NF" = 0 ] ;;
  *) exit 2 ;;
esac
