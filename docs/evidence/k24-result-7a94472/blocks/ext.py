import sys,hashlib,json
sys.path.insert(0,'.')
t=open('plan-a1e38e7.md',encoding='utf-8').read().split('\n')
bl=[];i=0
while i<len(t):
    if t[i].strip()=='```bash':
        j=i+1
        while t[j].strip()!='```': j+=1
        bl.append('\n'.join(t[i+1:j])+'\n'); i=j
    i+=1
names={12:'F',27:'G',117:'T변수',118:'T0',119:'T1',120:'T2',121:'T3',122:'T4',123:'T뒤관측',124:'T되돌림'}
out={}
for k,n in names.items():
    open(f'blocks/{n}.sh','w',encoding='utf-8',newline='\n').write(bl[k])
    out[n]={'index':k,'sha256':hashlib.sha256(bl[k].encode()).hexdigest()}
out['_source']='a1e38e7f12690ea172fd015b7e8c143ceac9b449:docs/LIMITED_LIVE_TEST_PLAN.md'
out['_nblocks']=len(bl)
json.dump(out,open('blocks/blocks.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
