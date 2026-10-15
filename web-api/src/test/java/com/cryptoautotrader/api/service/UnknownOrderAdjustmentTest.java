package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.OrderSettlementDiscrepancyEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.OrderSettlementDiscrepancyRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시험 16 — 장기 {@code UNKNOWN} 수동 조정 (선행 조건 5-i, 2026-10-15, V91).
 *
 * <p>기대(설계 §7-16): 주문이 {@code FAILED} 로 바뀌지 않고, <b>근거·금액·책임자·미해결 위험</b>이 보존된다. 더해서: 잔액은 한 번만
 * 돌아오고, 공유 풀은 그 주문을 계속 미결로 세며, 조정 뒤 거래소가 그 주문을 보고하면 자동 정산하지 않는다.</p>
 */
@DisplayName("장기 UNKNOWN 수동 조정 — 상태 유지 · 기록 보존 · 한 번 반환 · 자동 정산 차단")
class UnknownOrderAdjustmentTest extends IntegrationTestBase {

    @Autowired private UnknownOrderAdjustmentService adjustments;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderSettlementDiscrepancyRepository discrepancyRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> sessions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long id : sessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(o -> {
                discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(o.getId()).forEach(discrepancyRepository::delete);
                jdbc.update("DELETE FROM order_manual_adjustment WHERE order_id = ?", o.getId());
                orderRepository.delete(o);
            });
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
    }

    private record Fixture(Long sessionId, Long positionId, Long orderId, String identifier) {}

    /** 세션 10,000 에서 3,000 을 예약한 시장가 매수가 응답 불명으로 {@code minutesAgo} 분째 남아 있다. */
    private Fixture unknownBuy(String state, long minutesAgo) {
        Long sid = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-UNK").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                .totalAssetKrw(new BigDecimal("10000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        sessions.add(sid);
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-UNK").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(BigDecimal.ZERO).investedKrw(new BigDecimal("3000")).entryAllocationKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sid).openedAt(Instant.now()).build());
        String ident = "unk-" + UUID.randomUUID();
        Instant created = Instant.now().minus(minutesAgo, ChronoUnit.MINUTES);
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-UNK").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000")).reservedKrw(new BigDecimal("3000")).state(state)
                .sessionKind("LIVE").sessionId(sid).positionId(p.getId()).clientOrderId(ident)
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .unknownReason("UNKNOWN".equals(state) ? "제출 응답 시간 초과" : null)
                .createdAt(created).build());
        return new Fixture(sid, p.getId(), o.getId(), ident);
    }

    private BigDecimal available(Fixture f) {
        return liveRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw();
    }

    private OrderEntity order(Fixture f) {
        return orderRepository.findById(f.orderId()).orElseThrow();
    }

    @Test
    @DisplayName("조정: 주문은 UNKNOWN 그대로, 근거·금액·책임자·미해결 위험 보존, 잔액 한 번 반환, 공유 풀은 계속 미결로 센다")
    void adjustmentKeepsUnknownAndPreservesRecord() {
        Fixture f = unknownBuy("UNKNOWN", 45);
        BigDecimal poolOpenBefore = orderRepository.sumOpenReservedKrw();
        BigDecimal commitmentBefore = orderRepository.sumOpenBuyCommitmentKrw();

        UnknownOrderAdjustmentService.Adjustment a = adjustments.record(f.orderId(), "운영자 본인",
                "업비트 주문 내역(웹)에 이 식별자의 주문이 없음을 확인", "주문 내역 화면 2026-10-15 10:00 KST",
                new BigDecimal("3000"), "거래소가 늦게 접수·체결했을 가능성 — 그 경우 같은 3,000 이 두 번 잡힌다", Instant.now());

        OrderEntity o = order(f);
        assertThat(o.getState()).as("🔴 사람의 판단은 거래소 거절의 증거가 아니다 — FAILED 로 바꾸지 않는다").isEqualTo("UNKNOWN");
        assertThat(o.getReservedKrw()).isEqualByComparingTo("3000");
        assertThat(o.getClientOrderId()).isEqualTo(f.identifier());
        assertThat(a.orderStateAt()).isEqualTo("UNKNOWN");
        assertThat(a.adjustedBy()).isEqualTo("운영자 본인");
        assertThat(a.reason()).contains("주문이 없음을 확인");
        assertThat(a.evidence()).contains("주문 내역 화면");
        assertThat(a.releasedKrw()).isEqualByComparingTo("3000");
        assertThat(a.unresolvedRisk()).contains("두 번 잡힌다");
        assertThat(a.createdAt()).isNotNull();
        assertThat(available(f)).as("세션 잔액에 한 번 반환").isEqualByComparingTo("10000");
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        assertThat(orderRepository.sumOpenReservedKrw()).as("공유 풀은 계속 미결로 센다").isEqualByComparingTo(poolOpenBefore);
        assertThat(orderRepository.sumOpenBuyCommitmentKrw()).isEqualByComparingTo(commitmentBefore);

        // 두 번째 조정은 받지 않는다 — 잔액도 그대로
        assertThatThrownBy(() -> adjustments.record(f.orderId(), "운영자 본인", "다시", null, new BigDecimal("3000"),
                "위와 같음", Instant.now())).isInstanceOf(IllegalStateException.class).hasMessageContaining("이미");
        assertThat(available(f)).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("거부: UNKNOWN 이 아님 · 30분 미만 · 책임자/근거/미해결 위험 공란 · 예약액 초과 — 기록·잔액 변화 없음")
    void rejectsInvalidAdjustments() {
        Fixture submitted = unknownBuy("SUBMITTED", 45);
        assertThatThrownBy(() -> adjustments.record(submitted.orderId(), "운영자", "근거", null, new BigDecimal("3000"), "위험",
                Instant.now())).isInstanceOf(IllegalStateException.class).hasMessageContaining("UNKNOWN");

        Fixture young = unknownBuy("UNKNOWN", 5);
        assertThatThrownBy(() -> adjustments.record(young.orderId(), "운영자", "근거", null, new BigDecimal("3000"), "위험",
                Instant.now())).isInstanceOf(IllegalStateException.class).hasMessageContaining("30분");

        Fixture f = unknownBuy("UNKNOWN", 45);
        assertThatThrownBy(() -> adjustments.record(f.orderId(), " ", "근거", null, BigDecimal.ONE, "위험", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adjustments.record(f.orderId(), "운영자", "근거", null, BigDecimal.ONE, "", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adjustments.record(f.orderId(), "운영자", "근거", null, new BigDecimal("3000.01"), "위험",
                Instant.now())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("예약액");

        for (Fixture x : List.of(submitted, young, f)) {
            assertThat(adjustments.find(x.orderId())).isNull();
            assertThat(available(x)).isEqualByComparingTo("7000");
        }
        assertThat(order(submitted).getState()).isEqualTo("SUBMITTED");
        assertThat(order(f).getState()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("조정 뒤 거래소가 그 주문을 체결로 보고하면 자동 정산하지 않고 관측·불일치로 남긴다(대조군: 조정 없으면 추적 경로로 넘어간다)")
    void laterExchangeReportIsNotAutoSettled() {
        Fixture f = unknownBuy("UNKNOWN", 45);
        adjustments.record(f.orderId(), "운영자 본인", "주문 내역에 없음", null, new BigDecimal("3000"), "늦은 체결 가능성", Instant.now());

        Mockito.doReturn(done("uuid-late-" + f.orderId())).when(upbitOrderClient).getOrderByIdentifier(f.identifier());
        orderExecutionEngine.resolveUnresolvedSubmission(f.orderId());
        orderExecutionEngine.resolveUnresolvedSubmission(f.orderId());   // 반복 조회

        OrderEntity o = order(f);
        assertThat(o.getState()).isEqualTo("UNKNOWN");
        assertThat(o.getExchangeOrderId()).as("기존 추적 경로에 연결하지 않는다").isNull();
        assertThat(o.getAppliedExecutedFunds()).isNull();
        assertThat(available(f)).as("사람이 돌려준 3,000 위에 자동 정산을 겹치지 않는다").isEqualByComparingTo("10000");
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getSize()).isEqualByComparingTo("0");
        UnknownOrderAdjustmentService.Adjustment a = adjustments.find(f.orderId());
        assertThat(a.laterObservation()).contains("done").contains("9.8").contains("2940");
        assertThat(a.laterObservedAt()).isNotNull();
        List<OrderSettlementDiscrepancyEntity> d = discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(f.orderId());
        assertThat(d).as("같은 관측의 반복은 한 건").hasSize(1);
        assertThat(d.get(0).getKind()).isEqualTo(UnknownOrderAdjustmentService.DISCREPANCY_KIND);
        assertThat(d.get(0).getReceivedValue()).isEqualByComparingTo("9.8");
        assertThat(d.get(0).getResolvedAt()).isNull();

        // 대조군 — 조정하지 않은 같은 주문은 거래소가 찾아지면 SUBMITTED 로 올라 기존 폴링·정산 경로로 간다.
        Fixture g = unknownBuy("UNKNOWN", 45);
        Mockito.doReturn(done("uuid-ctl-" + g.orderId())).when(upbitOrderClient).getOrderByIdentifier(g.identifier());
        Mockito.doReturn(done("uuid-ctl-" + g.orderId())).when(upbitOrderClient).getOrder("uuid-ctl-" + g.orderId());
        orderExecutionEngine.resolveUnresolvedSubmission(g.orderId());
        assertThat(order(g).getState()).isNotEqualTo("UNKNOWN");
        assertThat(order(g).getExchangeOrderId()).isEqualTo("uuid-ctl-" + g.orderId());
    }

    private static OrderResponse done(String uuid) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("bid");
        r.setOrdType("price");
        r.setState("done");
        r.setMarket("KRW-UNK");
        r.setPrice(new BigDecimal("3000"));
        r.setExecutedVolume(new BigDecimal("9.8"));
        r.setExecutedFunds(new BigDecimal("2940"));
        r.setPaidFee(new BigDecimal("1.47"));
        return r;
    }
}
