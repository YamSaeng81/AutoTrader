"""Review submitted Git and archive bytes locally. No server, DB or product test calls."""
import ast
import datetime as dt
import hashlib
import io
import json
import pathlib
import re
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SRC = '7fea1b89d040a13f4da6fd471646fbe8b50eec12'
FIX = 'c584166bc3eebc217bba0b910a393933126fec08'
RUN = 'docs/evidence/v172run-20261116/'
PREP = 'docs/evidence/v172u2c-20261116/'
NEW = '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d'
BE = 'sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f'
FE = 'sha256:9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e'
OLD_BE = 'sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055'
OLD_FE = 'sha256:837db515e0eb8b9cb4bcf8f03ed3b98adf9f8cdb176d13777949c792d10b5017'
JARM = 'b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8'
checks = {}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.run(['git', '-c', 'core.quotepath=false', '-C', str(ROOT), *args], capture_output=True, check=True).stdout


def blob(path, commit=SRC):
    return git('show', commit + ':' + path)


def check(name, ok, **details):
    checks[name] = dict(ok=bool(ok), **details)
    assert ok, (name, details)


def sums(data):
    result = {}
    for line in data.decode('utf-8-sig').splitlines():
        m = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        assert m and m[2] not in result, line
        result[m[2]] = m[1]
    return result


for folder, expected in [(RUN, 45), ('docs/evidence/review-c584166b/', 4)]:
    manifest = sums(blob(folder + 'SHA256SUMS'))
    bad = [p for p, h in manifest.items() if sha(blob(folder + p)) != h or sha((ROOT / (folder + p)).read_bytes()) != h]
    check('manifest_' + folder, len(manifest) == expected and not bad, count=len(manifest), mismatch=bad)

check('head_origin_match_submission', git('rev-parse', 'HEAD').strip() == git('rev-parse', 'origin/main').strip() == SRC.encode())
paths = git('diff', '--name-only', FIX, SRC).decode().splitlines()
check('new_commit_docs_only', all(p.startswith('docs/') for p in paths), paths=paths)
for name in ('U0', 'U1', 'U2'):
    p = RUN + 'out/out-' + name + '-deploy.txt'
    check('original_output_preserved_' + name, blob(p) == blob(p, FIX))

extract = blob(PREP + 'runner/extract.py').decode()
expected = ast.literal_eval(re.search(r'EXP = (\{.*?^\})', extract, re.M | re.S)[1])
report = json.loads(blob(RUN + 'blocks-u2c/blocks.json'))
check('extraction_source', report['source'] == FIX)
for name, h in expected.items():
    check('extracted_hash_' + name, report[name]['sha256'] == h and report[name]['expected_match'])
check('fixed_runner_unchanged', blob(PREP + 'runner/run.sh') == blob(PREP + 'runner/run.sh', FIX))

server = {p.removeprefix('./'): h for p, h in sums(blob(RUN + 'server/maint-v172.sha256')).items()}
archive = {}
with tarfile.open(fileobj=io.BytesIO(blob(RUN + 'server/maint-v172-nojar.tar.gz')), mode='r:gz') as tar:
    for member in tar:
        p = pathlib.PurePosixPath(member.name)
        check('safe_archive_member_' + member.name, not p.is_absolute() and '..' not in p.parts and not member.issym() and not member.islnk())
        if member.isfile():
            assert p.parts[0] == 'maint-v172' and len(p.parts) == 2
            name = p.name
            assert name not in archive
            archive[name] = tar.extractfile(member).read()
bad = [n for n, data in archive.items() if sha(data) != server.get(n)]
omitted = sorted(set(server) - set(archive))
check('server_archive_124_of_127', len(server) == 127 and len(archive) == 124 and not bad and omitted == ['app-new.jar', 'app-old.jar', 'backend-before.log'], included=len(archive), server_count=len(server), omitted=omitted, mismatch=bad)


def text(name):
    return archive[name].decode('utf8')


