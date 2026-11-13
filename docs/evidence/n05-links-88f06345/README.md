# N05 — 후보 88f06345의 새 경로와 정지·비상정지·정산 연결표 (2026-11-13 15:25~15:45 KST, 로컬, 총괄 판정 전)

총괄 [v1.164 §3-3](../review-960828e7/README.md)에 따라 기존 연결 검토 [K1~K11](../n05-k23-links-1103f48/README.md)·[K12~K13](../w2-n05-links-1899b49/README.md)을 재사용했다. 그 뒤 추가된 경로만 고정 후보 **88f06345**의 코드 위치에 연결했다. 운영 서버·DB는 조회하지 않았다. **최종 운영판 확인(11-23~27)은 배포 뒤에 한다.**

약어: `OEE`=OrderExecutionEngine, `LTS`=LiveTradingService, `DTS`=DynamicTradingService, `RPD`=RealtimeProtectionDispatcher, `RMS`=RiskManagementService. 줄 번호는 88f06345 기준이다.

## 1. 연결표 (88f06345에서 새로 연결)

| 번호 | 경로 | 코드 위치 | 정지·비상정지·정산과의 연결 | 기존 시험·검증 SHA |
|---|---|---|---|---|
| K14 | **청산 대기·매도 의도 한 트랜잭션** | `OEE:597` `markClosingWithSellIntent`: `markClosingIfOpen`과 `prepareIntent`를 한 `submitTx`로 처리 | 호출 6곳 모두 이 경로다. 실시간·전략 매도 `LTS:1757`·`DTS:2020`, 정지 청산 `LTS:1822`·`DTS:2558`, 정리 작업 `LTS:2142`·`DTS:2753`. 정지 청산과 WS 매도가 겹치면 조건부 CLOSING 전환(`markClosingIfOpen`)이 한쪽만 통과시키고, 공유 자금 종류의 매도는 포지션 잠금(`lockSellSubmission`)·미반영 매도 수로 다시 막는다 | `ClosingIntentAtomicityPgTest` 5/5·`ClosingIntentRollbackPgTest` 2/2(`e0469187`), 88f06345 관련138/전체1,698 |
| K15 | **커밋 뒤 제출** | `OEE:dispatchPreparedIntent`: 호출자 트랜잭션이 있으면 `submitPreparedOrderAfterCommit`(`OEE:563`, afterCommit), 없으면 즉시 `submitPreparedOrder`(`OEE:664`, @Async) → `claimOwned` → `claimForSubmission` | 정지 뒤 BUY는 claim에서 RUNNING을 다시 확인하고 철회한다(`WITHDRAWN_STOPPED`). SELL은 막지 않는다(청산 우선). 실행기가 받지 못하면 `WITHDRAWN_DISPATCH_LOST` → 청산 확인이 OPEN으로 되돌린다 | `SchedulerProfileCleanupTest` 주문 흐름 관측(`88f06345`), `DynamicStopRaceTest`, `LateOrderSubmissionAfterStopTest`, [409차](../v164-wait20-20261113/README.md)에서 보완한 14클래스 |
| K16 | **재기동·작업 유실 복원** | `OEE:1847` `pollActiveOrders`(5초) → `OEE:1964` `resolveUnresolvedSubmission`. `INTENT_READY`는 소유 프로세스가 다르면 `WITHDRAWN_OWNER_LOST`, 같은데 `DISPATCHING`에 없으면 `WITHDRAWN_DISPATCH_LOST`로 철회. `DISPATCHING`에 있으면 철회하지 않고 경보만(`OEE:2022`) | 철회된 매도 → `LTS:2833`·`DTS:2835` 청산 확인(5초)이 OPEN으로 → 정지 세션이면 정리 작업(`LTS:2035`·`DTS:2646`, 10초)이 다시 판다. 시작 시 대조 `LTS:2469`·`DTS:404` | `CancelConfirmAndLateFillCleanupTest` D3·D3b(재시작), `ExchangeRecoveryRestartTest`, `LimitedTestExchangeRecoveryTest`, `IntentPrecommitAndOnceOnlyTest`(409차 보완 포함) |
| K17 | **시세 전달·보호선 첫 통과** | `RPD:76` `onPrice` → 코인별 순서 → `marketDataExecutor`. 포화 시 `RPD:85` `retryPending`. 처리기 `LTS:3393`·`DTS:3464`. 점검 간격 안이라도 보호선을 처음 넘은 유효 시세는 한 번 점검(`a2266114`·`666619ce`) | 보호선 통과 → K14 매도 의도. 정지와 겹치면 K14의 포지션 잠금이 하나만 남긴다(K11과 같은 시장가). 낡은·비가격 시세는 앞에서 버린다(K8~K10, K12~K13) | `RealtimeThrottleCrossingTest`(`90bea7b1`), `RealtimeProtectionSaturationTest`(`510effb8`), `StaleRealtimeExitTest`·N16·N17·N19·O1O3·SlWatchdog(409차 보완 포함) |
| K18 | **실현손익·phantom 확정** | phantom 추정 청산은 앞선 확정 실현손익을 덮지 않고 누적한다(`8f9423f5`). 세션 입금은 같은 트랜잭션 `applyWithin`(`280d7f54`, `SessionBalanceUpdater:89`·`DynamicSessionBalanceUpdater:61`). 감지 횟수 `LTS:2810` | 정지 뒤 남은 포지션의 정산도 같은 확정 경로다. 부분 매도 손익 합은 정산 반올림과 같은 규칙 | `PhantomKeepsConfirmedPnlPgTest`, `PartialSellRealizedPnlPgTest` 7/7, 정산 반올림 16/16 |
| K19 | **실자금 손실 보호식** | `RMS:211` `checkRealLossGuard`: 기존·DYNAMIC REAL·전체 REAL 세 식. 하나라도 한도 초과나 판정 불가면 신규 실자금 매수 거절. 조회 실패는 예외로 중단(`06add627`·`dbe1a7c8`) | 매도·정지·정산은 막지 않는다. 확정 손실은 정산의 `sell_applied_at`으로 창에 들어간다(407차 실제 시각 확인) | `RealLossThreeFormulaPgTest` 8/8, `LiveLossWindowPgTest` 7/7, `RealLossGlobalPgTest` 3/3 |

