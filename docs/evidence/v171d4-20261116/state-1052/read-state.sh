set -u
echo "[서버시각] $(date '+%F %T %Z')"
cd ~/crypto_trader/AutoTrader || exit 9
DC="docker compose -f docker-compose.prod.yml"
echo "== git"; git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(추적 끝)"
echo "== docker"; docker version --format 'client={{.Client.Version}} server={{.Server.Version}} api={{.Server.APIVersion}}'; docker compose version; docker info --format 'driver={{.Driver}} status={{json .DriverStatus}}'
for s in backend frontend; do
  c=$($DC ps -q $s); echo "== container $s id=$c"
  docker inspect --format '{{json .Name}} Created={{.Created}} StartedAt={{.State.StartedAt}} Restart={{.RestartCount}} Status={{.State.Status}} ConfigImage={{json .Config.Image}} Image={{.Image}}' "$c"
  echo "-- ImageManifestDescriptor"; docker inspect --format '{{json .ImageManifestDescriptor}}' "$c" 2>&1
  echo "-- compose labels"; docker inspect --format '{{json .Config.Labels}}' "$c" | tr ',' '\n' | grep -i 'com.docker.compose.image\|com.docker.compose.version\|service"'
done
for r in autotrader-backend:latest autotrader-backend:b5cd1d0 autotrader-frontend:latest autotrader-frontend:b5cd1d0 sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055; do
  echo "== image $r"; docker image inspect --format 'Id={{.Id}} Created={{.Created}} Os={{.Os}} Arch={{.Architecture}} RepoTags={{json .RepoTags}} Descriptor={{json .Descriptor}}' "$r" 2>&1
done
echo "== image ls (digests, no trunc)"; docker image ls --no-trunc --digests --format '{{.Repository}}:{{.Tag}} id={{.ID}} digest={{.Digest}} created={{.CreatedAt}}' | grep -E 'autotrader|<none>' 
echo "== image ls --tree (autotrader)"; docker image ls --tree 2>&1 | grep -A4 -E 'autotrader|e25c2a85' | head -60
echo "== 짧은 id 검색"; for p in d825a9f5 837db515 e25c2a85 fa5c9ba8; do echo "-- $p"; docker image inspect "$p" --format '{{.Id}} {{json .RepoTags}}' 2>&1 | head -2; done
echo "== maint-v168 image files"; for f in image-before.txt image-fe-before.txt fe-ref-before.txt image-new.txt image-fe-new.txt; do printf '%s: ' "$f"; cat ~/maint-v168/$f 2>/dev/null || echo '(없음)'; done
echo "== maint-v171"; ls -la ~/maint-v171 2>&1 | head
