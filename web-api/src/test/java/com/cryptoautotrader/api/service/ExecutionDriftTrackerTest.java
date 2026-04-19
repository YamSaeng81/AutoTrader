package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExecutionDriftLogEntity;
import com.cryptoautotrader.api.repository.ExecutionDriftLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
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
 */
class ExecutionDriftTrackerTest extends IntegrationTestBase {

    @Autowired
    private ExecutionDriftTracker tracker;

    @Autowired
    private ExecutionDriftLogRepository driftRepo;

    @AfterEach
    void cleanup() {
        driftRepo.deleteAll();
    }

    @Test
    @DisplayName("§14 record() — slippage 계산 및 저장 정확성")
    void record_calculatesSlippageCorrectly() {
        BigDecimal signalPrice = new BigDecimal("50000000");
        BigDecimal fillPrice   = new BigDecimal("50100000"); // +0.2% slippage

        tracker.record(1L, "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                signalPrice, fillPrice, Instant.now());

        List<ExecutionDriftLogEntity> all = driftRepo.findAll();
        assertThat(all).hasSize(1);

        ExecutionDriftLogEntity saved = all.get(0);
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

        tracker.record(2L, "KRW-ETH", "COMPOSITE_MOMENTUM", "SELL",
                signalPrice, fillPrice, Instant.now());

        BigDecimal slippage = driftRepo.findAll().get(0).getSlippagePct();
        assertThat(slippage).isLessThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("§14 record() — signalPrice 0이면 저장 생략")
    void record_skipsWhenSignalPriceIsZero() {
        tracker.record(3L, "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                BigDecimal.ZERO, new BigDecimal("50000000"), Instant.now());

        assertThat(driftRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("§14 getRecentBySession() — 세션별 최근 기록 조회")
    void getRecentBySession_returnsSessionRecords() {
        Instant now = Instant.now();
        tracker.record(10L, "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                new BigDecimal("50000000"), new BigDecimal("50050000"), now);
        tracker.record(10L, "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                new BigDecimal("50000000"), new BigDecimal("49950000"), now.plusSeconds(1));
        tracker.record(99L, "KRW-ETH", "COMPOSITE_MOMENTUM", "SELL",
                new BigDecimal("3000000"), new BigDecimal("3001000"), now.plusSeconds(2));

        List<ExecutionDriftLogEntity> session10 = tracker.getRecentBySession(10L);
        assertThat(session10).hasSize(2);
        assertThat(session10).allMatch(e -> e.getSessionId().equals(10L));
    }

    @Test
    @DisplayName("§14 getWeeklyAvgSlippage() — 7일 평균 slippage 계산")
    void getWeeklyAvgSlippage_computesCorrectly() {
        // +0.2%, -0.1% → 평균 +0.05%
        tracker.record(1L, "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
                new BigDecimal("100000000"), new BigDecimal("100200000"), Instant.now());
        tracker.record(1L, "KRW-BTC", "COMPOSITE_BREAKOUT", "SELL",
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
