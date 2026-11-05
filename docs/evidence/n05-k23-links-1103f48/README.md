# N05 — 1103f48 변경 경로와 정지·정산 절차의 연결 검토 (2026-11-05, 로컬)

총괄 v1.116 지시([k23-result-review-28e2183](../k23-result-review-28e2183/README.md) "22시와 독립적으로 계속할 일")에 따른 로컬 검토다.

**범위와 방법**
- 기준 코드는 운영 `1103f48505a1457029f6555547a87aa986535738`이다. 운영 코드 변경분(`fe01207..1103f48`, main 8파일)과 기존 정지·정산 경로를 `git show 1103f48:<파일>`로 대조했다. 아래 줄 번호는 모두 그 판 기준이다.
- 시험·Gradle·bootJar은 실행하지 않았다. 서버·운영 DB·외부 HTTP에도 접속하지 않았다. 운영 코드, 시험, 설정, 절차 블록은 바꾸지 않았다.
- "시험 근거"의 **173**은 같은 25클래스 XML 173/173을 가리킨다. 출처는 [k23-final-1103f48 summary](../k23-final-1103f48/tests/integ25-1103f48/summary.txt)이고 총괄 v1.114가 확인했다. 이미 통과한 시험이므로 다시 돌리지 않았다.
- "이전 판"은 1103f48 이전 후보에서 통과한 시험이다. 1103f48이 그 경로에서 바꾼 줄이 있는지는 아래에 따로 적었다.

## 연결 표

경로는 `web-api/src/main/java/com/cryptoautotrader/api/service/` 아래이며, 파일 이름을 줄였다. 표기는 다음과 같다.

| 줄임말 | 파일 |
|---|---|
| `EPR` | `EntryPriorityRounds` |
| `CRS` | `CapitalReservationService` |
| `OEE` | `OrderExecutionEngine` |
| `LTS` | `LiveTradingService` |
| `DTS` | `DynamicTradingService` |
| `MPB` | `MarkPriceBook` |
| `PLG` | `PoolLossGuard` |
| `HMR` | `HeldMarkRefresher` |

### N15 — 승인 실행 만료

