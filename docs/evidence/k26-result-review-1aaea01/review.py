"""Review preserved #26 results; read local files/Git only, never execute deployment blocks."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = '1aaea01d2c53a5f77e4017f5f53dc1e844c482bb'
SOURCE = 'de5a7e4d0d5d4f6ac6820c57ce5a5dce15e8642a'
OLD = 'ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8'
NEW = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
OLD_IMAGE = 'sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6'
IMAGE = 'sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1'
CREATED = '2026-11-06T04:36:36.916419403Z'
STARTED = '2026-11-06T04:36:38.159366843Z'
K = ROOT / 'docs/evidence/k26-result-1899b49'
M = K / 'maint-n20'


def read(p):
    return p.read_text(encoding='utf-8')


def sha(b):
    return hashlib.sha256(b).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def hashes(p):
    result = {}
    for line in read(p).splitlines():
        match = re.match(r'^([0-9a-f]{64})\s+\*?(.+)$', line)
        if match:
            name = match[2].removeprefix('./')
            assert name not in result, (p, name)
            result[name] = match[1]
    return result


paths = []
counts = {}
for name, count in [('k26-result-1899b49', 107), ('k26-accept-de5a7e4', 11)]:
    folder = ROOT / 'docs/evidence' / name
    rows = hashes(folder / 'SHA256SUMS')
    assert len(rows) == count, (name, len(rows))
    for name_in_folder, digest in rows.items():
        p = folder / name_in_folder
        assert p.is_file() and sha(p.read_bytes()) == digest, p
        paths.append((p, digest))
    counts[name] = count
requests = ''.join(REF + ':' + p.relative_to(ROOT).as_posix() + '\n' for p, _ in paths).encode()
batch = subprocess.check_output(['git', 'cat-file', '--batch'], input=requests, cwd=ROOT)
pos = 0
committed_counts = {name: 0 for name in counts}
missing = []
for p, digest in paths:
    end = batch.index(b'\n', pos)
    head = batch[pos:end].split()
    if head[-1] == b'missing':
        missing.append(p)
        pos = end + 1
        continue
    assert head[1] == b'blob', (p, head)
    size = int(head[2])
    data = batch[end + 1:end + 1 + size]
    assert sha(data) == digest and data == p.read_bytes(), p
    pos = end + 1 + size + 1
    committed_counts[p.relative_to(ROOT / 'docs/evidence').parts[0]] += 1
assert pos == len(batch)
assert {p.relative_to(K).as_posix() for p in missing} == {
    'out/final-state.txt', 'out/local-copy.sha256', 'out/operator-confirm.txt',
    'out/server-list-sha.txt', 'out/u1-present.txt',
}
# Preserve these existing bytes in a directory that is not hidden by the global out/ ignore.
# This is a supplement prepared by the reviewer, not a claim that the files existed in REF.
supplement = OUT / 'preserved-out'
supplement.mkdir(exist_ok=True)
for p in missing:
    target = supplement / p.name
    if target.exists():
        assert target.read_bytes() == p.read_bytes(), target
    else:
        target.write_bytes(p.read_bytes())

# Server copies: full local bytes, not a line-ending-normalized comparison.
local = hashes(K / 'out/local-copy.sha256')
server = hashes(K / 'out/server-list-sha.txt')
assert len(local) == 77 and len(list(M.iterdir())) == 77
for name, digest in local.items():
    assert server[name] == digest == sha((M / name).read_bytes()), name
server_only = sorted(set(server) - set(local))
assert set(server_only) == {'app-after30.log', 'app-deploy.log', 'app-news-20261106-1445.log',
                            'backend-before.log', 'build.log', 'app-new.jar', 'app-old.jar'}
assert len(hashes(M / 'jar-sha.txt')) == 2

# Fixed execution source and submitted runner have not been replaced by a later document revision.
source = git('show', SOURCE + ':docs/LIMITED_LIVE_TEST_PLAN.md').decode().replace('\r\n', '\n')
blocks = re.findall(r'^```bash\n(.*?)^```', source, re.M | re.S)
assert len(blocks) == 149
meta = json.loads(read(K / 'blocks/blocks.json'))
assert meta['source'] == SOURCE
approved = ROOT / 'docs/evidence/n20-k26-final-1899b49'
for name, row in meta.items():
    if not isinstance(row, dict):
        continue
    p = K / 'blocks' / (name + '.sh')
    data = p.read_bytes()
    assert data.decode() == blocks[row['index']] and sha(data) == row['sha256'], name
    if name.startswith('U'):
        assert data == (approved / 'blocks-k26' / p.name).read_bytes(), name
assert sum(isinstance(v, dict) for v in meta.values()) == 10
assert (K / 'blocks/run.sh').read_bytes() == (approved / 'runner/run.sh').read_bytes()

# The candidate has exactly the accepted two changed Java paths and no settings/migrations.
assert git('rev-parse', NEW + '^').decode().strip() == OLD
changed = git('diff', '--name-only', '-z', OLD, NEW).decode().strip('\0').split('\0')
assert len(changed) == 2 and all(p.endswith('.java') for p in changed), changed
assert sum(p.endswith('/HeldMarkRefresher.java') for p in changed) == 1
assert git('rev-parse', 'origin/candidate/n20-held-mark-ee509cf').decode().strip() == NEW

for name in ['U0', 'U1', 'U2', 'U3', 'U4', 'U뒤관측']:
    text = read(K / ('out-' + name + '-deploy.txt'))
    assert re.findall(r'\[로컬 끝\].*rc=(\d+)', text) == ['0'], name
    assert '출처=' + SOURCE in text and '실행 틀 거절' not in text and '🔴 멈춤:' not in text, name
assert not list(K.glob('out-*-rollback*'))
assert not list(M.glob('*rollback*'))
for name in ['ok-U0', 'ok-U1', 'ok-U2', 'ok-U3-deploy', 'ok-U4']:
    assert (M / name).is_file()
assert read(M / 'git-before.txt') == OLD + '\n(끝)\n'
assert read(M / 'git-deploy.txt') == NEW + '\n(끝)\n'
assert read(M / 'image-before.txt').strip() == OLD_IMAGE
assert read(M / 'image-new.txt').strip() == IMAGE
assert read(M / 't-deploy.utc').strip() == '2026-11-06T04:36:36Z'
assert read(M / 't-before.txt').strip() == '2026-11-06 13:32:33 KST'
assert read(M / 't-build.txt').strip() == '2026-11-06 13:32:53 KST'
assert read(M / 't-after30.txt').strip() == '2026-11-06 14:07:17 KST'

expected_diff = [
    '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher$Book.class',
    '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/HeldMarkRefresher.class',
    '(끝) 881 881',
]
assert read(M / 'jar-diff.txt').splitlines() == expected_diff
assert '89b4ca170921776a7ca2aa659da1bda26b0e958ab82801f8a5c2efafd7847ec9' in read(M / 'jar-sha.txt')
assert '10bf86285c6e5eaf7cbe740f060eff852cc943128e521eb3e2c0a42bd2db69a5' in read(M / 'jar-sha.txt')
old_jar = read(ROOT / 'docs/evidence/k25-result-ee509cf/maint-n19/jar-sha.txt')
assert '89b4ca170921776a7ca2aa659da1bda26b0e958ab82801f8a5c2efafd7847ec9' in old_jar

confirm = read(K / 'out/operator-confirm.txt')
assert '13:36:32' in confirm and '재기동 진행' in confirm and IMAGE in confirm
presentation = read(K / 'out/u1-present.txt')
assert 'autotrader-backend:ee509cf ' + OLD_IMAGE in presentation
assert 'autotrader-backend:7a94472 sha256:e51b512a' in presentation
assert '실행 backend Image=' + OLD_IMAGE in presentation
final = read(K / 'out/final-state.txt')
assert IMAGE in final and NEW in final and 'Created=' + CREATED in final
assert 'StartedAt=' + STARTED in final and 'RestartCount=0 Status=running' in final

svc = {f: read(M / f).splitlines() for f in ['svc-before.txt', 'svc-deploy.txt', 'svc-after30.txt']}
assert all(len(v) == 5 for v in svc.values())
assert svc['svc-deploy.txt'] == svc['svc-after30.txt']
assert svc['svc-before.txt'][1:] == svc['svc-deploy.txt'][1:]
assert svc['svc-deploy.txt'][0] == 'backend ' + IMAGE + ' ' + STARTED
assert len({(M / f).read_bytes() for f in ['s-before.txt', 's-deploy.txt', 's-after30.txt']}) == 1
assert read(M / 's-before.txt').strip() == '66 | 63 | 3 | -'
q = [read(M / f).split('== Q3', 1)[1].split('== Q10', 1)[0]
     for f in ['q-before.txt', 'q-deploy.txt', 'q-after30.txt']]
assert q[0] == q[1] == q[2]
for name in ['errors-deploy.txt', 'errors-after30.txt', 'errors-all-deploy.txt', 'errors-all-after30.txt',
             'errors-news-deploy.txt', 'errors-news-after30.txt', 'errors-news-20261106-1445.txt']:
    assert (M / name).read_bytes() == b'', name
assert read(M / 'btc-before.txt').strip() == 'true|11-06 12:00|11-06 12:00|20/20'
assert read(M / 'btc-deploy.txt') == read(M / 'btc-before.txt')
assert read(M / 'btc-after30.txt').strip() == 'true|11-06 13:00|11-06 13:00|20/20'
assert sha((M / 'crontab-before.txt').read_bytes()) == 'f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b'

u3 = read(K / 'out-U3-deploy.txt')
assert 'No migration necessary.' in u3 and 'Started CryptoAutoTraderApplication in 15.248 seconds' in u3
assert '열린 실자금 포지션 없음' in u3 and '아직 판정 불가 0' in u3
u4 = read(K / 'out-U4-deploy.txt')
assert '✅ U4 통과' in u4 and '아직 판정 불가 0' in u4
# U4 reaches the final marker only after sid203/install/cron/ledger/service invariants in the fixed block.
for text in [blocks[145], blocks[146]]:
    assert 'sid203 || stop' in text
assert all(token in blocks[146] for token in ['crontab -l | cmp', 'ledger-files-before.sha', '$LSUM', 'svc-after30'])
assert '관측 0회' in read(M / 'alerts-after30.txt') and '이 구간에 관측이 없다' in read(M / 'alerts-after30.txt')
supp = read(K / 'out-alerts-supp-deploy.txt')
assert all(token in supp for token in ['관측 1회', '11-06 05:19', 'KRW-BLAST', '🟢 21 / ⚪ 0 / 🔴 1', '종료 1'])
baseline = read(M / 'alerts-before.txt')
assert '관측 24회' in baseline and len(re.findall(r'^🔴 .*1종: KRW-BLAST$', baseline, re.M)) == 24

for suffix in ['deploy', 'after30', '20261106-1445']:
    text = read(M / ('holdobs-' + suffix + '.txt'))
    assert 'HOLD 기준선 질의 장기 대여 경고 0건' in text
    assert '그 밖의 장기 대여 경고 0건' in text and '연결 획득 시간 초과 줄 0건' in text
    for tag in ['entry', 'n17', 'n19', 'n20']:
        assert '미관측' in read(M / (tag + 'obs-' + suffix + '.txt')), (tag, suffix)
raw_counts = read(K / 'out/server-list-sha.txt').split('\n\n', 1)[0].splitlines()
assert len(raw_counts) == 4 and all('Connection is not available 0 · 누수 경고 0' in line for line in raw_counts)
after = read(K / 'out-U뒤관측-deploy.txt')
assert len(re.findall(r'^\S+ \S+: 완료 [1-9]\d*·미완료 0', after, re.M)) == 6
assert 'cryptopanic CRYPTOPANIC(30분): 완료 0·미완료 2' in after
assert 'HTTP 403 ×2' in after and '짝 2' in after and '짝 미확인 0' in after
assert '뉴스 출처 ERROR 0줄' in after

# Reuse the already reviewed automatic report only because the preserved full-file hashes agree.
pre = read(K / 'pre/state.txt')
report = ROOT / 'docs/evidence/n03-1106-1000'
for name in ['last_send', '20261106-1000.txt']:
    assert sha((report / name).read_bytes()) + '  ' + name in pre
assert 'INSTALLED_SHA c669ccdc6e248ce8e55e9a7ac7405197d266fa18' in pre
for digest in ['ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc',
               '653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8',
               '02a8585021fb2a1a52d9b3b56b60e957cb71b9ca3c11bf819595df15cf334f1c']:
    assert digest in pre

result = {
    'review_ref': REF, 'manifests_worktree': counts, 'committed_blob_matches': committed_counts,
    'missing_in_result_commit': [p.relative_to(K).as_posix() for p in missing],
    'preserved_supplement': 'preserved-out/; identical existing bytes, to be included with next actual result',
    'server_copies': 77,
    'server_only_hash_manifest_files': server_only, 'server_only_jars': 2,
    'fixed_execution_source': SOURCE, 'fixed_section': 98, 'UB': 98, 'execution_blocks': 10,
    'production_changes': changed, 'operating_head': NEW, 'operating_image': IMAGE,
    'created': CREATED, 'started_at': STARTED, 'image_record': '~/maint-n20/image-new.txt',
    'server_jar_diff': expected_diff, 'N20_runtime_accepted': True,
    'operator_reply': 'developer-preserved note; root did not independently observe live user reply',
    'P0_counts': 'developer raw-log counts for 4 logs plus preserved holdobs outputs; raw logs remain server-only',
    'Q_comparison': 'Q3 through Q9 byte-equal; no assertion about all Q output or unprinted SID values',
    'SID_install_ledger_checks': 'fixed source assertions and successful stage completion; no root server reread',
    'unsecured_old_container_interval_kst': '13:32 baseline capture through 13:36 recreation; do not claim full nominal 10:00-22:00 coverage',
    'natural_N15_N16_N17_N19_N20': 'unobserved; not induced; runtime code delivery verified independently of natural triggering',
    'news_403': '2 fixed one-line HTTP403/FAILED pairs; existing unresolved condition, not recovery',
    'APENFT_WARN': 'developer report, existing UpbitRestClient outside two changed jar entries; full log stays server-only',
    'N13_improvement': 'zero lease/acquisition warnings within this short observed interval; no new speed ratio or final regular-cycle claim',
    'F_pins': 'update upon this acceptance; final S remains unfixed until 2026-11-30',
    'phone_receipt': 'unconfirmed',
    'not_executed': ['SSH/server/DB/Docker/network', 'deployment blocks/runner', 'product tests', 'Gradle', 'jar build'],
}
(OUT / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'worktree_manifests': counts, 'committed_blob_matches': committed_counts,
                  'missing_in_result_commit': len(missing), 'server_copies': 77, 'execution_blocks': 10,
                  'server_jar_changes': 2, 'N20_runtime_accepted': True}, ensure_ascii=False))
