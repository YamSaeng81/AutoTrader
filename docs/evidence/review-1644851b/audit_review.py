"""Local static review only: committed evidence, shell syntax and isolated branches.

Does not invoke submitted runners, Docker, SSH, Gradle, DB, or their rehearsals.
The Bash snippets use shell functions exclusively for external commands. Run the
working-doc comparison before editing management docs, or in a clean source copy.
"""
import ast
import base64
import hashlib
import io
import json
import pathlib
import re
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SRC = '1644851bb90e21626eeee8820a030bf767728c76'
EV = 'docs/evidence/v171d4-20261116/'
BASH = 'C:/Program Files/Git/bin/bash.exe'
H = lambda b: hashlib.sha256(b).hexdigest()


def git(*args):
    return subprocess.run(['git', '-C', str(ROOT), *args], capture_output=True, check=True).stdout


def blob(p):
    return git('show', SRC + ':' + p)


def sums(folder):
    rows = []
    for line in blob(folder + '/SHA256SUMS').decode('utf-8').splitlines():
        h, p = line.split(None, 1)
        p = p.lstrip('*').strip()
        b = blob(folder + '/' + p)
        rows.append({'path': p, 'blob_match': H(b) == h,
                     'working_match': H((ROOT / folder / p).read_bytes()) == h})
    assert all(r['blob_match'] and r['working_match'] for r in rows)
    return {'files': len(rows), 'mismatches': 0}


def text(p):
    return blob(EV + p).decode('utf-8')


def cut(s, a, b):
    assert s.count(a) == 1 and s.count(b) == 1, (a, b)
    return s[s.index(a):s.index(b)]


def model(name, body, expected_rc, expected_output):
    p = subprocess.run([BASH, '--noprofile', '--norc'], input=body.encode('utf-8'),
                       capture_output=True, cwd=ROOT, timeout=10)
    o = p.stdout.decode('utf-8', 'replace') + p.stderr.decode('utf-8', 'replace')
    assert p.returncode == expected_rc and expected_output in o, (name, p.returncode, o)
    return {'name': name, 'rc': p.returncode, 'output': o,
            'snippet_sha256': H(body.encode('utf-8'))}


res = {'source': SRC, 'scope': 'local static files, bash -n, isolated shell branches only'}
res['evidence'] = {p: sums('docs/evidence/' + p) for p in
                   ['v171d4-20261116', 'review-b6afaddd']}
docs = ['START_HERE.md', 'LIMITED_LIVE_TEST_PLAN.md', 'CURRENT_HANDOFF.md',
        'LIVE_READINESS.md', 'NOVEMBER_LIVE_UPGRADE.md', 'PROGRESS.md', 'README.md']
res['management_source_match'] = {
    p: {'bytes_match': blob('docs/' + p) == (ROOT / 'docs' / p).read_bytes(),
        'lf_text_match': blob('docs/' + p).replace(b'\r\n', b'\n') == (ROOT / 'docs' / p).read_bytes().replace(b'\r\n', b'\n')}
    for p in docs}
assert all(r['lf_text_match'] for r in res['management_source_match'].values())

runner = text('runner/run.sh')
extractor = text('runner/extract.py')
def exp(s):
    return ast.literal_eval(re.search(r'EXP = (\{.*?^\})', s, re.M | re.S).group(1))
assert exp(runner) == exp(extractor)
blocks = {p.stem: p.read_bytes() for p in (ROOT / EV / 'd4/blocks-d4').glob('*.sh')}
for name, p in [('F', 'docs/LIMITED_LIVE_TEST_RUNBOOK.md'), ('G', 'docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md')]:
    chunks = re.findall(r'^```bash\n(.*?)^```\s*$', blob(p).decode('utf-8').replace('\r\n', '\n'), re.M | re.S)
    matches = [s.encode('utf-8') for s in chunks if H(s.encode('utf-8')) == exp(runner)[name]]
    assert len(matches) == 1
    blocks[name] = matches[0]
assert {k: H(v) for k, v in blocks.items()} == exp(runner)
syntax = {}
for k, b in blocks.items():
    p = subprocess.run([BASH, '--noprofile', '--norc', '-n'], input=b, capture_output=True)
    assert p.returncode == 0, (k, p.stderr)
    syntax[k] = 0
res['blocks'] = {k: {'sha256': H(v), 'bash_n': syntax[k]} for k, v in blocks.items()}
uv = blocks['U변수'].decode('utf-8')
embedded = cut(uv, "IFS= read -r -d '' ICPY <<'ICEOF' || true\n", '\nICEOF\n').split('\n', 1)[1] + '\n'
assert embedded.encode('utf-8') == blob(EV + 'tool/imgchain.py')
res['embedded_tool_sha256'] = H(embedded.encode('utf-8'))
assert res['embedded_tool_sha256'] in uv
assert 'JARM=b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8' in uv

# Validate the real small blobs without printing/base64 dumping their content.
res['real_image_blobs'] = {}
for short, manifest in [('52e12428', 'ca440b8403c4a65ce39c3a49f0e480c5ca5e9630380cdbcfe4daf6405fc6c628'),
                        ('837db515', 'fa5c9ba849c3ee1e48540004105ea18df6d8d25df1bf490a6eeac425413e3ad6')]:
    d = {k: base64.b64decode(v) for k, v in json.loads(text('standin/harness/fixreal/blobs-' + short + '.json')).items()}
    for n, b in d.items():
        if n.startswith('blobs/sha256/'):
            assert H(b) == n.split('/')[-1]
    m = json.loads(d['blobs/sha256/' + manifest])
    c = m['config']['digest']
    assert len(d['blobs/sha256/' + c[7:]]) == m['config']['size']
    cfg = json.loads(d['blobs/sha256/' + c[7:]])
    assert cfg['os'] == 'linux' and cfg['architecture'] == 'amd64'
    res['real_image_blobs'][short] = {'manifest': 'sha256:' + manifest, 'config': c, 'hashes_match': True}

