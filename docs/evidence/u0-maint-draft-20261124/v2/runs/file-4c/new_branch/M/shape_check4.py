"""r1-diag-4 실제 SQL 출력 형태·합 대조 (로컬 PC 전용, 결과 해석 없음). 총괄 v1.239 §3-1.

사용: python -I shape_check4.py <SQL 표준 출력 파일>
종료값: 0 SHAPE_OK · 7 SHAPE_MISMATCH(이유를 한 줄씩 표준 출력). 그 밖의 종료값은 없다(예외도 7 로 거절).
원칙(shape_check2 7f56f640 과 같음):
  - 인덱스 접근 전에 태그별 고정 열 수를 확인한다. 부족·초과는 SHAPE_MISMATCH 이며 0 으로 메우지 않는다. 열 수가 틀리면 관계 검사로 가지 않는다.
  - 열 수: META 10 · PRIOR 4 · ORIG 3 · END 2 · 본문(SPLIT EXP NEW RS RM RX OW TOT UST ORPH) 7, 쓰지 않는 칸은 \\N.
  - 필수 행 집합이 정확히 한 번씩 있어야 한다. 이유 문구에는 태그·종류·열 위치만 쓰고 값 원문은 내지 않는다.
  - META: r1-diag-4 · Flyway 96 · repeatable read · 15s · 2s · 시각 형식 · since = db_now − 30일 · db_now ≤ db_clock ≤ END.
관계:
  - ORIG = SPLIT a+p · EXP ALL rows = a+p · KNOWN + OTHER = ALL · LIVE KNOWN = 0
  - EXP rows 0 ⇔ 시각 세 칸 \\N. rows>0 이면 first ≤ last, first ≥ db_now(창 안 행의 경계는 기준 시각 이상), next_minute = 분 내림(last) + 1분 > last,
    ALL first/last = KNOWN·OTHER 의 min/max, KNOWN 경계는 [KNOWN_LO+30일, KNOWN_HI+30일) 안
  - NEW A ≤ SPLIT a · NEW P ≤ SPLIT p · NEW A + NEW P ≤ EXP OTHER rows (NEW 는 정의상 KNOWN 이 아니다)
  - RS 합 = RM REAL (종류별) · TOT item = RX LIVE + RX DYNAMIC + OW 여섯 owner 합
  - ORPH ORDER ACTIVE = OW NO_SESSION_ID 주문 두 item 합 · ORPH POSITION ACTIVE = OW NO_SESSION_ID 포지션 두 item 합 · OW NO_SESSION_ID CLEANUP_PENDING = 0(DDL NOT NULL)
  - PRIOR 세 값은 고정 상수와 글자 그대로 같아야 한다.
"""
import re
import sys
from datetime import datetime, timedelta

N = chr(92) + "N"
KINDS = ("LIVE", "DYNAMIC")
BODY = ("SPLIT", "EXP", "NEW", "RS", "RM", "RX", "OW", "TOT", "UST", "ORPH")
WIDTH = {"META": 10, "PRIOR": 4, "ORIG": 3, "END": 2}
for _t in BODY:
    WIDTH[_t] = 7
USED = {"SPLIT": 5, "EXP": 7, "NEW": 4, "RS": 4, "RM": 4, "RX": 4, "OW": 4, "TOT": 3, "UST": 3, "ORPH": 4}
CLASSES = ("KNOWN", "OTHER", "ALL")
STATUSES = ("CREATED", "RUNNING", "PAUSED", "STOPPED", "EMERGENCY_STOPPED", "DELETED", "OTHER")
MODES = ("REAL", "PAPER", "OTHER")
ITEMS = ("POS_OPEN", "POS_CLOSING", "ORD_UNRESOLVED_SUBMIT", "ORD_ACTIVE_OTHER", "CLEANUP_PENDING")
OWNERS = ("PAPER", "DYN_PAPER", "NO_SESSION_ID", "KIND_OTHER", "NO_ROW", "MODE_OTHER")
ORPH_KEYS = (("ORDER", "ACTIVE"), ("ORDER", "TERMINAL"), ("ORDER", "STATE_OTHER"),
             ("POSITION", "ACTIVE"), ("POSITION", "CLOSED"), ("POSITION", "STATUS_OTHER"))
PRIOR = ("2026-11-24T04:49:14.512077+00:00", "2026-10-25T04:49:14.512077+00:00", "2026-11-01T04:49:14.512077+00:00")
TS_US = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+00:00$")
TZ = re.compile(r"^[A-Za-z0-9_/+-]{1,64}$")
INT = re.compile(r"^(0|[1-9][0-9]{0,17})$")


def ts(v):
    return datetime.strptime(v[:26], "%Y-%m-%dT%H:%M:%S.%f")


