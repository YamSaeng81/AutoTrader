package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.cryptoautotrader.strategy.Candle;
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
 * 총괄 v1.154 §5 — B01 실제 다음 매도 봉 (2026-11-12). 종전 B01(Sma20LiveAlignedGapsTest)은 다음 봉을 기다릴 수 없어 마지막 평가 봉 표시를 지우고
 * 보유 시작을 4시간 앞으로 옮겨 대신했다. 이 시험은 <b>표시를 지우지 않고 보유 시작도 옮기지 않는다</b>. 공통 시계({@link EntryClock})만
 * 다음 마감 시각 봉(매수 봉 + 24시간)이 닫힌 시각으로 옮긴다. 가격 입력은 종전 B01 과 같다(마지막 봉 = 체결가 +0.32%, 앞 봉 = 그 ×1.005).
 *
 * <p>LIVE 판단의 실제 시각 접점 세 곳 — 캔들 조회 상한({@code loadRecentCandles} 의 to), 캔들 최신성 관문({@code freshCandles} → 관문 now),
 * 신호 매도 최소 보유 시간({@code heldMinutes}) — 이 실제 시각이면 시계를 옮겨도 다음 봉을 보지 못하거나 보유 시간이 모자라 SELL 이 없다(수정 전 실패).
 * 운영 기본 시계는 시스템 UTC 그대로다(시험만 setForTest). 매수·매도 주문·체결 시각(DB 감사 시각)은 실제 시각 그대로다.</p>
 */
