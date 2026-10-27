"""maint_verify.py 시험 (2026-10-23) — 로컬에서만. 실행: python scripts/ops/test_maint_verify.py [<web-api bootJar>]"""
import glob
import io
import json
import os
import shutil
import sys
import tempfile
import types
import zipfile
import contextlib

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import maint_verify as mv  # noqa: E402
REAL_RUNNING = mv._running_freshness

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)))


work = tempfile.mkdtemp()

# ── jar: 중첩 모듈 안의 클래스 ──
inner = io.BytesIO()
with zipfile.ZipFile(inner, "w") as z:
    z.writestr("com/x/upbit/UpbitRequestLimiter.class", b"x")
outer = os.path.join(work, "app.jar")
with zipfile.ZipFile(outer, "w") as z:
    z.writestr("BOOT-INF/classes/com/x/DynamicTradingService.class", b"runAfterCommit")
    z.writestr("BOOT-INF/lib/exchange-adapter-0.1.0.jar", inner.getvalue())
check("jar — 중첩 모듈 jar 안의 클래스를 찾는다(바깥 목록에는 없다)",
      mv.cmd_jar(outer, ["UpbitRequestLimiter.class", "DynamicTradingService.class"]) == 0)
check("jar — 없는 클래스면 실패", mv.cmd_jar(outer, ["NoSuch.class"]) == 1)
real = (sys.argv[1:] or [p for p in glob.glob(os.path.join(HERE, "..", "..", "web-api", "build", "libs", "*.jar")) if "plain" not in p] or [None])[0]
if real:
    check("jar — 실제 bootJar(%s)에서 제한기 클래스를 찾는다" % os.path.basename(real),
          mv.cmd_jar(real, ["upbit/UpbitRequestLimiter.class"]) == 0)

# ── freshness: 체크아웃 전·뒤 회차 구분, 이전 회차만 있으면 판정하지 않음 ──
log = os.path.join(work, "freshness_log.jsonl")
rows = [{"at": "2026-10-24T03:19:01.5+00:00", "green": 22, "unknown": 0, "red": 0, "rechecked": []}]
open(log, "w", encoding="utf-8").write("\n".join(json.dumps(r) for r in rows) + "\n")
mv._running_freshness = lambda: []
check("freshness — 체크아웃 뒤 회차가 없으면 판정하지 않는다(마지막 줄은 이전 회차)", mv.cmd_freshness("2026-10-24T04:05:00Z", log) == 2)
rows.append({"at": "2026-10-24T04:19:01.2+00:00", "green": 22, "unknown": 0, "red": 0, "rechecked": []})
open(log, "w", encoding="utf-8").write("\n".join(json.dumps(r) for r in rows) + "\n")
check("freshness — 체크아웃 뒤 첫 회차를 고른다", mv.cmd_freshness("2026-10-24T04:05:00Z", log) == 0)
mv._running_freshness = lambda: ["12345 python3 scripts/prospective/start_66.py freshness --save"]
check("freshness — 아직 실행 중이면 판정하지 않는다", mv.cmd_freshness("2026-10-24T04:05:00Z", log) == 2)

# ── freshness: pgrep 종료 코드 — 1 만 '없음', 오류·도구 부재는 ⚪ 중단 (리뷰 2026-10-23) ──
mv._running_freshness = REAL_RUNNING
real_subprocess = mv.subprocess


def fake_pgrep(rc=None, out="", err="", exc=None):
    def run(*a, **k):
        if exc:
            raise exc
        return types.SimpleNamespace(returncode=rc, stdout=out, stderr=err)
    mv.subprocess = types.SimpleNamespace(run=run)


fake_pgrep(rc=1)
check("freshness — pgrep 종료 코드 1(없음)이면 판정한다", mv.cmd_freshness("2026-10-24T04:05:00Z", log) == 0)
fake_pgrep(rc=0, out="12345 python3 scripts/prospective/start_66.py freshness --save\n")
check("freshness — pgrep 종료 코드 0(있음)이면 판정하지 않는다", mv.cmd_freshness("2026-10-24T04:05:00Z", log) == 2)
fake_pgrep(rc=2, err="pgrep: invalid option")
check("freshness — pgrep 종료 코드 2(실행 오류)는 '없음' 이 아니다 → 판정하지 않는다", mv.cmd_freshness("2026-10-24T04:05:00Z", log) == 2)
fake_pgrep(rc=3, err="pgrep: fatal")
check("freshness — pgrep 종료 코드 3 도 판정하지 않는다", mv.cmd_freshness("2026-10-24T04:05:00Z", log) == 2)
fake_pgrep(exc=FileNotFoundError("pgrep"))
check("freshness — pgrep 이 없으면 판정하지 않는다", mv.cmd_freshness("2026-10-24T04:05:00Z", log) == 2)
mv.subprocess = real_subprocess
mv._running_freshness = lambda: []

