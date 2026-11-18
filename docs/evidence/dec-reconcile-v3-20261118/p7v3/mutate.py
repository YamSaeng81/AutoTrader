"""P7 v3 변형 검사 — v3 에서 새로 넣은 계약을 하나씩 사본에서 망가뜨려 cases.py 가 잡는지 본다(원본 불변).
사용: python -I mutate.py <작업 폴더>
"""
import os, shutil, subprocess, sys

sys.stdout.reconfigure(encoding='utf-8', newline='\n')
HERE = os.path.dirname(os.path.abspath(__file__))
work = sys.argv[1]
MUTS = {
    'V01_no_code_link': ("    if app_sha is not None and not sha_eq(e['code_sha'], app_sha):", "    if False:"),
    'V02_missing_fp_counts_as_equal': ("    if any(v is None for v in fps):", "    if False:"),
    'V03_no_base_now_order': ("    if not t0 < t1:", "    if False:"),
    'V04_any_hold_blocks_all': ("        return any(ALL in h['blocks'] or cur in h['blocks'] for h in self.holds)", "        return bool(self.holds)"),
    'V05_no_db_to_exchange': ("        if u not in ea['done_order_ids']:", "        if False:"),
    'V06_gap_called_missing': ("            res.hold('EXCHANGE_COVERAGE_GAP', blocks=(),", "            res.anomaly('EXCHANGE_COVERAGE_GAP',"),
    'V07_no_finite_check': ("    if not x.is_finite():", "    if False:"),
    'V08_no_rule_format': ("    if not isinstance(r, dict):\n        raise Bad('rules 가 객체 아님')\n    unknown", "    return\n    unknown"),
    'V09_unreadable_registry_as_empty': ("    if reg_status == 'unreadable' or any(v != 'ok' for v in obs.values()):", "    if any(v != 'ok' for v in obs.values()):"),
    'V10_exception_as_ok': ("        return {'overall': 'ERROR', 'error'", "        return {'overall': 'OK', 'error'"),
    'V11_no_duplicate_keys': ("            dup = sorted({str(k) for k in keys if keys.count(k) > 1})", "            dup = []"),
    'V12_no_existing_session_created': ("    elif created != tstamp(e['session_created_at']):", "    elif False:"),
    'V13_flows_unknown_blocks_backing': ("        blk = res.blocked(cur)\n        account['backing']", "        blk = res.blocked(cur) or flows_blocked\n        account['backing']"),
    'V14_verdict_ignores_backing': ("    sts = [account['krw']['status']] + [v['status'] for k in ('coins', 'sessions', 'backing')",
                                    "    sts = [account['krw']['status']] + [v['status'] for k in ('coins', 'sessions')"),
    'V15_no_steps_check': ("    if not isinstance(st, list) or [s.get('step') if isinstance(s, dict) else None for s in st] != list(STEPS):",
                           "    if not isinstance(st, list):"),
    'V16_no_source_match': ("    elif not (reg_src == base['meta']['source'] == now['meta']['source']):", "    elif False:"),
}
src = open(os.path.join(HERE, 'reconcile.py'), encoding='utf-8').read()
for name, (a, b) in MUTS.items():
    assert src.count(a) == 1, name
    d = os.path.join(work, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    p = os.path.join(d, 'reconcile.py')
    open(p, 'w', encoding='utf-8', newline='\n').write(src.replace(a, b, 1))
    r = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'cases.py'), '--impl', p, '--out', os.path.join(d, 'out')], capture_output=True)
    text = r.stdout.decode('utf-8', 'replace')
    fails = [l.split(' | ')[2] for l in text.splitlines() if l.startswith('FAIL')]
    print(f'{name} rc={r.returncode} 잡힌 사례 {len(fails)}: {", ".join(fails)}')
    if r.returncode and not fails:
        print('  오류(사례 실패 아님):', (r.stderr.decode('utf-8', 'replace').strip().splitlines() or ['-'])[-1])
