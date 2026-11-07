"""v1.134: preserved evidence and fixed-source review only; no app/DB/network calls.

Run before the next developer result, against dbac6053. Later document changes
must be reviewed with a new version rather than rerunning this versioned check.
"""
import ast
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
HEAD = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip()
assert HEAD.startswith('dbac6053'), HEAD
RUNTIME = '1899b49f633f8aefdc66b15c42debdaba8b3852c'


def sha(b):
    return hashlib.sha256(b).hexdigest()


def blob(ref, path):
    return subprocess.check_output(['git', 'show', f'{ref}:{path}'], cwd=ROOT)


def manifest(folder):
    base = ROOT / 'docs/evidence' / folder
    entries = []
    for line in (base / 'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip():
            continue
        m = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        assert m, (folder, line)
        expected, name = m.groups()
        p = base / name
        rel = p.relative_to(ROOT).as_posix()
        assert sha(p.read_bytes()) == expected, rel
        entries.append((rel, expected))
    # Read all committed blobs in one git process, preserving their exact bytes.
    data = subprocess.check_output(
        ['git', 'cat-file', '--batch'],
        input=''.join(f'{HEAD}:{p}\n' for p, _ in entries).encode(), cwd=ROOT)
    offset = 0
    missing = []
    for path, expected in entries:
        end = data.index(b'\n', offset)
        header = data[offset:end].split()
        if header[-1] == b'missing':
            missing.append(path)
            offset = end + 1
            continue
        assert len(header) == 3 and header[1] == b'blob', (path, header)
        size = int(header[2])
        body = data[end + 1:end + 1 + size]
        assert sha(body) == expected, path
        offset = end + 2 + size
    assert offset == len(data)
    return {'files': len(entries), 'working_bytes_match': True,
            'committed_blobs_matched': len(entries) - len(missing),
            'committed_blobs_missing': missing}


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def blocks(text):
    return [sha(m.encode()) for m in
            re.findall(r'^```bash\r?\n(.*?)^```\s*$', text, re.M | re.S)]


def console(run):
    t = read(f'docs/evidence/w3-pg-rehearsal-1899b49/{run}-console.txt')
    summary = [s for s in t.splitlines() if s.startswith('PASS ')]
    notes = [s for s in summary if s.startswith('PASS (관측) ')]
    assert len(summary) == 41 and 'exit=0' in t
    return {'reported_total': len(summary), 'assertions': len(summary) - len(notes),
            'observational_notes': len(notes), 'exit': 0}


folders = ['w1-manifest-audit', 'w2-n05-links-1899b49', 'w4-n06-prep',
           'w3-pg-rehearsal-1899b49', 'n03-1106-2200',
           'k26-result-1899b49']
result = {'head': HEAD, 'runtime_source': RUNTIME,
          'manifests': {name: manifest(name) for name in folders},
          'rehearsal': {run: console(run) for run in ('run2', 'run5')}}

base = ROOT / 'docs/evidence/w3-pg-rehearsal-1899b49'
for run in ('run2', 'run5'):
    a = (base / run / 'final-before.json').read_bytes()
    b = (base / run / 'final-after.json').read_bytes()
    assert json.loads(a) == json.loads(b)
    result['rehearsal'][run]['final_record_equal'] = True
    assert (base / 'harness-final' / f'rehearse_{"sma20" if run == "run2" else "w3"}.py').read_bytes() == (
        ROOT / 'scripts/restart-test' / f'rehearse_{"sma20" if run == "run2" else "w3"}.py').read_bytes()

ledger = (base / 'run5/F5-4-ledger.out').read_text(encoding='utf-8')
assert re.search(r'STOPPED\s*\|\s*19142\.78\s*\|\s*19142\.78000000\s*\|\s*0\.00000000', ledger)
result['rehearsal']['run5']['ledger_balance_diff'] = '0.00000000'
result['rehearsal']['run5']['exchange_krw_comparison_diff'] = '0.22 (F3 +/-1 tolerance)'
log5 = (base / 'run5/app-02-w3-run.log').read_text(encoding='utf-8')
log2 = (base / 'run2/app-03-sma20-run-after-crash.log').read_text(encoding='utf-8')
assert 'trade_price=-5.0' in log5 and '실시간 손절 발동 (WS)' in log5
assert '18:07:46.840' in log2 and '18:07:47.269' in log2
assert '체결 수량이 있는데 반영값이 없는 실자금 주문 1건' in log2
result['observations'] = {'O1_raw_invalid_price_warn': True,
                          'O2_unknown_valuation_to_settlement_log_ms': 429,
                          'O3_rest_stop_loss_labelled_ws': True}

sources = {}
for name in ('LiveTradingService', 'DynamicTradingService', 'PoolLossGuard'):
    p = f'web-api/src/main/java/com/cryptoautotrader/api/service/{name}.java'
    b = blob(RUNTIME, p)
    text = b.decode('utf-8')
    findings = []
    for i, line in enumerate(text.splitlines(), 1):
        if any(s in line for s in ('trade_price={}', '강제 복구 실패 (coin={})',
                                   '실시간 손절 발동 (WS)', '실시간 손절(WS)',
                                   '체결 수량이 있는데 반영값이 없는 실자금 주문',
                                   'o.applied_executed_funds IS NULL')):
            findings.append({'line': i, 'text': line.strip()})
    sources[name] = {'sha256': sha(b), 'findings': findings}
result['fixed_source'] = sources

nbase = ROOT / 'docs/evidence/n03-1106-2200'
server_list = (nbase / 'server-files.txt').read_text(encoding='utf-8')
for name in ('20261106-2200.txt', '20261106-2200.txt.msg',
             '20261106-2200.txt.raw', 'last_send'):
    expected = re.search(r'^([0-9a-f]{64})  ' + re.escape(name) + r'$', server_list, re.M).group(1)
    assert sha((nbase / name).read_bytes()) == expected, name
report = (nbase / '20261106-2200.txt').read_text(encoding='utf-8')
msg = (nbase / '20261106-2200.txt.msg').read_text(encoding='utf-8')
assert report.startswith(msg)
assert len([s for s in report[len(msg):].splitlines() if s.strip()]) == 1
raw = (nbase / '20261106-2200.txt.raw').read_text(encoding='utf-8')
db = raw.split('--- DB (종료 0)', 1)[1].split('---', 1)[0]
db_rows = [s for s in db.splitlines() if '|' in s and not s.startswith('END|')]
assert len(db_rows) == 32 and all(len(s.split('|')) == 2 for s in db_rows)
assert len({s.split('|')[0] for s in db_rows}) == 32
assert '미분류 ERROR 1건' in report and 'Connection reset' in report
assert 'B1 true' in report and '필요 11-06 20:00' in report and '20/20' in report
assert '보고 전송 성공' in (nbase / 'last_send').read_text(encoding='utf-8')
result['n03'] = {'server_copy_hashes': 4, 'db_keys_valid_unique': 32,
                 'message_equals_report_before_send_result': True,
                 'planned_boot_and_coverage': True, 'unclassified_news_error': 1,
                 'phone_receipt': 'unconfirmed', 'full_server_log_recounted': False}

for filename in ('rehearse_sma20.py', 'rehearse_w3.py'):
    ast.parse(read(f'scripts/restart-test/{filename}'))
# Execute only existing pure predicate expressions with synthetic local values.
# No module import: importing the rehearsal would start an app/database run.
tree = ast.parse(read('scripts/restart-test/rehearse_w3.py'))
calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call)
         and isinstance(n.func, ast.Name) and n.func.id == 'check'
         and n.args and isinstance(n.args[0], ast.Constant)]


