(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = N20D2 ] || stop "U 변수가 N20 초안 D2 판이 아님 — F → G → N20 초안 U 변수"
[ -e "$M/ok-U1" ] || stop "U1 미통과"
[ ! -e "$M/ok-U2" ] || stop "U2 를 이미 했다 — U3"
IMG1=$(cat "$M/image-new.txt")
[ "$(git rev-parse HEAD)" = "$NEW" ] || stop "HEAD 가 NEW 가 아님"
[ "$(docker image inspect --format '{{.Id}}' $REPO:latest)" = "$IMG1" ] || stop "latest 가 image-new 와 다름"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 다른 것 있음"
[ "$(sc)" = "66 | 63 | 3 | -" ] || stop "검증 66세션 상태 다름"
date '+%F %T %Z' | tee "$M/t-deploy.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-deploy.utc"
echo "== 여기부터 재기동 — 이 뒤의 멈춤은 '되돌림 필요'"
$DC up -d --no-build --no-deps backend || stop "되돌림 필요: 재생성 실패"
sleep 15
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG1" ] || stop "되돌림 필요: 새 컨테이너 이미지가 image-new 와 다름"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "되돌림 필요: 공유 풀 등록값 다름"
svc | tee "$M/svc-deploy.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-deploy.txt") || stop "되돌림 필요: backend 외 서비스가 바뀜"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-deploy.txt"
touch "$M/ok-U2"; echo "✅ U2 통과 — 5~10분 뒤 U3"
)
