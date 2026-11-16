(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V172D4 ] || stop "U 변수가 v1.172 통합 후보 초안 D4 판이 아님 — F → G → v1.172 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
[ -e "$M/ok-U1" ] || stop "U1 미통과"
[ ! -e "$M/ok-U2" ] || stop "U2 를 이미 했다 — U3"
IMG1=$(cat "$M/image-new.txt")
[ "$(git rev-parse HEAD)" = "$NEW" ] || stop "HEAD 가 NEW 가 아님"
[ "$(docker image inspect --format '{{.Id}}' $REPO:latest)" = "$IMG1" ] || stop "latest 가 image-new 와 다름"
same3 $REPO:latest "$M/image-new-chain.txt" || stop "latest 가 image-new 대상·manifest·config 와 다름 또는 조회 실패(아직 바꾼 것 없음) — 보고"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 #27 이미지 대응이 아님 또는 조회 실패(아직 바꾼 것 없음) — 보고"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 다른 것 있음"
[ "$(sc)" = "66 | 63 | 3 | -" ] || stop "검증 66세션 상태 다름"
date '+%F %T %Z' | tee "$M/t-deploy.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-deploy.utc"
v94 || stop "재기동 전 Flyway 최고 버전이 94 가 아님 — 보고(아직 바꾼 것 없음)"
echo "== 여기부터 재기동 — 새 backend 기동 때 Flyway 가 V96 하나를 적용한다. 이 뒤의 멈춤은 '되돌림 필요'(V96 은 되돌리지 않는다)"
$DC up -d --no-build --no-deps backend || stop "되돌림 필요: 재생성 실패"
sleep 15
isimg backend "$(okimg "$M/image-new-chain.txt")" $(ok3 "$M/image-new-chain.txt") || stop "되돌림 필요: 새 컨테이너가 image-new 대상·manifest·config 와 연결되지 않음 또는 조회 실패"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "되돌림 필요: 공유 풀 등록값 다름"
v96 || stop "되돌림 필요: Flyway 최고 버전이 96 이 아님"
[ "$($PSQL -At -c "SELECT string_agg(version || ':' || success || ':' || checksum, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::int >= 95")" = "96:true:$V96SUM" ] || stop "되돌림 필요: V95 이후 이력이 V96 한 줄(성공·체크섬 $V96SUM)이 아님"
svc | tee "$M/svc-deploy.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-deploy.txt") || stop "되돌림 필요: backend 외 서비스가 바뀜"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-deploy.txt"
touch "$M/ok-U2"; echo "✅ U2 통과 — 5~10분 뒤 U3(frontend 는 아직 구판 — 새 모의 세션을 만들지 않는다)"
)
