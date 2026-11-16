set -u; echo "[서버시각] $(date '+%F %T %Z')"
cat /etc/docker/daemon.json 2>&1
for s in backend frontend; do c=$(cd ~/crypto_trader/AutoTrader && docker compose -f docker-compose.prod.yml ps -q $s); echo "== $s Driver={{.Driver}}"; docker inspect --format 'Driver={{.Driver}} Platform={{.Platform}} GraphDriver={{json .GraphDriver}} Storage={{json .Storage}}' "$c" 2>&1 | cut -c1-600; done
echo "== docker events 없음; journal에서 snapshotter 전환 흔적(권한 있으면)"; ls -ld /var/lib/docker /var/lib/docker/image 2>&1; ls /var/lib/docker/image/overlay2/imagedb/content/sha256 2>&1 | grep -c . ; ls /var/lib/docker/image/overlay2/imagedb/content/sha256/ 2>/dev/null | grep -E '^(d825a9f5|80ce7550)' 
grep -n -i 'snapshotter\|containerd' /etc/docker/daemon.json 2>/dev/null
zgrep -h -i 'containerd-snapshotter\|docker-ce\b' /var/log/apt/history.log* 2>/dev/null | head -5; grep -h 'Start-Date\|Upgrade:.*docker-ce[^-]' /var/log/apt/history.log 2>/dev/null | grep -B1 'docker-ce' | head -10
