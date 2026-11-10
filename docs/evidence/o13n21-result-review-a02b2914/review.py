"""Local evidence review only. No server, network, product test or build."""
from pathlib import Path
from datetime import datetime
import hashlib, json, re, subprocess

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
REF = 'a02b2914e7ec727b9aeaeb7683d0e2dcd213b365'
SRC = '9aa617d134e7bd51c2f97d16d0815cbdc6623a4e'
OLD = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
NEW = 'b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88'
IMG = 'sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055'
IMGOLD = 'sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1'
BASE = ROOT / 'docs/evidence/o13n21-d3-result-b5cd1d0'
M = BASE / 'maint-o13n21'

def sha(b): return hashlib.sha256(b).hexdigest()
def git(*args): return subprocess.check_output(['git', *args], cwd=ROOT)
def txt(p): return p.read_text(encoding='utf-8').replace('\r\n', '\n')
def require(value, label):
    if not value: raise AssertionError(label)

require(git('rev-parse', 'HEAD').decode().strip() == REF, 'review ref')
report = {'ref': REF, 'source': SRC, 'candidate': NEW, 'image': IMG}
report['manifests'] = {}
for name in ['o13n21-d3-result-b5cd1d0', 'o13n21-accept-9aa617d']:
    folder = ROOT / 'docs/evidence' / name
    lines = txt(folder / 'SHA256SUMS').splitlines()
    entries = {}
    for line in lines:
        if not line.strip(): continue
        match = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        require(match is not None, 'manifest shape')
        digest, rel = match.groups()
        require(rel not in entries and '..' not in Path(rel).parts, 'manifest path')
        p = folder / rel
        require(sha(p.read_bytes()) == digest, 'local checksum: ' + rel)
        require(sha(git('show', REF + ':' + p.relative_to(ROOT).as_posix())) == digest,
                'committed checksum: ' + rel)
        entries[rel] = digest
    actual = {p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file()}
    require(actual == set(entries) | {'SHA256SUMS'}, 'manifest complete: ' + name)
    require((folder / 'SHA256SUMS').read_bytes() == git('show', REF + ':' +
            (folder / 'SHA256SUMS').relative_to(ROOT).as_posix()), 'manifest blob')
    report['manifests'][name] = {'payload': len(entries), 'including_manifest': len(actual),
                               'local_and_git_match': True}

blocks = json.loads(txt(BASE / 'blocks/blocks.json'))
require(blocks['source'] == SRC, 'block source')
plan_old = git('show', SRC + ':docs/LIMITED_LIVE_TEST_PLAN.md').decode()
plan_blocks = re.findall(r'```bash\r?\n([\s\S]*?)\r?\n```', plan_old)
block_results = {}
for key, meta in blocks.items():
    if not isinstance(meta, dict): continue
    raw = (BASE / 'blocks' / (key + '.sh')).read_bytes()
    require(meta['expected_match'] and sha(raw) == meta['sha256'], 'block hash ' + key)
    if key in ('F', 'G'):
        expected = (plan_blocks[meta['index']] + '\n').encode()
    else:
        expected = git('show', SRC + ':' + meta['from'])
    require(raw == expected, 'block source identity ' + key)
    block_results[key] = sha(raw)
require(len(block_results) == 12, 'twelve blocks')
report['blocks'] = block_results

final = txt(BASE / 'out/final-state.txt')
server_hashes = dict((name, digest) for digest, name in
                     re.findall(r'^([0-9a-f]{64})  (\S+)$', final, re.M))
copies = {p.name: sha(p.read_bytes()) for p in M.iterdir() if p.is_file()}
require(len(copies) == 98, 'server small copy count')
require(all(server_hashes.get(name) == digest for name, digest in copies.items()),
        'server copy hashes')
local_copy = dict((name, digest) for digest, name in re.findall(
    r'^([0-9a-f]{64}) [ *](\S+)$', txt(BASE / 'out/local-copy.sha256'), re.M))
