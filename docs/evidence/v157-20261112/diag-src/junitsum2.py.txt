import sys, os, glob, xml.etree.ElementTree as ET
root = sys.argv[1]
fails = []
for m in sorted(os.listdir(root)):
    t = f = e = s = 0
    for x in glob.glob(os.path.join(root, m, '*.xml')):
        r = ET.parse(x).getroot()
        t += int(r.get('tests')); f += int(r.get('failures')); e += int(r.get('errors')); s += int(r.get('skipped'))
        for tc in r.findall('testcase'):
            fl = tc.find('failure')
            if fl is None: fl = tc.find('error')
            if fl is not None:
                fails.append(f"FAIL {tc.get('classname')} # {tc.get('name')}\n    {(fl.get('message') or '')[:300]}".replace('\r', ''))
    print(f"{m} tests {t} fail {f} err {e} skip {s} pass {t-f-e-s}")
print('\n'.join(fails))
