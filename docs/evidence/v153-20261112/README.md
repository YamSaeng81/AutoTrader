# 총괄 v1.153 실행 근거 — E 잔여·F 독립 보완·G 화면·H 복구 관문·I 통합 (2026-11-12, 개발 세션, 총괄 판정 전)

운영 접속은 N03 읽기 전용뿐이다. 운영 배포·DDL·세션 생성·재기동 없음. 로컬 합성 결과는 운영 부하 실측이 아니다.

| 단위 | 커밋(가지) | 결과 | 폴더 |
|---|---|---|---|
| E 잔여 | **b46302d9** (aligned2) | 연속 손실 한도 2 3/3 · 일/주/월 창 수정 전 6/7 → 후 7/7 | `e-r5/` |
| F 독립 보완 | **ec7d33ef** (`candidate/md-protect-dispatch`, b5cd1d0 기점, 미승인) | 기점 실패 → 후보 4/4, 관련 135/135 | `f-protect/` |
| G 화면 | **5f9ef2ad** (aligned2) | 실제 렌더링 7/7, tsc 0 | `g-render/` |
| H 복구 관문 | (도구·예행, 이 폴더) | 1판 로컬 PG 예행 10/10 · **2판**(기록 실패 시 성공 금지) 가짜 명령 1판 3/10 → 2판 10/10, PG 예행 10/10 | `h-rollback/`, `h-rollback/v2/` |
| I 통합 1차 | c7137896(조사 사본) | web-api 1257 중 실패 16(전체 실행에서만, 원인 미확인) | `i-final/c7137896-*` |
| F 2판 (v1.154) | **9d92a0f7** (`candidate/md-protect-dispatch`, 미승인) | 1판 디스패처 11건 중 5실패 → 2판 11/11, 관련 142/142 | `f-protect-v2/` |
| LIVE 손실 합산 (v1.154) | **0b23b44e** (`candidate/live-loss-window`, b5cd1d0 기점, 미승인) | 실제 PG 기점 1/7 → 후보 7/7, 관련 60/60 | `loss-window/` |
| B01 다음 매도 봉 (v1.154) | **8e81d0b4** (`candidate/b01-live-clock-seams`, 통합 제외) | 기점 실패 → LIVE SELL 통과, PAPER 미연결 관측, 기존 D 시험 1실패(고정 자료 정시 아님) | `b01-next-bar/` |
| I 통합 2차 | 9bce7dc1 (`integration/v154-aligned2`, 고정 후보 아님) | 관련 293/293 · PG 35/36(실패 1은 기점부터) · 전체 web-api 1273 중 실패 17(1차와 같은 16 + 대기열 1, 원인 미확인, `service.S*` 만은 180/180) · jar 881→891 | `i-final/9bce7dc1-*` |

## 1. E 잔여 — 같은 세션 연속 손실 한도 2·일/주/월 창 (`e-r5/`)

- 연속 손실 한도 2(3/3). 앞선 거래 1건은 **저장 행**, 마지막 거래만 엔진 실제 매수→매도 커밋이다.
  - 같은 세션의 두 번째 실제 진입은 다음 일봉(24시간 뒤)이어야 하는데, 캔들 조회 상한·최신성 관문·보유 시간이 실제 시각이라 시험 안에서 만들 수 없다.
  - 손실(−150)→실제 손절 손실: LIVE·PAPER 둘 다 연속 2회, 한도 2 CB 발동.
  - 손실→실제 수수료 경계: 위험 입력 +9.92(LIVE)/+9.95(PAPER)라 줄이 끊김(둘 다 0회, CB 없음). PAPER 성적 −0.08 로 셌다면 2회였다.
  - 이익(+150)→실제 손절 손실: 둘 다 1회, CB 없음.
- 일/주/월 창(PAPER `alignedRiskCheck`):
  - 제품 변경 1줄 — 창 기준 시각을 공통 시계(`EntryClock`, 운영 기본 시스템 UTC)로. 선택 모드 경로만.
  - 수정 전(`before/`) 6/7: +1일+1µs 에서 PAPER 가 시계를 따르지 않음("일" 그대로).
  - 수정 후(`after/`) 7/7: 실제 손절 손실 1건이 0·+1일(경계 포함 ≥)=일, +1일+1µs·+7일=주, +7일+1µs·+30일=월, +30일+1µs=창 밖.
