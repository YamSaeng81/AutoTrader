"""maint_verify.py 시험 (2026-10-23) — 로컬에서만. 실행: python scripts/ops/test_maint_verify.py [<web-api bootJar>]"""
import glob
import io
import json
import os
import shutil
import sys
import tempfile
import zipfile

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import maint_verify as mv  # noqa: E402

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

shutil.rmtree(work, ignore_errors=True)
print("\nmaint_verify 시험 %d/%d 통과" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
