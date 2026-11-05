# N15 최종 승인 경계 보완 — 차감 직전·의도 저장 직전 만료 재판정 (총괄 v1.109 §77, 11-05 오후)

- 기점: N15 후보 `6f6a522`(부모 운영 `fe01207`). 작업 사본 `D:\Claude Code\projects\cat-n15`.
- **후보 `d6f7e18924bbef785c5628200a8101a807e4948a`**(브랜치 `candidate/n15-entry-delay-fe01207`, 원격 반영). 부모는 6f6a522이다. 운영 코드 2파일 +69/−10(`diff-6f6a522-d6f7e18-main.txt`)과 새 시험 1파일을 바꿨다. fe01207 대비 누적은 운영 코드 7파일 +212/−18(`diff-fe01207-d6f7e18-main.txt`)이고, V95·설정·의존성 변경은 없다.
- 시간(KST):
  - 착수 14:11:27, 소스 대조 ~14:14
  - 수정 전 1차 14:15:15~14:15:37(LIVE·DYNAMIC 묶음 시험, 아래 3절), 수정 전 2차 14:16:04~14:16:24(5/6 실패, 대조군 통과)
  - 수정 후 N15 두 클래스 14:16:30~14:17:01(12/12)
  - 관련 16클래스 1차 14:17:14~14:18:34(98/99, 아래 3절), DailySma20 단독 14:19:09~14:19:35(24/24), 관련 16클래스 2차 14:19:41~14:20:50(99/99)
  - 커밋·푸시 14:21:00
- 운영 서버·DB·외부 출처 조회와 배포·재빌드는 없었다.

## 1. 변경 (`diff-6f6a522-d6f7e18-main.txt`)

- `CapitalReservationService.entryExpiry(E, stage)`를 추가했다. 회차 처리와 같은 `EntryClock`으로 E 이상이면 `ENTRY_EXPIRED` 거절을 돌려준다. 사유 문구는 `승인 실행 만료(만료 …, <위치> 판정 …) — 새 예약·매수 의도 없음`이다.
- `reserveForBuy`는 두 번 판정한다.
  - 계좌 잠금 직후: 6f6a522와 같다. 빠른 거절용이다.
  - **차감 바로 앞(최종)**: 등록 풀·손실 한도·포지션 한도·판정 불가·계좌 여력 조회를 모두 지난 뒤 판정한다. LIVE는 세션 조회·가용액 확인 뒤, DYNAMIC은 `em.refresh(PESSIMISTIC_WRITE)` 세션 행 잠금과 가용액 확인 뒤다. 거절이면 아무것도 바꾸지 않았다.
- `OrderExecutionEngine.prepareIntent`는 `approveNewEntry` 뒤, **의도 저장 바로 앞**에서 같은 판정을 한다. `approveNewEntry`는 회계 미반영·미해결 제출·위험 설정 조회와 세션 행 잠금을 한다.
  - 만료면 주문 식별자·의도 행·dispatch 소유권을 만들지 않고 내부 예외 `IntentExpired`를 던진다.
  - `reserveAndPrepareIntent`가 이를 잡아 기존 `INTENT_REJECTED`처럼 **같은 트랜잭션에서** `releaseWithinTransaction`으로 차감을 되돌린다. 그 다음 `ReservationRejected(ENTRY_EXPIRED)`를 던진다.
  - LIVE는 기존대로 예약 트랜잭션 전체가 롤백된다. 슬롯이 없고, 차감 복원도 함께 사라진다.
  - DYNAMIC은 틱 트랜잭션을 이어간다. 기존 처리기가 슬롯을 `CLOSED`로 닫고 원래 신호에 `공유 풀 예약 거절(ENTRY_EXPIRED): …`를 남긴다. 차감은 위 복원으로 지워진다.
- 바꾸지 않은 것:
  - 잠금 순서·트랜잭션 범위
  - 외부 조회 위치(잠금 안에 새 조회 없음)
  - 후보별 콜백 직전 검사
  - 이미 커밋된 주문의 claim·전송·정산 경로
- **최종 승인의 의미**(주석 명시): 애플리케이션이 필요한 잠금·조회를 모두 지난 뒤 하는 마지막 만료 판정이다. 그 뒤 DB 커밋이 물리적으로 끝나는 시각까지 E 미만을 보장하는 기능은 아니다. 대기 가능한 조회·잠금이 판정 뒤에 남아 E 이후 새 예약·의도를 승인하는 경로를 없앤다.
- 의도 저장 뒤 DYNAMIC의 세션 상태 갱신 refresh(`POSITION_MONITORING`)는 의도·차감이 이미 확정된 뒤의 기록이라서 검사를 더하지 않았다. 같은 트랜잭션이 이미 그 행을 잠그고 있어 다른 트랜잭션을 기다리지도 않는다.
- 사유 형식(총괄 결정): 기존 두 문구는 유지한다. 새 경계도 `예약 거절(ENTRY_EXPIRED): 승인 실행 만료(…)`(LIVE)와 `공유 풀 예약 거절(ENTRY_EXPIRED): 승인 실행 만료(…)`(DYNAMIC)로 같은 형식이다. 분석에서는 "승인 실행 만료" 한 범주로 묶는다.
- `prepareIntent`의 다른 호출 경로(즉시 `submitOrder`)는 `entryExpiresAt=null`이라서 이 판정을 하지 않는다. 값이 들어오더라도 예외로 트랜잭션이 롤백되어 의도가 남지 않는다.

