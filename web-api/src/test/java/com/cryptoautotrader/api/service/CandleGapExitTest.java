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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캔들 판정 불가 회차의 청산 범위 (2026-10-15 리뷰 7회차).
 *
 * <p>건너뛰는 것은 캔들이 필요한 판정뿐이어야 한다. 시간 초과 청산과, 유효한(120초 이내) 실시간 현재가가 있을 때의 손절·익절은
 * 캔들이 없어도 나가야 한다 — 그렇지 않으면 캔들 공백이 청산 누락으로 돌아온다. 낡은 시세는 현재가로 쓰지 않는다.</p>
 */
@DisplayName("캔들 판정 불가 회차 — 시간 초과·유효 현재가 손절·익절은 계속, 낡은 시세로는 판정 안 함")
class CandleGapExitTest extends IntegrationTestBase {

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private MarkPriceBook marks;
    @Autowired private PlatformTransactionManager txManager;

    @MockBean private UpbitOrderClient upbitOrderClient;

    private final List<Long> live = new ArrayList<>();
    private final List<Long> dyn = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                Mockito.anyString(), Mockito.anyString())).thenAnswer(inv -> {
            OrderResponse r = new OrderResponse();
            r.setUuid("gap-" + UUID.randomUUID());
            r.setSide("ask");
            r.setOrdType("market");
            r.setState("wait");
            r.setMarket(inv.getArgument(0));
            return new UpbitOrderClient.ExchangeResult(r, "{}");
        });
    }

    @AfterEach
    void tearDown() {
        for (Long id : live) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        for (Long id : dyn) {
            for (String k : List.of("DYNAMIC", "DYN_PAPER")) {
                orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(k, id).forEach(orderRepository::delete);
                positionRepository.findBySessionKindAndSessionId(k, id).forEach(positionRepository::delete);
            }
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
        marks.clear();
    }

    private LiveTradingSessionEntity liveSession(String coin, Integer maxHold) {
        LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7058.53"))
                .totalAssetKrw(new BigDecimal("10000")).maxHoldHours(maxHold)
                .status("RUNNING").startedAt(Instant.now()).build());
        live.add(s.getId());
        return s;
    }

    private DynamicSessionEntity dynamicSession(String coin, Integer maxHold, String mode) {
        BigDecimal cap = new BigDecimal("10000");
        DynamicSessionEntity s = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(cap).availableKrw(new BigDecimal("7058.53")).totalAssetKrw(cap)
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00")).maxHoldHours(maxHold)
                .status("RUNNING").scanState("POSITION_MONITORING").tradingMode(mode).currentCoinPair(coin)
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).build());
        dyn.add(s.getId());
        return s;
    }

    /** 9.8개 @300, 손절가 290, 익절가 330. */
    private PositionEntity position(String kind, Long sid, String coin, Duration held) {
        return positionRepository.save(PositionEntity.builder()
                .coinPair(coin).side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal("9.8")).investedKrw(new BigDecimal("2941.47")).entryAllocationKrw(new BigDecimal("3000"))
                .stopLossPrice(new BigDecimal("290")).takeProfitPrice(new BigDecimal("330"))
                .status("OPEN").sessionKind(kind).sessionId(sid).openedAt(Instant.now().minus(held)).build());
    }

    private List<OrderEntity> sells(String kind, Long sid) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid).stream()
                .filter(o -> "SELL".equals(o.getSide())).toList();
    }

    private String exitReason(Long positionId) {
        PositionEntity p = positionRepository.findById(positionId).orElseThrow();
        return p.getStatus() + "/" + (p.getExitReason() == null ? "" : p.getExitReason().name());
    }

    /** {@code submitOrder} 는 비동기다 — 주문 행이 생길 때까지 기다린다. */
    private void awaitSells(String kind, Long sid, int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (sells(kind, sid).size() < n && System.currentTimeMillis() < until) Thread.sleep(100);
    }

    private LiveTradingService liveTarget() {
        return (LiveTradingService) AopTestUtils.getUltimateTargetObject(liveTradingService);
    }

    private boolean inTx(DynamicSessionEntity s, String coin) {
        return Boolean.TRUE.equals(new TransactionTemplate(txManager).execute(st -> dynTarget().monitorWithoutCandles(s, coin)));
    }

    private DynamicTradingService dynTarget() {
        return (DynamicTradingService) AopTestUtils.getUltimateTargetObject(dynamicTradingService);
    }

    @Test
    @DisplayName("LIVE — 시세 없음: 시간 초과는 나간다 / 가격 기반은 판정하지 않는다. 낡은 시세(121초)로는 손절하지 않고, 유효한 시세면 손절·익절")
    void live() throws Exception {
        // ① 시간 초과 — 현재가 없이도 시장가 매도
        LiveTradingSessionEntity a = liveSession("KRW-GAPA", 1);
        PositionEntity pa = position("LIVE", a.getId(), "KRW-GAPA", Duration.ofHours(2));
        assertThat(liveTarget().exitsWithoutCandles(a, "KRW-GAPA")).isTrue();
        awaitSells("LIVE", a.getId(), 1);
        assertThat(sells("LIVE", a.getId())).hasSize(1);
        assertThat(exitReason(pa.getId())).isEqualTo("CLOSING/TIME_STOP");

        // ② 손절 조건이지만 시세가 121초 전 값 — 판정하지 않는다
        LiveTradingSessionEntity b = liveSession("KRW-GAPB", 0);
        PositionEntity pb = position("LIVE", b.getId(), "KRW-GAPB", Duration.ofMinutes(10));
        marks.record("KRW-GAPB", new BigDecimal("280"), Instant.now().minusSeconds(121));
        assertThat(liveTarget().exitsWithoutCandles(b, "KRW-GAPB")).isFalse();
        Thread.sleep(500);
        assertThat(sells("LIVE", b.getId())).isEmpty();
        assertThat(exitReason(pb.getId())).isEqualTo("OPEN/");

        // ③ 같은 값이 새 시세면 손절
        marks.record("KRW-GAPB", new BigDecimal("280"), Instant.now());
        assertThat(liveTarget().exitsWithoutCandles(b, "KRW-GAPB")).isTrue();
        awaitSells("LIVE", b.getId(), 1);
        assertThat(exitReason(pb.getId())).isEqualTo("CLOSING/STOP_LOSS");
        assertThat(sells("LIVE", b.getId()).get(0).getSignalReason()).contains("캔들 판정 불가 회차");

        // ④ 익절
        LiveTradingSessionEntity c = liveSession("KRW-GAPC", 0);
        PositionEntity pc = position("LIVE", c.getId(), "KRW-GAPC", Duration.ofMinutes(10));
        marks.record("KRW-GAPC", new BigDecimal("331"), Instant.now());
        assertThat(liveTarget().exitsWithoutCandles(c, "KRW-GAPC")).isTrue();
        awaitSells("LIVE", c.getId(), 1);
        assertThat(exitReason(pc.getId())).isEqualTo("CLOSING/TAKE_PROFIT");

        // ⑤ 조건 없음 — 아무것도 내지 않는다
        LiveTradingSessionEntity d = liveSession("KRW-GAPD", 0);
        position("LIVE", d.getId(), "KRW-GAPD", Duration.ofMinutes(10));
        marks.record("KRW-GAPD", new BigDecimal("305"), Instant.now());
        assertThat(liveTarget().exitsWithoutCandles(d, "KRW-GAPD")).isFalse();
        Thread.sleep(300);
        assertThat(sells("LIVE", d.getId())).isEmpty();
    }

    @Test
    @DisplayName("DYNAMIC(실자금·페이퍼) — 같은 규칙. 페이퍼도 현재가 없이 시간 초과로 나가고, 체결가만 마지막 시세 → 평균 단가로 시뮬레이션한다")
    void dynamic() throws Exception {
        DynamicSessionEntity a = dynamicSession("KRW-GAPE", 1, "REAL");
        PositionEntity pa = position("DYNAMIC", a.getId(), "KRW-GAPE", Duration.ofHours(2));
        assertThat(dynTarget().monitorWithoutCandles(a, "KRW-GAPE")).isTrue();
        awaitSells("DYNAMIC", a.getId(), 1);
        assertThat(sells("DYNAMIC", a.getId())).hasSize(1);
        assertThat(exitReason(pa.getId())).isEqualTo("CLOSING/TIME_STOP");

        DynamicSessionEntity b = dynamicSession("KRW-GAPF", 0, "REAL");
        PositionEntity pb = position("DYNAMIC", b.getId(), "KRW-GAPF", Duration.ofMinutes(10));
        marks.record("KRW-GAPF", new BigDecimal("280"), Instant.now().minusSeconds(121));
        assertThat(dynTarget().monitorWithoutCandles(b, "KRW-GAPF")).isFalse();
        Thread.sleep(500);
        assertThat(sells("DYNAMIC", b.getId())).isEmpty();
        marks.record("KRW-GAPF", new BigDecimal("280"), Instant.now());
        assertThat(dynTarget().monitorWithoutCandles(b, "KRW-GAPF")).isTrue();
        awaitSells("DYNAMIC", b.getId(), 1);
        assertThat(exitReason(pb.getId())).isEqualTo("CLOSING/STOP_LOSS");

        DynamicSessionEntity c = dynamicSession("KRW-GAPG", 0, "REAL");
        PositionEntity pc = position("DYNAMIC", c.getId(), "KRW-GAPG", Duration.ofMinutes(10));
        marks.record("KRW-GAPG", new BigDecimal("331"), Instant.now());
        assertThat(dynTarget().monitorWithoutCandles(c, "KRW-GAPG")).isTrue();
        awaitSells("DYNAMIC", c.getId(), 1);
        assertThat(exitReason(pc.getId())).isEqualTo("CLOSING/TAKE_PROFIT");

        // 페이퍼 — 실전과 같은 판정(엔진 동등성). 현재가가 없어도 시간 초과로 나가고, 체결가만 시뮬레이션이 정한다:
        //   시세 기록이 없으면 평균 단가(300), 낡은 시세만 있으면 그 값(295) 기준 — 둘 다 슬리피지로 그보다 낮다.
        String paperKind = DynamicTradingService.sessionKind(dynamicSession("KRW-GAPX", 1, "PAPER"));
        DynamicSessionEntity p = dynamicSession("KRW-GAPH", 1, "PAPER");
        PositionEntity pp = position(paperKind, p.getId(), "KRW-GAPH", Duration.ofHours(2));
        assertThat(inTx(p, "KRW-GAPH")).isTrue();   // 운영과 같이 감시 회차 트랜잭션 안에서 — 페이퍼는 정산까지 즉시
        assertThat(exitReason(pp.getId())).isEqualTo("CLOSED/TIME_STOP");
        List<OrderEntity> ps = sells(paperKind, p.getId());
        assertThat(ps).hasSize(1);
        assertThat(ps.get(0).getPrice()).isLessThan(new BigDecimal("300")).isGreaterThan(new BigDecimal("290"));
        assertThat(ps.get(0).getSignalReason()).contains("캔들 판정 불가 회차");

        DynamicSessionEntity q = dynamicSession("KRW-GAPI", 1, "PAPER");
        PositionEntity pq = position(paperKind, q.getId(), "KRW-GAPI", Duration.ofHours(2));
        marks.record("KRW-GAPI", new BigDecimal("295"), Instant.now().minusSeconds(600));
        assertThat(inTx(q, "KRW-GAPI")).isTrue();
        assertThat(exitReason(pq.getId())).isEqualTo("CLOSED/TIME_STOP");
        List<OrderEntity> qs = sells(paperKind, q.getId());
        assertThat(qs).hasSize(1);
        assertThat(qs.get(0).getPrice()).isLessThan(new BigDecimal("295")).isGreaterThan(new BigDecimal("285"));
    }
}
