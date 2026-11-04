import re, sys
# 읽기 전용: 획득 timeout 줄 주변의 로그 머리(시각·스레드·수준·로거)와 앱 프레임만 출력. 메시지는 Hikari 풀 상태 괄호만.
pre = re.compile(r'^\S+\s+\|\s?')
hdr = re.compile(r'^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]+)\] (\w+)\s+(\S+)')
app = re.compile(r'^\s+at (com\.cryptoautotrader\.[\w.$]+)\(([\w.]+):(\d+)\)')
pool = re.compile(r'\((total=\d+, active=\d+, idle=\d+, waiting=\d+)\)')
day = 0; prev = None; cur = None; n = 0
for l in sys.stdin:
    l = pre.sub('', l.rstrip('\n'))
    h = hdr.match(l)
    if h:
        t = h.group(1); s = int(t[:2]) * 3600 + int(t[3:5]) * 60 + int(t[6:8])
        if prev is not None and s < prev - 3600: day += 1
        prev = s
        if cur: print(cur); cur = None
        if 'request timed out' in l or 'Connection is not available' in l:
            p = pool.search(l)
            cur = '날짜+%d %s %s %s %s 풀=%s 프레임:' % (day, t, re.sub(r'-\d+$', '-N', h.group(2)), h.group(3), h.group(4), p.group(1) if p else '-'); n = 0
        continue
    if cur is not None:
        if pool.search(l) and '풀=-' in cur: cur = cur.replace('풀=-', '풀=' + pool.search(l).group(1))
        m = app.match(l)
        if m and n < 4: cur += ' ' + m.group(1).replace('com.cryptoautotrader.', '') + ':' + m.group(3); n += 1
if cur: print(cur)
print('마지막 날짜 지표 +%d' % day)
