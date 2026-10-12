package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.strategy.StrategySignal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DYNAMIC 매수가 <b>공유 풀 예약 경계</b>를 지나는가 (2026-10-12).
 *
 * <p>🔴 종전에는 세션 생성·재시작만 검사하고 <b>실제 DYNAMIC 매수는 예약 경계를 우회</b>했다 — 그러면
 * 전체 한도는 강제되지 않는다. LIVE 와 DYNAMIC 이 <b>같은 잠금·같은 판정</b>을 지나야 공유 풀이 하나다.</p>
 */
@DisplayName("DYNAMIC 매수 — 공유 풀 예약 통합")
class DynamicPoolReservationTest extends IntegrationTestBase {

    private static final BigDecimal CAPITAL = new BigDecimal("10000.00");

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager em;

    @MockBean private UpbitOrderClient upbitOrderClient;

    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final List<Long> liveSessions = new ArrayList<>();

    @BeforeEach
    void save() {
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
    }

    @AfterEach
    void restore() {
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
        for (Long id : dynamicSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id)
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
        for (Long id : liveSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id)
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
    }

    private Long dynamicSession() {
        Long id = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(CAPITAL).availableKrw(CAPITAL).totalAssetKrw(CAPITAL)
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
        dynamicSessions.add(id);
        return id;
    }

