# 12월 준비 1 — DYNAMIC REAL 운용 경로 연결 (고정 0e1d2a02, 로컬 읽기만)

2026-11-18 KST, [총괄 v1.184](../../DECEMBER_SMALL_LIVE_PLAN.md) §2-1. **운영0e1d2a02의 Git 내용만** 읽었다(`git show 0e1d2a02:<경로>`). 운영 DB/API 조회·빌드·시험 실행·코드 수정은 하지 않았다. 아래 줄 번호는 모두 0e1d2a02 기준이며 `S=web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java`(3,797줄)다.

기존 시험은 [417차 최종 회귀](../v168-final-20261115/README.md)(0e1d2a02 전체1,814=성공1,725·건너뜀89·실패0)에 포함된 것으로 인용만 한다. 같은 입력으로 다시 돌리지 않았다.

## 1. 경로 연결표

| 단계 | 0e1d2a02 코드 | 기존 수용 시험(이름) | 확인한 점 |
|---|---|---|---|
| 생성 | 컨트롤러 `POST /api/v1/dynamic-sessions` → `S:478 createSession` | WalkForwardGate{Disabled,Enabled}SessionCreationTest, StrategyEnablementGateTest | 전략 활성(전략+주기)·REAL은 거버넌스 BLOCKED·WF 관문(운영 `REQUIRE_WALK_FORWARD_GATE` 기본 false) 뒤 `requireAllocation`(S:559) 공유 풀 배정 검사. **`tradingMode`가 정확히 `"PAPER"`가 아니면 REAL**(S:480·525, 엔티티 기본 `REAL`) |
| 시작 | `POST /{id}/start` → `S:604 startSession` → `S:580 requireAllocation` | DynamicPoolReservationTest, CapitalPoolLimitTest | 계좌 동기화 → 계좌 잠금 → 세션 잠금·재조회 → 배정 검사(총 자본) → RUNNING·startedAt. 텔레그램 시작 알림 |
| 주기 | `S:768 tick` 60초 → `S:795 processTick` | DynamicScanTransitionGuardTest, DynamicCircuitBreakerTest | 서킷 브레이커 → 비상 정지(S:806~822). SCANNING인데 OPEN 있으면 감시로 자가복구(S:836~851) |
| 후보 선정 | `S:2178 resolveWatchlist`(공용 유니버스 켜짐이면 S:2220) | DynamicScanSelectionTest, DynamicPaperBuySameTickWatchlistTest | 거래대금 상위 maxCandidateSize → 스프레드·ATR·품질(거래대금 하한·ATR 상한·상승추세·급락 제외, risk_config 우선) → targetWatchSize. **코인 제외 목록 없음** |
| 진입 관문 | `S:961 processScanningTick`, 관문 S:1069~1210 | DynamicBlackSwan*(3), BtcGateUnknownEntryTest, BtcGateSupplyEndToEndTest, CrossSessionExposureTest, DynamicMinimumCandleGuardTest, CandleFreshnessGuardTest | WF → EMA200(감액/차단) → RANGE → 블랙스완 쿨다운/가격 → BLACK_SWAN_GUARD → BTC 급락(판정 불가도 차단) → 손실 쿨다운 → 같은 종류 동일 코인 상한. 차단돼도 신호는 BUY로 기록 |
| 코인 선택 | `S:1249 pickBestBuyCandidate` | DynamicScanSelectionTest | 통과 후보 중 신호 강도 최고 1개. 나머지는 "다른 코인 신호가 더 강함"으로 기록 |
| 회차 승인 | 공유 풀 + 등록 단계면 `entryPriorityRounds.submit`(S:1254~1270) → `S:881 approveQueuedEntry` → `S:912 approvalTimeGate` | EntryPriorityRoundsTest, EntryPriorityEndToEndTest, N15EntryExpiryTest, N15FinalApprovalBoundaryTest, N15PgLockWaitExpiryTest | 등록 단계가 아니면 즉시 `executeBuy`(S:1272). 승인 때 서킷·손실 쿨다운·블랙스완·동일 코인·BTC 판정 가능 여부 재확인 |
| 실자금 손실 관문 | `S:1651~1658 checkRealLossGuard` | RealLossThreeFormulaPgTest, RealLossGlobalPgTest, LiveLossWindowPgTest | 즉시 진입·회차 승인 모두 지남. 세 보호식 중 하나라도 초과/판정 불가면 거절 |
| 금액·예약·제출 | `S:1641 executeBuy`: 요청액=`availableKrw×investRatio×EMA배수`(S:1663) → `reserveAndPrepareIntent`(S:1763) → 같은 트랜잭션 상태 전환 → `submitPreparedOrderAfterCommit`(S:1790) | DynamicBuyExternalSubmissionBoundaryTest, DynamicBuyOuterWriteOrderingTest, DynamicSessionRowLockContentionTest, DynamicBuyCompletesConsistentlyTest, PositionLimitTest, PoolLossLimitTest | 예약 거절이면 size0 포지션 CLOSED·제출0. 커밋 뒤 제출이라 롤백 시 제출0 |
| 보유 감시 | `S:1311 processMonitoringTick`: 추적(S:1415)·TP/SL(S:1440·1467)·시간 초과(S:1479)·전략 SELL(S:1507, 운영 `STRATEGY_SIGNAL_EXIT_ENABLED` 기본 false) | DynamicTakeProfitCapTest, DynamicStopLossWidthTest, ExitRuleCalculatorTest, CandleGapExitTest, EngineParityTest | 손절폭은 ATR 기반과 전략 제안 중 넓은 쪽(오버라이드 세션은 오버라이드) |
| 실시간 보호 | `S:3473 doOnRealtimePriceEvent` → executeSell(S:3525·3540) | RealtimeProtection{Fairness,Saturation}Test, RealtimeThrottleCrossingTest, StaleRealtimeExitTest, WsSubscriptionUnionTest | 60초 틱 전에도 SL/TP. CLOSING 원자 전환으로 이중 매도 방지 |
| 매도 | `S:1994 executeSell` → `markClosingWithSellIntent` → `transitionToScanning` | ClosingIntentAtomicityPgTest, SubmitPollRaceTest, DynamicStopRaceTest, StopVersusInFlightExitRaceTest | KRW 복원은 정산 reconcile에서 |
| 정산·복구 주기 | S:2645 정지 정리(10초), S:2833 CLOSING 정산(5초), S:2923 유령(30초), S:3203 고아 매수(30초), S:3299 잔액(60초), S:3614 SL 감시 지연(60초) | DynamicFillAccountingTest, DynamicGhostPositionTest, DynamicBalanceLeakTest, DynamicUnlockedCapitalTest, ConcurrentFillSettlementTest, PartialSellRealizedPnlPgTest, SettlementRoundingPgTest, DynamicTradingSlWatchdogRecoveryTest, CancelConfirmAndLateFillCleanupTest | 재기동 뒤 블랙스완 쿨다운 복원(S:404). 포지션은 DB에서 다음 틱·SL 감시가 다시 잡음 |
| 정지 | `POST /{id}/stop` → `S:626`: 미종결 매수 취소 → 보유 청산 → STOPPED. 미해결 제출이 남으면 정리 미완료 경보 | DynamicStopOptimisticLockTest, DynamicStopRaceTest, SellQuarantineReleaseTest | 정지는 신규 승인만 막고 이미 claim된 주문 추적은 계속 |
| 비상 정지 | `POST /{id}/emergency-stop` → `S:687` | DynamicCircuitBreakerTest | 서킷 필드를 한 번의 REQUIRES_NEW 갱신에 실음(자기 교착 방지) |

