"""Audit fixed local Git evidence and shell predicates. No server or database calls."""
import ast
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import tarfile
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SRC = 'c584166bc3eebc217bba0b910a393933126fec08'
BASE = 'ce6df2fa2b1b4a0b089576bc700fb867a466686d'
PREP = 'docs/evidence/v172u2c-20261116/'
RUN = 'docs/evidence/v172run-20261116/'
D4 = 'docs/evidence/v172d4-20261116/'
BASH = 'C:/Program Files/Git/bin/bash.exe'
checks = {}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.run(['git', '-c', 'core.quotepath=false', '-C', str(ROOT), *args], capture_output=True, check=True).stdout


def blob(path, commit=SRC):
    return git('show', commit + ':' + path)


def check(name, ok, **detail):
    checks[name] = dict(ok=bool(ok), **detail)
    assert ok, (name, detail)


def sums(data):
    result = {}
    for line in data.decode('utf-8-sig').splitlines():
        match = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        assert match and match[2] not in result, line
        result[match[2]] = match[1]
    return result


for folder, count in [(RUN, 31), (PREP, 38),
                      ('docs/evidence/review-v172-u2/', 4),
                      ('docs/evidence/review-v172-u2/tail-record/', 1),
                      ('docs/evidence/review-ce6df2fa/', 4)]:
    manifest = sums(blob(folder + 'SHA256SUMS'))
    bad = [p for p, h in manifest.items()
           if sha(blob(folder + p)) != h or sha((ROOT / (folder + p)).read_bytes()) != h]
    check('manifest_' + folder, len(manifest) == count and not bad, count=len(manifest), mismatch=bad)

check('head_origin', git('rev-parse', 'HEAD').strip() == git('rev-parse', 'origin/main').strip() == SRC.encode())
added = git('diff-tree', '--no-commit-id', '--name-only', '-r', SRC).decode().splitlines()
check('supplement_only_four_raw_files', len(added) == 4 and all(p.startswith(RUN + 'out/') for p in added), paths=added)
changed = git('diff', '--name-only', BASE, SRC).decode().splitlines()
check('documentation_only_since_execution_source', all(p.startswith('docs/') for p in changed), count=len(changed))


def exp(text):
    return ast.literal_eval(re.search(r'EXP = (\{.*?^\})', text, re.M | re.S)[1])


extract = blob(PREP + 'runner/extract.py').decode()
runner = blob(PREP + 'runner/run.sh').decode()
expected = exp(extract)
check('runner_thirteen_fixed_hashes', len(expected) == 13 and expected == exp(runner))
check('u2c_fixed_hash', expected['U2확인'] == '1c9eab3582d8574371501ebb1cc693408bd822f66f752261ad0c8997ddbb7cc2')

# Execute only the local extractor. The temporary directory contains our own files.
with tempfile.TemporaryDirectory(prefix='codex-review-c584-') as temporary:
    temp = pathlib.Path(temporary).resolve()
    temp.relative_to(pathlib.Path(tempfile.gettempdir()).resolve())
    extractor_path = temp / 'extract.py'
    extractor_path.write_bytes(blob(PREP + 'runner/extract.py'))
    destination = temp / 'blocks'
    process = subprocess.run([sys.executable, '-I', '-B', '-X', 'utf8', str(extractor_path), str(destination), SRC], capture_output=True)
    check('actual_local_extraction', process.returncode == 0, exit_code=process.returncode,
          stdout=process.stdout.decode('utf-8', 'replace'), stderr=process.stderr.decode('utf-8', 'replace'))
    blocks = {name: (destination / (name + '.sh')).read_bytes() for name in expected}
    report = json.loads((destination / 'blocks.json').read_text(encoding='utf-8'))
    check('extracted_thirteen_hashes', all(sha(blocks[n]) == h == report[n]['sha256'] and report[n]['expected_match'] for n, h in expected.items()))
    for name, data in blocks.items():
        process = subprocess.run([BASH, '--noprofile', '--norc', '-n'], input=data, capture_output=True)
        check('bash_syntax_' + name, process.returncode == 0, stderr=process.stderr.decode('utf-8', 'replace'))

for name in ('F', 'G'):
    path = 'docs/LIMITED_LIVE_TEST_RUNBOOK.md' if name == 'F' else 'docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md'
    check('original_file_preserved_' + name, blob(path) == blob(path, BASE))
