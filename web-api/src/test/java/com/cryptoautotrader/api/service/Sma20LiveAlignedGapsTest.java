package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE_ALIGNED 에 남은 판단 차이·경계 (2026-11-11, 총괄 v1.145 §3-2 / v1.146 순서 3-2).
 *
 * <p>{@link Sma20LiveAlignedPaperSameInputTest} 의 14 시나리오는 바꾸지 않는다. 여기는 그 뒤에 남은 항목을 같은 대역·같은 운영
 * 호출부로 본다. 경합은 latch 로 고정한다 — sleep·반복·기대 완화 없음. 시간 초과는 실패다. 실시간 간격은 처리기의 시계를
 * 바꿔 경계를 고정하고, 간격 지도는 지우지 않는다.</p>
 */
@DisplayName("SMA20 LIVE_ALIGNED 남은 차이 — 평균가·급변 간격·위험 관문·가격 없는 시간 청산·정지/청산 경합")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=Sma20LiveAlignedGapsTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Sma20LiveAlignedGapsTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @Autowired private OrderExecutionEngine orderExecutionEngine;

    private static final BigDecimal FEE = new BigDecimal("0.0005");
    private static final BigDecimal SLIP = new BigDecimal("0.001");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "aligned-gaps";
    }

    private PaperTradingService paperTarget() {
        return AopTestUtils.getUltimateTargetObject(paperTradingService);
    }

    private LiveTradingService liveTarget() {
        return AopTestUtils.getUltimateTargetObject(liveTradingService);
    }

    private void refreshAligned() {
        ReflectionTestUtils.invokeMethod(paperTarget(), "refreshAlignedCoins",
                balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
    }

    /** 운영 스케줄러 {@code runStrategy} 의 세션 블록과 같은 경로 — 세션 잠금 안에서 다시 읽은 행으로 평가한다. */
    private void runPaperLocked(Long sessionId) {
        Object lock = ReflectionTestUtils.invokeMethod(paperTarget(), "alignedLock", sessionId);
        synchronized (lock) {
            VirtualBalanceEntity fresh = balanceRepo.findById(sessionId).orElse(null);
            if (fresh != null && "RUNNING".equals(fresh.getStatus())) {
                ReflectionTestUtils.invokeMethod(paperTarget(), "runSessionStrategy", fresh);
            }
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError(what + " — 15초 안에 도달하지 않았다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " — 인터럽트", e);
        }
    }

    private static <T> T get(Future<T> f, String what) {
        try {
            return f.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new AssertionError(what + " — 20초 안에 끝나지 않았다(미완료를 정상 종료로 보지 않는다)", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError(what + " — 예외로 끝났다: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " — 인터럽트", e);
        }
    }

    private List<PaperOrderEntity> paperOrders(Long sessionId, String side) {
        return paperOrderRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream().filter(o -> side.equals(o.getSide())).toList();
    }

    private static BigDecimal pct(BigDecimal price, BigDecimal base) {
        return price.subtract(base).divide(base, 6, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP);
    }

    /** 업비트 시장가 매수(price) 체결 응답 — 체결가를 PAPER 체결가와 같게 (A11 과 같은 대역). */
    private OrderResponse buyFilled(OrderEntity buy, BigDecimal fillPrice) {
        BigDecimal funds = new BigDecimal("20000").divide(new BigDecimal("1.0005"), 8, RoundingMode.DOWN);
        BigDecimal fee = funds.multiply(FEE).setScale(8, RoundingMode.HALF_UP);
        BigDecimal volume = funds.divide(fillPrice, 8, RoundingMode.DOWN);
        OrderResponse r = new OrderResponse();
        r.setUuid(buy.getExchangeOrderId());
        r.setSide("bid");
        r.setOrdType("price");
        r.setState("done");
        r.setMarket(buy.getCoinPair());
        r.setPrice(new BigDecimal("20000"));
        r.setExecutedVolume(volume);
        r.setExecutedFunds(funds);
        r.setPaidFee(fee);
        return r;
    }

    private OrderResponse sellFilled(OrderEntity sell, BigDecimal volume, BigDecimal fillPrice) {
        BigDecimal funds = volume.multiply(fillPrice).setScale(8, RoundingMode.HALF_UP);
        BigDecimal fee = funds.multiply(FEE).setScale(8, RoundingMode.HALF_UP);
        OrderResponse r = new OrderResponse();
        r.setUuid(sell.getExchangeOrderId());
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("done");
        r.setMarket(sell.getCoinPair());
        r.setVolume(volume);
        r.setExecutedVolume(volume);
        r.setExecutedFunds(funds);
        r.setPaidFee(fee);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> liveMap(String field) {
        return (Map<String, Long>) ReflectionTestUtils.getField(liveTarget(), field);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> paperMap(String field) {
        return (Map<String, Long>) ReflectionTestUtils.getField(paperTarget(), field);
    }

    @AfterEach
    void resetHooks() {
        PaperTradingService.alignedRaceHook = name -> { };
        paperTarget().alignedNowMs = System::currentTimeMillis;
    }

    // ── 평균가·수수료 ─────────────────────────────────────────────────

    @Test
    @DisplayName("B01 실제 매수 → +0.32% 전략 SELL 경계 → 매도 → 잔액: 판단 평균가는 둘 다 체결가, 가용 잔액 같음, 수수료 각 1회")
    void b01AveragePriceAndFeeEndToEnd() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        approveRound();
        PaperPositionEntity pp = paperPositions(p).get(0);
        OrderEntity buy = liveOrders(l, "BUY").get(0);
        Mockito.doReturn(buyFilled(buy, pp.getEntryPrice())).when(upbitOrderClient).getOrder(buy.getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(buy.getId());
        awaitDrained();
        PositionEntity lp = livePositions(l).get(0);
        BigDecimal fill = pp.getEntryPrice();
        BigDecimal feeInclusiveAvg = pp.getInvestedKrw().divide(pp.getSize(), 8, RoundingMode.HALF_UP);
        row("B01 평균가", "1 매수 뒤 판단 평균가", lp.getAvgPrice().stripTrailingZeros(), pp.getAvgPrice().stripTrailingZeros(), true);
        row("B01 평균가", "2 투입금(수수료 포함)", lp.getInvestedKrw(), pp.getInvestedKrw(), true);
        row("B01 평균가", "3 매수 뒤 가용 잔액", liveRepo.findById(l.getId()).orElseThrow().getAvailableKrw(),
                balanceRepo.findById(p.getId()).orElseThrow().getAvailableKrw(), true);
        row("B01 평균가", "4 종전 PAPER 관례 평균가(수수료 포함, 참고)", lp.getAvgPrice().stripTrailingZeros(), feeInclusiveAvg.stripTrailingZeros(), false);
        assertThat(pp.getAvgPrice()).as("판단 평균가 = 체결가").isEqualByComparingTo(fill);
        // 1회차는 LIVE 평균가도 체결가와 같다고 적었다가 실패했다(12507495 대 12507511.39) — LIVE 정의는 체결금액 ÷ 체결수량이고, 대역의 체결수량은
        // 8자리 버림이라 그만큼 다르다. 시험 기대의 결함이다. LIVE 는 자기 정의로, 두 평균가의 차이는 수량 버림 크기(0.001% 미만)로 본다.
        OrderResponse filled = buyFilled(buy, fill);
        assertThat(lp.getAvgPrice()).isEqualByComparingTo(
                filled.getExecutedFunds().divide(filled.getExecutedVolume(), 8, RoundingMode.HALF_UP));
        assertThat(lp.getAvgPrice().subtract(pp.getAvgPrice()).abs().divide(pp.getAvgPrice(), 10, RoundingMode.HALF_UP))
                .as("판단 평균가 차이는 수량 버림뿐 — 종전 수수료 관례 차이(0.05%%p)보다 50배 이상 작다")
                .isLessThan(new BigDecimal("0.00001"));
        assertThat(pp.getPositionFee()).as("매수 수수료 1회").isEqualByComparingTo(pp.getInvestedKrw().multiply(FEE).setScale(8, RoundingMode.HALF_UP));

        // 경계 입력: 체결가 +0.32% — 체결가 기준 pnl ≥ +0.30 이라 SELL 허용, 종전 수수료 포함 평균가(+0.05%p) 기준이면 +0.27 로 차단됐다.
        BigDecimal target = fill.multiply(new BigDecimal("1.0032")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal pnlByFill = pct(target, fill);
        BigDecimal pnlByFeeInclusive = pct(target, feeInclusiveAvg);
        row("B01 평균가", "5 경계가 +0.32% 손익(체결가 기준)", pnlByFill, pnlByFill, true);
        row("B01 평균가", "6 같은 가격을 종전 관례 평균가로 보면(참고)", pnlByFill, pnlByFeeInclusive, false);
        assertThat(pnlByFill).isGreaterThanOrEqualTo(new BigDecimal("0.300"));
        assertThat(pnlByFeeInclusive).as("종전 관례였다면 +0.30 미만이라 전략 SELL 이 차단됐을 값").isLessThan(new BigDecimal("0.300"));

        // 최소 보유 180분 — 보유 시작만 4시간 앞으로 옮긴다(회계·가격은 그대로). 둘 다 같은 값.
        Instant opened = Instant.now().minus(4, ChronoUnit.HOURS);
        lp.setOpenedAt(opened);
        positionRepository.save(lp);
        pp.setOpenedAt(opened);
        paperPositionRepo.save(pp);
        deleteCache();
        List<Candle> cs2 = series(lastOpen, i -> i == 499 ? target.longValue() : target.multiply(new BigDecimal("1.005")).longValue());
        insertCache(BTC, cs2);
        // 두 엔진 모두 같은 닫힌 봉은 다시 평가하지 않는다(newClosedCandle). 2회차는 이 줄이 없어 매수 봉으로 다시 평가해 둘 다 SELL 판단이
        // 없었다(하네스 결함). 회차 접수가 실제 시계로 마감을 보므로 매수를 더 이른 봉으로 옮길 수 없고, 다음 봉을 1시간 기다릴 수도 없다.
        // 그래서 "다음 닫힌 봉 도착"을 두 엔진의 마지막 평가 봉 표시를 같은 방식으로 지우는 것으로 대신한다. 판단 규칙·간격 지도는 그대로다.
        ((Map<?, ?>) ReflectionTestUtils.getField(liveTarget(), "lastEvaluatedClosedCandle")).remove(l.getId());
        ((Map<?, ?>) ReflectionTestUtils.getField(paperTarget(), "lastEvaluatedClosedCandle")).remove(p.getId());
        evaluateLive(liveRepo.findById(l.getId()).orElseThrow());
        runPaper(p);
        PaperPositionEntity pa = paperPositionRepo.findById(pp.getId()).orElseThrow();
        List<OrderEntity> sells = liveOrders(l, "SELL");
        row("B01 평균가", "7 전략 SELL 판단", sells.isEmpty() ? "없음" : "매도 접수", "CLOSED".equals(pa.getStatus()) ? "청산" : pa.getStatus(), false);
        assertThat(sells).as("LIVE 도 +0.32% 에서 전략 SELL").hasSize(1);
        assertThat(pa.getStatus()).isEqualTo("CLOSED");
        assertThat(pa.getExitReason()).isEqualTo(ExitReason.STRATEGY_SIGNAL);

        // LIVE 매도를 PAPER 매도 체결가로 정산(운영 폴러 + reconcileClosingPositions) — A12 와 같은 대역.
        BigDecimal sellFill = paperOrders(p.getId(), "SELL").get(0).getPrice();
        Mockito.doReturn(sellFilled(sells.get(0), lp.getSize(), sellFill)).when(upbitOrderClient).getOrder(sells.get(0).getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(sells.get(0).getId());
        awaitDrained();
        liveTradingService.reconcileClosingPositions();
        PositionEntity la = positionRepository.findById(lp.getId()).orElseThrow();
        LiveTradingSessionEntity lb = liveRepo.findById(l.getId()).orElseThrow();
        VirtualBalanceEntity pb = balanceRepo.findById(p.getId()).orElseThrow();
        row("B01 평균가", "8 매도 뒤 상태", la.getStatus(), pa.getStatus(), true);
        expect(row("B01 평균가", "9 매도 뒤 가용 잔액", lb.getAvailableKrw(), pb.getAvailableKrw(), true), "매도 뒤 잔액");
        row("B01 평균가", "10 실현 손익(LIVE 는 매수 수수료 제외 정의, PAPER 는 포함)", la.getRealizedPnl(), pa.getRealizedPnl(), false);
        // 수수료 각 1회: 포지션 수수료 = 매수 수수료 + 매도 수수료, 세션 누적 수수료 = 그 합.
        BigDecimal proceeds = pa.getSize().multiply(sellFill);
        BigDecimal sellFee = proceeds.multiply(FEE);
        System.out.println("[B01] 수수료 대조: 포지션 수수료=" + pa.getPositionFee() + " 세션 누적=" + pb.getTotalFee()
                + " 수량=" + pa.getSize() + " 매도 체결가=" + sellFill + " 매도 대금=" + proceeds + " 매도 수수료=" + sellFee
                + " 투입=" + pa.getInvestedKrw() + " 실현=" + pa.getRealizedPnl());
        // 3회차는 4자리로 비교해 실패했다(20.0169 대 20.0200) — position_fee·total_fee 열은 V18/V20 부터 NUMERIC(20,2)라 저장값이 둘째 자리로
        // 반올림된다(기존 저장 관례, 이중 차감 아님 — 실현 손익은 반올림 전 수수료로 계산돼 아래 4자리 단언과 맞는다). 저장 자릿수 2로 본다.
        assertThat(pa.getPositionFee())
                .isEqualByComparingTo(pa.getInvestedKrw().multiply(FEE).add(sellFee).setScale(2, RoundingMode.HALF_UP));
        assertThat(pb.getTotalFee()).isEqualByComparingTo(pa.getPositionFee());
        assertThat(pa.getRealizedPnl().setScale(4, RoundingMode.HALF_UP))
                .as("실현 손익 = 매도 순수익 − 투입금(수수료 포함) — 수수료를 빼먹거나 두 번 빼지 않는다")
                .isEqualByComparingTo(proceeds.subtract(sellFee).subtract(pa.getInvestedKrw()).setScale(4, RoundingMode.HALF_UP));
        assertThat(pb.getAvailableKrw().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("20200").subtract(pa.getInvestedKrw()).add(proceeds.subtract(sellFee)).setScale(2, RoundingMode.HALF_UP));
        assertThat(la.getRealizedPnl().subtract(pa.getRealizedPnl()).setScale(2, RoundingMode.HALF_UP))
                .as("두 실현 손익의 차이는 매수 수수료(LIVE 정의가 제외)뿐")
                .isEqualByComparingTo(pa.getInvestedKrw().multiply(FEE).setScale(2, RoundingMode.HALF_UP));
    }

    // ── 실시간 손절 확인 간격 ──────────────────────────────────────────

    @Test
    @DisplayName("B02 실시간 확인 간격 — 같은 시세 열에 둘 다 평상시 5초·급변 1초 (PAPER 는 시계로 4999/5000·999/1000 경계까지)")
    void b02RealtimeCheckInterval() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal close = lastClose(cs);
        BigDecimal entry = close.multiply(new BigDecimal("0.98")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal sl = entry.multiply(new BigDecimal("0.90"));   // 멀리 — 손절 없이 확인만
        BigDecimal tp = entry.multiply(new BigDecimal("1.50"));
        Instant opened = Instant.now().minus(10, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        livePos(l, entry, sl, tp, opened);
        paperPos(p, entry, sl, tp, opened);
        refreshAligned();
        liveMap("rtStopLossLastCheckMs").remove(BTC);   // 앞 시나리오의 값이 아니라 이 열의 첫 시세부터 본다(열 안에서는 지우지 않는다)
        liveMap("spikeCheckLastMs").remove(BTC);
        paperMap("alignedRtLastCheckMs").remove(BTC);
        paperMap("alignedSpikeLastCheckMs").remove(BTC);
        // 1회차는 이 줄이 없어 앞 시나리오(B06)가 실제 시계로 남긴 BTC 가격 이력이 창 안으로 읽혀 첫 시세부터 급변으로 잡혔다(3단계 실패) —
        // 하네스 결함. LIVE 쪽 이력은 fixture setUp 이 이미 비운다. 열 안에서는 아무것도 지우지 않는다.
        ((Map<?, ?>) ReflectionTestUtils.getField(paperTarget(), "alignedPriceHistory")).remove(BTC);

        long[] clock = {1_000_000L};
        paperTarget().alignedNowMs = () -> clock[0];
        BigDecimal down2 = close.multiply(new BigDecimal("0.98")).setScale(0, RoundingMode.HALF_UP);   // −2%/30s → 급변

        // 같은 시세 열: 정상 → 바로 또 정상(5초 안) → 바로 급락(−2%) → 바로 또 급락(1초 안)
        String[] steps = {"1 첫 시세(정상)", "2 5초 안 정상", "3 5초 안 급락 −2%", "4 1초 안 급락"};
        BigDecimal[] prices = {close, close, down2, down2};
        long[] paperDeltas = {0, 100, 200, 300};
        boolean[] expectChecked = {true, false, true, false};
        for (int i = 0; i < steps.length; i++) {
            Long liveBefore = liveMap("rtStopLossLastCheckMs").get(BTC);
            liveTarget().onRealtimePriceEvent(new RealtimePriceEvent(BTC, prices[i], Instant.now(), RealtimePriceEvent.WS_TRADE));
            Long liveAfter = liveMap("rtStopLossLastCheckMs").get(BTC);
            boolean liveChecked = liveAfter != null && !liveAfter.equals(liveBefore);

            clock[0] += paperDeltas[i];
            Long paperBefore = paperMap("alignedRtLastCheckMs").get(BTC);
            paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, prices[i], Instant.now(), RealtimePriceEvent.WS_TRADE));
            Long paperAfter = paperMap("alignedRtLastCheckMs").get(BTC);
            boolean paperChecked = paperAfter != null && !paperAfter.equals(paperBefore);
            System.out.println("[B02] " + steps[i] + " LIVE 확인=" + liveChecked + " PAPER 확인=" + paperChecked + " (기대 " + expectChecked[i] + ")");
            expect(row("B02 확인 간격", steps[i] + " 확인 여부", liveChecked, paperChecked, true), steps[i]);
            assertThat(liveChecked).as("LIVE " + steps[i]).isEqualTo(expectChecked[i]);
            assertThat(paperChecked).as("PAPER " + steps[i]).isEqualTo(expectChecked[i]);
        }
        // PAPER 만: 정확한 경계. 마지막 급변 확인이 clock=1,000,300 — 1초 미만(999ms 뒤)은 생략, 1초(1000ms 뒤)는 확인.
        long lastSpike = paperMap("alignedSpikeLastCheckMs").get(BTC);
        clock[0] = lastSpike + 999;
        paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, down2, Instant.now(), RealtimePriceEvent.WS_TRADE));
        boolean at999 = paperMap("alignedSpikeLastCheckMs").get(BTC) == clock[0];
        clock[0] = lastSpike + 1000;
        paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, down2, Instant.now(), RealtimePriceEvent.WS_TRADE));
        boolean at1000 = paperMap("alignedSpikeLastCheckMs").get(BTC) == clock[0];
        // 평상시 경계: 급변 창(30초)이 지난 뒤 같은 가격 → 정상 간격. 마지막 확인 시각 기준 4999 생략 / 5000 확인.
        clock[0] += 31_000;
        paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, down2, Instant.now(), RealtimePriceEvent.WS_TRADE));
        long lastNormal = paperMap("alignedRtLastCheckMs").get(BTC);
        assertThat(lastNormal).as("창 밖 첫 시세는 정상 간격으로 확인").isEqualTo(clock[0]);
        clock[0] = lastNormal + 4999;
        paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, down2, Instant.now(), RealtimePriceEvent.WS_TRADE));
        boolean at4999 = paperMap("alignedRtLastCheckMs").get(BTC) == clock[0];
        clock[0] = lastNormal + 5000;
        paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, down2, Instant.now(), RealtimePriceEvent.WS_TRADE));
        boolean at5000 = paperMap("alignedRtLastCheckMs").get(BTC) == clock[0];
        System.out.println("[B02] PAPER 경계: 급변 +999ms 확인=" + at999 + " +1000ms 확인=" + at1000 + " / 정상 +4999ms 확인=" + at4999 + " +5000ms 확인=" + at5000);
        row("B02 확인 간격", "5 PAPER 급변 999/1000ms", "—", at999 + "/" + at1000, false);
        row("B02 확인 간격", "6 PAPER 정상 4999/5000ms", "—", at4999 + "/" + at5000, false);
        assertThat(at999).isFalse();
        assertThat(at1000).isTrue();
        assertThat(at4999).isFalse();
        assertThat(at5000).isTrue();
    }

    // ── 위험 관문(모의 계좌 입력) ──────────────────────────────────────

    @Test
    @DisplayName("B03 위험 관문 — 연속 손실 한도면 둘 다 세션 정지(서킷 브레이커), 일일 손실 한도면 승인 거절(모의 계좌 입력, REAL 풀 불변)")
    void b03RiskGateFromPaperAccount() {
        var cfg = riskManagementService.getRiskConfig();
        assertThat(cfg.getCircuitBreakerEnabled()).as("risk_config 서킷 브레이커 설정").isTrue();
        int limit = cfg.getConsecutiveLossLimit();
        long realPositionsBefore = positionRepository.count();

        // (1) 연속 손실 한도 — 같은 수의 손실 청산을 둘 다에 넣고 평가 회차 → LIVE 비상 정지, PAPER 정지.
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        for (int i = 0; i < limit; i++) {
            Instant closedAt = Instant.now().minus(limit - i, ChronoUnit.HOURS);
            positionRepository.save(PositionEntity.builder().coinPair(BTC).side("BUY").entryPrice(new BigDecimal("100")).avgPrice(new BigDecimal("100"))
                    .size(BigDecimal.ONE).investedKrw(new BigDecimal("100")).realizedPnl(new BigDecimal("-1")).status("CLOSED")
                    .sessionId(l.getId()).sessionKind("LIVE").openedAt(closedAt.minus(1, ChronoUnit.HOURS)).closedAt(closedAt).build());
            paperPositionRepo.save(PaperPositionEntity.builder().sessionKind("PAPER").sessionId(p.getId()).coinPair(BTC).side("BUY")
                    .entryPrice(new BigDecimal("100")).avgPrice(new BigDecimal("100")).size(BigDecimal.ONE).investedKrw(new BigDecimal("100"))
                    .positionFee(BigDecimal.ZERO).realizedPnl(new BigDecimal("-1")).status("CLOSED")
                    .openedAt(closedAt.minus(1, ChronoUnit.HOURS)).closedAt(closedAt).build());
        }
        evaluateLive(l);
        runPaper(p);
        String liveStatus = liveRepo.findById(l.getId()).orElseThrow().getStatus();
        String paperStatus = balanceRepo.findById(p.getId()).orElseThrow().getStatus();
        System.out.println("[B03] 연속 손실 " + limit + "회 뒤 상태 LIVE=" + liveStatus + " PAPER=" + paperStatus);
        expect(row("B03 위험 관문", "1 연속 손실 한도 뒤 실행 중 아님", !"RUNNING".equals(liveStatus), !"RUNNING".equals(paperStatus), true), "서킷 브레이커");
        assertThat(paperStatus).isEqualTo("STOPPED");
        assertThat(liveStatus).isNotEqualTo("RUNNING");
        assertThat(liveOrders(l, "BUY")).isEmpty();
        assertThat(paperOrders(p.getId(), "BUY")).isEmpty();

        // (2) 일일 손실 한도 — 모의 세션 자신의 청산 손실이 초기 자본 × 한도를 넘으면 승인 차례에 거절. LIVE 의 전역 한도는 REAL 계좌 입력이라
        //     이 시험의 대역(승인)으로 고정돼 있다 — 여기서는 모의 계좌 입력이 REAL 표를 읽지도 쓰지도 않는 것만 본다.
        VirtualBalanceEntity p2 = paper(BTC);
        BigDecimal cap = p2.getInitialCapital();
        BigDecimal loss = cap.multiply(cfg.getMaxDailyLossPct().add(BigDecimal.ONE)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        paperPositionRepo.save(PaperPositionEntity.builder().sessionKind("PAPER").sessionId(p2.getId()).coinPair(BTC).side("BUY")
                .entryPrice(new BigDecimal("100")).avgPrice(new BigDecimal("100")).size(BigDecimal.ONE).investedKrw(new BigDecimal("100"))
                .positionFee(BigDecimal.ZERO).realizedPnl(loss.negate()).status("CLOSED")
                .openedAt(Instant.now().minus(3, ChronoUnit.HOURS)).closedAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());
        runPaper(p2);
        List<EntryPriorityRounds.Outcome> out = approveRound();
        String reason = out.stream().filter(o -> "PAPER".equals(o.candidate().kind()) && p2.getId().equals(o.candidate().sessionId()))
                .findFirst().map(EntryPriorityRounds.Outcome::reason).orElse("후보 없음");
        System.out.println("[B03] 일일 손실 " + loss + " 뒤 승인 결과: " + reason);
        row("B03 위험 관문", "2 일일 손실 한도 승인 결과", "리스크 한도(REAL 계좌 입력)", reason, false);
        assertThat(reason).startsWith("리스크 한도: 일일 손실 한도 초과");
        assertThat(paperPositions(p2)).filteredOn(x -> "OPEN".equals(x.getStatus())).isEmpty();
        assertThat(positionRepository.count()).as("REAL 포지션 표는 모의 관문이 건드리지 않는다(위 (1)의 LIVE 손실 행 제외)")
                .isEqualTo(realPositionsBefore + limit);
        assertThat(liveRepo.findById(l.getId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo(new BigDecimal("20200"));
    }

    // ── 가격 없는 시간 청산 ──────────────────────────────────────────

    @Test
    @DisplayName("B04 캔들·현재가 없는 회차의 시간 초과 — LIVE 는 시장가 의도, PAPER 는 판단 시각을 기록하고 첫 유효 현재가에 체결(두 시각 분리)")
    void b04TimeExitWithoutPrice() {
        deleteCache();
        markPriceBook.remove(ETH);
        BigDecimal entry = new BigDecimal("3000000");
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        Instant opened = Instant.now().minus(25, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(ETH);
        VirtualBalanceEntity p = paper(ETH);
        liveRepo.findById(l.getId()).ifPresent(x -> { x.setMaxHoldHours(24); liveRepo.save(x); });
        balanceRepo.findById(p.getId()).ifPresent(x -> { x.setMaxHoldHours(24); balanceRepo.save(x); });
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);
        refreshAligned();

        Instant t1Start = Instant.now();
        evaluateLive(liveRepo.findById(l.getId()).orElseThrow());
        runPaper(p);
        Instant t1End = Instant.now();
        PositionEntity lAfter = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pAfter = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        List<OrderEntity> sells = liveOrders(l, "SELL");
        @SuppressWarnings("unchecked")
        Map<Long, Instant> pending = (Map<Long, Instant>) ReflectionTestUtils.getField(paperTarget(), "alignedClosingDecidedAt");
        Instant decided = pending.get(ppos.getId());
        System.out.println("[B04] T1 LIVE=" + lAfter.getStatus() + " 매도 " + sells.size() + "건(가격 " + (sells.isEmpty() ? "-" : sells.get(0).getPrice())
                + ") / PAPER=" + pAfter.getStatus() + " 판단 시각=" + decided);
        row("B04 가격 없는 시간 청산", "1 T1 청산 판단", sells.isEmpty() ? "없음" : "시장가 매도 의도", decided == null ? "없음" : "판단 기록", false);
        assertThat(sells).as("LIVE 는 가격 없이 시간 초과 시장가 매도").hasSize(1);
        assertThat(lAfter.getStatus()).isEqualTo("CLOSING");
        // v1.147 (총괄 R2~R4 지시로 계약 변경): 가격 없는 시간 초과 판단은 LIVE 의 CLOSING 처럼 청산 대기로 남는다(종전 기대 OPEN).
        assertThat(pAfter.getStatus()).as("PAPER 는 청산 대기(체결가 없음)").isEqualTo("CLOSING");
        assertThat(pAfter.getExitReason()).as("결정한 사유가 저장된다").isEqualTo(ExitReason.TIME_STOP);
        assertThat(decided).as("청산 판단 시각이 T1 안에 기록된다").isNotNull().isBetween(t1Start, t1End);
        assertThat(sells.get(0).getCreatedAt()).isBetween(t1Start.minusSeconds(1), t1End.plusSeconds(1));

        // T2 — 첫 유효 현재가(실시간). PAPER 는 여기서 체결, 사유에 판단 시각과 체결가 시각이 따로 남는다.
        BigDecimal price = new BigDecimal("3010000");
        Instant t2Start = Instant.now();
        paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(ETH, price, Instant.now(), RealtimePriceEvent.WS_TRADE));
        PaperPositionEntity pClosed = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        PaperOrderEntity pSell = paperOrders(p.getId(), "SELL").get(0);
        System.out.println("[B04] T2 PAPER=" + pClosed.getStatus() + " 사유=" + pClosed.getExitReason() + " 체결가=" + pSell.getPrice() + " / " + pSell.getSignalReason());
        assertThat(pClosed.getStatus()).isEqualTo("CLOSED");
        assertThat(pClosed.getExitReason()).isEqualTo(ExitReason.TIME_STOP);
        assertThat(pSell.getSignalReason()).contains("청산 판단 " + decided).contains("체결가 시각 ");
        assertThat(pClosed.getClosedAt()).isAfterOrEqualTo(t2Start);
        assertThat(pending).doesNotContainKey(ppos.getId());
        row("B04 가격 없는 시간 청산", "2 체결가 시각", "정산 시(폴러)", pClosed.getClosedAt(), false);

        // LIVE 매도를 같은 체결가로 정산 — 잔액 같음(A12 와 같은 대역).
        BigDecimal fill = pSell.getPrice();
        Mockito.doReturn(sellFilled(sells.get(0), lpos.getSize(), fill)).when(upbitOrderClient).getOrder(sells.get(0).getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(sells.get(0).getId());
        awaitDrained();
        liveTradingService.reconcileClosingPositions();
        PositionEntity la = positionRepository.findById(lpos.getId()).orElseThrow();
        row("B04 가격 없는 시간 청산", "3 정산 뒤 상태", la.getStatus(), pClosed.getStatus(), true);
        row("B04 가격 없는 시간 청산", "4 청산 사유", la.getExitReason(), pClosed.getExitReason(), true);
        expect(row("B04 가격 없는 시간 청산", "5 정산 뒤 가용 잔액", liveRepo.findById(l.getId()).orElseThrow().getAvailableKrw(),
                balanceRepo.findById(p.getId()).orElseThrow().getAvailableKrw(), true), "잔액");
    }

    // ── 비동기 경계 ───────────────────────────────────────────────────

    @Test
    @DisplayName("B05 승인 E검사 통과 뒤·매수 저장 전에 정지 커밋 — 정지된 세션에 열린 포지션·부분 반영이 남지 않는다")
    void b05StopBetweenApprovalCheckAndBuy() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        markPriceBook.remove(BTC);   // 실시간 현재가(시세 장부) 없음 → 정지는 청산 대기
        VirtualBalanceEntity p = paper(BTC);
        runPaper(p);   // 회차 접수(LIVE 와 같은 회차 — 승인 차례에 체결)

        CountDownLatch atBuy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PaperTradingService.alignedRaceHook = name -> {
            if ("APPROVE_BEFORE_BUY".equals(name)) {
                atBuy.countDown();
                await(release, "승인 재개 신호");
            }
        };
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<List<EntryPriorityRounds.Outcome>> approval = ex.submit(this::approveRound);
            await(atBuy, "승인이 E검사를 지나 매수 직전에 도달");

            Future<VirtualBalanceEntity> stop = ex.submit(() -> paperTradingService.stop(p.getId()));
            // 정지가 승인과 직렬화되는지 — 승인이 멈춰 있는 동안 정지가 끝나면(true) 매수 저장이 정지 뒤에 들어간다.
            boolean stopFinishedWhileApprovalPaused;
            try {
                stop.get(2, TimeUnit.SECONDS);
                stopFinishedWhileApprovalPaused = true;
            } catch (java.util.concurrent.TimeoutException e) {
                stopFinishedWhileApprovalPaused = false;
            } catch (Exception e) {
                throw new AssertionError("정지가 예외로 끝났다: " + e.getCause(), e);
            }
            release.countDown();
            List<EntryPriorityRounds.Outcome> out = get(approval, "승인");
            VirtualBalanceEntity stopped = get(stop, "정지");

            VirtualBalanceEntity after = balanceRepo.findById(p.getId()).orElseThrow();
            List<PaperPositionEntity> positions = paperPositions(p);
            List<PaperOrderEntity> buys = paperOrders(p.getId(), "BUY");
            List<PaperOrderEntity> sells = paperOrders(p.getId(), "SELL");
            String approvalReason = out.stream().filter(o -> "PAPER".equals(o.candidate().kind())).findFirst()
                    .map(o -> o.reason() == null ? "진입" : o.reason()).orElse("후보 없음");
            System.out.println("[B05] 정지가 승인 중단 중에 끝남=" + stopFinishedWhileApprovalPaused + " 승인 결과=" + approvalReason
                    + " 상태=" + after.getStatus() + " 포지션=" + positions.stream().map(PaperPositionEntity::getStatus).toList()
                    + " 매수=" + buys.size() + " 매도=" + sells.size() + " 가용=" + after.getAvailableKrw() + " 총=" + after.getTotalKrw());
            row("B05 정지 경합", "0 정지가 승인 중단 중에 끝남", "—", stopFinishedWhileApprovalPaused, false);
            row("B05 정지 경합", "1 승인 결과", "—", approvalReason, false);
            row("B05 정지 경합", "2 정지 뒤 상태", "STOPPED", after.getStatus(), true);
            row("B05 정지 경합", "3 열린 포지션 수", 0, positions.stream().filter(x -> "OPEN".equals(x.getStatus())).count(), true);

            assertThat(stopFinishedWhileApprovalPaused).as("정지는 승인과 같은 세션 잠금을 기다린다").isFalse();
            assertThat(stopped.getStatus()).isEqualTo("STOPPED");
            assertThat(after.getStatus()).isEqualTo("STOPPED");
            assertThat(positions).as("🔴 정지된 세션에 열린 포지션이 남으면 아무도 청산하지 않는다")
                    .noneMatch(x -> "OPEN".equals(x.getStatus()));
            // v1.147 (총괄 R2 지시로 계약 변경): 유효 시세가 없으면 정지는 0원·최근 봉 종가로 정산하지 않고 청산 대기(CLOSING, FORCED_STOP)로 둔다.
            // 종전(9491166f) 기대는 "최근 H1 봉 종가로 매도 1건"이었다. 정산은 첫 유효 시세에서 한 번이다.
            assertThat(buys).hasSize(1);
            assertThat(sells).as("유효 시세 없이 매도를 만들지 않는다").isEmpty();
            assertThat(positions).hasSize(1);
            assertThat(positions.get(0).getStatus()).isEqualTo("CLOSING");
            assertThat(positions.get(0).getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
            assertThat(paperTradingService.pendingCloseCount(p.getId())).isEqualTo(1);
            assertThat(after.getAvailableKrw()).as("매수 차감만 반영 — 부분 반영 아님(포지션·주문·차감 함께)")
                    .isEqualByComparingTo(new BigDecimal("20200").subtract(positions.get(0).getInvestedKrw()));

            // 첫 유효 시세 → 한 번 정산
            BigDecimal px = lastClose(cs);
            paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, px, Instant.now(), RealtimePriceEvent.WS_TRADE));
            paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, px, Instant.now(), RealtimePriceEvent.WS_TRADE));
            PaperPositionEntity settled = paperPositionRepo.findById(positions.get(0).getId()).orElseThrow();
            List<PaperOrderEntity> sells2 = paperOrders(p.getId(), "SELL");
            VirtualBalanceEntity after2 = balanceRepo.findById(p.getId()).orElseThrow();
            BigDecimal fill = px.multiply(BigDecimal.ONE.subtract(SLIP)).setScale(8, RoundingMode.HALF_DOWN);
            BigDecimal proceeds = settled.getSize().multiply(fill);
            System.out.println("[B05] 정산 뒤 " + settled.getStatus() + " " + settled.getExitReason() + " 매도=" + sells2.size()
                    + " 가용=" + after2.getAvailableKrw() + " 대기=" + paperTradingService.pendingCloseCount(p.getId()));
            assertThat(settled.getStatus()).isEqualTo("CLOSED");
            assertThat(settled.getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
            assertThat(sells2).as("두 번째 시세에 다시 정산하지 않는다").hasSize(1);
            assertThat(sells2.get(0).getPrice()).isEqualByComparingTo(fill);
            assertThat(after2.getAvailableKrw().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(
                    new BigDecimal("20200").subtract(settled.getInvestedKrw()).add(proceeds.subtract(proceeds.multiply(FEE))).setScale(2, RoundingMode.HALF_UP));
            assertThat(paperTradingService.pendingCloseCount(p.getId())).isZero();
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("B06 실시간 손절과 평가 회차 청산이 같은 포지션에 동시에 — 매도 1회, 잔액 1회 반영")
    void b06RealtimeVersusEvaluationExitRace() {
        List<Candle> cs = downtrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal close = lastClose(cs);
        BigDecimal entry = close.multiply(new BigDecimal("1.05")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal sl = entry.multiply(new BigDecimal("0.97")).setScale(0, RoundingMode.HALF_UP);   // 현재가 < 손절가
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        VirtualBalanceEntity p = paper(BTC);
        PaperPositionEntity pos = paperPos(p, entry, sl, tp, Instant.now().minus(20, ChronoUnit.HOURS));
        refreshAligned();
        paperMap("alignedRtLastCheckMs").remove(BTC);
        paperMap("alignedSpikeLastCheckMs").remove(BTC);

        CountDownLatch atClose = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PaperTradingService.alignedRaceHook = name -> {
            if ("RT_BEFORE_CLOSE".equals(name)) {
                atClose.countDown();
                await(release, "실시간 처리기 재개 신호");
            }
        };
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<?> rt = ex.submit(() -> paperTarget().doOnAlignedRealtimePrice(
                    new RealtimePriceEvent(BTC, close, Instant.now(), RealtimePriceEvent.WS_TRADE)));
            await(atClose, "실시간 손절이 청산 직전에 도달");
            Future<?> tick = ex.submit(() -> runPaperLocked(p.getId()));
            boolean tickFinishedWhileRtPaused;
            try {
                tick.get(2, TimeUnit.SECONDS);
                tickFinishedWhileRtPaused = true;
            } catch (java.util.concurrent.TimeoutException e) {
                tickFinishedWhileRtPaused = false;
            } catch (Exception e) {
                throw new AssertionError("평가 회차가 예외로 끝났다: " + e.getCause(), e);
            }
            release.countDown();
            get(rt, "실시간 처리기");
            get(tick, "평가 회차");

            PaperPositionEntity after = paperPositionRepo.findById(pos.getId()).orElseThrow();
            VirtualBalanceEntity b = balanceRepo.findById(p.getId()).orElseThrow();
            List<PaperOrderEntity> sells = paperOrders(p.getId(), "SELL");
            BigDecimal fill = close.multiply(BigDecimal.ONE.subtract(SLIP)).setScale(8, RoundingMode.HALF_DOWN);
            BigDecimal proceeds = pos.getSize().multiply(fill);
            BigDecimal expectedAvail = new BigDecimal("200").add(proceeds.subtract(proceeds.multiply(FEE)));
            System.out.println("[B06] 평가가 RT 중단 중에 끝남=" + tickFinishedWhileRtPaused + " 상태=" + after.getStatus()
                    + " 사유=" + after.getExitReason() + " 매도=" + sells.size() + " 가용=" + b.getAvailableKrw() + " 기대=" + expectedAvail);
            row("B06 청산 경합", "0 평가가 RT 중단 중에 끝남", "—", tickFinishedWhileRtPaused, false);
            row("B06 청산 경합", "1 매도 주문 수", 1, sells.size(), true);
            assertThat(tickFinishedWhileRtPaused).as("평가 회차는 실시간 처리기와 같은 세션 잠금을 기다린다").isFalse();
            assertThat(after.getStatus()).isEqualTo("CLOSED");
            assertThat(sells).as("같은 포지션에 매도가 두 번 나가면 잔액이 두 번 늘어난다").hasSize(1);
            assertThat(b.getAvailableKrw().setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(expectedAvail.setScale(2, RoundingMode.HALF_UP));
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }
}
