"""TSMOM 자료 점검 — 시각 열만 읽어 코인별 OOS 경계·주간 판단 수·자연 청산 왕복 상한을 다시 계산한다
(2026-10-29, 리뷰 38차, docs/RESEARCH_PROPOSAL_TS_MOMENTUM.md §6-2).

실행: python -I scripts/tsmom/oos_calendar_from_times.py [BTC CSV] [ETH CSV] [점검 구간 CSV] [재조회 summary.json]
기본: d:/tmp/stval_btc/btc_h1.csv(원본 보존, 읽기만) · d:/tmp/stval_eth/eth_h1.csv ·
      scripts/tsmom/maintenance_windows_tsmom.csv · d:/tmp/stval_maint/recheck/summary.json

🔴 각 줄의 첫 칸(시각)만 읽는다. 가격·거래량 칸은 나누지도 않는다 — 가격 분석·성과 계산이 아니다.
규칙(가격을 읽지 않는 부분만, 로컬 계산기 TsMomentumCalculator 와 같다):
- 고정 구간 [2023-12-31T15:00Z, 2026-09-22T00:00Z). 구간 밖 행은 버린다(원본 파일은 고치지 않는다).
- 분할: 운영·b23737e 실행기 runRolling — 행 번호 기준, 윈도우 3, IS 0.7. OOS = 행 [isEnd, osEnd), 나머지 행은 버려진다.
- 판단 t = 월요일 00:00 UTC. 판단 봉 = 시작 ≤ t−1h 인 마지막 봉(시작 ≥ t−24h), 체결 봉 = 시작 ≥ t 인 첫 봉(시작 < t+24h).
- 시각 기준 이력 있음: 판단 봉·체결 봉이 모두 OOS 행이고, t−28일의 판단 봉이 자료에 있다.
- 예열 없음: 그에 더해 t−28일 판단 봉도 OOS 행이다.
- 자연 청산 왕복 상한 = ⌊판단 수 / 2⌋. 구간 끝 강제청산은 세지 않는다.
- (보조) 최신성 차단: 매수 대기 창 [t, t+24h) 의 모든 봉이 시각만으로 정해지는 관문 — 코인 최신성(COIN_UNKNOWN) 또는
  BTC 최신성(BTC_UNKNOWN) — 에 막히면 그 주는 매수할 수 없다. 계산기 problem()·fetched() 와 같은 규칙이고 공식 점검 예외는 없다
  (운영 예외는 운영자가 기록한 점검 구간에만 적용되고, 이 기간에는 기록이 없다). 급락 판정은 가격이 필요해 넣지 않는다 —
  그래서 이 값도 상한이다. 왕복 = 막히지 않은 판단에서 매수, 다음 판단에서 매도(앞에서부터 고르는 것이 최대).
- (리뷰 39차) 공식 점검 예외를 넣은 상한도 같이 적는다 — 운영 V93 의 두 조건: (가) 봉 구간 전체가 판정 시각 이전에 끝난 점검
  구간(점검 구간 CSV) 안 (나) 거래소 재조회(maintenance_recheck_h1.py)에서도 그 코인의 그 칸이 없었다. 최신 완료 봉 조건은 그대로다.
  예외 없음 결과(리뷰 38차, 상한 19)는 지우지 않고 나란히 적는다.
"""
import bisect
import csv
import hashlib
import json
import sys
from datetime import datetime, timedelta, timezone

sys.stdout.reconfigure(encoding="utf-8")
S0 = datetime(2023, 12, 31, 15, tzinfo=timezone.utc)
E0 = datetime(2026, 9, 22, 0, tzinfo=timezone.utc)
H, DAY, WEEK, LOOKBACK = timedelta(hours=1), timedelta(days=1), timedelta(days=7), timedelta(days=28)
files = {"KRW-BTC": sys.argv[1] if len(sys.argv) > 1 else "d:/tmp/stval_btc/btc_h1.csv",
         "KRW-ETH": sys.argv[2] if len(sys.argv) > 2 else "d:/tmp/stval_eth/eth_h1.csv"}
