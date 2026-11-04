from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '95400d828bd94fa539ceb679700c28b6ca40ac8e'
PREV = 'ea15998fe4aaf2f9046766a8b2d5987567cbbeab'
E = ROOT / 'docs/evidence/k21-fix-httpobs'
BASH = 'C:/Program Files/Git/bin/bash.exe'


def git(ref, path):
    return subprocess.check_output(['git', 'show', ref + ':' + path], cwd=ROOT)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def manifest(folder):
    result = []
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        h, name = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line).groups()
        data = (folder / name).read_bytes()
        assert sha(data) == h, name
        assert data == git(REF, (folder / name).relative_to(ROOT).as_posix()), name
        result.append({'path': name, 'sha256': h, 'raw_and_git_match': True})
    return result


def blocks(text):
    return re.findall(r'^```bash\r?\n(.*?)^```\s*$', text, re.M | re.S)


def helper(var):
    return var.split('httpobs() { python3 - "$@" <<\'PYEOF\'\n', 1)[1].split('\nPYEOF', 1)[0] + '\n'


plan = git(REF, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
old_plan = git(PREV, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
a, b = blocks(old_plan), blocks(plan)
assert len(a) == 84 and len(b) == 92 and a == b[:84]
report = {'submission': REF, 'submitted_manifest': manifest(E),
          'previous_review_manifest': manifest(ROOT / 'docs/evidence/k21-review-ea15998'),
          'old_84_blocks_unchanged': True, 'block_syntax': [], 'scope': []}
names = ['Lvar', 'L0', 'L1', 'L2', 'L3', 'L4', 'Lafter', 'Lback']
index = json.loads((E / 'block-hashes.json').read_text(encoding='utf-8'))
for n, name in enumerate(names):
    f = E / 'blocks' / (name + '.sh')
    raw = f.read_bytes()
    text = raw.decode('utf-8')
    assert text == b[84 + n]
    assert sha(raw) == index[name]['sha256']
    assert plan.splitlines()[index[name]['plan_fence_line'] - 1] == '```bash'
    cp = subprocess.run([BASH, '--noprofile', '--norc', '-n', str(f)], capture_output=True)
    assert cp.returncode == 0, (name, cp.stderr)
    report['block_syntax'].append({'name': name, 'sha256': sha(raw), 'exit': 0})
    old = a[76 + n]
    new = b[84 + n]
    if name == 'Lvar':
        old_prefix = old.split('# N12 출처 HTTP 비200 줄 관측.', 1)[0]
        new_prefix = new.split('# N12 출처 HTTP 비200 줄 관측(v1.95).', 1)[0]
        assert new_prefix.replace('LV=63', 'LV=61').replace('v1.95(§5-9 63)', 'v1.93(§5-9 61)') == old_prefix
    else:
        normalized = new.replace('= 63 ]', '= 61 ]').replace('v1.95(§5-9 63)', 'v1.93(§5-9 61)').replace('§5-9 63 L 변수', '§5-9 61 L 변수')
        assert normalized == old, name
    report['scope'].append({'name': name, 'only_helper_and_version_marker_changed': True})

f_before = blocks(old_plan.split('**F 변수**', 1)[1].split('### 5-9.', 1)[0])
f_after = blocks(plan.split('**F 변수**', 1)[1].split('### 5-9.', 1)[0])
assert len(f_before) == 15 and f_before == f_after
ops_before = blocks(git(PREV, 'docs/OPS_DAILY_CHECK.md').decode('utf-8'))
ops_after = blocks(git(REF, 'docs/OPS_DAILY_CHECK.md').decode('utf-8'))
assert ops_before == ops_after and len(ops_after) == 7
report['f_15_and_ops_7_unchanged'] = True

for name in ['cross-thread', 'two-warns-one-result', 'same-thread-control', 'untrusted-source-id']:
    assert (E / 'cases' / ('r-' + name + '.txt')).read_bytes() == (ROOT / 'docs/evidence/k21-review-ea15998/cases' / (name + '.txt')).read_bytes()
report['four_previous_inputs_byte_identical'] = True
for f in (ROOT / 'docs/evidence/k21-prep-6995c52/cases').glob('*.txt'):
    assert (E / 'cases' / f.name).read_bytes() == f.read_bytes()
report['fifteen_previous_inputs_byte_identical'] = True

program = helper(b[84])
(OUT / 'httpobs.py').write_text(program, encoding='utf-8')
expected = [1, 0, 0, 0, 0, 3, 1, 0, 1, 1, 3, 3, 0, 0, 1]
cases = [(p, rc) for p, rc in zip(sorted((E / 'cases').glob('c*.txt')), expected)]
cases += [(E / 'cases' / ('r-' + name + '.txt'), rc) for name, rc in
          [('cross-thread', 3), ('same-thread-control', 0), ('two-warns-one-result', 3), ('untrusted-source-id', 0)]]
cases += [(E / 'cases/x-coingecko-other-source.txt', 3), (None, 3)]
assert len(cases) == 21
report['cases'] = []
outputs = []
for p, rc in cases:
    cp = subprocess.run([sys.executable, '-I', '-X', 'utf8', str(OUT / 'httpobs.py')]
                        + ([str(p)] if p else []), capture_output=True, text=True, encoding='utf-8')
    assert cp.returncode == rc, (p, cp.returncode, cp.stdout, cp.stderr)
    assert cp.stderr == ''
    forbidden = ['DOCTYPE', '<html', 'FAKE', 'Just a moment', 'sourceId=', 'scheduler', 'http-nio']
    if p:
        text = p.read_text(encoding='utf-8')
        forbidden += re.findall(r'sourceId=([^\s:]+)', text)
        forbidden += re.findall(r'^.*?\d\d:\d\d:\d\d\.\d{3} \[([^\]]+)\]', text, re.M)
    assert not any(x and x in cp.stdout for x in forbidden), (p, cp.stdout)
    if p and p.name == 'r-two-warns-one-result.txt':
        assert '짝 1 · 짝 미확인 1' in cp.stdout
    name = p.name if p else 'no_arguments'
    outputs.append('== %s expected=%d actual=%d\n%s' % (name, rc, cp.returncode, cp.stdout))
    report['cases'].append({'name': name, 'expected_exit': rc, 'actual_exit': cp.returncode,
                            'raw_id_thread_body_in_output': False, 'stderr_empty': True})
(OUT / 'cases-results.txt').write_text('\n'.join(outputs), encoding='utf-8')

source = git('6995c52259e3de4c7c922a32f9fb2aa9ddd49f00',
             'web-api/src/main/java/com/cryptoautotrader/api/news/NewsAggregatorService.java').decode('utf-8')
assert 'NewsFetchResult result = source.fetch(config);' in source
assert 'log.warn("[NewsAggregator] 수집 미완료' in source
excerpt = source.split('        log.debug("[NewsAggregator] 수집 시작', 1)[1].split('        return result;', 1)[0]
(OUT / 'source-call-path.txt').write_text('6995c52 NewsAggregatorService.collectFromSource: synchronous fetch to result log.\n'
                                       + '        log.debug("[NewsAggregator] 수집 시작' + excerpt, encoding='utf-8')
report['same_thread_evidence'] = 'Synchronous source.fetch(config) followed by aggregator result log in collectFromSource; intervening unrelated/debug logs conservatively yield unknown.'
report['ruling'] = 'Accept helper correction; approve #21 execution from pinned 95400d8 section 63. App candidate and prior jar expectations remain accepted. Operational N12 completion awaits actual results.'
report['limits'] = ['Root executed only pure local log parsing and shell syntax checks; no L stages were sourced or executed.',
                    'No SSH/DB/HTTP access, app tests, jar rebuilds/comparison variants or full rehearsal were run.',
                    'Five following log headers and first same-thread record are a conservative pairing rule, not a request ID.',
                    'CoinGecko has no sourceId in its HTTP line; absent competing-source evidence is not independent configuration proof.',
                    'Interleaved/debug/incomplete observations remain white/3. Natural HTTP failure absent means unobserved, not validated.',
                    'CryptoPanic HTTP403 cause and Connection reset recovery are unresolved; catch ERROR classification is unchanged.']
(OUT / 'review.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifest': [len(report['submitted_manifest']), len(report['previous_review_manifest'])],
                  'syntax_blocks': len(report['block_syntax']), 'cases': len(report['cases']),
                  'old_blocks_unchanged': 84, 'f_blocks_unchanged': 15, 'ops_blocks_unchanged': 7}, ensure_ascii=False))
