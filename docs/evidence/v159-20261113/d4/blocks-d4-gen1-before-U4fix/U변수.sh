M=~/maint-v159; mkdir -p "$M"; OPS=~/ops-scripts; UB=V159D4   # v1.159 통합 후보(integration/v158 802b691) 배포 초안 D4(미승인·실행 금지) 판 표지 — U 블록 전부가 확인한다. 원본 구조 V159D4(sma20p-d4-802b6918), 생성기 mkdraft5.py. backend+frontend 같은 릴리스, DDL V96(새 backend 기동 때 Flyway)
OLD=b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88; NEW=802b691a6606821d6db838bf5aca0f4cf468205a
IMGOLD=sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055   # #27 O1·O3+N21 통합 배포 이미지(~/maint-o13n21/image-new.txt, v1.140 수용 값) — U0 가 실행 이미지·기록 파일과 같은지 확인한다. sha256: 로 시작하지 않으면 U0 가 멈춘다
IMGPREV8=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1   # #26 이미지 — 이전 복구 태그 autotrader-backend:1899b49 가 가리킨다(D3 U1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV7=sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6   # #25 이미지 — 이전 복구 태그 autotrader-backend:ee509cf 가 가리킨다(#26 U1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV6=sha256:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6   # #24 이미지 — 이전 복구 태그 autotrader-backend:7a94472 가 가리킨다(#25 U1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV=sha256:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9   # #19 이미지 — 이전 복구 태그 autotrader-backend:ea44900 가 가리킨다(보존, 바꾸지 않는다)
IMGPREV2=sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6   # #20 이미지 — 이전 복구 태그 autotrader-backend:b1d7664 가 가리킨다(보존, 바꾸지 않는다)
IMGPREV3=sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07   # #21 이미지 — 이전 복구 태그 autotrader-backend:6995c52 가 가리킨다(#22 P1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV4=sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7   # #22 이미지 — 이전 복구 태그 autotrader-backend:fe01207 가 가리킨다(#23 R1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV5=sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62   # #23 이미지 — 이전 복구 태그 autotrader-backend:1103f48 가 가리킨다(#24 T1 이 만듦, 보존, 바꾸지 않는다)
LS=8bfe8a9fa896f400b3fb6681fef021c99d6ba43b; LSUM=02a8585021fb2a1a52d9b3b56b60e957cb71b9ca3c11bf819595df15cf334f1c   # 지금 설치 장부 — 바꾸지 않는다
CK=c669ccdc6e248ce8e55e9a7ac7405197d266fa18; CKSH=ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc; CKSQL=653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8   # 정기 점검 설치본 — 바꾸지 않는다
CRON=f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b   # crontab — 바꾸지 않는다
h256() { sha256sum < "$1" | cut -d' ' -f1 ; }
v94() { [ "$($PSQL -At -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")" = "94" ] ; }
DIFFN="$(printf '%s\n' build.gradle \
  crypto-trader-frontend/.dockerignore \
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
  web-api/src/test/java/com/cryptoautotrader/api/service/N16InvalidPriceReproTest.java \
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
  web-api/src/test/resources/schema-h2.sql)"   # git diff --name-only OLD NEW — 100경로(시험 70·제품 Java 20·V96 1·build.gradle 1·프런트 8)
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
  '추가 BOOT-INF/classes/db/migration/V96__paper_aligned_runtime_state.sql')"   # 보존 b5cd1d0.jar(a7ef759f…) 대 로컬 bootJar 4a4a04a3=802b691a 입력(0834382a…), 비교기 v2 — 변경 48·추가 14
