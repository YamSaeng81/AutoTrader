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
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.158 결정 2 — 전역 REAL 손실: 기존 보호식 유지 + DYNAMIC REAL 보호식 + 전체 REAL 보호식, 어느 하나라도 기존 한도를 넘으면 신규
 * 실자금 매수 거절 (로컬 실제 PostgreSQL, 92aa8712 기점, 2026-11-12). <b>정책 운영 적용 승인이 아니다.</b>
 *
 * <p>손실은 실제 경로로만 만든다 — 매도 SUBMITTED → 폴러 {@code checkOrderStatus}(거래소 응답만 대역) → 엔진 청산 확정. 가상 체결·현금 보정 없음.
 * 판정은 실제 승인 경로({@code checkRisk}: LIVE 즉시·회차 승인·비세션 BUY / DYNAMIC REAL {@code executeBuy}: 즉시·회차 승인 공통)로 본다.
 * 비교 규칙은 기존 {@code RiskEngine}(손실률 &gt; 한도면 거절, 동률 승인) 그대로다. risk_config 는 NUMERIC(5,2).</p>
 *
 * <pre>
 * docker exec v152-pg psql -U trader -d postgres -c "CREATE DATABASE rl3_x"
 * RL_PG_URL=jdbc:postgresql://127.0.0.1:15437/rl3_x ./gradlew :web-api:test --rerun --tests '*RealLossThreeFormulaPgTest'
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
@DisplayName("v1.158 결정 2 — 실자금 손실 세 보호식 (로컬 PostgreSQL)")
class RealLossThreeFormulaPgTest extends IntegrationTestBase {

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
    private static final BigDecimal QTY = new BigDecimal("0.0016");   // 원가 19,992 → 18,000 체결·수수료 9 = 손실 2,001

    private Map<String, Object> savedRisk;

