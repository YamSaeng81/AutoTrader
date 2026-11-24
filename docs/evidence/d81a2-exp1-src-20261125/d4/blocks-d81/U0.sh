(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "$M" = "$HOME/maint-d81-20261127-exp1" ] && [ "${UB:-}" = D81D4 ] && [ "$OLD" = 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d ] && [ "$NEW" = d81c3f2f91c0239aacb0e1176dde9b898f4d7466 ] && [ "${JARN:-}" = "(끝) 895 898" ] && [ "${JARM:-}" = 33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509 ] && [ -n "${ICSUM:-}" ] && type v96h > /dev/null 2>&1 && type pf3ok > /dev/null 2>&1 && type oldtag > /dev/null 2>&1 && type isimg > /dev/null 2>&1 && type same3 > /dev/null 2>&1 && type okimg > /dev/null 2>&1 && type fresh_idle > /dev/null 2>&1 && type n21mark > /dev/null 2>&1 && type n21after > /dev/null 2>&1 && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 && type entryobs > /dev/null 2>&1 && type n17obs > /dev/null 2>&1 && type n19obs > /dev/null 2>&1 && type n20obs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → D81D4 U 변수"
case "$IMGOLD" in sha256:*) ;; *) stop "IMGOLD 가 sha256: 이미지 ID 가 아님 — 승인된 U 변수 원문이 아님(초안 D1~D4 는 실행하지 않는다)" ;; esac
[ ! -e "$M" ] && [ ! -L "$M" ] || stop "기록 경로 $M 이 이미 있다 — 삭제·이름 변경·덮어쓰기 없이 멈춘다. 다른 시도 기록을 이 시도의 전제로 쓰지 않는다(새 시도 이름·새 출처로만 다시). ls -la $M 를 보고"   # v1.241 §2
mkdir "$M" || stop "🔴 기록 경로 $M 를 만들지 못함 — 기록 실패, 아무것도 바꾸지 않았다"
[ ! -e "$M/ok-U1" ] || stop "U1 을 이미 시작했다 — U0 를 다시 하지 않는다. ls -l $M 를 보고"
kwin "$(date +%s)" || stop "시작 시각·정기 보고 확인 — 위 줄"
[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "HEAD 가 운영 0e1d2a02 가 아니거나 추적 파일 변경 있음"
date '+%F %T %Z' | tee "$M/t-before.txt"; date '+%F %T%:z' > "$M/maint_start.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-before.txt"
git fetch origin
git cat-file -e "$NEW^{commit}" || stop "후보 d81c3f2f 없음"
git merge-base --is-ancestor $OLD $NEW || stop "0e1d2a02 가 후보의 조상이 아님"
[ "$(git diff --name-only $OLD $NEW)" = "$DIFFN" ] || stop "diff 가 정확히 기대 43경로(제품 Java 14·시험 29)가 아님"
svc | tee "$M/svc-before.txt"
IMG0=$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)"); echo "$IMG0" | tee "$M/image-before.txt"
[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-v172/image-new.txt)" ] || stop "실행 이미지가 운영 0e1d2a02 배포 이미지(~/maint-v172/image-new.txt, 2c5d8427)와 다름 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:ea44900)" = "$IMGPREV" ] || stop "이전 복구 태그 $REPO:ea44900 이 #19 이미지(61804654…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:b1d7664)" = "$IMGPREV2" ] || stop "이전 복구 태그 $REPO:b1d7664 이 #20 이미지(fd3ac351…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:6995c52)" = "$IMGPREV3" ] || stop "이전 복구 태그 $REPO:6995c52 이 #21 이미지(67294e80…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:fe01207)" = "$IMGPREV4" ] || stop "이전 복구 태그 $REPO:fe01207 이 #22 이미지(9cb5d84f…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:1103f48)" = "$IMGPREV5" ] || stop "이전 복구 태그 $REPO:1103f48 이 #23 이미지(9818254c…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:7a94472)" = "$IMGPREV6" ] || stop "이전 복구 태그 $REPO:7a94472 이 #24 이미지(e51b512a…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:ee509cf)" = "$IMGPREV7" ] || stop "이전 복구 태그 $REPO:ee509cf 이 #25 이미지(200f2f57…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:1899b49)" = "$IMGPREV8" ] || stop "이전 복구 태그 $REPO:1899b49 이 #26 이미지(426a09a2…)가 아님 — 보고"
OT=0; oldtag $REPO:b5cd1d0 "$IMGPREV9" || OT=$?   # D81D4: 옛 보존 태그 두 개 — 있음·같음 / 명시적 부재(기록만) / 다른 대상·조회 불명(멈춤). 만들거나 바꾸지 않는다
case "$OT" in 0) echo "옛 보존 태그 $REPO:b5cd1d0 = #27(52e12428…) 그대로" ;; 1) echo "⚪ 옛 보존 태그 $REPO:b5cd1d0 없음(API 404) — 이번 복구 대상이 아니므로 만들지 않는다. 기록만" ;; 2) stop "옛 보존 태그 $REPO:b5cd1d0 이 #27 이미지가 아닌 대상을 가리킨다 — 보고" ;; *) stop "옛 보존 태그 $REPO:b5cd1d0 조회 불명 — 없음으로 보지 않는다. 보고" ;; esac
OF=0; oldtag autotrader-frontend:b5cd1d0 "$FEPREV9" || OF=$?
case "$OF" in 0) echo "옛 보존 태그 autotrader-frontend:b5cd1d0 = 837db515… 그대로" ;; 1) echo "⚪ 옛 보존 태그 autotrader-frontend:b5cd1d0 없음(API 404) — 만들지 않는다. 기록만" ;; 2) stop "옛 보존 태그 autotrader-frontend:b5cd1d0 이 837db515… 가 아닌 대상을 가리킨다 — 보고" ;; *) stop "옛 보존 태그 autotrader-frontend:b5cd1d0 조회 불명 — 없음으로 보지 않는다. 보고" ;; esac
echo "옛 보존 태그 판정 backend:b5cd1d0=$OT frontend:b5cd1d0=$OF (0 같음 / 1 명시적 부재)" | tee "$M/oldtag-before.txt"
[ "$(docker image inspect --format '{{.Id}}' $REPO:latest)" = "$IMG0" ] || stop "$REPO:latest 가 실행 이미지와 다름"
R0=$(rchain "$IMG0") || stop "backend 실행 이미지의 대상·manifest·config 조회 실패 — 대응 불명, 보고"; echo "$R0" | tee "$M/image-before-chain.txt"
[ "$(ok3 "$M/image-before-chain.txt")" = "$IMGOLD $IMGOLDM $IMGOLDC" ] || stop "운영 backend 이미지(2c5d8427)의 manifest·config 가 11-16 기대값(b3a42cd2…·4016cc38…)과 다름 — 기대값을 자동 교체하지 않는다. 보고"
isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 컨테이너가 운영 2c5d8427 이미지 manifest·config 와 연결되지 않음 — 보고"
same3 $REPO:latest "$M/image-before-chain.txt" || stop "$REPO:latest 의 대상·manifest·config 가 실행 이미지와 다름 — 보고"
TKB=0; tagkeep $REPO:0e1d2a0 "$M/image-before-chain.txt" || TKB=$?   # D81D4 새 보존 태그(V172D4 R1 네 갈래): 0 있음·같음 / 1 명시적 부재(API 404) / 2 다른 대상 / 3 조회 불명
case "$TKB" in 0) echo "보존 태그 $REPO:0e1d2a0 있음 — 운영 backend 대응과 같음" ;; 1) echo "보존 태그 $REPO:0e1d2a0 없음(API 404 확인) — U1 이 만든다" ;; 2) stop "보존 태그 $REPO:0e1d2a0 이 이미 다른 이미지를 가리킨다 — 보고" ;; *) stop "보존 태그 $REPO:0e1d2a0 조회 불명(권한·daemon·API·형식) — 없음으로 보지 않는다. 보고" ;; esac
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "공유 풀 등록값이 STAGE_1·100000·20000·3 과 다름"
v96 || stop "Flyway 최고 버전이 96 이 아님 — 보고(D81D4 는 V96 기적용 상태에서만)"
v96h > "$M/v96-before.txt" && VH=0 || VH=$?; cat "$M/v96-before.txt"
[ "$VH" = 0 ] || stop "V95 이후 이력이 1|96:true:$V96SUM 하나가 아님 또는 조회 실패(종료 $VH) — 보고"
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
echo "== 배포 전 기준선: N15·N16 줄(entryobs 종료 $ED — 0e1d2a02 실행 중 로그, 기록만)"; cat "$M/entry-before.txt"
n17obs "$M/backend-before.log" > "$M/n17-before.txt" || stop "n17obs 인자 오류 — n17-before.txt 를 보고"
echo "== 배포 전 기준선: N17 줄(0e1d2a02 실행 중 로그 — 옛 문구도 같은 정규식으로 센다, 기록만)"; cat "$M/n17-before.txt"
n19obs "$M/backend-before.log" > "$M/n19-before.txt" || stop "n19obs 인자 오류 — n19-before.txt 를 보고"
echo "== 배포 전 기준선: N19 줄(0e1d2a02 실행 중 로그, 자연 발생만, 기록만)"; cat "$M/n19-before.txt"
n20obs "$M/backend-before.log" > "$M/n20-before.txt" || stop "n20obs 인자 오류 — n20-before.txt 를 보고"
echo "== 배포 전 기준선: N20 줄(0e1d2a02 실행 중 로그, 자연 발생만, 기록만)"; cat "$M/n20-before.txt"
python3 scripts/prospective/start_66.py alerts --days 1 --no-mark > "$M/alerts-before.txt" 2>&1 && AE=0 || AE=$?
echo "경보 종료코드 $AE"
alertsok "$AE" "$M/alerts-before.txt" || stop "경보 조회가 끝까지 돌지 않음 — alerts-before.txt 를 보고"
grep -E '^(🔴|⚪)' "$M/alerts-before.txt" || true
echo "== 감시 스크립트 불변 확인(D4 는 start_66.py·시험 파일을 바꾸지 않는다)"
[ "$(git rev-parse "HEAD:$NF")" = "$NBOLD" ] && [ "$(h256 "$NF")" = "$NHOLD" ] || stop "start_66.py 가 운영 0e1d2a02 원문(68d441ce…)과 다름"
[ -e "$NT" ] && [ "$(git rev-parse "HEAD:$NT")" = "$(git rev-parse "$NEW:$NT")" ] && [ "$(git rev-parse "$NEW:$NF")" = "$NBNEW" ] || stop "후보의 start_66.py·시험 파일 블롭이 운영과 다름"
FI=0; fresh_idle u0 || FI=$?
[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다 — 끝난 뒤 U0 부터(끊지 않는다)"; stop "프로세스 조회 실패 — 회차 없음으로 보지 않는다. pgrep-u0.* 를 보고"; }
cp -p "$NF" "$M/start_66.py.before"
for s in freshness_streak.json freshness_snap.json; do [ ! -f "scripts/prospective/$s" ] || cp -p "scripts/prospective/$s" "$M/$s.before"; done
echo "== 추가 확인 — 후보 V95 없음·V96 원문 같음·frontend 실행 이미지(교체하지 않음)·배포 전 읽기 v3(읽기 전용)"
[ "$(git ls-tree --name-only $NEW web-api/src/main/resources/db/migration/ | grep -cE '/V9[5-9]__')" = 1 ] && [ "$(git rev-parse "$NEW:$V96F")" = "$V96B" ] || stop "후보의 V95 이후 마이그레이션이 V96 하나·원문 5b92eef0… 가 아님(V95 포함 금지)"
case "$FEIMGOLD" in sha256:*) ;; *) stop "FEIMGOLD 가 미정 — 승인된 U 변수 원문이 아님(frontend 실행 이미지는 추정하지 않는다)" ;; esac
FE0=$(docker inspect --format '{{.Image}}' "$($DC ps -q frontend)"); echo "$FE0" | tee "$M/image-fe-before.txt"
[ "$FE0" = "$FEIMGOLD" ] || stop "실행 중 frontend 이미지가 FEIMGOLD 와 다름 — 보고"
docker inspect --format '{{.Config.Image}}' "$($DC ps -q frontend)" | tee "$M/fe-ref-before.txt"
[ "$(cat "$M/fe-ref-before.txt")" = autotrader-frontend ] || stop "frontend 이미지 이름이 autotrader-frontend 가 아님 — 보고"
RF=$(rchain autotrader-frontend) || stop "frontend 이미지 이름의 대상·manifest·config 조회 실패 — 대응 불명, 보고"; echo "$RF" | tee "$M/image-fe-before-chain.txt"
[ "$(ok3 "$M/image-fe-before-chain.txt")" = "$FETOLD $FEMOLD $FECOLD" ] || stop "frontend 이미지 이름이 11-16 기대 대상·manifest·config(9d7f123a…·13343943…·11d9a954…)와 다름 — 기대값을 자동 교체하지 않는다. 보고"
isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 컨테이너가 이미지 이름의 manifest·config 와 연결되지 않음 — 보고"
echo "frontend 는 이 판에서 빌드·태그·재생성하지 않는다 — 시작 기동 기록(svc-before.txt frontend 줄)과 U0 대응(image-fe-before-chain.txt)을 U4 까지 직접 대조한다"
gsok || stop "GS(관문·조회문 고정 커밋)가 미정이거나 없음"
git show "$GS:$PFP" > "$M/predeploy-read.sql" && [ "$(h256 "$M/predeploy-read.sql")" = "$PFSUM" ] || stop "배포 전 읽기 v3 원문·해시가 다름"
PR=0; $PSQL -At -F ' | ' < "$M/predeploy-read.sql" > "$M/predeploy-read.txt" 2>&1 || PR=$?   # v1.226 §3-1 A2 실패 반출 보정: 실패 원문(부분 R3 금액 행·오류 문구)은 서버 $M 에만 둔다
[ "$PR" = 0 ] || { PH=$(h256 "$M/predeploy-read.txt" 2>/dev/null) || PH=불명; stop "🔴 배포 전 읽기 v3 조회 실패(종료 $PR) — 원문은 콘솔에 내보내지 않고 서버에만 보존: $M/predeploy-read.txt sha256 $PH — 배포 전 중단·보고(빌드·체크아웃·재기동으로 넘어가지 않는다)"; }
# ── v1.241 §2 정상 R1=0 전용 — 판정·필수 기록 실패 보완(U0-maint2 756ba396 의 정상 부분만. 유지보수 예외·추가 진단 SQL 없음) ──
# 판정 파일·r1-gate·표시의 실패는 기록 실패로 멈춘다 — "보수적 사전 기준 미충족"(비용 미확정)과 섞지 않는다. 어느 쪽이든 ok-U0 없음(U1 금지)
PV=0; pf3ok "$M/predeploy-read.txt" > "$M/predeploy-verdict.txt" || PV=$?
case "$PV" in 0) VL='판정 | 진행 가능' ;; 1) VL='판정 | 🔴 보수적 사전 기준 미충족' ;; *) VL= ;; esac
if [ -n "$VL" ]; then LC_ALL=C grep -q "^$VL" "$M/predeploy-verdict.txt" 2>/dev/null || stop "🔴 배포 전 판정 기록 실패(pf3ok 종료 $PV 인데 predeploy-verdict.txt 에 맞는 판정 줄 없음) — 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고"; fi
cat "$M/predeploy-verdict.txt" || stop "🔴 배포 전 판정 표시 실패 — 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고"
case "$PV" in
  0) G0="R1 해소 — 이번 U0 의 배포 전 읽기 v3 에서 R1 두 종류 0 · R3 판정 불가 0(원래 U0 기준, 유지보수 예외 없음)"
     { printf '%s\n' "$G0" > "$M/r1-gate.txt" && [ "$(cat "$M/r1-gate.txt")" = "$G0" ]; } 2>/dev/null || stop "🔴 r1-gate 필수 기록 실패 — 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고"
     cat "$M/r1-gate.txt" || stop "🔴 r1-gate 표시 실패 — 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고" ;;
  1) stop "🔴 배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 1 — 보수적 사전 기준 미충족: R1 판정 불가 또는 R3 판정 불가 행) — 배포 전 중단·보고. 유지보수 예외 없음, ok-U0 없음(U1 금지). 한도·자본·세션·과거 행을 바꾸지 않는다(원문 predeploy-read.txt 보존)" ;;
  *) stop "🔴 배포 전 읽기 v3 형식 이상·읽기 실패(pf3ok 종료 $PV) — 조회 결과로 보지 않는다. 배포 전 중단·보고, ok-U0 없음(U1 금지). 원문 predeploy-read.txt 보존" ;;