## 2. 고정 SHA에서 찾은 빈틈 (운용안·시작안에서 막아야 할 것)

| # | 빈틈 | 근거 | 영향 | 제안(코드 변경 아님) |
|---|---|---|---|---|
| P1 | **포지션당 상한 초과는 잘라 주지 않고 거절** | 요청액 S:1663, `CapitalReservationService.java:410` `PER_POSITION_CAP` 거절. 상한으로 줄이는 경로는 저장소에 없음(`getPerPositionCapKrw` 사용처 1곳) | STAGE_1(포지션당 20,000)에서 `availableKrw×investRatio`가 20,000을 넘으면 매 진입이 거절된다. 예: 자본25,000·비율0.8은 첫 진입 20,000이지만 **이익이 1원만 나도 다음 진입부터 영구 거절** = 상시 진입 차단 | 시작안에서 `initialCapital×investRatio`를 상한보다 충분히 낮게(이익 여유 포함) 고정하거나, 상한 맞춤 절단을 별도 후보로 제출해 판정받는다 |
| P2 | **DYNAMIC REAL이 KRW-BTC를 살 수 있다** | 동일 코인 상한은 같은 종류만 셈(S:1198~1202, `countBySessionKindAnd...`). 감시 후보에 제외 목록 없음(S:2178~2253) | SID203(LIVE, KRW-BTC)과 같은 계좌에서 BTC 수량이 섞인다. SID203 정산/장부 대조·56일 시험 해석에 간섭 가능 | 운용안에서 KRW-BTC 제외 방법(설정 경로 없으면 후보) 또는 섞일 때의 대조 규칙을 확정. 항목4 계좌 수량 대조에 연결 |
| P3 | **모드 기본값이 REAL** | S:480 `"PAPER".equals(...)`, S:525, `DynamicSessionEntity.java:55` | 요청 본문의 `tradingMode` 누락·오타·소문자 `paper`도 실자금 세션이 된다 | 시작안 본문에 `"tradingMode":"REAL"`을 명시하고 응답의 tradingMode·initialCapital를 대조하는 단계를 둔다. 모의 생성 도구에도 같은 확인이 있는지 대조 |
| P4 | 총 자본 배정에 CREATED도 포함 | `CapitalReservationService.java:76·79` | SID203(CREATED 20,200)이 STAGE_1 총 100,000 중 이미 배정으로 셈. 다른 LIVE/DYNAMIC REAL 행의 현재 상태는 **미확인**(DB 조회 안 함) | 시작안 사전 읽기에 배정 합계를 넣는다 |
| P5 | 공유 풀 단계 미등록이면 실자금 전부 거절 | `docker-compose.prod.yml:59~62`, `CapitalPoolLimits.java:47~49` | 운영 `.env`의 `CAPITAL_POOL_*` 실제 값은 이번에 읽지 않음 | 운영 값 확인은 기존 읽기 승인 범위에서 별도 |
| P6 | 운영 risk_config(쿨다운·스캔 기본값·공용 유니버스) 실제 값 미확인 | S:996·S:2183·S:2255 | 생성 요청에 값을 비우면 운영 DB 값이 들어감 | 시작안에서 감시 조건을 요청 본문에 명시하고 응답값을 기록 |

