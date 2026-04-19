package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.LiveTradingStartRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.exception.SessionStateException;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 20260415_analy.md Tier 2 §8 — 세션당 1코인 암묵 가정 / 자본 초과 배정 방지.
 *
 * <p>검증 항목:</p>
 * <ul>
 *   <li>createSession: 활성 세션 initialCapital 합 + 신규 > 계좌 잔고 → 거부</li>
 *   <li>createSession: 잔고 내라면 동일 코인 다중 세션도 허용</li>
 *   <li>sumInitialCapitalByStatusIn / sumAvailableKrwByStatusIn: 집계 쿼리 정확성</li>
 * </ul>
 */
class SessionCapitalGuardTest extends IntegrationTestBase {

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PortfolioManager portfolioManager;

    @BeforeEach
    void setUp() {
        cleanup();
        // 계좌 잔고 100만으로 설정
        portfolioManager.syncTotalCapital(new BigDecimal("1000000"));
    }

    @AfterEach
    void tearDown() {
        cleanup();
        portfolioManager.syncTotalCapital(BigDecimal.ZERO);
    }

    private void cleanup() {
        sessionRepository.deleteAll();
    }

    @Test
    @DisplayName("§8 자본 초과 배정: 활성 세션 합 + 신규 > 계좌 잔고면 SessionStateException")
    void createSession_exceedsAccountCapital_throws() {
        // given: 기존 세션 70만 + 신규 40만 = 110만 > 계좌 100만
        sessionRepository.save(buildSession("RUNNING", new BigDecimal("700000")));

        LiveTradingStartRequest req = new LiveTradingStartRequest();
        req.setStrategyType("COMPOSITE_BREAKOUT");
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("400000"));

        // when/then
        assertThatThrownBy(() -> liveTradingService.createSession(req))
                .isInstanceOf(SessionStateException.class)
                .hasMessageContaining("자본 초과 배정");
    }

    @Test
    @DisplayName("§8 잔고 내: 동일 코인 다중 세션도 합산이 잔고 이내면 허용")
    void createSession_withinCapital_allowsSameCoin() {
        // given: 기존 세션 50만 + 신규 40만 = 90만 < 계좌 100만
        sessionRepository.save(buildSession("RUNNING", new BigDecimal("500000")));

        LiveTradingStartRequest req = new LiveTradingStartRequest();
        req.setStrategyType("COMPOSITE_BREAKOUT");
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("400000"));

        // when
        LiveTradingSessionEntity created = liveTradingService.createSession(req);

        // then
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCoinPair()).isEqualTo("KRW-BTC");
    }

    @Test
    @DisplayName("§8 집계 쿼리: sumInitialCapitalByStatusIn은 RUNNING+CREATED만 합산")
    void sumInitialCapital_onlyActiveStatuses() {
        sessionRepository.save(buildSession("RUNNING", new BigDecimal("300000")));
        sessionRepository.save(buildSession("CREATED", new BigDecimal("200000")));
        sessionRepository.save(buildSession("STOPPED", new BigDecimal("500000")));

        BigDecimal sum = sessionRepository.sumInitialCapitalByStatusIn(
                List.of("RUNNING", "CREATED"));

        assertThat(sum).isEqualByComparingTo(new BigDecimal("500000"));
    }

    @Test
    @DisplayName("§8 집계 쿼리: sumAvailableKrwByStatusIn 정확성")
    void sumAvailableKrw_accuracy() {
        LiveTradingSessionEntity s1 = buildSession("RUNNING", new BigDecimal("300000"));
        s1.setAvailableKrw(new BigDecimal("250000"));
        sessionRepository.save(s1);

        LiveTradingSessionEntity s2 = buildSession("RUNNING", new BigDecimal("200000"));
        s2.setAvailableKrw(new BigDecimal("180000"));
        sessionRepository.save(s2);

        BigDecimal sum = sessionRepository.sumAvailableKrwByStatusIn(
                List.of("RUNNING"));

        assertThat(sum).isEqualByComparingTo(new BigDecimal("430000"));
    }

    private LiveTradingSessionEntity buildSession(String status, BigDecimal capital) {
        return LiveTradingSessionEntity.builder()
                .strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .initialCapital(capital)
                .availableKrw(capital)
                .totalAssetKrw(capital)
                .status(status)
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.0"))
                .startedAt("RUNNING".equals(status) ? Instant.now() : null)
                .build();
    }
}
