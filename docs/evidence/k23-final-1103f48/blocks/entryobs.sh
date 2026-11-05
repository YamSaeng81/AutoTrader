# N15·N16 줄 관측(v1.111). 인자: 앱 로그. 출력은 로그 머리 시각·엔진 종류·건수만(코인·세션 id·가격·사유 본문은 내지 않는다).
# 자연 발생만 본다 — 가격 이상·만료를 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다.
# 종료 1 = 🔴 만료 사유 기록 실패 줄 있음(원래 신호에 사유가 남지 않았을 수 있다), 3 = ⚪ 인자 오류, 0 = 그 밖(관측·미관측 모두)
entryobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ entryobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
CAT = [
    ("콜백 전 만료", re.compile(r"\[진입 우선순위\] \d+위 \S+ (LIVE|DYNAMIC)#\d+ → 승인 실행 만료\(")),
    ("의도 직전 만료", re.compile(r"의도 저장 직전 승인 실행 만료 — 매수 의도를 만들지 않는다 \(sessionKind=(LIVE|DYNAMIC),")),
    ("예약·의도 경계 거절", re.compile(r"(매수 예약이 거절돼 롤백했다|\[Dynamic\] 매수 예약 거절) \(.*사유=ENTRY_EXPIRED\)")),
    ("만료 사유 기록 실패", re.compile(r"\[진입 우선순위\] 만료 사유 기록 실패")),
    ("가격 아님 무시", re.compile(r"(\[Dynamic\] )?가격이 아닌 실시간 시세 무시: ")),
]
def kind(name, m, line):
    if name == "예약·의도 경계 거절":
        return "DYNAMIC" if m.group(1).startswith("[Dynamic]") else "LIVE"
    if name == "가격 아님 무시":
        return "DYNAMIC" if m.group(1) else "LIVE"
    if name == "만료 사유 기록 실패":
        return "-"
    return m.group(1)
seen = {c[0]: [] for c in CAT}
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    for name, pat in CAT:
        m = pat.search(r)
        if m:
            seen[name].append((h.group(1), kind(name, m, r)))
            break
cnt = lambda n, k: sum(1 for _, x in seen[n] if x == k)
n15 = sum(len(seen[n]) for n in ("콜백 전 만료", "의도 직전 만료", "예약·의도 경계 거절", "만료 사유 기록 실패"))
print("N15 승인 실행 만료 — 콜백 전 LIVE %d·DYNAMIC %d · 예약·의도 경계 거절(ENTRY_EXPIRED) LIVE %d·DYNAMIC %d(그중 의도 직전 LIVE %d·DYNAMIC %d) · 만료 사유 기록 실패 %d" % (
    cnt("콜백 전 만료", "LIVE"), cnt("콜백 전 만료", "DYNAMIC"), cnt("예약·의도 경계 거절", "LIVE"), cnt("예약·의도 경계 거절", "DYNAMIC"),
    cnt("의도 직전 만료", "LIVE"), cnt("의도 직전 만료", "DYNAMIC"), len(seen["만료 사유 기록 실패"])))
print("N16 가격이 아닌 실시간 시세 무시 — LIVE %d·DYNAMIC %d" % (cnt("가격 아님 무시", "LIVE"), cnt("가격 아님 무시", "DYNAMIC")))
for name, _ in CAT:
    for t, k in seen[name][:20]:
        print("  · %s%s %s %s" % ("🔴 " if name == "만료 사유 기록 실패" else "", name, t, k))
    if len(seen[name]) > 20:
        print("  · %s 그 밖 %d건(목록 생략)" % (name, len(seen[name]) - 20))
if not n15:
    print("N15: 미관측 — 이 구간에 자연 발생한 승인 실행 만료 없음(유도하지 않는다)")
if not seen["가격 아님 무시"]:
    print("N16: 미관측 — 이 구간에 자연 발생한 가격 아닌 실시간 시세 없음(유도하지 않는다)")
sys.exit(1 if seen["만료 사유 기록 실패"] else 0)
PYEOF
}
