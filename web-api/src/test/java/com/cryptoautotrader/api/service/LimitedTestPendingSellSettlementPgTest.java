package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>N05 C3 — 거래소 DOWN 비상 청산 매도 접수·대기 → UP → 체결·정산·정리</b> (2026-11-01 총괄 지시, NOVEMBER_LIVE_UPGRADE N05).
 *
 * <p>{@link LimitedTestExchangeRecoveryTest} 는 DOWN 비상 청산 매도가 <b>대기 중</b>일 때 중복 제출이 없는 것까지만 본다. 이 시험은 그 대기
 * 매도가 UP 뒤 체결될 때 기존 경로(폴러 {@code checkOrderStatus} → 정리 작업 {@code cleanupStoppedSessionPositions} → 청산 확정
 * {@code reconcileClosingPositions})가 포지션 종료·잔액 1회 입금·정리 완료로 잇는지, 그리고 그 뒤 F5-3·장부 L4·L5 가 읽을 DB 행이 남는지 본다.</p>
 *
 * <p>SID 203 과 같은 설정(KRW-BTC·H1·20,200원·0.995·상한 20,000원·손절 5%·{@code maxHoldHours=0}). 매수는 손으로 쓴 체결 행이 아니라
 * <b>요청액 20,000.00 예약(잔액 200.00) → 실제 매수 정산 경로</b>로 만든다 — 요청액·체결대금·수수료·잔액이 장부 공식
 * ({@code sid_ledger.sql} L4: 매수 −요청액 + round(요청액 − 대금 − 수수료, 2), 매도 + round(대금 − 수수료, 2))과 맞는다.</p>
 *
 * <p>DOWN 길이 두 가지: {@code short} — 체결 전에 청산 대기 시한({@code TradingConstants.CLOSING_TIMEOUT_MINUTES} 8분)이 지나지 않음
 * (포지션 CLOSING 유지). {@code long} — DOWN 이 8분을 넘겨 청산 확정이 CLOSING 을 OPEN 으로 되돌린 뒤 체결(정리 작업이 "체결된 매도
 * 반영 대기"로 다시 CLOSING 으로 돌린다). 시간 경과는 {@code closing_at} 을 9분 앞으로 옮겨 만든다.
 * (첫 실행 2026-11-01: 시한을 5분으로 잘못 알고 6분만 옮겨 {@code long} 이 OPEN 되돌림을 타지 않았다 — 시험 쪽 오류, 기록 보존.)</p>
 *
 * <p>🔴 H2 로 돌리지 않는다 — 잔액 열 반올림은 PostgreSQL NUMERIC(20,2) 에서 본다. 로컬 전용, 주소가 localhost 가 아니면 실행하지 않는다.
 * 시험 DB 는 매번 새로 만들고, <b>끝난 행을 지우지 않는다</b> — 시험 뒤 같은 DB 에 F5-3 조회와 {@code sid_ledger.sql} 을 그대로 돌려
 * 최종 보고가 비상정지 출처·정산 결과를 보존하는지 본다(시험이 출력하는 {@code [C3] sid=} 줄의 세션 번호로). 실행:</p>
 * <pre>
 * docker exec cat-rt-pg psql -U trader -d postgres -c "DROP DATABASE IF EXISTS n05_c3" -c "CREATE DATABASE n05_c3"
 * SR_PG_URL=jdbc:postgresql://localhost:15432/n05_c3 ./gradlew :web-api:cleanTest :web-api:test --tests '*LimitedTestPendingSellSettlementPgTest'
 * </pre>
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${SR_PG_URL}",
        "spring.datasource.username=${SR_PG_USER:trader}",
        "spring.datasource.password=${SR_PG_PASSWORD:rtpass}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "SR_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("N05 C3 — DOWN 비상 청산 매도 대기 → UP → 체결·정산·정리 (로컬 PostgreSQL)")
class LimitedTestPendingSellSettlementPgTest extends IntegrationTestBase {

    private static final BigDecimal INIT = new BigDecimal("20200");
    private static final BigDecimal REQUEST = new BigDecimal("20000.00");
    private static final BigDecimal VOLUME = new BigDecimal("0.00019900");
    // 매수 체결 — 환급 20,000 − 19,990 − 9.995 = 0.005 → 0.01 (두 자리 경계)
    private static final BigDecimal BUY_FUNDS = new BigDecimal("19990");
    private static final BigDecimal BUY_FEE = new BigDecimal("9.995");
    // 매도 체결 — 순입금 20,100.5 − 10.05025 = 20,090.44975 → 20,090.45
    private static final BigDecimal SELL_FUNDS = new BigDecimal("20100.5");
    private static final BigDecimal SELL_FEE = new BigDecimal("10.05025");

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private OrderSettlementService settlement;
    @Autowired private LiveTradingSessionRepository sessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ExchangeHealthMonitor healthMonitor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;

    @MockBean private PortfolioSyncService portfolioSyncService;
    @MockBean private UpbitOrderClient upbitOrderClient;
    /** {@code checkOrderStatus} 는 이것이 null 이면 아무 일도 하지 않고 돌아간다. */
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;

    /** 거래소가 지금 돌려줄 주문 조회 응답 — 없으면 대기({@code wait}, 체결 0). */
    private final Map<String, OrderResponse> exchangeView = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        healthMonitor.updateStatus("UP");
        // 거래소는 매도를 접수만 한다(대기). 체결 여부는 이후 조회 응답(exchangeView)이 정한다.
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        response("c3-" + UUID.randomUUID(), inv.getArgument(1), "wait", null, null, null), "{}"));
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString()))
                .thenAnswer(inv -> {
                    String uuid = inv.getArgument(0);
                    OrderResponse r = exchangeView.get(uuid);
                    return r != null ? r : response(uuid, "ask", "wait", null, null, null);
                });
    }

    private static OrderResponse response(String uuid, String side, String state,
                                          BigDecimal volume, BigDecimal funds, BigDecimal fee) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide(side);
        r.setOrdType("bid".equals(side) ? "price" : "market");
        r.setState(state);
        r.setMarket("KRW-BTC");
        if ("bid".equals(side)) r.setPrice(REQUEST);
        else r.setVolume(VOLUME);
        r.setExecutedVolume(volume == null ? BigDecimal.ZERO : volume);
        r.setExecutedFunds(funds);
        r.setPaidFee(fee);
        return r;
    }

    // ── 준비: 실제 매수 정산 경로로 만든 보유 ─────────────────────────────────

    /** SID 203 과 같은 설정. 요청액 20,000.00 을 예약한 뒤(잔액 200.00) 매수가 거래소에 접수된 상태. */
    private long[] sessionWithSubmittedBuy(Instant firstStart) {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("DAILY_SMA20")
                .coinPair("KRW-BTC").timeframe("H1")
                .initialCapital(INIT).availableKrw(INIT.subtract(REQUEST))
                .totalAssetKrw(INIT).mddPeakCapital(INIT)
                .investRatio(new BigDecimal("0.9950")).maxInvestment(new BigDecimal("20000"))
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0)
                .strategyParams(Map.of())
                .status("RUNNING").startedAt(firstStart).build());
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("100452261")).avgPrice(new BigDecimal("100452261"))
                .size(BigDecimal.ZERO).investedKrw(REQUEST).entryAllocationKrw(REQUEST)
                .stopLossPrice(new BigDecimal("95429648"))
                .status("OPEN").sessionKind("LIVE").sessionId(s.getId())
                .openedAt(firstStart.plus(1, ChronoUnit.HOURS)).build());
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-BTC").side("BUY").orderType("MARKET")
                .quantity(REQUEST).reservedKrw(REQUEST).state("SUBMITTED")
                .signalPrice(new BigDecimal("100452261"))
                .sessionKind("LIVE").sessionId(s.getId()).positionId(p.getId())
                .exchangeOrderId("c3-buy-" + UUID.randomUUID()).clientOrderId("c3-buy-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(firstStart.plus(1, ChronoUnit.HOURS)).submittedAt(firstStart.plus(1, ChronoUnit.HOURS)).build());
        return new long[]{s.getId(), p.getId(), o.getId()};
    }

    // ── 거래소 상태 전환 ─────────────────────────────────────────────────────

    private void down() {
        healthMonitor.updateStatus("DOWN");
        drainAll();
    }

    private void up() {
        healthMonitor.updateStatus("UP");
        drainAll();
    }

    private void repeatedUpEvent() {
        liveTradingService.onExchangeRecovered(new ExchangeRecoveredEvent(this, "UP"));
        drainAll();
    }

    /** 운영의 주기 작업 한 바퀴 — 폴러(활성·정산 미확정 주문 조회) → 정리 작업 → 청산 확정. */
    private void schedulerRound(Long sid) {
        for (OrderEntity o : orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sid)) {
            orderExecutionEngine.checkOrderStatus(o.getId());
        }
        liveTradingService.cleanupStoppedSessionPositions();
        liveTradingService.reconcileClosingPositions();
        drainAll();
    }

    private void drainAll() {
        awaitDrained(marketDataExecutor);
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

    // ── 조회 ────────────────────────────────────────────────────────────────

    private LiveTradingSessionEntity session(Long id) {
        return sessionRepository.findById(id).orElseThrow();
    }

    private PositionEntity position(Long id) {
        return positionRepository.findById(id).orElseThrow();
    }

    private List<OrderEntity> sells(Long sid) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sid).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).toList();
    }

    private int exchangeCalls(String side) {
        return (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && side.equals(i.getArgument(1)))
                .count();
    }

    private long activeOrders(Long sid) {
        return jdbc.queryForObject("SELECT count(*) FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = ? "
                + "AND state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')", Long.class, sid);
    }

    private long cleanupPending(Long posId) {
        return jdbc.queryForObject("SELECT count(*) FROM position_cleanup WHERE position_id = ? AND done_at IS NULL", Long.class, posId);
    }

    private long cleanupRows(Long posId) {
        return jdbc.queryForObject("SELECT count(*) FROM position_cleanup WHERE position_id = ?", Long.class, posId);
    }

    private long unresolvedDiscrepancies(Long sid) {
        return jdbc.queryForObject("SELECT count(*) FROM order_settlement_discrepancy d JOIN \"order\" o ON o.id = d.order_id "
                + "WHERE o.session_kind = 'LIVE' AND o.session_id = ? AND d.resolved_at IS NULL", Long.class, sid);
    }

    /** 장부 L4 잔액 기대 — 주문의 반영값으로 다시 계산한다(sid_ledger.sql 과 같은 공식). */
    private BigDecimal ledgerBalanceExpected(Long sid) {
        BigDecimal b = INIT;
        for (OrderEntity o : orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sid)) {
            if (o.getFilledQuantity() == null || o.getFilledQuantity().signum() <= 0) continue;
            BigDecimal net = o.getAppliedExecutedFunds().subtract(o.getAppliedPaidFee());
            if ("BUY".equalsIgnoreCase(o.getSide())) {
                b = b.subtract(o.getQuantity()).add(o.getQuantity().subtract(o.getAppliedExecutedFunds())
                        .subtract(o.getAppliedPaidFee()).setScale(2, RoundingMode.HALF_UP));
            } else {
                b = b.add(net.setScale(2, RoundingMode.HALF_UP));
            }
        }
        return b;
    }

    private static void trace(String step, Object... cols) {
        StringBuilder b = new StringBuilder("[C3] ").append(step);
        for (Object c : cols) b.append(" | ").append(c instanceof BigDecimal d ? d.toPlainString() : c);
        System.out.println(b);
    }

    // ── 사례 ────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "DOWN {0}")
    @ValueSource(strings = {"short", "long"})
    @DisplayName("보유 중 DOWN → 청산 매도 접수·대기 → UP → 체결·정산·정리 — 비상정지·최초 시작/종료 시각 유지, 매도 1건·입금 1회, "
            + "CLOSED·활성 주문·정리 대기·미해결 불일치 0, 반복 UP·정산에도 불변")
    void pendingEmergencySellSettlesAfterRecovery(String outage) {
        Instant firstStart = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant end56 = firstStart.plus(Duration.ofDays(56));
        long[] ids = sessionWithSubmittedBuy(firstStart);
        Long sid = ids[0];
        Long posId = ids[1];
        String buyUuid = orderRepository.findById(ids[2]).orElseThrow().getExchangeOrderId();
        trace("sid=" + sid, outage, "position " + posId, "buy order " + ids[2]);

        // ① 매수 체결 — 폴러 경로 그대로. 잔액 = 200.00 + round(0.005, 2) = 200.01
        exchangeView.put(buyUuid, response(buyUuid, "bid", "done", VOLUME, BUY_FUNDS, BUY_FEE));
        orderExecutionEngine.checkOrderStatus(ids[2]);
        assertThat(orderRepository.findById(ids[2]).orElseThrow().getState()).isEqualTo("FILLED");
        assertThat(position(posId).getSize()).isEqualByComparingTo(VOLUME);
        assertThat(session(sid).getAvailableKrw()).as("매수 뒤 잔액 = 장부 공식")
                .isEqualByComparingTo(ledgerBalanceExpected(sid)).isEqualByComparingTo("200.01");
        trace("매수 체결", session(sid).getAvailableKrw(), position(posId).getInvestedKrw(), position(posId).getAvgPrice());

        // ② DOWN — 비상정지, 청산 매도 1회 접수·대기
        down();
        LiveTradingSessionEntity stopped = session(sid);
        assertThat(stopped.getStatus()).isEqualTo("EMERGENCY_STOPPED");
        Instant stoppedAt = stopped.getStoppedAt();
        assertThat(stoppedAt).isNotNull();
        assertThat(exchangeCalls("ask")).as("DOWN 비상 청산 매도 1회").isEqualTo(1);
        assertThat(sells(sid)).hasSize(1);
        OrderEntity sell = sells(sid).get(0);
        assertThat(sell.getState()).as("거래소가 접수만 했다").isEqualTo("SUBMITTED");
        assertThat(position(posId).getStatus()).isEqualTo("CLOSING");
        assertThat(session(sid).getAvailableKrw()).as("체결 전 — 입금 없음").isEqualByComparingTo("200.01");
        trace("DOWN 접수", stopped.getStatus(), stoppedAt, "sell " + sell.getId(), sell.getExchangeOrderId());

        if ("long".equals(outage)) {
            // DOWN 이 청산 대기 시한(8분)을 넘긴다 — 청산 확정이 대기 중인 CLOSING 을 OPEN 으로 되돌린다(체결은 아직 없다)
            // (첫 실행: '6 minutes' — 시한 미달이라 되돌림이 일어나지 않았다)
            jdbc.update("UPDATE position SET closing_at = closing_at - interval '9 minutes' WHERE id = ?", posId);
        }
        schedulerRound(sid);
        trace("DOWN 중 주기 작업", position(posId).getStatus(), sells(sid).get(0).getState());
        assertThat(position(posId).getStatus()).as("DOWN %s — 대기 중 포지션 상태", outage)
                .isEqualTo("long".equals(outage) ? "OPEN" : "CLOSING");
        assertThat(exchangeCalls("ask")).as("대기 중 — 정리 작업이 다시 내지 않는다").isEqualTo(1);

        // ③ UP — 제한시험은 재개하지 않는다. 매도는 여전히 대기
        up();
        schedulerRound(sid);
        assertThat(session(sid).getStatus()).isEqualTo("EMERGENCY_STOPPED");
        assertThat(sells(sid).get(0).getState()).isEqualTo("SUBMITTED");
        assertThat(exchangeCalls("ask")).isEqualTo(1);
        assertThat(exchangeCalls("bid")).as("UP 뒤 매수 0").isZero();
        trace("UP 대기", session(sid).getStatus(), position(posId).getStatus(), sells(sid).get(0).getState());

        // ④ 거래소가 체결을 보고 — 폴러 → 정리 작업 → 청산 확정
        String sellUuid = sell.getExchangeOrderId();
        exchangeView.put(sellUuid, response(sellUuid, "ask", "done", VOLUME, SELL_FUNDS, SELL_FEE));
        schedulerRound(sid);
        trace("체결 1회차", position(posId).getStatus(), sells(sid).get(0).getState(), session(sid).getAvailableKrw(),
                cleanupPending(posId));
        schedulerRound(sid);   // 정리 작업이 CLOSED 를 보고 완료 처리
        trace("체결 2회차", position(posId).getStatus(), session(sid).getAvailableKrw(), cleanupPending(posId));

        BigDecimal settled = session(sid).getAvailableKrw();
        assertThat(position(posId).getStatus()).as("포지션 종료").isEqualTo("CLOSED");
        assertThat(settled).as("잔액 = 200.01 + round(20,090.44975, 2) — 입금 1회, 장부 공식과 같다")
                .isEqualByComparingTo(ledgerBalanceExpected(sid)).isEqualByComparingTo("20290.46");

        // ⑤ 반복 UP·반복 정산·반복 주기 작업 — 결과 불변
        repeatedUpEvent();
        repeatedUpEvent();
        assertThat(settlement.settle(sells(sid).get(0).getId(), response(sellUuid, "ask", "done", VOLUME, SELL_FUNDS, SELL_FEE)))
                .as("같은 체결 보고 반복 — 반영 없음").isEqualTo(OrderSettlementService.Outcome.NO_CHANGE);
        schedulerRound(sid);
        schedulerRound(sid);
        up();

        LiveTradingSessionEntity last = session(sid);
        trace("최종", last.getStatus(), last.getStartedAt(), last.getStoppedAt(), last.getAvailableKrw(),
                "realized " + position(posId).getRealizedPnl(), "ledger " + ledgerBalanceExpected(sid));
        assertThat(last.getStatus()).as("비상정지 유지 — 재개 없음").isEqualTo("EMERGENCY_STOPPED");
        assertThat(last.getStartedAt()).as("최초 시작 시각 그대로").isEqualTo(firstStart);
        assertThat(last.getStartedAt().plus(Duration.ofDays(56))).as("56일 종료 시각 그대로").isEqualTo(end56);
        assertThat(last.getStoppedAt()).as("정지 시각 그대로").isEqualTo(stoppedAt);
        assertThat(last.getAvailableKrw()).as("반복 뒤에도 입금 1회").isEqualByComparingTo(settled);
        assertThat(sells(sid)).as("매도 누적 1건").hasSize(1);
        assertThat(exchangeCalls("ask")).as("거래소 매도 호출 1회").isEqualTo(1);
        assertThat(exchangeCalls("bid")).as("거래소 매수 호출 0").isZero();
        assertThat(position(posId).getStatus()).isEqualTo("CLOSED");
        // 첫 실행 기대 ~~position.size = 0~~ 은 시험 쪽 오류 — CLOSED 행은 수량을 기록으로 남긴다. 장부 L4 open_size·F5-3 open_pos 는
        // OPEN·CLOSING 만 센다. 그 기준으로 본다.
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(size), 0) FROM position WHERE session_kind = 'LIVE' AND session_id = ? "
                + "AND status IN ('OPEN', 'CLOSING')", BigDecimal.class, sid)).as("열린 수량 0(장부 open_size)").isEqualByComparingTo("0");
        assertThat(activeOrders(sid)).as("활성 주문 0").isZero();
        assertThat(cleanupRows(posId)).as("정리 대기가 등록됐었다").isEqualTo(1);
        assertThat(cleanupPending(posId)).as("정리 대기 0").isZero();
        assertThat(unresolvedDiscrepancies(sid)).as("미해결 정산 불일치 0").isZero();
        OrderEntity filled = sells(sid).get(0);
        assertThat(filled.getState()).isEqualTo("FILLED");
        assertThat(filled.getAppliedExecutedFunds()).isEqualByComparingTo(SELL_FUNDS);
        assertThat(filled.getAppliedPaidFee()).isEqualByComparingTo(SELL_FEE);
        assertThat(filled.getReportedExecutedFunds()).isEqualByComparingTo(SELL_FUNDS);
        assertThat(filled.getReportedPaidFee()).isEqualByComparingTo(SELL_FEE);
        assertThat(filled.getFillAccountingPending()).as("회계 반영 대기 없음").isNotEqualTo(Boolean.TRUE);
        // 끝난 행은 지우지 않는다 — 이 DB 에 F5-3·sid_ledger.sql 을 그대로 돌린다(클래스 설명).
    }
}
