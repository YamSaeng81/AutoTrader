"""daily_check.sh 시험 (2026-10-22) — 로컬 시험 DB(cat-rt-pg, V94)와 모의 텔레그램 서버로 돌린다. 운영에 닿지 않는다.

2026-10-31: SID 203 실거래 판정(정상 매수·보유·청산·재진입 허용, 중복·미결 지연·정산 이상·다른 실자금 진입은 🔴).
경우: 정상 · 수치 이상 · 미분류 ERROR · DB 조회 실패 · 출력 누락 · 시간 초과 · 앱 없음 · 텔레그램 전송 실패 · 중복 실행.
2026-10-22 리뷰 보완: 66세션 결과 누락(종료 0 인데 정상 줄 없음 등) · 동시 시작 경합(로컬 mkdir 대체 잠금 + 리눅스 컨테이너의 flock) ·
공백 있는 retry_after 와 대기 상한 · 긴 한글 보고의 문자 단위 자르기 · 앱 로그 원문 보존.
실행: python scripts/ops/test_daily_check.py   (cat-rt-pg 컨테이너가 떠 있어야 한다 — scripts/restart-test/rt.py 와 같은 시험 DB)
"""
import gzip
import http.server
import json
import urllib.parse
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
# 2026-10-27: Windows 에서는 Git Bash 를 먼저 — PowerShell 의 PATH 로는 `bash` 가 WSL(System32\bash.exe)로 잡혀 `D:/…` 경로를 찾지 못하고
# 대부분의 경우가 실패한다(리뷰 실행 26/41 · 내 첫 실행도 같은 증상). 시험 대상 코드와 무관한 실행 환경 문제다.
GIT_BASH = r"C:\Program Files\Git\bin\bash.exe"
BASH = GIT_BASH if os.name == "nt" and os.path.exists(GIT_BASH) else (shutil.which("bash") or GIT_BASH)

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
    script = []        # 요청마다 (상태, 본문) — 비면 status·기본 본문
    texts = []         # 받은 text 를 UTF-8 엄격 해석으로 — 깨진 바이트면 "!!INVALID"

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n)
        TG.received.append(raw.decode("utf-8", "replace"))
        try:
            TG.texts.append(urllib.parse.parse_qs(raw.decode("ascii"), encoding="utf-8", errors="strict")["text"][0])
        except Exception as e:
            TG.texts.append("!!INVALID " + repr(e))
        if TG.script:
            st, b = TG.script.pop(0)
        else:
            st, b = TG.status, json.dumps({"ok": TG.status == 200})
        body = b.encode()
        self.send_response(st)
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
# 2026-10-31: 원본 시험 DB 에는 격리 리허설 기록(SID 203 STOPPED·주문 2건 등)이 보존돼 있다 — 원본은 건드리지 않고, 사본에서만 비운다.
sql(DB, 'TRUNCATE order_settlement_discrepancy, position_cleanup, "order", position, live_trading_session, paper_trading.virtual_balance CASCADE;')
# 운영 현재 상태가 기준 — SID 203 실자금 LIVE 세션 CREATED(12-01 시작 전), 주문·포지션 없음
SID203_ROW = ("INSERT INTO live_trading_session (id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, session_type, max_hold_hours) "
              "VALUES (203, 'DAILY_SMA20', 'KRW-BTC', 'H1', 20200, 20200, 20200, 'CREATED', 'REAL', 0);")
sql(DB, SID203_ROW)
sql(DB, "INSERT INTO paper_trading.virtual_balance (id, total_krw, available_krw, status) "
        "SELECT g, 1000000, 1000000, 'RUNNING' FROM generate_series(522, 587) g;")
# 검증 22코인 × 세 팔 — 사전 등록 순서 그대로(id 522 부터 코인마다 3개, KRW-BLAST = 576·577·578)
COINS22 = "IOTA WAVES CRO ONG SC POLYX NEAR WAXP BCH CVC POWR T ANKR DKA GLM HIVE PUNDIX ELF BLAST JUP G INJ".split()
sql(DB, "UPDATE paper_trading.virtual_balance v SET coin_pair = 'KRW-' || (ARRAY[%s])[(v.id - 522) / 3 + 1] WHERE v.id BETWEEN 522 AND 587;"
    % ",".join("'%s'" % c for c in COINS22))
# 운영 현재 상태가 기준 — KRW-BLAST 세 팔은 2026-10-27 13:05 KST 정지 완료(원래 66 중 RUNNING 63)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='STOPPED' WHERE id IN (576,577,578);")

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

# 서버 체크아웃을 흉내 내는 깨끗한 임시 저장소 — 시험하는 쪽 작업 트리의 변경이 "추적 파일 변경" 으로 잡히지 않게
fake_repo = os.path.join(work, "repo").replace("\\", "/")
os.makedirs(fake_repo)
open(os.path.join(fake_repo, "docker-compose.prod.yml"), "w").write("services: {}\n")
for c in (["init", "-q"], ["add", "."], ["-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init"]):
    subprocess.run(["git", "-C", fake_repo] + c, capture_output=True, check=True)
