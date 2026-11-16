"""Read-only source/evidence audit and isolated shell-branch checks. No server calls."""
import ast
import hashlib
import json
import pathlib
import re
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SRC = 'ce6df2fa2b1b4a0b089576bc700fb867a466686d'
BASE = 'docs/evidence/v172d4-20261116/'
BASH = 'C:/Program Files/Git/bin/bash.exe'
checks = {}

def sha(b):
    return hashlib.sha256(b).hexdigest()

def git(*args):
    return subprocess.run(['git', '-C', str(ROOT), *args], capture_output=True, check=True).stdout

def blob(path):
    return git('show', SRC + ':' + path)

def sums(b):
    out = {}
    for line in b.decode('utf-8-sig').splitlines():
        m = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        assert m, line
        assert m[2] not in out, m[2]
        out[m[2]] = m[1]
    return out

def check(name, ok, **detail):
    checks[name] = dict(ok=bool(ok), **detail)
    assert ok, (name, detail)

def manifest(folder, expected):
    paths = sums(blob(folder + '/SHA256SUMS'))
    bad_blob = []
    bad_work = []
    for p, h in paths.items():
        rel = folder + '/' + p
        if sha(blob(rel)) != h:
            bad_blob.append(p)
        if sha((ROOT / rel).read_bytes()) != h:
            bad_work.append(p)
    check(folder, len(paths) == expected and not bad_blob and not bad_work,
          count=len(paths), blob_mismatch=bad_blob, working_mismatch=bad_work)

manifest(BASE.rstrip('/'), 42)
manifest('docs/evidence/review-1644851b', 4)
manifest('docs/evidence/review-1644851b/postlog', 2)
check('main_origin', git('rev-parse', 'HEAD').strip() == git('rev-parse', 'origin/main').strip() == SRC.encode())

e = blob(BASE + 'runner/extract.py').decode('utf-8')
r = blob(BASE + 'runner/run.sh').decode('utf-8')
def exp(t):
    return ast.literal_eval(re.search(r'EXP = (\{.*?^\})', t, re.M | re.S)[1])
E = exp(e)
check('runner_fixed_hashes', len(E) == 12 and E == exp(r))
blocks = {}
for name in ('F', 'G'):
    path = 'docs/LIMITED_LIVE_TEST_RUNBOOK.md' if name == 'F' else 'docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md'
    text = blob(path).decode('utf-8').replace('\r\n', '\n')
    matches = [m[1].encode() for m in re.finditer(r'^```bash\n(.*?)^```\s*$', text, re.M | re.S) if sha(m[1].encode()) == E[name]]
    check('extract_' + name, len(matches) == 1)
    blocks[name] = matches[0]
S = sums(blob(BASE + 'd4/blocks-d4.sha256'))
for name, h in E.items():
    if name not in blocks:
        blocks[name] = blob(BASE + 'd4/blocks-d4/' + name + '.sh')
        check('hash_' + name, sha(blocks[name]) == h == S.get(name + '.sh'))
    p = subprocess.run([BASH, '--noprofile', '--norc', '-n'], input=blocks[name], capture_output=True)
    check('syntax_' + name, p.returncode == 0, stderr=p.stderr.decode('utf-8', 'replace'))

U = blocks['U변수'].decode('utf-8')
oldU = git('show', '1644851b:docs/evidence/v171d4-20261116/d4/blocks-d4/U변수.sh').decode('utf-8')
def embedded(t):
    return re.search(r"ICPY <<'ICEOF'[^\n]*\n(.*?)\nICEOF", t, re.S)[1].encode()
check('image_checker_preserved', embedded(U) == embedded(oldU), sha256=sha(embedded(U)))
for token in ('0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d',
              'b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8',
              '6385e1d5f64eb09a95791ba253a30d2b21341082'):
    check('constant_' + token[:8], token in U)
check('two_tags_before_write', blocks['U1'].index(b'FK=0; tagkeep') < blocks['U1'].index(b'docker tag'))
for name in ('U1되돌림', 'U되돌림'):
    t = blocks[name].decode('utf-8')
    first_change = min(t.index('git checkout --quiet'), t.index('docker tag'))
    check('both_names_before_mutation_' + name, t.index('BN=$(namecls') < first_change and t.index('$(namecls autotrader-frontend') < first_change)
for name in ('U3', 'U4', 'U뒤관측'):
    t = blocks[name].decode('utf-8')
    pos = t.index('holdobs ')
    check('p0_first_' + name, ('isimg backend' not in t or pos < t.index('isimg backend')) and pos < t.index('$PSQL ')
          and '"$HP0" = 3' in t and 'P0 검사 미완료' in t)

