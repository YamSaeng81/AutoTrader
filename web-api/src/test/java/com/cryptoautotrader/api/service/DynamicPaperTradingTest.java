package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 2026-08-06/07 신규 — 동적 멀티코인(DYNAMIC)에 PAPER(모의) 모드를 추가한 회귀 테스트.
 *
 * <p><b>배경</b>: 운영 주력은 동적 7세션인데, 전날 정렬한 페이퍼(PaperTradingService)는
 * LIVE(단일코인) 기준이라 동적 엔진은 페이퍼로 전혀 검증할 수 없었다. 별도 서비스를 만들지
 * 않고 {@code DynamicTradingService} 자체에 {@code trading_mode}(REAL|PAPER)를 추가했다 —
 * 전략 평가·진입 게이트 5종·SL/TP·time stop·워치리스트 스캔은 100% 공유하고, <b>체결(주문
 * 제출)만</b> REAL(실거래소)/PAPER(슬리피지 시뮬레이션)로 분기한다.</p>
 *
 * <p>이 테스트가 지키는 것: ① PAPER는 {@code OrderExecutionEngine}(실거래소)을 절대 호출하지
 * 않는다 ② PAPER 포지션/주문은 {@code session_kind='DYN_PAPER'}로 REAL과 완전히 분리된다
 * ③ 그 결과 실거래 reconcile 스케줄러 4종이 PAPER 데이터를 건드리지 않는다.</p>
 */