JARN="(끝) 881 895"
V96F=web-api/src/main/resources/db/migration/V96__paper_aligned_runtime_state.sql; V96B=5b92eef03548c6bd1feaef2b35451563d5327246; V96SUM=-424836241   # V96 원문 블롭·Flyway 체크섬(로컬 실제 java -jar 기동 두 번에서 같은 값, oldbin-v96-r1·r2)
v96() { [ "$($PSQL -At -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")" = "96" ] ; }
FEIMGOLD=미정   # 실행 중 frontend 이미지 ID — 추정하지 않는다. U0 직전 읽기 전용 확인(docker inspect frontend)값을 총괄 승인 판에 기입. 미정이면 U0 가 멈춘다
GS=미정   # 관문 3판·손실 조회문 v2 를 담은 main 고정 커밋 — 근거 커밋 뒤 총괄 승인 판에 기입. 미정이면 U0·U되돌림이 멈춘다
GTP=docs/evidence/v159-20261113/rollback-gate-v3; GATESQL=c9dc20563d6921d4e96cf6abc4b3737efeea94ecbaefddfbc23736056aabd56b; GATESH=fe19aab4e7396d50641a210b60cf328a117916ee8fab127bb3e53678522d6a42   # 상태 기반 복구 관문 3판 원문 해시
PFP=docs/evidence/v159-20261113/d4-draft/preflight-real-loss-readonly.v2.sql; PFSUM=af4105c1f70f6f095b8f1bbbb1c722acd717b2dcd46190e3a9224cd949c5cab0   # 배포 전 손실 조회 v2 원문 해시
gsok() { case "$GS" in 미정*|"") return 1 ;; esac; git cat-file -e "$GS^{commit}" 2>/dev/null ; }
# 기존 뉴스 출처 ERROR 원문 4개(후보에서도 바뀌지 않음). 정기 점검에서는 미분류 🔴 그대로다
NEWSERR='\[(RssNewsSource\] 수집 실패 — sourceId=|(UpbitNoticeSource|CryptoPanicSource|CoinGeckoTrendingSource)\] 수집 실패: )'
NEWSCFG="SELECT source_id || '|' || source_type || '|' || is_enabled || '|' || fetch_interval_min || '|' || coalesce(extract(epoch FROM last_fetched_at)::bigint::text, '-') FROM news_source_config ORDER BY source_id"
btcok() { [ "$(cut -d'|' -f1 "$1")" = true ] && [ "$(cut -d'|' -f4 "$1")" = 20/20 ] ; }
# BTC 관문은 candle 의 '기동 직전에도 판정 불가' 예외를 받지 않는다(v1.36 과 같다).
btcgate() { python3 - "$1" <<'PYEOF'
import re, sys
st = {}
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.search(r"\[캔들 최신성\] (.+?) 판정 (불가|재개) \(", line)
    if m and m.group(1).startswith("BTC 급락 관문 "):
        st[m.group(1)] = m.group(2)
if not st:
    print("BTC 관문: 판정 불가·재개 줄 없음")
for k, v in st.items():
    print("BTC 관문 마지막 상태: %s 판정 %s" % (k, v))
sys.exit(1 if "불가" in st.values() else 0)
PYEOF
}
# 시작 가능 시각(KST 10:10~20:59·22:10~07:59)과 직전 정기 보고 파일. 인자: 기준 epoch 초
kwin() { local now=$1 hm last
  hm=$(date -u -d "@$(( now + 32400 ))" +%H%M)
  if [ "$hm" -ge 1010 ] && [ "$hm" -lt 2100 ]; then last=$(date -u -d "@$(( now + 32400 ))" +%Y%m%d)-1000
  elif [ "$hm" -ge 2210 ]; then last=$(date -u -d "@$(( now + 32400 ))" +%Y%m%d)-2200
  elif [ "$hm" -lt 0800 ]; then last=$(date -u -d "@$(( now + 32400 - 86400 ))" +%Y%m%d)-2200
  else echo "시작 가능 시각 아님 — KST 10:10~20:59·22:10~07:59 에만 K0 (지금 KST ${hm:0:2}:${hm:2:2})"; return 1; fi
  [ -s "$HOME/daily-check/$last.txt" ] || { echo "직전 정기 보고 ~/daily-check/$last.txt 없음 — OPS §5 대로 보고만(재실행·재전송 금지)"; return 1; }
  echo "직전 정기 보고 ~/daily-check/$last.txt (읽은 뒤 시작)" ; }
