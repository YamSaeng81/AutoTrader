"""Check overnight authorization docs and preserve all reviewed executable blocks."""
from pathlib import Path
import hashlib, json, re, subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REF = 'a1e38e7f12690ea172fd015b7e8c143ceac9b449'

def sha(b):
    return hashlib.sha256(b).hexdigest()

def old(n):
    return subprocess.check_output(['git','show',REF+':docs/'+n],cwd=ROOT).decode().replace('\r\n','\n')

def read(n):
    return (ROOT/'docs'/n).read_text(encoding='utf-8')

def blocks(s):
    return re.findall(r'^```bash\n(.*?)^```',s,re.M|re.S)

plan = read('LIMITED_LIVE_TEST_PLAN.md')
assert plan.splitlines()[2].startswith('> **v1.121 ')
assert plan.count('**89.') == 1 and plan.index('**89.') < plan.index('## 6. 시작·중단·종료 조건')
assert blocks(plan) == blocks(old('LIMITED_LIVE_TEST_PLAN.md')) and len(blocks(plan)) == 125
assert blocks(read('OPS_DAILY_CHECK.md')) == blocks(old('OPS_DAILY_CHECK.md')) and len(blocks(read('OPS_DAILY_CHECK.md'))) == 7
assert read('LIVE_ENTRY_PREREG.md') == old('LIVE_ENTRY_PREREG.md')
frozen = json.loads((ROOT/'docs/evidence/k23-result-review-28e2183/n06-blocks.json').read_text(encoding='utf-8'))['blocks']
assert len(frozen) == 15
for row in frozen:
    assert sha(blocks(plan)[row['index_zero_based']].encode()) == row['new_sha256']

directive = read('OVERNIGHT_20261105.md')
assert not blocks(directive)
assert '2026-11-06 08:00 KST' in directive
assert 'a1e38e7f12690ea172fd015b7e8c143ceac9b449' in directive
assert '7a944723c2959898226c2ad80f6b079ab5678296' in directive
assert '1103f48505a1457029f6555547a87aa986535738' in directive
assert '9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62' in directive
assert '사전 승인' in directive and 'T2 직전에 새 승인 질문을 보내지 않는다' in directive
assert '새 후보를 운영에 설치하지 않는다' in directive
assert '세션 밖에서도 실행되는 예약 작업이나 무중단 감시를 뜻하지 않는다' in directive
assert '휴대폰 수신 미확인' in directive

# The preceding review's sealed evidence must remain unchanged.
prior = ROOT/'docs/evidence/n17-review-a1e38e7'
count = 0
for line in (prior/'SHA256SUMS').read_text(encoding='utf-8').splitlines():
    e,n = line.split('  ',1)
    assert sha((prior/n).read_bytes()) == e,n
    count += 1
assert count == 12

nov = read('NOVEMBER_LIVE_UPGRADE.md')
assert '| 운영 backend | `1103f48505a1457029f6555547a87aa986535738`' in nov
assert '| 운영 이미지 | `sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62` |' in nov
for code in ['N15','N16']:
    assert next(r for r in nov.splitlines() if r.startswith('| '+code+' |')) == next(r for r in old('NOVEMBER_LIVE_UPGRADE.md').splitlines() if r.startswith('| '+code+' |'))
assert read('PROGRESS.md').count('> **마지막 갱신**: 2026-11-05 (309차,') == 1
assert read('PROGRESS.md').count('> **이전 갱신**: 2026-11-05 (308차,') == 1
names = ['LIMITED_LIVE_TEST_PLAN.md','LIVE_READINESS.md','NOVEMBER_LIVE_UPGRADE.md','OPS_DAILY_CHECK.md','PROGRESS.md']
changed = sorted(subprocess.check_output(['git','diff','--name-only'],cwd=ROOT).decode().splitlines())
assert changed == sorted('docs/'+n for n in names),changed
assert subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True).returncode == 0
result = {'version':'1.121','section':89,'progress':309,'fixed_execution_source':REF,
    'directive':'docs/OVERNIGHT_20261105.md','directive_sha256':sha((ROOT/'docs/OVERNIGHT_20261105.md').read_bytes()),
    'prior_review_files_unchanged':count,'plan_OPS_F_unchanged':[125,7,15],
    'conditional_preapproval_only_reviewed_N17_one_deployment':True,
    'conditional_prior_image_rollback_once':True,'N18_N19_local_only_no_new_candidate_deployment':True,
    'no_server_DB_app_tests_build_actual_deployment_executed':True,'no_scheduled_task_or_daemon_created':True,
    'operating_F_pins_S_SID203_start_time_unchanged':True,'tracked_docs':changed,'git_diff_check':0}
(OUT/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows = [sha(p.read_bytes())+'  '+p.name for p in sorted(OUT.iterdir(),key=lambda p:p.name) if p.is_file() and p.name!='SHA256SUMS']
(OUT/'SHA256SUMS').write_text('\n'.join(rows)+'\n',encoding='utf-8')
print(json.dumps({'unchanged_plan_OPS_F':[125,7,15],'prior_review_files':count,'scope_evidence_files':len(rows),'diff_check':0}))
