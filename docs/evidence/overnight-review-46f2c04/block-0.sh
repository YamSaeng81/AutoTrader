M=~/maint-n13; mkdir -p "$M"; OPS=~/ops-scripts; PV=70   # v1.102(§5-9 69 블록·§70 판정) 판 표지 — P 블록 전부가 확인한다
OLD=6995c52259e3de4c7c922a32f9fb2aa9ddd49f00; NEW=fe01207f3f60e97e308925157f84a1bb2da04388
IMGOLD=sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07   # #21 배포 이미지(지금 실행 중, ~/maint-n12/image-new.txt)
IMGPREV=sha256:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9   # #19 이미지 — 이전 복구 태그 autotrader-backend:ea44900 가 가리킨다(보존, 바꾸지 않는다)
IMGPREV2=sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6   # #20 이미지 — 이전 복구 태그 autotrader-backend:b1d7664 가 가리킨다(보존, 바꾸지 않는다)
LS=8bfe8a9fa896f400b3fb6681fef021c99d6ba43b; LSUM=02a8585021fb2a1a52d9b3b56b60e957cb71b9ca3c11bf819595df15cf334f1c   # 지금 설치 장부 — 바꾸지 않는다
CK=c669ccdc6e248ce8e55e9a7ac7405197d266fa18; CKSH=ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc; CKSQL=653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8   # 정기 점검 설치본 — 바꾸지 않는다
CRON=f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b   # crontab — 바꾸지 않는다
h256() { sha256sum < "$1" | cut -d' ' -f1 ; }
v94() { [ "$($PSQL -At -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")" = "94" ] ; }
DIFFN="$(printf '%s\n' web-api/src/main/java/com/cryptoautotrader/api/repository/StrategyLogRepository.java \
  web-api/src/test/java/com/cryptoautotrader/api/n13/HoldBaselineLoopPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/n13/HoldBaselinePgSupport.java \
  web-api/src/test/java/com/cryptoautotrader/api/n13/HoldBaselineSqlEquivalencePgTest.java)"
PARX="$(printf '%s\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/StrategyLogRepository.class')"   # 실제 bootJar 대조(v1.101) — #21 기대값을 옮기지 않고 6995c52·fe01207 jar 에서 새로 셈
# 기존 뉴스 출처 ERROR 원문 4개(후보에서도 바뀌지 않음). 정기 점검에서는 미분류 🔴 그대로다
NEWSERR='\[(RssNewsSource\] 수집 실패 — sourceId=|(UpbitNoticeSource|CryptoPanicSource|CoinGeckoTrendingSource)\] 수집 실패: )'
NEWSCFG="SELECT source_id || '|' || source_type || '|' || is_enabled || '|' || fetch_interval_min || '|' || coalesce(extract(epoch FROM last_fetched_at)::bigint::text, '-') FROM news_source_config ORDER BY source_id"
btcok() { [ "$(cut -d'|' -f1 "$1")" = true ] && [ "$(cut -d'|' -f4 "$1")" = 20/20 ] ; }
# BTC 관문은 candle 의 '기동 직전에도 판정 불가' 예외를 받지 않는다(v1.36 과 같다).
btcgate() { python3 - "$1" <<'PYEOF'
import re, sys
st = {}
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.search(r"\[캔들 최신성\] (.+?) 판정 (불가|재개) \(", line)
    if m and m.group(1).startswith("BTC 급락 관문 "):
        st[m.group(1)] = m.group(2)
if not st:
    print("BTC 관문: 판정 불가·재개 줄 없음")
for k, v in st.items():
    print("BTC 관문 마지막 상태: %s 판정 %s" % (k, v))
sys.exit(1 if "불가" in st.values() else 0)
PYEOF
}
# 시작 가능 시각(KST 10:10~20:59·22:10~07:59)과 직전 정기 보고 파일. 인자: 기준 epoch 초
kwin() { local now=$1 hm last
  hm=$(date -u -d "@$(( now + 32400 ))" +%H%M)
  if [ "$hm" -ge 1010 ] && [ "$hm" -lt 2100 ]; then last=$(date -u -d "@$(( now + 32400 ))" +%Y%m%d)-1000
  elif [ "$hm" -ge 2210 ]; then last=$(date -u -d "@$(( now + 32400 ))" +%Y%m%d)-2200
  elif [ "$hm" -lt 0800 ]; then last=$(date -u -d "@$(( now + 32400 - 86400 ))" +%Y%m%d)-2200
  else echo "시작 가능 시각 아님 — KST 10:10~20:59·22:10~07:59 에만 K0 (지금 KST ${hm:0:2}:${hm:2:2})"; return 1; fi
  [ -s "$HOME/daily-check/$last.txt" ] || { echo "직전 정기 보고 ~/daily-check/$last.txt 없음 — OPS §5 대로 보고만(재실행·재전송 금지)"; return 1; }
  echo "직전 정기 보고 ~/daily-check/$last.txt (읽은 뒤 시작)" ; }
# 뉴스 출처별 관측. 인자: 앱 로그, K0 설정, 지금 설정, 기동 epoch 초, 관측 epoch 초(지금 설정을 읽은 시각)
# 종료 1 = 설정(종류·사용·간격)이 K0 와 다름, 2 = 새 코드가 시도했는데 완료·미완료 줄 없음,
# 3 = ⚪ 계산상 회차가 관측 전에 지났는데 시도 기록·완료·미완료 줄 없음(확인 필요, 실패로 확정하지 않음)
newsobs() { python3 - "$@" <<'PYEOF'
import re, sys, time
if len(sys.argv) != 6:
    print("⚪ newsobs 인자는 5개(로그·K0 설정·지금 설정·기동 epoch·관측 epoch)"); sys.exit(2)
log, before, now, start, obs = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
def cfg(p):
    d = {}
    for line in open(p, encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if len(f) != 5:
            print("⚪ 뉴스 설정 줄 형식 다름: %r" % line); sys.exit(2)
        d[f[0]] = f
    return d
kst = lambda t: time.strftime("%H:%M KST", time.gmtime(t + 32400))
b, n = cfg(before), cfg(now)
seen = {}
pat = re.compile(r"\[NewsAggregator\] 수집 (완료|미완료) — sourceId=(\S+) (.*)$")
for line in open(log, encoding="utf-8", errors="replace"):
    m = pat.search(line)
    if m:
        seen.setdefault(m.group(2), []).append((m.group(1), m.group(3).strip()))
rc, chk = 0, 0
print("(기동 %s · 관측 %s · 회차는 기동 3분 뒤부터 15분 간격으로 계산 — 실제는 앞 회차 소요만큼 늦어질 수 있다)" % (kst(start), kst(obs)))
for k in sorted(set(b) | set(n)):
    if b.get(k, ["없음"])[:4] != n.get(k, ["없음"])[:4]:
        print("🔴 뉴스 설정이 K0 때와 다름: %s → %s" % ("|".join(b.get(k, ["없음"])[:4]), "|".join(n.get(k, ["없음"])[:4])))
        rc = 1
for k in sorted(n):
    sid, typ, en, iv, lf = n[k]
    rs = seen.get(sid, [])
    if rs:
        done = sum(1 for r in rs if r[0] == "완료")
        print("%s %s(%s분): 완료 %d·미완료 %d — 마지막 '수집 %s — %s'" % (sid, typ, iv, done, len(rs) - done, rs[-1][0], rs[-1][1]))
        for r in rs:
            if r[0] == "미완료" and ("종류=INVALID_RESPONSE" in r[1] or "종류=SOURCE_REPORTED_FAILURE" in r[1]):
                print("   🔴 새 판정 — 출처 원문과 함께 보고: 수집 미완료 — sourceId=%s %s" % (sid, r[1]))
    elif en not in ("t", "true"):
        print("%s %s: 사용 안 함" % (sid, typ))
    elif lf != "-" and int(lf) >= start + 60:
        print("⚪ %s %s: 새 코드가 시도(last_fetched_at %s)했는데 완료·미완료 줄 없음 — 원문 보고" % (sid, typ, kst(int(lf))))
        rc = max(rc, 2)
    else:
        due = max(int(lf) + int(iv) * 60 if lf != "-" else start, start + 180)
        tick = start + 180 + -(-(due - start - 180) // 900) * 900
        if obs < tick:
            print("%s %s(%s분): 미관측 — 계산상 차례 전(%s 이후 첫 회차 %s 무렵)" % (sid, typ, iv, kst(due), kst(tick)))
        else:
            print("⚪ %s %s(%s분): 확인 필요 — 계산상 회차 %s ≤ 관측 %s 인데 시도 기록·완료·미완료 줄 없음(앞 회차 소요로 늦을 수 있어 실패로 확정하지 않는다) — 보고" % (sid, typ, iv, kst(tick), kst(obs)))
            chk += 1
if chk:
    print("⚪ 확인 필요 %d개 — 정상 관측으로 세지 않는다. 수집 API·강제 재시도·주기 변경·즉시 되돌림 없이 출력만 보고" % chk)
sys.exit(rc if rc else (3 if chk else 0))
PYEOF
}
# N12 출처 HTTP 비200 줄 관측(v1.95). 인자: 앱 로그. 로그에서 읽은 sourceId·스레드·꼬리·본문은 출력하지 않는다(고정 출처 이름·상태·시각·건수만).
# 짝: 뒤 5줄 안 같은 스레드의 첫 줄이 같은 상태·같은 sourceId(CoinGecko 는 다른 출처가 찍지 않은 sourceId)의 상위 FAILED/HTTP_n/0건이어야 하고, 결과 줄 하나는 한 번만 쓴다.
# 종료 1 = 🔴 본문이 붙은 형식이거나 HTTP 줄 바로 뒤에 로그 머리 없는 연속 줄, 3 = ⚪ 고정 한 줄인데 짝 미확인, 0 = 모두 고정 한 줄+짝 또는 미관측
httpobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ httpobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
HTTP = re.compile(r"\[(CryptoPanicSource|UpbitNoticeSource|RssNewsSource|CoinGeckoTrendingSource)\] HTTP (\d{3})(.*)$")
SID = re.compile(r"^ — sourceId=(\S+)$")
UP = re.compile(r"\[NewsAggregator\] 수집 미완료 — sourceId=(\S+) 결과=FAILED 종류=HTTP_(\d{3}) 판정=\S+ 받은=0건 신규=0건$")
TAG = re.compile(r"\[(\w+Source)\] .*sourceId=([^\s:]+)")
# 로그에서 읽은 sourceId·스레드·꼬리·본문은 내부 비교에만 쓴다. 출력은 고정 출처 이름·상태 숫자·시각 숫자·건수뿐이다.
rows = [PRE.sub("", l.rstrip("\r\n")) for l in open(sys.argv[1], encoding="utf-8", errors="replace")]
heads = [(i, HEAD.match(r)) for i, r in enumerate(rows)]
heads = [(i, h) for i, h in heads if h]
owner = {}   # 로그의 sourceId → 그 sourceId 를 직접 찍은 출처 클래스들(CoinGecko 짝 확인용)
for r in rows:
    t = TAG.search(r)
    if t:
        owner.setdefault(t.group(2), set()).add(t.group(1))
bad, chk, seen, used = 0, 0, {}, set()
for n, (i, h) in enumerate(heads):
    r = rows[i]
    m = HTTP.search(r)
    if not m:
        continue
    src, st, tail = m.group(1), m.group(2), m.group(3)
    tm, th = h.group(1), h.group(2)
    cont = 0
    while i + 1 + cont < len(rows) and not HEAD.match(rows[i + 1 + cont]):
        cont += 1
    k = "%s HTTP %s" % (src, st)
    s = seen.setdefault(k, [0, 0, 0, 0, 0])   # 전체·고정 한 줄·본문 형식/연속 줄·상위 짝·짝 미확인
    s[0] += 1
    sm = SID.match(tail)
    fixed = (tail == "") if src == "CoinGeckoTrendingSource" else sm is not None
    if not fixed or cont:
        s[2] += 1; bad += 1
        print("🔴 %s %s HTTP %s — 고정 한 줄이 아님(꼬리 %d자·뒤 연속 줄 %d줄, 내용은 출력하지 않는다)" % (src, tm, st, len(tail), cont))
        continue
    s[1] += 1
    # 짝 후보: 뒤 5개 로그 머리 줄 안에서 같은 스레드의 첫 줄 하나뿐이다(출처 fetch 와 상위 결과는 같은 스레드에서 이어진다).
    # 그 줄이 다른 HTTP·다른 수집 결과·다른 기록이면 경계로 보고 더 뒤 줄을 쓰지 않는다. 결과 줄 하나는 한 번만 쓴다.
    j = None
    for i2, h2 in heads[n + 1:n + 6]:
        if h2.group(2) == th:
            j = i2; break
    why = None
    if j is None:
        why = "뒤 5줄 안에 같은 스레드의 다음 줄 없음"
    elif j in used:
        why = "같은 스레드 다음 줄이 이미 다른 HTTP 줄의 짝"
    else:
        u = UP.search(rows[j])
        if not u:
            why = "같은 스레드 다음 줄이 상위 FAILED/HTTP_n/0건 결과가 아님"
        elif u.group(2) != st:
            why = "같은 스레드 상위 결과의 상태가 다름"
        elif sm and u.group(1) != sm.group(1):
            why = "같은 스레드 상위 결과의 sourceId 가 HTTP 줄과 다름(값은 출력하지 않는다)"
        elif not sm and (owner.get(u.group(1), set()) - {src}):
            why = "같은 스레드 상위 결과의 sourceId 를 다른 출처가 찍음(값은 출력하지 않는다)"
    if why is None:
        used.add(j); s[3] += 1
    else:
        s[4] += 1; chk += 1
        print("⚪ %s %s HTTP %s — %s — 보고" % (src, tm, st, why))
if not seen:
    print("출처 HTTP 비200 줄: 미관측 — 이 구간에 자연 발생한 비200 응답 없음(실패를 유도하지 않는다)")
for k in sorted(seen):
    a, f, b, p, q = seen[k]
    print("%s ×%d — 고정 한 줄 %d · 본문 형식/연속 줄 %d · 같은 스레드 상위 FAILED/HTTP_n/0건 짝 %d · 짝 미확인 %d" % (k, a, f, b, p, q))
sys.exit(1 if bad else (3 if chk else 0))
PYEOF
}
# N13 HOLD 기준선 질의 관측(v1.101). 인자: 앱 로그. 출력은 시각·스레드 종류·앱 프레임(클래스.메서드)·건수만(연결 식별자·질의문·본문 없음).
# 누수 경고는 대여 60초 초과 때만 찍힌다(운영 leak-detection-threshold). HOLD 경고 = 첫 앱 프레임이 SignalQualityService.evaluateHoldBaselineLoop.
# 종료 1 = 🔴 P0: 연결 획득 시간 초과 줄 있음, 3 = ⚪ 장기 대여 경고 있음(HOLD 또는 그 밖 — 개선 미확인), 0 = 둘 다 없음
holdobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ holdobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
TRIG = re.compile(r"leak detection triggered for (\S+) on thread (\S+), stack trace follows")
UNL = re.compile(r"Previously reported leaked connection (\S+) on thread (\S+) was returned to the pool \(unleaked\)")
APP = re.compile(r"^\s+at com\.cryptoautotrader\.([\w.$]+)\([\w.]+:\d+\)")
TO = re.compile(r"Connection is not available, request timed out")
kind = lambda t: re.sub(r"-\d+$", "-N", t)
rows = [PRE.sub("", l.rstrip("\r\n")) for l in open(sys.argv[1], encoding="utf-8", errors="replace")]
leaks, cur, to, cs, ce = [], None, [], [], []
context = None
for r in rows:
    h = HEAD.match(r)
    if h:
        context = (h.group(1), kind(h.group(2)))
    # Hikari timeout can be on a non-header Caused by line. Count every matching line once.
    if TO.search(r):
        to.append(context or ("시각미확인", "스레드미확인"))
    if h:
        cur = None
        m = TRIG.search(r)
        if m:
            cur = {"t": h.group(1), "c": m.group(1), "th": m.group(2), "f": None, "ret": None}
            leaks.append(cur)
            continue
        m = UNL.search(r)
        if m:
            for k in reversed(leaks):
                if k["c"] == m.group(1) and k["th"] == m.group(2) and k["ret"] is None:
                    k["ret"] = h.group(1)
                    break
            continue
        if "[SignalQuality] 시작 Catchup 시작" in r:
            cs.append(h.group(1))
        if "[SignalQuality] 시작 Catchup 완료" in r:
            ce.append(h.group(1))
        continue
    if cur is not None and cur["f"] is None:
        m = APP.match(r)
        if m:
            cur["f"] = m.group(1)
HOLDF = "api.service.SignalQualityService.evaluateHoldBaselineLoop"
hold = [k for k in leaks if k["f"] == HOLDF]
other = [k for k in leaks if k["f"] != HOLDF]
print("HOLD 기준선 질의 장기 대여 경고 %d건(반환 %d·반환 미관측 %d) · 그 밖의 장기 대여 경고 %d건 · 연결 획득 시간 초과 줄 %d건" % (
    len(hold), sum(1 for k in hold if k["ret"]), sum(1 for k in hold if not k["ret"]), len(other), len(to)))
for k in hold:
    print("  · HOLD %s %s · 반환 %s" % (k["t"], kind(k["th"]), k["ret"] or "미관측"))
for k in other:
    print("  · 그 밖 %s %s · %s · 반환 %s" % (k["t"], kind(k["th"]), k["f"] or "(앱 프레임 없음)", k["ret"] or "미관측"))
for t, th in to:
    print("  · 🔴 연결 획득 시간 초과 %s %s" % (t, th))
print("시작 Catchup: 시작 %s · 완료 %s (로그 머리 시각 UTC)" % (",".join(cs) or "없음", ",".join(ce) or "없음"))
sys.exit(1 if to else (3 if leaks else 0))
PYEOF
}
