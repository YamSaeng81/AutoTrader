"""parse.py 가상 출력 시험 — psql·DB 실행 없음. 사용: python -I tests.py --out <폴더>"""
import argparse, hashlib, json, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REG = {'registry_version': 'registry-3', 'sessions': [{'kind': 'LIVE', 'id': 203}, {'kind': 'DYNAMIC', 'id': 900}]}
T = '\t'
META = T.join(['META', 'v1', '2026-11-20 10:31:28.1+09', '2026-11-20 10:31:28.2+09', '100:100:', '96', 'repeatable read'])
ID = [T.join(['ID_S', 'LIVE', '203', 'RUNNING']), T.join(['ID_S', 'DYNAMIC', '900', 'RUNNING']),
      T.join(['ID_O', '11', 'LIVE', '203', 'FILLED', 'OK']), T.join(['ID_P', '7', 'LIVE', '203', 'OPEN'])]
S2 = [T.join(['S', 'LIVE', '203', 'REAL', 'X', 'H1', 'KRW-BTC', '20200', '11000.5', 'RUNNING', '2026-11-06 19:07:36+09', '\\N']),
      T.join(['O', '11', 'LIVE', '203', '7', 'KRW-BTC', 'BUY', 'MARKET', '9200', '9200', 'c-11', 'FILLED', '0.0001', 'u-11',
              '2026-11-20 09:00:00+09', '2026-11-20 09:00:01+09', '\\N', '9195.5', '4.59', '9195.5', '4.59', 't', 'f', '2026-11-20 09:00:01+09']),
      T.join(['P', '7', 'LIVE', '203', 'KRW-BTC', '0.0001', 'OPEN', '9200.09', '4.59', 'abc', '2026-11-20 09:00:01+09', '\\N', '\\N'])]
END = T.join(['END', '2026-11-20 10:31:28.9+09'])


def run(lines, rc=0):
    d = tempfile.mkdtemp()
    raw, reg, out = (os.path.join(d, x) for x in ('raw.tsv', 'reg.json', 'out.json'))
    open(raw, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    json.dump(REG, open(reg, 'w', encoding='utf-8'))
    p = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'parse.py'), '--raw', raw, '--psql-rc', str(rc), '--registry', reg, '--out', out],
                       capture_output=True)
    return p.returncode, json.load(open(out, encoding='utf-8'))


CASES = [
    ('GO 정상 — 2단계 조각', lambda: run([META] + ID + [T.join(['GATE', 'GO'])] + S2 + [END]), lambda rc, o: rc == 0 and o['orders'][0]['settlement_final'] is True),
    ('STOP — 소유 세션 없는 주문, 2단계 없음', lambda: run([META] + ID[:3] + [T.join(['ID_O', '12', 'LIVE', '\\N', 'SUBMITTED', 'NO_SESSION_ID'])] + ID[3:] + [T.join(['GATE', 'STOP']), END]),
     lambda rc, o: rc == 10 and len(o['unregistered_ids']) == 1),
    ('STOP — 등록 밖 실자금 세션 RUNNING', lambda: run([META, T.join(['ID_S', 'DYNAMIC', '901', 'RUNNING']), T.join(['GATE', 'STOP']), END]),
     lambda rc, o: rc == 10),
    ('형식 결함 — GO 인데 식별 목록에 등록 밖 포지션', lambda: run([META] + ID + [T.join(['ID_P', '8', 'LIVE', '204', 'OPEN']), T.join(['GATE', 'GO'])] + S2 + [END]),
     lambda rc, o: rc == 30),
    ('형식 결함 — STOP 인데 2단계 행', lambda: run([META] + ID + [T.join(['GATE', 'STOP'])] + S2 + [END]), lambda rc, o: rc == 30),
    ('형식 결함 — 열 수', lambda: run([META] + ID + [T.join(['GATE', 'GO']), S2[0] + T + 'x', END]), lambda rc, o: rc == 30),
    ('형식 결함 — END 없음(중간 끊김)', lambda: run([META] + ID + [T.join(['GATE', 'GO'])] + S2), lambda rc, o: rc == 30),
    ('psql 실패 — 부분 출력을 값으로 안 씀', lambda: run([META], rc=3), lambda rc, o: rc == 20 and 'sessions' not in o),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    os.makedirs(a.out, exist_ok=True)
    lines = [f"parse.py sha256={hashlib.sha256(open(os.path.join(HERE, 'parse.py'), 'rb').read()).hexdigest()}"]
    ok = 0
    for name, f, check in CASES:
        rc, o = f()
        good = check(rc, o)
        ok += good
        lines.append(f"{'PASS' if good else 'FAIL'} | {name} | rc={rc} {json.dumps(o.get('error'), ensure_ascii=False)}")
    lines.append(f'합계 {len(CASES)} 통과 {ok} 실패 {len(CASES) - ok}')
    open(os.path.join(a.out, 'tests.out.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 0 if ok == len(CASES) else 1


if __name__ == '__main__':
    sys.exit(main())
