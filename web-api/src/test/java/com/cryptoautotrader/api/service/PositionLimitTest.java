package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
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
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포지션별 한도 — 선행 조건 1번 1-g (2026-10-15).
 *
 * <p>사전 등록(§6): 공유 풀에서 {@code CAPITAL_LOSS} ≤ −15% 의 분모는 <b>진입 배정액</b>, {@code MAX_DRAWDOWN} ≤ −20% 의 분모는
 * <b>포지션 고점 평가액</b>. 리뷰 5회차: 부분 청산·추가 정산·프로세스 재시작에서도 분모와 고점이 의도대로 유지되는지, 임계 도달 시
 * 신규 진입 차단과 정지·정리로 연결되는지.</p>
 *
 * <p>🔴 시험 DB 는 공유된다. 판정은 모든 실자금 열린 포지션을 보므로 결과는 이 시험이 만든 포지션 id 로 거르고, 시험 중에 생긴 도달
 * 기록은 전부 지운다. 시세는 다른 시험이 쓰지 않는 코인 이름으로 남긴다.</p>
 */
@DisplayName("포지션별 한도 — 진입 배정액 · 포지션 고점 · 차단 · 정지")
class PositionLimitTest extends IntegrationTestBase {

    @Autowired private PositionLimitGuard guard;
    @Autowired private PositionLimitMonitor monitor;
    @Autowired private StrategyKillCriteriaService killCriteriaService;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private MarkPriceBook marks;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private OrderSettlementService settlementService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    @MockBean private UpbitOrderClient upbitOrderClient;

