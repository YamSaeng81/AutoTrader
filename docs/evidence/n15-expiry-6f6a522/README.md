# N15 진입 승인 실행 만료 후보 (총괄 v1.106 지시, 11-05 오후)

- 기점: 운영 `fe01207f3f60e97e308925157f84a1bb2da04388`. 작업 사본 `D:\Claude Code\projects\cat-n15`.
- **후보 `6f6a5220f7a19f381fa210a9c4a530e262a4fb04`**(브랜치 `candidate/n15-entry-delay-fe01207`, 원격 반영, 부모 fe01207)
  - 운영 코드 7파일 +152/−17(새 파일 `EntryClock` 포함)과 시험 1파일을 바꿨다.
  - N16 후보와 합치지 않았다. 오전 재현 시험 `N15EntryDelayReproTest`도 이 커밋에 넣지 않았다(작업 사본 밖에 보관).
  - 미배포 V95는 없다.
- 시간(KST): 착수 13:43:02, 정적 대조와 설계 ~13:46, 컴파일 13:47:24, 실행 7회 13:50:22~13:58:46, 커밋·푸시 13:59:05, 정리 ~14:05.
- 운영 서버·DB·외부 출처 조회와 배포·재빌드는 없었다.

## 1. 계약과 구현 (`diff-fe01207-6f6a522-main.txt`)

| 계약(총괄 v1.106) | 구현 |
|---|---|
| T, C=O+T, W=min(T/2,5분), D=C+W, E=D+W를 한 곳에서 계산 | `EntryPriorityRounds.window`·`deadline`(기존 식 그대로, W만 함수로 분리)·`expiry`·`isExpired`(`!now.isBefore(E)`: 경계 E 불허) |
| H1 09:00 마감 → 09:05 수집 완료 → 09:10부터 금지 | ⑥에서 확인. M1·M5는 E = 다음 캔들 마감, M15 이상은 W=5분 |
| 회차 시각 하나를 재사용하지 않고, 주입 가능한 같은 시계로 각 후보 직전 실제 시각을 읽는다 | 새 컴포넌트 `EntryClock`(운영은 시스템 UTC, 시험만 `setForTest`). `processDue`가 후보마다 `clock.now()`로 만료를 본다. 스케줄 `processDueRounds`도 같은 시계를 쓴다 |
| 엔진 관문·계좌 잠금 뒤에도 예약·의도 저장 전에 같은 E를 다시 확인 | `OrderRequest.entryExpiresAt` → `reserveAndPrepareIntent` → `CapitalReservationService.reserveForBuy(…, entryExpiresAt)`가 **잠금 획득 직후, 한도 확인·차감 전**에 같은 시계로 판정한다. 만료면 `ENTRY_EXPIRED`로 거절하고 아무것도 바꾸지 않는다. 기존 거절 경로를 그대로 타서 LIVE는 예약 트랜잭션을 롤백하고, DYNAMIC은 슬롯 포지션을 CLOSED로 닫는다(다른 예약 거절과 같음) |
| 외부 조회를 잠금 안에 넣거나 트랜잭션·잠금을 넓히지 않는다 | 잠금 안에 추가된 것은 시계 읽기와 비교뿐이다. 트랜잭션·잠금 구조는 그대로다 |
| 만료는 원래 신호 `blocked_reason`에 남고, 콜백을 건너뛰어도 기록이 사라지지 않는다 | `Candidate`에 만료 기록처(`expired`)를 더했다. LIVE `saveSignalQuality`, DYNAMIC `updateSignalQuality`로 `was_executed=false`와 `승인 실행 만료(…)`를 남긴다. 잠금 안 만료는 기존 승인 결과 기록으로 `예약 거절(ENTRY_EXPIRED): …`가 남는다 |
| 예약·의도·거래소 호출 0, 회차는 닫힌 채 재대기 없음 | ①~⑤에서 확인. 회차는 기존대로 꺼낼 때 닫히고, 만료 후보를 다시 넣는 경로는 없다 |
| 이미 원자적으로 확정된 주문은 기존 복구·정산 경로를 따른다 | 만료 검사는 예약 전에만 있다. 전송·응답·체결·철회 경로는 바꾸지 않았다. ①에서 E+10분 뒤에도 확정 주문이 철회되지 않고 예약액·세션 가용이 그대로임을 확인했다 |

- 즉시 진입(시험 단계)과 청산은 `entryExpiresAt=null`이라 검사하지 않는다.
- 4인자 `reserveForBuy`, 기존 `approveQueuedEntry`·`executeBuy`, 5인자 `Candidate` 생성자는 남겨 두었다. 이것들은 null 만료로 위임하므로 기존 호출·시험의 동작은 그대로다.
- 바꾸지 않은 것:
  - 접수 방식과 수집 마감 D
  - 우선순위·동순위, 한 회차 한 번 처리
  - 엔진 관문·가격 산식·위험 한도
  - 마이그레이션