- 🔴 **새 관측(제품 수정 없음, 판단 요청):** LIVE 세션 매도는 `OrderExecutionEngine.handleSellFill` 이 trade_log FILL 기록 전에 반환한다(정산은 `reconcileClosingPositions`, trade_log 없음).
  - 실제 경로 기록: 그 매수·매도 주문의 trade_log 7행이 모두 STATE_CHANGE, FILL 0.
  - LIVE 전역 `checkRisk` 의 일/주/월 손실(`sumRealizedLossSince`, trade_log FILL)이 **세션 매매 손실을 보지 않는다**는 뜻이다.
  - 운영 b5cd1d0 에 같은 코드다. 운영 trade_log 실제 행은 읽지 않았다(미확인).
  - `LIVE_RISK_IMPLEMENTATION_REVIEW.md` §1.1 은 손실 원천을 trade_log FILL 로 적고 있다.
- 실패 회차(원문 보존):
  - `run1-test-code-error`: 앞선 저장 행을 `get(0)` 으로 집은 시험 코드 결함, 4건 실패.
  - `run2-h2-json-op`: LIVE 창 함수(`->>`)가 H2 에서 문법 오류. LIVE 쪽은 FILL 건수 관측으로 바꿈.
- 남은 것: **B01 실제 다음 매도 봉**은 막힘으로 분리했다.
  - 같은 이유(24시간 진행에 캔들 조회 상한·최신성·보유 시간이 실제 시각)다.
  - 풀려면 LIVE 실자금 경로의 그 세 곳을 공통 시계로 옮겨야 한다(운영 기본 UTC면 동작 같음). 이번 "최소 변경" 범위를 넘어 하지 않았다.

## 2. F 독립 보완 — 실시간 보호 전달 경계 (`f-protect/`, 후보 ec7d33ef)

- 기점: 운영 b5cd1d0. 조사 원문 03cfd9eb 는 보존하고 병합하지 않았다.
- 바꾼 것:
  - LIVE·DYNAMIC `onRealtimePriceEvent` 의 `@EventListener @Async` 를 떼고, 새 `RealtimeProtectionDispatcher` 가 받는다.
  - 엔진별 **코인별 최신 시세 대기칸**에 넣고, 엔진별 처리 작업이 없을 때 같은 `marketDataExecutor` 에 하나 접수한다. 처리기는 프록시(트랜잭션) 그대로 부른다.
  - 접수 거절이면 그 시세를 버리지 않는다. 대기칸에 남기고 **엔진별 거절 수·고정 사유**(`실행기 포화 — 보호 점검 접수 거절, 코인별 최신 시세 대기 유지`)를 남긴다.
  - 다음 시세 때, 그리고 대기칸이 비어 있지 않은 동안 1초마다 다시 접수한다(앱 내부 스케줄, 새 cron 아님).
  - 비가격·더 이른 관측 시세는 대기 중인 유효 시세를 덮지 않는다.
- 바꾸지 않은 것: 실행기 크기(2/4/100)·전역 AbortPolicy·거래소 복구 이벤트의 `@Async` 거절 계약, 처리기 본문·잠금 순서, 전략 판단. 호출 스레드 대체 처리 없음.
- 두 안 비교(행 잠금 400ms 중 발행 지연 측정, 같은 실행):
  - 안 A(거절 시 호출 스레드에서 처리, 시험 안 변형): **407ms**. 웹소켓 콜백 스레드가 DB 잠금을 기다린다.
  - 안 B(대기칸, 제출안): **0ms**.
  - 둘 다 손절 1회. 안 B 를 제출한다.
