from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
REF = '8e852f40deba39d6f51c9cb113e38efa05dafb9c'
OLD = 'b1d7664db73222aec5d86f2e9137debef0ff4df3'
RED = 'b7a8da15933899b5c296e556b8282f79e535f690'
NEW = '6995c52259e3de4c7c922a32f9fb2aa9ddd49f00'
OUT = Path(__file__).resolve().parent
BASE = 'web-api/src/main/java/com/cryptoautotrader/api/news/'
TEST = 'web-api/src/test/java/com/cryptoautotrader/api/news/NewsHttpErrorLogTest.java'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def blob(ref, path):
    return git('show', f'{ref}:{path}')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def changed(a, b):
    return git('diff', '--name-only', a, b).decode().splitlines()


def manifest(folder):
    records = []
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        expected, name = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line).groups()
        p = folder / name
        raw = p.read_bytes()
        committed = blob(REF, p.relative_to(ROOT).as_posix())
        assert sha(raw) == sha(committed) == expected, name
        records.append({'path': name, 'sha256': expected, 'raw_and_git_match': True})
    return records


checks = {'submission': REF, 'base': OLD, 'failure_first': RED, 'candidate': NEW}
checks['submitted_manifest'] = manifest(ROOT / 'docs/evidence/n12-http-log-body')
checks['previous_review_manifest'] = manifest(ROOT / 'docs/evidence/k20-result-review-844fc63')
assert len(checks['submitted_manifest']) == 11
assert len(checks['previous_review_manifest']) == 7
assert git('rev-parse', RED + '^').decode().strip() == OLD
assert git('rev-parse', NEW + '^').decode().strip() == RED
prod = [BASE + 'source/CryptoPanicSource.java', BASE + 'source/UpbitNoticeSource.java']
assert sorted(changed(OLD, RED)) == [TEST]
assert sorted(changed(RED, NEW)) == sorted(prod)
assert sorted(changed(OLD, NEW)) == sorted(prod + [TEST])
assert blob(RED, TEST) == blob(NEW, TEST)
checks['scope'] = {'failure_first_test_only': True, 'fix_two_sources_only': True,
                   'test_unchanged_between_failure_and_fix': True,
                   'test_sha256': sha(blob(NEW, TEST)), 'changed_paths': changed(OLD, NEW)}

replacements = {
    'CryptoPanicSource': (
        '                log.warn("[CryptoPanicSource] HTTP {}: {}", response.statusCode(), response.body());',
        '                // 응답 본문(HTML·줄바꿈·URL 등)은 옮기지 않는다 — 출처와 상태 코드만 한 줄 (N12)\n'
        '                log.warn("[CryptoPanicSource] HTTP {} — sourceId={}", response.statusCode(), config.getSourceId());'),
    'UpbitNoticeSource': (
        '                log.warn("[UpbitNoticeSource] HTTP {}: {}", response.statusCode(),\n'
        '                        response.body() != null ? response.body().substring(0, Math.min(120, response.body().length())) : "");',
        '                // 응답 본문(HTML·줄바꿈·URL 등)은 옮기지 않는다 — 출처와 상태 코드만 한 줄 (N12)\n'
        '                log.warn("[UpbitNoticeSource] HTTP {} — sourceId={}", response.statusCode(), config.getSourceId());'),
}
checks['source_changes'] = {}
for source, (before, after) in replacements.items():
    path = BASE + 'source/' + source + '.java'
    old = blob(OLD, path).decode('utf-8')
    new = blob(NEW, path).decode('utf-8')
    assert old.count(before) == 1
    assert old.replace(before, after, 1) == new
    assert 'return NewsFetchResult.httpStatus(response.statusCode());' in new
    checks['source_changes'][source] = {'old_sha256': sha(old.encode()), 'new_sha256': sha(new.encode()),
                                        'only_http_warn_and_comment_changed': True,
                                        'typed_failure_and_other_code_unchanged': True}

checks['unchanged_paths'] = []
for path in [BASE + 'source/RssNewsSource.java', BASE + 'source/CoinGeckoTrendingSource.java',
             BASE + 'NewsAggregatorService.java', BASE + 'NewsFetchResult.java', BASE + 'NewsSource.java',
             'scripts/ops/daily_check.sh', 'scripts/ops/daily_checks.sql']:
    assert blob(OLD, path) == blob(NEW, path), path
    checks['unchanged_paths'].append({'path': path, 'sha256': sha(blob(NEW, path))})

submitted_diff = ROOT / 'docs/evidence/n12-http-log-body/diff-b1d7664-6995c52.txt'
assert submitted_diff.read_bytes() == git('diff', OLD, NEW)
checks['preserved_diff_equals_git_diff'] = True

checks['preserved_xml'] = []
folder = ROOT / 'docs/evidence/n12-http-log-body'
for stage in ['before', 'after']:
    for p in sorted((folder / stage).glob('*.xml')):
        suite = ET.parse(p).getroot()
        n, failures, errors, skipped = [int(suite.get(k, '0')) for k in ['tests', 'failures', 'errors', 'skipped']]
        assert len(suite.findall('testcase')) == n
        assert errors == skipped == 0
        if stage == 'before':
            assert n == 8 and failures == 4
            failed_names = [t.get('name') for t in suite.findall('testcase') if t.find('failure') is not None]
            assert {s.split(' HTTP ')[0] for s in failed_names} == {'CRYPTOPANIC', 'UPBIT_NOTICE'}
            assert len(failed_names) == 4
        else:
            expected = {'NewsFetchGapReproTest': 3, 'NewsFetchResultContractTest': 36,
                        'NewsHttpErrorLogTest': 8, 'NewsResponseShapeTest': 27}
            assert n == expected[suite.get('name').split('.')[-1]] and failures == 0
        checks['preserved_xml'].append({'path': p.relative_to(ROOT).as_posix(), 'tests': n,
                                        'failures': failures, 'errors': errors, 'skipped': skipped})
assert len(checks['preserved_xml']) == 5
checks['limits'] = ['XML is preserved developer execution evidence, not a new root test execution.',
                    'Root did not run application tests, build jars, access SSH/production DB or HTTP.',
                    'Production remains b1d7664; this review does not certify deployment or resolve HTTP403.',
                    'Fake response/API values demonstrate logging behavior, not an actual secret leak.']
(OUT / 'review.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'submitted_files': len(checks['submitted_manifest']),
                  'previous_files': len(checks['previous_review_manifest']),
                  'scope': checks['scope']['changed_paths'],
                  'xml_before': '8 tests / 4 failures', 'xml_after': '8+3+36+27 passed',
                  'exact_two_log_replacements': True}, ensure_ascii=False))