- 엔진 동등성: LIVE·DYNAMIC 실자금 매수가 같은 예약 경계(`reserveForBuy`)와 같은 회차 검사를 지난다. 독립 자본 페이퍼는 원래 회차 승인 대상이 아니다.
- DYNAMIC 승인 관문 주석의 전제("승인 마감이 다음 캔들 마감보다 앞선다")는 이제 E ≤ 다음 캔들 마감으로 강제된다.

## 2. 시험 (`tests/N15EntryExpiryTest.java`, 6건)

시험은 만료 판정 시계에 가짜 시계를 연결한다. 회차 처리 인자와 만료 판정이 같은 시각을 보므로, 오전 재현처럼 처리 인자만 미래로 넣던 차이가 없다.

- 엔진 관문(캔들 신선도·BTC)은 실제 시계를 쓴다. 그래서 회차를 실제 현재 근처에 두었다(E ≈ 지금 + 8분).
- 대기는 가짜 시계를 움직여 만들었고, 긴 sleep은 없다.
- 오전의 1.5초 지연 시험은 만료 근거로 쓰지 않았다.
- 수정 전 코드에서도 컴파일되도록 시계 연결과 `expiry` 대조는 반사로 했다.

| 사례 | 수정 전 fe01207 (`before/`) | 수정 후 6f6a522 (`after/`) |
|---|---|---|
| ① LIVE D+1ms·E−1ms·E·E+1ms | **4개 모두 진입·매수 의도 1** | D+1ms·E−1ms 진입. E·E+1ms는 콜백 없이 만료: 의도 0, 슬롯 0, 가용 20000, 거래소 0, 신호 `false`·`승인 실행 만료`, 회차 닫힘, 재처리 없음. 확정 주문 2건은 E+10분 뒤에도 철회 없음·예약액 5000·가용 15000 |
| ② DYNAMIC E−1ms·E | **둘 다 진입** | E−1ms 진입, E 만료(의도 0, 열린 포지션 0, 가용 10000, 거래소 0, 신호 사유) |
| ③ 앞 후보 지연 | **뒤 CRO 진입** | 1위 콜백 중 시계가 E에 닿으면 3위 CRO는 콜백 없이 만료, 주문 0, 신호 사유 |
| ④ 자기 관문 대기 | **LIVE 진입·거래소 호출 1** | 콜백 직전 E−1ms → 관문 안에서 E: LIVE `예약 거절(ENTRY_EXPIRED)`·포지션 0(롤백)·가용 20000, DYNAMIC `공유 풀 예약 거절(ENTRY_EXPIRED)`·열린 포지션 0·가용 10000, 둘 다 거래소 0 |
| ⑤ 계좌 잠금 대기 | **잠금 대기 관측 뒤 진입** | 다른 트랜잭션이 잠금을 쥔 동안 승인 스레드가 `lockAccount`에서 기다리는 것을 관측. 그 사이 E에 닿으면 잠금을 얻은 뒤 `ENTRY_EXPIRED`로 의도·포지션 0, 가용 20000 |
| ⑥ 정의·짧은 봉 | **`expiry` 없음(NoSuchMethodException)** | M1·M5·M15·H1·H4·D1 표 일치, H1 09:00→09:05→09:10, M1·M5 E = 다음 마감. M5 E−1ms 콜백 1, E 콜백 0 |

- 수정 전: 6건 중 6건 실패. 원문은 `before/`(XML·gradle·실패 줄 `failures.txt`·`[N15E]` 요약)에 있다. 확정 시험 파일로, 운영 코드만 fe01207 상태로 되돌려 실행했다.
- 수정 후 관련 15클래스 93/93(`after/`). 영향받은 부분만 골랐다:
  - 회차: `EntryPriorityRoundsTest`(동시 처리·정지·도착 기한 포함), `EntryPriorityEndToEndTest`
  - BTC 관문: `BtcGateUnknownEntryTest`, `BtcGateSupplyEndToEndTest`
  - LIVE 주문 경로: `DailySma20LiveOrderPathTest`
  - 예약 경계: `ConcurrentEntryCapTest`, `DynamicPoolReservationTest`, `CapitalPoolLimitTest`, `CapitalPoolRegistrationTest`, `SessionCapitalGuardTest`, `DynamicUnlockedCapitalTest`
  - 의도·전송·정산: `IntentPrecommitAndOnceOnlyTest`, `SubmitResponseUnknownTest`
  - 엔진 동등성: `DynamicPaperLiveRuleParityTest`
  - 재기동(연결 ⑦)은 바뀐 부분이 없어 다시 돌리지 않았다.
- 실행 명령(작업 사본, 표준 출력 UTF-8):
  - 수정 전: `./gradlew :web-api:test --tests "com.cryptoautotrader.api.service.N15EntryExpiryTest" --offline -i`
  - 수정 후: 위 15클래스를 `--tests`로 지정했다.
