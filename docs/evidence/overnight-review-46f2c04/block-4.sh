(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"
if [ -e "$M/t-rollback.utc" ]; then S=$(cat "$M/t-rollback.utc"); EXP=$(cat "$M/image-before.txt"); T=rollback; else [ -e "$M/ok-P2" ] || stop "P2 미통과"; S=$(cat "$M/t-deploy.utc"); EXP=$(cat "$M/image-new.txt"); T=deploy; fi
[ $(( $(date +%s) - $(date -d "$S" +%s) )) -ge 300 ] || stop "기동 5분 전 — 5분이 지난 뒤 P3"
$DC ps backend
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$EXP" ] || stop "실행 이미지가 기대와 다름"
$PSQL -At -c "$NEWSCFG" > "$M/news-$T.txt" || stop "뉴스 설정 조회 실패 — 1분 뒤 P3 를 다시"
OBS=$(date +%s); echo "$OBS" > "$M/obs-$T.txt"   # 관측 시각 = 뉴스 설정을 읽은 시각(로그는 그 뒤에 읽는다)
$DC logs --no-color --since "$S" backend > "$M/app-$T.log"
grep -E "Schema .* is up to date|Migrating schema|Successfully applied|매도 격리 점검|Started CryptoAutoTrader" "$M/app-$T.log" | head -10
grep -q "Started CryptoAutoTrader" "$M/app-$T.log" || stop "되돌림 필요: 기동 완료 줄 없음"
! grep -qE "Migrating schema|Successfully applied" "$M/app-$T.log" || stop "되돌림 필요: 마이그레이션이 돌았다(이번 배포에는 없어야 함)"
v94 || stop "되돌림 필요: Flyway 최고 버전이 94 가 아님"
grep -qF "공유 풀 한도 확인: 단계=STAGE_1 총상한=100000 포지션당=20000 동시보유=3" "$M/app-$T.log" || stop "되돌림 필요: 공유 풀 등록 로그 없음"
grep -qF "위험 한도 확인: 단계 STAGE_1 총 자본 100000 기준 누적 15.00%·24시간 3.00%" "$M/app-$T.log" || stop "되돌림 필요: 위험 한도 로그 없음"
! grep -qE "🔴 (공유 풀 한도|위험 한도)" "$M/app-$T.log" || stop "되돌림 필요: 🔴 공유 풀/위험 한도 줄"
grep " ERROR " "$M/app-$T.log" | grep -v "캔들 최신성" | grep -v -E "$NOT404" > "$M/errors-all-$T.txt" || true
grep -E "$NEWSERR" "$M/errors-all-$T.txt" > "$M/errors-news-$T.txt" || true
grep -v -E "$NEWSERR" "$M/errors-all-$T.txt" > "$M/errors-$T.txt" || true
[ ! -s "$M/errors-$T.txt" ] || stop "세 마켓 캔들 404·뉴스 출처 ERROR 밖의 ERROR — errors-$T.txt 를 보고"
if [ -s "$M/errors-news-$T.txt" ]; then
  echo "🔴 뉴스 출처 ERROR $(wc -l < "$M/errors-news-$T.txt")줄 — 미분류 🔴 그대로, 이것만으로 되돌리지 않는다. 원문과 배포 전 기준선 $(wc -l < "$M/news-err-before.txt")줄을 함께 보고"
  cut -c1-200 "$M/errors-news-$T.txt"
fi
grep -E "BTC 급락 관문 H1 판정 (불가|재개)" "$M/app-$T.log" | cut -c1-140 || true
btcgate "$M/app-$T.log" || stop "BTC 관문 마지막 상태가 판정 불가 — 바로 되돌리지 말고 위 출력을 보고"
python3 "$M/maint_verify.py" candle "$M/app-$T.log" "$M/backend-before.log" || stop "기동 뒤 판정 불가 대상이 아직 재개되지 않음 — 위 출력을 보고"
qc > "$M/q-$T.txt" 2>&1; sed -n '/== Q3/,$p' "$M/q-$T.txt" | grep -vE '^-+|^\s*$'
sc | tee "$M/s-$T.txt"; diff "$M/s-before.txt" "$M/s-$T.txt" || stop "되돌림 필요: 검증 세션 상태 바뀜"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 바뀐 것 있음 — 보고"
$PSQL -At -c "$BTCIN" > "$M/btc-$T.txt" || stop "BTC 입력 조회 실패 — 1분 뒤 P3 를 다시"
echo "== BTC H1 배포 전 $(cat "$M/btc-before.txt") · 지금 $(cat "$M/btc-$T.txt")"
if [ "$T" = deploy ]; then btcok "$M/btc-$T.txt" || stop "BTC 입력 미충족 — 바로 되돌리지 말고 위 출력을 보고(재기동 없이 P3 만 5분 뒤 한 번 다시)"; fi
echo "== 뉴스 출처별(기동 $(cut -c12-19 <<< "$S")Z 뒤 로그 · 첫 회차는 기동 3분 뒤)"
newsobs "$M/app-$T.log" "$M/news-before.txt" "$M/news-$T.txt" "$(date -d "$S" +%s)" "$OBS" | tee "$M/newsobs-$T.txt" && NO=0 || NO=$?
[ "$NO" = 0 ] || [ "$NO" = 3 ] || [ "$T" = rollback ] || stop "뉴스 관측 이상(종료 $NO) — 되돌리지 말고 위 출력을 보고"
if [ "$NO" = 3 ]; then cp "$M/newsobs-$T.txt" "$M/news-check-$T.txt"; echo "⚪ 뉴스 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고(정상 관측 아님)"; fi
httpobs "$M/app-$T.log" | tee "$M/httpobs-$T.txt" && HO=0 || HO=$?
[ "$HO" = 0 ] || [ "$HO" = 3 ] || [ "$T" = rollback ] || stop "출처 HTTP 비200 줄이 고정 한 줄이 아님(종료 $HO) — 되돌리지 말고 위 출력을 보고"
if [ "$HO" = 3 ]; then cp "$M/httpobs-$T.txt" "$M/http-check-$T.txt"; echo "⚪ HTTP 줄 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고"; fi
echo "== HOLD 질의 장기 대여·연결 획득(기동 뒤 로그 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-$T.log" | tee "$M/holdobs-$T.txt" && HQ=0 || HQ=$?
[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, P 되돌림 블록 준비됨)"
if [ "$HQ" = 3 ]; then cp "$M/holdobs-$T.txt" "$M/hold-check-$T.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi
touch "$M/ok-P3-$T"
if [ "$T" = deploy ]; then echo "✅ P3(deploy) 통과 — 기동 30분 뒤 P4"; else echo "✅ P3(rollback) 통과 — 6995c52 복귀 확인. P4 는 하지 않고 출력을 보고"; fi
)
