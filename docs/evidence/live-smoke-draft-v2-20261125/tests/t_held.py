# H01~H04: 정지 직전 보유 표시. r3 fixtures-readback.txt(8행, PG 재실행 없음)에 v2·원래 조건을 적용한다.
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
rb, smoke_v2, smoke_orig = sys.argv[1:4]
rows = []
for l in open(rb, encoding='utf-8'):
    c = [x.strip() for x in l.split('|')]
    if len(c) >= 3 and c[0].isdigit():
        rows.append((int(c[0]), c[1] or None, float(c[2])))
new = {s: int(st in ('OPEN', 'CLOSING')) for s, st, z in rows}
old = {s: int(st in ('OPEN', 'CLOSING') or z != 0) for s, st, z in rows}
odd = {s: int(st is None or st not in ('OPEN', 'CLOSING', 'CLOSED')) for s, st, z in rows}
E1 = {1: 0, 2: 1, 3: 1, 4: 1, 5: 0, 6: 0, 7: 0, 8: 0}; E2 = {k: 1 for k in range(1, 9)}; E3 = {1: 0, 2: 0, 3: 0, 4: 0, 5: 0, 6: 0, 7: 1, 8: 1}
v2 = open(smoke_v2, encoding='utf-8').read(); og = open(smoke_orig, encoding='utf-8').read()
held_v2 = re.search(r'^HELD_SQL=.*$', v2, re.M).group(0); held_og = [l for l in og.splitlines() if 'held-before-stop' in l][0]
res = [('H01', new == E1, 'v2 %s' % new), ('H02', old == E2, '원래 %s' % old), ('H03', odd == E3, '알 수 없음/NULL %s' % odd),
       ('H04', "status IN ('OPEN','CLOSING')\"" in held_v2 and 'size' not in held_v2 and 'size<>0' in held_og, 'v2: %s | 원래: %s' % (held_v2, held_og.strip()))]
for c, g, m in res: print(('ok ' if g else 'NG ') + c + ' ' + m)
n = sum(g for _, g, _ in res); print('t_held ok %d / %d' % (n, len(res))); sys.exit(0 if n == len(res) else 1)
