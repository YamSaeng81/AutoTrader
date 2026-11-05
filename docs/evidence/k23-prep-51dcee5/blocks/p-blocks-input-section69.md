**P 변수** — 🔴 새 셸마다 §5-8 **F 변수** → §5-9 의 **G 변수** → 이 블록 순서로 실행한다. F 변수가 `BTCIN`·`PSQL`·`DC`·`sc`·`svc` 를, G 변수가 `qc`·`zero5`·`alertsok`·`sid203`·`REPO`·`QS`·`POOLX`·`NOT404` 를 준다. 이 블록이 `M`·`OLD`·`NEW` 를 덮어쓴다. H~L 변수는 넣지 않는다.
```bash
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
```

**P0. ⏸ 직전 확인 — 읽기 전용, 재기동 없음** (총괄 실행 판단 뒤)
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "$M" = "$HOME/maint-n13" ] && [ "${PV:-}" = 70 ] && [ "$NEW" = fe01207f3f60e97e308925157f84a1bb2da04388 ] && [ -n "${BTCIN:-}" ] && type sid203 > /dev/null 2>&1 && type newsobs > /dev/null 2>&1 && type httpobs > /dev/null 2>&1 && type holdobs > /dev/null 2>&1 || stop "변수 순서 — F 변수 → G 변수 → §5-9 69 P 변수"
[ ! -e "$M/ok-P1" ] || stop "P1 을 이미 시작했다 — P0 를 다시 하지 않는다. ls -l $M 를 보고"
kwin "$(date +%s)" || stop "시작 시각·정기 보고 확인 — 위 줄"
[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "HEAD 가 6995c52 가 아니거나 추적 파일 변경 있음"
date '+%F %T %Z' | tee "$M/t-before.txt"; date '+%F %T%:z' > "$M/maint_start.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-before.txt"
git fetch origin
git cat-file -e "$NEW^{commit}" || stop "후보 fe01207 없음"
git merge-base --is-ancestor $OLD $NEW || stop "6995c52 가 후보의 조상이 아님"
[ "$(git diff --name-only $OLD $NEW)" = "$DIFFN" ] || stop "diff 가 정확히 N13 4개 파일이 아님(마이그레이션 포함 금지)"
svc | tee "$M/svc-before.txt"
IMG0=$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)"); echo "$IMG0" | tee "$M/image-before.txt"
[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n12/image-new.txt)" ] || stop "실행 이미지가 #21 배포 이미지(67294e80…)와 다름"
[ "$(docker image inspect --format '{{.Id}}' $REPO:ea44900)" = "$IMGPREV" ] || stop "이전 복구 태그 $REPO:ea44900 이 #19 이미지(61804654…)가 아님 — 보고"
[ "$(docker image inspect --format '{{.Id}}' $REPO:b1d7664)" = "$IMGPREV2" ] || stop "이전 복구 태그 $REPO:b1d7664 이 #20 이미지(fd3ac351…)가 아님 — 보고"
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
[ "$(cat "$OPS/INSTALLED_SHA")" = "$CK" ] && [ "$(h256 "$OPS/daily_check.sh")" = "$CKSH" ] && [ "$(h256 "$OPS/daily_checks.sql")" = "$CKSQL" ] || stop "정기 점검 설치본이 c669ccd 판이 아님(N14 먼저 설치)"
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
echo "== 배포 전 기준선: HOLD 질의 장기 대여(holdobs 종료 $HD — 6995c52 는 HOLD 경고가 있으므로 3 이어도 멈추지 않는다, 기록만)"; cat "$M/hold-before.txt"
python3 scripts/prospective/start_66.py alerts --days 1 --no-mark > "$M/alerts-before.txt" 2>&1 && AE=0 || AE=$?
echo "경보 종료코드 $AE"
alertsok "$AE" "$M/alerts-before.txt" || stop "경보 조회가 끝까지 돌지 않음 — alerts-before.txt 를 보고"
grep -E '^(🔴|⚪)' "$M/alerts-before.txt" || true
touch "$M/ok-P0"; echo "✅ P0 통과 — 30분 안에 P1"
)
```

**P1. 새 이미지 빌드와 jar 대조 — 재기동 없음**
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"
[ -e "$M/ok-P0" ] || stop "P0 미통과"
[ ! -e "$M/ok-P1" ] || stop "P1 을 이미 통과했다 — 바로 P2"
[ $(( $(date +%s) - $(stat -c %Y "$M/ok-P0") )) -le 1800 ] || stop "P0 가 30분보다 오래됨 — P0 부터(아직 바꾼 것 없음, P1-되돌림 하지 않는다)"
IMG0=$(cat "$M/image-before.txt")
if docker image inspect $REPO:6995c52 > /dev/null 2>&1; then [ "$(docker image inspect --format '{{.Id}}' $REPO:6995c52)" = "$IMG0" ] || stop "$REPO:6995c52 태그가 이미 다른 이미지를 가리킨다(HEAD·latest 그대로, P1-되돌림 하지 않는다)"; fi
docker tag "$IMG0" $REPO:6995c52
[ "$(docker image inspect --format '{{.Id}}' $REPO:6995c52)" = "$IMG0" ] || stop "기존 이미지 보존 태그 실패(HEAD·latest 그대로, P1-되돌림 하지 않는다)"
git checkout --quiet $NEW
[ "$(git rev-parse HEAD)" = "$NEW" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "체크아웃 결과가 NEW 가 아니거나 변경 있음 — P1-되돌림"
date '+%F %T %Z' | tee "$M/t-build.txt"
$DC build backend > "$M/build.log" 2>&1 || stop "빌드 실패 — build.log 를 보고. P1-되돌림"
IMG1=$(docker image inspect --format '{{.Id}}' $REPO:latest); echo "$IMG1" | tee "$M/image-new.txt"
[ "$IMG1" != "$IMG0" ] || stop "새 이미지가 만들어지지 않음 — P1-되돌림"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "🔴 실행 중 backend 가 바뀌었다 — 보고"
docker cp "$($DC ps -q backend)":/app/app.jar "$M/app-old.jar"
CID=$(docker create $REPO:latest); docker cp "$CID":/app/app.jar "$M/app-new.jar"; docker rm "$CID" > /dev/null
sha256sum "$M/app-old.jar" "$M/app-new.jar" | tee "$M/jar-sha.txt"
python3 - "$M/app-old.jar" "$M/app-new.jar" <<'EOF' | tee "$M/jar-diff.txt"
import hashlib, io, re, sys, zipfile
OWN = re.compile(r"BOOT-INF/lib/(core-engine|strategy-lib|exchange-adapter)[^/]*\.jar$")
def entries(path):
    z, out = zipfile.ZipFile(path), {}
    if len(set(z.namelist())) != len(z.namelist()):
        out["(중복 항목 이름 있음)"] = path
    for n in z.namelist():
        if n.endswith("/"):
            continue
        if OWN.match(n):
            inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
            for m in inner.namelist():
                if not m.endswith("/"):
                    out[re.sub(r"-[0-9.]+(-SNAPSHOT)?\.jar$", ".jar", n) + "!" + m] = hashlib.sha256(inner.read(m)).hexdigest()
        else:
            out[n] = hashlib.sha256(z.read(n)).hexdigest()
    return out
a, b = entries(sys.argv[1]), entries(sys.argv[2])
for k in sorted(set(a) | set(b)):
    if a.get(k) != b.get(k):
        print(("추가 " if k not in a else "삭제 " if k not in b else "변경 ") + k)
print("(끝)", len(a), len(b))
EOF
[ "$(grep -v '^(끝)' "$M/jar-diff.txt")" = "$PARX" ] || stop "jar 차이가 N13 1줄이 아님 — jar-diff.txt 를 보고(재기동 전). P1-되돌림"
grep -qx '(끝) 879 879' "$M/jar-diff.txt" || stop "항목 수가 879 → 879 가 아님 — jar-diff.txt 를 보고(재기동 전). P1-되돌림"
touch "$M/ok-P1"; echo "✅ P1 통과 — ⏸ 운영자 확인 1회 뒤 P2"
)
```