FAKE_SHA = subprocess.run(["git", "-C", fake_repo, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()

BASE_ENV = dict(os.environ,
                REPO_DIR=fake_repo,
                SQL_FILE=os.path.join(HERE, "daily_checks.sql").replace("\\", "/"),
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
    TG.texts.clear()
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
check("정상 — 실행 시각·구간·운영 SHA·점검 스크립트 판이 결과에 남는다",
      "실행 " in rep and "구간 " in rep and ("운영 " + FAKE_SHA) in rep and "점검 스크립트 " in rep, rep)
check("정상 — 알려진 ERROR 는 🔴 아님(공유 풀 1·캔들 1·404 1·통신 2·미분류 0)",
      "공유 풀 미등록 1 · 캔들 판정 불가 1 · 상장 폐지 404 1 · 통신 실패 2 · 미분류 0" in rep and "🔴" not in rep.split("—")[0], rep)
check("정상 — 보고 본문은 평문(서식 지정 없음)", sent and "parse_mode" not in sent[0], sent[0] if sent else "")

# 2. 수치 이상 — 66세션 하나 정지 + 중요 경보 전송 실패 기록
sql(DB, "UPDATE paper_trading.virtual_balance SET status='STOPPED' WHERE id=587;"
        "INSERT INTO telegram_notification_log (type, session_label, message_text, success, sent_at) VALUES ('RISK_LIMIT', NULL, '한도', FALSE, now());"
        "INSERT INTO telegram_notification_log (type, session_label, message_text, success, sent_at) VALUES ('CUSTOM', 'system', '보고', FALSE, now());")
rc, rep, last, sent, raw = run("anomaly")
# 2026-10-27: 개수(65/66)가 아니라 어느 세션인지로 알린다 — 지정 종료 세션 밖이라 🔴
check("수치 이상 — 🔴·종료 1·지정 종료 밖 587 정지 🔴·중요 경보 실패 🔴·CUSTOM 실패는 🟡",
      rc == 1 and rep.startswith("[정기 점검] 🔴") and "🔴 검증 세션 RUNNING 아님(지정 종료 외): 587:STOPPED" in rep
      and "원래 66 중 RUNNING 62" in rep
      and "🔴 텔레그램 중요 경보 전송 실패 RISK_LIMIT 1건" in rep and "🟡 텔레그램 전송 실패 CUSTOM 1건" in rep and len(sent) == 1, raw + rep)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='RUNNING' WHERE id=587; DELETE FROM telegram_notification_log;")

# ── 2026-10-27 리뷰: 검증 코인 BLAST 거래지원 종료 — 지정 종료 세션·검증 코인 404·종료 확인 마켓의 66세션 경보 ──
# 2026-10-27 리뷰: 종전 이 경우는 '🟡 정지 대기(판정 정상)' 를 기대했다 — 정지가 끝난 지금은 다시 RUNNING 이면 🔴(의도치 않은 재시작)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='RUNNING' WHERE id=576;")
rc, rep, last, sent, raw = run("ended_rerun_one")
check("종료한 지정 세션 576 하나만 다시 RUNNING — 🔴 의도치 않은 재시작(🟡 로 넘기지 않는다)",
      rc == 1 and "🔴 거래 종료한 지정 세션이 다시 RUNNING(의도치 않은 재시작): 576" in rep
      and "66세션: 원래 66 중 RUNNING 64 · 지정 종료 STOPPED 2/3(576 577 578)" in rep and "🟡 거래 종료" not in rep, rep)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='RUNNING' WHERE id IN (576,577,578);")
rc, rep, last, sent, raw = run("ended_rerun_all")
check("지정 세션 셋 모두 RUNNING — 🔴 576 577 578", rc == 1 and "다시 RUNNING(의도치 않은 재시작): 576 577 578" in rep, rep)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='STOPPED' WHERE id IN (576,577,578);")
rc, rep, last, sent, raw = run("ended_done")
check("나머지 63 RUNNING + 576·577·578 STOPPED — 정상·'원래 66 중 RUNNING 63 · 지정 종료 STOPPED 3/3'(기대값을 63 으로 낮춘 것이 아니다)",
      rc == 0 and "🟢 점검 항목 정상" in rep and "다시 RUNNING" not in rep
      and "66세션: 원래 66 중 RUNNING 63 · 지정 종료 STOPPED 3/3(576 577 578)" in rep, rep)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='STOPPED' WHERE id=522;")
rc, rep, last, sent, raw = run("ended_plus_other")
check("지정 종료 3 + 다른 세션 522 정지 — RUNNING 63 이 아니라 62, 522 를 🔴 로 짚는다",
      rc == 1 and "🔴 검증 세션 RUNNING 아님(지정 종료 외): 522:STOPPED" in rep and "RUNNING 62" in rep, rep)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='RUNNING' WHERE id=522; UPDATE paper_trading.virtual_balance SET status='PAUSED' WHERE id=576;")
rc, rep, last, sent, raw = run("ended_other_status")
check("지정 종료 세션이라도 STOPPED 가 아닌 상태(PAUSED)면 🔴 — 재시작으로 겹쳐 세지 않는다",
      rc == 1 and "🔴 검증 세션 RUNNING 아님(지정 종료 외): 576:PAUSED" in rep and "다시 RUNNING" not in rep, rep)
sql(DB, "UPDATE paper_trading.virtual_balance SET status='STOPPED' WHERE id IN (576,577,578);")
rc, rep, last, sent, raw = run("ended_override", S66_ENDED_IDS="")
check("지정 종료 목록이 비면(설정으로 덮어씀) 576~578 정지는 🔴 — 목록 밖 정지를 정상으로 보지 않는다",
      rc == 1 and "🔴 검증 세션 RUNNING 아님(지정 종료 외): 576:STOPPED 577:STOPPED 578:STOPPED" in rep, rep)

logs_404 = os.path.join(work, "logs_404.txt")
shutil.copy(logs_ok, logs_404)
with open(logs_404, "a", encoding="utf-8") as f:
    for i in range(2):
        f.write("backend-1  | 01:3%d:20.802 [scheduler-2] ERROR c.c.exchange.upbit.UpbitRestClient - Upbit API 오류: status=404, url=https://api.upbit.com/v1/candles/minutes/60?market=KRW-BLAST&count=200&to=2026-10-27T01:30:20, body={\"error\":{\"name\":404,\"message\":\"Code not found\"}}\n" % i)
rc, rep, last, sent, raw = run("v404_ended", LOGS_FILE=logs_404)
check("검증 코인 BLAST 404(종료 확인 마켓) — 🟡 코인명·건수, 검증 밖 BONK 는 마켓명만, 판정은 정상",
      rc == 0 and "🟡 검증 코인 KRW-BLAST 캔들 404 2건 — 거래지원 종료 확인된 마켓" in rep
      and "캔들 404 검증 밖 마켓: KRW-BONK 1" in rep and "상장 폐지 404 3" in rep, rep)
with open(logs_404, "a", encoding="utf-8") as f:
    f.write("backend-1  | 02:00:01.000 [scheduler-3] ERROR c.c.exchange.upbit.UpbitRestClient - Upbit API 오류: status=404, url=https://api.upbit.com/v1/candles/minutes/60?market=KRW-JUP&count=200&to=2026-10-27T02:00:01, body={\"error\":{\"name\":404,\"message\":\"Code not found\"}}\n")
rc, rep, last, sent, raw = run("v404_unconfirmed", LOGS_FILE=logs_404)
check("검증 코인 JUP 404(종료 확인 안 됨) — 🔴 마켓 소실, BLAST 는 🟡 그대로",
      rc == 1 and "🔴 검증 코인 KRW-JUP 캔들 404 1건 — 마켓 소실(거래지원 종료 미확인)" in rep
      and "🟡 검증 코인 KRW-BLAST 캔들 404 2건" in rep, rep)
rc, rep, last, sent, raw = run("v404_no_ended", LOGS_FILE=logs_404, ENDED_MARKETS="")
check("종료 확인 마켓이 비면 BLAST 404 도 🔴", rc == 1 and "🔴 검증 코인 KRW-BLAST 캔들 404 2건 — 마켓 소실" in rep, rep)

AL_BLAST = ("echo '관측 2회 (구간 지정 시각)'; echo '🔴 10-27 01:19  1종: KRW-BLAST'; echo '🔴 10-27 02:19  1종: KRW-BLAST'; "
            "echo '현재 10-27 02:19: 🟢 21 / ⚪ 0 / 🔴 1'; echo; echo '🔴 적재 뒤처짐 2회'; echo; "
            "echo '🔴 🔴 은 경보이고 확정이 아니다 — 분봉'; exit 1")
rc, rep, last, sent, raw = run("al_ended", ALERTS_CMD=AL_BLAST)
check("66세션 경보가 전부 종료 확인 마켓(BLAST) — 🟡, 판정은 정상",
      rc == 0 and "🟡 66세션 경보: 적재 뒤처짐 2회 — 전부 거래지원 종료 확인 마켓(KRW-BLAST), 다른 코인 이상 없음" in rep, rep)
rc, rep, last, sent, raw = run("al_mixed", ALERTS_CMD=AL_BLAST.replace("1종: KRW-BLAST'; echo '🔴 10-27 02:19", "2종: KRW-BLAST KRW-NEAR'; echo '🔴 10-27 02:19"))
check("66세션 경보에 다른 코인(NEAR)이 섞이면 🔴 — 코인명 표시",
      rc == 1 and "🔴 66세션 경보(확정 아님 — 확인 필요): 적재 뒤처짐 2회 · 코인: KRW-NEAR" in rep, rep)
rc, rep, last, sent, raw = run("al_ended_gap", ALERTS_CMD=AL_BLAST.replace("echo '🔴 적재 뒤처짐 2회'", "echo '🔴 적재 뒤처짐 2회 / 실행 누락 1구간'"))
check("BLAST 뿐이어도 실행 누락이 함께 있으면 🔴", rc == 1 and "🔴 66세션 경보(확정 아님 — 확인 필요): 적재 뒤처짐 2회 / 실행 누락 1구간" in rep, rep)
rc, rep, last, sent, raw = run("al_ended_none", ALERTS_CMD=AL_BLAST, ENDED_MARKETS="")
check("종료 확인 마켓이 비면 BLAST 경보도 🔴", rc == 1 and "🔴 66세션 경보(확정 아님 — 확인 필요): 적재 뒤처짐 2회 · 코인: KRW-BLAST" in rep, rep)


# ── 2026-10-31 리뷰: SID 203 실거래 — 정상 매수·보유·청산·재진입은 허용, 다른 실자금 진입·중복 주문·정산 불일치는 계속 잡는다 ──
# 정상 체결 주문은 거래소 보고값(reported_*)과 반영값(applied_*)을 둘 다 갖고 같다(리뷰 2026-10-31 보완 — 종전 시험은 보고값을 넣지 않아 누락 자료가 🟢 였다).
# None = NULL(0 으로 대체하지 않는다). 체결 없는 주문(filled 0)은 넷 다 NULL.
def order(oid, pid, side, state, filled, ago_min, sid=203, applied=True, pending=False,
          rep_funds="19600", rep_fee="9.8", app_funds="19600", app_fee="9.8"):
    if not filled:
        rep_funds = rep_fee = app_funds = app_fee = None
    if not applied:
        app_funds = app_fee = None
    v = lambda x: "NULL" if x is None else x
    sql(DB, "INSERT INTO \"order\" (id, position_id, coin_pair, side, order_type, quantity, state, filled_quantity, session_id, session_kind, created_at, "
            "reported_executed_funds, reported_paid_fee, applied_executed_funds, applied_paid_fee, fill_accounting_pending) "
            "VALUES (%d, %s, 'KRW-BTC', '%s', 'MARKET', 0.0016, '%s', %s, %d, 'LIVE', now() - interval '%d minutes', %s, %s, %s, %s, %s);"
            % (oid, pid or "NULL", side, state, filled, sid, ago_min, v(rep_funds), v(rep_fee), v(app_funds), v(app_fee),
               "true" if pending else "false"))


def pos(pid, status, sid=203):
    sql(DB, "INSERT INTO position (id, coin_pair, side, entry_price, avg_price, size, status, session_id, session_kind) "
            "VALUES (%d, 'KRW-BTC', 'LONG', 12250000, 12250000, 0.0016, '%s', %d, 'LIVE');" % (pid, status, sid))


def sid_reset(status="RUNNING"):
    sql(DB, 'DELETE FROM order_settlement_discrepancy; DELETE FROM position_cleanup; DELETE FROM "order"; DELETE FROM position; '
            "DELETE FROM live_trading_session WHERE id <> 203; UPDATE live_trading_session SET status='%s' WHERE id=203;" % status)


GREEN = "🟢 점검 항목 정상"
GRAY = "⚪ 판정 불가"
RUNNING = dict(SID_OK_STATUS="RUNNING")   # F2 시작 확인 뒤 설치본 cron 의 설정(OPS_DAILY_CHECK.md §3-1) — 기본값은 CREATED 하나

rc, rep, last, sent, raw = run("sid_created")
check("SID 203 CREATED·주문 없음(지금 운영 상태) — 정상, 'SID 203: CREATED' 표시",
      rc == 0 and GREEN in rep and "SID 203: CREATED · 잔액 20200" in rep and "구간 주문 매수 0 매도 0" in rep, rep)
pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60)
rc, rep, last, sent, raw = run("sid_created_order")
check("SID 203 CREATED 인데 매수·보유 — 🔴 시작 전 주문", rc == 1 and "🔴 SID 203 시작 전(CREATED)인데 sid_open_positions 1" in rep
      and "sid_orders_window 1 0" in rep, rep)

sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60)
rc, rep, last, sent, raw = run("sid_buy_hold", **RUNNING)
check("SID 203 RUNNING·정상 매수 1건 체결·보유 — 정상(종전에는 실자금 보유·매수로 🔴)",
      rc == 0 and GREEN in rep and "SID 203: RUNNING" in rep and "보유 1 · 미결 0 · 구간 주문 매수 1 매도 0" in rep and "SID 203 밖 실자금 보유 0" in rep, rep)
sql(DB, "UPDATE position SET status='CLOSED' WHERE id=1;"); order(2, 1, "SELL", "FILLED", 0.0016, 30)
rc, rep, last, sent, raw = run("sid_close", **RUNNING)
check("SID 203 매수 뒤 청산(매도 체결·CLOSED) — 정상", rc == 0 and GREEN in rep and "보유 0 · 미결 0 · 구간 주문 매수 1 매도 1" in rep, rep)
sql(DB, "UPDATE \"order\" SET created_at = now() - interval '2 days' WHERE id IN (1,2);"); pos(2, "OPEN"); order(3, 2, "BUY", "FILLED", 0.0016, 60)
rc, rep, last, sent, raw = run("sid_reentry", **RUNNING)
check("SID 203 청산 뒤 다음 봉 재진입(새 포지션·체결 매수 1) — 정상", rc == 0 and GREEN in rep and "보유 1 · 미결 0 · 구간 주문 매수 1 매도 0" in rep, rep)
order(4, 2, "SELL", "SUBMITTED", 0, 2)
rc, rep, last, sent, raw = run("sid_sell_fresh", **RUNNING)
check("SID 203 매도 접수 직후(2분) 미결 1건 — 정상", rc == 0 and GREEN in rep and "미결 1" in rep, rep)

sid_reset(); pos(1, "OPEN"); pos(2, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60); order(2, 2, "BUY", "FILLED", 0.0016, 50)
rc, rep, last, sent, raw = run("sid_two_positions", **RUNNING)
check("SID 203 열린 포지션 2 · 구간 체결 매수 2 — 🔴 둘 다", rc == 1 and "🔴 SID 203 열린 포지션 2(상한 1)" in rep
      and "🔴 SID 203 구간 안 체결 매수(일봉 전략 — 중복 매수 의심) 2(상한 1)" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60); order(2, 1, "BUY", "FILLED", 0.0016, 59)
rc, rep, last, sent, raw = run("sid_dup_buy", **RUNNING)
check("SID 203 한 포지션에 체결 매수 2건 — 🔴 중복 매수", rc == 1 and "🔴 SID 203 한 포지션에 체결 매수 2건 이상(중복 매수) 1" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "SELL", "SUBMITTED", 0, 2); order(2, 1, "SELL", "PENDING", 0, 1)
rc, rep, last, sent, raw = run("sid_two_active", **RUNNING)
check("SID 203 미결 주문 동시 2건 — 🔴 중복 주문", rc == 1 and "🔴 SID 203 동시 미결 주문(중복 주문) 2(상한 1)" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "SELL", "SUBMITTED", 0, 30)
rc, rep, last, sent, raw = run("sid_stale", **RUNNING)
check("SID 203 미결 주문 30분 지속 — 🔴(15분 기준)", rc == 1 and "🔴 SID 203 미결 주문 15분 넘게 지속 1" in rep, rep)
sid_reset(); order(1, None, "BUY", "UNKNOWN", 0, 1)
rc, rep, last, sent, raw = run("sid_unknown", **RUNNING)
check("SID 203 UNKNOWN 주문 — 바로 🔴", rc == 1 and "🔴 SID 203 UNKNOWN 주문 1" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 30, applied=False)
rc, rep, last, sent, raw = run("sid_unapplied", **RUNNING)
check("SID 203 체결 주문의 반영값 없음 — ⚪ 확인 불가(0 대체 없음)", rc == 1 and GRAY in rep and "⚪ SID 203 체결 주문 정산 확인 불가 1건" in rep and "주문 1)" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 30, pending=True)
rc, rep, last, sent, raw = run("sid_acct_pending", **RUNNING)
check("SID 203 회계 반영 대기 — ⚪ 확인 불가", rc == 1 and GRAY in rep and "⚪ SID 203 체결 주문 정산 확인 불가 1건" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60)
sql(DB, "INSERT INTO order_settlement_discrepancy (order_id, kind) VALUES (1, 'FEE');")
rc, rep, last, sent, raw = run("sid_discrepancy", **RUNNING)
check("미해결 정산 불일치 1건(보고값·반영값은 같아도) — 🔴", rc == 1 and "🔴 미해결 정산 불일치 1" in rep, rep)
sql(DB, "UPDATE order_settlement_discrepancy SET resolved_at = now(), resolved_by = 'test';")
rc, rep, last, sent, raw = run("sid_discrepancy_resolved", **RUNNING)
check("같은 불일치가 해결됨 — 정상", rc == 0 and GREEN in rep, rep)

sid_reset()
sql(DB, "INSERT INTO live_trading_session (id, strategy_type, coin_pair, timeframe, initial_capital, available_krw, total_asset_krw, status, session_type) "
        "VALUES (204, 'DAILY_SMA20', 'KRW-ETH', 'H1', 20000, 20000, 20000, 'RUNNING', 'REAL');")
pos(9, "OPEN", sid=204); order(9, 9, "BUY", "FILLED", 0.0016, 60, sid=204); order(10, 9, "SELL", "SUBMITTED", 0, 1, sid=204)
rc, rep, last, sent, raw = run("other_real", **RUNNING)
check("다른 실자금 세션(204) 진입 — 🔴 보유·미결·신규 매수 모두(SID 203 은 정상)",
      rc == 1 and "🔴 SID 203 밖 실자금 보유(OPEN·CLOSING) 1" in rep and "🔴 SID 203 밖 실자금 미결 주문 1" in rep
      and "🔴 점검 구간 SID 203 밖 실자금 신규 매수 1" in rep and "SID 203 열린" not in rep, rep)
sql(DB, "UPDATE live_trading_session SET session_type='PAPER' WHERE id=204;")
rc, rep, last, sent, raw = run("other_paper", **RUNNING)
check("같은 기록이 모의 세션(PAPER)이면 — 정상", rc == 0 and GREEN in rep, rep)

sid_reset("EMERGENCY_STOPPED")
rc, rep, last, sent, raw = run("sid_emergency")
check("SID 203 EMERGENCY_STOPPED — 🔴 상태", rc == 1 and "🔴 SID 203 상태 EMERGENCY_STOPPED(정상: CREATED)" in rep, rep)
rc, rep, last, sent, raw = run("sid_emergency_running", **RUNNING)
check("SID 203 EMERGENCY_STOPPED(시작 뒤 설정) — 🔴 상태", rc == 1 and "🔴 SID 203 상태 EMERGENCY_STOPPED(정상: RUNNING)" in rep, rep)

# 리뷰 2026-10-31 보완 ① 시작 전·후 정상 상태 구분 — 기본(설치 시) CREATED 만, F2 시작 확인 뒤 RUNNING 만
sid_reset("RUNNING")
rc, rep, last, sent, raw = run("sid_running_default")
check("설치 설정(CREATED 만)인데 SID 203 RUNNING(잘못 시작) — 🔴 상태", rc == 1 and "🔴 SID 203 상태 RUNNING(정상: CREATED)" in rep, rep)
sid_reset("CREATED")
rc, rep, last, sent, raw = run("sid_created_after_switch", **RUNNING)
check("시작 뒤 설정(RUNNING 만)인데 SID 203 CREATED(시작 안 됨) — 🔴 상태", rc == 1 and "🔴 SID 203 상태 CREATED(정상: RUNNING)" in rep, rep)

# ② 거래소 보고값 — 누락·반영 대기 ⚪, 대기 없이 보고값≠반영값 🔴 / ③ 시간 조건 없음(발견 즉시)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60, rep_fee=None)
rc, rep, last, sent, raw = run("settle_no_rep_fee", **RUNNING)
check("보고 수수료 없음(반영값은 있음) — ⚪ 확인 불가(종전 판은 🟢)", rc == 1 and GRAY in rep and "⚪ SID 203 체결 주문 정산 확인 불가 1건" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60, rep_funds=None)
rc, rep, last, sent, raw = run("settle_no_rep_funds", **RUNNING)
check("보고 체결금액 없음 — ⚪ 확인 불가", rc == 1 and GRAY in rep and "⚪ SID 203 체결 주문 정산 확인 불가 1건" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 1, applied=False)
rc, rep, last, sent, raw = run("settle_unapplied_fresh", **RUNNING)
check("체결 1분 뒤 반영값 없음 — 15분 기다리지 않고 ⚪", rc == 1 and GRAY in rep and "⚪ SID 203 체결 주문 정산 확인 불가 1건" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 2 * 24 * 60, pending=True)
rc, rep, last, sent, raw = run("settle_old_order_pending", **RUNNING)
check("이틀 전 주문에 방금 수수료 정정(반영 대기) — 경과 시간과 무관하게 ⚪(🔴 '15분 넘게' 아님)",
      rc == 1 and GRAY in rep and "⚪ SID 203 체결 주문 정산 확인 불가 1건" in rep and "🔴" not in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60, rep_fee="9.9")
rc, rep, last, sent, raw = run("settle_fee_mismatch", **RUNNING)
check("대기 없이 보고 수수료 9.9 ≠ 반영 9.8 — 🔴", rc == 1 and "🔴 SID 203 체결 주문 보고값≠반영값(반영 대기 아님, 주문 1) 1" in rep and GRAY not in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60, rep_funds="19650")
rc, rep, last, sent, raw = run("settle_funds_mismatch", **RUNNING)
check("대기 없이 보고 체결금액 ≠ 반영 — 🔴", rc == 1 and "🔴 SID 203 체결 주문 보고값≠반영값(반영 대기 아님, 주문 1) 1" in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60, rep_fee="9.9", pending=True)
rc, rep, last, sent, raw = run("settle_mismatch_pending", **RUNNING)
check("보고값 ≠ 반영값이지만 반영 대기 — 🔴 아닌 ⚪", rc == 1 and GRAY in rep and "보고값≠반영값(반영 대기 아님" not in rep, rep)
sid_reset(); pos(1, "OPEN"); order(1, 1, "BUY", "FILLED", 0.0016, 60); order(2, 1, "SELL", "CANCELLED", 0, 30)
rc, rep, last, sent, raw = run("settle_unfilled_null", **RUNNING)
check("체결 없는 취소 주문(보고값·반영값 모두 NULL)은 정산 대상 아님 — 정상", rc == 0 and GREEN in rep and "정산 확인 불가 0 · 보고값≠반영값 0" in rep, rep)
sid_reset("STOPPED")
rc, rep, last, sent, raw = run("sid_stopped_end", SID_OK_STATUS="STOPPED")
check("56일 종료 뒤 STOPPED 를 정상으로 지정하면 — 정상", rc == 0 and GREEN in rep and "SID 203: STOPPED" in rep, rep)
sql(DB, "DELETE FROM live_trading_session WHERE id=203;")
rc, rep, last, sent, raw = run("sid_missing")
check("SID 203 세션 없음 — 🔴 상태 none", rc == 1 and "🔴 SID 203 상태 none" in rep, rep)
sql(DB, SID203_ROW)

# 3. 미분류 ERROR
rc, rep, last, sent, raw = run("unknown_error", LOGS_FILE=logs_bad)
check("미분류 ERROR — 🔴 2건(다른 로거 1 + 알려진 로거의 다른 문구 1), 줄을 결과에 남긴다",
      rc == 1 and "🔴 미분류 ERROR 2건" in rep and "주문 상태 조회 실패" in rep and "공유 풀 예약 실패" in rep, rep)

# 3-1. 리뷰 2026-10-31: 원인 미확인 🟡 두 가지 — 운영 보고 원문(10-31 10:00) 그대로만, 경계 밖은 미분류 🔴
RAW_NEWS = [
    "backend-1  | 15:01:16.089 [scheduler-6] ERROR c.c.api.news.source.RssNewsSource - [RssNewsSource] 수집 실패 — sourceId=tokenpost_rss: null",
    "backend-1  | 15:01:21.098 [scheduler-6] ERROR c.c.a.n.s.CoinGeckoTrendingSource - [CoinGeckoTrendingSource] 수집 실패: null",
    "backend-1  | 15:01:26.106 [scheduler-6] ERROR c.c.api.news.source.RssNewsSource - [RssNewsSource] 수집 실패 — sourceId=coindesk_rss: null",
]
RAW_BLAST = "backend-1  | 19:12:16.443 [task-1] ERROR c.c.a.service.DataCollectionService - 캔들 수집 실패: KRW-BLAST — 캔들 데이터 수집 실패"


def logs_with(name, lines):
    p = os.path.join(work, name)
    shutil.copy(logs_ok, p)
    with open(p, "a", encoding="utf-8", newline="\n") as f:
        for l in lines:
            f.write(l + "\n")
    return p


rc, rep, last, sent, raw = run("raw_known", LOGS_FILE=logs_with("raw_known.txt", RAW_NEWS + [RAW_BLAST, RAW_BLAST + "\n\tat com.x.Y(Y.java:1)"]))
check("원문 뉴스 3·BLAST 2 — 🟡 원인 미확인(출처별 건수), 미분류 0·🔴 없음",
      rc == 0 and "미분류 0" in rep and "🔴" not in rep
      and "ERROR(원인 미확인 🟡): 보조 뉴스 수집 실패 3 · KRW-BLAST 보충 수집 실패 2" in rep
      and "🟡 보조 뉴스 수집 실패 — 원인 미확인 3건(출처별: CoinGeckoTrendingSource 1, coindesk_rss 1, tokenpost_rss 1)" in rep
      and "🟡 KRW-BLAST 보충 수집 실패 — 상세 원인 미확인 2건" in rep, raw + rep)
rc, rep, last, sent, raw = run("raw_none")
check("해당 줄 없음 — 원인 미확인 0 · 0, 🟡 줄 없음",
      "보조 뉴스 수집 실패 0 · KRW-BLAST 보충 수집 실패 0" in rep and "원인 미확인 0건" not in rep and "상세 원인 미확인 0건" not in rep, rep)
EDGE = [
    ("다른 RSS sourceId", RAW_NEWS[0].replace("tokenpost_rss", "cointelegraph_rss")),
    ("sourceId 접미(tokenpost_rss2)", RAW_NEWS[0].replace("tokenpost_rss", "tokenpost_rss2")),
    ("RSS 메시지 null 아님", RAW_NEWS[2].replace(": null", ": Read timed out")),
    ("RSS 메시지 null 뒤 덧붙임", RAW_NEWS[2] + " (retry)"),
    ("CoinGecko 메시지 null 아님", RAW_NEWS[1].replace(": null", ": 429 Too Many Requests")),
    ("다른 뉴스 출처(CryptoPanic)", "backend-1  | 15:01:30.000 [scheduler-6] ERROR c.c.a.n.s.CryptoPanicSource - [CryptoPanicSource] 수집 실패: null"),
    ("다른 뉴스 출처(UpbitNotice)", "backend-1  | 15:01:31.000 [scheduler-6] ERROR c.c.a.n.s.UpbitNoticeSource - [UpbitNoticeSource] 수집 실패: null"),
    ("다른 로거 이름이 같은 접미(XRssNewsSource)", RAW_NEWS[0].replace(".RssNewsSource - ", ".XRssNewsSource - ")),
    ("마켓 KRW-BLASTX", RAW_BLAST.replace("KRW-BLAST —", "KRW-BLASTX —")),
    ("다른 마켓 KRW-BTC", RAW_BLAST.replace("KRW-BLAST", "KRW-BTC")),
    ("BLAST 메시지 다름", RAW_BLAST.replace("캔들 데이터 수집 실패", "Read timed out")),
    ("BLAST 메시지 뒤 덧붙임", RAW_BLAST + ": 404"),
    ("다른 로거의 같은 BLAST 문구", RAW_BLAST.replace("c.c.a.service.DataCollectionService", "c.c.a.service.CandleBackfillService")),
]
for nm, line in EDGE:
    rc, rep, last, sent, raw = run("edge", LOGS_FILE=logs_with("edge.txt", [line]))
    check("경계 밖 — %s 는 미분류 🔴 1건(🟡 0)" % nm,
          rc == 1 and "🔴 미분류 ERROR 1건" in rep and "보조 뉴스 수집 실패 0 · KRW-BLAST 보충 수집 실패 0" in rep, line + "\n" + rep)

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
      rc == 1 and "🔴 backend 컨테이너 없음" in rep and "원래 66 중 RUNNING 63" in rep and "66세션 경보 조회 실패" in rep and len(sent) == 1, raw + rep)   # 2026-10-27 표시 형식 변경·BLAST 3 정지

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
cmd = 'mkdir -p "%s/.lock.d"; sleep 15 & echo $! > "%s/.lock.d/pid"; bash "%s"; echo "rc=$?"; kill %%1' % (dup_dir, dup_dir, SCRIPT.replace("\\", "/"))
r = subprocess.run([BASH, "-c", cmd], env=dict(BASE_ENV, OUT_DIR=dup_dir), capture_output=True)
o = r.stdout.decode("utf-8", "replace")
check("중복 실행 — 살아 있는 잠금이면 종료 3·건너뜀", "rc=3" in o and "이미 실행 중" in o, o)

