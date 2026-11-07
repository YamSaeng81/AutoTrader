import re, sys
if len(sys.argv) != 2:
    print("⚪ n17obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PAT = re.compile(r"\[(Dynamic\]\[)?§9\] REST 강제 갱신 시세가 가격이 아님 . 발행하지 않음 \(")   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    m = PAT.search(r)
    if m:
        seen.append((h.group(1), "DYNAMIC" if m.group(1) else "LIVE"))
print("N17 REST 강제 갱신 가격 아님(INVALID_PRICE·미발행) — LIVE %d·DYNAMIC %d" % (
    sum(1 for _, k in seen if k == "LIVE"), sum(1 for _, k in seen if k == "DYNAMIC")))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not seen:
    print("N17: 미관측 — 이 구간에 자연 발생한 REST 강제 갱신 가격 이상 없음(유도하지 않는다)")
sys.exit(0)