- **P1-되돌림**: 멈춤 문구에 "P1-되돌림" 이 있을 때만 한다(`git checkout` 뒤). `git checkout --quiet $OLD; docker tag "$(cat "$M/image-before.txt")" $REPO:latest; git rev-parse HEAD; docker image inspect --format '{{.Id}}' $REPO:latest` → `6995c522…`·`image-before.txt` 와 같은 ID. 재기동하지 않는다. 그 밖의 P1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:6995c52` 는 지금 이미지를 가리키므로 남겨 둔다.

**P2. 재기동 (개입 #22) — P1 통과 뒤** (⏸ 바로 앞에서 운영자 확인을 한 번 받는다)
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"
[ -e "$M/ok-P1" ] || stop "P1 미통과"
[ ! -e "$M/ok-P2" ] || stop "P2 를 이미 했다 — P3"
IMG1=$(cat "$M/image-new.txt")
[ "$(git rev-parse HEAD)" = "$NEW" ] || stop "HEAD 가 NEW 가 아님"
[ "$(docker image inspect --format '{{.Id}}' $REPO:latest)" = "$IMG1" ] || stop "latest 가 image-new 와 다름"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 다른 것 있음"
[ "$(sc)" = "66 | 63 | 3 | -" ] || stop "검증 66세션 상태 다름"
date '+%F %T %Z' | tee "$M/t-deploy.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-deploy.utc"
echo "== 여기부터 재기동 — 이 뒤의 멈춤은 '되돌림 필요'"
$DC up -d --no-build --no-deps backend || stop "되돌림 필요: 재생성 실패"
sleep 15
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG1" ] || stop "되돌림 필요: 새 컨테이너 이미지가 image-new 와 다름"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "되돌림 필요: 공유 풀 등록값 다름"
svc | tee "$M/svc-deploy.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-deploy.txt") || stop "되돌림 필요: backend 외 서비스가 바뀜"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-deploy.txt"
touch "$M/ok-P2"; echo "✅ P2 통과 — 5~10분 뒤 P3"
)
```

