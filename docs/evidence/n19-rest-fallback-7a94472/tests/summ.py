import sys,glob,xml.etree.ElementTree as ET
for f in sorted(glob.glob(sys.argv[1]+'/*.xml')):
    r=ET.parse(f).getroot()
    print('#',f.split('TEST-')[-1],r.get('tests'),r.get('failures'),r.get('errors'),r.get('skipped'))
    for tc in r.iter('testcase'):
        st='FAIL' if tc.find('failure') is not None else ('ERROR' if tc.find('error') is not None else ('SKIP' if tc.find('skipped') is not None else 'PASS'))
        m=''
        fe=tc.find('failure') if tc.find('failure') is not None else tc.find('error')
        if fe is not None: m=(fe.get('message') or '').replace('\n',' ')[:150]
        print(st,tc.get('name')[:70],'|',m)
