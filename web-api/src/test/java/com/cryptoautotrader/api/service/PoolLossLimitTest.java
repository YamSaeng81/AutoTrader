package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공유 풀 손실 한도 — 선행 조건 1번 (2026-10-14).
 *
 * <p>사전 등록(§6, 2026-10-06 개정): 분모는 <b>그 단계의 고정 투입 자본</b>, 측정은 <b>비용·미실현손익을 포함한 순자산
 * 변화</b>, 구간은 <b>롤링 24시간</b>. 그리고 세션을 멈추거나 다시 시작해도 한도 금액이 변하지 않아야 한다.</p>
 *
 * <p>🔴 시험 DB 는 다른 시험과 공유된다. 단계 이름을 시험마다 새로 만들어 기준 시각을 분리하고, 그 시각 이후에 연
 * 포지션만 평가 대상이 되게 한다(앞선 시험의 포지션은 기준 시각 전에 열렸다). 시세는 다른 시험이 쓰지 않는 코인
 * 이름으로 남긴다.</p>
 */
@DisplayName("공유 풀 손실 한도 — 고정 분모 · 순자산 · 24시간")
class PoolLossLimitTest extends IntegrationTestBase {

    private static final BigDecimal CAP = new BigDecimal("100000");

    @Autowired private PoolLossGuard guard;
    @Autowired private MarkPriceBook marks;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private RiskManagementService riskManagementService;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private PortfolioManager portfolioManager;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    /** 스냅숏은 시험이 정한다. */
    @MockBean private PortfolioSyncService portfolioSyncService;

