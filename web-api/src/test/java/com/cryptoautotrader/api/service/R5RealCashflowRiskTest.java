package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.core.risk.RiskCheckResult;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.152 E — R5: <b>엔진의 실제 매수→매도→커밋</b>된 계좌/주문/포지션으로 LIVE·PAPER 위험 입력을 대조한다 (2026-11-12).
 *
 * <p>같은 입력: 같은 봉·같은 체결가(LIVE 매수 체결 대역 = PAPER 체결가, LIVE 매도 체결 대역 = PAPER 매도 체결가). 매수는 공통 시계로 마지막 봉
 * 마감 정각에 실제 평가→회차 승인. 매도는 실제 실시간 청산 경로(시세 틱) — 손실은 손절가, 이익·수수료 경계는 익절가를 그 가격으로 둔다
 * (포지션의 청산 가격 필드만 시험이 정하고 회계·체결은 엔진이 한다). 일봉 전략의 다음 매도 봉은 24시간 뒤라 전략 매도 경로는 쓰지 않는다.</p>
 *
 * <p>위험 판단은 커밋된 행에서 제품 함수로 한다: LIVE {@code checkCircuitBreaker(세션)}(position 표), PAPER {@code alignedCircuitBreaker}·
 * {@code alignedRiskCheck}(모의 표). 한 거래로 판정이 갈리도록 이 시험 동안 risk_config 의 연속 손실 한도 1·일 손실 한도 0.01% 로 두고
 * 끝나면 되돌린다(제품 한도 변경 아님). LIVE 전역 {@code checkRisk}(공유 REAL 계좌·trade_log 합)는 단일 모의 계좌와 입력 범위가 달라
 * 같은지 단언하지 않고 값만 남긴다. 실제 거래소 주문 0(주문 클라이언트 대역).</p>
 */
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=R5RealCashflowRiskTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("E R5 — 실제 매수→매도 커밋 뒤 위험 입력: 이익·수수료 경계·손실에서 LIVE 관례 손익과 PAPER 위험 손익 부호 일치, 연속 손실·일 손실 판정")
class R5RealCashflowRiskTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @Autowired private OrderExecutionEngine orderExecutionEngine;

    private static final BigDecimal FEE = new BigDecimal("0.0005");
    private Map<String, Object> savedRisk;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "r5-real-cashflow";
    }

    @BeforeEach
    void saveRisk() {
        savedRisk = jdbc.queryForMap("SELECT id, consecutive_loss_limit, max_daily_loss_pct, circuit_breaker_enabled FROM risk_config ORDER BY id DESC LIMIT 1");
    }

    @AfterEach
    void restoreRisk() {
        jdbc.update("UPDATE risk_config SET consecutive_loss_limit = ?, max_daily_loss_pct = ?, circuit_breaker_enabled = ? WHERE id = ?",
                savedRisk.get("consecutive_loss_limit"), savedRisk.get("max_daily_loss_pct"), savedRisk.get("circuit_breaker_enabled"), savedRisk.get("id"));
    }

    private PaperTradingService paperTarget() {
        return AopTestUtils.getUltimateTargetObject(paperTradingService);
    }

    private LiveTradingService liveTarget() {
        return AopTestUtils.getUltimateTargetObject(liveTradingService);
    }

    private OrderResponse buyFilled(OrderEntity buy, BigDecimal fillPrice) {
        BigDecimal funds = new BigDecimal("20000").divide(new BigDecimal("1.0005"), 8, RoundingMode.DOWN);
        OrderResponse r = new OrderResponse();
        r.setUuid(buy.getExchangeOrderId());
        r.setSide("bid");
        r.setOrdType("price");
        r.setState("done");
        r.setMarket(buy.getCoinPair());
        r.setPrice(new BigDecimal("20000"));
        r.setExecutedVolume(funds.divide(fillPrice, 8, RoundingMode.DOWN));
        r.setExecutedFunds(funds);
        r.setPaidFee(funds.multiply(FEE).setScale(8, RoundingMode.HALF_UP));
        return r;
    }

    private OrderResponse sellFilled(OrderEntity sell, BigDecimal volume, BigDecimal fillPrice) {
        BigDecimal funds = volume.multiply(fillPrice).setScale(8, RoundingMode.HALF_UP);
        OrderResponse r = new OrderResponse();
        r.setUuid(sell.getExchangeOrderId());
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("done");
        r.setMarket(sell.getCoinPair());
        r.setVolume(volume);
        r.setExecutedVolume(volume);
        r.setExecutedFunds(funds);
        r.setPaidFee(funds.multiply(FEE).setScale(8, RoundingMode.HALF_UP));
        return r;
    }

    private List<PaperOrderEntity> paperOrders(Long sessionId, String side) {
        return paperOrderRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream().filter(o -> side.equals(o.getSide())).toList();
    }

    record Trip(LiveTradingSessionEntity live, VirtualBalanceEntity paper, PositionEntity lp, PaperPositionEntity pp) { }

    /** 실제 매수(공통 시계 마감 정각) → 같은 체결가 → 실제 실시간 청산(exitFactor × 체결가) → LIVE 체결·정산. */
    private Trip roundTrip(String exitFactor, boolean viaStopLoss) {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        entryClock.setForTest(Clock.fixed(lastOpen.plus(1, ChronoUnit.HOURS), ZoneOffset.UTC));
        evaluateLive(l);
        runPaper(p);
        approveRound();
        awaitDrained();
        PaperPositionEntity pp = paperPositions(p).get(0);
        OrderEntity buy = liveOrders(l, "BUY").get(0);
        Mockito.doReturn(buyFilled(buy, pp.getEntryPrice())).when(upbitOrderClient).getOrder(buy.getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(buy.getId());
        awaitDrained();
        PositionEntity lp = livePositions(l).get(0);
        BigDecimal fill = pp.getEntryPrice();
        BigDecimal exit = fill.multiply(new BigDecimal(exitFactor)).setScale(0, RoundingMode.HALF_UP);

        // 청산 가격 필드만 정한다(회계·체결은 엔진). 반대쪽 가격은 멀리.
        if (viaStopLoss) {
            lp.setStopLossPrice(exit); lp.setTakeProfitPrice(fill.multiply(new BigDecimal("2")));
            pp.setStopLossPrice(exit); pp.setTakeProfitPrice(fill.multiply(new BigDecimal("2")));
        } else {
            lp.setTakeProfitPrice(exit); lp.setStopLossPrice(fill.multiply(new BigDecimal("0.5")));
            pp.setTakeProfitPrice(exit); pp.setStopLossPrice(fill.multiply(new BigDecimal("0.5")));
        }
        positionRepository.save(lp);
        paperPositionRepo.save(pp);

        PaperPositionEntity pa;
        if (viaStopLoss) {
            // 손절: 실시간 시세 틱 경로(두 엔진 모두 실시간 손절)
            ReflectionTestUtils.invokeMethod(paperTarget(), "refreshAlignedCoins", balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
            ((Map<?, ?>) ReflectionTestUtils.getField(paperTarget(), "alignedPriceHistory")).remove(BTC);
            ((Map<?, ?>) ReflectionTestUtils.getField(paperTarget(), "alignedRtLastCheckMs")).remove(BTC);
            ((Map<?, ?>) ReflectionTestUtils.getField(paperTarget(), "alignedSpikeLastCheckMs")).remove(BTC);
            paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, exit, Instant.now(), RealtimePriceEvent.WS_TRADE));
            pa = paperPositionRepo.findById(pp.getId()).orElseThrow();
            assertThat(pa.getStatus()).as("PAPER 실시간 손절").isEqualTo("CLOSED");
            realtimeTick(BTC, exit);
        } else {
            // 익절: 실시간 경로는 손절만 하고 익절은 정기 평가(최신 봉 가격)에서 한다 — 다음(형성 중) 봉 가격을 익절가로 넣고 두 엔진 정기 평가.
            //   1회차는 익절도 실시간 틱으로 보내 PAPER 가 청산하지 않았다(시험 설계 결함, 원문 보존).
            insertInProgress(BTC, exit, exit, exit, exit);
            runPaper(p);
            pa = paperPositionRepo.findById(pp.getId()).orElseThrow();
            assertThat(pa.getStatus()).as("PAPER 정기 평가 익절").isEqualTo("CLOSED");
            evaluateLive(l);
        }
        BigDecimal paperSellPrice = paperOrders(p.getId(), "SELL").get(0).getPrice();

        // LIVE 매도 접수 → PAPER 매도 체결가로 체결 → 정산
        awaitDrained();
        List<OrderEntity> sells = liveOrders(l, "SELL");
        assertThat(sells).as("LIVE 청산 매도 접수").hasSize(1);
        Mockito.doReturn(sellFilled(sells.get(0), lp.getSize(), paperSellPrice)).when(upbitOrderClient).getOrder(sells.get(0).getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(sells.get(0).getId());
        awaitDrained();
        liveTradingService.reconcileClosingPositions();
        PositionEntity la = positionRepository.findById(lp.getId()).orElseThrow();
        assertThat(la.getStatus()).isEqualTo("CLOSED");
        return new Trip(liveRepo.findById(l.getId()).orElseThrow(), balanceRepo.findById(p.getId()).orElseThrow(), la, pa);
    }

    private String report(String name, Trip t) {
        BigDecimal liveRisk = t.lp().getRealizedPnl();
        BigDecimal paperRisk = PaperTradingService.riskPnl(t.pp());
        BigDecimal paperScore = t.pp().getRealizedPnl();
        jdbc.update("UPDATE risk_config SET consecutive_loss_limit = 1, max_daily_loss_pct = 0.01, circuit_breaker_enabled = true WHERE id = ?",
                savedRisk.get("id"));
        var liveCb = riskManagementService.checkCircuitBreaker(t.live());
        CircuitBreakerResult paperCb = ReflectionTestUtils.invokeMethod(paperTarget(), "alignedCircuitBreaker", t.paper());
        RiskCheckResult paperRisk2 = ReflectionTestUtils.invokeMethod(paperTarget(), "alignedRiskCheck", t.paper());
        String line = String.format("[R5] %s: 체결 %s → 매도 %s | LIVE 실현(위험 입력)=%s | PAPER 위험 입력=%s | PAPER 성적 실현=%s | 투입 LIVE %s / PAPER %s"
                        + " | 수량 LIVE %s / PAPER %s | LIVE 연속손실 CB=%s | PAPER CB=%s | PAPER 일손실 관문 승인=%s(%s) | 가용 LIVE %s / PAPER %s",
                name, t.pp().getEntryPrice(), paperOrders(t.paper().getId(), "SELL").get(0).getPrice(), liveRisk, paperRisk, paperScore,
                t.lp().getInvestedKrw(), t.pp().getInvestedKrw(), t.lp().getSize(), t.pp().getSize(),
                liveCb.isTriggered(), paperCb.isTriggered(), paperRisk2.isApproved(), paperRisk2.getReason(),
                t.live().getAvailableKrw(), t.paper().getAvailableKrw());
        System.out.println(line);
        row("R5 " + name, "위험 입력 부호", liveRisk.signum(), paperRisk.signum(), true);
        row("R5 " + name, "연속 손실 CB(한도1)", liveCb.isTriggered(), paperCb.isTriggered(), true);
        assertThat(paperRisk.signum()).as(name + " 위험 입력 부호 LIVE=PAPER").isEqualTo(liveRisk.signum());
        assertThat(paperCb.isTriggered()).as(name + " 연속 손실 CB LIVE=PAPER").isEqualTo(liveCb.isTriggered());
        // 크기: 차이는 LIVE 체결 수량 8자리 버림뿐(체결가 동일) — 1원 미만
        assertThat(paperRisk.subtract(liveRisk).abs()).as(name + " 위험 입력 크기 차이").isLessThan(BigDecimal.ONE);
        return line;
    }

    @Test
    @DisplayName("이익(+1%) — 둘 다 이익, CB 없음, 일 손실 관문 승인")
    void profit() {
        Trip t = roundTrip("1.01", false);
        report("이익 +1%", t);
        assertThat(t.lp().getRealizedPnl().signum()).isPositive();
        assertThat(t.pp().getRealizedPnl().signum()).isPositive();
        RiskCheckResult r = ReflectionTestUtils.invokeMethod(paperTarget(), "alignedRiskCheck", t.paper());
        assertThat(r.isApproved()).isTrue();
    }

    @Test
    @DisplayName("수수료 경계(+0.20%) — LIVE 관례·PAPER 위험 입력은 이익, PAPER 성적 실현은 손실(매수 수수료 포함) — 위험 판정은 손실로 세지 않음")
    void feeBoundary() {
        // PAPER 매도 체결가 = 틱 × (1 − 모의 슬리피지 0.1%) 이므로 틱 +0.20% → 체결 약 +0.10%. 매도 수수료 0.05% 뒤 LIVE 관례 +약 0.05%,
        // 매수 수수료 0.05% 까지 빼는 성적표는 0 부근 음수.
        Trip t = roundTrip("1.002", false);
        report("수수료 경계 +0.20%", t);
        assertThat(PaperTradingService.riskPnl(t.pp()).signum()).as("위험 입력은 이익").isPositive();
        assertThat(t.pp().getRealizedPnl().signum()).as("성적 실현은 매수 수수료 포함이라 손실").isNegative();
        RiskCheckResult r = ReflectionTestUtils.invokeMethod(paperTarget(), "alignedRiskCheck", t.paper());
        assertThat(r.isApproved()).as("일 손실 0.01% 한도에서도 손실로 세지 않음").isTrue();
    }

    @Test
    @DisplayName("손실(−1%, 손절) — 둘 다 손실, 연속 손실 한도1 CB 둘 다 발동, PAPER 일 손실 관문 거절")
    void loss() {
        Trip t = roundTrip("0.99", true);
        report("손실 −1%", t);
        assertThat(t.lp().getRealizedPnl().signum()).isNegative();
        RiskCheckResult r = ReflectionTestUtils.invokeMethod(paperTarget(), "alignedRiskCheck", t.paper());
        assertThat(r.isApproved()).as("일 손실 0.01% 초과 — 거절").isFalse();
    }
}
