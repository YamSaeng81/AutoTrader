"""P7 v7·F3 판정기 시험 — 총괄 v1.203 §2-4·§2-5. 입력은 v1.203 검토 입력(review-09f6bd4a/contract-probes.json P0~P4, 같은 시계는
그 파일이 밝힌 시험용 가상 선언)을 그대로 쓰고 이번 변경에 직접 영향받는 사례만 더한다. 옛 형식 260건 전체는 반복하지 않는다.
RED: v6 원문 출력 + 452차 초안 F3(account_sum 일치 단독)이 P1·P2·P4·P3 를 통과시키는 것을 먼저 고정.
사용: python -I cases_v7.py --out <폴더>   종료값: 전부 통과 0, 하나라도 실패 1. 운영·DB·네트워크 접속 없음.
"""
import argparse, copy, hashlib, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.normpath(os.path.join(HERE, '..', '..'))


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


V6 = load(os.path.join(EV, 'dec-registry-v3-p7v6-20261120', 'p7v6', 'reconcile.py'), 'p7v6')
V7 = load(os.path.join(HERE, 'reconcile.py'), 'p7v7')
F3 = load(os.path.join(HERE, 'f3.py'), 'f3')
P = {p['name'][:2]: p['source'] for p in json.load(open(os.path.join(EV, 'review-09f6bd4a', 'contract-probes.json'), encoding='utf-8'))['probes']
     if p['name'][:1] == 'P'}


def ev7(d):
    return V7.evaluate(copy.deepcopy(d['registry']), copy.deepcopy(d['base']), copy.deepcopy(d['now']))


def old_f3(o):
    return (o.get('account_sum') or {}).get('status') == '일치'


def codes(o, k='anomalies'):
    return sorted({x['code'] for x in o.get(k) or []})


CASES = []


def case(name):
    def deco(f):
        CASES.append((name, f))
        return f
    return deco


@case('RED 초안 F3(합산 일치 단독)는 v6 출력의 P1·P2·P3·P4 를 통과시킴')
def _():
    got = {k: old_f3(V6.evaluate(copy.deepcopy(P[k]['registry']), copy.deepcopy(P[k]['base']), copy.deepcopy(P[k]['now'])))
           for k in ('P1', 'P2', 'P3', 'P4')}
    return all(got.values()), got


@case('P0 정상 대조군: v7 OK·합산 일치·F3 통과')
def _():
    o = ev7(P['P0'])
    return o['overall'] == 'OK' and o['account_sum']['status'] == '일치' and F3.judge(o)[0], (o['overall'], F3.judge(o))


for k, label in (('P1', '규칙 지문 미확인(HOLD)'), ('P2', '규칙 지문 불일치(ANOMALY)'), ('P4', '거래소 완료 조회 미완료(HOLD)')):
    @case(f'{k} {label}: 합산 숫자는 일치로 남지만 F3 불통과')
    def _(k=k):
        o = ev7(P[k])
        ok, why = F3.judge(o)
        return o['account_sum']['status'] == '일치' and o['overall'] != 'OK' and not ok, dict(overall=o['overall'], f3=why)


@case('P3 두 기준 2030년(현재 관측 2026) → BASE_IN_FUTURE 이상·합산 불일치·F3 불통과')
def _():
    o = ev7(P['P3'])
    return ('ACCOUNT_SUM_BASE_IN_FUTURE' in codes(o) and o['account_sum']['status'] == '불일치'
            and not F3.judge(o)[0]), dict(anomalies=codes(o), sum=o['account_sum']['status'])


@case('P3b 현금 기준만 미래 → KRW 만 불일치, 코인 합산은 일치 유지')
def _():
    d = copy.deepcopy(P['P0'])
    d['registry']['cash_base']['base_at'] = '2030-01-01T00:00:00Z'
    o = ev7(d)
    c = o['account_sum']['currencies']
    return c['KRW']['status'] == '불일치' and c['BTC']['status'] == '일치' and c['ETH']['status'] == '일치', {k: v['status'] for k, v in c.items()}