**P3. 기동·뉴스·HTTP 줄·HOLD 질의 확인 — P2 5~10분 뒤** (되돌림 뒤에도 같은 묶음을 쓴다. `t-rollback.utc` 가 있으면 그 시각과 기존 이미지로 보고, BTC·뉴스·HTTP 줄은 기록만 한다)
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"
if [ -e "$M/t-rollback.utc" ]; then S=$(cat "$M/t-rollback.utc"); EXP=$(cat "$M/image-before.txt"); T=rollback; else [ -e "$M/ok-P2" ] || stop "P2 미통과"; S=$(cat "$M/t-deploy.utc"); EXP=$(cat "$M/image-new.txt"); T=deploy; fi
[ $(( $(date +%s) - $(date -d "$S" +%s) )) -ge 300 ] || stop "기동 5분 전 — 5분이 지난 뒤 P3"
$DC ps backend
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$EXP" ] || stop "실행 이미지가 기대와 다름"
$PSQL -At -c "$NEWSCFG" > "$M/news-$T.txt" || stop "뉴스 설정 조회 실패 — 1분 뒤 P3 를 다시"
OBS=$(date +%s); echo "$OBS" > "$M/obs-$T.txt"   # 관측 시각 = 뉴스 설정을 읽은 시각(로그는 그 뒤에 읽는다)
$DC logs --no-color --since "$S" backend > "$M/app-$T.log"
grep -E "Schema .* is up to date|Migrating schema|Successfully applied|매도 격리 점검|Started CryptoAutoTrader" "$M/app-$T.log" | head -10
grep -q "Started CryptoAutoTrader" "$M/app-$T.log" || stop "되돌림 필요: 기동 완료 줄 없음"
! grep -qE "Migrating schema|Successfully applied" "$M/app-$T.log" || stop "되돌림 필요: 마이그레이션이 돌았다(이번 배포에는 없어야 함)"
v94 || stop "되돌림 필요: Flyway 최고 버전이 94 가 아님"
grep -qF "공유 풀 한도 확인: 단계=STAGE_1 총상한=100000 포지션당=20000 동시보유=3" "$M/app-$T.log" || stop "되돌림 필요: 공유 풀 등록 로그 없음"
grep -qF "위험 한도 확인: 단계 STAGE_1 총 자본 100000 기준 누적 15.00%·24시간 3.00%" "$M/app-$T.log" || stop "되돌림 필요: 위험 한도 로그 없음"
! grep -qE "🔴 (공유 풀 한도|위험 한도)" "$M/app-$T.log" || stop "되돌림 필요: 🔴 공유 풀/위험 한도 줄"
grep " ERROR " "$M/app-$T.log" | grep -v "캔들 최신성" | grep -v -E "$NOT404" > "$M/errors-all-$T.txt" || true
grep -E "$NEWSERR" "$M/errors-all-$T.txt" > "$M/errors-news-$T.txt" || true
grep -v -E "$NEWSERR" "$M/errors-all-$T.txt" > "$M/errors-$T.txt" || true
[ ! -s "$M/errors-$T.txt" ] || stop "세 마켓 캔들 404·뉴스 출처 ERROR 밖의 ERROR — errors-$T.txt 를 보고"
if [ -s "$M/errors-news-$T.txt" ]; then
  echo "🔴 뉴스 출처 ERROR $(wc -l < "$M/errors-news-$T.txt")줄 — 미분류 🔴 그대로, 이것만으로 되돌리지 않는다. 원문과 배포 전 기준선 $(wc -l < "$M/news-err-before.txt")줄을 함께 보고"
  cut -c1-200 "$M/errors-news-$T.txt"
