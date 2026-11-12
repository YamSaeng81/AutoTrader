package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.util.TradingConstants;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.157 §125-7 — 부분 매도로 확정 실현손익을 기록한 LIVE 포지션의 남은 수량이 §15 phantom 으로 추정 청산될 때
 * 과거 확정분을 지우는가 (로컬 실제 PostgreSQL, 84fb03d6 기점, 2026-11-12).
 *
 * <p>경로: 첫 다리는 운영과 같다 — 체결 폴러 {@code checkOrderStatus}(거래소 응답만 대역) → LIVE {@code reconcileClosingPositions} 확정.
 * 남은 수량은 거래소 잔고 조회 대역이 0 을 돌려주고 {@code reconcilePhantomPositions}(60초 주기 본체)를 3회 불러 확정한다.
 * 가상의 거래소 체결·주문·FILL·현금 보정은 만들지 않는다 — phantom 은 주문 행 없이 추정가로만 닫는다.</p>
 *
 * <p>기점 관측(예상): phantom 확정이 {@code setRealizedPnl(추정분)} 으로 덮어 첫 다리 확정 손실이 포지션에서 사라진다.
 * 잔액·수수료·수량·추정 표시·주문 원천 확정 손실 합(전역 손실 판정 원천)은 수정 전후 같아야 한다.</p>
 *
 * <pre>
 * docker exec v152-pg psql -U trader -d postgres -c "CREATE DATABASE ph_x"
 * PH_PG_URL=jdbc:postgresql://127.0.0.1:15437/ph_x ./gradlew :web-api:test --rerun --tests '*PhantomKeepsConfirmedPnlPgTest'
 * </pre>
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${PH_PG_URL}",
        "spring.datasource.username=${PH_PG_USER:trader}",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "PH_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("v1.157 §125-7 phantom 추정 청산 — 부분 매도 확정 손익 보존 (로컬 PostgreSQL)")
class PhantomKeepsConfirmedPnlPgTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private TransactionTemplate tx;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;

    private static final BigDecimal AVG = new BigDecimal("12495000");
    private static final BigDecimal QTY = new BigDecimal("0.0016");
    private static final BigDecimal HALF = new BigDecimal("0.0008");

    private record Leg(String coin, Long sid, Long posId, BigDecimal realizedAfterLeg, OrderEntity s1) { }

    private static OrderResponse sellResponse(String uuid, String coin, String state, BigDecimal qty, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("ask");
        r.setOrdType("market");
        r.setState(state);
        r.setMarket(coin);
        r.setExecutedVolume(qty);
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    /** 실행 중 LIVE 세션 + 보유 2시간 CLOSING 포지션 → 첫 다리(반) 실제 체결·확정 → OPEN·반 수량·확정 손익. */
    private Leg firstLeg(String funds, String fee) throws Exception {
        String coin = "KRW-PH" + (System.nanoTime() % 100000);
        Long sid = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("100000")).availableKrw(new BigDecimal("80008"))
                .totalAssetKrw(new BigDecimal("100000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair(coin).side("BUY").entryPrice(AVG).avgPrice(AVG)
                .size(QTY).investedKrw(QTY.multiply(AVG)).entryAllocationKrw(QTY.multiply(AVG))
                .positionFee(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO)
                .status("CLOSING").closingAt(Instant.now())
                .sessionKind("LIVE").sessionId(sid).openedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
        OrderEntity s1 = orderRepository.save(OrderEntity.builder()
                .coinPair(coin).side("SELL").orderType("MARKET").quantity(HALF).state("SUBMITTED")
                .sessionKind("LIVE").sessionId(sid).positionId(p.getId())
                .exchangeOrderId("ph-" + UUID.randomUUID()).clientOrderId("ph-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());
        Mockito.doReturn(sellResponse(s1.getExchangeOrderId(), coin, "done", HALF, funds, fee))
                .when(upbitOrderClient).getOrder(s1.getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(s1.getId());
        liveTradingService.reconcileClosingPositions();
        PositionEntity mid = positionRepository.findById(p.getId()).orElseThrow();
        assertThat(mid.getStatus()).as("첫 다리 뒤 잔여 보유").isEqualTo("OPEN");
        assertThat(mid.getSize()).isEqualByComparingTo(HALF);
        return new Leg(coin, sid, p.getId(), mid.getRealizedPnl(), orderRepository.findById(s1.getId()).orElseThrow());
    }

    /** reconcilePhantomPosition 과 같은 추정 규칙(이 시험 자료: 손절가 없음·캔들 없음 → 첫 다리 체결가 또는 평균가). */
    private BigDecimal expectedEstimate(Leg l, BigDecimal remaining) {
        BigDecimal px = l.s1().getPrice() != null && l.s1().getPrice().signum() > 0 ? l.s1().getPrice() : AVG;
        BigDecimal proceeds = remaining.multiply(px);
        BigDecimal fee = proceeds.multiply(TradingConstants.FEE_RATE);
        return proceeds.subtract(fee).subtract(remaining.multiply(AVG));
    }

    private void phantomCycles(int n) {
        Mockito.doReturn(List.of()).when(upbitOrderClient).getAccounts();   // 거래소 보유 0 — 이 코인이 사라졌다
        for (int i = 0; i < n; i++) liveTradingService.reconcilePhantomPositions();
    }

    private BigDecimal orderSourceLoss() {
        BigDecimal v = orderRepository.sumLiveSessionRealizedLossSince(Instant.now().minus(1, ChronoUnit.DAYS));
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void trace(String name, Object... cols) {
        StringBuilder b = new StringBuilder("[PH157] ").append(name);
        for (Object c : cols) b.append(" | ").append(c instanceof BigDecimal d ? d.toPlainString() : c);
        System.out.println(b);
    }

    @Test
    @DisplayName("① 손실 첫 다리(−1,000.50) 뒤 남은 반이 phantom 확정 — 최종 실현손익 = 확정분 + 추정분, 잔액·수수료·주문 원천 손실은 그대로")
    void phantomAfterConfirmedPartialKeepsConfirmed() throws Exception {
        Leg l = firstLeg("9000", "4.5");
        BigDecimal availBefore = liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw();
        BigDecimal feeBefore = positionRepository.findById(l.posId()).orElseThrow().getPositionFee();
        BigDecimal orderLossBefore = orderSourceLoss();
        BigDecimal est = expectedEstimate(l, HALF);

        phantomCycles(3);
        PositionEntity closed = positionRepository.findById(l.posId()).orElseThrow();
        BigDecimal availAfter = liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw();
        BigDecimal orderLossAfter = orderSourceLoss();
        trace("①", "첫 다리 확정=" + l.realizedAfterLeg(), "추정분(계산)=" + est.setScale(4, RoundingMode.HALF_UP),
                "최종 실현=" + closed.getRealizedPnl(), "상태=" + closed.getStatus(), "사유=" + closed.getExitReason(),
                "잔액 차=" + availAfter.subtract(availBefore), "수수료 " + feeBefore + "→" + closed.getPositionFee(),
                "주문 원천 손실 " + orderLossBefore + "→" + orderLossAfter, "수량=" + closed.getSize());

        assertThat(l.realizedAfterLeg()).isEqualByComparingTo("-1000.50");
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(orderLossAfter).as("phantom 은 주문 원천(전역 확정 손실)에 더하지 않는다").isEqualByComparingTo(orderLossBefore);
        assertThat(closed.getPositionFee()).as("수수료 누적(종전 규칙)")
                .isEqualByComparingTo(feeBefore.add(HALF.multiply(l.s1().getPrice() != null && l.s1().getPrice().signum() > 0
                        ? l.s1().getPrice() : AVG).multiply(TradingConstants.FEE_RATE)));
        assertThat(closed.getRealizedPnl()).as("확정분 −1,000.50 + 추정분 — 기점은 추정분만 남긴다")
                .isEqualByComparingTo(l.realizedAfterLeg().add(est));
    }

    @Test
    @DisplayName("② 반복·재기동 — 확정 뒤 phantom 주기 3회 더·기동 복구 본체를 불러도 실현손익·잔액이 다시 더해지지 않는다")
    void repeatAndRestartDoNotAddAgain() throws Exception {
        Leg l = firstLeg("9000", "4.5");
        phantomCycles(3);
        PositionEntity once = positionRepository.findById(l.posId()).orElseThrow();
        BigDecimal availOnce = liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw();
        phantomCycles(3);
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ReflectionTestUtils.invokeMethod(target, "reconcileInternal");
        PositionEntity again = positionRepository.findById(l.posId()).orElseThrow();
        trace("②", "1회 확정=" + once.getRealizedPnl(), "반복·재기동 뒤=" + again.getRealizedPnl(),
                "잔액 " + availOnce + "→" + liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw());
        assertThat(again.getRealizedPnl()).isEqualByComparingTo(once.getRealizedPnl());
        assertThat(liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw()).isEqualByComparingTo(availOnce);
        assertThat(again.getRealizedPnl()).as("확정분 보존").isEqualByComparingTo(l.realizedAfterLeg().add(expectedEstimate(l, HALF)));
    }

    @Test
    @DisplayName("③ 롤백 — phantom 확정 트랜잭션이 롤백되면 포지션 OPEN·확정분만·잔액 그대로, 다음 확정에서 한 번만 반영")
    void rollbackLeavesConfirmedOnly() throws Exception {
        Leg l = firstLeg("9000", "4.5");
        BigDecimal availBefore = liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw();
        Mockito.doReturn(List.of()).when(upbitOrderClient).getAccounts();
        liveTradingService.reconcilePhantomPositions();
        liveTradingService.reconcilePhantomPositions();
        tx.executeWithoutResult(st -> { liveTradingService.reconcilePhantomPositions(); st.setRollbackOnly(); });
        PositionEntity rolled = positionRepository.findById(l.posId()).orElseThrow();
        trace("③ 롤백 뒤", "상태=" + rolled.getStatus(), "실현=" + rolled.getRealizedPnl(),
                "잔액 차=" + liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw().subtract(availBefore));
        assertThat(rolled.getStatus()).isEqualTo("OPEN");
        assertThat(rolled.getRealizedPnl()).isEqualByComparingTo(l.realizedAfterLeg());
        assertThat(liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw()).isEqualByComparingTo(availBefore);
        phantomCycles(3);
        PositionEntity closed = positionRepository.findById(l.posId()).orElseThrow();
        trace("③ 다시 확정", "상태=" + closed.getStatus(), "실현=" + closed.getRealizedPnl());
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getRealizedPnl()).isEqualByComparingTo(l.realizedAfterLeg().add(expectedEstimate(l, HALF)));
        BigDecimal px = l.s1().getPrice() != null && l.s1().getPrice().signum() > 0 ? l.s1().getPrice() : AVG;
        BigDecimal net = HALF.multiply(px).subtract(HALF.multiply(px).multiply(TradingConstants.FEE_RATE));
        BigDecimal availFinal = liveRepo.findById(l.sid()).orElseThrow().getAvailableKrw();
        trace("③ 입금", "확정 전 잔액=" + availBefore, "최종 잔액=" + availFinal, "추정 순입금 1회=" + BalanceAmounts.toBalance(net));
        assertThat(availFinal).as("롤백 뒤 재확정 — 추정 순입금은 한 번만").isEqualByComparingTo(availBefore.add(BalanceAmounts.toBalance(net)));
    }

    @Test
    @DisplayName("④ 대조 — 부분 매도 없이 전량 phantom: 실현손익 = 추정분(종전과 같다)")
    void fullPhantomUnchanged() throws Exception {
        String coin = "KRW-PHF" + (System.nanoTime() % 100000);
        Long sid = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("100000")).availableKrw(new BigDecimal("80008"))
                .totalAssetKrw(new BigDecimal("100000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair(coin).side("BUY").entryPrice(AVG).avgPrice(AVG)
                .size(QTY).investedKrw(QTY.multiply(AVG)).entryAllocationKrw(QTY.multiply(AVG))
                .positionFee(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO)
                .stopLossPrice(new BigDecimal("12000000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sid).openedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
        phantomCycles(3);
        PositionEntity closed = positionRepository.findById(p.getId()).orElseThrow();
        BigDecimal proceeds = QTY.multiply(new BigDecimal("12000000"));
        BigDecimal est = proceeds.subtract(proceeds.multiply(TradingConstants.FEE_RATE)).subtract(QTY.multiply(AVG));
        trace("④", "상태=" + closed.getStatus(), "실현=" + closed.getRealizedPnl(), "추정(계산)=" + est);
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getRealizedPnl()).isEqualByComparingTo(est);
    }
}
