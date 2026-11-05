src=open('f1-orig.sh',encoding='utf-8').read()
anchor='|| stop "설치 장부가 8bfe8a9 판(02a85850…)이 아님 — 보고"\n'
assert src.count(anchor)==1
add=open('add.txt',encoding='utf-8').read()
open('f1-prop.sh','w',encoding='utf-8',newline='\n').write(src.replace(anchor,anchor+add))
