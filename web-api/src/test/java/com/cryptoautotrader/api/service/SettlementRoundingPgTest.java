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
 * <p>보완(리뷰 2026-10-31 수용 뒤): ⑦ 종결 매수 주문의 정정이 열린 포지션 투입액·평균단가를 맞추고(①), CLOSED 를 재개방하지 않고
 * 매도 수수료를 덮어쓰지 않는다. ⑧ 상한 미만 소수 요청액이 실제 매수 경로(LIVE {@code executeSessionBuy}, DYNAMIC {@code executeBuy})에서
 * 예약 전에 두 자리로 버려진다. 배포 후보 검증은 운영과 같은 마이그레이션(V94)까지의 시험 DB 로 한다.</p>
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
        // 🔴 발견 1(리뷰 2026-10-31 — 이번 수정에 포함): 종결 뒤 매수 정정(settle)도 열린 포지션의 투입액·평균단가를 맞춘다.
        //    수정 전에는 투입액이 첫 반영값 2,941.47 에 남았다(이 시험 첫 실행 기록 — 계획 문서 §5-9 11).
        assertThat(position(f).getStatus()).isEqualTo("OPEN");
        assertThat(position(f).getInvestedKrw()).as("정정 주문의 포지션 투입액 = 일괄").isEqualByComparingTo(position(once).getInvestedKrw())
                .isEqualByComparingTo("2941.48");
        assertThat(position(f).getAvgPrice()).as("평균단가 = 대금 ÷ 수량").isEqualByComparingTo(position(once).getAvgPrice())
                .isEqualByComparingTo("300");
        // 알려진 통계 한계(리뷰 2026-10-31 — 이번 배포에서 열을 넓히지 않는다): position_fee 는 NUMERIC(20,2) 라 0.001원 차분이 열에서
        //    사라진다. 원시 수수료는 주문 반영값(위 단언 1.48)이 갖는다.
        assertThat(position(f).getPositionFee()).as("정정 포지션 수수료(2자리 열 — 한계)").isEqualByComparingTo("1.47");
        assertThat(position(once).getPositionFee()).as("일괄 포지션 수수료").isEqualByComparingTo("1.48");
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

    /** 포지션을 청산 중으로 두고, 그 포지션의 체결된 매도(보고 대금·수수료)를 만든다 — 확정은 {@link #confirmSells}. */
    private Long filledSellOn(Fx f, String qty, String funds, String fee) {
        jdbc.update("UPDATE position SET status = 'CLOSING', closing_at = now() WHERE id = ?", f.positionId());
        return orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-RND").side("SELL").orderType("MARKET")
                .quantity(new BigDecimal(qty)).state("FILLED").filledQuantity(new BigDecimal(qty))
                .price(new BigDecimal("306")).filledAt(Instant.now())
                .reportedExecutedFunds(new BigDecimal(funds)).reportedPaidFee(new BigDecimal(fee))
                .sessionKind(f.kind()).sessionId(f.sessionId()).positionId(f.positionId())
                .exchangeOrderId("rnd-" + UUID.randomUUID()).clientOrderId("rnd-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build()).getId();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("⑦ CLOSED 포지션의 매수 정정 — 재개방·수량·투입액 변경 없음, 매도 수수료 보존, 같은 응답 중복 반영 없음")
    void buyCorrectionOnClosedPosition(String kind) throws Exception {
        // (가) 종결 뒤 정정(settle): 체결 1.47 → 매도 확정(수수료 1.50) → 매수 수수료 1.49 정정
        Fx f = submittedBuy(kind);
        poll(f, buyResponse("done", "9.8", "2940", "1.47"));
        filledSellOn(f, "9.8", "3000", "1.50");
        confirmSells(kind);
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
        assertThat(position(f).getPositionFee()).as("매수 1.47 + 매도 1.50").isEqualByComparingTo("2.97");
        assertThat(available(f)).isEqualByComparingTo("10057.03");   // 7,058.53 + 2,998.50

        assertThat(settlement.settle(f.orderId(), buyResponse("done", "9.8", "2940", "1.49")))
                .isEqualTo(OrderSettlementService.Outcome.APPLIED);
        for (int i = 0; i < 2; i++) {
            assertThat(settlement.settle(f.orderId(), buyResponse("done", "9.8", "2940", "1.49")))
                    .as("같은 응답 다시").isEqualTo(OrderSettlementService.Outcome.NO_CHANGE);
        }
        PositionEntity p = position(f);
        trace(kind + " CLOSED 정정", p.getStatus(), p.getSize(), p.getInvestedKrw(), p.getPositionFee(), available(f));
        assertThat(p.getStatus()).as("재개방하지 않는다").isEqualTo("CLOSED");
        assertThat(p.getSize()).isEqualByComparingTo("9.8");
        assertThat(p.getInvestedKrw()).as("CLOSED 투입액 그대로").isEqualByComparingTo("2941.47");
        assertThat(p.getPositionFee()).as("매도 수수료 보존 + 매수 차분 0.02 한 번").isEqualByComparingTo("2.99");
        assertThat(available(f)).as("잔액 −0.02 한 번").isEqualByComparingTo("10057.01");

        // (나) 닫힌 포지션에 늦은 체결(체결 회계 경로 applyToPosition): 매수가 아직 활성인데 포지션이 이미 CLOSED 이고 매도 수수료
        //    0.80 이 기록된 상태 — 🔴 상태는 시험이 직접 만든다(부분 체결 'wait' 응답은 원자 체결 경로를 타지 않아 포지션 수량이 0 으로
        //    남으므로, 그 수량을 실제 매도로 닫는 순서는 이 엔진에서 생기지 않는다 — 첫 작성본의 시나리오 설계 실수, 실패 기록).
        Fx g = submittedBuy(kind);
        jdbc.update("UPDATE position SET status = 'CLOSED', closed_at = now(), position_fee = 0.80 WHERE id = ?", g.positionId());
        poll(g, buyResponse("done", "9.8", "2940", "1.47"));
        PositionEntity q = position(g);
        trace(kind + " CLOSED 늦은 체결", q.getStatus(), q.getSize(), q.getInvestedKrw(), q.getPositionFee(), available(g));
        assertThat(order(g).getState()).isEqualTo("FILLED");
        assertThat(q.getStatus()).as("재개방하지 않는다").isEqualTo("CLOSED");
        assertThat(q.getSize()).as("수량을 되살리지 않는다").isEqualByComparingTo("0");
        assertThat(q.getPositionFee()).as("매도 수수료 0.80 보존 + 매수 1.47 — 종전 set 이면 1.47").isEqualByComparingTo("2.27");
        assertThat(available(g)).as("잔액은 미사용 환급만").isEqualByComparingTo("7058.53");
        for (int i = 0; i < 2; i++) poll(g, buyResponse("done", "9.8", "2940", "1.47"));
        assertThat(position(g).getPositionFee()).as("같은 응답 다시 — 변화 없음").isEqualByComparingTo("2.27");
        assertThat(available(g)).isEqualByComparingTo("7058.53");
    }

    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("orderExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor orderExecutor;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private static java.util.List<com.cryptoautotrader.strategy.Candle> candles() {
        java.util.List<com.cryptoautotrader.strategy.Candle> out = new java.util.ArrayList<>();
        Instant t0 = Instant.now().minus(300, java.time.temporal.ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = new BigDecimal(300 + i % 7);
            out.add(com.cryptoautotrader.strategy.Candle.builder().time(t0.plus(i, java.time.temporal.ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    private void awaitSubmitted(Long orderId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            OrderEntity o = orderRepository.findById(orderId).orElseThrow();
            var pool = orderExecutor.getThreadPoolExecutor();
            if (o.getExchangeOrderId() != null && pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("주문 접수 기록 대기 시간 초과 (orderId=" + orderId + ")");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("⑧ 상한 미만 소수 요청액 — 실제 매수 경로가 예약 전에 두 자리로 버리고, 체결 뒤 잔액 = 주문별 두 자리 기대")
    void fractionalRequestTruncatedBeforeReservation(String kind) throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("RND_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("rnd-new-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        try {
            BigDecimal init;
            BigDecimal raw;            // 비율을 곱한 원래 요청액(셋째 자리 이하 있음)
            BigDecimal request;        // 기대 — 둘째 자리 버림
            Long sid;
            String result;
            if ("LIVE".equals(kind)) {
                // SID 203 과 같은 비율·상한(0.995 · 20,000) — 16,240.37 × 0.995 = 16,159.16815
                init = new BigDecimal("16240.37");
                raw = init.multiply(new BigDecimal("0.995"));
                LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                        .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-RND").timeframe("H1")
                        .initialCapital(init).availableKrw(init).totalAssetKrw(init)
                        .investRatio(new BigDecimal("0.995")).maxInvestment(new BigDecimal("20000"))
                        .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0)
                        .status("RUNNING").startedAt(Instant.now()).build());
                sid = s.getId();
                Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(liveTradingService);
                result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(target, "executeSessionBuy",
                        liveRepo.findById(sid).orElseThrow(), "KRW-RND", new BigDecimal("300"), "반올림 PG ⑧",
                        null, null, candles());
            } else {
                // 10,000.37 × 0.8 = 8,000.296
                init = new BigDecimal("10000.37");
                raw = init.multiply(new BigDecimal("0.8000"));
                sid = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                        .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                        .initialCapital(init).availableKrw(init).totalAssetKrw(init)
                        .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                        .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                        .maxCandidateSize(30).targetWatchSize(10)
                        .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                        .watchlistRefreshMin(60).build()).getId();
                Long dsid = sid;
                result = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(st ->
                        dynamicTradingService.executeBuy(dynamicRepo.findById(dsid).orElseThrow(), "KRW-RND", candles(),
                                com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "반올림 PG ⑧"), BigDecimal.ONE));
            }
            request = raw.setScale(2, RoundingMode.DOWN);
            assertThat(result).as("%s 매수 경로 결과(null = 예약·제출)", kind).isNull();

            OrderEntity o = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid).stream()
                    .filter(x -> "BUY".equals(x.getSide())).findFirst().orElseThrow();
            awaitSubmitted(o.getId());
            o = orderRepository.findById(o.getId()).orElseThrow();
            Fx f = new Fx(kind, sid, o.getPositionId(), o.getId());
            BigDecimal reserved = available(f);
            trace(kind + " 예약", raw, o.getQuantity(), o.getReservedKrw(), reserved, position(f).getInvestedKrw());
            assertThat(raw.scale()).as("원래 요청액은 셋째 자리 이하가 있다").isGreaterThan(2);
            assertThat(raw.compareTo(new BigDecimal("20000"))).as("상한 미만").isNegative();
            assertThat(o.getQuantity()).as("주문 요청액 = 둘째 자리 버림").isEqualByComparingTo(request);
            assertThat(o.getReservedKrw()).as("예약액 = 요청액").isEqualByComparingTo(request);
            assertThat(position(f).getInvestedKrw()).isEqualByComparingTo(request);
            assertThat(reserved).as("예약 뒤 잔액 = 시작 − 버린 요청액 (수정 전이면 원래 요청액을 빼 열에서 반올림)")
                    .isEqualByComparingTo(init.subtract(request));

            // 체결 — 환급의 셋째 자리 이하가 있는 대금·수수료
            BigDecimal funds = request.subtract(new BigDecimal("10.16655"));
            BigDecimal fee = new BigDecimal("8.07506");
            OrderResponse fill = buyResponse("done", "53.8", funds.toPlainString(), fee.toPlainString());
            fill.setMarket("KRW-RND");
            poll(f, fill);
            BigDecimal expected = init.subtract(request)
                    .add(request.subtract(funds).subtract(fee).setScale(2, RoundingMode.HALF_UP));
            BigDecimal rawLedger = init.subtract(funds).subtract(fee);
            trace(kind + " 체결", funds, fee, rawLedger, available(f), expected);
            assertThat(order(f).getState()).isEqualTo("FILLED");
            assertThat(available(f)).as("저장 잔액 = 주문별 두 자리 기대(시작 − 요청 + round(요청 − 대금 − 수수료, 2))")
                    .isEqualByComparingTo(expected);
            assertThat(position(f).getInvestedKrw()).as("투입액 = 대금 + 수수료(원시)").isEqualByComparingTo(funds.add(fee));
        } finally {
            limits.setStage(stage);
            limits.setTotalCapKrw(total);
            limits.setPerPositionCapKrw(per);
            limits.setMaxConcurrentPositions(slots);
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