## 2. 시험 (`tests/N15FinalApprovalBoundaryTest.java`, 6건)

실제 예약·의도 경로에서 확인했다. 진입은 `rounds.processDue` → 엔진 승인 → `reserveAndPrepareIntent` 순서로 들어간다. 대기는 같은 가짜 시계로 만들었고, 갈고리는 호출 스택이 그 지점일 때만 시계를 E로 움직인다(각 1회 호출 확인).

| 사례 | 확인 | 수정 전 6f6a522 (`before/`) | 수정 후 d6f7e18 (`after/`) |
|---|---|---|---|
| ⑦a (a) LIVE | 잠금 직후 E−1ms, `PoolLossGuard.checkForEntry` 중 E | **진입, 가용 20,000→15,000, 의도 1, 거래소 1** | `차감 직전` ENTRY_EXPIRED, 가용 20,000, 포지션 없음, 의도 0, 거래소 0, 신호 사유 |
| ⑦b (a) DYNAMIC | 같은 지점 | **진입, 가용 10,000→2,000, 의도 1** | `차감 직전` ENTRY_EXPIRED, 가용 10,000, 포지션 CLOSED/0(열린 슬롯 0), 의도 0, 거래소 0, 신호 사유 |
| ⑧ (b) DYNAMIC | 다른 트랜잭션이 `dynamic_session` 행을 `FOR UPDATE`로 잡은 동안 예약의 `refresh`가 실제로 기다림(H2 `waitForThisToEnd` 스택 관측), 그 사이 E | **진입, 10,000→2,000, 의도 1** | `차감 직전` ENTRY_EXPIRED, 가용 10,000, 열린 슬롯 0, 의도 0, 거래소 0 |
| ⑨a (c) LIVE | 차감 뒤 `approveNewEntry`의 위험 설정 조회 중 E | **진입, 15,000, 의도 1** | `의도 직전` ENTRY_EXPIRED, 가용 20,000(롤백), 포지션 없음, 의도(모든 상태) 0 |
| ⑨b (c) DYNAMIC | 같은 지점 — **차감이 실제로 일어난 뒤** | **진입, 2,000, 의도 1** | `의도 직전` ENTRY_EXPIRED, **가용 10,000으로 정확히 복원**, 포지션 CLOSED/0, 의도 0, 거래소 0 |
| ⑩ 대조군 | 같은 두 갈고리가 불리지만 시계는 E−1ms | 통과: LIVE 15,000·의도 1(예약 5,000), DYNAMIC 2,000·의도 1(예약 8,000) | 같음 |

- 만료 사례는 모두 원래 신호 `was_executed=false`·`blocked_reason`에 `ENTRY_EXPIRED`가 남고, 회차가 닫히고, 재대기가 없다.
- 기존 `N15EntryExpiryTest` 6건(정확히 E·E+1ms 만료, D+1ms·E−1ms 진입, 확정 주문 E+10분 뒤 철회·복원 없음, 앞 후보 지연, 자기 관문, 계좌 잠금 대기, 정의)도 수정 후 6/6이다.
- 수정 전: 6건 중 5건 실패(대조군만 통과). 원문 `before/TEST-…N15FinalApprovalBoundaryTest.xml`, `before/gradle-before.log`, 요약 `before/summary.txt`.
- 수정 후: 관련 16클래스 99/99 = 6f6a522 때 15클래스 93 + 새 6. 원문 `after/TEST-*.xml` 16개, `after/gradle-after.log`, `after/summary.txt`.
- 한계:
  - H2의 행 잠금으로 PostgreSQL 잠금 동작 전체를 검증한 것은 아니다. ⑧은 "refresh가 기다리는 동안 E를 지나면 차감 전에 걸린다"는 순서만 확인한다. 운영 DB·22시 N03은 재현에 쓰지 않았다.
  - ⑨는 실제 잠금 대기가 아니라 의도 승인 안의 조회 지점에 건 갈고리다. 예약 뒤에는 같은 트랜잭션이 세션 행을 이미 잡고 있어, `lockAndReadStatus`가 다른 트랜잭션을 기다리지 않는다(LIVE는 save, DYNAMIC은 refresh로 이미 잡음). 그래서 이 구간의 대기 가능 지점은 조회다.
  - 비동기 제출 대역(`UpbitOrderClient` 미지정 응답)의 `exchangeResult is null` 로그는 기존과 같은 공개된 시험 한계다. 대조군은 의도·예약·슬롯까지만 단언하고 거래소 결과를 단언하지 않는다. 기대값을 바꿔 숨긴 것은 없다.
