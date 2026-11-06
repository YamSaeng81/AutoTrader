(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = 98 ] || stop "U 변수가 v1.130(§5-9 98 블록) #26 최종안 판이 아님 — F → G → §5-9 98 U 변수"
[ -e "$M/ok-U3-deploy" ] || stop "U3 미통과"
S=$(cat "$M/t-deploy.utc")
[ $(( $(date +%s) - $(date -d "$S" +%s) )) -ge 1800 ] || stop "아직 30분 전 — 완료로 판정하지 않는다"
date '+%F %T %Z' | tee "$M/t-after30.txt"
$DC ps backend
$PSQL -At -c "$NEWSCFG" > "$M/news-after30.txt" || stop "뉴스 설정 조회 실패 — 1분 뒤 U4 를 다시"
OBS=$(date +%s); echo "$OBS" > "$M/obs-after30.txt"   # 관측 시각 = 뉴스 설정을 읽은 시각(로그는 그 뒤에 읽는다)
$DC logs --no-color --since "$S" backend > "$M/app-after30.log"
echo "== HOLD 질의 장기 대여·연결 획득(기동 뒤 30분 로그 — 시작 catchup 과 5분 뒤 첫 정기 회차를 포함 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-after30.log" | tee "$M/holdobs-after30.txt" && HQ=0 || HQ=$?
[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)"
grep " ERROR " "$M/app-after30.log" | grep -v "캔들 최신성" | grep -v -E "$NOT404" > "$M/errors-all-after30.txt" || true
grep -E "$NEWSERR" "$M/errors-all-after30.txt" > "$M/errors-news-after30.txt" || true
grep -v -E "$NEWSERR" "$M/errors-all-after30.txt" > "$M/errors-after30.txt" || true
[ ! -s "$M/errors-after30.txt" ] || stop "세 마켓 캔들 404·뉴스 출처 ERROR 밖의 ERROR — errors-after30.txt 를 보고"
if [ -s "$M/errors-news-after30.txt" ]; then
  echo "🔴 뉴스 출처 ERROR $(wc -l < "$M/errors-news-after30.txt")줄 — 미분류 🔴 그대로, 이것만으로 되돌리지 않는다. 원문과 배포 전 기준선 $(wc -l < "$M/news-err-before.txt")줄을 함께 보고"
  cut -c1-200 "$M/errors-news-after30.txt"
