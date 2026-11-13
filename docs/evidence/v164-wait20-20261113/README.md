# v1.164 §3-1·2 — 완료 대기 조건 대상 정리·고정 재현·시험 보완 (2026-11-13 14:40~15:20 KST, 개발 세션, 총괄 판정 전)

총괄 [v1.164 / 408차](../review-960828e7/README.md) §3-1·§3-2를 따른 결과다. 고정 후보 **88f06345** 위에 독립 가지 `candidate/wait20-gate-88f06345`(작업 사본 `/d/tmp/w20-88f06345`)를 만들었다. **시험 코드만** 바꿨고, 제품 코드·운영 설정·build.gradle·운영 서버·DB는 건드리지 않았다.

| 커밋 | 내용 |
|---|---|
| `032a1788` | 고정 재현 도구 `DrainGapGate`(시험 지원, 환경 변수가 있을 때만 켜짐) |
| `7d1d00ae` | 재현된 14클래스의 옛 대기를 실행기 장벽으로 교체(대기 함수 본문만) |

## 1. 대상 수 정리 — 20 · 19 · 실제 18

- **v156 목록(20)**과 **v158 정적표(19행)**의 합집합은 **21**이다. 대응은 [`wait20-map.tsv`](wait20-map.tsv)에 있다.
  - v158은 v156에서 `ExecutorBarrier`(장벽 구현)와 `OrderExecutorDrainPredicateGapTest`(경계를 보이는 시험)를 빼고 `O1O3LogContractTest`를 더했다.
- 88f06345에서 옛 조건(`대기열 비었음·active 0·taskCount == completedTaskCount`)을 **대기로 쓰는 시험은 18클래스**다(소스 전수 검색).
  - `SubmitOrderBoundaryProbeTest`는 4a4a04a3에서 결과 커밋 latch로 바뀌어 빠졌다.
  - 그 밖에 그 조건이 나오는 곳은 반례 시험 두 개(`OrderExecutorDrainPredicateGapTest`·`ExecutorBarrierTest`)뿐이다.
- 18클래스 호출 위치는 87곳이다: 정리 단계(@AfterEach) 20, 시험 본문·보조 함수 67.
- 기존 보완:
  - O1O3 `84fb03d6`, N16 `02d14061`: 각각 전체 회귀에서 1회 관측돼 시험을 보완했다.
  - DynamicStopRace는 `658ba4af` 계열에서 Future 대기로 바꿨다.
  - 그 밖의 15클래스에는 전체 회귀 실패 기록이 없었다. 과거 DailySma20 실패는 회차 키 충돌이었다(k23-prep).

## 2. 고정 재현 방법 — `DrainGapGate`

- `DRAIN_GAP_GATE_MS` 환경 변수가 있으면 `orderExecutor`·`marketDataExecutor` 빈을 **같은 설정**의 실행기로 바꾼다.
  - 같은 설정: 코어2·최대4·대기열 50/100·이름·AbortPolicy. 각 컨텍스트 로그 `[DrainGapGate] … 교체`, 10/10 컨텍스트에서 두 실행기를 확인했다.
  - 바뀐 실행기의 작업자는 **대기열에서 작업을 꺼낸 뒤 실행 표시 전에** 지정 시간만큼 머문다. 그동안 옛 조건은 참이고 작업은 아직 돌지 않았다.
  - 코어 작업자를 미리 띄워, 전체 실행에서 앞 클래스가 작업자를 만들어 둔 상태를 재현한다. v156 `O1O3DrainGapDiagTest`의 붙잡기를 일반화한 것이다.
- 변수가 없으면 공장이 `null`을 돌려준다. 컨텍스트·캐시 키·실행기는 종전과 같다(`spring.factories` 등록).
- 시험 JVM은 환경을 상속한다(TELEGRAM_* 만 제거). 그래서 build.gradle은 바꾸지 않았다.
- 실행 틀: [`run.sh`](run.sh)는 같은 18클래스를 돌리고, 시각·HEAD·작업 상태·종료값·XML을 보존하며, 기존 출력은 덮어쓰지 않는다.

## 3. 결과

| 실행(`runs/`) | 코드 | 게이트 | 결과(222건, PG 전용 N15 건너뜀4 포함) |
|---|---|---|---|
| `gate500-r1` 14:45~14:49 | 88f06345 그대로 | 500ms | **실패 42 / 14클래스**. 모두 작업 결과를 작업 전에 읽었다: 주문 0행, `INTENT_READY`(SUBMITTED 기대), 매도 0, 점검 시각 그대로, createOrder 1회(2회 기대) |
| `gate500-barrier-r1` 14:54~14:58 | 14클래스 대기 → 장벽 | 500ms | 실패 3. SlWatchdog DYNAMIC 1건, StopVersus (다) 2건 |
| `gate50-barrier-2cls` | 같음 | 50ms | SlWatchdog 4/4 통과 → 앞의 1건은 게이트 지연이 쌓인 부산물이다(문구 "121초 전" 기대, 실제 127초). (다) 2건 실패 |
| `nogate-barrier-r1` | 같음(전부 장벽) | 없음 | 실패 2 = (다). 장벽이 (다) 정지 목 응답 안 대기에서 다른 결과를 냄 |
| `nogate-orig-stopversus` | StopVersus 원본 | 없음 | 7/7 |
| `exp-stopversus-L423old` | (다) 목 응답 안 시세 대기 1곳만 종전 | 없음 | 7/7 |
| `orig-gate50-stopversus` | StopVersus **원본** | 50ms | **(다) 2건 실패, 같은 증상** |
| **`final-nogate`** 15:08~15:10 | 최종(커밋과 주석만 다름) | 없음 | **222 중 실패0·건너뜀4** |
| **`final-gate50`** 15:10~15:13 | 최종 | 50ms | 실패 2 = (다)뿐(원본에서도 같음) |
| `final2-nogate-stopversus` | 커밋 7d1d00ae와 같은 StopVersus | 없음 | 7/7 |