for name in expected.keys() - {'F', 'G', 'U2확인'}:
    check('original_block_preserved_' + name, blocks[name] == blob(D4 + 'd4/blocks-d4/' + name + '.sh', BASE))
reference = blob(PREP + 'u2c/U2-교정.sh')
check('reference_exact_single_literal_change', reference == blocks['U2'].replace(b'= "96:t:$V96SUM" ]', b'= "96:true:$V96SUM" ]'), sha256=sha(reference))

u2c = blocks['U2확인'].decode()
code = '\n'.join(line for line in u2c.splitlines() if not line.lstrip().startswith('#'))
sql = re.search(r'V96H=\$\(\$PSQL -At -c "([^"]+)"\)', code)[1]
check('one_snapshot_count_and_null_content', sql == "SELECT count(*) || '|' || coalesce(string_agg(version || ':' || success || ':' || coalesce(checksum::text, 'NULL'), ',' ORDER BY installed_rank), '-') FROM flyway_schema_history WHERE version::int >= 95")
check('p0_before_state_queries', all(code.index('holdobs ') < code.index(token) for token in ('git rev-parse HEAD', 'same3 ', 'isimg ', 'docker inspect', 'sid203 ||', 'v96 ||', 'V96H=$(')))
check('p0_fail_closed', '[ -s "$M/app-u2c-$TS.log" ]' in code and '[ "$HP" != 1 ]' in code and '[ "$HP" = 0 ] || [ "$HP" = 3 ]' in code)
check('tail_records_after_verification', code.index('diff <(grep -v') < code.index('cp "$M/svc-u2c-') < code.index('cmp -s') < code.index('> "$M/ok-U2"'))
check('original_tail_names_checked_twice', code.count('[ ! -e "$M/svc-deploy.txt" ]') == 2 and code.count('[ ! -e "$M/git-deploy.txt" ]') == 2)
check('completion_only_last_statement', code.splitlines()[-2].startswith("printf 'V172D4 U2확인") and code.count('> "$M/ok-U2"') == 1)
forbidden = re.findall(r'\$DC\s+(?:up|build|restart|stop|start|rm|down|pull|create)\b|docker\s+(?:tag|run|build|restart|stop|start|rm)\b|git\s+(?:checkout|reset|fetch|pull|switch|clean)\b|\b(?:INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE)\b', code)
check('no_product_mutation_commands_in_confirmation', not forbidden, matches=forbidden)
check('runner_continuation_only', 'case "$n" in U2확인|U3|UF2|U4|U되돌림|U뒤관측)' in runner and '[ "$mode" = deploy ]' in runner and 'rc=${PIPESTATUS[0]}' in runner and runner.rstrip().endswith('exit "$rc"'))

pg = blob(PREP + 'pg/pg-v96-r3-out.txt').decode().replace('\r\n', '\n')
pg_parts = re.split(r'(?m)^블록 [0-9a-f]{16} · SQL: ', pg)[1:]
old_rows = re.findall(r'^.+?\s+\[(.*?)\] (통과|멈춤)$', pg_parts[0], re.M)
new_rows = re.findall(r'^.+?\s+\[(.*?)\] (통과|멈춤)$', pg_parts[1], re.M)
check('submitted_actual_pg_twelve_inputs', len(old_rows) == len(new_rows) == 12 and sum(r[1] == '통과' for r in old_rows) == 5 and sum(r[1] == '통과' for r in new_rows) == 1 and sql in pg, old_pass=5, new_pass=1)
predicate = next(line for line in code.splitlines() if line.startswith('[ "$V96H" = '))
shell = 'set -u\nV96SUM=-424836241\nstop() { exit 1; }\n'
for value, verdict in new_rows:
    # These values are only numbers, separators, NULL and boolean literals.
    assert re.fullmatch(r'[0-9|:a-zA-Z,\-]+', value), value
    shell += "V96H='" + value + "'; ( " + predicate + ' ) >/dev/null 2>&1; printf "%s\\n" "$?"\n'
process = subprocess.run([BASH, '--noprofile', '--norc'], input=shell.encode(), capture_output=True)
statuses = [int(value) for value in process.stdout.splitlines()]
check('actual_shell_history_predicate', statuses == [0 if verdict == '통과' else 1 for _, verdict in new_rows], statuses=statuses)

