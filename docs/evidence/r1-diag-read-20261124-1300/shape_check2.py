"""r1-diag-2 실제 SQL 출력 형태·합 대조 (로컬 PC 전용, 결과 해석 없음). 총괄 v1.234 §3-1.

사용: python -I shape_check2.py <SQL 표준 출력 파일>
종료값: 0 SHAPE_OK · 7 SHAPE_MISMATCH(이유를 한 줄씩 표준 출력). 그 밖의 종료값은 없다(예외도 7 로 거절).
원칙:
  - 모든 행은 인덱스 접근·언패킹 전에 태그별 고정 열 수를 확인한다. 부족·초과는 SHAPE_MISMATCH 이며 0 으로 메우지 않는다.
  - 열 수 고정: META 10 · REF 4 · ORIG 3 · END 2 · 분해 행(SPLIT AM PM AX AF PX PF PE UQ AGE LEG) 9 (쓰지 않는 칸은 \\N 이어야 함).
  - 필수 행 집합(종류×모드/분류/표지/구간/분기)이 정확히 한 번씩 있어야 한다. 합이 0 이어도 행이 빠지면 거절한다.
  - 이유 문구에는 태그·종류·열 위치만 쓰고, 계약 밖 값 원문은 출력하지 않는다(소비하지 않고 멈춘다).
  - META: 식별자 r1-diag-2 · Flyway 96 · repeatable read · 15s · 2s · 시각 형식·since = db_now − 30일 · db_now ≤ db_clock ≤ END.
"""
import re
import sys
from datetime import datetime, timedelta

N = chr(92) + "N"
KINDS = ("LIVE", "DYNAMIC")
WIDTH = {"META": 10, "REF": 4, "ORIG": 3, "END": 2}
DECOMP = ("SPLIT", "AM", "PM", "AX", "AF", "PX", "PF", "PE", "UQ", "AGE", "LEG")
for _t in DECOMP:
    WIDTH[_t] = 9
USED = {"SPLIT": 5, "AM": 4, "PM": 4, "AX": 4, "AF": 4, "PX": 4, "PF": 4, "PE": 4, "UQ": 9, "AGE": 5, "LEG": 6}
MODES = ("REAL", "PAPER", "NO_ROW", "OTHER")
AX_CLS = ("FIELDS_ONLY", "COST_ONLY", "FIELDS_AND_COST")
AF_FLAGS = ("F_FUNDS_NULL", "F_FEE_NULL", "F_QTY_NULL_OR_LE0", "C_NO_POSITION", "C_NO_FILLED_BUY", "C_BUY_COST_NULL", "C_BUY_QTY_NULL")
PX_CLS = ("QTY_SHORT", "NO_SOURCE", "BOTH")
PF_FLAGS = ("Q_NO_SETTLED_SELL", "Q_PARTIAL_SETTLED", "Q_APPLIED_SELL_FUNDS_NULL", "Q_UNAPPLIED_SELL", "Q_NO_SELL_ORDER",
            "N_NO_ORDERS", "N_ER_SIZE_NOT_ZERO", "N_NO_BUY", "N_BUY_QTY_NULL", "N_BUY_NOT_FAILED_OR_CANCELLED",
            "N_BUY_CANCEL_UNCONFIRMED", "N_UNAPPLIED_SELL")
