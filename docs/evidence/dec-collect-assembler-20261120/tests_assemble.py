"""조립기 직접 영향 7경계 (총괄 v1.208 §3-2). 보존 원문(A_V3 정상·R3_V3 STOP)만 쓴다. PG·psql·네트워크 없음.
수집기 시각은 합성 값이다(실제 수집 기록 아님). 결과 원문은 파일에만 두고 요약만 출력한다.
사용: python -I tests_assemble.py <run 폴더(새 폴더)>"""
import hashlib, importlib.util, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
G = os.path.join(HERE, '..', 'dec-read-gate-v3-20261120')
P7 = os.path.join(HERE, '..', 'dec-registry-p7v8-20261120', 'p7v8', 'reconcile.py')
A, STOP = os.path.join(G, 'run1', 'A_V3'), os.path.join(G, 'run1', 'R3_V3')
BASE_NS = 1_000_000_000_000


def clock(shift_us=0):
    # d(A_V3 META clock_timestamp) = 04:27:34.45728 를 감싸는 합성 수집기 시각. shift_us 는 c0→c1 사이 벽시계 이동
    us = lambda x: f'2026-11-20T04:27:34.{x:06d}+00:00'
    return {'version': 'collect-clock-1', 'c_start': us(400000), 'm_start_ns': BASE_NS,
            'c0': us(440000), 'm0_ns': BASE_NS + 40_000_000,
            'c1': us(480000 + shift_us), 'm1_ns': BASE_NS + 80_000_000,
            'c_end': us(490000 + shift_us), 'm_end_ns': BASE_NS + 90_000_000}


def rd(p):
    with open(p, 'rb') as f:
        return f.read()


def main(run):
    os.mkdir(run)
    spec = importlib.util.spec_from_file_location('p7v8', P7)
    p7 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(p7)
    a_out = rd(os.path.join(A, 'stdout.tsv'))
    lines = a_out.split(b'\n')
    meta = lines[0].split(b'\t')
    bad_meta = b'\t'.join(meta[:-1])   # META 열 하나 누락 → 형식 결함
    cases = {
        'normal': dict(src=A, exp=0),
        'stop': dict(src=STOP, exp=10),
        'psql_fail_partial': dict(src=A, stdout=b'\n'.join(lines[:3]) + b'\n', rc=b'3\n',
                                  stderr=b'synthetic: connection lost mid-output\n', exp=20),
        'parse_format_fail': dict(src=A, stdout=b'\n'.join([bad_meta] + lines[1:]), exp=30),
        'clock_shift': dict(src=A, clk=clock(shift_us=2000), exp=13),
        'clock_absent': dict(src=A, clk=None, exp=41),
        'existing_out': dict(src=A, exp=40, pre=True),
    }
    res, ok_all = {}, True
    for name, c in cases.items():
        ind = os.path.join(run, 'inputs', name)
        os.makedirs(ind)
        files = {'registry': rd(os.path.join(c['src'], 'registry.json')),
                 'stdout': c.get('stdout', rd(os.path.join(c['src'], 'stdout.tsv'))),
                 'stderr': c.get('stderr', rd(os.path.join(c['src'], 'stderr.txt'))),
                 'psql-rc': c.get('rc', rd(os.path.join(c['src'], 'psql_rc.txt')))}
        clk = c.get('clk', clock()) if 'clk' in c else clock()
        if clk is not None:
            files['clock'] = json.dumps(clk).encode()
        args = []
        for k in ('registry', 'stdout', 'stderr', 'psql-rc', 'clock'):
            p = os.path.join(ind, k)
            if k in files:
                with open(p, 'wb') as f:
                    f.write(files[k])
            args += ['--' + k, p]
        out = os.path.join(run, name)
        marker = None
        if c.get('pre'):
            os.mkdir(out)
            with open(os.path.join(out, 'existing.txt'), 'wb') as f:
                f.write(b'pre-existing\n')
            marker = sorted(os.listdir(out))
        args += ['--sql', os.path.join(G, 'read.sql'), '--parser', os.path.join(G, 'parse.py'), '--out', out]
        p = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'assemble.py')] + args, capture_output=True)
        chk = {'rc': p.returncode, 'expected_rc': c['exp']}
        good = p.returncode == c['exp']
        if c.get('pre'):
            chk['unchanged'] = sorted(os.listdir(out)) == marker and rd(os.path.join(out, 'existing.txt')) == b'pre-existing\n'
            good &= chk['unchanged']
        else:
            man = json.load(open(os.path.join(out, 'bundle.json'), encoding='utf-8'))
            # 원문 보존: 입력 바이트 = 묶음 바이트
            chk['raw_preserved'] = all(
                rd(os.path.join(out, n)) == files[k] for k, n in
                (('registry', 'registry.json'), ('stdout', 'stdout.tsv'), ('stderr', 'stderr.txt'),
                 ('psql-rc', 'psql_rc.txt'), ('clock', 'clock.json')) if k in files)
            # SHA256SUMS 자기 대조
            sums = [l.split('  ') for l in rd(os.path.join(out, 'SHA256SUMS')).decode().splitlines()]
            chk['sums_ok'] = len(sums) == len(os.listdir(out)) - 1 and all(
                hashlib.sha256(rd(os.path.join(out, n))).hexdigest() == h for h, n in sums)
            chk['flags_false'] = not (man['is_p7_observation'] or man['is_f3_input'] or man['account_or_month_end_confirmed'])
            chk['downstream'] = man['downstream']
            chk['status'] = man.get('status')
            chk['clock_status'] = man.get('clock', {}).get('status')
            good &= chk['raw_preserved'] and chk['sums_ok'] and chk['flags_false']
            good &= (man['downstream'] == 'db_part_only') == (c['exp'] == 0)
            pj = os.path.join(out, 'parsed.json')
            if man.get('parse_rc', 0) != 0 and os.path.exists(pj):   # 파서 0 결과는 보존하되 downstream 으로 차단
                pd = json.load(open(pj, encoding='utf-8'))
                chk['amount_keys_absent'] = not ({'orders', 'positions', 'sessions'} & set(pd))
                good &= chk['amount_keys_absent']
            if c['exp'] == 0:
                s = man['clock']['sample']
                probs = []
                p7.v_clock(probs, 'meta.clock', {'version': p7.CLOCK_VERSION, 'samples': [s], 'same_source': []})
                chk['p7_sample_shape_problems'] = probs
                chk['sample_step'] = s['step']
                good &= not probs and s['step'] == 'db_extract' and set(s) == p7.SAMPLE_KEYS
            if c['exp'] == 13:
                chk['no_sample'] = 'sample' not in man['clock'] and 'db_extract_step' not in man['clock']
                good &= chk['no_sample']
        chk['pass'] = bool(good)
        ok_all &= good
        res[name] = chk
    summary = {'pass': sum(r['pass'] for r in res.values()), 'total': len(res), 'cases': res}
    with open(os.path.join(run, 'results.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    print(json.dumps({k: (v['rc'], v['pass']) for k, v in res.items()}), f"{summary['pass']}/{summary['total']}")
    return 0 if ok_all else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