# Actual helper definitions with local stand-ins only. The test snippets cannot call Docker/network.
helpers = U[U.index('same3() {'):U.index('\nGS=')]
helper_prefix = '''
set -euo pipefail
DAPI=v1.56
Q=0; C=200; BODY='{"Id":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
curl() { printf '%s\\n%s' "$BODY" "$C"; return "$Q"; }
rchain() { [ "$RFAIL" = 0 ] || return 5; echo "$CHAIN manifest config ctr"; }
ok3() { case "$1" in old) echo 'old manifest config';; new) echo 'new manifest config';; esac; }
test() { builtin test "$@"; }
'''  # Real temporary records are unnecessary: override the bracket's -s check for the three synthetic record names.
helper_prefix += '''
[() { if [[ "$#" = 3 && "$1" = -s && ( "$2" = old || "$2" = new ) ]]; then return 0; elif [[ "$#" = 3 && "$1" = -s && "$2" = missing ]]; then return 1; else builtin [ "$@"; fi; }
RFAIL=0; CHAIN=old
capture() { local rc=0 result; result=$("$@") || rc=$?; printf '%s|%s\\n' "$rc" "$result"; }
'''
helper_body = '''
capture tagq backup
C=404; BODY='{"message":"No such image: backup"}'; capture tagq backup
BODY='{"message":"other"}'; capture tagq backup
C=500; capture tagq backup
C=200; BODY='bad'; capture tagq backup
Q=7; capture tagq backup; Q=0
C=200; BODY='{"Id":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
capture same3 backup old
CHAIN=third; capture same3 backup old
RFAIL=1; capture same3 backup old; RFAIL=0
CHAIN=old; capture tagkeep backup old
C=404; BODY='{"message":"No such image: backup"}'; capture tagkeep backup old
C=200; BODY='{"Id":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'; CHAIN=third; capture tagkeep backup old
RFAIL=1; capture tagkeep backup old; RFAIL=0
CHAIN=old; capture namecls latest old new
CHAIN=new; capture namecls latest old new
capture namecls latest old missing
CHAIN=third; capture namecls latest old new
RFAIL=1; capture namecls latest old new
'''
p = subprocess.run([BASH, '--noprofile', '--norc'], input=(helper_prefix + helpers + '\n' + helper_body).encode(), capture_output=True)
actual = p.stdout.decode().splitlines()
want = ['0|', '1|', '2|', '2|', '2|', '2|', '0|', '1|', '2|', '0|', '1|', '2|', '3|', '0|원판', '0|새판', '0|제3', '0|제3', '2|']
check('isolated_helpers', p.returncode == 0 and actual == want, count=len(actual), actual=actual, stderr=p.stderr.decode('utf-8', 'replace'))

for version, text in [('r1', blob(BASE + 'd4/blocks-d4-r1/U뒤관측.sh').decode('utf-8')),
                      ('final', blocks['U뒤관측'].decode('utf-8'))]:
    a = text.index('echo "== 기동 뒤 HOLD 질의')
    b = text.index('\nnewsobs ', a) if version == 'final' else text.index('\necho "== 기동 뒤 N15', a)
    tail = text[a:b]
    for rc in (0, 1, 2, 3):
        prefix = '(\nset -euo pipefail\nM=/unused\nTS=test\nstop(){ echo "$*"; exit 1; }\nhead(){ echo baseline; }\ntee(){ cat; }\nholdobs(){ echo MODEL_HOLD; return ' + str(rc) + '; }\n'
        script = prefix + tail + '\necho AFTER_HOLD\n)\n'
        p = subprocess.run([BASH, '--noprofile', '--norc'], input=script.encode(), capture_output=True)
        want_rc = 0 if version == 'r1' or rc in (0, 3) else 1
        check('second_log_' + version + '_' + str(rc), p.returncode == want_rc,
              returncode=p.returncode, output=p.stdout.decode('utf-8', 'replace'), stderr=p.stderr.decode('utf-8', 'replace'))

summary = {}
for f in ('standin/r1/scen-r1-a.txt', 'standin/r1/scen-r1-b.txt', 'standin/r1/scen-r1-c.txt',
          'standin/scen-r2-d1.txt', 'standin/scen-r2-d2.txt', 'runner/runner-selftest.txt'):
    t = blob(BASE + f).decode('utf-8', 'replace')
    counts = (len(re.findall(r'^PASS ', t, re.M)), len(re.findall(r'^FAIL ', t, re.M)))
    summary[f] = dict(pass_count=counts[0], fail_count=counts[1], replacement_characters=t.count('\ufffd'), footer=re.findall(r'^== .*결과.*$', t, re.M))