# 뉴스 출처별 관측. 인자: 앱 로그, K0 설정, 지금 설정, 기동 epoch 초, 관측 epoch 초(지금 설정을 읽은 시각)
# 종료 1 = 설정(종류·사용·간격)이 K0 와 다름, 2 = 새 코드가 시도했는데 완료·미완료 줄 없음,
# 3 = ⚪ 계산상 회차가 관측 전에 지났는데 시도 기록·완료·미완료 줄 없음(확인 필요, 실패로 확정하지 않음)
newsobs() { python3 - "$@" <<'PYEOF'
import re, sys, time
if len(sys.argv) != 6:
    print("⚪ newsobs 인자는 5개(로그·K0 설정·지금 설정·기동 epoch·관측 epoch)"); sys.exit(2)
log, before, now, start, obs = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
def cfg(p):
    d = {}
    for line in open(p, encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if len(f) != 5:
            print("⚪ 뉴스 설정 줄 형식 다름: %r" % line); sys.exit(2)
        d[f[0]] = f
    return d
kst = lambda t: time.strftime("%H:%M KST", time.gmtime(t + 32400))
b, n = cfg(before), cfg(now)
seen = {}
pat = re.compile(r"\[NewsAggregator\] 수집 (완료|미완료) — sourceId=(\S+) (.*)$")
for line in open(log, encoding="utf-8", errors="replace"):
    m = pat.search(line)
    if m:
        seen.setdefault(m.group(2), []).append((m.group(1), m.group(3).strip()))
rc, chk = 0, 0
print("(기동 %s · 관측 %s · 회차는 기동 3분 뒤부터 15분 간격으로 계산 — 실제는 앞 회차 소요만큼 늦어질 수 있다)" % (kst(start), kst(obs)))
for k in sorted(set(b) | set(n)):
    if b.get(k, ["없음"])[:4] != n.get(k, ["없음"])[:4]:
        print("🔴 뉴스 설정이 K0 때와 다름: %s → %s" % ("|".join(b.get(k, ["없음"])[:4]), "|".join(n.get(k, ["없음"])[:4])))
        rc = 1
for k in sorted(n):
    sid, typ, en, iv, lf = n[k]
    rs = seen.get(sid, [])
    if rs:
        done = sum(1 for r in rs if r[0] == "완료")
        print("%s %s(%s분): 완료 %d·미완료 %d — 마지막 '수집 %s — %s'" % (sid, typ, iv, done, len(rs) - done, rs[-1][0], rs[-1][1]))
        for r in rs:
            if r[0] == "미완료" and ("종류=INVALID_RESPONSE" in r[1] or "종류=SOURCE_REPORTED_FAILURE" in r[1]):
                print("   🔴 새 판정 — 출처 원문과 함께 보고: 수집 미완료 — sourceId=%s %s" % (sid, r[1]))
    elif en not in ("t", "true"):
        print("%s %s: 사용 안 함" % (sid, typ))
    elif lf != "-" and int(lf) >= start + 60:
        print("⚪ %s %s: 새 코드가 시도(last_fetched_at %s)했는데 완료·미완료 줄 없음 — 원문 보고" % (sid, typ, kst(int(lf))))
        rc = max(rc, 2)
    else:
        due = max(int(lf) + int(iv) * 60 if lf != "-" else start, start + 180)
        tick = start + 180 + -(-(due - start - 180) // 900) * 900
        if obs < tick:
            print("%s %s(%s분): 미관측 — 계산상 차례 전(%s 이후 첫 회차 %s 무렵)" % (sid, typ, iv, kst(due), kst(tick)))
        else:
            print("⚪ %s %s(%s분): 확인 필요 — 계산상 회차 %s ≤ 관측 %s 인데 시도 기록·완료·미완료 줄 없음(앞 회차 소요로 늦을 수 있어 실패로 확정하지 않는다) — 보고" % (sid, typ, iv, kst(tick), kst(obs)))
            chk += 1
if chk:
    print("⚪ 확인 필요 %d개 — 정상 관측으로 세지 않는다. 수집 API·강제 재시도·주기 변경·즉시 되돌림 없이 출력만 보고" % chk)
sys.exit(rc if rc else (3 if chk else 0))
PYEOF
}
# N12 출처 HTTP 비200 줄 관측(v1.95). 인자: 앱 로그. 로그에서 읽은 sourceId·스레드·꼬리·본문은 출력하지 않는다(고정 출처 이름·상태·시각·건수만).
# 짝: 뒤 5줄 안 같은 스레드의 첫 줄이 같은 상태·같은 sourceId(CoinGecko 는 다른 출처가 찍지 않은 sourceId)의 상위 FAILED/HTTP_n/0건이어야 하고, 결과 줄 하나는 한 번만 쓴다.
# 종료 1 = 🔴 본문이 붙은 형식이거나 HTTP 줄 바로 뒤에 로그 머리 없는 연속 줄, 3 = ⚪ 고정 한 줄인데 짝 미확인, 0 = 모두 고정 한 줄+짝 또는 미관측
httpobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ httpobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
HTTP = re.compile(r"\[(CryptoPanicSource|UpbitNoticeSource|RssNewsSource|CoinGeckoTrendingSource)\] HTTP (\d{3})(.*)$")
SID = re.compile(r"^ — sourceId=(\S+)$")
UP = re.compile(r"\[NewsAggregator\] 수집 미완료 — sourceId=(\S+) 결과=FAILED 종류=HTTP_(\d{3}) 판정=\S+ 받은=0건 신규=0건$")
TAG = re.compile(r"\[(\w+Source)\] .*sourceId=([^\s:]+)")
# 로그에서 읽은 sourceId·스레드·꼬리·본문은 내부 비교에만 쓴다. 출력은 고정 출처 이름·상태 숫자·시각 숫자·건수뿐이다.
rows = [PRE.sub("", l.rstrip("\r\n")) for l in open(sys.argv[1], encoding="utf-8", errors="replace")]
heads = [(i, HEAD.match(r)) for i, r in enumerate(rows)]
heads = [(i, h) for i, h in heads if h]
owner = {}   # 로그의 sourceId → 그 sourceId 를 직접 찍은 출처 클래스들(CoinGecko 짝 확인용)
for r in rows:
    t = TAG.search(r)
    if t:
        owner.setdefault(t.group(2), set()).add(t.group(1))
bad, chk, seen, used = 0, 0, {}, set()
for n, (i, h) in enumerate(heads):
    r = rows[i]
    m = HTTP.search(r)
    if not m:
        continue
    src, st, tail = m.group(1), m.group(2), m.group(3)
    tm, th = h.group(1), h.group(2)
    cont = 0
    while i + 1 + cont < len(rows) and not HEAD.match(rows[i + 1 + cont]):
        cont += 1
    k = "%s HTTP %s" % (src, st)
    s = seen.setdefault(k, [0, 0, 0, 0, 0])   # 전체·고정 한 줄·본문 형식/연속 줄·상위 짝·짝 미확인
    s[0] += 1
    sm = SID.match(tail)
    fixed = (tail == "") if src == "CoinGeckoTrendingSource" else sm is not None
    if not fixed or cont:
        s[2] += 1; bad += 1
        print("🔴 %s %s HTTP %s — 고정 한 줄이 아님(꼬리 %d자·뒤 연속 줄 %d줄, 내용은 출력하지 않는다)" % (src, tm, st, len(tail), cont))
        continue
    s[1] += 1
    # 짝 후보: 뒤 5개 로그 머리 줄 안에서 같은 스레드의 첫 줄 하나뿐이다(출처 fetch 와 상위 결과는 같은 스레드에서 이어진다).
    # 그 줄이 다른 HTTP·다른 수집 결과·다른 기록이면 경계로 보고 더 뒤 줄을 쓰지 않는다. 결과 줄 하나는 한 번만 쓴다.
    j = None
    for i2, h2 in heads[n + 1:n + 6]:
        if h2.group(2) == th:
            j = i2; break
    why = None
    if j is None:
        why = "뒤 5줄 안에 같은 스레드의 다음 줄 없음"
    elif j in used:
        why = "같은 스레드 다음 줄이 이미 다른 HTTP 줄의 짝"
    else:
        u = UP.search(rows[j])
        if not u:
            why = "같은 스레드 다음 줄이 상위 FAILED/HTTP_n/0건 결과가 아님"
        elif u.group(2) != st:
            why = "같은 스레드 상위 결과의 상태가 다름"
        elif sm and u.group(1) != sm.group(1):
            why = "같은 스레드 상위 결과의 sourceId 가 HTTP 줄과 다름(값은 출력하지 않는다)"
        elif not sm and (owner.get(u.group(1), set()) - {src}):
            why = "같은 스레드 상위 결과의 sourceId 를 다른 출처가 찍음(값은 출력하지 않는다)"
    if why is None:
        used.add(j); s[3] += 1
    else:
        s[4] += 1; chk += 1
        print("⚪ %s %s HTTP %s — %s — 보고" % (src, tm, st, why))
if not seen:
    print("출처 HTTP 비200 줄: 미관측 — 이 구간에 자연 발생한 비200 응답 없음(실패를 유도하지 않는다)")
for k in sorted(seen):
    a, f, b, p, q = seen[k]
    print("%s ×%d — 고정 한 줄 %d · 본문 형식/연속 줄 %d · 같은 스레드 상위 FAILED/HTTP_n/0건 짝 %d · 짝 미확인 %d" % (k, a, f, b, p, q))
sys.exit(1 if bad else (3 if chk else 0))
PYEOF
}
# N13 HOLD 기준선 질의 관측(v1.101). 인자: 앱 로그. 출력은 시각·스레드 종류·앱 프레임(클래스.메서드)·건수만(연결 식별자·질의문·본문 없음).
# 누수 경고는 대여 60초 초과 때만 찍힌다(운영 leak-detection-threshold). HOLD 경고 = 첫 앱 프레임이 SignalQualityService.evaluateHoldBaselineLoop.
# 종료 1 = 🔴 P0: 연결 획득 시간 초과 줄 있음, 3 = ⚪ 장기 대여 경고 있음(HOLD 또는 그 밖 — 개선 미확인), 0 = 둘 다 없음
holdobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ holdobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
TRIG = re.compile(r"leak detection triggered for (\S+) on thread (\S+), stack trace follows")
UNL = re.compile(r"Previously reported leaked connection (\S+) on thread (\S+) was returned to the pool \(unleaked\)")
APP = re.compile(r"^\s+at com\.cryptoautotrader\.([\w.$]+)\([\w.]+:\d+\)")
TO = re.compile(r"Connection is not available, request timed out")
kind = lambda t: re.sub(r"-\d+$", "-N", t)
rows = [PRE.sub("", l.rstrip("\r\n")) for l in open(sys.argv[1], encoding="utf-8", errors="replace")]
leaks, cur, to, cs, ce = [], None, [], [], []
context = None
for r in rows:
    h = HEAD.match(r)
    if h:
        context = (h.group(1), kind(h.group(2)))
    # Hikari timeout can be on a non-header Caused by line. Count every matching line once.
    if TO.search(r):
        to.append(context or ("시각미확인", "스레드미확인"))
    if h:
        cur = None
        m = TRIG.search(r)
        if m:
            cur = {"t": h.group(1), "c": m.group(1), "th": m.group(2), "f": None, "ret": None}
            leaks.append(cur)
            continue
        m = UNL.search(r)
        if m:
            for k in reversed(leaks):
                if k["c"] == m.group(1) and k["th"] == m.group(2) and k["ret"] is None:
                    k["ret"] = h.group(1)
                    break
            continue
        if "[SignalQuality] 시작 Catchup 시작" in r:
            cs.append(h.group(1))
        if "[SignalQuality] 시작 Catchup 완료" in r:
            ce.append(h.group(1))
        continue
    if cur is not None and cur["f"] is None:
        m = APP.match(r)
        if m:
            cur["f"] = m.group(1)
HOLDF = "api.service.SignalQualityService.evaluateHoldBaselineLoop"
hold = [k for k in leaks if k["f"] == HOLDF]
other = [k for k in leaks if k["f"] != HOLDF]
print("HOLD 기준선 질의 장기 대여 경고 %d건(반환 %d·반환 미관측 %d) · 그 밖의 장기 대여 경고 %d건 · 연결 획득 시간 초과 줄 %d건" % (
    len(hold), sum(1 for k in hold if k["ret"]), sum(1 for k in hold if not k["ret"]), len(other), len(to)))
for k in hold:
    print("  · HOLD %s %s · 반환 %s" % (k["t"], kind(k["th"]), k["ret"] or "미관측"))
for k in other:
    print("  · 그 밖 %s %s · %s · 반환 %s" % (k["t"], kind(k["th"]), k["f"] or "(앱 프레임 없음)", k["ret"] or "미관측"))
for t, th in to:
    print("  · 🔴 연결 획득 시간 초과 %s %s" % (t, th))
print("시작 Catchup: 시작 %s · 완료 %s (로그 머리 시각 UTC)" % (",".join(cs) or "없음", ",".join(ce) or "없음"))
sys.exit(1 if to else (3 if leaks else 0))
PYEOF
}
# N15·N16 줄 관측(v1.111). 인자: 앱 로그. 출력은 로그 머리 시각·엔진 종류·건수만(코인·세션 id·가격·사유 본문은 내지 않는다).
# 자연 발생만 본다 — 가격 이상·만료를 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다.
# 종료 1 = 🔴 만료 사유 기록 실패 줄 있음(원래 신호에 사유가 남지 않았을 수 있다), 3 = ⚪ 인자 오류, 0 = 그 밖(관측·미관측 모두)
entryobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ entryobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
CAT = [
    ("콜백 전 만료", re.compile(r"\[진입 우선순위\] \d+위 \S+ (LIVE|DYNAMIC)#\d+ → 승인 실행 만료\(")),
    ("의도 직전 만료", re.compile(r"의도 저장 직전 승인 실행 만료 — 매수 의도를 만들지 않는다 \(sessionKind=(LIVE|DYNAMIC),")),
    ("예약·의도 경계 거절", re.compile(r"(매수 예약이 거절돼 롤백했다|\[Dynamic\] 매수 예약 거절) \(.*사유=ENTRY_EXPIRED\)")),
    ("만료 사유 기록 실패", re.compile(r"\[진입 우선순위\] 만료 사유 기록 실패")),
    ("가격 아님 무시", re.compile(r"(\[Dynamic\] )?가격이 아닌 실시간 시세 무시: ")),
]
def kind(name, m, line):
    if name == "예약·의도 경계 거절":
        return "DYNAMIC" if m.group(1).startswith("[Dynamic]") else "LIVE"
    if name == "가격 아님 무시":
        return "DYNAMIC" if m.group(1) else "LIVE"
    if name == "만료 사유 기록 실패":
        return "-"
    return m.group(1)
