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
import com.cryptoautotrader.core.risk.RiskCheckResult;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.154 §3 — LIVE 전역 일/주/월 손실 합산이 LIVE 세션 매도의 확정 실현 손실을 보는가 (로컬 실제 PostgreSQL, 2026-11-12).
 *
 * <p>경로는 운영과 같다 — 매도 체결은 폴러 진입점 {@code checkOrderStatus}(거래소 응답만 대역), 확정은 {@code reconcileClosingPositions},
 * 정정은 {@code settle}, 합산은 {@code RiskManagementService} 의 실제 창 계산({@code calculateLossPct}, 분모 100 을 주어 원 단위로 읽는다)과
 * {@code checkRisk()} 승인 판정. 거래소만 대역이다.</p>
 *
 * <p>기점 b5cd1d0 의 관측(수정 전): {@code OrderExecutionEngine.handleSellFill} 이 세션 매도에서 FILL 기록 전에 반환하고, 창 합산은
 * trade_log FILL 만 읽는다 — 그래서 아래 "세션 손실이 합산에 들어간다" 단언이 실패해야 한다. 비세션 체결 대조군은 수정 전후 모두 통과해야 한다.</p>
 *
 * <p>🔴 H2 로 돌리지 않는다(창 합산 질의의 {@code ->>}). 주소가 localhost 가 아니면 실행하지 않는다. 시험 DB 는 매번 새로 만든다. 실행:</p>
 * <pre>
 * docker exec v152-pg psql -U trader -d postgres -c "CREATE DATABASE lw_x"
 * LW_PG_URL=jdbc:postgresql://127.0.0.1:15437/lw_x ./gradlew :web-api:test --rerun --tests '*LiveLossWindowPgTest'
 * </pre>
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${LW_PG_URL}",
        "spring.datasource.username=${LW_PG_USER:trader}",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "LW_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("v1.154 §3 LIVE 일/주/월 손실 합산 — 세션 매도 확정 손실 (로컬 PostgreSQL)")
class LiveLossWindowPgTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private OrderSettlementService settlement;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private RiskManagementService riskManagementService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;

    private static final BigDecimal AVG = new BigDecimal("12495000");
    private static final BigDecimal QTY = new BigDecimal("0.0016");   // 원가 19,992
    private static final String COIN = "KRW-LWX";

    // ── 준비 ────────────────────────────────────────────────────────────────

    private Long liveSession(String initial, String available) {
        return liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair(COIN).timeframe("H1")
                .initialCapital(new BigDecimal(initial)).availableKrw(new BigDecimal(available))
                .totalAssetKrw(new BigDecimal(initial)).status("RUNNING").startedAt(Instant.now()).build()).getId();
    }

    private Long dynamicSession() {
        return dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("100000.00")).availableKrw(new BigDecimal("80000.00"))
                .totalAssetKrw(new BigDecimal("100000.00"))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("STOPPED").scanState("POSITION_MONITORING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).build()).getId();
    }

    /** 청산 중 포지션(수량 qty @ 12,495,000) — kind=null 이면 비세션(수동/전역) 포지션. */
    private PositionEntity closingPosition(String kind, Long sid, BigDecimal qty, String status) {
        return positionRepository.save(PositionEntity.builder()
                .coinPair(COIN).side("BUY").entryPrice(AVG).avgPrice(AVG)
                .size(qty).investedKrw(qty.multiply(AVG)).entryAllocationKrw(qty.multiply(AVG))
                .positionFee(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO)
                .status(status).closingAt("CLOSING".equals(status) ? Instant.now() : null)
                .sessionKind(kind == null ? "LIVE" : kind).sessionId(sid).openedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
    }

    /** 거래소에 낸 매도 주문(SUBMITTED) — 체결은 {@link #fill} 이 폴러 경로로 만든다. */
    private OrderEntity submittedSell(PositionEntity p, BigDecimal qty) {
        return orderRepository.save(OrderEntity.builder()
                .coinPair(COIN).side("SELL").orderType("MARKET")
                .quantity(qty).state("SUBMITTED")
                .sessionKind(p.getSessionKind()).sessionId(p.getSessionId()).positionId(p.getId())
                .exchangeOrderId("lw-" + UUID.randomUUID()).clientOrderId("lw-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());
    }

    private static OrderResponse sellResponse(String uuid, BigDecimal qty, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("done");
        r.setMarket(COIN);
        r.setExecutedVolume(qty);
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    /** 폴러가 부르는 경로 그대로 — 거래소 조회 응답을 정하고 {@code checkOrderStatus}. */
    private void fill(OrderEntity o, BigDecimal qty, String funds, String fee) throws Exception {
        Mockito.doReturn(sellResponse(o.getExchangeOrderId(), qty, funds, fee)).when(upbitOrderClient).getOrder(o.getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(o.getId());
        assertThat(orderRepository.findById(o.getId()).orElseThrow().getState()).isEqualTo("FILLED");
    }

    /** 실제 창 계산 — 분모 100 이면 반환값(%)이 손실 원 단위다(소수 둘째 자리까지). */
    private BigDecimal lossKrw(Instant since) {
        Object target = AopTestUtils.getUltimateTargetObject(riskManagementService);
        return ReflectionTestUtils.invokeMethod(target, "calculateLossPct", since, new BigDecimal("100"));
    }

    private record W(BigDecimal day, BigDecimal week, BigDecimal month) {
        W minus(W o) { return new W(day.subtract(o.day), week.subtract(o.week), month.subtract(o.month)); }
    }

    private W windows() {
        Instant now = Instant.now();
        return new W(lossKrw(now.minus(1, ChronoUnit.DAYS)), lossKrw(now.minus(7, ChronoUnit.DAYS)), lossKrw(now.minus(30, ChronoUnit.DAYS)));
    }

    private long fillRows(Long orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM trade_log WHERE order_id = ? AND event_type = 'FILL'", Long.class, orderId);
    }

    private PositionEntity pos(Long id) {
        return positionRepository.findById(id).orElseThrow();
    }

    private static void trace(String name, Object... cols) {
        StringBuilder b = new StringBuilder("[LW154] ").append(name);
        for (Object c : cols) b.append(" | ").append(c instanceof BigDecimal d ? d.toPlainString() : c);
        System.out.println(b);
    }

    private Map<String, Object> saveRisk() {
        return jdbc.queryForMap("SELECT max_daily_loss_pct, max_weekly_loss_pct, max_monthly_loss_pct, max_portfolio_drawdown_pct, "
                + "max_capital_utilization_pct FROM risk_config ORDER BY id LIMIT 1");
    }

    private void restoreRisk(Map<String, Object> m) {
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?, max_weekly_loss_pct = ?, max_monthly_loss_pct = ?, "
                        + "max_portfolio_drawdown_pct = ?, max_capital_utilization_pct = ?",
                m.get("max_daily_loss_pct"), m.get("max_weekly_loss_pct"), m.get("max_monthly_loss_pct"),
                m.get("max_portfolio_drawdown_pct"), m.get("max_capital_utilization_pct"));
    }

    private static final BigDecimal LOSS_A = new BigDecimal("2001.00");   // 18,000 − 9 − 19,992

    // ── 사례 ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("① 실제 LIVE 손실 체결→확정 뒤 checkRisk — 일일 한도(1.5%)를 넘는 세션 손실(2.00%)이면 진입 승인이 거절된다")
    void liveSessionLossBlocksEntry() throws Exception {
        Map<String, Object> saved = saveRisk();
        try {
            // 다른 위험 항목이 먼저 걸리지 않게 일/주/월만 낮게, 낙폭·사용률은 넉넉히(시험 DB 에서만).
            jdbc.update("UPDATE risk_config SET max_daily_loss_pct = 1.5, max_weekly_loss_pct = 50, max_monthly_loss_pct = 50, "
                    + "max_portfolio_drawdown_pct = 50, max_capital_utilization_pct = 90");
            Long sid = liveSession("100000", "80008");
            PositionEntity p = closingPosition("LIVE", sid, QTY, "CLOSING");
            OrderEntity sell = submittedSell(p, QTY);
            RiskCheckResult before = riskManagementService.checkRisk();
            fill(sell, QTY, "18000", "9");
            liveTradingService.reconcileClosingPositions();
            assertThat(pos(p.getId()).getStatus()).isEqualTo("CLOSED");
            assertThat(pos(p.getId()).getRealizedPnl()).isEqualByComparingTo("-2001");
            RiskCheckResult after = riskManagementService.checkRisk();
            trace("①", "체결 전 승인=" + before.isApproved(), "확정 뒤 승인=" + after.isApproved(), "사유=" + after.getReason(),
                    "FILL 행=" + fillRows(sell.getId()), "창=" + windows());
            assertThat(before.isApproved()).as("체결 전 — 거절 사유 없음").isTrue();
            assertThat(after.isApproved()).as("세션 손실 2.00% > 일일 한도 1.5% — 진입 승인 거절").isFalse();
            assertThat(after.getReason()).startsWith("일일 손실 한도 초과: 2.00%");
        } finally {
            restoreRisk(saved);
        }
    }

    @Test
    @Order(2)
    @DisplayName("② 확정 전 0 · 확정 뒤 정확히 한 번(재확정·재시도에도) · 늦은 정정은 차분만 · FILL 행은 만들지 않는다")
    void countedOnceAcrossRetryAndCorrection() throws Exception {
        Long sid = liveSession("100000", "80008");
        PositionEntity p = closingPosition("LIVE", sid, QTY, "CLOSING");
        OrderEntity sell = submittedSell(p, QTY);
        W w0 = windows();
        fill(sell, QTY, "18000", "9");
        W filled = windows().minus(w0);
        liveTradingService.reconcileClosingPositions();
        W once = windows().minus(w0);
        liveTradingService.reconcileClosingPositions();
        W twice = windows().minus(w0);
        OrderSettlementService.Outcome corr = settlement.settle(sell.getId(), sellResponse(sell.getExchangeOrderId(), QTY, "18000", "10"));
        W corrected = windows().minus(w0);
        trace("②", "체결만=" + filled, "확정=" + once, "재확정=" + twice, "정정(" + corr + ")=" + corrected,
                "포지션 손익=" + pos(p.getId()).getRealizedPnl(), "FILL 행=" + fillRows(sell.getId()));
        assertThat(filled.day()).as("체결됐으나 확정 전 — 확정 실현 손실 아님").isEqualByComparingTo("0");
        assertThat(once.day()).as("확정 손실 2,001 — 일").isEqualByComparingTo(LOSS_A);
        assertThat(once.week()).isEqualByComparingTo(LOSS_A);
        assertThat(once.month()).isEqualByComparingTo(LOSS_A);
        assertThat(twice.day()).as("재확정 — 이중 합산 없음").isEqualByComparingTo(LOSS_A);
        assertThat(corr).isEqualTo(OrderSettlementService.Outcome.APPLIED);
        assertThat(pos(p.getId()).getRealizedPnl()).isEqualByComparingTo("-2002");
        assertThat(corrected.day()).as("정정 수수료 9→10 — 포지션 손익과 같은 2,002, 정정 행을 따로 더하지 않음").isEqualByComparingTo("2002.00");
        assertThat(fillRows(sell.getId())).as("세션 매도 FILL 행은 이 후보도 만들지 않는다(기록 경로 불변)").isZero();
    }

    @Test
    @Order(3)
    @DisplayName("③ 부분 매도 뒤 나머지 매도 — 두 매도의 손실이 각각 한 번씩(1,000.50 + 2,000.00)")
    void partialThenFinal() throws Exception {
        Long sid = liveSession("100000", "80008");
        PositionEntity p = closingPosition("LIVE", sid, QTY, "CLOSING");
        BigDecimal half = new BigDecimal("0.0008");   // 원가 9,996
        W w0 = windows();
        OrderEntity s1 = submittedSell(p, half);
        fill(s1, half, "9000", "4.5");
        liveTradingService.reconcileClosingPositions();
        PositionEntity mid = pos(p.getId());
        W afterFirst = windows().minus(w0);
        assertThat(mid.getStatus()).as("부분 체결 — 잔여 보유").isEqualTo("OPEN");
        // 엔진이 나머지를 다시 파는 상태(청산 중)로 둔다 — 시험 DB 준비.
        mid.setStatus("CLOSING");
        mid.setClosingAt(Instant.now());
        positionRepository.save(mid);
        OrderEntity s2 = submittedSell(mid, half);
        fill(s2, half, "8000", "4");
        liveTradingService.reconcileClosingPositions();
        liveTradingService.reconcileClosingPositions();
        W afterSecond = windows().minus(w0);
        trace("③", "첫 매도=" + afterFirst, "둘째 매도=" + afterSecond, "포지션 상태=" + pos(p.getId()).getStatus(),
                "포지션 손익(관측)=" + pos(p.getId()).getRealizedPnl());
        assertThat(afterFirst.day()).isEqualByComparingTo("1000.50");
        assertThat(pos(p.getId()).getStatus()).isEqualTo("CLOSED");
        assertThat(afterSecond.day()).isEqualByComparingTo("3000.50");
    }

    @Test
    @Order(4)
    @DisplayName("④ 확정 롤백(세션 갱신 실패) — 합산 0, 실패 해소 뒤 재확정에서 한 번")
    void rollbackNotCounted() throws Exception {
        Long sid = liveSession("100000", "80008");
        PositionEntity p = closingPosition("LIVE", sid, QTY, "CLOSING");
        OrderEntity sell = submittedSell(p, QTY);
        fill(sell, QTY, "18000", "9");
        W w0 = windows();
        jdbc.execute("CREATE OR REPLACE FUNCTION lw_fail_session() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.id = " + sid + " THEN RAISE EXCEPTION 'lw: 세션 갱신 실패(시험)'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("DROP TRIGGER IF EXISTS lw_fail ON live_trading_session");
        jdbc.execute("CREATE TRIGGER lw_fail BEFORE UPDATE ON live_trading_session FOR EACH ROW EXECUTE FUNCTION lw_fail_session()");
        W rolled;
        Throwable thrown = null;
        try {
            try {
                liveTradingService.reconcileClosingPositions();
            } catch (Throwable t) {
                thrown = t;
            }
            rolled = windows().minus(w0);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS lw_fail ON live_trading_session");
        }
        Instant applied = orderRepository.findById(sell.getId()).orElseThrow().getSellAppliedAt();
        String stAfterFail = pos(p.getId()).getStatus();
        liveTradingService.reconcileClosingPositions();
        W retried = windows().minus(w0);
        trace("④", "롤백 뒤=" + rolled + " 예외=" + thrown + " 반영 표시=" + applied + " 포지션=" + stAfterFail, "재확정=" + retried);
        assertThat(applied).as("롤백 — 반영 표시도 되돌아감").isNull();
        assertThat(rolled.day()).as("롤백된 확정은 합산하지 않음").isEqualByComparingTo("0");
        assertThat(retried.day()).isEqualByComparingTo(LOSS_A);
    }

    @Test
    @Order(5)
    @DisplayName("⑤ 이익 매도와 손실 매도 — 손실만 합산(이익과 상쇄하지 않음)")
    void gainsNotOffset() throws Exception {
        W w0 = windows();
        Long s1 = liveSession("100000", "80008");
        PositionEntity win = closingPosition("LIVE", s1, QTY, "CLOSING");
        OrderEntity o1 = submittedSell(win, QTY);
        fill(o1, QTY, "22000", "11");   // +1,997
        Long s2 = liveSession("100000", "80008");
        PositionEntity loss = closingPosition("LIVE", s2, QTY, "CLOSING");
        OrderEntity o2 = submittedSell(loss, QTY);
        fill(o2, QTY, "18000", "9");    // −2,001
        liveTradingService.reconcileClosingPositions();
        W d = windows().minus(w0);
        trace("⑤", "이익 포지션 손익=" + pos(win.getId()).getRealizedPnl(), "손실 포지션 손익=" + pos(loss.getId()).getRealizedPnl(), "합산=" + d);
        assertThat(pos(win.getId()).getRealizedPnl()).isEqualByComparingTo("1997");
        assertThat(d.day()).isEqualByComparingTo(LOSS_A);
    }

    @Test
    @Order(6)
    @DisplayName("⑥ 범위 — 비세션 체결(FILL) 대조군은 그대로 합산, DYNAMIC REAL·DYN_PAPER 세션 매도는 이 후보 범위 밖(관측)")
    void scope() throws Exception {
        // 비세션: 기존 FILL 경로(handleSellFill) — 수정 전후 같아야 한다.
        W w0 = windows();
        PositionEntity manual = positionRepository.save(PositionEntity.builder()
                .coinPair(COIN).side("LONG").entryPrice(AVG).avgPrice(AVG).size(QTY)
                .realizedPnl(BigDecimal.ZERO).status("OPEN").build());
        OrderEntity mo = orderRepository.save(OrderEntity.builder()
                .coinPair(COIN).side("SELL").orderType("MARKET").quantity(QTY).state("SUBMITTED").positionId(manual.getId())
                .exchangeOrderId("lw-" + UUID.randomUUID()).clientOrderId("lw-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING).createdAt(Instant.now()).submittedAt(Instant.now()).build());
        fill(mo, QTY, "18000", "9");
        W manualD = windows().minus(w0);
        long manualFill = fillRows(mo.getId());

        // DYNAMIC REAL: 같은 조기 반환을 지나고 정산은 reconcileDynamicClosingPositions.
        W w1 = windows();
        Long did = dynamicSession();
        PositionEntity dp = closingPosition("DYNAMIC", did, QTY, "CLOSING");
        OrderEntity dso = submittedSell(dp, QTY);
        fill(dso, QTY, "18000", "9");
        dynamicTradingService.reconcileDynamicClosingPositions();
        W dynD = windows().minus(w1);
        trace("⑥", "비세션 FILL 행=" + manualFill + " 합산=" + manualD,
                "DYNAMIC REAL 포지션=" + pos(dp.getId()).getStatus() + " 손익=" + pos(dp.getId()).getRealizedPnl()
                        + " FILL 행=" + fillRows(dso.getId()) + " 합산=" + dynD);
        // 비세션 손익 식(handleSellFill): 18,000 − 18,000×0.0005 − 19,992 = −2,001.
        assertThat(manualFill).isEqualTo(1);
        assertThat(manualD.day()).as("비세션 대조군 — 기존 FILL 합산").isEqualByComparingTo(LOSS_A);
        assertThat(pos(dp.getId()).getStatus()).isEqualTo("CLOSED");
        assertThat(dynD.day()).as("DYNAMIC REAL 은 이 후보 범위 밖 — 분모(LIVE RUNNING 초기자본)와 범위 판단 대기").isEqualByComparingTo("0");
    }

    @Test
    @Order(7)
    @DisplayName("⑦ 창 경계 — 반영 시각 T 에서 since=T 포함(≥), since=T+1µs 제외")
    void windowBoundary() throws Exception {
        Long sid = liveSession("100000", "80008");
        PositionEntity p = closingPosition("LIVE", sid, QTY, "CLOSING");
        OrderEntity sell = submittedSell(p, QTY);
        fill(sell, QTY, "18000", "9");
        liveTradingService.reconcileClosingPositions();
        // 이 매도의 반영 시각을 다른 행이 없는 과거 시각으로 옮겨 그 한 건만 본다(시험 DB 에서만).
        Instant t = Instant.parse("2020-03-04T05:06:07.123456Z");
        jdbc.update("UPDATE \"order\" SET sell_applied_at = ? WHERE id = ?", Timestamp.from(t), sell.getId());
        BigDecimal at = lossKrw(t);
        BigDecimal after = lossKrw(t.plus(1, ChronoUnit.MICROS));
        trace("⑦", "since=T " + at, "since=T+1µs " + after);
        assertThat(at.subtract(after)).as("T 포함·T+1µs 제외 — 그 한 건 2,001").isEqualByComparingTo(LOSS_A);
    }
}