- 같은 시험 `RealtimeProtectionSaturationTest`(실제 실행기·실제 LIVE/DYNAMIC 보유 진입 300·손절가 285·실제 처리기·주문 실행기, 거래소 대역):
  - 기점 b5cd1d0(`before/`, 제품만 기점으로 되돌려 같은 시험) 3/4. 대기열 가득 사례 실패: 발행 쪽 `TaskRejectedException`, 시세 장부 null, 해제 뒤 새 시세 전 매도 0, 다음 코인 매도 0.
  - 후보(`after/`) **4/4**:
    - 대기열 가득: 발행 예외 0, 시세 장부 a·b=280, 해제 전 매도 0. 해제 뒤 새 시세 없이 재접수로 LIVE·DYNAMIC 손절 각 1회·다음 코인 1회. 재시세에 중복 없음. 실자금 주문은 그 3건뿐. 엔진별 거절 3·대기 0.
    - 작업자 바쁨·대기열 여유: 거절 0, 해제 뒤 1회.
    - 종료된 실행기: 발행 예외 0, 거절 2·고정 사유·대기 유지, 처리기 호출 0.
    - 비교(위).
  - 하네스 실패 회차(제품 변경 없이 시험 장치만 고침, 원문 보존):
    - `after-run1-harness-lock-order`: 원본 덮어씀 — 전사 메모만 남음.
    - `after-run2-harness-idle-threads`
    - `after-run3-harness-blockers-queued`: 원인 정정 — 다른 제출자가 아니라 래치가 대기열 칸을 차지했다.
- 관련 시험: 기존 `StopVersusInFlightExitRaceTest` 가 처리기를 직접 불러 동기로 바뀌어 4건 실패했다(`related-run1-direct-call`).
  - 진입을 경계로 바꿨다(단언 그대로). 그 뒤 7/7.
  - ec7d33ef 에서 12클래스 **135/135**(`related-final-ec7d33ef`).
- 남는 지연 한계:
  - 포화가 풀릴 때까지 + 최대 1초(재접수 주기).
  - 대기가 120초(유효기간)를 넘으면 처리기의 N02 검사가 그 값을 버린다. 그때 남는 보호는 기존 정기 평가 경로다.
  - 운영 발생·실제 손절 주문 누락은 미확인이다.
- DYNAMIC 독립 후보 81b0e7a7 과 같은 파일(`DynamicTradingService.java`)을 바꾼다. 함께 내보내려면 합친 커밋·jar 대조를 새로 해야 한다.

## 3. G 화면 (`g-render/`, 커밋 5f9ef2ad)

- 단건·일괄 정지 응답의 청산 대기 수를 화면에 연결했다.
  - 대기가 있거나 수가 응답에 없으면 "정산 완료"라고 쓰지 않는다.
  - 상세 화면은 대기 시 이동하지 않고 결과를 보인다(대기 0 이면 종전대로 목록 이동).
  - REAL 화면 변경 없음.
- 실제 렌더링: Next dev + Playwright, `/api/proxy/**` 를 가로채 `PaperTradingController` 필드 이름대로 대역 응답. 백엔드·운영 접속 없음. **7/7**(`run5/`, 화면 사진 7장):
  - ① 정상 보유
  - ② CLOSING·평가 null(평가 불가, 마지막 기록은 "현재 평가 아님"과 함께, 보유 표의 미정산 보유)
  - ③ 다시 읽어 정산 뒤 확정 총자산
  - ④ 정지 응답 대기 1(상세에 머묾)·0(목록 이동)·일괄 2·수 없음
- 한계: 대역의 목록 응답은 정지 뒤에도 세션 상태를 RUNNING 으로 준다(화면 결함 아님). 운영 프런트엔드 반영 아님.
- tsc 0, eslint 오류 0(기존 경고 3).
- 실행 실패 회차(원문 보존):
  - run1: 포트 3100 Windows 제외 범위
  - run2: 연결 node_modules 를 Turbopack 이 거부
  - run3·run4: webpack 에서 React/Next 이중 적재("app router to be mounted")
  - 고친 방법: node_modules 를 실제로 복사했다(원본 그대로).

## 4. H 복구 관문 (`h-rollback/README.md`)

