# N05 최종판 줄 대응(총괄 v1.176 §4-2): 후보판 88f06345 의 K14~K23 코드 위치를 운영 SHA 0e1d2a02 로 옮긴다.
# Git 블롭만 읽는다(작업 사본·빌드 산출물 미사용). 줄 대응은 difflib 정렬의 같은 줄 구간으로만 하고, 바뀐 구간 안 줄은 '대응 없음'으로 남긴다.
# 사용: python -I n05map.py <저장소 경로>  → 표 출력(표준 출력). 종료값 0 = 모든 위치가 대응·기대 토큰 포함, 1 = 하나라도 아님.
import difflib, subprocess, sys
sys.stdout.reconfigure(encoding="utf-8")   # -I 는 PYTHONIOENCODING 을 무시한다(1차 실행 cp949 오류, runs/n05map-0e1d2a02.run1-cp949.txt)

REPO = sys.argv[1]
OLD, NEW = "88f06345", "0e1d2a02"

def git(*a):
    return subprocess.run(["git", "-C", REPO, *a], capture_output=True, check=True).stdout

ALIAS = {"OEE": "OrderExecutionEngine", "LTS": "LiveTradingService", "DTS": "DynamicTradingService",
         "RPD": "RealtimeProtectionDispatcher", "RMS": "RiskManagementService", "PTS": "PaperTradingService",
         "SBU": "SessionBalanceUpdater", "DSBU": "DynamicSessionBalanceUpdater", "PTC": "PaperTradingController"}

# (번호, 약어, 후보판 줄, 그 줄에 있어야 할 토큰 — 표의 설명에서 가져온 이름. 토큰이 없으면 후보판 표 자체의 줄 표기 오류로 본다)
REFS = [
    ("K14", "OEE", 597, "markClosingWithSellIntent"),
    ("K14", "LTS", 1757, "markClosingWithSellIntent"), ("K14", "DTS", 2020, "markClosingWithSellIntent"),
    ("K14", "LTS", 1822, "markClosingWithSellIntent"), ("K14", "DTS", 2558, "markClosingWithSellIntent"),
    ("K14", "LTS", 2142, "markClosingWithSellIntent"), ("K14", "DTS", 2753, "markClosingWithSellIntent"),
    ("K15", "OEE", 563, "submitPreparedOrderAfterCommit"), ("K15", "OEE", 664, "submitPreparedOrder"),
    ("K16", "OEE", 1847, "pollActiveOrders"), ("K16", "OEE", 1964, "resolveUnresolvedSubmission"),
    ("K16", "OEE", 2022, None),
    ("K16", "LTS", 2833, None), ("K16", "DTS", 2835, None),
    ("K16", "LTS", 2035, None), ("K16", "DTS", 2646, None),
    ("K16", "LTS", 2469, None), ("K16", "DTS", 404, None),
    ("K17", "RPD", 76, "onPrice"), ("K17", "RPD", 85, "retryPending"),
    ("K17", "LTS", 3393, None), ("K17", "DTS", 3464, None),
    ("K18", "SBU", 89, None), ("K18", "DSBU", 61, None), ("K18", "LTS", 2810, None),
    ("K19", "RMS", 211, "checkRealLossGuard"),
    ("K20", "PTS", 344, "stopOnce"), ("K20", "PTS", 331, None), ("K20", "PTC", 133, None),
    ("K21", "PTS", 1406, "restoreAlignedClosing"),
    ("K22", "PTS", 1666, None), ("K22", "PTS", 1249, "settleClosingFresh"),
    ("K22", "PTS", 763, "settleClosingFromMarkBook"), ("K22", "PTS", 798, "settleClosingFromMarkBook"),
    ("K22", "PTS", 1418, "settleClosingFromMarkBook"), ("K22", "PTS", 1368, None),
    ("K23", "PTS", 479, "deleteSession"), ("K23", "PTS", 487, None), ("K23", "PTS", 502, None),
]

paths = {}
for p in git("ls-tree", "-r", "--name-only", NEW).decode().splitlines():
    if p.startswith("web-api/src/main/java/"):
        base = p.rsplit("/", 1)[-1][:-5]
        if base in ALIAS.values():
            paths.setdefault(base, []).append(p)

cache = {}
def load(cls):
    if cls in cache: return cache[cls]
    (p,) = paths[cls]
    o = git("show", f"{OLD}:{p}").decode("utf-8").split("\n")
    n = git("show", f"{NEW}:{p}").decode("utf-8").split("\n")
    bo = git("rev-parse", f"{OLD}:{p}").decode().strip(); bn = git("rev-parse", f"{NEW}:{p}").decode().strip()
    m = {}
    for blk in difflib.SequenceMatcher(None, o, n, autojunk=False).get_matching_blocks():
        for k in range(blk.size): m[blk.a + k + 1] = blk.b + k + 1
    cache[cls] = (p, o, n, bo, bn, m)
    return cache[cls]

print(f"# 후보판 {OLD} → 운영 {NEW}")
for cls in sorted({ALIAS[a] for _, a, _, _ in REFS}):
    p, o, n, bo, bn, _ = load(cls)
    print(f"파일 {p} · 블롭 {bo[:12]} → {bn[:12]} · {'같음' if bo == bn else '다름'} · 줄 {len(o)} → {len(n)}")
bad = 0
print("| 번호 | 위치(후보판) | 위치(운영) | 이동 | 토큰 | 운영 줄 원문(앞 110자) |")
print("|---|---|---|---|---|---|")
for k, a, ln, tok in REFS:
    p, o, n, bo, bn, m = load(ALIAS[a])
    new = m.get(ln)
    # 토큰은 그 줄에서 먼저 찾고, 없으면 ±5줄 안 가장 가까운 줄을 찾아 거리(후보판 표기와 선언 줄의 차이)를 적는다. 그 안에도 없으면 불일치.
    tok_at = None
    if tok is not None:
        for d in sorted(range(-5, 6), key=abs):
            if 1 <= ln + d <= len(o) and tok in o[ln + d - 1]: tok_at = d; break
    tok_ok = tok is None or tok_at is not None
    same = new is not None and n[new - 1] == o[ln - 1]
    if new is None or not tok_ok or not same: bad += 1
    txt = (n[new - 1] if new else o[ln - 1]).strip().replace("|", "\\|")[:110]
    tcol = "-" if tok is None else (f"없음(±5):{tok}" if tok_at is None else (tok if tok_at == 0 else f"{tok}({tok_at:+d}줄)"))
    print(f"| {k} | `{a}:{ln}` | {'`'+a+':'+str(new)+'`' if new else '대응 없음'} | {('+'+str(new-ln)) if new and new>=ln else (str(new-ln) if new else '-')} "
          f"| {tcol} | `{txt}` |")
print(f"# 결과: 위치 {len(REFS)}개 중 불일치 {bad}")
sys.exit(1 if bad else 0)