    private Long liveSession() {
        Long id = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-ADA").timeframe("H1")
                .initialCapital(CAPITAL).availableKrw(CAPITAL).totalAssetKrw(CAPITAL)
                .status("RUNNING").startedAt(Instant.now()).build()).getId();
        liveSessions.add(id);
        return id;
    }

    private static List<Candle> candles() {
        List<Candle> out = new ArrayList<>();
        Instant t0 = Instant.now().minus(300, ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = new BigDecimal(1000 + i);
            out.add(Candle.builder()
                    .time(t0.plus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.TEN)).low(p.subtract(BigDecimal.TEN)).close(p)
                    .volume(new BigDecimal("1000"))
                    .build());
        }
        return out;
    }

    /** 틱과 같은 경계로 DYNAMIC 매수를 돌린다. @return 차단 사유, 매수 성공이면 {@code null} */
    private String dynamicBuy(Long id, String coin, CountDownLatch go) {
        if (go != null) {
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(st -> {
            if (go != null) {
                em.createNativeQuery("SET LOCK_TIMEOUT 15000").executeUpdate();
            }
            DynamicSessionEntity s = dynamicRepo.findById(id).orElseThrow();
            String r = dynamicTradingService.executeBuy(s, coin, candles(),
                    StrategySignal.buy(new BigDecimal("80"), "공유 풀 통합 시험"), BigDecimal.ONE);
            sleepQuietly(300);   // 커밋 전 간극 — 잠금이 없으면 상대가 같은 합계를 읽는다
            return r;
        });
    }

    /** LIVE 매수 예약. @return 거절 코드, 승인이면 {@code null} */
    private String liveReserve(Long id, CountDownLatch go) {
        try {
            go.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tx.execute(st -> {
                em.createNativeQuery("SET LOCK_TIMEOUT 15000").executeUpdate();
                PositionEntity pos = positionRepository.save(PositionEntity.builder()
                        .coinPair("KRW-ADA").side("BUY")
                        .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                        .size(BigDecimal.ZERO).investedKrw(new BigDecimal("8000"))
                        .status("OPEN").sessionKind("LIVE").sessionId(id)
                        .openedAt(Instant.now()).build());
                OrderRequest req = new OrderRequest();
                req.setCoinPair("KRW-ADA");
                req.setSide("BUY");
                req.setOrderType("MARKET");
                req.setQuantity(new BigDecimal("8000"));
                req.setReason("공유 풀 통합 시험 — LIVE");
                req.setSignalPrice(new BigDecimal("300"));
                req.setSessionId(id);
                req.setSessionKind("LIVE");
                req.setPositionId(pos.getId());
                Long orderId = orderExecutionEngine.reserveAndPrepareIntent(req);
                sleepQuietly(300);
                return orderId;
            });
            return null;
        } catch (OrderExecutionEngine.ReservationRejected rejected) {
            return rejected.getCode();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("슬롯이 없으면 DYNAMIC 매수도 거절되고 슬롯·차감·의도가 남지 않는다")
    void dynamicBuyRespectsSlotLimit() {
        limits.setTotalCapKrw(new BigDecimal("1000000000000"));
        limits.setPerPositionCapKrw(new BigDecimal("1000000000000"));
        // 슬롯 하나를 LIVE 가 차지하게 하고 상한을 현재 점유 수와 같게 둔다 — 남은 슬롯 0.
        //    (상한 0 은 '미등록' 으로 읽히므로 0 으로 맞추지 않는다.)
        Long live = liveSession();
        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-ADA").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(BigDecimal.ZERO).investedKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind("LIVE").sessionId(live)
                .openedAt(Instant.now()).build());
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L));

        Long id = dynamicSession();
        String blocked = dynamicBuy(id, "KRW-BTC", null);

        System.out.println("[DYNAMIC 슬롯 한도] 차단 사유=" + blocked);
        assertThat(blocked).contains(CapitalReservationService.SLOT_LIMIT);
        DynamicSessionEntity after = dynamicRepo.findById(id).orElseThrow();
        assertThat(after.getAvailableKrw()).as("차감되지 않는다").isEqualByComparingTo(CAPITAL);
        assertThat(after.getScanState()).as("감시로 넘어가지 않는다").isEqualTo("SCANNING");
        assertThat(positionRepository.findBySessionKindAndSessionIdAndStatus("DYNAMIC", id, "OPEN"))
                .as("🔴 열린 size=0 슬롯이 남으면 슬롯을 점유하고 고아 정리가 없던 돈을 복원한다")
                .isEmpty();
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id))
                .as("의도도 만들지 않는다").isEmpty();
    }

    @Test
    @DisplayName("포지션당 상한 — DYNAMIC 매수에도 같은 등록 한도가 걸린다")
    void dynamicBuyRespectsPerPositionCap() {
        limits.setTotalCapKrw(new BigDecimal("1000000000000"));
        limits.setMaxConcurrentPositions(100_000);
        limits.setPerPositionCapKrw(new BigDecimal("7999"));   // 투자금 8,000 (10,000 × 0.8)

        Long id = dynamicSession();
        assertThat(dynamicBuy(id, "KRW-BTC", null)).contains(CapitalReservationService.PER_POSITION_CAP);
        assertThat(dynamicRepo.findById(id).orElseThrow().getAvailableKrw()).isEqualByComparingTo(CAPITAL);
    }

    @Test
    @DisplayName("🔴 LIVE 와 DYNAMIC 이 마지막 슬롯 하나를 동시에 다투면 하나만 승인된다")
    void liveAndDynamicShareOnePool() throws Exception {
        limits.setTotalCapKrw(new BigDecimal("1000000000000"));
        limits.setPerPositionCapKrw(new BigDecimal("1000000000000"));
        long baseline = positionRepository.countPoolSlots(-1L);
        limits.setMaxConcurrentPositions((int) baseline + 1);   // 남은 슬롯 1

        Long dyn = dynamicSession();
        Long live = liveSession();
        CountDownLatch go = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Thread t1 = new Thread(() -> {
            try {
                String r = dynamicBuy(dyn, "KRW-BTC", go);
                results.add("DYNAMIC:" + (r == null ? "승인" : r));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                String r = liveReserve(live, go);
                results.add("LIVE:" + (r == null ? "승인" : r));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        t1.start();
        t2.start();
        go.countDown();
        t1.join(40_000);
        t2.join(40_000);

        System.out.println("[LIVE·DYNAMIC 공유 슬롯] 결과=" + results + " 오류=" + errors);
        assertThat(errors).isEmpty();
        assertThat(results.stream().filter(r -> r.endsWith("승인")).count())
                .as("🔴 종류가 달라도 같은 풀이다 — 둘 다 승인되면 동시 보유 상한을 넘는다")
                .isEqualTo(1L);
        assertThat(results.stream().filter(r -> r.contains(CapitalReservationService.SLOT_LIMIT)).count())
                .isEqualTo(1L);
        assertThat(positionRepository.countPoolSlots(-1L)).isLessThanOrEqualTo(baseline + 1);
    }
}
