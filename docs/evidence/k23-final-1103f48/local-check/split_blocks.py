# R 블록 문서(r-blocks.md)에서 ```bash 블록 8개를 머리 순서대로 꺼낸다. 사용: python split_blocks.py <r-blocks.md> <out-dir>
import hashlib, json, pathlib, sys
src = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8').split('\n')
out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
NAMES = [('**R 변수**', 'R변수'), ('**R0.', 'R0'), ('**R1.', 'R1'), ('**R2.', 'R2'), ('**R3.', 'R3'), ('**R4.', 'R4'),
         ('**R 뒤 관측', 'R뒤관측'), ('**R 되돌림', 'R되돌림')]
res, i, cur = [], 0, None
while i < len(src):
    line = src[i]
    for head, name in NAMES:
        if line.startswith(head):
            cur = name
    if line == '```bash':
        j = src.index('```', i + 1)
        body = '\n'.join(src[i + 1:j]) + '\n'
        assert cur and cur not in [r[0] for r in res], (cur, i)
        (out / (cur + '.sh')).write_text(body, encoding='utf-8', newline='\n')
        res.append([cur, hashlib.sha256(body.encode('utf-8')).hexdigest()])
        cur, i = None, j
    i += 1
assert [r[0] for r in res] == [n for _, n in NAMES], res
(out.parent / 'blocks.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8', newline='\n')
print(len(res), 'blocks')