- 생성 전/후를 나눈 구판 복구 관문 `rollback_gate.sh` + 읽기 전용 `gate.sql` 을 만들었다.
  - 판정: PRE_CREATION_OK / POST_CREATION_OK(마지막 장부 먼저) / HOLD_PENDING / STOP_RUNNING / STOP_MISMATCH / STOP_UNKNOWN / LEDGER_REFUSED. 자동 되돌림 없음.
- 로컬 PG 예행 **10/10**. 관문 전후 표 내용 해시가 같아, 쓰기가 없음을 확인했다.
- 실패 회차 2건은 원문 보존.
- 독립 DYNAMIC 초안 점검과 F 와의 파일 겹침은 그 README §4 에 있다.

## 5. I 통합 — 1차 c7137896(조사 사본, 원문 보존)

- `integration/v153-aligned2-f` c7137896 = aligned2 5f9ef2ad + 시험 가지 4건 + F 1판 ec7d33ef. 고정 후보 아님. 이번에 원문 보존용으로 push 했다.
- 전체 회귀(`i-final/c7137896-full/`, 09:14:29~09:36:01): core-engine 298(건너뜀 7)·exchange-adapter 11·strategy-lib 113 전부 통과, **web-api 1257 중 실패 16·건너뜀 33**.
  - 실패 16은 전부 LIVE 진입이 생기지 않은 모양이다("후보 없음"·LIVE 매수 0): Sma20LivePaperSameInputTest 6·Sma20LiveAlignedPaperSameInputTest 6·Sma20LiveAlignedGapsTest 3·SharedLoopRoundsActiveTest 1. 메시지 원문은 `summary.txt`.
  - 같은 SHA 에서 한 클래스만(`c7137896-bisect/merge-c7137896.log`) 12/12, 병합 직전 b000944e 에서도 12/12, 실패 4클래스 + 새 시험 4클래스를 함께 59/59(`combo1`). **전체 실행에서만 생겨 원인 미확인**(실행 순서·다른 클래스 의존으로 추정, 확인 안 함).
  - 🔴 근거 손실: 전체 실행 직후 실패 클래스 XML 을 복사하지 않은 채 단일 클래스를 다시 돌려 Gradle 이 결과 폴더를 지웠다. 남은 것은 그 전에 만든 `summary.txt`(XML 에서 뽑은 실패 메시지)·`gradle.log`·`time.txt` 뿐이다. 이후 실행은 끝나자마자 XML 을 복사한다.
- 총괄 v1.154 지시대로 이 결과를 F/위험 합산/H 해결판의 통과로 쓰지 않는다.

## 6. F 2판 — 도착 순서 대기열 (`f-protect-v2/`, 후보 9d92a0f7, 미승인)

- 1판(ec7d33ef)의 코인별 최신값 덮기를 버리고, **엔진 × 코인별 도착 순서 대기열**로 바꿨다. 시세를 거르거나 덮지 않는다. 비가격·낡은 값·미래 관측의 판단과 throttle(평상 5초·급변 1초, 처리 시각 기준)은 종전처럼 처리 시각에 처리기가 한다. 경계는 관측 시각을 비교하지 않는다(원문 관측 시각 그대로 전달).
- 코인마다 처리 작업 하나 — 같은 코인은 순서대로, 다른 코인은 별도 작업. 접수 거절은 대기 유지·엔진별 거절 수·1초 재접수(1판과 같은 고정 사유 문구). 코인별 상한 100 초과는 새 시세만 버리고 엔진별 초과 수·고정 사유("코인별 보호 대기열 초과 — 새 시세 버림"). 처리기 예외는 그 시세 하나만 실패로 세고 계속.
- 바꾸지 않은 것: 실행기 2/4/100·전역 AbortPolicy·거래소 복구 이벤트 `@Async` 계약·처리기 본문·잠금 순서. 호출 스레드 대체 없음.
- 계약 시험(기존 `RealtimeProtectionSaturationTest` 에 7건 추가, 기존 4건 단언 불변). 같은 시세 열을 **기준 실행**(시세 장부에 적고 LIVE·DYNAMIC 처리기를 이 스레드에서 도착 순서대로 직접 호출)과 **경계 경유**(작업자를 모두 붙잡은 채 발행·해제)로 돌려 코인·엔진별 포지션 상태·매도 주문 수를 비교:

