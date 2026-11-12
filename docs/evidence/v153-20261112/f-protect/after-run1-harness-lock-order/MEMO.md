# F 후보 시험 1회차(수정 후) — 원본 덮어씀 기록

1회차 XML·gradle.log 는 2회차 실행이 같은 폴더(`after/`)에 쓰면서 덮어썼다(폴더 이름 바꾸기가 그 폴더 안에서 실행돼 실패한 것을 확인하지 않음).
아래는 1회차 직후 대화 출력에 남은 원문 전사다. 원본 파일은 없다.

- 결과: 4 tests completed, 1 failed (exit=1)
- 실패: compareCallerRunsVersusPendingLane — Saturation 준비 중
  `java.util.concurrent.RejectedExecutionException: Task java.util.concurrent.FutureTask@6f2c8fcf[...Saturation$$Lambda...] rejected from org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor$1@48689397[Running, pool size = 4, active threads = 4, queued tasks = 51, completed tasks = 187]`
- 원인(판단): 행 잠금을 포화 준비 전에 잡았다 — 시험 하네스 순서 결함. 제품 변경 없이 잠금을 포화 뒤로 옮겼다.
- 같은 실행의 다른 3건 출력:
  - `[F153] 작업자 바쁨: 예외 null 매도 1 상태 CLOSING | LIVE Snapshot[engine=LIVE, rejected=0, pendingCoins=0, processed=1, replaced=0, lastRejectReason=null]`
  - `[F153] 종료 거절: 예외 null | Snapshot[engine=LIVE, rejected=2, pendingCoins=1, processed=0, replaced=0, lastRejectReason=실행기 포화 — 보호 점검 접수 거절, 코인별 최신 시세 대기 유지]`
  - `[F153] 포화 중 발행 예외: null / null / null`
  - `[F153] 포화 중 시세 장부 a=280 b=280`
  - `[F153] 포화 중 매도: a=0 b=0 | LIVE Snapshot[rejected=3, pendingCoins=2, processed=1, replaced=1, ...] | DYNAMIC Snapshot[rejected=3, pendingCoins=2, processed=1, replaced=1, ...]`
  - `[F153] 해제·재접수(true) 뒤 매도: a=2 b=1 상태 la=CLOSING da=CLOSING lb=CLOSING | LIVE ...pendingCoins=0, processed=3 | DYNAMIC ...pendingCoins=0, processed=3`
  - `[F153] 재시세 뒤 매도: a=2 b=1 발행 예외 null 거래소 주문 증가 3`
