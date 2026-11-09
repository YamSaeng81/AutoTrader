"""v1.136: preserved evidence and pure local tools only; no application/server calls."""
import collections
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SCRATCH = ROOT / 'core-engine/build/review-94317640'
SCRATCH.mkdir(parents=True, exist_ok=True)
BASE = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
FINAL = '1ec91046bf2bf0b7af0e6f05c04267a6c78efc21'
def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)
def sha(data):
    return hashlib.sha256(data).hexdigest()
def norm(data):
    return data.replace(b'\r\n', b'\n')
HEAD = git('rev-parse', 'HEAD').decode().strip()
assert HEAD.startswith('94317640'), HEAD

def manifest(name):
    folder = ROOT / 'docs/evidence' / name
    entries = []
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        assert match, (name, line)
        expected, filename = match.groups()
        path = folder / filename
        assert sha(path.read_bytes()) == expected, path
        entries.append((path.relative_to(ROOT).as_posix(), expected))
    data = subprocess.check_output(['git', 'cat-file', '--batch'], cwd=ROOT,
        input=''.join(f'{HEAD}:{p}\n' for p, _ in entries).encode('utf-8'))
    pos = 0
    missing = []
    for path, expected in entries:
        end = data.index(b'\n', pos)
        header = data[pos:end].split()
        if header[-1] == b'missing':
            missing.append(path)
            pos = end + 1
            continue
        assert header[1] == b'blob', (path, header)
        size = int(header[2])
        assert sha(data[end + 1:end + 1 + size]) == expected, path
        pos = end + 2 + size
    assert pos == len(data)
    return {'files': len(entries), 'committed_matches': len(entries)-len(missing), 'missing': missing}

folders = ['weekend-review-a762b8cb', 'v1135-h1-f53-btc', 'v1135-pg-provenance-note',
    'v1135-drift-isolation-1ec9104', 'v1135-h2-d2-1ec9104', 'n03-1109-1000', 'n03-1109-2200']
result = {'head': HEAD, 'runtime': BASE, 'candidate': FINAL,
          'evidence': {name: manifest(name) for name in folders}}
assert all(not entry['missing'] for entry in result['evidence'].values())

def xml_counts(path):
    counts = collections.Counter()
    selected = {}
    for file in sorted(path.rglob('*.xml')):
        for node in ET.parse(file).getroot().iter('testcase'):
            state = 'failure' if node.find('failure') is not None else 'error' if node.find('error') is not None else 'skipped' if node.find('skipped') is not None else 'pass'
            counts[state] += 1
            cls = node.get('classname', '').split('.')[-1]
            if cls in ('ExecutionDriftTrackerTest', 'LimitedTestExchangeRecoveryTest', 'ExchangeHealthRecoveryEventTest', 'ExchangeRecoveryRestartTest'):
                selected.setdefault(cls, []).append({'name': node.get('name'), 'state': state})
    return {'total': sum(counts.values()), **counts, 'selected': selected}

drift = ROOT / 'docs/evidence/v1135-drift-isolation-1ec9104'
run = drift / 'runs/r16-final-full-1ec9104'
result['full_suite'] = xml_counts(run / 'xml')
assert result['full_suite']['total'] == 1141 and result['full_suite']['pass'] == 1118 and result['full_suite']['skipped'] == 23
assert not result['full_suite'].get('failure') and not result['full_suite'].get('error')
assert (run/'head.txt').read_text().strip() == FINAL
assert (run/'status.txt').stat().st_size == 0
result['fixed_class'] = xml_counts(drift/'runs/r15-drift-fixed-class/xml')
result['failing_probe'] = xml_counts(drift/'runs/r14-drift-probe-0d142aa/xml')
assert result['fixed_class']['pass'] == 7 and result['failing_probe']['failure'] == 1
result['candidate_paths'] = git('diff', '--name-only', '-z', BASE, FINAL).decode().rstrip('\0').split('\0')
assert len(result['candidate_paths']) == 6
result['since_previous_candidate'] = git('diff', '--name-only', '-z', '0d142aa', FINAL).decode().rstrip('\0').split('\0')
assert result['since_previous_candidate'] == ['web-api/src/test/java/com/cryptoautotrader/api/service/ExecutionDriftTrackerTest.java']
fixed = git('show', f'{FINAL}:web-api/src/test/java/com/cryptoautotrader/api/service/ExecutionDriftTrackerTest.java')
assert norm(fixed) == norm((drift/'code/ExecutionDriftTrackerTest.fixed.java').read_bytes())