class DynamicPaperTradingTest extends IntegrationTestBase {

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private OrderExecutionEngine orderExecutionEngine;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    /** {@code @PreUpdate}가 매번 now()로 덮으므로, 유예 시간 경과는 네이티브 UPDATE로만 만들 수 있다. */
    private void backdateUpdatedAt(Long sessionId, long minutesAgo) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE dynamic_session SET updated_at = :ts WHERE id = :id")
                        .setParameter("ts", Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))
                        .setParameter("id", sessionId)
                        .executeUpdate());
        entityManager.clear();
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
    }

    private DynamicSessionEntity newSession(String tradingMode) {
        DynamicSessionEntity s = DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("10000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("SCANNING")
                .tradingMode(tradingMode)
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000"))
                .maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build();
        return dynamicSessionRepository.saveAndFlush(s);
    }

    /** ATR 계산에 필요한 최소 캔들(14개 이상)을 만족하는 완만한 상승 캔들 시퀀스 */
    private List<Candle> candles(BigDecimal lastClose) {
        List<Candle> list = new ArrayList<>();
        BigDecimal price = lastClose.subtract(new BigDecimal("2000000"));
        Instant t = Instant.now().minus(30, ChronoUnit.HOURS);
        for (int i = 0; i < 30; i++) {
            price = price.add(new BigDecimal("70000"));
            list.add(Candle.builder()
                    .time(t.plus(i, ChronoUnit.HOURS))
                    .open(price).high(price.add(BigDecimal.valueOf(10000)))
                    .low(price.subtract(BigDecimal.valueOf(10000))).close(price)
                    .volume(new BigDecimal("100"))
                    .build());
        }
        list.set(list.size() - 1, Candle.builder()
                .time(t.plus(29, ChronoUnit.HOURS))
                .open(lastClose).high(lastClose).low(lastClose).close(lastClose)
                .volume(new BigDecimal("100"))
                .build());
        return list;
    }

    // ── ① PAPER 세션 생성 ────────────────────────────────────────────

    @Test
    @DisplayName("tradingMode 지정 없이 생성하면 REAL(기본값)이다")
    void createSession_defaultsToReal() {
        DynamicSessionEntity s = newSession(null);
        assertThat(s.isPaper()).isFalse();
        assertThat(DynamicTradingService.sessionKind(s)).isEqualTo("DYNAMIC");
    }

    @Test
    @DisplayName("PAPER로 생성하면 isPaper()=true, sessionKind='DYN_PAPER'")
    void createSession_paperMode() {
        DynamicSessionEntity s = newSession("PAPER");
        assertThat(s.isPaper()).isTrue();
        assertThat(DynamicTradingService.sessionKind(s)).isEqualTo("DYN_PAPER");
    }

    // ── ② PAPER 매수 — 실거래소 미호출 + 즉시 체결 ─────────────────────

    @Test
    @DisplayName("PAPER 매수는 OrderExecutionEngine을 호출하지 않고 즉시 체결된다")
    void paperBuy_neverCallsOrderExecutionEngine_andFillsImmediately() {
        DynamicSessionEntity session = newSession("PAPER");
        BigDecimal signalPrice = new BigDecimal("90000000");
        List<Candle> evalCandles = candles(signalPrice);
        StrategySignal signal = StrategySignal.buy(BigDecimal.valueOf(70), "테스트 매수 신호");

        String blocked = dynamicTradingService.executeBuy(session, "KRW-BTC", evalCandles, signal, BigDecimal.ONE);

        assertThat(blocked).isNull();
        verify(orderExecutionEngine, never()).submitOrderAfterCommit(any());
        verify(orderExecutionEngine, never()).submitOrder(any());

        List<PositionEntity> positions = positionRepository.findBySessionKindAndStatus("DYN_PAPER", "OPEN");
        assertThat(positions).hasSize(1);
        PositionEntity pos = positions.get(0);
        assertThat(pos.getSize()).isGreaterThan(BigDecimal.ZERO); // REAL과 달리 즉시 수량 확정
        assertThat(pos.getSessionKind()).isEqualTo("DYN_PAPER");

        List<OrderEntity> orders = orderRepository.findByPositionIdOrderByCreatedAtDesc(pos.getId());
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getState()).isEqualTo("FILLED");
        assertThat(orders.get(0).getSessionKind()).isEqualTo("DYN_PAPER");

        DynamicSessionEntity reloaded = dynamicSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getAvailableKrw()).isLessThan(new BigDecimal("10000.00"));
        assertThat(reloaded.getScanState()).isEqualTo("POSITION_MONITORING");
        assertThat(reloaded.getCurrentCoinPair()).isEqualTo("KRW-BTC");
    }

    @Test
    @DisplayName("REAL 매수는 여전히 OrderExecutionEngine을 호출한다 (회귀 방지)")
    void realBuy_stillCallsOrderExecutionEngine() {
        DynamicSessionEntity session = newSession("REAL");
        BigDecimal signalPrice = new BigDecimal("90000000");
        List<Candle> evalCandles = candles(signalPrice);
        StrategySignal signal = StrategySignal.buy(BigDecimal.valueOf(70), "테스트 매수 신호");

        String blocked = dynamicTradingService.executeBuy(session, "KRW-BTC", evalCandles, signal, BigDecimal.ONE);

        assertThat(blocked).isNull();
        verify(orderExecutionEngine).submitOrderAfterCommit(any());

        List<PositionEntity> positions = positionRepository.findBySessionKindAndStatus("DYNAMIC", "OPEN");
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getSize())
                .as("REAL은 체결 콜백 전까지 size=0으로 대기한다 — PAPER처럼 즉시 확정하지 않는다")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── ③ PAPER 매도 — finalizeDynamicSell 재사용 확인 ─────────────────

    @Test
    @DisplayName("PAPER 매도는 실거래소를 거치지 않고 즉시 청산·손익 확정된다")
    void paperSell_finalizesImmediately() {
        DynamicSessionEntity session = newSession("PAPER");
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.0001")).investedKrw(new BigDecimal("9000"))
                .status("OPEN").sessionId(session.getId()).sessionKind("DYN_PAPER")
                .stopLossPrice(new BigDecimal("85000000")).takeProfitPrice(new BigDecimal("95000000"))
                .build());

        dynamicTradingService.executeSell(session, pos, new BigDecimal("91000000"), "테스트 익절",
                com.cryptoautotrader.api.entity.ExitReason.TAKE_PROFIT);

        verify(orderExecutionEngine, never()).submitOrder(any());

        PositionEntity reloaded = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("CLOSED");
        assertThat(reloaded.getRealizedPnl()).isNotNull();

        List<OrderEntity> orders = orderRepository.findByPositionIdOrderByCreatedAtDesc(pos.getId());
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getState()).isEqualTo("FILLED");
        assertThat(orders.get(0).getSessionKind()).isEqualTo("DYN_PAPER");
    }

    // ── ④ 실거래 reconcile 스케줄러가 PAPER를 무시하는지 ───────────────

    @Test
    @DisplayName("PAPER의 size=0 고아 포지션은 reconcileDynamicOrphanBuyPositions가 건드리지 않는다")
    void orphanBuyReconcile_ignoresPaperPositions() {
        DynamicSessionEntity session = newSession("PAPER");
        PositionEntity orphan = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(BigDecimal.ZERO).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(session.getId()).sessionKind("DYN_PAPER")
                .build());

        dynamicTradingService.reconcileDynamicOrphanBuyPositions();

        PositionEntity reloaded = positionRepository.findById(orphan.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("REAL 전용 안전망이 PAPER 데이터를 건드리면 안 된다 — session_kind 필터로 격리된다")
                .isEqualTo("OPEN");
    }

    /**
     * ⚠️ <b>2026-08-27 판정 반전</b>: 이 테스트는 원래 "PAPER 는 이 안전망의 대상이 아니다"를
     * 잠그고 있었다(08-07). 그 근거는 "PAPER 는 REQUIRES_NEW·비동기 갭이 없어 잔고가 어긋날 수
     * 없다"였는데, <b>세션 60이 그 전제를 깼다</b> — 포지션 2852(7,108원)를 든 채
     * {@code available == total} 이 되어 같은 돈으로 재매수했고, 중복 포지션이 세션을 21시간
     * 정지시켰다. 이제 PAPER 도 대상이며, 포지션이 없는데 묶인 돈은 복원된다.
     *
     * <p>08-07 이 실제로 두려워한 것은 <b>오탐</b>이었다 — 포지션을 {@code SESSION_KIND}(REAL)로만
     * 조회해 PAPER 세션이 "포지션 없음"으로 오판되는 것. 그 원인은 세션의 실제
     * {@code session_kind} 로 조회하도록 고쳐 해소했고, 아래 두 번째 테스트가 그것을 잠근다.</p>
     */
    @Test
    @DisplayName("PAPER도 포지션 없이 묶인 잔고는 복원된다 — 08-07 제외를 08-27에 되돌림")
    void balanceReconcile_restoresPaperOrphanCapital() {
        DynamicSessionEntity session = newSession("PAPER");
        session.setAvailableKrw(new BigDecimal("2000.00"));
        session.setTotalAssetKrw(new BigDecimal("10000.00"));
        dynamicSessionRepository.saveAndFlush(session);
        backdateUpdatedAt(session.getId(), 10);

        dynamicTradingService.reconcileDynamicSessionBalance();

        DynamicSessionEntity reloaded = dynamicSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getAvailableKrw())
                .as("포지션도 활성 주문도 없이 8,000원이 묶여 있었다 — 대응물 없는 돈이다")
                .isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("🔴 보유 중인 PAPER 세션의 잔고는 건드리지 않는다 — 08-07이 막으려던 오탐")
    void balanceReconcile_doesNotTouchPaperSessionHoldingPosition() {
        DynamicSessionEntity session = newSession("PAPER");
        session.setAvailableKrw(new BigDecimal("2000.00"));
        session.setTotalAssetKrw(new BigDecimal("10000.00"));
        dynamicSessionRepository.saveAndFlush(session);
        backdateUpdatedAt(session.getId(), 10);

        // DYN_PAPER 포지션 보유 — 예전 코드는 이걸 REAL kind 로 조회해 못 보고 "고아 잔고"로 오판했다
        positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(session.getId()).sessionKind("DYN_PAPER")
                .build());

        dynamicTradingService.reconcileDynamicSessionBalance();

        DynamicSessionEntity reloaded = dynamicSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getAvailableKrw())
                .as("정상 보유 중이다 — 차이는 묶인 원금이지 고아 잔고가 아니다")
                .isEqualByComparingTo("2000.00");
    }

    // ── ⑤ 세션 간 노출 상한이 REAL/PAPER 별도 우주로 분리되는지 ─────────

    @Test
    @DisplayName("PAPER 세션의 코인 보유는 REAL 세션의 동일코인 노출 상한 계산에 섞이지 않는다")
    void crossSessionExposure_isolatesRealFromPaper() {
        DynamicSessionEntity paperSession = newSession("PAPER");
        positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-ETH").side("BUY")
                .entryPrice(new BigDecimal("2700000")).avgPrice(new BigDecimal("2700000"))
                .size(new BigDecimal("0.003")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(paperSession.getId()).sessionKind("DYN_PAPER")
                .build());

        long heldByRealUniverse = positionRepository
                .countBySessionKindAndCoinPairAndStatusAndSessionIdNot("DYNAMIC", "KRW-ETH", "OPEN", -1L);

        assertThat(heldByRealUniverse)
                .as("PAPER 포지션은 session_kind='DYN_PAPER'라 REAL 노출 집계에 잡히지 않는다")
                .isZero();
    }
}
