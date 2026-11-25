# D81B-EXP1 서버 유한 배치 공통 함수(총괄 v1.250 §2). batch.sh(실제)와 selftest.sh(합성)가 source 한다 — 이 파일만으로는 아무것도 실행하지 않는다.
# 호출자가 정하는 것: 변수 BASE LOCK RUNS R JOB B M DCK LDG PY BDIR EXP_LIST SRCEXP TAG LD T_N03 T_U0 T_START END_EXCL U1AGE
#                     함수 now_epoch sleep_s exec_block collect_pins verify_self (실제/합성이 다르다 — 이 파일은 블록 원문 외 명령을 만들지 않는다)
# 단계 종료값(steps.txt·final.txt): 0 정상 · 2 거절(실행 안 함) · 3 중복/기한 만료 · 4 기록 실패 · 5 시각 읽기 실패 · 그 밖 = 블록 원래 종료값
# 기록 계약(run.sh v1.199 §2 와 같다): 블록 실행 중 INCOMPLETE.txt · 출력 시작/끝 줄 · exit-codes.txt 완료 줄(마지막) 뒤에만 표지를 지운다.
#   블록 뒤 기록 실패 → 원격 0이어도 4(블록이 비영이면 그 값) · INCOMPLETE 를 남기고 다음 블록·재실행 없음.

kstl() { local x; x=$($PY -I "$BDIR/kst.py" at "$1" 2>/dev/null) && printf '%s' "${x##* }" || printf 'epoch%s(변환 실패)' "$1"; }
rec() { local f=$1; shift; printf '%s\n' "$*" >> "$f" 2>/dev/null; }                     # 필수 기록 한 줄 — 실패면 비영
# 상태(heartbeat): state.txt 를 통째로 바꾼다. steps.txt 에도 한 줄. 실패면 비영(변경 전이면 멈춘다)
st() { local n; n=$(now_epoch) || n=0; { printf '%s %s pid=%s %s\n' "$n" "$(kstl "$n")" "$$" "$*" > "$R/state.tmp" && mv -f "$R/state.tmp" "$R/state.txt"; } 2>/dev/null && rec "$R/steps.txt" "$n $*"; }
# 최종 파일 — 마지막 필수 기록. 이미 있으면 덮지 않는다. 결과 문자열 첫 낱말: COMPLETE-U1-READY | STOP-* | EXPIRED-* | RECORD-FAIL-*
fin() { local code=$1; shift; local n; n=$(now_epoch) || n=0
  st "끝 $code $*" || { echo "🔴 최종 상태 기록 실패 — final.txt 를 쓰지 않는다($code $*)"; return 4; }
  { printf '%s rc=%s 끝=%s %s 작업=%s\n' "$code" "${FRC:-?}" "$n" "$(kstl "$n")" "$JOB" > "$R/final.tmp" && mv -n "$R/final.tmp" "$R/final.txt" && [ ! -e "$R/final.tmp" ]; } 2>/dev/null \
    || { echo "🔴 final.txt 기록 실패($code)"; return 4; }
  echo "■ $code $*"; return 0; }

# 블록 11개 + blocks.json 을 고정값(EXP_LIST "hash 이름" 11줄)·출처 SRCEXP 와 대조. run.sh 해시 검사와 같은 조건 + 출처 전체 일치.
verify_blocks() { EXP_LIST=$EXP_LIST SRCEXP=$SRCEXP $PY -I - "$B" <<'PYEOF'
import hashlib, json, os, pathlib, sys
EXP = dict((l.split()[1], l.split()[0]) for l in os.environ['EXP_LIST'].strip().splitlines())
b = pathlib.Path(sys.argv[1]); j = json.loads((b / 'blocks.json').read_text(encoding='utf-8'))
ks = [k for k, v in j.items() if isinstance(v, dict)]
bad = [k for k in ks if not j[k]['expected_match'] or hashlib.sha256((b / (k + '.sh')).read_bytes()).hexdigest() != EXP.get(k) or j[k]['sha256'] != EXP.get(k)]
extra = [p.name for p in b.glob('*.sh') if p.stem not in EXP]
sys.exit(1 if bad or extra or len(EXP) != 11 or len(ks) != 11 or sorted(ks) != sorted(EXP) or j.get('source') != os.environ['SRCEXP'] or len(str(j.get('fg'))) != 40 else 0)
PYEOF
}

