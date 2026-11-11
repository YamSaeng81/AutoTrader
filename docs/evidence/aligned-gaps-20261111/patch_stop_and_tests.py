import io
def patch(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        n = s.count(old); assert n == 1, (path, n, old[:90])
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)

P = 'web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java'
patch(P, [
('''        List<PaperPositionEntity> openPositions = positionRepo.findBySessionIdAndStatus(sessionId, "OPEN");
        openPositions.forEach(pos -> {
            BigDecimal currentPrice = fetchCurrentPrice(pos.getCoinPair());
            closePosition(pos, currentPrice, session, "모의투자 중단 - 강제 청산", ExitReason.FORCED_STOP);
        });
''',
'''        List<PaperPositionEntity> openPositions = positionRepo.findBySessionIdAndStatus(sessionId, "OPEN");
        final boolean alignedStop = isAligned(session);
        openPositions.forEach(pos -> {
            if (alignedStop) {
                // LIVE_ALIGNED (2026-11-11, §3-2 정지 경계): LIVE 정지는 시장가 매도라 가격이 늘 정해진다. 종전 PAPER 는 M1·REST 가 없으면
                // 0 원에 강제 청산한다(B05 관측). 선택 모드는 실시간 현재가 → M1/REST → 그 세션 주기의 최근 봉 종가 순으로 쓰고 출처를 남긴다.
                String[] source = new String[1];
                BigDecimal p = alignedStopPrice(pos.getCoinPair(), session.getTimeframe(), source);
                closePosition(pos, p, session, "모의투자 중단 - 강제 청산 (가격 출처: " + source[0] + ")", ExitReason.FORCED_STOP);
                return;
            }
            BigDecimal currentPrice = fetchCurrentPrice(pos.getCoinPair());
            closePosition(pos, currentPrice, session, "모의투자 중단 - 강제 청산", ExitReason.FORCED_STOP);
        });
'''),
('''    /** 판단 시각(가격 없는 회차)과 체결가 시각을 나눠 적는다. 보류가 없으면 빈 문자열. */
''',
'''    /** 정지 강제 청산 가격 — 실시간 현재가 → M1/REST → 최근 봉 종가. 모두 없으면 종전과 같은 값(0)을 쓰고 오류로 남긴다. */
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
'''),
])

T = 'web-api/src/test/java/com/cryptoautotrader/api/service/Sma20LiveAlignedGapsTest.java'
patch(T, [
# B01: LIVE 평균가 정의(체결금액 ÷ 체결수량)와 비교
('''        assertThat(pp.getAvgPrice()).as("판단 평균가 = 체결가").isEqualByComparingTo(fill);
        assertThat(lp.getAvgPrice()).isEqualByComparingTo(fill);
''',
'''        assertThat(pp.getAvgPrice()).as("판단 평균가 = 체결가").isEqualByComparingTo(fill);
        // 1회차는 LIVE 평균가도 체결가와 같다고 적었다가 실패했다(12507495 대 12507511.39) — LIVE 정의는 체결금액 ÷ 체결수량이고, 대역의 체결수량은
        // 8자리 버림이라 그만큼 다르다. 시험 기대의 결함이다. LIVE 는 자기 정의로, 두 평균가의 차이는 수량 버림 크기(0.001% 미만)로 본다.
        OrderResponse filled = buyFilled(buy, fill);
        assertThat(lp.getAvgPrice()).isEqualByComparingTo(
                filled.getExecutedFunds().divide(filled.getExecutedVolume(), 8, RoundingMode.HALF_UP));
        assertThat(lp.getAvgPrice().subtract(pp.getAvgPrice()).abs().divide(pp.getAvgPrice(), 10, RoundingMode.HALF_UP))
                .as("판단 평균가 차이는 수량 버림뿐 — 종전 수수료 관례 차이(0.05%%p)보다 50배 이상 작다")
                .isLessThan(new BigDecimal("0.00001"));
'''),
# B02: 이 시나리오 시작에 PAPER 가격 이력도 비운다(LIVE 는 fixture setUp 이 비운다)
('''        paperMap("alignedRtLastCheckMs").remove(BTC);
        paperMap("alignedSpikeLastCheckMs").remove(BTC);

        long[] clock = {1_000_000L};
''',
'''        paperMap("alignedRtLastCheckMs").remove(BTC);
        paperMap("alignedSpikeLastCheckMs").remove(BTC);
        // 1회차는 이 줄이 없어 앞 시나리오(B06)가 실제 시계로 남긴 BTC 가격 이력이 창 안으로 읽혀 첫 시세부터 급변으로 잡혔다(3단계 실패) —
        // 하네스 결함. LIVE 쪽 이력은 fixture setUp 이 이미 비운다. 열 안에서는 아무것도 지우지 않는다.
        ((Map<?, ?>) ReflectionTestUtils.getField(paperTarget(), "alignedPriceHistory")).remove(BTC);

        long[] clock = {1_000_000L};
'''),
# B05: 정지 강제 청산 가격 출처 고정
('''    void b05StopBetweenApprovalCheckAndBuy() {
        insertCache(BTC, uptrend(lastOpen));
        VirtualBalanceEntity p = paper(BTC);
''',
'''    void b05StopBetweenApprovalCheckAndBuy() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        markPriceBook.remove(BTC);   // 실시간 현재가 없음 → M1/REST 없음(대역) → 최근 봉 종가가 정지 청산 가격
        VirtualBalanceEntity p = paper(BTC);
'''),
('''            assertThat(after.getTotalKrw()).isEqualByComparingTo(after.getAvailableKrw());
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("B06''',
'''            assertThat(after.getTotalKrw()).isEqualByComparingTo(after.getAvailableKrw());
            // 1회차(보완 전 정지 경로)는 M1·REST 가 없어 0 원에 강제 청산했다(가용 200). 선택 모드는 최근 봉 종가에 슬리피지.
            assertThat(sells).hasSize(1);
            assertThat(sells.get(0).getPrice()).as("정지 강제 청산 가격이 0 이면 포지션 가치 전부가 손실로 기록된다")
                    .isEqualByComparingTo(lastClose(cs).multiply(BigDecimal.ONE.subtract(SLIP)).setScale(8, RoundingMode.HALF_DOWN));
            assertThat(sells.get(0).getSignalReason()).contains("가격 출처: 최근 H1 봉 종가");
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("B06'''),
])
print('ok')
