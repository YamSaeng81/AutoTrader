import io, sys

def patch(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        n = s.count(old)
        assert n == 1, (path, n, old[:80])
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)

P = 'web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java'
R = 'web-api/src/main/java/com/cryptoautotrader/api/service/RiskManagementService.java'

# ───────────────────────── RiskManagementService: 순수 판정 분리 + 모의 계좌용 접근자 ─────────────────────────
patch(R, [
('''    private CircuitBreakerResult checkCircuitBreaker(String sessionKind, Long sessionId,
                                                     BigDecimal mddPeak, BigDecimal initialCapital,
                                                     BigDecimal totalAssetKrw, Instant countLossesAfter) {
        RiskConfigEntity config = getRiskConfig();

        if (!Boolean.TRUE.equals(config.getCircuitBreakerEnabled())) {
            return CircuitBreakerResult.pass();
        }
''',
'''    private CircuitBreakerResult checkCircuitBreaker(String sessionKind, Long sessionId,
                                                     BigDecimal mddPeak, BigDecimal initialCapital,
                                                     BigDecimal totalAssetKrw, Instant countLossesAfter) {
        // 연속 손실 입력 — 종전 루프와 같은 순서·같은 중단 조건(이번 가동 이전 청산분에서 끊는다). 판정은 아래 순수 함수와 같다.
        List<PositionEntity> closedPositions = positionRepository
                .findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc(sessionKind, sessionId, "CLOSED");
        List<BigDecimal> pnlNewestFirst = new java.util.ArrayList<>();
        for (PositionEntity pos : closedPositions) {
            if (countLossesAfter != null
                    && (pos.getClosedAt() == null || !pos.getClosedAt().isAfter(countLossesAfter))) {
                break; // 이번 가동 이전 청산분 — 집계 중단
            }
            pnlNewestFirst.add(pos.getRealizedPnl());
        }
        return evaluateCircuitBreaker(getRiskConfig(), mddPeak, initialCapital, totalAssetKrw, pnlNewestFirst);
    }

    /**
     * 모의 계좌 상태를 입력으로 같은 서킷 브레이커 기준을 판단한다 (2026-11-11, LIVE_ALIGNED — 총괄 v1.145 §3-2 위험 관문).
     *
     * <p>REAL 세션·공유 풀·{@code position} 표는 읽지 않는다. 임계값({@code risk_config})만 같이 쓴다.</p>
     *
     * @param closedPnlNewestFirst 그 모의 세션의 청산 손익, 최근 것부터
     */
    @Transactional(readOnly = true)
    public CircuitBreakerResult checkCircuitBreakerFromValues(BigDecimal mddPeak, BigDecimal initialCapital,
                                                              BigDecimal totalAssetKrw,
                                                              List<BigDecimal> closedPnlNewestFirst) {
        return evaluateCircuitBreaker(getRiskConfig(), mddPeak, initialCapital, totalAssetKrw, closedPnlNewestFirst);
    }

    /** 모의 계좌 입력으로 같은 한도({@code risk_config})를 판단하는 엔진 (LIVE_ALIGNED). REAL 계좌·풀을 읽지 않는다. */
    @Transactional(readOnly = true)
    public RiskEngine riskEngineForPaper() {
        return new RiskEngine(toRiskConfig(getRiskConfig()));
    }

    /** 순수 판정 — REAL·DYNAMIC·모의 계좌가 같은 식을 쓴다. 입력만 다르다. */
    private static CircuitBreakerResult evaluateCircuitBreaker(RiskConfigEntity config, BigDecimal mddPeak,
                                                               BigDecimal initialCapital, BigDecimal totalAssetKrw,
                                                               List<BigDecimal> closedPnlNewestFirst) {
        if (!Boolean.TRUE.equals(config.getCircuitBreakerEnabled())) {
            return CircuitBreakerResult.pass();
        }
'''),
('''        // ── 연속 손실 체크 ────────────────────────────────────
        List<PositionEntity> closedPositions = positionRepository
                .findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc(sessionKind, sessionId, "CLOSED");
        int consecutiveLosses = 0;
        for (PositionEntity pos : closedPositions) {
            if (countLossesAfter != null
                    && (pos.getClosedAt() == null || !pos.getClosedAt().isAfter(countLossesAfter))) {
                break; // 이번 가동 이전 청산분 — 집계 중단
            }
            if (pos.getRealizedPnl() != null
                    && pos.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                consecutiveLosses++;
            } else {
                break;
            }
        }
''',
'''        // ── 연속 손실 체크 ────────────────────────────────────
        int consecutiveLosses = 0;
        for (BigDecimal pnl : closedPnlNewestFirst) {
            if (pnl != null && pnl.compareTo(BigDecimal.ZERO) < 0) {
                consecutiveLosses++;
            } else {
                break;
            }
        }
'''),
])

