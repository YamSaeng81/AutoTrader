import io

def patch(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        n = s.count(old)
        assert n == 1, (path, n, old[:100])
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)

R = 'web-api/src/main/java/com/cryptoautotrader/api/service/RiskManagementService.java'
P = 'web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java'
REPO = 'web-api/src/main/java/com/cryptoautotrader/api/repository/paper/PaperPositionRepository.java'
CTRL = 'web-api/src/main/java/com/cryptoautotrader/api/controller/PaperTradingController.java'

# ── R6: 서킷 브레이커 조회 순서 복원 ─────────────────────────────────────────
patch(R, [
('''        // 연속 손실 입력 — 종전 루프와 같은 순서·같은 중단 조건(이번 가동 이전 청산분에서 끊는다). 판정은 아래 순수 함수와 같다.
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
''',
'''        // 🔴 종전 순서 그대로 (2026-11-11, 총괄 v1.147 R6): 설정 → 비활성이면 조회 없이 통과 → MDD 초과면 조회 없이 발동 → 그 뒤에만
        //    CLOSED 조회. 9491166f 는 조회를 앞에 둬 비활성·MDD 경로에서도 조회(와 그 예외)가 일어났다. 연속 손실 입력은 지연 공급한다.
        return evaluateCircuitBreaker(getRiskConfig(), mddPeak, initialCapital, totalAssetKrw, () -> {
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
            return pnlNewestFirst;
        });
'''),
('''        return evaluateCircuitBreaker(getRiskConfig(), mddPeak, initialCapital, totalAssetKrw, closedPnlNewestFirst);
    }
''', '''        return evaluateCircuitBreaker(getRiskConfig(), mddPeak, initialCapital, totalAssetKrw, () -> closedPnlNewestFirst);
    }
'''),
('''    private static CircuitBreakerResult evaluateCircuitBreaker(RiskConfigEntity config, BigDecimal mddPeak,
                                                               BigDecimal initialCapital, BigDecimal totalAssetKrw,
                                                               List<BigDecimal> closedPnlNewestFirst) {''',
'''    private static CircuitBreakerResult evaluateCircuitBreaker(RiskConfigEntity config, BigDecimal mddPeak,
                                                               BigDecimal initialCapital, BigDecimal totalAssetKrw,
                                                               java.util.function.Supplier<List<BigDecimal>> closedPnlNewestFirst) {'''),
('''        int consecutiveLosses = 0;
        for (BigDecimal pnl : closedPnlNewestFirst) {''', '''        int consecutiveLosses = 0;
        for (BigDecimal pnl : closedPnlNewestFirst.get()) {'''),
])

# ── 저장소: 상태별 조회 ─────────────────────────────────────────────────
patch(REPO, [
('''    List<PaperPositionEntity> findBySessionIdAndStatus(Long sessionId, String status);''',
'''    List<PaperPositionEntity> findBySessionIdAndStatus(Long sessionId, String status);

    /** 청산 대기(CLOSING) 복원용 — LIVE_ALIGNED 정지/시간 청산 판단 뒤 유효 가격을 기다리는 포지션 (2026-11-11). */
    List<PaperPositionEntity> findByStatus(String status);'''),
])

# ── 컨트롤러: 정지 응답에 청산 대기 수 ────────────────────────────────────
patch(CTRL, [
('''            return ApiResponse.ok(Map.of(
                    "id", session.getId(),
                    "status", session.getStatus(),
                    "finalAsset", session.getTotalKrw(),
                    "stoppedAt", session.getStoppedAt().toString()
            ));''',
'''            // 청산 대기(LIVE_ALIGNED, 유효 가격 없음)가 남았으면 정산 완료가 아니다 — 수와 함께 알린다 (2026-11-11). 종전 세션은 늘 0.
            long pendingCloses = paperTradingService.pendingCloseCount(sessionId);
            return ApiResponse.ok(Map.of(
                    "id", session.getId(),
                    "status", session.getStatus(),
                    "finalAsset", session.getTotalKrw(),
                    "stoppedAt", session.getStoppedAt().toString(),
                    "pendingCloseCount", pendingCloses,
                    "settlement", pendingCloses == 0 ? "완료" : "청산 대기 — 첫 유효 시세에 정산"
            ));'''),
])

