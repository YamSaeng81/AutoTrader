# N03 시점 설치 상태·로그 집계 수집(총괄 v1.251 §2) — batch.sh 와 pins-read.sh 가 같은 이 파일을 source 한다.
# 읽기만: HEAD·추적 변경 수, backend/frontend 상태 4필드, 설치 스크립트·cron·과거 장부 보존 목록 해시, 그 회차 log.gz 고정 키 집계.
# 계좌·DB·env·인증값·전체 inspect·로그 본문은 출력하지 않는다(풀어 둔 로그는 임시 파일로만 쓰고 지운다).
# 필요한 변수: DCK(보고 폴더) TAG(회차). 모든 필수 명령의 종료값을 직접 확인하고, 실패면 그 단계 고유값으로 비0 을 돌려준다 — echo 로 덮지 않는다.
#   11 checkout 이동 · 12 git rev-parse · 13 git status · 14 compose ps · 15 docker inspect · 16 INSTALLED_SHA · 17 crontab -l
#   18 sha256 대상 읽기 · 19 SID_LEDGER_SHA · 20 과거 장부 검사 실행 불가(보존 목록 없음 등)
#   21 로그 없음/빈 파일 · 22 gzip -t 실패 · 23 압축 해제 실패 · 24 grep 검사 자체 실패(종료 2 이상)
# 과거 장부 파일 대조의 '불일치'(sha256sum -c 종료 1)는 수집 실패가 아니라 값 past_ledger 1 로 남기고 판정기가 FAIL 로 본다.

h256f() { local x; x=$(sha256sum < "$1") || return 1; [[ ${x%% *} =~ ^[0-9a-f]{64}$ ]] || return 1; printf '%s' "${x%% *}"; }
pins_core() { local tmp=$1 v c s r
  cd ~/crypto_trader/AutoTrader || return 11
  v=$(git rev-parse HEAD) || return 12; echo "HEAD $v"
  v=$(git status --porcelain --untracked-files=no) || return 13
  if [ -z "$v" ]; then echo "DIRTY 0"; else echo "DIRTY $(printf '%s\n' "$v" | wc -l)"; fi
  for s in backend frontend; do
    c=$(docker compose -f docker-compose.prod.yml ps -q "$s") && [[ $c =~ ^[0-9a-f]{64}$ ]] || return 14
    v=$(docker inspect --format "Image={{.Image}} StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Status={{.State.Status}}" "$c") || return 15
    echo "$s $v"; done
  v=$(cat ~/ops-scripts/INSTALLED_SHA) || return 16; echo "INSTALLED_SHA $v"
  crontab -l > "$tmp" || return 17; v=$(h256f "$tmp") || return 18; echo "crontab $v"
  v=$(h256f ~/ops-scripts/daily_check.sh) || return 18; echo "daily_check.sh $v"
  v=$(h256f ~/ops-scripts/daily_checks.sql) || return 18; echo "daily_checks.sql $v"
  v=$(cat ~/ops-scripts/SID_LEDGER_SHA) || return 19; echo "SID_LEDGER_SHA $v"
  v=$(h256f ~/ops-scripts/sid_ledger.sql) || return 18; echo "sid_ledger.sql $v"
  [ -s ~/maint-n17/ledger-files-before.sha ] && [ -d ~/sid203-ledger ] || return 20
  r=0; ( cd ~/sid203-ledger && sha256sum --quiet -c ~/maint-n17/ledger-files-before.sha ) > /dev/null || r=$?
  [ "$r" -le 1 ] || return 20; echo "past_ledger $r"
}
pins_logs() { local t=$1 g=$DCK/$TAG.txt.log.gz k c r
  [ -f "$g" ] && [ -s "$g" ] || return 21
  gzip -t "$g" || return 22
  zcat "$g" > "$t" || return 23
  for k in 'Connection leak detection' 'Connection is not available' 'REST 강제 갱신 시세가 가격이 아님' 'REST 폴백 시세 항목 건너뜀' \
           '[시세 보강] 시세 항목 건너뜀' '[시세 보강] 회차 오류' '[시세 보강] 시세 처리 실패' 'DOCTYPE' '<html'; do
    r=0; c=$(grep -c -F -- "$k" "$t") || r=$?
    [ "$r" -le 1 ] && [[ $c =~ ^[0-9]+$ ]] || return 24       # 일치 없음(1)은 0건, 2 이상·숫자 아님은 검사 실패
    echo "logcount $c $k"; done
}
# 사용: collect_pins <pins 출력 파일>. stdout → 파일, stderr → 파일.err. 반환 = 첫 실패 단계 값(성공 0). 하위 셸이라 cd 가 호출자에 남지 않는다.
collect_pins() { local o=$1 rc=0
  ( pins_core "$o.tmp.cron" && pins_logs "$o.tmp.log" ) > "$o" 2> "$o.err" || rc=$?
  rm -f "$o.tmp.cron" "$o.tmp.log" 2>> "$o.err" || [ "$rc" != 0 ] || rc=25
  return "$rc"; }
