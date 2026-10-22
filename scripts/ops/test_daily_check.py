"""daily_check.sh 시험 (2026-10-22) — 로컬 시험 DB(cat-rt-pg, V94)와 모의 텔레그램 서버로 돌린다. 운영에 닿지 않는다.

경우: 정상 · 수치 이상 · 미분류 ERROR · DB 조회 실패 · 출력 누락 · 시간 초과 · 앱 없음 · 텔레그램 전송 실패 · 중복 실행.
실행: python scripts/ops/test_daily_check.py   (cat-rt-pg 컨테이너가 떠 있어야 한다 — scripts/restart-test/rt.py 와 같은 시험 DB)
"""
import http.server
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SCRIPT = os.path.join(HERE, "daily_check.sh")
DB = "rt_daily"
PG = ["docker", "exec", "-i", "cat-rt-pg", "psql", "-X", "-q", "-U", "trader"]
BASH = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"

results = []
sys.stdout.reconfigure(encoding="utf-8")


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "\n     " + detail.replace("\n", "\n     ")))


def sql(db, text):
    r = subprocess.run(PG + ["-d", db, "-v", "ON_ERROR_STOP=1"], input=text.encode("utf-8"), capture_output=True)
    if r.returncode != 0:
        raise SystemExit("SQL 실패: " + r.stderr.decode("utf-8", "replace"))


# ── 모의 텔레그램 서버 ───────────────────────────────────────────────────────────
class TG(http.server.BaseHTTPRequestHandler):
    status = 200
    received = []

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        TG.received.append(self.rfile.read(n).decode("utf-8", "replace"))
        body = json.dumps({"ok": TG.status == 200}).encode()
        self.send_response(TG.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), TG)
threading.Thread(target=srv.serve_forever, daemon=True).start()
TG_API = "http://127.0.0.1:%d" % srv.server_address[1]

# ── 시험 DB: V94 시험 DB 사본 + 66세션 행 ─────────────────────────────────────────
sql("postgres", "DROP DATABASE IF EXISTS %s; CREATE DATABASE %s TEMPLATE crypto_auto_trader;" % (DB, DB))
sql(DB, "INSERT INTO paper_trading.virtual_balance (id, total_krw, available_krw, status) "
        "SELECT g, 1000000, 1000000, 'RUNNING' FROM generate_series(522, 587) g;")

work = tempfile.mkdtemp(prefix="daily-check-")
logs_ok = os.path.join(work, "logs_ok.txt")
with open(logs_ok, "w", encoding="utf-8") as f:
    f.write("backend-1  | 04:48:09.169 [main] ERROR c.c.api.service.CapitalPoolLimits - 🔴 공유 풀 한도가 사전 등록과 맞지 않는다 — 실자금 진입·배정을 모두 거절한다: 한도 설정 누락 (단계= 총상한=null)\n")
    f.write("backend-1  | 04:49:09.466 [scheduler-3] ERROR c.c.api.service.CandleFreshnessGuard - 🔴 [캔들 최신성] KRW-XRP:M15 판정 불가 (DYNAMIC) — 결측 19칸\n")
    f.write("backend-1  | 05:06:15.503 [task-1] ERROR c.c.exchange.upbit.UpbitRestClient - Upbit API 오류: status=404, url=https://api.upbit.com/v1/candles/minutes/60?market=KRW-BONK&count=1, body={\"error\":{\"name\":404,\"message\":\"Code not found\"}}\n")
    f.write("backend-1  | 15:01:45.636 [scheduler-2] ERROR c.c.e.upbit.UpbitCandleCollector - 캔들 수집 실패: null\n")
    f.write("backend-1  | 19:40:02.424 [telegram-1] ERROR c.c.a.s.TelegramNotificationService - [Telegram] 메시지 전송 중 오류(응답 미수신): Connection reset\n")
    f.write("backend-1  | 05:00:00.000 [main] INFO  c.c.api.CryptoAutoTraderApplication - Started\n")
logs_bad = os.path.join(work, "logs_bad.txt")
shutil.copy(logs_ok, logs_bad)
with open(logs_bad, "a", encoding="utf-8") as f:
    f.write("backend-1  | 06:00:00.000 [scheduler-1] ERROR c.c.a.service.LiveTradingService - 주문 상태 조회 실패: 예상 밖 응답\n")
    # 알려진 로거라도 문구가 다르면 미분류다 — 제외 조건을 로거+문구로 한정했는지 본다
    f.write("backend-1  | 06:01:00.000 [scheduler-1] ERROR c.c.api.service.CapitalPoolLimits - 🔴 공유 풀 예약 실패: 잔액 부족\n")

