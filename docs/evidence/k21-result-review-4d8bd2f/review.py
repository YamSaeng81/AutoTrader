from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '4d8bd2ff046fc9311eba8fe77583d6db18205e90'
EXEC = '95400d828bd94fa539ceb679700c28b6ca40ac8e'
OLD = 'b1d7664db73222aec5d86f2e9137debef0ff4df3'
NEW = '6995c52259e3de4c7c922a32f9fb2aa9ddd49f00'
IMAGE = 'sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07'
OLD_IMAGE = 'sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6'
STARTED = '2026-11-04T07:39:14.259114932Z'
E = ROOT / 'docs/evidence/k21-result-6995c52'
M = E / 'maint-n12'


def sha(b):
    return hashlib.sha256(b).hexdigest()


def git(ref, path):
    return subprocess.check_output(['git', 'show', f'{ref}:{path}'], cwd=ROOT)


def text(p):
    return p.read_text(encoding='utf-8')


def manifest(folder):
    rows = []
    for line in text(folder / 'SHA256SUMS').splitlines():
        h, n = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line).groups()
        p = folder / n
        assert sha(p.read_bytes()) == sha(git(REF, p.relative_to(ROOT).as_posix())) == h, n
        rows.append({'file': n, 'sha256': h, 'raw_and_git_match': True})
    return rows


checks = {'submitted_manifest': manifest(E),
          'prior_acceptance_manifest': manifest(ROOT / 'docs/evidence/k21-accept-95400d8')}
assert len(checks['submitted_manifest']) == 63
assert len(checks['prior_acceptance_manifest']) == 8
remote = []
for line in text(E / 'server-sha256.txt').splitlines():
    h, n = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line).groups()
    assert sha((M / Path(n).name).read_bytes()) == h, n
    remote.append({'file': n, 'sha256': h, 'matches_preserved_copy': True})
assert len(remote) == 53
checks['preserved_remote_manifest'] = remote

assert text(M / 'git-before.txt').splitlines() == [OLD, '(끝)']
assert text(M / 'git-deploy.txt').splitlines() == [NEW, '(끝)']
assert text(M / 'image-before.txt').strip() == OLD_IMAGE
assert text(M / 'image-new.txt').strip() == IMAGE
jar_diff = [f'변경 BOOT-INF/classes/com/cryptoautotrader/api/news/source/{c}.class'
            for c in ['CryptoPanicSource', 'UpbitNoticeSource']] + ['(끝) 879 879']
assert text(M / 'jar-diff.txt').splitlines() == jar_diff
jar_hashes = [l.split()[0] for l in text(M / 'jar-sha.txt').splitlines()]
assert jar_hashes == ['80993eac3447d8ee111939c06f8366f72285797f6fe4a2a222f8cf6b630d2cb6',
                      'fb05eb565d912512870fe9c32b125a19dc1576c7968b6199727f78ff42d6df88']
previous = ROOT / 'docs/evidence/k20-result-b1d7664/maint-n11/jar-sha.txt'
assert jar_hashes[0] == text(previous).splitlines()[1].split()[0]
svc0, svc1 = [text(M / f'svc-{s}.txt').splitlines() for s in ['before', 'deploy']]
assert len(svc1) == 5 and svc0[1:] == svc1[1:]
assert svc1[0] == f'backend {IMAGE} {STARTED}'
assert (M / 'svc-deploy.txt').read_bytes() == (M / 'svc-after30.txt').read_bytes()
for prefix in ['q', 's']:
    assert len({(M / f'{prefix}-{s}.txt').read_bytes() for s in ['before', 'deploy', 'after30']}) == 1
assert text(M / 's-after30.txt').strip() == '66 | 63 | 3 | -'
q = text(M / 'q-after30.txt')
assert re.search(r'flyway_version\s*\n-+\s*\n\s*94\s*\n', q)
assert re.search(r'LIVE\s*\|\s*203\s*\|\s*CREATED\s*\|\s*KRW-BTC\s*\|\s*20200\.00', q)
errors = sorted(p.name for p in M.glob('errors-*.txt'))
assert len(errors) == 7 and all((M / n).stat().st_size == 0 for n in errors)
assert sorted(p.name for p in M.glob('ok-*')) == ['ok-L0', 'ok-L1', 'ok-L2', 'ok-L3-deploy', 'ok-L4']
assert not list(M.glob('news-check*')) and not list(M.glob('http-check*'))
assert text(M / 'btc-before.txt').strip() == text(M / 'btc-deploy.txt').strip() == 'true|11-04 15:00|11-04 15:00|20/20'
assert text(M / 'btc-after30.txt').strip() == 'true|11-04 16:00|11-04 16:00|20/20'
assert text(M / 't-before.txt').strip() == '2026-11-04 16:37:13 KST'
assert text(M / 't-build.txt').strip() == '2026-11-04 16:37:29 KST'
assert text(M / 't-deploy.utc').strip() == '2026-11-04T07:39:03Z'
assert text(M / 't-after30.txt').strip() == '2026-11-04 17:10:04 KST'
assert int(text(M / 'obs-deploy.txt')) == 1793778386
assert int(text(M / 'obs-after30.txt')) == 1793779805
for i in range(5):
    assert f'✅ L{i}' in text(E / f'out-l{i}.txt')
