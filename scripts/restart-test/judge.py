# 리허설 판정 순수 함수(총괄 v1.134 §3) — 앱·DB·외부 명령 없이 가짜 입력으로 검증한다(test_judge.py).
# 🔴 리허설 전용. 운영 N03 분류에 쓰지 않는다.
import datetime
import decimal
import re

RESTART_REJECT_REASON = '세션이 이미 실행 중입니다'


def restart_rejected(status, body, diff):
    """RUNNING 세션 재시작 요청: 실제 HTTP 409 + 거절 사유 + 기록 차이 0 을 함께 요구한다.
    200 성공 응답(status=200)이나 다른 HTTP 실패(500 등)는 기록이 그대로여도 거절한다."""
    return status == 409 and RESTART_REJECT_REASON in (body or '') and diff == []


KST_FMT = '%Y-%m-%d %H:%M:%S.%f'


def _kst(s):
    s = s.strip()
    return datetime.datetime.strptime(s if '.' in s else s + '.000000', KST_FMT)


def f51_shifted_ok(rc, due, ends, started_kst, days=56):
    """F5-1 시계 주입 뒤: 종료 0 · due t · end_kst 줄 2개 이상 모두 같고 · 실제 started_at + 56일과 같다."""
    if rc != 0 or due != 't' or len(ends) < 2 or len(set(ends)) != 1 or not started_kst:
        return False
    try:
        return _kst(ends[0]) == _kst(started_kst) + datetime.timedelta(days=days)
    except ValueError:
        return False


F53_MARKS = ('⛔ 판정: 세션 상태 STOPPED', 'DB 정리 조건 충족')

# 총괄 v1.135 H1: '거래소 BTC 증감 0' 부분 문자열은 '0.0016' 도 맞았다. BTC 줄을 행 구조로 읽고 세 값 모두 Decimal 0 을 요구한다.
BTC_KEY = '거래소 BTC 증감'
BTC_LINE = re.compile(r'^거래소 BTC 증감 (\S+) · 주문 순체결량 (\S+) · 열린 포지션 수량 (\S+)$')


def _dec_zero(s):
    try:
        d = decimal.Decimal(s)
    except decimal.InvalidOperation:
        return False
    return d.is_finite() and d == 0


def f53_btc_zero(out):
    """BTC 대조 줄: 1개 이상 · 모두 형식 일치 · 서로 다른 행 없음 · 거래소 증감·주문 순체결량·열린 수량 모두 0.
    0·0E-8·-0 같은 동등한 0 은 허용, 0.0016·-0.0016·누락·형식 이상·서로 다른 중복 행은 거절. 임의의 한 행만 고르지 않는다."""
    lines = [l.rstrip('\r') for l in (out or '').split('\n') if BTC_KEY in l]
    if not lines or len(set(lines)) != 1:
        return False
    m = BTC_LINE.match(lines[0])
    return bool(m) and all(_dec_zero(v) for v in m.groups())


def f53_ok(rc, out, f3_sha):
    """F5-3: 종료 0 · F3 해시 · 기대 상태/DB 정리 문구 · BTC 대조 줄 세 값 모두 0."""
    return rc == 0 and f3_sha in out and all(m in out for m in F53_MARKS) and f53_btc_zero(out)


# 로컬 하네스가 외부 인터넷을 막아 생기는 ERROR — W3 run2~run5 실제 로그에서 관측한 원문 그대로만(줄 끝까지 고정).
# 공통 꼬리('Source] 수집 실패')만으로 빼지 않는다: 다른 sourceId·null 아닌 메시지·다른 처리 오류는 새 ERROR 로 남는다.
ENV_ERROR_PATTERNS = (
    re.compile(r' ERROR +c\.c\.e\.upbit\.UpbitWebSocketClient - Upbit WebSocket 연결 실패: java\.net\.ConnectException$'),
    re.compile(r' ERROR +c\.c\.api\.news\.source\.RssNewsSource - \[RssNewsSource\] 수집 실패 — sourceId=(tokenpost_rss|blockmedia_rss): null$'),
    re.compile(r' ERROR +c\.c\.a\.news\.source\.(UpbitNoticeSource|CryptoPanicSource) - \[\1\] 수집 실패: null$'),
    re.compile(r' ERROR +c\.c\.a\.n\.s\.CoinGeckoTrendingSource - \[CoinGeckoTrendingSource\] 수집 실패: null$'),
)


def is_env_error(line):
    line = line.rstrip('\r\n')
    return any(p.search(line) for p in ENV_ERROR_PATTERNS)


def tally(results):
    """results: (이름, 참거짓, 상세) — '(관측) ' 으로 시작하는 항목은 단언 수에 넣지 않는다."""
    asserts = [r for r in results if not r[0].startswith('(관측) ')]
    notes = len(results) - len(asserts)
    return sum(1 for r in asserts if r[1]), len(asserts), notes