def predicate(prefix, values):
    call = next(n for n in calls if n.args[0].value.startswith(prefix))
    expression = compile(ast.Expression(call.args[1]), '<existing-predicate>', 'eval')
    return bool(eval(expression, {'__builtins__': {'len': len, 'set': set}}, values))


f53out = (base / 'run5/F5-3-w4exec.out').read_text(encoding='utf-8')
predicates = {
    'restart_409_no_change': predicate('③  RUNNING 재시작', {'d': [], 'again_txt': 'HTTP 409'}),
    'restart_200_no_change_false_positive': predicate('③  RUNNING 재시작', {'d': [], 'again_txt': 'HTTP 200'}),
    'f53_exit0_valid_text': predicate('⑥  F5-3(W4', {'out': f53out, 'rc': 0}),
    'f53_exit1_valid_text_false_positive': predicate('⑥  F5-3(W4', {'out': f53out, 'rc': 1}),
    'f51_shift_exit0_matching_ends': predicate('⑥  F5-1 주입 뒤',
        {'out': '', 'rc': 0, 'ends': ['same', 'same'], 'xval': lambda *_: 't'}),
    'f51_shift_exit1_matching_ends_false_positive': predicate('⑥  F5-1 주입 뒤',
        {'out': '', 'rc': 1, 'ends': ['same', 'same'], 'xval': lambda *_: 't'}),
}
assert all(predicates.values())
result['harness_predicate_reproduction'] = predicates
plan = read('docs/LIMITED_LIVE_TEST_PLAN.md')
ops = read('docs/OPS_DAILY_CHECK.md')
assert len(blocks(plan)) == 149 and len(blocks(ops)) == 7
result['protected'] = {'plan_bash': blocks(plan), 'ops_bash': blocks(ops),
                       'prereg_sha256': sha((ROOT / 'docs/LIVE_ENTRY_PREREG.md').read_bytes()),
                       'harness_ast_parse': 2}
result['executed'] = {'server': False, 'database': False, 'docker': False,
                      'gradle': False, 'product_tests': False, 'commit_push': False}
(OUT / 'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: v for k, v in result.items() if k not in ('protected', 'fixed_source')},
                 ensure_ascii=False, indent=2))
