# 총괄 v1.136 §104-3 — 11-09 관측 기록 공백 국소 재현(로컬, 실제 네트워크 없음)
# 실행 대상: 운영 배포판 1899b49 의 scripts/prospective/start_66.py 를 바꾸지 않고 불러 cmd_freshness / _alerts_report 를 그대로 돈다.
# 가짜로 바꾼 경계: 시계(datetime.now·time.sleep) · DNS(getaddrinfo → 문서용 주소 192.0.2.x N개) · 소켓 connect(시간 초과만큼 시계를 넘기고 TimeoutError)
#                   · 접속 가능 시각의 업비트 응답(최신 H1 봉 1개, BLAST 는 빈 배열) · 앱 API(need_env·_db_latest) · 기록 파일 경로(임시 폴더)
# 이 재현은 운영 218분의 원인을 확정하지 않는다. 주소 수·장애 구간은 가정값이다.
import contextlib, importlib.util, io, json, os, socket, sys, tempfile, types, urllib.request
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "start_66.1899b49.py")
UTC = timezone.utc
def T(s): return datetime.fromisoformat(s).replace(tzinfo=UTC)

# 앱 로그로 관측된 업비트 접속 실패 구간(UTC) — 파이썬 감시 도구가 같은 구간에 실패했다는 것은 가정이다
OUTAGE = [(T("2026-11-08T17:02:09"), T("2026-11-08T19:56:43")),
          (T("2026-11-08T19:58:53"), T("2026-11-08T20:48:53"))]
STARTS = ["2026-11-08T17:19:01", "2026-11-08T18:19:01", "2026-11-08T19:19:01", "2026-11-08T20:19:01"]

class Clock:
    now = None
CLOCK = Clock()
def in_outage(t): return any(a <= t < b for a, b in OUTAGE)

REAL_URLOPEN = urllib.request.urlopen
REAL_SOCKET = socket.socket
REAL_GAI = socket.getaddrinfo
N_ADDR = [1]
CONNECTS = [0]

class FakeSock(REAL_SOCKET):
    def connect(self, addr):
        assert addr[0].startswith("192.0.2."), addr   # 문서용 주소 외에는 절대 닿지 않는다
        CONNECTS[0] += 1
        CLOCK.now += timedelta(seconds=self.gettimeout())
        raise TimeoutError("timed out (가짜 — 실제 연결 없음)")
    def connect_ex(self, addr):
        raise AssertionError("connect_ex 금지")

def fake_gai(host, port, *a, **k):
    assert host == "api.upbit.com", host
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("192.0.2.%d" % (i + 1), port)) for i in range(N_ADDR[0])]

class Resp(io.BytesIO):
    status = 200
    def __enter__(self): return self
    def __exit__(self, *a): self.close()

def fake_urlopen(req, data=None, timeout=None):
    url = req.full_url if hasattr(req, "full_url") else req
    assert url.startswith("https://api.upbit.com/"), url
    if in_outage(CLOCK.now):
        return REAL_URLOPEN(req, data, timeout=timeout)   # 가짜 DNS·소켓으로만 간다
    CLOCK.now += timedelta(milliseconds=50)
    if "KRW-BLAST" in url:
        return Resp(b"[]")
    bar = CLOCK.now.replace(minute=0, second=0, microsecond=0)
    return Resp(json.dumps([{"candle_date_time_utc": bar.strftime("%Y-%m-%dT%H:%M:%S")}]).encode())

