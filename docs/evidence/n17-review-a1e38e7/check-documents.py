"""Verify review-only changes, fixed commands and unchanged operating pins."""
from pathlib import Path
import hashlib, json, re, subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = 'a1e38e7f12690ea172fd015b7e8c143ceac9b449'
NAMES = ['LIMITED_LIVE_TEST_PLAN.md','LIVE_READINESS.md','NOVEMBER_LIVE_UPGRADE.md','OPS_DAILY_CHECK.md','PROGRESS.md']

def old(n):
    return subprocess.check_output(['git','show',REF+':docs/'+n],cwd=ROOT).decode().replace('\r\n','\n')

def current(n):
    return (ROOT/'docs'/n).read_text(encoding='utf-8')

def blocks(s):
    return re.findall(r'^```bash\n(.*?)^```',s,re.M|re.S)

plan = current(NAMES[0])
assert len(blocks(plan)) == 125 and blocks(plan) == blocks(old(NAMES[0]))
assert plan.count('**88.') == 1 and plan.index('**88.') < plan.index('## 6. 시작·중단·종료 조건')
assert plan.splitlines()[2].startswith('> **v1.120 ')
assert len(blocks(current(NAMES[3]))) == 7 and blocks(current(NAMES[3])) == blocks(old(NAMES[3]))
assert current('LIVE_ENTRY_PREREG.md') == old('LIVE_ENTRY_PREREG.md')
fixed = json.loads((ROOT/'docs/evidence/k23-result-review-28e2183/n06-blocks.json').read_text(encoding='utf-8'))['blocks']
assert len(fixed) == 15
for b in fixed:
    assert hashlib.sha256(blocks(plan)[b['index_zero_based']].encode()).hexdigest() == b['new_sha256'], b['block']
nov = current(NAMES[2])
for name in ['N17','N18','N19']:
    rows = [l for l in nov.splitlines() if l.startswith('| '+name+' |')]
    assert len(rows) == 1 and rows[0].count('|') == next(l for l in nov.splitlines() if l.startswith('| N16 |')).count('|')
for name in ['N15','N16']:
    assert next(l for l in nov.splitlines() if l.startswith('| '+name+' |')) == next(l for l in old(NAMES[2]).splitlines() if l.startswith('| '+name+' |'))
assert '| 운영 backend | `1103f48505a1457029f6555547a87aa986535738`' in nov
assert '| 운영 이미지 | `sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62` |' in nov
assert current(NAMES[4]).count('> **마지막 갱신**: 2026-11-05 (308차,') == 1
assert current(NAMES[4]).count('> **이전 갱신**: 2026-11-05 (307차)') == 1
changed = sorted(subprocess.check_output(['git','diff','--name-only'],cwd=ROOT).decode().splitlines())
assert changed == sorted('docs/'+n for n in NAMES), changed
assert subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True).returncode == 0
result = {'reviewed_source':REF,'version':'1.120','section':88,'progress':308,
    'tracked_docs':changed,'unchanged_plan_bash':125,'unchanged_OPS_bash':7,
    'unchanged_F_blocks':15,'unchanged_PREREG':True,'operating_pins_1103f48_and_image_unchanged':True,
    'N15_N16_deployed_complete_rows_unchanged':True,'N17_local_accepted_deployment_approved_results_pending':True,
    'N18_N19_local_scopes_authorized_no_deployment':True,'git_diff_check':0}
(OUT/'doc-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows = [hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in sorted(OUT.iterdir(),key=lambda p:p.name)
        if p.is_file() and p.name != 'SHA256SUMS']
(OUT/'SHA256SUMS').write_text('\n'.join(rows)+'\n',encoding='utf-8')
for row in rows:
    e,n = row.split('  ',1)
    assert hashlib.sha256((OUT/n).read_bytes()).hexdigest() == e
print(json.dumps({'docs':5,'unchanged_plan_OPS_F':[125,7,15],'checksums':len(rows),'diff_check':0}))
