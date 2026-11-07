import glob,sys,xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')
for f in glob.glob(sys.argv[1]+'/*.xml'):
    for tc in ET.parse(f).getroot().iter('testcase'):
        fl=tc.find('failure'); er=tc.find('error'); x=fl if fl is not None else er
        msg='' if x is None else (x.get('message') or '').replace('\n',' ')[:170]
        print(('FAIL ' if x is not None else 'PASS ')+tc.get('name')[:45],'|',msg)
