#!/usr/bin/env python3
# 고정 오프셋 UTC+09:00 시각 함수(총괄 v1.249 §3-1 규칙, v1.250 배치 공용). 시스템 로컬 시간대·TZ·zoneinfo/tzdata 를 쓰지 않는다.
# 사용: kst.py now            → "<epoch> <YYYYMMDD> <HHMM> <ISO+09:00>" (한 번의 표본에서 함께 얻는다)
#       kst.py at <epoch>     → 같은 형식(순수 변환 — 배치는 표시에만 쓴다)
#       kst.py epoch <YYYY-MM-DDTHH:MM> → 그 KST 시각의 epoch(검증용)
# 형식 결함·실패면 종료 2, 출력 없음.
import datetime as d
import re
import sys
import time

K = d.timezone(d.timedelta(hours=9))


def fmt(e):
    t = d.datetime.fromtimestamp(e, d.timezone.utc).astimezone(K)
    return '%d %s %s %s' % (e, t.strftime('%Y%m%d'), t.strftime('%H%M'), t.isoformat())


def main(a):
    if a == ['now']:
        return fmt(int(time.time()))
    if len(a) == 2 and a[0] == 'at' and re.fullmatch(r'[0-9]{1,12}', a[1]):
        return fmt(int(a[1]))
    if len(a) == 2 and a[0] == 'epoch' and re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}', a[1]):
        t = d.datetime.strptime(a[1], '%Y-%m-%dT%H:%M').replace(tzinfo=K)  # 잘못된 날짜·시각은 ValueError
        return str(int(t.timestamp()))
    raise ValueError('인자 형식')


if __name__ == '__main__':
    try:
        out = main(sys.argv[1:])
    except Exception:
        sys.exit(2)
    print(out)
