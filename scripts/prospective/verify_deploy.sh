#!/usr/bin/env bash
# 배포 검증 + 기준점 채증 — docs/SUPERTREND_VALIDATION_PREREG.md §5 "보존할 기준점"
#
# 🔴 왜 이 스크립트가 필요한가
#   GET /api/v1/strategies/types 에 팔 A 가 보이는 것은 **3cb4d87 이상**이라는 뜻일 뿐,
#   RANGE 게이트 수정(58cc69f)이 들어갔다는 증거가 아니다. 두 커밋을 구별하지 못한다.
#   배포된 jar 안의 RangeRegimeGate 클래스에 팔 A 이름 상수가 있는지를 직접 본다 —
#   수정 전 클래스에는 그 문자열이 없다.
#
# 사용: bash scripts/prospective/verify_deploy.sh
set -uo pipefail

COMPOSE="${COMPOSE:-docker-compose.prod.yml}"
SERVICE="${SERVICE:-backend}"
OUT="scripts/prospective/deploy_baseline.txt"

echo "=== 전향 검증 배포 검증 — $(date -Is) ==="

echo
echo "① 저장소 HEAD"
git log --oneline -1
HEAD_SHA=$(git rev-parse HEAD)

echo
echo "② 컨테이너·이미지 식별자"
BE=$(docker compose -f "$COMPOSE" ps -q "$SERVICE" 2>/dev/null)
if [ -z "$BE" ]; then
  echo "   ✗ $SERVICE 컨테이너를 찾을 수 없다 (COMPOSE=$COMPOSE)."
  exit 2
fi
IMG=$(docker inspect -f '{{.Image}}' "$BE")
C_AT=$(docker inspect -f '{{.Created}}' "$BE")
I_AT=$(docker image inspect -f '{{.Created}}' "$IMG" 2>/dev/null || echo "?")
echo "   컨테이너   $BE"
echo "   이미지     $IMG"
echo "   컨테이너 생성 $C_AT   (UTC)"
echo "   이미지 생성   $I_AT   (UTC)"

echo
echo "③ 🔴 배포된 jar 안의 RANGE 게이트 — 팔 A 가 차단 목록에 있는가"
GATE=$(docker exec "$BE" sh -c '
  cd /tmp 2>/dev/null || exit 9
  unzip -o -q /app/app.jar "BOOT-INF/lib/core-engine*.jar" 2>/dev/null || exit 9
  unzip -p BOOT-INF/lib/core-engine*.jar \
    com/cryptoautotrader/core/selector/RangeRegimeGate.class 2>/dev/null \
    | grep -ac COMPOSITE_MTF_MOMENTUM_CLOSED' 2>/dev/null)
RC=$?
if [ "$RC" = "9" ] || [ -z "$GATE" ]; then
  GATE="?"
  echo "   ? jar 을 열 수 없다 (unzip 부재 등) — 이미지 생성 시각으로 판단할 것."
elif [ "$GATE" = "0" ]; then
  echo "   ✗ 없다 — 58cc69f 이전 코드로 빌드된 이미지다. **재빌드 필요.**"
else
  echo "   ✔ 있다 ($GATE) — RANGE 게이트 수정이 들어간 이미지다."
fi

echo
echo "④ 참고 — 팔 A 프리셋 자체 (3cb4d87)"
CLOSED=$(docker exec "$BE" sh -c '
  cd /tmp 2>/dev/null || exit 9
  unzip -p BOOT-INF/lib/core-engine*.jar \
    com/cryptoautotrader/core/selector/CandleDownsampler.class 2>/dev/null \
    | grep -ac downsampleClosedOnly' 2>/dev/null) || CLOSED="?"
echo "   downsampleClosedOnly 존재: ${CLOSED:-?}"

echo
if [ "$GATE" = "1" ]; then
  echo "🟢 판정: 배포 검증 통과 — probe 로 넘어간다."
else
  echo "🔴 판정: 통과하지 못했다 — 세션을 만들지 않는다. 재빌드 후 다시 실행한다."
fi

{
  echo "# 전향 검증 배포 기준점 — $(date -Is)"
  echo "repo_head=$HEAD_SHA"
  echo "container=$BE"
  echo "image=$IMG"
  echo "container_created_utc=$C_AT"
  echo "image_created_utc=$I_AT"
  echo "range_gate_has_arm_a=$GATE"
  echo "downsample_closed_only=${CLOSED:-?}"
} > "$OUT"
echo
echo "기준점 저장: $OUT  (사전 등록 문서에 이 값을 옮겨 적는다)"
[ "$GATE" = "1" ]
