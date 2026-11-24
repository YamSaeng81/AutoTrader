"""유지보수 배포 판정(초안, 총괄 v1.239 §3-2). **미승인 — U0-maint.sh 의 MAINT4 칸이 빈칸인 동안 호출되지 않는다.**

사용: python3 -I maint_gate4.py <r1-diag-4 출력(shape_check4 rc0 통과본)> <pf3ok 출력 predeploy-verdict.txt>
종료값: 0 MAINT_DEPLOY_ONLY(유지보수 배포 준비 가능 · 매매 시작 금지) · 1 MAINT_STOP(조건 미충족 — 배포하지 않는다) · 3 형식 이상·읽기 실패.
0 은 다음을 모두 충족할 때만 낸다. 하나라도 불명이면 1 또는 3 이다(0 으로 메우지 않는다).
  P1 pf3ok 판정 줄: R1 LIVE 0 · R3 RUNNING REAL 세션 청산 행 0 · 판정 불가 0 (기존 출력 그대로 읽는다)
  P2 r1-diag-4 ORIG DYNAMIC = pf3ok R1 DYNAMIC (다른 트랜잭션이므로 다르면 멈춘다), ORIG LIVE = 0
  P3 EXP DYNAMIC KNOWN rows = ORIG DYNAMIC, EXP DYNAMIC OTHER rows = 0, EXP LIVE ALL rows = 0, KNOWN rows ≤ 9(직전 진단 대상 수)
  P4 NEW 네 칸 0
  P5 REAL 세션 RUNNING·PAUSED·OTHER 0 (LIVE·DYNAMIC), 세션 모드 OTHER 0 (RM)
  P6 RX 열 칸(REAL 보유·정리 중·미해결 제출·활성 주문·정리 대기) 0
  P7 소유 불명 OW(NO_SESSION_ID·KIND_OTHER·NO_ROW·MODE_OTHER) 20칸 0, 상태 불명 UST 0, 옛 고아 ACTIVE 0
     (옛 고아 TERMINAL/CLOSED 행 수는 기록만 하고 정상으로 재분류하지 않는다)
KNOWN 은 부류 판정이다. 같은 행이라는 증거가 아니며 이 판정기도 그렇게 주장하지 않는다(행 ID 를 읽지 않는다).
출력 문구에는 개수·경계 시각·조건 이름만 쓴다.
"""
import re
import sys

N = chr(92) + "N"
PRIOR_TARGETS = 9
UNKNOWN_OWNERS = ("NO_SESSION_ID", "KIND_OTHER", "NO_ROW", "MODE_OTHER")
ITEMS = ("POS_OPEN", "POS_CLOSING", "ORD_UNRESOLVED_SUBMIT", "ORD_ACTIVE_OTHER", "CLEANUP_PENDING")


def fail(msg):
    print("MAINT_FORMAT " + msg)
    sys.exit(3)


