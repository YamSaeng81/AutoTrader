package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>매도 격리 해제</b> (2026-10-19, V94) — {@link SellQuarantineReleaseService}.
 *
 * <p>상황: V87 이전(반영 표시가 없던 시절)에 9.8 개를 사서 3.0 개를 판 포지션. 그때 매도가 반영됐다면 수량은 6.8 이고, 반영 표시만 없다.
 * 매도 입구는 이 포지션을 격리한다(새 매도·손절이 나가지 않는다).</p>
 *
 * <p>확인하는 것: ① 반영된 매도 — 해제하면 기록·반영 표시가 남고 잔액·수량·주문 상태는 그대로, 새 매도가 다시 나가며 기동 점검 목록에서 빠진다
 * ② 🔴 반영되지 않은 매도(수량이 9.8 그대로)는 거절하고 아무것도 바꾸지 않는다 ③ 대조 수량이 지금과 다르면 거절 ④ 책임자·근거·증거 필수
 * ⑤ 두 번 해제 거절 ⑥ 같은 포지션에 미결 매도가 있으면 거절 ⑦ 격리 대상이 아닌 주문(닫힌 포지션·매수) 거절 ⑧ 매수 기록이 없으면 거절
 * ⑨ 해제 뒤 청산 확정이 그 매도를 다시 반영하지 않는다(새 매도만 한 번) ⑩~⑫ 🔴 해제 전 판별 불가 매도는 청산 확정·유령 정산이 자동 반영하지 않는다
 * (LIVE 청산 확정·DYNAMIC 청산 확정·DYNAMIC 유령 정산) ⑬ 대조군 — 경계 뒤의 미반영 체결 매도(정지 롤백 등)는 종전대로 자동 반영한다
 * ⑭ 🔴 경계 불명(이력·설정 없음) — 최근 매도도 자동 반영하지 않는다(보류) ⑮ 🔴 해제 알림은 커밋 뒤에만 — 롤백되면 기록·표시·알림 모두 없음.</p>
 *
 * <p>경계 시각(V87 설치 — 운영은 Flyway 이력)은 시험에서 1일 전으로 둔다: 30일 전 매도는 판별 불가, 방금 만든 매도는 아니다.</p>
 */
@DisplayName("매도 격리 해제 — 기록을 남기는 반영 표시")
class SellQuarantineReleaseTest extends IntegrationTestBase {

    private static final String COIN = "KRW-SQR";

    @Autowired private SellQuarantineReleaseService releaseService;
    @Autowired private SellQuarantineReport quarantineReport;
    @Autowired private OrderExecutionEngine engine;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LegacySellPolicy legacySellPolicy;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository dynamicRepo;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegram;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private final List<Long> sessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();

    @BeforeEach
    void cutoff() {
        legacySellPolicy.overrideCutoffForTest(Instant.now().minus(1, ChronoUnit.DAYS));
    }

