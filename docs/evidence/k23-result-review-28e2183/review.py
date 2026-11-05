"""Review preserved #23 deployment records; no server, DB, build or app tests."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '28e21831bdd2b40b4735924682fe9b93ccfad417'
EXEC = '86c41e11f70c6cc44ccabe8e9bed5b2a5275d21b'
OLD = 'fe01207f3f60e97e308925157f84a1bb2da04388'
NEW = '1103f48505a1457029f6555547a87aa986535738'
IMAGE = 'sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62'
OLD_IMAGE = 'sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7'
STARTED = '2026-11-05T06:29:37.799171873Z'
E = ROOT / 'docs/evidence/k23-result-1103f48'
M = E / 'maint-n15n16'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def git(ref, name):
    return subprocess.check_output(['git', 'show', f'{ref}:{name}'], cwd=ROOT)

def read(path):
    return path.read_text(encoding='utf-8')

def blocks(value):
    return re.findall(r'^```bash\n(.*?)^```', value.replace('\r\n', '\n'), re.M | re.S)

def manifest(folder, ref, count):
    rows = []
    for line in read(folder / 'SHA256SUMS').splitlines():
        digest, name = re.fullmatch(r'([a-f0-9]{64}) [ *](.+)', line).groups()
        file = folder / name
        assert sha(file.read_bytes()) == digest, str(file)
        assert sha(git(ref, file.relative_to(ROOT).as_posix())) == digest, str(file)
        rows.append({'file': name, 'sha256': digest})
    assert len(rows) == count
    return rows

submitted = manifest(E, REF, 88)
previous = manifest(ROOT / 'docs/evidence/k23-accept-86c41e1', REF, 6)
remote = {}
for line in read(E / 'server-sha256.txt').splitlines():
    digest, name = re.fullmatch(r'([a-f0-9]{64}) [ *](.+)', line).groups()
    assert name not in remote
    remote[name] = digest
assert len(remote) == 72
copied = sorted(p for p in M.iterdir() if p.is_file())
assert len(copied) == 66
for file in copied:
    assert sha(file.read_bytes()) == remote[file.name], file.name
remote_only = {name: digest for name, digest in remote.items() if not (M / name).exists()}
assert set(remote_only) == {'app-old.jar', 'app-new.jar', 'backend-before.log', 'app-deploy.log', 'app-after30.log', 'app-news-20261105-1625.log'}

exec_plan = git(EXEC, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
ref_plan = git(REF, 'docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8')
assert len(blocks(exec_plan)) == 117 and blocks(exec_plan) == blocks(ref_plan)
names = ['R변수', 'R0', 'R1', 'R2', 'R3', 'R4', 'R뒤관측', 'R되돌림']
record = json.loads(read(E / 'blocks/blocks.json'))
old_record = json.loads(read(ROOT / 'docs/evidence/k23-final-1103f48/local-check/blocks.json'))
assert record == old_record
assert [row[0] for row in record] == names
for (name, digest), block in zip(record, blocks(exec_plan)[-8:]):
    assert sha(block.encode('utf-8')) == digest
    assert (E / f'blocks/{name}.sh').read_bytes() == block.encode('utf-8')
assert sha((E / 'blocks/F.sh').read_bytes()) == '09a110994328b1de5f5c75f412e71a261e9608ba32c2236285f3d8815f6b8c2a'
assert sha((E / 'blocks/G.sh').read_bytes()) == '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822'
assert (E / 'blocks/F.sh').read_text(encoding='utf-8') in blocks(exec_plan)
assert (E / 'blocks/G.sh').read_text(encoding='utf-8') in blocks(exec_plan)
runner = read(E / 'blocks/run.sh')
assert 'PIPESTATUS[0]' in runner and '< /dev/null' in runner and 'bash -c' in runner

assert read(M / 'git-before.txt').splitlines() == [OLD, '(끝)']
assert read(M / 'git-deploy.txt').splitlines() == [NEW, '(끝)']
assert read(M / 'image-before.txt').strip() == OLD_IMAGE
assert read(M / 'image-new.txt').strip() == IMAGE
diff = read(M / 'jar-diff.txt').splitlines()
local_diff = read(ROOT / 'docs/evidence/k23-final-1103f48/jars/diff-fe01207-1103f48.txt').splitlines()
assert diff == local_diff and diff[-1] == '(끝) 879 881'
assert sum(s.startswith('변경 ') for s in diff) == 27 and sum(s.startswith('추가 ') for s in diff) == 2
jar_hashes = [line.split()[0] for line in read(M / 'jar-sha.txt').splitlines()]
assert jar_hashes == ['0578ceb3e9afdde5f33e62098dcf62e5425f615922d840ae345d59f8c70ceb4e', 'a6f4357e8a8b53085541896c90c7c7add4632897dff062e6fdd130b07fa60e7e']
assert jar_hashes[0] == read(ROOT / 'docs/evidence/k22-result-fe01207/maint-n13/jar-sha.txt').splitlines()[1].split()[0]
assert [remote['app-old.jar'], remote['app-new.jar']] == jar_hashes

svc0 = read(M / 'svc-before.txt').splitlines()
svc1 = read(M / 'svc-deploy.txt').splitlines()
assert len(svc0) == len(svc1) == 5 and svc0[1:] == svc1[1:]
assert svc1[0] == f'backend {IMAGE} {STARTED}'
assert (M / 'svc-deploy.txt').read_bytes() == (M / 'svc-after30.txt').read_bytes()
for prefix in ('q', 's'):
    assert len({(M / f'{prefix}-{stage}.txt').read_bytes() for stage in ('before', 'deploy', 'after30')}) == 1
q = read(M / 'q-after30.txt')
assert re.search(r'flyway_version\s*\n-+\s*\n\s*94\s*\n', q)
assert re.search(r'LIVE\s*\|\s*203\s*\|\s*CREATED\s*\|\s*KRW-BTC\s*\|\s*20200\.00', q)
assert read(M / 's-after30.txt').strip() == '66 | 63 | 3 | -'
for number in (3, 6):
    section = re.search(rf'== Q{number} .*?(?=\n== Q|\nROLLBACK)', q, re.S).group()
    assert '(0 rows)' in section
for key in ('real_unresolved_orders', 'cleanup_pending', 'quarantine_release_rows'):
    assert re.search(rf'{key}\s*\n-+\s*\n\s*0\s*\n', q)
assert re.search(r'2026-11-05 06:27:41\+00\s*\|\s*0\s*\n', q)
assert sorted(p.name for p in M.glob('ok-*')) == ['ok-R0', 'ok-R1', 'ok-R2', 'ok-R3-deploy', 'ok-R4']
error_files = sorted(p.name for p in M.glob('errors-*.txt'))
assert len(error_files) == 7 and all((M / name).stat().st_size == 0 for name in error_files)
assert read(M / 'timezone-before.txt').strip() == 'DB 조회 세션 TimeZone: UTC'
assert sha((M / 'crontab-before.txt').read_bytes()) == 'f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b'
assert read(M / 't-deploy.utc').strip() == '2026-11-05T06:29:36Z'
for stage in ('before', 'deploy'):
    assert read(M / f'btc-{stage}.txt').strip() == 'true|11-05 14:00|11-05 14:00|20/20'
assert read(M / 'btc-after30.txt').strip() == 'true|11-05 15:00|11-05 14:00|20/20'
assert not any(M.glob('*check*'))

outputs = {}
for name in ['R0', 'R1', 'R2', 'R3', 'R4', 'R뒤관측']:
    output = read(E / f'out-{name}.txt')
    assert re.search(r'\[로컬 끝\].*rc=0\s*$', output), name
    if name != 'R뒤관측':
        assert f'✅ {name}' in output
    assert '🔴 멈춤:' not in output
    outputs[name] = output
assert 'Schema "public" is up to date. No migration necessary.' in outputs['R3']
assert 'Started CryptoAutoTraderApplication in 15.167 seconds' in outputs['R3']
assert 'BTC 관문: 판정 불가·재개 줄 없음' in outputs['R3'] and 'BTC 관문: 판정 불가·재개 줄 없음' in outputs['R4']
assert '재기동' in outputs['R2'] and 'Image autotrader-backend Built' in read(M / 'build.log')
start = datetime.fromisoformat(STARTED[:26] + '+00:00')
def wall(value):
    return datetime.strptime(value, '%Y-%m-%d %H:%M:%S').replace(tzinfo=timezone.utc)
seconds_to_r3 = (wall('2026-11-05 06:36:49') - start).total_seconds()
seconds_to_r4 = (wall('2026-11-05 07:00:44') - start).total_seconds()
assert 300 <= seconds_to_r3 <= 600 and seconds_to_r4 >= 1800

zero_hold = 'HOLD 기준선 질의 장기 대여 경고 0건(반환 0·반환 미관측 0) · 그 밖의 장기 대여 경고 0건 · 연결 획득 시간 초과 줄 0건'
for stage in ('deploy', 'after30', '20261105-1625'):
    assert zero_hold in read(M / f'holdobs-{stage}.txt')
    assert '시작 Catchup: 시작 06:29:54.148 · 완료 06:30:05.210' in read(M / f'holdobs-{stage}.txt')
    entry = read(M / f'entryobs-{stage}.txt')
    assert '만료 사유 기록 실패 0' in entry and 'N15: 미관측' in entry and 'N16: 미관측' in entry
for stage, count in [('after30', 1), ('20261105-1625', 2)]:
    h = read(M / f'httpobs-{stage}.txt')
    assert f'HTTP 403 ×{count}' in h and f'고정 한 줄 {count}' in h and f'0건 짝 {count}' in h
    assert '본문 형식/연속 줄 0' in h and '짝 미확인 0' in h
news = read(M / 'newsobs-20261105-1625.txt')
for source in ('blockmedia_rss', 'bloomberg_rss', 'coindesk_rss', 'tokenpost_rss', 'coingecko_trending'):
    assert re.search(rf'^{source} .*완료 1·미완료 0', news, re.M), source
assert re.search(r'^upbit_notice .*완료 2·미완료 0', news, re.M)
assert re.search(r'^cryptopanic .*완료 0·미완료 2.*HTTP_403', news, re.M)
cfg = lambda stage: [r.split('|')[:4] for r in read(M / f'news-{stage}.txt').splitlines()]
assert len(cfg('before')) == 7
assert all(cfg(stage) == cfg('before') for stage in ('deploy', 'after30', '20261105-1625'))

alerts = read(M / 'alerts-after30.txt')
supp = read(E / 'out-alerts-supp.txt')
assert '관측 0회' in alerts and '⚪ 이 구간에 관측이 없다' in alerts
assert re.findall(r'^🔴 \d\d-\d\d .*?1종: (.+)$', read(M / 'alerts-before.txt'), re.M) == ['KRW-BLAST'] * 24
assert re.findall(r'^🔴 \d\d-\d\d .*?1종: (.+)$', supp, re.M) == ['KRW-BLAST']
assert '관측 1회' in supp and '11-05 07:19' in supp and '🟢 21 / ⚪ 0 / 🔴 1' in supp
assert '경보 종료코드 1' in supp and '실행 누락 없음' in alerts

checks = {
    'reviewed_submission': REF, 'execution_source': EXEC,
    'submitted_manifest': submitted, 'previous_review_manifest': previous,
    'server_manifest_files': 72, 'copied_server_files_verified': 66,
    'server_only_hashes_not_fetched': remote_only,
    'reviewer_server_DB_build_app_test_execution': False,
    'execution_blocks': {'count': 8, 'source_and_final_submission_hashes_equal': True, 'F_G_source_equal': True, 'before_and_after_plan_commands_equal': 117, 'runner_captures_ssh_exit_status': True},
    'deployment': {'ruling': 'accepted', 'head': NEW, 'image': IMAGE, 'previous_head': OLD, 'previous_image': OLD_IMAGE, 'started_at_utc': STARTED, 'container_Created_not_supplied_separately': True,
        'actual_server_jar_diff_matches_approved_local_paths': True, 'changed': 27, 'added': 2, 'deleted': 0, 'entries_before_after': [879, 881], 'server_jar_hashes': jar_hashes, 'old_jar_equals_previous_deployed_new_jar': True,
        'other_four_services_unchanged': True, 'backend_no_restart_through_R4': True, 'q_s_three_stages_byte_equal': True, 'flyway': 94, 'SID203': 'CREATED/20200.00', 'seven_filtered_error_files_empty': True,
        'R3_seconds_after_started': seconds_to_r3, 'R4_seconds_after_started': seconds_to_r4,
        'R0_R4_sid_baseline_EG_ledger_installed_ops_cron_guards': 'accepted from unchanged reviewed blocks and successful outputs; original server files not read independently',
        'operator_confirmation_once': 'developer record, not independently observed'},
    'alerts': {'R4_scheduled_observations': 0, 'R4_coverage_white_retained': True, 'supplement_read_kst': '2026-11-05 16:25', 'supplement_observation_kst': '2026-11-05 16:19', 'green_white_red': [21, 0, 1], 'only_red': 'KRW-BLAST', 'execution_missing': 'none in reported interval'},
    'N15_N16': {'ruling': 'complete: local behavior validation and deployed artifact/safety verification accepted; runtime-unapplied P0 closed', 'local_validation': 'previously reviewed 25 classes 173/173; not rerun', 'direct_runtime_guard_behavior': 'unobserved natural events; no induced orders or prices', 'future_timestamp_correction': 'not observable through entryobs; not claimed directly observed', 'profitability_and_Dec1_start_approval': 'not established by this ruling'},
    'N13': {'ruling': 'deployment retained; regular HOLD improvement unconfirmed', 'long_leases_or_acquisition_timeouts': [0, 0], 'observed_after_restart_minutes': 56, 'no_HOLD_completion_does_not_prove_query_skipped': True},
    'news': {'RSS_four_CoinGecko_success_once': True, 'Upbit_success_twice': True, 'CryptoPanic_HTTP403_cumulative_count': 2, 'R4_one_is_subset_of_two': True, 'fixed_lines_and_upper_result_pairs': True, 'cause_recovery': 'unresolved'},
    'logs': {'current_22_report_nominal_window': '2026-11-05 10:00~22:00 KST', 'old_container_last_two_minutes': '15:27~15:29 not secured; retain as uncovered', 'current_StartedAt_is_not_Created': True, 'new_report_expected': 'latest planned restart red; Created coverage white; actual values must be read'},
    'next': 'update F variable/F1 head-image-record path and N06 hashes; keep final S unfixed until Nov30; next actual results commit includes ruling unchanged',
}
(OUT / 'review.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'submission': 88, 'previous': 6, 'server_copy': 66, 'server_only': 6, 'blocks': 8, 'jar_diff': '27 changed + 2 added / 879 -> 881', 'deployment': 'accepted', 'N15_N16': 'complete, natural behavior unobserved'}, ensure_ascii=False))
