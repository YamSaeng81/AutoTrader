(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "$M" = "$HOME/maint-n15n16" ] && [ "${RV:-}" = 81 ] && [ "$NEW" = 1103f48505a1457029f6555547a87aa986535738 ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 && type entryobs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 81 R 변수"
[ ! -e "$M/ok-R1" ] || stop "R1 을 이미 시작했다 — R0 를 다시 하지 않는다. ls -l $M 를 보고"
kwin "$(date +%s)" || stop "시작 시각·정기 보고 확인 — 위 줄"
[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "HEAD 가 fe01207 가 아니거나 추적 파일 변경 있음"
date '+%F %T %Z' | tee "$M/t-before.txt"; date '+%F %T%:z' > "$M/maint_start.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-before.txt"
git fetch origin
git cat-file -e "$NEW^{commit}" || stop "후보 1103f48 없음"
git merge-base --is-ancestor $OLD $NEW || stop "fe01207 가 후보의 조상이 아님"
[ "$(git diff --name-only $OLD $NEW)" = "$DIFFN" ] || stop "diff 가 정확히 N15·N16 12개 파일이 아님(마이그레이션·설정 포함 금지)"
svc | tee "$M/svc-before.txt"
IMG0=$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)"); echo "$IMG0" | tee "$M/image-before.txt"
[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n13/image-new.txt)" ] || stop "실행 이미지가 #22 배포 이미지(9cb5d84f…)와 다름"
[ "$(docker image inspect --format '{{.Id}}' $REPO:ea44900)" = "$IMGPREV" ] || stop "이전 복구 태그 $REPO:ea44900 이 #19 이미지(61804654…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:b1d7664)" = "$IMGPREV2" ] || stop "이전 복구 태그 $REPO:b1d7664 이 #20 이미지(fd3ac351…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:6995c52)" = "$IMGPREV3" ] || stop "이전 복구 태그 $REPO:6995c52 이 #21 이미지(67294e80…)가 아님 — 보고"
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
echo "== 배포 전 기준선: N15·N16 줄(entryobs 종료 $ED — fe01207 에는 이 줄들이 없으므로 미관측이 기대, 기록만)"; cat "$M/entry-before.txt"
python3 scripts/prospective/start_66.py alerts --days 1 --no-mark > "$M/alerts-before.txt" 2>&1 && AE=0 || AE=$?
echo "경보 종료코드 $AE"
alertsok "$AE" "$M/alerts-before.txt" || stop "경보 조회가 끝까지 돌지 않음 — alerts-before.txt 를 보고"
grep -E '^(🔴|⚪)' "$M/alerts-before.txt" || true
touch "$M/ok-R0"; echo "✅ R0 통과 — 30분 안에 R1"
)