# ── freshness: at 은 관측 시각 — 재확인이 붙어 :20 대로 넘어간 회차도 체크아웃 뒤 첫 회차로 고른다 ──
rows.append({"at": "2026-10-24T05:20:38.0+00:00", "green": 22, "unknown": 0, "red": 0, "rechecked": ["KRW-IOTA"]})
open(log, "w", encoding="utf-8").write("\n".join(json.dumps(r) for r in rows) + "\n")
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    rc = mv.cmd_freshness("2026-10-24T05:05:00Z", log)
check("freshness — 재확인으로 at 이 :20 인 회차도 고르고, at 을 관측 시각으로 표시한다",
      rc == 0 and "05:20:38" in buf.getvalue() and "관측 시각" in buf.getvalue() and "cron" in buf.getvalue(), buf.getvalue())

# ── candle: 재개 여부 ──
after = os.path.join(work, "after.log")
before = os.path.join(work, "before.log")
open(after, "w", encoding="utf-8").write(
    "ERROR [캔들 최신성] KRW-XRP:M15 판정 불가 (DYNAMIC) — 결측 19칸\n"
    "ERROR [캔들 최신성] BTC 급락 관문 M15 판정 불가 (DYNAMIC) — BTC\n"
    "ERROR [캔들 최신성] KRW-CAP:M15 판정 불가 (DYNAMIC) — 결측 20칸\n"
    "WARN  [캔들 최신성] KRW-XRP:M15 판정 재개 (DYNAMIC): 종전 …\n"
    "WARN  [캔들 최신성] BTC 급락 관문 M15 판정 재개 (DYNAMIC): 종전 …\n")
open(before, "w", encoding="utf-8").write("ERROR [캔들 최신성] KRW-CAP:M15 판정 불가 (DYNAMIC)\n")
check("candle — 재개 안 된 대상이 기동 전에도 판정 불가였으면 통과", mv.cmd_candle(after, before) == 0)
check("candle — 기동 전 기록 없이 재개 안 된 대상이 있으면 실패", mv.cmd_candle(after) == 1)
open(before, "w", encoding="utf-8").write("ERROR [캔들 최신성] KRW-CAP:M15 판정 불가 (DYNAMIC)\n"
                                           "WARN  [캔들 최신성] KRW-CAP:M15 판정 재개 (DYNAMIC): 종전 …\n")
check("candle — 기동 전에 한때 판정 불가였다가 재개된 대상은 예외가 아니다(직전 상태로 판단)", mv.cmd_candle(after, before) == 1)

# ── candle: 기동 뒤 로그도 마지막 상태로 (리뷰 2026-10-23) ──
open(after, "w", encoding="utf-8").write(
    "ERROR [캔들 최신성] KRW-XRP:M15 판정 불가 (DYNAMIC) — 결측 19칸\n"
    "WARN  [캔들 최신성] KRW-XRP:M15 판정 재개 (DYNAMIC): 종전 …\n"
    "ERROR [캔들 최신성] KRW-XRP:M15 판정 불가 (DYNAMIC) — 결측 3칸\n")
open(before, "w", encoding="utf-8").write("")
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    rc = mv.cmd_candle(after, before)
check("candle — 불가 → 재개 → 다시 불가면 실패(아직 판정 불가 1)", rc == 1 and "아직 판정 불가 1" in buf.getvalue(), buf.getvalue())
open(after, "a", encoding="utf-8").write("WARN  [캔들 최신성] KRW-XRP:M15 판정 재개 (DYNAMIC): 종전 …\n")
check("candle — 그 뒤 다시 재개되면 통과", mv.cmd_candle(after, before) == 0)
open(after, "w", encoding="utf-8").write(
    "ERROR [캔들 최신성] KRW-CAP:M15 판정 불가 (DYNAMIC)\n"
    "WARN  [캔들 최신성] KRW-CAP:M15 판정 재개 (DYNAMIC): 종전 …\n"
    "ERROR [캔들 최신성] KRW-CAP:M15 판정 불가 (DYNAMIC)\n")
