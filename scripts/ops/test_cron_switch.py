"""OPS_DAILY_CHECK.md §3-1 cron 상태 전환 블록 시험 (로컬 — 운영에 닿지 않는다). 리뷰 2026-10-31 보완 2.

    python scripts/ops/test_cron_switch.py

문서의 블록을 손으로 옮기지 않고 그대로 꺼내(§3-1 첫 bash 블록의 '(' ~ ')'), 가짜 crontab(임시 폴더의 파일 하나)을 PATH 앞에 두고 bash 로 돌린다.
HOME 도 임시 폴더라 실제 crontab·~/daily-check 에 닿지 않는다. 실패 사례에서는 cron 파일이 한 글자도 바뀌지 않아야 한다.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DOC = os.path.join(HERE, "..", "..", "docs", "OPS_DAILY_CHECK.md")
BASH = os.environ.get("BASH_EXE") or (r"C:\Program Files\Git\usr\bin\bash.exe" if os.name == "nt" else shutil.which("bash"))
sys.stdout.reconfigure(encoding="utf-8")
results = []

FAKE = r'''#!/bin/bash
# 가짜 crontab — $FAKE_DIR/crontab.txt 하나가 사용자 crontab 이다
CT="$FAKE_DIR/crontab.txt"
case "$1" in
  -l) [ -n "$FAKE_LIST_FAIL" ] && { echo "no crontab for user" >&2; exit 1; }
      [ -e "$CT" ] || { echo "no crontab for user" >&2; exit 1; }
      cat "$CT" ;;
  -)  [ -n "$FAKE_WRITE_FAIL" ] && exit 1
      cat > "$CT"; touch "$FAKE_DIR/written" ;;
  *)  [ -n "$FAKE_WRITE_FAIL" ] && { echo "crontab: write failed" >&2; exit 1; }
      cp "$1" "$CT"; touch "$FAKE_DIR/written"
      [ -n "$FAKE_CORRUPT" ] && echo "# 적용 중 섞인 줄" >> "$CT"; true ;;
esac
'''

CRON = ("# 정기 점검 자동화 (OPS_DAILY_CHECK.md) — 10:00·22:00 KST\n"
        "0 10,22 * * * SID_OK_STATUS=CREATED REPO_DIR=$HOME/crypto_trader/AutoTrader SQL_FILE=$HOME/ops-scripts/daily_checks.sql "
        "$HOME/ops-scripts/daily_check.sh >> $HOME/daily-check/cron.log 2>&1\n"
        "30 9 * * * cd $HOME/crypto_trader/AutoTrader && set -C && echo ledger > $HOME/sid203-ledger/x.txt\n"
        "25 0 * * * python3 $HOME/other/start_66.py watch\n")


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "\n     " + str(detail).replace("\n", "\n     ")))


def block():
    doc = open(DOC, encoding="utf-8").read()
    sec = doc[doc.index("### 3-1."):doc.index("## 4. 수동 실행")]
    m = re.search(r"```bash\n(\(\n.*?\n\))\n", sec, re.S)
    return m.group(1)


BLOCK = block()


def run(cron=CRON, script=None, d=None, **env):
    """d 를 주면 그 임시 HOME 을 다시 쓴다(두 번째 실행)."""
    if d is None:
        d = tempfile.mkdtemp(prefix="cronsw-")
        os.makedirs(os.path.join(d, "bin")); os.makedirs(os.path.join(d, "daily-check"))
        with open(os.path.join(d, "bin", "crontab"), "w", encoding="utf-8", newline="\n") as f:
            f.write(FAKE)
    for x in ("written", "crontab.txt"):
        if os.path.exists(os.path.join(d, x)):
            os.remove(os.path.join(d, x))
    if cron is not None:
        with open(os.path.join(d, "crontab.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write(cron)
    e = dict(os.environ, HOME=d, FAKE_DIR=d, **env)
    # Git Bash 에서는 C:\… 경로의 ':' 가 PATH 구분자와 겹친다 — POSIX 경로로 바꾼다(리눅스에서는 그대로)
    pre = ('FAKE_DIR=$(cygpath -u "$FAKE_DIR" 2>/dev/null || echo "$FAKE_DIR"); HOME=$FAKE_DIR; export FAKE_DIR HOME\n'
           'chmod +x "$FAKE_DIR/bin/crontab"; export PATH="$FAKE_DIR/bin:$PATH"\n')
    r = subprocess.run([BASH, "-c", pre + (script or BLOCK)], env=e, capture_output=True)
    out = r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    ct = os.path.join(d, "crontab.txt")
    now = open(ct, encoding="utf-8").read() if os.path.exists(ct) else None
    return r.returncode, out, now, os.path.exists(os.path.join(d, "written")), d


check("문서 블록을 꺼냈다 — set -euo pipefail · 조회 성공본 저장 · 대상 1개 · 다른 줄 보존 · 적용 뒤 대조",
      "set -euo pipefail" in BLOCK and 'crontab -l > "$B"' in BLOCK and "diff <(" in BLOCK and 'crontab "$N"' in BLOCK and "cmp -s" in BLOCK
      and "| crontab -" not in BLOCK, BLOCK)

# 종전 명령 재현 — 리뷰 지적: 조회가 실패하면 빈 입력을 적용해 cron 전체를 비운다
old = "crontab -l | sed 's/SID_OK_STATUS=CREATED /SID_OK_STATUS=RUNNING /' | crontab -"
rc, out, now, written, d = run(script=old, FAKE_LIST_FAIL="1")
check("종전 명령(v1.28) 재현 — 조회 실패에도 빈 입력이 적용돼 cron 4줄이 0줄이 된다(결함 확인)", written and now == "", (rc, out, now))

rc, out, now, written, d = run()
exp = CRON.replace("SID_OK_STATUS=CREATED ", "SID_OK_STATUS=RUNNING ")
check("정상 — ✅ CREATED → RUNNING · 대상 줄만 바뀌고 다른 3줄 그대로 · 보존본 = 원래 cron",
      rc == 0 and "✅ cron 전환 CREATED → RUNNING" in out and now == exp
      and open(os.path.join(d, "daily-check", "crontab.before-RUNNING"), encoding="utf-8").read() == CRON, (rc, out, now))

for name, kw, msg in (
        ("조회 실패", dict(FAKE_LIST_FAIL="1"), "crontab 조회 실패"),
        ("대상 줄 0(이미 RUNNING)", dict(cron=exp), "SID_OK_STATUS=CREATED 줄이 정확히 1개가 아니다"),
        ("대상 줄 2", dict(cron=CRON + CRON.splitlines(True)[1]), "SID_OK_STATUS=CREATED 줄이 정확히 1개가 아니다"),
        ("적용 실패", dict(FAKE_WRITE_FAIL="1"), "crontab 적용 실패"),
):
    before = kw.get("cron", CRON)
    rc, out, now, written, d = run(**kw)
    check("%s — 🔴 멈춤 '%s' · cron 그대로(%s)" % (name, msg, "적용 시도했으나 거절" if "WRITE" in str(kw) else "적용 안 함"),
          rc != 0 and ("🔴 멈춤: " + msg) in out and "✅" not in out and now == before and not written, (rc, out, now))

rc, out, now, written, d = run(cron=None)
check("crontab 없음(no crontab for user) — 🔴 멈춤 조회 실패 · 아무것도 만들지 않음",
      rc != 0 and "🔴 멈춤: crontab 조회 실패" in out and now is None and not written, (rc, out))
rc, out, now, written, d = run(cron="")
check("빈 crontab(조회 성공·0줄) — 🔴 멈춤 '비었다' · 적용 안 함", rc != 0 and "🔴 멈춤: 조회한 crontab 이 비었다" in out and now == "" and not written, (rc, out))
rc, out, now, written, d = run(FAKE_CORRUPT="1")
check("적용 뒤 대조 실패 — 🔴 '되돌리고 crontab -l 출력을 보고' 안내 · ✅ 없음",
      rc != 0 and "🔴 적용 뒤 대조 실패" in out and "crontab.before-RUNNING" in out and "✅" not in out, (rc, out))

# 다시 돌리면(보존본이 이미 있음) 멈춘다 — 같은 HOME 에서 두 번째 실행(cron 은 원래 내용으로 되돌려 둔다)
rc1, out1, now1, w1, d = run()
rc, out, now, written, d = run(d=d)
check("보존본이 이미 있음(두 번째 실행) — 🔴 멈춤 · 보존본을 덮어쓰지 않고 cron 그대로",
      rc1 == 0 and rc != 0 and "이미 있다" in out and now == CRON and not written
      and open(os.path.join(d, "daily-check", "crontab.before-RUNNING"), encoding="utf-8").read() == CRON, out)

# 56일 종료 뒤 — 첫 줄만 FROM=RUNNING; TO=EMERGENCY_STOPPED (비상정지 상태 그대로를 정상으로)
end_block = BLOCK.replace("FROM=CREATED; TO=RUNNING", "FROM=RUNNING; TO=EMERGENCY_STOPPED", 1)
rc, out, now, written, d = run(cron=exp, script=end_block)
check("종료 뒤 전환(첫 줄만 바꿈) RUNNING → EMERGENCY_STOPPED — ✅ · 대상 줄만 바뀜",
      end_block != BLOCK and rc == 0 and "✅ cron 전환 RUNNING → EMERGENCY_STOPPED" in out
      and now == exp.replace("SID_OK_STATUS=RUNNING ", "SID_OK_STATUS=EMERGENCY_STOPPED "), (rc, out, now))

# ── 2026-10-31 리뷰: §3 "기존 줄 교체" 블록 — SID_OK_STATUS 없는 2026-10-22 줄을 CREATED 명시 줄로, 그 줄만 ──
def block_replace():
    doc = open(DOC, encoding="utf-8").read()
    sec = doc[doc.index("**기존 줄 교체"):doc.index("### 3-1.")]
    return re.search(r"```bash\n(\(\n.*?\n\))\n", sec, re.S).group(1)


RBLOCK = block_replace()
OLD_LINE = ("0 10,22 * * * REPO_DIR=$HOME/crypto_trader/AutoTrader SQL_FILE=$HOME/ops-scripts/daily_checks.sql "
            "$HOME/ops-scripts/daily_check.sh >> $HOME/daily-check/cron.log 2>&1\n")
OLD_CRON = CRON.replace(CRON.splitlines(True)[1], OLD_LINE)
check("교체 블록을 꺼냈다 — 글자 그대로 1줄(grep -Fx) · 다른 줄 보존 · 적용 뒤 대조 · 파이프 적용 없음",
      "set -euo pipefail" in RBLOCK and "grep -cFx" in RBLOCK and "diff <(" in RBLOCK and "cmp -s" in RBLOCK and "| crontab -" not in RBLOCK
      and OLD_LINE.strip() in RBLOCK, RBLOCK)

rc, out, now, written, d = run(cron=OLD_CRON, script=RBLOCK)
check("교체 정상 — ✅ · 대상 줄만 CREATED 명시 줄로, 다른 일정 3줄 그대로 · 보존본 = 원래 cron",
      rc == 0 and "✅ 정기 점검 줄 교체" in out and now == CRON
      and open(os.path.join(d, "daily-check", "crontab.before-sidok"), encoding="utf-8").read() == OLD_CRON, (rc, out, now))
# 이어서 12-01 §3-1 전환이 그대로 된다
rc2, out2, now2, w2, d = run(cron=now, script=BLOCK, d=d)
check("교체 뒤 §3-1 전환(CREATED → RUNNING)이 이어서 된다", rc2 == 0 and now2 == exp, (rc2, out2, now2))

for name, kw, msg in (
        ("조회 실패", dict(FAKE_LIST_FAIL="1"), "crontab 조회 실패"),
        ("대상 줄 0(이미 교체됨)", dict(cron=CRON), "SID_OK_STATUS 줄이 이미 있다"),
        ("정기 점검 줄 2", dict(cron=OLD_CRON + OLD_LINE), "정기 점검 줄이 정확히 1개가 아니다"),
        ("글자가 다른 줄(시각 0 9,21)", dict(cron=OLD_CRON.replace("0 10,22 ", "0 9,21 ")), "기존 10:00·22:00 줄과 글자 그대로 같은 줄이 정확히 1개가 아니다"),
        ("글자가 다른 줄(끝 공백)", dict(cron=OLD_CRON.replace("2>&1\n30 9", "2>&1 \n30 9")), "기존 10:00·22:00 줄과 글자 그대로 같은 줄이 정확히 1개가 아니다"),
        ("적용 실패", dict(FAKE_WRITE_FAIL="1"), "crontab 적용 실패"),
):
    before = kw.pop("cron", OLD_CRON)
    rc, out, now, written, d = run(cron=before, script=RBLOCK, **kw)
    check("교체 %s — 🔴 멈춤 '%s' · cron 그대로" % (name, msg),
          rc != 0 and ("🔴 멈춤: " + msg) in out and "✅" not in out and now == before and not written, (rc, out, now))
rc1, out1, now1, w1, d = run(cron=OLD_CRON, script=RBLOCK)
rc, out, now, written, d = run(cron=OLD_CRON, script=RBLOCK, d=d)
check("교체 두 번째 실행(보존본 있음) — 🔴 멈춤 · 보존본 그대로",
      rc1 == 0 and rc != 0 and "이미 있다" in out and now == OLD_CRON and not written
      and open(os.path.join(d, "daily-check", "crontab.before-sidok"), encoding="utf-8").read() == OLD_CRON, out)
rc, out, now, written, d = run(cron=OLD_CRON, script=RBLOCK, FAKE_CORRUPT="1")
check("교체 적용 뒤 대조 실패 — 🔴 되돌리기 안내 · ✅ 없음",
      rc != 0 and "🔴 적용 뒤 대조 실패" in out and "crontab.before-sidok" in out and "✅" not in out, (rc, out))

n_ok = sum(1 for _, ok in results if ok)
print(f"cron 전환 시험 {n_ok}/{len(results)} 통과")
sys.exit(0 if n_ok == len(results) else 1)