체결 알림(매수/매도 텔레그램)은 이 클래스 안에서 서킷·불일치 경보만 확인했다. 실제 매매 알림 경로는 주문 실행/정산 서비스 쪽으로 **미확인**이며 다음 항목에서 이어 본다. 쿨다운 후보81b0e7a7(운영 미포함)과의 간섭은 §2-3에서 따로 다룬다.

## 3. 범위와 한계

- 코드 읽기와 기존 시험 이름 대응만 했다. 시험·빌드·운영 조회를 실행하지 않았으므로 P1~P6은 **코드상 경로 확인**이며 운영 실측이 아니다.
- 금액(총원금·월 손실 상한)은 사용자 답 대기라 P1의 구체 숫자는 정하지 않았다.

## 4. 사용자 수집 4항목과 0e1d2a02 기록 대응 (항목5 선행 확인)

[v1.184 용어](../../DECEMBER_SMALL_LIVE_PLAN.md): 고정코인 LIVE / DYNAMIC REAL / 전략 선택형 LIVE(향후). 아래는 DYNAMIC 경로가 **이미 DB에 남기는 것**만 코드로 확인한 것이다(LIVE 쪽 대응은 같은 방법으로 이어 본다).

| 수집 항목 | 이미 남는 곳 | 없음/한계 |
|---|---|---|
| 코인·전략·주기 | strategy_log `coin_pair·strategy_name·timeframe·session_id·ruleset_hash`(S:2302~2311), position `coin_pair·ruleset_hash`, dynamic_session 본문 | 코드 SHA·세션 등록표는 DB에 없음 → 외부 등록표(v1.184 §3) |
| 판단 당시 추세·변동성 | strategy_log `market_regime`(S:2315)·`indicators_json`(adx14·atr14·ema20·ema50·close·봉 시각, `IndicatorSnapshot.java`), position `market_regime`(진입 시) | DYNAMIC은 `ema200Pass`를 null로 남김(S:2316~2319). EMA200 감액 여부는 blocked_reason/로그로만 |
| 판단 당시 유동성 | 없음 | 감시 후보 필터의 거래대금·스프레드·ATR% 값은 DB에 저장되지 않음. `watchlist_json`은 코인 이름만. 필요하면 최소 기록 후보로 별도 판정 |
| 비용 뒤 손익·낙폭·거래 수 | order `applied_executed_funds·applied_paid_fee·signal_price·fill_price_source`, position `realized_pnl·position_fee·opened/closed_at·exit_reason·peak_value_krw` | 월 낙폭은 세션 단위 자산 시계열이 없어 계산 방법을 정해야 함(항목5) |
| 신호가 있었지만 매매 못 한 이유 | strategy_log `blocked_reason`(관문·회차·예약 거절·실자금 손실 관문 모두 `updateSignalQuality` 경유, S:1224·1269·1272·901), order `failure_code·failed_reason·unknown_reason` | **평가마다 HOLD도 행이 남음**(S:1221). 다만 "감시 후보 0"·틱 단위 차단 집계는 INFO 로그뿐(S:966~972·1240~1246) — DB에는 없음 |
