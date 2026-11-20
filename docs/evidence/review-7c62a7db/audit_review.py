"""Review committed evidence only; no application, PG, Docker, build or network."""
from pathlib import Path
import hashlib
import io
import json
import posixpath
import re
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHA = '7c62a7db73e236e1664745e1fb0037ba45516182'
FIX = '20104a69'
FINAL = 'd81c3f2f91c0239aacb0e1176dde9b898f4d7466'
DRAFT = 'docs/evidence/dec-deploy-draft-d81-20261119/'
EVIDENCE = 'docs/evidence/dec-final-abc-20261119/'
checks, observations = [], {}

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def blob(sha, path):
    return git('show', sha + ':' + path)

def text(sha, path):
    return blob(sha, path).decode('utf-8')

def check(name, passed, actual=None):
    checks.append({'check': name, 'pass': bool(passed), 'actual': actual})

def tree(sha, prefix):
    return git('-c', 'core.quotepath=false', 'ls-tree', '-r', '--name-only', sha, '--', prefix).decode().splitlines()

def batch(sha, paths):
    r = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
        input=''.join(sha + ':' + p + '\n' for p in paths).encode(), stdout=subprocess.PIPE, check=True)
    stream, out = io.BytesIO(r.stdout), {}
    for p in paths:
        h = stream.readline().decode().split()
        assert len(h) == 3 and h[1] == 'blob', (p, h)
        out[p] = stream.read(int(h[2]))
        assert stream.read(1) == b'\n'
    assert not stream.read()
    return out

