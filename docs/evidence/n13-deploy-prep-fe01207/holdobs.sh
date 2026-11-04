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
for r in rows:
    h = HEAD.match(r)
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
        if TO.search(r):
            to.append((h.group(1), kind(h.group(2))))
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