check('u2_confirmation_marker_preserves_original_failure', all(s in text('ok-U2') for s in ['원래 U2 rc1', '14:16:12 KST', '05:16:14.129686133Z']))
check('required_completion_markers', all(n in archive for n in ['ok-U0', 'ok-U1', 'ok-U2', 'ok-U3-deploy', 'ok-UF2', 'ok-U4']))
check('no_rollback_records', not any('rollback' in n or '되돌림' in n for n in archive))
for tail, new in [('svc-deploy.txt', 'svc-u2c-20261116-161400.txt'), ('git-deploy.txt', 'git-u2c-20261116-161400.txt')]:
    check('tail_bytes_' + tail, archive[tail] == archive[new])
check('actual_git_head_clean', text('git-deploy.txt') == NEW + '\n(끝)\n')
for name, value in [('image-new.txt', BE), ('image-fe-new.txt', FE), ('image-before.txt', OLD_BE)]:
    check('image_record_' + name, text(name).strip() == value)
check('old_frontend_container_id_and_target_distinct', text('image-fe-before.txt').strip() == 'sha256:d825a9f5b00d3dac93ee154f32f91b5a1474248fefaa3651f0d0c3b0cc2f7f66' and text('image-fe-before-chain.txt').split()[0] == OLD_FE)
check('jar_content_manifest_hash', sha(archive['jar-manifest-new.txt']) == JARM)
check('jar_name_diff_count', len(text('jar-diff.txt').splitlines()) == 63 and text('jar-diff.txt').splitlines()[-1] == '(끝) 881 895')
for name, target, manifest, config in [
    ('image-new-chain.txt', BE, 'b3a42cd2dd9947e6dffc5ccec71de077b3d2fccfc810d21796378be73b05aa0c', '4016cc389044ba95a88f9048f6b14de7118e276c75f9a524998e2b8baa50ac34'),
    ('image-fe-new-chain.txt', FE, '13343943ed4ab0db003bf40e77cb38c9d41e67287a7d51fcb6da818ad6569d93', '11d9a954a3054d3eff3c6d2d798fad55beefce3a166c9335259d8fa6367e4f89')]:
    check('image_chain_' + name, text(name).split() == [target, 'sha256:' + manifest, 'sha256:' + config, '-'])


def services(name):
    return {line.split()[0]: line.split()[1:] for line in text(name).splitlines()}


before, deployed, fe, final = [services(n) for n in ['svc-before.txt', 'svc-deploy.txt', 'svc-fe.txt', 'svc-after30.txt']]
check('all_five_services', all(len(x) == 5 for x in [before, deployed, fe, final]))
check('three_other_services_unchanged', all(before[n] == deployed[n] == fe[n] == final[n] for n in ['db', 'redis', 'db-backup']))
check('backend_no_second_recreate', deployed['backend'] == fe['backend'] == final['backend'] == [BE, '2026-11-16T05:16:14.129686133Z'])
check('frontend_replaced_once', before['frontend'] == deployed['frontend'] and fe['frontend'] == final['frontend'] == [FE, '2026-11-16T07:15:16.447837224Z'])
check('frontend_stage_replaced', text('fe-stage.txt').strip() == '교체끝')