def manifest_check(sha, prefix, expected):
    manifest = blob(sha, prefix + 'SHA256SUMS')
    rows = [re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', s) for s in manifest.decode('utf-8-sig').splitlines()]
    assert all(rows)
    paths = [posixpath.normpath(prefix + m[2]) for m in rows]
    saved = batch(sha, paths)
    bad = [p for p, m in zip(paths, rows) if hashlib.sha256(saved[p]).hexdigest() != m[1]]
    check(sha[:8] + ' ' + prefix + ' checksums', len(rows) == expected and not bad,
        {'listed': len(rows), 'mismatch': bad})
    return saved

at_fix = manifest_check(FIX, EVIDENCE, 355)
at_head = manifest_check(SHA, EVIDENCE, 355)
check('all 355 original files remain identical after correction', at_fix == at_head)
check('original manifest unchanged since 27cffb99', blob(FIX, EVIDENCE + 'SHA256SUMS') ==
      blob('27cffb99', EVIDENCE + 'SHA256SUMS'))
manifest_check(FIX, 'docs/evidence/review-e97ca31b/', 5)
manifest_check(SHA, 'docs/evidence/review-27cffb99/', 5)
manifest_check(SHA, DRAFT, 20)
delta = git('diff-tree', '--no-commit-id', '--name-status', '-r', FIX).decode().splitlines()
added = [l.split('\t', 1)[1] for l in delta if l.startswith('A\t')]
modified = [l.split('\t', 1)[1] for l in delta if l.startswith('M\t')]
check('correction limited to 302 originals and 12 additions', len(delta) == 314 and len(added) == 12 and len(modified) == 302 and
      all(l.split('\t', 1)[1].startswith(EVIDENCE) for l in delta))
old_bytes = batch('27cffb99', modified)
check('302 changes restore CRLF bytes without other edits', all(
      at_fix[p].replace(b'\r\n', b'\n') == old_bytes[p] for p in modified))
prior = json.loads(text(SHA, 'docs/evidence/review-27cffb99/observations.json'))
missing = {x['path'] for x in prior['missing_committed_logs']}
check('11 formerly missing logs are the 11 added logs', {p for p in added if p.endswith('.log')} == missing)
check('correction disables text conversion locally', blob(FIX, EVIDENCE + '.gitattributes').strip() == b'* -text')
names = git('diff-tree', '--no-commit-id', '--name-only', '-r', SHA).decode().splitlines()
evidence_paths = [p for p in names if p.startswith('docs/evidence/')]
saved = batch(SHA, evidence_paths)
check('26 new review/draft evidence files equal working copies', len(saved) == 26 and all(
      (ROOT / p).is_file() and (ROOT / p).read_bytes() == b for p, b in saved.items()), len(saved))
check('permission settings excluded from both submissions', not any('.claude/settings.local.json' in x for x in delta + names))
check('final candidate unchanged', git('rev-parse', 'integ/final-abc-60df3437').decode().strip() == FINAL)
product_paths = git('diff', '--name-only', '0e1d2a02', FINAL).decode().splitlines()
check('candidate changes only web-api Java main/test, no frontend/resources/build inputs', all(
      p.startswith(('web-api/src/main/java/', 'web-api/src/test/java/')) for p in product_paths), len(product_paths))

repo = 'web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java'
check('SQL query source is identical across C / ABC / final', blob('b408a223', repo) == blob('60df3437', repo) == blob(FINAL, repo))
pre = 'docs/evidence/dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql'
old_sql = text(SHA, pre).splitlines()
regen = text(SHA, DRAFT + 'checks/predeploy_read.regen-d81c3f2f.sql').splitlines()
changed_sql_lines = [(i + 1, a, b) for i, (a, b) in enumerate(zip(old_sql, regen)) if a != b]
check('regenerated SQL differs only in source-SHA comment', len(old_sql) == len(regen) and len(changed_sql_lines) == 1 and
    changed_sql_lines[0][0] == 2 and changed_sql_lines[0][1].replace('b408a223', 'd81c3f2f') == changed_sql_lines[0][2], changed_sql_lines)
check('predeploy SQL fixed hash', hashlib.sha256(blob(SHA, pre)).hexdigest() ==
      'ca6e7c8d6cc67ee967ac21b7847f7230cc859dc3cb9dc895da6f7694dfe26aa3')
gate_prefix = 'docs/evidence/dec-costbasis-followup-20261119/rollback-gate-v4-draft/'
gates = {}
for name, expected in [('gate.sql', 'b24239f82f09a6fcfcf36026a10614d3d7e7df3687fd720e0336fe1b84d77da0'),
    ('rollback_gate.sh', '0b391e58180d334f1f0d38ac88f7186ae03a6b233c945b54df19eaba8aa16906')]:
    b = blob(SHA, gate_prefix + name)
    gates[name] = b.decode()
    check(name + ' fixed gate source hash', hashlib.sha256(b).hexdigest() == expected and b == blob('e97ca31b', gate_prefix + name))
check('gate incompatibility check precedes ledger writes and pending checks',
      gates['rollback_gate.sh'].index('if [ "$G_exit_reason_unknown_to_old"') <
      gates['rollback_gate.sh'].index('if [ "$G_closing"') < gates['rollback_gate.sh'].index('mkdir "$OUT"'))

entity = 'web-api/src/main/java/com/cryptoautotrader/api/entity/'
def constants(sha):
    s = text(sha, entity + 'ExitReason.java')
    return set(re.findall(r'^\s*([A-Z][A-Z_]+)\s*[,;]', s, re.M))
now = constants(FINAL)
for old in ['0e1d2a02', 'b5cd1d0d']:
    previous = constants(old)
    check(old + ' enum difference is ENTRY_REJECTED only', len(previous) == 8 and now - previous == {'ENTRY_REJECTED'} and previous <= now,
        {'old': sorted(previous), 'new': sorted(now - previous)})
for n in ['PositionEntity.java', 'paper/PaperPositionEntity.java']:
    check(n + ' entity mapping unchanged', blob('0e1d2a02', entity + n) == blob(FINAL, entity + n))
mapping = git('grep', '-n', '@Enumerated', FINAL, '--', 'web-api/src/main/java').decode().splitlines()
check('two string-enum mappings in web-api entities', len(mapping) == 2 and all('PositionEntity.java' in l for l in mapping), mapping)
check('gate checks both position tables against old enum list', 'FROM position WHERE exit_reason IS NOT NULL' in gates['gate.sql'] and
      'FROM paper_trading.position WHERE exit_reason IS NOT NULL' in gates['gate.sql'] and
      gates['gate.sql'].count("'STOP_LOSS', 'TAKE_PROFIT', 'TRAILING_STOP', 'TIME_STOP', 'STRATEGY_SIGNAL', 'BLACK_SWAN', 'FORCED_STOP', 'UNKNOWN'") == 2)

rows = [x.split('\t') for x in text(SHA, DRAFT + 'gate4-standin/result.tsv').splitlines()]
expected = [('STOP_INCOMPATIBLE', 3)] * 4 + [('ROLLBACK_GATE_OK', 0), ('HOLD_PENDING', 2), ('HOLD_PENDING', 2),
    ('STOP_UNKNOWN', 3), ('STOP_UNKNOWN', 3), ('LEDGER_REFUSED', 4), ('LEDGER_REFUSED', 4)]
observations['gate_standin'] = []
for row, (verdict, rc) in zip(rows, expected):
    out = text(SHA, DRAFT + 'gate4-standin/out-' + row[1] + '.txt')
    ok = row[0] == 'PASS' and row[2] == f'want={verdict}/{rc}' and row[3] == f'got={verdict}/{rc}' and 'VERDICT ' + verdict in out
    check(row[1] + ' saved result and verdict', ok, row)
    observations['gate_standin'].append(row)
check('all eleven gate cases retained', len(rows) == len(expected) == 11)
harness = text(SHA, DRAFT + 'gate4-standin/run.sh')
check('standin tests call original gate script via fake psql', 'export PSQL="bash $W/fake_psql.sh"' in harness and
      'out_txt="$(bash "$GATE" "$out" 2>&1)"' in harness and 'want="$2" wrc="$3"' in harness)
check('standin PASS covers verdict and exit only; ledger is recorded', '"$v" = "$want"' in harness and
      '"$rc" = "$wrc"' in harness and 'ledger=%s' in harness and '"$led" =' not in harness)

archive_path = 'docs/evidence/v172run-20261116/server/maint-v172-nojar.tar.gz'
server_hashes = text(SHA, 'docs/evidence/v172run-20261116/server/maint-v172.sha256')
with tarfile.open(fileobj=io.BytesIO(blob(SHA, archive_path)), mode='r:gz') as archive:
    archived = {name: archive.extractfile('maint-v172/' + name).read() for name in [
        'image-new-chain.txt', 'image-fe-new-chain.txt', 'svc-fe.txt']}
for name, b in archived.items():
    h = hashlib.sha256(b).hexdigest()
    check('saved server record hash: ' + name, any(h in line and name in line for line in server_hashes.splitlines()))
be = archived['image-new-chain.txt'].decode().split()
fe = archived['image-fe-new-chain.txt'].decode().split()
check('saved backend chain identifies current deployed target', be[:3] == [
    'sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f',
    'sha256:b3a42cd2dd9947e6dffc5ccec71de077b3d2fccfc810d21796378be73b05aa0c',
    'sha256:4016cc389044ba95a88f9048f6b14de7118e276c75f9a524998e2b8baa50ac34'])
check('saved frontend chain identifies unchanged target', fe[:3] == [
    'sha256:9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e',
    'sha256:13343943ed4ab0db003bf40e77cb38c9d41e67287a7d51fcb6da818ad6569d93',
    'sha256:11d9a954a3054d3eff3c6d2d798fad55beefce3a166c9335259d8fa6367e4f89'])
check('saved frontend container Image is target ID', 'frontend ' + fe[0] + ' ' in archived['svc-fe.txt'].decode())
old_archive = blob(SHA, 'docs/evidence/v171d4-20261116/standin/standin-runs-r1r2.tar.gz')
with tarfile.open(fileobj=io.BytesIO(old_archive), mode='r:gz') as archive:
    old_manifest = archive.extractfile('s1-c01/home/maint-v171/jar-manifest-new.txt').read()
new_manifest = blob(SHA, EVIDENCE + 'jar-d81c3f2f/jar-manifest-d81c3f2f.lf.txt')
old_n, new_n = len(old_manifest.splitlines()), len(new_manifest.splitlines())
check('JARN known from accepted manifests: 895 to 898', old_n == 895 and new_n == 898 and
      hashlib.sha256(old_manifest).hexdigest() == 'b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8' and
      hashlib.sha256(new_manifest).hexdigest() == '33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509')

u2 = text(SHA, 'docs/evidence/v172d4-20261116/d4/blocks-d4/U2.sh')
u4 = text(SHA, 'docs/evidence/v172d4-20261116/d4/blocks-d4/U4.sh')
check('old U2 contains obsolete V94 requirement and boolean t expectation', 'v94 || stop' in u2 and '96:t:$V96SUM' in u2)
check('old U4 requires removed frontend stage artifacts', 'ok-UF2' in u4 and 'svc-fe.txt' in u4)
observations.update({'submission': SHA, 'correction': git('rev-parse', FIX).decode().strip(), 'candidate': FINAL,
    'historical_image_chain_source': archive_path, 'backend': be[:3], 'frontend': fe[:3],
    'JARN': '(끝) 895 898', 'missing_at_runtime': ['Current image/container and service identity must be revalidated before deployment.',
       'Existence/identity of new preservation tags is unknown; runtime tagkeep must distinguish absent/different/unknown.'],
    'limits': ['No app tests, PG, build, Docker, SSH, remote query or production command executed.',
       'Gate standin verifies shell verdict/exit using fake aggregates, not SQL aggregation or Docker behavior.',
       'Standin ledger status is recorded but is not part of its PASS expression; no complete ledger directory was submitted.',
       'Image chain values are preserved observations from Nov 16, not a current server read.',
       'The fixed original review of 27cffb99 keeps its three preservation failures; this review verifies the later correction.']})
(HERE / 'checks.json').write_text(json.dumps({'passed': sum(x['pass'] for x in checks),
    'failed': sum(not x['pass'] for x in checks), 'checks': checks}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(HERE / 'observations.json').write_text(json.dumps(observations, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'passed': sum(x['pass'] for x in checks), 'failed': sum(not x['pass'] for x in checks)}, ensure_ascii=False))
raise SystemExit(0 if all(x['pass'] for x in checks) else 1)