@DisplayName("B01 실제 다음 매도 봉 — 공통 시계로 다음 마감 봉, 평가 표시·보유 시작을 건드리지 않음")
class B01RealNextSellBarTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @Autowired private OrderExecutionEngine orderExecutionEngine;

    private static final BigDecimal FEE = new BigDecimal("0.0005");

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "b01-real-next-sell-bar";
    }

    private void at(Instant t) {
        entryClock.setForTest(Clock.fixed(t, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private Instant marker(Object service, Long id) {
        Object target = AopTestUtils.getUltimateTargetObject(service);
        return ((Map<Long, Instant>) ReflectionTestUtils.getField(target, "lastEvaluatedClosedCandle")).get(id);
    }

    private static OrderResponse buyFilled(OrderEntity buy, BigDecimal fillPrice) {
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

    @Test
    void realNextSellBar() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);

        // ① 매수 — 시계 = 매수 봉(lastOpen) 마감 정각
        at(lastOpen.plus(1, ChronoUnit.HOURS));
        evaluateLive(l);
        runPaper(p);
        approveRound();
        PaperPositionEntity pp = paperPositions(p).get(0);
        OrderEntity buy = liveOrders(l, "BUY").get(0);
        Mockito.doReturn(buyFilled(buy, pp.getEntryPrice())).when(upbitOrderClient).getOrder(buy.getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(buy.getId());
        awaitDrained();
        PositionEntity lp = livePositions(l).get(0);
        Instant liveOpened = lp.getOpenedAt();
        Instant paperOpened = pp.getOpenedAt();
        BigDecimal target = pp.getEntryPrice().multiply(new BigDecimal("1.0032")).setScale(0, RoundingMode.HALF_UP);

        // ② 다음 마감 시각 봉(lastOpen + 24h) — 종전 B01 과 같은 가격 입력을 그 봉 기준으로. 평가 표시는 지우지 않는다.
        Instant sellBar = lastOpen.plus(24, ChronoUnit.HOURS);
        deleteCache();
        List<Candle> cs2 = series(sellBar, i -> i == 499 ? target.longValue() : target.multiply(new BigDecimal("1.005")).longValue());
        insertCache(BTC, cs2);

        // ②-a 마감 1µs 전 — 형성 중이라 새 판단 없음
        at(sellBar.plus(1, ChronoUnit.HOURS).minus(1, ChronoUnit.MICROS));
        evaluateLive(liveRepo.findById(l.getId()).orElseThrow());
        runPaper(p);
        int sellsBefore = liveOrders(l, "SELL").size();
        String paperBefore = paperPositionRepo.findById(pp.getId()).orElseThrow().getStatus();

        // ②-b 마감 정각 — 새 닫힌 봉
        at(sellBar.plus(1, ChronoUnit.HOURS));
        evaluateLive(liveRepo.findById(l.getId()).orElseThrow());
        runPaper(p);
        awaitDrained();
        List<OrderEntity> sells = liveOrders(l, "SELL");
        PaperPositionEntity pa = paperPositionRepo.findById(pp.getId()).orElseThrow();
        System.out.println("[B01n] 매수 봉 " + lastOpen + " · 매도 봉 " + sellBar + " · 경계가 " + target
                + " | 마감 1µs 전: LIVE 매도 " + sellsBefore + " PAPER " + paperBefore
                + " | 마감 정각: LIVE 매도 " + sells.size() + " 표시 " + marker(liveTradingService, l.getId())
                + " · PAPER " + pa.getStatus() + " " + pa.getExitReason() + " 표시 " + marker(paperTradingService, p.getId())
                + " | 보유 시작 LIVE " + liveOpened + " → " + positionRepository.findById(lp.getId()).orElseThrow().getOpenedAt()
                + " · PAPER " + paperOpened + " → " + pa.getOpenedAt());

        assertThat(sellsBefore).as("형성 중 봉 — LIVE 매도 없음").isZero();
        assertThat(paperBefore).isEqualTo("OPEN");
        assertThat(positionRepository.findById(lp.getId()).orElseThrow().getOpenedAt()).as("보유 시작 그대로").isEqualTo(liveOpened);
        assertThat(marker(liveTradingService, l.getId())).as("LIVE 는 다음 마감 봉을 평가").isEqualTo(sellBar);
        assertThat(sells).as("LIVE 실제 다음 봉에서 전략 SELL").hasSize(1);
        // PAPER — 1회차는 여기서 "CLOSED" 를 기대해 실패했다(원문 보존: v153-20261112/b01-next-bar/after-run1-paper-seams). PAPER 의 판단 시각 접점
        // 4곳(fetchRecentCandles 의 to·alignedCandleProblem·alignedBtcGate 의 now·신호 매도 heldMinutes)은 실제 시각 그대로이고, 총괄 v1.154 §5 가
        // 허용한 범위는 LIVE 세 곳뿐이라 연결하지 않았다. 그래서 지금 관측(다음 봉을 보지 못해 OPEN·표시는 매수 봉 다음 봉)을 그대로 고정해 둔다 —
        // PAPER 접점을 연결하는 별도 승인 뒤 "CLOSED·STRATEGY_SIGNAL·표시 = 매도 봉" 으로 바꿀 자리다.
        assertThat(pa.getStatus()).as("PAPER 판단 시각 접점 미연결(범위 밖) — 다음 봉을 보지 못함").isEqualTo("OPEN");
        assertThat(marker(paperTradingService, p.getId())).isEqualTo(lastOpen.plus(1, ChronoUnit.HOURS));

        // ③ 같은 시계로 다시 — 같은 봉 재처리 없음
        evaluateLive(liveRepo.findById(l.getId()).orElseThrow());
        runPaper(p);
        awaitDrained();
        assertThat(liveOrders(l, "SELL")).hasSize(1);
    }

    /**
     * 접점 연결 뒤 D 시험(RealNextBarClockTest) ① 이 실패한 원인 확인 — 그 고정 자료의 봉 시작은 정시가 아니다(지금 −61분, 초 단위). 최신성 관문은
     * 정시 격자로 "필요 최신 완료 봉" 을 정한다. 실제 시각으로 볼 때는 우연히 통과했고, 공통 시계(마감 1µs 전)로 보면 직전 봉이 격자보다 늦어 판정 불가가 된다.
     * 운영 캔들은 정시 시작이다. 같은 단계(마감 1µs 전 — 직전 닫힌 봉 평가, 매수 없음)를 정시 시작 봉으로 돌려 LIVE 가 평가하는지 본다. D 시험은 고치지 않는다.
     */
    @Test
    void hourAlignedPrevBarStillEvaluatedJustBeforeClose() {
        Instant aligned = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        useCloseHour(aligned);
        insertCache(BTC, uptrend(aligned));
        LiveTradingSessionEntity l = live(BTC);
        at(aligned.plus(1, ChronoUnit.HOURS).minus(1, ChronoUnit.MICROS));
        evaluateLive(l);
        Instant m = marker(liveTradingService, l.getId());
        System.out.println("[B01n] 정시 시작 봉 " + aligned + " 마감 1µs 전: LIVE 표시 " + m + " · 매수 " + liveOrders(l, "BUY").size());
        assertThat(m).as("정시 시작 봉이면 마감 1µs 전에도 직전 닫힌 봉을 평가").isEqualTo(aligned.minus(1, ChronoUnit.HOURS));
        assertThat(liveOrders(l, "BUY")).isEmpty();
    }
}
