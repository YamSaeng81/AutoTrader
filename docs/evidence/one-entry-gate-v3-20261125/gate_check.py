"""1회 제한 시험 세션 종료·복구 게이트 v3 판정기 (총괄 v1.243 §3-1).

사용: python -I gate_check.py <psql 출력> <psql 종료코드 파일> <등록 파일>
  psql 출력: recovery-gate-v3.sql 을 -A -F '|' -P footer=off 로 돌린 결과(머리줄 + 한 행)
  종료코드 파일: psql 종료코드 숫자 한 줄
  등록 파일: key=value 줄. 필수 키 REG_KEYS. 시험 전에 고정한 사전 등록값이다.
출력: 첫 줄 'VERDICT PASS|HOLD|FAIL', 다음 줄부터 사유. 종료코드 PASS 0 · HOLD 2 · FAIL 1.
PASS 는 이 DB 판정만이다 — 거래소 잔량(장부 밖 체결)은 확인하지 않는다(A3·장부 대조가 맡는다).
"""
import sys
from decimal import Decimal, InvalidOperation

COLS = ["found", "target_sid", "status", "session_type", "strategy_type", "coin_pair", "timeframe",
        "initial_capital", "invest_ratio", "max_investment", "max_entries_type", "max_entries_text",
        "started", "stopped_at_set",
        "active_orders", "unknown_order_states", "fill_accounting_pending", "sell_unapplied",
        "open_or_residual_positions", "unknown_position_status", "cleanup_pending", "buy_rows",
        "unproven_terminal", "discrepancy_open",
        "sell_rows", "cleanup_done", "settlement_recheck", "unresolved_submissions", "end_mark"]
BLOCK = ["active_orders", "unknown_order_states", "fill_accounting_pending", "sell_unapplied",
         "open_or_residual_positions", "unknown_position_status", "cleanup_pending"]
HOLD = ["unproven_terminal", "discrepancy_open"]
COUNTS = BLOCK + HOLD + ["buy_rows", "sell_rows", "cleanup_done", "settlement_recheck", "unresolved_submissions"]
REG_KEYS = ["sid", "session_type", "strategy_type", "coin_pair", "timeframe",
            "initial_capital", "invest_ratio", "max_investment", "max_entries"]
NUMERIC = {"initial_capital", "invest_ratio", "max_investment"}
KNOWN_STATUS = {"CREATED", "RUNNING", "STOPPED", "EMERGENCY_STOPPED", "DELETED"}


def num(v):
    try:
        return Decimal(v)
    except (InvalidOperation, TypeError):
        return None


def judge(out_path, rc_path, reg_path):
    fail, hold = [], []
    try:
        rc = open(rc_path, encoding="utf-8").read().strip()
    except OSError as e:
        return "FAIL", ["종료코드 파일 없음: %s" % e]
    if rc != "0":
        fail.append("조회 실패(psql rc=%s)" % rc)
    try:
        lines = [l.rstrip("\r\n") for l in open(out_path, encoding="utf-8") if l.strip()]
    except OSError as e:
        return "FAIL", fail + ["출력 파일 없음: %s" % e]
    reg = {}
    try:
        for l in open(reg_path, encoding="utf-8"):
            l = l.strip()
            if l and not l.startswith("#") and "=" in l:
                k, v = l.split("=", 1)
                reg[k.strip()] = v.strip()
    except OSError as e:
        fail.append("등록 파일 없음: %s" % e)
    missing = [k for k in REG_KEYS if not reg.get(k)]
    if missing:
        fail.append("등록값 비어 있음: " + ",".join(missing))
    if fail:
        return "FAIL", fail
    if len(lines) != 2 or lines[0].split("|") != COLS:
        return "FAIL", ["출력 형태 불일치(머리줄+1행 아님 또는 열 다름) — 행 %d" % len(lines)]
    row = dict(zip(COLS, lines[1].split("|")))
    if len(lines[1].split("|")) != len(COLS) or row["end_mark"] != "END":
        return "FAIL", ["부분 출력(END 표지 없음 또는 열 수 다름)"]
    if row["found"] != "t":
        return "FAIL", ["대상 세션 없음(target_sid=%s)" % row["target_sid"]]
    if row["target_sid"] != reg["sid"]:
        fail.append("대상 ID %s ≠ 등록 %s" % (row["target_sid"], reg["sid"]))
    for k in ["session_type", "strategy_type", "coin_pair", "timeframe"]:
        if row[k] != reg[k]:
            fail.append("%s %s ≠ 등록 %s" % (k, row[k], reg[k]))
    if row["session_type"] != "REAL":
        fail.append("session_type REAL 아님: %s" % row["session_type"])
    for k in NUMERIC:
        a, b = num(row[k]), num(reg[k])
        if a is None or b is None or a != b:
            fail.append("%s %s ≠ 등록 %s" % (k, row[k], reg[k]))
    if reg["max_entries"] != "1":
        fail.append("등록 max_entries 가 1 이 아님: %s" % reg["max_entries"])
    if row["max_entries_type"] != "number" or row["max_entries_text"] != "1":
        fail.append("maxEntries 정수 1 아님: type=%s text=%r" % (row["max_entries_type"], row["max_entries_text"]))
    counts = {}
    for k in COUNTS:
        if not row[k].isdigit():
            fail.append("개수 열 %s 가 숫자 아님: %r" % (k, row[k]))
        else:
            counts[k] = int(row[k])
    st = row["status"]
    if st not in KNOWN_STATUS:
        fail.append("알 수 없는 세션 상태: %r" % st)
    elif st == "EMERGENCY_STOPPED":
        hold.append("비상 정지 — 사람이 원인·미해결 상태를 판단(자동 통과 없음)")
    elif st != "STOPPED":
        fail.append("STOPPED 아님: %s" % st)
    for k in BLOCK:
        if counts.get(k, 0) != 0:
            fail.append("%s = %d" % (k, counts[k]))
    if counts.get("buy_rows", 0) > 1:
        fail.append("buy_rows = %d > 1 (1회 제한 위반)" % counts["buy_rows"])
    for k in HOLD:
        if counts.get(k, 0) != 0:
            hold.append("%s = %d — 해당 주문을 따로 읽고 사람 확인" % (k, counts[k]))
    if fail:
        return "FAIL", fail + hold
    if hold:
        return "HOLD", hold
    return "PASS", ["STOPPED·차단 0·보류 0. 참고: buy=%d sell=%d cleanup_done=%d settlement_recheck=%d"
                    % (counts["buy_rows"], counts["sell_rows"], counts["cleanup_done"], counts["settlement_recheck"]),
                    "거래소 잔량은 이 판정 밖(A3·장부 대조)"]


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) != 4:
        print("VERDICT FAIL\n사용법 오류")
        sys.exit(1)
    v, why = judge(*sys.argv[1:4])
    print("VERDICT " + v)
    for w in why:
        print("- " + w)
    sys.exit({"PASS": 0, "HOLD": 2}.get(v, 1))
