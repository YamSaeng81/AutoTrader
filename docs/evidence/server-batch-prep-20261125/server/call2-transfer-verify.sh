# v1.250 §1-2·§1-3 서버 호출 2: 전용 폴더 새로 만들기(있으면 중단) → tar 풀기 → 해시 → 합성 검증 1묶음 → 분리 실행 사례 시작. 운영 체크아웃·compose·~/maint-* 변경 없음.
set -u
D=$HOME/work-batches/d81b-20261127-exp1
echo "[호출2 시작] $(date -u '+%F %T')Z"
[ ! -e "$D" ] || { echo "🔴 $D 가 이미 있다 — 삭제·덮어쓰기 없이 중단"; exit 9; }
mkdir -p "$HOME/work-batches" && mkdir "$D" || { echo "🔴 폴더 생성 실패"; exit 4; }
tar -xf - -C "$D" --strip-components=1 -k || { echo "🔴 tar 풀기 실패"; exit 4; }
echo "== 해시"; ( cd "$D/src" && sha256sum -c MANIFEST.sha256 ) | sed 's/^/src /'; S1=${PIPESTATUS[0]}
( cd "$D/batch" && sha256sum -c BATCH.sha256 ) | sed 's/^/batch /'; S2=${PIPESTATUS[0]}
echo "src rc=$S1 batch rc=$S2"; [ "$S1" = 0 ] && [ "$S2" = 0 ] || { echo "🔴 해시 불일치 — 검증 중단"; exit 2; }
echo "== 합성 검증"; mkdir "$D/selftest" && bash "$D/batch/selftest.sh" all "$D/selftest/r1" > /dev/null 2>&1; echo "selftest rc=$?"; tail -1 "$D/selftest/r1/result.txt"; grep '^FAIL' "$D/selftest/r1/result.txt"
echo "== 분리 실행 사례 시작"; bash "$D/batch/selftest.sh" detach-start "$D/selftest/detach1"
echo "== 변경 없음 확인"; [ -e "$HOME/maint-d81b-20261127-exp1" ] && echo "🔴 maint 경로 있음" || echo "maint-d81b 없음"; [ -e "$D/runs" ] && echo "🔴 runs 있음" || echo "runs 없음(배치 미등록)"
echo "crontab $(crontab -l | sha256sum | cut -d' ' -f1)"; cd ~/crypto_trader/AutoTrader && echo "HEAD $(git rev-parse HEAD) 변경 $(git status --porcelain --untracked-files=no | wc -l)"
echo "load $(cut -d' ' -f1-3 /proc/loadavg) · home avail $(df -h ~ | tail -1 | awk '{print $4}')"
find "$D" -maxdepth 2 | sort
echo "[호출2 끝] $(date -u '+%F %T')Z"
