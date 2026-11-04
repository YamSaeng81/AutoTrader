(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #21 이미지(67294e80…)가 아님"
date '+%F %T %Z' | tee "$M/t-rollback.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-rollback.utc"
git checkout --quiet $OLD; [ "$(git rev-parse HEAD)" = "$OLD" ] || stop "체크아웃 실패"
docker tag "$IMG0" $REPO:latest
$DC up -d --no-build --no-deps --force-recreate backend
sleep 15
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "이미지가 image-before 와 다름"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "공유 풀 등록값 다름"
svc | tee "$M/svc-rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-rollback.txt"
echo "✅ 6995c52 (67294e80…) 로 복귀 — 5분 뒤 P3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의는 기록만)"
)
