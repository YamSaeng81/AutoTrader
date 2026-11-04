import re, sys, collections
# 읽기 전용: 표준 입력의 앱 로그에서 Hikari 누수 경고·반환·획득 timeout 을 대조한다.
# 출력은 시각·스레드 종류·앱 프레임(클래스.메서드:줄)·건수만. 로그 본문·URL·질의문은 내지 않는다.
label = sys.argv[1] if len(sys.argv) > 1 else '?'
pre = re.compile(r'^\S+\s+\|\s?')
hdr = re.compile(r'^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]+)\] (\w+)\s')
trig = re.compile(r'leak detection triggered for (\S+) on thread (\S+), stack trace follows')
unl = re.compile(r'Previously reported leaked connection (\S+) on thread (\S+) was returned to the pool \(unleaked\)')
app = re.compile(r'^\s+at (com\.cryptoautotrader\.[\w.$]+)\(([\w.]+):(\d+)\)')
def sec(t):
    h, m, s = t.split(':'); return int(h) * 3600 + int(m) * 60 + float(s)
def tkind(t):
    return re.sub(r'-\d+$', '-N', t)
lines = [pre.sub('', l.rstrip('\n')) for l in sys.stdin]
leaks = []; cur = None; first_t = last_t = None
timeouts = []; other = collections.Counter()
for l in lines:
    h = hdr.match(l)
    if h:
        first_t = first_t or h.group(1); last_t = h.group(1)
        cur = None
        m = trig.search(l)
        if m:
            cur = {'t': h.group(1), 'conn': m.group(1), 'thr': m.group(2), 'frames': [], 'ret': None}
            leaks.append(cur); continue
        m = unl.search(l)
        if m:
            for k in reversed(leaks):
                if k['conn'] == m.group(1) and k['thr'] == m.group(2) and k['ret'] is None:
                    k['ret'] = h.group(1); break
            else:
                other['반환 짝 없음'] += 1
            continue
        if 'request timed out' in l or 'Connection is not available' in l:
            timeouts.append((h.group(1), h.group(2)))
        if 'Thread starvation' in l or 'clock leap' in l:
            other['housekeeper 지연/시계'] += 1
        continue
    if cur is not None:
        m = app.match(l)
        if m and len(cur['frames']) < 4:
            cur['frames'].append('%s:%s' % (m.group(1).replace('com.cryptoautotrader.', ''), m.group(3)))
print('== %s  로그 머리 시각 %s ~ %s (UTC, 날짜 없음)' % (label, first_t, last_t))
print('누수 경고 %d건 · 반환(unleaked) 짝 %d건 · 반환 미관측(구간 안) %d건 · 획득 timeout 줄 %d건 · %s' % (
    len(leaks), sum(1 for k in leaks if k['ret']), sum(1 for k in leaks if not k['ret']), len(timeouts), dict(other)))
groups = collections.OrderedDict()
for i, k in enumerate(leaks):
    held = None
    if k['ret']:
        d = sec(k['ret']) - sec(k['t'])
        if d < 0: d += 86400
        held = 60 + d  # 대여 시각 ≈ 경고 시각 − 임계값 60 초(운영 유효값은 따로 확인)
    key = (tkind(k['thr']), k['frames'][0] if k['frames'] else '(앱 프레임 없음)')
    groups.setdefault(key, []).append((k, held))
    print('%02d %s 스레드=%s 반환=%s 대여≈%s 초  %s' % (
        i + 1, k['t'], tkind(k['thr']), k['ret'] or '미관측',
        ('%.0f' % held) if held is not None else '-', ' ← '.join(k['frames']) or '(앱 프레임 없음)'))
print('-- 묶음(스레드 종류 · 첫 앱 프레임): 건수 · 대여 최소/최대 초')
for (tk, f), v in groups.items():
    hs = [h for _, h in v if h is not None]
    print('%s · %s : %d건 · %s' % (tk, f, len(v), ('%.0f/%.0f' % (min(hs), max(hs))) if hs else '반환 미관측'))
# 정확한 연결·스레드 식별자로 대조(식별자 자체는 출력하지 않는다)
ids = [(k['conn'], k['thr']) for k in leaks]
print('-- 정확 식별자 대조: 경고의 (연결,스레드) 고유 %d개 · 반환 줄은 같은 (연결,스레드)의 가장 최근 미반환 경고에만 짝 · 짝 없는 반환 줄 %d건' % (len(set(ids)), other.get('반환 짝 없음', 0)))
byth = collections.OrderedDict()
for k in leaks: byth.setdefault(k['thr'], []).append(k)
gaps = []; same = 0
for th, v in byth.items():
    for a, b in zip(v, v[1:]):
        if not a['ret']: continue
        g = (sec(b['t']) - 60) - sec(a['ret'])
        if g < -43200: g += 86400
        if 0 <= g < 60 or -5 < g < 0:
            gaps.append(g); same += (a['frames'][:1] == b['frames'][:1])
gaps.sort()
print('-- 같은 정확 스레드 안 연속 대여(앞 반환 → 다음 추정 대여 60초 미만): %d쌍 · 그중 같은 첫 앱 프레임 %d쌍 · 간격 초 최소/중앙/최대 %s · 스레드 %d개' % (
    len(gaps), same, ('%.1f/%.1f/%.1f' % (gaps[0], gaps[len(gaps) // 2], gaps[-1])) if gaps else '-', len(byth)))
ev = []; day = 0; prev = None
for k in leaks:
    a = sec(k['t']) - 60
    if prev is not None and a < prev - 3600: day += 1
    prev = a; a += day * 86400
    if k['ret']:
        b = sec(k['ret']) + day * 86400
        if b < a: b += 86400
        ev += [(a, 1), (b, -1)]
    else:
        ev += [(a, 1)]  # 반환 미관측: 로그 끝까지 잡힌 것으로 센다
c = mx = 0
for _, d in sorted(ev):
    c += d; mx = max(mx, c)
print('-- 60초 넘게 잡힌 연결의 최대 동시 수(추정 대여 시각 기준): %d' % mx)
for t, th in timeouts:
    print('timeout %s 스레드=%s' % (t, tkind(th)))