| 시세 열 | 기준 = 경계 |
|---|---|
| 280→300 | LIVE·DYNAMIC CLOSING, 매도 2 (1판: OPEN·매도 0) |
| 310→290→280 | OPEN·매도 0 (1판: CLOSING·매도 2) |
| 300→280 | LIVE CLOSING·DYNAMIC OPEN, 매도 1 |
| 미래(+24h)300→280 | LIVE CLOSING·DYNAMIC OPEN, 매도 1 |
| 0→280, null→280 | CLOSING·매도 2 |
| 낡은(−200초)280→300 | OPEN·매도 0 |
| 역순 관측 280(−10초)→300(−20초) | CLOSING·매도 2 |
| A280·B300·A300·B280 | A: CLOSING·매도 2 / B: LIVE CLOSING·DYNAMIC OPEN·매도 1 |

  - 표의 OPEN·매도 0 사례(310→290→280, 낡은 280→300)와 DYNAMIC 이 300 뒤 280 에서 OPEN 인 것은 **처리기의 기존 throttle·유효기간 규칙**에서 나온 결과이고 기준 실행과 같다. 경계가 만든 손실이 아니다. 다만 "5초 안의 뒤 시세는 점검하지 않는다"는 기존 규칙 자체가 손절선을 지난 시세를 넘길 수 있다는 관측으로 남긴다(제품 변경 없음, 판단 요청).
  - 지연: LIVE 코인 A 포지션 행 잠금 400ms 중 A 매도 주문 414ms, **B 매도 주문 13ms**(작업자 여유일 때). 1판은 엔진당 처리 작업 하나라 구조상 B 가 A 뒤에 줄 선다(이번 1판 실행에서 이 시험은 통과했다 — 코인 순회 순서에 따라 B 가 먼저 처리될 수 있다).
  - 단위: 작업자 1개를 붙잡고 100건을 쌓으면 같은 객체·같은 순서·관측 시각 원문 그대로 처리, 101번째만 초과 1. 처리기 예외 1건은 실패 1·나머지 처리.
- 실행: 1판 디스패처로 같은 시험 11건 중 5실패(`before-v1-*`) → 2판 **11/11**(`after-v2-*`). 2판 1회차는 시험 코드 결함 1건(리플렉션 오버로드, `after-run1-test-code-error*` 원문 보존). 관련 12클래스 **142/142**(`related-v2-*`, 1판 때 135 + 새 7).
- 총괄 탐침 원문(`review-v153-progress/ProtectionPendingWitnessTest.java`, sha256 00bacde6…)을 2판에 그대로 넣어 한 번 돌리고 지웠다(`root-probe-on-v2-*`): 결함을 단언하는 ①(280→300 매도 0)·③(미래 300 뒤 매도 0)이 **실패**(매도 2·1), 대조군 ②(280 단독 매도 2) 통과. 탐침은 커밋하지 않았다.
- 남는 한계(새 전달 정책): 대기가 120초를 넘으면 처리기 N02 가 버림, 재접수 최대 1초, 상한 초과 시 새 시세 버림, 처리 시도 수·대기 수는 보호 완료 수가 아님. 거래소 복구 이벤트·120초 초과·실행기 종료 후 재기동은 이번에 새로 시험하지 않았다(종료 거절은 기존 시험).
- DYNAMIC 독립 후보 81b0e7a7 과 같은 파일(`DynamicTradingService.java`)을 바꾸는 점은 그대로다.

## 7. LIVE 일/주/월 손실 합산 (`loss-window/`, 후보 0b23b44e, 운영 b5cd1d0 기점, 미승인)

원천 대조(코드 읽기):

