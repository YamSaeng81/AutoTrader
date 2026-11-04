from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SCRATCH = ROOT / 'core-engine/build/review-k21-ea15998'
SCRATCH.mkdir(parents=True, exist_ok=True)
REF = 'ea15998fe4aaf2f9046766a8b2d5987567cbbeab'
PREV = '8e852f40deba39d6f51c9cb113e38efa05dafb9c'
E = ROOT / 'docs/evidence/k21-prep-6995c52'
BASH = 'C:/Program Files/Git/bin/bash.exe'


def git(ref, path):
    return subprocess.check_output(['git', 'show', ref + ':' + path], cwd=ROOT)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def manifest(folder):
    result = []
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        h, name = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line).groups()
        p = folder / name
        assert sha(p.read_bytes()) == h
        assert p.read_bytes() == git(REF, p.relative_to(ROOT).as_posix())
        result.append({'path': name, 'sha256': h, 'raw_and_git_match': True})
    return result


def blocks(text):
    return re.findall(r'^```bash\r?\n(.*?)^```\s*$', text, re.M | re.S)


def comparator(text):
    marker = 'python3 - "$M/app-old.jar" "$M/app-new.jar" <<\'EOF\' | tee "$M/jar-diff.txt"\n'
    return text.split(marker, 1)[1].split('\nEOF', 1)[0] + '\n'


