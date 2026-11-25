#!/usr/bin/env python3
# N03 10:00 보고·09:30 장부 원문 판정(총괄 v1.250 §2 — 기존 N03 판정 계약을 기계 검사로 옮긴 것, 보고를 만들거나 다시 보내지 않는다).
# 단순 파일 존재·첫 줄 이모지만으로 통과시키지 않는다. 항목마다 PASS/FAIL 줄을 남기고, 하나라도 FAIL 이면 BLOCK.
# 사용: n03check.py <보존 폴더> <회차 YYYYMMDD-1000>
#   보존 폴더: <회차>.txt · last_send · ledger.txt · files.txt(원본 이름·mtime epoch·sha256 줄) · pins.txt(배치가 읽은 설치 상태·로그 집계)
# 종료: 0 = PASS(U0 진입 조건 충족) · 1 = BLOCK(조건 미충족 — U0 금지) · 2 = 형식 결함·읽기 실패(BLOCK 과 같게 U0 금지)
import datetime as d
import pathlib
import re
import sys

K = d.timezone(d.timedelta(hours=9))
# 설치 핀(현재 운영 기대값 — N03 계약·env-read 11-25 관측과 같다). 현재값이라고 가정하지 않고 pins.txt 원문과 대조한다.
PIN = {
    'HEAD': '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d',
    'DIRTY': '0',
    'backend': 'Image=sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f StartedAt=2026-11-16T05:16:14.129686133Z RestartCount=0 Status=running',
    'frontend': 'Image=sha256:9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e StartedAt=2026-11-16T07:15:16.447837224Z RestartCount=0 Status=running',
    'INSTALLED_SHA': 'c669ccdc6e248ce8e55e9a7ac7405197d266fa18',
    'crontab': 'f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b',
    'daily_check.sh': 'ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc',
    'daily_checks.sql': '653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8',
    'SID_LEDGER_SHA': '8bfe8a9fa896f400b3fb6681fef021c99d6ba43b',
    'sid_ledger.sql': '02a8585021fb2a1a52d9b3b56b60e957cb71b9ca3c11bf819595df15cf334f1c',
    'past_ledger': '0',
}
LOGKEYS = ['Connection leak detection', 'Connection is not available', 'REST 강제 갱신 시세가 가격이 아님', 'REST 폴백 시세 항목 건너뜀',
           '[시세 보강] 시세 항목 건너뜀', '[시세 보강] 회차 오류', '[시세 보강] 시세 처리 실패', 'DOCTYPE', '<html']
# 보고 머리(3번째 줄부터 '—' 전까지)에 허용하는 🟡 줄 — 과거 회차 판정에서 매매·정산 이상이 아니라고 본 유형만. 그 밖의 🟡/🔴/⚪ 머리 줄은 BLOCK(사람 확인).
OKHEAD = [r'🟡 외부 통신 실패 ERROR [0-9]+건',
          r'🟡 66세션 경보: 적재 뒤처짐 [0-9]+회 — 전부 거래지원 종료 확인 마켓\(KRW-BLAST\), 다른 코인 이상 없음',
          r'🟡 KRW-BLAST 보충 수집 실패 — 상세 원인 미확인 [0-9]+건',
          r'🟡 검증 코인 KRW-BLAST 캔들 [0-9 ]+건 — 거래지원 종료 확인된 마켓']

res = []


def ck(name, ok, detail=''):
    res.append((name, bool(ok), detail))


def kst(e):
    return d.datetime.fromtimestamp(e, d.timezone.utc).astimezone(K)