| 경로 | 손익 확정 | trade_log FILL | 주문 반영 표시·반영 대금 | 종전 합산 |
|---|---|---|---|---|
| 비세션 매도(`handleSellFill`) | 포지션 | **씀**(realizedPnl) | — | 들어감 |
| LIVE 세션 매도(`finalizeSellPosition`) | 포지션·세션 잔액 | 안 씀(조기 반환) | `sell_applied_at`·`applied_executed_funds/fee` | **빠짐** |
| LIVE 늦은 정정(`applySellDelta`) | 포지션 손익 += 차분 | 안 씀 | 반영 대금·수수료 갱신 | 빠짐 |
| LIVE 팬텀 정리(추정가) | 포지션 | 안 씀 | 없음 | 빠짐(유지 — 추정 체결) |
| DYNAMIC REAL 세션 매도 | 포지션·세션 잔액 | 안 씀 | 있음 | 빠짐(이 후보도 범위 밖) |

- 선택: **기록 경로를 바꾸지 않고**, 창 합산에 "LIVE 세션 매도 확정 손실"(주문 행: 반영 대금 − 반영 수수료 − 체결 수량 × 포지션 평균단가, 손실 매도만, `session_kind='LIVE'`·`sell_applied_at ≥ since`)을 더한다. FILL 을 새로 쓰거나(정정 차분 행이 손실 합에서 따로 놀고 과거 행은 없다) CLOSED 포지션을 더하는(부분 매도 뒤 덮어쓰기·정정) 방식은 쓰지 않았다. DDL·한도·손익 관례 변경 없음. `RiskManagementService` 생성자 불변(필드 주입, 단위 시험 생성자 경로는 종전대로 FILL 만).
- 실제 PG 시험 `LiveLossWindowPgTest`(로컬 v152-pg 새 DB): 기점 **1/7**(비세션 FILL 대조군만 통과, 세션 손실 0) → 후보 **7/7**.
  - 실제 LIVE 손실 체결→확정 뒤 `checkRisk()`: 일일 1.5% 한도에서 "일일 손실 한도 초과: 2.00% > 1.50%" 거절(기점은 승인).
  - 체결만·확정 전 0, 확정 2,001, 재확정 2,001(이중 없음), 정정 수수료 9→10 뒤 2,002(포지션 손익과 같음), FILL 행 0 유지.
  - 부분 매도 1,000.50 → 나머지 매도 뒤 3,000.50. 롤백(세션 갱신 트리거 실패) 0·반영 표시 null → 재확정 2,001. 이익(+1,997)과 상쇄 없음. 창 경계 T 포함·T+1µs 제외.
  - 범위 관측: 비세션 FILL 대조군 2,001 그대로, **DYNAMIC REAL 세션 손실 2,001 은 합산 0**(이 후보 범위 밖 — 분모가 LIVE RUNNING 초기자본 합이라 넣을지 판단 요청).
- 🔴 새 관측(제품 변경 없음): LIVE 부분 매도 뒤 나머지 매도로 닫으면 `finalizeSellPosition` 이 포지션 실현손익을 **더하지 않고 덮어써** 앞 부분 손익(−1,000.50)이 포지션 행에서 사라진다(시험 ③: 포지션 −2,000 대 두 매도 합 −3,000.50). 연속 손실 판정 등 포지션 손익을 읽는 곳에 영향이 있을 수 있다. 운영 발생은 미확인.
- 관련 13클래스 60/60. 운영 행·실제 차단 실패는 읽지 않았다(미확인). 이 공백은 실전 시작 전 확인 항목이다.

## 8. B01 실제 다음 매도 봉 (`b01-next-bar/`, 별도 가지 `candidate/b01-live-clock-seams` 8e81d0b4, aligned2 머리 아님, 미승인)

