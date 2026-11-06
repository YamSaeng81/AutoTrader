"""계획 정비 확인 도구 (2026-10-23, 읽기 전용) — 계획 정비 절차서(docs/MAINTENANCE_NEXT_*.md)에서 쓴다.

  python3 maint_verify.py jar <app.jar> <클래스 경로>...          app.jar 바깥(BOOT-INF/classes)과 중첩 모듈 jar(BOOT-INF/lib/*.jar) 안까지 찾는다
  python3 maint_verify.py freshness <체크아웃 UTC ISO> <freshness_log.jsonl>
                                                                   체크아웃 뒤 회차만 골라 보인다 — 실행 중이거나 실행 여부를 모르면 판정하지 않는다
                                                                   (JSON at = 재확인이 없으면 1차 조회 시작 직전, 있으면 재확인 끝난 뒤 · 시작 시각은 cron 기록)
  python3 maint_verify.py candle <기동 뒤 앱 로그> [<기동 전 앱 로그>]
                                                                   기동 뒤 '판정 불가' 로 들어간 대상마다 로그 끝에서 판정 재개 상태인지

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
    """실행 중인 freshness 프로세스 줄 목록. 확인하지 못했으면 None.

    리뷰 2026-10-23: pgrep 종료 코드 0 = 있음, **1 만 '없음'** 이다. 2·3(실행 오류) 이나 도구 부재를 '없음' 으로 세면
    실행 중인 회차를 끝난 것으로 판정할 수 있다."""
    try:
        p = subprocess.run(["pgrep", "-af", "start_66.py freshness"], capture_output=True, text=True)
    except (FileNotFoundError, OSError) as e:
        print("⚪ pgrep 을 실행하지 못했다: %s" % e)
        return None
    if p.returncode == 1:
        return []
    if p.returncode != 0:
        print("⚪ pgrep 오류(종료 코드 %d): %s" % (p.returncode, (p.stderr or "").strip()[:200]))
        return None
    return [l for l in p.stdout.splitlines() if "pgrep" not in l and "maint_verify" not in l]


def cmd_freshness(checkout_utc, log_path):
    """리뷰 2026-10-23: cron 기록은 '시작' 근거일 뿐이다. 재조회가 붙으면 :20 에도 돈다 — 프로세스 종료와 그 회차의 JSON 결과를 맞춘다.
    JSON 의 `at` 은 그 회차의 **관측 시각**이다 — 재조회가 없으면 1차 조회 **시작 직전**(DB 조회 뒤), 있으면 **재조회가 끝난 뒤**
    (`start_66.py` cmd_freshness 의 `now`, 리뷰 2026-10-27 정정 — 종전 설명 '판정 직전' 은 재조회 없는 회차에 맞지 않았다).
    cron 시작 시각이 아니다. 시작 시각은 cron 기록으로 본다."""
    running = _running_freshness()
    if running is None:
        print("⚪ 실행 여부를 확인하지 못해 판정하지 않는다 — pgrep 을 확인하고 다시 실행한다")
        return 2
    if running:
        print("🔴 freshness 가 아직 실행 중이다 — 끝난 뒤 다시 본다:")
        for l in running:
            print("   " + l)
        return 2
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
        print("체크아웃 전 마지막 회차(옛 코드): 관측 %s green %s" % (b.get("at"), b.get("green")))
    if not after:
        print("⚪ 체크아웃(%sZ) 뒤 회차가 아직 없다 — 마지막 줄은 이전 회차다" % ck)
        return 2
    a = after[0]
    print("✅ 체크아웃 뒤 첫 회차(새 코드): 관측 시각(at) %s · green %s · unknown %s · red %s · 재확인 %s"
          % (a.get("at"), a.get("green"), a.get("unknown"), a.get("red"), a.get("rechecked")))
    if a.get("rechecked"):
        print("   재확인이 있었다 — 관측 시각은 시작보다 70초 넘게 뒤다")
    print("   🔴 at 은 cron 시작 시각이 아니다(재확인이 없으면 1차 조회 시작 직전, 있으면 재확인 끝난 뒤) — 새 버전 첫 실행 '시작' 시각은 cron 기록으로 적는다")
    print("   체크아웃 뒤 회차 수 %d" % len(after))
    return 0


# 🔴 2026-10-27(정비 #12 에서 발견, 리뷰): 상태는 **키 바로 뒤의 첫 '판정 불가 (엔진)' · '판정 재개 (엔진)'** 하나로 읽는다.
#    BTC 관문 재개 줄은 `… 판정 재개 (DYNAMIC): 종전 <시각> BTC 캔들 판정 불가(결측 …` 처럼 종전 사유에 '판정 불가' 가 들어 있다
#    (CandleFreshnessGuard.btcGate). 종전에는 '판정 불가' 를 먼저 찾아 이 재개 줄을 판정 불가로 읽었다.
#    로그 형식은 CandleFreshnessGuard — `[캔들 최신성] {키} 판정 불가 ({엔진}) — …` · `[캔들 최신성] {키} 판정 재개 ({엔진}): 종전 …`.
_STATUS = re.compile(r"\[캔들 최신성\] (.+?) 판정 (불가|재개) \(")


def _events(path):
    """(키, 판정 불가인가) 를 로그 순서대로."""
    for line in open(path, encoding="utf-8", errors="replace"):
        m = _STATUS.search(line)
        if m:
            yield m.group(1), m.group(2) == "불가"


def _keys(path, stale):
    keys = []
    for k, s in _events(path):
        if s == stale and k not in keys:
            keys.append(k)
    return keys


def _stale_at_end(path):
    """로그 끝 시점에 아직 판정 불가인 대상 — 판정 불가 뒤 재개가 나오면 빠진다(순서대로 마지막 상태)."""
    state = {}
    for k, s in _events(path):
        state[k] = s
    return {k for k, v in state.items() if v}


def cmd_candle(after_log, before_log=None):
    """리뷰 2026-10-23: 특정 점검 공백 로그가 반드시 나와야 한다고 고정하지 않는다 — 재확인이 필요해진 대상마다 실제로 판정이 재개됐는지로 본다."""
    stale = _keys(after_log, True)
    resumed = set(_keys(after_log, False))
    # 리뷰 2026-10-23: 기동 뒤 로그도 **마지막 상태**로 본다 — '불가 → 재개 → 다시 불가' 는 재개가 한 번 있었어도 지금 판정 불가다
    end = _stale_at_end(after_log)
    # 기동 '직전' 에도 판정 불가였던 대상만 예외로 둔다 — 이전 컨테이너 기간 중 한때 판정 불가였다가 재개된 대상은 예외가 아니다
    pre = _stale_at_end(before_log) if before_log else set()
    left = [k for k in stale if k in end]
    print("기동 뒤 판정 불가로 들어간 대상 %d · 로그 끝 기준 판정 재개 %d · 아직 판정 불가 %d"
          % (len(stale), len(stale) - len(left), len(left)))
    for k in left:
        print("   판정 불가: %s%s%s" % (k, "  (재개 뒤 다시 판정 불가)" if k in resumed else "",
                                    "  (기동 직전에도 판정 불가였다)" if k in pre else ""))
    # 기동 뒤 한 번이라도 재개됐다가 다시 판정 불가가 된 대상은 기동 직전 상태로 넘기지 않는다 — 새로 생긴 판정 불가다
    return 0 if all(k in pre and k not in resumed for k in left) else 1


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
