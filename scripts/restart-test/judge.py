# 리허설 판정 순수 함수(총괄 v1.134 §3) — 앱·DB·외부 명령 없이 가짜 입력으로 검증한다(test_judge.py).
# 🔴 리허설 전용. 운영 N03 분류에 쓰지 않는다.
import datetime
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


F53_MARKS = ('⛔ 판정: 세션 상태 STOPPED', '거래소 BTC 증감 0', 'DB 정리 조건 충족')


def f53_ok(rc, out, f3_sha):
    """F5-3: 기대 텍스트를 다 출력해도 종료값이 0 이 아니면 거절."""
    return rc == 0 and f3_sha in out and all(m in out for m in F53_MARKS)


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
