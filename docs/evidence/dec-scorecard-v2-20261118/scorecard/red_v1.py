"""v1 원문(dec-scorecard-20261118, 수정 안 함)에 총괄 v1.190 §3 네 반례 입력을 그대로 넣고 v2 계약으로 검사한다.
목적: 보완 전에 실패를 먼저 남긴다. 기대: 네 사례 모두 FAIL(= v1 이 계약을 어김), 종료값 0 은 '네 사례 모두 실패 재현'을 뜻한다.
사용: python -I red_v1.py --out <폴더>
"""
import argparse, hashlib, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EVID = os.path.dirname(os.path.dirname(HERE))
V1 = os.path.join(EVID, 'dec-scorecard-20261118', 'scorecard', 'scorecard.py')
OBS = os.path.join(EVID, 'review-f14ff757', 'observations.json')


def load(path):
    spec = importlib.util.spec_from_file_location('scorecard_v1', path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def sha(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()


# v2 계약(총괄 v1.190 §3 표의 '필요한 계약' 열) — v1 출력에 대해 참이어야 하는 조건
def need_s1(r):
    c = r['sessions'][0]
    # 기말 보유 0 → 기말 미실현 0. −10 은 기간 중 미실현 '증감'이며 기말 미실현 칸에 들어가면 안 된다.
    return [('기말 미실현 칸이 0 또는 별도 칸', c.get('unrealized_end') == '0'),
            ('-10 은 증감 칸으로만', c.get('unrealized_change') == '-10' and c.get('unrealized_derived') is None),
            ('없는 원가 → 기초 미실현 미확인', c.get('unrealized_start') is None)]


def need_s2(r):
    # 월 밖(1월) 배정은 제외 또는 계약 위반 거절. 12월 손익 −50 확정이면 실패.
    c = r['sessions'][0]
    return [('월 밖 배정을 손익에서 빼지 않음', c['month_pnl'] != '-50')]


def need_s3(r):
    # 실계좌 손익 10 과 실자금 세션(LIVE) 10 → 차 0. 모의(PAPER) 10 을 실계좌 대조에 넣으면 실패.
    return [('실계좌−실자금 세션 = 0', r['account_minus_sessions'] == '0'),
            ('실자금·모의 혼합 전체 합 없음', not any(a['group'] == 'all' for a in r['aggregates']))]


def need_s4(r):
    d = r['sessions'][0]['drawdown']
    return [('최대 금액 낙폭 −400', d.get('value_krw') == '-400' or d.get('max_amount_krw') == '-400'),
            ('최대 비율 낙폭 −50%(금액 최대 지점의 −40% 아님)', d.get('pct') == '-50.00' or d.get('max_pct') == '-50.00')]


CHECKS = {'S1_carry_in_unrealized': need_s1, 'S2_out_of_period_flow': need_s2,
          'S3_paper_real_account_mix': need_s3, 'S4_drawdown_percentage': need_s4}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    v1 = load(V1)
    obs = json.loads(open(OBS, 'rb').read().decode('utf-8'))
    lines = [f'v1 {os.path.relpath(V1, EVID).replace(os.sep, '/')} sha256 {sha(V1)}', f'입력 {os.path.relpath(OBS, EVID).replace(os.sep, '/')} sha256 {sha(OBS)}']
    reproduced = 0
    for name, chk in CHECKS.items():
        inp = obs[name]['input']
        r = v1.build(json.loads(json.dumps(inp)))
        res = chk(r)
        failed = [lbl for lbl, ok in res if not ok]
        reproduced += bool(failed)
        lines.append(f"{'FAIL(재현)' if failed else 'PASS(재현 안 됨)'} | {name} | 어긴 계약: {'; '.join(failed) or '-'}")
        with open(os.path.join(a.out, name + '.v1.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'input': inp, 'v1_result': r, 'v2_contract': [{'check': l, 'ok': o} for l, o in res]}, f, ensure_ascii=False, indent=1)
    lines.append(f'반례 {len(CHECKS)} 중 v1 실패 재현 {reproduced}')
    with open(os.path.join(a.out, 'red.out.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 0 if reproduced == len(CHECKS) else 1


if __name__ == '__main__':
    sys.exit(main())