# ── PaperTradingService ─────────────────────────────────────────────────
patch(P, [
# 정지: 선택 모드는 유효 시세가 없으면 청산 대기
('''        alignedPendingTimeExit.keySet().removeIf(posId -> positionRepo.findById(posId)
                .map(x -> sessionId.equals(x.getSessionId())).orElse(true));
        alignedPeakTotalKrw.remove(sessionId);
''', '''        // 청산 대기(CLOSING)는 지우지 않는다 — 첫 유효 시세에 정산한다(v1.147 R2).
        alignedPeakTotalKrw.remove(sessionId);
'''),
('''            if (alignedStop) {
                // LIVE_ALIGNED (2026-11-11, §3-2 정지 경계): LIVE 정지는 시장가 매도라 가격이 늘 정해진다. 종전 PAPER 는 M1·REST 가 없으면
                // 0 원에 강제 청산한다(B05 관측). 선택 모드는 실시간 현재가 → M1/REST → 그 세션 주기의 최근 봉 종가 순으로 쓰고 출처를 남긴다.
                String[] source = new String[1];
                BigDecimal p = alignedStopPrice(pos.getCoinPair(), session.getTimeframe(), source);
                closePosition(pos, p, session, "모의투자 중단 - 강제 청산 (가격 출처: " + source[0] + ")", ExitReason.FORCED_STOP);
                return;
            }''',
'''            if (alignedStop) {
                // LIVE_ALIGNED (2026-11-11, 총괄 v1.147 R2): 유효 가격(시세 장부의 기존 유효기간 계약)이 있을 때만 그 가격으로 정산한다.
                // 없으면 0원·오래된 봉 종가로 체결하지 않고 청산 대기(CLOSING, FORCED_STOP)로 두며 첫 유효 시세에서 한 번 정산한다.
                BigDecimal p = markPriceBook == null ? null : markPriceBook.freshPrice(pos.getCoinPair(), Instant.now());
                if (MarkPriceBook.isPrice(p)) {
                    closePosition(pos, p, session, "모의투자 중단 - 강제 청산 (실시간 현재가 " + p.toPlainString() + ")", ExitReason.FORCED_STOP);
                } else {
                    markClosingInTx(pos, ExitReason.FORCED_STOP, "정지 — 유효 시세 없음");
                }
                return;
            }'''),
# 틱: 청산 대기 우선 정산
('''        // 🔴 절대 하한 — 현재가조차 못 잡으면 청산 판정도 불가하므로 사이클을 건너뛴다.
        if (candles.size() < 10) {
            log.warn("모의투자 캔들 부족: {} {}건 (sessionId={})", coinPair, candles.size(), sessionId);
            if (aligned) alignedExitsWithoutCandles(session, coinPair);
            return;
        }
''', '''        // 🔴 절대 하한 — 현재가조차 못 잡으면 청산 판정도 불가하므로 사이클을 건너뛴다.
        if (candles.size() < 10) {
            log.warn("모의투자 캔들 부족: {} {}건 (sessionId={})", coinPair, candles.size(), sessionId);
            if (aligned) alignedExitsWithoutCandles(session, coinPair);
            return;
        }
        if (aligned) {
            // 청산 대기(이미 내린 청산 판단)가 있으면 다른 판단보다 먼저, 정해 둔 사유로 이 회차 현재가에 정산한다(v1.147 R3).
            // 대기 중에는 새 진입·새 청산 판단을 하지 않는다(LIVE 의 CLOSING 과 같다).
            Optional<PaperPositionEntity> closing = positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "CLOSING");
            if (closing.isPresent()) {
                settleClosing(closing.get(), candles.get(candles.size() - 1).getClose(), "주기 회차 현재가");
                return;
            }
        }
'''),
# 회차 밖 BUY (선택 모드): 한 트랜잭션 + 위험 한도
('''                    boolean bought = executeBuy(sessionId, coinPair, currentPrice, session, finalSignal, evalCandles,
                            preEvalRegime != null ? preEvalRegime.name() : null);
                    saveSignalQuality(savedSignalLog, bought, bought ? null : "매수 실행 실패(최소주문/잔고 미달)");''',
'''                    final String regime = preEvalRegime != null ? preEvalRegime.name() : null;
                    final BigDecimal buyPrice = currentPrice;
                    // 선택 모드: 포지션·주문·차감을 한 트랜잭션으로(v1.147 3-2a). 종전 PAPER 는 그대로.
                    boolean bought = aligned
                            ? Boolean.TRUE.equals(optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 매수 #" + sessionId, st -> {
                                VirtualBalanceEntity fresh = balanceRepo.findById(sessionId).orElse(null);
                                if (fresh == null || !"RUNNING".equals(fresh.getStatus())) return false;
                                return executeBuy(sessionId, coinPair, buyPrice, fresh, finalSignal, evalCandles, regime);
                            }))
                            : executeBuy(sessionId, coinPair, currentPrice, session, finalSignal, evalCandles, regime);
                    saveSignalQuality(savedSignalLog, bought, bought ? null : "매수 실행 실패(최소주문/잔고 미달)");'''),
# 전략 SELL (선택 모드): 원자 청산
('''                    } else {
                        closePosition(pos, currentPrice, session, String.format(
                                "전략 신호: %s -- %s (pnl=%s%%)",
                                strategyName, finalSignal.getReason(), heldPnlPct.toPlainString()),
                                ExitReason.STRATEGY_SIGNAL);
                        saveSignalQuality(savedSignalLog, true, null);
                    }''',
'''                    } else {
                        String why = String.format("전략 신호: %s -- %s (pnl=%s%%)",
                                strategyName, finalSignal.getReason(), heldPnlPct.toPlainString());
                        if (aligned) {
                            closeAligned(pos, currentPrice, session, why, ExitReason.STRATEGY_SIGNAL);   // 한 트랜잭션(v1.147 3-2a)
                        } else {
                            closePosition(pos, currentPrice, session, why, ExitReason.STRATEGY_SIGNAL);
                        }
                        saveSignalQuality(savedSignalLog, true, null);
                    }'''),
# 필드: 보류 지도 → 청산 대기
('''    /** 가격 없는 회차의 시간 초과 청산 판단 시각 (포지션 id → 판단 시각). 첫 유효 현재가에 체결하며 지운다. 재기동 뒤에는 조건이 다시 판단된다. */
    private final Map<Long, Instant> alignedPendingTimeExit = new ConcurrentHashMap<>();
''', '''    /**
     * 청산 대기(포지션 status=CLOSING, exit_reason=결정한 사유)의 판단 시각과 코인 (2026-11-11, 총괄 v1.147 R2~R4). 대기 자체는 DB 에 있고
     * 재기동 때 {@link #restoreAlignedClosing} 이 코인·포지션을 다시 올린다. 판단 시각은 표에 열이 없어 메모리에만 있다 — 재기동 뒤에는
     * "판단 시각 미상(재기동 전)"으로 적는다(DDL 안은 별도 제출).
     */
    private final Map<Long, Instant> alignedClosingDecidedAt = new ConcurrentHashMap<>();
    private final Map<String, java.util.Set<Long>> alignedClosingByCoin = new ConcurrentHashMap<>();
'''),
# alignedPriceExits: 청산 대기면 정해 둔 사유로 정산
('''    private boolean alignedPriceExits(VirtualBalanceEntity session, PaperPositionEntity pos, BigDecimal price, String note) {
        if (pos.getTakeProfitPrice() != null && price.compareTo(pos.getTakeProfitPrice()) >= 0) {''',
'''    private boolean alignedPriceExits(VirtualBalanceEntity session, PaperPositionEntity pos, BigDecimal price, String note) {
        // 이미 내린 청산 판단(청산 대기)은 이후 가격으로 사유를 다시 고르지 않는다(v1.147 R3).
        PaperPositionEntity current = positionRepo.findById(pos.getId()).orElse(pos);
        if ("CLOSING".equals(current.getStatus())) {
            settleClosing(current, price, "현재가" + note);
            return true;
        }
        if (pos.getTakeProfitPrice() != null && price.compareTo(pos.getTakeProfitPrice()) >= 0) {'''),
('''            closeAligned(pos, price, session, String.format("시간 초과 청산%s — 보유 %d시간 ≥ %d시간%s",
                    note, heldHours, session.getMaxHoldHours(), pendingTimeExitNote(pos)), ExitReason.TIME_STOP);''',
'''            closeAligned(pos, price, session, String.format("시간 초과 청산%s — 보유 %d시간 ≥ %d시간",
                    note, heldHours, session.getMaxHoldHours()), ExitReason.TIME_STOP);'''),
# alignedStopPrice / pendingTimeExitNote / closeAligned 교체
('''    /** 정지 강제 청산 가격 — 실시간 현재가 → M1/REST → 최근 봉 종가. 모두 없으면 종전과 같은 값(0)을 쓰고 오류로 남긴다. */
    private BigDecimal alignedStopPrice(String coinPair, String timeframe, String[] source) {
        Instant now = Instant.now();
        BigDecimal p = markPriceBook == null ? null : markPriceBook.freshPrice(coinPair, now);
        if (MarkPriceBook.isPrice(p)) {
            source[0] = "실시간 현재가";
            return p;
        }
        p = fetchCurrentPrice(coinPair);
        if (MarkPriceBook.isPrice(p)) {
            source[0] = "M1/REST 현재가";
            return p;
        }
        List<Candle> cs = fetchRecentCandles(coinPair, timeframe);
        if (!cs.isEmpty() && MarkPriceBook.isPrice(cs.get(cs.size() - 1).getClose())) {
            Candle last = cs.get(cs.size() - 1);
            source[0] = "최근 " + timeframe + " 봉 종가(봉 시작 " + last.getTime() + ")";
            return last.getClose();
        }
        log.error("모의투자[LIVE_ALIGNED] 정지 강제 청산 가격 없음 — 종전 값으로 청산한다 ({}): {}", coinPair, p);
        source[0] = "가격 없음(종전 값 " + p + ")";
        return p;
    }

    /** 판단 시각(가격 없는 회차)과 체결가 시각을 나눠 적는다. 보류가 없으면 빈 문자열. */
    private String pendingTimeExitNote(PaperPositionEntity pos) {
        Instant decided = alignedPendingTimeExit.remove(pos.getId());
        return decided == null ? "" : " [청산 판단 " + decided + " / 체결가 시각 " + Instant.now() + "]";
    }
''', '''    // ── 청산 대기 (2026-11-11, 총괄 v1.147 R2~R4) ─────────────────────────────────────────

    /** 커밋 뒤 한 번 — 트랜잭션이 없으면 바로. 롤백·재시도된 시도의 부수효과(알림 적재·메모리 상태·성공 로그)를 남기지 않는다(v1.147 3-2a). */
    private static void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            r.run();
                        }
                    });
        } else {
            r.run();
        }
    }

    /** 이미 트랜잭션 안 — OPEN 이면 CLOSING 으로 바꾸고 결정한 사유를 적는다. 메모리 표시는 커밋 뒤. */
    private void markClosingInTx(PaperPositionEntity pos, ExitReason reason, String why) {
        PaperPositionEntity fresh = positionRepo.findById(pos.getId()).orElse(null);
        if (fresh == null || !"OPEN".equals(fresh.getStatus())) return;
        fresh.setStatus("CLOSING");
        fresh.setExitReason(reason);
        positionRepo.save(fresh);
        Instant decided = Instant.now();
        Long posId = fresh.getId();
        String coin = fresh.getCoinPair();
        afterCommit(() -> {
            alignedClosingDecidedAt.putIfAbsent(posId, decided);
            alignedClosingByCoin.computeIfAbsent(coin, k -> ConcurrentHashMap.newKeySet()).add(posId);
            log.warn("모의투자[LIVE_ALIGNED] 청산 대기 — {} 판단 {} ({}) 유효 시세가 없어 첫 유효 시세에 정산한다 (posId={}, {})",
                    reason, decided, why, posId, coin);
        });
    }

    private void markClosing(PaperPositionEntity pos, ExitReason reason, String why) {
        optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 청산 대기 #" + pos.getId(), st -> {
            markClosingInTx(pos, reason, why);
            return null;
        });
    }

    /**
     * 청산 대기 정산 — 유효 가격에서 한 번. 사유는 대기 때 정한 값 그대로다(가격으로 다시 고르지 않는다). 트랜잭션 안에서 다시 읽어 CLOSING
     * 일 때만 닫는다. 0·음수·null 가격이면 아무것도 하지 않는다.
     */
    private void settleClosing(PaperPositionEntity pos, BigDecimal price, String source) {
        if (!MarkPriceBook.isPrice(price)) return;
        Long posId = pos.getId();
        synchronized (alignedLock(pos.getSessionId())) {
            optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 청산 대기 정산 #" + posId, st -> {
                PaperPositionEntity fresh = positionRepo.findById(posId).orElse(null);
                VirtualBalanceEntity s = fresh == null ? null : balanceRepo.findById(fresh.getSessionId()).orElse(null);
                if (fresh == null || s == null || !"CLOSING".equals(fresh.getStatus())) return null;
                Instant decided = alignedClosingDecidedAt.get(posId);
                ExitReason reason = fresh.getExitReason() != null ? fresh.getExitReason() : ExitReason.UNKNOWN;
                closePosition(fresh, price, s, "청산 대기 정산(" + reason + ", " + source + " " + price.toPlainString() + ") [청산 판단 "
                        + (decided == null ? "미상(재기동 전)" : decided.toString()) + " / 체결가 시각 " + Instant.now() + "]", reason);
                return null;
            });
            afterClosed(posId, pos.getCoinPair());
        }
    }

    /** 닫힌 뒤(커밋된 뒤) 메모리 표시를 지운다. DB 가 CLOSING 이 아니면 지운다 — 정산이 실패했으면 남는다. */
    private void afterClosed(Long posId, String coin) {
        boolean stillClosing = positionRepo.findById(posId).map(x -> "CLOSING".equals(x.getStatus())).orElse(false);
        if (stillClosing) return;
        alignedClosingDecidedAt.remove(posId);
        java.util.Set<Long> set = alignedClosingByCoin.get(coin);
        if (set != null) {
            set.remove(posId);
            if (set.isEmpty()) alignedClosingByCoin.remove(coin, set);
        }
    }

    /** 이 세션의 청산 대기 수 — 정지 API 가 정산 완료 여부를 알리는 데 쓴다. */
    public long pendingCloseCount(Long sessionId) {
        return positionRepo.findBySessionIdAndStatus(sessionId, "CLOSING").size();
    }

    /** 재기동 복원 — DB 의 CLOSING 포지션(선택 모드)을 실시간·주기 정산 대상에 다시 올린다. 판단 시각은 미상이다. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void restoreAlignedClosing() {
        int n = 0;
        for (PaperPositionEntity p : positionRepo.findByStatus("CLOSING")) {
            VirtualBalanceEntity s = balanceRepo.findById(p.getSessionId()).orElse(null);
            if (s == null || !isAligned(s)) continue;
            alignedClosingByCoin.computeIfAbsent(p.getCoinPair(), k -> ConcurrentHashMap.newKeySet()).add(p.getId());
            n++;
        }
        if (n > 0) log.warn("모의투자[LIVE_ALIGNED] 청산 대기 {}건 복원 — 첫 유효 시세에 정산한다(판단 시각 미상)", n);
    }

    /** 주기 정산 — 청산 대기 코인마다 시세 장부의 유효 가격이 있으면 정산한다(정지된 세션 포함). */
    private void settleClosingFromMarkBook() {
        if (alignedClosingByCoin.isEmpty() || markPriceBook == null) return;
        for (var e : new java.util.ArrayList<>(alignedClosingByCoin.entrySet())) {
            BigDecimal p = markPriceBook.freshPrice(e.getKey(), Instant.now());
            if (!MarkPriceBook.isPrice(p)) continue;
            for (Long posId : new java.util.ArrayList<>(e.getValue())) {
                positionRepo.findById(posId).ifPresent(pos -> settleClosing(pos, p, "시세 장부 유효 현재가"));
            }
        }
    }
'''),
('''        optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 청산 #" + pos.getId(), st -> {
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
    }''', '''        optimisticRetry.inNewTransaction("모의투자[LIVE_ALIGNED] 청산 #" + pos.getId(), st -> {
            PaperPositionEntity fresh = positionRepo.findById(pos.getId()).orElse(null);
            VirtualBalanceEntity s = balanceRepo.findById(session.getId()).orElse(null);
            if (fresh == null || s == null || !"OPEN".equals(fresh.getStatus())) {
                log.info("모의투자[LIVE_ALIGNED] 청산 생략 — 이미 닫힘 또는 청산 대기 (posId={})", pos.getId());
                return null;
            }
            closePosition(fresh, signalPrice, s, reason, exitReason);
            return null;
        });
    }'''),
# 캔들 없는 회차
('''    private void alignedExitsWithoutCandles(VirtualBalanceEntity session, String coinPair) {
        Optional<PaperPositionEntity> open = positionRepo.findBySessionIdAndCoinPairAndStatus(session.getId(), coinPair, "OPEN");
        if (open.isEmpty()) return;
        BigDecimal price = markPriceBook == null ? null : markPriceBook.freshPrice(coinPair, Instant.now());
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
            }''', '''    private void alignedExitsWithoutCandles(VirtualBalanceEntity session, String coinPair) {
        BigDecimal price = markPriceBook == null ? null : markPriceBook.freshPrice(coinPair, Instant.now());
        Optional<PaperPositionEntity> closing = positionRepo.findBySessionIdAndCoinPairAndStatus(session.getId(), coinPair, "CLOSING");
        if (closing.isPresent()) {
            if (MarkPriceBook.isPrice(price)) settleClosing(closing.get(), price, "실시간 현재가(캔들 판정 불가 회차)");
            return;
        }
        Optional<PaperPositionEntity> open = positionRepo.findBySessionIdAndCoinPairAndStatus(session.getId(), coinPair, "OPEN");
        if (open.isEmpty()) return;
        if (price == null) {
            PaperPositionEntity pos = open.get();
            if (ExitRuleCalculator.shouldTimeStop(session.getMaxHoldHours(), pos.getOpenedAt(), Instant.now())) {
                // LIVE 는 여기서 가격 없이 시장가 매도 의도를 낸다(CLOSING). 모의 체결에는 가격이 필요하므로 같은 판단을 청산 대기로 남기고
                // 첫 유효 현재가(실시간 처리기·다음 회차·시세 장부 주기 정산)에 그 사유 그대로 체결한다.
                markClosing(pos, ExitReason.TIME_STOP, String.format("캔들 판정 불가 + 유효한 현재가 없음, 보유 %dh ≥ %dh",
                        Duration.between(pos.getOpenedAt(), Instant.now()).toHours(), session.getMaxHoldHours()));
                return;
            }'''),
# R5: 위험 입력 정의 + MDD 피크 복원
('''    private CircuitBreakerResult alignedCircuitBreaker(VirtualBalanceEntity s) {
        BigDecimal total = nz(s.getTotalKrw());
        BigDecimal peak = alignedPeakTotalKrw.merge(s.getId(), total.max(nz(s.getInitialCapital())), BigDecimal::max);
        List<BigDecimal> pnlNewestFirst = positionRepo.findBySessionIdAndStatus(s.getId(), "CLOSED").stream()
                .sorted(java.util.Comparator.comparing((PaperPositionEntity p) -> p.getClosedAt() == null ? Instant.EPOCH : p.getClosedAt())
                        .reversed())
                .map(PaperPositionEntity::getRealizedPnl)
                .toList();''', '''    private CircuitBreakerResult alignedCircuitBreaker(VirtualBalanceEntity s) {
        BigDecimal total = nz(s.getTotalKrw());
        List<PaperPositionEntity> closed = positionRepo.findBySessionIdAndStatus(s.getId(), "CLOSED");
        // MDD 피크 — 메모리에 없으면(재기동 뒤) 청산 이력의 실현 자산 경로에서 복원한다(v1.147 3-2c 임시안). 미실현 고점은 복원되지 않는다
        // (LIVE 는 mdd_peak_capital 열에 저장 — 모의 표 열 추가 DDL 안은 별도 제출).
        BigDecimal peak = alignedPeakTotalKrw.computeIfAbsent(s.getId(), k -> realizedEquityPeak(s, closed));
        peak = alignedPeakTotalKrw.merge(s.getId(), total.max(peak), BigDecimal::max);
        List<BigDecimal> pnlNewestFirst = closed.stream()
                .sorted(java.util.Comparator.comparing((PaperPositionEntity p) -> p.getClosedAt() == null ? Instant.EPOCH : p.getClosedAt())
                        .reversed())
                .map(PaperTradingService::riskPnl)
                .toList();'''),
('''    private static BigDecimal alignedGrossLossPct(List<PaperPositionEntity> closed, Instant since, BigDecimal cap) {
        BigDecimal loss = closed.stream()
                .filter(p -> p.getClosedAt() != null && !p.getClosedAt().isBefore(since))
                .map(PaperPositionEntity::getRealizedPnl).filter(v -> v != null && v.signum() < 0)''',
'''    /**
     * 위험 판단 입력 손익 — LIVE 관례(매도 순수익 − 수량 × 체결 평균가, 매수 수수료는 빼지 않음)로 맞춘다 (2026-11-11, 총괄 v1.147 R5).
     * 성적표의 {@code realizedPnl}(투입금 기준, 매수 수수료 포함)은 바꾸지 않는다. 선택 모드의 avgPrice 는 체결가이므로
     * realizedPnl + (investedKrw − size × avgPrice) 가 LIVE 정의의 손익이다. 값이 모자라면 realizedPnl 그대로.
     */
    static BigDecimal riskPnl(PaperPositionEntity p) {
        if (p.getRealizedPnl() == null || p.getInvestedKrw() == null || p.getSize() == null || p.getAvgPrice() == null) {
            return p.getRealizedPnl();
        }
        return p.getRealizedPnl().add(p.getInvestedKrw().subtract(p.getSize().multiply(p.getAvgPrice())));
    }

    /** 실현 자산 경로의 고점 — 초기 자본에서 청산 순서대로 실현 손익(현금 기준)을 더한 값의 최대. */
    private static BigDecimal realizedEquityPeak(VirtualBalanceEntity s, List<PaperPositionEntity> closed) {
        BigDecimal eq = nz(s.getInitialCapital());
        BigDecimal peak = eq;
        for (PaperPositionEntity p : closed.stream()
                .sorted(java.util.Comparator.comparing((PaperPositionEntity x) -> x.getClosedAt() == null ? Instant.EPOCH : x.getClosedAt()))
                .toList()) {
            eq = eq.add(nz(p.getRealizedPnl()));
            peak = peak.max(eq);
        }
        return peak;
    }

    private static BigDecimal alignedGrossLossPct(List<PaperPositionEntity> closed, Instant since, BigDecimal cap) {
        BigDecimal loss = closed.stream()
                .filter(p -> p.getClosedAt() != null && !p.getClosedAt().isBefore(since))
                .map(PaperTradingService::riskPnl).filter(v -> v != null && v.signum() < 0)'''),
# 승인: 청산 대기면 진입 없음, 쓰기 직전 만료 재검사
('''                } else if (positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN").isPresent()) {
                    result = "승인 차례에 이미 포지션이 있다";''', '''                } else if (positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN").isPresent()) {
                    result = "승인 차례에 이미 포지션이 있다";
                } else if (positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "CLOSING").isPresent()) {
                    result = "승인 차례에 포지션 청산 대기 중";'''),
('''                        alignedRaceHook.accept("APPROVE_BEFORE_BUY");
                        boolean bought = executeBuy(sessionId, coinPair, priceNow, session, signal, evalCandles, marketRegime);
                        result = bought ? null : "매수 실행 실패(최소주문/잔고 미달)";''', '''                        alignedRaceHook.accept("APPROVE_BEFORE_BUY");
                        try {
                            boolean bought = executeBuy(sessionId, coinPair, priceNow, session, signal, evalCandles, marketRegime, expiresAt);
                            result = bought ? null : "매수 실행 실패(최소주문/잔고 미달)";
                        } catch (AlignedEntryExpired expired) {
                            result = expired.getMessage();
                        }'''),
# 실시간: 청산 대기 정산은 간격 검사 전
('''    void doOnAlignedRealtimePrice(RealtimePriceEvent event) {
        String coin = event.getCoinCode();
        if (!alignedCoins.contains(coin)) return;
        BigDecimal price = event.getPrice();
        if (markPriceBook != null && markPriceBook.isStale(event.getObservedAt(), Instant.now())) return;
        if (!MarkPriceBook.isPrice(price)) return;
        long now = alignedNowMs.getAsLong();
''', '''    void doOnAlignedRealtimePrice(RealtimePriceEvent event) {
        String coin = event.getCoinCode();
        java.util.Set<Long> closingHere = alignedClosingByCoin.get(coin);
        if (!alignedCoins.contains(coin) && (closingHere == null || closingHere.isEmpty())) return;
        BigDecimal price = event.getPrice();
        if (markPriceBook != null && markPriceBook.isStale(event.getObservedAt(), Instant.now())) return;
        if (!MarkPriceBook.isPrice(price)) return;
        // 이미 내린 청산 판단(청산 대기)은 손절 확인 간격과 무관하게 **첫 유효 현재가**에 정산한다(v1.147 R4). 정지된 세션도 포함.
        if (closingHere != null && !closingHere.isEmpty()) {
            for (Long posId : new java.util.ArrayList<>(closingHere)) {
                positionRepo.findById(posId).ifPresent(pos -> settleClosing(pos, price, "실시간 현재가"));
            }
        }
        if (!alignedCoins.contains(coin)) return;
        long now = alignedNowMs.getAsLong();
'''),
('''                PaperPositionEntity pos = open.get();
                if (alignedPendingTimeExit.containsKey(pos.getId())) {
                    // 가격 없는 회차에 이미 내린 시간 초과 청산 판단 — 이 가격이 첫 유효 현재가다.
                    long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
                    closeAligned(pos, price, session, String.format("시간 초과 청산(실시간 현재가 %s) — 보유 %d시간 ≥ %d시간%s",
                            price.toPlainString(), heldHours, session.getMaxHoldHours(), pendingTimeExitNote(pos)), ExitReason.TIME_STOP);
                    continue;
                }
''', '''                PaperPositionEntity pos = open.get();
'''),
# 리스너 거르기: 청산 대기 코인도
('''        if (!alignedCoins.contains(event.getCoinCode())) return;
        Runnable task = () -> {''', '''        java.util.Set<Long> closingHere = alignedClosingByCoin.get(event.getCoinCode());
        if (!alignedCoins.contains(event.getCoinCode()) && (closingHere == null || closingHere.isEmpty())) return;
        Runnable task = () -> {'''),
# 주기 정산: runStrategy 끝
('''        refreshAlignedCoins(runningSessions);
    }
''', '''        refreshAlignedCoins(runningSessions);
        try {
            settleClosingFromMarkBook();
        } catch (Exception e) {
            log.error("모의투자[LIVE_ALIGNED] 청산 대기 주기 정산 오류: {}", e.getMessage(), e);
        }
    }
'''),
# executeBuy: 만료 E 를 쓰기 직전까지
('''    private boolean executeBuy(Long sessionId, String coinPair, BigDecimal signalPrice,
                                VirtualBalanceEntity session, StrategySignal signal,
                                List<Candle> evalCandles, String marketRegime) {''',
'''    private boolean executeBuy(Long sessionId, String coinPair, BigDecimal signalPrice,
                                VirtualBalanceEntity session, StrategySignal signal,
                                List<Candle> evalCandles, String marketRegime) {
        return executeBuy(sessionId, coinPair, signalPrice, session, signal, evalCandles, marketRegime, null);
    }

    /** 승인 실행 만료 — 쓰기 직전 재검사에서 걸리면 던진다. 아무것도 쓰기 전이다(v1.147 R1). */
    static final class AlignedEntryExpired extends RuntimeException {
        AlignedEntryExpired(String message) {
            super(message);
        }
    }

    /**
     * @param expiresAt 회차 승인 실행 만료 E (선택 모드 승인만). 마지막 지연 가능한 조회(규칙 지문) 뒤, 첫 쓰기 직전에 같은 시계로 다시 본다.
     *                  그 시점이 확정 경계다 — 쓰기를 시작한 매수는 같은 트랜잭션으로 모두 반영되거나(커밋) 모두 되돌려진다(예외). 이미 확정된
     *                  체결을 나중에 취소하지 않는다. LIVE 의 "잠금 안 예약 직전 E 재확인"과 같은 자리다.
     */
    private boolean executeBuy(Long sessionId, String coinPair, BigDecimal signalPrice,
                                VirtualBalanceEntity session, StrategySignal signal,
                                List<Candle> evalCandles, String marketRegime, Instant expiresAt) {'''),
('''        String rulesetHash = rulesetRegistry.hashFor(session);
        PaperPositionEntity pos = PaperPositionEntity.builder()''',
'''        String rulesetHash = rulesetRegistry.hashFor(session);
        if (expiresAt != null) {
            alignedRaceHook.accept("BUY_BEFORE_WRITE");
            Instant at = entryClock != null ? entryClock.now() : Instant.now();
            if (EntryPriorityRounds.isExpired(expiresAt, at)) {
                throw new AlignedEntryExpired("승인 실행 만료(쓰기 직전 판정 " + at + ", 만료 " + expiresAt + ") — 모의 매수 없음");
            }
        }
        PaperPositionEntity pos = PaperPositionEntity.builder()'''),
('''        pos = positionRepo.save(pos);

        PaperOrderEntity order = PaperOrderEntity.builder()
                .sessionId(sessionId)
                .positionId(pos.getId())
                .coinPair(coinPair)
                .side("BUY")''', '''        pos = positionRepo.save(pos);
        if (alignedMode) alignedRaceHook.accept("WRITE_AFTER_POSITION");

        PaperOrderEntity order = PaperOrderEntity.builder()
                .sessionId(sessionId)
                .positionId(pos.getId())
                .coinPair(coinPair)
                .side("BUY")'''),
('''        log.info("모의 매수 체결 (sessionId={}): {} {}개 @ {} SL={} TP={} (수수료: {})",
                sessionId, coinPair, quantity, price, stopLossPrice, takeProfitPrice, fee);

        if (Boolean.TRUE.equals(session.getTelegramEnabled())) {
            telegramService.bufferTradeEvent(
                    "[모의투자] 세션#" + sessionId, coinPair, "BUY",
                    price, quantity, fee, null, signal.getReason());
        }
        return true;''', '''        final BigDecimal fPrice = price, fQty = quantity, fFee = fee, fSl = stopLossPrice, fTp = takeProfitPrice;
        final boolean tg = Boolean.TRUE.equals(session.getTelegramEnabled());
        final String sigReason = signal.getReason();
        Runnable report = () -> {
            log.info("모의 매수 체결 (sessionId={}): {} {}개 @ {} SL={} TP={} (수수료: {})",
                    sessionId, coinPair, fQty, fPrice, fSl, fTp, fFee);
            if (tg) {
                telegramService.bufferTradeEvent(
                        "[모의투자] 세션#" + sessionId, coinPair, "BUY",
                        fPrice, fQty, fFee, null, sigReason);
            }
        };
        if (alignedMode) afterCommit(report); else report.run();   // 선택 모드는 커밋 뒤 한 번(v1.147 3-2a)
        return true;'''),
# closePosition: 선택 모드 훅·커밋 뒤 보고
('''        pos.setExitReason(exitReason != null ? exitReason : ExitReason.UNKNOWN);
        positionRepo.save(pos);
''', '''        pos.setExitReason(exitReason != null ? exitReason : ExitReason.UNKNOWN);
        positionRepo.save(pos);
        final boolean alignedClose = isAligned(session);
        if (alignedClose) alignedRaceHook.accept("WRITE_AFTER_POSITION");
'''),
('''        log.info("모의 매도 체결 (sessionId={}): {} {}개 @ {} 손익: {} KRW",
                pos.getSessionId(), pos.getCoinPair(), pos.getSize(), currentPrice, realizedPnl);

        if (Boolean.TRUE.equals(session.getTelegramEnabled())) {
            telegramService.bufferTradeEvent(
                    "[모의투자] 세션#" + pos.getSessionId(), pos.getCoinPair(), "SELL",
                    currentPrice, pos.getSize(), fee, realizedPnl, reason);
        }''', '''        final boolean tg = Boolean.TRUE.equals(session.getTelegramEnabled());
        final BigDecimal fFee = fee;
        Runnable report = () -> {
            log.info("모의 매도 체결 (sessionId={}): {} {}개 @ {} 손익: {} KRW",
                    pos.getSessionId(), pos.getCoinPair(), pos.getSize(), currentPrice, realizedPnl);
            if (tg) {
                telegramService.bufferTradeEvent(
                        "[모의투자] 세션#" + pos.getSessionId(), pos.getCoinPair(), "SELL",
                        currentPrice, pos.getSize(), fFee, realizedPnl, reason);
            }
        };
        if (alignedClose) afterCommit(report); else report.run();   // 선택 모드는 커밋 뒤 한 번(v1.147 3-2a)'''),
])
print('ok')
