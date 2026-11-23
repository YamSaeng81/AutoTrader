# A3 사전 조회 원격 본문 a3-remote-1 (총괄 v1.228 §3-2). **검토용 원문 — 미실행, 별도 승인 전 실행 금지.**
# a3-preread.sh 가 해시 확인 뒤 표준 입력으로 서버 bash 에 1회 보낸다: ssh cat-prod "bash -s -- <인자 6개>" < a3-remote.sh
# 인자: $1 TAG(a3pre-YYYYMMDD-HHMM) $2 인증 파일 서버 경로 $3 with_orders(true|false) $4 등록 SID $5 SQL base64 $6 SQL sha256
# 서버에 쓰는 곳은 새 출력 폴더 ~/a3-pre/$TAG 하나뿐이다(없어야 함). 계좌 JSON·SQL 결과·표준오류 원문은 그 폴더에만 둔다.
# 표준 출력(=개발 PC 로 오는 것)은 아래 out() 의 허용 줄뿐이다. 토큰·인증 파일 내용·로그·오류 원문은 내보내지 않는다.
# 호출: 장부 SQL 1회 → 앱 시계 1회 → 계좌 요약 1회(백엔드가 업비트 계좌 1회 + 보유 코인 있으면 공개 시세 1회) → 앱 시계 1회. 재시도 없음.
set -u
TAG=$1; AUTH=$2; WO=$3; SID=$4; SQLB=$5; SQLSHA=$6
ts() { date -u +%Y-%m-%dT%H:%M:%S.%6NZ; }      # 서버 시계, 마이크로초(그 아래는 버림 — 기록에 명시)
out() { printf '%s\n' "$*"; }
stop() { out "STOP $1 $2"; [ -n "${M:-}" ] && [ -d "$M" ] && out "STDERR_BYTES $(wc -c < "$M/stderr.txt" 2>/dev/null || echo -)"; exit "$1"; }
M=~/a3-pre/$TAG
[ ! -e "$M" ] || { out "STOP 2 출력폴더_이미_있음"; exit 2; }
mkdir -p ~/a3-pre && mkdir "$M" && : > "$M/stderr.txt" || { out "STOP 4 출력폴더_생성_실패"; exit 4; }
exec 2>>"$M/stderr.txt"
out "SERVER_START $(ts)"
case "$AUTH" in "~/"*) AUTH="$HOME/${AUTH#\~/}";; esac
[ -f "$AUTH" ] && [ -r "$AUTH" ] && [ "$(stat -c %a "$AUTH")" = 600 ] || stop 2 인증파일_없음_또는_권한600아님   # 내용은 읽지 않는다
printf '%s' "$SQLB" | base64 -d > "$M/a3-pre.sql" && [ "$(sha256sum < "$M/a3-pre.sql" | cut -d' ' -f1)" = "$SQLSHA" ] || stop 2 SQL_해시_불일치
cd ~/crypto_trader/AutoTrader || stop 2 앱_폴더_없음
DC="docker compose -f docker-compose.prod.yml"
# 1) 장부 SQL — 서버 시계 전후 구간 + SQL 안 DB 시계(시작·끝)
LT0=$(ts)
$DC exec -T -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' db psql -X -q -At -F "$(printf '\t')" -v ON_ERROR_STOP=1 \
  -v sid="$SID" -v with_orders="$WO" -U trader -d crypto_auto_trader -f - < "$M/a3-pre.sql" > "$M/ledger.tsv"; LRC=$?
LT1=$(ts)
out "LEDGER_CALL $LT0 $LT1 rc=$LRC"
[ "$LRC" = 0 ] || stop 3 장부_SQL_실패
[ "$(wc -c < "$M/stderr.txt")" = 0 ] || stop 5 장부_SQL_표준오류
# 2) 앱 시계 → 계좌 요약 → 앱 시계. 서버 시계로 각각 감싼다(앱–서버 관계 표본 2개)
AT0=$(ts); AP0=$($DC exec -T backend date -u +%Y-%m-%dT%H:%M:%S.%6NZ); AR0=$?; AT1=$(ts)
CT0=$(ts)
HC=$(curl -sS --max-time 20 -o "$M/account.json" -w '%{http_code}' -H @"$AUTH" http://localhost:8080/api/v1/account/summary); CRC=$?
CT1=$(ts)
BT0=$(ts); AP1=$($DC exec -T backend date -u +%Y-%m-%dT%H:%M:%S.%6NZ); AR1=$?; BT1=$(ts)
out "APP_CLOCK_1 server=$AT0..$AT1 app=$AP0 rc=$AR0"
out "ACCOUNT_CALL $CT0 $CT1 curl_rc=$CRC http=$HC"
out "APP_CLOCK_2 server=$BT0..$BT1 app=$AP1 rc=$AR1"
# 3) 허용 열만 출력. 계좌 JSON 에서는 KRW 세 정수·BTC 수량·다른 보유 개수·fetchedAt·error 키 유무만.
python3 - "$M/account.json" "$M/ledger.tsv" <<'PY' || stop 4 출력_변환_실패
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding='utf-8')).get('data') or {}
except Exception:
    d = None
if d is None:
    print('ACCT parse=fail')
else:
    btc = [h for h in d.get('holdings', []) if h.get('currency') == 'BTC']
    print('ACCT apiKeyConfigured=%s error_key=%s availableKrw=%s lockedKrw=%s totalKrwBalance=%s fetchedAt=%s btc_balance=%s btc_locked=%s btc_currentPrice=%s other_holdings=%d' % (
        d.get('apiKeyConfigured'), 'error' in d, d.get('availableKrw', '-'), d.get('lockedKrw', '-'), d.get('totalKrwBalance', '-'),
        d.get('fetchedAt', '-'), btc[0].get('balance') if btc else 0, btc[0].get('locked') if btc else 0,
        btc[0].get('currentPrice') if btc else '-', len([h for h in d.get('holdings', []) if h.get('currency') != 'BTC'])))
for line in open(sys.argv[2], encoding='utf-8'):
    if line.split('\t', 1)[0] in ('META', 'SESS', 'OBUY', 'CNT', 'POS', 'MAXTS', 'MORD', 'MSES', 'END'):
        print(line.rstrip('\n'))
PY
(cd "$M" && sha256sum a3-pre.sql ledger.tsv account.json > SERVER-SHA256SUMS) || stop 4 서버_해시목록_실패
out "SERVER_SHA256SUMS $(sha256sum < "$M/SERVER-SHA256SUMS" | cut -d' ' -f1)"
out "STDERR_BYTES $(wc -c < "$M/stderr.txt")"
out "SERVER_END $(ts)"
[ "$CRC" = 0 ] && [ "$HC" = 200 ] || exit 6
exit 0
