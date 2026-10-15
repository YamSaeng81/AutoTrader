"""DYNAMIC 틱 롤백의 발생 후보를 운영 로그에서 찾는다 (2026-10-15 리뷰 7회차) — 읽기 전용.

배경: 5-h 시험(S16)에서 찾은 결함 — 캔들 REST 수집 실패가 트랜잭션 프록시를 지나 DYNAMIC processTick 트랜잭션을 롤백 전용으로
만들어, 그 틱의 청산 판정(손절·익절·시간 초과)이 커밋되지 못했다. 로그 흔적은 같은 스케줄러 스레드의 같은 틱 안에서 차례로 남는다.

    ① WARN  [Dynamic] 캔들 조회 실패 (KRW-XXX H1): <원인>
    ② ERROR [Dynamic] 세션 tick 오류 (id=N): Transaction silently rolled back because it has been marked as rollback-only

🔴 두 문구가 함께 나온 횟수로 발생 건수를 확정하지 않는다. 이 스크립트는 ②마다 같은 스레드에서 직전 틱 경계 이후의 ①을 붙여
"후보" 와 "다른 원인" 으로 나누고 세션·시각·원인을 표로 낸다. 후보가 실제 청산 누락이었는지는 그 시각에 세션 N 이 포지션을
감시 중이었는지(DB, 아래 SQL)와 함께 사람이 판정한다.

사용: python dynamic_rollback_candidates.py system.2026-10-*.log system.log  > candidates.tsv
      (logback 형식: "yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger - msg")

판정 보조 SQL(읽기 전용, 후보의 세션 N·시각 T 마다):
    SELECT id, coin_pair, status, opened_at, closed_at, exit_reason FROM position
     WHERE session_kind = 'DYNAMIC' AND session_id = N AND opened_at <= 'T' AND (closed_at IS NULL OR closed_at >= 'T');
"""
import re
import sys
from datetime import datetime, timedelta

WINDOW = timedelta(seconds=120)   # 한 틱 안 — 틱 주기 60초에 처리 지연 여유

LINE = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([^\]]+)\] (\w+)\s+\S+ - (.*)$')
CANDLE_FAIL = re.compile(r'\[Dynamic\] 캔들 조회 실패 \((\S+) (\S+)\): (.*)$')
TICK_ERR = re.compile(r'\[Dynamic\] 세션 tick 오류 \(id=(\d+)\): (.*)$')
ROLLBACK = 'rolled back because it has been marked as rollback-only'


def main(paths):
    pending = {}      # thread → 이 스레드에서 직전 틱 오류 이후 본 캔들 조회 실패들
    rows = []
    for path in paths:
        with open(path, encoding='utf-8', errors='replace') as f:
            for raw in f:
                m = LINE.match(raw.rstrip('\n'))
                if not m:
                    continue
                ts, thread, _level, msg = m.groups()
                at = datetime.strptime(ts, '%Y-%m-%d %H:%M:%S.%f')
                c = CANDLE_FAIL.search(msg)
                if c:
                    pending.setdefault(thread, []).append((ts, c.group(1), c.group(2), c.group(3), at))
                    pending[thread] = [x for x in pending[thread] if at - x[4] <= WINDOW]
                    continue
                t = TICK_ERR.search(msg)
                if t:
                    sid, err = t.group(1), t.group(2)
                    # 같은 스레드에서 이 오류 직전 120초 안의 실패만 붙인다 — 수정 뒤 로그에서는 실패가 오류 없이 지나가므로 오래된 실패를 붙이지 않는다
                    fails = [x for x in pending.pop(thread, []) if at - x[4] <= WINDOW]
                    if ROLLBACK in err and fails:
                        kind = '후보'
                    elif ROLLBACK in err:
                        kind = '다른 원인(롤백 전용, 캔들 실패 없음)'
                    else:
                        kind = '다른 원인'
                    cause = '; '.join(f'{f[0][11:]} {f[1]} {f[2]}: {f[3][:120]}' for f in fails) or '-'
                    rows.append((ts, thread, sid, kind, err[:120], cause))
    print('시각\t스레드\t세션\t분류\t오류\t같은 틱의 캔들 조회 실패')
    for r in rows:
        print('\t'.join(r))
    n = sum(1 for r in rows if r[3] == '후보')
    print(f'# 세션 tick 오류 {len(rows)}건 중 후보 {n}건 — 후보는 확정이 아니다(위 SQL 로 그 시각의 포지션 감시 여부를 확인)', file=sys.stderr)


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1:])
