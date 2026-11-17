# ab_create.m15_window 를 제안 시각에 직접 대 본다(네트워크·DB 없음). 인자: 묶음 create 폴더
import sys; from datetime import datetime, timedelta, timezone
sys.path.insert(0, sys.argv[1]); import ab_create as ab
K = timezone(timedelta(hours=9))
print("WINDOW_FROM", ab.WINDOW_FROM, "WINDOW_TO", ab.WINDOW_TO)
for hh, mm, ss in [(14,5,59),(14,6,0),(14,9,0),(14,12,0),(14,12,1),(14,13,0)]:
    t = datetime(2026,11,18,hh,mm,ss,tzinfo=K); c, ok = ab.m15_window(t)
    print(t.strftime("%H:%M:%S"), "C", c.strftime("%H:%M"), "창안" if ok else "창밖", "T0", (c+timedelta(minutes=15)).strftime("%H:%M"))