# 11. 죽은 잠금은 넘겨받는다
stale = os.path.join(work, "stale").replace("\\", "/")
os.makedirs(stale + "/.lock.d")
open(stale + "/.lock.d/pid", "w").write("999999")
rc, rep, last, sent, raw = run("stale")
check("죽은 잠금 — 넘겨받아 실행", rc == 0 and "점검 항목 정상" in rep, raw)

# 12. 서버 시간대가 KST 가 아니면 🟡 로 알린다
rc, rep, last, sent, raw = run("tz", TZ_EXPECT="+1234")
check("시간대 불일치 — 🟡 경고(판정은 정상 유지)", rc == 0 and "🟡 서버 시간대" in rep, rep)

# ── 리뷰 보완(2026-10-22) ──────────────────────────────────────────────────────────

# 13. PID 기록 전의 잠금(빈 pid, 방금 생성)은 실행 중으로 본다 — 종전에는 지우고 함께 실행했다
fresh = os.path.join(work, "fresh").replace("\\", "/")
os.makedirs(fresh + "/.lock.d")
rc, rep_, last, sent, raw = run("fresh")
check("pid 기록 전 잠금 — 실행 중으로 보고 종료 3(지우지 않는다)", rc == 3 and os.path.isdir(fresh + "/.lock.d"), raw)