assert 'Schema "public" is up to date. No migration necessary.' in text(E / 'out-l3.txt')
assert 'Started CryptoAutoTraderApplication in 15.354 seconds' in text(E / 'out-l3.txt')

alerts = text(M / 'alerts-after30.txt')
assert '관측 0회' in alerts and '⚪ 이 구간에 관측이 없다' in alerts
assert '적재를 **확인하지 않았다**' in alerts
assert re.findall(r'^🔴 \d\d-\d\d .*?1종: (.+)$', alerts, re.M) == []
baseline_alerts = text(M / 'alerts-before.txt')
assert re.findall(r'^🔴 \d\d-\d\d .*?1종: (.+)$', baseline_alerts, re.M) == ['KRW-BLAST'] * 24

news = text(E / 'news-log-lines.txt')
http = re.findall(r'\| (\d\d:\d\d:\d\d\.\d{3}) \[([^\]]+)\] WARN .* - \[CryptoPanicSource\] HTTP (\d{3}) — sourceId=(\S+)$', news, re.M)
assert http == [('07:57:36.454', 'scheduler-1', '403', 'cryptopanic'),
                ('08:27:37.876', 'scheduler-4', '403', 'cryptopanic')]
failed = re.findall(r'\| (\d\d:\d\d:\d\d\.\d{3}) \[([^\]]+)\] WARN .* - \[NewsAggregator\] 수집 미완료 — sourceId=(\S+) 결과=FAILED 종류=HTTP_(\d{3}) 판정=(\S+) 받은=0건 신규=0건$', news, re.M)
assert len(failed) == 2
assert [(x[1], x[2], x[3]) for x in failed] == [('scheduler-1', 'cryptopanic', '403'), ('scheduler-4', 'cryptopanic', '403')]
assert [x[4] for x in failed] == ['2026-11-04T07:57:36.455316443Z', '2026-11-04T08:27:37.876134005Z']
success = re.findall(r'수집 완료 — sourceId=(\S+) 전체=(\d+)건 신규=(\d+)건', news)
assert len(success) == 7 and {x[0] for x in success} == {'upbit_notice','blockmedia_rss','bloomberg_rss','coindesk_rss','tokenpost_rss','coingecko_trending'}
assert ' ERROR ' not in news
for stage, count in [('after30', 1), ('20261104-1743', 2)]:
    h = text(M / f'httpobs-{stage}.txt')
    assert f'HTTP 403 ×{count}' in h and f'고정 한 줄 {count}' in h
    assert '본문 형식/연속 줄 0' in h and f'0건 짝 {count}' in h and '짝 미확인 0' in h
    assert 'cryptopanic' not in h and 'scheduler-' not in h
assert '미관측' in text(M / 'httpobs-deploy.txt')
cfg = lambda s: [l.split('|') for l in text(M / f'news-{s}.txt').splitlines()]
assert len(cfg('before')) == 7
assert [[x[:4] for x in cfg(s)] for s in ['deploy','after30','20261104-1743']] == [[x[:4] for x in cfg('before')]] * 3