def main(argv):
    if len(argv) != 3:
        fail("사용법")
    try:
        rows = [ln.split("\t") for ln in open(argv[1], "rb").read().decode("utf-8").split("\n") if ln]
        ver = open(argv[2], "rb").read().decode("utf-8").split("\n")
    except (OSError, UnicodeDecodeError) as e:
        fail("파일 읽기 실패(%s)" % type(e).__name__)
    r1 = [x for x in ver if x.startswith("R1 판정 불가(30일) ")]
    r3 = [x for x in ver if x.startswith("R3 RUNNING REAL 세션 청산 행 ")]
    if len(r1) != 1 or len(r3) != 1:
        fail("pf3ok 판정 줄 수")
    m1 = re.fullmatch(r"R1 판정 불가\(30일\) LIVE ([0-9]+) · DYNAMIC ([0-9]+)", r1[0])
    m3 = re.fullmatch(r"R3 RUNNING REAL 세션 청산 행 ([0-9]+) · 판정 불가 ([0-9]+)( \(.*\))?", r3[0])
    if not m1 or not m3:
        fail("pf3ok 판정 줄 형식")
    pf_live, pf_dyn, r3_rows, r3_und = int(m1.group(1)), int(m1.group(2)), int(m3.group(1)), int(m3.group(2))
    t = {}
    for f in rows:
        key = {"ORIG": 2, "EXP": 3, "NEW": 3, "RS": 3, "RM": 3, "RX": 3, "OW": 3, "UST": 2, "ORPH": 3}.get(f[0])
        if key:
            t[tuple(f[:key])] = f
    try:
        g = lambda k, c: int(t[k][c])
        bad = []
        if pf_live != 0:
            bad.append("P1 R1 LIVE ≠ 0")
        if r3_rows != 0 or r3_und != 0:
            bad.append("P1 R3 RUNNING REAL 청산 행·판정 불가 ≠ 0")
        if g(("ORIG", "LIVE"), 2) != 0:
            bad.append("P2 ORIG LIVE ≠ 0")
        if g(("ORIG", "DYNAMIC"), 2) != pf_dyn:
            bad.append("P2 ORIG DYNAMIC ≠ pf3ok R1 DYNAMIC")
        known = g(("EXP", "DYNAMIC", "KNOWN"), 3)
        if known != g(("ORIG", "DYNAMIC"), 2):
            bad.append("P3 KNOWN ≠ ORIG DYNAMIC")
        if g(("EXP", "DYNAMIC", "OTHER"), 3) != 0:
            bad.append("P3 DYNAMIC OTHER ≠ 0(새/불명 분기)")
        if g(("EXP", "LIVE", "ALL"), 3) != 0:
            bad.append("P3 LIVE 대상 ≠ 0")
        if known > PRIOR_TARGETS:
            bad.append("P3 KNOWN > 직전 진단 9")
        for k in ("LIVE", "DYNAMIC"):
            for b in ("A", "P"):
                if g(("NEW", k, b), 3) != 0:
                    bad.append(f"P4 NEW {k}/{b} ≠ 0")
            for s in ("RUNNING", "PAUSED", "OTHER"):
                if g(("RS", k, s), 3) != 0:
                    bad.append(f"P5 REAL {k} {s} ≠ 0")
            if g(("RM", k, "OTHER"), 3) != 0:
                bad.append(f"P5 {k} 세션 모드 OTHER ≠ 0")
            for i in ITEMS:
                if g(("RX", k, i), 3) != 0:
                    bad.append(f"P6 REAL {k} {i} ≠ 0")
        for w in UNKNOWN_OWNERS:
            for i in ITEMS:
                if g(("OW", w, i), 3) != 0:
                    bad.append(f"P7 소유 불명 {w} {i} ≠ 0")
        for x in ("ORDER", "POSITION"):
            if g(("UST", x), 2) != 0:
                bad.append(f"P7 상태 불명 {x} ≠ 0")
            if g(("ORPH", x, "ACTIVE"), 3) != 0:
                bad.append(f"P7 옛 고아 {x} ACTIVE ≠ 0")
        orph_old = g(("ORPH", "ORDER", "TERMINAL"), 3), g(("ORPH", "POSITION", "CLOSED"), 3)
        last, nxt = t[("EXP", "DYNAMIC", "KNOWN")][5], t[("EXP", "DYNAMIC", "KNOWN")][6]
    except (KeyError, ValueError, IndexError):
        fail("필수 행·정수 형식")
    if bad:
        print("MAINT_STOP 유지보수 배포 조건 미충족 — 배포하지 않는다")
        for x in bad:
            print(" - " + x)
        return 1
    if known == 0:
        print("MAINT_FORMAT R1 DYNAMIC 0 — 이 판정기를 부를 경우가 아님(pf3ok 0 경로)")
        return 3
    print(f"MAINT_DEPLOY_ONLY 유지보수 배포 준비 가능 · 매매 시작 금지 — R1 DYNAMIC KNOWN {known}(부류 일치, 동일 행 증거 아님) · "
          f"마지막 만료 경계 {last} · 다음 분 {nxt} · 옛 고아 기록만 주문 {orph_old[0]}/포지션 {orph_old[1]}")
    return 0


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    sys.stdout.reconfigure(encoding="utf-8", newline=chr(10))
    try:
        sys.exit(main(sys.argv))
    except SystemExit:
        raise
    except Exception as ex:
        print("MAINT_FORMAT 내부 예외 " + type(ex).__name__)
        sys.exit(3)