for name, pass_count, fail_count in [('r2/scen-u2c-run2.txt', 158, 4), ('r3/scen-u2c-run3.txt', 187, 0)]:
    raw = blob(PREP + 'standin/' + name)
    text = raw.decode('utf-8', 'replace')
    counts = (len(re.findall(r'^PASS ', text, re.M)), len(re.findall(r'^FAIL ', text, re.M)))
    check('submitted_counts_' + name, counts == (pass_count, fail_count), passed=counts[0], failed=counts[1], decode_replacements=text.count('\ufffd'))
selftest = blob(PREP + 'runner/runner-selftest.txt').decode()
check('runner_submitted_eighteen_cases', len(re.findall(r'^PASS ', selftest, re.M)) == 18 and '== 결과: PASS 18 · FAIL 0' in selftest)

archive = ROOT / (PREP + 'standin/r3/standin-runs-r3.tar.gz')
with tarfile.open(archive) as tar:
    members = [member for member in tar if member.isfile()]
    names = {member.name: member for member in members}
    check('archive_member_paths', len(names) == len(members) == 5471 and all(not pathlib.PurePosixPath(n).is_absolute() and '..' not in pathlib.PurePosixPath(n).parts for n in names), files=len(members))

    def data(path):
        return tar.extractfile(names[path]).read()

    cases = sorted({n.split('/')[1] for n in names})
    forbidden_logs = [n for n in names if n.endswith('/forbidden.log')]
    check('archive_forbidden_logs_empty', len(forbidden_logs) == len(cases) and all(not data(n) for n in forbidden_logs), cases=len(cases))
    prefix = 'runs/u2e-c01/'
    home = prefix + 'home/maint-v172/'
    tail = [n for n in names if n.startswith(home + 'svc-u2c-')]
    git_tail = [n for n in names if n.startswith(home + 'git-u2c-')]
    check('archive_positive_tail_bytes', len(tail) == len(git_tail) == 1 and data(tail[0]) == data(home + 'svc-deploy.txt') and data(git_tail[0]) == data(home + 'git-deploy.txt'))
    expected_rc = {'U0': 0, 'U1': 0, 'U2': 1, 'U2확인': 0, 'U2확인-2': 1, 'U3': 0, 'UF2': 0, 'U4': 0, 'U뒤관측': 0}
    for name, rc in expected_rc.items():
        output = data(prefix + 'out/out-' + name + '.txt').decode()
        check('archive_positive_' + name, re.search(r'\[대역 끝\] rc=(\d+)', output)[1] == str(rc))
    check('archive_positive_completion', all(home + n in names for n in ('ok-U2', 'ok-U3-deploy', 'ok-UF2', 'ok-U4')))
    for case in cases:
        if case == 'u2e-c01':
            continue
        h = 'runs/' + case + '/home/maint-v172/'
        check('archive_negative_' + case, h + 'ok-U2' not in names and h + 'git-deploy.txt' not in names and ((h + 'svc-deploy.txt' in names) == (case == 'u2e-c44')))
    for case in ('c11', 'c12', 'c13', 'c14'):
        calls = data('runs/u2e-' + case + '/calls.log').decode().splitlines()
        after = calls[max(i for i, line in enumerate(calls) if 'logs --no-color' in line) + 1:]
        check('archive_p0_no_later_state_queries_' + case, not any('\tgit\t' in line or 'image inspect' in line or 'exec -T db' in line for line in after))

read = blob(RUN + 'diag/read-u2c-prep-2.txt').decode()
check('last_actual_read', '2026-11-16 15:19:00 KST' in read and '연결 획득 실패 0' in read and read.count('Restart=0 Status=running') == 5 and 'HEAD 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d 변경 0' in read)
check('original_u2_exit_preserved', 'rc=1' in blob(RUN + 'out/out-U2-deploy.txt').decode() and 'rc=1' in blob(RUN + 'out/exit-codes.txt').decode())

(OUT / 'checks.json').write_text(json.dumps({'source': SRC, 'base': BASE, 'checks': checks}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'checks': len(checks), 'failed': sum(not value['ok'] for value in checks.values())}, ensure_ascii=False))