# 14. 동시 시작 — 로컬(flock 없음 → mkdir 대체 잠금) 5회: 매번 정확히 하나만 실행
SLOW_OK = "sleep 3; echo '🟢 경보 없음 · 실행 누락 없음'; exit 0"
bad = []
for k in range(5):
    d = os.path.join(work, "conc%d" % k).replace("\\", "/")
    env = dict(BASE_ENV, OUT_DIR=d, DRY_RUN="1", ALERTS_CMD=SLOW_OK)
    sp = SCRIPT.replace("\\", "/")
    cmd = 'bash "%s" >/dev/null 2>&1 & a=$!; bash "%s" >/dev/null 2>&1 & b=$!; wait $a; ra=$?; wait $b; rb=$?; echo "$ra $rb"' % (sp, sp)
    o = subprocess.run([BASH, "-c", cmd], env=env, capture_output=True, text=True).stdout.split()
    if sorted(o) != ["0", "3"]:
        bad.append("round %d: %s" % (k, o))
check("동시 시작(로컬 mkdir 잠금) 5회 — 매번 하나는 실행(0)·하나는 건너뜀(3)", not bad, "\n".join(bad))

# 15~18. 66세션 결과 누락 — 종료 코드만 믿지 않는다
rc, rep_, last, sent, raw = run("al_empty", ALERTS_CMD="exit 0")
check("66세션 종료 0·출력 없음 — ⚪ 판정 불가(정상 아님)",
      rc == 1 and "66세션 경보: 정상 결과 줄 없음" in rep_ and rep_.startswith("[정기 점검] ⚪"), rep_)