exits = blob(RUN + 'out/exit-codes.txt').decode()
rows = re.findall(r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (\S+) deploy rc=(\d+) (\S+)$', exits, re.M)
check('all_eight_actual_statuses', len(rows) == 8 and [(n, int(rc)) for _, n, rc, _ in rows] == [('U0', 0), ('U1', 0), ('U2', 1), ('U2확인', 0), ('U3', 0), ('UF2', 0), ('U4', 0), ('U뒤관측', 0)])
outputs = {}
for _, name, rc, filename in rows:
    raw = blob(RUN + 'out/' + filename).decode('utf8')
    outputs[name] = raw
    check('actual_exit_' + name, re.search(r'\[로컬 끝\].*rc=' + rc + r'\s*$', raw) is not None)
    if name in ['U2확인', 'U3', 'UF2', 'U4', 'U뒤관측']:
        check('continuation_source_' + name, '출처=c584166b' in raw)
check('history_count_content_actual', '1|96:true:-424836241' in outputs['U2확인'])
check('two_second_start_delay_actual', '배포 기록 뒤 2초' in outputs['U2확인'] and '재시작 0 · running' in outputs['U2확인'])
start = dt.datetime(2026, 11, 16, 14, 16, 14)
u3start = dt.datetime(2026, 11, 16, 16, 14, 37)
late = int((u3start - start - dt.timedelta(minutes=10)).total_seconds())
check('u3_late_deviation', late == 6503, seconds_after_window=late, seconds_after_start=int((u3start - start).total_seconds()))
check('u3_immediate_after_confirmation', dt.datetime(2026, 11, 16, 16, 14, 37) - dt.datetime(2026, 11, 16, 16, 14, 30) == dt.timedelta(seconds=7))
check('u4_after_original_thirty_minimum', dt.datetime(2026, 11, 16, 16, 20, 58) > start + dt.timedelta(minutes=30))

logs = [n for n in archive if n.startswith('app-') and n.endswith('.log')]
for n in logs:
    s = text(n)
    check('nonempty_original_start_log_' + n, 'Successfully applied 1 migration' in s and 'now at version v96' in s and len(s.splitlines()) > 100)
    check('no_p0_leak_limit_' + n, not re.search(r'Connection is not available|Apparent connection leak detected|\bConnection leak detection triggered\b|🔴.*(?:공유 풀|위험 한도)', s))
    errors = [line for line in s.splitlines() if ' ERROR ' in line]
    check('three_known_404_errors_' + n, len(errors) == 3 and all('status=404' in line for line in errors) and all(any('market=' + market in line for line in errors) for market in ['KRW-BLAST', 'KRW-BONK', 'KRW-STORJ']))
final_log = text('app-news-20261116-1621.log')
check('latest_two_p0_log_bytes_equal', archive['app-news-20261116-1621.log'] == archive['app-20261116-1621-p0.log'])
check('cryptopanic_four_known_fixed_lines', len(re.findall(r'WARN\s+.*CryptoPanicSource - \[CryptoPanicSource\] HTTP 403 — sourceId=cryptopanic$', final_log, re.M)) == 4 and 'HTTP 403 ×282' in text('http-before.txt') and '짝 미확인 0' in text('httpobs-20261116-1621.txt'))
check('blast_existing_only', '적재 뒤처짐 3회' in text('alerts-after30.txt') and '적재 뒤처짐 24회' in text('alerts-before.txt') and set(re.findall(r'1종: (\S+)', text('alerts-after30.txt'))) == {'KRW-BLAST'})
check('second_p0_result_zero', '연결 획득 시간 초과 줄 0건' in text('holdobs-20261116-1621.txt') and text('holdobs-20261116-1621.txt') == text('holdobs-20261116-1621-p0.txt'))
check('error_files_empty', all(not data for n, data in archive.items() if n.startswith('errors-')))
for stage in ['U3', 'U4']:
    s = outputs[stage]
    check('real_state_and_btc_' + stage, all(x in s for x in ['(0 rows)', 'real_unresolved_orders', 'cleanup_pending', 'real_buys_since_maint', '66 | 63 | 3 | -', 'true|11-16 15:00|11-16 15:00|20/20']))
check('new_behaviour_unobserved', all('미관측' in text(n) for n in ['entryobs-after30.txt', 'n17obs-after30.txt', 'n19obs-after30.txt', 'n20obs-after30.txt']))
screen = blob(RUN + 'screen/fe-screen-2.txt').decode()
check('six_pages_and_proxy_apis_200', len(re.findall(r'^페이지 .* → 200 .*오류 문자열 0$', screen, re.M)) == 6 and len(re.findall(r'^API .* → 200 · success=True', screen, re.M)) == 6)
check('frontend_restart_zero_http_read', FE in screen and '07:15:16.447837224Z 0' in screen)
check('sid203_created_capital', 'id=203 status=CREATED' in screen and 'availableKrw=20200.0' in screen)
check('blind_session_interim_metric_disclosed', 'sessions/587' in screen and 'totalReturnPct=' in screen, finding='Session 587 is in preregistered IDs 522-587. Interim return was printed. Record scope deviation, do not use it or query further returns.')
check('http_checks_do_not_claim_rendering', '화면이 실제로 그려진 모양' in blob(RUN + 'README.md').decode())
runbook_path = 'docs/LIMITED_LIVE_TEST_RUNBOOK.md'
old_text = blob(runbook_path).decode().replace('\r\n', '\n')
new_text = (ROOT / runbook_path).read_text(encoding='utf8')
old_blocks = re.findall(r'^```bash\n(.*?)^```', old_text, re.M | re.S)
new_blocks = re.findall(r'^```bash\n(.*?)^```', new_text, re.M | re.S)
names = ['F 변수', 'F1', 'F1 운영자 확인', 'F2 POST', 'F2 확인', 'F2-1', 'F3', 'F3-2', 'F5', 'F5-1', 'F5-2', 'F5-3', 'F5-3v', 'F5-4', 'F6 사유', 'F6 요청']
check('runbook_sixteen_blocks_two_pin_changes', len(old_blocks) == len(new_blocks) == len(names) == 16 and [i for i, (a, b) in enumerate(zip(old_blocks, new_blocks)) if a != b] == [0, 1])
check('runbook_new_head_pin', re.search(r'^NEW=([0-9a-f]{40})', new_blocks[0], re.M)[1] == NEW)
check('runbook_new_image_and_record_pin', re.search(r'^IMGF1=(sha256:[0-9a-f]{64})', new_blocks[1], re.M)[1] == BE and 'cat ~/maint-v172/image-new.txt' in new_blocks[1])
for name, block in zip(names[:2], new_blocks[:2]):
    p = subprocess.run(['C:/Program Files/Git/bin/bash.exe', '--noprofile', '--norc', '-n'], input=block.encode(), capture_output=True)
    check('updated_runbook_syntax_' + name, p.returncode == 0)
check('f3_original_hash_preserved', sha(new_blocks[6].encode()) == 'bb86fb397da00523f564a8f02a1960c98bb1ac03887629fe74372990929fa2bb')
pins = {'source_before': SRC, 'version': '1.176', 'operating_head': NEW, 'operating_image': BE, 'image_record': '~/maint-v172/image-new.txt', 'frontend_image': FE, 'flyway': 96, 'rollback_head': 'b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88', 'rollback_backend_image': OLD_BE, 'rollback_frontend_image': OLD_FE, 'rollback_condition': 'V96 retained, original state-based gate and operator comparison required', 'final_S': 'unfixed_until_2026-11-30', 'hash_format': 'UTF-8 bash body, LF ending; fences excluded', 'runbook_sha256': sha((ROOT / runbook_path).read_bytes()), 'blocks': [{'block': name, 'index_zero_based_runbook': i, 'old_sha256': sha(old.encode()), 'new_sha256': sha(new.encode()), 'changed': old != new} for i, (name, old, new) in enumerate(zip(names, old_blocks, new_blocks))]}
(OUT / 'n06-blocks.json').write_text(json.dumps(pins, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')
result = {'source': SRC, 'fixed_correction_source': FIX, 'count': len(checks), 'failed': sum(not x['ok'] for x in checks.values()), 'scope': 'Local submitted bytes only. No production calls, DB calls, builds or product tests. Omitted server binaries not downloaded.', 'checks': checks}
(OUT / 'checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')
print(json.dumps({'count': result['count'], 'failed': result['failed'], 'server_included': len(archive), 'server_omitted': omitted}, ensure_ascii=False))
