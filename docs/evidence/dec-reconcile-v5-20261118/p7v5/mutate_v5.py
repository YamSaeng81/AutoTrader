"""P7 v5 변형 시험 — 바꾼 시계 축 검사만 대상으로 한 글자 변형을 넣고 cases_v5.py(K·P·F)가 실패로 잡는지 본다.
v4 변형 V01~V27 은 바뀐 곳과 무관해 다시 돌리지 않는다.
사용: python -I mutate_v5.py <작업 폴더> [이름…]   종료값: 모든 변형 검출 0, 하나라도 살아남으면 1.
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = open(os.path.join(HERE, 'reconcile.py'), encoding='utf-8').read()

M = {
    'W01_static_union': ('같은 시계 선언을 한쪽 관측에만 있어도 사용',
                         "    return set.intersection(*sets) if sets else set()", "    return set.union(*sets) if sets else set()"),
    'W02_extrapolate': ('감싸는 표본이 한쪽만 있어도 변환(외삽)',
                        "            if ia and ib:\n                part = smp[max(ia):min(ib) + 1]\n                out.append((X,",
                        "            if ia or ib:\n                part = smp\n                out.append((X,"),
    'W03_mono_ignored': ('단조 시계 왕복 불일치 표본을 버리지 않음', "            if widen > MONO_TOL:\n                r['shift'] += 1   # 단조",
                         "            if False:\n                r['shift'] += 1   # 단조"),
    'W04_backward_ignored': ('X 시계 역행을 이동으로 보지 않음', "        if back:\n            ctx.report", "        if False:\n            ctx.report"),
    'W05_unknown_is_true': ('미확정 비교를 참으로', "    if ax is None or ay is None:\n        return None\n    X = convert",
                            "    if ax is None or ay is None:\n        return True\n    X = convert"),
    'W06_legacy_promoted': ('축 정보 없는 v4 입력을 같은 시계로 승격',
                            "        return ctx   # 축 정보 없음",
                            "        ctx.static |= {(_pair(a, b), 'x') for a in AXES for b in AXES if a < b}\n        return ctx   # 축 정보 없음"),
    'W07_window_end_is_end': ('창 끝을 거래소 뒤 읽기 끝으로', "    wt = step_bounds(now, 'exchange_after')[0]",
                              "    wt = step_bounds(now, 'exchange_after')[1] or step_bounds(now, 'exchange_after')[0]"),
    'W08_exchange_equals_intent': ('거래소 생성 상한을 DB 추출이 아니라 의도 생성 시각으로',
                                   "r_hi = le3(qf, 'exchange', oc, 'app', pair), le3(t1x, 'db', qt, 'exchange', pair)",
                                   "r_hi = le3(qf, 'exchange', oc, 'app', pair), le3(oc, 'app', qt, 'exchange', pair)"),
    'W09_rule_unknown_anomaly': ('구간 미확정 주문의 규칙 위반을 이상으로', "            if all(i in win_unk for i in orders):", "            if False:"),
    'W10_contract_unknown_mismatch': ('새 세션 생성 비교 미확정을 계약 불일치로', "        if r is False:\n            diffs.append('새 세션",
                                      "        if r is not True:\n            diffs.append('새 세션"),
    'W11_stale_kept': ('수집 구간 밖 표본을 버리지 않음', "        if c0 < span[0] or c1 > span[1]:", "        if False:"),
    'W12_no_widen': ('단조/벽시계 차이만큼 범위를 넓히지 않음', "        fresh.setdefault(X, []).append((c0 - widen, d, c1 + widen))",
                     "        fresh.setdefault(X, []).append((c0, d, c1))"),
}


def main():
    work = sys.argv[1]
    names = sys.argv[2:] or sorted(M)
    os.makedirs(work, exist_ok=True)
    out, alive = [], 0
    for n in names:
        why, a, b = M[n]
        if SRC.count(a) != 1:
            out.append(f'ERROR | {n} | 원문 위치 {SRC.count(a)}곳')
            alive += 1
            continue
        p = os.path.join(work, f'{n}.py')
        open(p, 'w', encoding='utf-8', newline='\n').write(SRC.replace(a, b))
        r = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'cases_v5.py'), '--impl', p, '--out', os.path.join(work, n), '--only', 'KPF'],
                           capture_output=True)
        txt = r.stdout.decode('utf-8', 'replace')
        fails = [l.split(' | ')[2] for l in txt.splitlines() if l.startswith('FAIL')]
        if r.returncode not in (0, 1):
            out.append(f'ERROR | {n} | 시험 실행 실패 rc={r.returncode}: {r.stderr.decode("utf-8", "replace").strip().splitlines()[-1:]}')
            alive += 1
            continue
        killed = r.returncode == 1 and fails
        alive += not killed
        out.append(f"{'KILLED' if killed else 'ALIVE'} | {n} | {why} | 잡은 시험 {len(fails)}: {','.join(fails[:6])}{'…' if len(fails) > 6 else ''}")
    out.append(f'변형 {len(names)} 검출 {len(names) - alive} 생존 {alive}')
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    print('\n'.join(out))
    return 1 if alive else 0


if __name__ == '__main__':
    sys.exit(main())
