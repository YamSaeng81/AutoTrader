# UF2 뒤 화면 확인(읽기만, GET 만) — 새 frontend 로 목록·상세 페이지와 그 화면이 부르는 프록시 API 를 서버 안 localhost 에서 받는다.
# 운영자가 준 로그인 비밀번호로 정상 로그인(표준 입력, 출력·저장 안 함)한 쿠키로 GET 만. 브라우저 시각 확인이 아니라 페이지 응답·프록시 데이터 경로 확인이다.
set -u
echo "== 화면 확인 $(date '+%F %T %Z') (서버 시계)"
FE=autotrader-frontend-1
echo "frontend 컨테이너 이미지 $(docker inspect --format '{{.Image}} {{.State.StartedAt}} {{.RestartCount}}' $FE)"
B=http://localhost:3000
JAR=$(mktemp); trap 'rm -f "$JAR"' EXIT
LP=$(head -n1)   # 로그인 비밀번호는 표준 입력 첫 줄(출력·저장 안 함)
LC=$(printf '%s' "$LP" | python3 -c 'import json,sys; print(json.dumps({"password":sys.stdin.read()}))' | curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -H 'Content-Type: application/json' --data-binary @- "$B/api/auth/login") || LC=000
unset LP; echo "로그인 POST /api/auth/login → $LC · 쿠키 $(grep -c auth_session "$JAR")개"
[ "$LC" = 200 ] || { echo "로그인 실패 — 화면 확인 중단"; exit 1; }
pg() { local p=$1 f; f=$(mktemp); local c; c=$(curl -s -o "$f" -w '%{http_code} %{size_download}' -b "$JAR" "$B$p") || c="000 0"; echo "페이지 $p → $c · 오류 문자열 $(grep -ciE 'Application error|Internal Server Error|Unhandled Runtime Error' "$f")"; rm -f "$f"; }
api() { local p=$1 f; f=$(mktemp); local c; c=$(curl -s -o "$f" -w '%{http_code}' -b "$JAR" "$B/api/proxy$p") || c=000; printf '%s' "$c" > "$f.code"; echo "$f"; }
summ() { python3 -c '
import json,sys
p,f,c=sys.argv[1],sys.argv[2],open(sys.argv[2]+".code").read()
try: j=json.load(open(f))
except Exception as e: print(f"API {p} → {c} · JSON 해독 실패 {e}"); sys.exit(0)
d=j.get("data") if isinstance(j,dict) else None
if isinstance(d,list):
    st={}
    for x in d:
        s=str(x.get("status")); st[s]=st.get(s,0)+1
    print(f"API {p} → {c} · success={j.get(\"success\")} · {len(d)}건 · 상태 {dict(sorted(st.items()))}")
elif isinstance(d,dict):
    ks=["id","status","sessionKind","mode","coinPair","strategyType","strategyName","initialCapital","currentBalance","totalAssetKrw","availableKrw","totalReturnPct"]
    print(f"API {p} → {c} · success={j.get(\"success\")} · " + " ".join(f"{k}={d[k]}" for k in ks if k in d))
else:
    print(f"API {p} → {c} · success={j.get(\"success\") if isinstance(j,dict) else None} · data 형식 {type(d).__name__}")
' "$1" "$2"; }
first() { python3 -c '
import json,sys
d=json.load(open(sys.argv[1])).get("data") or []
r=[x for x in d if str(x.get("status"))=="RUNNING"] or d
print(r[0]["id"] if r else "")' "$1" 2>/dev/null; }
echo "-- 인증 없는 요청(로그인 이동 기대)"
echo "/ → $(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' $B/)"
echo "/login → $(curl -s -o /dev/null -w '%{http_code}' $B/login)"
for L in "/paper-trading:/api/v1/paper-trading/sessions" "/trading:/api/v1/trading/sessions" "/trading/dynamic:/api/v1/dynamic-sessions"; do
  P=${L%%:*}; A=${L#*:}
  echo "-- 목록 $P"
  pg "$P"; f=$(api "$A"); summ "$A" "$f"; id=$(first "$f"); rm -f "$f" "$f.code"
  [ -n "$id" ] || { echo "상세 대상 없음"; continue; }
  echo "-- 상세 $P/$id"
  pg "$P/$id"; f=$(api "$A/$id"); summ "$A/$id" "$f"; rm -f "$f" "$f.code"
done
echo "== 끝"
