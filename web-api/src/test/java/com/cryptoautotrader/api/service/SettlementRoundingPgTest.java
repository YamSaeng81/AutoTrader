package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>잔액 반영 두 자리 규칙 — PostgreSQL 에서 실제 정산 코드로</b> (리뷰 2026-10-31 반올림 누적 결정, {@link BalanceAmounts}).
 *
 * <p>규칙: 세션 잔액에는 주문별 누적 금액(매수 환급 = 요청 − 대금 − 수수료, 매도 순입금 = 대금 − 수수료)을 둘째 자리 HALF_UP 으로
 * 반올림한 값만 넣고, 정정은 그 반올림값끼리의 차분으로 넣는다. 원시 기록(주문 대금·수수료, 포지션 손익)은 8자리 그대로다.
 * 각 사례는 세 값을 따로 본다 — <b>원시 장부</b>(주문 8자리 반영값으로 다시 계산), <b>저장 잔액</b>(세션 행), <b>주문별 두 자리
 * 기대</b>(이 시험이 따로 계산). 허용 오차 없음.</p>
 *
 * <p>경로는 운영과 같다 — 매수는 폴러 진입점 {@code checkOrderStatus}(거래소 응답만 대역), 매도는 청산 확정
 * ({@code reconcileClosingPositions}·{@code reconcileDynamicClosingPositions}) 뒤 정산 {@code settle}. LIVE·DYNAMIC 을 같은 사례로 돈다.</p>
 *
 * <p>🔴 <b>H2 로 돌리지 않는다</b> — 잔액 열 반올림은 PostgreSQL NUMERIC(20,2) 에서 본다. 로컬 전용이며 주소가 localhost 가 아니면
 * 실행하지 않는다(운영 DB 보호). 시험 DB 는 매번 새로 만든다(Flyway 가 운영과 같은 마이그레이션을 적용한다). 실행:</p>
 * <pre>
 * docker exec cat-rt-pg psql -U trader -d postgres -c "DROP DATABASE IF EXISTS settle_round_check" -c "CREATE DATABASE settle_round_check"
 * SR_PG_URL=jdbc:postgresql://localhost:15432/settle_round_check ./gradlew :web-api:cleanTest :web-api:test --tests '*SettlementRoundingPgTest'
 * </pre>
 * <p>🔴 롤백 사례는 시험 DB 에만 일시 트리거를 걸어 세션 갱신을 실패시킨다. "재시작" 은 프로세스 재기동이 아니라, 실패 뒤 DB 에 남은
 * 행만으로 다시 정산하는 것이다(정산은 메모리 상태를 쓰지 않는다). 실제 프로세스 재기동 시험은 하지 않았다.</p>
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
@DisplayName("잔액 반영 두 자리 규칙 — 실제 정산 코드 (로컬 PostgreSQL)")
class SettlementRoundingPgTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private OrderSettlementService settlement;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitOrderClient upbitOrderClient;
    /** {@code checkOrderStatus} 는 이것이 null 이면 아무 일도 하지 않고 돌아간다. */
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;

    private static final BigDecimal REQUEST = new BigDecimal("3000.00");
    private static final BigDecimal AFTER_RESERVE = new BigDecimal("7000.00");

    private record Fx(String kind, Long sessionId, Long positionId, Long orderId) {}

    // ── 준비 ────────────────────────────────────────────────────────────────

    private Long session(String kind, String available) {
        if ("LIVE".equals(kind)) {
            return liveRepo.save(LiveTradingSessionEntity.builder()
                    .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-RND").timeframe("H1")
                    .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal(available))
                    .totalAssetKrw(new BigDecimal("10000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        }
        // 틱이 평가하지 않게 정지 상태로 둔다 — 체결 회계·청산 확정은 세션 상태와 무관하게 돈다(DynamicFillAccountingTest 와 같다).
        return dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000.00")).availableKrw(new BigDecimal(available))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("STOPPED").scanState("POSITION_MONITORING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).build()).getId();
    }

    /** 시장가 매수 3,000.00 을 예약해 잔액 7,000.00 · 포지션 수량 0 · 주문 SUBMITTED. */
    private Fx submittedBuy(String kind) {
        Long sid = session(kind, "7000.00");
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-RND").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(BigDecimal.ZERO).investedKrw(REQUEST).entryAllocationKrw(REQUEST)
                .status("OPEN").sessionKind(kind).sessionId(sid).openedAt(Instant.now()).build());
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-RND").side("BUY").orderType("MARKET")
                .quantity(REQUEST).reservedKrw(REQUEST).state("SUBMITTED")
                .sessionKind(kind).sessionId(sid).positionId(p.getId())
                .exchangeOrderId("rnd-" + UUID.randomUUID()).clientOrderId("rnd-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());
        return new Fx(kind, sid, p.getId(), o.getId());
    }

    /** 체결된 매도가 확정을 기다리는 상태 — 잔액 200.00, 포지션 CLOSING 0.0016 @ 12,495,000, 매도 FILLED(보고 대금·수수료). */
    private Fx filledSell(String kind, String fee) {
        Long sid = session(kind, "200.00");
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-RND").side("BUY").entryPrice(new BigDecimal("12495000")).avgPrice(new BigDecimal("12495000"))
                .size(new BigDecimal("0.0016")).investedKrw(new BigDecimal("19989.99")).entryAllocationKrw(new BigDecimal("20000"))
                .positionFee(new BigDecimal("9.99")).realizedPnl(BigDecimal.ZERO)
                .status("CLOSING").closingAt(Instant.now()).sessionKind(kind).sessionId(sid).openedAt(Instant.now()).build());
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-RND").side("SELL").orderType("MARKET")
                .quantity(new BigDecimal("0.0016")).state("FILLED").filledQuantity(new BigDecimal("0.0016"))
                .price(new BigDecimal("13493750")).filledAt(Instant.now())
                .reportedExecutedFunds(new BigDecimal("21590")).reportedPaidFee(new BigDecimal(fee))
                .sessionKind(kind).sessionId(sid).positionId(p.getId())
                .exchangeOrderId("rnd-" + UUID.randomUUID()).clientOrderId("rnd-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());
        return new Fx(kind, sid, p.getId(), o.getId());
    }

    private static OrderResponse buyResponse(String state, String volume, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-RND");
        r.setPrice(new BigDecimal("3000"));
        r.setExecutedVolume(new BigDecimal(volume));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(fee == null ? null : new BigDecimal(fee));
        return r;
    }

    private static OrderResponse sellResponse(String fee) {
        OrderResponse r = new OrderResponse();
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("done");
        r.setMarket("KRW-RND");
        r.setExecutedVolume(new BigDecimal("0.0016"));
        r.setExecutedFunds(new BigDecimal("21590"));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    /** 폴러가 부르는 경로 그대로 — 거래소 조회 응답을 정하고 {@code checkOrderStatus} 를 부른다. */
    private void poll(Fx f, OrderResponse r) throws Exception {
        r.setUuid(order(f).getExchangeOrderId());
        Mockito.doReturn(r).when(upbitOrderClient).getOrder(order(f).getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());
    }

    private void confirmSells(String kind) {
        if ("LIVE".equals(kind)) liveTradingService.reconcileClosingPositions();
        else dynamicTradingService.reconcileDynamicClosingPositions();
    }

    private BigDecimal available(Fx f) {
        return "LIVE".equals(f.kind())
                ? liveRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw()
                : dynamicRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw();
    }

    private OrderEntity order(Fx f) {
        return orderRepository.findById(f.orderId()).orElseThrow();
    }

    private PositionEntity position(Fx f) {
        return positionRepository.findById(f.positionId()).orElseThrow();
    }

    /** 매수 원시 장부 = 7,000 + (요청 − 반영 대금 − 반영 수수료), 주문 8자리 반영값으로. */
    private BigDecimal buyRaw(Fx f) {
        OrderEntity o = order(f);
        return AFTER_RESERVE.add(REQUEST).subtract(o.getAppliedExecutedFunds()).subtract(nz(o.getAppliedPaidFee()));
    }

    /** 매수 주문별 두 자리 기대 = 7,000 + (요청 − 대금 − 수수료) 를 HALF_UP. */
    private static BigDecimal buyExpected(String funds, String fee) {
        return AFTER_RESERVE.add(REQUEST.subtract(new BigDecimal(funds)).subtract(fee == null ? BigDecimal.ZERO : new BigDecimal(fee))
                .setScale(2, RoundingMode.HALF_UP));
    }

    /** 매도 원시 장부 = 200 + (반영 대금 − 반영 수수료). */
    private BigDecimal sellRaw(Fx f) {
        OrderEntity o = order(f);
        return new BigDecimal("200").add(o.getAppliedExecutedFunds()).subtract(o.getAppliedPaidFee());
    }

    private static BigDecimal sellExpected(String fee) {
        return new BigDecimal("200").add(new BigDecimal("21590").subtract(new BigDecimal(fee)).setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void trace(String name, Object... cols) {
        StringBuilder b = new StringBuilder("[반올림 PG] ").append(name);
        for (Object c : cols) b.append(" | ").append(c instanceof BigDecimal d ? d.toPlainString() : c);
        System.out.println(b);
    }

    // ── 사례 ────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("① 매수 반복 정정 = 일괄 반영 · .005 경계 — 수수료 1.47 → 0.001원씩 10회 정정 vs 1.48 한 번")
    void buyRepeatedCorrectionsEqualSingleApplication(String kind) throws Exception {
        Fx f = submittedBuy(kind);
        poll(f, buyResponse("done", "9.8", "2940", "1.47"));
        assertThat(order(f).getState()).isEqualTo("FILLED");
        assertThat(available(f)).isEqualByComparingTo(buyExpected("2940", "1.47")).isEqualByComparingTo("7058.53");

        BigDecimal fee = new BigDecimal("1.47");
        for (int i = 1; i <= 10; i++) {
            fee = fee.add(new BigDecimal("0.001"));
            String fs = fee.toPlainString();
            // 종결된 주문의 늦은 정정은 폴러의 정산 경로(settle)로 들어온다
            OrderSettlementService.Outcome out = settlement.settle(f.orderId(), buyResponse("done", "9.8", "2940", fs));
            assertThat(out).as("%s 회차 %d", kind, i).isEqualTo(OrderSettlementService.Outcome.APPLIED);
            BigDecimal raw = buyRaw(f);
            BigDecimal stored = available(f);
            trace(kind + " 매수 정정 " + i, fs, raw, stored, buyExpected("2940", fs), stored.subtract(raw));
            assertThat(order(f).getAppliedPaidFee()).as("원시 기록 8자리 그대로").isEqualByComparingTo(fs);
            assertThat(stored).as("%s 회차 %d — 저장 잔액 = 주문별 두 자리 기대", kind, i).isEqualByComparingTo(buyExpected("2940", fs));
        }
        // .005 경계: 회차 5(수수료 1.475 → 환급 58.525)는 58.53, 회차 6(58.524)은 58.52 — 위 반복에서 단언됐다.

        Fx once = submittedBuy(kind);
        poll(once, buyResponse("done", "9.8", "2940", "1.48"));
        trace(kind + " 매수 일괄", "1.48", buyRaw(once), available(once));
        assertThat(available(f)).as("반복 정정 최종 = 일괄 반영").isEqualByComparingTo(available(once)).isEqualByComparingTo("7058.52");
        // 🔴 발견(기록, 리뷰 판단 — 이번 수정 범위 밖): 종결 뒤 매수 수수료 정정(settle)은 포지션을 고치지 않는다 — 투입액이 첫 반영값
        //    2,941.47 에 남고, 한 번에 받은 주문은 2,941.48 이다. 잔액·주문 반영값은 맞다. 이 시험 첫 실행에서 드러났다.
        assertThat(position(f).getInvestedKrw()).as("정정 주문의 포지션 투입액(첫 반영값에 남음)").isEqualByComparingTo("2941.47");
        assertThat(position(once).getInvestedKrw()).as("일괄 주문의 포지션 투입액").isEqualByComparingTo("2941.48");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("② 같은 응답 반복 — 체결·정정 응답을 세 번씩 받아도 잔액은 한 번 반영과 같다")
    void sameResponseRepeated(String kind) throws Exception {
        Fx f = submittedBuy(kind);
        for (int i = 0; i < 3; i++) poll(f, buyResponse("done", "9.8", "2940.0049", "1.47000245"));
        BigDecimal afterFill = available(f);
        assertThat(afterFill).isEqualByComparingTo(buyExpected("2940.0049", "1.47000245"));   // 58.52509755 → 58.53
        for (int i = 0; i < 3; i++) {
            OrderSettlementService.Outcome out = settlement.settle(f.orderId(), buyResponse("done", "9.8", "2940.0049", "1.47000245"));
            assertThat(out).isEqualTo(OrderSettlementService.Outcome.NO_CHANGE);
        }
        trace(kind + " 같은 응답", buyRaw(f), available(f));
        assertThat(available(f)).isEqualByComparingTo(afterFill);
        assertThat(order(f).getAppliedExecutedFunds()).as("원시 기록").isEqualByComparingTo("2940.0049");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("③ 부분 체결 → 남은 금액 취소 — 중간 환급도 두 자리, 최종은 취소 응답 한 번과 같다")
    void partialFillThenCancel(String kind) throws Exception {
        Fx f = submittedBuy(kind);
        poll(f, buyResponse("wait", "5", "1500.004", "0.750002"));          // 환급 1,499.245998 → 1,499.25
        assertThat(order(f).getState()).as("아직 활성").isEqualTo("SUBMITTED");
        trace(kind + " 부분 체결", buyRaw(f), available(f));
        assertThat(available(f)).isEqualByComparingTo(buyExpected("1500.004", "0.750002")).isEqualByComparingTo("8499.25");

        poll(f, buyResponse("cancel", "9.8", "2940.0037", "1.47000185"));   // 환급 58.52629815 → 58.53
        trace(kind + " 취소 종결", buyRaw(f), available(f));
        assertThat(available(f)).isEqualByComparingTo(buyExpected("2940.0037", "1.47000185")).isEqualByComparingTo("7058.53");
        assertThat(position(f).getSize()).isEqualByComparingTo("9.8");

        Fx once = submittedBuy(kind);
        poll(once, buyResponse("cancel", "9.8", "2940.0037", "1.47000185"));
        assertThat(available(f)).as("부분 → 취소 = 취소 한 번").isEqualByComparingTo(available(once));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("④ 수수료 추정 → 실측 — 수수료 없는 체결 뒤 실측 1.475(.005 경계)가 와도 실측 한 번과 같다")
    void estimatedThenReportedFee(String kind) throws Exception {
        Fx f = submittedBuy(kind);
        poll(f, buyResponse("done", "9.8", "2940", null));
        assertThat(order(f).getFeeSource()).isEqualTo(OrderExecutionEngine.FEE_SOURCE_ESTIMATED);
        BigDecimal estimated = available(f);
        trace(kind + " 추정", buyRaw(f), estimated);
        assertThat(estimated).isEqualByComparingTo(buyExpected("2940", null));

        OrderSettlementService.Outcome out = settlement.settle(f.orderId(), buyResponse("done", "9.8", "2940", "1.475"));
        assertThat(out).isEqualTo(OrderSettlementService.Outcome.APPLIED);
        assertThat(order(f).getFeeSource()).isEqualTo(OrderExecutionEngine.FEE_SOURCE_REPORTED);
        trace(kind + " 실측", buyRaw(f), available(f));
        assertThat(available(f)).isEqualByComparingTo(buyExpected("2940", "1.475")).isEqualByComparingTo("7058.53");   // 58.525 → 58.53

        Fx once = submittedBuy(kind);
        poll(once, buyResponse("done", "9.8", "2940", "1.475"));
        assertThat(available(f)).as("추정 → 실측 = 실측 한 번").isEqualByComparingTo(available(once));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("⑤ 매도 확정 + 반복 정정 = 일괄 — 순입금 21,579.205(.005 경계) 확정 뒤 수수료 0.001원씩 10회 vs 10.805 한 번, 실현손익은 원시값")
    void sellConfirmAndCorrectionsEqualSingle(String kind) {
        Fx f = filledSell(kind, "10.795");
        confirmSells(kind);
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
        assertThat(order(f).getSellAppliedAt()).isNotNull();
        trace(kind + " 매도 확정", sellRaw(f), available(f));
        assertThat(available(f)).as("확정 — 21,579.205 → 21,579.21").isEqualByComparingTo(sellExpected("10.795")).isEqualByComparingTo("21779.21");

        BigDecimal fee = new BigDecimal("10.795");
        for (int i = 1; i <= 10; i++) {
            fee = fee.add(new BigDecimal("0.001"));
            String fs = fee.toPlainString();
            OrderSettlementService.Outcome out = settlement.settle(f.orderId(), sellResponse(fs));
            assertThat(out).as("%s 매도 정정 %d", kind, i).isEqualTo(OrderSettlementService.Outcome.APPLIED);
            BigDecimal raw = sellRaw(f);
            BigDecimal stored = available(f);
            trace(kind + " 매도 정정 " + i, fs, raw, stored, sellExpected(fs), stored.subtract(raw));
            assertThat(stored).as("%s 매도 정정 %d — 저장 잔액 = 주문별 두 자리 기대", kind, i).isEqualByComparingTo(sellExpected(fs));
        }

        Fx once = filledSell(kind, "10.805");
        confirmSells(kind);
        trace(kind + " 매도 일괄", sellRaw(once), available(once));
        assertThat(available(f)).as("반복 정정 최종 = 일괄").isEqualByComparingTo(available(once)).isEqualByComparingTo("21779.20");
        assertThat(position(f).getRealizedPnl()).as("실현손익은 8자리 원시값 — 일괄과 같다")
                .isEqualByComparingTo(position(once).getRealizedPnl())
                .isEqualByComparingTo(new BigDecimal("21579.195").subtract(new BigDecimal("0.0016").multiply(new BigDecimal("12495000"))));
        // 🔴 발견(기록, 리뷰 판단 — 바꾸려면 DB 마이그레이션): position.position_fee 는 NUMERIC(20,2) 라 잔액과 같은 누적이 생긴다 —
        //    매수 9.99 + 매도 10.795 = 20.785 → 20.79 로 저장된 뒤 0.001원 정정 10회가 모두 사라져 20.79, 한 번에 받은 쪽은 20.795 → 20.80.
        //    장부(sid_ledger.sql)는 주문의 8자리 반영 수수료를 쓰므로 이 열을 판정에 쓰지 않는다.
        assertThat(position(f).getPositionFee()).as("정정 포지션 수수료(2자리 열)").isEqualByComparingTo("20.79");
        assertThat(position(once).getPositionFee()).as("일괄 포지션 수수료(2자리 열)").isEqualByComparingTo("20.80");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("⑥ 롤백 → 다시 정산 — 세션 갱신이 실패하면 주문 반영값·잔액이 함께 되돌아가고, 다시 정산하면 한 번만 반영된다")
    void rollbackThenRetry(String kind) throws Exception {
        String table = "LIVE".equals(kind) ? "live_trading_session" : "dynamic_session";
        Fx f = submittedBuy(kind);
        try {
            // 첫 체결 회계가 실패 — 관측·대기는 남고 회계(주문·잔액·포지션)는 롤백
            failSessionUpdate(table, f.sessionId());
            poll(f, buyResponse("done", "9.8", "2940", "1.47"));
            assertThat(order(f).getAppliedExecutedFunds()).as("회계 롤백").isNull();
            assertThat(order(f).getFillAccountingPending()).as("재처리 대기 남음").isTrue();
            assertThat(available(f)).isEqualByComparingTo(AFTER_RESERVE);
            assertThat(position(f).getSize()).isEqualByComparingTo("0");
            dropFailure(table);

            poll(f, buyResponse("done", "9.8", "2940", "1.47"));
            assertThat(available(f)).as("다시 정산 — 한 번").isEqualByComparingTo("7058.53");

            // 정정이 실패 — 주문 반영값·정산 버전·잔액이 그대로
            Long version = order(f).getSettlementVersion();
            failSessionUpdate(table, f.sessionId());
            assertThatThrownBy(() -> settlement.settle(f.orderId(), buyResponse("done", "9.8", "2940", "1.476")));
            assertThat(order(f).getAppliedPaidFee()).isEqualByComparingTo("1.47");
            assertThat(order(f).getSettlementVersion()).isEqualTo(version);
            assertThat(available(f)).isEqualByComparingTo("7058.53");
            dropFailure(table);

            // DB 에 남은 행만으로 다시 정산 — 한 번만
            assertThat(settlement.settle(f.orderId(), buyResponse("done", "9.8", "2940", "1.476")))
                    .isEqualTo(OrderSettlementService.Outcome.APPLIED);
            assertThat(settlement.settle(f.orderId(), buyResponse("done", "9.8", "2940", "1.476")))
                    .isEqualTo(OrderSettlementService.Outcome.NO_CHANGE);
            trace(kind + " 롤백 뒤", buyRaw(f), available(f));
            assertThat(available(f)).isEqualByComparingTo(buyExpected("2940", "1.476")).isEqualByComparingTo("7058.52");
        } finally {
            dropFailure(table);
        }
    }

    /** 시험 DB 에만 — 이 세션 행의 갱신을 실패시키는 트리거. */
    private void failSessionUpdate(String table, Long sessionId) {
        jdbc.execute("CREATE OR REPLACE FUNCTION rnd_fail_session() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.id = " + sessionId + " THEN RAISE EXCEPTION 'rnd: 세션 갱신 실패(시험)'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("DROP TRIGGER IF EXISTS rnd_fail ON " + table);
        jdbc.execute("CREATE TRIGGER rnd_fail BEFORE UPDATE ON " + table + " FOR EACH ROW EXECUTE FUNCTION rnd_fail_session()");
    }

    private void dropFailure(String table) {
        jdbc.execute("DROP TRIGGER IF EXISTS rnd_fail ON " + table);
    }
}