def main(folder, tag):
    m = re.fullmatch(r'([0-9]{4})([0-9]{2})([0-9]{2})-1000', tag)
    if not m:
        return 2
    run = d.datetime(int(m[1]), int(m[2]), int(m[3]), 10, 0, tzinfo=K)
    prev = run - d.timedelta(hours=12)
    P = pathlib.Path(folder)
    rep = (P / (tag + '.txt')).read_text(encoding='utf-8').split('\n')
    ls = (P / 'last_send').read_text(encoding='utf-8')
    led = (P / 'ledger.txt').read_text(encoding='utf-8')
    files = {}
    for ln in (P / 'files.txt').read_text(encoding='utf-8').splitlines():
        n, e, h = ln.split(' ')
        files[n] = (int(e), h)
    pins = {}
    for ln in (P / 'pins.txt').read_text(encoding='utf-8').splitlines():
        k, _, v = ln.partition(' ')
        if k == 'logcount':
            c, _, key = v.partition(' ')
            pins['logcount:' + key] = c
        else:
            pins[k] = v
    ld = '%04d%02d%02d' % (run.year, run.month, run.day)

    # 1. 생성 시각(원본 mtime, 서버 epoch) — 그 회차 보고·그날 09:30 장부
    re_, _ = files.get(tag + '.txt', (0, ''))
    ck('보고 생성 시각', run.timestamp() <= re_ <= run.timestamp() + 600, kst(re_).isoformat() if re_ else '없음')
    le, _ = files.get(ld + '.txt', (0, ''))
    l0 = run.replace(hour=9, minute=30).timestamp()
    ck('장부 생성 시각', l0 <= le <= l0 + 300, kst(le).isoformat() if le else '없음')
    ck('last_send 시각', files.get('last_send', (0, ''))[0] >= run.timestamp(), '')

    # 2. 보고 본문
    if rep and rep[-1] == '':
        rep = rep[:-1]
    ck('머리 🟢', len(rep) > 3 and rep[0] == '[정기 점검] 🟢 점검 항목 정상', rep[0] if rep else '')
    l2 = '실행 %s KST · 구간 %s ~ (12시간) · 운영 0e1d2a02 · 점검 스크립트 c669ccdc6e24' % (run.strftime('%Y-%m-%d %H:%M'), prev.strftime('%m-%d %H:%M'))
    ck('실행·구간·운영·점검판', len(rep) > 1 and rep[1] == l2, rep[1] if len(rep) > 1 else '')
    try:
        dash = rep.index('—')
    except ValueError:
        dash = -1
    ck('머리 구분선', dash >= 2, '')
    heads = rep[2:dash] if dash >= 2 else []
    bad = [h for h in heads if not any(re.fullmatch(p, h) for p in OKHEAD)]
    ck('머리 경고 줄이 허용 유형뿐', dash >= 2 and not bad, ' | '.join(bad))
    body = rep[dash + 1:] if dash >= 2 else []

    def one(prefix):
        xs = [x for x in body if x.startswith(prefix)]
        return xs[0] if len(xs) == 1 else None

    a = one('앱: ')
    ck('앱 running·재시작0·이미지', a is not None and a.startswith('앱: running · 재시작 0 · 기동 2026-11-16T05:16:14.129686133Z · 이미지 2c5d84276cdb'), a or '')
    g = one('로그 범위: ')
    ck('로그 범위 재생성 공백 없음', g is not None and g.endswith('재생성 공백 없음)'), g or '')
    db = one('DB: ')
    ck('DB 실자금·미결·66세션', db == 'DB: Flyway 96 · SID 203 밖 실자금 보유 0 · 미결 0 · 정리 대기 0 · 격리 대상 0 · 66세션: 원래 66 중 RUNNING 63 · 지정 종료 STOPPED 3/3(576 577 578) · 구간 SID 203 밖 실자금 매수 0', db or '')
    s = one('SID 203: ')
    ck('SID203 CREATED·20200·보유0·주문0·정산 이상0', s == 'SID 203: CREATED · 잔액 20200.00 · 보유 0 · 미결 0 · 구간 주문 매수 0 매도 0 · 정산 확인 불가 0 · 보고값≠반영값 0 · 미해결 정산 불일치(전체) 0', s or '')
    b = one('BTC H1 입력')
    ck('B1 true·B3 20/20', b is not None and ' B1 true ' in b and b.endswith('B3 최근 20일 마감 봉 20/20'), b or '')
    e = one('ERROR(구간): ')
    em = re.fullmatch(r'ERROR\(구간\): 전체 ([0-9]+) · 공유 풀 미등록 0 · 캔들 판정 불가 0 · 상장 폐지 404 0 · 통신 실패 ([0-9]+) · 미분류 0', e or '')
    ck('ERROR 전부 통신 실패·미분류0', em is not None and em[1] == em[2], e or '')
    reds = [x for x in body if '🔴' in x or '⚪' in x]
    okred = [x for x in reds if re.fullmatch(r'66세션: 🔴 적재 뒤처짐 ([0-9]+)회', x)]
    blast = [h for h in heads if h.startswith('🟡 66세션 경보: 적재 뒤처짐')]
    red_ok = len(reds) == len(okred) and (not okred or (len(okred) == 1 and len(blast) == 1 and re.search(r'([0-9]+)회', okred[0])[1] == re.search(r'([0-9]+)회', blast[0])[1]))
    ck('본문 🔴/⚪ 는 KRW-BLAST 적재 뒤처짐(같은 횟수)뿐', red_ok, ' | '.join(reds))
    last = rep[-1] if rep else ''
    lm = re.fullmatch(r'([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}) KST 보고 전송 성공 · 🟢 점검 항목 정상', last)
    ck('보고 전송 성공 줄', lm is not None and lm[1].startswith(run.strftime('%Y-%m-%d 10:0')), last)
    ck('last_send = 보고 끝 줄', ls.rstrip('\n') == last, ls.rstrip('\n'))

    # 3. 09:30 장부(ledger.txt)
    sec = {}
    cur = None
    for ln in led.splitlines():
        mm = re.match(r'== (L[0-5]) ', ln)
        if mm:
            cur = mm[1]
            sec[cur] = []
        elif cur:
            sec[cur].append(ln)
    ck('장부 L0~L5·ROLLBACK·끝', sorted(sec) == ['L0', 'L1', 'L2', 'L3', 'L4', 'L5'] and 'ROLLBACK' in led.splitlines() and led.splitlines()[-1] == '== 끝', '')
    L0 = sec.get('L0', [])
    ck('L0 203 CREATED 20200', '(1 row)' in L0 and any(re.search(r'\| 203 \| KRW-BTC +\| CREATED \| +20200\.00 \| 20200 \| t +\| +20200\.00 \| - +\| - +\| -$', x) for x in L0), '')
    for k in ('L1', 'L2', 'L3'):
        ck(k + ' 0행', '(0 rows)' in sec.get(k, []), '')
    L4 = sec.get('L4', [])
    ck('L4 balance_diff0·raw_diff0·미확인0·순체결0·보유0', '(1 row)' in L4 and any(re.match(r' CREATED \| +20200\.00 \| +20200 \| +0\.00 \| +20200 \| +0\.00 \| +0 \| +0 \| +0 \| ', x) for x in L4), '')
    L5 = ' '.join(sec.get('L5', []))
    ck('L5 중단 기록0·CREATED', all(re.search(p, L5) for p in (r'서킷브레이커 +\| 0 +\|', r'포지션 한도 도달 +\| 0 +\|', r'공유 풀 손실 한도\(진입 차단\) \| 0 +\|', r'세션 최종 상태 +\| CREATED \|')) and '(4 rows)' in L5, '')

    # 4. 설치 핀·로그 집계(배치가 같은 시점에 읽은 원문)
    for k, v in PIN.items():
        ck('핀 ' + k, pins.get(k) == v, pins.get(k, '없음'))
    for k in LOGKEYS:
        ck('로그 0: ' + k, pins.get('logcount:' + k) == '0', pins.get('logcount:' + k, '없음'))
    return 0 if all(ok for _, ok, _ in res) else 1


if __name__ == '__main__':
    try:
        rc = main(sys.argv[1], sys.argv[2]) if len(sys.argv) == 3 else 2
    except Exception as x:
        print('FORMAT 읽기·형식 결함: %s' % type(x).__name__)
        sys.exit(2)
    for n, ok, det in res:
        print('%s %s%s' % ('PASS' if ok else 'FAIL', n, (' — ' + det) if det and not ok else ''))
    print('N03 %s 항목 %d 실패 %d' % ('PASS' if rc == 0 else ('BLOCK' if rc == 1 else 'FORMAT'), len(res), sum(1 for _, ok, _ in res if not ok)))
    sys.exit(rc)
