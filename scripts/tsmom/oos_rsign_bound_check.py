"""TSMOM 리뷰 41차 독립 확인 — 고정 OOS 분할에 개발 실행 출력(weekly.csv)의 실제 R 부호를 넣은 자연 청산 왕복 상한
(2026-10-29, docs/RESEARCH_PROPOSAL_TS_MOMENTUM.md §4-3 ④).

실행: python -I scripts/tsmom/oos_rsign_bound_check.py d:/tmp/stval_btc/btc_h1.csv d:/tmp/stval_eth/eth_h1.csv d:/tmp/tsmom_devrun_v40/weekly.csv

🔴 계산기·워크포워드를 다시 실행하지 않는다. 가격 CSV 는 시각 칸만 읽고, R 은 이미 남은 출력(주 비용 팔)에서 읽는다.
- OOS 분할과 판단 주: oos_calendar_from_times.py · TsMomentumMaintenanceRealTimesCheck.oosWeeks 와 같다(행 기준 윈도우 3, IS 0.7, 시각 기준 이력).
- 매수는 R > 0 인 주에만, 자연 청산은 R ≤ 0 인 주에만 허용한다. 관문·최소 주문 제한은 무시한다(유리한 쪽).
- R 이 기록되지 않은 주(구간 마지막 주, NA)는 양쪽 다 허용한다(유리한 쪽). 구간 끝 강제청산은 세지 않는다.
- 현금 시작(실행기와 같음)과, 각 OOS 를 보유 상태로 공짜 시작한 경우(더 유리한 쪽)를 함께 적는다.
"""
import bisect, csv, sys
from datetime import datetime, timedelta, timezone

UTC = timezone.utc
S0, E0 = datetime(2023, 12, 31, 15, tzinfo=UTC), datetime(2026, 9, 22, tzinfo=UTC)
H, DAY, NEG = timedelta(hours=1), timedelta(days=1), -10**9


def times(p):
    out = []
    for line in open(p, encoding='utf-8'):
        try:
            t = datetime.fromisoformat(line.split(',')[0]).replace(tzinfo=UTC)
        except ValueError:
            continue
        if S0 <= t < E0:
            out.append(t)
    return out


def floor(ts, t): return bisect.bisect_right(ts, t) - 1
def ceil(ts, t): return bisect.bisect_left(ts, t)


def next_monday(t):
    d = datetime(t.year, t.month, t.day, tzinfo=UTC)
    while d < t or d.weekday() != 0:
        d += DAY
    return d


def bound(dec, held_start):
    """현금·보유 두 상태 동적 계획 — 계산기 maxNaturalRoundTrips 와 같다."""
    c, h = 0, (0 if held_start else NEG)
    for buy, sell in dec:
        c, h = max(c, h + 1 if sell else NEG), max(h, c if buy else NEG)
    return max(c, h)


def main(btc, eth, weekly):
    r = {(x['coin'], x['t']): float(x['r']) for x in csv.DictReader(open(weekly, encoding='utf-8'))
         if x['arm'] == 'main' and x['r'] != 'NA'}
    for coin, p in (('KRW-BTC', btc), ('KRW-ETH', eth)):
        ts = times(p)
        n = len(ts); win = n // 3; is_ = int(win * 0.7)
        cnt, flat, free, unknown = [], [], [], 0
        for w in range(3):
            a, b = w * win + is_, min(w * win + win, n)
            dec, t = [], next_monday(ts[a])
            while t <= ts[b - 1]:
                d, f = floor(ts, t - H), ceil(ts, t)
                back = floor(ts, t - timedelta(days=28) - H)
                if (d >= a and ts[d] >= t - DAY and f < b and ts[f] < t + DAY
                        and back >= 0 and ts[back] >= t - timedelta(days=28) - DAY):
                    k = (coin, t.strftime('%Y-%m-%dT%H:%M:%SZ'))
                    if k in r:
                        dec.append((r[k] > 0, r[k] <= 0))
                    else:
                        dec.append((True, True)); unknown += 1
                t += timedelta(days=7)
            cnt.append(len(dec)); flat.append(bound(dec, False)); free.append(bound(dec, True))
        print(f'{coin} rows={n} weeks={cnt} unknownR={unknown} '
              f'flat-start={flat} sum={sum(flat)} free-held-start={free} sum={sum(free)}')


if __name__ == '__main__':
    main(*sys.argv[1:4])
