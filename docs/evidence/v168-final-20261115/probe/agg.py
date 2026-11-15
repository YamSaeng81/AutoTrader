# 탐침 집계: runs/*/probe-times.tsv 의 사례별 시작 시각(UTC)을 KST 분으로 바꿔 클래스×분 결과를 센다.
import sys, glob, collections, datetime
sys.stdout.reconfigure(encoding="utf-8")
root = sys.argv[1]
tab = collections.defaultdict(lambda: collections.Counter())
fails = []
pats = sys.argv[2:] or ["*"]
runs = sorted({f for pt in pats for f in glob.glob(root + "/runs/" + pt + "/probe-times.tsv")})
n = 0
for f in runs:
    for line in open(f, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 5: continue
        cls, name, t0, t1, res = p[:5]
        t = datetime.datetime.fromisoformat(t0.replace("Z", "+00:00")) + datetime.timedelta(hours=9)
        key = "%02d" % t.minute
        ok = res == "OK"
        tab[cls][(key, ok)] += 1
        n += 1
        if not ok: fails.append(f"{t:%m-%d %H:%M:%S} {cls} :: {name} :: {res[:250]}  [{f.split('/')[-2]}]")
mins = sorted({k for c in tab.values() for (k, _) in c})
print(f"실행 {len(runs)}회 · 사례 {n}건 · 실패 {len(fails)}")
print("클래스\t" + "\t".join(mins))
for cls in sorted(tab):
    print(cls + "\t" + "\t".join(f"{tab[cls][(m, True)]}/{tab[cls][(m, False)]}" for m in mins))
print("--- 실패(성공/실패 표기: 성공수/실패수)")
for l in fails: print(l)
