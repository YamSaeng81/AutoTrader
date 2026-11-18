package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 총괄 v1.185 §3-4 P1 재현(시험만, 제품 변경 없음) — 실제 {@code executeBuy → 예약} 경로의 포지션당 상한 경계.
 *
 * <p>STAGE_1 등록값(총 100,000 · 포지션당 20,000 · 동시 3)으로 두고 가용액·투자 비율·EMA 배수를 바꿔 본다.
 * 요청액 = 둘째 자리 버림(가용 × 비율 × 배수). 상한을 넘으면 잘라 주지 않고 PER_POSITION_CAP 으로 거절된다는 것이
 * 0e1d2a02 의 현행 보호 계약이며, 이 시험은 그 경계가 실제로 어디인지를 고정할 뿐 계약을 바꾸지 않는다.</p>
 */
@DisplayName("P1 — DYNAMIC 매수 요청액과 포지션당 상한 경계(STAGE_1)")
class DynamicPerPositionCapBoundaryTest extends IntegrationTestBase {

    @SpyBean private RiskManagementService realLossGuardSpy;   // H2 에서 실자금 손실 관문은 승인(기존 시험과 같은 처리)

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PlatformTransactionManager txManager;

    @MockBean private UpbitOrderClient upbitOrderClient;   // 외부 주문 0
    /** 승인된 매수가 실시간 구독을 연다 — 기존 매수 승인 시험(DynamicBuyExternalSubmissionBoundaryTest 등)과 같이 대역으로 막는다. */
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient upbitWebSocketClient;

    private BigDecimal savedTotal, savedPer;
    private Integer savedSlots;
    private final List<Long> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(realLossGuardSpy).checkRealLossGuard();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setTotalCapKrw(new BigDecimal("100000"));
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setMaxConcurrentPositions(3);
    }

    @AfterEach
    void tearDown() {
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
        for (Long id : sessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
    }

    private Long session(BigDecimal capital, BigDecimal available, BigDecimal ratio) {
        Long id = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(capital).availableKrw(available).totalAssetKrw(available)
                .investRatio(ratio).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
        sessions.add(id);
        return id;
    }

    private static List<Candle> candles() {
        List<Candle> out = new ArrayList<>();
        Instant t0 = Instant.now().minus(300, ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = new BigDecimal(1000 + i);
            out.add(Candle.builder().time(t0.plus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.TEN)).low(p.subtract(BigDecimal.TEN)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    private String buy(Long id, BigDecimal multiplier) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(st -> dynamicTradingService.executeBuy(dynamicRepo.findById(id).orElseThrow(), "KRW-ETH",
                candles(), StrategySignal.buy(new BigDecimal("80"), "P1 경계 시험"), multiplier));
    }

    @ParameterizedTest(name = "{0}: 가용 {2} × 비율 {3} × 배수 {4} → 기대 {5}")
    @CsvSource({
            // 이름,                         자본,   가용,      비율,  배수, 기대(요청액 또는 REJECT)
            "첫 진입 정확히 상한,              25000, 25000,     0.80, 1,   20000.00",
            "0.01원 이익 — 버림으로 상한 유지, 25000, 25000.01,  0.80, 1,   20000.00",
            "1원 이익 — 상한 초과,            25000, 25001,     0.80, 1,   REJECT",
            "1원 이익 + EMA 감액 0.5,          25000, 25001,     0.80, 0.5, 10000.40",
            "2배 넘는 이익 + EMA 감액 0.5,     25000, 50001,     0.80, 0.5, REJECT",
            "여유 자본 20000·비율 0.8,         20000, 20000,     0.80, 1,   16000.00",
            "여유안에서 이익 5000 뒤,          20000, 25000,     0.80, 1,   20000.00",
            "여유안에서 이익 5001 뒤,          20000, 25001,     0.80, 1,   REJECT",
    })
    void boundary(String name, String capital, String available, String ratio, String mult, String expected) {
        BigDecimal avail = new BigDecimal(available);
        Long id = session(new BigDecimal(capital), avail, new BigDecimal(ratio));

        String blocked = buy(id, new BigDecimal(mult));
        DynamicSessionEntity after = dynamicRepo.findById(id).orElseThrow();
        List<OrderEntity> orders = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id);
        System.out.printf("[P1] %s → 결과=%s · 주문 %d건 · 요청액 %s · 가용 후 %s%n", name,
                blocked == null ? "승인" : blocked, orders.size(),
                orders.isEmpty() ? "-" : orders.get(0).getQuantity().toPlainString(), after.getAvailableKrw().toPlainString());

        if ("REJECT".equals(expected)) {
            assertThat(blocked).contains(CapitalReservationService.PER_POSITION_CAP);
            assertThat(after.getAvailableKrw()).as("차감 없음").isEqualByComparingTo(avail);
            assertThat(orders).as("주문 의도 없음").isEmpty();
        } else {
            assertThat(blocked).as("승인").isNull();
            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getQuantity()).as("요청액").isEqualByComparingTo(new BigDecimal(expected));
        }
    }
}