rc, rep_, last, sent, raw = run("al_garbage", ALERTS_CMD="echo 'Traceback something'; exit 0")
check("66세션 종료 0·알 수 없는 출력 — ⚪", rc == 1 and "정상 결과 줄 없음" in rep_, rep_)
rc, rep_, last, sent, raw = run("al_red_noline", ALERTS_CMD="echo '이상함'; exit 1")
check("66세션 종료 1·🔴 결과 줄 없음 — ⚪", rc == 1 and "66세션 경보: 결과 줄 없음(종료 1)" in rep_, rep_)
rc, rep_, last, sent, raw = run("al_red", ALERTS_CMD="echo '🔴 실행 누락 1구간'; exit 1")
check("66세션 종료 1·🔴 줄 — 🔴 경보(확정 아님)",
      rc == 1 and "🔴 66세션 경보(확정 아님 — 확인 필요): 실행 누락 1구간" in rep_ and rep_.startswith("[정기 점검] 🔴"), rep_)

# 19~20. 공백 있는 retry_after — JSON 으로 읽는다(실제로 자지 않는다: SLEEP_CMD=:)
TG.script = [(429, '{"ok": false, "error_code": 429, "description": "Too Many Requests: retry after 3", "parameters": {"retry_after": 3}}')]
rc, rep_, last, sent, raw = run("ra_spaced", SLEEP_CMD=":")
check("429 retry_after(공백 있음)=3 — 3초 기다린 뒤 두 번째 시도 성공", rc == 0 and len(sent) == 2 and "재시도 대기 3초" in last, last + raw)
TG.script = [(429, '{"ok": false, "error_code": 429, "parameters": {"retry_after": 20}}')] * 3
rc, rep_, last, sent, raw = run("ra_cap", SLEEP_CMD=":")
TG.script = []
check("429 retry_after 20초 두 번 — 합계가 상한 30초를 넘어 두 번째 대기 없이 중단(요청 2·종료 2)",
      rc == 2 and len(sent) == 2 and "다음 대기 20초가 대기 상한 30초를 넘어 중단" in last and "대기 20초" in last, last + raw)

