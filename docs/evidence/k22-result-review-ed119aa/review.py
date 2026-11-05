from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = 'ed119aaa6089f363a091d2a7caba115dfc69cbf6'
EXEC = '782e7ac70e94ca2e14268f2f5580bd18bfb9879a'
OLD = '6995c52259e3de4c7c922a32f9fb2aa9ddd49f00'
NEW = 'fe01207f3f60e97e308925157f84a1bb2da04388'
IMAGE = 'sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7'
OLD_IMAGE = 'sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07'
CK = 'c669ccdc6e248ce8e55e9a7ac7405197d266fa18'
OLD_CK = 'd491fd1609b700d195ecc4d0ccea6be2987bf2fe'
SHELL_SHA = 'ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc'
SQL_SHA = '653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8'
CRON_SHA = 'f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b'
E = ROOT / 'docs/evidence/k22-result-fe01207'
M = E / 'maint-n13'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(ref, path):
    return subprocess.check_output(['git', 'show', f'{ref}:{path}'], cwd=ROOT)


def text(path):
    return path.read_text(encoding='utf-8')


def blocks(value):
    return re.findall(r'^```bash\n(.*?)^```', value.replace('\r\n', '\n'), re.M | re.S)


submitted = []
for row in text(E / 'SHA256SUMS').splitlines():
    digest, name = re.fullmatch(r'([a-f0-9]{64}) [ *](.+)', row).groups()
    path = E / name
    assert sha(path.read_bytes()) == sha(git(REF, path.relative_to(ROOT).as_posix())) == digest, name
    submitted.append({'file': name, 'sha256': digest})
assert len(submitted) == 71
remote = []
for row in text(E / 'server-sha256.txt').splitlines():
    digest, name = re.fullmatch(r'([a-f0-9]{64}) [ *](.+)', row).groups()
    assert sha((M / Path(name).name).read_bytes()) == digest, name
    remote.append({'file': name, 'sha256': digest})
assert len(remote) == 59

assert text(M / 'git-before.txt').splitlines() == [OLD, '(끝)']
assert text(M / 'git-deploy.txt').splitlines() == [NEW, '(끝)']
assert text(M / 'image-before.txt').strip() == OLD_IMAGE
assert text(M / 'image-new.txt').strip() == IMAGE
expected_diff = ['변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/StrategyLogRepository.class', '(끝) 879 879']
assert text(M / 'jar-diff.txt').splitlines() == expected_diff
jar_hashes = [row.split()[0] for row in text(M / 'jar-sha.txt').splitlines()]
assert jar_hashes == ['fb05eb565d912512870fe9c32b125a19dc1576c7968b6199727f78ff42d6df88',
                      '0578ceb3e9afdde5f33e62098dcf62e5425f615922d840ae345d59f8c70ceb4e']
previous = ROOT / 'docs/evidence/k21-result-6995c52/maint-n12/jar-sha.txt'
assert jar_hashes[0] == text(previous).splitlines()[1].split()[0]
svc0, svc1 = [text(M / f'svc-{stage}.txt').splitlines() for stage in ['before', 'deploy']]
assert len(svc1) == 5 and svc0[1:] == svc1[1:]
assert svc1[0] == f'backend {IMAGE} 2026-11-05T01:14:51.94517082Z'
assert (M / 'svc-deploy.txt').read_bytes() == (M / 'svc-after30.txt').read_bytes()
for prefix in ['q', 's']:
    assert len({(M / f'{prefix}-{stage}.txt').read_bytes() for stage in ['before', 'deploy', 'after30']}) == 1
q = text(M / 'q-after30.txt')
assert re.search(r'flyway_version\s*\n-+\s*\n\s*94\s*\n', q)
assert re.search(r'LIVE\s*\|\s*203\s*\|\s*CREATED\s*\|\s*KRW-BTC\s*\|\s*20200\.00', q)
assert text(M / 's-after30.txt').strip() == '66 | 63 | 3 | -'
assert sorted(p.name for p in M.glob('ok-*')) == ['ok-P0', 'ok-P1', 'ok-P2', 'ok-P3-deploy', 'ok-P4']
errors = sorted(p.name for p in M.glob('errors-*.txt'))
assert len(errors) == 7 and all((M / name).stat().st_size == 0 for name in errors)
for stage in ['before', 'deploy', 'after30']:
    assert text(M / f'btc-{stage}.txt').strip() == 'true|11-05 09:00|11-05 09:00|20/20'
assert text(M / 'timezone-before.txt').strip() == 'DB 조회 세션 TimeZone: UTC'
assert text(M / 't-deploy.utc').strip() == '2026-11-05T01:14:50Z'
assert not list(M.glob('news-check*')) and not list(M.glob('http-check*')) and not list(M.glob('hold-check*'))
for i in range(5):
    assert f'✅ P{i}' in text(E / f'out-p{i}.txt')
