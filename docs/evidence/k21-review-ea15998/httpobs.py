import re, sys
if len(sys.argv) != 2:
    print("⚪ httpobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^\d\d:\d\d:\d\d\.\d{3} \[")
HTTP = re.compile(r"\[(CryptoPanicSource|UpbitNoticeSource|RssNewsSource|CoinGeckoTrendingSource)\] HTTP (\d{3})(.*)$")
FIX = {"CoinGeckoTrendingSource": re.compile(r"^$")}
SID = re.compile(r"^ — sourceId=(\S+)$")
UP = re.compile(r"\[NewsAggregator\] 수집 미완료 — sourceId=(\S+) 결과=FAILED 종류=HTTP_(\d{3}) 판정=\S+ 받은=0건 신규=0건$")
rows = [PRE.sub("", l.rstrip("\r\n")) for l in open(sys.argv[1], encoding="utf-8", errors="replace")]
bad, chk, seen = 0, 0, {}
for i, r in enumerate(rows):
    m = HTTP.search(r)
    if not m or not HEAD.match(r):
        continue
    src, st, tail = m.group(1), m.group(2), m.group(3)
    cont = 0
    while i + 1 + cont < len(rows) and not HEAD.match(rows[i + 1 + cont]):
        cont += 1
    k = "%s HTTP %s" % (src, st)
    s = seen.setdefault(k, [0, 0, 0, 0, set()])   # 전체·고정 한 줄·본문 형식/연속 줄·상위 짝
    s[0] += 1
    sm = SID.match(tail)
    fixed = (FIX[src].match(tail) is not None) if src in FIX else sm is not None
    if not fixed or cont:
        s[2] += 1; bad += 1
        print("🔴 %s %s — 고정 한 줄이 아님(꼬리 %d자·뒤 연속 줄 %d줄, 내용은 출력하지 않는다)" % (src, r[:12], len(tail), cont))
        continue
    s[1] += 1
    if sm:
        s[4].add(sm.group(1))
    nxt = [x for x in rows[i + 1:i + 6] if HEAD.match(x)]
    ups = [UP.search(x) for x in nxt]
    pair = any(u and u.group(2) == st and (not sm or u.group(1) == sm.group(1)) for u in ups)
    if pair:
        s[3] += 1
    else:
        chk += 1
        print("⚪ %s %s HTTP %s — 고정 한 줄인데 뒤 5줄 안에 상위 '수집 미완료 … FAILED 종류=HTTP_%s … 받은=0건 신규=0건' 없음 — 보고" % (src, r[:12], st, st))
if not seen:
    print("출처 HTTP 비200 줄: 미관측 — 이 구간에 자연 발생한 비200 응답 없음(실패를 유도하지 않는다)")
for k in sorted(seen):
    a, f, b, p, ids = seen[k]
    print("%s ×%d — 고정 한 줄 %d · 본문 형식/연속 줄 %d · 상위 FAILED/HTTP_n/0건 짝 %d%s" % (k, a, f, b, p, (" · sourceId=" + ",".join(sorted(ids))) if ids else ""))
sys.exit(1 if bad else (3 if chk else 0))
