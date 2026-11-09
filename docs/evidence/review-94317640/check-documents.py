"""Check v1.136 additive documentation and protected executable blocks. Local only."""
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
SCRATCH=ROOT/'core-engine/build/review-94317640'
before=json.loads((SCRATCH/'before-docs.json').read_text(encoding='utf-8'))
expected=json.loads((SCRATCH/'expected-docs.json').read_text(encoding='utf-8'))
result={'documents':{},'protected':{}}
for name,encoded in before.items():
    old=base64.b64decode(encoded)
    new=(ROOT/'docs'/name).read_bytes()
    assert hashlib.sha256(new).hexdigest()==expected[name], name
    # All pre-existing lines must remain in order, including previous review records.
    if name=='PROGRESS.md':
        old=old.replace('> **마지막 갱신**: 2026-11-09 (344차'.encode(),'> **이전 갱신**: 2026-11-09 (344차'.encode(),1)
    remaining=iter(new.splitlines())
    for line in old.splitlines():
        assert any(candidate==line for candidate in remaining), (name,line[:100])
    result['documents'][name]={'sha256':hashlib.sha256(new).hexdigest(),'history_preserved':True}
    if name in ('LIMITED_LIVE_TEST_PLAN.md','OPS_DAILY_CHECK.md'):
        pattern=rb'^```bash[^\r\n]*\r?\n(.*?)^```'
        blocks_old=re.findall(pattern,old,re.M|re.S)
        blocks_new=re.findall(pattern,new,re.M|re.S)
        assert blocks_new==blocks_old, name
        result['protected'][name]={'bash_blocks':len(blocks_new),'all_byte_identical':True}
        if name=='LIMITED_LIVE_TEST_PLAN.md':
            assert len(blocks_new)==149
            result['protected']['F15']=[hashlib.sha256(b.replace(b'\r\n',b'\n')).hexdigest() for b in blocks_new[12:27]]
        else:
            assert len(blocks_new)==7
changed=subprocess.check_output(['git','diff','--name-only','-z'],cwd=ROOT).decode('utf-8').rstrip('\0').split('\0')
assert set(changed)=={f'docs/{name}' for name in before},changed
check=subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True)
assert check.returncode==0,check.stdout+check.stderr
result['diff_check']=0
result['tracked_changes']=changed
result['product_changes']=False
(OUT/'doc-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'documents':len(before),'protected_plan':149,'protected_ops':7,'protected_F':15,'diff_check':0},ensure_ascii=False))
