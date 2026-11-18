"""P7 v4 변형 검사 — V01~V16 은 v3 계약, V17~V27 은 v4(총괄 v1.188 U1~U3)에서 새로 넣은 계약. 각각을 하나씩 사본에서 망가뜨려 cases.py 가 잡는지 본다(원본 불변).
사용: python -I mutate.py <작업 폴더> [변형 이름 ...]   (이름을 주면 그 변형만 — v4 는 영향받은 V17~V27 만 실행)
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
    # ── v4(총괄 v1.188 U1~U3) ──
    'V17_window_hold_off': ("    if why:\n        res.hold('EXCHANGE_WINDOW_UNCONFIRMED'", "    if False:\n        res.hold('EXCHANGE_WINDOW_UNCONFIRMED'"),
    'V18_window_range_ignored': ("    elif tstamp(q['from']) > tstamp(w_from) or tstamp(q['to']) < tstamp(w_to):", "    elif False:"),
    'V19_window_end_at_db_extract': ("    w_to = next(s['at'] for s in now['meta']['steps'] if s['step'] == 'exchange_after')",
                                     "    w_to = next(s['at'] for s in now['meta']['steps'] if s['step'] == 'db_extract')"),
    'V20_unrecognized_state_settles': ("not o.get('fill_accounting_pending') and st in SETTLED",
                                       "not o.get('fill_accounting_pending') and st not in ACTIVE and st != 'UNKNOWN'"),
    'V21_no_state_unrecognized_hold': ("        elif o['state'] not in ORDER_STATES:", "        elif False:"),
    'V22_no_order_type_check': ("        if o['order_type'] not in ORDER_TYPES:", "        if False:"),
    'V23_no_position_status_check': ("        elif p['status'] not in POSITION_STATUSES:", "        elif False:"),
    'V24_ledger_not_blocked_by_unrecognized': ("        blk = res.blocked(ALL) or key in unrec_sessions", "        blk = res.blocked(ALL)"),
    'V25_decode_error_escapes': ("        text = raw.decode('utf-8')\n    except UnicodeDecodeError as e:", "        text = raw.decode('utf-8')\n    except LookupError as e:"),
    'V26_decode_as_unreadable': ("        return {'__load_error__': ('invalid', f'UTF-8", "        return {'__load_error__': ('unreadable', f'UTF-8"),
    'V27_decode_leaks_bytes': ("f'UTF-8 디코딩 실패(바이트 위치 {e.start}, 길이 {len(raw)})'", "f'{e}'"),
}
src = open(os.path.join(HERE, 'reconcile.py'), encoding='utf-8').read()
names = sys.argv[2:] or list(MUTS)
for name in names:
    a, b = MUTS[name]
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