fi
grep -E "BTC 급락 관문 H1 판정 (불가|재개)" "$M/app-$T.log" | cut -c1-140 || true
btcgate "$M/app-$T.log" || stop "BTC 관문 마지막 상태가 판정 불가 — 바로 되돌리지 말고 위 출력을 보고"
python3 "$M/maint_verify.py" candle "$M/app-$T.log" "$M/backend-before.log" || stop "기동 뒤 판정 불가 대상이 아직 재개되지 않음 — 위 출력을 보고"
qc > "$M/q-$T.txt" 2>&1; sed -n '/== Q3/,$p' "$M/q-$T.txt" | grep -vE '^-+|^\s*$'
sc | tee "$M/s-$T.txt"; diff "$M/s-before.txt" "$M/s-$T.txt" || stop "되돌림 필요: 검증 세션 상태 바뀜"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 바뀐 것 있음 — 보고"
$PSQL -At -c "$BTCIN" > "$M/btc-$T.txt" || stop "BTC 입력 조회 실패 — 1분 뒤 P3 를 다시"
echo "== BTC H1 배포 전 $(cat "$M/btc-before.txt") · 지금 $(cat "$M/btc-$T.txt")"
if [ "$T" = deploy ]; then btcok "$M/btc-$T.txt" || stop "BTC 입력 미충족 — 바로 되돌리지 말고 위 출력을 보고(재기동 없이 P3 만 5분 뒤 한 번 다시)"; fi
echo "== 뉴스 출처별(기동 $(cut -c12-19 <<< "$S")Z 뒤 로그 · 첫 회차는 기동 3분 뒤)"
newsobs "$M/app-$T.log" "$M/news-before.txt" "$M/news-$T.txt" "$(date -d "$S" +%s)" "$OBS" | tee "$M/newsobs-$T.txt" && NO=0 || NO=$?
[ "$NO" = 0 ] || [ "$NO" = 3 ] || [ "$T" = rollback ] || stop "뉴스 관측 이상(종료 $NO) — 되돌리지 말고 위 출력을 보고"
if [ "$NO" = 3 ]; then cp "$M/newsobs-$T.txt" "$M/news-check-$T.txt"; echo "⚪ 뉴스 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고(정상 관측 아님)"; fi
httpobs "$M/app-$T.log" | tee "$M/httpobs-$T.txt" && HO=0 || HO=$?
[ "$HO" = 0 ] || [ "$HO" = 3 ] || [ "$T" = rollback ] || stop "출처 HTTP 비200 줄이 고정 한 줄이 아님(종료 $HO) — 되돌리지 말고 위 출력을 보고"
if [ "$HO" = 3 ]; then cp "$M/httpobs-$T.txt" "$M/http-check-$T.txt"; echo "⚪ HTTP 줄 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고"; fi
echo "== HOLD 질의 장기 대여·연결 획득(기동 뒤 로그 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-$T.log" | tee "$M/holdobs-$T.txt" && HQ=0 || HQ=$?
[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, P 되돌림 블록 준비됨)"
if [ "$HQ" = 3 ]; then cp "$M/holdobs-$T.txt" "$M/hold-check-$T.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi
touch "$M/ok-P3-$T"
if [ "$T" = deploy ]; then echo "✅ P3(deploy) 통과 — 기동 30분 뒤 P4"; else echo "✅ P3(rollback) 통과 — 6995c52 복귀 확인. P4 는 하지 않고 출력을 보고"; fi
)
```

**P4. 30분 재점검 — 기동 30분 뒤**
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"
[ -e "$M/ok-P3-deploy" ] || stop "P3 미통과"
S=$(cat "$M/t-deploy.utc")
[ $(( $(date +%s) - $(date -d "$S" +%s) )) -ge 1800 ] || stop "아직 30분 전 — 완료로 판정하지 않는다"
date '+%F %T %Z' | tee "$M/t-after30.txt"
$DC ps backend
$PSQL -At -c "$NEWSCFG" > "$M/news-after30.txt" || stop "뉴스 설정 조회 실패 — 1분 뒤 P4 를 다시"
OBS=$(date +%s); echo "$OBS" > "$M/obs-after30.txt"   # 관측 시각 = 뉴스 설정을 읽은 시각(로그는 그 뒤에 읽는다)
$DC logs --no-color --since "$S" backend > "$M/app-after30.log"
grep " ERROR " "$M/app-after30.log" | grep -v "캔들 최신성" | grep -v -E "$NOT404" > "$M/errors-all-after30.txt" || true
grep -E "$NEWSERR" "$M/errors-all-after30.txt" > "$M/errors-news-after30.txt" || true
grep -v -E "$NEWSERR" "$M/errors-all-after30.txt" > "$M/errors-after30.txt" || true
[ ! -s "$M/errors-after30.txt" ] || stop "세 마켓 캔들 404·뉴스 출처 ERROR 밖의 ERROR — errors-after30.txt 를 보고"
if [ -s "$M/errors-news-after30.txt" ]; then
  echo "🔴 뉴스 출처 ERROR $(wc -l < "$M/errors-news-after30.txt")줄 — 미분류 🔴 그대로, 이것만으로 되돌리지 않는다. 원문과 배포 전 기준선 $(wc -l < "$M/news-err-before.txt")줄을 함께 보고"
  cut -c1-200 "$M/errors-news-after30.txt"
fi
! grep -qE "🔴 (공유 풀 한도|위험 한도)" "$M/app-after30.log" || stop "되돌림 필요: 🔴 공유 풀/위험 한도 줄"
v94 || stop "되돌림 필요: Flyway 최고 버전이 94 가 아님"
grep -E "BTC 급락 관문 H1 판정 (불가|재개)" "$M/app-after30.log" | cut -c1-140 || true
btcgate "$M/app-after30.log" || stop "BTC 관문 마지막 상태가 판정 불가 — 바로 되돌리지 말고 위 출력을 보고"
python3 "$M/maint_verify.py" candle "$M/app-after30.log" "$M/backend-before.log" || stop "판정 불가 대상이 남아 있음 — 위 출력을 보고"
qc > "$M/q-after30.txt" 2>&1; sed -n '/== Q3/,$p' "$M/q-after30.txt" | grep -vE '^-+|^\s*$'
sc | tee "$M/s-after30.txt"; diff "$M/s-before.txt" "$M/s-after30.txt" || stop "되돌림 필요: 검증 세션 상태 바뀜"
svc | tee "$M/svc-after30.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-after30.txt") || stop "되돌림 필요: backend 외 서비스가 바뀜"
[ "$(grep '^backend ' "$M/svc-deploy.txt")" = "$(grep '^backend ' "$M/svc-after30.txt")" ] || stop "되돌림 필요: backend 재시작 있음"
sid203 || stop "SID 203·기준 행·EG·0 확인 중 바뀐 것 있음 — 보고"
[ "$(h256 "$OPS/sid_ledger.sql")" = "$LSUM" ] && [ "$(cat "$OPS/SID_LEDGER_SHA")" = "$LS" ] || stop "설치 장부가 바뀜 — 보고"
[ "$(cat "$OPS/INSTALLED_SHA")" = "$CK" ] && [ "$(h256 "$OPS/daily_check.sh")" = "$CKSH" ] && [ "$(h256 "$OPS/daily_checks.sql")" = "$CKSQL" ] || stop "정기 점검 설치본이 바뀜 — 보고"
crontab -l | cmp -s - "$M/crontab-before.txt" || stop "cron 이 P0 때와 다름 — crontab -l 을 보고"
( cd ~/sid203-ledger && sha256sum --quiet -c "$M/ledger-files-before.sha" ) || stop "과거 장부 기록 파일이 바뀜 — 보고"
$PSQL -At -c "$BTCIN" > "$M/btc-after30.txt" || stop "BTC 입력 조회 실패 — 1분 뒤 P4 를 다시"
echo "== BTC H1 P3 $(cat "$M/btc-deploy.txt") · 지금 $(cat "$M/btc-after30.txt")"
btcok "$M/btc-after30.txt" || stop "BTC 입력 미충족 — 바로 되돌리지 말고 위 출력을 보고"
echo "== 뉴스 출처별(기동 뒤 30분 로그)"
newsobs "$M/app-after30.log" "$M/news-before.txt" "$M/news-after30.txt" "$(date -d "$S" +%s)" "$OBS" | tee "$M/newsobs-after30.txt" && NO=0 || NO=$?
[ "$NO" = 0 ] || [ "$NO" = 3 ] || stop "뉴스 관측 이상(종료 $NO) — 되돌리지 말고 위 출력을 보고"
if [ "$NO" = 3 ]; then cp "$M/newsobs-after30.txt" "$M/news-check-after30.txt"; echo "⚪ 뉴스 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고(정상 관측 아님)"; fi
httpobs "$M/app-after30.log" | tee "$M/httpobs-after30.txt" && HO=0 || HO=$?
[ "$HO" = 0 ] || [ "$HO" = 3 ] || stop "출처 HTTP 비200 줄이 고정 한 줄이 아님(종료 $HO) — 되돌리지 말고 위 출력을 보고"
if [ "$HO" = 3 ]; then cp "$M/httpobs-after30.txt" "$M/http-check-after30.txt"; echo "⚪ HTTP 줄 확인 필요 — 되돌리지 않는다. 위 ⚪ 줄을 보고"; fi
echo "== HOLD 질의 장기 대여·연결 획득(기동 뒤 30분 로그 — 시작 catchup 과 5분 뒤 첫 정기 회차를 포함 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-after30.log" | tee "$M/holdobs-after30.txt" && HQ=0 || HQ=$?
[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, P 되돌림 블록 준비됨)"
grep -q "시작 Catchup 완료" "$M/app-after30.log" || echo "⚪ 시작 catchup 완료 줄이 아직 없다 — HOLD 질의 개선 관측 미완(되돌리지 않는다)"
if [ "$HQ" = 3 ]; then cp "$M/holdobs-after30.txt" "$M/hold-check-after30.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi
python3 scripts/prospective/start_66.py alerts --since "$S" --no-mark > "$M/alerts-after30.txt" 2>&1 && AE=0 || AE=$?
echo "경보 종료코드 $AE (1 = 🔴 경보 있음 — 이것만으로 멈추지 않고 네 갈래로 대조)"
alertsok "$AE" "$M/alerts-after30.txt" || stop "경보 조회가 끝까지 돌지 않음 — alerts-after30.txt 를 보고"
echo "== 경보 (지금)"; cat "$M/alerts-after30.txt"
echo "== 경보 (배포 전 기준선)"; cat "$M/alerts-before.txt"
touch "$M/ok-P4"; echo "✅ P4 통과 — P0~P4 출력을 보고(F 변수·F1 은 총괄 판단 뒤 개발 에이전트가 갱신)"
[ ! -e "$M/news-check-after30.txt" ] || echo "⚪ 단, 뉴스 확인 필요가 남았다 — 뉴스 관측 완료로 보고하지 않는다(news-check-after30.txt)"
[ ! -e "$M/http-check-after30.txt" ] || echo "⚪ 단, HTTP 줄 확인 필요가 남았다 — N12 운영 관측 완료로 보고하지 않는다(http-check-after30.txt)"
[ ! -e "$M/hold-check-after30.txt" ] || echo "⚪ 단, 장기 대여 경고가 남았다 — N13 개선 확인으로 보고하지 않는다(hold-check-after30.txt)"
)
```

