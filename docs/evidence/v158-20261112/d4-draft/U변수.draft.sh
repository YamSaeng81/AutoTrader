# ⛔ 실행 금지 — v1.158 D4 변수 초안(397차, 총괄 판정 전). 운영 배포·DDL·세션 생성 미승인. NEW 는 포함 범위 판정 전 후보다.
# 값 출처(로컬 실제 대조): DIFFN = git diff --name-only OLD NEW, RARX/JARN = jar 비교기 v2(56f5d3ba…)로 보존 b5cd1d0.jar(a7ef759f…) 대 로컬 bootJar 4a4a04a3(0834382a…).
# 이미지·장부·점검 설치본·crontab 값은 sma20p-d4-af7ffa58/d4/blocks-d4/U변수.sh 구조를 따르되 운영 현재값 재확인 전에는 미정. 블록 생성·대역 리허설은 최종 SHA 고정 뒤.
UB=V158D4   # 판 표지(미정 — 블록 생성 때 확정)
OLD=b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88; NEW=4a4a04a304fe22555fbeacd904175a84598af015   # NEW = integration/v158 후보(미확정)
IMGOLD=미정   # #27 이미지(52e12428…) — U0 직전 실행 이미지·기록 파일 재확인 뒤 기입
DDLV=96   # V94→V96(V96__paper_aligned_runtime_state 한 개), V95 없음
DIFFN="$(printf '%s\n' build.gradle \
  crypto-trader-frontend/e2e/paper-render.spec.ts \
  crypto-trader-frontend/playwright.paper-render.config.ts \
  crypto-trader-frontend/src/app/paper-trading/[sessionId]/page.tsx \
  crypto-trader-frontend/src/app/paper-trading/history/page.tsx \
  crypto-trader-frontend/src/app/paper-trading/page.tsx \
  crypto-trader-frontend/src/lib/paperValuation.ts \
  crypto-trader-frontend/src/lib/types.ts \
  web-api/src/main/java/com/cryptoautotrader/api/controller/PaperTradingController.java \
  web-api/src/main/java/com/cryptoautotrader/api/dto/PaperTradingStartRequest.java \
  web-api/src/main/java/com/cryptoautotrader/api/dto/PerformanceSummaryResponse.java \
  web-api/src/main/java/com/cryptoautotrader/api/entity/paper/PaperPositionEntity.java \
  web-api/src/main/java/com/cryptoautotrader/api/entity/paper/VirtualBalanceEntity.java \
  web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java \
  web-api/src/main/java/com/cryptoautotrader/api/repository/paper/PaperPositionRepository.java \
  web-api/src/main/java/com/cryptoautotrader/api/repository/paper/VirtualBalanceRepository.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/CandleFreshnessGuard.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/EntryPriorityRounds.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/PaperExecutionMode.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/PaperMaxInvestment.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/RealtimeProtectLines.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/RealtimeProtectionDispatcher.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/RiskManagementService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/TelegramNotificationService.java \
  web-api/src/main/resources/db/migration/V96__paper_aligned_runtime_state.sql \
  web-api/src/test/java/com/cryptoautotrader/api/service/AbChainPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AbChainV4PgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AbstractPerformanceSnapshotS5.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AbstractSma20SameInputFixture.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AlignedRestartContextTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AlignedV147FixesTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AlignedV148FixesTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AlignedV150FixesTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AlignedV96PgBehaviorTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/B01RealNextSellBarTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/BalanceListSnapshotPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/BtcGateSupplyEndToEndTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/BtcGateUnknownEntryTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/ClosingIntentAtomicityPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/ClosingIntentRollbackPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DailySma20PaperForwardPathTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicBlackSwanCooldownCrossKindTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicBuyCompletesConsistentlyTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicBuyExternalSubmissionBoundaryTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicBuyOuterWriteOrderingTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicJudgeClockTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicPaperBuySameTickWatchlistTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicPaperLiveRuleParityTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicPaperTradingTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicPoolReservationTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicStopRaceTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/EngineParityTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/EntryPriorityEndToEndTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/EntryRoundsCrossRoundPaperDelayTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/ExitAbArmContractTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/LiveLossWindowPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/N15EntryExpiryTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/N15FinalApprovalBoundaryTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/N15GridRounds.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/N15PgLockWaitExpiryTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/O1O3LogContractTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/OrderExecutorDrainPredicateGapTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PartialSellRealizedPnlPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PerformanceSnapshotS5PgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PhantomKeepsConfirmedPnlPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PoolLossLimitTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PositionLimitTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/R5RealCashflowRiskTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealLossGlobalPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealLossThreeFormulaPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealNextBarClockTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealtimeProtectLinesTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealtimeProtectionFairnessTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealtimeProtectionSaturationTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealtimeThrottleCrossingTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RiskCircuitBreakerOrderTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RiskConfigMissingTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RiskManagementServiceTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/SettlementRoundingPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/SharedLoopRoundsActiveTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/SharedMarketDataExecutorSaturationTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/SharedPaperLoopIsolationTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/Sma20LiveAlignedGapsTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/Sma20LiveAlignedPaperSameInputTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/Sma20LivePaperSameInputTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/StartedAtPrecisionPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/StopVersusInFlightExitRaceTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/SubmitOrderBoundaryProbeTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/TelegramTestIsolationTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/V96MigrationPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/support/ExecutorBarrier.java \
  web-api/src/test/java/com/cryptoautotrader/api/support/ExecutorBarrierTest.java \
  web-api/src/test/resources/application-test.yml \
  web-api/src/test/resources/schema-h2.sql)"   # 98경로