# 목표 시각까지 기다린다(유한). 0 = 도달 · 3 = 기한(END_EXCL) 지남/횟수 초과 · 4 = 상태 기록 실패 · 5 = 시각 읽기 실패
wait_until() { local T=$1 lab=$2 n dl i=0
  while :; do
    n=$(now_epoch) && [[ $n =~ ^[0-9]{10}$ ]] || return 5
    [ "$n" -lt "$END_EXCL" ] || return 3
    [ "$n" -lt "$T" ] && [ "$i" -le 1000 ] || { [ "$n" -ge "$T" ] && return 0; return 3; }
    st "대기 $lab 목표=$T($(kstl "$T")) 기한=$END_EXCL" || return 4
    dl=$(( T - n )); [ "$dl" -le 300 ] || dl=300
    sleep_s "$dl" || return 5; i=$((i+1))
  done; }

# N03 원문 보존·판정. 보고·장부를 만들거나 다시 보내지 않는다(복사·해시·읽기만). 0 PASS · 1 BLOCK · 2 형식 · 4 기록 실패
n03_stage() { local D=$R/n03 f h1 h2 e
  mkdir "$D" 2>/dev/null || return 4
  for f in "$TAG.txt" "$TAG.txt.msg" "$TAG.txt.raw" "$TAG.txt.log.gz" last_send "L:$LD.txt"; do
    local src=$DCK/$f dst=$D/$f
    case $f in L:*) f=${f#L:}; src=$LDG/$f; dst=$D/$f ;; esac
    [ -f "$src" ] && [ -s "$src" ] || { rec "$D/files-missing.txt" "$f" || return 4; continue; }
    e=$(stat -c %Y "$src") && h1=$(sha256sum < "$src" | cut -d' ' -f1) && cp -p "$src" "$dst" && h2=$(sha256sum < "$dst" | cut -d' ' -f1) && [ "$h1" = "$h2" ] || return 4
    rec "$D/files.txt" "$f $e $h1" || return 4
  done
  [ ! -e "$D/files-missing.txt" ] || { rec "$D/verdict.txt" "N03 BLOCK 원문 없음: $(tr '\n' ' ' < "$D/files-missing.txt")" || return 4; return 1; }
  cp "$D/$LD.txt" "$D/ledger.txt" 2>/dev/null || return 4
  # v1.251 §2: 수집 rc 비0 또는 stderr 가 비어 있지 않으면 판정기로 넘어가지 않는다(사유·rc·원문 보존, U0 호출 0)
  local crc=0 esz; collect_pins "$D/pins.txt" || crc=$?
  esz=$(stat -c %s "$D/pins.txt.err" 2>/dev/null) || esz=없음
  rec "$D/collect-rc.txt" "collect_pins rc=$crc stderr=${esz}B" || return 4
  if [ "$crc" != 0 ] || [ "$esz" != 0 ]; then
    rec "$D/verdict.txt" "N03 BLOCK 수집 실패 — collect_pins rc=$crc stderr=${esz}B, 판정기 실행 안 함(pins.txt·pins.txt.err·collect-rc.txt 보존)" || return 4
    ( cd "$D" && sha256sum -- * > SHA256SUMS ) 2>/dev/null || return 4
    return 1; fi
  local rc=0; $PY -I "$BDIR/n03check.py" "$D" "$TAG" > "$D/verdict.txt" 2>&1 || rc=$?
  [ -s "$D/verdict.txt" ] && grep -q "^N03 " "$D/verdict.txt" || return 4
  ( cd "$D" && sha256sum -- * > SHA256SUMS ) 2>/dev/null || return 4
  return $rc; }

# 블록 1회 실행(run.sh 와 같은 기록 계약). 결과는 RB_RC(블록 종료값)·RB_WR(블록 뒤 기록 실패 1)·RB_FR(이 함수 판정 종료값). 반환 = RB_FR
run_block() { local n=$1 mode=$2 o=$R/out-$1-$2.txt I=$R/INCOMPLETE.txt s st en rc wr=0 ps
  RB_RC=-; RB_FR=2; RB_WR=0
  [ ! -e "$o" ] && [ ! -e "$I" ] || { echo "🔴 거절: 출력 $o 또는 미완료 표지가 이미 있다 — 블록 실행 0"; return 2; }
  verify_blocks || { echo "🔴 거절: 블록 해시가 고정값과 다름 — 블록 실행 0"; return 2; }
  s="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/$n.sh")" || { echo "🔴 거절: 블록 읽기 실패"; return 2; }
  st="[서버 시작] $(kstl "$(now_epoch)") 블록=$n 구분=$mode 출처=${SRCEXP:0:8} 판=D81BX1 작업=$JOB"
  rec "$I" "$st 출력=$(basename "$o")" || { rm -f "$I"; RB_FR=4; echo "🔴 기록 실패: 미완료 표지 — 블록 실행 0"; return 4; }
  rec "$o" "$st" || { rm -f "$I"; RB_FR=4; echo "🔴 기록 실패: 출력 시작 줄 — 블록 실행 0"; return 4; }
  st "실행 $n $mode" || { rm -f "$I"; RB_FR=4; echo "🔴 기록 실패: 상태 — 블록 실행 0"; return 4; }
  ( cd ~ && exec_block "$s" 9>&- ) < /dev/null 2>&1 | tee -a "$o" > /dev/null   # 잠금 fd 9 는 블록 자식에게 넘기지 않는다
  ps=("${PIPESTATUS[@]}"); rc=${ps[0]}; [ "${ps[1]}" = 0 ] || wr=1; RB_RC=$rc
  en="[서버 끝] $(kstl "$(now_epoch)") rc=$rc"
  rec "$o" "$en" || wr=1
  [ "$wr" = 0 ] && rec "$R/exit-codes.txt" "$(now_epoch) $n $mode rc=$rc $(basename "$o")" || wr=1
  [ "$wr" = 0 ] && rm -f "$I" && [ ! -e "$I" ] || wr=1
  if [ "$wr" != 0 ]; then RB_WR=1; RB_FR=4; [ "$rc" = 0 ] || RB_FR=$rc
    echo "🔴 기록 실패 — 블록 rc=$rc · 기록 실패=1 · 판정=$RB_FR. 원격 상태 미확인/부분: 재실행 금지, 미완료 표지 남김"; return "$RB_FR"; fi
  RB_FR=$rc; return "$rc"; }

