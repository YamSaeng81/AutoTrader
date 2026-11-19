"""v2 원문(dec-scorecard-v2-20261118, 수정 안 함)에 총괄 v1.191 §3 두 반례 입력을 그대로 넣고 v3 계약으로 검사한다.
목적: 보완 전에 실패를 먼저 남긴다. 기대: 두 사례 모두 FAIL(= v2 가 계약을 어김). 종료값 0 은 '두 사례 모두 실패 재현'을 뜻한다.
사용: python -I red_v2.py --out <폴더>
"""
import argparse, hashlib, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EVID = os.path.dirname(os.path.dirname(HERE))
V2 = os.path.join(EVID, 'dec-scorecard-v2-20261118', 'scorecard', 'scorecard.py')
OBS = os.path.join(EVID, 'review-16595682', 'observations.json')


def load(path):
    spec = importlib.util.spec_from_file_location('scorecard_v2', path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def sha(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()


def need_fee(m, inp):
    # 매수 정산 시각 미상 — 매수 수수료 25 의 월 귀속을 모른다. 알려진 매도 수수료 26 은 부분 합이고 확정 합이 아니다.
    r = m.build(json.loads(json.dumps(inp)))
    c = r['sessions'][0]
    return r, [('수수료 확정 합을 내지 않음', c.get('fees_paid') is None),
               ('알려진 부분 합 26 을 따로', c.get('fees_known_part') == '26'),
               ('수수료 상태 미확정', c.get('fees_status') == '미확정')]


def need_chrono(m, inp):
    # 매도 정산이 매수 정산보다 24시간 앞선다 — 시각 모순은 계약 위반으로 거절(산출 없음)해야 한다.
    try:
        r = m.build(json.loads(json.dumps(inp)))
    except m.Bad as e:
        return {'rejected': str(e)}, [('시각 모순 거절', True)]
    c = r['sessions'][0]
    return r, [('시각 모순 거절', False), ('보유시간 음수를 내지 않음', (c.get('holding_hours_median') or 0) >= 0)]


CHECKS = {'fee_time_unknown': need_fee, 'sell_before_buy': need_chrono}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    ap.add_argument('--target', default=V2, help='검사할 scorecard.py (기본 v2 원문). v3 확인 때 ./scorecard.py — 그때 종료값 1(재현 안 됨)이 통과')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    v2 = load(a.target)
    obs = json.loads(open(OBS, 'rb').read().decode('utf-8'))
    lines = [f'대상 {os.path.relpath(a.target, EVID).replace(os.sep, "/")} sha256 {sha(a.target)}',
             f'입력 {os.path.relpath(OBS, EVID).replace(os.sep, "/")} sha256 {sha(OBS)}']
    reproduced = 0
    for name, chk in CHECKS.items():
        inp = obs[name]['input']
        r, res = chk(v2, inp)
        failed = [lbl for lbl, ok in res if not ok]
        reproduced += bool(failed)
        lines.append(f"{'FAIL(재현)' if failed else 'PASS(재현 안 됨)'} | {name} | 어긴 계약: {'; '.join(failed) or '-'}")
        with open(os.path.join(a.out, name + '.v2.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'input': inp, 'v2_result': r, 'v3_contract': [{'check': l, 'ok': o} for l, o in res]}, f, ensure_ascii=False, indent=1)
    lines.append(f'반례 {len(CHECKS)} 중 v2 실패 재현 {reproduced}')
    with open(os.path.join(a.out, 'red.out.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 0 if reproduced == len(CHECKS) else 1


if __name__ == '__main__':
    sys.exit(main())