def load(tmp):
    os.environ["API_TOKEN"] = "dummy-not-a-secret"
    spec = importlib.util.spec_from_file_location("start_66_1899b49", SRC)
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    class FDT(datetime):
        @classmethod
        def now(cls, tz=None): return CLOCK.now
    m.datetime = FDT
    m.time = types.SimpleNamespace(sleep=lambda s: setattr(CLOCK, "now", CLOCK.now + timedelta(seconds=s)))
    m.need_env = lambda: None
    def db_latest():
        CLOCK.now += timedelta(milliseconds=200)
        bar = CLOCK.now.replace(minute=0, second=0, microsecond=0)
        out = {}
        for c in m.COINS:
            p = "KRW-" + c
            to = T("2026-10-27T00:00:00") if c == "BLAST" else bar
            out[p] = {"coinPair": p, "timeframe": "H1", "to": to.isoformat(), "count": 1000}
        return out
    m._db_latest = db_latest
    m.call = lambda *a, **k: (_ for _ in ()).throw(AssertionError("앱 API 호출 금지"))
    for name, fn in (("FRESH_LOG", "freshness_log.jsonl"), ("FRESH", "freshness_snap.json"),
                     ("STREAK", "freshness_streak.json"), ("ALERTS_MARK", "alerts_last_check.json")):
        setattr(m, name, os.path.join(tmp, fn))
    return m

def scenario(n_addr, seed_rows, out):
    N_ADDR[0] = n_addr; CONNECTS[0] = 0
    tmp = tempfile.mkdtemp(prefix="gap1109-")
    m = load(tmp)
    with open(m.FRESH_LOG, "w", encoding="utf-8") as fh:
        for r in seed_rows: fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    out.write("\n== 주소 %d개(접속 시도 1회 = 주소 %d × 10초) ==\n" % (n_addr, n_addr))
    out.write("시작(cron) | 기록 at | 실행 시간 | 1차 net | 재확인 | ⚪ | 🔴\n")
    for s in STARTS:
        CLOCK.now = T(s)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            m.cmd_freshness(save=True)
        last = json.loads(open(m.FRESH_LOG, encoding="utf-8").read().splitlines()[-1])
        at = T(last["at"][:26])
        out.write("%s | %s | %.1f분 | %s | %d | %d | %d\n" % (
            s[11:], last["at"][11:19], (at - T(s)).total_seconds() / 60,
            sum(1 for v in last["rows"].values() if v["exErr"] == "net") if not last["rechecked"] else "-",
            len(last["rechecked"]), last["unknown"], last["red"]))
        with open(os.path.join(HERE, "stdout-n%d-%s.txt" % (n_addr, s[11:16].replace(":", ""))), "w", encoding="utf-8") as fh:
            fh.write(buf.getvalue())
    # 원 프로세스들은 동시에 떠 있었다 — 파일에는 끝난 순서로 붙는다. 같은 판정기에 넣기 위해 at 순으로 다시 쓴다(판정기도 정렬한다)
    rows = [json.loads(l) for l in open(m.FRESH_LOG, encoding="utf-8")]
    out.write("connect 시도 %d회(모두 가짜)\n" % CONNECTS[0])
    now = T("2026-11-08T21:19:02")   # 다음 정상 회차(21:19) 직전까지 — 재현은 20:19 시작까지만 돌리므로 그 뒤 꼬리 공백을 만들지 않는다
    lines, bad, n = m._alerts_report(T("2026-11-08T13:00:01"), now, "지정 시각")
    out.write("-- 같은 판정기(_alerts_report, 구간 11-08 13:00:01Z~21:19:02Z) --\n")
    out.write("\n".join(l for l in lines if "실행 누락" in l or "⚪" in l) + "\n")
    out.write("요약: %s\n" % (" / ".join(bad) or "없음"))
    return rows

def main():
    socket.socket = FakeSock
    socket.getaddrinfo = fake_gai
    urllib.request.urlopen = fake_urlopen
    seed = [json.loads(l) for l in open(os.path.join(HERE, "..", "freshness_log-1108T14-22.jsonl"), encoding="utf-8")][:3]
    out = io.StringIO()
    out.write("운영 기록(at, freshness_log.jsonl): 16:19:01.85 · 19:56:42.59 · 19:57:08.70 · 19:58:12.85 · 20:50:12.71\n")
    out.write("운영 cron 시작(syslog KST→UTC): 17:19:01 · 18:19:01 · 19:19:01 · 20:19:01\n")
    for n in (1, 8):
        scenario(n, seed, out)
    sys.stdout.write(out.getvalue())
    open(os.path.join(HERE, "result.txt"), "w", encoding="utf-8").write(out.getvalue())

if __name__ == "__main__":
    main()
