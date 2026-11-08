package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExecutionDriftLogEntity;
import com.cryptoautotrader.api.repository.ExecutionDriftLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20260415_analy.md Tier 3 §14 — 실전/백테스트 drift 트래커.
 *
 * <p>🔴 시험 격리(총괄 v1.135 §3): 같은 Spring 컨텍스트·H2 를 쓰는 앞선 시험 클래스가 남긴 drift 행이 있으면
 * 이 클래스의 첫 시험이 {@code findAll().get(0)} 으로 그 행을 읽었다(전체 실행에서 음수 slippage 시험이 0.000000 을 읽은 원인).
 * 각 시험 전에도 비우고, 기록 시험은 방금 기록한 세션·코인의 행만 골라 읽는다. 단언(음수·양수·크기)은 바꾸지 않았다.</p>
 */
class ExecutionDriftTrackerTest extends IntegrationTestBase {

    @Autowired
    private ExecutionDriftTracker tracker;

    @Autowired
    private ExecutionDriftLogRepository driftRepo;

    @BeforeEach
    void isolate() {
        driftRepo.deleteAll();
    }

    @AfterEach
    void cleanup() {
        driftRepo.deleteAll();
    }

    /** 방금 기록한 세션·코인의 행 — 정확히 1개여야 한다(다른 행을 고르지 않는다). */
    private ExecutionDriftLogEntity recorded(long sessionId, String coinPair) {
        List<ExecutionDriftLogEntity> rows = driftRepo.findAll().stream()
                .filter(e -> e.getSessionId() == sessionId && coinPair.equals(e.getCoinPair()))
                .toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("§14 record() — slippage 계산 및 저장 정확성")
    void record_calculatesSlippageCorrectly() {
        BigDecimal signalPrice = new BigDecimal("50000000");
        BigDecimal fillPrice   = new BigDecimal("50100000"); // +0.2% slippage

        tracker.record(1L, "LIVE", "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                signalPrice, fillPrice, Instant.now());

        List<ExecutionDriftLogEntity> all = driftRepo.findAll();
        assertThat(all).hasSize(1);

        ExecutionDriftLogEntity saved = recorded(1L, "KRW-BTC");
        assertThat(saved.getSlippagePct())
                .isGreaterThan(BigDecimal.ZERO)         // 양수 slippage
                .isLessThan(new BigDecimal("1"));       // 1% 미만
        assertThat(saved.getSide()).isEqualTo("SELL");
        assertThat(saved.getStrategyType()).isEqualTo("COMPOSITE_BREAKOUT");
    }

    @Test
    @DisplayName("§14 record() — 음수 slippage (체결가 < 신호가)")
    void record_negativeSlippage() {
        BigDecimal signalPrice = new BigDecimal("50000000");
        BigDecimal fillPrice   = new BigDecimal("49900000"); // -0.2% slippage

        tracker.record(2L, "LIVE", "KRW-ETH", "COMPOSITE_MOMENTUM", "SELL",
                signalPrice, fillPrice, Instant.now());

        BigDecimal slippage = recorded(2L, "KRW-ETH").getSlippagePct();
        assertThat(slippage).isLessThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("§14 record() — 다른 세션의 slippage 0 행이 먼저 있어도 방금 기록한 음수 행을 읽는다(고정 오염 탐침)")
    void record_negativeSlippage_withEarlierZeroRow() {
        driftRepo.save(ExecutionDriftLogEntity.builder()
                .sessionId(900L).sessionKind("LIVE").coinPair("KRW-BTC").strategyType("COMPOSITE_BREAKOUT")
                .side("BUY")
                .signalPrice(new BigDecimal("50000000"))
                .fillPrice(new BigDecimal("50000000"))
                .slippagePct(new BigDecimal("0.000000"))
                .executedAt(Instant.now())
                .build());

        tracker.record(2L, "LIVE", "KRW-ETH", "COMPOSITE_MOMENTUM", "SELL",
                new BigDecimal("50000000"), new BigDecimal("49900000"), Instant.now());

        assertThat(driftRepo.findAll()).hasSize(2);   // 오염 행은 그대로 있다 — 행 선택으로 구분한다
        assertThat(recorded(2L, "KRW-ETH").getSlippagePct()).isLessThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("§14 record() — signalPrice 0이면 저장 생략")
    void record_skipsWhenSignalPriceIsZero() {
        tracker.record(3L, "LIVE", "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                BigDecimal.ZERO, new BigDecimal("50000000"), Instant.now());

        assertThat(driftRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("§14 getRecentBySession() — 세션별 최근 기록 조회")
    void getRecentBySession_returnsSessionRecords() {
        Instant now = Instant.now();
        tracker.record(10L, "LIVE", "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                new BigDecimal("50000000"), new BigDecimal("50050000"), now);
        tracker.record(10L, "LIVE", "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                new BigDecimal("50000000"), new BigDecimal("49950000"), now.plusSeconds(1));
        tracker.record(99L, "LIVE", "KRW-ETH", "COMPOSITE_MOMENTUM", "SELL",
                new BigDecimal("3000000"), new BigDecimal("3001000"), now.plusSeconds(2));

        List<ExecutionDriftLogEntity> session10 = tracker.getRecentBySession(10L);
        assertThat(session10).hasSize(2);
        assertThat(session10).allMatch(e -> e.getSessionId().equals(10L));
    }

    @Test
    @DisplayName("§14 getWeeklyAvgSlippage() — 7일 평균 slippage 계산")
    void getWeeklyAvgSlippage_computesCorrectly() {
        // +0.2%, -0.1% → 평균 +0.05%
        tracker.record(1L, "LIVE", "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                new BigDecimal("100000000"), new BigDecimal("100200000"), Instant.now());
        tracker.record(1L, "LIVE", "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                new BigDecimal("100000000"), new BigDecimal("99900000"), Instant.now().plusSeconds(1));

        BigDecimal avg = tracker.getWeeklyAvgSlippage("COMPOSITE_BREAKOUT");
        // 평균 ≈ 0.05%: 양수·범위 내
        assertThat(avg.doubleValue()).isGreaterThan(-1.0).isLessThan(1.0);
    }

    @Test
    @DisplayName("§14 오래된 기록은 7일 평균에서 제외")
    void getWeeklyAvgSlippage_excludesOldRecords() {
        // 8일 전 기록은 제외
        ExecutionDriftLogEntity old = ExecutionDriftLogEntity.builder()
                .sessionId(1L).coinPair("KRW-BTC").strategyType("COMPOSITE_BREAKOUT")
                .side("SELL")
                .signalPrice(new BigDecimal("100000000"))
                .fillPrice(new BigDecimal("110000000"))   // +10% slippage
                .slippagePct(new BigDecimal("10.0"))
                .executedAt(Instant.now().minus(8, ChronoUnit.DAYS))
                .build();
        driftRepo.save(old);

        BigDecimal avg = tracker.getWeeklyAvgSlippage("COMPOSITE_BREAKOUT");
        // 8일 전 기록 제외 → 평균 0
        assertThat(avg.doubleValue()).isEqualTo(0.0);
    }
}
