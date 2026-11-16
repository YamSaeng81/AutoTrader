set -u
echo "[서버시각] $(date '+%F %T %Z')"
which curl jq python3 2>&1
for r in autotrader-frontend:latest autotrader-backend:latest sha256:e25c2a85e4c7695a9ab2e5b4606fdf69cf0b6d7f8c894c8a128fc919d9850c06; do
  echo "== $r manifests=1"
  curl -s --unix-socket /var/run/docker.sock "http://localhost/v1.56/images/$r/json?manifests=1" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("Id",d.get("Id")); print("Descriptor",json.dumps(d.get("Descriptor"))); print("Config.Image?", d.get("Config",{}).get("Image")); [print("M",json.dumps(m)) for m in d.get("Manifests") or []]' 2>&1
done
echo "== docker image inspect --format Manifests (CLI)"; docker image inspect --format '{{json .Manifests}}' autotrader-frontend:latest 2>&1 | head -c 3000; echo