trunc = os.path.join(work, "psql_trunc.sh")
with open(trunc, "w", newline="\n") as f:
    f.write('#!/usr/bin/env bash\ndocker exec -i cat-rt-pg psql -X -U trader -d %s "$@" | head -4\n' % DB)
slow = os.path.join(work, "psql_slow.sh")
with open(slow, "w", newline="\n") as f:
    f.write("#!/usr/bin/env bash\nsleep 20\n")

BASE_ENV = dict(os.environ,
                REPO_DIR=REPO,
                PSQL_CMD="docker exec -i cat-rt-pg psql -X -U trader -d " + DB,
                LOGS_FILE=logs_ok,
                APP_STATE="running|0|2026-10-01T00:00:00Z|sha256:8b1b8c7898c0",
                ALERTS_CMD="echo '🟢 경보 없음 · 실행 누락 없음'; exit 0",
                TG_API=TG_API, TELEGRAM_BOT_TOKEN="test-token", TELEGRAM_CHAT_ID="1",
                TZ_EXPECT="",   # 시험 기계 시간대는 따지지 않는다(아래 별도 경우)
                SQL_TIMEOUT="20")


def run(name, **over):
    out_dir = os.path.join(work, name)
    env = dict(BASE_ENV, OUT_DIR=out_dir.replace("\\", "/"))
    env.update(over)
    TG.received.clear()
    r = subprocess.run([BASH, SCRIPT.replace("\\", "/")], env=env, capture_output=True)
    rep = ""
    if os.path.isdir(out_dir):
        files = sorted(x for x in os.listdir(out_dir) if x.endswith(".txt") and x[0] == "2")
        if files:
            rep = open(os.path.join(out_dir, files[-1]), encoding="utf-8").read()
    last = ""
    p = os.path.join(out_dir, "last_send")
    if os.path.exists(p):
        last = open(p, encoding="utf-8").read()
    sent = list(TG.received)
    return r.returncode, rep, last, sent, r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")


# 1. 정상
rc, rep, last, sent, raw = run("normal")
check("정상 — '점검 항목 정상'·종료 0·보고 1건 전송", rc == 0 and "🟢 점검 항목 정상" in rep and len(sent) == 1 and "보고 전송 성공" in last, raw + rep)
check("정상 — 실행 시각·구간·운영 SHA 가 결과에 남는다", "실행 " in rep and "구간 " in rep and "운영 " in rep, rep)
check("정상 — 알려진 ERROR 는 🔴 아님(공유 풀 1·캔들 1·404 1·통신 2·미분류 0)",
      "공유 풀 미등록 1 · 캔들 판정 불가 1 · 상장 폐지 404 1 · 통신 실패 2 · 미분류 0" in rep and "🔴" not in rep.split("—")[0], rep)
check("정상 — 보고 본문은 평문(서식 지정 없음)", sent and "parse_mode" not in sent[0], sent[0] if sent else "")

# 2. 수치 이상 — 66세션 하나 정지 + 중요 경보 전송 실패 기록
sql(DB, "UPDATE paper_trading.virtual_balance SET status='STOPPED' WHERE id=587;"
        "INSERT INTO telegram_notification_log (type, session_label, message_text, success, sent_at) VALUES ('RISK_LIMIT', NULL, '한도', FALSE, now());"
        "INSERT INTO telegram_notification_log (type, session_label, message_text, success, sent_at) VALUES ('CUSTOM', 'system', '보고', FALSE, now());")
rc, rep, last, sent, raw = run("anomaly")
check("수치 이상 — 🔴·종료 1·66세션 65/66·중요 경보 실패 🔴·CUSTOM 실패는 🟡",
      rc == 1 and rep.startswith("[정기 점검] 🔴") and "🔴 검증 66세션 RUNNING 65/66" in rep
      and "🔴 텔레그램 중요 경보 전송 실패 RISK_LIMIT 1건" in rep and "🟡 텔레그램 전송 실패 CUSTOM 1건" in rep and len(sent) == 1, raw + rep)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='RUNNING' WHERE id=587; DELETE FROM telegram_notification_log;")

# 3. 미분류 ERROR
rc, rep, last, sent, raw = run("unknown_error", LOGS_FILE=logs_bad)
check("미분류 ERROR — 🔴 2건(다른 로거 1 + 알려진 로거의 다른 문구 1), 줄을 결과에 남긴다",
      rc == 1 and "🔴 미분류 ERROR 2건" in rep and "주문 상태 조회 실패" in rep and "공유 풀 예약 실패" in rep, rep)

