package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.dto.LiveTradingStartRequest;
import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공유 풀 한도와 계좌 여력의 <b>정의</b>를 고정한다 (2026-10-12).
 *
 * <p>동시성은 {@code ConcurrentEntryCapTest} 가 맡는다. 여기서는 "무엇을 세는가" 를 고정한다 —
 * 계좌 잔고가 총잔고인지 주문 가능 잔고인지, 기존 예약이 이미 차감된 값인지가 틀리면 동시성이
 * 완벽해도 <b>이중 차감</b>(과소 승인)이나 <b>과대 승인</b>이 난다.</p>
 *
 * <p>🔴 시험 기반의 DB 는 다른 시험과 공유된다. 그래서 모든 한도를 <b>시험 시작 시점의 기준선 위에</b>
 * 정한다 — 남은 행 때문에 시험이 환경을 측정하지 않게 한다.</p>
 */
@DisplayName("공유 풀 한도 · 계좌 여력의 정의")
class CapitalPoolLimitTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PortfolioManager portfolioManager;
    @Autowired private LiveTradingSessionRepository sessionRepository;
    @Autowired private DynamicSessionRepository dynamicSessionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PlatformTransactionManager txManager;

    /** 실제 동기화가 스냅숏을 덮어쓰지 않게 막는다 — 스냅숏은 시험이 정한다. */
    @MockBean private PortfolioSyncService portfolioSyncService;

    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final List<Long> orders = new ArrayList<>();

    @Autowired private org.springframework.jdbc.core.JdbcTemplate quiesceJdbc;

    @BeforeEach
    void save() {
        // 계좌 여력은 전역 약정 합을 쓴다 — 앞선 시험이 남긴 열린 매수가 시험 도중 움직이지 않게 닫는다.
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(quiesceJdbc);
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
    }

    @AfterEach
    void restore() {
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
        orders.forEach(id -> orderRepository.findById(id).ifPresent(orderRepository::delete));
        liveSessions.forEach(id -> positionRepository.findBySessionKindAndSessionId("LIVE", id)
                .forEach(positionRepository::delete));
        liveSessions.forEach(id -> sessionRepository.findById(id).ifPresent(sessionRepository::delete));
        dynamicSessions.forEach(id -> dynamicSessionRepository.findById(id).ifPresent(dynamicSessionRepository::delete));
        portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private Long liveSession(String status, BigDecimal initial, BigDecimal available) {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-DOGE").timeframe("H1")
                .initialCapital(initial).availableKrw(available).totalAssetKrw(initial)
                .status(status).startedAt(Instant.now()).build());
        liveSessions.add(s.getId());
        return s.getId();
    }

    /** 운영 진입점으로 예약한다. @return 거절 사유 코드, 승인이면 {@code null} */
    private String reserve(Long sessionId, String coin, BigDecimal amount) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            Long id = tx.execute(st -> {
                PositionEntity pos = positionRepository.save(PositionEntity.builder()
                        .coinPair(coin).side("BUY")
                        .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                        .size(BigDecimal.ZERO).investedKrw(amount)
                        .status("OPEN").sessionKind("LIVE").sessionId(sessionId)
                        .openedAt(Instant.now()).build());
                OrderRequest req = new OrderRequest();
                req.setCoinPair(coin);
                req.setSide("BUY");
                req.setOrderType("MARKET");
                req.setQuantity(amount);
                req.setReason("공유 풀 정의 시험");
                req.setSignalPrice(new BigDecimal("300"));
                req.setSessionId(sessionId);
                req.setSessionKind("LIVE");
                req.setPositionId(pos.getId());
                return orderExecutionEngine.reserveAndPrepareIntent(req);
            });
            orders.add(id);
            return null;
        } catch (OrderExecutionEngine.ReservationRejected rejected) {
            return rejected.getCode();
        }
    }

    private BigDecimal openCommitment() {
        return orderRepository.sumOpenBuyCommitmentKrw();
    }

    /** 등록 풀은 넉넉하게 — 계좌 축만 시험할 때 쓴다. */
    private void generousPool() {
        limits.setTotalCapKrw(new BigDecimal("1000000000000"));
        limits.setPerPositionCapKrw(new BigDecimal("1000000000000"));
        limits.setMaxConcurrentPositions(100_000);
    }

    // ── 계좌 여력의 정의 ────────────────────────────────────────────

    @Test
    @DisplayName("계좌 스냅숏은 총잔고이고 열린 예약은 한 번만 뺀다 — 이중 차감이 없다")
    void openReservationsAreDeductedOnce() {
        generousPool();
        // 총잔고 = 기준선 + 6,000. 3,000 을 예약하면 그 돈은 아직 계좌 **안에** 있다(가용 또는 잠금).
        portfolioManager.syncTotalCapital(openCommitment().add(new BigDecimal("6000")));
        // 세션마다 미해결 제출이 하나면 신규 매수가 막히므로(V84) 세션을 나눈다.
        Long a = liveSession("RUNNING", new BigDecimal("10000"), new BigDecimal("10000"));
        Long b = liveSession("RUNNING", new BigDecimal("10000"), new BigDecimal("10000"));
        Long c = liveSession("RUNNING", new BigDecimal("10000"), new BigDecimal("10000"));

        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("3000"))).isNull();
        // 🔴 이중 차감이면(세션 가용 KRW 감소 + 열린 예약을 둘 다 빼면) 여기서 거절된다.
        assertThat(reserve(b, "KRW-ADA", new BigDecimal("3000")))
                .as("6,000 − 열린 예약 3,000 = 여력 3,000 — 정확히 들어간다")
                .isNull();
        assertThat(reserve(c, "KRW-XRP", new BigDecimal("1")))
                .as("여력 0 — 1원도 더 승인하지 않는다")
                .isEqualTo(CapitalReservationService.ACCOUNT_HEADROOM);
    }

    @Test
    @DisplayName("스냅숏 이후 로컬에서 체결된 매수는 아직 스냅숏 안의 돈으로 센다 — 과대 승인이 없다")
    void fillsAfterSnapshotStillCount() {
        generousPool();
        portfolioManager.syncTotalCapital(openCommitment().add(new BigDecimal("3000")));
        Long a = liveSession("RUNNING", new BigDecimal("10000"), new BigDecimal("10000"));
        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("3000"))).isNull();

        // 스냅숏 **뒤에** 체결돼 종결됐다. 열린 예약에서는 빠지지만 스냅숏은 체결 전 값이다.
        OrderEntity filled = orderRepository.findById(orders.get(0)).orElseThrow();
        filled.setState("FILLED");
        filled.setFilledAt(Instant.now().plusSeconds(1));
        orderRepository.save(filled);

        assertThat(reserve(a, "KRW-ADA", new BigDecimal("3000")))
                .as("🔴 종결된 주문을 빼고 스냅숏(3,000)을 그대로 믿으면 이미 쓴 돈을 다시 승인한다")
                .isEqualTo(CapitalReservationService.ACCOUNT_HEADROOM);

        // 동기화가 체결 뒤의 잔고(0)를 반영하면 그 주문은 더 세지 않는다 — 스냅숏이 이미 뺐다.
        portfolioManager.syncTotalCapital(openCommitment());
        filled.setFilledAt(Instant.now().minusSeconds(1));
        orderRepository.save(filled);
        assertThat(reserve(a, "KRW-XRP", new BigDecimal("1")))
                .as("체결 후 스냅숏에 남은 여력은 0 이다")
                .isEqualTo(CapitalReservationService.ACCOUNT_HEADROOM);
        portfolioManager.syncTotalCapital(openCommitment().add(new BigDecimal("3000")));
        assertThat(reserve(a, "KRW-XRP", new BigDecimal("3000")))
                .as("새 스냅숏에 들어온 돈만큼은 승인한다 — 스냅숏 전 체결은 다시 빼지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("스냅숏이 없거나 낡았으면 승인하지 않는다 — 0 이면 건너뛰던 종전 동작을 막는다")
    void missingOrStaleSnapshotRejects() {
        generousPool();
        Long a = liveSession("RUNNING", new BigDecimal("10000"), new BigDecimal("10000"));

        ReflectionTestUtils.setField(portfolioManager, "snapshot", null);
        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("3000")))
                .isEqualTo(CapitalReservationService.ACCOUNT_SNAPSHOT_MISSING);

        portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));
        ReflectionTestUtils.setField(portfolioManager, "snapshot", new PortfolioManager.CapitalSnapshot(
                new BigDecimal("1000000000000"),
                Instant.now().minus(CapitalReservationService.MAX_SNAPSHOT_AGE).minus(Duration.ofMinutes(1))));
        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("3000")))
                .as("🔴 동기화가 멈추면 계좌에서 돈이 빠져도 모른다")
                .isEqualTo(CapitalReservationService.ACCOUNT_SNAPSHOT_STALE);
    }

    // ── 등록 풀 한도 ────────────────────────────────────────────────

    @Test
    @DisplayName("등록되지 않으면 승인하지 않고, 거절은 슬롯까지 롤백한다 — 차감 없는 고아 포지션이 남지 않는다")
    void unregisteredPoolRejectsAndRollsBackSlot() {
        Long a = liveSession("RUNNING", new BigDecimal("10000"), new BigDecimal("10000"));
        limits.setTotalCapKrw(null);
        long positionsBefore = positionRepository.findBySessionKindAndSessionId("LIVE", a).size();

        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("3000")))
                .isEqualTo(CapitalReservationService.POOL_NOT_REGISTERED);
        assertThat(positionRepository.findBySessionKindAndSessionId("LIVE", a))
                .as("🔴 V85 는 한도 거절을 null 로 돌려 트랜잭션이 커밋됐고, 차감 없는 size=0 포지션이 남았다. "
                        + "고아 정리가 그것을 배정액으로 복원하면 없던 돈이 생긴다")
                .hasSize((int) positionsBefore);
        assertThat(sessionRepository.findById(a).orElseThrow().getAvailableKrw())
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("포지션당 상한 — 한 번의 진입 금액이 등록 금액을 넘으면 거절한다")
    void perPositionCap() {
        generousPool();
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        Long a = liveSession("RUNNING", new BigDecimal("100000"), new BigDecimal("100000"));

        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("20001")))
                .isEqualTo(CapitalReservationService.PER_POSITION_CAP);
        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("20000"))).isNull();
    }

    @Test
    @DisplayName("계좌에 다른 자금이 있어도 등록 한도가 승인을 정한다")
    void accountSurplusDoesNotRaisePoolLimit() {
        generousPool();
        portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));   // 계좌에는 1조
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("100000")));
        Long a = liveSession("RUNNING", new BigDecimal("200000"), new BigDecimal("200000"));
        Long b = liveSession("RUNNING", new BigDecimal("200000"), new BigDecimal("200000"));

        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("60000"))).isNull();
        assertThat(reserve(b, "KRW-ADA", new BigDecimal("60000")))
                .as("🔴 거래소는 이 주문을 받아 줄 것이다 — 막는 것은 등록 한도뿐이다")
                .isEqualTo(CapitalReservationService.POOL_CAP);
    }

    // ── 세션 생성·재시작 ───────────────────────────────────────────

    private BigDecimal allocatedBaseline() {
        return sessionRepository.sumInitialCapitalExcluding(
                        CapitalReservationService.LIVE_ALLOCATING_STATUSES, -1L)
                .add(dynamicSessionRepository.sumRealInitialCapitalExcluding(
                        CapitalReservationService.DYNAMIC_ALLOCATING_STATUSES, -1L));
    }

    @Test
    @DisplayName("세션 생성 — 계좌가 넉넉해도 등록 총 자본 상한을 넘는 배정은 거절한다 (LIVE·DYNAMIC 합산)")
    void createSessionRespectsRegisteredPool() {
        generousPool();
        portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));
        limits.setTotalCapKrw(allocatedBaseline().add(new BigDecimal("100000")));

        LiveTradingStartRequest live = new LiveTradingStartRequest();
        live.setStrategyType("COMPOSITE_BREAKOUT");
        live.setCoinPair("KRW-BTC");
        live.setTimeframe("H1");
        live.setInitialCapital(new BigDecimal("60000"));
        liveSessions.add(liveTradingService.createSession(live).getId());

        DynamicSessionRequest dyn = new DynamicSessionRequest();
        dyn.setStrategyType("COMPOSITE_MTF_BTC");
        dyn.setTimeframe("H1");
        dyn.setInitialCapital(new BigDecimal("60000"));
        assertThatThrownBy(() -> dynamicTradingService.createSession(dyn))
                .as("🔴 실자금 동적 세션은 종전에 자본 검사가 전혀 없었다 — LIVE 60,000 + DYNAMIC 60,000 > 100,000")
                .hasMessageContaining(CapitalReservationService.POOL_CAP);

        // 페이퍼는 실자금이 아니므로 세지 않는다.
        DynamicSessionRequest paper = new DynamicSessionRequest();
        paper.setStrategyType("COMPOSITE_MTF_BTC");
        paper.setTimeframe("H1");
        paper.setInitialCapital(new BigDecimal("60000"));
        paper.setTradingMode("PAPER");
        dynamicSessions.add(dynamicTradingService.createSession(paper).getId());
    }

    @Test
    @DisplayName("재시작 — 다른 세션의 열린 예약은 계좌에 남아 있으므로 다시 배정하지 않는다")
    void restartCountsOpenReservations() {
        generousPool();
        // 계좌: 기준선 + 10,000. 세션 A 가 10,000 을 들고 3,000 을 예약했다 → A 가용 7,000.
        portfolioManager.syncTotalCapital(openCommitment().add(new BigDecimal("10000")));
        Long a = liveSession("RUNNING", new BigDecimal("10000"), new BigDecimal("10000"));
        BigDecimal heldElsewhere = sessionRepository.sumAvailableKrwExcluding(
                CapitalReservationService.LIVE_ALLOCATING_STATUSES, a);
        // 다른 시험이 남긴 활성 세션의 가용 KRW 만큼 계좌를 늘려 기준선을 맞춘다.
        portfolioManager.syncTotalCapital(openCommitment().add(new BigDecimal("10000")).add(heldElsewhere)
                .add(dynamicSessionRepository.sumRealAvailableKrwExcluding(
                        CapitalReservationService.DYNAMIC_ALLOCATING_STATUSES, -1L)));
        assertThat(reserve(a, "KRW-DOGE", new BigDecimal("3000"))).isNull();

        // 정지된 세션 B(3,000 보유)를 재시작한다. A 가용 7,000 + B 3,000 = 10,000 = 계좌 10,000.
        // 🔴 종전 검사(가용 KRW 합만)는 이것을 통과시킨다 — 그러나 A 의 예약 3,000 은 아직 계좌에
        //    있고 곧 나간다. 체결 후 계좌는 7,000 인데 세션들은 10,000 을 쓸 수 있다고 믿는다.
        Long b = liveSession("STOPPED", new BigDecimal("3000"), new BigDecimal("3000"));
        assertThatThrownBy(() -> liveTradingService.startSession(b))
                .hasMessageContaining(CapitalReservationService.ACCOUNT_HEADROOM);
        assertThat(sessionRepository.findById(b).orElseThrow().getStatus()).isEqualTo("STOPPED");
    }
}
