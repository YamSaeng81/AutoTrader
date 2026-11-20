"""P7 v8·F3 v8 시험(총괄 v1.204 §2 P1·F1~F3, v1.206 §4-1). 직접 영향 사례만 — v7 12건·옛 형식 260건 전체는 반복하지 않는다.
입력: review-09f6bd4a/contract-probes.json 의 P0(시험용 가상 정상 입력)과 총괄 v1.204 탐침(review-43d729db) 변형.
RED: v7 원문이 P1 음수 이상을 예약 보류로 가리고, v7 F3 가 F1~F3 모순 파일을 통과시키는 것을 먼저 고정.
사용: python -I cases_v8.py --out <폴더>   종료값: 전부 통과 0, 하나라도 실패 1. 운영·DB·네트워크 접속 없음.
"""
import argparse, copy, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.normpath(os.path.join(HERE, '..', '..'))


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


OLD = os.path.join(EV, 'dec-registry-p7v7-20261120', 'p7v7')
V7, F7 = load(os.path.join(OLD, 'reconcile.py'), 'p7v7'), load(os.path.join(OLD, 'f3.py'), 'f3v7')
V8, F8 = load(os.path.join(HERE, 'reconcile.py'), 'p7v8'), load(os.path.join(HERE, 'f3.py'), 'f3v8')
P0 = [p['source'] for p in json.load(open(os.path.join(EV, 'review-09f6bd4a', 'contract-probes.json'), encoding='utf-8'))['probes']
      if p['name'][:2] == 'P0'][0]


def ev(V, d):
    return V.evaluate(copy.deepcopy(d['registry']), copy.deepcopy(d['base']), copy.deepcopy(d['now']))


def codes(o, k='anomalies'):
    return sorted({x['code'] for x in o.get(k) or []})


def p1(reserve=True):
    d = copy.deepcopy(P0)
    d['registry']['cash_base']['amount_krw'] = '10000'
    d['now']['db']['sessions'][0]['available_krw'] = '3000.00'
    if reserve:
        d['now']['db']['orders'].append(dict(d['now']['db']['orders'][1], id=89, position_id=None, state='PARTIAL_FILLED',
                                             filled_quantity='0.001', reserved_krw='1000', exchange_order_id='u-89', client_order_id='c-89'))
    return d


def p1_time_unknown():
    """미배정 계산 자체가 불명인 경우 — 기준 포함 선언에서 기준 이전 이벤트 alloc-203 을 빼면 기준 경계가 불명(BASE_BOUNDARY 보류).
    시험 입력은 모든 축을 같은 시계로 가상 선언하므로 시각 축 불명 대신 이 경로로 '계산 불명'을 만든다."""
    d = p1(reserve=False)
    d['registry']['cash_base']['includes_event_refs'] = []
    return d


def edit(f):
    o = ev(V8, P0)
    f(o)
    return o


F_EDITS = {
    'F1 OK인데 이상 추가': lambda x: x['anomalies'].append({'code': 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED'}),
    'F2 OK인데 보류 추가': lambda x: x['holds'].append({'code': 'RULESET_UNCONFIRMED', 'blocks': []}),
    'F3 code.now 만 다름': lambda x: x['code'].update(now='a' * 40),
    'F4 anomalies 누락': lambda x: x.pop('anomalies'),
    'F5 holds 가 문자열': lambda x: x.update(holds=''),
    'F6 observation_stable 문자열': lambda x: x.update(observation_stable='true'),
    'F7 code.now_ref 없음': lambda x: x['code'].update(now_ref=None),
    'F8 code.base 형식 아님': lambda x: x['code'].update(base='zz'),
}

CASES = []


def case(name):
    def deco(f):
        CASES.append((name, f))
        return f
    return deco


@case('대조군 P0: v8 OK·F3 v8 통과')
def _():
    o = ev(V8, P0)
    return o['overall'] == 'OK' and F8.judge(o)[0]


@case('대조군 P0: v8 출력이 v7 출력과 같음')
def _():
    return json.dumps(ev(V8, P0), sort_keys=True, default=str) == json.dumps(ev(V7, P0), sort_keys=True, default=str)


@case('RED-P1 v7: 미배정 −10000 인데 예약 보류에 가려 음수 이상 없음')
def _():
    o = ev(V7, p1())
    return o['account_sum']['currencies']['KRW']['unallocated'] == '-10000' and 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' not in codes(o)


@case('GREEN-P1 v8: 음수 이상과 예약 보류를 함께 남김(ANOMALY)')
def _():
    o = ev(V8, p1())
    return ('ACCOUNT_SUM_NEGATIVE_UNALLOCATED' in codes(o) and 'ACCOUNT_SUM_PARTIAL_RESERVATION' in codes(o, 'holds')
            and o['overall'] == 'ANOMALY' and not F8.judge(o)[0])


@case('GREEN-P1 v8: 예약 없어도 음수 이상(v7 과 같음)')
def _():
    return 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' in codes(ev(V8, p1(False))) and 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' in codes(ev(V7, p1(False)))


@case('GREEN-P1 v8: 미배정 계산 자체가 불명(기준 경계 불명)이면 음수 이상 확정 안 함')
def _():
    o = ev(V8, p1_time_unknown())
    return ('ACCOUNT_SUM_BASE_BOUNDARY' in codes(o, 'holds') and 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' not in codes(o))


for _n, _f in F_EDITS.items():
    def _mk(n=_n, f=_f):
        @case(f'F3 {n}: v7 판정 기록·v8 불통과')
        def _():
            o = edit(f)
            try:
                old = F7.judge(o)[0]
            except Exception:
                old = '예외'
            CASE_NOTES[n] = old
            return F8.judge(o)[0] is False
    _mk()
CASE_NOTES = {}


@case('RED-F1~F3 v7: 이상/보류 추가·code.now 변경 파일을 통과시킴')
def _():
    return all(F7.judge(edit(F_EDITS[k]))[0] for k in ('F1 OK인데 이상 추가', 'F2 OK인데 보류 추가', 'F3 code.now 만 다름'))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=False)
    lines, fail = [], 0
    for name, f in CASES:
        try:
            ok, err = bool(f()), None
        except Exception as e:
            ok, err = False, f'{type(e).__name__}: {e}'
        fail += not ok
        lines.append(f"{'통과' if ok else '실패'} {name}" + (f' — {err}' if err else ''))
    lines.append('v7 F3 의 형식 변형 판정(참고): ' + json.dumps(CASE_NOTES, ensure_ascii=False))
    lines.append(f'합계 {len(CASES)} 통과 {len(CASES) - fail} 실패 {fail}')
    text = '\n'.join(lines) + '\n'
    open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write(text)
    print(text, end='')
    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())