| 번호 | 연결 | 운영 코드 위치(1103f48) | 기존 시험·근거 | 대조 결과 |
|---|---|---|---|---|
| K1 | **만료 전달 → 회차 처리.** E 이후 후보는 콜백 없이 원래 신호에 사유가 남는다 | `EPR:126` `expiry`, `EPR:210-213` 후보마다 시각을 재판정하고 사유를 기록, `EPR:222` 콜백 / `LTS:1369`·`DTS:1260` 만료 기록처 | `N15EntryExpiryTest` ①②③⑥ (173) | 콜백이 없으면 예약·의도·주문 행도 없다. 그래서 정지 절차(F6)와 정산 조건(F5-3)이 볼 새 행이 생기지 않는다. 공백 없음 |
| K2 | **정지된 세션의 회차 후보** | `LTS:1484`·`DTS:880` 승인 차례에 RUNNING을 다시 확인. 만료가 먼저면 K1에서 끝난다 | `EntryPriorityRoundsTest` "승인 차례의 재확인 — 평가 뒤 정지된 세션(LIVE·DYNAMIC)은 진입하지 않는다" (173) | 정지와 만료가 함께 일어나면 신호 사유는 만료 문구가 된다. 두 갈래 모두 주문 0이고, 사유 문구만 다르다. 공백 없음 |
| K3 | **RUNNING을 읽은 뒤 정지가 커밋되는 경합** | `OEE:691` `approveNewEntry`(세션 행 잠금 안 정지 확인)가 먼저, `OEE:697-703` 만료 판정이 그 뒤. 정지면 기존 의도 거절 복원(`OEE:533`) | `IntentPrecommitAndOnceOnlyTest` ② (173). `LateOrderSubmissionAfterStopTest`는 이전 판(25클래스 밖) | 1103f48은 정지 확인 **뒤에** 만료 블록만 더했다. 즉시 진입(`entryExpiresAt` null)은 동작이 같다. 재실행 안 함, 공백 없음 |
| K4 | **예약 거절**(잠금 뒤 / 차감 직전) → 가용·슬롯 | `CRS:196` 잠금 뒤, `CRS:249`·`CRS:279` 차감 직전. 거절이면 아무것도 바꾸지 않는다. LIVE는 호출자 롤백으로 슬롯이 사라진다 | `N15EntryExpiryTest` ④⑤, `N15FinalApprovalBoundaryTest` ⑦a⑦b⑧ (173). 차감·슬롯·의도·거래소 0 | 가용 KRW 차감과 주문 행이 없으므로 장부 공식의 입력이 바뀌지 않는다. 코드 대조이며 장부 SQL을 실행한 것은 아니다. 공백 없음 |
| K5 | **차감 복구**(의도 직전 만료) | `OEE:697-703` 만료 → `OEE:522-528` 같은 트랜잭션에서 `releaseWithinTransaction` → `ENTRY_EXPIRED` | `N15FinalApprovalBoundaryTest` ⑨a⑨b(가용 원복·슬롯·의도·거래소 0), ⑩ 대조군 (173) | 기존 의도 거절 복원(`OEE:533`)과 같은 방식이다. 슬롯이 남지 않으므로 정지 경로의 빈 포지션 정리(`settleEmptyPosition`)와 정리 대기 등록 대상이 생기지 않는다. 공백 없음 |
| K6 | **확정 의도(E 전 커밋) → 전송·정지·정산** | `entryExpiresAt`은 `OrderRequest`에만 있고 저장하지 않는다. 읽는 곳은 `CRS`(예약)와 `OEE:511`·`OEE:697`(의도 전)뿐이며, 의도 커밋 뒤 참조는 0이다 | `N15EntryExpiryTest` ① "확정 주문 그대로", `IntentPrecommitAndOnceOnlyTest` ①~③, `SubmitResponseUnknownTest` (173). `CancelConfirmAndLateFillCleanupTest`는 이전 판 | 커밋된 의도는 기존 전송, 응답 불명 추적, 정지 취소(`LTS:610`·`DTS:627`), 정리 경로를 그대로 탄다. 재시작 뒤에도 만료가 끼어들 자리가 없다. 공백 없음 |
| K7 | **매도·청산에는 만료가 없다** | `entryExpiresAt` 설정은 매수 두 곳뿐이다(`LTS:1618`, `DTS:1743`). 정지·비상정지 청산(`LTS:1781~` `closeSessionPositions`, 새 `OrderRequest` 시장가)과 정리 작업은 null이다 | `StopVersusInFlightExitRaceTest` (173). N05 C3 `LimitedTestPendingSellSettlementPgTest`는 `5bb0544` 브랜치 | 매도·정산 경로에 바뀐 줄이 없어 C3을 재실행하지 않았다. 공백 없음 |

### N16 — 비정상 가격·관측 시각

| 번호 | 연결 | 운영 코드 위치(1103f48) | 기존 시험·근거 | 대조 결과 |
|---|---|---|---|---|
| K8 | **실시간 가격 거절 → 손절 점검 이력** | `LTS:3361`·`DTS:3439` `isPrice` 거절. 위치는 낡음 거절 다음이고, 이력·간격·`recordSlCheck`(`LTS:3406`·`DTS:3466`)보다 앞이다. 처리기는 RUNNING 세션만 돈다(`LTS:3387`·`DTS:3448`) | `N16InvalidPriceReproTest` ①②③(LIVE·DYNAMIC), `StaleRealtimeExitTest` (173) | 0이나 null로는 보호 매도가 나가지 않는다. 다음 정상 가격에서 손절은 1회다. 비상정지·정지 세션은 이 처리기 대상이 아니다. 공백 없음 |
| K9 | **가격 거절 뒤 SL 감시기(§9)** | 점검 시각이 바뀌지 않으면 `LTS:2679` `warnStaleSlCheck`가 경보를 보내고 `forceRefreshPrice`(`LTS:2723`·`DTS:3626`)를 부른다. 결과 분류는 `isStale`만 본다(`LTS:2736`·`DTS:3639-3640`) | `SlWatchdogFreshnessTest`, `LiveTradingSlWatchdogRecoveryTest`, `DynamicTradingSlWatchdogRecoveryTest` (173). REST 체결가 0·음수 경우의 시험은 없다 | **관찰(아래)**. 보호 동작(매도 0, 점검 기록 없음, 다음 주기 재경보)은 맞다 |
| K10 | **관측 시각 보정 → 시세 장부·평가·풀 판정** | `MPB:129-141` `observe`가 `min(관측, 수신)`을 쓰고 `MPB:150`(호가)도 같다. `MPB:199` `freshPrice`. 소비처는 `PLG:356`(신규 진입 판정), `LTS:3530` `exitsWithoutCandles`, `HMR:110` | `N16InvalidPriceReproTest` ④a~④e·⑤, `CandleGapExitTest`, `PoolLossLimitTest` "대체 가격 … 풀 전체 신규 진입을 막고" (173) | 미래 값은 120초 뒤 낡은 값이 되어 풀 판정 불가가 되고, **신규 진입만** 막는다(더 엄격한 쪽). 시간 청산은 가격 없이 나간다(`CandleGapExitTest`). 공백 없음 |
| K11 | **정지·비상정지 청산 가격** | `LTS:1781~` 시장가 매도에 가격 인자가 없다. 정산은 체결 응답의 반영값을 쓴다 | `StopVersusInFlightExitRaceTest` (173), N05 C3(이전 판) | N16 변경은 청산 수량·가격·정산에 들어가지 않는다. 공백 없음 |

