"""성적표 S1·S2 보완 시험(총괄 v1.204 §2·§3-2, v1.206 §4-1). 직접 영향 사례만 — 기존 24건 전체는 반복하지 않는다.
입력: 보존된 v3 정상 원천(dec-scorecard-source-v3-20261120/run1/file/source.json)과 총괄 탐침(review-43d729db) S1·S2 변형.
RED: v3 원문 변환기·v4 성적표가 반례를 확정값으로 내는 것을 먼저 고정. GREEN: v4 변환기·v5 성적표가 미확정으로 낸다.
사용: python -I cases.py --out <폴더>   종료값: 전부 통과 0, 하나라도 실패 1. 운영·DB·네트워크 접속 없음.
"""
import argparse, copy, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.dirname(HERE)
OLD = os.path.join(EV, 'dec-scorecard-source-v3-20261120')


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


C3, V4 = load(os.path.join(OLD, 'convert.py'), 'c3'), load(os.path.join(OLD, 'scorecard', 'scorecard.py'), 'v4')
C4, V5 = load(os.path.join(HERE, 'convert.py'), 'c4'), load(os.path.join(HERE, 'scorecard', 'scorecard.py'), 'v5')
SRC = json.load(open(os.path.join(OLD, 'run1', 'file', 'source.json'), encoding='utf-8'))
SAVED = json.load(open(os.path.join(OLD, 'run1', 'file', 'scorecard', 'scorecard.json'), encoding='utf-8'))


def run(C, V, s):
    i, rep = C.convert(copy.deepcopy(s))
    return i, rep, V.build(copy.deepcopy(i))


def s1():
    s = copy.deepcopy(SRC)
    s['clock'] = {'same_source': []}
    s['period']['axis'] = 'db'
    s['sessions'][0]['created_at'] = s['period']['from']
    s['sessions'][0]['boundary']['start'] = None
    s['sessions'][0]['orders'] = []
    s['registry_events'] = []
    return s


def s2():
    s = copy.deepcopy(SRC)
    s['clock'] = {'same_source': []}
    return s


CASES = []


def case(name):
    def deco(f):
        CASES.append((name, f))
        return f
    return deco


@case('RED-S1 v3 변환기: 축 관계 없는 생성 시각으로 새 세션·기초0 확정')
def _():
    i, _, c = run(C3, V4, s1())
    return i['sessions'][0].get('started_in_period') is True and c['sessions'][0]['start_equity'] == '0'


@case('GREEN-S1 v4: 새 세션·기초0 확정 안 함, 기초 미확정·보고에 생성 소속 미확정')
def _():
    i, rep, c = run(C4, V5, s1())
    x, card = i['sessions'][0], c['sessions'][0]
    return ('started_in_period' not in x and 'start' not in x and 'created_at' not in x
            and card['start_equity'] is None and rep['sessions'][0]['started_in_period'] == '미확정'
            and rep['sessions'][0]['creation']['status'] == '미확정')


@case('GREEN-S1 v4: 다른 세션은 계속 계산(세션 수 유지)')
def _():
    i3, _, _ = run(C3, V4, s1())
    i4, _, c = run(C4, V5, s1())
    return len(i4['sessions']) == len(i3['sessions']) == len(c['sessions'])


@case('GREEN-S1 v4: 같은 축 선언이 있으면 기존대로 새 세션·기초0')
def _():
    s = s1()
    s['period']['axis'] = 'app'
    i, _, c = run(C4, V5, s)
    return i['sessions'][0].get('started_in_period') is True and c['sessions'][0]['start_equity'] == '0'


@case('RED-S2 v4 성적표: 매도 월 소속 미상인데 왕복0·승0 확정')
def _():
    _, _, c = run(C3, V4, s2())
    card = c['sessions'][0]
    return card['realized_in_period'] is None and card['round_trips_closed'] == 0 and card['wins'] == 0


@case('GREEN-S2 v5: 전체 왕복·승패 미확정, 확인된 부분 합 0 보존, 사유 기록')
def _():
    _, _, c = run(C4, V5, s2())
    card = c['sessions'][0]
    return (card['realized_in_period'] is None and card['round_trips_closed'] is None and card['wins'] is None
            and card['losses'] is None and card['round_trips_closed_known_part'] == 0 and card['wins_known_part'] == 0
            and any('월 소속 미상' in r['why'] for r in card['round_trips_unconfirmed']))


@case('GREEN-S2 v5: 매도 수량이 확정으로 부족하면(미종료) 미확정 목록에 넣지 않음')
def _():
    t = [{'buy': {'qty': '2', 'funds': '200', 'fee': '0', 'value_status': '확정', 'settled_at': '2026-10-02T00:00:00+00:00'},
          'sells': [{'qty': '1', 'funds': '110', 'fee': '0', 'value_status': '확정', 'settled_at': None}]}]
    p = (V5.tstamp('2026-10-01T00:00:00+00:00', 'f'), V5.tstamp('2026-11-01T00:00:00+00:00', 't'))
    m = V5.trade_metrics(t, p, 'x')
    return m['round_trips_closed'] == 0 and m['round_trips_unconfirmed'] == []


@case('대조군: 보존 정상 원천의 v5 성적표 세션 카드가 v4 보존 출력과 같음')
def _():
    _, _, c = run(C4, V5, SRC)
    return c['sessions'] == SAVED['sessions']


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
            ok = bool(f())
            err = None
        except Exception as e:   # 시험 실패로 센다
            ok, err = False, f'{type(e).__name__}: {e}'
        fail += not ok
        lines.append(f"{'통과' if ok else '실패'} {name}" + (f' — {err}' if err else ''))
    lines.append(f'합계 {len(CASES)} 통과 {len(CASES) - fail} 실패 {fail}')
    text = '\n'.join(lines) + '\n'
    open(os.path.join(a.out, 'cases.out.txt'), 'w', encoding='utf-8', newline='\n').write(text)
    print(text, end='')
    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())