# 21~22. 긴 한글 보고 — 문자 단위로 자른다
sql(DB, "INSERT INTO telegram_notification_log (type, session_label, message_text, success, sent_at) "
        "SELECT '한글유형' || g, NULL, '본문', FALSE, now() FROM generate_series(1, 300) g;")
rc, rep_, last, sent, raw = run("long_ko")
txt = TG.texts[0] if TG.texts else ""
check("긴 한글 보고(%d자) — UTF-8 깨짐 없이 3,500자 + 생략 표시로 전송" % len(rep_),
      len(rep_) > 3500 and len(sent) == 1 and not txt.startswith("!!INVALID") and txt.startswith(rep_[:3500])
      and "이하 생략" in txt and len(txt) <= 4096, txt[:80] + " … " + str(len(txt)))   # 생략 표시에 결과 파일 경로가 붙는다 — 텔레그램 상한 4096자 안
sql(DB, "DELETE FROM telegram_notification_log;")
rc, rep_, last, sent, raw = run("cut_ko", REPORT_LIMIT="41")
txt = TG.texts[0] if TG.texts else ""
check("자르는 자리가 한글 한가운데여도 문자 경계 — 앞 41자 그대로", not txt.startswith("!!INVALID") and txt.startswith(rep_[:41])
      and txt[41:42] == "\n", repr(txt[:60]) + " / " + repr(rep_[:45]))