## K9 관찰 — 경보 문구 분류 시험 공백 (보호 공백 아님)

- **현상(코드 대조)**: `forceRefreshPrice`는 REST `trade_price`가 0이나 음수여도 관측 시각이 신선하면 `PUBLISHED`로 분류한다. 이벤트는 처리기에서 N16 규칙으로 버려진다. 그래서 경보는 "SL 점검 이벤트를 발행했습니다 — 실제 점검 기록은 다음 주기에 확인하며, 그때도 없으면 다시 알립니다"가 되고, 다음 주기에 같은 경보가 다시 나간다.
- **영향**:
  - 매도 0, 점검 시각 없음, 재경보가 유지되므로 보호에는 공백이 없다.
  - N02 기준("`PUBLISHED` 알림만으로 매도 유무를 말하지 않는다")에 따라 이 문구를 점검 완료로 읽지 않는다.
  - 운영 0 체결가의 출처로 확인된 것은 웹소켓 ticker 파서다(N16 ③). REST ticker가 0을 준 사례는 조사하지 않았다.
- **판단 필요**: 국소 재현을 하지 않았고, 수정 후보도 만들지 않았다.
  - 지시의 "구체적으로 비어 있는 연결"은 보호 경로 기준으로 읽었다. 이 항목은 경보 문구 분류에 대한 시험 공백이다.
  - 필요하면 다음 두 가지를 할 수 있다.
    1. 단위 시험 1건으로 재현한다: REST 0 → `PUBLISHED` 분류 → 처리기 거절 → 다음 주기 재경보.
    2. 최소 후보를 낸다: `isPrice`가 아니면 `PUBLISHED`가 아닌 결과로 분류한다(LIVE·DYNAMIC 같은 규칙).

## 결론과 한계

- 11개 연결에서 정지·정산 절차와 만나는 지점은 모두 기존 시험 이름과 코드 위치로 짝지었다. 보호 경로의 새 공백은 찾지 못했다. 경보 문구 분류에 대한 시험 공백 1건(K9)은 총괄 판단으로 남긴다.
- 이 표는 코드·시험 대조다.
  - 전체 리허설이 끝났다는 뜻이 아니다.
  - 새 결함이 없다고 확정한 것도 아니다.
  - 운영에서 보호 동작을 관측한 것도 아니다(N15·N16 자연 발생은 미관측).
- 이전 판 근거(`LateOrderSubmissionAfterStopTest`, `CancelConfirmAndLateFillCleanupTest`, N05 C3)는 1103f48에서 다시 돌리지 않았다. 이 시험들이 지나는 경로에서 1103f48이 바꾼 것은 K3의 정지 확인 뒤 만료 블록뿐이고, 즉시 진입에서는 동작하지 않는다.
- 운영 변경, 새 시간·위험 정책, 전략 조정은 하지 않았다.
- 실제 작업 시간: 16:47~16:53 KST(기록 커밋 전까지).