## 2. 잔여 공백

| 번호 | 내용 | 상태 |
|---|---|---|
| G1 | **정지 진행 중 WS 보호 매도 의도의 미제출**([409차 §4](../v164-wait20-20261113/README.md)) | 원인 미확인. 아래 §3 |
| G2 | 과거 정리 시험의 60초 지연 | 미확정 그대로(88f06345 관측 수용, 원인 미입증) |
| G3 | K15·K16의 운영 시점 | 시험은 스케줄러를 끄고 직접 부른다. 5초·10초 주기의 실제 회복 시간은 운영 배포 뒤 관측 칸(정리 대기·`STALE_INTENT_ALERT` 경보 건수)으로 본다 |
| G4 | 최종 운영판 | 배포 뒤 N05 최종판에서 같은 줄 번호를 다시 대조한다 |

## 3. G1 추가 확인 — `DISPATCHING` 등록 상태

409차 재현 조건 그대로(StopVersus (다), 정지 목 응답 안 시세 대기를 장벽으로) 임시 계측을 한 번 더 했다. 커밋하지 않았고 실행 뒤 작업 사본을 되돌렸다(변경 0). 원문은 [`runs/diag-stopversus-dispatching`](runs/diag-stopversus-dispatching/)이다.

- 남은 두 번째 매도: LIVE id=2, DYNAMIC id=4. 상태는 `INTENT_READY`, 사유는 `실시간 손절`, 소유자는 현재 인스턴스, 의도 커밋 시각이 있다. 그리고 **`dispatching=false`**다.
- 따라서 이 의도는 "살아 있는 작업이 맡고 있다"로 남지 않는다. 운영의 5초 폴러가 돈다면 `WITHDRAWN_DISPATCH_LOST`로 철회되고(K16), 청산 확인 → OPEN → 다음 보호 판정으로 이어지는 기존 회복 경로에 들어갈 것으로 **추정**한다.
- 이 시험은 스케줄러가 꺼져 있어 그 회복을 관측하지 않았다. 회복까지 걸리는 시간(폴러 5초 + 청산 확인 5초 + 다음 시세)도 재지 않았다.
- 등록이 어느 경계에서 빠졌는지는 확인하지 않았다. 후보는 claim 작업의 `finally`, 또는 등록 트랜잭션의 비커밋 완료다. claim 경고·철회·디스패치 실패 기록이 없어 두 후보 모두 확정하지 못했다.
- **제품 결함으로 확정하지 않는다.** 다음 확인 후보:
  1. 같은 재현에서 폴러를 직접 1회 불러, 철회 → OPEN → 재매도가 실제로 이어지는지 본다.
  2. `submitPreparedOrderAfterCommit` 등록과 afterCommit 발화를 계측해 빠지는 경계를 찾는다.

  둘 다 시험 사본에서만 하고, 필요성이 확인되면 진행한다.
