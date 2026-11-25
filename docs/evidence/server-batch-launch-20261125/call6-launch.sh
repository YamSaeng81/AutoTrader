# v1.252 서버 호출 6: 시작용 접속 1회. 고정 해시·목록·시각·runs/잠금/기존 기록 확인 → 모두 통과 시에만 batch-r2/launch.sh 1회 → 작업 ID·PID·세션·state 확인. 재시도 없음.
set -u
D=$HOME/work-batches/d81b-20261127-exp1; N=$D/batch-r2; T_N03=1795742040
echo "[호출6 시작] $(date -u '+%F %T')Z epoch=$(date +%s) KST=$(TZ=Asia/Seoul date '+%F %T')"
NOW=$(date +%s); [ "$NOW" -lt "$T_N03" ] || { echo "🔴 이미 11-27 10:14 KST 지남 — 시작 안 함"; exit 9; }
chk() { local f=$1 exp=$2 got; got=$(sha256sum "$f" | cut -d' ' -f1); echo "$got  $f"; [ "$got" = "$exp" ] || { echo "🔴 해시 불일치 $f — 시작 안 함"; exit 2; }; }
chk "$N/BATCH.sha256"   f05d47a30221afa3b7db7d168dfbfc00c19cce48ad35f618538bb153a922a438
chk "$N/launch.sh"      5f1ad35d927c5d917d0a12c23ae920e6604e605cbb1fd0fafa10de95f05fc072
chk "$N/batch.sh"       4dd355d9645a36b39624055ad088bf8f72e2463655ca3c6ee7c03421f4461247
chk "$N/lib.sh"         3255301be112b9d5d0847b21d74247fee485d71f847769c9caa365d95fb4ea04
chk "$N/pins.sh"        7f0117aa3002c097de8d112faf4cbfb1e106ed6c57483f4c6f142f8c71e516d6
chk "$N/n03check.py"    d0047ac9214f1198725fefc950f211d1331902a29fc7abea410843e9def1df7a
chk "$D/src/MANIFEST.sha256" 67e8ed1f17225539e22ddca4fe67089f5206c607953570ef57fed0218a6abc68
echo "== BATCH 목록"; ( cd "$N" && sha256sum -c BATCH.sha256 ) || { echo "🔴 BATCH 목록 불일치"; exit 2; }
echo "== src 목록";   ( cd "$D/src" && sha256sum -c MANIFEST.sha256 ) || { echo "🔴 src 목록 불일치"; exit 2; }
echo "== 충돌 확인"
if [ -e "$D/runs" ]; then [ -z "$(ls -A "$D/runs")" ] || { echo "🔴 runs/ 비어 있지 않음"; ls -l "$D/runs"; exit 3; }; echo "runs/ 있음·비어 있음"; else echo "runs/ 없음"; fi
[ -e "$D/batch.lock" ] && { flock -n "$D/batch.lock" true && echo "batch.lock 있음·잡혀 있지 않음" || { echo "🔴 잠금 잡혀 있음"; exit 3; }; } || echo "batch.lock 없음"
[ ! -e "$HOME/maint-d81b-20261127-exp1" ] && echo "maint-d81b 없음" || { echo "🔴 maint-d81b 있음"; exit 3; }
pgrep -af 'batch(-r2)?/batch\.sh' && { echo "🔴 batch.sh 프로세스가 이미 있음"; exit 3; } || echo "batch.sh 프로세스 0"
ls -A "$D/logs" 2>/dev/null | sed 's/^/logs: /'
echo "== launch 1회"; bash "$N/launch.sh"; LRC=$?; echo "launch rc=$LRC"
sleep 4
echo "== 시작 뒤 확인 $(date -u '+%F %T')Z"
ls -l "$D/runs"; J=$(ls "$D/runs" 2>/dev/null | head -1); echo "작업=$J (runs 항목 수 $(ls "$D/runs" | wc -l))"
[ -n "$J" ] && { echo "-- job.txt"; cat "$D/runs/$J/job.txt"; echo "-- state.txt"; cat "$D/runs/$J/state.txt"; echo "-- steps.txt"; cat "$D/runs/$J/steps.txt"; ls -l "$D/runs/$J"; }
P=$(pgrep -f "batch-r2/batch\.sh $J"); echo "pgrep pid=$P"
[ -n "$P" ] && { ps -o pid,ppid,pgid,sid,tty,etimes,stat,args -p "$P"; echo "SIGHUP 무시 마스크: $(grep SigIgn /proc/$P/status)"; echo "자식: $(ps -o pid,args --ppid "$P" --no-headers)"; }
echo "-- 로그"; cat "$D/logs/$J.txt"
echo "-- final/INCOMPLETE: $(ls "$D/runs/$J" | grep -E 'final|INCOMPLETE' || echo 없음)"
cd ~/crypto_trader/AutoTrader && echo "HEAD $(git rev-parse HEAD) 변경 $(git status --porcelain --untracked-files=no | wc -l) · crontab $(crontab -l | sha256sum | cut -c1-8) · backend $(docker inspect -f '{{.Image}} {{.RestartCount}} {{.State.Status}} {{.State.StartedAt}}' $(docker compose ps -q backend) 2>/dev/null | cut -c1-80) · uptime $(cut -d' ' -f1 /proc/uptime)s"
echo "[호출6 끝] $(date -u '+%F %T')Z"
