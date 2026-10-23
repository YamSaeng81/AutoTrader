"""계획 정비 확인 도구 (2026-10-23, 읽기 전용) — docs/MAINTENANCE_NEXT_895a471.md 에서 쓴다.

  python3 maint_verify.py jar <app.jar> <클래스 경로>...          app.jar 바깥(BOOT-INF/classes)과 중첩 모듈 jar(BOOT-INF/lib/*.jar) 안까지 찾는다
  python3 maint_verify.py freshness <체크아웃 UTC ISO> <freshness_log.jsonl>
                                                                   체크아웃 뒤 회차만 골라 보인다 — 실행 중이면 판정하지 않는다
  python3 maint_verify.py candle <기동 뒤 앱 로그> [<기동 전 앱 로그>]
                                                                   기동 뒤 '판정 불가' 로 들어간 대상마다 '판정 재개' 가 있었는지

🔴 쓰기·외부 호출 없음. 판정은 사람이 한다 — 이 도구는 근거를 나란히 놓을 뿐이다.
"""
import io
import json
import re
import subprocess
import sys
import zipfile


def cmd_jar(path, classes):
    """리뷰 2026-10-23: 다른 모듈의 클래스는 app.jar 의 BOOT-INF/lib/<모듈>.jar 안에 중첩된다 — 바깥 목록만 보면 정상 이미지도 0 이다."""
    outer = zipfile.ZipFile(path)
    found = {c: [] for c in classes}
    for name in outer.namelist():
        for c in classes:
            if name.endswith(c):
                found[c].append(name)
    nested = [n for n in outer.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
    for n in nested:
        try:
            inner = zipfile.ZipFile(io.BytesIO(outer.read(n)))
        except zipfile.BadZipFile:
            continue
        for name in inner.namelist():
            for c in classes:
                if name.endswith(c):
                    found[c].append(n + "!/" + name)
    ok = True
    for c, where in found.items():
        print("%s %s" % ("있음" if where else "없음", c))
        for w in where:
            print("     " + w)
        ok = ok and bool(where)
    print("중첩 jar %d개 확인" % len(nested))
    return 0 if ok else 1


def _running_freshness():
    try:
        out = subprocess.run(["pgrep", "-af", "start_66.py freshness"], capture_output=True, text=True).stdout
    except FileNotFoundError:
        return None
    return [l for l in out.splitlines() if "pgrep" not in l and "maint_verify" not in l]


def cmd_freshness(checkout_utc, log_path):
    """리뷰 2026-10-23: cron 기록은 '시작' 근거일 뿐이다. 재조회가 붙으면 :20 에도 돈다 — 프로세스 종료와 그 회차의 JSON 결과를 맞춘다."""
    running = _running_freshness()
    if running:
        print("🔴 freshness 가 아직 실행 중이다 — 끝난 뒤 다시 본다:")
        for l in running:
            print("   " + l)
        return 2
    if running is None:
        print("⚪ pgrep 을 쓸 수 없어 실행 여부를 확인하지 못했다")
    rows = []
    for line in open(log_path, encoding="utf-8"):
        line = line.strip()
        if line:
            try:
                rows.append(json.loads(line))
            except ValueError:
                print("⚪ 읽지 못한 줄이 있다(잘린 기록?): " + line[:80])
    ck = checkout_utc.replace("Z", "")[:19]
    before = [r for r in rows if r.get("at", "")[:19] < ck]
    after = [r for r in rows if r.get("at", "")[:19] >= ck]
    if before:
        b = before[-1]
        print("체크아웃 전 마지막 회차(옛 코드): %s green %s" % (b.get("at"), b.get("green")))
    if not after:
        print("⚪ 체크아웃(%sZ) 뒤 회차가 아직 없다 — 마지막 줄은 이전 회차다" % ck)
        return 2
    a = after[0]
    print("✅ 체크아웃 뒤 첫 회차(새 코드): at %s · green %s · unknown %s · red %s · 재확인 %s"
          % (a.get("at"), a.get("green"), a.get("unknown"), a.get("red"), a.get("rechecked")))
    print("   체크아웃 뒤 회차 수 %d" % len(after))
    return 0


_STALE = re.compile(r"\[캔들 최신성\] (.+?) 판정 불가")
_RESUME = re.compile(r"\[캔들 최신성\] (.+?) 판정 재개")


def _keys(path, pattern):
    keys = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = pattern.search(line)
        if m and m.group(1) not in keys:
            keys.append(m.group(1))
    return keys


def cmd_candle(after_log, before_log=None):
    """리뷰 2026-10-23: 특정 점검 공백 로그가 반드시 나와야 한다고 고정하지 않는다 — 재확인이 필요해진 대상마다 실제로 판정이 재개됐는지로 본다."""
    stale = _keys(after_log, _STALE)
    resumed = set(_keys(after_log, _RESUME))
    pre = set(_keys(before_log, _STALE)) if before_log else set()
    left = [k for k in stale if k not in resumed]
    print("기동 뒤 판정 불가로 들어간 대상 %d · 그중 판정 재개 %d · 아직 재개 없음 %d" % (len(stale), len(stale) - len(left), len(left)))
    for k in left:
        print("   재개 없음: %s%s" % (k, "  (기동 전에도 판정 불가였다)" if k in pre else ""))
    return 0 if all(k in pre for k in left) else 1


def main(argv):
    if len(argv) >= 3 and argv[1] == "jar":
        return cmd_jar(argv[2], argv[3:])
    if len(argv) == 4 and argv[1] == "freshness":
        return cmd_freshness(argv[2], argv[3])
    if len(argv) in (3, 4) and argv[1] == "candle":
        return cmd_candle(argv[2], argv[3] if len(argv) == 4 else None)
    print(__doc__)
    return 64


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv))
