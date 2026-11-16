set -u; echo "[서버시각] $(date '+%F %T %Z')"
for r in "$@"; do echo "=== save $r"; s=$(date +%s); docker image save --platform linux/amd64 "$r" | python3 -I /tmp/ocichain-$$.py; echo "rc=${PIPESTATUS[0]}/${PIPESTATUS[1]} 초=$(( $(date +%s)-s ))"; done