- 실행 명령(작업 사본, `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 …`):
  - 수정 전: `./gradlew :web-api:test --tests "com.cryptoautotrader.api.service.N15FinalApprovalBoundaryTest" --offline -i`
  - 수정 후 관련: 위 명령에 아래 15개를 `--tests com.cryptoautotrader.api.service.<이름>`으로 더했다.
    - `BtcGateSupplyEndToEndTest`, `BtcGateUnknownEntryTest`
    - `CapitalPoolLimitTest`, `CapitalPoolRegistrationTest`, `ConcurrentEntryCapTest`
    - `DailySma20LiveOrderPathTest`, `DynamicPaperLiveRuleParityTest`, `DynamicPoolReservationTest`, `DynamicUnlockedCapitalTest`
    - `EntryPriorityEndToEndTest`, `EntryPriorityRoundsTest`
    - `IntentPrecommitAndOnceOnlyTest`, `N15EntryExpiryTest`, `SessionCapitalGuardTest`, `SubmitResponseUnknownTest`

## 3. 실패 원문 (`fail-runs/`)

- `before-run1-combined-cases.log`(수정 전 1차, 3/4 실패): 첫 시험 설계는 ⑦·⑨에서 LIVE와 DYNAMIC을 한 시험 안에서 차례로 돌렸다. LIVE가 먼저 실패해 DYNAMIC 사례가 실행되지 않았다. 결함 판정 자체는 2차와 같다(⑦ LIVE·⑧·⑨ LIVE 진입). LIVE·DYNAMIC을 별도 시험(⑦a/⑦b, ⑨a/⑨b)으로 나눠 2차에서 각각 실패를 확인했다. 운영 코드는 그 사이에 바꾸지 않았다.
- `after-run1-dailysma20-roundkey-collision.log`, `after-run1-TEST-DailySma20LiveOrderPathTest.xml`(수정 후 관련 1차, 98/99):
  - 실패: `DailySma20LiveOrderPathTest` "통합 ② 부분 체결 뒤 잔량 취소", 원문 `접수 거절=회차 마감 뒤 도착(H1 2026-11-05T04:16:39Z)`.
  - 원인: 그 시험 클래스는 회차 키를 `now−61분`(초 단위)에서 1초씩 내려가며 쓴다. 같은 JVM에서 먼저 돈 다른 클래스가 14:17:39 KST 무렵 같은 초 단위 키(04:16:39Z)의 회차를 이미 처리해 닫았다. 6f6a522 제출 때 기록한 4차 실행 설계 실수와 같은 종류의 시험 간 회차 키 충돌이다.
  - 처리: 이번 변경은 `entryExpiresAt`가 있는 경로의 만료 판정만 더하므로 회차 접수와는 관계가 없다. 그 클래스를 단독으로 돌려 24/24(`after-dailysma20-alone/`)를 확인했고, 같은 16클래스를 다시 돌려 99/99를 확인했다.
  - 다른 시험 클래스의 회차 키 방식은 범위 밖이라 고치지 않았다. 통합 단계에서도 같은 순서 의존이 다시 나올 수 있다.

## 4. 남은 것

- 운영 fe01207에는 N15 결함이 그대로 있다. 이 후보는 배포하지 않았다.
- 다음 단계(총괄 v1.109 3절, 착수 확인 없이 진행): fe01207 기점 별도 통합 초안(N16 ac29ecf + N15 d6f7e18), 관련 시험, 실제 로컬 bootJar 대조, 개입 #23 배포·복구 초안. 결과는 별도 근거로 제출한다.

## 5. 파일

| 파일 | 내용 |
|---|---|
| `candidate-sha.txt` | 후보 SHA·부모·제목, 6f6a522·fe01207 대비 변경 통계·경로 |
| `diff-6f6a522-d6f7e18-main.txt` / `diff-fe01207-d6f7e18-main.txt` | 운영 코드 diff(이번 보완 / 누적) |
| `tests/N15FinalApprovalBoundaryTest.java` | 후보 커밋의 시험 원본 사본 |
| `before/` | 수정 전 XML·gradle 원문·`[N15F]` 출력 요약 |
| `after/` | 수정 후 16클래스 XML·gradle 원문·요약 |
| `after-dailysma20-alone/` | DailySma20 단독 재실행 XML·gradle 원문 |
| `fail-runs/` | 시험 설계 1회·시험 간 회차 키 충돌 1회 원문 |