plan0 = git(EXEC, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode()
plan1 = git(REF, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode()
blocks = lambda t: re.findall(r'^```bash\n(.*?)^```', t.replace('\r\n', '\n'), re.M | re.S)
assert len(blocks(plan0)) == len(blocks(plan1)) == 92 and blocks(plan0) == blocks(plan1)
checks['deployment'] = {
    'source': EXEC, 'old_head': OLD, 'new_head': NEW, 'old_image': OLD_IMAGE, 'new_image': IMAGE,
    'started_utc': STARTED, 'jar_diff': jar_diff, 'jar_sha256': jar_hashes,
    'old_jar_matches_k20_new_jar': True, 'other_4_services_unchanged': True,
    'no_restart_through_l4': True, 'q_and_s_byte_equal_in_three_stages': True,
    'zero_length_filtered_error_files': errors, 'five_ok_markers': True,
    'btc_l4': text(M / 'btc-after30.txt').strip(),
    'monitor_cron_ledger_prior_tag_preservation': 'accepted reviewed L0/L4 checks; full originals not fetched',
    'known_404_exclusions': 'BLAST/BONK/STORJ, 3 lines reported, full app log not in submitted copy',
    'warnings': 'filtered ERROR zero is not absence of WARN; Hikari report separately investigated',
    'alert_observation': '0 in deployment window, no postdeployment loading assessment yet; defer to N03',
    'execution_markers_not_independent_remote_audit': True,
}
checks['news'] = {
    'unique_http_403_events': [{'time_utc': t, 'thread': th, 'status': int(s), 'source': sid} for t,th,s,sid in http],
    'unique_count': 2, 'l4_count': 1, 'l_after_cumulative_count': 2,
    'correction': '1 is included in cumulative 2; not 3 independent occurrences',
    'submitted_full_log_httpobs': 'all observed CryptoPanic HTTP lines fixed / paired / no continuation',
    'raw_news_excerpt_consistency': 'two warnings and two same-thread matching results; excerpt is not full app log',
    'success_rows': [{'source': s, 'total': int(n), 'new': int(k)} for s,n,k in success],
    'seven_config_id_type_enabled_interval_unchanged': True,
    'other_sources_non200': 'unobserved; do not induce failures or claim independently observed',
    'doctype_html_zero': 'developer full-log read report, not independently scanned full log',
    'cryptopanic_cause_and_connection_reset_recovery': 'unresolved',
}

excerpts = []
source_checks = {}
for name in ['RssNewsSource','UpbitNoticeSource','CryptoPanicSource','CoinGeckoTrendingSource']:
    path = f'web-api/src/main/java/com/cryptoautotrader/api/news/source/{name}.java'
    a, b = [git(ref, path).decode() for ref in [OLD, NEW]]
    errors_in = lambda s: re.findall(r'log\.error\([^;]+;', s)
    assert errors_in(a) == errors_in(b) and len(errors_in(b)) == 1
    source_checks[name] = {'catch_error_unchanged': True}
aggpath = 'web-api/src/main/java/com/cryptoautotrader/api/news/NewsAggregatorService.java'
assert git(OLD, aggpath) == git(NEW, aggpath)
agg = git(NEW, aggpath).decode()
assert re.search(r'@Transactional\s+public void collectAll\(\)', agg)
assert 'source.fetch(config)' in agg
yamlpath = 'web-api/src/main/resources/application.yml'
assert git(OLD, yamlpath) == git(NEW, yamlpath)
yaml = git(NEW, yamlpath).decode()
assert 'leak-detection-threshold: ${DB_LEAK_DETECTION_MS:60000}' in yaml
for path, s, ranges in [(aggpath, agg, [(37,56),(58,66),(80,90)]), (yamlpath,yaml,[(10,35)])]:
    lines = s.splitlines()
    for lo, hi in ranges:
        excerpts.extend(f'{NEW}:{path}:{i+1}: {lines[i]}' for i in range(lo-1, min(hi, len(lines))))
(OUT / 'source-diagnostic-excerpts.txt').write_text('\n'.join(excerpts) + '\n', encoding='utf-8')
checks['source'] = source_checks
checks['hikari_follow_up'] = {
    'reported_counts': {'before': 49, 'after': 26, 'headerless_lines': 956},
    'raw_stacks_or_return_logs_in_submission': False,
    'count_windows_differ': True, 'leak_or_cause_proven': False,
    'static_code': 'same NewsAggregator transaction includes fetch; same Hikari default threshold60000ms; producer not identified',
    'effective_production_threshold': 'not read',
    'next_task': 'N13 P1 bounded existing stack/return/pool timeout review, then local reproduction only if attributable; no settings or API changes',
}
checks['before_pin_update'] = {'plan_bash': 92, 'source_and_submission_blocks_identical': True}
checks['ruling'] = 'accept deployment and N12 completion within CryptoPanic observed / other non200 local-test-only scope; update actual F1 pins; N03 loading confirmation still pending'
(OUT / 'review.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifest':63, 'prior_manifest':8, 'remote_records':53, 'unique_403':2,
                  'alert_observations_after_deploy':0, 'deployment':'accepted', 'N12':'complete within stated scope',
                  'N13':'bounded diagnosis assigned, no cause conclusion'}, ensure_ascii=False))
