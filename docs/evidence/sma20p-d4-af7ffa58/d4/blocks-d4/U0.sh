(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "$M" = "$HOME/maint-sma20p" ] && [ "${UB:-}" = SMA20PD4 ] && [ "$NEW" = af7ffa58960fa1392dee00397924827b4e4e6cd5 ] && [ -n "${JARN:-}" ] && type fresh_idle > /dev/null 2>&1 && type n21mark > /dev/null 2>&1 && type n21after > /dev/null 2>&1 && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 && type entryobs > /dev/null 2>&1 && type n17obs > /dev/null 2>&1 && type n19obs > /dev/null 2>&1 && type n20obs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → SMA20 PAPER 후보 초안 D4 U 변수"
case "$IMGOLD" in sha256:*) ;; *) stop "IMGOLD 가 sha256: 이미지 ID 가 아님 — 승인된 U 변수 원문이 아님(초안 D1~D4 는 실행하지 않는다)" ;; esac
[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 시작했다 — U0 를 다시 하지 않는다. ls -l $M 를 보고"
kwin "$(date +%s)" || stop "시작 시각·정기 보고 확인 — 위 줄"
[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "HEAD 가 b5cd1d0 가 아니거나 추적 파일 변경 있음"
date '+%F %T %Z' | tee "$M/t-before.txt"; date '+%F %T%:z' > "$M/maint_start.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-before.txt"
git fetch origin
git cat-file -e "$NEW^{commit}" || stop "후보 af7ffa5 없음"
git merge-base --is-ancestor $OLD $NEW || stop "b5cd1d0 가 후보의 조상이 아님"
[ "$(git diff --name-only $OLD $NEW)" = "$DIFFN" ] || stop "diff 가 정확히 제품 Java 5·시험 Java 7, 12개 파일이 아님(마이그레이션·설정 포함 금지)"
svc | tee "$M/svc-before.txt"
IMG0=$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)"); echo "$IMG0" | tee "$M/image-before.txt"
[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-o13n21/image-new.txt)" ] || stop "실행 이미지가 #27 배포 이미지(~/maint-o13n21/image-new.txt)와 다름"
[ "$(docker image inspect --format '{{.Id}}' $REPO:ea44900)" = "$IMGPREV" ] || stop "이전 복구 태그 $REPO:ea44900 이 #19 이미지(61804654…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:b1d7664)" = "$IMGPREV2" ] || stop "이전 복구 태그 $REPO:b1d7664 이 #20 이미지(fd3ac351…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:6995c52)" = "$IMGPREV3" ] || stop "이전 복구 태그 $REPO:6995c52 이 #21 이미지(67294e80…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:fe01207)" = "$IMGPREV4" ] || stop "이전 복구 태그 $REPO:fe01207 이 #22 이미지(9cb5d84f…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:1103f48)" = "$IMGPREV5" ] || stop "이전 복구 태그 $REPO:1103f48 이 #23 이미지(9818254c…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:7a94472)" = "$IMGPREV6" ] || stop "이전 복구 태그 $REPO:7a94472 이 #24 이미지(e51b512a…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:ee509cf)" = "$IMGPREV7" ] || stop "이전 복구 태그 $REPO:ee509cf 이 #25 이미지(200f2f57…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:1899b49)" = "$IMGPREV8" ] || stop "이전 복구 태그 $REPO:1899b49 이 #26 이미지(426a09a2…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:latest)" = "$IMG0" ] || stop "$REPO:latest 가 실행 이미지와 다름"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "공유 풀 등록값이 STAGE_1·100000·20000·3 과 다름"
v94 || stop "Flyway 최고 버전이 94 가 아님"
TZDB=$($PSQL -At -c "SHOW TimeZone") || stop "DB TimeZone 조회 실패"
printf 'DB 조회 세션 TimeZone: %s\n' "$TZDB" | tee "$M/timezone-before.txt"
case "$TZDB" in UTC|Etc/UTC|GMT|Etc/GMT|Asia/Seoul) ;; *) stop "DB TimeZone 이 검증한 UTC/Asia-Seoul 계열이 아님 — timezone-before.txt 를 보고" ;; esac
$DC exec -T backend date '+%Y-%m-%d %H:%M:%S %Z %z' | tee "$M/container-time-before.txt"

