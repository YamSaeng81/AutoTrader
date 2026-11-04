from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'core-engine/build/review-k20-result-844fc63'
OUT.mkdir(parents=True, exist_ok=True)
REF = '844fc63f7dce4e2c964c33e37e99a3bf39886d24'
OLD = 'ea4490099f0fa6b614fe71102534b0ba84c0eb7f'
NEW = 'b1d7664db73222aec5d86f2e9137debef0ff4df3'
IMAGE = 'sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6'
E = ROOT / 'docs/evidence/k20-result-b1d7664'
M = E / 'maint-n11'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def git(ref, path):
    return subprocess.check_output(['git', 'show', f'{ref}:{path}'], cwd=ROOT)

def text(path):
    return path.read_text(encoding='utf-8')

def manifests(folder):
    result = []
    for line in text(folder / 'SHA256SUMS').splitlines():
        h, name = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line).groups()
        p = folder / name
        raw = p.read_bytes()
        blob = git(REF, p.relative_to(ROOT).as_posix())
        assert sha(raw) == sha(blob) == h, name
        result.append({'path': name, 'sha256': h, 'raw_and_git_match': True})
    return result

checks = {}
checks['result_manifest'] = manifests(E)
checks['previous_acceptance_manifest'] = manifests(ROOT / 'docs/evidence/k20-accept-b577f67')
remote = []
for line in text(E / 'server-sha256.txt').splitlines():
    h, name = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line).groups()
    p = M / Path(name).name
    assert sha(p.read_bytes()) == h, name
    remote.append({'remote_path': name, 'sha256': h, 'matches_preserved_copy': True})
assert len(remote) == 49
checks['server_manifest'] = remote

assert text(M / 'git-before.txt').splitlines() == [OLD, '(끝)']
assert text(M / 'git-deploy.txt').splitlines() == [NEW, '(끝)']
assert text(M / 'image-new.txt').strip() == IMAGE
assert text(M / 'image-before.txt').strip() == 'sha256:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9'
assert text(M / 'jar-diff.txt').splitlines() == text(ROOT / 'docs/evidence/k20-accept-b577f67/jar-baseline.txt').splitlines()
assert (M / 'svc-deploy.txt').read_bytes() == (M / 'svc-after30.txt').read_bytes()
before, after = text(M / 'svc-before.txt').splitlines(), text(M / 'svc-deploy.txt').splitlines()
assert before[1:] == after[1:] and after[0] == f'backend {IMAGE} 2026-11-04T04:54:17.850162707Z'
for group in ['q', 's']:
    assert len({(M / f'{group}-{stage}.txt').read_bytes() for stage in ['before', 'deploy', 'after30']}) == 1
assert text(M / 's-after30.txt').strip() == '66 | 63 | 3 | -'
errors = sorted(p.name for p in M.glob('errors-*.txt'))
assert len(errors) == 7 and all((M / n).stat().st_size == 0 for n in errors)
assert sorted(p.name for p in M.glob('ok-*')) == ['ok-K0', 'ok-K1', 'ok-K2', 'ok-K3-deploy', 'ok-K4']
assert not list(M.glob('news-check*'))
assert text(M / 'btc-after30.txt').strip() == 'true|11-04 13:00|11-04 13:00|20/20'
assert all('✅' in text(E / f'out-k{i}.txt') for i in range(5))
alerts = text(M / 'alerts-after30.txt')
assert '관측 1회' in alerts and '⚪ 0' in alerts and '🟢 21' in alerts
assert re.findall(r'^🔴 \d\d-\d\d .*?1종: (.+)$', alerts, re.M) == ['KRW-BLAST']
news = text(E / 'news-log-lines.txt')
assert len(re.findall(r'수집 미완료 — sourceId=cryptopanic 결과=FAILED 종류=HTTP_403', news)) == 2
assert ' ERROR ' not in news
success = re.findall(r'수집 완료 — sourceId=(\S+) 전체=(\d+)건 신규=(\d+)건', news)
assert len(success) == 7 and {s for s, _, _ in success} == {'blockmedia_rss','bloomberg_rss','coindesk_rss','tokenpost_rss','coingecko_trending','upbit_notice'}
cfg = lambda stage: [l.split('|') for l in text(M / f'news-{stage}.txt').splitlines()]
assert len(cfg('before')) == 7
assert [[x[:4] for x in cfg(stage)] for stage in ['deploy','after30','20261104-1443']] == [[x[:4] for x in cfg('before')]] * 3