- 접점 목록(LIVE, aligned2 기준): ① `loadRecentCandles` 의 `to = Instant.now()`, ② `freshCandles` → `CandleFreshnessGuard.filter` 안의 `Instant.now()`(새 5인자 판으로 판단 시각을 받음, 다른 호출자는 4인자 = 실제 시각), ③ 신호 매도 `heldMinutes` 의 `Instant.now()`. 세 곳만 `judgeNow()`(EntryClock, 운영 기본 시스템 UTC)로. DB 감사·주문·체결 시각, 실시간 throttle, 시간 손절, BTC 관문 now, DYNAMIC 은 그대로.
- `B01RealNextSellBarTest`: 매수 봉 마감 정각 매수·체결 → **평가 표시 삭제·보유 시작 이동 없이** 시계만 다음 마감 봉(+24h) 마감으로. 가격 입력은 종전 B01 과 같다.
  - 기점 aligned2 5f9ef2ad: 실패 — LIVE·PAPER 표시가 매수 봉 +1h 에 머묾(`before*`). 1회차는 시험 컴파일 오류(`run0-compile-error.log`).
  - 접점 연결 뒤: LIVE 마감 1µs 전 매도 0, 마감 정각 **전략 SELL 1·표시 = 매도 봉·보유 시작 그대로·재처리 없음**.
  - PAPER 는 같은 종류 접점 4곳(`fetchRecentCandles` to·`alignedCandleProblem`·`alignedBtcGate` now·신호 매도 `heldMinutes`)이 실제 시각이라 OPEN. 1회차는 PAPER "CLOSED" 기대로 실패(`after-run1-paper-seams*` 원문 보존). 허용 범위(LIVE 세 곳) 밖이라 연결하지 않고 **현재 관측(OPEN)을 고정**했다 — PAPER 접점 연결 승인 뒤 바꿀 자리.
- 🔴 기존 시험 충돌: 이 커밋에서 `RealNextBarClockTest`(D) ① 이 실패한다(`related-run1-*`: 13클래스 96 중 실패 1). 그 고정 자료의 봉 시작이 정시가 아니라(지금 −61분, 초 단위) 최신성 관문의 정시 격자에서 마감 1µs 전 직전 봉이 "30분 늦음"으로 판정 불가가 된다(로그 원문 XML). 정시 시작 봉으로 같은 단계를 돌리면 직전 봉을 평가한다(`hourAlignedPrevBarStillEvaluatedJustBeforeClose`, `aligned-check*` 2/2). D 시험은 고치지 않았다. 그래서 이 커밋은 통합 사본에 넣지 않았다 — D 고정 자료를 정시로 맞출지 판단 요청.

## 9. I 통합 — 2차 9bce7dc1 (`i-final/`, `integration/v154-aligned2`, 고정 후보 아님)

- 구성: aligned2 0cfaecae(= 5f9ef2ad + 잔고·목록 RR PG 시험) + 시험 가지 4건(cherry-pick) + F 2판 9d92a0f7 병합 + 손실 합산 0b23b44e 병합. 충돌 없음. B01 접점 8e81d0b4·DYNAMIC 81b0e7a7·main V95 는 넣지 않았다.
- 관련(H2): 36클래스 **293/293**(`9bce7dc1-related/`).
- 실제 PG(로컬 v152-pg, 클래스마다 새 DB, `9bce7dc1-pg/`):

| 클래스 | 결과 |
|---|---|
| AlignedV96PgBehaviorTest | 5/5 |
| **BalanceListSnapshotPgTest**(잔고·목록 RR 경합) | **2/2** |
| PerformanceSnapshotS5PgTest | 1/1 |
| StartedAtPrecisionPgTest | 1/1 |
| LiveLossWindowPgTest | 7/7 |
| N15PgLockWaitExpiryTest | 4/4 |
| SettlementRoundingPgTest | 15/16 — 실패 1(⑧ LIVE): 시험이 `executeSessionBuy` 를 7인자로 리플렉션 호출, 제품은 b5cd1d0 부터 8인자. **기점 b5cd1d0 에서도 같은 1실패**(`SettlementRoundingPgTest-on-b5cd1d0*`) — 기존 시험 결함, 고치지 않음 |

  - 돌리지 않은 PG 시험: N13 2클래스·AB_CHAIN 2클래스·V96_PG_MIG 1클래스(이번 변경과 무관한 이행·도구 시험).
