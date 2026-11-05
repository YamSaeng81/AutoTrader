import glob, re, sys, os
d = sys.argv[1]
t = f = e = s = 0
rows = []
for p in sorted(glob.glob(os.path.join(d, '*.xml'))):
    h = open(p, encoding='utf-8').read(4000)
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', h)
    a = list(map(int, m.groups()))
    t += a[0]; s += a[1]; f += a[2]; e += a[3]
    name = os.path.basename(p)[len('TEST-'):-4].split('.')[-1]
    rows.append('%s tests="%d" skipped="%d" failures="%d" errors="%d"' % (name, *a))
print('\n'.join(rows))
print('합계 tests=%d skipped=%d failures=%d errors=%d' % (t, s, f, e))
