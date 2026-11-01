package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>N01 — 거래소 DOWN → UP 이 제한시험(SMA20)을 다시 여는지</b> (2026-11-01 총괄 지시, NOVEMBER_LIVE_UPGRADE N01).
 *
 * <p>제한시험은 중단 뒤 재개하지 않고, 종료는 <b>실제 최초 시작({@code started_at}) + 56일</b>이다(LIMITED_LIVE_TEST_PLAN §5-8 F4·F5·§6-2).
 * 운영 {@code cad3939} 의 복구 경로는 DOWN 순간 RUNNING 이던 세션을 모두 다시 켜고({@code restartAfterExchangeRecovery} →
 * {@code startLocked}) {@code started_at} 을 지금으로 덮는다 — 그러면 F5 의 {@code now() >= started_at + 56일} 기산점이 밀린다.</p>
 *
 * <p>SID 203 과 같은 설정(KRW-BTC·H1·20,200원·투자 비율 0.995·매수 상한 20,000원·손절 5%·{@code maxHoldHours=0})의 시험 세션으로
 * 매수 체결·정산 → DOWN → UP → 반복 UP → 다시 DOWN·UP 을 잇는다. 운영 세션·API 는 쓰지 않는다. 일반 세션의 승인된 자동 복구는
 * {@link ExchangeRecoveryRestartTest} 가 그대로 본다 — 여기서는 함께 정지된 일반 세션이 여전히 재시작되는지만 대조한다.</p>
 */
@DisplayName("N01 거래소 DOWN → UP — 제한시험(SMA20)은 재개하지 않고 최초 started_at 을 보존한다")
class LimitedTestExchangeRecoveryTest extends IntegrationTestBase {

    private static final BigDecimal SIZE = new BigDecimal("0.00019900");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private LiveTradingSessionRepository sessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private ExchangeHealthMonitor healthMonitor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;

