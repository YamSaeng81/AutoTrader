"""Inspect local submitted execution records only. Does not contact the server."""
import hashlib
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SRC = 'ce6df2fa2b1b4a0b089576bc700fb867a466686d'
BASE = 'docs/evidence/v172d4-20261116/'
DIAG = ROOT / 'docs/evidence/v172run-20261116/diag'

def blob(p):
    return subprocess.run(['git', '-C', str(ROOT), 'show', SRC + ':' + p], check=True, capture_output=True).stdout

checks = {}
records = {}
def check(n, v):
    checks[n] = bool(v)
    assert v, n

u2 = blob(BASE + 'd4/blocks-d4/U2.sh').decode('utf-8')
stub = blob(BASE + 'standin/harness/stubs/docker').decode('utf-8')
check('expected_t_in_original_block', '"96:t:$V96SUM"' in u2)
check('concatenation_query', "version || ':' || success || ':' || checksum" in u2)
check('wrong_stub_response', 'echo "96:t:${V96CK:--424836241}"' in stub)
for name in ('u2-diag-1.txt', 'u2-diag-2.txt', 'post-u1-read.txt', 'post-u2-read.txt', 'post-u2-read-5min.txt'):
    b = (DIAG / name).read_bytes()
    records[name] = dict(sha256=hashlib.sha256(b).hexdigest(), bytes=len(b))
d = (DIAG / 'u2-diag-2.txt').read_text(encoding='utf-8')
p = (DIAG / 'post-u2-read.txt').read_text(encoding='utf-8')
p5 = (DIAG / 'post-u2-read-5min.txt').read_text(encoding='utf-8')
check('actual_concatenation_true', '96:true:-424836241' in d)
check('original_failed_check_precedes_tail', u2.index('"96:t:$V96SUM"') < u2.index('svc | tee') < u2.index('touch "$M/ok-U2"'))
check('new_backend_reported', '2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f' in p)
check('actual_started_at', 'StartedAt=2026-11-16T05:16:14.129686133Z' in p)
check('original_deploy_time', '2026-11-16 14:16:12 KST' in p)
check('p0_and_leak_zero_at_1422', '연결 획득 실패 0 · 누수 경고 0' in p5)
check('catchup_observed_at_1422', '시작 Catchup 완료 1' in p5)
check('failed_initial_diagnostic_retained', 'column "|" does not exist' in (DIAG / 'u2-diag-1.txt').read_text(encoding='utf-8'))
(OUT / 'checks.json').write_text(json.dumps(dict(source=SRC, scope='Local source and uncommitted diagnostic snapshots. No live queries.', checks=checks, records=records), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(checks=len(checks), failed=sum(not x for x in checks.values())), ensure_ascii=False))
