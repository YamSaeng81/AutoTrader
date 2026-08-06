package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-06 신규(P2 후속) — DYNAMIC에는 이제껏 없던 §9류 SL 미점검 워치독을 신설한 회귀 테스트.
 *
 * <p>LIVE의 {@link LiveTradingSlWatchdogRecoveryTest}와 동일 패턴 — WS 실시간 SL/TP 판정
 * ({@code doOnRealtimePriceEvent})이 조용히 멈춰도 감지하는 안전망이 DYNAMIC엔 전혀 없었다.
 * 2026-08-03 ELSA SL 2.1%p 이탈 사고 당시에도 이 공백이 원인 중 하나로 지목됐다.</p>
 */
class DynamicTradingSlWatchdogRecoveryTest extends IntegrationTestBase {

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @MockBean
    private UpbitRestClient upbitRestClient;

    @MockBean
    private TelegramNotificationService telegramService;

    @BeforeEach
    @AfterEach
    void cleanup() {
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
    }

    private DynamicSessionEntity newMonitoringSession(String coinPair) {
        DynamicSessionEntity s = DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("POSITION_MONITORING")
                .currentCoinPair(coinPair)
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000"))
                .maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build();
        return dynamicSessionRepository.saveAndFlush(s);
    }

    private void openPosition(Long sessionId, String coinPair) {
        positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair(coinPair).side("BUY")
                .entryPrice(new BigDecimal("1500")).avgPrice(new BigDecimal("1500"))
                .size(new BigDecimal("5.33333333")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(sessionId).sessionKind("DYNAMIC")
                .build());
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Instant> lastSlCheckAt() {
        return (Map<Long, Instant>) ReflectionTestUtils.getField(dynamicTradingService, "lastSlCheckAt");
    }

    // ── forceRefreshPrice 단위 동작 ──────────────────────────────────

    @Test
    @DisplayName("forceRefreshPrice: REST 시세 조회 성공 시 true 반환")
    void forceRefreshPrice_succeeds() throws Exception {
        when(upbitRestClient.getTicker("KRW-XRP")).thenReturn(List.of(
                Map.of("market", "KRW-XRP", "trade_price", "1550")));

        boolean result = dynamicTradingService.forceRefreshPrice("KRW-XRP");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("forceRefreshPrice: 거래소 API 오류 시 false 반환")
    void forceRefreshPrice_returnsFalse_onException() throws Exception {
        when(upbitRestClient.getTicker("KRW-XRP")).thenThrow(new RuntimeException("Upbit API timeout"));

        boolean result = dynamicTradingService.forceRefreshPrice("KRW-XRP");

        assertThat(result).isFalse();
    }

    // ── warnStaleSlCheck 통합 ──────────────────────────────────

    @Test
    @DisplayName("warnStaleSlCheck: SL 미점검 감지 시 강제 복구를 시도하고 성공 문구를 보낸다")
    void warnStaleSlCheck_attemptsRecovery_andReportsSuccess() throws Exception {
        DynamicSessionEntity session = newMonitoringSession("KRW-XRP");
        openPosition(session.getId(), "KRW-XRP");
        lastSlCheckAt().put(session.getId(), Instant.now().minus(10, ChronoUnit.MINUTES));

        when(upbitRestClient.getTicker(anyString())).thenReturn(List.of(
                Map.of("market", "KRW-XRP", "trade_price", "1550")));

        dynamicTradingService.warnStaleSlCheck();

        verify(telegramService).sendCustomNotification(
                argThat(msg -> msg.contains("강제 갱신") && msg.contains("동적#" + session.getId())));
    }

    @Test
    @DisplayName("warnStaleSlCheck: 강제 복구 자체가 실패하면 사람 개입이 필요하다는 문구를 보낸다")
    void warnStaleSlCheck_reportsFailure_whenRecoveryFails() throws Exception {
        DynamicSessionEntity session = newMonitoringSession("KRW-XRP");
        openPosition(session.getId(), "KRW-XRP");
        lastSlCheckAt().put(session.getId(), Instant.now().minus(10, ChronoUnit.MINUTES));

        when(upbitRestClient.getTicker(anyString())).thenThrow(new RuntimeException("Upbit API timeout"));

        dynamicTradingService.warnStaleSlCheck();

        verify(telegramService).sendCustomNotification(
                argThat(msg -> msg.contains("자동 복구도 실패")));
    }

    @Test
    @DisplayName("warnStaleSlCheck: 방금 점검된 세션은 건드리지 않는다")
    void warnStaleSlCheck_skipsFreshSession() {
        DynamicSessionEntity session = newMonitoringSession("KRW-XRP");
        openPosition(session.getId(), "KRW-XRP");
        lastSlCheckAt().put(session.getId(), Instant.now());

        dynamicTradingService.warnStaleSlCheck();

        verify(telegramService, never()).sendCustomNotification(any());
    }

    @Test
    @DisplayName("warnStaleSlCheck: SCANNING 상태(보유 없음) 세션은 대상이 아니다")
    void warnStaleSlCheck_skipsScanningSession() {
        DynamicSessionEntity session = DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("10000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("SCANNING")
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000"))
                .maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build();
        dynamicSessionRepository.saveAndFlush(session);

        dynamicTradingService.warnStaleSlCheck();

        verify(telegramService, never()).sendCustomNotification(any());
    }
}
