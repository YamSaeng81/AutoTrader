"""P7 v6 영향 시험 — v5 시험 생성기(cases_v5.py 원문, 읽기만)가 만드는 K·P·R·L 입력 전부를 v5 원문과 v6 에 함께 넣고 비교한다.
기대: 이상 코드·계좌 계산·그 밖 출력이 v5 와 같고, 차이는 (등록표가 유효한 경우) 보류 ACCOUNT_SUM_UNAVAILABLE 1개 추가와
그로 인한 OK→HOLD 뿐이다. 이상이 사라지거나 보류가 빠지면 실패. PG·네트워크·DB 접속 없음.
사용: python -I impact.py --out <폴더>
"""
import argparse, copy, hashlib, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.normpath(os.path.join(HERE, '..', '..'))
V5DIR = os.path.join(EV, 'dec-reconcile-v5-20261118', 'p7v5')


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    cv = load(os.path.join(V5DIR, 'cases_v5.py'), 'cases_v5')
    cv.PG_SUMS = cv.sums(cv.PG)
    v5 = load(os.path.join(V5DIR, 'reconcile.py'), 'v5')
    v6 = load(os.path.join(HERE, 'reconcile.py'), 'v6')
    inputs = [('K_' + n, d) for n, _w, d, *_ in cv.k_cases()]
    inputs += [('P_' + n, d) for n, _w, d, _v in cv.p_cases()]
    for n, d in cv.r_cases():
        inputs.append(('R_' + n, cv.synth(d['input'])))
        inputs.append(('L_' + n, d['input']))
    lines = [f"v5 {hashlib.sha256(open(os.path.join(V5DIR, 'reconcile.py'), 'rb').read()).hexdigest()}",
             f"v6 {hashlib.sha256(open(os.path.join(HERE, 'reconcile.py'), 'rb').read()).hexdigest()}"]
    fails, trans = 0, {}
    for name, d in inputs:
        r5 = cv.run(v5, d)
        r6 = cv.run(v6, d)
        bad = []
        if cv.codes(r6, 'anomalies') != cv.codes(r5, 'anomalies') or r6.get('anomalies') != r5.get('anomalies'):
            bad.append('이상 다름')
        h5 = r5.get('holds') or []
        h6 = [h for h in r6.get('holds') or [] if h['code'] != 'ACCOUNT_SUM_UNAVAILABLE']
        added = len(r6.get('holds') or []) - len(h6)
        if h6 != h5:
            bad.append('기존 보류 다름')
        sm = r6.get('account_sum')
        if r5.get('registry_status') == 'valid' and r5.get('account') is not None:
            if added != 1 or (sm or {}).get('status') != '보류':
                bad.append(f'ACCOUNT_SUM_UNAVAILABLE {added}개·합산 {sm}')
        elif r5.get('registry_status') == 'invalid' and r5.get('account') is not None:
            if added or (sm or {}).get('status') != '불일치' or not r5.get('anomalies'):
                bad.append(f'등록표 무효인데 합산 {sm}·보류 추가 {added}')
        elif added or sm is not None:
            bad.append('관측 보류인데 합산 결과 생김')
        for k in set(r5) | set(r6):
            if k not in ('overall', 'holds', 'account_sum', 'anomalies') and r5.get(k) != r6.get(k):
                bad.append(f'{k} 다름')
        o5, o6 = r5.get('overall'), r6.get('overall')
        if not (o5 == o6 or (o5 == 'OK' and o6 == 'HOLD' and added == 1)):
            bad.append(f'overall {o5}→{o6}')
        trans[f'{o5}→{o6}'] = trans.get(f'{o5}→{o6}', 0) + 1
        fails += bool(bad)
        lines.append(f"{'PASS' if not bad else 'FAIL'} | {name} | {o5}→{o6}" + (f" | {'; '.join(bad)}" if bad else ''))
    lines.append('전이 ' + ', '.join(f'{k} {v}' for k, v in sorted(trans.items())))
    lines.append(f'합계 {len(inputs)} 통과 {len(inputs) - fails} 실패 {fails}')
    os.makedirs(a.out, exist_ok=True)
    open(os.path.join(a.out, 'impact.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print('\n'.join(lines[-3:]))
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