seen = {c[0]: [] for c in CAT}
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    for name, pat in CAT:
        m = pat.search(r)
        if m:
            seen[name].append((h.group(1), kind(name, m, r)))
            break
cnt = lambda n, k: sum(1 for _, x in seen[n] if x == k)
n15 = sum(len(seen[n]) for n in ("콜백 전 만료", "의도 직전 만료", "예약·의도 경계 거절", "만료 사유 기록 실패"))
print("N15 승인 실행 만료 — 콜백 전 LIVE %d·DYNAMIC %d · 예약·의도 경계 거절(ENTRY_EXPIRED) LIVE %d·DYNAMIC %d(그중 의도 직전 LIVE %d·DYNAMIC %d) · 만료 사유 기록 실패 %d" % (
    cnt("콜백 전 만료", "LIVE"), cnt("콜백 전 만료", "DYNAMIC"), cnt("예약·의도 경계 거절", "LIVE"), cnt("예약·의도 경계 거절", "DYNAMIC"),
    cnt("의도 직전 만료", "LIVE"), cnt("의도 직전 만료", "DYNAMIC"), len(seen["만료 사유 기록 실패"])))
print("N16 가격이 아닌 실시간 시세 무시 — LIVE %d·DYNAMIC %d" % (cnt("가격 아님 무시", "LIVE"), cnt("가격 아님 무시", "DYNAMIC")))
for name, _ in CAT:
    for t, k in seen[name][:20]:
        print("  · %s%s %s %s" % ("🔴 " if name == "만료 사유 기록 실패" else "", name, t, k))
    if len(seen[name]) > 20:
        print("  · %s 그 밖 %d건(목록 생략)" % (name, len(seen[name]) - 20))