- 전체 회귀(`9bce7dc1-full/`, 10:38:39~11:02:17, `--rerun --continue`, 끝나자마자 XML 복사): core-engine 298(건너뜀 7)·exchange-adapter 11·strategy-lib 113 전부 통과, **web-api 1273 중 실패 17·건너뜀 42**.
  - 1차 c7137896 의 16건과 **같은 16건이 다시 실패**했다(목록 비교 일치). 모두 LIVE "후보 없음"·빈 목록 계열이다: SharedLoopRoundsActiveTest 1, Sma20LiveAlignedGapsTest 3, Sma20LiveAlignedPaperSameInputTest 6, Sma20LivePaperSameInputTest 6. 이번에는 XML 원문이 남아 있다. 실패 클래스 출력에 `캔들 부족: KRW-BTC H1 0건`·`[캔들 최신성] … 최신 완료 봉 없음` 줄이 있다.
  - 보존 순서(총괄 v1.155 §5 대조): 실행 스크립트가 종료 직후 XML·로그를 `/d/tmp/v153-runs/i154/full/` 로 복사했다(같은 SHA 새 경로). **해시 고정(`i-final/9bce7dc1-full.sha256`, 266파일, 11:07:56)은 진단 실행(11:04 시작) 뒤**였다 — 진단은 작업 사본의 `build/test-results` 만 덮었고, 보존 사본과 근거 사본의 해시가 266/266 같음을 그때 확인했다.
  - **새 실패 1**: `SharedMarketDataExecutorSaturationTest`(0298628b, 시험만) `[대기열 가득] expected: 0 but was: 100` — 포화 단계에서 대기열을 채웠는데 비어 있었다(①, 101행).
  - 진단 1회(`9bce7dc1-diag-S/`, 11:04~11:06): 같은 SHA 에서 `service.S*` 33클래스만 함께 → **180/180**(위 17건의 클래스 포함). 따라서 17건은 S 보다 앞 순서(A~R) 클래스가 남긴 상태에 기대는 것으로 보이나 **원인 미확인**이다. 추정(확인 안 함): 대기열 건은 앞선 포화 시험이 늘려 둔 유휴 작업자(최대 4)가 유지 시간 안에 대기열 작업을 바로 가져간 것, "후보 없음" 건은 앞선 클래스의 캔들 캐시·세션 잔여 상태. 같은 실패를 녹색으로 만들려고 반복 실행하지 않았다.
- 실제 jar(`9bce7dc1-jar/`, **조사용** — 총괄 v1.155 §5 대로 이후 수정 후보의 최종 jar 증거로 쓰지 않는다): 로컬 `bootJar`(rc 0) sha256 `73a9c6bc…69e2`, 기준 b5cd1d0 jar `c704528f…f318`. `jarcmp_v2` 항목 881→891, **추가 10·변경 37·삭제 0**, 거절 0. 추가는 V96 이행 SQL·PaperExecutionMode·PaperMaxInvestment·RealtimeProtectionDispatcher 4종·PaperTradingService 내부 4종, 변경은 `src/main` 차이 18파일(`src-main-diffstat.txt`)의 클래스와 같다. 모듈 jar(core-engine 등) 차이 없음.
- 화면 산출물: 이번 2차 사본에서는 만들지 않았다(G 화면 5f9ef2ad 의 렌더링·tsc 근거는 §3).

## 10. 미승인 D4·복구안 메모 (초안 아님 — 빈칸 목록)

운영 반영은 승인되지 않았다. 9bce7dc1 은 고정 후보가 아니다(전체 회귀 17 실패 원인 미확인). 아래는 실행안을 쓸 때 채워야 할 칸이다.

- 반영 단위: 위 jar 1개 + V96 이행(DDL, 승인 대상). F 2판·손실 합산·G 화면이 함께 들어간다. B01 접점·DYNAMIC 81b0e7a7·main V95 는 빠져 있다.
- 되돌림: 세션 생성 전이면 종전 D4 U되돌림, 생성 뒤면 `h-rollback/v2/rollback_gate.sh` 판정을 먼저 지난다(`LEDGER_REFUSED`·`HOLD_PENDING`·`STOP_*` 는 구판 복구 불가).
- 반영 전에 닫을 것: 전체 회귀 17건 원인, D 시험 고정 자료 정시화 여부, DYNAMIC REAL 손실 포함 여부, LIVE 부분 매도 실현손익 덮어쓰기.
