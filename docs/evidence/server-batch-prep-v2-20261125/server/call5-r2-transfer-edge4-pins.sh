# v1.251 서버 호출 5: batch-r2 새 경로 전송(있으면 중단) → BATCH.sha256 → 4경계 검증 → 통과 시에만 pins-read.sh(20261125-1000 고정) 1회. launch 없음.
set -u
D=$HOME/work-batches/d81b-20261127-exp1; N=$D/batch-r2
echo "[호출5 시작] $(date -u '+%F %T')Z"
[ -d "$D/batch" ] && [ -d "$D/src" ] || { echo "🔴 기존 전용 폴더 구성이 아님 — 중단"; exit 9; }
[ ! -e "$N" ] && [ ! -e "$D/edge4-r2" ] && [ ! -e "$D/pins-format-20261125-1000" ] || { echo "🔴 batch-r2·edge4-r2·pins-format 중 이미 있는 경로 — 중단"; exit 9; }
mkdir "$N" && tar -xf - -C "$N" --strip-components=1 -k || { echo "🔴 전송 실패"; exit 4; }
( cd "$N" && sha256sum -c BATCH.sha256 ) | sed 's/^/r2 /'; [ "${PIPESTATUS[0]}" = 0 ] || { echo "🔴 batch-r2 해시 불일치 — 중단"; exit 2; }
( cd "$D/batch" && sha256sum --quiet -c BATCH.sha256 ) && ( cd "$D/src" && sha256sum --quiet -c MANIFEST.sha256 ) && echo "기존 batch/·src/ 보존 해시 일치" || { echo "🔴 기존 사본 해시 변화 — 중단"; exit 2; }
echo "== 4경계"; bash "$N/edge4.sh" "$D/edge4-r2" > /dev/null 2>&1; e=$?; cat "$D/edge4-r2/result.txt"; echo "edge4 rc=$e"
[ "$e" = 0 ] || { echo "🔴 4경계 실패 — 실제 읽기 하지 않음"; exit 3; }
echo "== 실제 형식 확인 1회"; bash "$N/pins-read.sh"; echo "pins-read rc=$?"
[ -e "$D/runs" ] && echo "🔴 runs 있음" || echo "runs 없음(배치 미등록)"; [ -e "$HOME/maint-d81b-20261127-exp1" ] && echo "🔴 maint 있음" || echo "maint-d81b 없음"
cd ~/crypto_trader/AutoTrader && echo "HEAD $(git rev-parse HEAD) 변경 $(git status --porcelain --untracked-files=no | wc -l) · crontab $(crontab -l | sha256sum | cut -c1-8) · load $(cut -d' ' -f1-3 /proc/loadavg)"
echo "[호출5 끝] $(date -u '+%F %T')Z"