# ───────────────────────── PaperTradingService ─────────────────────────
patch(P, [
# 섹션 주석 — 남은 차이 갱신
('''    // 선택한 새 검증 세션만 쓴다. 공통 판단(전략·관문·회차·만료·청산 조건)은 LIVE 와 같은 함수/빈을 쓰고, 모의 잔액·포지션·체결
    // (슬리피지·수수료)만 PAPER 어댑터다. REAL 계좌 단위 관문(전역 위험 한도 checkRisk·세션 서킷 브레이커·공유 풀 한도·§8 예약)은
    // 모의 계좌에 대응물이 없어 적용하지 않는다 — 비교 실행기 S09 와 문서에 남은 차이로 적는다.
''',
'''    // 선택한 새 검증 세션만 쓴다. 공통 판단(전략·관문·회차·만료·청산 조건)은 LIVE 와 같은 함수/빈을 쓰고, 모의 잔액·포지션·체결
    // (슬리피지·수수료)만 PAPER 어댑터다.
    //
    // 2026-11-11 (총괄 v1.145 §3-2) 보완:
    //  - 판단 평균가는 체결가(LIVE 체결 평균가와 같은 관례), 비용 원가는 investedKrw(수수료 포함). 종전 PAPER 는 그대로다.
    //  - 실시간 손절 확인 간격: 평상시 5초, 급변(30초 −1.5%/+2.0%) 1초 — LIVE 와 같은 상수.
    //  - 위험 관문: 세션 서킷 브레이커(MDD·연속 손실)와 전역 한도(일/주/월 손실·포지션 수·자본 사용률·드로우다운)를 **이 모의 세션의
    //    계좌 상태**를 입력으로, 같은 risk_config 임계값으로 판단한다. REAL 계좌·공유 풀·§8 예약은 읽지도 쓰지도 않는다.
    //    공유 풀 한도(CapitalPoolLimits)·§8 예약은 실자금 배정 장치라 모의 계좌 대응물이 없다(남은 차이).
    //  - 가격 없는 시간 청산: 판단 시각을 기록해 두고 첫 유효 현재가에 체결한다(LIVE 는 그 자리에서 시장가 의도).
    //  - 승인 차례는 세션 행을 다시 읽는 한 트랜잭션이고, 정지는 같은 세션 잠금을 잡는다.
'''),
# 훅 설명 옆에 시계·급변·보류·피크 필드
('''    static volatile java.util.function.Consumer<String> alignedRaceHook = name -> { };
''',
'''    static volatile java.util.function.Consumer<String> alignedRaceHook = name -> { };

    /** 실시간 처리기의 시계 — 시험이 간격 경계를 고정하는 데만 바꾼다. 운영은 시스템 시계다. */
    java.util.function.LongSupplier alignedNowMs = System::currentTimeMillis;

    // LIVE 급변 감지와 같은 상수 (SPIKE_*) — 평상시 5초, 급변 1초.
    private final Map<String, java.util.Deque<long[]>> alignedPriceHistory = new ConcurrentHashMap<>();
    private final Map<String, Long> alignedSpikeLastCheckMs = new ConcurrentHashMap<>();
    private static final long ALIGNED_SPIKE_WINDOW_MS = 30_000;
    private static final long ALIGNED_SPIKE_HISTORY_TTL_MS = 60_000;
    private static final long ALIGNED_SPIKE_CHECK_INTERVAL_MS = 1_000;
    private static final BigDecimal ALIGNED_SPIKE_DOWN_THRESHOLD = new BigDecimal("-1.5");
    private static final BigDecimal ALIGNED_SPIKE_UP_THRESHOLD = new BigDecimal("2.0");

    /** 가격 없는 회차의 시간 초과 청산 판단 시각 (포지션 id → 판단 시각). 첫 유효 현재가에 체결하며 지운다. 재기동 뒤에는 조건이 다시 판단된다. */
    private final Map<Long, Instant> alignedPendingTimeExit = new ConcurrentHashMap<>();

    /** 세션 총자산 피크 (MDD 분모) — LIVE mddPeakCapital 대응. 모의 세션 표에는 열이 없어 메모리에만 두고, 재기동 뒤에는 초기 자본부터 다시 본다. */
    private final Map<Long, BigDecimal> alignedPeakTotalKrw = new ConcurrentHashMap<>();
'''),
# 승인: 한 트랜잭션 + 재조회
('''    String approveAlignedEntry(Long sessionId, String coinPair, StrategySignal signal, String marketRegime,
                               List<Candle> evalCandles, StrategyLogEntity signalLog, Instant expiresAt) {
        synchronized (alignedLock(sessionId)) {
            String result;
            VirtualBalanceEntity session = balanceRepo.findById(sessionId).orElse(null);
            if (session == null || !"RUNNING".equals(session.getStatus())) {
                result = "승인 차례에 세션이 실행 중이 아니다";
            } else if (positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN").isPresent()) {
                result = "승인 차례에 이미 포지션이 있다";
            } else {
                List<Candle> latest = fetchRecentCandles(coinPair, session.getTimeframe());
                if (alignedCandleProblem(coinPair, session.getTimeframe(), latest) != null) latest = List.of();
                result = alignedApprovalGate(coinPair, session.getTimeframe(), latest);
                if (result == null && expiresAt != null && entryClock != null
                        && EntryPriorityRounds.isExpired(expiresAt, entryClock.now())) {
                    result = "승인 실행 만료(만료 " + expiresAt + ") — 모의 매수 없음";
                }
                if (result == null) {
                    BigDecimal priceNow = latest.get(latest.size() - 1).getClose();
                    alignedRaceHook.accept("APPROVE_BEFORE_BUY");
                    boolean bought = executeBuy(sessionId, coinPair, priceNow, session, signal, evalCandles, marketRegime);
                    result = bought ? null : "매수 실행 실패(최소주문/잔고 미달)";
                }
            }
            saveSignalQuality(signalLog, result == null, result);
            return result;
        }
    }

    private String alignedApprovalGate(String coinPair, String timeframe, List<Candle> latest) {
        if (latest.size() < 10) {
''',
'''    String approveAlignedEntry(Long sessionId, String coinPair, StrategySignal signal, String marketRegime,
                               List<Candle> evalCandles, StrategyLogEntity signalLog, Instant expiresAt) {
        synchronized (alignedLock(sessionId)) {
            // 🔴 세션 재확인부터 포지션·주문·차감 저장까지 한 트랜잭션이다 (2026-11-11, §3-2 비동기 경계). 종전에는 트랜잭션 밖이라
            //    E검사 뒤 정지가 커밋되면 포지션·주문은 저장되고 세션 차감만 낙관적 잠금으로 실패해 정지된 세션에 열린 포지션이 남았다
            //    (B05 재현). 충돌이면 다시 읽어 다시 판단한다 — 정지됐으면 매수 없음.
            return optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 승인 #" + sessionId, st -> {
                String result;
                VirtualBalanceEntity session = balanceRepo.findById(sessionId).orElse(null);
                if (session == null || !"RUNNING".equals(session.getStatus())) {
                    result = "승인 차례에 세션이 실행 중이 아니다";
                } else if (positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN").isPresent()) {
                    result = "승인 차례에 이미 포지션이 있다";
                } else {
                    List<Candle> latest = fetchRecentCandles(coinPair, session.getTimeframe());
                    if (alignedCandleProblem(coinPair, session.getTimeframe(), latest) != null) latest = List.of();
                    result = alignedApprovalGate(session, coinPair, session.getTimeframe(), latest);
                    if (result == null && expiresAt != null && entryClock != null
                            && EntryPriorityRounds.isExpired(expiresAt, entryClock.now())) {
                        result = "승인 실행 만료(만료 " + expiresAt + ") — 모의 매수 없음";
                    }
                    if (result == null) {
                        BigDecimal priceNow = latest.get(latest.size() - 1).getClose();
                        alignedRaceHook.accept("APPROVE_BEFORE_BUY");
                        boolean bought = executeBuy(sessionId, coinPair, priceNow, session, signal, evalCandles, marketRegime);
                        result = bought ? null : "매수 실행 실패(최소주문/잔고 미달)";
                    }
                }
                saveSignalQuality(signalLog, result == null, result);
                return result;
            });
        }
    }

    /** LIVE {@code approvalTimeGate} 와 같은 순서 — 서킷 브레이커 → 위험 한도 → 최신 캔들 → 블랙스완 → BTC. 처음 둘은 모의 계좌 입력이다. */
    private String alignedApprovalGate(VirtualBalanceEntity session, String coinPair, String timeframe, List<Candle> latest) {
        CircuitBreakerResult cb = alignedCircuitBreaker(session);
        if (cb.isTriggered()) {
            return "승인 차례에 서킷 브레이커 조건: " + cb.getReason();
        }
        RiskCheckResult risk = alignedRiskCheck(session);
        if (!risk.isApproved()) {
            return "리스크 한도: " + risk.getReason();
        }
        if (latest.size() < 10) {
'''),
# 정지: 선택 모드는 세션 잠금 안에서
('''    public VirtualBalanceEntity stop(Long sessionId) {
        VirtualBalanceEntity stopped = optimisticRetry.inNewTransaction(
                "모의투자 정지 #" + sessionId, st -> stopOnce(sessionId));
''',
'''    public VirtualBalanceEntity stop(Long sessionId) {
        VirtualBalanceEntity stopped;
        if (balanceRepo.findById(sessionId).map(PaperTradingService::isAligned).orElse(false)) {
            // LIVE_ALIGNED — 평가·승인·실시간 처리기와 같은 세션 잠금 안에서 정지한다 (2026-11-11, §3-2). 승인이 매수를 쓰는 중이면
            // 그 트랜잭션이 끝난 뒤 정지가 그 포지션까지 강제 청산한다. 종전 PAPER 는 잠금 없이 그대로다.
            synchronized (alignedLock(sessionId)) {
                stopped = optimisticRetry.inNewTransaction("모의투자 정지 #" + sessionId, st -> stopOnce(sessionId));
            }
        } else {
            stopped = optimisticRetry.inNewTransaction("모의투자 정지 #" + sessionId, st -> stopOnce(sessionId));
        }
        alignedPendingTimeExit.keySet().removeIf(posId -> positionRepo.findById(posId)
                .map(x -> sessionId.equals(x.getSessionId())).orElse(true));
        alignedPeakTotalKrw.remove(sessionId);
'''),
# 틱 시작 — 서킷 브레이커 (LIVE 틱 시작과 같은 자리) : candles 읽기 직후
('''        List<Candle> candles = candlesFor(coinPair, timeframe);
        final boolean aligned = PaperExecutionMode.isLiveAligned(session.getStrategyParams());
        if (aligned) {
''',
'''        List<Candle> candles = candlesFor(coinPair, timeframe);
        final boolean aligned = PaperExecutionMode.isLiveAligned(session.getStrategyParams());
        if (aligned) {
            // LIVE 틱 시작의 세션 서킷 브레이커와 같은 자리·같은 기준 — 발동이면 정지(강제 청산)하고 이 틱은 끝낸다. 입력은 이 모의 계좌다.
            CircuitBreakerResult cb = alignedCircuitBreaker(session);
            if (cb.isTriggered()) {
                log.error("모의투자[LIVE_ALIGNED] 서킷 브레이커 발동 — 정지 (sessionId={}): {}", sessionId, cb.getReason());
                stop(sessionId);
                return;
            }
        }
        if (aligned) {
'''),
# 비회차 BUY 경로 — 위험 한도 (LIVE checkRisk 자리)
('''                } else if (openPos.isEmpty()) {
                    boolean bought = executeBuy(sessionId, coinPair, currentPrice, session, finalSignal, evalCandles,
                            preEvalRegime != null ? preEvalRegime.name() : null);
                    saveSignalQuality(savedSignalLog, bought, bought ? null : "매수 실행 실패(최소주문/잔고 미달)");
''',
'''                } else if (openPos.isEmpty()) {
                    if (aligned) {
                        RiskCheckResult risk = alignedRiskCheck(session);
                        if (!risk.isApproved()) {
                            log.warn("모의투자[LIVE_ALIGNED] 리스크 한도로 매수 차단 (sessionId={}): {}", sessionId, risk.getReason());
                            saveSignalQuality(savedSignalLog, false, "리스크 한도: " + risk.getReason());
                            return;
                        }
                    }
                    boolean bought = executeBuy(sessionId, coinPair, currentPrice, session, finalSignal, evalCandles,
                            preEvalRegime != null ? preEvalRegime.name() : null);
                    saveSignalQuality(savedSignalLog, bought, bought ? null : "매수 실행 실패(최소주문/잔고 미달)");
'''),
# alignedPriceExits — 시간 초과에 판단 시각 병기, 청산은 원자 경로
('''    private boolean alignedPriceExits(VirtualBalanceEntity session, PaperPositionEntity pos, BigDecimal price, String note) {
        if (pos.getTakeProfitPrice() != null && price.compareTo(pos.getTakeProfitPrice()) >= 0) {
            closePosition(pos, price, session, "익절 발동" + note + " — 현재가 " + price.toPlainString() + " ≥ 익절가 "
                    + pos.getTakeProfitPrice().toPlainString(), ExitReason.TAKE_PROFIT);
            return true;
        }
        if (ExitRuleCalculator.shouldTimeStop(session.getMaxHoldHours(), pos.getOpenedAt(), Instant.now())) {
            long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
            closePosition(pos, price, session, String.format("시간 초과 청산%s — 보유 %d시간 ≥ %d시간",
                    note, heldHours, session.getMaxHoldHours()), ExitReason.TIME_STOP);
            return true;
        }
''',
'''    private boolean alignedPriceExits(VirtualBalanceEntity session, PaperPositionEntity pos, BigDecimal price, String note) {
        if (pos.getTakeProfitPrice() != null && price.compareTo(pos.getTakeProfitPrice()) >= 0) {
            closeAligned(pos, price, session, "익절 발동" + note + " — 현재가 " + price.toPlainString() + " ≥ 익절가 "
                    + pos.getTakeProfitPrice().toPlainString(), ExitReason.TAKE_PROFIT);
            return true;
        }
        if (ExitRuleCalculator.shouldTimeStop(session.getMaxHoldHours(), pos.getOpenedAt(), Instant.now())) {
            long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
            closeAligned(pos, price, session, String.format("시간 초과 청산%s — 보유 %d시간 ≥ %d시간%s",
                    note, heldHours, session.getMaxHoldHours(), pendingTimeExitNote(pos)), ExitReason.TIME_STOP);
            return true;
        }
'''),
('''        if (sl) {
            closePosition(pos, price, session, "손절 발동" + note + " -- 손익률 " + pnlPct + "%", ExitReason.STOP_LOSS);
            return true;
        }
        return false;
    }
''',
'''        if (sl) {
            closeAligned(pos, price, session, "손절 발동" + note + " -- 손익률 " + pnlPct + "%", ExitReason.STOP_LOSS);
            return true;
        }
        return false;
    }

    /** 판단 시각(가격 없는 회차)과 체결가 시각을 나눠 적는다. 보류가 없으면 빈 문자열. */
    private String pendingTimeExitNote(PaperPositionEntity pos) {
        Instant decided = alignedPendingTimeExit.remove(pos.getId());
        return decided == null ? "" : " [청산 판단 " + decided + " / 체결가 시각 " + Instant.now() + "]";
    }

    /**
     * LIVE_ALIGNED 청산 — 포지션·주문·잔액 쓰기를 한 트랜잭션으로 묶고, 안에서 다시 읽은 행이 OPEN 일 때만 닫는다 (2026-11-11).
     * 세션 잠금이 같은 프로세스의 경합을 막고, 트랜잭션이 부분 반영을 막는다. 종전 PAPER 의 {@link #closePosition} 은 그대로다.
     */
    private void closeAligned(PaperPositionEntity pos, BigDecimal signalPrice, VirtualBalanceEntity session,
                              String reason, ExitReason exitReason) {
        optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 청산 #" + pos.getId(), st -> {
            PaperPositionEntity fresh = positionRepo.findById(pos.getId()).orElse(null);
            VirtualBalanceEntity s = balanceRepo.findById(session.getId()).orElse(null);
            if (fresh == null || s == null || !"OPEN".equals(fresh.getStatus())) {
                log.info("모의투자[LIVE_ALIGNED] 청산 생략 — 이미 닫힘 (posId={})", pos.getId());
                return null;
            }
            closePosition(fresh, signalPrice, s, reason, exitReason);
            return null;
        });
        alignedPendingTimeExit.remove(pos.getId());
    }
'''),
# 가격 없는 회차 — 시간 초과 판단 기록
('''        BigDecimal price = markPriceBook == null ? null : markPriceBook.freshPrice(coinPair, Instant.now());
        if (price == null) {
            log.warn("모의투자[LIVE_ALIGNED] 캔들 판정 불가 + 유효한 현재가 없음 — 가격 청산 판정 안 함 (sessionId={}, {})",
                    session.getId(), coinPair);
            return;
        }
        alignedPriceExits(session, open.get(), price, "(캔들 판정 불가 회차, 실시간 현재가 " + price.toPlainString() + ")");
    }
''',
'''        BigDecimal price = markPriceBook == null ? null : markPriceBook.freshPrice(coinPair, Instant.now());
        if (price == null) {
            PaperPositionEntity pos = open.get();
            if (ExitRuleCalculator.shouldTimeStop(session.getMaxHoldHours(), pos.getOpenedAt(), Instant.now())) {
                // LIVE 는 여기서 가격 없이 시장가 매도 의도를 낸다. 모의 체결에는 가격이 필요하므로 **판단 시각만 지금** 기록하고,
                // 첫 유효 현재가(실시간 처리기 또는 다음 회차)에 체결한다. 판단과 체결가 시각은 청산 사유에 나눠 남는다.
                Instant decided = alignedPendingTimeExit.computeIfAbsent(pos.getId(), k -> Instant.now());
                log.warn("모의투자[LIVE_ALIGNED] 시간 초과 청산 판단 {} — 캔들 판정 불가 + 유효한 현재가 없음, 첫 유효 현재가에 체결 "
                        + "(sessionId={}, {}, posId={}, 보유 {}h ≥ {}h)", decided, session.getId(), coinPair, pos.getId(),
                        Duration.between(pos.getOpenedAt(), Instant.now()).toHours(), session.getMaxHoldHours());
                return;
            }
            log.warn("모의투자[LIVE_ALIGNED] 캔들 판정 불가 + 유효한 현재가 없음 — 가격 청산 판정 안 함 (sessionId={}, {})",
                    session.getId(), coinPair);
            return;
        }
        alignedPriceExits(session, open.get(), price, "(캔들 판정 불가 회차, 실시간 현재가 " + price.toPlainString() + ")");
    }

    // ── 모의 계좌 입력의 위험 관문 (2026-11-11, §3-2) ─────────────────────────────────────────

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** LIVE 세션 서킷 브레이커와 같은 기준(MDD·연속 손실) — 입력은 이 모의 세션의 총자산 피크·초기 자본·총자산·청산 손익. */
    private CircuitBreakerResult alignedCircuitBreaker(VirtualBalanceEntity s) {
        BigDecimal total = nz(s.getTotalKrw());
        BigDecimal peak = alignedPeakTotalKrw.merge(s.getId(), total.max(nz(s.getInitialCapital())), BigDecimal::max);
        List<BigDecimal> pnlNewestFirst = positionRepo.findBySessionIdAndStatus(s.getId(), "CLOSED").stream()
                .sorted(java.util.Comparator.comparing((PaperPositionEntity p) -> p.getClosedAt() == null ? Instant.EPOCH : p.getClosedAt())
                        .reversed())
                .map(PaperPositionEntity::getRealizedPnl)
                .toList();
        return riskManagementService.checkCircuitBreakerFromValues(peak, s.getInitialCapital(), total, pnlNewestFirst);
    }

    /**
     * LIVE 전역 위험 한도 {@code checkRisk} 와 같은 식(일/주/월 총손실률·열린 포지션 수·자본 사용률·드로우다운)을 **이 모의 세션 하나**를
     * 포트폴리오로 보고 판단한다. 분모는 세션 초기 자본, 손실은 이 세션의 청산 손익 중 손실만 합산(LIVE 와 같은 gross loss).
     */
    private RiskCheckResult alignedRiskCheck(VirtualBalanceEntity s) {
        if (!riskManagementService.hasRiskConfig()) {
            return RiskCheckResult.reject("risk_config 행이 없다 — 신규 진입 차단(V88 기본 행 확인 필요)");
        }
        BigDecimal cap = nz(s.getInitialCapital());
        if (cap.signum() <= 0) {
            return RiskCheckResult.reject("초기 자본이 0 — 신규 진입 차단");
        }
        List<PaperPositionEntity> closed = positionRepo.findBySessionIdAndStatus(s.getId(), "CLOSED");
        Instant now = Instant.now();
        BigDecimal daily = alignedGrossLossPct(closed, now.minus(1, ChronoUnit.DAYS), cap);
        BigDecimal weekly = alignedGrossLossPct(closed, now.minus(7, ChronoUnit.DAYS), cap);
        BigDecimal monthly = alignedGrossLossPct(closed, now.minus(30, ChronoUnit.DAYS), cap);
        int openCount = positionRepo.findBySessionIdAndStatus(s.getId(), "OPEN").size();
        BigDecimal util = cap.subtract(nz(s.getAvailableKrw())).max(BigDecimal.ZERO)
                .divide(cap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        BigDecimal drawdown = cap.subtract(nz(s.getTotalKrw())).max(BigDecimal.ZERO)
                .divide(cap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        return riskManagementService.riskEngineForPaper().check(daily, weekly, monthly, openCount, util, drawdown);
    }

    private static BigDecimal alignedGrossLossPct(List<PaperPositionEntity> closed, Instant since, BigDecimal cap) {
        BigDecimal loss = closed.stream()
                .filter(p -> p.getClosedAt() != null && !p.getClosedAt().isBefore(since))
                .map(PaperPositionEntity::getRealizedPnl).filter(v -> v != null && v.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return loss.abs().divide(cap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }
'''),
# RT — 급변 1초/평상시 5초 + 보류된 시간 청산 체결 + 원자 청산
('''    void doOnAlignedRealtimePrice(RealtimePriceEvent event) {
        String coin = event.getCoinCode();
        if (!alignedCoins.contains(coin)) return;
        BigDecimal price = event.getPrice();
        if (markPriceBook != null && markPriceBook.isStale(event.getObservedAt(), Instant.now())) return;
        if (!MarkPriceBook.isPrice(price)) return;
        long now = System.currentTimeMillis();
        Long last = alignedRtLastCheckMs.get(coin);
        if (last != null && now - last < ALIGNED_RT_CHECK_INTERVAL_MS) return;
        alignedRtLastCheckMs.put(coin, now);
''',
'''    void doOnAlignedRealtimePrice(RealtimePriceEvent event) {
        String coin = event.getCoinCode();
        if (!alignedCoins.contains(coin)) return;
        BigDecimal price = event.getPrice();
        if (markPriceBook != null && markPriceBook.isStale(event.getObservedAt(), Instant.now())) return;
        if (!MarkPriceBook.isPrice(price)) return;
        long now = alignedNowMs.getAsLong();

        // LIVE 와 같은 순서 — 이력은 간격 전에 항상 기록, 급변(30초 −1.5%/+2.0%)이면 1초, 아니면 5초 간격.
        java.util.Deque<long[]> history = alignedPriceHistory.computeIfAbsent(coin, k -> new java.util.ArrayDeque<>());
        BigDecimal spikeRate;
        synchronized (history) {
            history.addLast(new long[] {now, price.multiply(new BigDecimal("100000000")).longValue()});
            long[] first;
            while ((first = history.peekFirst()) != null && now - first[0] > ALIGNED_SPIKE_HISTORY_TTL_MS) history.pollFirst();
            spikeRate = alignedSpikeRate(history, now);
        }
        boolean spike = spikeRate.compareTo(ALIGNED_SPIKE_DOWN_THRESHOLD) <= 0 || spikeRate.compareTo(ALIGNED_SPIKE_UP_THRESHOLD) >= 0;
        if (spike) {
            Long lastSpike = alignedSpikeLastCheckMs.get(coin);
            if (lastSpike != null && now - lastSpike < ALIGNED_SPIKE_CHECK_INTERVAL_MS) return;
            alignedSpikeLastCheckMs.put(coin, now);
        } else {
            Long last = alignedRtLastCheckMs.get(coin);
            if (last != null && now - last < ALIGNED_RT_CHECK_INTERVAL_MS) return;
        }
        alignedRtLastCheckMs.put(coin, now);
'''),
('''                PaperPositionEntity pos = open.get();
                if (pos.getAvgPrice() == null || pos.getAvgPrice().signum() <= 0) continue;
                BigDecimal pnlPct = price.subtract(pos.getAvgPrice()).divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                BigDecimal rawStopLoss = session.getStopLossPct() != null
                        ? session.getStopLossPct() : exitChecker().getConfig().getStopLossPct();
                boolean sl = pos.getStopLossPrice() != null
                        ? price.compareTo(pos.getStopLossPrice()) <= 0
                        : pnlPct.compareTo(rawStopLoss.negate()) <= 0;
                if (sl) {
                    alignedRaceHook.accept("RT_BEFORE_CLOSE");
                    closePosition(pos, price, session, "실시간 손절 — 손익률 " + pnlPct + "%", ExitReason.STOP_LOSS);
                    continue;
                }
''',
'''                PaperPositionEntity pos = open.get();
                if (alignedPendingTimeExit.containsKey(pos.getId())) {
                    // 가격 없는 회차에 이미 내린 시간 초과 청산 판단 — 이 가격이 첫 유효 현재가다.
                    long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
                    closeAligned(pos, price, session, String.format("시간 초과 청산(실시간 현재가 %s) — 보유 %d시간 ≥ %d시간%s",
                            price.toPlainString(), heldHours, session.getMaxHoldHours(), pendingTimeExitNote(pos)), ExitReason.TIME_STOP);
                    continue;
                }
                if (pos.getAvgPrice() == null || pos.getAvgPrice().signum() <= 0) continue;
                BigDecimal pnlPct = price.subtract(pos.getAvgPrice()).divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                BigDecimal rawStopLoss = session.getStopLossPct() != null
                        ? session.getStopLossPct() : exitChecker().getConfig().getStopLossPct();
                boolean sl = pos.getStopLossPrice() != null
                        ? price.compareTo(pos.getStopLossPrice()) <= 0
                        : pnlPct.compareTo(rawStopLoss.negate()) <= 0;
                if (sl) {
                    alignedRaceHook.accept("RT_BEFORE_CLOSE");
                    closeAligned(pos, price, session, "실시간 손절 — 손익률 " + pnlPct + "%", ExitReason.STOP_LOSS);
                    continue;
                }
'''),
('''    /** 신호 품질(실행 여부/차단 사유) 기록 — LIVE의 동명 메서드와 동일 목적. */
''',
'''    /** LIVE {@code calcSpikeRate} 와 같은 식 — 30초 창의 첫 관측 대비 변화율(%). 이력은 [시각ms, 가격×1e8]. */
    private static BigDecimal alignedSpikeRate(java.util.Deque<long[]> history, long nowMs) {
        if (history.size() < 2) return BigDecimal.ZERO;
        long[] latest = history.peekLast();
        long[] windowStart = null;
        for (long[] snap : history) {
            if (nowMs - snap[0] <= ALIGNED_SPIKE_WINDOW_MS) {
                windowStart = snap;
                break;
            }
        }
        if (windowStart == null || windowStart == latest || windowStart[1] <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(latest[1]).subtract(BigDecimal.valueOf(windowStart[1]))
                .divide(BigDecimal.valueOf(windowStart[1]), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /** 신호 품질(실행 여부/차단 사유) 기록 — LIVE의 동명 메서드와 동일 목적. */
'''),
# 매수 — 판단 평균가
('''        // avgPrice = 수수료 포함 실제 취득단가 (investAmount / quantity)
        // 이렇게 해야 closePosition 에서 costBasis = investAmount 가 되어 정확한 실현손익 계산
        BigDecimal avgPriceWithFee = investAmount.divide(quantity, 8, RoundingMode.HALF_UP);
''',
'''        // avgPrice = 수수료 포함 실제 취득단가 (investAmount / quantity)
        // 이렇게 해야 closePosition 에서 costBasis = investAmount 가 되어 정확한 실현손익 계산
        BigDecimal avgPriceWithFee = investAmount.divide(quantity, 8, RoundingMode.HALF_UP);
        // LIVE_ALIGNED (2026-11-11, §3-2): 판단 평균가는 **체결가**다 — LIVE 정산의 평균단가(체결금액 ÷ 수량, 수수료 제외)와 같은 관례.
        //   손절률·전략 SELL(+0.30%) 경계가 LIVE 와 같아진다. 비용 원가는 investedKrw(수수료 포함)로 따로 들고 가며 청산 손익은 그것으로
        //   계산한다 — 수수료는 매수 1회·매도 1회 그대로다. 종전 PAPER·66 세션은 수수료 포함 평균가 그대로.
        boolean alignedMode = isAligned(session);
        BigDecimal avgPriceForDecision = alignedMode ? price : avgPriceWithFee;
'''),
('''                .entryPrice(price)
                .avgPrice(avgPriceWithFee)
                .size(quantity)
''',
'''                .entryPrice(price)
                .avgPrice(avgPriceForDecision)
                .size(quantity)
'''),
# 청산 — 비용 원가
('''        BigDecimal costBasis = pos.getSize().multiply(pos.getAvgPrice());
        BigDecimal realizedPnl = netProceeds.subtract(costBasis);
''',
'''        // LIVE_ALIGNED 는 평균가가 체결가라 원가는 investedKrw(수수료 포함)다. 종전 PAPER 는 size × 수수료 포함 평균가 그대로.
        BigDecimal costBasis = (isAligned(session) && pos.getInvestedKrw() != null)
                ? pos.getInvestedKrw() : pos.getSize().multiply(pos.getAvgPrice());
        BigDecimal realizedPnl = netProceeds.subtract(costBasis);
'''),
# RT 문서 주석 갱신
('''     * 실시간 시세 — LIVE {@code onRealtimePriceEvent} 와 같은 조건(낡은 시세·가격 아님 무시, 코인별 5초 간격, 손절가 이하면 손절,
     * 아니면 익절 트레일링). LIVE_ALIGNED 세션이 없는 코인은 DB 를 보지 않고 버린다. LIVE 의 급변 시 1초 간격 예외는 옮기지 않았다(남은 차이).
''',
'''     * 실시간 시세 — LIVE {@code onRealtimePriceEvent} 와 같은 조건(낡은 시세·가격 아님 무시, 코인별 평상시 5초·급변 1초 간격, 손절가
     * 이하면 손절, 아니면 익절 트레일링). LIVE_ALIGNED 세션이 없는 코인은 DB 를 보지 않고 버린다. 가격 없는 회차에 판단한 시간 초과 청산은
     * 여기 첫 유효 현재가에 체결한다.
'''),
('''     * 캔들을 쓸 수 없는 회차 — LIVE {@code exitsWithoutCandles} 와 같이 받은 지 기준 이내의 실시간 현재가만 쓴다. 없으면 가격 청산을
     * 판정하지 않는다. LIVE 는 가격 없이 시간 초과 시장가 매도를 내지만 모의 체결에는 가격이 필요해 그 경우만 다음 회차로 미룬다(남은 차이).
''',
'''     * 캔들을 쓸 수 없는 회차 — LIVE {@code exitsWithoutCandles} 와 같이 받은 지 기준 이내의 실시간 현재가만 쓴다. 없으면 가격 청산을
     * 판정하지 않는다. LIVE 는 가격 없이 시간 초과 시장가 매도를 내지만 모의 체결에는 가격이 필요해, 그 경우 **판단 시각을 기록**하고
     * 첫 유효 현재가에 체결한다(판단·체결가 시각을 사유에 나눠 남긴다).
'''),
])
print('ok')
