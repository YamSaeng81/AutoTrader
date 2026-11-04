(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${LV:-}" = 63 ] || stop "L 변수가 v1.95(§5-9 63) 판이 아님 — F → G → §5-9 63 L 변수"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #20 이미지(fd3ac351…)가 아님"
date '+%F %T %Z' | tee "$M/t-rollback.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-rollback.utc"
git checkout --quiet $OLD; [ "$(git rev-parse HEAD)" = "$OLD" ] || stop "체크아웃 실패"
docker tag "$IMG0" $REPO:latest
$DC up -d --no-build --no-deps --force-recreate backend
sleep 15
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "이미지가 image-before 와 다름"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "공유 풀 등록값 다름"
svc | tee "$M/svc-rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-rollback.txt"
echo "✅ b1d7664 (fd3ac351…) 로 복귀 — 5분 뒤 L3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄은 기록만)"
)