sources = {}
excerpts = []
for name in ['RssNewsSource', 'UpbitNoticeSource', 'CryptoPanicSource', 'CoinGeckoTrendingSource']:
    p = f'web-api/src/main/java/com/cryptoautotrader/api/news/source/{name}.java'
    a, b = git(OLD, p).decode('utf-8'), git(NEW, p).decode('utf-8')
    calls = lambda t: re.findall(r'log\.error\([^;]+;', t)
    assert calls(a) == calls(b) and len(calls(b)) == 1
    sources[name] = {'old_sha256': sha(a.encode()), 'new_sha256': sha(b.encode()), 'error_call_unchanged': calls(b)}
    for i, line in enumerate(b.splitlines()):
        if 'log.' in line or 'response.body()' in line:
            excerpts.append(f'{NEW}:{p}:{i+1}: {line.strip()}')
    if name == 'CryptoPanicSource':
        assert 'log.warn("[CryptoPanicSource] HTTP {}: {}", response.statusCode(), response.body());' in a
        assert 'log.warn("[CryptoPanicSource] HTTP {}: {}", response.statusCode(), response.body());' in b

checks['deployment'] = {
    'old_head': OLD, 'new_head': NEW, 'new_image': IMAGE,
    'runtime_started_utc': '2026-11-04T04:54:17.850162707Z',
    'jar_expected_9_lines_and_877_879': True,
    'other_4_services_unchanged': True, 'no_backend_restart_through_k4': True,
    'q_and_s_three_stages_byte_equal': True, 'zero_length_error_files': errors,
    'five_ok_markers': True, 'no_news_check_file_in_preserved_copy': True,
    'btc_k4': text(M / 'btc-after30.txt').strip(),
    'alerts': 'one expected observation, KRW-BLAST only, green21/white0/red1',
    'ledger_monitor_cron_preservation': 'accepted from exact reviewed K4 block success; originals not fetched',
    'jar_originals_and_full_app_logs': 'server only; reviewed stored comparator/output, no independent fresh server scan',
}
checks['news'] = {
    'successes': [{'source': s, 'total': int(n), 'new': int(k)} for s,n,k in success],
    'seven_source_identity_type_enabled_interval_unchanged': True,
    'cryptopanic': {'failed': 2, 'failure_kind': 'HTTP_403', 'recovery': 'not confirmed'},
    'error_calls': sources,
    'correction': 'exceptions such as Connection reset still log ERROR; HTTP non200 was WARN before N11; N11 adds truthful aggregator outcome WARN',
    'raw_response_logging': 'preexisting CryptoPanic full body / Upbit first120 characters; no exposure of a specific secret established',
    'source_log_excerpts_sha256': sha(('\n'.join(excerpts)+'\n').encode()),
}
plan0 = git('b577f67', 'docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
plan1 = git(REF, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
blocks = lambda t: re.findall(r'^```bash\n(.*?)^```', t.replace('\r\n','\n'), re.M|re.S)
b0, b1 = blocks(plan0), blocks(plan1)
assert len(b0) == len(b1) == 76 and b1 == b0
quoted = re.findall(r'^  > ```bash\n(.*?)^  > ```', plan1, re.M|re.S)
checks['plan_blocks_before_pin_update'] = {'b577f67_column_zero':len(b0), '844fc63_column_zero':len(b1), 'existing_76_unchanged':True, 'quoted_historical_bash_openers':len(re.findall(r'^  > ```bash$',plan1,re.M)), 'note':'developer count77 includes one quoted historical fence; root compares76 top-level execution blocks'}
(OUT / 'source-log-excerpts.txt').write_text('\n'.join(excerpts)+'\n', encoding='utf-8')
(OUT / 'review.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'result_manifest':len(checks['result_manifest']), 'previous_acceptance_manifest':len(checks['previous_acceptance_manifest']), 'remote_copies':len(remote), 'deployment':checks['deployment'], 'news_success_rows':len(success), 'cryptopanic_failures':2, 'error_calls_unchanged':4, 'plan_blocks':checks['plan_blocks_before_pin_update']}, ensure_ascii=False, indent=2))