require(copies == local_copy, 'local-copy manifest')
large = {k: v for k, v in server_hashes.items() if k not in copies}
require(len(large) == 7 and set(large) == {
    'app-old.jar','app-new.jar','backend-before.log','app-deploy.log',
    'app-after30.log','app-news-20261110-1500.log','build.log'}, 'large originals remain server')
report['server_copies'] = {'matched': len(copies), 'server_only_hashes': large,
                           'root_did_not_read_server_or_large_files': True}

outputs = {}
for phase in ['U0', 'U1', 'U2', 'U3', 'U4', 'U5', 'U뒤관측']:
    content = txt(BASE / ('out-' + phase + '-deploy.txt'))
    sm = re.search(r'^\[로컬 시작\] (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) \+0900', content, re.M)
    em = re.search(r'^\[로컬 끝\] (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) \+0900 rc=(\d+)$', content, re.M)
    require(sm and em and em[2] == '0', 'actual exit ' + phase)
    outputs[phase] = {'local_start_kst': sm[1], 'local_end_kst': em[1], 'exit': int(em[2])}
    if phase != 'U뒤관측': require('✅ ' + phase in content, 'success marker ' + phase)
report['phase_outputs'] = outputs
require(not any(p.name.startswith('out-') and ('rollback' in p.name or '되돌림' in p.name)
                for p in BASE.iterdir()), 'no rollback output')

started = re.search(r'^backend .*StartedAt=(\S+)', final, re.M)[1]
start_dt = datetime.fromisoformat(started.replace('Z', '+00:00'))
u3_dt = datetime.fromisoformat(outputs['U3']['local_start_kst']).replace(tzinfo=None)
start_kst = start_dt.timestamp() + 9*3600
u3_delay = (u3_dt - datetime(1970,1,1)).total_seconds() - start_kst
u4_dt = datetime.fromisoformat(outputs['U4']['local_start_kst'])
u4_delay = (u4_dt - datetime(1970,1,1)).total_seconds() - start_kst
require(u3_delay > 600 and u4_delay >= 1800, 'late U3 and >=30m U4')
report['timing'] = {'started_at_utc': started, 'u3_after_started_seconds': round(u3_delay, 3),
                    'u3_5_to_10_minute_window_met': False,
                    'u4_after_started_seconds': round(u4_delay, 3),
                    'time_rule_is_not_replaced_by_later_log_review': True}

for name in ['q-before.txt', 'q-deploy.txt', 'q-after30.txt']:
    require((M/name).is_file(), 'q file')
require((M/'q-before.txt').read_bytes() == (M/'q-deploy.txt').read_bytes() ==
        (M/'q-after30.txt').read_bytes(), 'q byte identity')
require((M/'s-before.txt').read_bytes() == (M/'s-deploy.txt').read_bytes() ==
        (M/'s-after30.txt').read_bytes(), 's byte identity')
require(txt(M/'s-before.txt').strip() == '66 | 63 | 3 | -', '63+3 sessions')
svcs = {}
for suffix in ['before','deploy','after30']:
    svcs[suffix] = dict(line.split(' ',1) for line in txt(M/('svc-'+suffix+'.txt')).splitlines())
for service in ['db','redis','db-backup','frontend']:
    require(len({v[service] for v in svcs.values()}) == 1, 'other service unchanged ' + service)
require(svcs['deploy']['backend'] == svcs['after30']['backend'], 'no backend restart')
require(txt(M/'image-new.txt').strip() == IMG and txt(M/'image-before.txt').strip() == IMGOLD, 'image files')
require(txt(M/'git-deploy.txt').splitlines() == [NEW, '(끝)'], 'head clean')
require(txt(M/'git-u5.txt').splitlines() == [NEW, '(끝)'], 'U5 head clean')
require('HEAD ' + NEW in final and '추적 변경 0' in final and 'RestartCount=0 Status=running' in final, 'final head/runtime')
jar_lines = txt(M/'jar-diff.txt').splitlines()
require(jar_lines == [
 '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService.class',
 '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$PriceSnapshot.class',
 '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService.class',
 '(끝) 881 881'], 'server jar diff')