# 서버 작업 트리 변경 — 🔴
open(os.path.join(fake_repo, "docker-compose.prod.yml"), "a").write("# 손댐\n")
rc, rep_, last, sent, raw = run("dirty")
subprocess.run(["git", "-C", fake_repo, "checkout", "-q", "--", "."], check=True)
check("서버 작업 트리에 추적 파일 변경 — 🔴", rc == 1 and "🔴 서버 작업 트리에 추적 파일 변경 1건" in rep_, rep_)

# 설치 SHA 기록 — 스크립트 옆 INSTALLED_SHA 를 결과에 적는다
inst = os.path.join(work, "inst").replace("\\", "/")
os.makedirs(inst)
shutil.copy(SCRIPT, inst + "/daily_check.sh")
open(inst + "/INSTALLED_SHA", "w").write("abc1234def567890\n")
env = dict(BASE_ENV, OUT_DIR=inst + "/out", DRY_RUN="1")
o = subprocess.run([BASH, inst + "/daily_check.sh"], env=env, capture_output=True).stdout.decode("utf-8", "replace")
check("설치 SHA — 결과에 '점검 스크립트 abc1234def56'", "점검 스크립트 abc1234def56" in o, o[:300])

# 23. 앱 로그 원문 보존
d = os.path.join(work, "normal")
gz = [x for x in os.listdir(d) if x.endswith(".log.gz")]
body = gzip.open(os.path.join(d, gz[0]), "rt", encoding="utf-8").read() if gz else ""
check("앱 로그 원문을 결과 파일 옆에 압축 보존(.log.gz)", bool(gz) and "Connection reset" in body and "공유 풀 한도" in body, str(gz))

# 24. 리눅스(서버와 같은 ubuntu:22.04)에서 flock 으로 동시 시작 — 도커로 돌린다
CANNED = ("flyway_version|94\nreal_open_positions|0\nreal_unresolved_orders|0\ncleanup_pending|0\nquarantine_targets|0\n"
          "s66_running|63\ns66_total|66\ns66_not_running|576:STOPPED 577:STOPPED 578:STOPPED\ns66_coins|KRW-BLAST\nreal_buys_window|0\nquarantine_release_rows|0\ntg_queue_rejected|0\ntg_ok_window|0\n"
          "sid_status|CREATED\nsid_available_krw|20200\nsid_open_positions|0\nsid_active_orders|0\nsid_stale_orders|0\nsid_unknown_orders|0\n"
          "sid_dup_buy_positions|0\nsid_filled_buys_window|0\nsid_orders_window|0 0\nsid_settle_unknown|0\nsid_settle_mismatch|0\nsid_settle_ids|-\nsettlement_unresolved|0\n"
          "tg_last_ok|none\nEND|ok\n")
with open(os.path.join(work, "canned.txt"), "w", newline="\n") as f:
    f.write(CANNED)