check('submitted_rehearsal_counts', [(x['pass_count'], x['fail_count']) for x in summary.values()] == [(56,4),(108,0),(83,0),(23,0),(77,0),(12,0)], summaries=summary)

all_sums = sums(blob(BASE + 'standin/runs-r1r2.sha256'))
bad = []
seen = set()
names = set()
forbidden = []
second_outputs = {}
archive = ROOT / BASE / 'standin/standin-runs-r1r2.tar.gz'
with tarfile.open(archive, 'r:gz') as z:
    for m in z:
        if not m.isfile():
            continue
        if m.name in names:
            bad.append('duplicate:' + m.name)
        names.add(m.name)
        key = m.name.removeprefix('./')
        if key.startswith('runs/'):
            key = key[5:]
        expected = all_sums.get(key)
        data = z.extractfile(m).read()
        if expected is None or sha(data) != expected:
            bad.append(m.name)
        if key.endswith('/forbidden.log'):
            forbidden.append((key, len(data)))
        if re.match(r'r2d2-p(11|12|13|14|15|16)/out/out-U', key):
            second_outputs[key] = data.decode('utf-8', 'replace')
        seen.add(key)
check('rehearsal_archive', not bad and len(seen) == 9212 and len(all_sums) == 11286,
      included=len(seen), full_manifest=len(all_sums), omitted=len(all_sums) - len(seen), mismatch=bad[:8])
omitted = sorted(set(all_sums) - seen)
check('archive_omissions_documented', all(p.endswith('.jar') or '/fix/' in p or '/scripts/prospective/' in p for p in omitted), count=len(omitted), examples=omitted[:4])
check('archive_forbidden_commands', len(forbidden) == 66 and not any(n for p, n in forbidden), cases=len(forbidden), nonempty=[p for p,n in forbidden if n])
for case, block, marker in [('p11','U뒤관측','(두 번째 로그)'), ('p12','U뒤관측','holdobs 종료 2'),
                           ('p13','U3','holdobs 종료 2'), ('p14','U4','holdobs 종료 2'),
                           ('p15','U3','연결 획득 시간 초과 줄이 있다'), ('p16','U뒤관측','두 번째 로그를 확보하지 못함')]:
    key = 'r2d2-' + case + '/out/out-' + block + '.txt'
    text = second_outputs.get(key, '')
    check('archive_second_log_' + case, marker in text and re.search(r'rc=1\b', text) is not None, file=key)

docs = ['START_HERE.md', 'LIMITED_LIVE_TEST_PLAN.md', 'CURRENT_HANDOFF.md', 'LIVE_READINESS.md',
        'NOVEMBER_LIVE_UPGRADE.md', 'PROGRESS.md', 'README.md']
bad_links = []
bad_characters = []
line_counts = {}
for name in docs:
    path = ROOT / 'docs' / name
    text = path.read_text(encoding='utf-8')
    line_counts[name] = len(text.splitlines())
    for target in re.findall(r'\]\(([^)]+)\)', text):
        target = target.strip('<>').split('#', 1)[0]
        if target and not re.match(r'\w+://', target) and not (path.parent / target).exists():
            bad_links.append([name, target])
    if re.search(r'[\u3001\u3002\u3040-\u30ff\u4e00-\u9fff]', text):
        bad_characters.append(name)
    check('latest_review_' + name, 'review-ce6df2fa/README.md' in text)
check('management_links', not bad_links, broken=bad_links)
check('management_characters', not bad_characters, files=bad_characters)
check('management_lengths', all(n <= 150 for n in line_counts.values()), lines=line_counts)
plan = (ROOT / 'docs/LIMITED_LIVE_TEST_PLAN.md').read_text(encoding='utf-8')
old_plan = blob('docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
def night(t):
    return t[t.index('### 야간 작업 원칙'):t.index('### 보완 뒤에도')]
check('night_policy_preserved', night(plan) == night(old_plan))

out = dict(source=SRC, scope='Local source, hashes, archive and isolated shell branches. No server or full rehearsal execution.', checks=checks)
(OUT / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(checks=len(checks), failed=sum(not x['ok'] for x in checks.values()), archive_files=len(seen), source=SRC), ensure_ascii=False))