# 전체 흐름. 1회 작업: 잠금 보유 중 다른 실행이 있거나 runs/ 에 기록이 하나라도 있으면 아무것도 만들지 않고 3.
batch_main() { local rc n m age
  exec 9>>"$LOCK" 2>/dev/null || { echo "🔴 잠금 파일 열기 실패 — 시작 안 함"; return 3; }
  flock -n 9 || { echo "🔴 중복: 다른 배치가 잠금을 잡고 있다 — 시작 안 함"; return 3; }
  [ -d "$RUNS" ] && [ -z "$(ls -A "$RUNS")" ] || { echo "🔴 1회 작업: $RUNS 에 이전 실행 기록이 있거나 폴더가 없다 — 재실행하지 않는다"; return 3; }
  mkdir "$R" 2>/dev/null || { echo "🔴 작업 폴더 생성 실패 — 시작 안 함"; return 3; }
  n=$(now_epoch) && [[ $n =~ ^[0-9]{10}$ ]] || { rec "$R/steps.txt" "시각 읽기 실패 — 시작 안 함"; FRC=5; fin STOP-TIME "시작 시각 읽기 실패 — 블록 실행 0"; return 5; }
  { printf 'job %s\npid %s ppid %s sid %s\nstart %s %s\nlock %s\n' "$JOB" "$$" "$PPID" "$(ps -o sid= -p $$ 2>/dev/null | tr -d ' ')" "$n" "$(kstl "$n")" "$LOCK"
    printf 'T_N03 %s %s\nT_U0 %s %s\nT_START %s %s\nEND_EXCL %s %s\nU1AGE %s\n' "$T_N03" "$(kstl "$T_N03")" "$T_U0" "$(kstl "$T_U0")" "$T_START" "$(kstl "$T_START")" "$END_EXCL" "$(kstl "$END_EXCL")" "$U1AGE"
    printf 'source %s\nM %s\n' "$SRCEXP" "$M"; } > "$R/job.txt" 2>/dev/null && st "시작" || { echo "🔴 작업 기록 실패 — 블록 실행 0"; FRC=4; fin RECORD-FAIL-START "작업 기록 실패 — 블록 실행 0"; return 4; }
  verify_self || { FRC=2; fin STOP-SELF "배치 파일 해시 불일치 — 블록 실행 0"; return 2; }
  verify_blocks || { FRC=2; fin STOP-HASH "블록 해시 불일치 — 블록 실행 0"; return 2; }
  st "검증 통과 — N03 대기" || { FRC=4; fin RECORD-FAIL-START "상태 기록 실패 — 블록 실행 0"; return 4; }

  rc=0; wait_until "$T_N03" N03 || rc=$?
  case $rc in 0) ;; 3) FRC=3; fin EXPIRED-BEFORE-N03 "N03 판정 전 기한 만료 — 블록 실행 0"; return 3 ;;
    4) FRC=4; fin RECORD-FAIL-WAIT "대기 상태 기록 실패 — 블록 실행 0"; return 4 ;; *) FRC=5; fin STOP-TIME "시각 읽기 실패 — 블록 실행 0"; return 5 ;; esac
  st "N03 판정" || { FRC=4; fin RECORD-FAIL-N03 "상태 기록 실패 — 블록 실행 0"; return 4; }
  rc=0; n03_stage || rc=$?
  case $rc in 0) st "N03 PASS" || { FRC=4; fin RECORD-FAIL-N03 "상태 기록 실패 — 블록 실행 0"; return 4; } ;;
    4) FRC=4; fin RECORD-FAIL-N03 "N03 보존·판정 기록 실패 — 블록 실행 0"; return 4 ;;
    *) FRC=$rc; fin STOP-N03 "N03 조건 미충족(n03/verdict.txt) — 블록 실행 0, U0 금지"; return 1 ;; esac

  rc=0; wait_until "$T_U0" U0 || rc=$?
  case $rc in 0) ;; 3) FRC=3; fin EXPIRED-BEFORE-U0 "U0 창 지남 — 블록 실행 0"; return 3 ;;
    4) FRC=4; fin RECORD-FAIL-WAIT "대기 상태 기록 실패 — 블록 실행 0"; return 4 ;; *) FRC=5; fin STOP-TIME "시각 읽기 실패 — 블록 실행 0"; return 5 ;; esac
  n=$(now_epoch) && [[ $n =~ ^[0-9]{10}$ ]] && [ "$n" -ge "$T_START" ] && [ "$n" -lt "$END_EXCL" ] || { FRC=3; fin EXPIRED-BEFORE-U0 "U0 직전 창 재검사 실패 — 블록 실행 0"; return 3; }
  verify_self || { FRC=2; fin STOP-SELF "U0 직전 배치 파일 해시 불일치 — 블록 실행 0"; return 2; }
  rc=0; run_block U0 deploy || rc=$?
  [ "$RB_WR" = 1 ] || [ "$RB_FR" = 4 ] && { FRC=$RB_FR; fin RECORD-FAIL-U0 "U0 블록 rc=$RB_RC 뒤 기록 실패 — INCOMPLETE 남김, 원격 상태 미확인, U1 금지·재실행 금지"; return "$RB_FR"; }
  [ "$rc" = 0 ] && [ -f "$M/ok-U0" ] || { FRC=$rc; fin STOP-U0 "U0 rc=$RB_RC 또는 ok-U0 없음 — U1 금지(out-U0-deploy.txt)"; return 1; }
  st "U0 통과 — U1 시각 조건 확인" || { FRC=4; fin RECORD-FAIL-U0 "상태 기록 실패 — U1 실행 0"; return 4; }

  local j=0
  while :; do
    n=$(now_epoch) && [[ $n =~ ^[0-9]{10}$ ]] || { FRC=5; fin STOP-TIME "시각 읽기 실패 — U1 실행 0"; return 5; }
    age=$(( n - $(stat -c %Y "$M/ok-U0") )) || { FRC=2; fin STOP-U1-PRE "ok-U0 시각 읽기 실패 — U1 실행 0"; return 2; }
    [ "$age" -le "$U1AGE" ] || { FRC=3; fin STOP-U1-LATE "ok-U0 뒤 ${age}초 > ${U1AGE}초 — U1 실행 0(U0 부터 다시는 사람 판단)"; return 3; }
    [ "$n" -lt "$END_EXCL" ] || { FRC=3; fin EXPIRED-BEFORE-U1 "U1 창 지남 — U1 실행 0"; return 3; }
    m=$(( (n / 60) % 60 ))
    [ "$m" -ge 14 ] && [ "$m" -le 24 ] || break
    j=$((j+1)); [ "$j" -le 3 ] || { FRC=3; fin STOP-U1-LATE ":14~:24 대기 반복 초과 — U1 실행 0"; return 3; }
    st "U1 대기 — 매시 :14~:24 제외(지금 :$m)" || { FRC=4; fin RECORD-FAIL-U0 "상태 기록 실패 — U1 실행 0"; return 4; }
    sleep_s $(( (25 - m) * 60 - n % 60 )) || { FRC=5; fin STOP-TIME "대기 실패 — U1 실행 0"; return 5; }
  done
  verify_self || { FRC=2; fin STOP-SELF "U1 직전 배치 파일 해시 불일치 — U1 실행 0"; return 2; }
  rc=0; run_block U1 deploy || rc=$?
  [ "$RB_WR" = 1 ] || [ "$RB_FR" = 4 ] && { FRC=$RB_FR; fin RECORD-FAIL-U1 "U1 블록 rc=$RB_RC 뒤 기록 실패 — INCOMPLETE 남김, 원격 상태 미확인·재실행 금지"; return "$RB_FR"; }
  [ "$rc" = 0 ] && [ -f "$M/ok-U1" ] || { FRC=$rc; fin STOP-U1 "U1 rc=$RB_RC 또는 ok-U1 없음 — U2 대상 아님(out-U1-deploy.txt, U1되돌림 판단은 사람)"; return 1; }
  FRC=0; fin COMPLETE-U1-READY "U0·U1 통과 — U2 는 배치에 없다, 사용자 '재기동 진행' 확인 대기" || return 4
  return 0; }