win_csv = sys.argv[3] if len(sys.argv) > 3 else "scripts/tsmom/maintenance_windows_tsmom.csv"
recheck = sys.argv[4] if len(sys.argv) > 4 else "d:/tmp/stval_maint/recheck/summary.json"


def utc(s):
    return datetime.fromisoformat(s).replace(tzinfo=timezone.utc)


def fmt(t):
    return t.strftime("%Y-%m-%d %H:%M")


def next_monday(t):
    m = (t - timedelta(days=t.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    return m if m >= t else m + WEEK


def floor_bar(ts, t):                       # 시작 ≤ t−1h 인 마지막 봉, 24시간 안
    i = bisect.bisect_right(ts, t - H) - 1
    return i if i >= 0 and ts[i] >= t - DAY else None


def ceil_bar(ts, t):                        # 시작 ≥ t 인 첫 봉, 24시간 안
    i = bisect.bisect_left(ts, t)
    return i if i < len(ts) and ts[i] < t + DAY else None


GRACE, SPAN = timedelta(minutes=2), timedelta(hours=500)


WINDOWS = [(r["id"], datetime.fromisoformat(r["starts_at_utc"].replace("Z", "+00:00")),
            datetime.fromisoformat(r["ends_at_utc"].replace("Z", "+00:00"))) for r in csv.DictReader(open(win_csv, encoding="utf-8"))]
ABSENT = {}
for r in json.load(open(recheck, encoding="utf-8")):
    ABSENT.setdefault(r["market"], set()).update(utc(a + ":00") for a in r["absent"])


def exempt(coin, slot, now):                # V93 (가) 끝난 점검 구간이 봉 구간을 통째로 담는다 (나) 재조회에서도 없었다
    return slot in ABSENT.get(coin, ()) and any(e <= now and s <= slot and slot + H <= e for _, s, e in WINDOWS)


def stale(ts, h, coin=None):                # 시작 h 봉 시가로 매수할 때 now = h + 2분 의 최신성 문제 여부. coin 을 주면 점검 예외
    now = h + GRACE
    lo, hi = bisect.bisect_left(ts, now - SPAN), bisect.bisect_right(ts, h - H)
    if hi <= lo or ts[hi - 1] < h - H:      # 완료 봉 없음 · 최신 완료 봉(h−1h) 없음 — 예외 없음
        return True
    missing = int((ts[hi - 1] - ts[lo]) / H) + 1 - (hi - lo)
    if missing > 0 and coin:
        have, t = set(ts[lo:hi]), ts[lo]
        while t <= ts[hi - 1]:
            if t not in have and exempt(coin, t, now):
                missing -= 1
            t += H
    return missing > 2


times = {}
for coin, path in files.items():
    raw = open(path, "rb").read()
    all_ts = [utc(line.split(b",", 1)[0].decode()) for line in raw.splitlines() if line]
    keep = [line for line in raw.splitlines() if line and S0 <= utc(line.split(b",", 1)[0].decode()) < E0]
    ts = [utc(line.split(b",", 1)[0].decode()) for line in keep]
    times[coin] = ts
    expected = int((E0 - S0) / H)
    have = set(ts)
    gaps = [S0 + i * H for i in range(expected) if S0 + i * H not in have]
    print(f"== {coin}  {path}")
    print(f"원본 SHA-256 {hashlib.sha256(raw).hexdigest()} · 원본 행 {len(all_ts)} ({fmt(all_ts[0])} ~ {fmt(all_ts[-1])})")
    print(f"구간 안 행 {len(ts)} · 최초 {fmt(ts[0])} · 최종 {fmt(ts[-1])} · 기대 {expected} · 빠진 시간 {len(gaps)}"
          f" · 중복 {len(ts) - len(have)} · 오름차순 {all(a < b for a, b in zip(ts, ts[1:]))}")
    print(f"구간 안 행만 이은 내용의 SHA-256 {hashlib.sha256(b''.join(l + b'\n' for l in keep)).hexdigest()}")

    n = len(ts)
    win = n // 3
    is_size = max(1, int(win * 0.7))
    if is_size >= win:
        is_size = win - 1
    print(f"분할: 행 {n} → 윈도우 {win} · IS {is_size} · OOS {win - is_size} · 버려지는 끝 행 {n - 3 * win}"
          + (f" ({fmt(ts[3 * win])} ~ {fmt(ts[-1])})" if n > 3 * win else ""))
    btc = times["KRW-BTC"]
    ex = sorted(t for t in gaps if exempt(coin, t, E0))
    print(f"점검 예외 대상(구간 안 + 재조회에서도 없음) {len(ex)}칸 · 예외 아님 {len(gaps) - len(ex)}칸: "
          + " ".join(g.strftime("%Y-%m-%dT%H") for g in gaps if g not in ex))
    tot_w = tot_n = tot_rt = tot_blk = tot_rt2 = tot_blk3 = tot_rt3 = 0
    for w in range(3):
        a_i, b_i = w * win + is_size, min(w * win + win, n)   # OOS 행 [a_i, b_i)
        a, last = ts[a_i], ts[b_i - 1]
        with_h = no_h = blk = rt2 = blk3 = rt3 = 0
        holding = holding3 = False
        blocked_at, blocked_at3 = [], []
        t = next_monday(a)
        while t <= last:
            d, f = floor_bar(ts, t), ceil_bar(ts, t)
            if d is not None and f is not None and a_i <= d and f < b_i:
                back = floor_bar(ts, t - LOOKBACK)
                if back is not None:
                    with_h += 1
                    if back >= a_i:
                        no_h += 1
                    window = ts[f:bisect.bisect_left(ts, t + DAY)]
                    blocked = all(stale(ts, h) or stale(btc, h) for h in window)
                    if blocked:
                        blk += 1
                        blocked_at.append(t.strftime("%m-%d"))
                    if holding:
                        holding, rt2 = False, rt2 + 1
                    elif not blocked:
                        holding = True
                    blocked3 = all(stale(ts, h, coin) or stale(btc, h, "KRW-BTC") for h in window)
                    if blocked3:
                        blk3 += 1
                        blocked_at3.append(t.strftime("%m-%d"))
                    if holding3:
                        holding3, rt3 = False, rt3 + 1
                    elif not blocked3:
                        holding3 = True
            t += WEEK
        tot_w, tot_n, tot_rt = tot_w + with_h, tot_n + no_h, tot_rt + with_h // 2
        tot_blk, tot_rt2, tot_blk3, tot_rt3 = tot_blk + blk, tot_rt2 + rt2, tot_blk3 + blk3, tot_rt3 + rt3
        print(f"  OOS {w + 1}: {fmt(a)} ~ {fmt(last)} (행 {a_i}..{b_i - 1}) · 판단 이력 있음 {with_h} · 예열 없음 {no_h}"
              f" · 자연 왕복 상한 {with_h // 2} · (보조) 최신성 차단 주 {blk} {blocked_at} → 상한 {rt2}"
              f" · 점검 예외 반영 차단 주 {blk3} {blocked_at3} → 상한 {rt3}")
    print(f"  합계: 판단 {tot_w} · 예열 없음 {tot_n} · 자연 청산 왕복 상한 {tot_rt} · (보조) 최신성 차단 주 {tot_blk} → 상한 {tot_rt2}"
          f" · 점검 예외 반영 차단 주 {tot_blk3} → 상한 {tot_rt3}")
    print("빠진 시각: " + " ".join(g.strftime("%Y-%m-%dT%H") for g in gaps))
    print()
