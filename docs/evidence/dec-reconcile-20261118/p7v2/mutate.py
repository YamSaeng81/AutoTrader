"""P7 v2 대조기 변형 검사 — 핵심 식을 하나씩 일부러 망가뜨려 시나리오가 실패하는지 본다(사본에서만, 원본 불변).
사용: python -I mutate.py <p7v2 폴더> <작업 폴더>
"""
import sys, shutil, subprocess, os
sys.stdout.reconfigure(encoding='utf-8', newline='\n')
src, work = sys.argv[1], sys.argv[2]
muts = {
 'M1_no_round_explain': ("explained += rnd1 - rnd0", "explained += 0"),
 'M2_hold_hides_anomaly': ("overall = 'ANOMALY' if res.anomalies else ('HOLD' if res.holds else 'OK')", "overall = 'HOLD' if res.holds else ('ANOMALY' if res.anomalies else 'OK')"),
 'M3_current_not_delta': ("de = total(acc1, cur) - total(acc0, cur) - ext.get(cur, D(0))", "de = total(acc1, cur) - ext.get(cur, D(0))"),
 'M4_id_only_key': ("        d = contract_diff(e, s)\n", "        d = []\n"),
 'M5_skip_orphans': ("        if is_paper(s):\n            continue\n        prev", "        if s is None or is_paper(s):\n            continue\n        prev"),
 'M6_no_stability': ("    stable = check_observation('base', base, res) & check_observation('now', now, res)", "    stable = True"),
 'M7_one_won_tolerance': ("        if abs(adjusted) > krw_quantum:", "        if abs(adjusted) > 2:"),
}
for name, (a, b) in muts.items():
    d = os.path.join(work, name); shutil.rmtree(d, ignore_errors=True); shutil.copytree(src, d, ignore=shutil.ignore_patterns('out', '__pycache__'))
    p = os.path.join(d, 'reconcile.py'); s = open(p, encoding='utf-8').read(); assert a in s, name
    open(p, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b, 1))
    r = subprocess.run([sys.executable, '-I', os.path.join(d, 'scenarios.py'), os.path.join(d, 'out')], capture_output=True, text=True, encoding='utf-8')
    fails = [l.split(' | ')[1] for l in r.stdout.splitlines() if l.startswith('FAIL')]
    print(f'{name} rc={r.returncode} 잡힌 시나리오 {len(fails)}: {", ".join(fails)}')
    if r.returncode and not fails:
        print('  오류(시나리오 실패 아님):', (r.stderr.strip().splitlines() or ['-'])[-1])
