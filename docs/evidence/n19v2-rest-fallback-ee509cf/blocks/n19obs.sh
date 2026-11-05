# N19 줄 관측(v1.124 — ee509cf 고정 문구). 인자: 앱 로그. 출력은 로그 머리 시각·사유·건수만(코인·값은 내지 않는다 — 문구에도 없다).
# 자연 발생만 본다 — WS 를 끊거나 REST 이상 응답을 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다. 종료 3 = ⚪ 인자 오류, 0 = 그 밖
n19obs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ n19obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PAT = re.compile(r"\[§9\] REST 폴백 시세 항목 건너뜀 . (가격 형식이 숫자가 아님|가격이 아님 \(부호=(?:0|음수)\)|코인 형식이 문자열이 아님)\s*$")   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
KIND = {"가격 형식": "형식", "가격이 아": "가격 아님", "코인 형식": "코인 형식"}
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    m = PAT.search(r)
    if m:
        seen.append((h.group(1), KIND[m.group(1)[:5]]))
c = lambda k: sum(1 for _, x in seen if x == k)
print("N19 REST 전역 폴백 항목 건너뜀 — 형식 이상 %d·가격 아님 %d·코인 형식 %d" % (c("형식"), c("가격 아님"), c("코인 형식")))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not seen:
    print("N19: 미관측 — 이 구간에 자연 발생한 REST 전역 폴백 이상 항목 없음(유도하지 않는다)")
sys.exit(0)
PYEOF
}