fi
! grep -qE "🔴 (공유 풀 한도|위험 한도)" "$M/app-after30.log" || stop "되돌림 필요: 🔴 공유 풀/위험 한도 줄"
v94 || stop "되돌림 필요: Flyway 최고 버전이 94 가 아님"
grep -E "BTC 급락 관문 H1 판정 (불가|재개)" "$M/app-after30.log" | cut -c1-140 || true
btcgate "$M/app-after30.log" || stop "BTC 관문 마지막 상태가 판정 불가 — 바로 되돌리지 말고 위 출력을 보고"
python3 "$M/maint_verify.py" candle "$M/app-after30.log" "$M/backend-before.log" || stop "판정 불가 대상이 남아 있음 — 위 출력을 보고"
qc > "$M/q-after30.txt" 2>&1; sed -n '/== Q3/,$p' "$M/q-after30.txt" | grep -vE '^-+|^\s*$'
sc | tee "$M/s-after30.txt"; diff "$M/s-before.txt" "$M/s-after30.txt" || stop "되돌림 필요: 검증 세션 상태 바뀜"
svc | tee "$M/svc-after30.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-after30.txt") || stop "되돌림 필요: backend 외 서비스가 바뀜"
[ "$(grep '^backend ' "$M/svc-deploy.txt")" = "$(grep '^backend ' "$M/svc-after30.txt")" ] || stop "되돌림 필요: backend 재시작 있음"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 바뀐 것 있음 — 보고"
[ "$(h256 "$OPS/sid_ledger.sql")" = "$LSUM" ] && [ "$(cat "$OPS/SID_LEDGER_SHA")" = "$LS" ] || stop "설치 장부가 바뀜 — 보고"
[ "$(cat "$OPS/INSTALLED_SHA")" = "$CK" ] && [ "$(h256 "$OPS/daily_check.sh")" = "$CKSH" ] && [ "$(h256 "$OPS/daily_checks.sql")" = "$CKSQL" ] || stop "정기 점검 설치본이 바뀜 — 보고"
crontab -l | cmp -s - "$M/crontab-before.txt" || stop "cron 이 U0 때와 다름 — crontab -l 을 보고"
( cd ~/sid203-ledger && sha256sum --quiet -c "$M/ledger-files-before.sha" ) || stop "과거 장부 기록 파일이 바뀜 — 보고"
$PSQL -At -c "$BTCIN" > "$M/btc-after30.txt" || stop "BTC 입력 조회 실패 — 1분 뒤 U4 를 다시"
echo "== BTC H1 U3 $(cat "$M/btc-deploy.txt") · 지금 $(cat "$M/btc-after30.txt")"
btcok "$M/btc-after30.txt" || stop "BTC 입력 미충족 — 바로 되돌리지 말고 위 출력을 보고"
echo "== 뉴스 출처별(기동 뒤 30분 로그)"
newsobs "$M/app-after30.log" "$M/news-before.txt" "$M/news-after30.txt" "$(date -d "$S" +%s)" "$OBS" | tee "$M/newsobs-after30.txt" && NO=0 || NO=$?
[ "$NO" = 0 ] || [ "$NO" = 3 ] || stop "뉴스 관측 이상(종료 $NO) — 되돌리지 말고 위 출력을 보고"
if [ "$NO" = 3 ]; then cp "$M/newsobs-after30.txt" "$M/news-check-after30.txt"; echo "⚪ 뉴스 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고(정상 관측 아님)"; fi
httpobs "$M/app-after30.log" | tee "$M/httpobs-after30.txt" && HO=0 || HO=$?
[ "$HO" = 0 ] || [ "$HO" = 3 ] || stop "출처 HTTP 비200 줄이 고정 한 줄이 아님(종료 $HO) — 되돌리지 말고 위 출력을 보고"
if [ "$HO" = 3 ]; then cp "$M/httpobs-after30.txt" "$M/http-check-after30.txt"; echo "⚪ HTTP 줄 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고"; fi
grep -q "시작 Catchup 완료" "$M/app-after30.log" || echo "⚪ 시작 catchup 완료 줄이 아직 없다 — HOLD 질의 개선 관측 미완(되돌리지 않는다)"
if [ "$HQ" = 3 ]; then cp "$M/holdobs-after30.txt" "$M/hold-check-after30.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi
echo "== N15·N16 줄(기동 뒤 30분 로그 · 자연 발생만)"
entryobs "$M/app-after30.log" | tee "$M/entryobs-after30.txt" && EQ=0 || EQ=$?
[ "$EQ" = 0 ] || stop "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 바로 되돌리지 말고 위 출력과 원문 줄을 보고"
echo "== N17 줄(기동 뒤 30분 로그 · 자연 발생만)"
n17obs "$M/app-after30.log" | tee "$M/n17obs-after30.txt" || stop "n17obs 인자 오류 — 위 출력을 보고"
echo "== N19 줄(기동 뒤 30분 로그 · 자연 발생만)"
n19obs "$M/app-after30.log" | tee "$M/n19obs-after30.txt" || stop "n19obs 인자 오류 — 위 출력을 보고"
echo "== N20 줄(기동 뒤 30분 로그 · 자연 발생만)"
n20obs "$M/app-after30.log" | tee "$M/n20obs-after30.txt" || stop "n20obs 인자 오류 — 위 출력을 보고"
python3 scripts/prospective/start_66.py alerts --since "$S" --no-mark > "$M/alerts-after30.txt" 2>&1 && AE=0 || AE=$?
echo "경보 종료코드 $AE (1 = 🔴 경보 있음 — 이것만으로 멈추지 않고 네 갈래로 대조)"
alertsok "$AE" "$M/alerts-after30.txt" || stop "경보 조회가 끝까지 돌지 않음 — alerts-after30.txt 를 보고"
echo "== 경보 (지금)"; cat "$M/alerts-after30.txt"
echo "== 경보 (배포 전 기준선)"; cat "$M/alerts-before.txt"
touch "$M/ok-U4"; echo "✅ U4 통과 — U0~U4 출력을 보고(F 변수·F1·N06 핀은 총괄 판단 뒤 개발 에이전트가 갱신)"
[ ! -e "$M/news-check-after30.txt" ] || echo "⚪ 단, 뉴스 확인 필요가 남았다 — 뉴스 관측 완료로 보고하지 않는다(news-check-after30.txt)"
[ ! -e "$M/http-check-after30.txt" ] || echo "⚪ 단, HTTP 줄 확인 필요가 남았다 — N12 운영 관측 완료로 보고하지 않는다(http-check-after30.txt)"
[ ! -e "$M/hold-check-after30.txt" ] || echo "⚪ 단, 장기 대여 경고가 남았다 — N13 개선 확인으로 보고하지 않는다(hold-check-after30.txt)"
grep -q "^N15: 미관측" "$M/entryobs-after30.txt" && echo "⚪ N15 만료 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(유도하지 않는다)" || true
grep -q "^N16: 미관측" "$M/entryobs-after30.txt" && echo "⚪ N16 가격 아닌 시세 미관측 — 동작 관측은 미관측으로 보고(유도하지 않는다). 미래 관측 시각 보정은 로그 줄이 없어 이 함수로 보지 않는다" || true
grep -q "^N17: 미관측" "$M/n17obs-after30.txt" && echo "⚪ N17 REST 강제 갱신 가격 이상 미관측 — 동작 관측은 미관측으로 보고(INVALID_PRICE 를 주입하지 않는다)" || true
grep -q "^N19: 미관측" "$M/n19obs-after30.txt" && echo "⚪ N19 REST 전역 폴백 항목 건너뜀 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(WS 를 끊거나 이상 응답을 주입하지 않는다)" || true
grep -q "^N20: 미관측" "$M/n20obs-after30.txt" && echo "⚪ N20 보유 시세 보강 항목 건너뜀·처리 실패 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(이상 응답·예외를 주입하지 않는다)" || true
)