- **분류**(클래스별 대상 작업·직후 단언·결과는 [`wait20-map.tsv`](wait20-map.tsv)):
  - **경쟁 확인 → 보완(14)**: CancelConfirm·ClaimAndUnknown·DailySma20·ExchangeRecoveryRestart·IntentPrecommit·LimitedTestExchangeRecovery·LiveEmergencyStop·LiveStop·N17·N19·SlWatchdog·StaleRealtimeExit·StopVersus·SubmitResponseUnknown.
  - **미변경(4)**:
    - LateOrderSubmission: 음성 단언이다. 게이트에서도 통과하며, 공백은 거짓 통과로만 이어질 수 있다.
    - N16·O1O3: 이미 보완했다.
    - N15Pg: 정리 전용이고 PG 전용이라 H2에서 돌지 않는다.
- **보완 방식**: 대기 함수 본문만 기존 `ExecutorBarrier.awaitPriorTasks(같은 실행기, 종전 한도 15~20초)`로 바꿨다(`fa4363f6`의 확정 신호, 시간 초과는 실패).
  - 호출 위치·기대값·단언·sleep은 그대로다. 치환 스크립트는 [`patch_barrier.py`](patch_barrier.py)다.
  - 두 실행기를 거치는 경로(시세 → 주문)는 기존처럼 시세 → 주문 순서로 기다리므로 장벽 두 번이 차례로 닫힌다.
  - 전부 교체가 아니라 게이트로 실패가 재현된 클래스만 바꿨다.
- 과거 60초 지연의 원인은 이 작업으로 확정하지 않는다. 미확정으로 남긴다.

## 4. 미확인 관측 — 정지 진행 중 WS 보호 매도 의도가 제출되지 않음 (원인 미확인, N05 연결 대상)

`StopVersusInFlightExitRaceTest` (다)의 재현 조건:
- 정지 트랜잭션이 마지막 등록 조회(목 응답) 안에서 멈춘다.
- 그동안 WS 손절이 두 번째 매도를 낸다.

다음 두 조건 중 하나에서 두 번째 매도가 20초 동안 접수되지 않았다(LIVE·DYNAMIC 각 1건, 이 조건의 실행 8회 모두 같음):
- 그 자리에서 시세 작업 완료를 장벽으로 기다린다.
- 대기는 종전대로 두고 실행기 시작만 50ms 늦춘다.

임시 계측(커밋 안 함, `diag-stopversus-dump*`):
- 두 번째 행은 **`실시간 손절` 사유의 SELL `INTENT_READY`**다. 시세 작업자가 커밋했고(LIVE 15:07:46.178 생성), 시험 스레드는 정지 트랜잭션 안에서 그 행을 읽었다.
- 실패 시점의 스레드 상태: 주문·시세 작업자 4개가 모두 `LinkedBlockingQueue.take`로 놀고 있었다.
- 그 주문에 대한 claim·철회·디스패치 실패 기록이 없다.

판단의 한계:
- 커밋 뒤 제출(`dispatchPreparedIntent` → 호출자 트랜잭션이 있으면 그 커밋 뒤 `submitPreparedOrder`)이 실행되지 않은 것으로 보인다. 다만 어느 트랜잭션 경계에서 빠졌는지는 확인하지 않았다.
- `DISPATCHING`에 남아 있으면 같은 프로세스의 복구가 소유자 생존으로 보고 철회하지 않을 수 있다(`OEE:2022`). 그러나 이 경로가 실제로 걸리는지는 확인하지 않았다.
- 원본 시점(지연 없음)에서는 통과한다. 따라서 이 관측은 **제품 결함으로 확정하지 않는다.** 시험 끼어들기가 운영에서 같은 시점을 만들 수 있는지도 미확인이다.
- 운영 보호 경로와 맞닿아 있으므로 N05 연결표의 잔여 공백으로 올리고, 다음 독립 검토 대상으로 둔다.

## 5. 한계와 다음

- 게이트는 공백의 최악 경우(머묾 50~500ms)를 강제한다. 실제 전체 실행의 발생 빈도를 재지 않았다. 보완 전 실패 42건은 "그 창이 열리면 틀리게 읽는 위치"의 목록이다.
- 전체 회귀·관련 확대·PG 실행은 하지 않았다(지시: 반복 회귀로 시간을 메우지 않음). 통합 후보로 넣을 때 관련·전체 회귀에 포함한다. N15Pg는 PG에서만 확인된다.
- 체크섬: [`SHA256SUMS`](SHA256SUMS). 패치 원문: [`patches/`](patches/).