open(before, "w", encoding="utf-8").write("ERROR [캔들 최신성] KRW-CAP:M15 판정 불가 (DYNAMIC)\n")
check("candle — 기동 직전 판정 불가였어도 기동 뒤 재개됐다가 다시 불가면 예외가 아니다", mv.cmd_candle(after, before) == 1)

# ── candle: 실제 로그 형식 — BTC 관문 재개 줄의 '종전' 사유에 '판정 불가' 가 들어 있다 (정비 #12 2026-10-27, 리뷰) ──
P = "backend-1  | %s [scheduler-%d] %s c.c.api.service.CandleFreshnessGuard - "
BTC_STALE = (P % ("01:31:16.316", 4, "ERROR") + "🔴 [캔들 최신성] BTC 급락 관문 H1 판정 불가 (DYNAMIC) — 이 관문을 요구하는 신규 진입을 막는다: "
             "BTC 캔들 판정 불가(결측 4칸(허용 2) — 완료 봉 196개 / 기대 200개 (2026-10-19T02:00:00Z ~ 2026-10-27T00:00:00Z)) — 급락 여부를 모른다\n")
BTC_RESUME = (P % ("01:32:20.910", 6, "WARN ") + "[캔들 최신성] BTC 급락 관문 H1 판정 재개 (DYNAMIC): 종전 2026-10-27T01:31:16.315929903Z "
              "BTC 캔들 판정 불가(결측 4칸(허용 2) — 완료 봉 196개 / 기대 200개 (2026-10-19T02:00:00Z ~ 2026-10-27T00:00:00Z)) — 급락 여부를 모른다\n")
COIN_STALE = (P % ("01:30:40.100", 2, "ERROR") + "🔴 [캔들 최신성] KRW-SOON:M15 판정 불가 (DYNAMIC) — 이 회차의 진입·캔들 기반 청산을 하지 않는다: "
              "결측 5칸(허용 2) — 완료 봉 470개 / 기대 475개\n")
open(before, "w", encoding="utf-8").write(BTC_STALE + BTC_RESUME + COIN_STALE)
open(after, "w", encoding="utf-8").write(BTC_STALE + COIN_STALE + BTC_RESUME)
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    rc = mv.cmd_candle(after, before)
out = buf.getvalue()
check("candle — 실제 형식: BTC 관문 재개 줄을 재개로 읽는다(종전 사유의 '판정 불가' 에 속지 않는다)",
      rc == 0 and "판정 불가로 들어간 대상 2 · 로그 끝 기준 판정 재개 1 · 아직 판정 불가 1" in out
      and "판정 재개 (DYNAMIC)" not in out and "BTC 급락 관문 H1" not in out, out)
check("candle — 실제 형식: 재개 줄 키는 'BTC 급락 관문 H1' 하나다",
      mv._keys(after, False) == ["BTC 급락 관문 H1"] and mv._keys(after, True) == ["BTC 급락 관문 H1", "KRW-SOON:M15"],
      (mv._keys(after, True), mv._keys(after, False)))
open(after, "w", encoding="utf-8").write(BTC_STALE + BTC_RESUME + BTC_STALE.replace("01:31:16.316", "01:45:16.002"))
open(before, "w", encoding="utf-8").write("")
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    rc = mv.cmd_candle(after, before)
check("candle — 실제 형식: 판정 불가 → 재개 → 다시 판정 불가면 실패(재개 뒤 다시 판정 불가)",
      rc == 1 and "판정 불가: BTC 급락 관문 H1  (재개 뒤 다시 판정 불가)" in buf.getvalue(), buf.getvalue())
open(after, "a", encoding="utf-8").write(BTC_RESUME.replace("01:32:20.910", "01:46:20.500"))
check("candle — 실제 형식: 그 뒤 다시 재개되면 통과", mv.cmd_candle(after, before) == 0)
dbg = os.path.join(work, "dbg.log")
open(dbg, "w", encoding="utf-8").write("DEBUG [캔들 최신성] KRW-SOON:M15 판정 불가 지속: 결측 5칸\n")
check("candle — '판정 불가 지속' 디버그 줄은 상태 줄로 세지 않는다(엔진 괄호 없음)", list(mv._events(dbg)) == [])

shutil.rmtree(work, ignore_errors=True)
print("\nmaint_verify 시험 %d/%d 통과" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
