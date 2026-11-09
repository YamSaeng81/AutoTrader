[정기 점검] 🔴 이상 2건
실행 2026-11-09 10:00 KST · 구간 11-08 22:00 ~ (12시간) · 운영 1899b49f · 점검 스크립트 c669ccdc6e24
🔴 미분류 ERROR 377건
🔴 66세션 경보(확정 아님 — 확인 필요): 적재 뒤처짐 12회 / 판정 불가 2회 / 실행 누락 1구간
🟡 외부 통신 실패 ERROR 2335건
🟡 KRW-BLAST 보충 수집 실패 — 상세 원인 미확인 3건
—
앱: running · 재시작 0 · 기동 2026-11-06T04:36:38.159366843Z · 이미지 426a09a20afc
로그 범위: 요청 11-08 22:00 ~ 실행 시각 · 현재 컨테이너 생성 11-06 13:36:36(구간 시작 전·같음 — 재생성 공백 없음)
DB: Flyway 94 · SID 203 밖 실자금 보유 0 · 미결 0 · 정리 대기 0 · 격리 대상 0 · 66세션: 원래 66 중 RUNNING 63 · 지정 종료 STOPPED 3/3(576 577 578) · 구간 SID 203 밖 실자금 매수 0
SID 203: CREATED · 잔액 20200.00 · 보유 0 · 미결 0 · 구간 주문 매수 0 매도 0 · 정산 확인 불가 0 · 보고값≠반영값 0 · 미해결 정산 불일치(전체) 0
BTC H1 입력(관측 11-09 10:00:02 KST): B1 true · 최신 완료 봉 11-09 09:00 · 필요 11-09 08:00(2분 유예) · B3 최근 20일 마감 봉 20/20
텔레그램(구간): 성공 4 · 큐 진입 실패 0 · 마지막 성공 2026-11-09 05:00
ERROR(구간): 전체 2715 · 공유 풀 미등록 0 · 캔들 판정 불가 0 · 상장 폐지 404 0 · 통신 실패 2335 · 미분류 377
ERROR(원인 미확인 🟡): 보조 뉴스 수집 실패 0 · KRW-BLAST 보충 수집 실패 3
  · 17:03:39.108 [scheduler-8] ERROR c.c.a.service.ExchangeHealthMonitor - 거래소 DOWN 감지 — ExchangeDownEvent 발행 (Upbit API 연속 3회 연결 실패)
  · 17:03:39.109 [scheduler-8] ERROR c.c.api.service.LiveTradingService - 거래소 DOWN 이벤트 수신 -- 모든 실전매매 세션을 비상 정지합니다.
  · 17:03:39.588 [scheduler-8] ERROR c.c.api.service.LiveTradingService - 전체 비상 정지 실행! (dryRun=false)
  · 17:03:39.602 [scheduler-8] ERROR c.c.api.service.LiveTradingService - 전체 비상 정지 완료: 0개 세션 정지 (dryRun=false)
  · 17:03:39.603 [scheduler-8] ERROR c.c.a.service.ExchangeHealthMonitor - 거래소 연속 3회 실패 — DOWN 선언
66세션: 🔴 적재 뒤처짐 12회 / 판정 불가 2회 / 실행 누락 1구간