RARX="$(printf '%s\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/controller/PaperTradingController.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/PaperTradingStartRequest.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/PerformanceSummaryResponse$PerformanceSummaryResponseBuilder.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/PerformanceSummaryResponse$RegimeStat$RegimeStatBuilder.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/PerformanceSummaryResponse$RegimeStat.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/PerformanceSummaryResponse$SessionPerformance$SessionPerformanceBuilder.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/PerformanceSummaryResponse$SessionPerformance.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/PerformanceSummaryResponse.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/entity/paper/PaperPositionEntity$PaperPositionEntityBuilder.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/entity/paper/PaperPositionEntity.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/entity/paper/VirtualBalanceEntity$VirtualBalanceEntityBuilder.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/entity/paper/VirtualBalanceEntity.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/OrderRepository.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/paper/PaperPositionRepository.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/paper/VirtualBalanceRepository.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/CandleFreshnessGuard.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$1.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$2.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$3.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$BlackSwanGateDecision.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$BuyCandidate.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$EmptyPositionOutcome.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/EntryPriorityRounds.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$1.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$EmptyPositionOutcome.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$PriceSnapshot.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$1.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$2.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$3.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$4.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$5.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$CancelOutcome.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$ClosingSellIntent.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$IntentExpired.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$ReservationRejected.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine$SubmitRejection.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperExecutionMode.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperMaxInvestment.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperTradingService$1.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperTradingService$2.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperTradingService$AlignedEntryExpired.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperTradingService$RiskMetrics.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperTradingService$SessionValuation.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperTradingService.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/RealtimeProtectLines$Collector.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/RealtimeProtectLines$Line.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/RealtimeProtectLines.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/RealtimeProtectionDispatcher$CoinLane.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/RealtimeProtectionDispatcher$Engine.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/RealtimeProtectionDispatcher$Snapshot.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/RealtimeProtectionDispatcher.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/RiskManagementService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$Attempt.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$Outcome.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$Reply.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$SendMessageRequest.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$TradeEvent.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService.class' \
  '추가 BOOT-INF/classes/db/migration/V96__paper_aligned_runtime_state.sql')"   # 62줄
JARN="(끝) 881 895"
