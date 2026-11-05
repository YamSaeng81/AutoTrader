# N20 줄 관측(초안 D1 — 1899b49 고정 문구). 인자: 앱 로그. 출력은 로그 머리 시각·사유·건수만(코인·값은 내지 않는다 — 문구에도 없다).
# 자연 발생만 본다 — 이상 응답·예외를 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다. 종료 3 = ⚪ 인자 오류, 0 = 그 밖
n20obs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ n20obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PATS = [   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
    ("항목 형식", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 가격 형식이 숫자가 아님\s*$")),
    ("항목 가격 아님", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 가격이 아님 \(부호=(?:0|음수)\)\s*$")),
    ("항목 코인 형식", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 코인 형식이 문자열이 아님\s*$")),
    ("시세 처리 실패", re.compile(r"\[시세 보강\] 시세 처리 실패 \(종류=\w+\)\s*$")),
    ("회차 오류", re.compile(r"\[시세 보강\] 회차 오류 \(종류=\w+\)\s*$")),
    ("경보 전송 실패", re.compile(r"\[시세 보강\] 경보 전송 실패 \(종류=\w+\)\s*$")),
    ("호가 보강", re.compile(r"\[시세 보강\] 호가로 보강한 보유 ")),
]
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    for k, p in PATS:
        if p.search(r):
            seen.append((h.group(1), k))
            break
c = lambda k: sum(1 for _, x in seen if x == k)
print("N20 보유 시세 보강 — " + "·".join("%s %d" % (k, c(k)) for k, _ in PATS))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not any(k != "호가 보강" for _, k in seen):
    print("N20: 미관측 — 이 구간에 자연 발생한 시세 보강 항목 건너뜀·처리 실패·회차 오류·경보 전송 실패 없음(유도하지 않는다)")
sys.exit(0)
PYEOF
}
