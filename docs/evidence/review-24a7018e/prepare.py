from pathlib import Path
import subprocess, hashlib, json, zipfile, io, xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
MAIN = '24a7018e9f27a612bf2f8c6cd2d970d756edd4ab'
BASE = '0298628b6cd761cefb1bf6b29e1c87e2881b1d9b'
def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)
def digest(b):
    return hashlib.sha256(b).hexdigest()

report = {'main': MAIN, 'candidate': git('rev-parse', BASE).decode().strip(),
          'isolation': git('rev-parse', '03cfd9eb').decode().strip(), 'checks': {}, 'xml': {}}
for folder in ['overnight-v152-20261111', 'ab-create-v4-20261111', 'n03-1111-2200', 'review-c4a4d684']:
    prefix = 'docs/evidence/' + folder + '/'
    rows = []
    for line in git('show', MAIN + ':' + prefix + 'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not line.strip(): continue
        expected, path = line.split(maxsplit=1)
        path = path.lstrip('*')
        p = subprocess.run(['git', 'show', MAIN + ':' + prefix + path], cwd=ROOT, capture_output=True)
        local = ROOT / prefix / path
        blob = p.stdout if p.returncode == 0 else b''
        rows.append({'path': path, 'expected': expected, 'blob': digest(blob),
                     'local': digest(local.read_bytes()) if local.exists() else None,
                     'ok': p.returncode == 0 and digest(blob) == expected and local.exists() and digest(local.read_bytes()) == expected})
        if path.endswith('.xml') and p.returncode == 0:
            e = ET.fromstring(blob)
            report['xml'][folder + '/' + path] = {k: int(e.get(k, '0')) for k in ['tests', 'failures', 'errors', 'skipped']}
    report['checks'][folder] = rows
    print(folder, sum(x['ok'] for x in rows), '/', len(rows), flush=True)
assert all(x['ok'] for rows in report['checks'].values() for x in rows)
(OUT / 'audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
snap = OUT / 'snapshot'
assert not snap.exists()
snap.mkdir()
zipfile.ZipFile(io.BytesIO(git('archive', '--format=zip', BASE, 'core-engine', 'strategy-lib', 'exchange-adapter', 'web-api'))).extractall(snap)
for path in ['web-api/src/main/java/com/cryptoautotrader/api/config/AsyncConfig.java',
             'web-api/src/test/java/com/cryptoautotrader/api/service/MarketDataRejectIsolationTest.java']:
    dest = OUT / 'isolation' / path
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(git('show', report['isolation'] + ':' + path))
for prefix, name in [('docs/evidence/ab-create-v4-20261111', 'tools'),
                     ('docs/evidence/overnight-v152-20261111/pgchain-v4', 'pgchain')]:
    for path in git('ls-tree', '-r', '--name-only', MAIN, prefix).decode().splitlines():
        dest = OUT / name / Path(path).relative_to(prefix)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(git('show', MAIN + ':' + path))
print('immutable sources extracted', flush=True)
