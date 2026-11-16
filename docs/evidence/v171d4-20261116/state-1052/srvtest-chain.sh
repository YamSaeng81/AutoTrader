# 서버 읽기 전용 시험: 실제 docker 로 rchain/cchain 을 돌린다(태그·컨테이너 변경 없음). 도구는 /tmp/imgchain-t.py
set -uo pipefail
cd ~/crypto_trader/AutoTrader; DC="docker compose -f docker-compose.prod.yml"; IC=/tmp/imgchain-t.py
rchain() { local t j a c; t=$(docker image inspect --format '{{.Id}}' "$1") || return 4
  j=$(curl -sf --unix-socket /var/run/docker.sock "http://localhost/v1.56/images/$t/json?manifests=1") || return 5
  a=$(printf '%s' "$j" | python3 -I $IC api "$t") || return 6
  c=$(docker image save --platform linux/amd64 "$t" | python3 -I $IC save "${a%% *}") || return 7
  echo "$t ${a%% *} $c ${a#* }"; }
cchain() { local id; id=$($DC ps -q "$1") && [ -n "$id" ] || return 4
  docker inspect --format '{{.Id}} {{.Image}} {{json .ImageManifestDescriptor}}' "$id" | python3 -I $IC ctr; }
echo "[서버시각] $(date '+%F %T %Z')"
for r in autotrader-backend:latest autotrader-backend:b5cd1d0 autotrader-frontend autotrader-frontend:latest sha256:e25c2a85e4c7695a9ab2e5b4606fdf69cf0b6d7f8c894c8a128fc919d9850c06 autotrader-frontend:b5cd1d0 sha256:d825a9f5b00d3dac93ee154f32f91b5a1474248fefaa3651f0d0c3b0cc2f7f66 cloudflare/cloudflared:latest; do
  o=$(rchain "$r"); echo "rchain $r rc=$? → $o"; done
for s in backend frontend; do o=$(cchain $s); echo "cchain $s rc=$? → $o"; done
