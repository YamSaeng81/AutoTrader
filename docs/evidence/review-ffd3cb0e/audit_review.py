"""Static artifact review only; never execute prepared production scripts."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SOURCE = 'ffd3cb0e'
BASH = 'C:/Program Files/Git/bin/bash.exe'
def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)
def sha(data):
    return hashlib.sha256(data).hexdigest()
def manifest(folder):
    base = ROOT / 'docs/evidence' / folder
    rows = []
    for line in (base/'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        expected, name = line[:64], line[64:].strip().lstrip('*')
        rel = (base/name).relative_to(ROOT).as_posix()
        rows.append(dict(path=name, working=sha((base/name).read_bytes()) == expected,
                         committed=sha(git('show', SOURCE+':'+rel)) == expected))
    assert all(row['working'] and row['committed'] for row in rows)
    return dict(count=len(rows), mismatch=[])

result = dict(source=git('rev-parse', SOURCE).decode().strip())
result['manifests'] = {name: manifest(name) for name in ['n15-late-20261116', 'review-d5790c1c']}
base = ROOT/'docs/evidence/n15-late-20261116'
result['runs'] = {}
for folder in sorted((base/'runs').iterdir()):
    xml = ET.parse(next(folder.glob('TEST-*.xml'))).getroot()
    output = '\n'.join(xml.itertext())
    result['runs'][folder.name] = dict(tests=int(xml.get('tests')), failures=int(xml.get('failures')),
        errors=int(xml.get('errors')), skipped=int(xml.get('skipped')),
        pin_lines=[line for line in output.splitlines() if '[N15PIN]' in line],
        meta=(folder/'meta.txt').read_text(encoding='utf-8'),
        summary=(folder/'summary.txt').read_text(encoding='utf-8'))
result['fix_paths'] = git('diff', '--name-only', 'ee6e4729', 'e9d982ab').decode().splitlines()
result['investigation_paths'] = git('diff', '--name-only', '0e1d2a02', 'e9d982ab').decode().splitlines()
assert result['fix_paths'] == result['investigation_paths'] == ['web-api/src/test/java/com/cryptoautotrader/api/service/N15EntryExpiryTest.java']
result['fix_diff_matches'] = git('diff', 'ee6e4729', 'e9d982ab') == (base/'e9d982ab-fix.diff').read_bytes()
assert result['fix_diff_matches']
result['fix_diff_sha256'] = sha(git('diff', 'ee6e4729', 'e9d982ab'))
result['candidate_unchanged'] = git('rev-parse', 'candidate/order-poll-race-b987d7a7').decode().strip() == git('rev-parse', '0e1d2a02').decode().strip()
assert result['candidate_unchanged']

prepared = Path('D:/tmp/v168run')
snapshots = {
    'prepared-keep-v161.sh': prepared/'pre/keep-v161.sh',
    'prepared-runner.sh': prepared/'runner/run.sh',
    'prepared-extractor.py': prepared/'runner/extract.py',
    'prepared-blocks.json': prepared/'blocks/blocks.json',
    'prepared-selftest.txt': Path('D:/tmp/v168run-selftest/selftest.txt'),
}
for name, path in snapshots.items():
    if not (OUT/name).exists():
        (OUT/name).write_bytes(path.read_bytes())
result['prepared_sha256'] = {name: sha((OUT/name).read_bytes()) for name in snapshots}
index = json.loads((OUT/'prepared-blocks.json').read_text(encoding='utf-8'))
result['blocks'] = {}
for name, meta in index.items():
    if not isinstance(meta, dict):
        continue
    data = (prepared/'blocks'/(name+'.sh')).read_bytes()
    if name in ['F', 'G']:
        file = 'docs/'+meta['from']
        text = git('show', 'd5790c1c:'+file).decode().replace('\r\n', '\n')
        expected = re.findall(r'^```bash\n(.*?)^```\s*$', text, re.M|re.S)[meta['index']].encode()
    else:
        expected = git('show', 'd5790c1c:'+meta['from'])
    syntax = subprocess.run([BASH, '--noprofile', '--norc', '-n', str(prepared/'blocks'/(name+'.sh'))], capture_output=True)
    result['blocks'][name] = dict(sha256=sha(data), fixed_source_match=data==expected,
                                  manifest_match=sha(data)==meta['sha256'], syntax_rc=syntax.returncode)
assert len(result['blocks']) == 12
assert all(row['fixed_source_match'] and row['manifest_match'] and row['syntax_rc']==0 for row in result['blocks'].values())

# Model the unchecked failing hash pipeline; no files, sockets or production tools.
def shell_model(strict):
    code = ('set -euo pipefail; ' if strict else 'set -u; ')
    code += 'bad() { return 7; }; a=$(bad | cat); b=$(bad | cat); [ "$a" = "$b" ]; echo ACCEPTED'
    p = subprocess.run([BASH, '--noprofile', '--norc', '-c', code], capture_output=True, text=True)
    return dict(rc=p.returncode, stdout=p.stdout, stderr=p.stderr)
result['preservation_failure_model'] = dict(current=shell_model(False), strict=shell_model(True))
assert result['preservation_failure_model']['current']['rc'] == 0
assert result['preservation_failure_model']['strict']['rc'] == 7
keep_root = Path('D:/tmp/v168run-selftest/keep2')
keep_sources = {name: prepared/'pre'/name for name in ['keep-v161-remote.sh', 'keep-v161.sh', 'keep-v161.v1-before.sh']}
keep_sources['selftest.txt'] = keep_root/'selftest.txt'
keep_sources.update({'bin/'+path.name: path for path in (keep_root/'bin').iterdir() if path.is_file()})
keep_sources.update({path.relative_to(keep_root).as_posix(): path for path in keep_root.glob('out-*/keep-v161.txt')})
keep_out = OUT/'keep2'
result['keep2_files'] = {}
for name, source in keep_sources.items():
    target = keep_out/name
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        target.write_bytes(source.read_bytes())
    result['keep2_files'][name] = dict(sha256=sha(target.read_bytes()), current_match=target.read_bytes()==source.read_bytes())
assert all(row['current_match'] for row in result['keep2_files'].values())
for name, prefix in [('keep-v161-remote.sh', 'f9b09a0d'), ('keep-v161.sh', '0b8fd38f'), ('keep-v161.v1-before.sh', '26cb622c')]:
    assert result['keep2_files'][name]['sha256'].startswith(prefix)
assert (keep_out/'keep-v161.v1-before.sh').read_bytes() == (OUT/'prepared-keep-v161.sh').read_bytes()
result['keep2_syntax'] = {name: subprocess.run([BASH, '--noprofile', '--norc', '-n', str(keep_out/name)], capture_output=True).returncode for name in ['keep-v161-remote.sh', 'keep-v161.sh']}
assert all(rc==0 for rc in result['keep2_syntax'].values())
expected_rc = dict(normal=0, origlist=4, keeplist=4, copyfail=4, mismatch=5,
    **{'exist-keep':3, 'exist-v168':3, 'exist-v167':3, 'no-v161':3})
result['keep2_cases'] = {}
for name, expected in expected_rc.items():
    data = (keep_out/('out-'+name)/'keep-v161.txt').read_text(encoding='utf-8')
    rc = int(re.findall(r'\[로컬 끝\].*rc=(\d+)', data)[-1])
    success = '✅ 보존 완료' in data
    home = keep_root/('h-'+name)
    actual_copy = (home/'maint-v161-pre-v168-20261116').exists()
    expected_copy = name in ['normal', 'keeplist', 'copyfail', 'mismatch', 'exist-keep']
    temporary = list(home.rglob('keep-v161.*')) if home.exists() else []
    result['keep2_cases'][name] = dict(rc=rc, success=success, copy_exists=actual_copy,
        temporary_residual=[path.relative_to(home).as_posix() for path in temporary])
    assert rc==expected and success==(name=='normal') and actual_copy==expected_copy and not temporary
for name in ['origlist', 'keeplist']:
    data = (keep_out/('out-v1-'+name)/'keep-v161.txt').read_text(encoding='utf-8')
    assert data.rstrip().endswith('rc=0') and '✅ 원본·사본 sha256 목록 같음' in data
result['keep2_repeat_output_rejected'] = 'repeat-output rc=2' in (keep_out/'selftest.txt').read_text(encoding='utf-8')
assert result['keep2_repeat_output_rejected']
docs = [ROOT/'docs'/name for name in ['START_HERE.md', 'LIMITED_LIVE_TEST_PLAN.md',
    'CURRENT_HANDOFF.md', 'LIVE_READINESS.md', 'NOVEMBER_LIVE_UPGRADE.md', 'PROGRESS.md', 'README.md']]
result['document_qa'] = {}
for path in docs+[OUT/'README.md']:
    text = path.read_text(encoding='utf-8')
    broken = []
    for target in re.findall(r'\]\(([^)]+)\)', text):
        if target.startswith(('https://', 'http://', '#')):
            continue
        if not (path.parent/target.split('#')[0]).exists():
            broken.append(target)
    foreign = re.findall(r'[\u3001\u3002\u3040-\u30ff\u4e00-\u9fff]', text)
    result['document_qa'][path.name if path.parent != OUT else 'review/README.md'] = dict(lines=len(text.splitlines()), broken_links=broken, foreign_count=len(foreign))
    assert not broken and not foreign, str(path)
result['recent_progress_items'] = len(re.findall(r'^### \d+차', docs[5].read_text(encoding='utf-8'), re.M))
assert result['recent_progress_items'] == 5
old_plan = git('show', SOURCE+':docs/LIMITED_LIVE_TEST_PLAN.md').decode()
new_plan = docs[1].read_text(encoding='utf-8')
night_section = lambda text: text.split('### 야간 작업 원칙', 1)[1].split('### 보완 뒤에도', 1)[0].replace('\r\n', '\n')
result['night_policy_unchanged'] = night_section(old_plan) == night_section(new_plan)
assert result['night_policy_unchanged']
result['scope'] = 'Static Git, hash, XML and bash syntax checks; shell failure model only. No operational script executed.'
(OUT/'checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
sum_paths = sorted(path for path in OUT.rglob('*') if path.name not in ['.gitattributes', 'SHA256SUMS'] and path.is_file())
(OUT/'SHA256SUMS').write_text(''.join(sha(path.read_bytes())+' *'+path.relative_to(OUT).as_posix()+'\n' for path in sum_paths), encoding='utf-8')
print(json.dumps(dict(manifests=result['manifests'], runs={key:{field:value[field] for field in ['tests','failures','errors','skipped']} for key,value in result['runs'].items()}, blocks=len(result['blocks']), candidate_unchanged=result['candidate_unchanged'], failure_model=result['preservation_failure_model']), ensure_ascii=False))
