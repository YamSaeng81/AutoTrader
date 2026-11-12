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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.157 §125-6 — 전역 REAL 확정 손실 실패 재현 (로컬 실제 PostgreSQL, 84fb03d6 기점, 2026-11-12). <b>정책 운영 적용 승인이 아니다.</b>
 *
 * <p>현재 전역 일/주/월 손실 판정({@code checkRisk})의 분자는 비세션 FILL + LIVE 세션 매도 확정(주문 원천)이고, 분모는 RUNNING LIVE 초기자본
 * (없으면 설정·최종 폴백)이다. DYNAMIC REAL 확정 손실은 분자에 없다. 방향 초안: 분자 = LIVE·DYNAMIC REAL·비세션 실자금 확정 손실(PAPER/DYN_PAPER·
 * 추정 청산 제외), 분모 = RUNNING LIVE 초기자본 + RUNNING DYNAMIC REAL 초기자본. 이 시험은 초안 기대를 단언하고 기점에서 실패해야 한다(①).
 * ②·③ 은 판정 변화 관측(초안이 LIVE 판정을 완화할 수 있는 경우 포함)과 대조군이다.</p>
 *
 * <pre>
 * docker exec v152-pg psql -U trader -d postgres -c "CREATE DATABASE rl_x"
 * RL_PG_URL=jdbc:postgresql://127.0.0.1:15437/rl_x ./gradlew :web-api:test --rerun --tests '*RealLossGlobalPgTest'
 * </pre>
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${RL_PG_URL}",
        "spring.datasource.username=${RL_PG_USER:trader}",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "RL_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("v1.157 §125-6 전역 REAL 확정 손실 — DYNAMIC REAL 손실 실패 재현·판정 변화 (로컬 PostgreSQL)")
class RealLossGlobalPgTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
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
    private static final BigDecimal QTY = new BigDecimal("0.0016");   // 원가 19,992 → 18,000 체결·수수료 9 = −2,001

    private String coin() { return "KRW-RL" + (System.nanoTime() % 100000); }

    private Long liveRunning(String coin) {
        return liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("100000")).availableKrw(new BigDecimal("80008"))
                .totalAssetKrw(new BigDecimal("100000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
    }

    private Long dynamicRunning(String mode) {
        return dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("100000.00")).availableKrw(new BigDecimal("80000.00"))
                .totalAssetKrw(new BigDecimal("100000.00"))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("POSITION_MONITORING").tradingMode(mode)
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).build()).getId();
    }

    /** 청산 중 포지션 → 매도 SUBMITTED → 폴러 체결(18,000·수수료 9) → 엔진 확정. */
    private PositionEntity lossSell(String kind, Long sid, String coin) throws Exception {
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair(coin).side("BUY").entryPrice(AVG).avgPrice(AVG)
                .size(QTY).investedKrw(QTY.multiply(AVG)).entryAllocationKrw(QTY.multiply(AVG))
                .positionFee(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO)
                .status("CLOSING").closingAt(Instant.now())
                .sessionKind(kind).sessionId(sid).openedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair(coin).side("SELL").orderType("MARKET").quantity(QTY).state("SUBMITTED")
                .sessionKind(kind).sessionId(sid).positionId(p.getId())
                .exchangeOrderId("rl-" + UUID.randomUUID()).clientOrderId("rl-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING).createdAt(Instant.now()).submittedAt(Instant.now()).build());
        OrderResponse r = new OrderResponse();
        r.setUuid(o.getExchangeOrderId()); r.setSide("ask"); r.setOrdType("market"); r.setState("done"); r.setMarket(coin);
        r.setExecutedVolume(QTY); r.setExecutedFunds(new BigDecimal("18000")); r.setPaidFee(new BigDecimal("9"));
        Mockito.doReturn(r).when(upbitOrderClient).getOrder(o.getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(o.getId());
        if ("LIVE".equals(kind)) liveTradingService.reconcileClosingPositions();
        else dynamicTradingService.reconcileDynamicClosingPositions();
        PositionEntity done = positionRepository.findById(p.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo("CLOSED");
        return done;
    }

    private BigDecimal lossKrw() {
        Object target = AopTestUtils.getUltimateTargetObject(riskManagementService);
        return ReflectionTestUtils.invokeMethod(target, "calculateLossPct", Instant.now().minus(1, ChronoUnit.DAYS), new BigDecimal("100"));
    }

    private BigDecimal denominator() {
        Object target = AopTestUtils.getUltimateTargetObject(riskManagementService);
        return ReflectionTestUtils.invokeMethod(target, "resolvePortfolioLimit", riskManagementService.getRiskConfig());
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

    /** 이전 사례의 RUNNING 세션·손실이 섞이지 않게 정지·창 밖으로 민다(시험 DB 에서만). */
    private void isolate() {
        jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'");
        jdbc.update("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING'");
        jdbc.update("UPDATE \"order\" SET sell_applied_at = sell_applied_at - interval '400 days' WHERE sell_applied_at IS NOT NULL");
        jdbc.update("UPDATE trade_log SET created_at = created_at - interval '400 days'");
    }

    private void lowDaily() {
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = 1.5, max_weekly_loss_pct = 50, max_monthly_loss_pct = 50, "
                + "max_portfolio_drawdown_pct = 50, max_capital_utilization_pct = 90");
    }

    private static void trace(String name, Object... cols) {
        StringBuilder b = new StringBuilder("[RL157] ").append(name);
        for (Object c : cols) b.append(" | ").append(c instanceof BigDecimal d ? d.toPlainString() : c);
        System.out.println(b);
    }

    @Test
    @Order(1)
    @DisplayName("① 실패 재현 — LIVE 10만·DYNAMIC REAL 10만 RUNNING, DYNAMIC REAL 확정 손실 2건(−4,002 = 초안 분모 20만의 2.0%) > 일일 1.5% 인데 checkRisk 승인")
    void dynamicRealLossDoesNotBlock() throws Exception {
        Map<String, Object> saved = saveRisk();
        try {
            isolate();
            lowDaily();
            String c = coin();
            liveRunning(c);
            Long did = dynamicRunning("REAL");
            RiskCheckResult before = riskManagementService.checkRisk();
            lossSell("DYNAMIC", did, c);
            lossSell("DYNAMIC", did, c);
            RiskCheckResult after = riskManagementService.checkRisk();
            trace("①", "전 승인=" + before.isApproved(), "후 승인=" + after.isApproved(), "사유=" + after.getReason(),
                    "분자(원)=" + lossKrw(), "분모=" + denominator());
            assertThat(before.isApproved()).isTrue();
            assertThat(after.isApproved()).as("초안: DYNAMIC REAL 확정 손실 4,002 / (10만+10만) = 2.0% > 1.5% — 거절이어야 한다").isFalse();
        } finally {
            restoreRisk(saved);
        }
    }

    @Test
    @Order(2)
    @DisplayName("② 판정 변화 관측 — LIVE 손실 −2,001 만: 현재 분모 10만(2.00% 거절), 초안 분모 20만이면 1.00%(승인) — 초안이 LIVE 판정을 완화하는 경우")
    void liveOnlyLossObservation() throws Exception {
        Map<String, Object> saved = saveRisk();
        try {
            isolate();
            lowDaily();
            String c = coin();
            Long lid = liveRunning(c);
            dynamicRunning("REAL");
            lossSell("LIVE", lid, c);
            RiskCheckResult now = riskManagementService.checkRisk();
            trace("②", "현재 승인=" + now.isApproved(), "사유=" + now.getReason(), "분자(원)=" + lossKrw(), "현재 분모=" + denominator(),
                    "초안 분모=200000 → " + new BigDecimal("2001").divide(new BigDecimal("2000"), 2, java.math.RoundingMode.HALF_UP) + "%");
            assertThat(now.isApproved()).as("현재: LIVE 분모만 — 거절(관측 고정)").isFalse();
        } finally {
            restoreRisk(saved);
        }
    }

    @Test
    @Order(3)
    @DisplayName("③ 대조 — DYN_PAPER 손실은 현재·초안 모두 합산하지 않는다")
    void dynPaperExcluded() throws Exception {
        Map<String, Object> saved = saveRisk();
        try {
            isolate();
            lowDaily();
            String c = coin();
            liveRunning(c);
            Long pid = dynamicRunning("PAPER");
            BigDecimal before = lossKrw();
            PositionEntity p = positionRepository.save(PositionEntity.builder()
                    .coinPair(c).side("BUY").entryPrice(AVG).avgPrice(AVG).size(QTY).investedKrw(QTY.multiply(AVG))
                    .realizedPnl(new BigDecimal("-5000")).status("CLOSED").closedAt(Instant.now())
                    .sessionKind("DYN_PAPER").sessionId(pid).openedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
            RiskCheckResult r = riskManagementService.checkRisk();
            trace("③", "DYN_PAPER 손실 행=" + p.getRealizedPnl(), "분자 " + before + "→" + lossKrw(), "승인=" + r.isApproved());
            assertThat(lossKrw()).isEqualByComparingTo(before);
            assertThat(r.isApproved()).isTrue();
        } finally {
            restoreRisk(saved);
        }
    }
}