    private String savedStage;
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private BigDecimal savedDaily;
    private BigDecimal savedCumulative;
    private Long riskConfigId;
    private String stage;
    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final List<Long> positions = new ArrayList<>();
    private final List<Long> orders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        RiskConfigEntity rc = riskManagementService.getRiskConfig();
        riskConfigId = rc.getId();
        savedDaily = rc.getMaxDailyLossPct();
        savedCumulative = rc.getMaxPortfolioDrawdownPct();
        // 사전 등록값으로 둔다 — 시험 단계는 대조하지 않지만 같은 숫자로 계산해야 금액이 등록값과 같다.
        setRiskConfig("3.0", "15.0");
        stage = "LOSS_" + UUID.randomUUID().toString().substring(0, 8);
        limits.setStage(stage);
        limits.setTotalCapKrw(CAP);
        guard.setEnabledSetting(true);
        portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));
    }

    @AfterEach
    void tearDown() {
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?, max_portfolio_drawdown_pct = ? WHERE id = ?",
                savedDaily, savedCumulative, riskConfigId);
        orders.forEach(id -> orderRepository.findById(id).ifPresent(orderRepository::delete));
        for (Long id : liveSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        for (Long id : dynamicSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
        positions.forEach(id -> positionRepository.findById(id).ifPresent(positionRepository::delete));
        jdbc.update("DELETE FROM pool_equity_snapshot WHERE stage = ?", stage);
        jdbc.update("DELETE FROM capital_stage_baseline WHERE stage = ?", stage);
        marks.clear();
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private void setRiskConfig(String daily, String cumulative) {
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?, max_portfolio_drawdown_pct = ? WHERE id = ?",
                new BigDecimal(daily), new BigDecimal(cumulative), riskConfigId);
    }

    private <T> T tx(Supplier<T> body) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t.execute(st -> body.get());
    }

    private Instant baseline() {
        return tx(() -> guard.ensureBaseline(Instant.now()));
    }

    private CapitalReservationService.Decision check(Instant now) {
        return tx(() -> guard.checkForEntry(now));
    }

    private Long liveSession(String status) {
        Long id = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-LOSSA").timeframe("H1")
                .initialCapital(new BigDecimal("20000")).availableKrw(new BigDecimal("20000"))
                .totalAssetKrw(new BigDecimal("20000"))
                .status(status).startedAt(Instant.now()).build()).getId();
        liveSessions.add(id);
        return id;
    }

    private PositionEntity position(Long sessionId, String coin, String status, String size, String avg) {
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair(coin).side("BUY")
                .entryPrice(new BigDecimal(avg)).avgPrice(new BigDecimal(avg))
                .size(new BigDecimal(size)).investedKrw(new BigDecimal("20000"))
                // 🔴 실현손익 열은 일부러 틀린 값으로 둔다 — 손실 한도는 이 열을 읽지 않는다.
                .realizedPnl(new BigDecimal("5000"))
                .status(status).sessionKind("LIVE").sessionId(sessionId)
                .openedAt(Instant.now().plusMillis(5)).build());
        positions.add(p.getId());
        return p;
    }

    private void order(PositionEntity p, String side, String funds, String fee, String qty) {
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair(p.getCoinPair()).side(side).orderType("MARKET")
                .quantity(new BigDecimal(qty)).filledQuantity(new BigDecimal(qty))
                .state("FILLED").sessionKind("LIVE").sessionId(p.getSessionId()).positionId(p.getId())
                .appliedExecutedFunds(funds == null ? null : new BigDecimal(funds))
                .appliedPaidFee(fee == null ? null : new BigDecimal(fee))
                .feeSource("REPORTED")
                .clientOrderId("loss-" + UUID.randomUUID())
                .build());
        orders.add(o.getId());
    }

    /** 순수 현금 손실 — 매수 금액만큼 나가고 수량 0 으로 닫힌 포지션. 순자산 변화 = −금액. */
    private void cashLoss(Long sessionId, String amount) {
        PositionEntity p = position(sessionId, "KRW-LOSSZ", "CLOSED", "0", "300");
        order(p, "BUY", amount, "0", "1");
    }

    private void snapshot(Instant at, String net) {
        jdbc.update("INSERT INTO pool_equity_snapshot (stage, taken_at, cash_flow_krw, holdings_krw, net_change_krw) "
                + "VALUES (?, ?, ?, 0, ?)", stage, Timestamp.from(at), new BigDecimal(net), new BigDecimal(net));
    }

    // ── 평가 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("순자산 변화에 매수·매도 수수료와 미실현손익이 들어간다 — 실현손익 열은 쓰지 않는다")
    void valuationIncludesCostsAndUnrealized() {
        Instant start = baseline();
        Long s = liveSession("RUNNING");
        // 보유 중: 300 에 30 개, 대금 9,000 + 수수료 4.5. 시세 280.
        PositionEntity held = position(s, "KRW-LOSSA", "OPEN", "30", "300");
        order(held, "BUY", "9000", "4.5", "30");
        marks.record("KRW-LOSSA", new BigDecimal("280"), Instant.now());

        PoolLossGuard.Valuation v = tx(() -> guard.valuate(start));
        // 현금 −9,004.5, 보유 30 × 280 × (1 − 0.0005) = 8,395.8 → −608.7
        assertThat(v.cashFlow()).isEqualByComparingTo("-9004.5");
        assertThat(v.holdings()).isEqualByComparingTo("8395.8");
        assertThat(v.net()).isEqualByComparingTo("-608.7");

        // 같은 가격에 팔아 닫으면 순자산 변화가 같다 — 매도 수수료가 미실현 평가에 이미 들어 있었다.
        jdbc.update("UPDATE position SET size = 0, status = 'CLOSED' WHERE id = ?", held.getId());
        order(held, "SELL", "8400", "4.2", "30");
        PoolLossGuard.Valuation closed = tx(() -> guard.valuate(start));
        assertThat(closed.holdings()).isEqualByComparingTo("0");
        assertThat(closed.net()).isEqualByComparingTo("-608.7");
    }

    @Test
    @DisplayName("시세 기록이 없으면 평가 루프가 남긴 평가손익으로 되돌린다(재기동 직후)")
    void markFallsBackToEvaluationLoop() {
        Instant start = baseline();
        Long s = liveSession("RUNNING");
        PositionEntity held = position(s, "KRW-LOSSA", "OPEN", "30", "300");
        order(held, "BUY", "9000", "4.5", "30");
        jdbc.update("UPDATE position SET unrealized_pnl = -600 WHERE id = ?", held.getId());   // 루프가 본 시세 280

        PoolLossGuard.Valuation v = tx(() -> guard.valuate(start));
        assertThat(v.holdings()).isEqualByComparingTo("8395.8");
        assertThat(v.marks()).contains("평가루프");
    }

    @Test
    @DisplayName("대체 가격(평가 루프 값·평균단가·낡은 시세)이 섞이면 값은 계산돼도 풀 전체 신규 진입을 막고 기준 기록도 남기지 않는다 (리뷰 8회차)")
    void fallbackMarkBlocksEntry() {
        Instant start = baseline();
        Long s = liveSession("RUNNING");
        PositionEntity held = position(s, "KRW-LOSSA", "OPEN", "30", "300");
        order(held, "BUY", "9000", "4.5", "30");
        Instant now = Instant.now();

        // ① 시세 기록 없음 → 평균단가로 계산은 되지만 판정 불가
        PoolLossGuard.Valuation v = tx(() -> guard.valuate(start, now));
        assertThat(v).isNotNull();
        assertThat(v.observed()).isFalse();
        assertThat(v.marks()).contains("[대체]").doesNotContain("@");   // 관측 가격이면 "출처@관측 시각" 이 붙는다
        CapitalReservationService.Decision none = check(now);
        assertThat(none.approved()).isFalse();
        assertThat(none.code()).isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
        assertThat(none.message()).contains("대체 가격").contains("KRW-LOSSA");
        // 대체 가격 평가는 24시간 기준으로 남기지 않는다
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM pool_equity_snapshot WHERE stage = ?", Integer.class, stage);
        assertThat(tx(() -> guard.recordSnapshotAt(start, now))).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_equity_snapshot WHERE stage = ?", Integer.class, stage)).isEqualTo(before);

        // ② 평가 루프 값도 대체다
        jdbc.update("UPDATE position SET unrealized_pnl = -600 WHERE id = ?", held.getId());
        assertThat(check(now).code()).isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);

        // ③ 실시간 시세라도 기준(120초)을 넘으면 대체다 — 포지션별 한도와 같은 기준
        marks.record("KRW-LOSSA", new BigDecimal("280"), now.minusSeconds(121));
        CapitalReservationService.Decision stale = check(now);
        assertThat(stale.approved()).isFalse();
        assertThat(stale.code()).isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);

        // ④ 기준 이내의 실시간 시세면 판정한다 — 한도 안이므로 승인, 기준 기록도 남는다
        marks.record("KRW-LOSSA", new BigDecimal("280"), now.minusSeconds(119));
        CapitalReservationService.Decision ok = check(now);
        assertThat(ok.approved()).as(ok.message()).isTrue();
        assertThat(tx(() -> guard.recordSnapshotAt(start, now))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pool_equity_snapshot WHERE stage = ?", Integer.class, stage)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("반영값이 없는 체결 주문이 있으면 계산하지 않고 진입을 막는다")
    void unappliedFillBlocks() {
        baseline();
        Long s = liveSession("RUNNING");
        PositionEntity p = position(s, "KRW-LOSSA", "OPEN", "30", "300");
        order(p, "BUY", null, null, "30");

        CapitalReservationService.Decision d = check(Instant.now());
        assertThat(d.approved()).isFalse();
        assertThat(d.code()).isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
    }

    // ── 한도 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("누적: 총 자본 100,000 의 15% — −15,000 에서 막고 −14,999.99 는 통과")
    void cumulativeBoundary() {
        Instant start = baseline();
        Long s = liveSession("RUNNING");
        Instant later = start.plus(30, ChronoUnit.HOURS);
        // 24시간 전 기록을 현재와 가깝게 둔다 — 24시간 한도와 섞이지 않게.
        snapshot(later.minus(24, ChronoUnit.HOURS), "-14000");

        cashLoss(s, "14999.99");
        CapitalReservationService.Decision ok = check(later);
        assertThat(ok.approved()).as(ok.message()).isTrue();

        cashLoss(s, "0.01");
        CapitalReservationService.Decision blocked = check(later);
        assertThat(blocked.approved()).isFalse();
        assertThat(blocked.code()).isEqualTo(PoolLossGuard.POOL_LOSS_LIMIT);
        assertThat(blocked.message()).startsWith("누적 손실 한도 도달").contains("한도 누적 −15000.00").contains("총 자본 100000.00 고정");
    }

    @Test
    @DisplayName("24시간: 지금 − 24시간 전 ≤ −3,000 에서 막는다 (누적은 한도 안)")
    void rolling24hBoundary() {
        Instant start = baseline();
        Long s = liveSession("RUNNING");
        Instant later = start.plus(30, ChronoUnit.HOURS);
        snapshot(later.minus(24, ChronoUnit.HOURS).minus(1, ChronoUnit.MINUTES), "-1000");

        cashLoss(s, "3999.99");   // −3,999.99 − (−1,000) = −2,999.99
        assertThat(check(later).approved()).isTrue();

        cashLoss(s, "0.01");      // −3,000
        CapitalReservationService.Decision d = check(later);
        assertThat(d.approved()).isFalse();
        assertThat(d.code()).isEqualTo(PoolLossGuard.POOL_LOSS_LIMIT);
        assertThat(d.message()).startsWith("최근 24시간 순자산 감소 한도 도달").contains("한도 누적 −15000.00 · 24시간 −3000.00");
    }

    @Test
    @DisplayName("단계 시작 후 24시간 미만이면 단계 시작(0)과 비교한다")
    void rollingBeforeOneDayUsesStageStart() {
        Instant start = baseline();
        Long s = liveSession("RUNNING");
        cashLoss(s, "2999.99");
        assertThat(check(start.plus(1, ChronoUnit.HOURS)).approved()).isTrue();
        cashLoss(s, "0.01");
        CapitalReservationService.Decision d = check(start.plus(1, ChronoUnit.HOURS));
        assertThat(d.approved()).isFalse();
        assertThat(d.message()).startsWith("최근 24시간").contains("기준 0.00, 단계 시작");
    }

    @Test
    @DisplayName("24시간 전 기록에 공백이 있으면 더 엄격한 값으로 대신하고, 아무 기록도 없으면 막는다")
    void snapshotGap() {
        Instant start = baseline();
        Long s = liveSession("RUNNING");
        Instant later = start.plus(30, ChronoUnit.HOURS);
        Instant t = later.minus(24, ChronoUnit.HOURS);
        cashLoss(s, "3000");

        // 기록이 하나도 없다 — 24시간 전 값을 모른다.
        CapitalReservationService.Decision unknown = check(later);
        assertThat(unknown.approved()).isFalse();
        assertThat(unknown.code()).isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);

        // 기준 시각 2시간 전 −5,000(허용 10분 밖)과 1시간 뒤 0. 정확한 기준(−5,000)이면 +2,000 이라 통과하지만, 공백이므로
        // 구간 최댓값 0 을 기준으로 −3,000 → 막는다.
        snapshot(t.minus(2, ChronoUnit.HOURS), "-5000");
        snapshot(t.plus(1, ChronoUnit.HOURS), "0");
        CapitalReservationService.Decision strict = check(later);
        assertThat(strict.approved()).isFalse();
        assertThat(strict.message()).contains("공백 대체");

        // 기준 시각 5분 전 기록이 있으면 그 값을 쓴다 — −3,000 − (−5,000) = +2,000.
        snapshot(t.minus(5, ChronoUnit.MINUTES), "-5000");
        assertThat(check(later).approved()).isTrue();
    }

    @Test
    @DisplayName("세션 정지·재시작에도 한도 금액과 순자산 변화가 변하지 않는다 (종전 분모는 변한다)")
    void invariantUnderSessionStopRestart() {
        Instant start = baseline();
        Long a = liveSession("RUNNING");
        Long b = liveSession("RUNNING");
        Long c = liveSession("RUNNING");
        PositionEntity held = position(a, "KRW-LOSSA", "OPEN", "30", "300");
        order(held, "BUY", "9000", "4.5", "30");
        marks.record("KRW-LOSSA", new BigDecimal("280"), Instant.now());
        cashLoss(b, "1000");

        RiskConfigEntity rc = riskManagementService.getRiskConfig();
        Object legacy = AopTestUtils.getTargetObject(riskManagementService);
        record Seen(PoolLossGuard.Limits limits, BigDecimal net, BigDecimal legacyDenominator) {}
        Supplier<Seen> seen = () -> new Seen(guard.limitsFor(riskManagementService.getRiskConfig()),
                tx(() -> guard.valuate(start)).net(),
                ReflectionTestUtils.invokeMethod(legacy, "resolvePortfolioLimit", rc));

        Seen running = seen.get();
        jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE id IN (?, ?)", a, b);
        Seen stopped = seen.get();
        jdbc.update("UPDATE live_trading_session SET status = 'RUNNING' WHERE id IN (?, ?)", a, b);
        Seen restarted = seen.get();

        assertThat(running.limits()).isEqualTo(new PoolLossGuard.Limits(new BigDecimal("15000.00"), new BigDecimal("3000.00")));
        assertThat(stopped.limits()).isEqualTo(running.limits());
        assertThat(restarted.limits()).isEqualTo(running.limits());
        assertThat(running.net()).isEqualByComparingTo("-1608.7");
        assertThat(stopped.net()).isEqualByComparingTo(running.net());
        assertThat(restarted.net()).isEqualByComparingTo(running.net());
        // 종전 판정의 분모는 RUNNING 세션 합이라 정지에 따라 움직인다 — 이번 수정이 대체하는 것이 이것이다.
        assertThat(stopped.legacyDenominator()).isLessThan(running.legacyDenominator());
        assertThat(restarted.legacyDenominator()).isEqualByComparingTo(running.legacyDenominator());
        assertThat(c).isNotNull();

        // 기준 시각은 다시 만들어지지 않는다 — 재배정·프로세스 재시작에도 같은 범위다.
        assertThat(tx(() -> guard.ensureBaseline(Instant.now().plus(1, ChronoUnit.DAYS)))).isEqualTo(start);
    }

    // ── risk_config 대조 ─────────────────────────────────────────────

    @Test
    @DisplayName("등록 단계는 risk_config 가 사전 등록값(24시간 3.0 · 누적 15.0)과 정확히 같아야 한다")
    void riskConfigComparison() {
        assertThat(PoolLossGuard.riskConfigProblem(rc("3.0", "15.0"), true)).isNull();
        assertThat(PoolLossGuard.riskConfigProblem(rc("3.00", "15.00"), true)).isNull();
        assertThat(PoolLossGuard.riskConfigProblem(rc("1.5", "15.0"), true))
                .contains("max_daily_loss_pct=1.5").contains("사전 등록값은 최근 24시간 3.0 · 단계 누적 15.0");
        assertThat(PoolLossGuard.riskConfigProblem(rc("3.0", "20.0"), true)).isNotNull();
        assertThat(PoolLossGuard.riskConfigProblem(rc(null, "15.0"), true)).contains("한도 값이 없다");
        // 시험 단계는 값이 있기만 하면 된다.
        assertThat(PoolLossGuard.riskConfigProblem(rc("1.5", "15.0"), false)).isNull();
        assertThat(PoolLossGuard.riskConfigProblem(rc("0", "15.0"), false)).isNotNull();
    }

    @Test
    @DisplayName("시험 단계에서도 risk_config 한도 값이 없으면 진입을 막는다")
    void missingRiskValueBlocks() {
        baseline();
        jdbc.update("UPDATE risk_config SET max_daily_loss_pct = NULL WHERE id = ?", riskConfigId);
        CapitalReservationService.Decision d = check(Instant.now());
        assertThat(d.approved()).isFalse();
        assertThat(d.code()).isEqualTo(PoolLossGuard.RISK_CONFIG_MISMATCH);
    }

    private static RiskConfigEntity rc(String daily, String cumulative) {
        RiskConfigEntity e = new RiskConfigEntity();
        e.setMaxDailyLossPct(daily == null ? null : new BigDecimal(daily));
        e.setMaxPortfolioDrawdownPct(cumulative == null ? null : new BigDecimal(cumulative));
        return e;
    }

    // ── 운영 진입점 — LIVE·DYNAMIC 이 같은 자리에서 막힌다 ────────────────

    @Test
    @DisplayName("한도 도달 시 LIVE 예약과 DYNAMIC 매수가 같은 사유로 거절되고 흔적이 없다")
    void bothKindsBlockedAtReservation() {
        // 등록 풀 검사를 통과하도록 총 자본을 앞선 시험이 남긴 노출 위에 둔다. 손실 한도 금액은 그 총 자본에서 나온다.
        BigDecimal exposure = positionRepository.sumPoolExposureKrw(-1L);
        BigDecimal cap = exposure.add(CAP);
        limits.setTotalCapKrw(cap);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        BigDecimal cumLimit = cap.multiply(new BigDecimal("0.15"));

        baseline();
        Long live = liveSession("RUNNING");
        cashLoss(live, cumLimit.toPlainString());

        long ordersBefore = orderRepository.count();
        String liveCode = liveReserve(live);
        assertThat(liveCode).isEqualTo(PoolLossGuard.POOL_LOSS_LIMIT);
        assertThat(liveRepo.findById(live).orElseThrow().getAvailableKrw()).isEqualByComparingTo("20000");

        Long dyn = dynamicSession();
        String r = tx(() -> dynamicTradingService.executeBuy(dynamicRepo.findById(dyn).orElseThrow(), "KRW-LOSSB",
                candles(), StrategySignal.buy(new BigDecimal("80"), "손실 한도 시험"), BigDecimal.ONE));
        assertThat(r).contains("공유 풀 예약 거절(" + PoolLossGuard.POOL_LOSS_LIMIT + ")");
        assertThat(dynamicRepo.findById(dyn).orElseThrow().getAvailableKrw()).isEqualByComparingTo("10000");
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(positionRepository.findBySessionKindAndSessionId("DYNAMIC", dyn))
                .allSatisfy(p -> assertThat(p.getStatus()).isEqualTo("CLOSED"));

        // 손실이 한도 안으로 돌아오면(예: 시험상 손실 주문 삭제) 다시 승인한다 — 차단은 상태가 아니라 판정이다.
        orders.forEach(id -> orderRepository.findById(id).ifPresent(orderRepository::delete));
        orders.clear();
        assertThat(liveReserve(live)).isNull();
    }

    @Test
    @DisplayName("단계의 첫 매수가 기준 시각을 만들어도 그 매수의 포지션이 평가 대상에 든다 (S14 에서 발견)")
    void firstBuyOfStageIsInScope() {
        BigDecimal exposure = positionRepository.sumPoolExposureKrw(-1L);
        limits.setTotalCapKrw(exposure.add(CAP));
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        Long live = liveSession("RUNNING");
        // 기준 시각을 미리 만들지 않는다 — 운영에서 세션 배정 없이 첫 매수가 오는 경우와 같다.
        assertThat(liveReserve(live)).isNull();
        Instant start = tx(() -> guard.baseline());
        PositionEntity p = positionRepository.findBySessionKindAndSessionId("LIVE", live).get(0);
        assertThat(p.getOpenedAt()).isAfterOrEqualTo(start);

        // 체결을 반영한 것처럼 만든다 — 300 에 10 개, 대금 3,000 + 수수료 1.5. 시세 기록 없음 → 평균단가.
        Long orderId = orders.get(orders.size() - 1);
        jdbc.update("UPDATE position SET size = 10 WHERE id = ?", p.getId());
        jdbc.update("UPDATE \"order\" SET state = 'FILLED', filled_quantity = 10, applied_executed_funds = 3000, "
                + "applied_paid_fee = 1.5 WHERE id = ?", orderId);
        PoolLossGuard.Valuation v = tx(() -> guard.valuate(start));
        // 현금 −3,001.5 + 보유 10 × 300 × 0.9995 = 2,998.5 → −3.0. 🔴 수정 전에는 포지션이 빠져 0 이었다.
        assertThat(v.openPositions()).isEqualTo(1);
        assertThat(v.net()).isEqualByComparingTo("-3.0");
    }

    private String liveReserve(Long sessionId) {
        try {
            Long id = tx(() -> {
                PositionEntity pos = positionRepository.save(PositionEntity.builder()
                        .coinPair("KRW-LOSSA").side("BUY")
                        .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                        .size(BigDecimal.ZERO).investedKrw(new BigDecimal("8000"))
                        .status("OPEN").sessionKind("LIVE").sessionId(sessionId)
                        .openedAt(Instant.now()).build());
                OrderRequest req = new OrderRequest();
                req.setCoinPair("KRW-LOSSA");
                req.setSide("BUY");
                req.setOrderType("MARKET");
                req.setQuantity(new BigDecimal("8000"));
                req.setReason("손실 한도 시험");
                req.setSignalPrice(new BigDecimal("300"));
                req.setSessionId(sessionId);
                req.setSessionKind("LIVE");
                req.setPositionId(pos.getId());
                return orderExecutionEngine.reserveAndPrepareIntent(req);
            });
            orders.add(id);
            return null;
        } catch (OrderExecutionEngine.ReservationRejected rejected) {
            return rejected.getCode();
        }
    }

    private Long dynamicSession() {
        BigDecimal capital = new BigDecimal("10000");
        Long id = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(capital).availableKrw(capital).totalAssetKrw(capital)
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
        dynamicSessions.add(id);
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

    // ── 보유 코인 시세 보강 (리뷰 8회차 후속) — 차단이 작동하고, 정상 데이터가 공급되면 진입이 가능하다 ─────────────

    @Autowired private HeldMarkRefresher refresher;

    private static java.util.Map<String, Object> ticker(String coin, String price, Instant tradeAt) {
        return java.util.Map.of("market", coin, "trade_price", new BigDecimal(price), "trade_timestamp", tradeAt.toEpochMilli());
    }

    /** 호가 — 매수호가 단계 "가격:잔량" 을 높은 순으로. */
    private static java.util.Map<String, Object> orderbook(String coin, Instant at, String... bids) {
        List<java.util.Map<String, Object>> units = new ArrayList<>();
        for (String b : bids) {
            String[] pq = b.split(":");
            units.add(java.util.Map.of("bid_price", new BigDecimal(pq[0]), "bid_size", new BigDecimal(pq[1]),
                    "ask_price", new BigDecimal(pq[0]).add(BigDecimal.ONE), "ask_size", BigDecimal.ONE));
        }
        return java.util.Map.of("market", coin, "timestamp", at.toEpochMilli(), "orderbook_units", units);
    }

    /** 시세 REST 대역을 꽂고 본문을 돌린 뒤 되돌린다. */
    private void withRest(com.cryptoautotrader.exchange.upbit.UpbitRestClient rest, Runnable body) {
        HeldMarkRefresher target = AopTestUtils.getUltimateTargetObject(refresher);
        Object saved = ReflectionTestUtils.getField(target, "upbitRestClient");
        ReflectionTestUtils.setField(target, "upbitRestClient", rest);
        try {
            body.run();
        } finally {
            ReflectionTestUtils.setField(target, "upbitRestClient", saved);
            ReflectionTestUtils.setField(target, "failing", new java.util.concurrent.atomic.AtomicReference<String>());
        }
    }

    private static void stub(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("웹소켓은 살아 있지만 체결이 조용한 보유: 낡은 체결가를 다시 받아도 신선하다고 보지 않는다. 호가 깊이가 보유량보다 얕으면 차단 유지, "
            + "충분하면 보유량을 매수호가에 채운 평균 가격으로 관측해 승인")
    void quietHoldingIsObservedByBookDepth() throws Exception {
        Instant start = baseline();
        Long s = liveSession("RUNNING");
        PositionEntity held = position(s, "KRW-QUIET", "OPEN", "30", "300");
        order(held, "BUY", "9000", "4.5", "30");
        Instant now = Instant.now();
        Instant oldTrade = now.minusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        // 연결은 살아 있다 — 다른 코인 틱은 지금 온다. 보유 코인의 마지막 웹소켓 틱은 10분 전 체결이다(지금 받았어도).
        marks.onPrice(new RealtimePriceEvent("KRW-BTC", new BigDecimal("100000000"), now, RealtimePriceEvent.WS_TRADE));
        marks.onPrice(new RealtimePriceEvent("KRW-QUIET", new BigDecimal("290"), oldTrade, RealtimePriceEvent.WS_TRADE));
        assertThat(marks.get("KRW-QUIET").receivedAt()).isAfterOrEqualTo(now);
        assertThat(check(now).code()).as("받은 시각이 아니라 체결 시각으로 판단 — 차단").isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);

        var rest = org.mockito.Mockito.mock(com.cryptoautotrader.exchange.upbit.UpbitRestClient.class);
        // REST 시세도 같은 10분 전 체결가를 준다. 호가는 아직 실패.
        org.mockito.Mockito.when(rest.getTicker(org.mockito.ArgumentMatchers.contains("KRW-QUIET")))
                .thenReturn(List.of(ticker("KRW-QUIET", "290", oldTrade)));
        org.mockito.Mockito.when(rest.getOrderbook("KRW-QUIET")).thenThrow(new RuntimeException("호가 조회 실패(시험)"));
        withRest(rest, () -> {
            HeldMarkRefresher.Result r = refresher.refreshNow(now);
            assertThat(r.targets()).contains("KRW-QUIET");
            assertThat(r.byTrade()).doesNotContain("KRW-QUIET");
            assertThat(r.unobserved()).contains("KRW-QUIET");
            assertThat(marks.get("KRW-QUIET").at()).as("재조회가 체결 시각을 바꾸지 않는다").isEqualTo(oldTrade);
            assertThat(marks.freshPrice("KRW-QUIET", now)).isNull();
            assertThat(check(now).code()).as("차단 유지").isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
            assertThat(refresher.failingSince()).contains("KRW-QUIET");

            // 호가가 오지만 보이는 매수 잔량(10 + 10 = 20)이 보유량 30 보다 적다 — 최우선 매수호가 289 만으로 평가하지 않는다
            stub(() -> org.mockito.Mockito.doReturn(List.of(orderbook("KRW-QUIET", now, "289:10", "288:10"))).when(rest).getOrderbook("KRW-QUIET"));
            HeldMarkRefresher.Result shallow = refresher.refreshNow(now);
            assertThat(shallow.byBook()).doesNotContain("KRW-QUIET");
            assertThat(shallow.unobserved()).contains("KRW-QUIET");
            assertThat(shallow.error()).contains("KRW-QUIET 호가 깊이 부족(보이는 매수 잔량 20 < 보유 30");
            assertThat(check(now).code()).as("깊이 부족 — 판정 불가, 차단 유지").isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);

            // 깊이가 충분하면 — 289×10 + 288×10 + 280×10 = 8,570 → 평균 285.66666666(내림)
            stub(() -> org.mockito.Mockito.doReturn(List.of(orderbook("KRW-QUIET", now, "289:10", "288:10", "280:20"))).when(rest).getOrderbook("KRW-QUIET"));
            HeldMarkRefresher.Result r2 = refresher.refreshNow(now);
            assertThat(r2.byBook()).contains("KRW-QUIET");
            assertThat(marks.get("KRW-QUIET").source()).isEqualTo(MarkPriceBook.REST_ORDERBOOK_DEPTH);
            assertThat(marks.get("KRW-QUIET").price()).isEqualByComparingTo("285.66666666");
            CapitalReservationService.Decision ok = check(now);
            assertThat(ok.approved()).as(ok.message()).isTrue();
            assertThat(ok.message()).contains("KRW-QUIET=285.66666666(REST_ORDERBOOK_DEPTH@");
            // 보유 평가 = 30 × 285.66666666 × 0.9995 — 마지막 체결가 290 도, 최우선 매수호가 289 도 아니다
            BigDecimal expected = new BigDecimal("30").multiply(new BigDecimal("285.66666666")).multiply(new BigDecimal("0.9995"));
            assertThat(tx(() -> guard.valuate(start, now)).holdings()).isEqualByComparingTo(expected.setScale(8, java.math.RoundingMode.HALF_UP));
            assertThat(refresher.failingSince()).as("회복").isNull();

            // 뒤이어 온 낡은 체결가는 더 최근의 호가 관측을 덮지 않는다
            marks.onPrice(new RealtimePriceEvent("KRW-QUIET", new BigDecimal("290"), oldTrade, RealtimePriceEvent.REST_TRADE));
            assertThat(marks.get("KRW-QUIET").source()).isEqualTo(MarkPriceBook.REST_ORDERBOOK_DEPTH);
        });
    }

    @Test
    @DisplayName("최근 체결이 있으면 시세 조회의 체결가로 충분하다 — 호가를 부르지 않는다. 호가 시각이 낡았으면 인정하지 않는다")
    void recentTradeSufficesAndStaleBookIsRejected() throws Exception {
        baseline();
        Long s = liveSession("RUNNING");
        position(s, "KRW-ACTIVE", "OPEN", "10", "300");
        position(s, "KRW-STALEBOOK", "OPEN", "10", "300");
        Instant now = Instant.now();
        var rest = org.mockito.Mockito.mock(com.cryptoautotrader.exchange.upbit.UpbitRestClient.class);
        org.mockito.Mockito.when(rest.getTicker(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(
                ticker("KRW-ACTIVE", "301", now.minusSeconds(5)), ticker("KRW-STALEBOOK", "300", now.minusSeconds(900))));
        org.mockito.Mockito.when(rest.getOrderbook("KRW-STALEBOOK")).thenReturn(List.of(orderbook("KRW-STALEBOOK", now.minusSeconds(300), "299:100")));
        withRest(rest, () -> {
            HeldMarkRefresher.Result r = refresher.refreshNow(now);
            assertThat(r.byTrade()).contains("KRW-ACTIVE");
            assertThat(r.unobserved()).contains("KRW-STALEBOOK").doesNotContain("KRW-ACTIVE");
            assertThat(r.error()).contains("KRW-STALEBOOK 호가 시각 낡음");
            assertThat(marks.get("KRW-ACTIVE").source()).isEqualTo(MarkPriceBook.REST_TRADE);
        });
        org.mockito.Mockito.verify(rest, org.mockito.Mockito.never()).getOrderbook("KRW-ACTIVE");
        assertThat(check(now).code()).as("한 보유라도 관측 가격이 없으면 풀 전체 차단").isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
    }

    @Test
    @DisplayName("재시작 직후(빈 장부)·REST 실패: 차단과 경보 한 번, 복구되면 보강이 장부를 채워 승인")
    void restartAndRestFailureRecovery() throws Exception {
        baseline();
        Long s = liveSession("RUNNING");
        PositionEntity held = position(s, "KRW-RESTART", "OPEN", "30", "300");
        order(held, "BUY", "9000", "4.5", "30");
        marks.clear();   // 재시작 직후 — 장부가 비었다
        Instant now = Instant.now();
        assertThat(check(now).code()).isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);

        TelegramNotificationService tg = org.mockito.Mockito.mock(TelegramNotificationService.class);
        HeldMarkRefresher target = AopTestUtils.getUltimateTargetObject(refresher);
        Object savedTg = ReflectionTestUtils.getField(target, "telegram");
        ReflectionTestUtils.setField(target, "telegram", tg);
        var rest = org.mockito.Mockito.mock(com.cryptoautotrader.exchange.upbit.UpbitRestClient.class);
        try {
            org.mockito.Mockito.when(rest.getTicker(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("503(시험)"));
            org.mockito.Mockito.when(rest.getOrderbook(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("503(시험)"));
            withRest(rest, () -> {
                refresher.refreshNow(now);
                refresher.refreshNow(now.plusSeconds(15));
                assertThat(check(now).code()).as("REST 실패 — 차단 유지").isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
                org.mockito.Mockito.verify(tg, org.mockito.Mockito.times(1)).sendCustomNotification(
                        org.mockito.ArgumentMatchers.argThat(m -> m.contains("KRW-RESTART") && m.contains("시세 공급 실패")));

                // 복구 — REST 가 다시 응답한다(최근 체결)
                Instant later = now.plusSeconds(30);
                stub(() -> org.mockito.Mockito.doReturn(List.of(ticker("KRW-RESTART", "305", later.minusSeconds(3))))
                        .when(rest).getTicker(org.mockito.ArgumentMatchers.anyString()));
                HeldMarkRefresher.Result r = refresher.refreshNow(later);
                assertThat(r.byTrade()).contains("KRW-RESTART");
                CapitalReservationService.Decision ok = check(later);
                assertThat(ok.approved()).as(ok.message()).isTrue();
                assertThat(ok.message()).contains("KRW-RESTART=305(REST_TRADE@");
            });
        } finally {
            ReflectionTestUtils.setField(target, "telegram", savedTg);
        }
    }

    @Test
    @DisplayName("호가 채우기 — 매수호가를 위에서부터 보유량만큼 채운 평균(내림), 잔량이 모자라거나 잔량 정보가 없으면 가격 없음")
    void bookFillAveragesDownTheBook() {
        Instant at = Instant.now();
        java.util.function.Function<String[], List<java.util.Map<String, Object>>> units = bids -> {
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> u = (List<java.util.Map<String, Object>>) orderbook("X", at, bids).get("orderbook_units");
            return u;
        };
        assertThat(HeldMarkRefresher.fill(new BigDecimal("5"), units.apply(new String[]{"100:10", "90:10"}), at).price()).isEqualByComparingTo("100");
        assertThat(HeldMarkRefresher.fill(new BigDecimal("15"), units.apply(new String[]{"100:10", "90:10"}), at).price())
                .isEqualByComparingTo("96.66666666");
        var shallow = HeldMarkRefresher.fill(new BigDecimal("25"), units.apply(new String[]{"100:10", "90:10"}), at);
        assertThat(shallow.price()).isNull();
        assertThat(shallow.depth()).isEqualByComparingTo("20");
        // 잔량 정보가 없는 단계는 깊이로 세지 않는다
        List<java.util.Map<String, Object>> noSize = List.of(java.util.Map.of("bid_price", new BigDecimal("100")));
        assertThat(HeldMarkRefresher.fill(new BigDecimal("1"), noSize, at).price()).isNull();
    }

    @Autowired private LiveTradingService liveTradingService;

    @Test
    @DisplayName("기존 시세 경로도 체결 시각을 싣는다 — 웹소켓 틱(ttms)·LIVE·DYNAMIC REST 강제 갱신(trade_timestamp, 없으면 신선하지 않음)")
    void existingPricePathsCarryTradeTime() throws Exception {
        Instant now = Instant.now();
        Instant oldTrade = now.minusSeconds(600).truncatedTo(ChronoUnit.MILLIS);

        // 웹소켓 틱 — 10분 전 체결(지금 받음) → 신선하지 않다
        LiveTradingService live = AopTestUtils.getUltimateTargetObject(liveTradingService);
        live.onWsTick(com.cryptoautotrader.exchange.upbit.dto.TickerData.builder().code("KRW-WSOLD")
                .tradePrice(new BigDecimal("100")).timestamp(now).tradeTimestamp(oldTrade).build());
        assertThat(marks.get("KRW-WSOLD").at()).isEqualTo(oldTrade);
        assertThat(marks.get("KRW-WSOLD").source()).isEqualTo(MarkPriceBook.WS_TRADE);
        assertThat(marks.freshPrice("KRW-WSOLD", now)).isNull();
        live.onWsTick(com.cryptoautotrader.exchange.upbit.dto.TickerData.builder().code("KRW-WSNEW")
                .tradePrice(new BigDecimal("100")).timestamp(now).tradeTimestamp(now).build());
        assertThat(marks.freshPrice("KRW-WSNEW", now)).isEqualByComparingTo("100");

        // REST 강제 갱신 — LIVE·DYNAMIC
        var rest = org.mockito.Mockito.mock(com.cryptoautotrader.exchange.upbit.UpbitRestClient.class);
        org.mockito.Mockito.when(rest.getTicker("KRW-RESTOLD")).thenReturn(List.of(ticker("KRW-RESTOLD", "200", oldTrade)));
        org.mockito.Mockito.when(rest.getTicker("KRW-NOTS")).thenReturn(List.of(java.util.Map.of("market", "KRW-NOTS", "trade_price", 50)));
        DynamicTradingService dyn = AopTestUtils.getUltimateTargetObject(dynamicTradingService);
        Object savedLive = ReflectionTestUtils.getField(live, "upbitRestClient");
        Object savedDyn = ReflectionTestUtils.getField(dyn, "upbitRestClient");
        ReflectionTestUtils.setField(live, "upbitRestClient", rest);
        ReflectionTestUtils.setField(dyn, "upbitRestClient", rest);
        try {
            assertThat(live.forceRefreshPrice("KRW-RESTOLD")).isTrue();
            assertThat(marks.get("KRW-RESTOLD").at()).as("LIVE: 체결 시각").isEqualTo(oldTrade);
            assertThat(marks.freshPrice("KRW-RESTOLD", now)).isNull();
            marks.remove("KRW-RESTOLD");
            assertThat(dyn.forceRefreshPrice("KRW-RESTOLD")).isTrue();
            assertThat(marks.get("KRW-RESTOLD").at()).as("DYNAMIC: 체결 시각").isEqualTo(oldTrade);
            assertThat(marks.get("KRW-RESTOLD").source()).isEqualTo(MarkPriceBook.REST_TRADE);
            // 체결 시각이 없는 응답 — 받은 시각으로 메우지 않는다
            assertThat(live.forceRefreshPrice("KRW-NOTS")).isTrue();
            assertThat(marks.get("KRW-NOTS").at()).isEqualTo(Instant.EPOCH);
            assertThat(marks.freshPrice("KRW-NOTS", now)).isNull();
        } finally {
            ReflectionTestUtils.setField(live, "upbitRestClient", savedLive);
            ReflectionTestUtils.setField(dyn, "upbitRestClient", savedDyn);
        }
    }
}
