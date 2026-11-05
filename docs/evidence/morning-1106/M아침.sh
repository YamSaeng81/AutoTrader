# 11-06 아침 읽기 전용 — 서버에 새 파일을 쓰지 않는다(표준 출력만). F·G·T변수의 조회 함수만 쓴다.
(
SINCE=2026-11-05T13:00:00Z   # 22시 N03 log.gz 끝(12:59:22Z) 뒤
echo "== 시각 $(date '+%F %T %Z')"
echo "== 서비스"; svc | diff - ~/maint-n17/svc-after30.txt && echo "서비스 5개 T4(20:12)와 같음" || echo "🔴 서비스가 T4 와 다름"
c=$($DC ps -q backend); docker inspect --format "backend Created={{.Created}} StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Status={{.State.Status}}" "$c"
echo "HEAD $(git -C ~/crypto_trader/AutoTrader rev-parse HEAD)"
echo "== 세션"; sc | diff - ~/maint-n17/s-after30.txt && echo "세션 상태 T4 와 같음" || echo "🔴 세션 상태가 T4 와 다름"
sid203 && echo "SID 203·기준 행·EG·0 확인 통과" || echo "🔴 SID 203 확인 실패"
v94 && echo "Flyway 94" || echo "🔴 Flyway 94 아님"
echo "== BTC $($PSQL -At -c "$BTCIN")"
echo "== 설치·cron·장부"
[ "$(cat "$OPS/INSTALLED_SHA")" = "$CK" ] && [ "$(h256 "$OPS/daily_check.sh")" = "$CKSH" ] && [ "$(h256 "$OPS/daily_checks.sql")" = "$CKSQL" ] && echo "설치판 c669ccd 그대로" || echo "🔴 설치판 다름"
[ "$(h256 "$OPS/sid_ledger.sql")" = "$LSUM" ] && [ "$(cat "$OPS/SID_LEDGER_SHA")" = "$LS" ] && echo "장부 설치 그대로" || echo "🔴 장부 설치 다름"
crontab -l | cmp -s - ~/maint-n17/crontab-before.txt && echo "crontab T0 와 같음 $(crontab -l | sha256sum | cut -c1-16)" || echo "🔴 crontab 다름"
( cd ~/sid203-ledger && sha256sum --quiet -c ~/maint-n17/ledger-files-before.sha ) && echo "과거 장부 파일 그대로" || echo "🔴 과거 장부 파일 바뀜"
ls -l --time-style=+%m-%dT%H:%M ~/sid203-ledger | tail -2
echo "== 로그($SINCE 이후)"
L() { $DC logs --no-color --since "$SINCE" backend 2>&1; }
echo "줄 수 $(L | wc -l) · 첫/끝 머리:"; L | grep -oE "^[^|]*\| ?[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}" | sed -n '1p;$p'
echo "ERROR 전체 $(L | grep -c ' ERROR ') · 세 마켓 404 밖 $(L | grep ' ERROR ' | grep -v 캔들\ 최신성 | grep -vcE "$NOT404")"
L | grep ' ERROR ' | grep -v 캔들\ 최신성 | grep -vE "$NOT404" | cut -c1-220 | head -20
echo "연결 획득 실패(Connection is not available) $(L | grep -c 'Connection is not available') · 누수 경고 $(L | grep -c 'Connection leak detection') · unleaked $(L | grep -c unleaked)"
echo "🔴 공유 풀/위험 한도 줄 $(L | grep -cE '🔴 (공유 풀 한도|위험 한도)') · BTC 판정 불가/재개 $(L | grep -cE 'BTC 급락 관문 H1 판정 (불가|재개)')"
echo "HTTP 비200:"; L | grep -oE "[A-Za-z]+Source[^ ]* .{0,40}HTTP [0-9]{3}" | grep -oE "^[A-Za-z]+Source|HTTP [0-9]{3}" | paste - - | sort | uniq -c
echo "뉴스 완료 $(L | grep -c '수집 완료 —') · 미완료 $(L | grep -c '수집 미완료')"; L | grep -oE "수집 미완료 — sourceId=[a-z_]+ 결과=[A-Z_]+ 종류=[A-Z_0-9]+" | sort | uniq -c
echo "N17 줄 $(L | grep -c 'REST 강제 갱신 시세가 가격이 아님') · REST 폴백 줄 $(L | grep -c 'REST 폴백')"
echo "주문·체결 관련 줄(참고) $(L | grep -ciE '주문 (접수|체결|제출)')"
echo "== 정기 점검 폴더 최근 파일"; ls -l --time-style=+%m-%dT%H:%M ~/daily-check | tail -4
)