p3, p4, pn = [text(E / name) for name in ['out-p3.txt', 'out-p4.txt', 'out-pn.txt']]
assert 'Schema "public" is up to date. No migration necessary.' in p3
assert 'Started CryptoAutoTraderApplication in 14.967 seconds' in p3
assert 'DB 조회 세션 TimeZone: UTC' in text(E / 'out-p0.txt')
zero_hold = 'HOLD 기준선 질의 장기 대여 경고 0건(반환 0·반환 미관측 0) · 그 밖의 장기 대여 경고 0건 · 연결 획득 시간 초과 줄 0건'
for stage in ['deploy', 'after30', '20261105-1106']:
    h = text(M / f'holdobs-{stage}.txt')
    assert zero_hold in h
    assert '시작 Catchup: 시작 01:15:08.065 · 완료 01:15:40.443' in h
assert '103건(반환 103·반환 미관측 0)' in text(M / 'hold-before.txt')
alerts = text(M / 'alerts-after30.txt')
assert re.findall(r'^🔴 \d\d-\d\d .*?1종: (.+)$', alerts, re.M) == ['KRW-BLAST']
assert '관측 1회' in alerts and '🟢 21 / ⚪ 0 / 🔴 1' in alerts
assert re.findall(r'^🔴 \d\d-\d\d .*?1종: (.+)$', text(M / 'alerts-before.txt'), re.M) == ['KRW-BLAST'] * 24
for stage, count in [('after30', 1), ('20261105-1106', 2)]:
    h = text(M / f'httpobs-{stage}.txt')
    assert f'HTTP 403 ×{count}' in h and f'고정 한 줄 {count}' in h and f'0건 짝 {count}' in h
    assert '본문 형식/연속 줄 0' in h and '짝 미확인 0' in h
news = text(M / 'newsobs-20261105-1106.txt')
for source in ['blockmedia_rss', 'bloomberg_rss', 'coindesk_rss', 'tokenpost_rss', 'coingecko_trending']:
    assert re.search(rf'^{source} .*완료 1·미완료 0', news, re.M), source
assert re.search(r'^upbit_notice .*완료 2·미완료 0', news, re.M)
assert re.search(r'^cryptopanic .*완료 0·미완료 2.*HTTP_403', news, re.M)
cfg = lambda stage: [row.split('|')[:4] for row in text(M / f'news-{stage}.txt').splitlines()]
assert len(cfg('before')) == 7 and all(cfg(stage) == cfg('before') for stage in ['deploy', 'after30', '20261105-1106'])

report = text(E / 'n14/20261105-1000.txt')
report_hash = sha((E / 'n14/20261105-1000.txt').read_bytes())
assert report_hash == '981e95b38156580ddae9553df90fbb42307570b194871c152f2cfaef3592b46b'
assert '점검 스크립트 c669ccdc6e24' in report
assert '현재 컨테이너 생성 11-04 16:39:03(구간 시작 전·같음 — 재생성 공백 없음)' in report
assert '⚪' not in report and '🔴 미분류 ERROR 8건' in report
assert 'B1 true · 최신 완료 봉 11-05 09:00 · 필요 11-05 08:00(2분 유예) · B3 최근 20일 마감 봉 20/20' in report
assert text(E / 'n14/last_send').strip() == '2026-11-05 10:00:03 KST 보고 전송 성공 · 🔴 이상 1건'
install = text(E / 'n14/out-n14.txt')
old_marker_hash = sha((OLD_CK + '\n').encode())
assert old_marker_hash == '930ee34e1e59d5761c83c3de19894ccd973c09be07a314373d02634a500b4369'
assert f'{old_marker_hash}  INSTALLED_SHA' in install
assert sha(git(CK, 'scripts/ops/daily_check.sh')) == SHELL_SHA
assert sha(git(CK, 'scripts/ops/daily_checks.sql')) == SQL_SHA

