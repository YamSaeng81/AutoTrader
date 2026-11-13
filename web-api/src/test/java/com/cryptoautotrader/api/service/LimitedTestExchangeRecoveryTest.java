package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.exception.SessionStateException;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 *
 * <p><b>시험 범위</b>(2026-11-01 총괄 지시 4): 매수 체결·정산은 <b>준비 자료</b>다(시험이 직접 넣은 FILLED 매수·OPEN 포지션). 이 시험이 확인하는 것은
 * DOWN 비상 청산 <b>매도 접수 뒤 대기 중</b>(거래소가 {@code wait} 로만 답함) 정리 작업·복구·반복 UP 이 같은 매도를 다시 내지 않는 것(중복 제출 방지)까지다.
 * 매도 체결·최종 정산 완료는 이 시험이 검증하지 않는다.</p>
 *
 * <p><b>수동 시작</b>(N01 보완, 같은 날 총괄 지시 2): 제한시험은 {@code CREATED}·{@code started_at} 없음인 최초 시작만 받는다 — 정지·비상정지 뒤
 * 수동 {@code startSession} 은 거절되고 상태·시각·주문이 그대로다. 일반 전략의 정지 세션 수동 시작은 그대로다.</p>
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
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PortfolioManager portfolioManager;

    @MockBean private PortfolioSyncService portfolioSyncService;
    @MockBean private UpbitOrderClient upbitOrderClient;

    private final List<Long> created = new ArrayList<>();
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private Object savedSnapshot;
    private boolean capitalOpened;

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
        if (capitalOpened) {
            limits.setTotalCapKrw(savedTotal);
            limits.setPerPositionCapKrw(savedPer);
            limits.setMaxConcurrentPositions(savedSlots);
            ReflectionTestUtils.setField(portfolioManager, "snapshot", savedSnapshot);
            capitalOpened = false;
        }
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
        return sma20Session("RUNNING", startedAt);
    }

    private LiveTradingSessionEntity sma20Session(String status, Instant startedAt) {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("DAILY_SMA20")
                .coinPair("KRW-BTC").timeframe("H1")
                .initialCapital(new BigDecimal("20200")).availableKrw(new BigDecimal("300.00"))
                .totalAssetKrw(new BigDecimal("20200")).mddPeakCapital(new BigDecimal("20200"))
                .investRatio(new BigDecimal("0.9950")).maxInvestment(new BigDecimal("20000"))
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0)
                .strategyParams(Map.of())
                .status(status).startedAt(startedAt).build());
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
        // 🔴 (총괄 v1.164 §3-2, 2026-11-13, 시험만) 옛 조건(대기열 비었음·active 0·taskCount == completed)은 놀고 있던 작업자가
        //    작업을 꺼낸 뒤 실행 표시 전에도 참이다 — DrainGapGate(꺼낸 뒤 머묾)로 이 클래스의 단언이 작업 전에 읽히는 것을 고정 재현했다.
        //    이 호출 전에 넣은 작업이 모두 끝났음을 실행기 장벽(확정 신호)으로 기다린다. 시간 초과는 실패다. 기대값·단언은 그대로다.
        com.cryptoautotrader.api.support.ExecutorBarrier.awaitPriorTasks(ex.getThreadPoolExecutor(), 20_000);
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

    // ── N01 보완: 수동 시작은 최초 시작만 (2026-11-01 총괄 지시 2) ─────────────────────

    /** 시작 검사(계좌·풀)가 이 시험의 관심사가 아니게 넉넉히 연다 — 끝나면 되돌린다. */
    private void allowCapital() {
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        savedSnapshot = ReflectionTestUtils.getField(portfolioManager, "snapshot");
        capitalOpened = true;
        limits.setTotalCapKrw(new BigDecimal("1000000000000"));
        limits.setPerPositionCapKrw(new BigDecimal("1000000000000"));
        limits.setMaxConcurrentPositions(100_000);
        portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));
    }

    private int orderCount(Long sid) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sid).size();
    }

    /** 거절 — 예외, 그리고 상태·시작·정지 시각·주문 수·거래소 호출이 그대로다. */
    private void assertRejectedUnchanged(Long sid) {
        LiveTradingSessionEntity before = reload(sid);
        int orders = orderCount(sid);
        int calls = exchangeCalls("bid") + exchangeCalls("ask");
        assertThatThrownBy(() -> liveTradingService.startSession(sid))
                .as("🔴 수동 시작이 %s 제한시험을 다시 연다 (started_at %s)", before.getStatus(), before.getStartedAt())
                .isInstanceOf(SessionStateException.class)
                .hasMessageContaining("최초 시작");
        drainAll();
        LiveTradingSessionEntity after = reload(sid);
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getStartedAt()).isEqualTo(before.getStartedAt());
        assertThat(after.getStoppedAt()).isEqualTo(before.getStoppedAt());
        assertThat(orderCount(sid)).as("주문 불변").isEqualTo(orders);
        assertThat(exchangeCalls("bid") + exchangeCalls("ask")).as("거래소 호출 불변").isEqualTo(calls);
    }

    @Test
    @DisplayName("수동 시작 — CREATED·started_at 없음은 최초 시작으로 허용, 정지 뒤 다시 부르면 거절·불변, 일반 전략의 정지 세션은 그대로 시작된다")
    void manualStartAllowsOnlyFirstStart() {
        allowCapital();
        Long sid = sma20Session("CREATED", null).getId();

        LiveTradingSessionEntity started = liveTradingService.startSession(sid);
        assertThat(started.getStatus()).as("최초 시작은 허용").isEqualTo("RUNNING");
        Instant firstStart = reload(sid).getStartedAt();
        assertThat(firstStart).isNotNull();

        liveTradingService.stopSession(sid);
        drainAll();
        assertThat(reload(sid).getStatus()).isEqualTo("STOPPED");
        assertThat(reload(sid).getStartedAt()).isEqualTo(firstStart);
        assertRejectedUnchanged(sid);

        Long general = generalSession();
        liveTradingService.stopSession(general);
        drainAll();
        assertThat(reload(general).getStatus()).isEqualTo("STOPPED");
        assertThat(liveTradingService.startSession(general).getStatus())
                .as("일반 전략의 정지 세션 수동 시작은 그대로").isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("수동 시작 — 보유 중 DOWN 비상정지(매도 접수·대기) 뒤 수동 시작 거절·불변, CREATED 이지만 started_at 이 있으면 거절")
    void manualStartRejectedAfterEmergencyStop() {
        allowCapital();
        Instant firstStart = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long sid = sma20Session(firstStart).getId();
        settledBuy(sid);

        down();
        assertThat(reload(sid).getStatus()).isEqualTo("EMERGENCY_STOPPED");
        assertRejectedUnchanged(sid);
        up();
        assertRejectedUnchanged(sid);
        assertThat(reload(sid).getStartedAt()).isEqualTo(firstStart);
        assertThat(buyOrders(sid)).isEqualTo(1);
        assertThat(exchangeCalls("ask")).as("청산 매도는 DOWN 의 1회뿐").isEqualTo(1);

        Long odd = sma20Session("CREATED", firstStart).getId();
        assertRejectedUnchanged(odd);
    }
}