def check(data):
    err = []
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return ["UTF-8 아님"]
    if "\r" in text:
        err.append("CR 문자 포함")
    if not text:
        return ["빈 출력"]
    if not text.endswith("\n"):
        err.append("마지막 줄바꿈 없음")
    lines = text[:-1].split("\n") if text.endswith("\n") else text.split("\n")
    rows = []
    for i, line in enumerate(lines, 1):
        f = line.split("\t")
        t = f[0]
        if t not in WIDTH:
            err.append(f"{i}행: 허용 밖 태그")
            continue
        if len(f) != WIDTH[t]:
            err.append(f"{i}행 {t}: 열 수 {len(f)} ≠ {WIDTH[t]}")
            continue
        if t in USED and any(x != N for x in f[USED[t]:]):
            err.append(f"{i}행 {t}: 쓰지 않는 칸이 \\N 아님")
            continue
        rows.append((i, f))
    if err:
        return err

    def num(i, t, col, v):
        if not INT.match(v):
            err.append(f"{i}행 {t}: {col}열 정수 아님")
            return None
        return int(v)

    # META / PRIOR / END
    if rows[0][1][0] != "META":
        err.append("첫 줄이 META 아님")
    if rows[-1][1][0] != "END":
        err.append("끝 줄이 END 아님")
    metas = [f for _, f in rows if f[0] == "META"]
    ends = [f for _, f in rows if f[0] == "END"]
    priors = [f for _, f in rows if f[0] == "PRIOR"]
    if len(metas) != 1 or len(ends) != 1 or len(priors) != 1:
        err.append(f"META/PRIOR/END 행 수 {len(metas)}/{len(priors)}/{len(ends)}")
        return err
    m, e, pr = metas[0], ends[0], priors[0]
    db_now = None
    if m[1] != "r1-diag-4":
        err.append("META 식별자 불일치")
    if not all(TS_US.match(x) for x in (m[2], m[3], m[4], e[1])):
        err.append("META/END 시각 형식 불일치")
    else:
        db_now, clk, since, end = ts(m[2]), ts(m[3]), ts(m[4]), ts(e[1])
        if since != db_now - timedelta(days=30):
            err.append("META since ≠ db_now − 30일")
        if not (db_now <= clk <= end):
            err.append("META db_now ≤ db_clock ≤ END 순서 불일치")
    if m[5] != "96":
        err.append("META Flyway ≠ 96")
    if m[6] != "repeatable read":
        err.append("META 격리 수준 ≠ repeatable read")
    if not TZ.match(m[7]):
        err.append("META TimeZone 형식 불일치")
    if m[8] != "15s" or m[9] != "2s":
        err.append("META 보호값 ≠ 15s/2s")
    if tuple(pr[1:]) != PRIOR:
        err.append("PRIOR 고정 상수 불일치")
    known_lo, known_hi = ts(PRIOR[1]), ts(PRIOR[2])

    # 필수 행 집합(정확히 한 번)
    seen = {}
    for i, f in rows:
        t = f[0]
        if t in ("META", "PRIOR", "END"):
            continue
        key = {"ORIG": 2, "SPLIT": 2, "EXP": 3, "NEW": 3, "RS": 3, "RM": 3, "RX": 3, "OW": 3, "TOT": 2, "UST": 2, "ORPH": 3}[t]
        k = tuple(f[:key])
        if k in seen:
            err.append(f"{i}행 {t}: 같은 키 중복")
            continue
        seen[k] = (i, f)
    need = []
    for k in KINDS:
        need += [("ORIG", k), ("SPLIT", k)]
        need += [("EXP", k, c) for c in CLASSES]
        need += [("NEW", k, b) for b in ("A", "P")]
        need += [("RS", k, s) for s in STATUSES]
        need += [("RM", k, x) for x in MODES]
        need += [("RX", k, x) for x in ITEMS]
    need += [("OW", w, x) for w in OWNERS for x in ITEMS]
    need += [("TOT", x) for x in ITEMS]
    need += [("UST", "ORDER"), ("UST", "POSITION")]
    need += [("ORPH",) + x for x in ORPH_KEYS]
    miss = [x for x in need if x not in seen]
    extra = [x for x in seen if x not in set(need)]
    for x in miss:
        err.append(f"필수 행 없음 {x[0]} {'/'.join(x[1:])}")
    for x in extra:
        i, f = seen[x]
        err.append(f"{i}행 {f[0]}: 계약 밖 키")
    if err:
        return err

    def v(key, col):
        i, f = seen[key]
        return num(i, key[0], col + 1, f[col])

    ok_num = True
    for key, (i, f) in seen.items():
        t = f[0]
        cols = {"ORIG": (2,), "SPLIT": (2, 3, 4), "EXP": (3,), "NEW": (3,), "RS": (3,), "RM": (3,), "RX": (3,), "OW": (3,),
                "TOT": (2,), "UST": (2,), "ORPH": (3,)}[t]
        for c in cols:
            if num(i, t, c + 1, f[c]) is None:
                ok_num = False
    if not ok_num:
        return err

    for k in KINDS:
        a, p, ap = v(("SPLIT", k), 2), v(("SPLIT", k), 3), v(("SPLIT", k), 4)
        if a + p != ap:
            err.append(f"SPLIT {k}: a + p ≠ a_plus_p")
        if v(("ORIG", k), 2) != ap:
            err.append(f"ORIG {k} ≠ SPLIT a_plus_p")
        ex = {}
        for c in CLASSES:
            i, f = seen[("EXP", k, c)]
            r = int(f[3])
            if r == 0:
                if f[4:7] != [N, N, N]:
                    err.append(f"{i}행 EXP {k}/{c}: 대상 0 인데 경계 칸이 \\N 아님")
                ex[c] = (0, None, None)
                continue
            if not all(TS_US.match(x) for x in f[4:7]):
                err.append(f"{i}행 EXP {k}/{c}: 경계 시각 형식 불일치")
                ex[c] = None
                continue
            first, last, nxt = ts(f[4]), ts(f[5]), ts(f[6])
            if first > last:
                err.append(f"{i}행 EXP {k}/{c}: first > last")
            if db_now is not None and first < db_now:
                err.append(f"{i}행 EXP {k}/{c}: first < db_now")
            if nxt != last.replace(second=0, microsecond=0) + timedelta(minutes=1) or not nxt > last:
                err.append(f"{i}행 EXP {k}/{c}: next_minute ≠ 분 내림(last) + 1분")
            if c == "KNOWN" and not (known_lo + timedelta(days=30) <= first and last < known_hi + timedelta(days=30)):
                err.append(f"{i}행 EXP {k}/KNOWN: 경계가 KNOWN 구간 밖")
            ex[c] = (r, first, last)
        if None in ex.values():
            continue
        if ex["KNOWN"][0] + ex["OTHER"][0] != ex["ALL"][0]:
            err.append(f"EXP {k}: KNOWN + OTHER ≠ ALL")
        if ex["ALL"][0] != ap:
            err.append(f"EXP {k}: ALL rows ≠ SPLIT a_plus_p")
        if k == "LIVE" and ex["KNOWN"][0] != 0:
            err.append("EXP LIVE: KNOWN ≠ 0")
        parts = [ex[c] for c in ("KNOWN", "OTHER") if ex[c][0] > 0]
        if parts and ex["ALL"][0] > 0:
            if ex["ALL"][1] != min(x[1] for x in parts) or ex["ALL"][2] != max(x[2] for x in parts):
                err.append(f"EXP {k}: ALL 경계 ≠ KNOWN·OTHER 의 min/max")
        na, np_ = v(("NEW", k, "A"), 3), v(("NEW", k, "P"), 3)
        if na > a or np_ > p:
            err.append(f"NEW {k}: 분기 건수 초과")
        if na + np_ > ex["OTHER"][0]:
            err.append(f"NEW {k}: A + P > EXP OTHER rows")
        if sum(v(("RS", k, s), 3) for s in STATUSES) != v(("RM", k, "REAL"), 3):
            err.append(f"RS {k} 합 ≠ RM REAL")
    for x in ITEMS:
        tot = v(("TOT", x), 2)
        part = v(("RX", "LIVE", x), 3) + v(("RX", "DYNAMIC", x), 3) + sum(v(("OW", w, x), 3) for w in OWNERS)
        if tot != part:
            err.append(f"TOT {x} ≠ RX + OW 합")
    if v(("ORPH", "ORDER", "ACTIVE"), 3) != v(("OW", "NO_SESSION_ID", "ORD_UNRESOLVED_SUBMIT"), 3) + v(("OW", "NO_SESSION_ID", "ORD_ACTIVE_OTHER"), 3):
        err.append("ORPH ORDER ACTIVE ≠ OW NO_SESSION_ID 주문 합")
    if v(("ORPH", "POSITION", "ACTIVE"), 3) != v(("OW", "NO_SESSION_ID", "POS_OPEN"), 3) + v(("OW", "NO_SESSION_ID", "POS_CLOSING"), 3):
        err.append("ORPH POSITION ACTIVE ≠ OW NO_SESSION_ID 포지션 합")
    if v(("OW", "NO_SESSION_ID", "CLEANUP_PENDING"), 3) != 0:
        err.append("OW NO_SESSION_ID CLEANUP_PENDING ≠ 0")
    return err


def main(argv):
    if len(argv) != 2:
        print("SHAPE_MISMATCH")
        print(" - 사용법: shape_check4.py <출력 파일>")
        return 7
    try:
        with open(argv[1], "rb") as fh:
            data = fh.read()
        err = check(data)
    except Exception as ex:  # 예외도 traceback 없이 거절(성공으로 취급하지 않음)
        err = [f"검사기 내부 예외 {type(ex).__name__}"]
    if err:
        print("SHAPE_MISMATCH")
        for x in err:
            print(" -", x)
        return 7
    print("SHAPE_OK")
    return 0


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    sys.stdout.reconfigure(encoding="utf-8", newline=chr(10))
    sys.exit(main(sys.argv))