plan_exec = git(EXEC, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode()
plan_ref = git(REF, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode()
assert len(blocks(plan_ref)) == 101 and blocks(plan_exec) == blocks(plan_ref)
changed = subprocess.check_output(['git', 'diff', '--name-only', OLD, NEW], cwd=ROOT).decode().splitlines()
assert len(changed) == 4 and sum('/src/main/' in path for path in changed) == 1
assert changed[0] == 'web-api/src/main/java/com/cryptoautotrader/api/repository/StrategyLogRepository.java'
service_path = 'web-api/src/main/java/com/cryptoautotrader/api/service/SignalQualityService.java'
service = git(NEW, service_path).decode()
assert git(OLD, service_path) == git(NEW, service_path)
assert '@Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 5 * 60 * 1000)' in service
assert 'int hold4h  = evaluateHoldBaselineLoop(true,  MAX_HOLD_PER_RUN);' in service
assert 'int hold24h = evaluateHoldBaselineLoop(false, MAX_HOLD_PER_RUN);' in service
excerpts = []
for i, line in enumerate(service.splitlines(), 1):
    if any(pattern in line for pattern in ['@Scheduled(', 'if (processed4h', 'if (hold4h', 'HOLD 기준선 평가', '정기 평가 완료', '시작 Catchup 완료', 'int hold4h', 'int hold24h']):
        excerpts.append(f'{NEW}:{service_path}:{i}: {line}')
(OUT / 'source-observation-contract.txt').write_text('\n'.join(excerpts) + '\n', encoding='utf-8')

checks = {
    'reviewed_submission': REF, 'execution_source': EXEC,
    'submitted_manifest': submitted, 'preserved_remote_manifest': remote,
    'remote_read_by_reviewer': False, 'original_server_jar_app_log_cron_not_fetched': True,
    'deployment': {'head': NEW, 'image': IMAGE, 'old_head': OLD, 'old_image': OLD_IMAGE,
        'started_at_utc': '2026-11-05T01:14:51.94517082Z', 'not_container_created': True,
        'jar_diff': expected_diff, 'jar_sha256_reported': jar_hashes, 'jar_files_not_independently_fetched': True,
        'other_four_services_unchanged': True, 'no_restart_through_p4': True,
        'q_and_s_identical_in_three_stages': True, 'flyway': 94, 'sid203': 'CREATED/20200.00',
        'filtered_error_files_empty': errors, 'timezone_of_query_connection': 'UTC',
        'timezone_of_all_jdbc_connections': 'not separately observed',
        'install_cron_ledger_preservation': 'accepted reviewed P0/P4 assertions; original files retained on server'},
    'N14': {'ruling': 'complete: installed, dry run and first automatic report accepted',
        'installed_sha': CK, 'shell_sha256': SHELL_SHA, 'sql_sha256': SQL_SHA,
        'sql_actual_file_origin': '8435935, unchanged bytes', 'cron_sha256': CRON_SHA,
        'report_sha256': report_hash, 'send_success_kst': '2026-11-05 10:00:03',
        'phone_receipt': 'operator reported; not independently byte-compared',
        'old_marker_content_sha': OLD_CK, 'old_marker_file_sha256': old_marker_hash,
        'first_report_existing_news_unclassified_error_lines': 8,
        'first_report_red_items': 1, 'first_report_white': 0,
        'future_in_window_recreation_white': 'expected at Nov05 22:00, not yet observed'},
    'N13': {'ruling': 'deployment accepted; startup improvement indication; regular HOLD improvement remains unconfirmed',
        'before_warning_count': 103, 'before_window_utc': ['2026-11-04T07:39:15.913Z', '2026-11-05T01:12:11.200Z'],
        'after_warning_count': 0, 'after_window_utc': ['2026-11-05T01:14:50Z', '2026-11-05T02:06Z'],
        'acquisition_timeout_lines': 0, 'startup_catchup_seconds': 32.378,
        'startup_hold_processed': [23, 23], 'startup_counts_source': 'developer preserved README; raw completion detail not separately supplied',
        'startup_start_end': 'independently checked holdobs outputs',
        'regular_01_20': 'unobserved', 'regular_01_50': 'developer report of normal signal evaluation 0/2; raw row not separately supplied',
        'hold_processed_line_absence': 'does not prove that queries were skipped or lacked workload; zero returned rows still execute SQL',
        'sql_speedup_or_runtime_in_production': 'not measured; no speedup ratio inferred',
        'next': 'N03 existing logs: positive HOLD completion, long leases/returns, acquisition failures; no forced run/DDL/index/transaction/config change'},
    'news': {'rss_four_coingecko_success': True, 'upbit_success_count': 2,
        'cryptopanic_http403_cumulative_count': 2, 'p4_one_is_subset_of_two': True,
        'fixed_http_lines_and_pairs': True, 'cause_or_recovery': 'unresolved',
        'whole_log_analysis': 'submitted summaries only, full app log retained remotely'},
    'before_pin_update': {'plan_bash_count': 101, 'execution_source_and_submission_blocks_identical': True,
        'main_runtime_diff_paths': changed},
    'unchanged_limits_and_start': 'SID203 CREATED; no start request; 2026-12-01 08:50 KST; final S on Nov30',
}
(OUT / 'review.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifest': len(submitted), 'remote_records': len(remote), 'deployment': 'accepted',
                  'N14': 'complete', 'N13': 'deployed; regular HOLD improvement unconfirmed',
                  'old_marker_file_hash_not_content': old_marker_hash, 'plan_blocks': 101}, ensure_ascii=False))
