# UF2(V159D4 새 블록) — U3(deploy) 통과 뒤 frontend 만 새 이미지로 교체한다. backend 먼저·frontend 나중이 기본안이다.
# 사이 구간(구 frontend + 새 API)은 키 없는 세션 표시·정지 이동이 같다(로컬 대역 fe-compat-old A·B). 새 모드 CLOSING 은 구 화면이 0원·보유 0 으로 보이므로
# 양쪽 설치·관측이 끝나기 전에는 새 모의 세션을 만들지 않는다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V159D4 ] || stop "U 변수가 v1.159 통합 후보 초안 D4 판이 아님 — F → G → v1.159 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
[ -e "$M/ok-U3-deploy" ] || stop "U3(deploy) 미통과 — frontend 는 backend 관측 뒤"
[ ! -e "$M/t-rollback.utc" ] || stop "되돌림을 시작했다 — frontend 를 바꾸지 않는다"
[ ! -e "$M/ok-UF2" ] || stop "UF2 를 이미 했다 — U4"
FE0=$(cat "$M/image-fe-before.txt"); FE1=$(cat "$M/image-fe-new.txt"); FEREF=$(cat "$M/fe-ref-before.txt")
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q frontend)")" = "$FE0" ] || stop "실행 중 frontend 가 image-fe-before 가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' "$FEREF")" = "$FE1" ] || stop "frontend 이미지 이름이 image-fe-new 를 가리키지 않음 — 보고"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$(cat "$M/image-new.txt")" ] || stop "backend 가 새 이미지가 아님 — 보고"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 다른 것 있음"
[ "$(sc)" = "66 | 63 | 3 | -" ] || stop "검증 66세션 상태 다름"
svc | tee "$M/svc-pre-fe.txt"
date '+%F %T %Z' | tee "$M/t-fe.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-fe.utc"
echo "== 여기부터 frontend 재생성 — 이 뒤의 멈춤은 'frontend 되돌림 필요'(U되돌림의 frontend 단계)"
$DC up -d --no-build --no-deps frontend || stop "frontend 되돌림 필요: 재생성 실패"
sleep 10
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q frontend)")" = "$FE1" ] || stop "frontend 되돌림 필요: 새 컨테이너 이미지가 image-fe-new 와 다름"
svc | tee "$M/svc-fe.txt"
diff <(grep -v '^frontend ' "$M/svc-pre-fe.txt") <(grep -v '^frontend ' "$M/svc-fe.txt") || stop "frontend 외 서비스가 바뀜 — 보고"
C=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:3000/) || C=000; echo "frontend / 응답 $C" | tee "$M/fe-http.txt"
case "$C" in 200|302|307|308) ;; *) stop "frontend 되돌림 필요: / 응답 $C" ;; esac
$DC logs --no-color --since "$(cat "$M/t-fe.utc")" frontend > "$M/frontend-fe.log"
! grep -qiE "error|unhandled" "$M/frontend-fe.log" || { grep -iE "error|unhandled" "$M/frontend-fe.log" | cut -c1-200; stop "frontend 로그에 오류 줄 — 바로 되돌리지 말고 위 줄을 보고"; }
touch "$M/ok-UF2"; echo "✅ UF2 통과 — 화면 확인(목록·상세 표시) 뒤 U4. 새 모의 세션 생성은 별도 승인"
)