# Reclassify the stored ERROR lines with exactly the installed shell's ERE patterns.
installed = git('show', 'c669ccd:scripts/ops/daily_check.sh').decode('utf-8')
patterns = dict(re.findall(r"^  (P_[A-Z0-9_]+)='([^']*)'$", installed, re.M))
assert set(patterns) == {'P_POOL','P_CANDLE','P_404','P_IO','P_NEWS','P_BLAST'}
errors = (ROOT/'docs/evidence/n03-1109-1000/error_lines.txt').read_bytes()
lines = errors.decode('utf-8-sig').splitlines()
assert len(lines) == 2715 and all(' ERROR ' in line for line in lines)
bash = r'C:\Program Files\Git\bin\bash.exe'
classified = {}
for name, pattern in patterns.items():
    proc = subprocess.run([bash, '--noprofile', '--norc', '-c', 'grep -E -- "$1"', 'review', pattern], input=norm(errors), capture_output=True)
    assert proc.returncode in (0,1), proc.stderr
    classified[name] = proc.stdout.decode('utf-8').splitlines()
proc = subprocess.run([bash, '--noprofile', '--norc', '-c', 'grep -vE -- "$1"', 'review', '|'.join(patterns.values())], input=norm(errors), capture_output=True)
assert proc.returncode == 0
other = proc.stdout.decode('utf-8').splitlines()
assert len(other) == 377
groups = collections.Counter()
for line in other:
    message = line.split(' ERROR ',1)[1].strip()
    logger, text = message.split(' - ',1)
    text = re.sub(r'KRW-[A-Z0-9]+', 'KRW-X', text)
    text = re.sub(r'연속 \d+회', '연속 N회', text)
    groups[(logger, text)] += 1
result['morning_error_classification'] = {
    'total': len(lines), 'categories': {name: len(values) for name, values in classified.items()},
    'unclassified': len(other), 'unclassified_groups': [{'logger': k[0], 'message': k[1], 'count': v} for k,v in groups.most_common()]}