    @BeforeEach
    void isolate() {
        savedRisk = jdbc.queryForMap("SELECT max_daily_loss_pct, max_weekly_loss_pct, max_monthly_loss_pct, max_portfolio_drawdown_pct, "
                + "max_capital_utilization_pct FROM risk_config ORDER BY id LIMIT 1");
        // 이전 사례의 RUNNING 세션·손실이 섞이지 않게 정지·창 밖으로 민다(시험 DB 에서만).
        jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'");
        jdbc.update("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING'");
        jdbc.update("UPDATE \"order\" SET sell_applied_at = sell_applied_at - interval '400 days' WHERE sell_applied_at IS NOT NULL");
        jdbc.update("UPDATE trade_log SET created_at = created_at - interval '400 days'");
        limits("1.50", "50", "50");
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?, max_weekly_loss_pct = ?, max_monthly_loss_pct = ?, "
                        + "max_portfolio_drawdown_pct = ?, max_capital_utilization_pct = ?",
                savedRisk.get("max_daily_loss_pct"), savedRisk.get("max_weekly_loss_pct"), savedRisk.get("max_monthly_loss_pct"),
                savedRisk.get("max_portfolio_drawdown_pct"), savedRisk.get("max_capital_utilization_pct"));
        jdbc.execute("DROP TRIGGER IF EXISTS rl3_fail ON dynamic_session");
    }

    private void limits(String d, String w, String m) {
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?::numeric, max_weekly_loss_pct = ?::numeric, max_monthly_loss_pct = ?::numeric, "
                + "max_portfolio_drawdown_pct = 50, max_capital_utilization_pct = 90", d, w, m);
    }

    private String coin() { return "KRW-RL3" + (System.nanoTime() % 100000); }

    private Long live(String coin, String initial) {
        return liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal(initial)).availableKrw(new BigDecimal(initial))
                .totalAssetKrw(new BigDecimal(initial)).status("RUNNING").startedAt(Instant.now()).build()).getId();
    }

    private Long dynamic(String mode, String initial) {
        return dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal(initial)).availableKrw(new BigDecimal(initial))
                .totalAssetKrw(new BigDecimal(initial))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode(mode)
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).build()).getId();
    }

    private void stopDynamic(Long id) { jdbc.update("UPDATE dynamic_session SET status = 'STOPPED' WHERE id = ?", id); }

    private static OrderResponse done(String uuid, String coin, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid); r.setSide("ask"); r.setOrdType("market"); r.setState("done"); r.setMarket(coin);
        r.setExecutedVolume(QTY); r.setExecutedFunds(new BigDecimal(funds)); r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    /** 청산 중 포지션 → 매도 SUBMITTED → 폴러 체결(18,000·수수료 9). confirm 이면 엔진 확정까지. */
    private OrderEntity lossSell(String kind, Long sid, String coin, boolean confirm) throws Exception {
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair(coin).side("BUY").entryPrice(AVG).avgPrice(AVG)
                .size(QTY).investedKrw(QTY.multiply(AVG)).entryAllocationKrw(QTY.multiply(AVG))
                .positionFee(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO)
                .status("CLOSING").closingAt(Instant.now())
                .sessionKind(kind).sessionId(sid).openedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair(coin).side("SELL").orderType("MARKET").quantity(QTY).state("SUBMITTED")
                .sessionKind(kind).sessionId(sid).positionId(p.getId())
                .exchangeOrderId("rl3-" + UUID.randomUUID()).clientOrderId("rl3-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING).createdAt(Instant.now()).submittedAt(Instant.now()).build());
        Mockito.doReturn(done(o.getExchangeOrderId(), coin, "18000", "9")).when(upbitOrderClient).getOrder(o.getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(o.getId());
        if (confirm) {
            confirm(kind);
            assertThat(positionRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
        }
        return orderRepository.findById(o.getId()).orElseThrow();
    }

    private void confirm(String kind) {
        if ("LIVE".equals(kind)) liveTradingService.reconcileClosingPositions();
        else dynamicTradingService.reconcileDynamicClosingPositions();
    }

    /** DYNAMIC REAL 실제 진입 경로(즉시 진입·회차 승인 공통 {@code executeBuy}) — 손실 보호식 거절 사유, 통과면 null. */
    private String dynamicEntry(Long did, String coin) {
        DynamicSessionEntity s = dynamicRepo.findById(did).orElseThrow();
        List<Candle> candles = List.of(Candle.builder().time(Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("1000")).high(new BigDecimal("1010")).low(new BigDecimal("990")).close(new BigDecimal("1000"))
                .volume(new BigDecimal("100")).build());
        try {
            String r = dynamicTradingService.executeBuy(s, coin, candles, StrategySignal.buy(BigDecimal.ONE, "시험"), BigDecimal.ONE);
            return r != null && r.contains("실자금 손실 보호식") ? r : null;
        } catch (RuntimeException e) {
            return null;   // 보호식 뒤의 단계에서 난 예외 — 보호식은 통과했다
        }
    }

    private static void trace(String name, Object... cols) {
        StringBuilder b = new StringBuilder("[RL158] ").append(name);
        for (Object c : cols) b.append(" | ").append(c);
        System.out.println(b);
    }

    @Test @Order(1)
    @DisplayName("① DYNAMIC REAL −4,002 (LIVE 10만·DYN 10만): DYNAMIC 4.00%·전체 2.00% > 1.50% — checkRisk·DYNAMIC 진입 모두 거절")
    void dynamicLossBlocks() throws Exception {
        String c = coin();
        live(c, "100000");
        Long did = dynamic("REAL", "100000");
        assertThat(riskManagementService.checkRisk().isApproved()).isTrue();
        lossSell("DYNAMIC", did, c, true);
        lossSell("DYNAMIC", did, c, true);
        RiskCheckResult r = riskManagementService.checkRisk();
        String dyn = dynamicEntry(did, c);
        trace("①", "checkRisk=" + r.isApproved() + " " + r.getReason(), "DYNAMIC 진입=" + dyn);
        assertThat(r.isApproved()).isFalse();
        assertThat(r.getReason()).contains("DYNAMIC REAL 보호식");
        assertThat(dyn).isNotNull();
    }

    @Test @Order(2)
    @DisplayName("② LIVE −2,001 (LIVE 10만·DYN 10만): 기존 2.00% 거절 유지 — DYNAMIC 자본을 더한 전체 1.00% 로 완화되지 않음")
    void liveLossNotDiluted() throws Exception {
        String c = coin();
        Long lid = live(c, "100000");
        Long did = dynamic("REAL", "100000");
        lossSell("LIVE", lid, c, true);
        RiskCheckResult r = riskManagementService.checkRisk();
        String dyn = dynamicEntry(did, c);
        trace("②", "checkRisk=" + r.isApproved() + " " + r.getReason(), "DYNAMIC 진입=" + dyn);
        assertThat(r.isApproved()).isFalse();
        assertThat(r.getReason()).contains("기존 보호식");
        assertThat(dyn).as("실자금 매수 전체 — DYNAMIC REAL 진입도 같은 관문").isNotNull();
    }

    @Test @Order(3)
    @DisplayName("③ 판정 불가 — DYNAMIC REAL 손실이 있는데 RUNNING DYNAMIC REAL 자본이 없으면 거절, 손실 0 이면 자본 없음만으로 막지 않음")
    void undetermined() throws Exception {
        limits("50", "50", "50");
        String c = coin();
        live(c, "100000");
        RiskCheckResult zero = riskManagementService.checkRisk();
        Long did = dynamic("REAL", "100000");
        lossSell("DYNAMIC", did, c, true);
        stopDynamic(did);
        RiskCheckResult r = riskManagementService.checkRisk();
        trace("③", "손실0·DYN자본0=" + zero.isApproved(), "손실 있음·DYN 정지=" + r.isApproved() + " " + r.getReason());
        assertThat(zero.isApproved()).as("자체 손실 0 — 비활성 엔진 때문에 자동 차단하지 않는다").isTrue();
        assertThat(r.isApproved()).isFalse();
        assertThat(r.getReason()).contains("판정 불가");
    }

    @Test @Order(4)
    @DisplayName("④ 동률 비교 — 손실률 = 한도(2.00%) 승인, 한도 1.99% 거절 (기존·DYNAMIC 각각)")
    void boundaryEquality() throws Exception {
        String c = coin();
        Long lid = live(c, "100050");          // 2,001 / 100,050 = 2.0000%
        lossSell("LIVE", lid, c, true);
        limits("2.00", "50", "50");
        boolean liveEq = riskManagementService.checkRisk().isApproved();
        limits("1.99", "50", "50");
        RiskCheckResult liveOver = riskManagementService.checkRisk();
        // DYNAMIC: LIVE 손실을 창 밖으로 밀고 DYNAMIC 만 본다.
        jdbc.update("UPDATE \"order\" SET sell_applied_at = sell_applied_at - interval '400 days' WHERE sell_applied_at IS NOT NULL");
        Long did = dynamic("REAL", "100050");
        lossSell("DYNAMIC", did, c, true);    // DYN 2.0000%, 전체 2,001/200,100 = 1.0000%
        limits("2.00", "50", "50");
        boolean dynEq = riskManagementService.checkRisk().isApproved();
        limits("1.99", "50", "50");
        RiskCheckResult dynOver = riskManagementService.checkRisk();
        trace("④", "기존 동률=" + liveEq, "기존 초과=" + liveOver.getReason(), "DYN 동률=" + dynEq, "DYN 초과=" + dynOver.getReason());
        assertThat(liveEq).isTrue();
        assertThat(liveOver.isApproved()).isFalse();
        assertThat(dynEq).isTrue();
        assertThat(dynOver.isApproved()).isFalse();
        assertThat(dynOver.getReason()).contains("DYNAMIC REAL 보호식");
    }

    @Test @Order(5)
    @DisplayName("⑤ 창 경계 — 2일 전 DYNAMIC 손실은 일일 밖·주간 안")
    void windows() throws Exception {
        String c = coin();
        live(c, "100000");
        Long did = dynamic("REAL", "100000");
        OrderEntity o = lossSell("DYNAMIC", did, c, true);
        jdbc.update("UPDATE \"order\" SET sell_applied_at = now() - interval '2 days' WHERE id = ?", o.getId());
        boolean daily = riskManagementService.checkRisk().isApproved();
        limits("50", "1.50", "50");
        RiskCheckResult weekly = riskManagementService.checkRisk();
        trace("⑤", "일일 한도 1.5=" + daily, "주간 한도 1.5=" + weekly.getReason());
        assertThat(daily).isTrue();
        assertThat(weekly.isApproved()).isFalse();
        assertThat(weekly.getReason()).contains("주간");
    }

    @Test @Order(6)
    @DisplayName("⑥ 반복 확정·늦은 정정·롤백 — DYNAMIC 손실은 확정 한 번, 정정은 차분만, 롤백은 합산 0")
    void repeatCorrectionRollback() throws Exception {
        limits("50", "50", "50");
        String c = coin();
        live(c, "100000");
        Long did = dynamic("REAL", "100000");
        // 롤백: 세션 갱신 실패 → 확정 롤백
        OrderEntity o = lossSell("DYNAMIC", did, c, false);
        jdbc.execute("CREATE OR REPLACE FUNCTION rl3_fail_session() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.id = " + did + " THEN RAISE EXCEPTION 'rl3: 세션 갱신 실패(시험)'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER rl3_fail BEFORE UPDATE ON dynamic_session FOR EACH ROW EXECUTE FUNCTION rl3_fail_session()");
        try { confirm("DYNAMIC"); } catch (RuntimeException ignored) { }
        jdbc.execute("DROP TRIGGER IF EXISTS rl3_fail ON dynamic_session");
        BigDecimal rolled = dynLoss();
        Instant appliedAfterRollback = orderRepository.findById(o.getId()).orElseThrow().getSellAppliedAt();
        confirm("DYNAMIC");
        BigDecimal once = dynLoss();
        confirm("DYNAMIC");
        orderExecutionEngine.checkOrderStatus(o.getId());
        BigDecimal twice = dynLoss();
        OrderSettlementService.Outcome corr = settlement.settle(o.getId(), done(o.getExchangeOrderId(), c, "18000", "10"));
        BigDecimal corrected = dynLoss();
        trace("⑥", "롤백 뒤=" + rolled + " 반영표시=" + appliedAfterRollback, "확정=" + once, "재확정=" + twice, "정정(" + corr + ")=" + corrected);
        assertThat(appliedAfterRollback).isNull();
        assertThat(rolled).isEqualByComparingTo("0");
        assertThat(once).isEqualByComparingTo("-2001");
        assertThat(twice).isEqualByComparingTo("-2001");
        assertThat(corrected).isEqualByComparingTo("-2002");
    }

    private BigDecimal dynLoss() {
        BigDecimal v = orderRepository.sumDynamicRealSessionRealizedLossSince(Instant.now().minus(1, ChronoUnit.DAYS));
        return v == null ? BigDecimal.ZERO : v;
    }

    @Test @Order(7)
    @DisplayName("⑦ DYN_PAPER 손실 제외 — 분자·분모 모두 REAL 만")
    void paperExcluded() throws Exception {
        String c = coin();
        live(c, "100000");
        Long pid = dynamic("PAPER", "100000");
        positionRepository.save(PositionEntity.builder()
                .coinPair(c).side("BUY").entryPrice(AVG).avgPrice(AVG).size(QTY).investedKrw(QTY.multiply(AVG))
                .realizedPnl(new BigDecimal("-5000")).status("CLOSED").closedAt(Instant.now())
                .sessionKind("DYN_PAPER").sessionId(pid).openedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
        RiskCheckResult r = riskManagementService.checkRisk();
        trace("⑦", "DYN_PAPER 손실 −5,000 → checkRisk=" + r.isApproved() + " DYN REAL 손실=" + dynLoss());
        assertThat(r.isApproved()).isTrue();
        assertThat(dynLoss()).isEqualByComparingTo("0");
    }

    @Test @Order(8)
    @DisplayName("⑧ 자본 추가·정지 판정 표 (관측 + 단언)")
    void capitalTable() throws Exception {
        String c = coin();
        Long lid = live(c, "100000");
        lossSell("LIVE", lid, c, true);                       // LIVE −2,001
        boolean a = riskManagementService.checkRisk().isApproved();      // 기존 2.00% > 1.5 → 거절
        Long d1 = dynamic("REAL", "100000");
        boolean b = riskManagementService.checkRisk().isApproved();      // DYN 자본 추가 — 기존 식 그대로 거절
        stopDynamic(d1);
        boolean cc = riskManagementService.checkRisk().isApproved();     // DYN 정지 — 거절
        // LIVE 손실을 창 밖으로, DYNAMIC 손실 사례
        jdbc.update("UPDATE \"order\" SET sell_applied_at = sell_applied_at - interval '400 days' WHERE sell_applied_at IS NOT NULL");
        Long d2 = dynamic("REAL", "100000");
        lossSell("DYNAMIC", d2, c, true);                     // DYN −2,001: DYN 2.00%, 전체 1.00%
        boolean d = riskManagementService.checkRisk().isApproved();      // 거절(DYN)
        Long d3 = dynamic("REAL", "100000");
        boolean e = riskManagementService.checkRisk().isApproved();      // DYN 자본 20만 → DYN 1.00%, 전체 0.67% → 승인(자체 자본 추가)
        stopDynamic(d2); stopDynamic(d3);
        RiskCheckResult f = riskManagementService.checkRisk();           // DYN 전부 정지 — 손실 남음 → 판정 불가 거절(더 엄격)
        trace("⑧", "LIVE손실·LIVE만=" + a, "+DYN자본=" + b, "DYN정지=" + cc, "DYN손실·DYN1개=" + d, "+DYN자본=" + e, "DYN전부정지=" + f.isApproved() + " " + f.getReason());
        assertThat(a).isFalse();
        assertThat(b).isFalse();
        assertThat(cc).isFalse();
        assertThat(d).isFalse();
        assertThat(e).isTrue();
        assertThat(f.isApproved()).isFalse();
        assertThat(f.getReason()).contains("판정 불가");
    }
}
