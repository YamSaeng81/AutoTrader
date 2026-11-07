# judge.py 가짜 입력 시험(총괄 v1.134 §3-5). 앱·DB·외부 명령 없음. 사용: python -I test_judge.py
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import judge  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8')

R = []


def t(name, got, want):
    R.append((name, got == want))
    print(('PASS ' if got == want else 'FAIL ') + name + ('' if got == want else f' — got {got!r} want {want!r}'))


B409 = '{"success":false,"error":{"code":"CONFLICT","message":"세션이 이미 실행 중입니다: id=203"}}'
# 1. 재시작 요청
t('409·사유·차이0 → 통과(대조군, run2/run5 실제 응답)', judge.restart_rejected(409, B409, []), True)
t('200 성공·차이0 → 거절(허위 통과 1)', judge.restart_rejected(200, '{"success":true}', []), False)
t('500 실패·차이0 → 거절', judge.restart_rejected(500, '{"error":"x"}', []), False)
t('409 다른 사유 → 거절', judge.restart_rejected(409, '{"message":"다른 충돌"}', []), False)
t('409·사유·차이 있음 → 거절', judge.restart_rejected(409, B409, ['.x: 1 → 2']), False)
t('응답 없음(None) → 거절', judge.restart_rejected(None, None, []), False)

# 2. F5-1 주입 뒤·F5-3
S = '2026-11-06 19:50:00.656152'
E = '2027-01-01 19:50:00.656152'
t('F5-1 rc0·due t·같은 3줄·started+56일 → 통과(대조군, run5 원문)', judge.f51_shifted_ok(0, 't', [E, E, E], S), True)
t('F5-1 rc1 → 거절(허위 통과 2)', judge.f51_shifted_ok(1, 't', [E, E, E], S), False)
t('F5-1 같은 3줄이지만 started+56일 아님 → 거절', judge.f51_shifted_ok(0, 't', ['2027-01-02 19:50:00.656152'] * 3, S), False)
t('F5-1 줄 서로 다름 → 거절', judge.f51_shifted_ok(0, 't', [E, '2027-01-01 19:50:00'], S), False)
t('F5-1 due f → 거절', judge.f51_shifted_ok(0, 'f', [E, E], S), False)
t('F5-1 줄 1개 → 거절', judge.f51_shifted_ok(0, 't', [E], S), False)
t('F5-1 started 없음 → 거절', judge.f51_shifted_ok(0, 't', [E, E], ''), False)
t('F5-1 소수 초 없는 시각도 비교', judge.f51_shifted_ok(0, 't', ['2027-01-01 19:50:00'] * 2, '2026-11-06 19:50:00'), True)
F3 = 'bb86fb397da00523f564a8f02a1960c98bb1ac03887629fe74372990929fa2bb'
OUT = f'{F3}  f3-block.sh\n⛔ 판정: 세션 상태 STOPPED\n거래소 BTC 증감 0\nDB 정리 조건 충족\n'
t('F5-3 rc0·기대 텍스트 → 통과(대조군)', judge.f53_ok(0, OUT, F3), True)
t('F5-3 기대 텍스트 출력 뒤 rc1 → 거절(허위 통과 3)', judge.f53_ok(1, OUT, F3), False)
t('F5-3 F3 해시 다름 → 거절', judge.f53_ok(0, OUT.replace(F3, '30b5e729'), F3), False)
t('F5-3 정리 조건 줄 없음 → 거절', judge.f53_ok(0, OUT.replace('DB 정리 조건 충족', ''), F3), False)

# 4. 환경 ERROR(로컬 차단) — 실제 관측 원문만
P = '20:05:21.123 [scheduler-7]'
ENV = [
    '17:01:20.001 [ForkJoinPool.commonPool-worker-1] ERROR c.c.e.upbit.UpbitWebSocketClient - Upbit WebSocket 연결 실패: java.net.ConnectException',
    f'{P} ERROR c.c.api.news.source.RssNewsSource - [RssNewsSource] 수집 실패 — sourceId=tokenpost_rss: null',
    f'{P} ERROR c.c.api.news.source.RssNewsSource - [RssNewsSource] 수집 실패 — sourceId=blockmedia_rss: null\r\n',
    f'{P} ERROR c.c.a.news.source.UpbitNoticeSource - [UpbitNoticeSource] 수집 실패: null',
    f'{P} ERROR c.c.a.news.source.CryptoPanicSource - [CryptoPanicSource] 수집 실패: null',
    f'{P} ERROR c.c.a.n.s.CoinGeckoTrendingSource - [CoinGeckoTrendingSource] 수집 실패: null',
]
for i, l in enumerate(ENV):
    t(f'환경 ERROR 대조 {i + 1} → 환경', judge.is_env_error(l), True)
NOT_ENV = [
    ('뉴스 파싱 오류(null 아님)', f'{P} ERROR c.c.a.news.source.CryptoPanicSource - [CryptoPanicSource] 수집 실패: JSON parse error'),
    ('운영 CryptoPanic Connection reset', f'{P} ERROR c.c.a.news.source.CryptoPanicSource - [CryptoPanicSource] 수집 실패: Connection reset'),
    ('다른 sourceId', f'{P} ERROR c.c.api.news.source.RssNewsSource - [RssNewsSource] 수집 실패 — sourceId=coindesk_rss: null'),
    ('클래스·꼬리 불일치', f'{P} ERROR c.c.a.news.source.UpbitNoticeSource - [CryptoPanicSource] 수집 실패: null'),
    ('뉴스 처리 오류(다른 문구)', f'{P} ERROR c.c.a.news.NewsService - [NewsService] 처리 실패: Source] 수집 실패'),
    ('꼬리 뒤 덧붙은 문자', f'{P} ERROR c.c.a.news.source.CryptoPanicSource - [CryptoPanicSource] 수집 실패: null extra'),
    ('웹소켓 다른 예외', '17:01 [x] ERROR c.c.e.upbit.UpbitWebSocketClient - Upbit WebSocket 연결 실패: java.io.IOException'),
    ('같은 문구의 WARN', f'{P} WARN  c.c.a.news.source.CryptoPanicSource - [CryptoPanicSource] 수집 실패: null'),
    ('PoolLossGuard ERROR', f'{P} ERROR c.c.api.service.PoolLossGuard - 🔴 체결 수량이 있는데 반영값이 없는 실자금 주문 1건'),
]
for name, l in NOT_ENV:
    t(f'환경 아님: {name} → 새 ERROR', judge.is_env_error(l), False)

# 3. 단언/관측 분리
res = [('a', True, ''), ('(관측) b', True, ''), ('c', True, ''), ('(관측) d', True, '')]
t('집계: 단언 2/2 · 관측 2', judge.tally(res), (2, 2, 2))
t('집계: 실패 단언 반영', judge.tally(res + [('e', False, '')]), (2, 3, 2))

ok = sum(1 for _, o in R if o)
print(f'결과 {ok}/{len(R)}')
sys.exit(0 if ok == len(R) else 1)