r2_raw = blob(EV + 'standin/scenarios-v171-r2.txt')
r2 = r2_raw.decode('utf-8', 'replace')
res['submitted_r2'] = {'pass_lines': len(re.findall(r'^PASS ', r2, re.M)),
                       'fail_lines': len(re.findall(r'^FAIL ', r2, re.M)),
                       'summaries': re.findall(r'^== 결과:.*$', r2, re.M),
                       'utf8_replacement_characters': r2.count('\ufffd')}
assert res['submitted_r2']['pass_lines'] == 203 and res['submitted_r2']['fail_lines'] == 0
archive = blob(EV + 'standin/standin-runs-r1r2.tar.gz')
expected = {}
for line in text('standin/runs-r1r2.sha256').splitlines():
    h, p = line.split(None, 1)
    expected[p.lstrip('*').strip()] = h
with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as t:
    members = [m for m in t if m.isfile()]
    missing = []
    matched = 0
    for m in members:
        p = m.name.removeprefix('./')
        # Archive member paths are relative to the rehearsal root.
        options = [p, 'runs/' + p]
        key = next((k for k in options if k in expected), None)
        if key is None:
            missing.append(p)
        else:
            assert H(t.extractfile(m).read()) == expected[key], p
            matched += 1
    assert not missing, missing[:5]
res['archive'] = {'included_files': matched, 'source_hash_rows': len(expected),
                  'omitted_from_archive': len(expected) - matched, 'included_mismatches': 0}

# These are exact branch slices from submitted blocks; all commands are functions.
# Models establish control flow, not a claim about production occurrence.
u1 = blocks['U1'].decode('utf-8')
rb1 = blocks['U1되돌림'].decode('utf-8')
rb = blocks['U되돌림'].decode('utf-8')
u3 = blocks['U3'].decode('utf-8')
models = []
tag_prefix = cut(u1, 'if docker image inspect $REPO:b5cd1d0', 'echo 보존태그 >')
models.append(model('R1: both backup inspections fail with daemon error, tags still written',
    'set -euo pipefail\nREPO=autotrader-backend; IMGOLD=old-be; FETOLD=old-fe; M=unused\n'
    'stop(){ echo STOP; exit 1; }; same3(){ return 0; }; '
    'docker(){ if [ "$1" = image ]; then echo daemon-error >&2; return 42; fi; echo MUTATION "$@"; }\n'
    + tag_prefix, 0, 'MUTATION tag old-fe autotrader-frontend:b5cd1d0'))
fe_branch = cut(rb1, 'FL=0; same3 autotrader-frontend', 'echo "되돌릴 이미지 이름:')
models.append(model('R2a: build-start plus unrelated third frontend passes rollback plan',
    'set -euo pipefail\nM=unused; FS=빌드시작\n'
    'stop(){ echo STOP; exit 1; }; same3(){ case "$1" in *:b5cd1d0) return 0;; *) return 1;; esac; }; '
    'docker(){ echo third-unrelated-target; }; tee(){ cat; }\n'
    + fe_branch.replace('2>> "$M/u1rb-probe.txt"', '2>/dev/null')
    + '\necho REACHED_ROLLBACK_PLAN\n', 0, 'REACHED_ROLLBACK_PLAN'))
be_tail = cut(rb, 'docker tag "$IMGOLD" $REPO:latest', '[ "$($DC exec -T backend env')
models.append(model('R2b: explicit rollback mutates backend latest without checking current target',
    'set -euo pipefail\nIMGOLD=old-be; REPO=autotrader-backend; M=unused; DC=dc\n'
    'stop(){ echo STOP; exit 1; }; docker(){ echo MUTATION "$@"; }; same3(){ return 0; }; '
    'dc(){ echo MUTATION "$@"; }; sleep(){ :; }; isimg(){ return 0; }; okimg(){ echo old-be; }; ok3(){ echo old-be old-manifest old-config; }\n'
    + be_tail, 0, 'MUTATION tag old-be autotrader-backend:latest'))
news_prefix = cut(u3, '$DC ps backend', '[ "$IMGOK" = 0 ]')
models.append(model('R3: P0 log exists but news DB query fails before log read',
    'set -euo pipefail\nM=/dev/null; DC=dc; PSQL=psql_fake; NEWSCFG=unused; EXPF=unused; S=unused; T=deploy\n'
    'stop(){ echo "STOP $*"; exit 1; }; isimg(){ return 1; }; okimg(){ echo unused; }; ok3(){ echo unused; }; '
    'psql_fake(){ return 2; }; dc(){ if [ "$1" = logs ]; then echo LOG_READ >&2; echo "Connection is not available"; fi; }; '
    'holdobs(){ echo HOLDOBS >&2; return 1; }\n'
    + news_prefix.replace('> "$M/news-$T.txt"', '> /dev/null'), 1, '뉴스 설정 조회 실패'))
assert 'LOG_READ' not in models[-1]['output'] and 'HOLDOBS' not in models[-1]['output']
res['branch_models'] = models
(OUT / 'checks.json').write_text(json.dumps(res, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: res[k] for k in ['source', 'evidence', 'submitted_r2', 'archive', 'branch_models']}, ensure_ascii=False, indent=2))