BUCKETS = ("D00_06", "D07_13", "D14_20", "D21_29", "D30P")
REF_VERS = ("81", "87", "94")
TS_US = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+00:00$")
TS_S = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+00:00$")
TS_RAW = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,6})?$")
TZ = re.compile(r"^[A-Za-z0-9_/+-]{1,64}$")
PE_GRP = re.compile(r"^(\(null\)|\(nonconforming\)|[A-Z][A-Z0-9_]{0,39})$")
INT = re.compile(r"^(0|[1-9][0-9]{0,17})$")


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
        return err  # 열 수가 맞지 않으면 관계 검사로 가지 않는다

    def num(i, t, col, v):
        if not INT.match(v):
            err.append(f"{i}행 {t}: {col}열 정수 아님")
            return None
        return int(v)

    # META / END 위치·형태
    if rows[0][1][0] != "META":
        err.append("첫 줄이 META 아님")
    if rows[-1][1][0] != "END":
        err.append("끝 줄이 END 아님")
    metas = [f for _, f in rows if f[0] == "META"]
    ends = [f for _, f in rows if f[0] == "END"]
    if len(metas) != 1 or len(ends) != 1:
        err.append(f"META/END 행 수 {len(metas)}/{len(ends)}")
        return err
    m, e = metas[0], ends[0]
    if m[1] != "r1-diag-2":
        err.append("META 식별자 불일치")
    if not (TS_US.match(m[2]) and TS_US.match(m[3]) and TS_US.match(m[4]) and TS_US.match(e[1])):
        err.append("META/END 시각 형식 불일치")
    else:
        p = lambda s: datetime.strptime(s[:26], "%Y-%m-%dT%H:%M:%S.%f")
        now, clk, since, end = p(m[2]), p(m[3]), p(m[4]), p(e[1])
        if since != now - timedelta(days=30):
            err.append("META since ≠ db_now − 30일")
        if not (now <= clk <= end):
            err.append("META db_now ≤ db_clock ≤ END 순서 불일치")
    if m[5] != "96":
        err.append("META Flyway ≠ 96")
    if m[6] != "repeatable read":
        err.append("META 격리 수준 ≠ repeatable read")
    if not TZ.match(m[7]):
        err.append("META TimeZone 형식 불일치")
    if m[8] != "15s" or m[9] != "2s":
        err.append("META 보호값 ≠ 15s/2s")

    # REF: 81·87·94 각 1행
    refs = [f for _, f in rows if f[0] == "REF"]
    if sorted(r[1] for r in refs) != list(REF_VERS):
        err.append("REF 버전 집합 ≠ 81·87·94")
    for r in refs:
        if not (TS_RAW.match(r[2]) and TS_S.match(r[3])):
            err.append(f"REF {r[1] if r[1] in REF_VERS else '?'}: 시각 형식 불일치")

    # 필수 키 집합
    def keyed(tag, nkey):
        d = {}
        for i, f in rows:
            if f[0] == tag:
                k = tuple(f[1:1 + nkey])
                if k in d:
                    err.append(f"{tag}: 같은 키 중복")
                d[k] = (i, f)
        return d

    req = {
        "ORIG": (1, [(k,) for k in KINDS]),
        "SPLIT": (1, [(k,) for k in KINDS]),
        "UQ": (1, [(k,) for k in KINDS]),
        "AM": (2, [(k, x) for k in KINDS for x in MODES]),
        "PM": (2, [(k, x) for k in KINDS for x in MODES]),
        "AX": (2, [(k, x) for k in KINDS for x in AX_CLS]),
        "AF": (2, [(k, x) for k in KINDS for x in AF_FLAGS]),
        "PX": (2, [(k, x) for k in KINDS for x in PX_CLS]),
        "PF": (2, [(k, x) for k in KINDS for x in PF_FLAGS]),
        "AGE": (3, [(k, b, x) for k in KINDS for b in ("A", "P") for x in BUCKETS]),
        "LEG": (2, [(k, b) for k in KINDS for b in ("A", "P")]),
    }
    D = {}
    for tag, (nk, keys) in req.items():
        D[tag] = keyed(tag, nk)
        if set(D[tag]) != set(keys):
            err.append(f"{tag}: 필수 행 집합 불일치(있음 {len(D[tag])} / 필요 {len(keys)})")
    pe = keyed("PE", 2)
    for (k, g), _ in pe.items():
        if k not in KINDS or not PE_GRP.match(g):
            err.append("PE: 종류·코드 그룹 형식 밖")
    if err:
        return err

    def v(tag, key, col):
        i, f = D[tag][key]
        return num(i, tag, col, f[col])

    for k in KINDS:
        o = v("ORIG", (k,), 2)
        a, p, ap = v("SPLIT", (k,), 2), v("SPLIT", (k,), 3), v("SPLIT", (k,), 4)
        if None in (o, a, p, ap):
            continue
        if a + p != ap or ap != o:
            err.append(f"{k}: ORIG ≠ SPLIT A+P")

        def total(tag, keys, col=3):
            xs = [v(tag, kk, col) for kk in keys]
            return None if None in xs else sum(xs)

        for tag, keys, want, lab in (("AM", [(k, x) for x in MODES], a, "A"), ("PM", [(k, x) for x in MODES], p, "P"),
                                     ("AX", [(k, x) for x in AX_CLS], a, "A"), ("PX", [(k, x) for x in PX_CLS], p, "P")):
            s = total(tag, keys)
            if s is not None and s != want:
                err.append(f"{k}: {tag} 합 ≠ {lab}")
        pes = [num(i, "PE", 3, f[3]) for (kk, _), (i, f) in pe.items() if kk == k]
        if None not in pes and sum(pes) != p:
            err.append(f"{k}: PE 합 ≠ P")
        both = v("PX", (k, "BOTH"), 3)
        if both not in (None, 0):
            err.append(f"{k}: PX BOTH ≠ 0 (②·④ 동시 — 정의상 불가)")
        for x in AF_FLAGS:
            n = v("AF", (k, x), 3)
            if n is not None and n > a:
                err.append(f"{k}: AF {x} > A")
        for x in PF_FLAGS:
            n = v("PF", (k, x), 3)
            if n is not None and n > p:
                err.append(f"{k}: PF {x} > P")
        qs, qp, qshort = v("PF", (k, "Q_NO_SETTLED_SELL"), 3), v("PF", (k, "Q_PARTIAL_SETTLED"), 3), v("PX", (k, "QTY_SHORT"), 3)
        if None not in (qs, qp, qshort, both) and qs + qp != qshort + both:
            err.append(f"{k}: PF Q_NO_SETTLED+Q_PARTIAL ≠ PX ②")
        for br, want in (("A", a), ("P", p)):
            s = total("AGE", [(k, br, x) for x in BUCKETS], col=4)
            if s is not None and s != want:
                err.append(f"{k}: AGE {br} 합 ≠ {br}")
            i, f = D["LEG"][(k, br)]
            for col in (3, 4):
                if f[col] != N:
                    n = num(i, "LEG", col, f[col])
                    if n is not None and n > want:
                        err.append(f"{k}: LEG {br} {col}열 > {br}")
            n = num(i, "LEG", 5, f[5])
            if n is not None and n > want:
                err.append(f"{k}: LEG {br} opened_null > {br}")
        i, f = D["UQ"][(k,)]
        u = [num(i, "UQ", c, f[c]) for c in range(2, 9)]   # 열 수는 위에서 9 로 확인됨
        if None in u:
            continue
        ua, ud, un, up, ub, uu, ui = u
        if ua != a or up != p:
            err.append(f"{k}: UQ a/p ≠ SPLIT")
        if uu != ud + up - ub or ui != uu + un or ud + un > ua or ub > min(ud, up):
            err.append(f"{k}: UQ 고유 수 계산 불일치")
    return err


def main(argv):
    if len(argv) != 2:
        print("SHAPE_MISMATCH")
        print(" - 사용법: shape_check2.py <출력 파일>")
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