esac
rm -f "$M/image-new.txt" "$M/image-new-chain.txt"   # V172D4 R2(D81D4 는 frontend 빌드 기록이 없다): 이번 빌드판 기록은 이 U0 뒤의 U1 만 쓴다(앞 시도 기록을 이번 빌드로 오인하지 않게, 모든 확인 통과 뒤에만 지운다)
[ ! -e "$M/ok-U0" ] && [ ! -L "$M/ok-U0" ] && [ ! -e "$M/ok-U0.tmp" ] && [ ! -L "$M/ok-U0.tmp" ] || stop "🔴 ok-U0(또는 임시 표지)가 이미 있다 — 낡은 표지를 이 U0 의 통과로 인정하지 않는다. U1 금지, ls -l $M 를 보고"
OKT="ok-U0 $(date -u '+%Y-%m-%dT%H:%M:%SZ') 경로=$M pid=$$ R1=0 R3 판정 불가 0"
{ printf '%s\n' "$OKT" > "$M/ok-U0.tmp" && [ -f "$M/ok-U0.tmp" ] && [ "$(cat "$M/ok-U0.tmp")" = "$OKT" ] && mv -n "$M/ok-U0.tmp" "$M/ok-U0" && [ ! -e "$M/ok-U0.tmp" ] && [ -f "$M/ok-U0" ] && [ "$(cat "$M/ok-U0")" = "$OKT" ]; } 2>/dev/null || { rm -f "$M/ok-U0.tmp" 2>/dev/null; [ ! -e "$M/ok-U0" ] || [ "$(cat "$M/ok-U0" 2>/dev/null)" != "$OKT" ] || rm -f "$M/ok-U0" 2>/dev/null; stop "🔴 ok-U0 표지 기록 실패 — U1 로 넘어가지 않는다(U1 금지). ls -l $M 로 ok-U0 가 남았는지 확인·보고(남았으면 U1 을 돌리지 않는다)"; }
echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"
)
