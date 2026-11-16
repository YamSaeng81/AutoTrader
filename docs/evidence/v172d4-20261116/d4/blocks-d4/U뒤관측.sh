(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${UB:-}" = V172D4 ] || stop "U 변수가 v1.172 통합 후보 초안 D4 판이 아님 — F → G → v1.172 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"
[ -e "$M/ok-U4" ] || stop "U4 미통과"
S=$(cat "$M/t-deploy.utc"); TS=$(date '+%Y%m%d-%H%M')
echo "== V172D4 R3 — P0 먼저: 기동 구간 로그 보존 → 연결 획득 시간 초과 검사(이미지 대응·뉴스 DB 조회는 그 뒤)"
$DC logs --no-color --since "$S" backend > "$M/app-$TS-p0.log" || stop "🔴 P0 검사 미완료 — 기동 구간 로그를 확보하지 못함(로그 조회 실패). 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"
[ -s "$M/app-$TS-p0.log" ] || stop "🔴 P0 검사 미완료 — 기동 구간 로그가 비었다. 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"
holdobs "$M/app-$TS-p0.log" > "$M/holdobs-$TS-p0.txt" && HP0=0 || HP0=$?
[ "$HP0" != 1 ] || { cat "$M/holdobs-$TS-p0.txt"; stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)"; }
[ "$HP0" = 0 ] || [ "$HP0" = 3 ] || { cat "$M/holdobs-$TS-p0.txt"; stop "🔴 P0 검사 미완료 — holdobs 종료 $HP0. 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고"; }
isimg backend "$(okimg "$M/image-new-chain.txt")" $(ok3 "$M/image-new-chain.txt") || stop "실행 이미지가 image-new 대상·manifest·config 와 다름 또는 조회 실패 — 보고"
$PSQL -At -c "$NEWSCFG" > "$M/news-$TS.txt" || stop "뉴스 설정 조회 실패"
OBS=$(date +%s)   # 관측 시각 = 뉴스 설정을 읽은 시각
$DC logs --no-color --since "$S" backend > "$M/app-news-$TS.log" || stop "🔴 P0 검사 미완료 — 두 번째 로그를 확보하지 못함(로그 조회 실패). 즉시 보고"
[ -s "$M/app-news-$TS.log" ] || stop "🔴 P0 검사 미완료 — 두 번째 로그가 비었다. 즉시 보고"
echo "== 기동 뒤 HOLD 질의 장기 대여·연결 획득(N13, 두 번째 로그 — 첫 검사 뒤 새로 나타난 줄 포함 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-news-$TS.log" | tee "$M/holdobs-$TS.txt" && HQ=0 || HQ=$?
case "$HQ" in 0) ;; 3) echo "⚪ 장기 대여 경고 있음 — 개선 미확인, 위 줄을 보고" ;; 1) stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다(두 번째 로그). 즉시 보고(되돌림 여부는 총괄 판단, U 되돌림 블록 준비됨)" ;; *) stop "🔴 P0 검사 미완료 — 두 번째 로그 holdobs 종료 $HQ(판정 실패). 연결 획득 실패를 0 으로 보지 않는다. 즉시 보고" ;; esac
newsobs "$M/app-news-$TS.log" "$M/news-before.txt" "$M/news-$TS.txt" "$(date -d "$S" +%s)" "$OBS" | tee "$M/newsobs-$TS.txt" && NO=0 || NO=$?
case "$NO" in 0) ;; 3) echo "⚪ 뉴스 확인 필요 — 위 ⚪ 줄을 보고(정상 관측 아님, 실패로 확정하지 않음)" ;; *) echo "🔴 뉴스 관측 이상(종료 $NO) — 위 출력을 보고" ;; esac
grep " ERROR " "$M/app-news-$TS.log" | grep -E "$NEWSERR" > "$M/errors-news-$TS.txt" || true
echo "== 기동 뒤 뉴스 출처 ERROR $(wc -l < "$M/errors-news-$TS.txt")줄(출처별)"; grep -oE "$NEWSERR" "$M/errors-news-$TS.txt" | sort | uniq -c || true
echo "== 기동 뒤 출처 HTTP 비200 줄(N12)"
httpobs "$M/app-news-$TS.log" | tee "$M/httpobs-$TS.txt" && HO=0 || HO=$?
case "$HO" in 0) ;; 3) echo "⚪ HTTP 줄 확인 필요 — 위 ⚪ 줄을 보고" ;; *) echo "🔴 출처 HTTP 비200 줄이 고정 한 줄이 아님(종료 $HO) — 위 출력을 보고" ;; esac
echo "== 기동 뒤 N15·N16 줄(자연 발생만)"
entryobs "$M/app-news-$TS.log" | tee "$M/entryobs-$TS.txt" && EQ=0 || EQ=$?
case "$EQ" in 0) ;; *) echo "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 위 출력과 원문 줄을 보고" ;; esac
echo "== 기동 뒤 N17 줄(자연 발생만)"
n17obs "$M/app-news-$TS.log" | tee "$M/n17obs-$TS.txt" || echo "⚪ n17obs 인자 오류 — 위 출력을 보고"
echo "== 기동 뒤 N19 줄(자연 발생만)"
n19obs "$M/app-news-$TS.log" | tee "$M/n19obs-$TS.txt" || echo "⚪ n19obs 인자 오류 — 위 출력을 보고"
echo "== 기동 뒤 N20 줄(자연 발생만)"
n20obs "$M/app-news-$TS.log" | tee "$M/n20obs-$TS.txt" || echo "⚪ n20obs 인자 오류 — 위 출력을 보고"
)