**P 뒤 관측(뉴스·HTTP 줄·HOLD 질의) — 읽기 전용, 반복 가능** (P4 통과 뒤 다음 정기 보고 무렵 등. 수집 API·강제 재시도·주기 변경 없이 기존 주기의 로그만 읽는다)
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"
[ -e "$M/ok-P4" ] || stop "P4 미통과"
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
echo "== 기동 뒤 HOLD 질의 장기 대여·연결 획득(N13 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-news-$TS.log" | tee "$M/holdobs-$TS.txt" && HQ=0 || HQ=$?
case "$HQ" in 0) ;; 3) echo "⚪ 장기 대여 경고 있음 — 개선 미확인, 위 줄을 보고" ;; *) echo "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고" ;; esac
)
```

**P 되돌림 — backend (P2~P4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. 구판으로 돌아가면 HOLD 질의 장기 대여는 다시 생길 수 있다)
```bash
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${PV:-}" = 70 ] || stop "P 변수가 v1.102(§5-9 69 블록·§70 판정) 판이 아님 — F → G → §5-9 69 P 변수"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #21 이미지(67294e80…)가 아님"
date '+%F %T %Z' | tee "$M/t-rollback.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-rollback.utc"
git checkout --quiet $OLD; [ "$(git rev-parse HEAD)" = "$OLD" ] || stop "체크아웃 실패"
docker tag "$IMG0" $REPO:latest
$DC up -d --no-build --no-deps --force-recreate backend
sleep 15
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "이미지가 image-before 와 다름"
[ "$($DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort)" = "$POOLX" ] || stop "공유 풀 등록값 다름"
svc | tee "$M/svc-rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-rollback.txt"
echo "✅ 6995c52 (67294e80…) 로 복귀 — 5분 뒤 P3 를 다시 실행(t-rollback.utc 기준으로 자동 전환, BTC·뉴스·HTTP 줄·HOLD 질의는 기록만)"
)
```