if not n15:
    print("N15: 미관측 — 이 구간에 자연 발생한 승인 실행 만료 없음(유도하지 않는다)")
if not seen["가격 아님 무시"]:
    print("N16: 미관측 — 이 구간에 자연 발생한 가격 아닌 실시간 시세 없음(유도하지 않는다)")
sys.exit(1 if seen["만료 사유 기록 실패"] else 0)
PYEOF
}
# N17 줄 관측(v1.119). 인자: 앱 로그. 출력은 로그 머리 시각·엔진 종류·건수만(코인·가격은 내지 않는다).
# 자연 발생만 본다 — REST 가격 이상을 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다. 종료 3 = ⚪ 인자 오류, 0 = 그 밖
n17obs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ n17obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PAT = re.compile(r"\[(Dynamic\]\[)?§9\] REST 강제 갱신 시세가 가격이 아님 . 발행하지 않음 \(")   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    m = PAT.search(r)
    if m:
        seen.append((h.group(1), "DYNAMIC" if m.group(1) else "LIVE"))
print("N17 REST 강제 갱신 가격 아님(INVALID_PRICE·미발행) — LIVE %d·DYNAMIC %d" % (
    sum(1 for _, k in seen if k == "LIVE"), sum(1 for _, k in seen if k == "DYNAMIC")))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not seen:
    print("N17: 미관측 — 이 구간에 자연 발생한 REST 강제 갱신 가격 이상 없음(유도하지 않는다)")