    @AfterEach
    void tearDown() {
        for (Long id : sessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(o -> {
                jdbc.update("DELETE FROM sell_quarantine_release WHERE order_id = ?", o.getId());
                orderRepository.delete(o);
            });
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        for (Long id : dynamicSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
        legacySellPolicy.overrideCutoffForTest(null);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private static final Instant LEGACY = Instant.now().minus(30, ChronoUnit.DAYS);

    /** 9.8 매수(체결) + 3.0 매도(체결, 반영 표시 없음 — V87 이전). size 는 그 매도가 반영됐는지에 따라 정한다. */
    private PositionEntity legacyPosition(String size) {
        Long sid = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair(COIN).timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7958.53"))
                .totalAssetKrw(new BigDecimal("10000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        sessions.add(sid);
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair(COIN).side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal(size)).investedKrw(new BigDecimal("2941.47")).entryAllocationKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sid).openedAt(LEGACY).build());
        order(p, "BUY", "9.8", "FILLED", LEGACY);
        order(p, "SELL", "3.0", "FILLED", LEGACY.plus(1, ChronoUnit.HOURS));
        return p;
    }

    /** DYNAMIC 판: 같은 모양(9.8 매수·3.0 매도, 매도는 V87 이전·반영 표시 없음). */
    private PositionEntity legacyDynamicPosition(String size, String status) {
        Long sid = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7958.53")).totalAssetKrw(new BigDecimal("10000"))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("POSITION_MONITORING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).build()).getId();
        dynamicSessions.add(sid);
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair(COIN).side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal(size)).investedKrw(new BigDecimal("2941.47")).entryAllocationKrw(new BigDecimal("3000"))
                .status(status).sessionKind("DYNAMIC").sessionId(sid).openedAt(LEGACY).build());
        if ("CLOSING".equals(status)) {
            jdbc.update("UPDATE position SET closing_at = ? WHERE id = ?", Timestamp.from(Instant.now()), p.getId());
        }
        order(p, "BUY", "9.8", "FILLED", LEGACY);
        order(p, "SELL", "3.0", "FILLED", LEGACY.plus(1, ChronoUnit.HOURS));
        jdbc.update("UPDATE dynamic_session SET current_position_id = ? WHERE id = ?", p.getId(), sid);
        return p;
    }

    private BigDecimal dynamicAvailable(PositionEntity p) {
        return dynamicRepo.findById(p.getSessionId()).orElseThrow().getAvailableKrw();
    }

    private OrderEntity order(PositionEntity p, String side, String filled, String state, Instant at) {
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair(COIN).side(side).orderType("MARKET").price(new BigDecimal("300"))
                .quantity(new BigDecimal(filled)).filledQuantity(new BigDecimal(filled)).state(state)
                .sessionKind(p.getSessionKind()).sessionId(p.getSessionId()).positionId(p.getId())
                .exchangeOrderId("sqr-" + UUID.randomUUID()).clientOrderId("sqr-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING).createdAt(at).build());
        jdbc.update("UPDATE \"order\" SET created_at = ? WHERE id = ?", Timestamp.from(at), o.getId());
        return o;
    }

    private OrderEntity legacySell(PositionEntity p) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(p.getSessionKind(), p.getSessionId()).stream()
                .filter(o -> "SELL".equals(o.getSide())).reduce((a, b) -> b).orElseThrow();
    }

    private void sell(PositionEntity p, String qty) {
        OrderRequest r = new OrderRequest();
        r.setCoinPair(COIN);
        r.setSide("SELL");
        r.setOrderType("MARKET");
        r.setQuantity(new BigDecimal(qty));
        r.setReason("격리 해제 시험");
        r.setSessionId(p.getSessionId());
        r.setSessionKind("LIVE");
        r.setPositionId(p.getId());
        ((OrderExecutionEngine) AopTestUtils.getUltimateTargetObject(engine)).submitOrder(r);
    }

    private static OrderResponse accepted() {
        OrderResponse r = new OrderResponse();
        r.setUuid("sqr-new-" + UUID.randomUUID());
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket(COIN);
        return r;
    }

    private Instant appliedAt(Long orderId) {
        Timestamp t = jdbc.queryForObject("SELECT sell_applied_at FROM \"order\" WHERE id = ?", Timestamp.class, orderId);
        return t == null ? null : t.toInstant();
    }

    private boolean listed(PositionEntity p) {
        return quarantineReport.report().stream().anyMatch(r -> ((Number) (r.get("position_id") != null
                ? r.get("position_id") : r.get("POSITION_ID"))).longValue() == p.getId());
    }

    private SellQuarantineReleaseService.Release release(OrderEntity o, String confirmed) {
        return releaseService.release(o.getId(), "운영자", "업비트 주문 내역 대조 — 3.0 매도 체결이 포지션 수량에 반영돼 있다",
                "업비트 주문 " + o.getExchangeOrderId() + " 체결 3.0", new BigDecimal(confirmed), Instant.now());
    }

    // ── 시험 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("① 반영된 매도 — 기록·반영 표시, 잔액·수량·상태 불변, 새 매도 다시 나감, 기동 점검 목록에서 빠짐")
    void releaseAppliedLegacySell() {
        when(upbitOrderClient.createOrder(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new UpbitOrderClient.ExchangeResult(accepted(), "{}"));
        PositionEntity p = legacyPosition("6.8");
        OrderEntity s = legacySell(p);
        sell(p, "6.8");
        verify(upbitOrderClient, never()).createOrder(any(), any(), any(), any(), any(), any());
        assertThat(listed(p)).as("해제 전: 격리").isTrue();

        SellQuarantineReleaseService.Release r = release(s, "6.8");

        assertThat(r.positionSizeAt()).isEqualByComparingTo("6.8");
        assertThat(r.buyFilledQty()).isEqualByComparingTo("9.8");
        assertThat(r.sellFilledQty()).isEqualByComparingTo("3.0");
        assertThat(r.releasedBy()).isEqualTo("운영자");
        assertThat(appliedAt(s.getId())).isNotNull();
        assertThat(orderRepository.findById(s.getId()).orElseThrow().getState()).as("주문 상태 불변").isEqualTo("FILLED");
        assertThat(positionRepository.findById(p.getId()).orElseThrow().getSize()).isEqualByComparingTo("6.8");
        assertThat(liveRepo.findById(p.getSessionId()).orElseThrow().getAvailableKrw()).as("잔액 불변").isEqualByComparingTo("7958.53");
        assertThat(listed(p)).as("해제 뒤: 기동 점검 목록에서 빠진다").isFalse();

        sell(p, "6.8");
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 ② 반영되지 않은 매도(수량 9.8 그대로) — 거절, 기록·표시 없음 (해제하면 이미 판 3.0 을 다시 팔 수 있다)")
    void refuseUnappliedSell() {
        PositionEntity p = legacyPosition("9.8");
        OrderEntity s = legacySell(p);

        assertThatThrownBy(() -> release(s, "9.8")).hasMessageContaining("반영되지 않은 것으로 보인다");
        assertThat(appliedAt(s.getId())).isNull();
        assertThat(releaseService.find(s.getId())).isNull();
        assertThat(listed(p)).isTrue();
    }

    @Test
    @DisplayName("③ 대조 수량이 지금 DB 값과 다르면 거절")
    void refuseStaleConfirmation() {
        PositionEntity p = legacyPosition("6.8");
        assertThatThrownBy(() -> release(legacySell(p), "6.7")).hasMessageContaining("대조 뒤 바뀌었다");
        assertThat(appliedAt(legacySell(p).getId())).isNull();
    }

    @Test
    @DisplayName("④ 책임자·근거·증거는 필수")
    void requireFields() {
        PositionEntity p = legacyPosition("6.8");
        Long id = legacySell(p).getId();
        BigDecimal q = new BigDecimal("6.8");
        assertThatThrownBy(() -> releaseService.release(id, " ", "근거", "증거", q, Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> releaseService.release(id, "운영자", "", "증거", q, Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> releaseService.release(id, "운영자", "근거", null, q, Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> releaseService.release(id, "운영자", "근거", "증거", null, Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(appliedAt(id)).isNull();
    }

    @Test
    @DisplayName("⑤ 두 번 해제 거절")
    void refuseDoubleRelease() {
        PositionEntity p = legacyPosition("6.8");
        OrderEntity s = legacySell(p);
        release(s, "6.8");
        assertThatThrownBy(() -> release(s, "6.8")).hasMessageContaining("이미 반영 표시가 있는 매도");
    }

    @Test
    @DisplayName("⑥ 같은 포지션에 미결 매도가 있으면 거절 — 식별자 조회가 결론 낼 일이다")
    void refuseWithUnresolvedSell() {
        PositionEntity p = legacyPosition("6.8");
        OrderEntity s = legacySell(p);
        OrderEntity pending = order(p, "SELL", "0", "SUBMITTED", Instant.now());
        jdbc.update("UPDATE \"order\" SET filled_quantity = 0 WHERE id = ?", pending.getId());
        assertThatThrownBy(() -> release(s, "6.8")).hasMessageContaining("미결 매도");
    }

    @Test
    @DisplayName("⑦ 격리 대상이 아닌 주문 — 닫힌 포지션의 매도·매수 주문은 거절")
    void refuseNonQuarantine() {
        PositionEntity p = legacyPosition("6.8");
        OrderEntity s = legacySell(p);
        OrderEntity buy = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", p.getSessionId()).stream()
                .filter(o -> "BUY".equals(o.getSide())).findFirst().orElseThrow();
        assertThatThrownBy(() -> release(buy, "6.8")).hasMessageContaining("실자금 매도만");
        jdbc.update("UPDATE position SET status = 'CLOSED' WHERE id = ?", p.getId());
        assertThatThrownBy(() -> release(s, "6.8")).hasMessageContaining("열린 포지션");
    }

    @Test
    @DisplayName("⑧ 이 포지션의 체결 매수 기록이 없으면 수량 근거를 확인할 수 없어 거절")
    void refuseWithoutBuyRecord() {
        PositionEntity p = legacyPosition("6.8");
        orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", p.getSessionId()).stream()
                .filter(o -> "BUY".equals(o.getSide())).forEach(orderRepository::delete);
        jdbc.update("UPDATE position SET size = ? WHERE id = ?", new BigDecimal("-3.0"), p.getId());
        assertThatThrownBy(() -> release(legacySell(p), "-3.0")).hasMessageContaining("체결 매수 기록이 없다");
    }

    @Test
    @DisplayName("🔴 ⑩ 해제 전 격리 포지션이 CLOSING 이 되면(정지가 표시만 하고 매도는 막힘) — 청산 확정이 V87 이전 매도를 자동 반영하지 않는다")
    void quarantinedClosingDoesNotAutoApplyLegacySell() {
        PositionEntity p = legacyPosition("6.8");          // 그 매도는 사실 이미 반영돼 있다
        OrderEntity s = legacySell(p);
        jdbc.update("UPDATE position SET status = 'CLOSING', closing_at = ? WHERE id = ?", Timestamp.from(Instant.now()), p.getId());
        sell(p, "6.8");                                    // 정지의 매도 — 격리로 막힌다
        verify(upbitOrderClient, never()).createOrder(any(), any(), any(), any(), any(), any());

        liveTradingService.reconcileClosingPositions();

        PositionEntity after = positionRepository.findById(p.getId()).orElseThrow();
        System.out.println("[격리 CLOSING 측정] 포지션=" + after.getStatus() + "/" + after.getSize() + " 매도 반영 표시="
                + appliedAt(s.getId()) + " 잔액=" + liveRepo.findById(p.getSessionId()).orElseThrow().getAvailableKrw());
        assertThat(appliedAt(s.getId())).as("🔴 판별 불가 매도를 자동 반영하면 대금이 두 번 들어간다").isNull();
        assertThat(after.getSize()).isEqualByComparingTo("6.8");
        assertThat(liveRepo.findById(p.getSessionId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo("7958.53");
    }

    @Test
    @DisplayName("🔴 ⑪ DYNAMIC 청산 확정 — 격리 포지션이 CLOSING 이어도 V87 이전 매도를 자동 반영하지 않는다")
    void dynamicClosingDoesNotAutoApplyLegacySell() {
        PositionEntity p = legacyDynamicPosition("6.8", "CLOSING");
        OrderEntity s = legacySell(p);

        dynamicTradingService.reconcileDynamicClosingPositions();

        assertThat(appliedAt(s.getId())).isNull();
        assertThat(positionRepository.findById(p.getId()).orElseThrow().getSize()).isEqualByComparingTo("6.8");
        assertThat(dynamicAvailable(p)).isEqualByComparingTo("7958.53");
    }

    @Test
    @DisplayName("🔴 ⑫ DYNAMIC 유령 정산 — 열린 격리 포지션의 V87 이전 매도를 자동 반영하지 않는다")
    void dynamicGhostDoesNotAutoApplyLegacySell() {
        // 유령 정산의 조건에 맞춘다 — 매도 체결량 = 포지션 수량, 체결 뒤 유예 시간이 지났고, 한 번도 정산되지 않았다(실현 손익 0).
        // 수량 3.0 이 그 매도를 반영하지 않은 값인지(9.8 − 3.0 이 아니라 앞선 다른 매도가 있었는지) DB 로는 판별할 수 없다.
        PositionEntity p = legacyDynamicPosition("3.0", "OPEN");
        OrderEntity s = legacySell(p);
        jdbc.update("UPDATE \"order\" SET filled_at = ? WHERE id = ?", Timestamp.from(LEGACY.plus(1, ChronoUnit.HOURS)), s.getId());

        dynamicTradingService.reconcileDynamicGhostPositions();

        assertThat(appliedAt(s.getId())).as("판별 불가 매도는 유령 정산도 자동 반영하지 않는다 — 사람이 판정한다").isNull();
        assertThat(positionRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        assertThat(positionRepository.findById(p.getId()).orElseThrow().getSize()).isEqualByComparingTo("3.0");
        assertThat(dynamicAvailable(p)).isEqualByComparingTo("7958.53");
    }

    @Test
    @DisplayName("⑬ 대조군 — 경계 뒤의 미반영 체결 매도(정지 롤백 등)는 종전대로 자동 반영한다(대금 한 번)")
    void postCutoffUnappliedSellStillApplied() {
        PositionEntity p = legacyPosition("9.8");
        // 옛 매도는 해제된 것으로 두고(표시만), 경계 뒤의 미반영 체결 매도 하나를 둔다
        jdbc.update("UPDATE \"order\" SET sell_applied_at = ? WHERE id = ?", Timestamp.from(Instant.now()), legacySell(p).getId());
        jdbc.update("UPDATE position SET size = 6.8, status = 'CLOSING', closing_at = ? WHERE id = ?", Timestamp.from(Instant.now()), p.getId());
        OrderEntity rolledBack = order(p, "SELL", "6.8", "FILLED", Instant.now());
        jdbc.update("UPDATE \"order\" SET reported_executed_funds = 2040, reported_paid_fee = 1.02 WHERE id = ?", rolledBack.getId());

        liveTradingService.reconcileClosingPositions();

        assertThat(appliedAt(rolledBack.getId())).isNotNull();
        assertThat(positionRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
        assertThat(liveRepo.findById(p.getSessionId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo("9997.51");
    }

    @Test
    @DisplayName("🔴 ⑭ 경계 불명(Flyway 이력·설정 없음, 운영처럼) — 경계 뒤의 미반영 체결 매도도 자동 반영하지 않고 보류한다")
    void unknownBoundaryHoldsAutoApply() {
        PositionEntity p = legacyPosition("9.8");
        jdbc.update("UPDATE \"order\" SET sell_applied_at = ? WHERE id = ?", Timestamp.from(Instant.now()), legacySell(p).getId());
        jdbc.update("UPDATE position SET size = 6.8, status = 'CLOSING', closing_at = ? WHERE id = ?", Timestamp.from(Instant.now()), p.getId());
        OrderEntity recent = order(p, "SELL", "6.8", "FILLED", Instant.now());
        jdbc.update("UPDATE \"order\" SET reported_executed_funds = 2040, reported_paid_fee = 1.02 WHERE id = ?", recent.getId());
        legacySellPolicy.resetForTest(false);   // 시험 기반의 H2 에는 Flyway 이력이 없다 — 설정도 없다 → 경계 불명

        liveTradingService.reconcileClosingPositions();

        assertThat(legacySellPolicy.state()).isEqualTo(LegacySellPolicy.State.UNKNOWN);
        assertThat(appliedAt(recent.getId())).as("판정할 수 없으면 보류").isNull();
        assertThat(liveRepo.findById(p.getSessionId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo("7958.53");
        verify(telegram, times(1)).notifyRiskLimitBreached(org.mockito.ArgumentMatchers.contains("경계 시각을 정하지 못했다"));
    }

    @Test
    @DisplayName("🔴 ⑮ 해제 알림은 커밋 뒤에만 — 바깥 트랜잭션이 롤백되면 기록·반영 표시·알림이 모두 없다. 커밋되면 알림 한 번")
    void releaseNotificationAfterCommitOnly() {
        PositionEntity p = legacyPosition("6.8");
        OrderEntity s = legacySell(p);

        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.executeWithoutResult(st -> {
            release(s, "6.8");
            st.setRollbackOnly();   // 해제 뒤 커밋이 실패한 것과 같다
        });
        assertThat(releaseService.find(s.getId())).isNull();
        assertThat(appliedAt(s.getId())).isNull();
        verify(telegram, never()).notifyRiskLimitBreached(org.mockito.ArgumentMatchers.contains("매도 격리 해제"));
        assertThat(listed(p)).as("여전히 격리").isTrue();

        release(s, "6.8");
        assertThat(releaseService.find(s.getId())).isNotNull();
        verify(telegram, times(1)).notifyRiskLimitBreached(org.mockito.ArgumentMatchers.contains("매도 격리 해제"));
    }

    @Test
    @DisplayName("⑨ 해제 뒤 청산 — 청산 확정은 해제한 매도를 다시 반영하지 않고 새 매도만 한 번 반영한다")
    void settlementAfterReleaseAppliesOnlyNewSell() {
        PositionEntity p = legacyPosition("6.8");
        OrderEntity s = legacySell(p);
        release(s, "6.8");
        Instant releasedMark = appliedAt(s.getId());

        jdbc.update("UPDATE position SET status = 'CLOSING', closing_at = ? WHERE id = ?", Timestamp.from(Instant.now()), p.getId());
        OrderEntity fresh = order(p, "SELL", "6.8", "FILLED", Instant.now());
        jdbc.update("UPDATE \"order\" SET reported_executed_funds = 2040, reported_paid_fee = 1.02 WHERE id = ?", fresh.getId());

        liveTradingService.reconcileClosingPositions();
        liveTradingService.reconcileClosingPositions();

        PositionEntity after = positionRepository.findById(p.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("CLOSED");
        assertThat(appliedAt(s.getId())).as("해제 표시 그대로").isEqualTo(releasedMark);
        assertThat(appliedAt(fresh.getId())).isNotNull();
        assertThat(liveRepo.findById(p.getSessionId()).orElseThrow().getAvailableKrw())
                .as("새 매도 대금 한 번: 7,958.53 + 2,040 − 1.02").isEqualByComparingTo("9997.51");
    }
}