    @MockBean private PortfolioSyncService portfolioSyncService;
    @MockBean private UpbitOrderClient upbitOrderClient;

    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionRepository.findByStatus("RUNNING").forEach(s -> {
            s.setStatus("STOPPED");
            sessionRepository.save(s);
        });
        // 거래소는 매도를 접수만 하고(대기) 조회에도 대기로 답한다 — 접수만으로 청산 완료라고 쓰지 않는지 본다.
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-n01-" + SEQ.incrementAndGet() + "-" + System.nanoTime(),
                                inv.getArgument(0), inv.getArgument(1)), "{}"));
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString()))
                .thenAnswer(inv -> report(inv.getArgument(0), null, "ask"));
    }

    @AfterEach
    void cleanUp() {
        healthMonitor.updateStatus("UP");
        awaitDrained(marketDataExecutor);
        awaitDrained(orderExecutor);
        created.forEach(id -> {
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(p -> cleanupRepository.deleteById(p.getId()));
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            sessionRepository.findById(id).ifPresent(sessionRepository::delete);
        });
        created.clear();
    }

    private static OrderResponse report(String uuid, String market, String side) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide(side);
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket(market);
        r.setVolume(SIZE);
        r.setExecutedVolume(BigDecimal.ZERO);
        return r;
    }

    /** SID 203 과 같은 설정 — 시작 시각은 사흘 전(초 단위)으로 고정해 덮였는지 바로 보이게 한다. */
    private LiveTradingSessionEntity sma20Session(Instant startedAt) {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("DAILY_SMA20")
                .coinPair("KRW-BTC").timeframe("H1")
                .initialCapital(new BigDecimal("20200")).availableKrw(new BigDecimal("300.00"))
                .totalAssetKrw(new BigDecimal("20200")).mddPeakCapital(new BigDecimal("20200"))
                .investRatio(new BigDecimal("0.9950")).maxInvestment(new BigDecimal("20000"))
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0)
                .strategyParams(Map.of())
                .status("RUNNING").startedAt(startedAt).build());
        created.add(s.getId());
        return s;
    }

    private Long generalSession() {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-DOGE").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("10000"))
                .totalAssetKrw(new BigDecimal("10000")).mddPeakCapital(new BigDecimal("10000"))
                .status("RUNNING").maxHoldHours(24).startedAt(Instant.now().minus(1, ChronoUnit.DAYS)).build());
        created.add(s.getId());
        return s.getId();
    }

    /** 매수 체결·정산이 끝난 보유 — 체결된 BUY 주문 1건과 OPEN 포지션(진입 1억 원, 손절가 9,500만 원). */
    private PositionEntity settledBuy(Long sid) {
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("100000000")).avgPrice(new BigDecimal("100000000"))
                .size(SIZE).investedKrw(new BigDecimal("19900")).entryAllocationKrw(new BigDecimal("19900"))
                .stopLossPrice(new BigDecimal("95000000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sid)
                .openedAt(Instant.now().minus(2, ChronoUnit.DAYS)).build());
        orderRepository.save(OrderEntity.builder()
                .positionId(pos.getId()).coinPair("KRW-BTC").side("BUY").orderType("MARKET")
                .price(new BigDecimal("100000000")).quantity(SIZE).filledQuantity(SIZE)
                .executedFunds(new BigDecimal("19900")).state("FILLED")
                .exchangeOrderId("uuid-n01-buy-" + System.nanoTime())
                .reportedExecutedFunds(new BigDecimal("19900")).reportedPaidFee(new BigDecimal("9.95"))
                .appliedExecutedFunds(new BigDecimal("19900")).appliedPaidFee(new BigDecimal("9.95"))
                .settlementFinal(true)
                .sessionKind("LIVE").sessionId(sid)
                .createdAt(Instant.now().minus(2, ChronoUnit.DAYS)).filledAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .build());
        return pos;
    }

    private LiveTradingSessionEntity reload(Long id) {
        return sessionRepository.findById(id).orElseThrow();
    }

    private int exchangeCalls(String side) {
        return (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && side.equals(i.getArgument(1)))
                .count();
    }

    private long buyOrders(Long sid) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sid).stream()
                .filter(o -> "BUY".equalsIgnoreCase(o.getSide())).count();
    }

    /** 운영과 같은 발행 지점 — 상태가 DOWN 으로 바뀌면 감시기가 DOWN 이벤트를 낸다(동기 처리). */
    private void down() {
        healthMonitor.updateStatus("DOWN");
        awaitDrained(orderExecutor);
    }

    /** DOWN → UP 전환 — 감시기가 복구 이벤트를 낸다(비동기 처리). */
    private void up() {
        healthMonitor.updateStatus("UP");
        drainAll();
    }

    /** 반복 UP — 이미 UP 인 상태에서 복구 이벤트가 한 번 더 오는 경우(중복 이벤트). */
    private void repeatedUpEvent() {
        liveTradingService.onExchangeRecovered(new ExchangeRecoveredEvent(this, "UP"));
        drainAll();
    }

    private void drainAll() {
        awaitDrained(marketDataExecutor);
        awaitDrained(orderExecutor);
    }

    /** 정지 뒤 정리 작업·CLOSING 대조를 한 바퀴씩 — 복구 사이에 같은 청산을 다시 내지 않는지 본다. */
    private void cleanupRound() {
        liveTradingService.cleanupStoppedSessionPositions();
        liveTradingService.reconcileClosingPositions();
        awaitDrained(orderExecutor);
    }

    private static void awaitDrained(ThreadPoolTaskExecutor ex) {
        var pool = ex.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("실행기가 20초 안에 비워지지 않았다");
    }

    @Test
    @DisplayName("보유 중 DOWN → UP → 반복 UP → DOWN·UP — 비상정지 유지, 최초 started_at·종료 시각 보존, 추가 매수 0, 청산 매도 1회")
    void limitedTestStaysStoppedThroughRecoveries() {
        Instant firstStart = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant end56 = firstStart.plus(Duration.ofDays(56));
        Long sid = sma20Session(firstStart).getId();
        Long general = generalSession();
        PositionEntity pos = settledBuy(sid);

        down();
        LiveTradingSessionEntity afterDown = reload(sid);
        assertThat(afterDown.getStatus()).isEqualTo("EMERGENCY_STOPPED");
        Instant stoppedAt = afterDown.getStoppedAt();
        assertThat(stoppedAt).isNotNull();
        assertThat(exchangeCalls("ask")).as("DOWN 비상 청산 매도 1회").isEqualTo(1);
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus())
                .as("거래소가 접수만 했다 — CLOSED 로 쓰지 않는다").isNotEqualTo("CLOSED");
        cleanupRound();

        up();
        LiveTradingSessionEntity afterUp = reload(sid);
        assertThat(afterUp.getStatus())
                .as("🔴 cad3939: 복구가 제한시험을 RUNNING 으로 다시 연다 — 중단 후 재개 금지 위반 (started_at %s → %s)",
                        firstStart, afterUp.getStartedAt())
                .isEqualTo("EMERGENCY_STOPPED");
        assertThat(afterUp.getStartedAt())
                .as("🔴 cad3939: startLocked 가 started_at 을 지금으로 덮어 56일 기산점이 밀린다")
                .isEqualTo(firstStart);
        assertThat(afterUp.getStartedAt().plus(Duration.ofDays(56))).isEqualTo(end56);
        assertThat(afterUp.getStoppedAt()).as("정지 시각도 그대로").isEqualTo(stoppedAt);
        assertThat(reload(general).getStatus()).as("일반 세션의 승인된 자동 복구는 그대로").isEqualTo("RUNNING");
        cleanupRound();

        repeatedUpEvent();   // 반복 UP — 재시작 목록은 비었다
        repeatedUpEvent();
        LiveTradingSessionEntity afterRepeat = reload(sid);
        assertThat(afterRepeat.getStatus()).isEqualTo("EMERGENCY_STOPPED");
        assertThat(afterRepeat.getStartedAt()).isEqualTo(firstStart);

        down();  // 다시 DOWN — 제한시험은 이미 정지라 대상이 아니다
        cleanupRound();
        up();
        LiveTradingSessionEntity last = reload(sid);
        assertThat(last.getStatus()).isEqualTo("EMERGENCY_STOPPED");
        assertThat(last.getStartedAt()).isEqualTo(firstStart);
        assertThat(last.getStoppedAt()).isEqualTo(stoppedAt);

        assertThat(buyOrders(sid)).as("추가 매수 주문 0 — 처음의 체결 1건뿐").isEqualTo(1);
        assertThat(exchangeCalls("bid")).as("거래소 매수 호출 0").isZero();
        assertThat(exchangeCalls("ask")).as("청산 매도는 처음 DOWN 의 1회뿐 — 정리 작업·복구가 다시 내지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("보유 없이 DOWN → UP — 제한시험은 비상정지 유지·started_at 보존, 주문 0")
    void limitedTestWithoutPositionStaysStopped() {
        Instant firstStart = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long sid = sma20Session(firstStart).getId();

        down();
        up();
        repeatedUpEvent();
        LiveTradingSessionEntity s = reload(sid);
        assertThat(s.getStatus()).as("started_at %s → %s", firstStart, s.getStartedAt()).isEqualTo("EMERGENCY_STOPPED");
        assertThat(s.getStartedAt()).isEqualTo(firstStart);
        assertThat(exchangeCalls("bid") + exchangeCalls("ask")).isZero();
    }
}