sys.exit(0)
PYEOF
}
# N19 줄 관측(v1.124 — ee509cf 고정 문구). 인자: 앱 로그. 출력은 로그 머리 시각·사유·건수만(코인·값은 내지 않는다 — 문구에도 없다).
# 자연 발생만 본다 — WS 를 끊거나 REST 이상 응답을 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다. 종료 3 = ⚪ 인자 오류, 0 = 그 밖
n19obs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ n19obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PAT = re.compile(r"\[§9\] REST 폴백 시세 항목 건너뜀 . (가격 형식이 숫자가 아님|가격이 아님 \(부호=(?:0|음수)\)|코인 형식이 문자열이 아님)\s*$")   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
KIND = {"가격 형식": "형식", "가격이 아": "가격 아님", "코인 형식": "코인 형식"}
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    m = PAT.search(r)
    if m:
        seen.append((h.group(1), KIND[m.group(1)[:5]]))
c = lambda k: sum(1 for _, x in seen if x == k)
print("N19 REST 전역 폴백 항목 건너뜀 — 형식 이상 %d·가격 아님 %d·코인 형식 %d" % (c("형식"), c("가격 아님"), c("코인 형식")))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not seen:
    print("N19: 미관측 — 이 구간에 자연 발생한 REST 전역 폴백 이상 항목 없음(유도하지 않는다)")