require(large['app-old.jar'] == '10bf86285c6e5eaf7cbe740f060eff852cc943128e521eb3e2c0a42bd2db69a5', 'old jar accepted base')
for suffix in ['deploy', 'after30']:
    require(txt(M/('btc-'+suffix+'.txt')).strip() == 'true|11-10 13:00|11-10 13:00|20/20', 'BTC')
    for kind in ['errors','errors-all','errors-news']:
        require((M/(kind+'-'+suffix+'.txt')).stat().st_size == 0, 'error file empty')
require(txt(M/'crontab-before.txt') == txt(M/'crontab-u5.txt'), 'cron equality')
require(sha((M/'crontab-u5.txt').read_bytes()) == 'f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b', 'cron accepted hash')
report['safety'] = {'q_three_way_equal': True, 'session_three_way_equal': True,
                    'other_services_unchanged': True, 'jar_changed_entries': 3,
                    'jar_entries': [881,881], 'btc': 'true|13:00|13:00|20/20',
                    'error_files_empty': True, 'cron_unchanged': True}

rows = [json.loads(line) for line in txt(M/'log-after-apply.jsonl').splitlines() if line.strip()]
require(len(rows) == 1, 'N21 one new row')
row = rows[0]
require((row['green'],row['unknown'],row['red']) == (21,0,1), 'N21 colors')
require(row['redCoins'] == ['KRW-BLAST'] and row['rechecked'] == ['KRW-CVC'], 'N21 coins')
require(row['rows']['KRW-CVC']['exErr'] is None and
        row['rows']['KRW-CVC']['dbTo'] == row['rows']['KRW-CVC']['exTo'], 'N21 successful retry')
require('13:19:01' in txt(M/'cron-after-apply.txt'), 'cron after checkout')
require('Traceback' not in txt(M/'runs-after-apply.log') if (M/'runs-after-apply.log').exists()
        else 'Traceback 0줄' in txt(M/'n21after-u5.txt'), 'no new traceback')
require('칸 같음 N21 상태 0' in txt(M/'n21after-u5.txt'), 'N21 after function')
report['n21'] = {'cron_start_rows': 1, 'new_json_rows': 1, 'traceback': 0,
                  'rechecked': ['KRW-CVC'], 'retry_failure_net_404_observed': False,
                  'cron_start_and_observation_one_to_one_pid_proven': False,
                  'failure_404_BLAST_was_not_rechecked': True}

p0_lines = re.findall(r'^(\S+\.log) 줄 (\d+) · Connection is not available (\d+) · 누수 경고 (\d+)$', final, re.M)
require(len(p0_lines) == 5 and all(p0 == leak == '0' for _,_,p0,leak in p0_lines), 'five stored log counters')
report['p0'] = {'submitted_server_raw_counts': p0_lines, 'root_recounted_raw_large_logs': False}
report['limits'] = [
 'U3 timing missed; later logs cannot prove state at the missed point.',
 'Server jars and large logs were not downloaded or reread by this review.',
 'Server/local jar hash difference cause is not proven to be only metadata.',
 'U3 extra permission added around 13:51 in this conversation; full delay cause remains unproven.',
 'Prior container tail after ~12:42:53 to recreation 13:48:42 is not in submitted app logs.',
 'N15-N20 and O1/O3 abnormal input natural behavior remains unobserved.',
 '11-09 outage/218-minute record gap cause, CryptoPanic incident and phone receipt remain open.'
]
(HERE/'review.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'result':'PASS', 'manifests': report['manifests'],
                  'server_copies':len(copies), 'blocks':len(block_results),
                  'outputs':len(outputs),'u3_delay_seconds':round(u3_delay,3)}, ensure_ascii=False))
