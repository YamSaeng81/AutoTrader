"""R6-2 curl 직전 창 재검사 — 원문 순서 대조 + 순수 창 판정 2경계(총괄 v1.230 §2 R6-2). 파일 전용.

SSH·가짜 SSH·서버 별칭·docker·curl·운영 호출을 실행하지 않는다. a3-remote.sh 전체도 실행하지 않는다.
1) 순서: r6 a3-remote.sh 에서 `gate ACCOUNT`(앱 시계 앞, 유지) < APP_CLOCK_1 검증 < `gate ACCOUNT_CURL` < 유일한 curl 줄.
   r5 a3-remote.sh 에는 ACCOUNT_CURL 관문이 없다(보완 전/후 비교). 두 파일에서 win_ok·gate 함수 본문은 같다.
2) 창 판정: r6 원문 파일에서 win_ok 함수 본문을 그대로 잘라 bash 로 정의한 뒤 두 경우만 판정한다.
   정상    승인 2026-12-01 08:50-08:55, 서버 KST 2026-12-01 08:55(끝 분 포함) → 통과(0)
   창 경과 같은 승인, 서버 KST 2026-12-01 08:56(앱 시계 지연으로 끝 분을 지난 경우) → 불통과(비영) → gate 가 STOP 2, 계좌 호출 0회
사용: python -I win_gate.py --bash <Git Bash 경로> --out <기록 폴더(없어야 함)>
종료값: 기대대로 0, 아니면 1.
"""
import argparse, hashlib, json, os, re, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
NEW = os.path.join(os.path.dirname(HERE), 'read', 'a3-remote.sh')
OLD = os.path.join(os.path.dirname(os.path.dirname(HERE)), 'bundle-1124-r5-20261123', 'read', 'a3-remote.sh')
OLD_SHA = '2cb807428c70e6878c989aff357f6725efbf654d8ba39bb858e4a9dca3ecec3e'


def func(src, name):
    m = re.search(r'^' + name + r'\(\) \{\n.*?^\}\n', src, re.S | re.M)
    return m.group(0) if m else None


def idx(lines, pred):
    hits = [i for i, l in enumerate(lines) if pred(l)]
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--bash', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    old_b, new_b = open(OLD, 'rb').read(), open(NEW, 'rb').read()
    if hashlib.sha256(old_b).hexdigest() != OLD_SHA:
        print('🔴 r5 원문 해시 불일치')
        return 2
    os.makedirs(a.out, exist_ok=False)
    old, new = old_b.decode('utf-8'), new_b.decode('utf-8')
    nl = new.split('\n')
    code = lambda l: l.split('#', 1)[0].strip()
    g_acc = idx(nl, lambda l: code(l) == 'gate ACCOUNT')
    clk1 = idx(nl, lambda l: 'APP_CLOCK_1 server=' in l and 'stop 7 APP_CLOCK_1_INVALID' in l)
    g_curl = idx(nl, lambda l: code(l) == 'gate ACCOUNT_CURL')
    curl = idx(nl, lambda l: re.search(r'\bcurl\s+-', code(l)) is not None)
    between = nl[g_curl[0] + 1:curl[0]] if g_curl and curl else None
    order = {
        'gate_account_kept_once': len(g_acc) == 1,
        'app_clock_1_check_once': len(clk1) == 1,
        'gate_account_curl_once': len(g_curl) == 1,
        'curl_once': len(curl) == 1,
        'order_gate_acc_lt_clock1_lt_gate_curl_lt_curl': bool(g_acc and clk1 and g_curl and curl) and g_acc[0] < clk1[0] < g_curl[0] < curl[0],
        'only_timestamp_between_gate_and_curl': between is not None and [code(l) for l in between if code(l)] == ['CT0=$(ts)'],
        'r5_has_no_curl_gate': 'ACCOUNT_CURL' not in old,
        'win_ok_unchanged': func(old, 'win_ok') is not None and func(old, 'win_ok') == func(new, 'win_ok'),
        'gate_unchanged': func(old, 'gate') is not None and func(old, 'gate') == func(new, 'gate'),
        'no_new_ssh_sql_curl': new.count('ssh ') == old.count('ssh ') and new.count('psql') == old.count('psql')
                               and new.count('curl -sS') == old.count('curl -sS') == 1,
    }
    wf = func(new, 'win_ok')
    with open(os.path.join(a.out, 'win_ok.extracted.sh'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(wf or '')
    cases = {'normal_end_minute': ('2026-12-01', '08:50-08:55', '2026-12-01', '08:55', 0),
             'window_elapsed': ('2026-12-01', '08:50-08:55', '2026-12-01', '08:56', 1)}
    ver = subprocess.run([a.bash, '--version'], capture_output=True).stdout.decode('utf-8', 'replace').split('\n')[0]
    res = {}
    for k, (ad, aw, nd, nt, exp) in cases.items():
        p = subprocess.run([a.bash, '--noprofile', '--norc', '-c', wf + 'win_ok "$1" "$2" "$3" "$4"', 'x', ad, aw, nd, nt],
                           capture_output=True)
        rc = p.returncode
        res[k] = {'args': [ad, aw, nd, nt], 'rc': rc, 'stderr': p.stderr.decode('utf-8', 'replace'),
                  'ok': (rc == 0) if exp == 0 else (rc != 0 and p.stderr == b'')}
    ok = all(order.values()) and all(r['ok'] for r in res.values())
    summary = {'ok': ok, 'bash': ver, 'remote_old_sha256': OLD_SHA, 'remote_new_sha256': hashlib.sha256(new_b).hexdigest(),
               'test_sha256': hashlib.sha256(open(os.path.abspath(__file__), 'rb').read()).hexdigest(),
               'lines_1based': {'gate_ACCOUNT': [i + 1 for i in g_acc], 'APP_CLOCK_1': [i + 1 for i in clk1],
                                'gate_ACCOUNT_CURL': [i + 1 for i in g_curl], 'curl': [i + 1 for i in curl]},
               'order': order, 'win_ok_cases': res}
    with open(os.path.join(a.out, 'summary.json'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(summary, ensure_ascii=False, indent=1) + '\n')
    print('PASS' if ok else 'FAIL', {k: v for k, v in order.items() if not v}, {k: v['rc'] for k, v in res.items()})
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
