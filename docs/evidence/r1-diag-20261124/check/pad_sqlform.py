"""model.py 출력을 r1-diag-1 SQL 출력 모양(8열, 빈 열 NULL 표기, META/REF/LEG/END)으로 감싼다 — 형태 검사 시험용."""
import sys
sys.stdout.reconfigure(encoding="utf-8")
N = chr(92) + "N"   # 백슬래시+N (psql null 표기)
print("\t".join(["META", "r1-diag-1", "2026-11-24T01:30:00.000000+00:00", "2026-11-24T01:30:00.100000+00:00",
                 "2026-10-25T01:30:00.000000+00:00", "96", "repeatable read", "Etc/UTC", "15s", "2s"]))
print("\t".join(["REF", "81", "2026-10-08 00:00:00", "2026-10-08T00:00:00+00:00"]))
for line in open(sys.argv[1], encoding="utf-8").read().splitlines():
    f = line.split("\t")
    if f[0] != "ORIG":
        f += [N] * (8 - len(f))
    print("\t".join(f))
print("\t".join(["LEG", "DYNAMIC", "A", "1", "1", "0", N, N]))
print("\t".join(["END", "2026-11-24T01:30:00.200000+00:00"]))
