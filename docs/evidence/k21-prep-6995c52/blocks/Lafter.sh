(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${LV:-}" = 61 ] || stop "L 변수가 v1.93(§5-9 61) 판이 아님 — F → G → §5-9 61 L 변수"
[ -e "$M/ok-L4" ] || stop "L4 미통과"
S=$(cat "$M/t-deploy.utc"); TS=$(date '+%Y%m%d-%H%M')
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$(cat "$M/image-new.txt")" ] || stop "실행 이미지가 image-new 와 다름 — 보고"
$PSQL -At -c "$NEWSCFG" > "$M/news-$TS.txt" || stop "뉴스 설정 조회 실패"
OBS=$(date +%s)   # 관측 시각 = 뉴스 설정을 읽은 시각
$DC logs --no-color --since "$S" backend > "$M/app-news-$TS.log"
newsobs "$M/app-news-$TS.log" "$M/news-before.txt" "$M/news-$TS.txt" "$(date -d "$S" +%s)" "$OBS" | tee "$M/newsobs-$TS.txt" && NO=0 || NO=$?
case "$NO" in 0) ;; 3) echo "⚪ 뉴스 확인 필요 — 위 ⚪ 줄을 보고(정상 관측 아님, 실패로 확정하지 않음)" ;; *) echo "🔴 뉴스 관측 이상(종료 $NO) — 위 출력을 보고" ;; esac
grep " ERROR " "$M/app-news-$TS.log" | grep -E "$NEWSERR" > "$M/errors-news-$TS.txt" || true
echo "== 기동 뒤 뉴스 출처 ERROR $(wc -l < "$M/errors-news-$TS.txt")줄(출처별)"; grep -oE "$NEWSERR" "$M/errors-news-$TS.txt" | sort | uniq -c || true
echo "== 기동 뒤 출처 HTTP 비200 줄(N12)"
httpobs "$M/app-news-$TS.log" | tee "$M/httpobs-$TS.txt" && HO=0 || HO=$?
case "$HO" in 0) ;; 3) echo "⚪ HTTP 줄 확인 필요 — 위 ⚪ 줄을 보고" ;; *) echo "🔴 출처 HTTP 비200 줄이 고정 한 줄이 아님(종료 $HO) — 위 출력을 보고" ;; esac
)