@case('P3c 기준 시각과 현재 관측의 시계 관계 없음 → 그 통화 합산 보류(이상 아님)')
def _():
    d = copy.deepcopy(P['P0'])
    for side in ('base', 'now'):
        d[side]['meta']['clock']['same_source'] = [x for x in d[side]['meta']['clock']['same_source'] if 'app' not in x['axes']]
    o = ev7(d)
    return ('ACCOUNT_SUM_BASE_TIME_UNCONFIRMED' in codes(o, 'holds') and 'ACCOUNT_SUM_BASE_IN_FUTURE' not in codes(o)
            and o['account_sum']['currencies']['KRW']['status'] == '보류' and not F3.judge(o)[0]), dict(holds=codes(o, 'holds'), anomalies=codes(o))


@case('P3d 현재 관측 뒤 이벤트를 기준 포함으로 선언 → INCLUDED_EVENT_AFTER_OBSERVATION 이상')
def _():
    d = copy.deepcopy(P['P0'])
    d['registry']['capital_events'].append({'ref': 'dep-late', 'type': 'DEPOSIT', 'amount': '1000', 'currency': 'KRW',
                                            'applied_at': '2026-11-18T03:00:00Z', 'recorded_at': '2026-11-18T03:00:00Z',
                                            'axis': 'app', 'session': None})
    d['registry']['cash_base']['includes_event_refs'].append('dep-late')
    o = ev7(d)
    return 'ACCOUNT_SUM_INCLUDED_EVENT_AFTER_OBSERVATION' in codes(o) and o['account_sum']['currencies']['KRW']['status'] == '불일치', codes(o)


@case('P3e 미배정 현금 음수(기준 10000 − 배정 20000) → NEGATIVE_UNALLOCATED 이상')
def _():
    d = copy.deepcopy(P['P0'])
    d['registry']['cash_base']['amount_krw'] = '10000'
    o = ev7(d)
    return 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' in codes(o) and o['account_sum']['currencies']['KRW']['status'] == '불일치', codes(o)


@case('R v6 통화별 보류 보존: CLOSING 포지션은 그 코인만 보류, KRW 는 일치')
def _():
    d = copy.deepcopy(P['P0'])
    pos = [p for p in d['now']['db']['positions'] if p['status'] == 'OPEN']
    pos[0]['status'] = 'CLOSING'
    coin = pos[0]['coin_pair'].split('-')[1]
    o = ev7(d)
    c = o['account_sum']['currencies']
    return c[coin]['status'] == '보류' and c['KRW']['status'] == '일치', {k: v['status'] for k, v in c.items()}


@case('F 파일 판정기 CLI: P0 rc0 · P1 rc1')
def _():
    import subprocess, tempfile
    rcs = {}
    for k in ('P0', 'P1'):
        with tempfile.NamedTemporaryFile('w', suffix='.json', delete=False, encoding='utf-8') as f:
            json.dump(ev7(P[k]), f, ensure_ascii=False, default=str)
        rcs[k] = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'f3.py'), '--p7', f.name], capture_output=True).returncode
        os.unlink(f.name)
    return rcs == {'P0': 0, 'P1': 1}, rcs


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    lines = [f"{f} sha256={hashlib.sha256(open(os.path.join(HERE, f), 'rb').read()).hexdigest()}" for f in ('reconcile.py', 'f3.py')]
    n = ok = 0
    for name, fn in CASES:
        n += 1
        try:
            passed, detail = fn()
        except Exception as e:  # noqa: BLE001
            passed, detail = False, f'{type(e).__name__}: {e}'
        ok += bool(passed)
        lines.append(f"{'PASS' if passed else 'FAIL'} | {name} | {json.dumps(detail, ensure_ascii=False, default=str)[:300]}")
    lines.append(f'합계 {n} 통과 {ok} 실패 {n - ok}')
    out = '\n'.join(lines) + '\n'
    open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write(out)
    print(out, end='')
    return 0 if ok == n else 1


if __name__ == '__main__':
    sys.exit(main())
