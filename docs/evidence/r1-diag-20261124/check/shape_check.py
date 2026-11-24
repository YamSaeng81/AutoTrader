"""r1-diag-1 출력 형태·합 대조 (로컬 PC 전용, 결과 해석 없음).

사용: python -I shape_check.py <출력.tsv> [--no-meta]
  --no-meta : 파일 입력 대조(model.py 출력)처럼 META/REF/END/LEG 가 없는 입력일 때.
종료값: 0 SHAPE_OK · 7 SHAPE_MISMATCH(이유를 한 줄씩 표준 출력). 숫자 외 값은 읽지 않는다.
"""
import sys

TAGS = {"META", "REF", "ORIG", "SPLIT", "AM", "PM", "AX", "AF", "PX", "PF", "PE", "UQ", "AGE", "LEG", "END"}
KINDS = ("LIVE", "DYNAMIC")

def main(path, no_meta):
    rows = []
    for line in open(path, encoding="utf-8").read().splitlines():
        f = line.split("\t")
        while len(f) > 1 and f[-1] == "\\N":   # SQL 결과의 빈 열(\N) 꼬리 제거
            f.pop()
        rows.append(f)
    err = []
    if not rows:
        err.append("빈 출력")
    bad = [r[0] for r in rows if r[0] not in TAGS]
    if bad:
        err.append(f"허용 밖 태그 {sorted(set(bad))}")
    if not no_meta and rows:
        m = rows[0]
        if not (m[0] == "META" and len(m) == 10 and m[1] == "r1-diag-1" and m[6] == "repeatable read" and m[8] == "15s" and m[9] == "2s"):
            err.append("META 첫 줄 형태·보호값 불일치")
        if not (rows[-1][0] == "END" and len(rows[-1]) == 2):
            err.append("END 끝 줄 없음")
    by = lambda t: [r for r in rows if r[0] == t]
    def num(v):
        if not v.isdigit():
            err.append(f"정수 아님: {v!r}"); return 0
        return int(v)
    for k in KINDS:
        o = [num(r[2]) for r in by("ORIG") if r[1] == k]
        s = [r for r in by("SPLIT") if r[1] == k]
        if len(o) != 1 or len(s) != 1:
            err.append(f"{k}: ORIG/SPLIT 행 수 {len(o)}/{len(s)}"); continue
        a, p, ap = num(s[0][2]), num(s[0][3]), num(s[0][4])
        if a + p != ap or ap != o[0]:
            err.append(f"{k}: ORIG {o[0]} ≠ SPLIT A{a}+P{p}={ap}")
        def tot(tag, n=3, cond=lambda r: True):
            return sum(num(r[n]) for r in by(tag) if r[1] == k and cond(r))
        for tag, want in (("AM", a), ("PM", p), ("AX", a), ("PX", p), ("PE", p)):
            if tot(tag) != want:
                err.append(f"{k}: {tag} 합 {tot(tag)} ≠ {want}")
        if tot("PX", cond=lambda r: r[2] == "BOTH") != 0:
            err.append(f"{k}: PX BOTH ≠ 0 (②·④ 동시 — 정의상 불가)")
        for br, want in (("A", a), ("P", p)):
            g = tot("AGE", n=4, cond=lambda r, br=br: r[2] == br)
            if g != want:
                err.append(f"{k}: AGE {br} 합 {g} ≠ {want}")
        u = [r for r in by("UQ") if r[1] == k]
        if len(u) != 1:
            err.append(f"{k}: UQ 행 수 {len(u)}")
        else:
            ua, ud, un, up, ub, uu, ui = (num(x) for x in u[0][2:9])
            if ua != a or up != p:
                err.append(f"{k}: UQ a/p {ua}/{up} ≠ SPLIT {a}/{p}")
            if uu != ud + up - ub or ui != uu + un or ud + un > ua:
                err.append(f"{k}: UQ 고유 수 계산 불일치")
    if err:
        print("SHAPE_MISMATCH"); [print(" -", e) for e in err]; return 7
    print("SHAPE_OK"); return 0

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", newline=chr(10))
    sys.exit(main(sys.argv[1], "--no-meta" in sys.argv[2:]))
