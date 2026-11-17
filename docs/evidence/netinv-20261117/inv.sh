#!/bin/bash
# 통신 오류 9줄·SSH 끊김 읽기 전용 조사(총괄 v1.178 §3) — 사건 구간으로 제한한 기존 backend 로그와 SSH 서비스 로그만. 서버에 쓰지 않는다.
# 능동 부하·API 시험·설정 변경·재시작 없음. 출력의 IPv4 는 앞 두 자리만 남긴다. 인자: $1=로컬 폴더
set -u
L=${1:-.}
R='cd ~/crypto_trader/AutoTrader && c=$(docker compose -f docker-compose.prod.yml ps -q backend)'
w() {  # 이름 since until 패턴
  ssh -o BatchMode=yes cat-prod "$R"'; z() { docker logs --since '"$2"' --until '"$3"' $c 2>&1; }; echo "구간 '"$2"'~'"$3"' 줄 $(z | wc -l) · ERROR $(z | grep -c " ERROR ") · WARN $(z | grep -c " WARN ")"; z | grep -E "'"$4"'" | cut -c1-220 | head -80' </dev/null > "$L/$1.txt" 2>&1
}
w w1-candle-0000kst 2026-11-16T14:55:00Z 2026-11-16T15:20:00Z 'KRW-IOTA|KRW-WAVES|KRW-CRO|timed out|동기화 실패|동기화 완료|캔들 동기화|Connection reset'
w w2-news-0050kst   2026-11-16T15:40:00Z 2026-11-16T16:10:00Z 'CryptoPanic|수집 완료|수집 미완료|Connection reset'
w w3-telegram-0426kst 2026-11-16T19:20:00Z 2026-11-16T19:40:00Z '\[Telegram\]|Connection reset'
w w4-claude-0500kst 2026-11-16T19:55:00Z 2026-11-16T20:10:00Z 'ClaudeProvider|LlmTaskRouter|REPORT_NARRATION|Connection reset'
# 같은 밤 전체의 연결 계열 오류 시각 분포(13:00Z~23:30Z) — 묶여 있는지
ssh -o BatchMode=yes cat-prod "$R"'; docker logs --since 2026-11-16T13:00:00Z --until 2026-11-16T23:30:00Z $c 2>&1 | grep -E "timed out|Connection reset|Connection refused|UnknownHost|SocketTimeout|connect timed out" | grep -oE "^[0-9]{2}:[0-9]{2}" | uniq -c' </dev/null > "$L/net-minutes.txt" 2>&1
# SSH 서비스 로그(07:50~08:10 KST = 22:50~23:10Z) — 권한이 없으면 그 사실만 남긴다
ssh -o BatchMode=yes cat-prod 't=$(journalctl -u ssh -u sshd --since "2026-11-16 22:50:00 UTC" --until "2026-11-16 23:10:00 UTC" --no-pager -o short-iso 2>&1 | sed -E "s/([0-9]{1,3}\.[0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}/\1.x.x/g"); echo "줄 $(printf "%s
" "$t" | wc -l)"; printf "%s
" "$t" | grep -oE "MaxStartups|drop connection|Connection closed by|Connection reset|Accepted publickey|Failed|Disconnected|Timeout before authentication|error: kex|banner exchange|No supported|refused" | sort | uniq -c; echo "---"; printf "%s
" "$t" | head -60' </dev/null > "$L/ssh-journal.txt" 2>&1
grep -cE '[0-9]{8,10}:[A-Za-z0-9_-]{30,}' "$L"/*.txt > "$L/token-check.txt"