# 4. DB 조회 실패
rc, rep, last, sent, raw = run("db_fail", PSQL_CMD="docker exec -i cat-rt-pg psql -X -U trader -d no_such_db")
check("DB 조회 실패 — ⚪ 판정 불가·종료 1·보고 전송(정상 아님)",
      rc == 1 and rep.startswith("[정기 점검] ⚪ 판정 불가") and "DB 조회 실패" in rep and "점검 항목 정상" not in rep and len(sent) == 1, raw + rep)

# 5. 출력 누락
rc, rep, last, sent, raw = run("truncated", PSQL_CMD="bash " + trunc.replace("\\", "/"))
check("DB 출력 누락 — ⚪ 판정 불가(끝 표시 없음)", rc == 1 and "출력 누락" in rep and rep.startswith("[정기 점검] ⚪"), raw + rep)

# 6. 시간 초과
rc, rep, last, sent, raw = run("timeout", PSQL_CMD="bash " + slow.replace("\\", "/"), SQL_TIMEOUT="2")
check("DB 시간 초과 — ⚪ 판정 불가", rc == 1 and "DB 조회 시간 초과(2초)" in rep, raw + rep)

# 7. 앱 없음 — 나머지 점검·보고는 계속
rc, rep, last, sent, raw = run("app_down", APP_STATE="absent|0|-|-",
                               ALERTS_CMD="echo 'API 에 닿지 않는다' >&2; exit 2")
check("앱 없음 — 🔴 컨테이너 없음 + DB 점검은 그대로 + 66세션은 판정 불가 + 보고 전송",
      rc == 1 and "🔴 backend 컨테이너 없음" in rep and "66세션 RUNNING 66" in rep and "66세션 경보 조회 실패" in rep and len(sent) == 1, raw + rep)

# 8. 구간 안 재기동 — 계획 여부 확인 요구
rc, rep, last, sent, raw = run("restarted", APP_STATE="running|0|%s|sha256:abc" %
                               subprocess.run([BASH, "-c", "date -u -d '-1 hour' '+%Y-%m-%dT%H:%M:%SZ'"], capture_output=True, text=True).stdout.strip())
check("구간 안 기동 — 🔴 '계획된 재기동인지 확인'", rc == 1 and "계획된 재기동인지 확인" in rep, rep)

# 9. 텔레그램 전송 실패 — 종료 2·last_send 에 실패·결과 파일은 남는다
TG.status = 500
rc, rep, last, sent, raw = run("tg_fail")
TG.status = 200
check("텔레그램 전송 실패 — 3회 시도 뒤 종료 2·'보고 전송 실패' 기록·결과 파일 유지",
      rc == 2 and len(sent) == 3 and "보고 전송 실패" in last and "🟢 점검 항목 정상" in rep, raw + rep + last)

# 10. 중복 실행 — 살아 있는 잠금이면 건너뛴다
dup_dir = os.path.join(work, "dup").replace("\\", "/")
cmd = 'mkdir -p "%s/.lock"; sleep 15 & echo $! > "%s/.lock/pid"; bash "%s"; echo "rc=$?"; kill %%1' % (dup_dir, dup_dir, SCRIPT.replace("\\", "/"))
r = subprocess.run([BASH, "-c", cmd], env=dict(BASE_ENV, OUT_DIR=dup_dir), capture_output=True)
o = r.stdout.decode("utf-8", "replace")
check("중복 실행 — 살아 있는 잠금이면 종료 3·건너뜀", "rc=3" in o and "이미 실행 중" in o, o)

# 11. 죽은 잠금은 넘겨받는다
stale = os.path.join(work, "stale").replace("\\", "/")
os.makedirs(stale + "/.lock")
open(stale + "/.lock/pid", "w").write("999999")
rc, rep, last, sent, raw = run("stale")
check("죽은 잠금 — 넘겨받아 실행", rc == 0 and "점검 항목 정상" in rep, raw)

# 12. 서버 시간대가 KST 가 아니면 🟡 로 알린다
rc, rep, last, sent, raw = run("tz", TZ_EXPECT="+1234")
check("시간대 불일치 — 🟡 경고(판정은 정상 유지)", rc == 0 and "🟡 서버 시간대" in rep, rep)

sql("postgres", "DROP DATABASE IF EXISTS %s;" % DB)
srv.shutdown()
shutil.rmtree(work, ignore_errors=True)
passed = sum(1 for _, ok in results if ok)
print("\ndaily_check 시험 %d/%d 통과" % (passed, len(results)))
sys.exit(0 if passed == len(results) else 1)