    private Instant testStart;
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        testStart = Instant.now().minusSeconds(1);
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        monitor.setAutoStop(false);
    }

    @AfterEach
    void tearDown() {
        monitor.setAutoStop(false);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
        for (Long id : liveSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(p -> {
                jdbc.update("DELETE FROM position_limit_breach WHERE position_id = ?", p.getId());
                positionRepository.delete(p);
            });
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        for (Long id : dynamicSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(p -> {
                jdbc.update("DELETE FROM position_limit_breach WHERE position_id = ?", p.getId());
                positionRepository.delete(p);
            });
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
        // 공유 DB 의 다른 포지션에 이 시험 중 생긴 기록도 지운다 — 남기면 그 세션의 진입이 다른 시험에서 막힌다.
        jdbc.update("DELETE FROM position_limit_breach WHERE breached_at >= ?", Timestamp.from(testStart));
        marks.clear();
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private <T> T tx(Supplier<T> body) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t.execute(st -> body.get());
    }

    private Long liveSession(String coin) {
        // 세션 초기자본은 일부러 배정액과 다르게 둔다 — 종전 판정(세션 초기자본 기준)이면 같은 손실이 −3% 다.
        Long id = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT")
                .coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("100000")).availableKrw(new BigDecimal("100000"))
                .totalAssetKrw(new BigDecimal("100000"))
                .status("RUNNING").startedAt(Instant.now()).build()).getId();
        liveSessions.add(id);
        return id;
    }

    private Long dynamicSession() {
        BigDecimal cap = new BigDecimal("100000");
        Long id = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(cap).availableKrw(cap).totalAssetKrw(cap)
                .investRatio(new BigDecimal("0.2000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
        dynamicSessions.add(id);
        return id;
    }

    private PositionEntity position(String kind, Long sessionId, String coin, String size, String avg,
                                    String allocation, String invested) {
        return positionRepository.save(PositionEntity.builder()
                .coinPair(coin).side("BUY")
                .entryPrice(new BigDecimal(avg)).avgPrice(new BigDecimal(avg))
                .size(new BigDecimal(size))
                .entryAllocationKrw(allocation == null ? null : new BigDecimal(allocation))
                .investedKrw(new BigDecimal(invested))
                // 🔴 실현손익 열은 일부러 틀린 값으로 둔다 — 포지션 한도는 이 열을 읽지 않는다.
                .realizedPnl(new BigDecimal("5000"))
                .status("OPEN").sessionKind(kind).sessionId(sessionId)
                .openedAt(Instant.now()).build());
    }

    private void filledOrder(PositionEntity p, String side, String qty, String funds, String fee) {
        orderRepository.save(OrderEntity.builder()
                .coinPair(p.getCoinPair()).side(side).orderType("MARKET")
                .quantity(new BigDecimal(qty)).filledQuantity(new BigDecimal(qty))
                .state("FILLED").sessionKind(p.getSessionKind()).sessionId(p.getSessionId()).positionId(p.getId())
                .appliedExecutedFunds(new BigDecimal(funds)).appliedPaidFee(new BigDecimal(fee))
                .feeSource("REPORTED")
                .clientOrderId("plim-" + UUID.randomUUID())
                .build());
    }

    private PositionLimitGuard.Evaluation evaluate(Long positionId, String coin, String price) {
        if (price != null) marks.record(coin, new BigDecimal(price), Instant.now());
        return guard.evaluateAll(Instant.now()).stream()
                .filter(e -> e.positionId().equals(positionId))
                .findFirst().orElseThrow(() -> new AssertionError("판정 대상에 없다: 포지션 #" + positionId));
    }

    private BigDecimal dbPeak(Long positionId) {
        return jdbc.queryForObject("SELECT peak_value_krw FROM position WHERE id = ?", BigDecimal.class, positionId);
    }

    private BigDecimal dbAllocation(Long positionId) {
        return jdbc.queryForObject("SELECT entry_allocation_krw FROM position WHERE id = ?", BigDecimal.class, positionId);
    }

    private Map<String, Object> breach(Long positionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM position_limit_breach WHERE position_id = ?", positionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── 분모 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("CAPITAL_LOSS 의 분모는 진입 배정액 — 20,000 이면 −3,000 경계, 투입금·세션 초기자본이 아니다")
    void capitalLossUsesEntryAllocation() {
        Long s = liveSession("KRW-PLIMA");
        // 배정 20,000, 대금 19,990 + 수수료 10 = 원가 20,000. 투입금 열은 정산이 덮어쓴 것처럼 12,000 으로 둔다.
        PositionEntity p = position("LIVE", s, "KRW-PLIMA", "100", "199.9", "20000", "12000");
        filledOrder(p, "BUY", "100", "19990", "10");

        // 평가액 = 100 × 170.09 × 0.9995 = 17,000.4955 → 손익 −2,999.50 (한도 −3,000 안)
        PositionLimitGuard.Evaluation ok = evaluate(p.getId(), "KRW-PLIMA", "170.09");
        assertThat(ok.code()).as(ok.detail()).isNull();
        assertThat(ok.pnl()).isEqualByComparingTo("-2999.5045");

        // 100 × 170.08 × 0.9995 = 16,999.496 → 손익 −3,000.50 → 도달
        PositionLimitGuard.Evaluation hit = evaluate(p.getId(), "KRW-PLIMA", "170.08");
        assertThat(hit.code()).isEqualTo(PositionLimitGuard.CAPITAL_LOSS);
        assertThat(hit.detail()).contains("-3000.00").contains("진입 배정액 20000.00");
        assertThat(hit.newBreach()).isTrue();
        assertThat(breach(p.getId())).containsEntry("CODE", "CAPITAL_LOSS");

        // 같은 손실이라도 배정액이 40,000 이면 −7.5% — 도달하지 않는다(분모가 배정액 열에서 온다는 대조).
        Long s2 = liveSession("KRW-PLIMA");
        PositionEntity p2 = position("LIVE", s2, "KRW-PLIMA", "100", "199.9", "40000", "20000");
        filledOrder(p2, "BUY", "100", "19990", "10");
        assertThat(evaluate(p2.getId(), "KRW-PLIMA", "170.08").code()).isNull();
    }

    // ── 고점 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("부분 청산 — 판 몫의 순대금이 평가액에 남아 고점·낙폭이 그대로다. 고점 대비 −20% 경계")
    void partialCloseKeepsPeak() {
        Long s = liveSession("KRW-PLIMB");
        PositionEntity p = position("LIVE", s, "KRW-PLIMB", "100", "200", "20010", "20010");
        filledOrder(p, "BUY", "100", "20000", "10");

        // 250 → 평가액 24,987.5 = 고점
        PositionLimitGuard.Evaluation up = evaluate(p.getId(), "KRW-PLIMB", "250");
        assertThat(up.value()).isEqualByComparingTo("24987.5");
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("24987.5");

        // 절반을 250 에 판다: 대금 12,500 − 수수료 6.25. 잔량 50.
        jdbc.update("UPDATE position SET size = 50 WHERE id = ?", p.getId());
        filledOrder(p, "SELL", "50", "12500", "6.25");
        PositionLimitGuard.Evaluation after = evaluate(p.getId(), "KRW-PLIMB", "250");
        assertThat(after.value())
                .as("잔량 평가액(12,493.75)만 보면 절반을 판 순간 −50% 낙폭이 된다")
                .isEqualByComparingTo("24987.5");
        assertThat(after.code()).isNull();
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("24987.5");

        // 한도 = 24,987.5 × −20% = −4,997.5. 150.01 → 19,990.49975 (−4,997.00) 안, 150 → 19,990 (−4,997.5) 도달.
        assertThat(evaluate(p.getId(), "KRW-PLIMB", "150.01").code()).isNull();
        PositionLimitGuard.Evaluation hit = evaluate(p.getId(), "KRW-PLIMB", "150");
        assertThat(hit.code()).as("손익은 −20 이라 CAPITAL_LOSS 가 아니다").isEqualTo(PositionLimitGuard.MAX_DRAWDOWN);
        assertThat(hit.pnl()).isEqualByComparingTo("-20");
        assertThat(dbPeak(p.getId())).as("내려갈 때 고점은 그대로다").isEqualByComparingTo("24987.5");
    }

    @Test
    @DisplayName("추가 정산 — 부분 체결·잔여 체결·수수료 정정이 원가를 바꿔도 배정액은 그대로, 고점은 체결분만큼 오른다")
    void settlementKeepsAllocation() {
        Long s = liveSession("KRW-PLIMC");
        PositionEntity p = position("LIVE", s, "KRW-PLIMC", "0", "300", "3000", "3000");
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-PLIMC").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000")).state("SUBMITTED")
                .sessionKind("LIVE").sessionId(s).positionId(p.getId())
                .exchangeOrderId("plim-uuid-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());

        // ① 부분 체결 5 개 / 1,500 / 0.75
        settlementService.observeAndApplyBuyFill(o.getId(), response("wait", "5", "1500", "0.75"));
        PositionLimitGuard.Evaluation e1 = evaluate(p.getId(), "KRW-PLIMC", "300");
        assertThat(e1.cost()).isEqualByComparingTo("1500.75");
        assertThat(dbAllocation(p.getId())).isEqualByComparingTo("3000");
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("1499.25");   // 5 × 300 × 0.9995

        // ② 전량 체결 9.8 개 / 2,940 / 1.47 — 정산이 투입금을 실제 사용액(2,941.47)으로 덮어쓴다
        settlementService.observeAndApplyBuyFill(o.getId(), response("done", "9.8", "2940", "1.47"));
        assertThat(positionRepository.findById(p.getId()).orElseThrow().getInvestedKrw()).isEqualByComparingTo("2941.47");
        PositionLimitGuard.Evaluation e2 = evaluate(p.getId(), "KRW-PLIMC", "300");
        assertThat(e2.cost()).isEqualByComparingTo("2941.47");
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("2938.53");   // 9.8 × 300 × 0.9995

        // ③ 늦은 수수료 정정 1.47 → 2.00 (정산 경로)
        OrderSettlementService.Outcome out = settlementService.settle(o.getId(), response("done", "9.8", "2940", "2.00"));
        assertThat(out).isEqualTo(OrderSettlementService.Outcome.APPLIED);
        PositionLimitGuard.Evaluation e3 = evaluate(p.getId(), "KRW-PLIMC", "300");
        assertThat(e3.cost()).as("원가는 주문 반영값을 따라간다").isEqualByComparingTo("2942.00");
        assertThat(dbAllocation(p.getId())).as("배정액은 정산이 바꾸지 않는다").isEqualByComparingTo("3000");

        // 손익 −444.94: 배정액(3,000) 기준 한도 −450 안 — 투입금(2,941.47) 기준이었다면 −441.22 로 도달했을 값.
        PositionLimitGuard.Evaluation near = evaluate(p.getId(), "KRW-PLIMC", "254.93");
        assertThat(near.pnl()).isBetween(new BigDecimal("-450"), new BigDecimal("-441.23"));
        assertThat(near.code()).as(near.detail()).isNull();
        assertThat(evaluate(p.getId(), "KRW-PLIMC", "254").code()).isEqualTo(PositionLimitGuard.CAPITAL_LOSS);
    }

    private static OrderResponse response(String state, String volume, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setUuid("plim");
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-PLIMC");
        r.setVolume(new BigDecimal("3000"));
        r.setExecutedVolume(new BigDecimal(volume));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    @Test
    @DisplayName("엔진이 먼저 읽어 둔 포지션을 저장해도 고점·배정액이 되돌아가지 않는다")
    void staleEntitySaveKeepsPeakAndAllocation() {
        Long s = liveSession("KRW-PLIMD");
        PositionEntity p = position("LIVE", s, "KRW-PLIMD", "100", "200", "20010", "20010");
        filledOrder(p, "BUY", "100", "20000", "10");
        PositionEntity stale = positionRepository.findById(p.getId()).orElseThrow();   // 고점 없음

        evaluate(p.getId(), "KRW-PLIMD", "250");
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("24987.5");

        stale.setStopLossPrice(new BigDecimal("190"));
        stale.setEntryAllocationKrw(new BigDecimal("1"));
        positionRepository.saveAndFlush(stale);

        assertThat(dbPeak(p.getId())).isEqualByComparingTo("24987.5");
        assertThat(dbAllocation(p.getId())).isEqualByComparingTo("20010");
        assertThat(jdbc.queryForObject("SELECT stop_loss_price FROM position WHERE id = ?", BigDecimal.class, p.getId()))
                .as("저장 자체는 반영됐다").isEqualByComparingTo("190");
    }

    @Test
    @DisplayName("재시작 — 메모리 없는 새 판정기가 DB 의 고점·도달 기록으로 판정, 실시간 시세 전에는 대체 시세로 도달을 남기지 않는다")
    void restartUsesPersistedState() {
        Long s = liveSession("KRW-PLIME");
        PositionEntity p = position("LIVE", s, "KRW-PLIME", "100", "200", "20010", "20010");
        filledOrder(p, "BUY", "100", "20000", "10");
        evaluate(p.getId(), "KRW-PLIME", "300");   // 고점 29,985
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("29985");

        // 프로세스가 내려갔다 올라온 상태: 시세 기록 없음, 평가 루프는 종료 전에 평균단가(200)를 보고 평가손익 0 을 남겼다.
        marks.clear();
        jdbc.update("UPDATE position SET unrealized_pnl = 0 WHERE id = ?", p.getId());
        PositionLimitGuard fresh = new PositionLimitGuard(poolLossGuard);
        ReflectionTestUtils.setField(fresh, "em", em);

        // 🔴 대체 시세(200 → 고점 대비 −33%)로는 도달을 기록하지 않는다 — 실시간 시세가 오기 전 낡은 값으로 세션을 영구히 막지 않는다.
        PositionLimitGuard.Evaluation stale = tx(() -> fresh.evaluateAll(Instant.now())).stream()
                .filter(x -> x.positionId().equals(p.getId())).findFirst().orElseThrow();
        assertThat(stale.code()).isEqualTo(PositionLimitGuard.MARK_UNAVAILABLE);
        assertThat(stale.mark()).contains("평가루프");
        assertThat(breach(p.getId())).isNull();
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("29985");

        // 실시간 시세 239 가 들어온다: 100 × 239 × 0.9995 = 23,888.05 → 고점 29,985(DB) 대비 −6,096.95 ≤ −5,997 → 도달.
        // 손익은 +3,878 이라 CAPITAL_LOSS 아님.
        marks.record("KRW-PLIME", new BigDecimal("239"), Instant.now());
        PositionLimitGuard.Evaluation e = tx(() -> fresh.evaluateAll(Instant.now())).stream()
                .filter(x -> x.positionId().equals(p.getId())).findFirst().orElseThrow();
        assertThat(e.peak()).isEqualByComparingTo("29985");
        assertThat(e.code()).isEqualTo(PositionLimitGuard.MAX_DRAWDOWN);

        // 다시 새 판정기 — 기록은 한 번뿐이고, 진입 차단은 기록에서 온다.
        PositionLimitGuard again = new PositionLimitGuard(poolLossGuard);
        ReflectionTestUtils.setField(again, "em", em);
        PositionLimitGuard.Evaluation e2 = tx(() -> again.evaluateAll(Instant.now())).stream()
                .filter(x -> x.positionId().equals(p.getId())).findFirst().orElseThrow();
        assertThat(e2.newBreach()).isFalse();
        assertThat(tx(() -> again.checkSessionForEntry("LIVE", s)).code()).isEqualTo(PositionLimitGuard.POSITION_LIMIT);
    }

    @Test
    @DisplayName("오래된 실시간 시세 — 기준(120초)을 넘으면 판정하지 않는다: 고점·도달 기록 없음, 새 시세가 오면 판정")
    void staleRealtimeMarkIsNotJudged() {
        Long s = liveSession("KRW-PLIMS");
        PositionEntity p = position("LIVE", s, "KRW-PLIMS", "100", "200", "20010", "20010");
        filledOrder(p, "BUY", "100", "20000", "10");
        Instant now = Instant.now();

        // 121초 전 시세 400 — 고점을 올리지 않는다(낡은 높은 값이 고점이 되면 뒤의 정상 시세가 거짓 낙폭이 된다).
        marks.record("KRW-PLIMS", new BigDecimal("400"), now.minusSeconds(121));
        PositionLimitGuard.Evaluation high = pick(guard.evaluateAll(now), p.getId());
        assertThat(high.code()).isEqualTo(PositionLimitGuard.MARK_STALE);
        assertThat(PositionLimitGuard.isUnjudged(high.code())).isTrue();
        assertThat(high.detail()).contains("121초 전").contains("기준 120초");
        assertThat(dbPeak(p.getId())).isNull();

        // 121초 전 시세 100 — 손익 −10,015 ≤ −3,001.5 이지만 도달을 남기지 않는다.
        marks.record("KRW-PLIMS", new BigDecimal("100"), now.minusSeconds(121));
        assertThat(pick(guard.evaluateAll(now), p.getId()).code()).isEqualTo(PositionLimitGuard.MARK_STALE);
        assertThat(breach(p.getId())).isNull();

        // 경계 안(119초 전) 시세 250 은 판정한다 — 고점 24,987.5.
        marks.record("KRW-PLIMS", new BigDecimal("250"), now.minusSeconds(119));
        PositionLimitGuard.Evaluation ok = pick(guard.evaluateAll(now), p.getId());
        assertThat(ok.code()).as(ok.detail()).isNull();
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("24987.5");

        // 새 시세 100 → 도달.
        marks.record("KRW-PLIMS", new BigDecimal("100"), now);
        assertThat(pick(guard.evaluateAll(now), p.getId()).code()).isEqualTo(PositionLimitGuard.CAPITAL_LOSS);
        assertThat(breach(p.getId())).containsEntry("CODE", "CAPITAL_LOSS");
    }

    @Test
    @DisplayName("판정 불가가 이어지면 정상으로 두지 않는다 — 180초 넘게 이어지면 경보 한 번, 판정이 돌아오면 해제")
    void monitorFlagsPersistentUnjudged() {
        Long s = liveSession("KRW-PLIMU");
        PositionEntity p = position("LIVE", s, "KRW-PLIMU", "100", "200", "20010", "20010");
        filledOrder(p, "BUY", "100", "20000", "10");
        Instant t0 = Instant.now();

        // 시세 없음(재시작 직후와 같다) — 판정 불가로 잡히지만 아직 경보 전.
        assertThat(pick(monitor.runOnce(t0), p.getId()).code()).isEqualTo(PositionLimitGuard.MARK_UNAVAILABLE);
        assertThat(unjudged(p.getId())).isNotNull().extracting(PositionLimitMonitor.Unjudged::alerted).isEqualTo(false);

        // 시세가 오지만 오래된 값 — 사유는 바뀌어도 이어진 시간은 처음부터 센다.
        marks.record("KRW-PLIMU", new BigDecimal("210"), t0);
        PositionLimitGuard.Evaluation stale = pick(monitor.runOnce(t0.plusSeconds(179)), p.getId());
        assertThat(stale.code()).isEqualTo(PositionLimitGuard.MARK_STALE);
        assertThat(unjudged(p.getId()).alerted()).isFalse();
        assertThat(unjudged(p.getId()).since()).isEqualTo(t0);

        monitor.runOnce(t0.plusSeconds(181));
        assertThat(unjudged(p.getId()).alerted()).as("180초 넘게 판정 불가 → 경보").isTrue();
        assertThat(unjudged(p.getId()).code()).isEqualTo(PositionLimitGuard.MARK_STALE);

        // 새 시세 → 판정 재개, 목록에서 빠진다.
        marks.record("KRW-PLIMU", new BigDecimal("210"), t0.plusSeconds(185));
        assertThat(pick(monitor.runOnce(t0.plusSeconds(190)), p.getId()).code()).isNull();
        assertThat(unjudged(p.getId())).isNull();
        assertThat(breach(p.getId())).isNull();
    }

    private static PositionLimitGuard.Evaluation pick(List<PositionLimitGuard.Evaluation> all, Long positionId) {
        return all.stream().filter(e -> e.positionId().equals(positionId)).findFirst()
                .orElseThrow(() -> new AssertionError("판정 대상에 없다: 포지션 #" + positionId));
    }

    private PositionLimitMonitor.Unjudged unjudged(Long positionId) {
        return monitor.unjudgedPositions().stream().filter(u -> u.positionId().equals(positionId)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("반영값 없는 체결 주문이 있으면 평가하지 않고 고점도 올리지 않는다")
    void unappliedFillSkipsValuation() {
        Long s = liveSession("KRW-PLIMF");
        PositionEntity p = position("LIVE", s, "KRW-PLIMF", "100", "200", "20010", "20010");
        filledOrder(p, "BUY", "100", "20000", "10");
        evaluate(p.getId(), "KRW-PLIMF", "250");
        orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-PLIMF").side("SELL").orderType("MARKET")
                .quantity(new BigDecimal("50")).filledQuantity(new BigDecimal("50"))
                .state("FILLED").sessionKind("LIVE").sessionId(s).positionId(p.getId())
                .feeSource("REPORTED").clientOrderId("plim-" + UUID.randomUUID()).build());

        PositionLimitGuard.Evaluation e = evaluate(p.getId(), "KRW-PLIMF", "400");
        assertThat(e.code()).isEqualTo(PositionLimitGuard.VALUATION_UNKNOWN);
        assertThat(dbPeak(p.getId())).isEqualByComparingTo("24987.5");
        assertThat(breach(p.getId())).isNull();
    }

    @Test
    @DisplayName("일일 폐기 판정은 실자금 LIVE 세션에 세션 단위 CAPITAL_LOSS 를 걸지 않는다 — 같은 이름의 기준이 두 개가 되지 않게")
    void dailyKillCriteriaLeavesCapitalRulesToPositions() {
        Long s = liveSession("KRW-PLIMP");
        jdbc.update("UPDATE live_trading_session SET total_asset_krw = 70000, mdd_peak_capital = 150000 WHERE id = ?", s);
        StrategyKillCriteriaService.Judgment j = killCriteriaService.evaluateAll().stream()
                .filter(x -> "LIVE".equals(x.sessionKind()) && s.equals(x.sessionId()))
                .findFirst().orElseThrow();
        assertThat(j.code()).as(j.reason()).isNotIn("CAPITAL_LOSS", "MAX_DRAWDOWN");
    }

    // ── 도달 → 차단 · 정지 ──────────────────────────────────────────

    private void openPool() {
        limits.setTotalCapKrw(new BigDecimal("1000000000000"));
        limits.setPerPositionCapKrw(new BigDecimal("1000000000000"));
        limits.setMaxConcurrentPositions(100_000);
    }

    /** LIVE 매수 예약. @return 거절 코드, 승인이면 {@code null} */
    private String liveReserve(Long sessionId, String coin) {
        try {
            tx(() -> {
                PositionEntity pos = positionRepository.save(PositionEntity.builder()
                        .coinPair(coin).side("BUY")
                        .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                        .size(BigDecimal.ZERO).investedKrw(new BigDecimal("8000"))
                        .entryAllocationKrw(new BigDecimal("8000"))
                        .status("OPEN").sessionKind("LIVE").sessionId(sessionId)
                        .openedAt(Instant.now()).build());
                OrderRequest req = new OrderRequest();
                req.setCoinPair(coin);
                req.setSide("BUY");
                req.setOrderType("MARKET");
                req.setQuantity(new BigDecimal("8000"));
                req.setReason("포지션 한도 시험 — LIVE");
                req.setSignalPrice(new BigDecimal("300"));
                req.setSessionId(sessionId);
                req.setSessionKind("LIVE");
                req.setPositionId(pos.getId());
                return orderExecutionEngine.reserveAndPrepareIntent(req);
            });
            return null;
        } catch (OrderExecutionEngine.ReservationRejected rejected) {
            return rejected.getCode();
        }
    }

    private static List<Candle> candles() {
        List<Candle> out = new ArrayList<>();
        Instant t0 = Instant.now().minus(300, ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal px = new BigDecimal(1000 + i);
            out.add(Candle.builder().time(t0.plus(i, ChronoUnit.HOURS))
                    .open(px).high(px.add(BigDecimal.TEN)).low(px.subtract(BigDecimal.TEN)).close(px)
                    .volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    private String dynamicBuy(Long sessionId, String coin) {
        return tx(() -> dynamicTradingService.executeBuy(dynamicRepo.findById(sessionId).orElseThrow(), coin, candles(),
                StrategySignal.buy(new BigDecimal("80"), "포지션 한도 시험"), BigDecimal.ONE));
    }

    @Test
    @DisplayName("도달한 세션은 LIVE·DYNAMIC 모두 공유 예약 지점에서 거절되고, 포지션이 닫힌 뒤에도 유지된다 — 다른 세션은 통과")
    void breachBlocksEntryForThatSessionOnly() {
        openPool();
        // LIVE: 도달시킨 뒤 포지션을 닫는다
        Long live = liveSession("KRW-PLIMG");
        PositionEntity lp = position("LIVE", live, "KRW-PLIMG", "100", "199.9", "20000", "20000");
        filledOrder(lp, "BUY", "100", "19990", "10");
        assertThat(evaluate(lp.getId(), "KRW-PLIMG", "150").code()).isEqualTo(PositionLimitGuard.CAPITAL_LOSS);
        jdbc.update("UPDATE position SET size = 0, status = 'CLOSED' WHERE id = ?", lp.getId());
        BigDecimal liveKrwBefore = liveRepo.findById(live).orElseThrow().getAvailableKrw();
        long ordersBefore = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", live).size();

        assertThat(liveReserve(live, "KRW-PLIMG")).isEqualTo(PositionLimitGuard.POSITION_LIMIT);
        assertThat(liveRepo.findById(live).orElseThrow().getAvailableKrw()).isEqualByComparingTo(liveKrwBefore);
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", live)).hasSize((int) ordersBefore);

        // DYNAMIC: 같은 지점, 같은 사유
        Long dyn = dynamicSession();
        PositionEntity dp = position("DYNAMIC", dyn, "KRW-PLIMH", "100", "199.9", "20000", "20000");
        filledOrder(dp, "BUY", "100", "19990", "10");
        assertThat(evaluate(dp.getId(), "KRW-PLIMH", "150").code()).isEqualTo(PositionLimitGuard.CAPITAL_LOSS);
        jdbc.update("UPDATE position SET size = 0, status = 'CLOSED' WHERE id = ?", dp.getId());
        BigDecimal dynKrwBefore = dynamicRepo.findById(dyn).orElseThrow().getAvailableKrw();
        String reason = dynamicBuy(dyn, "KRW-PLIMJ");
        assertThat(reason).contains("(" + PositionLimitGuard.POSITION_LIMIT + ")").contains("포지션별 한도 도달 기록");
        assertThat(dynamicRepo.findById(dyn).orElseThrow().getAvailableKrw()).isEqualByComparingTo(dynKrwBefore);

        // 대조: 도달 기록 없는 세션은 같은 경로로 승인된다
        Long clean = liveSession("KRW-PLIMK");
        assertThat(liveReserve(clean, "KRW-PLIMK")).isNull();
    }

    @Test
    @DisplayName("🔴 판정 불가(시세 없음·120초 초과·반영값 없는 체결)인 포지션이 있는 세션은 그 순간부터 신규 진입 거절 — 감시·경보를 기다리지 않는다. "
            + "다른 세션은 통과, 그 포지션의 청산은 나간다")
    void unjudgedBlocksEntryImmediately() {
        openPool();
        Long live = liveSession("KRW-PLIMQ");
        PositionEntity lp = position("LIVE", live, "KRW-PLIMQ", "100", "200", "20010", "20010");
        filledOrder(lp, "BUY", "100", "20000", "10");
        BigDecimal krwBefore = liveRepo.findById(live).orElseThrow().getAvailableKrw();
        assertThat(monitor.unjudgedPositions()).as("감시는 한 번도 돌지 않았다").noneMatch(u -> u.positionId().equals(lp.getId()));

        // 🔴 리뷰 8회차: 이 보유는 공유 풀에서도 대체 가격으로만 평가된다 — 예약 지점의 풀 판정(②-2)이 먼저, 풀 전체를 막는다.
        Long other = liveSession("KRW-PLIMQ3");
        assertThat(liveReserve(live, "KRW-PLIMQ2")).isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
        assertThat(liveReserve(other, "KRW-PLIMQ3")).as("풀 전체 — 다른 세션도").isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
        marks.record("KRW-PLIMQ", new BigDecimal("250"), Instant.now().minusSeconds(121));
        assertThat(liveReserve(other, "KRW-PLIMQ3")).as("121초 전 시세도 대체").isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
        marks.remove("KRW-PLIMQ");

        // 세션 단위 판정(②-4)은 그 뒤에 있다 — 풀 판정을 끄고(시험 런타임 전용) 그 연결을 따로 본다(7회차 변형 검사가 잡은 연결).
        poolLossGuard.setEnabledSetting(false);
        try {
            // ① 시세 없음
            assertThat(liveReserve(live, "KRW-PLIMQ2")).isEqualTo(PositionLimitGuard.POSITION_LIMIT_UNJUDGED);
            // ② 121초 전 시세
            marks.record("KRW-PLIMQ", new BigDecimal("250"), Instant.now().minusSeconds(121));
            assertThat(liveReserve(live, "KRW-PLIMQ2")).isEqualTo(PositionLimitGuard.POSITION_LIMIT_UNJUDGED);
            assertThat(liveRepo.findById(live).orElseThrow().getAvailableKrw()).as("거절은 아무것도 바꾸지 않는다").isEqualByComparingTo(krwBefore);
            assertThat(breach(lp.getId())).as("판정 불가는 도달이 아니다 — 영구 기록 없음").isNull();

            // ③ 대조: 세션 단위 판정만 보면 다른 세션(열린 포지션 없음)은 같은 시각에 통과한다
            assertThat(liveReserve(other, "KRW-PLIMQ3")).isNull();
        } finally {
            poolLossGuard.setEnabledSetting(true);
        }

        // ④ 그 포지션의 청산은 막지 않는다 — 매도는 거래소로 나간다
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                Mockito.anyString(), Mockito.anyString())).thenReturn(acceptedSell("KRW-PLIMQ"));
        OrderRequest sell = new OrderRequest();
        sell.setCoinPair("KRW-PLIMQ");
        sell.setSide("SELL");
        sell.setOrderType("MARKET");
        sell.setQuantity(new BigDecimal("100"));
        sell.setReason("판정 불가 중 청산");
        sell.setSessionId(live);
        sell.setSessionKind("LIVE");
        sell.setPositionId(lp.getId());
        ((OrderExecutionEngine) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(orderExecutionEngine)).submitOrder(sell);
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(Mockito.eq("KRW-PLIMQ"), Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.anyString(), Mockito.anyString());
        orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", live).stream()
                .filter(o -> "SELL".equals(o.getSide())).forEach(orderRepository::delete);

        // ⑤ 새 시세 → 같은 세션 통과
        marks.record("KRW-PLIMQ", new BigDecimal("250"), Instant.now());
        assertThat(liveReserve(live, "KRW-PLIMQ2")).isNull();

        // ⑥ 반영값 없는 체결(시세는 새것) — 다시 거절
        Long live2 = liveSession("KRW-PLIMR");
        PositionEntity lp2 = position("LIVE", live2, "KRW-PLIMR", "100", "200", "20010", "20010");
        filledOrder(lp2, "BUY", "100", "20000", "10");
        marks.record("KRW-PLIMR", new BigDecimal("250"), Instant.now());
        orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-PLIMR").side("SELL").orderType("MARKET")
                .quantity(new BigDecimal("50")).filledQuantity(new BigDecimal("50"))
                .state("FILLED").sessionKind("LIVE").sessionId(live2).positionId(lp2.getId())
                .feeSource("REPORTED").clientOrderId("plim-" + UUID.randomUUID()).build());
        assertThat(tx(() -> guard.checkUnjudgedForEntry("LIVE", live2, Instant.now())).code())
                .isEqualTo(PositionLimitGuard.POSITION_LIMIT_UNJUDGED);
        assertThat(tx(() -> guard.checkUnjudgedForEntry("LIVE", live2, Instant.now())).message()).contains("VALUATION_UNKNOWN");
        // 🔴 반영값 없는 체결은 공유 풀 순자산도 계산할 수 없게 한다 — 그 세션뿐 아니라 풀 전체의 신규 승인이 먼저 막힌다
        assertThat(liveReserve(live2, "KRW-PLIMR2")).isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
        assertThat(liveReserve(other, "KRW-PLIMQ3")).as("풀 평가 불가 — 다른 세션도").isEqualTo(PoolLossGuard.POOL_VALUATION_UNKNOWN);
        orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", live2).stream()
                .filter(o -> "SELL".equals(o.getSide())).forEach(orderRepository::delete);

        // ⑦ DYNAMIC — 같은 지점, 같은 사유
        Long dyn = dynamicSession();
        PositionEntity dp = position("DYNAMIC", dyn, "KRW-PLIMT", "100", "200", "20010", "20010");
        filledOrder(dp, "BUY", "100", "20000", "10");
        BigDecimal dynKrwBefore = dynamicRepo.findById(dyn).orElseThrow().getAvailableKrw();
        assertThat(dynamicBuy(dyn, "KRW-PLIMT2")).as("풀 판정이 먼저 — 리뷰 8회차").contains("(" + PoolLossGuard.POOL_VALUATION_UNKNOWN + ")");
        poolLossGuard.setEnabledSetting(false);
        try {
            assertThat(dynamicBuy(dyn, "KRW-PLIMT2")).contains("(" + PositionLimitGuard.POSITION_LIMIT_UNJUDGED + ")")
                    .contains("MARK_UNAVAILABLE");
        } finally {
            poolLossGuard.setEnabledSetting(true);
        }
        assertThat(dynamicRepo.findById(dyn).orElseThrow().getAvailableKrw()).isEqualByComparingTo(dynKrwBefore);
    }

    private UpbitOrderClient.ExchangeResult acceptedSell(String coin) {
        OrderResponse r = new OrderResponse();
        r.setUuid("plim-sell-" + UUID.randomUUID());
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket(coin);
        r.setVolume(new BigDecimal("100"));
        r.setExecutedVolume(BigDecimal.ZERO);
        return new UpbitOrderClient.ExchangeResult(r, "{}");
    }

    @Test
    @DisplayName("자동 정지 OFF — 경보로 한 번 처리하고 세션은 그대로, 진입은 막힌다")
    void monitorAlertsWhenAutoStopOff() {
        Long s = liveSession("KRW-PLIML");
        PositionEntity p = position("LIVE", s, "KRW-PLIML", "100", "199.9", "20000", "20000");
        filledOrder(p, "BUY", "100", "19990", "10");
        marks.record("KRW-PLIML", new BigDecimal("150"), Instant.now());

        monitor.runOnce(Instant.now());
        Map<String, Object> b = breach(p.getId());
        assertThat(b).containsEntry("STOP_ACTION", PositionLimitMonitor.ACTION_ALERTED);
        Object firstAt = b.get("STOP_AT");
        assertThat(liveRepo.findById(s).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(tx(() -> guard.checkSessionForEntry("LIVE", s)).approved()).isFalse();

        monitor.runOnce(Instant.now());
        assertThat(breach(p.getId()).get("STOP_AT")).as("다시 처리하지 않는다").isEqualTo(firstAt);
        assertThat(guard.pendingBreaches()).noneMatch(x -> x.positionId().equals(p.getId()));
    }

    @Test
    @DisplayName("자동 정지 ON — LIVE·DYNAMIC 세션을 일반 정지로 멈추고(청산 매도 제출) 결과를 기록한다")
    void monitorStopsSessionsWhenAutoStopOn() {
        Mockito.doReturn(acceptedSell("KRW-PLIMM")).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        monitor.setAutoStop(true);

        Long live = liveSession("KRW-PLIMM");
        PositionEntity lp = position("LIVE", live, "KRW-PLIMM", "100", "199.9", "20000", "20000");
        filledOrder(lp, "BUY", "100", "19990", "10");
        marks.record("KRW-PLIMM", new BigDecimal("150"), Instant.now());

        Long dyn = dynamicSession();
        jdbc.update("UPDATE dynamic_session SET scan_state = 'HOLDING' WHERE id = ?", dyn);
        PositionEntity dp = position("DYNAMIC", dyn, "KRW-PLIMN", "100", "199.9", "20000", "20000");
        filledOrder(dp, "BUY", "100", "19990", "10");
        marks.record("KRW-PLIMN", new BigDecimal("150"), Instant.now());

        monitor.runOnce(Instant.now());

        assertThat(breach(lp.getId())).containsEntry("STOP_ACTION", PositionLimitMonitor.ACTION_AUTO_STOPPED);
        assertThat(breach(dp.getId())).containsEntry("STOP_ACTION", PositionLimitMonitor.ACTION_AUTO_STOPPED);
        assertThat(liveRepo.findById(live).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(dynamicRepo.findById(dyn).orElseThrow().getStatus()).isEqualTo("STOPPED");
        // 정지 성공 ≠ 정리 완료: 청산 매도가 나갔고(CLOSING), 체결 반영은 별도 추적이다.
        assertThat(positionRepository.findById(lp.getId()).orElseThrow().getStatus()).isIn("CLOSING", "CLOSED");
        assertThat(positionRepository.findById(dp.getId()).orElseThrow().getStatus()).isIn("CLOSING", "CLOSED");
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", live))
                .anyMatch(o -> "SELL".equals(o.getSide()));
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", dyn))
                .anyMatch(o -> "SELL".equals(o.getSide()));

    }
}