with open(os.path.join(work, "fakepsql"), "w", newline="\n") as f:
    f.write("#!/bin/bash\ncat >/dev/null\ncat /w/canned.txt\n")
with open(os.path.join(work, "linux_flock.sh"), "w", newline="\n", encoding="utf-8") as f:
    f.write("""set -u
command -v flock >/dev/null || { echo NOFLOCK; exit 0; }
ok=0
for k in 1 2 3 4 5; do
  export OUT_DIR=/tmp/out$k
  bash /repo/scripts/ops/daily_check.sh >/dev/null 2>&1 & a=$!
  bash /repo/scripts/ops/daily_check.sh >/dev/null 2>&1 & b=$!
  wait $a; ra=$?; wait $b; rb=$?
  echo "round $k: $ra $rb"
  # 컨테이너에는 git 이 없어 운영 SHA 가 ⚪(종료 1) — 잠금 판정은 "하나는 3, 다른 하나는 실행(0 또는 1)" 으로 본다
  if { [ $ra = 3 ] && [ $rb != 3 ]; } || { [ $rb = 3 ] && [ $ra != 3 ]; }; then ok=$((ok+1)); fi
  [ -e /tmp/out$k/.lock.flock ] && [ ! -e /tmp/out$k/.lock.d ] && echo "  flock 사용"
done
echo "OK=$ok"
""")
have = subprocess.run(["docker", "image", "inspect", "ubuntu:22.04"], capture_output=True).returncode == 0 or \
       subprocess.run(["docker", "pull", "-q", "ubuntu:22.04"], capture_output=True).returncode == 0
if not have:
    check("리눅스 flock 동시 시작 — ubuntu:22.04 이미지를 받지 못함(판정 불가)", False, "docker pull 실패")
else:
    r = subprocess.run(["docker", "run", "--rm", "-v", REPO.replace("\\", "/") + ":/repo:ro", "-v", work.replace("\\", "/") + ":/w",
                        "-e", "REPO_DIR=/repo", "-e", "PSQL_CMD=bash /w/fakepsql", "-e", "LOGS_FILE=/dev/null",
                        "-e", "APP_STATE=running|0|2026-10-01T00:00:00Z|sha256:x", "-e", "DRY_RUN=1", "-e", "TZ_EXPECT=",
                        "-e", "ALERTS_CMD=" + SLOW_OK, "ubuntu:22.04", "bash", "/w/linux_flock.sh"], capture_output=True)
    out = r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    check("리눅스(ubuntu:22.04) flock 동시 시작 5회 — 매번 하나만 실행·flock 잠금 사용", "OK=5" in out and out.count("flock 사용") == 5, out)

    # 2026-10-27: 서버 대화형 셸(C.UTF-8)에서 공유 풀 기동 줄이 미분류 🔴 로 셌다 — 스크립트가 로케일을 C 로 고정한다. 실제 줄 그대로.
    with open(os.path.join(work, "pool_line.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("backend-1  | 01:30:12.574 [main] ERROR c.c.api.service.CapitalPoolLimits - 🔴 공유 풀 한도가 사전 등록과 맞지 않는다 — "
                "실자금 진입·배정을 모두 거절한다: 한도 설정 누락 (단계= 총상한=null 포지션당=null 동시보유=null)\n")
    for loc in ("C.UTF-8", "C"):
        r = subprocess.run(["docker", "run", "--rm", "-v", REPO.replace("\\", "/") + ":/repo:ro", "-v", work.replace("\\", "/") + ":/w",
                            "-e", "LC_ALL=" + loc, "-e", "LANG=" + loc, "-e", "REPO_DIR=/repo", "-e", "PSQL_CMD=bash /w/fakepsql",
                            "-e", "LOGS_FILE=/w/pool_line.txt", "-e", "OUT_DIR=/tmp/pool", "-e", "APP_STATE=running|0|2026-10-01T00:00:00Z|sha256:x",
                            "-e", "DRY_RUN=1", "-e", "TZ_EXPECT=", "-e", "ALERTS_CMD=echo '🟢 경보 없음 · 실행 누락 없음'; exit 0",
                            "ubuntu:22.04", "bash", "/repo/scripts/ops/daily_check.sh"], capture_output=True)
        out = r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
        check("리눅스 %s 로 실행해도 공유 풀 기동 줄은 '공유 풀 미등록 1 · … 미분류 0'" % loc,
              "공유 풀 미등록 1 ·" in out and "미분류 0" in out and "미분류 ERROR" not in out, out)

    # 2026-10-31: 원인 미확인 🟡 원문과 경계(KRW-BLASTX)를 서버와 같은 리눅스·대화형 셸 로케일로
    with open(os.path.join(work, "raw_lines.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(RAW_NEWS + [RAW_BLAST, RAW_BLAST.replace("KRW-BLAST —", "KRW-BLASTX —")]) + "\n")
    r = subprocess.run(["docker", "run", "--rm", "-v", REPO.replace("\\", "/") + ":/repo:ro", "-v", work.replace("\\", "/") + ":/w",
                        "-e", "LC_ALL=C.UTF-8", "-e", "LANG=C.UTF-8", "-e", "REPO_DIR=/repo", "-e", "PSQL_CMD=bash /w/fakepsql",
                        "-e", "LOGS_FILE=/w/raw_lines.txt", "-e", "OUT_DIR=/tmp/raw", "-e", "APP_STATE=running|0|2026-10-01T00:00:00Z|sha256:x",
                        "-e", "DRY_RUN=1", "-e", "TZ_EXPECT=", "-e", "ALERTS_CMD=echo '🟢 경보 없음 · 실행 누락 없음'; exit 0",
                        "ubuntu:22.04", "bash", "/repo/scripts/ops/daily_check.sh"], capture_output=True)
    out = r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    check("리눅스 C.UTF-8 — 원문 뉴스 3·BLAST 1 은 🟡, KRW-BLASTX 1 은 미분류 🔴",
          "보조 뉴스 수집 실패 3 · KRW-BLAST 보충 수집 실패 1" in out and "미분류 1" in out and "KRW-BLASTX" in out
          and "출처별: CoinGeckoTrendingSource 1, coindesk_rss 1, tokenpost_rss 1" in out, out)

sql("postgres", "DROP DATABASE IF EXISTS %s;" % DB)
srv.shutdown()
shutil.rmtree(work, ignore_errors=True)
passed = sum(1 for _, ok in results if ok)
print("\ndaily_check 시험 %d/%d 통과" % (passed, len(results)))
sys.exit(0 if passed == len(results) else 1)
