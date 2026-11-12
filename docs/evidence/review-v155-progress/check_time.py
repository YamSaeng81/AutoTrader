from pathlib import Path
import json,hashlib
O=Path(__file__).resolve().parent;B=Path('D:/tmp/v153-runs/v155/int155b-full')
b=(B/'time.txt').read_bytes(); prefix=b[:b.rfind(b'hashed ')]
expected=next(l.split()[0] for l in (B/'hashes.sha256').read_text().splitlines() if l.endswith('./time.txt'))
d={'expected':expected,'current':hashlib.sha256(b).hexdigest(),'prefix_before_hashed_line':hashlib.sha256(prefix).hexdigest(),'prefix_matches':hashlib.sha256(prefix).hexdigest()==expected,'appended_line':b[len(prefix):].decode('utf8')}
(O/'time-check.json').write_text(json.dumps(d,indent=2)+'\n',encoding='utf8');print(json.dumps(d,indent=2))
