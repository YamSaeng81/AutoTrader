import sys,glob,os,xml.etree.ElementTree as ET
d=sys.argv[1]; T=F=E=S=0; out=[]
for f in sorted(glob.glob(os.path.join(d,'xml','*.xml'))):
    r=ET.parse(f).getroot()
    t,fa,er,sk=(int(r.get(k)) for k in ('tests','failures','errors','skipped'))
    T+=t;F+=fa;E+=er;S+=sk
    out.append(f'{os.path.basename(f)[:-4].split(".")[-1]} tests="{t}" skipped="{sk}" failures="{fa}" errors="{er}"')
out.append(f'TOTAL tests={T} failures={F} errors={E} skipped={S}')
open(os.path.join(d,'summary.txt'),'w',encoding='utf-8',newline='\n').write('\n'.join(out)+'\n')
print('\n'.join(out))