assert sum(len(values) for values in classified.values()) + len(other) == len(lines)
(OUT/'error-classification.json').write_text(json.dumps(result['morning_error_classification'], ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

# The runtime is a candidate branch, not main. The limited-trial guard must be checked there.
source_path = 'web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java'
runtime_source = git('show', f'{BASE}:{source_path}').decode('utf-8')
start = runtime_source.index('public LiveTradingSessionEntity restartAfterExchangeRecovery(')
end = runtime_source.index('private LiveTradingSessionEntity startLocked(', start)
recovery = runtime_source[start:end]
assert 'LIMITED_TEST_STRATEGIES.contains(session.getStrategyType())' in recovery and 'return null;' in recovery
assert 'Set.of("DAILY_SMA20")' in runtime_source
final_source = git('show', f'{FINAL}:{source_path}').decode('utf-8')
assert recovery == final_source[final_source.index('public LiveTradingSessionEntity restartAfterExchangeRecovery('):final_source.index('private LiveTradingSessionEntity startLocked(')]
(OUT/'runtime-recovery-excerpt.txt').write_text(f'Source {BASE}:{source_path}\n\n'+recovery, encoding='utf-8')
result['limited_trial_recovery_guard'] = {'runtime_guard': True, 'candidate_identical': True,
    'xml': result['full_suite']['selected']['LimitedTestExchangeRecoveryTest']}

# New pure tools may be checked locally; no application tests, DB or long stand-in rerun.
proc = subprocess.run([sys.executable,'-B','-X','utf8','scripts/restart-test/test_judge.py'], cwd=ROOT, capture_output=True)
(OUT/'judge-47.txt').write_bytes(proc.stdout+proc.stderr)
assert proc.returncode == 0, proc.stdout[-1000:]
result['judge_exit'] = proc.returncode
tool = ROOT/'docs/evidence/v1135-h2-d2-1ec9104/comparator'
proc = subprocess.run([sys.executable,'-B','-X','utf8',str(tool/'test_jarcmp_v2.py'),str(SCRATCH/'jar-fixtures')],cwd=ROOT,capture_output=True)
(OUT/'jarcmp-26.txt').write_bytes(proc.stdout+proc.stderr)
assert proc.returncode == 0, proc.stdout[-1000:]
result['jarcmp_exit'] = proc.returncode
final_jar = Path('D:/tmp/o1o3/jars/1ec9104.jar')
assert sha(final_jar.read_bytes()) == '76a8e403b7527a7c7ec9f32b0d439f008d226a77127e1ce5e394d51fbe976a03'
comparisons = []
for old in ('0d142aa', '1899b49'):
    proc = subprocess.run([sys.executable,'-B','-X','utf8',str(tool/'jarcmp_v2.py'),f'D:/tmp/o1o3/jars/{old}.jar',str(final_jar)],capture_output=True)
    assert proc.returncode == 0
    comparisons.append({'old': old, 'output': proc.stdout.decode('utf-8').strip()})
assert comparisons[0]['output'] == '(끝) 881 881'
assert len(comparisons[1]['output'].splitlines()) == 4
result['stored_jars'] = comparisons

d2 = ROOT/'docs/evidence/v1135-h2-d2-1ec9104/d2/blocks-d2'
blocks = list(d2.glob('*.sh'))
assert len(blocks) == 8
for path in blocks:
    proc = subprocess.run([bash,'--noprofile','--norc','-n',str(path)],capture_output=True)
    assert proc.returncode == 0, (path, proc.stderr)
result['d2_syntax'] = 8
u1 = (d2/'U1.sh').read_text(encoding='utf-8')
embedded = u1.split("<<'EOF'",1)[1].split('\n',1)[1].split('\nEOF',1)[0]+'\n'
assert embedded == (tool/'jarcmp_v2.py').read_text(encoding='utf-8')
assert u1.index('[ "$JC" = 0 ]') < u1.index('touch "$M/ok-U1"')
summary = (ROOT/'docs/evidence/v1135-h2-d2-1ec9104/standin/scenarios-d2-summary.txt').read_text(encoding='utf-8')
assert '0줄 · 대역 35개' in summary
result['d2_standin_preserved'] = {'cases':35,'forbidden_calls':0,'rerun':False}

# Four exact source bytes, not just similar displayed reports.
result['reports'] = {}
for daypart in ('1000','2200'):
    folder = ROOT/f'docs/evidence/n03-1109-{daypart}'
    result['reports'][daypart] = {name:sha((folder/name).read_bytes()) for name in (
        f'20261109-{daypart}.txt',f'20261109-{daypart}.txt.msg',f'20261109-{daypart}.txt.raw','last_send')}
assert (ROOT/'docs/evidence/n03-1109-2200/error_lines.txt').stat().st_size == 0
result['root_actions'] = {'server':False,'db':False,'gradle':False,'bootjar_build':False,
    'product_mutation':False,'deployment_authorized':False,'git_commit_push':False}
(OUT/'review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
print(json.dumps({'head':HEAD,'manifests':{k:v['files'] for k,v in result['evidence'].items()},
    'suite':{k:v for k,v in result['full_suite'].items() if k!='selected'},
    'morning_categories':result['morning_error_classification']['categories'],
    'unclassified':len(other),'limited_test_guard':True,'judge':0,'comparator':0,'syntax':8},ensure_ascii=False))