sid203 || stop "SID 203·기준 행·EG·0 확인 중 다른 것 있음 — F1 과 같은 조회로 보고"
sc | tee "$M/s-before.txt"; [ "$(cat "$M/s-before.txt")" = "66 | 63 | 3 | -" ] || stop "검증 66세션 상태 다름"
[ "$(h256 "$OPS/sid_ledger.sql")" = "$LSUM" ] && [ "$(cat "$OPS/SID_LEDGER_SHA")" = "$LS" ] || stop "설치 장부가 8bfe8a9 판이 아님"
[ "$(cat "$OPS/INSTALLED_SHA")" = "$CK" ] && [ "$(h256 "$OPS/daily_check.sh")" = "$CKSH" ] && [ "$(h256 "$OPS/daily_checks.sql")" = "$CKSQL" ] || stop "정기 점검 설치본이 c669ccd 판이 아님"
crontab -l > "$M/crontab-before.txt" || stop "crontab 조회 실패"
[ "$(h256 "$M/crontab-before.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름"
( cd ~/sid203-ledger && sha256sum -- * ) > "$M/ledger-files-before.sha"; cat "$M/ledger-files-before.sha"
$PSQL -At -c "$BTCIN" > "$M/btc-before.txt" || stop "BTC 입력 조회 실패"
echo "== BTC H1 충족|최신 완료 봉|필요 봉|최근 20일 마감 봉: $(cat "$M/btc-before.txt")"
btcok "$M/btc-before.txt" || stop "BTC 입력 미충족 — 배포하지 않고 보고(N08 때의 정체 허용 예외는 이번에 없다)"
$PSQL -At -c "$NEWSCFG" > "$M/news-before.txt" || stop "뉴스 설정 조회 실패"
[ -s "$M/news-before.txt" ] || stop "뉴스 설정이 비었음"
echo "== 뉴스 출처(종류|사용|간격 분|last_fetched_at epoch)"; cat "$M/news-before.txt"
qc > "$M/q-before.txt" 2>&1; sed -n '/== Q3/,$p' "$M/q-before.txt" | grep -vE '^-+|^\s*$'
git show $QS:scripts/ops/maint_verify.py > "$M/maint_verify.py"
$DC logs --no-color backend > "$M/backend-before.log"
grep " ERROR " "$M/backend-before.log" | grep -E "$NEWSERR" > "$M/news-err-before.txt" || true
echo "== 배포 전 기준선: 실행 중 backend 로그의 뉴스 출처 ERROR $(wc -l < "$M/news-err-before.txt")줄(출처별)"
grep -oE "$NEWSERR" "$M/news-err-before.txt" | sort | uniq -c || true
tail -n 3 "$M/news-err-before.txt" | cut -c1-200
httpobs "$M/backend-before.log" > "$M/http-before.txt" && HB=0 || HB=$?
echo "== 배포 전 기준선: 출처 HTTP 비200 줄(httpobs 종료 $HB — 기록만)"; cat "$M/http-before.txt"
holdobs "$M/backend-before.log" > "$M/hold-before.txt" && HD=0 || HD=$?
[ "$HD" != 1 ] || stop "🔴 P0 — 배포 전 로그에 연결 획득 시간 초과 줄이 있다. 배포하지 않고 hold-before.txt 를 즉시 보고"
echo "== 배포 전 기준선: HOLD 질의 장기 대여(holdobs 종료 $HD — 3 이어도 멈추지 않는다, 기록만)"; cat "$M/hold-before.txt"
entryobs "$M/backend-before.log" > "$M/entry-before.txt" && ED=0 || ED=$?
echo "== 배포 전 기준선: N15·N16 줄(entryobs 종료 $ED — b5cd1d0 실행 중 로그, 기록만)"; cat "$M/entry-before.txt"
n17obs "$M/backend-before.log" > "$M/n17-before.txt" || stop "n17obs 인자 오류 — n17-before.txt 를 보고"
echo "== 배포 전 기준선: N17 줄(b5cd1d0 실행 중 로그 — 옛 문구도 같은 정규식으로 센다, 기록만)"; cat "$M/n17-before.txt"
n19obs "$M/backend-before.log" > "$M/n19-before.txt" || stop "n19obs 인자 오류 — n19-before.txt 를 보고"
echo "== 배포 전 기준선: N19 줄(b5cd1d0 실행 중 로그, 자연 발생만, 기록만)"; cat "$M/n19-before.txt"
n20obs "$M/backend-before.log" > "$M/n20-before.txt" || stop "n20obs 인자 오류 — n20-before.txt 를 보고"
echo "== 배포 전 기준선: N20 줄(b5cd1d0 실행 중 로그, 자연 발생만, 기록만)"; cat "$M/n20-before.txt"
python3 scripts/prospective/start_66.py alerts --days 1 --no-mark > "$M/alerts-before.txt" 2>&1 && AE=0 || AE=$?
echo "경보 종료코드 $AE"
alertsok "$AE" "$M/alerts-before.txt" || stop "경보 조회가 끝까지 돌지 않음 — alerts-before.txt 를 보고"
grep -E '^(🔴|⚪)' "$M/alerts-before.txt" || true
echo "== 감시 스크립트 불변 확인(D4 는 start_66.py·시험 파일을 바꾸지 않는다)"
[ "$(git rev-parse "HEAD:$NF")" = "$NBOLD" ] && [ "$(h256 "$NF")" = "$NHOLD" ] || stop "start_66.py 가 b5cd1d0 원문(68d441ce…)과 다름"
[ -e "$NT" ] && [ "$(git rev-parse "HEAD:$NT")" = "$(git rev-parse "$NEW:$NT")" ] && [ "$(git rev-parse "$NEW:$NF")" = "$NBNEW" ] || stop "후보의 start_66.py·시험 파일 블롭이 운영과 다름"
FI=0; fresh_idle u0 || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다 — 끝난 뒤 U0 부터(끊지 않는다)"; stop "프로세스 조회 실패 — 회차 없음으로 보지 않는다. pgrep-u0.* 를 보고"; }
cp -p "$NF" "$M/start_66.py.before"
for s in freshness_streak.json freshness_snap.json; do [ ! -f "scripts/prospective/$s" ] || cp -p "scripts/prospective/$s" "$M/$s.before"; done
touch "$M/ok-U0"; echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"
)