sys.exit(0)
PYEOF
}
# N20 줄 관측(§5-9 98 — 1899b49 고정 문구). 인자: 앱 로그. 출력은 로그 머리 시각·사유·건수만(코인·값은 내지 않는다 — 문구에도 없다).
# 자연 발생만 본다 — 이상 응답·예외를 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다. 종료 3 = ⚪ 인자 오류, 0 = 그 밖
n20obs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ n20obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PATS = [   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
    ("항목 형식", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 가격 형식이 숫자가 아님\s*$")),
    ("항목 가격 아님", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 가격이 아님 \(부호=(?:0|음수)\)\s*$")),
    ("항목 코인 형식", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 코인 형식이 문자열이 아님\s*$")),
    ("시세 처리 실패", re.compile(r"\[시세 보강\] 시세 처리 실패 \(종류=\w+\)\s*$")),
    ("회차 오류", re.compile(r"\[시세 보강\] 회차 오류 \(종류=\w+\)\s*$")),
    ("경보 전송 실패", re.compile(r"\[시세 보강\] 경보 전송 실패 \(종류=\w+\)\s*$")),
    ("호가 보강", re.compile(r"\[시세 보강\] 호가로 보강한 보유 ")),
]
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    for k, p in PATS:
        if p.search(r):
            seen.append((h.group(1), k))
            break
c = lambda k: sum(1 for _, x in seen if x == k)
print("N20 보유 시세 보강 — " + "·".join("%s %d" % (k, c(k)) for k, _ in PATS))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not any(k != "호가 보강" for _, k in seen):
    print("N20: 미관측 — 이 구간에 자연 발생한 시세 보강 항목 건너뜀·처리 실패·회차 오류·경보 전송 실패 없음(유도하지 않는다)")
sys.exit(0)
PYEOF
}
# ── 감시 스크립트 고정값 — 운영 b5cd1d0 과 후보의 start_66.py·시험 파일은 같은 블롭이다(D4 는 감시 스크립트를 바꾸지 않는다)
NF=scripts/prospective/start_66.py; NT=scripts/prospective/test_freshness_recheck_fail.py   # NT 는 새로 추적되는 시험 파일 — 서버에서 실행하지 않는다
NBOLD=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d; NBNEW=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d   # start_66.py 블롭(운영 b5cd1d0 · 후보 — 같다)
NHOLD=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b; NHNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b   # 파일 sha256
# ── N21 공통 함수 시작(통합 초안 D3 U변수에 같은 원문으로 들어간다) ──
JLOG=scripts/prospective/freshness_log.jsonl      # 관측 행(회차마다 한 행)
RUNLOG=scripts/prospective/freshness_runs.log     # cron 회차의 표준 출력·오류(>> 덧붙임)
SYSLOG=/var/log/syslog                            # cron 시작 줄(CRON[pid]: (사용자) CMD (…))
# freshness 회차 프로세스 패턴 — 명령줄 맨 앞이 python 실행 파일인 프로세스만 잡는다.
# cron 의 /bin/sh -c 껍데기, 이 블록을 담은 bash(대화형·bash -c·bash 파일) 명령줄은 맨 앞이 python 이 아니라서 잡히지 않는다.
FPAT='^[^ ]*python[0-9.]* +([^ ]+ +)*[^ ]*start_66[.]py +freshness'
# 회차 조회. 인자: 기록 이름. 종료 0 = 회차 없음(pgrep 종료 1·출력 없음), 1 = 회차 있음(pgrep 종료 0), 2 = 조회 실패(그 밖 전부)
fresh_idle() { local rc=0
  pgrep -af "$FPAT" > "$M/pgrep-$1.txt" 2> "$M/pgrep-$1.err" || rc=$?
  case "$rc" in
    1) if [ -s "$M/pgrep-$1.txt" ] || [ -s "$M/pgrep-$1.err" ]; then cat "$M/pgrep-$1.txt" "$M/pgrep-$1.err"; echo "프로세스 조회 이상: pgrep 종료 1 인데 출력 있음"; return 2; fi
       echo "freshness 회차 없음(pgrep 종료 1)"; return 0 ;;
    0) cat "$M/pgrep-$1.txt"; echo "freshness 회차가 돌고 있다(pgrep 종료 0)"; return 1 ;;
    *) cat "$M/pgrep-$1.txt" "$M/pgrep-$1.err"; echo "프로세스 조회 실패(pgrep 종료 $rc)"; return 2 ;;
  esac; }
# 적용 직전 기준 위치. 인자: 표지 이름. 시스템 기록 inode·줄 수, 실행 기록 줄 수, 관측 행 수. 하나라도 못 읽으면 종료 1
n21mark() {
  [ -r "$SYSLOG" ] || { echo "시스템 기록 $SYSLOG 를 읽을 수 없음"; return 1; }
  [ -f "$RUNLOG" ] && [ -r "$RUNLOG" ] || { echo "실행 기록 $RUNLOG 를 읽을 수 없음"; return 1; }
  [ -f "$JLOG" ] && [ -r "$JLOG" ] || { echo "관측 행 $JLOG 를 읽을 수 없음"; return 1; }
  printf '%s %s %s %s\n' "$(stat -c %i "$SYSLOG")" "$(wc -l < "$SYSLOG")" "$(wc -l < "$RUNLOG")" "$(wc -l < "$JLOG")" > "$M/mark-$1.txt"
  echo "기준 위치(시스템 기록 inode·줄, 실행 기록 줄, 관측 행): $(cat "$M/mark-$1.txt")"; }