plan = git(REF, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
old = git(PREV, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
a, b = blocks(old), blocks(plan)
assert len(a) == 76 and len(b) == 84 and b[:76] == a
report = {'submission': REF, 'submitted_manifest': manifest(E),
          'previous_manifest': manifest(ROOT / 'docs/evidence/n12-review-8e852f4'),
          'old_76_blocks_unchanged': True}
report['block_syntax'] = []
for f in sorted((E / 'blocks').glob('*.sh')):
    text = f.read_text(encoding='utf-8')
    assert text in b
    temp = SCRATCH / f.name
    temp.write_text(text, encoding='utf-8')
    cp = subprocess.run([BASH, '--noprofile', '--norc', '-n', str(temp)], capture_output=True)
    assert cp.returncode == 0, (f.name, cp.stderr)
    report['block_syntax'].append({'path': f.name, 'sha256_lf': sha(text.encode()), 'exit': 0})

compare = comparator((E / 'blocks/L1.sh').read_text(encoding='utf-8'))
assert compare == comparator(plan[plan.index('**55.'):plan.index('**56.')])
(OUT / 'jar-compare.py').write_text(compare, encoding='utf-8')
jar_paths = [Path('D:/Claude Code/projects/cat-n11/web-api/build/libs/web-api-0.1.0.jar'),
             Path('D:/Claude Code/projects/cat-n12/web-api/build/libs/web-api-0.1.0.jar')]
expected = ['6d9b61265f5add6237b12a2c81e934297749c087ca474c94e318bdeeba49f6c2',
            'a8bdf107a4ea04f8b9168969cccb6653fadac0502345c3b9560b95c392817509']
report['jars'] = []
for p, h in zip(jar_paths, expected):
    assert sha(p.read_bytes()) == h
    report['jars'].append({'path': str(p), 'sha256': h, 'bytes': p.stat().st_size})
cp = subprocess.run([sys.executable, '-X', 'utf8', str(OUT / 'jar-compare.py'),
                     *[str(p) for p in jar_paths]], capture_output=True, text=True, encoding='utf-8')
assert cp.returncode == 0
assert cp.stdout == (E / 'jar-diff-l1.txt').read_text(encoding='utf-8')
(OUT / 'jar-diff.txt').write_text(cp.stdout, encoding='utf-8')
report['full_comparator_identical_to_55'] = True
report['jar_diff_matches_two_classes_879_879'] = True

var = (E / 'blocks/Lvar.sh').read_text(encoding='utf-8')
helper = var.split('httpobs() { python3 - "$@" <<\'PYEOF\'\n', 1)[1].split('\nPYEOF', 1)[0] + '\n'
(OUT / 'httpobs.py').write_text(helper, encoding='utf-8')


def run_helper(path=None):
    return subprocess.run([sys.executable, '-X', 'utf8', str(OUT / 'httpobs.py')]
                          + ([str(path)] if path else []), capture_output=True, text=True, encoding='utf-8')


report['submitted_cases'] = []
expected = [1, 0, 0, 0, 0, 3, 1, 0, 1, 1, 3, 3, 0, 0, 1]
for f, x in zip(sorted((E / 'cases').glob('*.txt')), expected):
    cp = run_helper(f)
    assert cp.returncode == x
    assert not any(s in cp.stdout for s in ['DOCTYPE', '<html', 'Just a moment', 'FAKE-SECRET'])
    report['submitted_cases'].append({'case': f.name, 'expected_exit': x, 'actual_exit': cp.returncode})
cp = run_helper()
assert cp.returncode == 3
report['submitted_cases'].append({'case': 'no_arguments', 'expected_exit': 3, 'actual_exit': 3})

prefix = 'backend-1  | '
warn = prefix + '05:12:35.872 [scheduler-7] WARN c.c.a.news.source.CryptoPanicSource - [CryptoPanicSource] HTTP 403 — sourceId=cryptopanic\n'
result = prefix + '05:12:35.875 [scheduler-7] WARN c.c.api.news.NewsAggregatorService - [NewsAggregator] 수집 미완료 — sourceId=cryptopanic 결과=FAILED 종류=HTTP_403 판정=2026-11-04T05:12:35.874216063Z 받은=0건 신규=0건\n'
cases = {
    'cross-thread': (warn + result.replace('[scheduler-7]', '[http-nio-8080-exec-2]'), 3),
    'two-warns-one-result': (warn.replace('35.872', '35.870') + warn + result, 3),
    'untrusted-source-id': ((warn + result).replace('sourceId=cryptopanic', 'sourceId=<FAKE-BODY-SECRET-K21>'), None),
    'same-thread-control': (warn + result, 0),
}
(OUT / 'cases').mkdir(exist_ok=True)
(OUT / 'results').mkdir(exist_ok=True)
report['additional_cases'] = []
for name, (text, expected_exit) in cases.items():
    p = OUT / 'cases' / (name + '.txt')
    p.write_text(text, encoding='utf-8')
    cp = run_helper(p)
    (OUT / 'results' / (name + '.stdout')).write_text(cp.stdout, encoding='utf-8')
    (OUT / 'results' / (name + '.stderr')).write_text(cp.stderr, encoding='utf-8')
    marker = 'FAKE-BODY-SECRET-K21' in cp.stdout
    reproduced = marker if expected_exit is None else cp.returncode != expected_exit
    report['additional_cases'].append({'case': name, 'expected_exit': expected_exit,
                                        'actual_exit': cp.returncode,
                                        'fake_body_marker_in_stdout': marker,
                                        'finding_reproduced': reproduced})
assert [c['finding_reproduced'] for c in report['additional_cases']] == [True, True, True, False]
report['ruling'] = 'Hold #21 execution; keep app candidate accepted; fix only httpobs result correlation and unsafe raw sourceId output.'
report['limits'] = ['These are crafted local log inputs, not production incidents or actual secret exposure.',
                    'Root ran only pure local log parsing, preserved-jar content comparison and shell syntax checks.',
                    'Root did not source/execute L shell steps, access SSH/DB/HTTP or build jars/run app tests.',
                    'Five-line pairing window can conservatively yield unknown on interleaving; do not label that success.']
(OUT / 'review.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifest': [len(report['submitted_manifest']), len(report['previous_manifest'])],
                  'blocks': len(report['block_syntax']), 'existing_cases': len(report['submitted_cases']),
                  'additional': report['additional_cases']}, ensure_ascii=False))