- 로그의 `submitPreparedOrder … exchangeResult is null` NPE(수정 전 8회, 수정 후 13회)는 시험 대역 때문이다. 대역 `UpbitOrderClient.createOrder`를 정하지 않아 null을 돌려주고, 비동기 전송이 그 null을 기록하다 난다. 진입이 일어난 횟수만큼 나며, 이번 변경과는 관계가 없다.

## 3. 실행 7회와 실패 원문 (`fail-runs/`)

| 회 | 시각 | 코드 | 결과 | 원인·조치 |
|---|---|---|---|---|
| 1 | 13:50:22~13:50:48 | 수정 전 | 6/6 실패. 그중 ①②③은 접수 단계 실패 | **시험 설계**: 시험들이 1초 안에 돌면서 회차 키(타임프레임·봉 시작)가 앞 시험의 닫힌 회차와 겹쳤다. 시험마다 기준 시각을 5초씩 당겼다. `run1-before-roundkey-within-class.log` |
| 2 | 13:51:21~13:51:44 | 수정 전 | 6/6 실패(모두 기대 지점) | 원문은 7회차와 같은 파일명이라 덮어써져 남지 않았다. 내용은 7회차와 같은 판정이다 |
| 3 | 13:51:56~13:53:10 | 수정 후 + 관련 | 93건 중 2건 실패(①·②의 접수 0) | 처음에는 운영 스케줄러가 같은 가짜 시계(미래)를 읽어 시험 회차를 먼저 처리한 것으로 보고, 가짜 시계를 시험 스레드에만 적용했다. 4회차에도 같은 실패가 나서 이 원인은 확정하지 못했다. 스레드 한정은 간섭 예방으로 남겨 두었다. `run3/` |
| 4 | 13:54:03~13:55:13 | 수정 후 + 관련 | 93건 중 2건 실패(①·②의 다른 코인 접수 0) | **시험 설계**: 같은 JVM에서 먼저 돈 다른 시험 클래스의 닫힌 회차 키(초 단위 `now−61분`)와 겹쳤다. 수정 전 실행은 이 클래스만 돌아서 드러나지 않았다. 기준 시각에 371ms를 더해 초 단위 키와 겹칠 수 없게 했다. `run4/` |
| 5 | 13:55:42~13:56:51 | 수정 후 + 관련 | 93건 중 1건 실패(① 확정 주문 상태) | **시험 단언 실수**: 확정 주문 상태가 `INTENT_READY`→`SUBMITTING`으로 바뀐 것은 비동기 제출 실행기의 정상 진행이다. 단언을 철회 아님·예약액·가용 불변으로 바꿨다. `run5-confirmed-state-assert.log` |
| 6 | 13:57:16~13:58:09 | 수정 후 + 관련 | **93/93** | `after/` |
| 7 | 13:58:20~13:58:46 | 수정 전(확정 시험) | **6/6 실패**(모두 기대 지점) | `before/` |

운영 코드는 3~6회 사이에 바꾸지 않았다. 바꾼 것은 시험 파일뿐이다.

## 4. 남은 것·판단 필요

- 운영 fe01207에는 이 결함이 그대로 있다. 이 후보는 배포하지 않았다.
- N16 후보(`ac29ecf`)와의 통합, 배포·복구 절차, jar 대조 기대값은 두 후보 수용 뒤의 별도 판정 사항이다.
- 계좌 잠금 안 판정은 앱 시계를 쓴다. 운영 PostgreSQL `lock_timeout`(10초) 동안 잠금을 기다린 후보도 잠금을 얻은 뒤 E를 지났으면 거절된다(⑤와 같은 구조). H2 시험은 lock_timeout 동작 자체를 검증하지 않는다(`application-test.yml` 기존 주석).
- 원래 신호의 만료 사유 문자열은 두 가지다. 콜백 전 만료는 `승인 실행 만료(…)`, 잠금 안 만료는 `예약 거절(ENTRY_EXPIRED): 승인 실행 만료(…)`(DYNAMIC은 `공유 풀 예약 거절(…)`)이다. 집계에서 한 가지로 묶어야 하면 접두어를 맞추는 것은 별도 결정이 필요하다(지금은 기존 거절 문구 형식을 따랐다).
- 계획 §2-3 표와 PREREG §5는 총괄 v1.106에서 이미 개정됐다. 이 후보는 그 개정 사양을 구현한 것이며, 문서는 고치지 않았다.

## 5. 파일

| 파일 | 내용 |
|---|---|
| `candidate-sha.txt` | 후보 SHA·부모·제목, fe01207 대비 변경 통계(시험 포함) |
| `diff-fe01207-6f6a522-main.txt` | 운영 코드 diff |
| `tests/N15EntryExpiryTest.java` | 후보 커밋의 시험 원본 사본 |
| `before/` | 수정 전 XML·gradle 원문·요약·실패 줄 |
| `after/` | 수정 후 15클래스 XML·gradle 원문·요약 |
| `fail-runs/` | 1·3·4·5회차 원문(3·4회차는 XML 포함) |