# 적용 뒤 확인. 인자: 표지 이름. 구간 = 표지 위치 뒤에 덧붙은 줄. 종료 0 = 세 근거 모두 확인, 1 = 미확인 또는 이상(이유 출력)
# PID·완료 식별자가 없어 시작 줄과 관측 행의 1:1 짝은 확정하지 않는다. at 은 재확인 회차면 재확인 뒤, 아니면 1차 조회 전 시각이다
n21after() { local ino s0 r0 j0 ino1 s1 r1 j1 bad=0
  read -r ino s0 r0 j0 < "$M/mark-$1.txt" || { echo "미확인: 기준 위치 기록 없음"; return 1; }
  [ -r "$SYSLOG" ] || { echo "미확인: 시스템 기록 $SYSLOG 를 읽을 수 없음"; return 1; }
  [ -f "$RUNLOG" ] && [ -r "$RUNLOG" ] && [ -f "$JLOG" ] && [ -r "$JLOG" ] || { echo "미확인: 실행 기록·관측 행 파일을 읽을 수 없음"; return 1; }
  ino1=$(stat -c %i "$SYSLOG"); s1=$(wc -l < "$SYSLOG"); r1=$(wc -l < "$RUNLOG"); j1=$(wc -l < "$JLOG")
  echo "기준 → 지금: 시스템 기록 inode $ino→$ino1 줄 $s0→$s1 · 실행 기록 줄 $r0→$r1 · 관측 행 $j0→$j1"
  [ "$ino1" = "$ino" ] && [ "$s1" -ge "$s0" ] || { echo "미확인: 시스템 기록이 교체·축소됨(회전 등) — 회전된 파일을 찾지 않고 보고"; return 1; }
  [ "$r1" -ge "$r0" ] && [ "$j1" -ge "$j0" ] || { echo "미확인: 실행 기록 또는 관측 행이 줄었다 — 보고"; return 1; }
  sed -n "$((s0 + 1)),${s1}p" "$SYSLOG" | grep -E 'CRON\[[0-9]+\]: (\([^)]*\) )?CMD \(.*start_66[.]py freshness' > "$M/cron-after-$1.txt" || true
  sed -n "$((r0 + 1)),${r1}p" "$RUNLOG" > "$M/runlog-after-$1.txt"
  sed -n "$((j0 + 1)),${j1}p" "$JLOG" > "$M/log-after-$1.jsonl"
  echo "== 적용 뒤 cron 시작 줄 $(wc -l < "$M/cron-after-$1.txt")개"; tail -3 "$M/cron-after-$1.txt"
  echo "== 적용 뒤 실행 기록 $(wc -l < "$M/runlog-after-$1.txt")줄 · 그중 Traceback $(grep -c 'Traceback' "$M/runlog-after-$1.txt" || true)줄"
  echo "== 적용 뒤 관측 행 $(wc -l < "$M/log-after-$1.jsonl")개"
  [ -s "$M/cron-after-$1.txt" ] || { echo "미확인: 적용 뒤 cron 시작 줄이 없다 — 새 판이 cron 으로 시작됐다는 근거 없음"; bad=1; }
  [ -s "$M/log-after-$1.jsonl" ] || { echo "미확인: 적용 뒤 새 관측 행이 없다 — 회차가 아직 안 끝났거나 실패(손으로 돌리지 않는다)"; bad=1; }
  if grep -q 'Traceback' "$M/runlog-after-$1.txt"; then grep -n -A3 'Traceback' "$M/runlog-after-$1.txt" | tail -20; echo "이상: 적용 뒤 실행 기록에 새 Traceback"; bad=1; fi
  local py=0; [ ! -s "$M/log-after-$1.jsonl" ] || python3 -c '
import json, sys
sys.stdout.reconfigure(encoding="utf-8")
keys = ["at", "green", "rechecked", "red", "redCoins", "rows", "unknown", "unknownCoins"]
rk = ["count", "dbTo", "exErr", "exTo", "rechecked"]
bad = 0
for line in open(sys.argv[1], encoding="utf-8"):
    try:
        r = json.loads(line)
        ok = sorted(r) == keys and all(sorted(v) == rk for v in r["rows"].values())
        stale = [p for p, v in r["rows"].items() if v["exErr"] and v["exTo"]]
    except Exception as e:
        print("행 해석 실패:", type(e).__name__); bad += 1; continue
    print(r["at"], "🟢%d ⚪%d 🔴%d 재확인 %d" % (r["green"], r["unknown"], r["red"], len(r["rechecked"])),
          "칸 같음" if ok else "칸 다름", "N21 상태 %d" % len(stale))
    bad += (not ok) or bool(stale)
sys.exit(1 if bad else 0)
' "$M/log-after-$1.jsonl" > "$M/rows-after-$1.txt" 2>&1 || py=$?
  [ ! -s "$M/rows-after-$1.txt" ] || cat "$M/rows-after-$1.txt"
  [ "$py" = 0 ] || { echo "이상: JSON 칸이 다르거나 N21 상태 행(실패 종류가 있는데 exTo 가 남음)·해석 실패(종료 $py)"; bad=1; }
  return "$bad"; }
# ── N21 공통 함수 끝 ──
